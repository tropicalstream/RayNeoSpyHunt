package com.rayneo.spyhunt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.rayneo.spyhunt.core.TAG
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.game.GameEvent
import com.rayneo.spyhunt.game.GameState
import com.rayneo.spyhunt.game.Phase
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * ============================================================================
 *  Real-time procedural audio. No assets: every sound is synthesised into an
 *  AudioTrack from scratch, one 256-frame block at a time.
 * ============================================================================
 *
 * THREADING. [update] runs on the game thread and does nothing but publish
 * scalars and post triggers into a lock-free single-producer/single-consumer
 * ring. All DSP happens on the mixer thread, which never locks, never allocates
 * and never touches [GameState]. That separation is the whole design: a lock or
 * a GC pause inside the write loop is an audible click on a 5 ms buffer.
 *
 * MIX. The X3 Pro drives small open-ear speakers with essentially no output
 * below ~150 Hz. Everything is therefore voiced mid-forward, with the weight in
 * the 150-400 Hz band and a master high-pass that throws away sub content we
 * could not reproduce anyway — spending that headroom on the midrange instead.
 */

// ---------------------------------------------------------------------------
//  Patch ids for the shared SFX voice
// ---------------------------------------------------------------------------

private const val P_FIRE = 0
private const val P_EXPLODE = 1
private const val P_CLANG = 2
private const val P_CHIME = 3
private const val P_MISSILE = 4
private const val P_SPLASH = 5
private const val P_BOMB = 6
private const val P_OIL = 7
private const val P_SMOKE = 8
private const val P_CIVILIAN = 9
private const val P_TICK = 10
private const val P_GAMEOVER = 11

/** Circular-plate mode ratios — inharmonic, which is what reads as "metal". */
private val CLANG_RATIO = floatArrayOf(1f, 2.76f, 5.40f, 8.93f, 11.34f, 14.09f)

private val CHIME_PICKUP = intArrayOf(0, 4, 7, 12)
private val CHIME_LIFE = intArrayOf(0, 7, 12, 16, 19, 24)
private val CHIME_LEVEL = intArrayOf(0, 5, 9, 12, 17, 21)

private fun decayCoef(sec: Float): Float = exp(-6.9078f / (max(1e-4f, sec) * SRF))

// ---------------------------------------------------------------------------
//  One polyphonic SFX voice
// ---------------------------------------------------------------------------

/**
 * A single pooled voice that can render any of the one-shot patches. One class
 * rather than twelve because the resources (six oscillators, two SVFs, three
 * envelopes, noise) are shared across patches and preallocating them per voice
 * keeps the pool a fixed, known cost — the patch only chooses which render loop
 * runs, and that choice is made once per block, not per sample.
 */
internal class SfxVoice(seed: Int) {
    @JvmField var active = false
    @JvmField var patch = 0
    @JvmField var priority = 1f
    /** Current envelope level — the voice-stealing heuristic reads this. */
    @JvmField var curLevel = 0f

    private var gl = 0.7f
    private var gr = 0.7f
    private var amp = 1f
    private var t = 0f
    private var ctl = 0

    private val osc = Array(6) { Osc() }
    private val noise = Noise(seed * 7919 + 13)
    private val pink = PinkNoise(seed * 104729 + 7)
    private val svf1 = Svf()
    private val svf2 = Svf()
    private val lp1 = OnePole()
    private val hp1 = OnePole()
    private val env1 = ExpEnv()
    private val env2 = ExpEnv()
    private val env3 = ExpEnv()

    private val pLev = FloatArray(6)
    private val pDec = FloatArray(6)
    private val pFrq = FloatArray(6)
    private val fa = FloatArray(8)
    private val notes = IntArray(8)
    private var noteCount = 0
    private var noteIdx = 0
    private var noteTimer = 0f
    private var grain = 0

    fun trigger(p: Int, pan: Float, gain: Float, prio: Float, p0: Float, p1: Float) {
        patch = p
        priority = prio
        amp = gain
        gl = panL(pan)
        gr = panR(pan)
        t = 0f
        ctl = 0
        grain = 0
        noteIdx = 0
        noteCount = 0
        noteTimer = 0f
        svf1.reset(); svf2.reset(); lp1.reset(); hp1.reset()
        env1.kill(); env2.kill(); env3.kill()
        for (i in 0 until 6) { pLev[i] = 0f; pDec[i] = 0.999f; pFrq[i] = 440f }
        for (i in 0 until 8) fa[i] = 0f
        active = true
        curLevel = 1f

        when (p) {
            P_FIRE -> {
                env1.hit(1f, 0.085f); env2.hit(1f, 0.028f); osc[0].reset(0f)
            }
            P_EXPLODE -> {
                env1.hit(1f, 0.75f); env2.hit(1f, 0.26f); env3.hit(1f, 0.30f)
                osc[0].reset(0f); fa[0] = 0.006f; svf2.set(2400f, 3.5f)
            }
            P_CLANG -> {
                val f0 = 176f * (0.94f + noise.uni() * 0.12f)
                for (k in 0 until 6) {
                    pFrq[k] = f0 * CLANG_RATIO[k] * (1f + (noise.uni() - 0.5f) * 0.012f)
                    osc[k].setFreq(pFrq[k]); osc[k].reset(0f)
                    pLev[k] = 1f / (1f + k.toFloat() * 0.85f)
                    pDec[k] = decayCoef(0.62f / (1f + k.toFloat() * 0.55f))
                }
                env1.hit(1f, 0.045f); svf1.set(3400f, 2.5f)
            }
            P_CHIME -> {
                // Notes arrive via setNotes() straight after the trigger.
                fa[0] = if (p0 > 0f) p0 else 660f
                fa[1] = 0.075f
                fa[2] = 0.42f
            }
            P_MISSILE -> {
                // No decay envelope: the whoosh gets its contour from the sweep.
                osc[0].reset(0f)
            }
            P_SPLASH -> {
                env1.hit(1f, 0.50f); env2.hit(1f, 0.10f); env3.hit(1f, 0.22f)
                osc[3].reset(0f); fa[1] = 0.012f
            }
            P_BOMB -> {
                env1.hit(1f, 0.65f); env2.hit(1f, 0.90f)
                osc[0].reset(0f); osc[1].reset(0.3f); osc[2].setFreq(17f); osc[2].reset(0f)
                lp1.setCutoff(1000f)
            }
            P_OIL -> {
                noteIdx = 0; noteTimer = 0f
            }
            P_SMOKE -> {
                env1.hit(1f, 0.85f); hp1.setCutoff(950f)
            }
            P_CIVILIAN -> {
                env1.hit(1f, 0.60f); env2.hit(1f, 0.35f)
                osc[0].reset(0f); osc[1].reset(0.33f); osc[2].reset(0.66f)
            }
            P_TICK -> {
                val f = if (p0 > 0f) p0 else 880f
                env1.hit(1f, 0.075f); env2.hit(1f, 0.006f)
                osc[0].setFreq(f); osc[0].reset(0f)
                svf1.set(f * 1.6f, 3f)
            }
            P_GAMEOVER -> {
                fa[0] = 330f; env1.hit(1f, 2.0f)
                osc[0].reset(0f); osc[1].reset(0.2f); osc[2].reset(0.6f)
            }
            else -> active = false
        }
        // p1 is currently only used as a per-patch brightness nudge.
        fa[7] = p1
    }

    /** Loads the arpeggio for a [P_CHIME] voice. Call directly after [trigger]. */
    fun setNotes(src: IntArray, count: Int, baseHz: Float, stepSec: Float, decaySec: Float) {
        noteCount = if (count > notes.size) notes.size else count
        for (i in 0 until noteCount) notes[i] = src[i]
        fa[0] = baseHz
        fa[1] = stepSec
        fa[2] = decaySec
        noteIdx = 0
        noteTimer = 0f
    }

    fun kill() { active = false; curLevel = 0f }

    fun render(l: FloatArray, r: FloatArray, n: Int) {
        if (!active) return
        when (patch) {
            P_FIRE -> renderFire(l, r, n)
            P_EXPLODE -> renderExplode(l, r, n)
            P_CLANG -> renderClang(l, r, n)
            P_CHIME -> renderChime(l, r, n)
            P_MISSILE -> renderMissile(l, r, n)
            P_SPLASH -> renderSplash(l, r, n)
            P_BOMB -> renderBomb(l, r, n)
            P_OIL -> renderOil(l, r, n)
            P_SMOKE -> renderSmoke(l, r, n)
            P_CIVILIAN -> renderCivilian(l, r, n)
            P_TICK -> renderTick(l, r, n)
            P_GAMEOVER -> renderGameOver(l, r, n)
            else -> active = false
        }
    }

    // -- machine gun: band-passed noise crack with a fast downward pitch drop --
    private fun renderFire(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val e = env1.next()
            val p = env2.next()
            if ((ctl and 3) == 0) svf1.set(700f + 2700f * p, 7f)
            ctl++
            svf1.process(noise.white())
            osc[0].setFreq(140f + 300f * p)
            // The SVF band-pass has peak gain Q, so every bp tap here is scaled
            // back to keep the bus near unity before the limiter sees it.
            val s = (svf1.bp * 0.50f + osc[0].sine() * p * 0.6f) * e * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = env1.level
        if (env1.done) active = false
    }

    // -- explosion: noise body + descending thump + a crackling debris tail --
    private fun renderExplode(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val e1 = env1.next()
            val e2 = env2.next()
            val e3 = env3.next()
            if ((ctl and 3) == 0) {
                svf1.set(200f + 5600f * e2, 1.1f)
                svf2.set(1500f + 2600f * noise.uni(), 3.5f)
            }
            ctl++
            svf1.process(noise.white() * 0.9f + pink.next() * 0.8f)
            osc[0].setFreq(58f + 210f * e2 * e2)
            val thump = osc[0].sine() * e3
            // Debris crackle: random re-excitations that thin out as the fireball dies.
            if (noise.uni() < fa[0] * e1) pLev[0] = 0.55f + noise.uni() * 0.45f
            pLev[0] *= 0.9950f
            svf2.process(noise.white())
            val s = (svf1.lp * e1 * 0.90f + softClip(thump * 1.6f) * 0.85f +
                svf2.bp * pLev[0] * 0.22f) * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = max(env1.level, pLev[0])
        if (env1.done && pLev[0] < 1e-4f) active = false
    }

    // -- player hit: inharmonic partials, i.e. a metallic clang, not a tone --
    private fun renderClang(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            var body = 0f
            for (k in 0 until 6) {
                if (pLev[k] > 1e-5f) {
                    body += osc[k].sine() * pLev[k]
                    pLev[k] *= pDec[k]
                }
            }
            val tr = env1.next()
            svf1.process(noise.white())
            val s = (body * 0.42f + svf1.bp * tr * 0.30f) * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = pLev[0]
        if (pLev[0] < 1e-4f && env1.done) active = false
    }

    // -- pickups / extra life / level up: bright arpeggiated chimes --
    private fun renderChime(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            noteTimer -= INV_SR
            if (noteTimer <= 0f && noteIdx < noteCount) {
                val g = grain
                grain = (grain + 1) % 3
                val f = fa[0] * Semitone.ratio(notes[noteIdx])
                osc[g * 2].setFreq(f); osc[g * 2].reset(0f)
                osc[g * 2 + 1].setFreq(f * 2.01f); osc[g * 2 + 1].reset(0f)
                pLev[g] = 1f
                pDec[g] = decayCoef(fa[2])
                noteIdx++
                noteTimer += fa[1]
            }
            var s = 0f
            for (g in 0 until 3) {
                if (pLev[g] > 1e-5f) {
                    // Saturating the fundamental adds odd harmonics, which buys a
                    // glassy bell timbre without a third oscillator per grain.
                    s += (softClip(osc[g * 2].sine() * 1.7f) * 0.55f +
                        osc[g * 2 + 1].sine() * 0.35f) * pLev[g]
                    pLev[g] *= pDec[g]
                }
            }
            val out = s * 0.75f * amp
            l[i] += out * gl; r[i] += out * gr
        }
        val loud = max(max(pLev[0], pLev[1]), pLev[2])
        curLevel = loud
        if (noteIdx >= noteCount && loud < 1e-4f) active = false
    }

    // -- missile launch: noise sweeping UP through a band-pass --
    private fun renderMissile(l: FloatArray, r: FloatArray, n: Int) {
        var e = 0f
        for (i in 0 until n) {
            t += INV_SR
            val sw = clamp(t * (1f / 0.5f), 0f, 1f)
            // Half-sine contour rather than a decay: a whoosh has to swell into
            // the sweep, and a plain exponential would be silent by the time the
            // filter reached the top. It also lands on exactly zero at the end.
            e = Waves.sine(sw * 0.5f)
            if ((ctl and 3) == 0) svf1.set(300f + 5000f * sw, 3.4f)
            ctl++
            svf1.process(noise.white() * 0.8f + pink.next() * 0.9f)
            osc[0].setFreq(180f + 1000f * sw)
            val s = (svf1.bp * 0.60f + osc[0].sine() * 0.30f * sw) * e * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = e
        if (t >= 0.5f) active = false
    }

    // -- water impact: lowpassed noise slam, a whoomp, and rising bubble chirps --
    private fun renderSplash(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            t += INV_SR
            val e1 = env1.next()
            val e2 = env2.next()
            val e3 = env3.next()
            if ((ctl and 3) == 0) svf1.set(450f + 5000f * e2, 1.4f)
            ctl++
            svf1.process(noise.white())
            osc[3].setFreq(85f + 220f * e3)
            val whoomp = osc[3].sine() * e3

            fa[1] -= INV_SR
            if (fa[1] <= 0f && t < 0.5f) {
                val g = grain
                grain = (grain + 1) % 3
                pLev[g] = 0.7f + noise.uni() * 0.3f
                pFrq[g] = 320f + noise.uni() * 640f
                fa[1] = 0.03f + noise.uni() * 0.08f
            }
            var bub = 0f
            for (g in 0 until 3) {
                if (pLev[g] > 1e-4f) {
                    osc[g].setFreq(pFrq[g])
                    pFrq[g] *= 1.00016f      // the rising chirp is what says "bubble"
                    bub += osc[g].sine() * pLev[g]
                    pLev[g] *= 0.99915f
                }
            }
            val s = (svf1.lp * e1 * 0.55f + softClip(whoomp * 1.4f) * 0.55f + bub * 0.20f) * amp
            l[i] += s * gl; r[i] += s * gr
        }
        val loud = max(max(pLev[0], pLev[1]), pLev[2])
        curLevel = max(env1.level, loud)
        if (env1.done && loud < 1e-4f && t > 0.6f) active = false
    }

    // -- helicopter bomb: descending whistle over a chopping rotor wash --
    private fun renderBomb(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            t += INV_SR
            val pr = clamp(t / 0.6f, 0f, 1f)
            val f = 2300f - 1850f * pr * pr
            osc[0].setFreq(f)
            osc[1].setFreq(f * 1.006f)
            val atk = if (t < 0.04f) t / 0.04f else 1f
            val we = env1.next() * atk
            val whistle = (osc[0].sine() + osc[1].sine() * 0.6f) * we
            val chop = 0.35f + 0.65f * (0.5f + 0.5f * osc[2].sine())
            val rotor = lp1.lp(noise.white()) * chop * env2.next()
            val s = (whistle * 0.55f + rotor * 0.70f) * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = max(env1.level, env2.level)
        if (env1.done && env2.done) active = false
    }

    // -- oil slick: three descending glugs --
    private fun renderOil(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            noteTimer -= INV_SR
            if (noteTimer <= 0f && noteIdx < 3) {
                env1.hit(1f - noteIdx.toFloat() * 0.24f, 0.13f)
                env2.hit(1f, 0.055f)
                osc[0].reset(0f)
                noteTimer = 0.085f
                noteIdx++
            }
            val a = env1.next()
            val p = env2.next()
            osc[0].setFreq(95f + 360f * p)
            if ((ctl and 3) == 0) svf1.set(280f + 950f * p, 4.5f)
            ctl++
            svf1.process(noise.white() * a * 0.5f)
            val s = (osc[0].sine() * a * 0.85f + svf1.lp * 0.7f) * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = env1.level
        if (noteIdx >= 3 && env1.done) active = false
    }

    // -- smoke screen: pressurised hiss with a closing cutoff and turbulence --
    private fun renderSmoke(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            t += INV_SR
            val atk = if (t < 0.05f) t / 0.05f else 1f
            val e = env1.next() * atk
            if ((ctl and 3) == 0) svf1.set(4200f - 3100f * clamp(t / 0.8f, 0f, 1f), 1.7f)
            ctl++
            val x = hp1.hp(noise.white())
            svf1.process(x)
            val turb = clamp(0.72f + pink.next() * 2.2f, 0.30f, 1.40f)
            val s = (svf1.bp * 0.75f + x * 0.30f) * e * turb * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = env1.level
        if (env1.done) active = false
    }

    // -- civilian killed: a harsh descending minor-second buzz. Deliberately ugly. --
    private fun renderCivilian(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val e = env1.next()
            val p = env2.next()
            val f = 135f + 190f * p
            osc[0].setFreq(f)
            osc[1].setFreq(f * 1.0595f)     // a semitone apart -> sour beating
            osc[2].setFreq(f * 0.5f)
            if ((ctl and 3) == 0) svf1.set(420f + 1500f * p, 2.2f)
            ctl++
            svf1.process(osc[0].saw() * 0.5f + osc[1].saw() * 0.45f + osc[2].square(0.5f) * 0.3f)
            val s = softClip(svf1.lp * 1.6f) * e * 0.55f * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = env1.level
        if (env1.done) active = false
    }

    // -- countdown blip --
    private fun renderTick(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val e = env1.next()
            val c = env2.next()
            svf1.process(osc[0].square(0.5f) * 0.6f + noise.white() * c * 0.9f)
            val s = (svf1.bp * 0.85f + svf1.lp * 0.4f) * e * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = env1.level
        if (env1.done) active = false
    }

    // -- game over: a minor triad collapsing in pitch as the filter shuts --
    private fun renderGameOver(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            t += INV_SR
            fa[0] *= 0.999985f
            val e = env1.next() * (if (t < 0.03f) t / 0.03f else 1f)
            osc[0].setFreq(fa[0])
            osc[1].setFreq(fa[0] * 1.1892f)     // minor third
            osc[2].setFreq(fa[0] * 1.4983f)     // fifth
            if ((ctl and 7) == 0) svf1.set(300f + 3600f * clamp(1f - t / 1.6f, 0f, 1f), 2.4f)
            ctl++
            svf1.process(
                osc[0].saw() * 0.5f + osc[1].saw() * 0.4f + osc[2].saw() * 0.35f + pink.next() * 0.35f
            )
            val s = softClip(svf1.lp * 1.5f) * e * 0.75f * amp
            l[i] += s * gl; r[i] += s * gr
        }
        curLevel = env1.level
        if (env1.done) active = false
    }
}

// ---------------------------------------------------------------------------
//  The engine bed — continuous, never stolen, never allowed to click
// ---------------------------------------------------------------------------

/**
 * Detuned saw stack plus filtered noise whose pitch, cutoff and turbo whine all
 * track road speed. Every control goes through a [Smooth]: the oscillators only
 * ever have their *increment* changed, never their phase, so a speed change is
 * a glide rather than a discontinuity.
 */
internal class EngineVoice {
    private val o1 = Osc()
    private val o2 = Osc()
    private val o3 = Osc()
    private val o4 = Osc()
    private val whine1 = Osc()
    private val whine2 = Osc()
    private val lfo = Osc()
    private val pinkA = PinkNoise(0x2277CC)
    private val pinkB = PinkNoise(0x51A3B7)
    private val roarA = Svf()
    private val roarB = Svf()
    private val body = Svf()
    private val hp = OnePole()

    private val fSm = Smooth(46f, 0.045f)
    private val cutSm = Smooth(500f, 0.06f)
    private val gainSm = Smooth(0f, 0.15f)
    private val waterSm = Smooth(0f, 0.40f)
    private val whineSm = Smooth(0f, 0.20f)

    private var spd = 0f
    private var swooshEnv = 0f
    private var swooshDir = 1f
    private var ctl = 0

    init { hp.setCutoff(60f) }

    fun setParams(speedNorm: Float, gain: Float, water: Float) {
        spd = clamp(speedNorm, 0f, 1f)
        // 46 Hz idle to 164 Hz flat out: the fundamental itself is inaudible on
        // these drivers, but its 3rd-8th harmonics land squarely in the band
        // they do reproduce, which is where the growl actually lives.
        fSm.target = 46f + 118f * spd
        cutSm.target = 420f + 3200f * spd
        gainSm.target = gain
        waterSm.target = water
        whineSm.target = 0.05f * spd * spd
    }

    /** Filter sweep for the road/water transform. [up] opens, otherwise closes. */
    fun swoosh(up: Boolean) { swooshEnv = 1f; swooshDir = if (up) 1f else -1f }

    fun render(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val f = fSm.next()
            val g = gainSm.next()
            val w = waterSm.next()
            val cutBase = cutSm.next()
            val wh = whineSm.next()
            swooshEnv *= 0.99988f
            if (g < 1e-4f) continue

            val cut = cutBase * (1f - 0.5f * w) * max(0.12f, 1f + swooshDir * 0.85f * swooshEnv)

            // Idle lopes; at speed it smooths out. On water it burbles again.
            val depth = 0.20f * (1f - spd) + 0.16f * w
            lfo.setFreq(7f + 16f * spd)
            val a = g * (1f - depth + depth * (0.5f + 0.5f * lfo.sine()))

            o1.setFreq(f); o2.setFreq(f * 1.0075f); o3.setFreq(f * 0.9932f); o4.setFreq(f * 2f)
            var core = (o1.saw() + o2.saw() + o3.saw()) * 0.27f + o4.saw() * 0.32f
            if ((ctl and 3) == 0) {
                body.set(cut, 1.7f)
                roarA.set(260f + 2300f * spd, 1.1f)
                roarB.set(300f + 2100f * spd, 1.0f)
            }
            ctl++
            body.process(core)
            // The saturator is what makes the bed sit at a constant loudness
            // regardless of revs, so its output is trimmed here rather than by
            // riding the gain — the bed is a floor, not the loudest thing.
            core = softClip((body.lp * 1.25f + body.bp * 0.35f) * (1.1f + 0.8f * spd)) * 0.62f

            val whf = 1700f + 3500f * spd
            whine1.setFreq(whf)
            whine2.setFreq(whf * 1.5f)
            val whineSig = (whine1.sine() * 0.7f + whine2.sine() * 0.3f) * wh * (1f - 0.85f * w)

            roarA.process(pinkA.next())
            roarB.process(pinkB.next())
            val roarGain = (0.16f + 0.42f * spd) * (1f + 0.6f * w) * a

            // Two decorrelated noise sources give the bed real stereo width for
            // the price of one extra filter — no reverb, which we cannot afford.
            val mono = hp.hp((core + whineSig) * a)
            l[i] += mono + roarA.bp * roarGain
            r[i] += mono + roarB.bp * roarGain
        }
    }
}

// ---------------------------------------------------------------------------
//  Tyre screech — a sustained voice, refreshed while the event keeps firing
// ---------------------------------------------------------------------------

internal class ScreechVoice {
    private val noise = Noise(0x7F2B19)
    private val f1 = Svf()
    private val f2 = Svf()
    private val f3 = Svf()
    private val wob1 = Osc()
    private val wob2 = Osc()
    private val lvl = Smooth(0f, 0.06f)
    private val panSm = Smooth(0f, 0.09f)
    private var ctl = 0

    init { wob1.setFreq(6.7f); wob2.setFreq(11.3f); f3.set(340f, 1.0f) }

    fun excite(pan: Float, gain: Float) {
        lvl.target = clamp(gain, 0f, 1f)
        panSm.target = clamp(pan, -1f, 1f)
    }

    /** Called once per block: the level bleeds away unless events keep arriving. */
    fun decay() { lvl.target *= 0.84f }

    fun render(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val a = lvl.next()
            val p = panSm.next()
            // The wobble LFOs run even while silent so the squeal never restarts
            // from a stale phase.
            val w = wob1.sine() * 0.62f + wob2.sine() * 0.38f
            if (a < 1e-4f) continue
            if ((ctl and 3) == 0) {
                f1.set(1450f + 560f * w, 12f)
                f2.set(2950f + 880f * w, 8f)
            }
            ctl++
            val x = noise.white()
            f1.process(x); f2.process(x); f3.process(x)
            val s = softClip((f1.bp * 0.9f + f2.bp * 0.55f + f3.bp * 0.35f) * 1.4f) * a * 0.42f
            l[i] += s * panL(p)
            r[i] += s * panR(p)
        }
    }
}

// ---------------------------------------------------------------------------
//  The engine proper
// ---------------------------------------------------------------------------

/**
 * @param ctx read only for the platform's audio HAL burst size, so the
 *            AudioTrack buffer can be an exact multiple of it. A buffer that is
 *            not burst-aligned makes the fast-mixer path quietly refuse the
 *            track, costing ~20 ms of latency that is invisible until measured.
 */
class AudioEngine(private val ctx: Context? = null) {

    // ---- buffers (all preallocated; the mixer never allocates) ----
    private val outBuf = FloatArray(BLOCK * 2)
    private val bedL = FloatArray(BLOCK)
    private val bedR = FloatArray(BLOCK)
    private val sfxL = FloatArray(BLOCK)
    private val sfxR = FloatArray(BLOCK)
    private val musL = FloatArray(BLOCK)
    private val musR = FloatArray(BLOCK)

    // ---- voices ----
    private val voices = Array(12) { SfxVoice(it * 2179 + 17) }
    private val bed = EngineVoice()
    private val screech = ScreechVoice()
    private val music = Music()

    // ---- master chain ----
    private val limiter = Limiter(0.92f)
    private val hpL = OnePole()
    private val hpR = OnePole()
    private var duckEnv = 0f
    private val duckAtk = exp(-1f / (0.006f * SRF))
    private val duckRel = exp(-1f / (0.25f * SRF))

    // ---- cross-thread state ----
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile private var pSpeed = 0f
    @Volatile private var pLevel = 1
    @Volatile private var pPhase: Phase = Phase.ATTRACT
    @Volatile private var pWater = false
    @Volatile private var pMusicOn = true

    /**
     * SPSC trigger ring. The game thread only ever writes [ringHead] (after the
     * payload), the mixer thread only ever writes [ringTail]; the volatile write
     * of the head is what publishes the payload. No locks, no allocation, and a
     * full ring drops events rather than stalling the game thread.
     */
    private val ringEv = arrayOfNulls<GameEvent>(RING)
    private val ringPan = FloatArray(RING)
    private val ringGain = FloatArray(RING)
    @Volatile private var ringHead = 0
    @Volatile private var ringTail = 0

    private var lastPhase: Phase = Phase.ATTRACT

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    fun start() {
        if (running) return
        val minBytes = AudioTrack.getMinBufferSize(
            SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        // Round the request up to a whole number of HAL bursts (see [ctx]).
        val burst = halBurstFrames()
        val frames = (BLOCK * BUFFERED_BLOCKS + burst - 1) / burst * burst
        val want = frames * 2 * 4
        val bytes = if (minBytes > 0) max(minBytes, want) else want

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            // A device with no usable output must not take the game down with it.
            Log.e(TAG, "AudioTrack unavailable, running silent: ${e.message}")
            return
        }

        hpL.setCutoff(95f)
        hpR.setCutoff(95f)
        hpL.reset()
        hpR.reset()
        limiter.reset()
        music.reset()

        // onPause() stops the mixer mid-block, so any voice sounding at that
        // instant is still flagged active. Without this, resuming replays the
        // tail of whatever was exploding when the glasses came off.
        for (v in voices) v.kill()
        screech.excite(0f, 0f)
        duckEnv = 0f

        running = true
        val th = Thread({ audioLoop(t) }, "SpyHuntAudio")
        th.priority = Thread.MAX_PRIORITY
        thread = th
        th.start()
    }

    /**
     * The audio HAL's native burst, in frames. Falls back to [BLOCK] when there
     * is no Context or the property is absent/nonsense — a wrong value here is a
     * silent latency regression, so implausible readings are rejected.
     */
    private fun halBurstFrames(): Int {
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return BLOCK
        val v = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        return if (v in 16..2048) v else BLOCK
    }

    fun stop() {
        if (!running) return
        running = false
        val th = thread
        thread = null
        if (th != null) {
            try {
                th.join(600L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Game-thread side. Publishes the continuous parameters and posts one
     * trigger per queued event.
     *
     * Does NOT clear [GameState.events] — particles read the same queue, so the
     * frame loop owns clearing it.
     */
    fun update(state: GameState) {
        pSpeed = state.speed
        pLevel = state.level
        pPhase = state.phase
        pWater = state.onWater

        val px = state.player.x
        val pz = state.player.z
        val q = state.events
        var i = 0
        while (i < q.count) {
            val dx = q.x(i) - px
            val dz = q.z(i) - pz
            // Pan by lateral offset: half a road width is already most of the
            // way to one side, which is what makes events feel placed.
            val pan = clamp(dx * 0.14f, -1f, 1f)
            // Distance rolloff, biased forward — the road ahead has to stay
            // legible, what is behind you no longer matters.
            val d = if (dz >= 0f) dz else -dz * 1.6f
            val gain = clamp(1f / (1f + d * 0.012f), 0.22f, 1f)
            push(q.event(i), pan, gain)
            i++
        }
    }

    fun setMusicEnabled(on: Boolean) { pMusicOn = on }

    // -----------------------------------------------------------------------
    //  Producer side
    // -----------------------------------------------------------------------

    private fun push(e: GameEvent, pan: Float, gain: Float) {
        val h = ringHead
        val next = (h + 1) and RING_MASK
        if (next == ringTail) return       // full: drop it, never stall the game
        ringEv[h] = e
        ringPan[h] = pan
        ringGain[h] = gain
        ringHead = next                    // publishes the payload above
    }

    // -----------------------------------------------------------------------
    //  Mixer thread
    // -----------------------------------------------------------------------

    private fun audioLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // play() FIRST, then prime with silence. A blocking write to a stopped
        // track only returns once the data fits, and PERFORMANCE_MODE_LOW_LATENCY
        // is free to hand back a buffer smaller than we asked for — priming
        // before play() can therefore block forever, stranding this thread at
        // MAX_PRIORITY where stop()'s join() cannot reclaim it.
        t.play()
        outBuf.fill(0f)
        t.write(outBuf, 0, outBuf.size, AudioTrack.WRITE_BLOCKING)
        while (running) {
            renderBlock()
            val w = t.write(outBuf, 0, outBuf.size, AudioTrack.WRITE_BLOCKING)
            if (w < 0) {
                Log.e(TAG, "AudioTrack write failed: $w")
                break
            }
        }
        try {
            t.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack already stopped")
        }
        t.release()
    }

    private fun renderBlock() {
        val n = BLOCK
        // Bleed the screech down first, so a TYRE_SCREECH arriving in this block
        // re-excites it at full level rather than being immediately decayed.
        screech.decay()
        drainTriggers()

        val phase = pPhase
        if (phase != lastPhase) {
            // Restart the loop from bar one whenever a run begins, so the track
            // and the countdown line up.
            if (phase == Phase.COUNTDOWN) music.reset()
            lastPhase = phase
        }

        // 18 m/s crawl to ~123 m/s flat out maps onto the full timbral range.
        val spd = clamp((pSpeed - 18f) * (1f / 105f), 0f, 1f)

        val bedGain = when (phase) {
            Phase.PLAYING, Phase.DYING -> 0.85f
            Phase.COUNTDOWN -> 0.55f
            Phase.ATTRACT -> 0.35f
            Phase.GAME_OVER, Phase.CALIBRATING -> 0f
        }
        bed.setParams(spd, bedGain, if (pWater) 1f else 0f)

        val musicGain = when (phase) {
            Phase.PLAYING, Phase.DYING, Phase.COUNTDOWN, Phase.ATTRACT ->
                if (pMusicOn) 1f else 0f
            Phase.GAME_OVER, Phase.CALIBRATING -> 0f
        }
        val full = phase == Phase.PLAYING || phase == Phase.DYING
        music.setIntensity(spd, pLevel, full, true, musicGain)

        bedL.fill(0f); bedR.fill(0f)
        sfxL.fill(0f); sfxR.fill(0f)
        musL.fill(0f); musR.fill(0f)

        bed.render(bedL, bedR, n)
        screech.render(sfxL, sfxR, n)
        for (v in voices) if (v.active) v.render(sfxL, sfxR, n)
        music.render(musL, musR, n)

        // Mix down. The engine bed deliberately sits outside the duck sidechain:
        // it is always loud, so feeding it in would hold the music down forever.
        var j = 0
        for (i in 0 until n) {
            val sl = sfxL[i] * SFX_GAIN
            val sr = sfxR[i] * SFX_GAIN
            val mag = max(abs(sl), abs(sr))
            duckEnv = if (mag > duckEnv) mag + (duckEnv - mag) * duckAtk
            else mag + (duckEnv - mag) * duckRel
            val duck = 1f / (1f + 2.6f * max(0f, duckEnv - 0.10f))

            sfxL[i] = hpL.hp(bedL[i] * BED_GAIN + sl + musL[i] * duck)
            sfxR[i] = hpR.hp(bedR[i] * BED_GAIN + sr + musR[i] * duck)
        }
        limiter.process(sfxL, sfxR, n)
        for (i in 0 until n) {
            outBuf[j] = sfxL[i]; j++
            outBuf[j] = sfxR[i]; j++
        }
    }

    private fun drainTriggers() {
        var tail = ringTail
        val head = ringHead
        while (tail != head) {
            val e = ringEv[tail]
            if (e != null) trigger(e, ringPan[tail], ringGain[tail])
            tail = (tail + 1) and RING_MASK
        }
        ringTail = tail
    }

    /**
     * Maps a game event onto a patch. Events the sim raises without a meaningful
     * world position (level up, game over, ticks, anything player-local) are
     * forced to centre at full gain — relying on a default 0,0 position would
     * pan and attenuate them by the player's own odometer.
     */
    private fun trigger(e: GameEvent, pan: Float, gain: Float) {
        when (e) {
            GameEvent.PLAYER_FIRE ->
                fire(P_FIRE, 0.30f, 0f, 0.38f)

            GameEvent.MISSILE_FIRE ->
                fire(P_MISSILE, 0.75f, 0f, 0.55f)

            GameEvent.PLAYER_HIT ->
                fire(P_CLANG, 0.95f, 0f, 0.70f)

            GameEvent.ENEMY_EXPLODE ->
                fire(P_EXPLODE, 0.85f, pan, 0.62f * gain)

            GameEvent.CIVILIAN_KILLED ->
                fire(P_CIVILIAN, 0.90f, pan * 0.6f, 0.55f * gain)

            GameEvent.VEHICLE_SPLASH ->
                fire(P_SPLASH, 0.70f, pan, 0.55f * gain)

            GameEvent.ENTER_WATER -> {
                fire(P_SPLASH, 0.95f, 0f, 0.85f)
                bed.swoosh(false)
            }

            GameEvent.ENTER_ROAD -> {
                fire(P_SPLASH, 0.90f, 0f, 0.55f)
                bed.swoosh(true)
            }

            GameEvent.HELI_BOMB ->
                fire(P_BOMB, 0.80f, pan, 0.55f * gain)

            GameEvent.OIL_DROP ->
                fire(P_OIL, 0.55f, 0f, 0.50f)

            GameEvent.SMOKE_DROP ->
                fire(P_SMOKE, 0.55f, 0f, 0.45f)

            // Bounded pan so a screech is placed but never yanked hard to one ear.
            GameEvent.TYRE_SCREECH -> screech.excite(pan * 0.35f, 0.9f)

            GameEvent.WEAPON_PICKUP -> chime(CHIME_PICKUP, 660f, 0.070f, 0.40f, 0f, 0.45f)
            GameEvent.EXTRA_LIFE -> chime(CHIME_LIFE, 587f, 0.085f, 0.55f, 0f, 0.50f)
            GameEvent.LEVEL_UP -> chime(CHIME_LEVEL, 523f, 0.080f, 0.50f, 0f, 0.50f)

            GameEvent.COUNTDOWN_TICK ->
                fire(P_TICK, 0.85f, 0f, 0.45f, 880f)

            GameEvent.GAME_OVER -> {
                // Clear the deck: nothing should survive over the death sting.
                for (v in voices) v.kill()
                screech.excite(0f, 0f)
                fire(P_GAMEOVER, 2.0f, 0f, 0.70f)
            }
        }
    }

    private fun fire(
        patch: Int, prio: Float, pan: Float, gain: Float, p0: Float = 0f, p1: Float = 0f
    ): SfxVoice? {
        val v = alloc(prio) ?: return null
        v.trigger(patch, pan, gain, prio, p0, p1)
        return v
    }

    private fun chime(
        notes: IntArray, baseHz: Float, stepSec: Float, decaySec: Float, pan: Float, gain: Float
    ) {
        val v = fire(P_CHIME, 0.90f, pan, gain, baseHz) ?: return
        v.setNotes(notes, notes.size, baseHz, stepSec, decaySec)
    }

    /**
     * Polite voice stealing: take a free voice if there is one, otherwise take
     * the quietest/least important sounding voice — and only if the incoming
     * sound actually outranks it. The engine bed and the screech live outside
     * this pool, so they can never be stolen.
     */
    private fun alloc(prio: Float): SfxVoice? {
        var worst: SfxVoice? = null
        var worstScore = Float.MAX_VALUE
        for (v in voices) {
            if (!v.active) return v
            val score = v.priority * (0.15f + v.curLevel)
            if (score < worstScore) { worstScore = score; worst = v }
        }
        return if (worst != null && worstScore < prio * 0.9f) worst else null
    }

    private companion object {
        /** ~5.3 ms per block at 48 kHz; four buffered keeps latency near 21 ms. */
        const val BLOCK = 256
        const val BUFFERED_BLOCKS = 4
        const val RING = 128
        const val RING_MASK = RING - 1
        const val BED_GAIN = 0.55f
        const val SFX_GAIN = 0.90f
    }
}
