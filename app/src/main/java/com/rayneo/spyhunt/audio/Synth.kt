package com.rayneo.spyhunt.audio

import com.rayneo.spyhunt.core.clamp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * ============================================================================
 *  Procedural DSP toolbox. There is not one audio asset in this APK — every
 *  sound the player hears is computed here, sample by sample.
 * ============================================================================
 *
 * House rules, both of which exist because the glasses run a low-latency HAL
 * with a ~5 ms buffer and underruns are instantly audible as clicks:
 *
 *  1. Nothing in this file allocates after construction. The mixer thread must
 *     stay garbage-free, so every buffer, table and filter state is a field.
 *  2. Every object here is owned by the mixer thread alone. The only
 *     cross-thread traffic in the audio module is the lock-free trigger ring in
 *     [AudioEngine], which carries plain ints and floats.
 */

internal const val SR = 48000
internal const val SRF = 48000f
internal const val INV_SR = 1f / SRF
internal const val TWO_PI = 6.2831853f

/**
 * Shared sine table. 2048 points plus linear interpolation is well under the
 * noise floor of an open-ear driver, and it turns every oscillator into two
 * loads and a multiply-add.
 */
internal object Waves {
    const val N = 2048
    private const val MASK = N - 1

    /** One guard point past the end so interpolation never needs a wrap test. */
    private val table = FloatArray(N + 1) { sin(TWO_PI * it.toFloat() / N.toFloat()) }

    /** Sine of a normalised phase; [p] is expected in [0,1). */
    fun sine(p: Float): Float {
        val x = p * N.toFloat()
        val i = x.toInt()
        val i0 = i and MASK
        return table[i0] + (table[i0 + 1] - table[i0]) * (x - i.toFloat())
    }
}

/** Fast tanh-shaped saturator (Pade approximant): monotonic, unity slope at 0. */
internal fun softClip(x: Float): Float {
    val a = if (x < -3f) -3f else if (x > 3f) 3f else x
    val a2 = a * a
    return a * (27f + a2) / (27f + 9f * a2)
}

/**
 * Taylor expansion of tan(). Error is under 0.1% below ~10 kHz and degrades
 * gracefully toward Nyquist. Filters sweep their cutoff every few samples, so
 * kotlin.math.tan would otherwise be the single most expensive call in the mix.
 */
internal fun fastTan(x: Float): Float {
    val x2 = x * x
    return x * (1f + x2 * (0.3333333f + x2 * (0.1333333f + x2 * (0.0539683f + x2 * 0.0218695f))))
}

/**
 * polyBLEP residual for a unit-height step at phase [t] with per-sample phase
 * increment [dt]. Subtracting this at every discontinuity is what keeps the saw
 * and pulse alias-free — naive versions fold garbage down into the midrange,
 * which on a tiny driver sounds like a broken speaker rather than an engine.
 */
internal fun polyBlep(t: Float, dt: Float): Float {
    if (t < dt) { val x = t / dt; return x + x - x * x - 1f }
    if (t > 1f - dt) { val x = (t - 1f) / dt; return x * x + x + x + 1f }
    return 0f
}

/** Equal-temperament ratios without a pow() per note. */
internal object Semitone {
    private val t = FloatArray(97) { 2f.pow((it - 48).toFloat() / 12f) }

    /** Frequency ratio for [n] semitones, clamped to +/-48. */
    fun ratio(n: Int): Float {
        val i = n + 48
        return t[if (i < 0) 0 else if (i > 96) 96 else i]
    }

    /** MIDI note number to Hz. */
    fun hz(midi: Int): Float = 440f * ratio(midi - 69)
}

/** Constant-power pan law; [pan] is -1 (hard left) .. +1 (hard right). */
internal fun panL(pan: Float): Float = Waves.sine((clamp(pan, -1f, 1f) + 1f) * 0.125f + 0.25f)
internal fun panR(pan: Float): Float = Waves.sine((clamp(pan, -1f, 1f) + 1f) * 0.125f)

// ---------------------------------------------------------------------------
//  Oscillators and noise
// ---------------------------------------------------------------------------

/**
 * One band-limited oscillator. Frequency is set through [inc] rather than by
 * resetting phase, so sweeping the pitch of a sounding voice is always
 * continuous — that is what stops the engine bed clicking when speed changes.
 */
internal class Osc {
    @JvmField var phase = 0f
    @JvmField var inc = 0f
    private var triZ = 0f

    fun setFreq(hz: Float) { inc = clamp(hz * INV_SR, 0f, 0.45f) }
    fun reset(p: Float = 0f) { phase = p; triZ = 0f }

    private fun step() { phase += inc; if (phase >= 1f) phase -= 1f }

    fun sine(): Float { val v = Waves.sine(phase); step(); return v }

    /** Alias-free sawtooth, -1..1. */
    fun saw(): Float {
        val v = 2f * phase - 1f - polyBlep(phase, inc)
        step()
        return v
    }

    /** Alias-free pulse; [duty] 0..1 (0.5 is a square). */
    fun square(duty: Float = 0.5f): Float {
        var v = if (phase < duty) 1f else -1f
        v += polyBlep(phase, inc)
        var t2 = phase - duty
        if (t2 < 0f) t2 += 1f
        v -= polyBlep(t2, inc)
        step()
        return v
    }

    /** Triangle by leaky-integrating the band-limited pulse, so it stays clean. */
    fun tri(): Float {
        val sq = square(0.5f)
        triZ = triZ * 0.9995f + inc * sq * 4f
        return triZ
    }
}

/** xorshift32 white noise. Deterministic, branch-free, no allocation. */
internal class Noise(seed: Int = 0x1F123BB5) {
    private var s: Int = if (seed == 0) 0x2545F491 else seed

    private fun nextInt(): Int {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return s
    }

    /** Bipolar, -1..1. */
    fun white(): Float = (nextInt() shr 8).toFloat() * (1f / 8388608f)

    /** Unipolar, 0..1 — used for grain/crackle scheduling. */
    fun uni(): Float = (nextInt() ushr 8).toFloat() * (1f / 16777216f)
}

/**
 * Pink noise via Paul Kellet's economy filter bank. Pink reads as "air" and
 * "tyre roar"; pure white on these drivers just sounds like tape hiss.
 */
internal class PinkNoise(seed: Int = 0x2A5D3F1) {
    private val w = Noise(seed)
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun next(): Float {
        val v = w.white()
        b0 = 0.99886f * b0 + v * 0.0555179f
        b1 = 0.99332f * b1 + v * 0.0750759f
        b2 = 0.96900f * b2 + v * 0.1538520f
        b3 = 0.86650f * b3 + v * 0.3104856f
        b4 = 0.55000f * b4 + v * 0.5329522f
        b5 = -0.7616f * b5 - v * 0.0168980f
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + v * 0.5362f
        b6 = v * 0.115926f
        return out * 0.11f
    }
}

// ---------------------------------------------------------------------------
//  Envelopes
// ---------------------------------------------------------------------------

/**
 * Percussive exponential decay. Most SFX in an arcade game are "hit and fall",
 * so this one-field envelope covers the majority of voices for the cost of a
 * single multiply per sample.
 */
internal class ExpEnv {
    @JvmField var level = 0f
    private var coef = 0f

    /** Retrigger at [amp], falling ~60 dB over [decaySec]. */
    fun hit(amp: Float, decaySec: Float) {
        level = amp
        setDecay(decaySec)
    }

    /** Change the fall rate without retriggering (for swept sounds). */
    fun setDecay(decaySec: Float) { coef = exp(-6.9078f / (max(1e-4f, decaySec) * SRF)) }

    fun next(): Float { val v = level; level *= coef; return v }
    fun kill() { level = 0f }
    val done: Boolean get() = level < 1e-4f
}

/** Classic four-stage envelope: linear attack, exponential decay/release. */
internal class Adsr {
    @JvmField var level = 0f
    @JvmField var sustain = 0.7f
    private var stage = IDLE
    private var attInc = 1f
    private var decCoef = 0f
    private var relCoef = 0f

    fun set(attackSec: Float, decaySec: Float, sustainLevel: Float, releaseSec: Float) {
        attInc = 1f / max(1f, attackSec * SRF)
        decCoef = exp(-4.6f / max(1f, decaySec * SRF))
        relCoef = exp(-4.6f / max(1f, releaseSec * SRF))
        sustain = clamp(sustainLevel, 0f, 1f)
    }

    fun gateOn() { stage = ATTACK }
    fun gateOff() { if (stage != IDLE) stage = RELEASE }
    fun kill() { stage = IDLE; level = 0f }
    val active: Boolean get() = stage != IDLE

    fun next(): Float {
        when (stage) {
            ATTACK -> {
                level += attInc
                if (level >= 1f) { level = 1f; stage = DECAY }
            }
            DECAY -> {
                level = sustain + (level - sustain) * decCoef
                if (level - sustain < 0.001f) {
                    if (sustain < 0.001f) { level = 0f; stage = IDLE } else { level = sustain; stage = SUSTAIN }
                }
            }
            RELEASE -> {
                level *= relCoef
                if (level < 0.0005f) { level = 0f; stage = IDLE }
            }
            else -> { /* IDLE and SUSTAIN hold their level */ }
        }
        return level
    }

    private companion object {
        const val IDLE = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
        const val RELEASE = 4
    }
}

/**
 * One-pole parameter smoother. Every control value that can jump between frames
 * (engine pitch, filter cutoff, gain) goes through one of these; a raw jump on a
 * sounding voice is a click, and clicks are all you hear on open-ear drivers.
 */
internal class Smooth(initial: Float = 0f, timeConstSec: Float = 0.02f) {
    @JvmField var value = initial
    @JvmField var target = initial
    private var coef = exp(-1f / max(1f, timeConstSec * SRF))

    fun setTime(sec: Float) { coef = exp(-1f / max(1f, sec * SRF)) }
    fun next(): Float { value = target + (value - target) * coef; return value }
    fun snap(v: Float) { value = v; target = v }
}

// ---------------------------------------------------------------------------
//  Filters
// ---------------------------------------------------------------------------

/** One-pole low/high pass. Cheap tone shaping; 6 dB/oct. */
internal class OnePole {
    private var z = 0f
    private var a = 0.5f

    /** Bilinear-free approximation — exact enough for tone shaping, one mul. */
    fun setCutoff(hz: Float) { a = clamp(TWO_PI * hz * INV_SR, 0.0001f, 0.98f) }

    fun lp(x: Float): Float { z += a * (x - z); return z }
    fun hp(x: Float): Float { z += a * (x - z); return x - z }
    fun reset() { z = 0f }
}

/**
 * Topology-preserving-transform state variable filter (Simper). Zero-delay
 * feedback means it stays stable and keeps its tuning even when the cutoff is
 * swept per sample, which is exactly what the explosion and whoosh patches do.
 * All three outputs are produced at once and left in fields to avoid returning
 * a tuple (which would allocate).
 */
internal class Svf {
    @JvmField var lp = 0f
    @JvmField var bp = 0f
    @JvmField var hp = 0f
    private var ic1 = 0f
    private var ic2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var k = 1f

    fun set(cutoffHz: Float, q: Float) {
        val fc = clamp(cutoffHz, 20f, SRF * 0.45f)
        val g = fastTan(3.1415927f * fc * INV_SR)
        k = 1f / max(0.4f, q)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(v0: Float) {
        val v3 = v0 - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        lp = v2
        bp = v1
        hp = v0 - k * v1 - v2
    }

    fun reset() { ic1 = 0f; ic2 = 0f; lp = 0f; bp = 0f; hp = 0f }
}

// ---------------------------------------------------------------------------
//  Delay and dynamics
// ---------------------------------------------------------------------------

/** Ring-buffer delay for slap-back echo. Integer taps only — no interpolation
 *  needed because the echo time only changes at musical step boundaries. */
internal class DelayLine(sizeSamples: Int) {
    private val buf = FloatArray(max(8, sizeSamples))
    private var w = 0

    fun clear() { buf.fill(0f); w = 0 }

    /** Writes [x] (plus [feedback] of the tap) and returns the sample from
     *  [delay] samples ago. */
    fun tick(x: Float, delay: Int, feedback: Float): Float {
        val d = clamp(delay.toFloat(), 1f, (buf.size - 1).toFloat()).toInt()
        var r = w - d
        if (r < 0) r += buf.size
        val out = buf[r]
        buf[w] = x + out * feedback
        w++
        if (w >= buf.size) w = 0
        return out
    }
}

/**
 * Master peak limiter. No look-ahead — a 0.4 ms attack lets the very first
 * transient through, which the final soft-clip mops up. Its real job is to keep
 * a pile-up of explosions from pinning the tiny drivers into distortion.
 */
internal class Limiter(private val ceiling: Float = 0.92f) {
    private var env = 0f
    private val atk = exp(-1f / (0.0004f * SRF))
    private val rel = exp(-1f / (0.15f * SRF))

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            val a = max(abs(l[i]), abs(r[i]))
            env = if (a > env) a + (env - a) * atk else a + (env - a) * rel
            val g = if (env > ceiling) ceiling / env else 1f
            l[i] = softClip(l[i] * g)
            r[i] = softClip(r[i] * g)
        }
    }

    fun reset() { env = 0f }
}
