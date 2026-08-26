package com.rayneo.spyhunt.core

import android.opengl.GLES30 as GL
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

const val TAG = "SpyHunt"

fun checkGl(where: String) {
    var e = GL.glGetError()
    while (e != GL.GL_NO_ERROR) {
        Log.e(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
        e = GL.glGetError()
    }
}

fun floatBuf(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(data); position(0) }

fun shortBuf(data: ShortArray): ShortBuffer =
    ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer().apply { put(data); position(0) }

/**
 * Compiled shader program with a lazily-populated uniform location cache.
 * Uniform lookups by name are cheap after first use, so call sites stay readable.
 */
class Program(vsSrc: String, fsSrc: String, private val label: String = "prog") {
    val id: Int = link(vsSrc, fsSrc, label)
    private val locs = HashMap<String, Int>(32)

    fun use() = GL.glUseProgram(id)

    fun loc(name: String): Int = locs.getOrPut(name) { GL.glGetUniformLocation(id, name) }
    fun attr(name: String): Int = GL.glGetAttribLocation(id, name)

    fun u1i(n: String, v: Int) { GL.glUniform1i(loc(n), v) }
    fun u1f(n: String, v: Float) { GL.glUniform1f(loc(n), v) }
    fun u2f(n: String, a: Float, b: Float) { GL.glUniform2f(loc(n), a, b) }
    fun u3f(n: String, a: Float, b: Float, c: Float) { GL.glUniform3f(loc(n), a, b, c) }
    fun u4f(n: String, a: Float, b: Float, c: Float, d: Float) { GL.glUniform4f(loc(n), a, b, c, d) }
    fun uMat4(n: String, m: FloatArray) { GL.glUniformMatrix4fv(loc(n), 1, false, m, 0) }
    fun uMat4Array(n: String, m: FloatArray, count: Int) {
        GL.glUniformMatrix4fv(loc(n), count, false, m, 0)
    }

    /** Binds a texture to [unit] and points sampler [n] at it. */
    fun tex(n: String, unit: Int, texId: Int, target: Int = GL.GL_TEXTURE_2D) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(target, texId)
        GL.glUniform1i(loc(n), unit)
    }

    fun release() = GL.glDeleteProgram(id)

    companion object {
        private fun compile(type: Int, src: String, label: String): Int {
            val s = GL.glCreateShader(type)
            GL.glShaderSource(s, src)
            GL.glCompileShader(s)
            val st = IntArray(1)
            GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, st, 0)
            if (st[0] == 0) {
                val log = GL.glGetShaderInfoLog(s)
                Log.e(TAG, "[$label] ${if (type == GL.GL_VERTEX_SHADER) "VS" else "FS"} compile failed:\n$log")
                // Echo the offending source with line numbers — invaluable on-device.
                src.lineSequence().forEachIndexed { i, l -> Log.e(TAG, "%3d| %s".format(i + 1, l)) }
                GL.glDeleteShader(s)
                throw RuntimeException("[$label] shader compile failed: $log")
            }
            return s
        }

        fun link(vs: String, fs: String, label: String): Int {
            val v = compile(GL.GL_VERTEX_SHADER, vs, label)
            val f = compile(GL.GL_FRAGMENT_SHADER, fs, label)
            val p = GL.glCreateProgram()
            GL.glAttachShader(p, v)
            GL.glAttachShader(p, f)
            GL.glLinkProgram(p)
            val st = IntArray(1)
            GL.glGetProgramiv(p, GL.GL_LINK_STATUS, st, 0)
            if (st[0] == 0) {
                val log = GL.glGetProgramInfoLog(p)
                GL.glDeleteProgram(p)
                throw RuntimeException("[$label] program link failed: $log")
            }
            GL.glDeleteShader(v)
            GL.glDeleteShader(f)
            return p
        }
    }
}

/** One interleaved vertex attribute. */
data class Attr(val name: String, val size: Int)

/**
 * Static indexed mesh in a VAO. Attributes are interleaved in one VBO and bound
 * by location index (matching `layout(location=N)` in the shaders).
 */
class Mesh(
    vertices: FloatArray,
    indices: ShortArray,
    private val attrs: List<Attr>,
    val mode: Int = GL.GL_TRIANGLES
) {
    private val vao = IntArray(1)
    private val bufs = IntArray(2)
    val indexCount = indices.size
    val stride = attrs.sumOf { it.size } * 4

    init {
        GL.glGenVertexArrays(1, vao, 0)
        GL.glGenBuffers(2, bufs, 0)
        GL.glBindVertexArray(vao[0])

        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, bufs[0])
        GL.glBufferData(GL.GL_ARRAY_BUFFER, vertices.size * 4, floatBuf(vertices), GL.GL_STATIC_DRAW)

        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, bufs[1])
        GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, shortBuf(indices), GL.GL_STATIC_DRAW)

        var off = 0
        attrs.forEachIndexed { i, a ->
            GL.glEnableVertexAttribArray(i)
            GL.glVertexAttribPointer(i, a.size, GL.GL_FLOAT, false, stride, off)
            off += a.size * 4
        }
        GL.glBindVertexArray(0)
    }

    fun draw() {
        GL.glBindVertexArray(vao[0])
        GL.glDrawElements(mode, indexCount, GL.GL_UNSIGNED_SHORT, 0)
        GL.glBindVertexArray(0)
    }

    fun drawInstanced(n: Int) {
        GL.glBindVertexArray(vao[0])
        GL.glDrawElementsInstanced(mode, indexCount, GL.GL_UNSIGNED_SHORT, 0, n)
        GL.glBindVertexArray(0)
    }

    fun release() {
        GL.glDeleteBuffers(2, bufs, 0)
        GL.glDeleteVertexArrays(1, vao, 0)
    }
}

/**
 * Dynamic mesh for per-frame geometry (particles, trails, HUD). Re-uploads the
 * whole vertex range each frame with GL_STREAM_DRAW — simple and fast enough at
 * the vertex counts this game reaches.
 */
class DynamicMesh(private val maxVerts: Int, private val attrs: List<Attr>, private val maxIdx: Int = 0) {
    private val vao = IntArray(1)
    private val bufs = IntArray(2)
    val floatsPerVert = attrs.sumOf { it.size }
    private val stride = floatsPerVert * 4
    val cpu = FloatArray(maxVerts * floatsPerVert)
    val cpuIdx = ShortArray(if (maxIdx > 0) maxIdx else 1)
    var vertCount = 0
    var idxCount = 0

    // Persistent direct buffers. Uploading straight from `cpu` via a fresh
    // FloatBuffer each frame would allocate hundreds of KB per frame and hand
    // the GC a steady stream of garbage right in the middle of the frame.
    private val gpuVerts = ByteBuffer.allocateDirect(maxVerts * floatsPerVert * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val gpuIdx = ByteBuffer.allocateDirect((if (maxIdx > 0) maxIdx else 1) * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer()

    init {
        GL.glGenVertexArrays(1, vao, 0)
        GL.glGenBuffers(2, bufs, 0)
        GL.glBindVertexArray(vao[0])
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, bufs[0])
        GL.glBufferData(GL.GL_ARRAY_BUFFER, maxVerts * stride, null, GL.GL_STREAM_DRAW)
        if (maxIdx > 0) {
            GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, bufs[1])
            GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, maxIdx * 2, null, GL.GL_STREAM_DRAW)
        }
        var off = 0
        attrs.forEachIndexed { i, a ->
            GL.glEnableVertexAttribArray(i)
            GL.glVertexAttribPointer(i, a.size, GL.GL_FLOAT, false, stride, off)
            off += a.size * 4
        }
        GL.glBindVertexArray(0)
    }

    fun reset() { vertCount = 0; idxCount = 0 }

    fun hasRoom(verts: Int, idx: Int = 0) =
        vertCount + verts <= maxVerts && (maxIdx == 0 || idxCount + idx <= maxIdx)

    /** Appends one vertex; [v] must contain exactly [floatsPerVert] values. */
    fun push(vararg v: Float) {
        val base = vertCount * floatsPerVert
        System.arraycopy(v, 0, cpu, base, v.size)
        vertCount++
    }

    fun pushIndex(i: Int) { cpuIdx[idxCount++] = i.toShort() }

    /** Emits two triangles for the last four pushed vertices (quad as a fan). */
    fun quadIndices(v0: Int) {
        pushIndex(v0); pushIndex(v0 + 1); pushIndex(v0 + 2)
        pushIndex(v0); pushIndex(v0 + 2); pushIndex(v0 + 3)
    }

    fun flushAndDraw(mode: Int = GL.GL_TRIANGLES) {
        if (vertCount == 0) return
        GL.glBindVertexArray(vao[0])

        gpuVerts.clear()
        gpuVerts.put(cpu, 0, vertCount * floatsPerVert)
        gpuVerts.position(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, bufs[0])
        GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, vertCount * stride, gpuVerts)

        if (maxIdx > 0 && idxCount > 0) {
            gpuIdx.clear()
            gpuIdx.put(cpuIdx, 0, idxCount)
            gpuIdx.position(0)
            GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, bufs[1])
            GL.glBufferSubData(GL.GL_ELEMENT_ARRAY_BUFFER, 0, idxCount * 2, gpuIdx)
            GL.glDrawElements(mode, idxCount, GL.GL_UNSIGNED_SHORT, 0)
        } else {
            GL.glDrawArrays(mode, 0, vertCount)
        }
        GL.glBindVertexArray(0)
    }

    fun release() {
        GL.glDeleteBuffers(2, bufs, 0)
        GL.glDeleteVertexArrays(1, vao, 0)
    }
}

/**
 * Render target. [halfFloat] gives an RGBA16F buffer so emissive materials can
 * exceed 1.0 and drive the bloom bright-pass properly.
 */
class Fbo(val w: Int, val h: Int, halfFloat: Boolean = false, depth: Boolean = false) {
    private val fbo = IntArray(1)
    private val rbo = IntArray(1)
    val tex = IntArray(1)

    init {
        GL.glGenTextures(1, tex, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex[0])
        val ifmt = if (halfFloat) GL.GL_RGBA16F else GL.GL_RGBA8
        val type = if (halfFloat) GL.GL_HALF_FLOAT else GL.GL_UNSIGNED_BYTE
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, ifmt, w, h, 0, GL.GL_RGBA, type, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)

        GL.glGenFramebuffers(1, fbo, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[0])
        GL.glFramebufferTexture2D(
            GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex[0], 0
        )
        if (depth) {
            GL.glGenRenderbuffers(1, rbo, 0)
            GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, rbo[0])
            GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT24, w, h)
            GL.glFramebufferRenderbuffer(
                GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, rbo[0]
            )
        }
        val status = GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)
        if (status != GL.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO ${w}x$h incomplete: 0x${Integer.toHexString(status)}")
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    /** True when this target actually has a depth attachment to clear. */
    private val hasDepth = depth

    fun bind(clear: Boolean = true) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo[0])
        GL.glViewport(0, 0, w, h)
        if (clear) {
            GL.glClearColor(0f, 0f, 0f, 0f)
            if (hasDepth) {
                // glClear honours the depth mask; force it on so a caller that left
                // writes disabled cannot turn this into a no-op.
                GL.glDepthMask(true)
                GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
            } else {
                GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            }
        }
    }

    fun release() {
        GL.glDeleteFramebuffers(1, fbo, 0)
        GL.glDeleteTextures(1, tex, 0)
        if (rbo[0] != 0) GL.glDeleteRenderbuffers(1, rbo, 0)
    }
}

/** Single triangle covering the screen — fewer verts and no diagonal seam vs. a quad. */
class FullscreenTri {
    private val mesh = Mesh(
        floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f),
        shortArrayOf(0, 1, 2),
        listOf(Attr("aPos", 2))
    )
    fun draw() = mesh.draw()
    fun release() = mesh.release()
}
