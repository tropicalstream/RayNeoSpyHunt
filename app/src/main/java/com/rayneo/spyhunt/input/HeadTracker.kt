package com.rayneo.spyhunt.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import com.rayneo.spyhunt.core.DEG2RAD
import com.rayneo.spyhunt.core.angleDelta
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.damp
import com.rayneo.spyhunt.game.InputState
import kotlin.math.sqrt

/**
 * ============================================================================
 *  HEAD TRACKING
 * ============================================================================
 *
 * Reports head orientation RELATIVE to wherever the player was looking when the
 * game started (or last re-centred), so the hologram board lands in front of
 * them instead of at magnetic north. Yaw/pitch/roll are clamped to +/-35 deg —
 * the waveguide FOV is narrow and a board that can be driven off the edge of
 * the display is a board the player loses.
 *
 * TYPE_GAME_ROTATION_VECTOR is preferred: it fuses gyro + accelerometer only,
 * so there is no magnetometer to be dragged around by the glasses' own speaker
 * magnets, and a slow yaw drift is irrelevant when everything is measured
 * against a reference we can re-capture on demand.
 *
 * THREADING: samples are processed on a private HandlerThread (never the UI
 * thread — a 200 Hz callback there would show up as frame jitter), and the
 * three results are published through volatile fields that [applyTo] reads from
 * the GL render thread. Nothing in [onSensorChanged] allocates.
 */
class HeadTracker(private val ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var thread: HandlerThread? = null
    private var running = false

    // ---- published results, render thread reads these ----
    @Volatile private var outYaw = 0f
    @Volatile private var outPitch = 0f
    @Volatile private var outRoll = 0f
    @Volatile private var recenterPending = true

    // ---- sensor-thread scratch; preallocated because this runs up to 200 Hz ----
    private val quat = FloatArray(4)
    private val rm = FloatArray(16)
    private val remapped = FloatArray(16)
    private val orient = FloatArray(3)

    private var axisX = SensorManager.AXIS_X
    private var axisY = SensorManager.AXIS_Y

    private var haveRef = false
    private var refYaw = 0f
    private var refPitch = 0f
    private var refRoll = 0f
    private var lastNs = 0L
    private var smYaw = 0f
    private var smPitch = 0f
    private var smRoll = 0f

    fun start() {
        if (running) return
        val s = sensor ?: return
        computeAxisRemap()
        haveRef = false
        recenterPending = true
        lastNs = 0L
        val t = HandlerThread("spyhunt-head")
        t.start()
        thread = t
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper))
        running = true
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    /** Writes the latest smoothed, re-centred, clamped orientation into [input]. */
    fun applyTo(input: InputState) {
        input.headYaw = outYaw
        input.headPitch = outPitch
        input.headRoll = outRoll
    }

    /** Re-anchors the board to the current head direction on the next sample. */
    fun recenter() {
        recenterPending = true
        if (!running) {
            // No sensor thread will service the request, so answer it here.
            outYaw = 0f
            outPitch = 0f
            outRoll = 0f
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        if (v.size < 3) return

        quat[0] = v[0]
        quat[1] = v[1]
        quat[2] = v[2]
        // Some drivers hand back only the vector part, others append an accuracy estimate
        // as a fifth element that getRotationMatrixFromVector rejects. Normalise to 4.
        quat[3] = if (v.size >= 4) {
            v[3]
        } else {
            val t = 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]
            if (t > 0f) sqrt(t) else 0f
        }

        SensorManager.getRotationMatrixFromVector(rm, quat)
        SensorManager.remapCoordinateSystem(rm, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orient)

        // getOrientation returns azimuth about -Z, so negating gives "turn right = +yaw";
        // negating pitch gives "look up = +pitch". Both match the game's +X right, +Y up.
        val yawRaw = -orient[0]
        val pitchRaw = -orient[1]
        val rollRaw = orient[2]

        if (recenterPending || !haveRef) {
            refYaw = yawRaw
            refPitch = pitchRaw
            refRoll = rollRaw
            haveRef = true
            recenterPending = false
            smYaw = 0f
            smPitch = 0f
            smRoll = 0f
            outYaw = 0f
            outPitch = 0f
            outRoll = 0f
            lastNs = e.timestamp
            return
        }

        // Sensor timestamps are the only clock that matches the sample rate; clamp because
        // a scheduling hiccup would otherwise teleport the smoothed value.
        val dt = if (lastNs == 0L) NOMINAL_DT
        else clamp((e.timestamp - lastNs) * 1e-9f, 1e-3f, 0.1f)
        lastNs = e.timestamp

        // angleDelta unwraps the +/-PI seam, so sweeping the head across it does not flip
        // the board end for end.
        val ty = clamp(angleDelta(refYaw, yawRaw), -MAX_TILT, MAX_TILT)
        val tp = clamp(angleDelta(refPitch, pitchRaw), -MAX_TILT, MAX_TILT)
        val tr = clamp(angleDelta(refRoll, rollRaw), -MAX_TILT, MAX_TILT)

        smYaw = damp(smYaw, ty, SMOOTH_RATE, dt)
        smPitch = damp(smPitch, tp, SMOOTH_RATE, dt)
        smRoll = damp(smRoll, tr, SMOOTH_RATE, dt)

        outYaw = smYaw
        outPitch = smPitch
        outRoll = smRoll
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
        // Game rotation vector has no calibration state worth reacting to, and reacting to
        // the magnetometer's would only produce a visible jolt mid-race.
    }

    /**
     * Sensor axes are defined against the device's NATURAL orientation, which on these
     * glasses may or may not be the landscape one the display uses. Remapping by the live
     * display rotation is the only version that is right on every firmware build.
     */
    private fun computeAxisRemap() {
        when (displayRotation()) {
            Surface.ROTATION_90 -> {
                axisX = SensorManager.AXIS_Y
                axisY = SensorManager.AXIS_MINUS_X
            }

            Surface.ROTATION_180 -> {
                axisX = SensorManager.AXIS_MINUS_X
                axisY = SensorManager.AXIS_MINUS_Y
            }

            Surface.ROTATION_270 -> {
                axisX = SensorManager.AXIS_MINUS_Y
                axisY = SensorManager.AXIS_X
            }

            else -> {
                axisX = SensorManager.AXIS_X
                axisY = SensorManager.AXIS_Y
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.display?.rotation ?: Surface.ROTATION_0
        } else {
            // defaultDisplay is deprecated from API 30 but is the only route on API 29.
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
    } catch (t: Throwable) {
        // Context.getDisplay() throws on a non-visual context; assume the natural landscape.
        Surface.ROTATION_0
    }
}

/** Board stays reachable: never report more than this off the reference direction. */
private const val MAX_TILT = 35f * DEG2RAD

/** Approach rate for the smoothing filter, per second. High enough to feel attached. */
private const val SMOOTH_RATE = 12f

/** Used for the very first dt, before two timestamps exist to subtract. */
private const val NOMINAL_DT = 1f / 60f
