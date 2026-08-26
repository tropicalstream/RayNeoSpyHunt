package com.rayneo.spyhunt.render

import com.rayneo.spyhunt.core.DEG2RAD
import com.rayneo.spyhunt.core.DynamicMesh
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.cosf
import com.rayneo.spyhunt.core.sinf
import com.rayneo.spyhunt.game.GameState
import com.rayneo.spyhunt.game.Phase
import com.rayneo.spyhunt.game.RenderStyle
import com.rayneo.spyhunt.game.WeaponKind
import kotlin.math.ceil

/**
 * The vector HUD.
 *
 * LAYOUT SPACE: elements are placed in (x, v) where x is metres across the road
 * (0 = centreline) and v runs up a plane that leans back [LEAN] degrees from
 * vertical, anchored just above the road surface a few metres ahead of the car.
 * v is converted to a world (y, z) pair by [rowY]/[rowZ]. Coordinates are already
 * player-relative — the same convention as Particles/Trails after originZ has been
 * subtracted — because the renderer applies the board transform on top.
 *
 * Each element is drawn as a small vertical billboard planted at its own row's z
 * rather than lying in the leaning plane. VectorFont strokes are planar by design,
 * and from a raised viewpoint a standing glyph is far more legible than a glyph
 * foreshortened into the road; the lean survives in how the rows are distributed.
 *
 * Readouts hug the left and right edges so the middle of the board — where the
 * road is — stays clear.
 *
 * Colour code: cyan = neutral, amber = warning/value, magenta = special weapon,
 * red = danger. Everything is HDR and additive; alpha is intensity, not coverage.
 */
class Hud {

    private companion object {
        /** Panel lean from vertical, so it faces a viewpoint that sits above the board. */
        const val LEAN = 20f
        val LEAN_C = cosf(LEAN * DEG2RAD)
        val LEAN_S = sinf(LEAN * DEG2RAD)

        const val BASE_Y = 0.35f     // hovers just clear of the road surface
        const val BASE_Z = 3.0f      // just ahead of the player's nose
        const val HALF_W = 11.0f     // road is 12 m wide, so the columns clear it
        const val TOP_V = 13.4f

        const val S_LABEL = 0.80f
        const val S_VALUE = 1.28f
        const val S_BIG = 2.60f
        const val S_HUGE = 4.20f

        // ---- start-screen mode list ----
        /** Baseline of the first mode row. */
        const val MENU_TOP = 9.4f
        /** Baseline the last row may not sink below — the instructions live under it. */
        const val MENU_BOTTOM = 5.0f
        /** Preferred row spacing; shrinks if the list grows past what the band holds. */
        const val MENU_PITCH = 3.2f
        /** Left edge of the mode names; the selection caret sits in the gutter left of it. */
        const val MENU_X = -8.0f
        const val S_MENU = 1.70f
        /** Segments in the busiest glyph — the per-string capacity estimate. */
        const val MAX_GLYPH_STROKES = 13

        // Stroke weight as a fraction of cap height. Small text needs a heavier
        // stroke to survive the waveguide; big text would go blobby with the same.
        const val TH_LABEL = 0.14f
        const val TH_VALUE = 0.12f
        const val TH_BIG = 0.10f

        val CYAN = floatArrayOf(0.30f, 2.70f, 3.40f)
        val AMBER = floatArrayOf(3.60f, 1.50f, 0.14f)
        val MAGENTA = floatArrayOf(3.30f, 0.35f, 3.10f)
        val RED = floatArrayOf(3.80f, 0.40f, 0.22f)
        val WHITE = floatArrayOf(2.80f, 3.00f, 3.30f)
        val GREEN = floatArrayOf(0.50f, 3.40f, 1.10f)

        const val MPS_TO_MPH = 2.2369f
        const val COUNTDOWN_SECS = 3f
    }

    /** Scratch for allocation-free number formatting. */
    private val buf = CharArray(32)

    /**
     * Appends the whole HUD to [mesh] (7 floats/vert: pos3, rgba4). The caller owns
     * reset()/flushAndDraw(). [calibrationHint] is shown large and centred while the
     * game is in [Phase.CALIBRATING].
     */
    fun build(mesh: DynamicMesh, state: GameState, calibrationHint: String?) {
        if (mesh.floatsPerVert != 7 || mesh.cpuIdx.size < 6) return
        when (state.phase) {
            Phase.CALIBRATING -> buildCalibration(mesh, state, calibrationHint)
            Phase.ATTRACT -> buildAttract(mesh, state)
            Phase.COUNTDOWN -> { buildPlaying(mesh, state, 0.55f); buildCountdown(mesh, state) }
            Phase.PLAYING -> buildPlaying(mesh, state, 1f)
            Phase.DYING -> buildPlaying(mesh, state, 0.55f)
            Phase.GAME_OVER -> { buildPlaying(mesh, state, 0.40f); buildGameOver(mesh, state) }
        }
    }

    // ---- in-game readouts ----------------------------------------------------

    private fun buildPlaying(mesh: DynamicMesh, st: GameState, dim: Float) {
        // Border flashes red on the frames the player is taking damage.
        val hurt = st.player.flashUntil > st.time
        chrome(mesh, if (hurt) RED else CYAN, (if (hurt) 1.5f else 0.65f) * dim)

        val lx = -HALF_W
        val rx = HALF_W

        // ---- top left: score ------------------------------------------------
        text(mesh, "SCORE", lx, TOP_V - 1.0f, S_LABEL, CYAN, 0.50f * dim, TH_LABEL)
        var n = fmtLong(st.score, 6)
        num(mesh, n, lx, TOP_V - 2.7f, S_VALUE, CYAN, 1.25f * dim, TH_VALUE)

        // ---- top right: level and distance ----------------------------------
        textR(mesh, "LEVEL", rx, TOP_V - 1.0f, S_LABEL, CYAN, 0.50f * dim, TH_LABEL)
        n = fmtLong(st.level.toLong(), 2)
        numR(mesh, n, rx, TOP_V - 2.7f, S_VALUE, AMBER, 1.25f * dim, TH_VALUE)

        textR(mesh, "KM", rx, TOP_V - 4.6f, S_LABEL, CYAN, 0.45f * dim, TH_LABEL)
        n = fmtFixed(st.distance * 0.001f, 2)
        numR(mesh, n, rx, TOP_V - 6.0f, S_LABEL * 1.25f, CYAN, 0.95f * dim, TH_VALUE)

        // ---- left column: weapon + gun heat ---------------------------------
        val special = st.weapon != WeaponKind.MACHINE_GUN
        val wcol = if (special) MAGENTA else CYAN
        val wname = when (st.weapon) {
            WeaponKind.MACHINE_GUN -> "GUNS"
            WeaponKind.OIL_SLICK -> "OIL"
            WeaponKind.SMOKE_SCREEN -> "SMOKE"
            WeaponKind.MISSILE -> "MISSILE"
        }
        text(mesh, "WEAPON", lx, TOP_V - 4.6f, S_LABEL, CYAN, 0.45f * dim, TH_LABEL)
        text(mesh, wname, lx, TOP_V - 6.0f, S_LABEL * 1.25f, wcol, 1.2f * dim, TH_VALUE)
        if (special && st.weaponAmmo > 0) {
            val ax = lx + VectorFont.measure(wname, S_LABEL * 1.25f) + 0.5f
            text(mesh, "X", ax, TOP_V - 6.0f, S_LABEL, wcol, 0.7f * dim, TH_LABEL)
            n = fmtLong(st.weaponAmmo.toLong(), 1)
            num(mesh, n, ax + VectorFont.measure("X", S_LABEL) + 0.15f, TOP_V - 6.0f,
                S_LABEL * 1.25f, wcol, 1.2f * dim, TH_VALUE)
        }

        // Gun heat: cyan while cool, red once it is about to cut out.
        val heat = clamp(st.gunHeat, 0f, 1f)
        val hcol = if (heat > 0.75f) RED else if (heat > 0.45f) AMBER else CYAN
        bar(mesh, lx, TOP_V - 7.5f, 4.6f, 0.42f, heat, hcol, (0.5f + 0.9f * heat) * dim)

        // ---- bottom left: lives ---------------------------------------------
        text(mesh, "LIVES", lx, 2.7f, S_LABEL, CYAN, 0.45f * dim, TH_LABEL)
        val shown = if (st.lives > 5) 1 else st.lives
        // Last life pulses so peripheral vision catches it.
        val lowPulse = if (st.lives <= 1) 0.55f + 0.75f * (0.5f + 0.5f * sinf(st.time * 7f)) else 1f
        val lcol = if (st.lives <= 1) RED else GREEN
        for (i in 0 until shown) {
            carIcon(mesh, lx + 0.55f + i * 1.45f, 1.35f, 1.15f, lcol, 1.1f * lowPulse * dim)
        }
        if (st.lives > 5) {
            text(mesh, "X", lx + 1.6f, 1.0f, S_LABEL, GREEN, 0.8f * dim, TH_LABEL)
            n = fmtLong(st.lives.toLong(), 1)
            num(mesh, n, lx + 2.5f, 1.0f, S_VALUE, GREEN, 1.1f * dim, TH_VALUE)
        }

        // ---- bottom right: speed --------------------------------------------
        textR(mesh, "MPH", rx, 2.7f, S_LABEL, CYAN, 0.45f * dim, TH_LABEL)
        val mph = st.speed * MPS_TO_MPH
        n = fmtLong(mph.toLong(), 1)
        val fast = st.speed > 58f
        numR(mesh, n, rx, 1.0f, S_VALUE * 1.35f, if (fast) AMBER else CYAN,
            (if (fast) 1.6f else 1.15f) * dim, TH_VALUE)

        // ---- centre-low: transient callouts ---------------------------------
        if (st.onWater) {
            textC(mesh, "MARINE", 0f, 0.6f, S_LABEL, CYAN, 0.9f * dim, TH_LABEL)
        }
        if (st.combo > 1 && st.comboTimer > 0f) {
            // Pulse rate rises with the chain so a long combo feels urgent.
            val p = 0.5f + 0.5f * sinf(st.time * (7f + st.combo.toFloat()))
            val w = VectorFont.measure("COMBO X", S_VALUE)
            text(mesh, "COMBO X", -w * 0.5f - 0.6f, 4.4f, S_VALUE, MAGENTA,
                (0.9f + 1.2f * p) * dim, TH_VALUE)
            n = fmtLong(st.combo.toLong(), 1)
            num(mesh, n, w * 0.5f - 0.4f, 4.4f, S_VALUE * 1.3f, MAGENTA,
                (1.1f + 1.4f * p) * dim, TH_VALUE)
        }
        val toGo = st.nextExtraLifeAt - st.score
        if (toGo in 1L..4000L) {
            val p = 0.5f + 0.5f * sinf(st.time * 5.5f)
            textC(mesh, "EXTRA LIFE IN", 0f, TOP_V + 1.4f, S_LABEL, AMBER,
                (0.5f + 0.8f * p) * dim, TH_LABEL)
            n = fmtLong(toGo, 1)
            numC(mesh, n, 0f, TOP_V + 0.1f, S_VALUE, AMBER, (0.7f + 1.1f * p) * dim, TH_VALUE)
        }
    }

    // ---- phase overlays ------------------------------------------------------

    private fun buildCalibration(mesh: DynamicMesh, st: GameState, hint: String?) {
        chrome(mesh, CYAN, 0.8f)
        textC(mesh, "CALIBRATE", 0f, TOP_V - 3.2f, S_BIG, CYAN, 1.5f, TH_BIG)
        textCFit(mesh, hint ?: "HOLD STILL - CENTRING VIEW", 0f, TOP_V - 5.6f,
            S_VALUE, AMBER, 1.3f, TH_VALUE)

        // Slowly spinning reticle: a live element proves the tracker is running.
        val a = st.time * 0.9f
        val c = cosf(a); val s = sinf(a)
        val cx = 0f
        val y = rowY(4.6f); val z = rowZ(4.6f)
        val r = 1.9f
        val px0 = c * r; val py0 = s * r
        val px1 = -s * r; val py1 = c * r
        VectorFont.stroke(mesh, cx + px0, y + py0, cx + px1, y + py1, z, 0.09f, CYAN[0], CYAN[1], CYAN[2], 1.1f)
        VectorFont.stroke(mesh, cx + px1, y + py1, cx - px0, y - py0, z, 0.09f, CYAN[0], CYAN[1], CYAN[2], 1.1f)
        VectorFont.stroke(mesh, cx - px0, y - py0, cx - px1, y - py1, z, 0.09f, CYAN[0], CYAN[1], CYAN[2], 1.1f)
        VectorFont.stroke(mesh, cx - px1, y - py1, cx + px0, y + py0, z, 0.09f, CYAN[0], CYAN[1], CYAN[2], 1.1f)
        VectorFont.stroke(mesh, cx - 0.7f, y, cx + 0.7f, y, z, 0.09f, WHITE[0], WHITE[1], WHITE[2], 1.6f)
        VectorFont.stroke(mesh, cx, y - 0.7f, cx, y + 0.7f, z, 0.09f, WHITE[0], WHITE[1], WHITE[2], 1.6f)
    }

    /**
     * Start screen: title, the mode list, and the temple-pad instructions.
     *
     * The highlighted row has to survive a 640x480 waveguide, so selection is
     * carried by three redundant cues — a caret in the gutter, a large brightness
     * step, and a pulse — never by hue alone.
     */
    private fun buildAttract(mesh: DynamicMesh, st: GameState) {
        chrome(mesh, CYAN, 0.7f)
        val p = 0.5f + 0.5f * sinf(st.time * 3.0f)
        textCFit(mesh, "SPY HUNT", 0f, TOP_V - 1.0f, S_HUGE, CYAN, 1.8f, TH_BIG)

        // Last run's score, on a single line so it cannot crowd the mode list.
        if (st.score > 0L) {
            val n = fmtLong(st.score, 1)
            val lw = VectorFont.measure("LAST SCORE", S_LABEL)
            val nw = VectorFont.measureCount(n, S_LABEL * 1.3f)
            val x0 = -(lw + 0.8f + nw) * 0.5f
            text(mesh, "LAST SCORE", x0, MENU_TOP + 1.6f, S_LABEL, CYAN, 0.5f, TH_LABEL)
            num(mesh, n, x0 + lw + 0.8f, MENU_TOP + 1.6f, S_LABEL * 1.3f, AMBER, 1.1f, TH_VALUE)
        }

        // ---- mode list -------------------------------------------------------
        val modes = RenderStyle.ordered
        val n = modes.size
        if (n > 0) {
            // menuIndex free-runs with the swipe, so wrap it instead of trusting it.
            val sel = ((st.menuIndex % n) + n) % n
            // Rows tighten, and the type with them, if the list ever outgrows the band.
            val pitch = if (n > 1) minOf(MENU_PITCH, (MENU_TOP - MENU_BOTTOM) / (n - 1)) else 0f
            val ms = if (n > 1) minOf(S_MENU, pitch * 0.55f) else S_MENU
            // Room reserved on the right for the trailing caret.
            val maxW = HALF_W - 1.7f - MENU_X
            for (i in 0 until n) {
                val name = modes[i].title
                if (!roomFor(mesh, name.length + 8)) break
                val v = MENU_TOP - i * pitch
                val on = i == sel
                val w = VectorFont.measure(name, ms)
                val sz = if (w > maxW && w > 0f) ms * (maxW / w) else ms
                text(
                    mesh, name, MENU_X, v, sz, if (on) AMBER else CYAN,
                    if (on) 1.5f + 0.9f * p else 0.42f, TH_VALUE
                )
                if (!on) continue

                // Caret pair: the left one is the brightest thing on the screen and
                // drifts a little with the pulse, so it reads even out of focus.
                val cv = v + sz * 0.42f
                val nudge = 0.22f * p
                caret(mesh, MENU_X - 1.35f - nudge, cv, sz * 0.62f, WHITE, 1.9f + 1.1f * p, 1f)
                caret(
                    mesh, MENU_X + VectorFont.measure(name, sz) + 1.35f + nudge, cv,
                    sz * 0.62f, AMBER, 1.0f + 0.8f * p, -1f
                )

                val blurb = modes[i].blurb
                if (roomFor(mesh, blurb.length)) {
                    textFit(
                        mesh, blurb, MENU_X + 0.3f, v - sz * 0.95f,
                        minOf(S_LABEL, sz * 0.5f), maxW - 0.3f, CYAN, 0.85f, TH_LABEL
                    )
                }
            }
        }

        // ---- controls ---------------------------------------------------------
        textCFit(mesh, "TAP TO START", 0f, 3.1f, S_VALUE, AMBER, 0.7f + 1.3f * p, TH_VALUE)
        textCFit(mesh, "SWIPE THE ARM TO CHANGE MODE", 0f, 1.95f, S_LABEL, CYAN, 0.6f, TH_LABEL)
        textCFit(mesh, "IN GAME - SWIPE STEERS - TAP FIRES", 0f, 0.85f, S_LABEL, CYAN, 0.45f, TH_LABEL)
        textCFit(mesh, "DOUBLE TAP FOR SPECIAL", 0f, -0.2f, S_LABEL, CYAN, 0.4f, TH_LABEL)
    }

    private fun buildCountdown(mesh: DynamicMesh, st: GameState) {
        val remain = COUNTDOWN_SECS - st.phaseTime
        if (remain > 0f) {
            val n = ceil(remain).toInt()
            // frac: 0 the instant the digit appears, ->1 as it ages. The digit pops
            // in large and settles, which reads as a beat even without audio.
            val frac = clamp(n.toFloat() - remain, 0f, 1f)
            val pop = 0.75f + 0.70f * (1f - frac)
            val len = fmtLong(n.toLong(), 1)
            numC(mesh, len, 0f, 5.4f, S_HUGE * pop, AMBER, 1.3f + 1.1f * (1f - frac), TH_BIG)
        } else {
            textCFit(mesh, "GO", 0f, 5.4f, S_HUGE, GREEN, 2.4f, TH_BIG)
        }
    }

    private fun buildGameOver(mesh: DynamicMesh, st: GameState) {
        val p = 0.5f + 0.5f * sinf(st.time * 2.4f)
        textCFit(mesh, "GAME OVER", 0f, TOP_V - 4.4f, S_BIG, RED, 1.6f + 0.7f * p, TH_BIG)
        textCFit(mesh, "FINAL SCORE", 0f, TOP_V - 6.6f, S_LABEL, CYAN, 0.8f, TH_LABEL)
        val n = fmtLong(st.score, 1)
        numC(mesh, n, 0f, TOP_V - 8.6f, S_VALUE * 1.5f, AMBER, 1.5f, TH_VALUE)
        // Two exits, and the long press is the only route back to mode selection —
        // so it has to be spelled out rather than discovered.
        textCFit(mesh, "TAP - RESTART SAME MODE", 0f, 2.6f, S_LABEL, CYAN, 0.5f + 0.9f * p, TH_LABEL)
        textCFit(mesh, "LONG PRESS - START SCREEN", 0f, 1.4f, S_LABEL, CYAN, 0.45f, TH_LABEL)
    }

    // ---- chrome --------------------------------------------------------------

    /** Four corner brackets. Cheap, unmistakably vector-arcade, and frames the board. */
    private fun chrome(mesh: DynamicMesh, c: FloatArray, a: Float) {
        val x = HALF_W + 0.7f
        val vLo = -0.5f
        val vHi = TOP_V + 2.6f
        bracket(mesh, -x, vLo, 1f, 1f, c, a)
        bracket(mesh, x, vLo, -1f, 1f, c, a)
        bracket(mesh, -x, vHi, 1f, -1f, c, a)
        bracket(mesh, x, vHi, -1f, -1f, c, a)
    }

    private fun bracket(
        mesh: DynamicMesh, x: Float, v: Float, sx: Float, sy: Float, c: FloatArray, a: Float
    ) {
        val y = rowY(v)
        val z = rowZ(v)
        val arm = 1.8f
        VectorFont.stroke(mesh, x, y, x + sx * arm, y, z, 0.11f, c[0], c[1], c[2], a)
        VectorFont.stroke(mesh, x, y, x, y + sy * arm, z, 0.11f, c[0], c[1], c[2], a)
    }

    /** Top-down car silhouette, nose toward +Y. Centred on ([cx], row [v]). */
    private fun carIcon(mesh: DynamicMesh, cx: Float, v: Float, s: Float, c: FloatArray, a: Float) {
        val y = rowY(v)
        val z = rowZ(v)
        val hw = 0.34f * s
        val hl = 0.58f * s
        val t = 0.11f * s
        val r = c[0]; val g = c[1]; val b = c[2]
        VectorFont.stroke(mesh, cx - hw, y - hl, cx + hw, y - hl, z, t, r, g, b, a)
        VectorFont.stroke(mesh, cx + hw, y - hl, cx + hw, y + hl * 0.35f, z, t, r, g, b, a)
        VectorFont.stroke(mesh, cx + hw, y + hl * 0.35f, cx + hw * 0.5f, y + hl, z, t, r, g, b, a)
        VectorFont.stroke(mesh, cx + hw * 0.5f, y + hl, cx - hw * 0.5f, y + hl, z, t, r, g, b, a)
        VectorFont.stroke(mesh, cx - hw * 0.5f, y + hl, cx - hw, y + hl * 0.35f, z, t, r, g, b, a)
        VectorFont.stroke(mesh, cx - hw, y + hl * 0.35f, cx - hw, y - hl, z, t, r, g, b, a)
        VectorFont.stroke(mesh, cx - hw * 0.7f, y, cx + hw * 0.7f, y, z, t * 0.8f, r, g, b, a * 0.8f)
    }

    /**
     * Selection caret centred on ([cx], row [v]), [s] tall-ish. [dir] is +1 for a
     * ">" pointing right and -1 for a "<". Two strokes: it stays a legible arrow
     * head at any size, which a glyph from the font would not at this weight.
     */
    private fun caret(
        mesh: DynamicMesh, cx: Float, v: Float, s: Float, c: FloatArray, a: Float, dir: Float
    ) {
        val y = rowY(v)
        val z = rowZ(v)
        val hx = 0.42f * s * dir
        val hy = 0.62f * s
        val t = 0.20f * s
        VectorFont.stroke(mesh, cx - hx, y + hy, cx + hx, y, z, t, c[0], c[1], c[2], a)
        VectorFont.stroke(mesh, cx + hx, y, cx - hx, y - hy, z, t, c[0], c[1], c[2], a)
    }

    /**
     * True while [chars] more characters are certain to fit. Every stroke is a quad
     * (4 verts, 6 indices) and the busiest glyph is [MAX_GLYPH_STROKES] strokes, so
     * this is a worst case: a mode list can grow without ever half-drawing a row.
     */
    private fun roomFor(mesh: DynamicMesh, chars: Int): Boolean =
        mesh.hasRoom(chars * MAX_GLYPH_STROKES * 4, chars * MAX_GLYPH_STROKES * 6)

    /** Outlined meter with a solid fill bar. Small and bright — never a dim wash. */
    private fun bar(
        mesh: DynamicMesh, x: Float, v: Float, w: Float, h: Float, fill: Float,
        c: FloatArray, a: Float
    ) {
        val y = rowY(v)
        val z = rowZ(v)
        VectorFont.frame(mesh, x, y, x + w, y + h, z, 0.06f, c[0], c[1], c[2], a * 0.5f)
        val f = clamp(fill, 0f, 1f)
        if (f > 0.015f) {
            VectorFont.rect(
                mesh, x + 0.08f, y + 0.08f, x + 0.08f + (w - 0.16f) * f, y + h - 0.08f, z,
                c[0], c[1], c[2], a
            )
        }
    }

    // ---- layout plane --------------------------------------------------------

    private fun rowY(v: Float) = BASE_Y + v * LEAN_C
    private fun rowZ(v: Float) = BASE_Z + v * LEAN_S

    // ---- text helpers --------------------------------------------------------

    private fun text(
        mesh: DynamicMesh, s: String, x: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) = VectorFont.draw(mesh, s, x, rowY(v), rowZ(v), size, c[0], c[1], c[2], a, th)

    private fun textR(
        mesh: DynamicMesh, s: String, right: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) = VectorFont.draw(
        mesh, s, right - VectorFont.measure(s, size), rowY(v), rowZ(v), size,
        c[0], c[1], c[2], a, th
    )

    private fun textC(
        mesh: DynamicMesh, s: String, cx: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) = VectorFont.draw(
        mesh, s, cx - VectorFont.measure(s, size) * 0.5f, rowY(v), rowZ(v), size,
        c[0], c[1], c[2], a, th
    )

    /** Left-aligned at [x], shrunk to [maxW] — mode names and blurbs vary in length. */
    private fun textFit(
        mesh: DynamicMesh, s: String, x: Float, v: Float, size: Float, maxW: Float,
        c: FloatArray, a: Float, th: Float
    ) {
        val w = VectorFont.measure(s, size)
        val sz = if (w > maxW && w > 0f) size * (maxW / w) else size
        text(mesh, s, x, v, sz, c, a, th)
    }

    /** Centred, shrunk to fit the board width — long calibration hints must not run off. */
    private fun textCFit(
        mesh: DynamicMesh, s: String, cx: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) {
        val maxW = HALF_W * 2f
        val w = VectorFont.measure(s, size)
        val sz = if (w > maxW && w > 0f) size * (maxW / w) else size
        textC(mesh, s, cx, v, sz, c, a, th)
    }

    private fun num(
        mesh: DynamicMesh, len: Int, x: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) = VectorFont.drawChars(mesh, buf, 0, len, x, rowY(v), rowZ(v), size, c[0], c[1], c[2], a, th)

    private fun numR(
        mesh: DynamicMesh, len: Int, right: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) = VectorFont.drawChars(
        mesh, buf, 0, len, right - VectorFont.measureCount(len, size), rowY(v), rowZ(v), size,
        c[0], c[1], c[2], a, th
    )

    private fun numC(
        mesh: DynamicMesh, len: Int, cx: Float, v: Float, size: Float,
        c: FloatArray, a: Float, th: Float
    ) = VectorFont.drawChars(
        mesh, buf, 0, len, cx - VectorFont.measureCount(len, size) * 0.5f, rowY(v), rowZ(v), size,
        c[0], c[1], c[2], a, th
    )

    // ---- allocation-free number formatting -----------------------------------

    /**
     * Renders [v] into [buf] starting at index 0, zero-padded to at least [pad]
     * digits; returns the length. String.format would allocate every frame, and the
     * HUD is rebuilt 60 times a second.
     */
    private fun fmtLong(v: Long, pad: Int): Int {
        var n = if (v < 0L) 0L else v
        var i = buf.size
        do {
            buf[--i] = '0' + (n % 10L).toInt()
            n /= 10L
        } while (n > 0L && i > 1)
        while (buf.size - i < pad && i > 0) buf[--i] = '0'
        val len = buf.size - i
        System.arraycopy(buf, i, buf, 0, len)
        return len
    }

    /** Fixed-point render of [v] with [decimals] places. Negatives clamp to zero. */
    private fun fmtFixed(v: Float, decimals: Int): Int {
        var scale = 1L
        for (d in 0 until decimals) scale *= 10L
        var rem = ((if (v < 0f) 0f else v) * scale + 0.5f).toLong()
        var i = buf.size
        for (d in 0 until decimals) {
            buf[--i] = '0' + (rem % 10L).toInt()
            rem /= 10L
        }
        if (decimals > 0) buf[--i] = '.'
        do {
            buf[--i] = '0' + (rem % 10L).toInt()
            rem /= 10L
        } while (rem > 0L && i > 1)
        val len = buf.size - i
        System.arraycopy(buf, i, buf, 0, len)
        return len
    }
}
