package com.rayneo.spyhunt.core

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal math layer. Matrices are FloatArray(16), column-major, matching
 * android.opengl.Matrix and GLSL's mat4 so they upload with transpose=false.
 */

const val DEG2RAD = 0.017453292f

data class V2(@JvmField val x: Float, @JvmField val y: Float) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(s: Float) = V2(x * s, y * s)
    fun len() = sqrt(x * x + y * y)
    companion object { val ZERO = V2(0f, 0f) }
}

data class V3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) ZERO else V3(x / l, y / l, z / l) }
    companion object { val ZERO = V3(0f, 0f, 0f) }
}

fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp((x - e0) / (e1 - e0), 0f, 1f); return t * t * (3f - 2f * t)
}

/** Frame-rate independent exponential approach: moves [a] toward [b], [rate] per second. */
fun damp(a: Float, b: Float, rate: Float, dt: Float): Float =
    lerp(a, b, 1f - kotlin.math.exp(-rate * dt))

/** Shortest signed difference between two angles, in radians. */
fun angleDelta(a: Float, b: Float): Float {
    var d = b - a
    while (d > Math.PI.toFloat()) d -= (2.0 * Math.PI).toFloat()
    while (d < -Math.PI.toFloat()) d += (2.0 * Math.PI).toFloat()
    return d
}

object M4 {
    fun identity(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun mul(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        Matrix.multiplyMM(out, 0, a, 0, b, 0); return out
    }

    fun translate(x: Float, y: Float, z: Float): FloatArray =
        identity().also { Matrix.translateM(it, 0, x, y, z) }

    fun scale(x: Float, y: Float, z: Float): FloatArray =
        identity().also { Matrix.scaleM(it, 0, x, y, z) }

    fun rotate(deg: Float, x: Float, y: Float, z: Float): FloatArray =
        FloatArray(16).also { Matrix.setRotateM(it, 0, deg, x, y, z) }

    /**
     * Off-axis (parallel-axis asymmetric) perspective frustum.
     * This is the correct stereo projection: both eyes keep parallel forward
     * axes and the frustum is sheared instead of toed-in, which avoids the
     * vertical disparity that causes eye strain.
     *
     * @param eyeShift  signed lateral eye offset in metres (+right)
     * @param converge  distance in metres at which the two images coincide
     */
    fun perspectiveOffAxis(
        fovYDeg: Float, aspect: Float, near: Float, far: Float,
        eyeShift: Float, converge: Float
    ): FloatArray {
        val top = near * kotlin.math.tan(fovYDeg * 0.5f * DEG2RAD)
        val bottom = -top
        val halfW = top * aspect
        // Shear proportional to near/converge so the zero-parallax plane sits at `converge`.
        val shift = eyeShift * near / converge
        val out = FloatArray(16)
        Matrix.frustumM(out, 0, -halfW - shift, halfW - shift, bottom, top, near, far)
        return out
    }

    fun lookAt(
        ex: Float, ey: Float, ez: Float,
        cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float
    ): FloatArray = FloatArray(16).also {
        Matrix.setLookAtM(it, 0, ex, ey, ez, cx, cy, cz, ux, uy, uz)
    }

    fun invert(m: FloatArray): FloatArray =
        FloatArray(16).also { Matrix.invertM(it, 0, m, 0) }

    fun transpose(m: FloatArray): FloatArray =
        FloatArray(16).also { Matrix.transposeM(it, 0, m, 0) }

    /** Upper-left 3x3 normal matrix (inverse-transpose) as a mat3-compatible mat4. */
    fun normalMatrix(model: FloatArray): FloatArray = transpose(invert(model))

    /** Build a TRS matrix: translate * rotateY * scale. Common case for ground vehicles. */
    fun trs(px: Float, py: Float, pz: Float, yawDeg: Float, sx: Float, sy: Float, sz: Float): FloatArray =
        FloatArray(16).also { trsInto(it, px, py, pz, yawDeg, sx, sy, sz) }

    /** Allocation-free [trs]. Use this anywhere inside the frame loop. */
    fun trsInto(
        out: FloatArray,
        px: Float, py: Float, pz: Float, yawDeg: Float,
        sx: Float, sy: Float, sz: Float
    ) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, px, py, pz)
        Matrix.rotateM(out, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, sx, sy, sz)
    }

    /** Allocation-free [normalMatrix]; [tmp] is scratch the caller owns. */
    fun normalMatrixInto(out: FloatArray, model: FloatArray, tmp: FloatArray) {
        Matrix.invertM(tmp, 0, model, 0)
        Matrix.transposeM(out, 0, tmp, 0)
    }
}

/** Deterministic PRNG — reproducible runs, no allocation, far cheaper than java.util.Random. */
class Rng(seed: Long = 0x5EED_1234_ABCDL) {
    private var s: Long = if (seed == 0L) 1L else seed
    fun nextULong(): Long { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return s }
    /** Uniform in [0,1). */
    fun f(): Float = ((nextULong() ushr 40).toInt() and 0xFFFFFF) / 16777216f
    fun range(a: Float, b: Float) = a + (b - a) * f()
    fun int(nExclusive: Int): Int = if (nExclusive <= 0) 0 else (abs(nextULong() % nExclusive)).toInt()
    fun chance(p: Float) = f() < p
    fun sign() = if (f() < 0.5f) -1f else 1f
}

/** Cheap 1-D value noise, C1-continuous. Used for road curvature and camera shake. */
fun valueNoise(x: Float, seed: Int = 0): Float {
    val i = kotlin.math.floor(x).toInt()
    val f = x - i
    fun h(n: Int): Float {
        var v = (n * 374761393 + seed * 668265263)
        v = (v xor (v shr 13)) * 1274126177
        return ((v xor (v shr 16)) and 0x7FFFFFF) / 67108864f - 1f
    }
    val u = f * f * (3f - 2f * f)
    return lerp(h(i), h(i + 1), u)
}

fun fbm(x: Float, octaves: Int = 3, seed: Int = 0): Float {
    var a = 0.5f; var fr = 1f; var sum = 0f
    for (o in 0 until octaves) { sum += a * valueNoise(x * fr, seed + o); fr *= 2f; a *= 0.5f }
    return sum
}

fun sinf(x: Float) = sin(x)
fun cosf(x: Float) = cos(x)
