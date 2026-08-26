package com.rayneo.spyhunt.render

import com.rayneo.spyhunt.core.DynamicMesh
import kotlin.math.sqrt

/**
 * Stroke font and the shared thick-line primitive for all vector chrome.
 *
 * Glyphs are authored as line segments in a unit box (0..1 across, 0 = baseline,
 * 1 = cap height) and expanded at draw time into quads of the requested weight.
 * No bitmap, no Canvas, no texture: at 640x480 per eye through a waveguide, a
 * sampled glyph atlas turns to mush, whereas strokes stay crisp and bloom cleanly.
 *
 * Text is monospaced on purpose — a proportional score readout jitters horribly
 * when the digits change every frame.
 *
 * Vertex format: 7 floats/vert (pos3, rgba4), indexed triangles.
 */
object VectorFont {

    /** Horizontal pen advance per character, as a multiple of `size`. */
    const val ADVANCE = 0.86f

    /** Glyph box width as a multiple of `size` (height is exactly `size`). */
    private const val GW = 0.64f

    private const val N_GLYPHS = 46

    private val GLYPHS = Array(N_GLYPHS) { FloatArray(0) }

    init {
        // ---- letters ---------------------------------------------------------
        p('A', 0f, 0f, .5f, 1f, .5f, 1f, 1f, 0f, .17f, .34f, .83f, .34f)
        p(
            'B', 0f, 0f, 0f, 1f, 0f, 1f, .72f, 1f, .72f, 1f, 1f, .8f, 1f, .8f, .72f, .55f,
            .72f, .55f, 0f, .55f, .72f, .55f, 1f, .3f, 1f, .3f, .72f, 0f, .72f, 0f, 0f, 0f
        )
        p(
            'C', 1f, .8f, .75f, 1f, .75f, 1f, .25f, 1f, .25f, 1f, 0f, .75f, 0f, .75f, 0f, .25f,
            0f, .25f, .25f, 0f, .25f, 0f, .75f, 0f, .75f, 0f, 1f, .2f
        )
        p(
            'D', 0f, 0f, 0f, 1f, 0f, 1f, .6f, 1f, .6f, 1f, 1f, .68f, 1f, .68f, 1f, .32f,
            1f, .32f, .6f, 0f, .6f, 0f, 0f, 0f
        )
        p('E', 1f, 1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, .52f, .72f, .52f)
        p('F', 1f, 1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, .55f, .7f, .55f)
        p(
            'G', 1f, .8f, .75f, 1f, .75f, 1f, .25f, 1f, .25f, 1f, 0f, .75f, 0f, .75f, 0f, .25f,
            0f, .25f, .25f, 0f, .25f, 0f, .72f, 0f, .72f, 0f, 1f, .25f, 1f, .25f, 1f, .46f,
            1f, .46f, .55f, .46f
        )
        p('H', 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, .5f, 1f, .5f)
        p('I', .5f, 0f, .5f, 1f, .15f, 1f, .85f, 1f, .15f, 0f, .85f, 0f)
        p('J', .85f, 1f, .85f, .28f, .85f, .28f, .6f, 0f, .6f, 0f, .25f, 0f, .25f, 0f, 0f, .28f)
        p('K', 0f, 0f, 0f, 1f, 1f, 1f, 0f, .45f, .36f, .61f, 1f, 0f)
        p('L', 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        p('M', 0f, 0f, 0f, 1f, 0f, 1f, .5f, .42f, .5f, .42f, 1f, 1f, 1f, 1f, 1f, 0f)
        p('N', 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f)
        p(
            'O', 0f, .25f, .25f, 0f, .25f, 0f, .75f, 0f, .75f, 0f, 1f, .25f, 1f, .25f, 1f, .75f,
            1f, .75f, .75f, 1f, .75f, 1f, .25f, 1f, .25f, 1f, 0f, .75f, 0f, .75f, 0f, .25f
        )
        p(
            'P', 0f, 0f, 0f, 1f, 0f, 1f, .72f, 1f, .72f, 1f, 1f, .8f, 1f, .8f, 1f, .62f,
            1f, .62f, .72f, .45f, .72f, .45f, 0f, .45f
        )
        p(
            'Q', 0f, .25f, .25f, 0f, .25f, 0f, .75f, 0f, .75f, 0f, 1f, .25f, 1f, .25f, 1f, .75f,
            1f, .75f, .75f, 1f, .75f, 1f, .25f, 1f, .25f, 1f, 0f, .75f, 0f, .75f, 0f, .25f,
            .6f, .28f, 1f, -.02f
        )
        p(
            'R', 0f, 0f, 0f, 1f, 0f, 1f, .72f, 1f, .72f, 1f, 1f, .8f, 1f, .8f, 1f, .62f,
            1f, .62f, .72f, .45f, .72f, .45f, 0f, .45f, .55f, .45f, 1f, 0f
        )
        p(
            'S', 1f, .82f, .75f, 1f, .75f, 1f, .25f, 1f, .25f, 1f, 0f, .8f, 0f, .8f, .25f, .56f,
            .25f, .56f, .75f, .56f, .75f, .56f, 1f, .32f, 1f, .32f, .75f, 0f, .75f, 0f, .25f, 0f,
            .25f, 0f, 0f, .18f
        )
        p('T', 0f, 1f, 1f, 1f, .5f, 1f, .5f, 0f)
        p(
            'U', 0f, 1f, 0f, .25f, 0f, .25f, .25f, 0f, .25f, 0f, .75f, 0f, .75f, 0f, 1f, .25f,
            1f, .25f, 1f, 1f
        )
        p('V', 0f, 1f, .5f, 0f, .5f, 0f, 1f, 1f)
        p('W', 0f, 1f, .22f, 0f, .22f, 0f, .5f, .6f, .5f, .6f, .78f, 0f, .78f, 0f, 1f, 1f)
        p('X', 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f)
        p('Y', 0f, 1f, .5f, .5f, 1f, 1f, .5f, .5f, .5f, .5f, .5f, 0f)
        p('Z', 0f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

        // ---- digits ----------------------------------------------------------
        p(
            '0', 0f, .25f, .25f, 0f, .25f, 0f, .75f, 0f, .75f, 0f, 1f, .25f, 1f, .25f, 1f, .75f,
            1f, .75f, .75f, 1f, .75f, 1f, .25f, 1f, .25f, 1f, 0f, .75f, 0f, .75f, 0f, .25f,
            .18f, .22f, .82f, .78f
        )
        p('1', .22f, .76f, .5f, 1f, .5f, 1f, .5f, 0f, .18f, 0f, .82f, 0f)
        p(
            '2', 0f, .78f, .25f, 1f, .25f, 1f, .75f, 1f, .75f, 1f, 1f, .78f, 1f, .78f, 1f, .6f,
            1f, .6f, 0f, 0f, 0f, 0f, 1f, 0f
        )
        p(
            '3', 0f, .92f, .2f, 1f, .2f, 1f, .8f, 1f, .8f, 1f, 1f, .78f, 1f, .78f, .75f, .55f,
            .3f, .55f, .75f, .55f, .75f, .55f, 1f, .3f, 1f, .3f, .8f, 0f, .8f, 0f, .2f, 0f,
            .2f, 0f, 0f, .16f
        )
        p('4', .74f, 0f, .74f, 1f, .74f, 1f, 0f, .32f, 0f, .32f, 1f, .32f)
        p(
            '5', 1f, 1f, .12f, 1f, .12f, 1f, .12f, .58f, .12f, .58f, .72f, .58f, .72f, .58f, 1f, .36f,
            1f, .36f, 1f, .2f, 1f, .2f, .75f, 0f, .75f, 0f, .22f, 0f, .22f, 0f, 0f, .16f
        )
        p(
            '6', .88f, .88f, .62f, 1f, .62f, 1f, .3f, 1f, .3f, 1f, 0f, .66f, 0f, .66f, 0f, .24f,
            0f, .24f, .25f, 0f, .25f, 0f, .72f, 0f, .72f, 0f, 1f, .24f, 1f, .24f, .72f, .5f,
            .72f, .5f, .22f, .5f, .22f, .5f, 0f, .32f
        )
        p('7', 0f, 1f, 1f, 1f, 1f, 1f, .34f, 0f, .26f, .46f, .8f, .46f)
        p(
            '8', .22f, 1f, .78f, 1f, .78f, 1f, 1f, .82f, 1f, .82f, .78f, .58f, .78f, .58f, .22f, .58f,
            .22f, .58f, 0f, .82f, 0f, .82f, .22f, 1f, .22f, .58f, 0f, .3f, 0f, .3f, 0f, .2f,
            0f, .2f, .22f, 0f, .22f, 0f, .78f, 0f, .78f, 0f, 1f, .2f, 1f, .2f, 1f, .3f,
            1f, .3f, .78f, .58f
        )
        p(
            '9', .12f, .12f, .38f, 0f, .38f, 0f, .7f, 0f, .7f, 0f, 1f, .34f, 1f, .34f, 1f, .76f,
            1f, .76f, .75f, 1f, .75f, 1f, .28f, 1f, .28f, 1f, 0f, .76f, 0f, .76f, .28f, .5f,
            .28f, .5f, .78f, .5f, .78f, .5f, 1f, .68f
        )

        // ---- punctuation -----------------------------------------------------
        p('.', .42f, 0f, .58f, 0f)
        p(',', .55f, .14f, .34f, -.16f)
        p(':', .42f, .68f, .58f, .68f, .42f, .2f, .58f, .2f)
        p('-', .12f, .5f, .88f, .5f)
        p('/', .08f, 0f, .92f, 1f)
        p('\'', .52f, 1f, .42f, .7f)
        p('!', .5f, 1f, .5f, .28f, .44f, .03f, .56f, .03f)
        p(
            '?', 0f, .78f, .22f, 1f, .22f, 1f, .72f, 1f, .72f, 1f, .95f, .78f, .95f, .78f, .5f, .44f,
            .5f, .44f, .5f, .28f, .44f, .03f, .56f, .03f
        )
        p(
            '%', .06f, .86f, .2f, 1f, .2f, 1f, .34f, .86f, .34f, .86f, .2f, .72f, .2f, .72f, .06f, .86f,
            .66f, .14f, .8f, .28f, .8f, .28f, .94f, .14f, .94f, .14f, .8f, 0f, .8f, 0f, .66f, .14f,
            .06f, .06f, .94f, .94f
        )
        p('+', .15f, .5f, .85f, .5f, .5f, .16f, .5f, .84f)
    }

    // ---- measurement ---------------------------------------------------------

    fun measure(s: String, size: Float): Float = s.length * ADVANCE * size

    /** Width of [n] monospaced characters — for right-aligning number readouts. */
    fun measureCount(n: Int, size: Float): Float = n * ADVANCE * size

    // ---- text ----------------------------------------------------------------

    /**
     * Draws [s] with its baseline at [y] and its left edge at [x], in the XY plane
     * at [z]. [size] is the cap height in world units; [thickness] is the stroke
     * weight as a fraction of [size] (0.08 ~ a light vector-arcade stroke, 0.14 is
     * chunky and stays readable at small sizes).
     */
    fun draw(
        mesh: DynamicMesh, s: String, x: Float, y: Float, z: Float, size: Float,
        r: Float, g: Float, b: Float, a: Float, thickness: Float = 0.08f
    ) {
        val adv = ADVANCE * size
        val th = thickness * size
        var pen = x
        for (i in s.indices) {
            glyph(mesh, s[i], pen, y, z, size, r, g, b, a, th)
            pen += adv
        }
    }

    /** As [draw], but over a CharArray slice so callers can format numbers without allocating. */
    fun drawChars(
        mesh: DynamicMesh, buf: CharArray, off: Int, len: Int,
        x: Float, y: Float, z: Float, size: Float,
        r: Float, g: Float, b: Float, a: Float, thickness: Float = 0.08f
    ) {
        val adv = ADVANCE * size
        val th = thickness * size
        var pen = x
        for (i in 0 until len) {
            glyph(mesh, buf[off + i], pen, y, z, size, r, g, b, a, th)
            pen += adv
        }
    }

    // ---- primitives ----------------------------------------------------------

    /**
     * Thick line segment as a quad in the XY plane at [z]. [thickness] is the full
     * width in world units. Ends are extended by half the width so that strokes
     * meeting at a corner fill the joint instead of leaving a notch.
     *
     * Shared by the glyph renderer and the HUD chrome so both carry identical weight.
     */
    fun stroke(
        mesh: DynamicMesh, x0: Float, y0: Float, x1: Float, y1: Float, z: Float,
        thickness: Float, r: Float, g: Float, b: Float, a: Float
    ) {
        if (mesh.floatsPerVert != 7 || mesh.cpuIdx.size < 6 || !mesh.hasRoom(4, 6)) return
        val hw = thickness * 0.5f
        val dx = x1 - x0
        val dy = y1 - y0
        val l = sqrt(dx * dx + dy * dy)
        val ux: Float
        val uy: Float
        if (l > 1e-6f) { ux = dx / l; uy = dy / l } else { ux = 1f; uy = 0f }
        val ex = ux * hw
        val ey = uy * hw
        val nx = -uy * hw
        val ny = ux * hw

        val v0 = mesh.vertCount
        vtx(mesh, x0 - ex + nx, y0 - ey + ny, z, r, g, b, a)
        vtx(mesh, x1 + ex + nx, y1 + ey + ny, z, r, g, b, a)
        vtx(mesh, x1 + ex - nx, y1 + ey - ny, z, r, g, b, a)
        vtx(mesh, x0 - ex - nx, y0 - ey - ny, z, r, g, b, a)
        mesh.quadIndices(v0)
    }

    /** Axis-aligned filled quad in the XY plane at [z]. Used for meters and bars. */
    fun rect(
        mesh: DynamicMesh, x0: Float, y0: Float, x1: Float, y1: Float, z: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        if (mesh.floatsPerVert != 7 || mesh.cpuIdx.size < 6 || !mesh.hasRoom(4, 6)) return
        val v0 = mesh.vertCount
        vtx(mesh, x0, y0, z, r, g, b, a)
        vtx(mesh, x1, y0, z, r, g, b, a)
        vtx(mesh, x1, y1, z, r, g, b, a)
        vtx(mesh, x0, y1, z, r, g, b, a)
        mesh.quadIndices(v0)
    }

    /** Rectangle outline built from four strokes. */
    fun frame(
        mesh: DynamicMesh, x0: Float, y0: Float, x1: Float, y1: Float, z: Float,
        thickness: Float, r: Float, g: Float, b: Float, a: Float
    ) {
        stroke(mesh, x0, y0, x1, y0, z, thickness, r, g, b, a)
        stroke(mesh, x1, y0, x1, y1, z, thickness, r, g, b, a)
        stroke(mesh, x1, y1, x0, y1, z, thickness, r, g, b, a)
        stroke(mesh, x0, y1, x0, y0, z, thickness, r, g, b, a)
    }

    // ---- internals -----------------------------------------------------------

    private fun glyph(
        mesh: DynamicMesh, c: Char, x: Float, y: Float, z: Float, size: Float,
        r: Float, g: Float, b: Float, a: Float, absThick: Float
    ) {
        val id = idOf(c)
        if (id < 0) return
        val seg = GLYPHS[id]
        val sw = size * GW
        var i = 0
        while (i + 3 < seg.size) {
            stroke(
                mesh,
                x + seg[i] * sw, y + seg[i + 1] * size,
                x + seg[i + 2] * sw, y + seg[i + 3] * size,
                z, absThick, r, g, b, a
            )
            i += 4
        }
    }

    /** Glyph table index, or -1 for space and anything unmapped (drawn as a gap). */
    private fun idOf(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a'
        in '0'..'9' -> 26 + (c - '0')
        '.' -> 36
        ',' -> 37
        ':' -> 38
        '-' -> 39
        '/' -> 40
        '\'' -> 41
        '!' -> 42
        '?' -> 43
        '%' -> 44
        '+' -> 45
        else -> -1
    }

    private fun p(c: Char, vararg segments: Float) {
        val i = idOf(c)
        if (i >= 0) GLYPHS[i] = segments
    }

    /** Direct CPU-buffer write; DynamicMesh.push() is a vararg and would allocate per vertex. */
    private fun vtx(
        m: DynamicMesh, x: Float, y: Float, z: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val o = m.vertCount * 7
        val c = m.cpu
        c[o] = x; c[o + 1] = y; c[o + 2] = z
        c[o + 3] = r; c[o + 4] = g; c[o + 5] = b; c[o + 6] = a
        m.vertCount++
    }
}
