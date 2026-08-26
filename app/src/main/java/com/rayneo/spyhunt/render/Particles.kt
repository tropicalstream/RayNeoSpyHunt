package com.rayneo.spyhunt.render

import com.rayneo.spyhunt.core.DynamicMesh
import com.rayneo.spyhunt.core.Rng
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.cosf
import com.rayneo.spyhunt.core.lerp
import com.rayneo.spyhunt.core.sinf
import com.rayneo.spyhunt.core.smoothstep
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Emissive particle system for the additive waveguide display.
 *
 * Everything here is HDR and additive: there is no such thing as a dark particle,
 * only a bright one and an absent one. Colour values run 3..13 so the bloom pass
 * has something to bite on, and `alpha` is an *intensity* multiplier rather than
 * an occlusion term.
 *
 * Storage is struct-of-arrays over a fixed pool, kept compacted at the front so
 * update/emit are linear scans with zero allocation. Dead slots are removed by
 * swapping the last live particle down.
 */
class Particles {

    private companion object {
        const val CAP = 1400

        /** Fireball / flame: ramps white-hot -> amber -> deep red. */
        const val K_FIRE = 0
        /** Velocity-aligned streak, extremely bright, very short lived. */
        const val K_SPARK = 1
        /** Water droplet: cyan-white -> deep cyan, dies on contact with the surface. */
        const val K_SPLASH = 2
        /** Single-frame-ish muzzle/impact flash. */
        const val K_MUZZLE = 3
        /** Tumbling tinted shard; carries its own colour. */
        const val K_DEBRIS = 4

        /** Road/water plane. Particles bounce (or drown) here. */
        const val GROUND_Y = 0.04f

        /** Debris quads are drawn elongated so the radial sprite reads as a shard. */
        const val DEBRIS_STRETCH = 2.4f
    }

    // ---- pool (struct-of-arrays) --------------------------------------------
    private val px = FloatArray(CAP)
    private val py = FloatArray(CAP)
    private val pz = FloatArray(CAP)
    private val vx = FloatArray(CAP)
    private val vy = FloatArray(CAP)
    private val vz = FloatArray(CAP)
    private val life = FloatArray(CAP)
    /** 1/maxLife, cached so the per-frame age computation is a multiply. */
    private val invMax = FloatArray(CAP)
    private val size0 = FloatArray(CAP)
    private val size1 = FloatArray(CAP)
    private val rot = FloatArray(CAP)
    private val spin = FloatArray(CAP)
    private val cr = FloatArray(CAP)
    private val cg = FloatArray(CAP)
    private val cb = FloatArray(CAP)
    private val drag = FloatArray(CAP)
    private val grav = FloatArray(CAP)
    private val kind = IntArray(CAP)

    private var count = 0
    /** Round-robin recycle pointer, used only once the pool is saturated. */
    private var cursor = 0

    private val rng = Rng(0x9E37_79B9_7F4AL)

    val activeCount: Int get() = count

    // ---- spawning ------------------------------------------------------------

    /**
     * Fireball. [scale] ~1 for a car, ~2 for a helicopter or a fuel truck.
     * [hot] marks an ammunition/fuel detonation: brighter, faster, throws embers.
     */
    fun spawnExplosion(x: Float, y: Float, z: Float, scale: Float, hot: Boolean) {
        val s = clamp(scale, 0.25f, 4f)
        val boost = if (hot) 1.4f else 1f

        // Instantaneous core flash — this is what actually reads as "explosion" at
        // 60 Hz; the flame that follows only sells the aftermath.
        add(
            K_MUZZLE, x, y, z, 0f, 0f, 0f,
            0.14f, 2.2f * s, 4.6f * s,
            boost, boost, boost, 5f, 0f, 0f
        )

        val n = (14f + 24f * s).toInt().coerceAtMost(52)
        for (j in 0 until n) {
            val a = rng.range(0f, 6.2831855f)
            val e = rng.range(-0.35f, 1f)                  // biased upward
            val ch = sqrt(clamp(1f - e * e, 0f, 1f))
            val sp = rng.range(3f, 13f) * s * boost
            add(
                K_FIRE, x, y, z,
                cosf(a) * ch * sp, e * sp + rng.range(0.6f, 3.4f), sinf(a) * ch * sp,
                rng.range(0.42f, 1.15f) * (0.75f + 0.4f * s),
                rng.range(0.32f, 0.75f) * s, rng.range(1.0f, 2.3f) * s,
                boost, boost, boost, 2.2f, 5.5f, 0f
            )
        }

        val embers = ((if (hot) 16f else 8f) + 10f * s).toInt()
        for (j in 0 until embers) {
            val a = rng.range(0f, 6.2831855f)
            val e = rng.range(0.1f, 1f)
            val ch = sqrt(clamp(1f - e * e, 0f, 1f))
            val sp = rng.range(9f, 26f) * s
            add(
                K_SPARK, x, y, z,
                cosf(a) * ch * sp, e * sp, sinf(a) * ch * sp,
                rng.range(0.28f, 0.7f), 0.11f, 0.03f,
                boost, boost, boost, 0.7f, 13f, 0f
            )
        }
    }

    /**
     * Directional spark spray — bullet impacts, tyre scrapes, guardrail hits.
     * [dirX]/[dirZ] is the spray axis on the ground plane; it does not need to be
     * normalised.
     */
    fun spawnSparks(x: Float, y: Float, z: Float, dirX: Float, dirZ: Float, count: Int) {
        val l = sqrt(dirX * dirX + dirZ * dirZ)
        val dx: Float
        val dz: Float
        if (l > 1e-4f) { dx = dirX / l; dz = dirZ / l } else { dx = 0f; dz = 1f }
        val n = count.coerceIn(1, 64)
        for (j in 0 until n) {
            val a = rng.range(-0.65f, 0.65f)
            val c = cosf(a); val si = sinf(a)
            val ex = dx * c - dz * si
            val ez = dx * si + dz * c
            val sp = rng.range(8f, 27f)
            add(
                K_SPARK, x, y, z,
                ex * sp, rng.range(1.2f, 8f), ez * sp,
                rng.range(0.14f, 0.4f), rng.range(0.07f, 0.13f), 0.02f,
                1f, 1f, 1f, 1.0f, 15f, 0f
            )
        }
    }

    /** Water impact: a flat cyan disc plus a crown of droplets. */
    fun spawnSplash(x: Float, y: Float, z: Float, scale: Float) {
        val s = clamp(scale, 0.25f, 4f)
        // Flat expanding ring flash sitting on the surface.
        add(
            K_SPLASH, x, y + 0.05f, z, 0f, 0f, 0f,
            0.3f, 0.9f * s, 3.4f * s,
            1f, 1f, 1f, 4f, 0f, 0f
        )
        val n = (16f + 20f * s).toInt().coerceAtMost(52)
        for (j in 0 until n) {
            val a = rng.range(0f, 6.2831855f)
            val out = rng.range(2.5f, 9f) * s
            add(
                K_SPLASH, x, y, z,
                cosf(a) * out, rng.range(4f, 13f) * s, sinf(a) * out,
                rng.range(0.35f, 0.85f), rng.range(0.10f, 0.24f) * s, 0.04f,
                1f, 1f, 1f, 0.7f, 17f, 0f
            )
        }
    }

    /** Gun muzzle flash. Deliberately tiny and brutally bright — bloom does the rest. */
    fun spawnMuzzle(x: Float, y: Float, z: Float) {
        add(
            K_MUZZLE, x, y, z, 0f, 0f, 0f,
            0.055f, 0.55f, 0.16f,
            1f, 1f, 1f, 0f, 0f, 0f
        )
        for (j in 0 until 3) {
            add(
                K_SPARK, x, y, z,
                rng.range(-3f, 3f), rng.range(-1f, 2.5f), rng.range(10f, 22f),
                rng.range(0.05f, 0.12f), 0.07f, 0.02f,
                1f, 1f, 1f, 1.5f, 9f, 0f
            )
        }
    }

    /** Tinted shrapnel — panels off a wrecked car, rotor blades, guardrail chunks. */
    fun spawnDebris(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, count: Int) {
        val n = count.coerceIn(1, 48)
        for (j in 0 until n) {
            add(
                K_DEBRIS, x, y, z,
                rng.range(-7.5f, 7.5f), rng.range(2f, 11f), rng.range(-7.5f, 7.5f),
                rng.range(0.7f, 1.7f), rng.range(0.10f, 0.26f), rng.range(0.05f, 0.14f),
                r, g, b, 0.55f, 11f, rng.range(-16f, 16f)
            )
        }
    }

    // ---- simulation ----------------------------------------------------------

    /**
     * Integrates the pool. [worldScrollZ] is how far the world scrolled forward
     * this frame in metres; it drives a mild slipstream that drags flame forward
     * behind a fast-moving car, which is what makes explosions smear convincingly
     * at 50 m/s instead of hanging in the air like fireworks.
     */
    fun update(dt: Float, worldScrollZ: Float) {
        if (dt <= 0f) return
        // Clamped hard: a frame hitch (or a caller handing us an absolute Z) must
        // not turn into a hurricane.
        val wake = clamp(worldScrollZ / dt, 0f, 120f) * 0.12f

        var i = 0
        while (i < count) {
            var l = life[i] - dt
            if (l <= 0f) { swapRemove(i); continue }

            val k = kind[i]
            // Implicit-Euler style drag: unconditionally stable, no exp() per particle.
            val d = 1f / (1f + drag[i] * dt)
            var nvx = vx[i] * d
            var nvy = (vy[i] - grav[i] * dt) * d
            var nvz = vz[i] * d
            if (k == K_FIRE) nvz += wake * dt

            val nx = px[i] + nvx * dt
            var ny = py[i] + nvy * dt
            val nz = pz[i] + nvz * dt

            if (ny < GROUND_Y) {
                if (k == K_SPLASH) {
                    // Droplets fall back into the water rather than skidding along it.
                    ny = GROUND_Y
                    if (l > 0.06f) l = 0.06f
                    nvx = 0f; nvy = 0f; nvz = 0f
                } else {
                    ny = GROUND_Y
                    nvy = -nvy * 0.32f
                    nvx *= 0.62f
                    nvz *= 0.62f
                    if (abs(nvy) < 0.45f) nvy = 0f
                }
            }

            life[i] = l
            vx[i] = nvx; vy[i] = nvy; vz[i] = nvz
            px[i] = nx; py[i] = ny; pz[i] = nz
            rot[i] += spin[i] * dt
            i++
        }
    }

    // ---- geometry ------------------------------------------------------------

    /**
     * Appends camera-facing quads to [mesh] (9 floats/vert: pos3, uv2, rgba4).
     * The caller owns reset()/flushAndDraw().
     *
     * [originZ] is the player's absolute Z and is subtracted from every vertex so
     * the geometry stays near the origin after kilometres of driving.
     */
    fun emit(
        mesh: DynamicMesh, camRightX: Float, camRightY: Float, camRightZ: Float,
        camUpX: Float, camUpY: Float, camUpZ: Float, originZ: Float
    ) {
        if (mesh.floatsPerVert != 9 || mesh.cpuIdx.size < 6) return

        var i = 0
        while (i < count) {
            if (!mesh.hasRoom(4, 6)) break

            val t = clamp(1f - life[i] * invMax[i], 0f, 1f)
            val k = kind[i]

            // ---- colour ramp -------------------------------------------------
            var rr: Float; var gg: Float; var bb: Float; var aa: Float
            when (k) {
                K_FIRE -> {
                    val k1 = smoothstep(0f, 0.20f, t)      // white-hot -> amber
                    val k2 = smoothstep(0.34f, 0.96f, t)   // amber -> deep red
                    rr = lerp(lerp(11.0f, 9.5f, k1), 3.6f, k2)
                    gg = lerp(lerp(10.0f, 4.0f, k1), 0.45f, k2)
                    bb = lerp(lerp(8.5f, 0.75f, k1), 0.10f, k2)
                    aa = (1f - t) * (1f - t) * 1.15f
                }
                K_SPARK -> {
                    rr = 12f
                    gg = lerp(10.5f, 4.2f, t)
                    bb = lerp(9f, 0.8f, t)
                    aa = 1f - t * t
                }
                K_SPLASH -> {
                    rr = lerp(5.5f, 0.35f, t)
                    gg = lerp(8.5f, 3.0f, t)
                    bb = lerp(11f, 5.5f, t)
                    aa = (1f - t) * 1.1f
                }
                K_MUZZLE -> {
                    rr = 13f
                    gg = lerp(12f, 6f, t)
                    bb = lerp(10f, 2.2f, t)
                    aa = 1f - t
                }
                else -> {
                    // Debris carries its own tint; push it into HDR so it blooms.
                    rr = cr[i] * 3.6f
                    gg = cg[i] * 3.6f
                    bb = cb[i] * 3.6f
                    aa = 1f - t * t
                }
            }
            if (k != K_DEBRIS) { rr *= cr[i]; gg *= cg[i]; bb *= cb[i] }
            if (aa <= 0.002f) { i++; continue }

            // ---- orientation --------------------------------------------------
            // a1 is the quad's long axis expressed in the camera's (right, up) basis.
            var a1r: Float
            var a1u: Float
            var elong = 1f
            if (k == K_SPARK) {
                // Velocity-aligned: a moving spark is a streak, not a dot.
                val sr = vx[i] * camRightX + vy[i] * camRightY + vz[i] * camRightZ
                val su = vx[i] * camUpX + vy[i] * camUpY + vz[i] * camUpZ
                val sl = sqrt(sr * sr + su * su)
                if (sl > 1e-4f) { a1r = sr / sl; a1u = su / sl } else { a1r = 1f; a1u = 0f }
                elong = 1f + clamp(sl * 0.055f, 0f, 5f)
            } else {
                a1r = cosf(rot[i]); a1u = sinf(rot[i])
                if (k == K_DEBRIS) elong = DEBRIS_STRETCH
            }
            val a2r = -a1u
            val a2u = a1r

            val s = lerp(size0[i], size1[i], t)
            val hx = s * elong
            val hy = s
            val e1x = (a1r * camRightX + a1u * camUpX) * hx
            val e1y = (a1r * camRightY + a1u * camUpY) * hx
            val e1z = (a1r * camRightZ + a1u * camUpZ) * hx
            val e2x = (a2r * camRightX + a2u * camUpX) * hy
            val e2y = (a2r * camRightY + a2u * camUpY) * hy
            val e2z = (a2r * camRightZ + a2u * camUpZ) * hy

            val x = px[i]
            val y = py[i]
            val z = pz[i] - originZ

            val v0 = mesh.vertCount
            vtx(mesh, x - e1x - e2x, y - e1y - e2y, z - e1z - e2z, 0f, 0f, rr, gg, bb, aa)
            vtx(mesh, x + e1x - e2x, y + e1y - e2y, z + e1z - e2z, 1f, 0f, rr, gg, bb, aa)
            vtx(mesh, x + e1x + e2x, y + e1y + e2y, z + e1z + e2z, 1f, 1f, rr, gg, bb, aa)
            vtx(mesh, x - e1x + e2x, y - e1y + e2y, z - e1z + e2z, 0f, 1f, rr, gg, bb, aa)
            mesh.quadIndices(v0)
            i++
        }
    }

    // ---- internals -----------------------------------------------------------

    /**
     * Writes one vertex straight into the mesh's CPU buffer. DynamicMesh.push() is
     * a vararg, so it allocates a FloatArray per call — at 1400 quads that is 5600
     * short-lived arrays every frame, which the GC would notice on an Adreno 621.
     */
    private fun vtx(
        m: DynamicMesh, x: Float, y: Float, z: Float, u: Float, v: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val o = m.vertCount * 9
        val c = m.cpu
        c[o] = x; c[o + 1] = y; c[o + 2] = z
        c[o + 3] = u; c[o + 4] = v
        c[o + 5] = r; c[o + 6] = g; c[o + 7] = b; c[o + 8] = a
        m.vertCount++
    }

    private fun add(
        k: Int, x: Float, y: Float, z: Float,
        ivx: Float, ivy: Float, ivz: Float,
        lifeS: Float, s0: Float, s1: Float,
        tr: Float, tg: Float, tb: Float,
        dragC: Float, gravC: Float, spinR: Float
    ) {
        val i = alloc()
        kind[i] = k
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = ivx; vy[i] = ivy; vz[i] = ivz
        val ls = if (lifeS < 0.02f) 0.02f else lifeS
        life[i] = ls; invMax[i] = 1f / ls
        size0[i] = s0; size1[i] = s1
        cr[i] = tr; cg[i] = tg; cb[i] = tb
        drag[i] = dragC; grav[i] = gravC
        rot[i] = rng.range(0f, 6.2831855f)
        spin[i] = spinR
    }

    /**
     * Claims a slot. Once the pool saturates we recycle round-robin rather than
     * dropping the request: a brand new explosion must always be visible, and
     * stealing an already-fading particle is the cheapest way to guarantee it.
     */
    private fun alloc(): Int {
        if (count < CAP) return count++
        val i = cursor
        cursor++
        if (cursor >= CAP) cursor = 0
        return i
    }

    private fun swapRemove(i: Int) {
        val last = count - 1
        if (i != last) {
            px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last]
            vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
            life[i] = life[last]; invMax[i] = invMax[last]
            size0[i] = size0[last]; size1[i] = size1[last]
            rot[i] = rot[last]; spin[i] = spin[last]
            cr[i] = cr[last]; cg[i] = cg[last]; cb[i] = cb[last]
            drag[i] = drag[last]; grav[i] = grav[last]
            kind[i] = kind[last]
        }
        count = last
        if (cursor >= count) cursor = 0
    }
}
