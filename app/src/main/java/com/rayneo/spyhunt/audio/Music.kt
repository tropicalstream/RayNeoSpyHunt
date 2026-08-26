package com.rayneo.spyhunt.audio

import com.rayneo.spyhunt.core.clamp

/**
 * ============================================================================
 *  "NIGHT CIRCUIT" — the chase track. Original composition, sequenced live.
 * ============================================================================
 *
 * PROVENANCE / COPYRIGHT NOTE. The 1983 arcade cabinet licensed Henry Mancini's
 * "Peter Gunn" theme. Nothing from that piece appears here: no melodic fragment,
 * no bassline quotation, not the harmonic shape. That theme is built on a static
 * one-chord chromatic ostinato; this is deliberately the opposite — an eight-bar
 * *moving* progression in A natural minor with a borrowed major V, over a
 * sixteenth-note octave-jumping bass figure written for this game. Any
 * resemblance is limited to the genre itself (driving minor-key electronic),
 * which is not protectable.
 *
 * Everything is generated per sample; there is no audio data in the APK.
 *
 * MIX NOTE. The X3 Pro's open-ear drivers roll off hard below ~150 Hz, so the
 * arrangement is voiced up: the bass fundamental sits at 82-147 Hz purely so its
 * 2nd-4th harmonics land in the 150-400 Hz band where the drivers actually have
 * output, and the kick sweeps 335 -> 95 Hz instead of down into a sub thump
 * nobody would hear.
 */

private const val STEPS_PER_BAR = 16
private const val BARS = 8
private const val TOTAL_STEPS = STEPS_PER_BAR * BARS

/**
 * i - VI - III - VII - i - VI - iv - V in A minor, one chord per bar.
 * MIDI note numbers: A2 F2 C3 G2 A2 F2 D3 E2.
 */
private val PROG_ROOT = intArrayOf(45, 41, 48, 43, 45, 41, 50, 40)
private val PROG_MINOR = booleanArrayOf(true, false, false, false, true, false, true, false)

/** Sixteenth-note bass ostinato; -1 is a rest, values are semitones over the root. */
private val BASS_A = intArrayOf(0, -1, 0, 12, 0, -1, 0, 7, 0, -1, 12, -1, 0, 7, 12, 10)
private val BASS_B = intArrayOf(0, -1, 0, 12, 7, -1, 0, -1, 12, -1, 10, 7, 0, -1, 12, 7)

/** Indices into the current chord's tone table — an up/down arp with a twist. */
private val ARP_PAT = intArrayOf(0, 2, 3, 4, 3, 2, 5, 3, 0, 2, 3, 4, 5, 4, 3, 1)

/** Four on the floor, with a push into beat 4. */
private val KICK_PAT = intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0)
private val SNARE_PAT = intArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1)

// ---------------------------------------------------------------------------
//  Instruments
// ---------------------------------------------------------------------------

/** Monophonic filtered-saw bass — the engine of the track. */
private class BassVoice {
    private val o1 = Osc()
    private val o2 = Osc()
    private val filt = Svf()
    private val amp = ExpEnv()
    private val fenv = ExpEnv()
    private val hp = OnePole()
    private var freq = 110f
    private var ctl = 0

    @JvmField var bright = 0.5f

    init { hp.setCutoff(75f) }

    fun note(hz: Float, lenSec: Float) {
        freq = hz
        // Phase reset gives every note the same punchy attack transient instead
        // of a random-polarity click.
        o1.reset(0f); o2.reset(0.25f)
        amp.hit(1f, lenSec)
        fenv.hit(1f, lenSec * 0.45f)
    }

    fun render(): Float {
        val a = amp.next()
        if (a < 1e-4f) return 0f
        val fe = fenv.next()
        o1.setFreq(freq)
        o2.setFreq(freq * 1.004f)
        val raw = o1.saw() * 0.8f + o2.square(0.42f) * 0.35f
        if ((ctl and 3) == 0) filt.set(200f + (820f + 1500f * bright) * fe + 160f * bright, 2.6f)
        ctl++
        filt.process(raw)
        return hp.hp(softClip(filt.lp * 1.4f)) * a * 0.5f
    }

    fun kill() { amp.kill(); fenv.kill() }
}

/** Three-voice round-robin pluck arpeggio so notes can ring into each other. */
private class ArpVoice {
    private val osc = Array(3) { Osc() }
    private val env = Array(3) { ExpEnv() }
    private val filt = Array(3) { Svf() }
    private val freq = FloatArray(3)
    private var rr = 0
    private var ctl = 0

    @JvmField var bright = 0.5f

    fun note(hz: Float, decaySec: Float) {
        val i = rr
        rr = (rr + 1) % 3
        freq[i] = hz
        osc[i].reset(0f)
        osc[i].setFreq(hz)
        filt[i].reset()
        env[i].hit(1f, decaySec)
    }

    fun render(): Float {
        val doCtl = (ctl and 3) == 0
        ctl++
        var s = 0f
        for (i in 0 until 3) {
            val e = env[i].next()
            if (e < 1e-4f) continue
            if (doCtl) filt[i].set(700f + 2400f * bright + freq[i] * 2.2f, 3.0f)
            osc[i].setFreq(freq[i])
            filt[i].process(osc[i].square(0.30f))
            s += filt[i].lp * e
        }
        return s * 0.28f
    }

    fun kill() { for (i in 0 until 3) env[i].kill() }
}

/** Three-note detuned saw pad, split L/R by detune group for width. */
private class PadVoice {
    private val osc = Array(6) { Osc() }
    private val hzs = FloatArray(3)
    private val env = Adsr()
    private val fL = Svf()
    private val fR = Svf()
    private val lfo = Osc()
    private var ctl = 0

    @JvmField var bright = 0.5f
    @JvmField var outL = 0f
    @JvmField var outR = 0f

    init {
        env.set(0.40f, 0.55f, 0.62f, 0.85f)
        lfo.setFreq(0.07f)
    }

    fun chord(a: Float, b: Float, c: Float) {
        hzs[0] = a; hzs[1] = b; hzs[2] = c
        env.gateOn()
    }

    fun release() = env.gateOff()

    fun render() {
        val e = env.next()
        // The LFO has to keep running even when the pad is silent, or the filter
        // jumps to a stale phase the next time the chord comes in.
        val m = lfo.sine()
        if (e < 1e-4f) { outL = 0f; outR = 0f; return }
        var a = 0f
        var b = 0f
        for (i in 0 until 3) {
            osc[i].setFreq(hzs[i] * 0.9970f)
            osc[i + 3].setFreq(hzs[i] * 1.0042f)
            a += osc[i].saw()
            b += osc[i + 3].saw()
        }
        if ((ctl and 7) == 0) {
            val c = 430f + 950f * bright + 360f * m
            fL.set(c, 1.1f)
            fR.set(c * 1.07f, 1.1f)
        }
        ctl++
        fL.process(a * 0.33f)
        fR.process(b * 0.33f)
        outL = fL.lp * e * 0.42f
        outR = fR.lp * e * 0.42f
    }

    fun kill() { env.kill(); outL = 0f; outR = 0f }
}

private class KickVoice {
    private val o = Osc()
    private val amp = ExpEnv()
    private val pit = ExpEnv()
    private val click = ExpEnv()
    private val cf = Svf()
    private val noise = Noise(0x4B1C7D)

    fun hit(v: Float) {
        amp.hit(v, 0.22f)
        pit.hit(1f, 0.045f)
        click.hit(v, 0.014f)
        o.reset(0f)
        cf.set(1600f, 2.2f)
    }

    fun render(): Float {
        val a = amp.next()
        val c = click.next()
        if (a < 1e-4f && c < 1e-4f) return 0f
        val p = pit.next()
        // 335 -> 95 Hz. High for a kick on purpose: below ~150 Hz these drivers
        // move air but produce nothing you can hear, so the punch lives in the
        // sweep and the click, not in a sub fundamental.
        o.setFreq(95f + 240f * p)
        cf.process(noise.white())
        return softClip(o.sine() * a * 1.3f) * 0.55f + cf.bp * c * 0.5f
    }

    fun kill() { amp.kill(); click.kill() }
}

private class SnareVoice {
    private val n = Noise(0x99AA33)
    private val bp = Svf()
    private val o1 = Osc()
    private val o2 = Osc()
    private val amp = ExpEnv()
    private val tone = ExpEnv()

    fun hit(v: Float) {
        amp.hit(v, 0.17f)
        tone.hit(v * 0.6f, 0.09f)
        bp.set(1850f, 1.1f)
        o1.setFreq(205f); o1.reset(0f)
        o2.setFreq(322f); o2.reset(0f)
    }

    fun render(): Float {
        val a = amp.next()
        if (a < 1e-4f) return 0f
        val t = tone.next()
        bp.process(n.white())
        return bp.bp * a * 0.55f + (o1.sine() + o2.sine() * 0.7f) * t * 0.22f
    }

    fun kill() { amp.kill(); tone.kill() }
}

private class HatVoice {
    private val n = Noise(0x1357BD)
    private val hp = OnePole()
    private val bp = Svf()
    private val amp = ExpEnv()

    init { hp.setCutoff(6500f); bp.set(9000f, 0.8f) }

    fun hit(v: Float, open: Boolean) { amp.hit(v, if (open) 0.19f else 0.035f) }

    fun render(): Float {
        val a = amp.next()
        if (a < 1e-4f) return 0f
        val x = hp.hp(n.white())
        bp.process(x)
        return (x * 0.6f + bp.bp * 0.5f) * a * 0.22f
    }

    fun kill() { amp.kill() }
}

// ---------------------------------------------------------------------------
//  Sequencer
// ---------------------------------------------------------------------------

internal class Music {
    private val bass = BassVoice()
    private val arp = ArpVoice()
    private val pad = PadVoice()
    private val kick = KickVoice()
    private val snare = SnareVoice()
    private val hat = HatVoice()

    // Slap-back echo on the arp. Three and four sixteenths: not the same value,
    // so the two channels never collapse to mono.
    private val delayL = DelayLine(32768)
    private val delayR = DelayLine(32768)
    private var dTimeL = 6000
    private var dTimeR = 8000

    private val gainSm = Smooth(0f, 0.35f)

    private var bpm = 124f
    private var bpmTarget = 124f
    private var samplesPerStep = 5806
    private var stepCounter = 0
    private var step = 0

    private val tones = IntArray(6)
    private var rootMidi = 45
    private var drumsOn = true
    private var arpOn = true
    private var lvl = 1
    private var speedN = 0f

    init { setChord(0) }

    /**
     * Called once per audio block from the mixer thread.
     * @param gain 0 fades the track out, 1 fades it in (never a hard cut).
     */
    fun setIntensity(speedNorm: Float, level: Int, drums: Boolean, arpeggio: Boolean, gain: Float) {
        speedN = clamp(speedNorm, 0f, 1f)
        lvl = if (level < 1) 1 else level
        drumsOn = drums
        arpOn = arpeggio
        gainSm.target = gain
        bpmTarget = clamp(118f + 26f * speedN + (lvl - 1).toFloat() * 1.5f, 112f, 172f)
        val br = clamp(0.25f + 0.70f * speedN + (lvl - 1).toFloat() * 0.04f, 0f, 1f)
        bass.bright = br
        arp.bright = br
        pad.bright = br
    }

    fun reset() {
        step = 0
        stepCounter = 0
        bpm = 124f
        delayL.clear()
        delayR.clear()
        bass.kill(); arp.kill(); pad.kill()
        kick.kill(); snare.kill(); hat.kill()
        gainSm.snap(0f)
        setChord(0)
    }

    /** Accumulates the music bus into [l]/[r]; the caller owns the ducking. */
    fun render(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            if (stepCounter <= 0) {
                onStep()
                stepCounter = samplesPerStep
            }
            stepCounter--

            val b = bass.render()
            val a = arp.render()
            pad.render()
            val dl = delayL.tick(a * 0.5f, dTimeL, 0.34f)
            val dr = delayR.tick(a * 0.5f, dTimeR, 0.30f)
            val d = kick.render() + snare.render() + hat.render()

            val g = gainSm.next() * MASTER
            l[i] += (b + pad.outL + a * 0.7f + dl + d) * g
            r[i] += (b + pad.outR + a * 0.7f + dr + d) * g
        }
    }

    private fun setChord(bar: Int) {
        rootMidi = PROG_ROOT[bar]
        val third = if (PROG_MINOR[bar]) 3 else 4
        tones[0] = 0
        tones[1] = third
        tones[2] = 7
        tones[3] = 12
        tones[4] = 12 + third
        tones[5] = 19
    }

    private fun onStep() {
        val bar = step ushr 4
        val s16 = step and 15

        if (s16 == 0) {
            setChord(bar)
            pad.chord(
                Semitone.hz(rootMidi + 12 + tones[0]),
                Semitone.hz(rootMidi + 12 + tones[1]),
                Semitone.hz(rootMidi + 12 + tones[2])
            )
        }
        if (s16 == 13) pad.release()

        val pat = if ((bar and 1) == 0) BASS_A else BASS_B
        val off = pat[s16]
        if (off >= 0) bass.note(Semitone.hz(rootMidi + off), 0.16f)

        // Rest on the last sixteenth of odd bars so the arp breathes instead of
        // turning into a wall of eighth notes.
        if (arpOn && !(s16 == 15 && (bar and 1) == 1)) {
            arp.note(Semitone.hz(rootMidi + 24 + tones[ARP_PAT[s16]]), 0.20f)
        }

        if (drumsOn) {
            if (KICK_PAT[s16] != 0) kick.hit(if (s16 == 0 || s16 == 8) 1f else 0.72f)
            if (SNARE_PAT[s16] != 0) snare.hit(if (s16 == 15) 0.35f else 0.9f)
            val sixteenths = lvl >= 2 || speedN > 0.6f
            if ((s16 and 1) == 0 || sixteenths) {
                hat.hit(if ((s16 and 3) == 0) 0.9f else 0.5f, s16 == 14)
            }
        }

        // Tempo glides toward its target instead of lurching when speed changes.
        bpm += (bpmTarget - bpm) * 0.08f
        samplesPerStep = (SRF * 60f / (bpm * 4f)).toInt()
        dTimeL = samplesPerStep * 3
        dTimeR = samplesPerStep * 4

        step++
        if (step >= TOTAL_STEPS) step = 0
    }

    private companion object {
        /** Leaves room for the SFX bus, which always wins in an arcade mix. */
        const val MASTER = 0.38f
    }
}
