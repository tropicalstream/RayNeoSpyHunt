package com.rayneo.spyhunt.render

import com.rayneo.spyhunt.core.DynamicMesh
import kotlin.math.sqrt

/**
 * Camera-facing light ribbons trailing every moving vehicle.
 *
 * Points are stored in ABSOLUTE world space, so a car doing 50 m/s lays down a
 * ~25 m streak behind itself purely because the world moves on underneath it.
 * That relative smear is most of what sells motion at 60 Hz on a display with no
 * motion blur of its own.
 *
 * Fixed pool: [SLOTS] ribbons x [PTS] points, ring-buffered, zero allocation
 * after construction.
 */
class Trails {

    private companion object {
        const val SLOTS = 24
        const val PTS = 32

        /** Seconds a ribbon point survives. Longer reads as smeary, shorter as choppy. */
        const val LIFE = 0.55f
        /** Minimum spacing between stored points; below this we just slide the head. */
        const val MIN_SPACING = 0.55f
        const val MIN_SPACING_SQ = MIN_SPACING * MIN_SPACING
        /**
         * A single-frame jump beyond this is not motion — the sim clamps dt to
         * 50 ms and nothing travels at 500 m/s. It means the id now belongs to a
         * different vehicle: Spawner.recycle() frees a pool slot and respawns
         * into it in the same frame, so the renderer never gets to call expire()
         * in between. Without the guard the ribbon bridges the two positions with
         * one several-hundred-metre quad at full additive brightness — a bright
         * bar straight across the board.
         */
        const val BREAK_DIST = 25f
        const val BREAK_DIST_SQ = BREAK_DIST * BREAK_DIST
        /**
         * A ribbon whose owner has not pushed for this long is orphaned. Vehicles
         * that vanish without an expire() call would otherwise leak their slot.
         */
        const val STALE = 1.2f

        const val ST_FREE = 0
        const val ST_LIVE = 1
        const val ST_ORPHAN = 2

        /** Head-to-tail width taper: the tail keeps this fraction of the head width. */
        const val TAIL_WIDTH = 0.16f
        /** HDR gain so ribbons blow out into the bloom pass. */
        const val GAIN = 3.2f
    }

    // ---- ribbon slots --------------------------------------------------------
    private val ownerId = IntArray(SLOTS)
    private val ownerState = IntArray(SLOTS)
    private val head = IntArray(SLOTS)   // ring index of the newest point
    private val len = IntArray(SLOTS)    // number of valid points
    private val lastPush = FloatArray(SLOTS)

    // ---- points (flat, slot-major) -------------------------------------------
    private val px = FloatArray(SLOTS * PTS)
    private val py = FloatArray(SLOTS * PTS)
    private val pz = FloatArray(SLOTS * PTS)
    private val pr = FloatArray(SLOTS * PTS)
    private val pg = FloatArray(SLOTS * PTS)
    private val pb = FloatArray(SLOTS * PTS)
    private val pw = FloatArray(SLOTS * PTS)
    private val pt = FloatArray(SLOTS * PTS)   // birth stamp, compared against `clock`

    private var clock = 0f

    /**
     * Extends the ribbon owned by [id] (a stable per-vehicle key). Call once per
     * frame per vehicle, at the emitter position — usually the rear of the car.
     */
    fun push(id: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, width: Float) {
        val s = slotFor(id)
        lastPush[s] = clock

        val base = s * PTS
        if (len[s] > 0) {
            val h = base + head[s]
            val dx = x - px[h]
            val dy = y - py[h]
            val dz = z - pz[h]
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > BREAK_DIST_SQ) {
                // The slot was handed to a different vehicle — see [BREAK_DIST].
                // Drop the inherited points and start the ribbon here.
                head[s] = 0
                len[s] = 1
                write(base, x, y, z, r, g, b, width)
                return
            }
            if (d2 < MIN_SPACING_SQ) {
                // Too close to append: slide the head instead so the ribbon stays
                // glued to the vehicle even when it is barely moving.
                write(h, x, y, z, r, g, b, width)
                return
            }
        }
        head[s] = if (len[s] == 0) 0 else (head[s] + 1) % PTS
        write(base + head[s], x, y, z, r, g, b, width)
        if (len[s] < PTS) len[s]++
    }

    /** Releases the ribbon for [id]. It keeps fading rather than popping out of existence. */
    fun expire(id: Int) {
        for (s in 0 until SLOTS) {
            if (ownerState[s] == ST_LIVE && ownerId[s] == id) {
                ownerState[s] = ST_ORPHAN
                return
            }
        }
    }

    fun update(dt: Float) {
        clock += dt
        for (s in 0 until SLOTS) {
            if (ownerState[s] == ST_FREE) continue

            // Points are appended in time order, so expiry only ever eats the tail.
            val base = s * PTS
            while (len[s] > 0) {
                val tail = base + ((head[s] - (len[s] - 1) + PTS * 2) % PTS)
                if (clock - pt[tail] <= LIFE) break
                len[s]--
            }

            if (ownerState[s] == ST_LIVE && clock - lastPush[s] > STALE) ownerState[s] = ST_ORPHAN
            if (ownerState[s] == ST_ORPHAN && len[s] == 0) ownerState[s] = ST_FREE
        }
    }

    /**
     * Appends ribbon quads to [mesh] (9 floats/vert: pos3, uv2, rgba4). The caller
     * owns reset()/flushAndDraw(). [originZ] is the player's absolute Z, subtracted
     * from every vertex to keep the geometry near the origin.
     *
     * UVs pin u to 0.5 and sweep v across the ribbon, so the shader's radial sprite
     * falloff becomes a soft edge across the ribbon and stays solid along its length.
     */
    fun emit(
        mesh: DynamicMesh, camRightX: Float, camRightY: Float, camRightZ: Float,
        camUpX: Float, camUpY: Float, camUpZ: Float, originZ: Float
    ) {
        if (mesh.floatsPerVert != 9 || mesh.cpuIdx.size < 6) return
        val invLife = 1f / LIFE

        for (s in 0 until SLOTS) {
            if (ownerState[s] == ST_FREE || len[s] < 2) continue
            val base = s * PTS

            var k = 0
            while (k < len[s] - 1) {
                if (!mesh.hasRoom(4, 6)) return

                val ia = base + ((head[s] - k + PTS * 2) % PTS)
                val ib = base + ((head[s] - (k + 1) + PTS * 2) % PTS)

                var fa = 1f - (clock - pt[ia]) * invLife
                var fb = 1f - (clock - pt[ib]) * invLife
                if (fa < 0f) fa = 0f
                if (fb < 0f) fb = 0f

                val ax = px[ia]; val ay = py[ia]; val az = pz[ia] - originZ
                val bx = px[ib]; val by = py[ib]; val bz = pz[ib] - originZ

                // Screen-space perpendicular of the segment: project the segment into
                // the camera basis, rotate 90 degrees, lift back into world space.
                val dx = bx - ax; val dy = by - ay; val dz = bz - az
                val dr = dx * camRightX + dy * camRightY + dz * camRightZ
                val du = dx * camUpX + dy * camUpY + dz * camUpZ
                val dl = sqrt(dr * dr + du * du)
                val nr: Float
                val nu: Float
                if (dl > 1e-5f) { nr = -du / dl; nu = dr / dl } else { nr = 1f; nu = 0f }
                val sx = nr * camRightX + nu * camUpX
                val sy = nr * camRightY + nu * camUpY
                val sz = nr * camRightZ + nu * camUpZ

                val wa = pw[ia] * (TAIL_WIDTH + (1f - TAIL_WIDTH) * fa) * 0.5f
                val wb = pw[ib] * (TAIL_WIDTH + (1f - TAIL_WIDTH) * fb) * 0.5f

                // The segment nearest the vehicle gets an extra hot core.
                val hot = if (k == 0) 1.7f else 1f
                val alphaA = fa * fa * 1.4f * hot
                val alphaB = fb * fb * 1.4f

                val ar = pr[ia] * GAIN; val ag = pg[ia] * GAIN; val ablu = pb[ia] * GAIN
                val br = pr[ib] * GAIN; val bg = pg[ib] * GAIN; val bblu = pb[ib] * GAIN

                val v0 = mesh.vertCount
                vtx(mesh, ax - sx * wa, ay - sy * wa, az - sz * wa, 0.5f, 0f, ar, ag, ablu, alphaA)
                vtx(mesh, bx - sx * wb, by - sy * wb, bz - sz * wb, 0.5f, 0f, br, bg, bblu, alphaB)
                vtx(mesh, bx + sx * wb, by + sy * wb, bz + sz * wb, 0.5f, 1f, br, bg, bblu, alphaB)
                vtx(mesh, ax + sx * wa, ay + sy * wa, az + sz * wa, 0.5f, 1f, ar, ag, ablu, alphaA)
                mesh.quadIndices(v0)
                k++
            }
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun write(i: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, w: Float) {
        px[i] = x; py[i] = y; pz[i] = z
        pr[i] = r; pg[i] = g; pb[i] = b
        pw[i] = w; pt[i] = clock
    }

    /** Finds the ribbon for [id], claiming a free slot — or stealing the shortest — as needed. */
    private fun slotFor(id: Int): Int {
        var free = -1
        var orphan = -1
        var orphanLen = Int.MAX_VALUE
        var victim = 0
        var victimLen = Int.MAX_VALUE
        for (s in 0 until SLOTS) {
            if (ownerState[s] == ST_LIVE && ownerId[s] == id) return s
            when (ownerState[s]) {
                ST_FREE -> if (free < 0) free = s
                ST_ORPHAN -> if (len[s] < orphanLen) { orphanLen = len[s]; orphan = s }
                else -> if (len[s] < victimLen) { victimLen = len[s]; victim = s }
            }
        }
        // Prefer an empty slot, then the least-visible orphan, and only as a last
        // resort steal from a live vehicle (there are more vehicles than ribbons).
        val s = if (free >= 0) free else if (orphan >= 0) orphan else victim
        ownerId[s] = id
        ownerState[s] = ST_LIVE
        head[s] = 0
        len[s] = 0
        lastPush[s] = clock
        return s
    }

    /** See Particles.vtx — DynamicMesh.push() is a vararg and would allocate per vertex. */
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
}
