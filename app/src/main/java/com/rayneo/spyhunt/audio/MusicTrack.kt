package com.rayneo.spyhunt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.rayneo.spyhunt.core.TAG
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.game.Phase

/**
 * Streams the soundtrack from `assets/` via [MediaPlayer].
 *
 * Deliberately NOT routed through the game's own mixer. MediaPlayer decodes on
 * the platform's hardware/offload path, which on a 4-core Adreno 621 device
 * keeps a 6 MB MP3 off the CPU entirely — decoding it ourselves would cost real
 * frame budget for no audible gain. The trade is that MP3 loop points are not
 * gapless, so a short seam lands once per playthrough of the track.
 *
 * If the asset is missing the engine silently falls back to its procedural
 * sequencer, so the game still ships music either way.
 */
class MusicTrack(private val ctx: Context) {

    private var mp: MediaPlayer? = null
    private var prepared = false

    /** Base level, before phase gain and ducking. */
    private var baseVolume = 0.62f

    /** Sidechain duck, 0 = no duck, 1 = fully ducked. Decays in [update]. */
    private var duck = 0f
    private var lastNanos = 0L
    private var lastApplied = -1f
    private var enabled = true

    val available: Boolean get() = prepared

    fun start() {
        if (mp != null) return
        try {
            val afd = ctx.assets.openFd(ASSET)
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "music error what=$what extra=$extra")
                prepared = false
                true
            }
            player.prepare()
            player.setVolume(0f, 0f)
            player.start()
            mp = player
            prepared = true
            lastNanos = System.nanoTime()
            Log.i(TAG, "music: playing $ASSET (${player.duration / 1000}s)")
        } catch (e: Exception) {
            // Missing or unplayable asset is not fatal — the procedural
            // sequencer takes over.
            Log.w(TAG, "music: asset unavailable ($ASSET): ${e.message}")
            releaseQuietly()
            prepared = false
        }
    }

    fun stop() {
        releaseQuietly()
        prepared = false
        lastApplied = -1f
    }

    private fun releaseQuietly() {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }

    fun setEnabled(on: Boolean) { enabled = on }

    /** Duck the music under a loud event. Called from the game thread. */
    fun push(amount: Float) { duck = maxOf(duck, clamp(amount, 0f, 1f)) }

    /**
     * Applies phase gain and the decaying duck. Called once per frame from the
     * game thread; MediaPlayer.setVolume is safe off the audio thread.
     */
    fun update(phase: Phase) {
        val player = mp ?: return
        val now = System.nanoTime()
        val dt = clamp((now - lastNanos) / 1_000_000_000f, 0f, 0.25f)
        lastNanos = now

        // ~0.35 s recovery, matching the mixer's own sidechain feel.
        duck *= Math.exp((-dt / 0.35f).toDouble()).toFloat()
        if (duck < 0.002f) duck = 0f

        val phaseGain = when (phase) {
            Phase.PLAYING, Phase.DYING -> 1f
            Phase.COUNTDOWN -> 0.75f
            Phase.ATTRACT -> 0.55f
            // Silence during calibration so the spoken-word-free hint reads clearly,
            // and after death so the game-over sting lands.
            Phase.GAME_OVER -> 0.25f
            Phase.CALIBRATING -> 0f
        }

        val v = if (!enabled) 0f else baseVolume * phaseGain * (1f - 0.55f * duck)
        // MediaPlayer.setVolume crosses a binder; only call it when it changes.
        if (lastApplied < 0f || kotlin.math.abs(v - lastApplied) > 0.01f) {
            try { player.setVolume(v, v) } catch (_: IllegalStateException) {}
            lastApplied = v
        }
    }

    companion object {
        const val ASSET = "music_gunnrunner.mp3"
    }
}
