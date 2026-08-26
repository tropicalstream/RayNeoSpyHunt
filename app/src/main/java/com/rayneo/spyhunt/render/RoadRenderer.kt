package com.rayneo.spyhunt.render

import android.opengl.GLES30 as GL
import com.rayneo.spyhunt.core.Attr
import com.rayneo.spyhunt.core.DynamicMesh
import com.rayneo.spyhunt.core.M4
import com.rayneo.spyhunt.core.Program
import com.rayneo.spyhunt.game.GameState
import com.rayneo.spyhunt.game.RenderStyle
import com.rayneo.spyhunt.game.Surface
import kotlin.math.sin

/**
 * Builds the road ribbon from [GameState.road] each frame, plus the roadside
 * scenery scattered alongside it.
 *
 * Geometry is emitted in player-relative space (world Z minus the player's Z),
 * matching the convention used by particles, trails and the HUD, so float
 * precision stays tight no matter how far the player has driven.
 */
class RoadRenderer {

    // aPos(3), aUv(2), aSurf(1) — see Shaders.ROAD_VS
    private val mesh = DynamicMesh(
        maxVerts = GameState.ROAD_SLICES * 4 + 8,
        attrs = listOf(Attr("aPos", 3), Attr("aUv", 2), Attr("aSurf", 1)),
        maxIdx = GameState.ROAD_SLICES * 12 + 24
    )

    private val prog = Program(Shaders.ROAD_VS, Shaders.ROAD_FS, "road")
    private val progVintage = Program(VintageShaders.ROAD_VS, VintageShaders.ROAD_FS, "roadVintage")

    /**
     * The verge either side of the tarmac. Vintage only — in neon the surround is
     * left black, which on this display means the wearer's real floor shows
     * through, and that reads far better than a painted-on shoulder.
     */
    private val groundMesh = DynamicMesh(
        maxVerts = GameState.ROAD_SLICES * 2 + 4,
        attrs = listOf(Attr("aPos", 3), Attr("aUv", 2)),
        maxIdx = GameState.ROAD_SLICES * 6 + 12
    )
    private val progGround = Program(VintageShaders.GROUND_VS, VintageShaders.GROUND_FS, "ground")

    /** Vertices emitted by the last [build] — a cheap "is the road there?" probe. */
    val vertCount: Int get() = mesh.vertCount

    private companion object {
        /** Half-extent of the verge strip, metres. Comfortably past the frame edge. */
        const val GROUND_HALF = 46f
        /** Dropped below the tarmac so the two coplanar strips cannot z-fight. */
        const val GROUND_Y = -0.06f
    }

    /** Scenery placement is derived from Z so props never pop or drift. */
    private val sceneryModel = M4.identity()

    fun build(state: GameState) {
        buildGround(state)
        mesh.reset()
        val originZ = state.player.z

        var prevValid = false
        var pL0 = 0; var pR0 = 0     // previous slice's left/right vertex indices (band 0)
        var pL1 = 0; var pR1 = 0     // band 1, only when forked

        for (i in 0 until GameState.ROAD_SLICES) {
            val s = state.road[i]
            val z = s.z - originZ
            val surf = if (s.surface == Surface.WATER) 1f else 0f

            // Bank raises the outer edge; sin() of a small angle is fine here.
            val bankLift = sin(s.bank) * s.halfWidth

            if (!s.hasFork) {
                if (!mesh.hasRoom(2, 6)) break
                val lx = s.centerX - s.halfWidth
                val rx = s.centerX + s.halfWidth
                val l = mesh.vertCount
                mesh.push(lx, -bankLift, z, -1f, s.z, surf)
                mesh.push(rx, bankLift, z, 1f, s.z, surf)
                val r = l + 1

                if (prevValid) {
                    mesh.pushIndex(pL0); mesh.pushIndex(pR0); mesh.pushIndex(r)
                    mesh.pushIndex(pL0); mesh.pushIndex(r); mesh.pushIndex(l)
                }
                pL0 = l; pR0 = r
                // Collapse the second band onto the first so a fork ending mid-run
                // does not stitch triangles to stale indices.
                pL1 = l; pR1 = r
                prevValid = true
            } else {
                if (!mesh.hasRoom(4, 12)) break
                // Two carriageways separated by a central island of half-width forkGap.
                val outerL = s.centerX - s.halfWidth
                val innerL = s.centerX - s.forkGap
                val innerR = s.centerX + s.forkGap
                val outerR = s.centerX + s.halfWidth

                val a = mesh.vertCount
                mesh.push(outerL, -bankLift, z, -1f, s.z, surf)
                mesh.push(innerL, 0f, z, 1f, s.z, surf)
                mesh.push(innerR, 0f, z, -1f, s.z, surf)
                mesh.push(outerR, bankLift, z, 1f, s.z, surf)
                val b = a + 1; val c = a + 2; val d = a + 3

                if (prevValid) {
                    mesh.pushIndex(pL0); mesh.pushIndex(pR0); mesh.pushIndex(b)
                    mesh.pushIndex(pL0); mesh.pushIndex(b); mesh.pushIndex(a)
                    mesh.pushIndex(pL1); mesh.pushIndex(pR1); mesh.pushIndex(d)
                    mesh.pushIndex(pL1); mesh.pushIndex(d); mesh.pushIndex(c)
                }
                pL0 = a; pR0 = b
                pL1 = c; pR1 = d
                prevValid = true
            }
        }
    }

    /** Builds the verge strip. Cheap enough to keep current even in neon. */
    private fun buildGround(state: GameState) {
        groundMesh.reset()
        val originZ = state.player.z
        var prev = -1
        for (i in 0 until GameState.ROAD_SLICES) {
            if (!groundMesh.hasRoom(2, 6)) break
            val s = state.road[i]
            val z = s.z - originZ
            val l = groundMesh.vertCount
            groundMesh.push(s.centerX - GROUND_HALF, GROUND_Y, z, -1f, s.z)
            groundMesh.push(s.centerX + GROUND_HALF, GROUND_Y, z, 1f, s.z)
            if (prev >= 0) {
                groundMesh.pushIndex(prev); groundMesh.pushIndex(prev + 1); groundMesh.pushIndex(l + 1)
                groundMesh.pushIndex(prev); groundMesh.pushIndex(l + 1); groundMesh.pushIndex(l)
            }
            prev = l
        }
    }

    fun draw(state: GameState, viewProj: FloatArray) {
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthMask(true)
        GL.glDisable(GL.GL_BLEND)
        GL.glDisable(GL.GL_CULL_FACE)

        val vintage = state.style == RenderStyle.VINTAGE

        // Verge first: it sits below the tarmac and the road overdraws it.
        if (vintage) {
            progGround.use()
            progGround.uMat4("uMVP", viewProj)
            progGround.u1f("uTime", state.time)
            progGround.u1f("uFogStart", 62f)
            progGround.u1f("uFogEnd", 115f)
            groundMesh.flushAndDraw()
        }

        val prog = if (vintage) progVintage else this.prog
        prog.use()
        prog.uMat4("uMVP", viewProj)
        prog.u1f("uTime", state.time)
        prog.u3f("uEdgeCol", 0.15f, 0.95f, 1.0f)
        prog.u3f("uLaneCol", 1.0f, 0.72f, 0.16f)
        // Vintage needs its own water grade. The neon value pushed through the
        // vintage road shader peaks near full blue after gamma and posterisation —
        // a saturated near-max slab covering the whole carriageway, brighter than
        // anything else in the style and a lot of light to put into a waveguide.
        // This value is chosen so the three quantised wave bands still land in
        // three DISTINCT buckets after posterise; dimming it much further collapses
        // two of them together and the surface goes flat.
        if (vintage) prog.u3f("uWaterCol", 0.030f, 0.140f, 0.420f)
        else prog.u3f("uWaterCol", 0.06f, 0.36f, 0.85f)
        // The camera's downtilt puts the top of the frame at ~80 m of local Z, so the
        // fade has to reach past that or the road visibly stops in mid-air well short
        // of the frame edge.
        prog.u1f("uFogStart", 62f)
        prog.u1f("uFogEnd", 115f)
        mesh.flushAndDraw()
    }

    /**
     * Roadside props. Positions come from a hash of the slot index, so a given
     * stretch of road always grows the same scenery — no popping when the
     * generation window slides forward.
     */
    fun drawScenery(state: GameState, viewProj: FloatArray, scene: Program) {
        val originZ = state.player.z
        val spacing = 26f
        val first = kotlin.math.floor((state.player.z - 30f) / spacing).toInt()
        val count = 7

        scene.use()
        scene.u1f("uFlash", 0f)
        scene.u1f("uFade", 1f)

        for (k in 0 until count) {
            val slot = first + k
            val zWorld = slot * spacing
            val zLocal = zWorld - originZ
            if (zLocal < -30f || zLocal > 110f) continue

            val n = slotHash(slot)

            // Nearest generated slice at this Z, without allocating an iterator.
            var cx = 0f
            var hw = 7f
            var water = false
            for (j in 0 until GameState.ROAD_SLICES) {
                val sl = state.road[j]
                if (sl.z >= zWorld) {
                    cx = sl.centerX; hw = sl.halfWidth; water = sl.surface == Surface.WATER
                    break
                }
            }

            for (side in 0..1) {
                val sgn = if (side == 0) -1f else 1f
                // Set well back from the kerb. Close scenery is the single easiest way
                // to wreck readability here: it is bright, it is large in frame, and it
                // competes with the traffic the player actually has to react to.
                val off = hw + 11f + (n * 4f + 6f)
                val x = cx + sgn * off
                val mesh = when {
                    water -> Meshes.buoy
                    n > 0.72f -> Meshes.building   // rare; they are the bulkiest prop
                    n > -0.15f -> Meshes.pylon
                    else -> Meshes.tree
                }
                val yaw = if (side == 0) 90f else -90f
                val s = 0.62f + n * 0.18f
                val m = M4.trs(x, 0f, zLocal, yaw, s, s, s)
                scene.uMat4("uModel", m)
                scene.uMat4("uNrmMat", M4.normalMatrix(m))
                scene.uMat4("uMVP", M4.mul(viewProj, m))
                // Held well below the vehicles' brightness so the bloom budget goes to
                // things that matter. Scenery is there for speed cues and depth, not
                // to be looked at.
                scene.u3f("uTint", 0.38f, 0.40f, 0.46f)
                mesh.draw()
            }
        }
    }

    /** Stable per-slot hash in -1..1, so scenery is deterministic along the road. */
    private fun slotHash(slot: Int): Float {
        var v = slot * 374761393 + 668265263
        v = (v xor (v shr 13)) * 1274126177
        return ((v xor (v shr 16)) and 0x7FFFFFF) / 67108864f - 1f
    }

    fun release() {
        mesh.release()
        groundMesh.release()
        prog.release()
        progVintage.release()
        progGround.release()
    }
}
