package com.rayneo.spyhunt.render

import android.opengl.Matrix
import com.rayneo.spyhunt.core.Attr
import com.rayneo.spyhunt.core.Mesh
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Build-time geometry accumulator for the one interleaved vertex format the
 * scene shader binds by location index:
 *
 *   0 aPos(3)  metres, model space
 *   1 aNrm(3)  unit
 *   2 aCol(3)  linear RGB, MAY exceed 1.0 — values above 1 survive the RGBA16F
 *              buffer and become bloom
 *   3 aGlow(1) 0 = lit surface, 1 = fully emissive
 *
 * Everything here runs once, on the GL thread, at load time — allocation is
 * fine; the result is a static VBO. Shapes are flat-shaded by default because
 * hard facets are what make an outline readable at small angular size on the
 * waveguide; [loft] can smooth normals where a form should read as curved.
 *
 * Faces are emitted through [face], which takes an outward *reference*
 * direction and reverses the winding when the computed normal opposes it. That
 * makes the shape code below immune to getting a loop order backwards, which is
 * otherwise the classic way to lose half a model to back-face culling.
 */
class MeshBuilder(vertCapacity: Int = 256, indexCapacity: Int = 768) {

    private var vbuf = FloatArray(maxOf(16, vertCapacity) * FLOATS_PER_VERT)
    private var ibuf = ShortArray(maxOf(24, indexCapacity))
    private var vFloats = 0
    private var iCount = 0

    var vertCount = 0
        private set

    val triCount: Int get() = iCount / 3

    // ---- transform stack -------------------------------------------------
    private var mat = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val stack = ArrayList<FloatArray>(8)
    private var xformed = false
    private var det = 1f

    // scratch — reused so the shape helpers stay allocation-light even here
    private val p4 = FloatArray(4)
    private val q4 = FloatArray(4)
    private val poly = FloatArray(MAX_POLY * 3)
    private val ex = FloatArray(3)
    private val ey = FloatArray(3)
    private val ez = FloatArray(3)

    // =====================================================================
    //  core
    // =====================================================================

    /** Appends one vertex through the current transform. Returns its index. */
    fun vert(
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float, glow: Float
    ): Int {
        if (vFloats + FLOATS_PER_VERT > vbuf.size) vbuf = vbuf.copyOf(vbuf.size * 2)
        var x = px; var y = py; var z = pz
        var ax = nx; var ay = ny; var az = nz
        if (xformed) {
            p4[0] = px; p4[1] = py; p4[2] = pz; p4[3] = 1f
            Matrix.multiplyMV(q4, 0, mat, 0, p4, 0)
            x = q4[0]; y = q4[1]; z = q4[2]
            // Rotation + uniform scale only — a non-uniform scale would need the
            // inverse-transpose, which none of the shapes below rely on.
            p4[0] = nx; p4[1] = ny; p4[2] = nz; p4[3] = 0f
            Matrix.multiplyMV(q4, 0, mat, 0, p4, 0)
            val l = sqrt(q4[0] * q4[0] + q4[1] * q4[1] + q4[2] * q4[2])
            if (l > 1e-6f) { ax = q4[0] / l; ay = q4[1] / l; az = q4[2] / l }
        }
        var o = vFloats
        vbuf[o++] = x; vbuf[o++] = y; vbuf[o++] = z
        vbuf[o++] = ax; vbuf[o++] = ay; vbuf[o++] = az
        vbuf[o++] = r; vbuf[o++] = g; vbuf[o++] = b
        vbuf[o++] = glow
        vFloats = o
        return vertCount++
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (iCount + 3 > ibuf.size) ibuf = ibuf.copyOf(ibuf.size * 2)
        // A mirroring transform flips handedness, so the index order has to swap
        // back or the triangle would be culled from the wrong side.
        if (det >= 0f) {
            ibuf[iCount++] = a.toShort(); ibuf[iCount++] = b.toShort(); ibuf[iCount++] = c.toShort()
        } else {
            ibuf[iCount++] = a.toShort(); ibuf[iCount++] = c.toShort(); ibuf[iCount++] = b.toShort()
        }
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    fun build(): Mesh {
        require(vertCount in 1..65535) { "mesh has $vertCount verts; indices are 16-bit" }
        return Mesh(vbuf.copyOf(vFloats), ibuf.copyOf(iCount), VERTEX_LAYOUT)
    }

    /** Drops all accumulated geometry so one builder can emit several meshes. */
    fun clear() {
        vFloats = 0; iCount = 0; vertCount = 0
        stack.clear(); Matrix.setIdentityM(mat, 0); xformed = false; det = 1f
    }

    // =====================================================================
    //  transform stack
    // =====================================================================

    fun push() { stack.add(mat.copyOf()) }
    fun pop() { mat = stack.removeAt(stack.size - 1); refresh() }
    fun translate(x: Float, y: Float, z: Float) { Matrix.translateM(mat, 0, x, y, z); refresh() }
    fun rotateX(deg: Float) { Matrix.rotateM(mat, 0, deg, 1f, 0f, 0f); refresh() }
    fun rotateY(deg: Float) { Matrix.rotateM(mat, 0, deg, 0f, 1f, 0f); refresh() }
    fun rotateZ(deg: Float) { Matrix.rotateM(mat, 0, deg, 0f, 0f, 1f); refresh() }
    fun scale(x: Float, y: Float, z: Float) { Matrix.scaleM(mat, 0, x, y, z); refresh() }

    private fun refresh() {
        val m00 = mat[0]; val m01 = mat[4]; val m02 = mat[8]
        val m10 = mat[1]; val m11 = mat[5]; val m12 = mat[9]
        val m20 = mat[2]; val m21 = mat[6]; val m22 = mat[10]
        det = m00 * (m11 * m22 - m12 * m21) -
              m01 * (m10 * m22 - m12 * m20) +
              m02 * (m10 * m21 - m11 * m20)
        var identity = true
        for (i in 0 until 16) {
            val want = if (i % 5 == 0) 1f else 0f
            if (abs(mat[i] - want) > 1e-6f) { identity = false; break }
        }
        xformed = !identity
    }

    // =====================================================================
    //  polygons
    // =====================================================================

    /**
     * Flat-shaded convex polygon (triangle fan) from [n] positions packed as
     * xyz triples in [p]. When the reference direction is non-zero the winding
     * is reversed if the computed normal opposes it.
     */
    fun face(
        p: FloatArray, n: Int,
        r: Float, g: Float, b: Float, glow: Float,
        rx: Float = 0f, ry: Float = 0f, rz: Float = 0f
    ) {
        if (n < 3) return
        // Newell's method: stable even when the first three points are collinear.
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val x0 = p[i * 3]; val y0 = p[i * 3 + 1]; val z0 = p[i * 3 + 2]
            val x1 = p[j * 3]; val y1 = p[j * 3 + 1]; val z1 = p[j * 3 + 2]
            nx += (y0 - y1) * (z0 + z1)
            ny += (z0 - z1) * (x0 + x1)
            nz += (x0 - x1) * (y0 + y1)
        }
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-9f) return                       // degenerate — nothing to draw
        nx /= l; ny /= l; nz /= l
        var flip = false
        val hasRef = rx != 0f || ry != 0f || rz != 0f
        if (hasRef && nx * rx + ny * ry + nz * rz < 0f) {
            flip = true; nx = -nx; ny = -ny; nz = -nz
        }
        val base = vertCount
        if (flip) {
            for (i in n - 1 downTo 0) vert(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], nx, ny, nz, r, g, b, glow)
        } else {
            for (i in 0 until n) vert(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], nx, ny, nz, r, g, b, glow)
        }
        for (i in 1 until n - 1) tri(base, base + i, base + i + 1)
    }

    /** Flat quad from four corners, with an outward reference direction. */
    fun quadFace(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        r: Float, g: Float, b: Float, glow: Float,
        rx: Float, ry: Float, rz: Float
    ) {
        poly[0] = x0; poly[1] = y0; poly[2] = z0
        poly[3] = x1; poly[4] = y1; poly[5] = z1
        poly[6] = x2; poly[7] = y2; poly[8] = z2
        poly[9] = x3; poly[10] = y3; poly[11] = z3
        face(poly, dedupePoly(4), r, g, b, glow, rx, ry, rz)
    }

    /** Flat triangle from three corners, with an outward reference direction. */
    fun triFace(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, glow: Float,
        rx: Float, ry: Float, rz: Float
    ) {
        poly[0] = x0; poly[1] = y0; poly[2] = z0
        poly[3] = x1; poly[4] = y1; poly[5] = z1
        poly[6] = x2; poly[7] = y2; poly[8] = z2
        face(poly, 3, r, g, b, glow, rx, ry, rz)
    }

    /** Removes consecutive coincident points — loft quads pinch to a point at nose tips. */
    private fun dedupePoly(n: Int): Int {
        var w = 0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val dx = poly[i * 3] - poly[j * 3]
            val dy = poly[i * 3 + 1] - poly[j * 3 + 1]
            val dz = poly[i * 3 + 2] - poly[j * 3 + 2]
            if (dx * dx + dy * dy + dz * dz < 1e-8f) continue
            poly[w * 3] = poly[i * 3]; poly[w * 3 + 1] = poly[i * 3 + 1]; poly[w * 3 + 2] = poly[i * 3 + 2]
            w++
        }
        return w
    }

    // =====================================================================
    //  solids
    // =====================================================================

    /** Axis-aligned box, 12 tris, faceted. */
    fun box(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
        r: Float, g: Float, b: Float, glow: Float
    ) = boxFaces(cx, cy, cz, hx, hy, hz, r, g, b, glow, 1f)

    /**
     * Box with its faces turned inward, so it is visible only when looked *into*
     * through an opening. Used for the weapons van's cargo bay.
     */
    fun boxInside(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
        r: Float, g: Float, b: Float, glow: Float
    ) = boxFaces(cx, cy, cz, hx, hy, hz, r, g, b, glow, -1f)

    private fun boxFaces(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
        r: Float, g: Float, b: Float, glow: Float, s: Float
    ) {
        val x0 = cx - hx; val x1 = cx + hx
        val y0 = cy - hy; val y1 = cy + hy
        val z0 = cz - hz; val z1 = cz + hz
        quadFace(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, glow, 0f, 0f, s)
        quadFace(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b, glow, 0f, 0f, -s)
        quadFace(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, glow, s, 0f, 0f)
        quadFace(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, glow, -s, 0f, 0f)
        quadFace(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, glow, 0f, s, 0f)
        quadFace(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, glow, 0f, -s, 0f)
    }

    /**
     * Hexahedron from 8 corners: 0..3 are the bottom loop (-x-z, +x-z, +x+z,
     * -x+z), 4..7 the matching top loop. Corners may be moved freely as long as
     * that lattice is preserved — outward normals come from the centroid, so
     * wedges and mirrored parts stay correctly wound.
     */
    fun hull8(c: FloatArray, r: Float, g: Float, b: Float, glow: Float) {
        var gx = 0f; var gy = 0f; var gz = 0f
        for (i in 0 until 8) { gx += c[i * 3]; gy += c[i * 3 + 1]; gz += c[i * 3 + 2] }
        gx /= 8f; gy /= 8f; gz /= 8f
        hullFace(c, 0, 1, 2, 3, gx, gy, gz, r, g, b, glow)   // bottom
        hullFace(c, 4, 5, 6, 7, gx, gy, gz, r, g, b, glow)   // top
        hullFace(c, 3, 2, 6, 7, gx, gy, gz, r, g, b, glow)   // +Z
        hullFace(c, 0, 1, 5, 4, gx, gy, gz, r, g, b, glow)   // -Z
        hullFace(c, 1, 2, 6, 5, gx, gy, gz, r, g, b, glow)   // +X
        hullFace(c, 0, 3, 7, 4, gx, gy, gz, r, g, b, glow)   // -X
    }

    private fun hullFace(
        c: FloatArray, a: Int, b2: Int, c2: Int, d: Int,
        gx: Float, gy: Float, gz: Float,
        r: Float, g: Float, b: Float, glow: Float
    ) {
        poly[0] = c[a * 3]; poly[1] = c[a * 3 + 1]; poly[2] = c[a * 3 + 2]
        poly[3] = c[b2 * 3]; poly[4] = c[b2 * 3 + 1]; poly[5] = c[b2 * 3 + 2]
        poly[6] = c[c2 * 3]; poly[7] = c[c2 * 3 + 1]; poly[8] = c[c2 * 3 + 2]
        poly[9] = c[d * 3]; poly[10] = c[d * 3 + 1]; poly[11] = c[d * 3 + 2]
        val n = dedupePoly(4)
        if (n < 3) return
        var mx = 0f; var my = 0f; var mz = 0f
        for (i in 0 until n) { mx += poly[i * 3]; my += poly[i * 3 + 1]; mz += poly[i * 3 + 2] }
        face(poly, n, r, g, b, glow, mx / n - gx, my / n - gy, mz / n - gz)
    }

    /** Wedge/tapered block: independent half-width and top/bottom height at each end. */
    fun taperedBox(
        zBack: Float, zFront: Float,
        hwBack: Float, hwFront: Float,
        yLoBack: Float, yHiBack: Float,
        yLoFront: Float, yHiFront: Float,
        cx: Float, r: Float, g: Float, b: Float, glow: Float
    ) {
        val c = floatArrayOf(
            cx - hwBack, yLoBack, zBack,
            cx + hwBack, yLoBack, zBack,
            cx + hwFront, yLoFront, zFront,
            cx - hwFront, yLoFront, zFront,
            cx - hwBack, yHiBack, zBack,
            cx + hwBack, yHiBack, zBack,
            cx + hwFront, yHiFront, zFront,
            cx - hwFront, yHiFront, zFront
        )
        hull8(c, r, g, b, glow)
    }

    /** Box with its four vertical edges cut back — reads as a machined block. */
    fun chamferedBox(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float, chamfer: Float,
        r: Float, g: Float, b: Float, glow: Float
    ) {
        // A zero half-extent would divide to NaN, and coerceIn does not trap NaN
        // — it would sail straight into the VBO and poison every triangle of the
        // section. Every other shape helper here bails on a degenerate input, so
        // this one does too.
        if (hx < 1e-5f || hy < 1e-5f || hz < 1e-5f) return
        val fx = (chamfer / hx).coerceIn(0.02f, 0.45f)
        val fy = (chamfer / (2f * hy)).coerceIn(0.02f, 0.45f)
        val sec = floatArrayOf(
            -1f + fx, 0f, 1f - fx, 0f, 1f, fy,
            1f, 1f - fy, 1f - fx, 1f, -1f + fx, 1f,
            -1f, 1f - fy, -1f, fy
        )
        val st = floatArrayOf(
            cz - hz, hx, 2f * hy, cy - hy,
            cz + hz, hx, 2f * hy, cy - hy
        )
        push(); translate(cx, 0f, 0f)
        loft(sec, st, r, g, b, glow)
        pop()
    }

    // =====================================================================
    //  lofts and lathes
    // =====================================================================

    /**
     * Sweeps a closed convex cross-section along +Z — the workhorse for vehicle
     * bodies.
     *
     * @param sec      n*2 floats, the section polygon in XY with x in [-1,1] and
     *                 y in [0,1]; wound either way, [face] fixes each side.
     * @param stations m*4 floats: z, xScale, yScale, yOffset — back (-Z) to front.
     * @param smooth   average normals across the sweep. Off by default: facets
     *                 are what give the vector-arcade read. On for hulls.
     */
    fun loft(
        sec: FloatArray, stations: FloatArray,
        r: Float, g: Float, b: Float, glow: Float,
        capBack: Boolean = true, capFront: Boolean = true, smooth: Boolean = false
    ) {
        val n = sec.size / 2
        val m = stations.size / 4
        if (n < 3 || m < 2 || n > MAX_POLY) return
        var scx = 0f; var scy = 0f
        for (i in 0 until n) { scx += sec[i * 2]; scy += sec[i * 2 + 1] }
        scx /= n; scy /= n
        if (smooth) loftSmooth(sec, n, stations, m, scx, scy, r, g, b, glow)
        else loftFlat(sec, n, stations, m, scx, scy, r, g, b, glow)
        if (capBack) loftCap(sec, n, stations, 0, false, r, g, b, glow)
        if (capFront) loftCap(sec, n, stations, m - 1, true, r, g, b, glow)
    }

    private fun lx(sec: FloatArray, i: Int, st: FloatArray, s: Int) = sec[i * 2] * st[s * 4 + 1]
    private fun ly(sec: FloatArray, i: Int, st: FloatArray, s: Int) = st[s * 4 + 3] + sec[i * 2 + 1] * st[s * 4 + 2]

    private fun loftFlat(
        sec: FloatArray, n: Int, st: FloatArray, m: Int,
        scx: Float, scy: Float, r: Float, g: Float, b: Float, glow: Float
    ) {
        for (s in 0 until m - 1) {
            val z0 = st[s * 4]; val z1 = st[(s + 1) * 4]
            // interior reference: the section axis, midway between the two stations
            val axX = 0.5f * (scx * st[s * 4 + 1] + scx * st[(s + 1) * 4 + 1])
            val axY = 0.5f * ((st[s * 4 + 3] + scy * st[s * 4 + 2]) + (st[(s + 1) * 4 + 3] + scy * st[(s + 1) * 4 + 2]))
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                poly[0] = lx(sec, i, st, s); poly[1] = ly(sec, i, st, s); poly[2] = z0
                poly[3] = lx(sec, j, st, s); poly[4] = ly(sec, j, st, s); poly[5] = z0
                poly[6] = lx(sec, j, st, s + 1); poly[7] = ly(sec, j, st, s + 1); poly[8] = z1
                poly[9] = lx(sec, i, st, s + 1); poly[10] = ly(sec, i, st, s + 1); poly[11] = z1
                val cnt = dedupePoly(4)
                if (cnt < 3) continue
                var mx = 0f; var my = 0f
                for (k in 0 until cnt) { mx += poly[k * 3]; my += poly[k * 3 + 1] }
                face(poly, cnt, r, g, b, glow, mx / cnt - axX, my / cnt - axY, 0f)
            }
        }
    }

    private fun loftSmooth(
        sec: FloatArray, n: Int, st: FloatArray, m: Int,
        scx: Float, scy: Float, r: Float, g: Float, b: Float, glow: Float
    ) {
        val pos = FloatArray(n * m * 3)
        val nrm = FloatArray(n * m * 3)
        for (s in 0 until m) for (i in 0 until n) {
            val o = (s * n + i) * 3
            pos[o] = lx(sec, i, st, s); pos[o + 1] = ly(sec, i, st, s); pos[o + 2] = st[s * 4]
        }
        // One global winding decision keeps the accumulated normals coherent.
        var reversed = false
        loop@ for (s in 0 until m - 1) {
            val axX = scx * st[s * 4 + 1]
            val axY = st[s * 4 + 3] + scy * st[s * 4 + 2]
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val a = (s * n + i) * 3; val bi = (s * n + j) * 3; val d = ((s + 1) * n + i) * 3
                val ux = pos[bi] - pos[a]; val uy = pos[bi + 1] - pos[a + 1]; val uz = pos[bi + 2] - pos[a + 2]
                val vx = pos[d] - pos[a]; val vy = pos[d + 1] - pos[a + 1]; val vz = pos[d + 2] - pos[a + 2]
                val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
                if (cx * cx + cy * cy + cz * cz < 1e-10f) continue
                val refX = 0.5f * (pos[a] + pos[bi]) - axX
                val refY = 0.5f * (pos[a + 1] + pos[bi + 1]) - axY
                reversed = cx * refX + cy * refY < 0f
                break@loop
            }
        }
        for (s in 0 until m - 1) for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val i0 = (s * n + i) * 3; val i1 = (s * n + j) * 3
            val i2 = ((s + 1) * n + j) * 3; val i3 = ((s + 1) * n + i) * 3
            // diagonal cross product — correct even when the quad is not planar
            val ux = pos[i2] - pos[i0]; val uy = pos[i2 + 1] - pos[i0 + 1]; val uz = pos[i2 + 2] - pos[i0 + 2]
            val vx = pos[i3] - pos[i1]; val vy = pos[i3 + 1] - pos[i1 + 1]; val vz = pos[i3 + 2] - pos[i1 + 2]
            var fx = uy * vz - uz * vy; var fy = uz * vx - ux * vz; var fz = ux * vy - uy * vx
            val fl = sqrt(fx * fx + fy * fy + fz * fz)
            if (fl < 1e-7f) continue
            fx /= fl; fy /= fl; fz /= fl
            if (reversed) { fx = -fx; fy = -fy; fz = -fz }
            nrm[i0] += fx; nrm[i0 + 1] += fy; nrm[i0 + 2] += fz
            nrm[i1] += fx; nrm[i1 + 1] += fy; nrm[i1 + 2] += fz
            nrm[i2] += fx; nrm[i2 + 1] += fy; nrm[i2 + 2] += fz
            nrm[i3] += fx; nrm[i3 + 1] += fy; nrm[i3 + 2] += fz
        }
        val ids = IntArray(n * m)
        for (s in 0 until m) for (i in 0 until n) {
            val k = s * n + i; val o = k * 3
            var ax = nrm[o]; var ay = nrm[o + 1]; var az = nrm[o + 2]
            val l = sqrt(ax * ax + ay * ay + az * az)
            if (l < 1e-6f) {                        // pinched tip: fall back to radial
                ax = pos[o] - scx * st[s * 4 + 1]
                ay = pos[o + 1] - (st[s * 4 + 3] + scy * st[s * 4 + 2])
                az = 0f
                val l2 = sqrt(ax * ax + ay * ay)
                if (l2 < 1e-6f) { ax = 0f; ay = 1f } else { ax /= l2; ay /= l2 }
            } else { ax /= l; ay /= l; az /= l }
            ids[k] = vert(pos[o], pos[o + 1], pos[o + 2], ax, ay, az, r, g, b, glow)
        }
        for (s in 0 until m - 1) for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val a = ids[s * n + i]; val b2 = ids[s * n + j]
            val c = ids[(s + 1) * n + j]; val d = ids[(s + 1) * n + i]
            if (reversed) quad(a, d, c, b2) else quad(a, b2, c, d)
        }
    }

    private fun loftCap(
        sec: FloatArray, n: Int, st: FloatArray, s: Int, front: Boolean,
        r: Float, g: Float, b: Float, glow: Float
    ) {
        for (i in 0 until n) {
            poly[i * 3] = lx(sec, i, st, s)
            poly[i * 3 + 1] = ly(sec, i, st, s)
            poly[i * 3 + 2] = st[s * 4]
        }
        face(poly, dedupePoly(n), r, g, b, glow, 0f, 0f, if (front) 1f else -1f)
    }

    /**
     * Revolves a profile about the Y axis through (cx,cy,cz).
     * [profile] is (radius, yOffset) pairs ordered bottom to top; a zero radius
     * end collapses to a point, which is how cones and missile noses are made.
     */
    fun lathe(
        profile: FloatArray, seg: Int,
        cx: Float, cy: Float, cz: Float,
        r: Float, g: Float, b: Float, glow: Float
    ) {
        val k = profile.size / 2
        if (k < 2 || seg < 3 || seg > MAX_POLY) return
        for (p in 0 until k - 1) {
            val r0 = profile[p * 2]; val y0 = cy + profile[p * 2 + 1]
            val r1 = profile[p * 2 + 2]; val y1 = cy + profile[p * 2 + 3]
            for (s in 0 until seg) {
                val a0 = TAU * s / seg
                val a1 = TAU * (s + 1) / seg
                val c0 = cos(a0); val s0 = sin(a0)
                val c1 = cos(a1); val s1 = sin(a1)
                poly[0] = cx + r0 * c0; poly[1] = y0; poly[2] = cz + r0 * s0
                poly[3] = cx + r0 * c1; poly[4] = y0; poly[5] = cz + r0 * s1
                poly[6] = cx + r1 * c1; poly[7] = y1; poly[8] = cz + r1 * s1
                poly[9] = cx + r1 * c0; poly[10] = y1; poly[11] = cz + r1 * s0
                val cnt = dedupePoly(4)
                if (cnt < 3) continue
                val am = 0.5f * (a0 + a1)
                face(poly, cnt, r, g, b, glow, cos(am), 0f, sin(am))
            }
        }
        latheCap(profile[0], cy + profile[1], seg, cx, cz, false, r, g, b, glow)
        latheCap(profile[(k - 1) * 2], cy + profile[(k - 1) * 2 + 1], seg, cx, cz, true, r, g, b, glow)
    }

    private fun latheCap(
        rad: Float, y: Float, seg: Int, cx: Float, cz: Float, up: Boolean,
        r: Float, g: Float, b: Float, glow: Float
    ) {
        if (rad < 1e-4f) return
        for (s in 0 until seg) {
            val a = TAU * s / seg
            poly[s * 3] = cx + rad * cos(a); poly[s * 3 + 1] = y; poly[s * 3 + 2] = cz + rad * sin(a)
        }
        face(poly, seg, r, g, b, glow, 0f, if (up) 1f else -1f, 0f)
    }

    /** Wheel/disc: a lathe whose axis is X, for anything that rolls. */
    fun wheelX(
        cx: Float, cy: Float, cz: Float,
        radius: Float, halfWidth: Float, seg: Int,
        r: Float, g: Float, b: Float, glow: Float
    ) {
        val profile = floatArrayOf(
            0f, -halfWidth,
            radius, -halfWidth * 0.72f,
            radius, halfWidth * 0.72f,
            0f, halfWidth
        )
        push(); translate(cx, cy, cz); rotateZ(90f)
        lathe(profile, seg, 0f, 0f, 0f, r, g, b, glow)
        pop()
    }

    /** Flat annulus in the XZ plane — rotor blur discs, waterline rings. */
    fun ring(
        cx: Float, cy: Float, cz: Float,
        rInner: Float, rOuter: Float, seg: Int,
        r: Float, g: Float, b: Float, glow: Float,
        doubleSided: Boolean = true
    ) {
        if (seg < 3) return
        for (s in 0 until seg) {
            val a0 = TAU * s / seg
            val a1 = TAU * (s + 1) / seg
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val ix0 = cx + rInner * c0; val iz0 = cz + rInner * s0
            val ix1 = cx + rInner * c1; val iz1 = cz + rInner * s1
            val ox0 = cx + rOuter * c0; val oz0 = cz + rOuter * s0
            val ox1 = cx + rOuter * c1; val oz1 = cz + rOuter * s1
            quadFace(ix0, cy, iz0, ox0, cy, oz0, ox1, cy, oz1, ix1, cy, iz1, r, g, b, glow, 0f, 1f, 0f)
            if (doubleSided) {
                quadFace(ix0, cy, iz0, ox0, cy, oz0, ox1, cy, oz1, ix1, cy, iz1, r, g, b, glow, 0f, -1f, 0f)
            }
        }
    }

    // =====================================================================
    //  neon linework
    // =====================================================================

    /**
     * Emissive line segment as a slim triangular prism — 8 tris, and unlike a
     * billboarded quad it survives back-face culling from every angle. This is
     * what draws the outlines that read as pure light on the waveguide.
     */
    fun neonEdge(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        thick: Float, r: Float, g: Float, b: Float, glow: Float = 1f
    ) {
        var dx = bx - ax; var dy = by - ay; var dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return
        dx /= len; dy /= len; dz /= len
        // any axis not parallel to the segment seeds the frame
        val sx: Float; val sy: Float; val sz: Float
        if (abs(dy) < 0.9f) { sx = 0f; sy = 1f; sz = 0f } else { sx = 1f; sy = 0f; sz = 0f }
        var ux = dy * sz - dz * sy; var uy = dz * sx - dx * sz; var uz = dx * sy - dy * sx
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= ul; uy /= ul; uz /= ul
        val vx = dy * uz - dz * uy; val vy = dz * ux - dx * uz; val vz = dx * uy - dy * ux
        for (k in 0 until 3) {
            val a = 1.5707964f + k * 2.0943952f
            val c = cos(a) * thick; val s = sin(a) * thick
            ex[k] = ux * c + vx * s; ey[k] = uy * c + vy * s; ez[k] = uz * c + vz * s
        }
        for (k in 0 until 3) {
            val k2 = if (k == 2) 0 else k + 1
            quadFace(
                ax + ex[k], ay + ey[k], az + ez[k],
                bx + ex[k], by + ey[k], bz + ez[k],
                bx + ex[k2], by + ey[k2], bz + ez[k2],
                ax + ex[k2], ay + ey[k2], az + ez[k2],
                r, g, b, glow,
                0.5f * (ex[k] + ex[k2]), 0.5f * (ey[k] + ey[k2]), 0.5f * (ez[k] + ez[k2])
            )
        }
        triFace(
            ax + ex[0], ay + ey[0], az + ez[0],
            ax + ex[1], ay + ey[1], az + ez[1],
            ax + ex[2], ay + ey[2], az + ez[2],
            r, g, b, glow, -dx, -dy, -dz
        )
        triFace(
            bx + ex[0], by + ey[0], bz + ez[0],
            bx + ex[1], by + ey[1], bz + ez[1],
            bx + ex[2], by + ey[2], bz + ez[2],
            r, g, b, glow, dx, dy, dz
        )
    }

    /**
     * Neon wireframe of a box. [bottom] can be dropped for things that sit on
     * the ground, where the lower ring would be buried anyway.
     */
    fun edgeFrame(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float, thick: Float,
        r: Float, g: Float, b: Float,
        vr: Float = r, vg: Float = g, vb: Float = b,
        bottom: Boolean = true
    ) {
        val x0 = cx - hx; val x1 = cx + hx
        val y0 = cy - hy; val y1 = cy + hy
        val z0 = cz - hz; val z1 = cz + hz
        // uprights
        neonEdge(x0, y0, z0, x0, y1, z0, thick, vr, vg, vb)
        neonEdge(x1, y0, z0, x1, y1, z0, thick, vr, vg, vb)
        neonEdge(x1, y0, z1, x1, y1, z1, thick, vr, vg, vb)
        neonEdge(x0, y0, z1, x0, y1, z1, thick, vr, vg, vb)
        // top ring
        neonEdge(x0, y1, z0, x1, y1, z0, thick, r, g, b)
        neonEdge(x1, y1, z0, x1, y1, z1, thick, r, g, b)
        neonEdge(x1, y1, z1, x0, y1, z1, thick, r, g, b)
        neonEdge(x0, y1, z1, x0, y1, z0, thick, r, g, b)
        if (bottom) {
            neonEdge(x0, y0, z0, x1, y0, z0, thick, r, g, b)
            neonEdge(x1, y0, z0, x1, y0, z1, thick, r, g, b)
            neonEdge(x1, y0, z1, x0, y0, z1, thick, r, g, b)
            neonEdge(x0, y0, z1, x0, y0, z0, thick, r, g, b)
        }
    }

    companion object {
        const val FLOATS_PER_VERT = 10
        private const val MAX_POLY = 32
        private const val TAU = 6.2831855f

        /** The one true vertex layout — the shaders bind these by location index. */
        val VERTEX_LAYOUT: List<Attr> =
            listOf(Attr("aPos", 3), Attr("aNrm", 3), Attr("aCol", 3), Attr("aGlow", 1))
    }
}
