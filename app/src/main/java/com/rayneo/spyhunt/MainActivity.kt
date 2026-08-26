package com.rayneo.spyhunt

import android.annotation.SuppressLint
import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.rayneo.spyhunt.audio.AudioEngine
import com.rayneo.spyhunt.input.HeadTracker
import com.rayneo.spyhunt.input.TouchpadController
import com.rayneo.spyhunt.render.GameRenderer

/**
 * Host activity for the RayNeo X3 Pro.
 *
 * The glasses present a single 1280x480 surface that is physically a
 * side-by-side stereo pair, so the app simply takes the whole window and splits
 * it itself — there is no compositor doing the duplication for us.
 */
class MainActivity : Activity() {

    private lateinit var view: GameView
    private lateinit var touch: TouchpadController
    private lateinit var head: HeadTracker
    private lateinit var audio: AudioEngine
    private lateinit var renderer: GameRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        touch = TouchpadController(this)
        head = HeadTracker(this)
        audio = AudioEngine(this)
        renderer = GameRenderer(touch, head, audio)

        view = GameView(this, renderer, touch)
        setContentView(view)
        goImmersive()
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        view.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
        head.start()
        audio.start()
    }

    override fun onPause() {
        super.onPause()
        audio.stop()
        head.stop()
        view.onPause()
    }

    /**
     * Temple-pad gestures arrive as key events on some firmware builds, and the
     * frame buttons are ordinary keys. Everything except POWER is ours.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(event)
        if (touch.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}

/**
 * GLSurfaceView configured for an ES 3.x context with no alpha channel — the
 * waveguide treats black as transparent optically, so a translucent window
 * would only cost fill rate without changing what the wearer sees.
 */
@SuppressLint("ViewConstructor")
class GameView(
    ctx: android.content.Context,
    private val renderer: GameRenderer,
    private val touch: TouchpadController
) : GLSurfaceView(ctx) {

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Gestures are consumed on the UI thread and read on the GL thread; the
        // controller owns the handoff.
        return touch.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        queueEvent { renderer.release() }
        super.onDetachedFromWindow()
    }
}
