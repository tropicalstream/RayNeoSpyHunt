package com.rayneo.spyhunt.game

import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.fbm
import com.rayneo.spyhunt.core.lerp
import com.rayneo.spyhunt.core.smoothstep
import com.rayneo.spyhunt.core.valueNoise
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The road is a **pure function of Z**. Nothing in here accumulates, so the same
 * Z always yields the same slice — the strip can be regenerated from scratch
 * every frame without the geometry crawling, and the simulation and renderer
 * cannot possibly disagree about where the tarmac is.
 *
 * Structure is two layers:
 *  - a continuous low-frequency wander (fbm) that gives gentle sweeping curves
 *    everywhere, and
 *  - fixed-length *sections* along Z, each hashed to one set-piece type
 *    (narrowing / fork with a central island / water crossing). Set-pieces fade
 *    in and out through a smooth envelope that is exactly zero at both section
 *    boundaries, so neighbouring sections always join seamlessly.
 *
 * The `level` parameter is accepted on every sampler for API symmetry, but the
 * topology deliberately derives its difficulty from Z instead. Level is a
 * counter that ticks over mid-drive; if the road shape depended on it the whole
 * strip — including the stretch already behind the player — would visibly pop
 * on every level-up. Z-derived difficulty is equivalent (level advances with
 * distance) and continuous.
 */
object RoadTopology {

    /** Metres of road per set-piece section. */
    const val SECTION_LEN = 240f

    /** Metres between level-ups; mirrors [World.LEVEL_DISTANCE]. */
    const val LEVEL_DISTANCE = 2000f

    // --- curve shaping -------------------------------------------------------
    // CURVE_K sets the base wavelength (1/k metres). CURVE_AMP is bounded by a
    // hard playability constraint: |dCenterX/dz| * speed must stay below the
    // player's lateral authority (~16 m/s at top speed) or the road would walk
    // sideways faster than the car can follow and become impossible to hold.
    private const val CURVE_K = 0.0016f      // ~625 m base wavelength
    private const val CURVE_AMP = 18f
    private const val WIGGLE_K = 0.009f
    private const val WIGGLE_AMP = 1.0f

    // Scaled with ROAD_HALF_WIDTH_DEFAULT so a pinch stays the same *proportional*
    // squeeze. Widening the road without moving these would make narrow sections
    // read as far more violent than they were tuned to be.
    private const val NARROW_HALF = 4.0f
    private const val MIN_HALF = 3.3f
    private const val FORK_WIDEN = 1.2f
    private const val FORK_ISLAND = 3.0f
    private const val WATER_WIDEN = 3.0f

    private const val BANK_SCALE = 220f
    private const val BANK_MAX = 0.30f
    /** Finite-difference step for curvature. Small vs. the finest curve octave. */
    private const val BANK_H = 11f

    private const val SEC_PLAIN = 0
    private const val SEC_NARROW = 1
    private const val SEC_FORK = 2
    private const val SEC_WATER = 3

    /**
     * Sections per water crossing. A single 240 m section is barely 2 seconds
     * at cruising speed — less time than the car-to-boat morph itself takes —
     * so water is laid down in multi-section runs to make the crossing an
     * actual set-piece rather than a flicker.
     */
    private const val WATER_RUN = 3
    private const val NO_RUN = Int.MIN_VALUE
    /** Scripted first crossing, so every run gets to be a boat early on. */
    private const val FIRST_WATER_ANCHOR = 6
    /** Metres of shoreline ramp at each end of a water run. */
    private const val SHORE_IN = 20f
    private const val SHORE_OUT = 110f

    // ---------------------------------------------------------------------
    //  public samplers
    // ---------------------------------------------------------------------

    /**
     * Fills [GameState.road] with [GameState.ROAD_SLICES] slices starting near
     * `player.z - ROAD_BEHIND`.
     *
     * The start is snapped to a fixed multiple of [GameState.ROAD_SLICE_SPACING]
     * rather than tracking the player exactly: slice Zs then stay pinned to a
     * world-space lattice as the player moves, so the road's vertices do not
     * slide under its own texturing and stripe phase every frame.
     */
    fun generate(state: GameState) {
        val lvl = state.level
        val spacing = GameState.ROAD_SLICE_SPACING
        val z0 = floor((state.player.z - GameState.ROAD_BEHIND) / spacing) * spacing
        for (i in 0 until GameState.ROAD_SLICES) {
            val s = state.road[i]
            val z = z0 + i * spacing
            val gap = sampleForkGap(z, lvl)
            s.z = z
            s.centerX = sampleCenterX(z, lvl)
            s.halfWidth = sampleHalfWidth(z, lvl)
            s.surface = sampleSurface(z, lvl)
            s.hasFork = gap > 0.05f
            s.forkGap = gap
            s.bank = sampleBank(z, lvl)
        }
    }

    /** Lateral position of the road's centreline at [z]. */
    fun sampleCenterX(z: Float, level: Int): Float {
        // Curve amplitude grows with distance, not with `level` — see class KDoc.
        val amp = CURVE_AMP + min(6f, max(0f, z) / LEVEL_DISTANCE) * 1.1f
        return fbm(z * CURVE_K, 3, 7) * amp + valueNoise(z * WIGGLE_K, 23) * WIGGLE_AMP
    }

    /** Half-width of the drivable surface at [z], measured from the centreline. */
    fun sampleHalfWidth(z: Float, level: Int): Float {
        val sec = sectionOf(z)
        val type = sectionType(sec)
        val env = envAt(z, sec, type)
        // Slow breathing so even "plain" road never looks like a constant ribbon.
        var hw = ROAD_HALF_WIDTH_DEFAULT + valueNoise(z * 0.0071f, 91) * 0.7f
        when (type) {
            SEC_NARROW -> hw = lerp(hw, NARROW_HALF, env)
            SEC_FORK -> hw += env * FORK_WIDEN     // a little extra room around the island
            SEC_WATER -> hw += env * WATER_WIDEN   // rivers run wider than the road
            else -> {}
        }
        return max(MIN_HALF, hw)
    }

    /** ROAD or WATER at [z]. Water only exists inside a water set-piece. */
    fun sampleSurface(z: Float, level: Int): Surface {
        val sec = sectionOf(z)
        val anchor = waterRunAnchor(sec)
        if (anchor == NO_RUN) return Surface.ROAD
        // Threshold the envelope: the surface flag is discrete, but because the
        // envelope is a fixed function of Z the shoreline sits at a stable Z.
        return if (waterEnv(z, anchor) >= 0.5f) Surface.WATER else Surface.ROAD
    }

    /**
     * Half-width of the undrivable central island at [z], or 0 where the road
     * does not fork. The drivable lanes are `forkGap < |x - centerX| < halfWidth`.
     */
    fun sampleForkGap(z: Float, level: Int): Float {
        val sec = sectionOf(z)
        if (sectionType(sec) != SEC_FORK) return 0f
        return sectionEnv(z, sec) * FORK_ISLAND
    }

    /** Length in metres of the water crossing at [z], or 0 on dry land. */
    fun waterRunLength(z: Float): Float =
        if (waterRunAnchor(sectionOf(z)) == NO_RUN) 0f else WATER_RUN * SECTION_LEN

    /**
     * Roll of the road surface at [z], radians, right-hand rule about the +Z
     * (travel) axis. A right-hand curve (centreline accelerating toward +X)
     * produces a negative bank, i.e. the right-hand kerb drops — the car leans
     * into the turn.
     */
    fun sampleBank(z: Float, level: Int): Float {
        val a = sampleCenterX(z - BANK_H, level)
        val b = sampleCenterX(z, level)
        val c = sampleCenterX(z + BANK_H, level)
        val curvature = (a - 2f * b + c) / (BANK_H * BANK_H)
        return clamp(-curvature * BANK_SCALE, -BANK_MAX, BANK_MAX)
    }

    // ---------------------------------------------------------------------
    //  section machinery
    // ---------------------------------------------------------------------

    private fun sectionOf(z: Float): Int = floor(z / SECTION_LEN).toInt()

    /**
     * 0 at both ends of the section, 1 across the middle. Guarantees C1 joins
     * between sections whatever types the neighbours picked.
     *
     * The ramps span ~58 m rather than something tighter: a narrowing pinches
     * the road by over 3 m, and at 85 m/s a shorter ramp would slam shut in
     * under half a second — readable on paper, unreactable in the headset.
     */
    private fun sectionEnv(z: Float, sec: Int): Float {
        val t = (z - sec * SECTION_LEN) / SECTION_LEN
        return smoothstep(0.10f, 0.34f, t) * (1f - smoothstep(0.66f, 0.90f, t))
    }

    /**
     * One set-piece per section, chosen from a single hash draw so the land
     * types are mutually exclusive. Probabilities rise with the section's own
     * distance, so later sections are busier without any dependence on mutable
     * game state. Water wins outright — a fork in the middle of a river is not
     * a thing.
     */
    private fun sectionType(sec: Int): Int {
        if (sec < 2) return SEC_PLAIN            // calm opening stretch to settle in
        if (waterRunAnchor(sec) != NO_RUN) return SEC_WATER
        val d = 1f + max(0f, sec * SECTION_LEN) / LEVEL_DISTANCE
        val pFork = min(0.26f, 0.05f + 0.040f * (d - 1f))
        val pNarrow = min(0.34f, 0.10f + 0.050f * (d - 1f))
        val u = hash01(sec, 0x1F)
        if (u < pFork) return SEC_FORK
        if (u < pFork + pNarrow) return SEC_NARROW
        return SEC_PLAIN
    }

    /** Water sections span a whole run, so they need the run-wide envelope. */
    private fun envAt(z: Float, sec: Int, type: Int): Float =
        if (type == SEC_WATER) waterEnv(z, waterRunAnchor(sec)) else sectionEnv(z, sec)

    /**
     * Anchor section of the water run covering [sec], or [NO_RUN].
     *
     * Runs are pinned to every [WATER_RUN]-th section so two of them can never
     * overlap. That is what keeps the run extent — and therefore the shoreline
     * envelope — a plain function of Z with no search or accumulated state.
     */
    private fun waterRunAnchor(sec: Int): Int {
        if (sec < 2) return NO_RUN
        val anchor = (sec / WATER_RUN) * WATER_RUN
        if (anchor < 2) return NO_RUN
        // The boat transformation is the signature beat of the whole game, so
        // the first crossing is placed by hand at ~1.4 km — roughly twenty
        // seconds in. Left purely to the hash it could be ten kilometres away
        // and most players would never see it.
        if (anchor == FIRST_WATER_ANCHOR) return anchor
        val d = 1f + anchor * SECTION_LEN / LEVEL_DISTANCE
        val p = min(0.24f, 0.10f + 0.030f * (d - 1f))
        return if (hash01(anchor, 0x5A) < p) anchor else NO_RUN
    }

    /** 0 on both banks, 1 across the open water. Zero at the run's boundaries. */
    private fun waterEnv(z: Float, anchor: Int): Float {
        val a = anchor * SECTION_LEN
        val b = a + WATER_RUN * SECTION_LEN
        return smoothstep(a + SHORE_IN, a + SHORE_OUT, z) *
            (1f - smoothstep(b - SHORE_OUT, b - SHORE_IN, z))
    }

    /** Integer hash to [0,1). Deterministic and allocation-free. */
    private fun hash01(n: Int, salt: Int): Float {
        var v = n * 0x27D4EB2D + salt * 0x165667B1
        v = v xor (v ushr 15); v *= 0x2C1B3C6D
        v = v xor (v ushr 12); v *= 0x297A2D39
        v = v xor (v ushr 15)
        return (v ushr 8).toFloat() / 16777216f
    }
}
