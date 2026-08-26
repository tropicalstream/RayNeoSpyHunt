package com.rayneo.spyhunt.render

import android.opengl.GLES30 as GL
import com.rayneo.spyhunt.core.Fbo
import com.rayneo.spyhunt.core.FullscreenTri
import com.rayneo.spyhunt.core.Program

/**
 * HDR + bloom post chain, sized for ONE eye.
 *
 * The eyes are deliberately processed separately rather than blooming the whole
 * 1280x480 framebuffer in one go. A shared blur would smear light across the
 * seam at x=640, leaking the left eye's highlights into the right eye's image —
 * which the visual system reads as a ghost and refuses to fuse. Running the
 * chain twice on a 640x480 target costs almost nothing at these resolutions.
 */
class PostChain(val eyeW: Int, val eyeH: Int) {

    object Tune {
        // Threshold above 1.0 means only genuinely over-bright emissives bloom, so
        // lit surfaces and HUD strokes stay crisp instead of smearing into halos.
        var threshold = 1.05f
        var knee = 0.45f
        var bloomNear = 0.48f
        var bloomWide = 0.32f
        var exposure = 1.15f
        var gamma = 2.2f
        /** Where the soft border begins (0..1 of the half-extent). */
        var edgeFade = 0.80f
    }

    /**
     * 1983 presentation. Bloom is not switched fully off — a trace of it keeps the
     * image from looking dead on a waveguide, where there is no ambient light to
     * lift the picture — but it is far below the neon setting, and the composite
     * additionally pixelates, posterises and scanlines.
     */
    object VintageTune {
        var threshold = 1.6f
        var knee = 0.2f
        var bloomNear = 0.10f
        var bloomWide = 0.05f
        var exposure = 1.0f
        var gamma = 2.2f
        var edgeFade = 0.86f
        /** Arcade-ish block count per eye. 640x480 / ~2.9 gives chunky but readable. */
        var pixelX = 224f
        var pixelY = 168f
        /** Colour levels per channel. Sprite hardware had very few. */
        var posterize = 6f
        var scanline = 0.30f
    }

    /** Selected presentation; set once per frame before [resolveTo]. */
    var vintage = false

    /** Scene target. Half-float so emissive materials can exceed 1.0. */
    val hdr = Fbo(eyeW, eyeH, halfFloat = true, depth = true)

    private val bright = Fbo(eyeW / 2, eyeH / 2, halfFloat = true)
    private val blurA = Fbo(eyeW / 4, eyeH / 4, halfFloat = true)
    private val blurB = Fbo(eyeW / 4, eyeH / 4, halfFloat = true)
    private val blurC = Fbo(eyeW / 8, eyeH / 8, halfFloat = true)
    private val blurD = Fbo(eyeW / 8, eyeH / 8, halfFloat = true)

    private val pBright = Program(Shaders.POST_VS, Shaders.BRIGHT_FS, "bright")
    private val pBlur = Program(Shaders.POST_VS, Shaders.BLUR_FS, "blur")
    private val pDown = Program(Shaders.POST_VS, Shaders.DOWN_FS, "down")
    private val pComposite = Program(Shaders.POST_VS, Shaders.COMPOSITE_FS, "composite")
    private val pCompositeVintage =
        Program(Shaders.POST_VS, VintageShaders.COMPOSITE_FS, "compositeVintage")

    private val tri = FullscreenTri()

    /** Full-screen white flash, set by the renderer on big impacts. */
    var flash = 0f

    /** Binds the HDR target and clears it. Draw the scene after calling this. */
    fun beginScene() {
        // Depth writes MUST be re-enabled before the clear: glClear(GL_DEPTH_BUFFER_BIT)
        // is gated by the depth mask, and resolveTo() leaves it false. Clearing first
        // and unmasking after silently keeps last frame's depth buffer, so geometry
        // gets rejected against stale values and survives only in slivers — while the
        // depth-test-free HUD looks perfectly fine and hides the cause.
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthMask(true)
        hdr.bind(clear = true)
        GL.glDisable(GL.GL_BLEND)
    }

    /**
     * Runs bloom and composites into the default framebuffer at the given
     * viewport — which is how each eye lands in its half of the 1280x480 screen.
     */
    fun resolveTo(viewportX: Int, viewportY: Int, viewportW: Int, viewportH: Int) {
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDepthMask(false)
        GL.glDisable(GL.GL_BLEND)

        // 1. bright pass + downsample to 1/2
        bright.bind()
        pBright.use()
        pBright.tex("uTex", 0, hdr.tex[0])
        pBright.u2f("uTexel", 1f / eyeW, 1f / eyeH)
        pBright.u1f("uThreshold", if (vintage) VintageTune.threshold else Tune.threshold)
        pBright.u1f("uKnee", if (vintage) VintageTune.knee else Tune.knee)
        tri.draw()

        // 2. downsample 1/2 -> 1/4, then blur horizontally and vertically
        blurA.bind()
        pDown.use()
        pDown.tex("uTex", 0, bright.tex[0])
        pDown.u2f("uTexel", 2f / eyeW, 2f / eyeH)
        tri.draw()

        blurB.bind()
        pBlur.use()
        pBlur.tex("uTex", 0, blurA.tex[0])
        pBlur.u2f("uDir", 4f / eyeW, 0f)
        tri.draw()

        blurA.bind()
        pBlur.use()
        pBlur.tex("uTex", 0, blurB.tex[0])
        pBlur.u2f("uDir", 0f, 4f / eyeH)
        tri.draw()
        // blurA now holds the tight bloom octave.

        // 3. second, wider octave at 1/8
        blurC.bind()
        pDown.use()
        pDown.tex("uTex", 0, blurA.tex[0])
        pDown.u2f("uTexel", 4f / eyeW, 4f / eyeH)
        tri.draw()

        blurD.bind()
        pBlur.use()
        pBlur.tex("uTex", 0, blurC.tex[0])
        pBlur.u2f("uDir", 8f / eyeW, 0f)
        tri.draw()

        blurC.bind()
        pBlur.use()
        pBlur.tex("uTex", 0, blurD.tex[0])
        pBlur.u2f("uDir", 0f, 8f / eyeH)
        tri.draw()
        // blurC now holds the wide bloom octave.

        // 4. composite into this eye's half of the screen
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(viewportX, viewportY, viewportW, viewportH)

        val p = if (vintage) pCompositeVintage else pComposite
        p.use()
        p.tex("uScene", 0, hdr.tex[0])
        p.tex("uBloomNear", 1, blurA.tex[0])
        p.tex("uBloomWide", 2, blurC.tex[0])
        if (vintage) {
            p.u1f("uBloomNearAmt", VintageTune.bloomNear)
            p.u1f("uBloomWideAmt", VintageTune.bloomWide)
            p.u1f("uExposure", VintageTune.exposure)
            p.u1f("uGamma", VintageTune.gamma)
            p.u1f("uEdgeFade", VintageTune.edgeFade)
            p.u2f("uPixelGrid", VintageTune.pixelX, VintageTune.pixelY)
            p.u1f("uPosterize", VintageTune.posterize)
            p.u1f("uScanline", VintageTune.scanline)
        } else {
            p.u1f("uBloomNearAmt", Tune.bloomNear)
            p.u1f("uBloomWideAmt", Tune.bloomWide)
            p.u1f("uExposure", Tune.exposure)
            p.u1f("uGamma", Tune.gamma)
            p.u1f("uEdgeFade", Tune.edgeFade)
        }
        p.u1f("uFlash", flash)
        tri.draw()
    }

    fun release() {
        hdr.release(); bright.release()
        blurA.release(); blurB.release(); blurC.release(); blurD.release()
        pBright.release(); pBlur.release(); pDown.release()
        pComposite.release(); pCompositeVintage.release()
        tri.release()
    }
}
