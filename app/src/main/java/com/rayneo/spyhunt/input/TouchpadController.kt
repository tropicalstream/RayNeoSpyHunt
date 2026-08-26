package com.rayneo.spyhunt.input

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.damp
import com.rayneo.spyhunt.core.smoothstep
import com.rayneo.spyhunt.game.InputState
import kotlin.math.abs
import kotlin.math.max

/**
 * ============================================================================
 *  TEMPLE TOUCHPAD DRIVER — RayNeo X3 Pro
 * ============================================================================
 *
 * The glasses carry TWO electrically identical capacitive pads, one per temple
 * ("cyttsp5_mt" and "cyttsp6_mt"). Nothing in the device name tells us which
 * arm is which, so the first run runs a calibration: the player swipes the
 * RIGHT arm, we latch whichever pad reported the swipe, persist its name, and
 * from then on the left pad is dropped on the floor. Otherwise a stray brush of
 * the left temple would yank the car across two lanes.
 *
 * Both pads are INPUT_PROP_DIRECT with a 639 x 197 raw extent that Android maps
 * onto the 1280 x 480 display, so MotionEvent coordinates arrive in display
 * pixels. Everything below normalises by the *measured* extent rather than
 * those numbers so nothing breaks if the mapping changes.
 *
 * Axis assignment (measured, not guessed):
 *   X (long, 639 raw)  = front-to-back along the temple = STEERING
 *   Y (short, 197 raw) = across the temple              = THROTTLE
 *
 * THREADING: touch/key events are delivered on the UI thread, [update] and the
 * reads of [input] happen on the GL render thread. All mutable state is guarded
 * by [lock], and the one-frame edges are staged in `pending*` flags so an edge
 * raised microseconds after the sim called `clearEdges()` still survives to be
 * seen for exactly one frame.
 */
class TouchpadController(ctx: Context) {

    /** Per-frame input the simulation consumes. Owned and written by this class. */
    val input = InputState()

    /**
     * Raised when the player asks to re-centre the head-tracked board (volume down).
     * Wire it to [HeadTracker.recenter]. Invoked on the input thread, outside [lock],
     * so the callback must not block.
     */
    var onRecenter: (() -> Unit)? = null

    /**
     * True once the right-arm pad has been identified. Assigning `false` forgets the
     * latched pad (both pads go live again); see [resetCalibration] for the full reset.
     */
    @Volatile
    var calibrated: Boolean = false
        set(value) {
            field = value
            // clearLatch() mutates the same fields the input thread reads in bindDevice,
            // so take the lock here too — this setter is public and may be poked from
            // anywhere. The monitor is reentrant, so the internal callers that already
            // hold it (resetCalibration, latchDevice) are unaffected.
            if (!value) synchronized(lock) { clearLatch() }
        }

    /** Text the HUD shows while [calibrated] is false. Empty once we know the arm. */
    val calibrationHint: String
        get() = when {
            calibrated -> ""
            sawTapOnly -> "SWIPE - DO NOT TAP - SLIDE ALONG YOUR RIGHT TEMPLE"
            else -> "SWIPE YOUR RIGHT ARM"
        }

    private val lock = Any()
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- latched device ----
    private var rightPadName: String? = null
    /** Cached id for the fast path; ids are not stable across reboots, names are. */
    private var rightPadId: Int = Int.MIN_VALUE
    @Volatile private var sawTapOnly = false

    // ---- mapped extents, used to keep gestures resolution independent ----
    private var steerExtent: Float
    private var throttleExtent: Float

    // ---- continuous axes ----
    /** Where steering wants to be; [input].steer chases it so single samples never snap. */
    private var steerTarget = 0f
    private var throttleTarget = THROTTLE_NEUTRAL

    // ---- current gesture ----
    private var activePointerId = -1
    private var curDeviceId = Int.MIN_VALUE
    /** Null when the driver gives the device no name — see [latchDevice]. */
    private var curDeviceName: String? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTimeMs = 0L
    private var lastSampleTimeMs = 0L
    /** Largest squared deviation from the touch-down point, for the tap/long-press tests. */
    private var maxMoveSq = 0f
    private var skipFirstMove = true
    private var longPressFired = false
    /** Set when this press follows a tap: the player is holding the trigger, not opening a menu. */
    private var holdIsTrigger = false
    private var ignoreRestOfGesture = false

    private var lastTapUpMs = 0L
    /** True once a tap pair has spent its special, so a rapid chain cannot spam them. */
    private var doubleTapConsumed = false
    /** Time of the last gesture we accepted, used to reject the firmware's F-key echo. */
    private var lastGestureMs = 0L

    // ---- staged one-frame edges ----
    private var pendingFire = false
    private var pendingSpecial = false
    private var pendingMenu = false
    private var recenterRequested = false

    /** See [bindDevice]; read once at construction so it costs nothing per event. */
    private val anyInputAllowed: Boolean = try {
        android.provider.Settings.System.getInt(ctx.contentResolver, "spyhunt_any_input", 0) == 1
    } catch (_: Exception) {
        false
    }

    init {
        val dm = ctx.resources.displayMetrics
        steerExtent = max(MIN_EXTENT, dm.widthPixels.toFloat())
        throttleExtent = max(MIN_EXTENT, dm.heightPixels.toFloat())

        val saved = prefs.getString(KEY_RIGHT_PAD, null)
        if (!saved.isNullOrEmpty()) {
            rightPadName = saved
            calibrated = true
        }
        input.throttle = THROTTLE_NEUTRAL
    }

    // ------------------------------------------------------------------ touch

    fun onTouchEvent(ev: MotionEvent): Boolean = synchronized(lock) { handleTouchLocked(ev) }

    private fun handleTouchLocked(ev: MotionEvent): Boolean {
        // Fast path first: once bound, the common case is a single id comparison.
        if (ev.deviceId != rightPadId && !bindDevice(ev.deviceId, ev.device?.name)) {
            return true // wrong temple — swallow it so nothing downstream reacts
        }
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                learnExtents(ev)
                beginGesture(ev)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId >= 0) {
                    val pi = ev.findPointerIndex(activePointerId)
                    if (pi >= 0) {
                        // Batched history carries the real shape of a fast flick; without it
                        // a 120 Hz pad collapses to one sample per frame and flicks feel mushy.
                        for (h in 0 until ev.historySize) {
                            processSample(
                                ev.getHistoricalX(pi, h),
                                ev.getHistoricalY(pi, h),
                                ev.getHistoricalEventTime(h)
                            )
                        }
                        processSample(ev.getX(pi), ev.getY(pi), ev.eventTime)
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                endGesture(ev.eventTime)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                true
            }

            // Secondary fingers are ignored outright: this pad is 5 mm tall and a second
            // contact is nearly always the player's cheek or the frame of the glasses.
            MotionEvent.ACTION_POINTER_DOWN -> true

            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(ev.actionIndex) == activePointerId) cancelGesture()
                true
            }

            else -> false
        }
    }

    private fun beginGesture(ev: MotionEvent) {
        activePointerId = ev.getPointerId(0)
        curDeviceId = ev.deviceId
        curDeviceName = ev.device?.name
        downX = ev.getX(0)
        downY = ev.getY(0)
        lastX = downX
        lastY = downY
        downTimeMs = ev.eventTime
        lastSampleTimeMs = ev.eventTime
        maxMoveSq = 0f
        skipFirstMove = true
        longPressFired = false
        ignoreRestOfGesture = false
        // "Hold still after a tap" means the trigger, not the menu. Deciding it here — at
        // press time — keeps long-press and continuous fire from ever racing each other.
        holdIsTrigger = calibrated && (ev.eventTime - lastTapUpMs) <= HOLD_AFTER_TAP_MS
    }

    private fun processSample(x: Float, y: Float, tMs: Long) {
        if (ignoreRestOfGesture) return

        val dx = x - lastX
        val dy = y - lastY
        val dtMs = max(1L, tMs - lastSampleTimeMs)
        lastX = x
        lastY = y
        lastSampleTimeMs = tMs

        // The cypress tracker is still settling on the first sample after ACTION_DOWN and
        // reports a position several tens of pixels from the real contact point.
        if (skipFirstMove) {
            skipFirstMove = false
            return
        }

        // Debounce: no finger crosses 40% of an axis inside a single input sample, so a
        // jump that large is the pad glitching, not the player.
        if (abs(dx) > steerExtent * NOISE_FRAC || abs(dy) > throttleExtent * NOISE_FRAC) return

        val ddx = x - downX
        val ddy = y - downY
        val d2 = ddx * ddx + ddy * ddy
        if (d2 > maxMoveSq) maxMoveSq = d2

        if (!calibrated) {
            // Only a real swipe identifies the arm — a tap could be either temple brushing
            // the frame, and latching on that would bind the wrong pad forever.
            val need = steerExtent * SWIPE_LATCH_FRAC
            if (d2 >= need * need) latchDevice(curDeviceId, curDeviceName)
            return
        }

        val dtSec = dtMs * 0.001f

        // Steering is relative and velocity-flavoured: the same 200 px of travel is a fine
        // trim when it takes half a second and a decisive lane change when it takes 60 ms.
        val speedPxPerSec = abs(dx) / dtSec
        val boost = 1f + smoothstep(FLICK_LO, FLICK_HI, speedPxPerSec) * FLICK_BOOST
        val norm = dx / steerExtent * STEER_AXIS_SIGN
        steerTarget = clamp(steerTarget + norm * STEER_GAIN * boost, -1f, 1f)

        // Screen Y grows downward, so swiping "up" (accelerate) is a negative dy.
        throttleTarget = clamp(
            throttleTarget + dy / throttleExtent * THROTTLE_GAIN * THROTTLE_AXIS_SIGN, 0f, 1f
        )
    }

    private fun endGesture(tMs: Long) {
        if (activePointerId < 0) return
        val heldMs = tMs - downTimeMs
        val wasTap = !ignoreRestOfGesture &&
            heldMs <= TAP_MAX_MS &&
            maxMoveSq <= TAP_MAX_MOVE_PX * TAP_MAX_MOVE_PX

        if (wasTap) {
            if (!calibrated) {
                // Player tapped when we asked for a swipe — nudge the hint text.
                sawTapOnly = true
            } else {
                pendingFire = true
                val sinceLastTap = tMs - lastTapUpMs
                if (sinceLastTap <= DOUBLE_TAP_MS && !doubleTapConsumed) {
                    pendingSpecial = true
                    // Latch so a machine-gun tap chain spends exactly one special per pair
                    // instead of one per tap from the second onward.
                    doubleTapConsumed = true
                } else if (sinceLastTap > DOUBLE_TAP_MS) {
                    doubleTapConsumed = false
                }
                // Always the most recent tap: the trigger-hold test below reads this too.
                lastTapUpMs = tMs
                lastGestureMs = tMs
            }
        } else if (calibrated && !ignoreRestOfGesture) {
            // A swipe breaks the tap chain, so a hold after it opens the menu, not the gun.
            lastGestureMs = tMs
            lastTapUpMs = 0L
            doubleTapConsumed = false
        }

        activePointerId = -1
        input.fireHeld = false
    }

    private fun cancelGesture() {
        activePointerId = -1
        longPressFired = false
        holdIsTrigger = false
        ignoreRestOfGesture = false
        input.fireHeld = false
    }

    // -------------------------------------------------------------------- keys

    fun onKeyEvent(ev: KeyEvent): Boolean {
        // Never swallow power: the OS must always be able to sleep or wake the glasses.
        if (ev.keyCode == KeyEvent.KEYCODE_POWER) return false
        val consumed = synchronized(lock) { handleKeyLocked(ev) }
        if (recenterRequested) {
            recenterRequested = false
            onRecenter?.invoke()
        }
        return consumed
    }

    private fun handleKeyLocked(ev: KeyEvent): Boolean {
        val edge = ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0

        // gpio-keys / qpnp_pon are chassis buttons, not temple pads, so they bypass the
        // calibration filter entirely.
        when (ev.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (edge) pendingMenu = true
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (edge) recenterRequested = true
                return true
            }
        }

        if (!isPadKey(ev)) return false
        if (ev.deviceId != rightPadId && !bindDevice(ev.deviceId, ev.device?.name)) return true
        if (!edge) return true

        // Some firmware reports a gesture twice: once as touch samples and once as an
        // F-key. Drop the key if a touch is live or we just accepted a touch gesture.
        if (activePointerId >= 0 || ev.eventTime - lastGestureMs < KEY_ECHO_MS) return true

        if (!calibrated) {
            // Only the swipe keys can complete calibration, matching the touch rule.
            if (ev.keyCode == KeyEvent.KEYCODE_F4 || ev.keyCode == KeyEvent.KEYCODE_F5) {
                latchDevice(ev.deviceId, ev.device?.name)
            } else {
                sawTapOnly = true
            }
            return true
        }

        // Defensive mapping: the firmware's gesture-to-keycode table is undocumented and
        // varies by build, so every pad key lands on something sane rather than nothing.
        when (ev.keyCode) {
            KeyEvent.KEYCODE_F1 -> pendingFire = true
            KeyEvent.KEYCODE_F2 -> {
                pendingSpecial = true
                pendingFire = true
            }

            KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F8 -> pendingMenu = true
            KeyEvent.KEYCODE_F4 ->
                steerTarget = clamp(steerTarget + KEY_STEER_NUDGE * STEER_AXIS_SIGN, -1f, 1f)

            KeyEvent.KEYCODE_F5 ->
                steerTarget = clamp(steerTarget - KEY_STEER_NUDGE * STEER_AXIS_SIGN, -1f, 1f)

            KeyEvent.KEYCODE_F6 ->
                throttleTarget = clamp(throttleTarget + KEY_THROTTLE_NUDGE, 0f, 1f)

            KeyEvent.KEYCODE_F7 ->
                throttleTarget = clamp(throttleTarget - KEY_THROTTLE_NUDGE, 0f, 1f)

            else -> pendingFire = true // KEY_LINEFEED and anything else the pad invents
        }
        lastGestureMs = ev.eventTime
        return true
    }

    private fun isPadKey(ev: KeyEvent): Boolean =
        ev.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F8 || ev.scanCode == SCAN_LINEFEED

    // ------------------------------------------------------------------ frame

    /** Settles the continuous axes and publishes staged edges. Call once per frame. */
    fun update(dt: Float) {
        synchronized(lock) { updateLocked(dt) }
    }

    private fun updateLocked(dt: Float) {
        val now = SystemClock.uptimeMillis()
        val touching = activePointerId >= 0

        if (touching) {
            // Watchdog: these pads occasionally drop the UP event and leave a phantom
            // finger pinned to the glass, which would freeze the steering trim.
            if (now - lastSampleTimeMs > STUCK_TOUCH_MS) {
                cancelGesture()
            } else if (calibrated && !ignoreRestOfGesture) {
                val heldMs = now - downTimeMs
                val still = maxMoveSq <= LONG_PRESS_MAX_MOVE_PX * LONG_PRESS_MAX_MOVE_PX
                if (!still) {
                    input.fireHeld = false
                } else if (holdIsTrigger) {
                    // Tap, then press and hold: keep the trigger down.
                    input.fireHeld = heldMs > TAP_MAX_MS
                } else if (!longPressFired && heldMs >= LONG_PRESS_MS) {
                    longPressFired = true
                    pendingMenu = true
                    lastGestureMs = now
                }
            }
        } else {
            input.fireHeld = false
        }

        if (!touching) {
            // Spring the wheel back to centre on lift; a relative axis that kept its last
            // demand would leave the car quietly drifting into the verge.
            steerTarget = damp(steerTarget, 0f, STEER_RETURN_RATE, dt)
            // Throttle bleeds back to cruise far more slowly — it is a held setting, but a
            // stray full-brake swipe must not be permanent.
            throttleTarget = damp(throttleTarget, THROTTLE_NEUTRAL, THROTTLE_SETTLE_RATE, dt)
        }

        input.steer = damp(input.steer, steerTarget, STEER_FOLLOW_RATE, dt)
        input.throttle = damp(input.throttle, throttleTarget, THROTTLE_FOLLOW_RATE, dt)

        // Edges are staged rather than written directly so one raised between the sim's
        // read and its clearEdges() call still gets exactly one frame of visibility.
        if (pendingFire) {
            input.firePressed = true
            pendingFire = false
        }
        if (pendingSpecial) {
            input.specialPressed = true
            pendingSpecial = false
        }
        if (pendingMenu) {
            input.menuPressed = true
            pendingMenu = false
        }
    }

    // ----------------------------------------------------------- calibration

    fun resetCalibration() {
        synchronized(lock) {
            calibrated = false // setter clears and forgets the latched pad
            sawTapOnly = false
            cancelGesture()
            steerTarget = 0f
            throttleTarget = THROTTLE_NEUTRAL
            input.steer = 0f
            input.throttle = THROTTLE_NEUTRAL
            lastTapUpMs = 0L
            doubleTapConsumed = false
            lastGestureMs = 0L
        }
    }

    private fun latchDevice(deviceId: Int, name: String?) {
        rightPadId = deviceId
        // Only a real driver name is worth persisting: ids are reassigned on every boot.
        // Storing a synthetic "#id" would latch a pad that can never match again, and
        // since an unmatched pad has all of its events swallowed the game would come up
        // permanently uncontrollable with no way back short of clearing app data. An
        // unnamed pad is therefore latched for this session only and re-calibrates next
        // launch, which is recoverable.
        val persistent = name?.takeIf { it.isNotEmpty() }
        rightPadName = persistent
        if (persistent != null) prefs.edit().putString(KEY_RIGHT_PAD, persistent).apply()
        calibrated = true
        sawTapOnly = false
        // Drop the remainder of the calibration swipe: it was an instruction, not a steer.
        ignoreRestOfGesture = true
        steerTarget = 0f
        throttleTarget = THROTTLE_NEUTRAL
        input.steer = 0f
        input.throttle = THROTTLE_NEUTRAL
    }

    private fun clearLatch() {
        rightPadName = null
        rightPadId = Int.MIN_VALUE
        prefs.edit().remove(KEY_RIGHT_PAD).apply()
    }

    /**
     * True when [deviceId] is the latched right-arm pad, re-binding the volatile id to the
     * persisted name when needed. Before calibration every device passes.
     */
    private fun bindDevice(deviceId: Int, name: String?): Boolean {
        // Test hatch. SELinux stops `adb shell sendevent` writing to /dev/input, so the
        // only way to drive the game from a host is `adb shell input`, whose synthetic
        // events come from a different device and are correctly rejected by the filter
        // below. This lets a developer opt out of the filter for a session:
        //     adb shell settings put system spyhunt_any_input 1
        // Default 0, so a normal launch still honours the calibrated pad.
        if (anyInputAllowed) return true

        // No name to match against: before calibration every device passes, but once an
        // unnamed pad has been latched only its (session-stable) id may speak.
        val want = rightPadName ?: return rightPadId == Int.MIN_VALUE
        if (name != null && name == want) {
            rightPadId = deviceId
            return true
        }
        return false
    }

    /**
     * Prefers the driver's own mapped range over display metrics — if the pad is ever
     * mapped to a sub-region of the screen, the metrics would over-estimate the travel and
     * the steering would feel dead.
     */
    private fun learnExtents(ev: MotionEvent) {
        val dev = ev.device ?: return
        val rx = dev.getMotionRange(MotionEvent.AXIS_X)
        if (rx != null && rx.range > MIN_EXTENT) steerExtent = rx.range
        val ry = dev.getMotionRange(MotionEvent.AXIS_Y)
        if (ry != null && ry.range > MIN_EXTENT) throttleExtent = ry.range
    }
}

private const val PREFS_NAME = "spyhunt_input"
private const val KEY_RIGHT_PAD = "right_pad_device_name"

/** Smallest believable axis travel, in mapped pixels. Guards against a bogus range of 0. */
private const val MIN_EXTENT = 64f

private const val TAP_MAX_MS = 180L
private const val TAP_MAX_MOVE_PX = 28f
private const val DOUBLE_TAP_MS = 280L
private const val HOLD_AFTER_TAP_MS = 280L
private const val LONG_PRESS_MS = 600L
private const val LONG_PRESS_MAX_MOVE_PX = 40f
private const val STUCK_TOUCH_MS = 15_000L
private const val KEY_ECHO_MS = 250L

/** Single-sample jump beyond this fraction of an axis is pad noise. */
private const val NOISE_FRAC = 0.40f

/** Travel needed during calibration to count as a swipe rather than a tap. */
private const val SWIPE_LATCH_FRAC = 0.10f

/**
 * +1 when increasing mapped X points toward the lens (forward along the temple), which is
 * the direction that must steer right. Flip this single constant if steering feels mirrored.
 */
private const val STEER_AXIS_SIGN = 1f

/** Screen Y grows downward while "up the temple" must accelerate, hence the inversion. */
private const val THROTTLE_AXIS_SIGN = -1f

/** Pad fractions to full lock: ~42% of the long axis in one drag reaches the stop. */
private const val STEER_GAIN = 2.4f
private const val FLICK_LO = 900f
private const val FLICK_HI = 4500f
private const val FLICK_BOOST = 1.4f
private const val STEER_RETURN_RATE = 6.5f
private const val STEER_FOLLOW_RATE = 24f

private const val THROTTLE_NEUTRAL = 0.5f
private const val THROTTLE_GAIN = 1.4f
private const val THROTTLE_SETTLE_RATE = 0.9f
private const val THROTTLE_FOLLOW_RATE = 10f

private const val KEY_STEER_NUDGE = 0.55f
private const val KEY_THROTTLE_NUDGE = 0.20f

/** Linux KEY_LINEFEED; Android has no keycode for it, so it arrives by scan code. */
private const val SCAN_LINEFEED = 101
