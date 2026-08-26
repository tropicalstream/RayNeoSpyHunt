package com.rayneo.spyhunt.render

import android.opengl.Matrix
import com.rayneo.spyhunt.core.DEG2RAD
import com.rayneo.spyhunt.core.M4
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.damp
import com.rayneo.spyhunt.core.fbm
import com.rayneo.spyhunt.game.GameState
import com.rayneo.spyhunt.game.InputState
import kotlin.math.sqrt

/**
 * Stereo camera rig for the RayNeo X3 Pro.
 *
 * ## Why the scene is rendered as a miniature
 *
 * The framebuffer is 1280x480 = two 640x480 eye views side by side. Depth
 * perception from stereo comes from binocular disparity, which falls off as
 * 1/distance: with a 63 mm IPD, a point 60 m away has ~1 mrad (0.06 deg) of
 * disparity — utterly invisible. An overhead racing camera naturally sits tens
 * of metres from the action, so rendering at true world scale would produce a
 * picture that is *technically* stereo and *perceptually* flat.
 *
 * The fix is to shrink the entire scene, camera included, in eye space. Uniform
 * scaling leaves every projected angle identical — the framing is untouched —
 * but pulls the content from ~60 m to ~3.4 m, where disparity is ~1 degree and
 * reads clearly. The game becomes a hologram diorama floating in the room,
 * which is also a far more natural thing for AR glasses to show than a
 * simulated flat screen.
 *
 * ## Transform chain
 *
 *     eye = T(-eyeShift) . R(headLock) . S(boardScale) . V_world . model
 *
 * `V_world` frames the action in metres (readable physics numbers), `S` turns
 * it into a tabletop, `R` counter-rotates for partial world-locking, and
 * `T(-eyeShift)` separates the eyes. Projection is an off-axis (parallel-axis)
 * frustum rather than toed-in, so there is no vertical disparity and no eye
 * strain — see [M4.perspectiveOffAxis].
 */
class StereoRig {

    object Config {
        /** Interpupillary distance, metres. 63 mm is the adult median. */
        var ipd = 0.063f

        /**
         * Per-eye vertical FOV. The X3 Pro is ~50 deg diagonal; at the 4:3 aspect
         * of a 640x480 eye view that works out to ~40.5 deg horizontal / ~31.2 deg
         * vertical. Rendering at the true optical FOV is what keeps the board
         * feeling physically present instead of sliding when you move.
         */
        var fovYDeg = 31.2f

        /** Clip planes, in *board* metres (i.e. after boardScale). */
        var near = 0.35f
        var far = 24f

        /** Camera framing, in world metres. */
        var camHeight = 40f
        var camBack = 47f
        var camAimY = 1.5f
        /** Where the camera looks, as ground distance ahead of the camera. */
        var camAimAhead = 59.3f

        /**
         * World -> board scale. Chosen so the player's car lands ~3.4 m from the
         * viewer: sqrt(40^2 + 47^2) * 0.055 = 3.39 m.
         */
        var boardScale = 0.055f

        /**
         * Zero-parallax distance in board metres. Set slightly *beyond* the car so
         * most of the scene sits at or behind the screen plane — far more
         * comfortable to fuse than content popping out toward the nose.
         */
        var convergence = 3.8f

        /**
         * How strongly the board stays fixed in the room as the head turns.
         * 0 = welded to the face (always playable, zero presence),
         * 1 = fully world-locked (most presence, easy to look away from).
         * A modest value gives parallax that reads as solidity without ever
         * letting the player lose the board.
         */
        var worldLock = 0.22f

        /** How far the camera drifts laterally to follow the car (0..1). */
        var lateralFollow = 0.55f
        /** Extra pull-back at top speed, metres. Sells acceleration. */
        var speedPullback = 9f
    }

    /**
     * Sign conventions produced by HeadTracker (verified against its source, not
     * assumed): yaw is POSITIVE when turning RIGHT, pitch is POSITIVE when
     * looking UP, roll is the raw SensorManager.getOrientation roll.
     *
     * These signs matter more than they look. Get one backwards and world-lock
     * *adds* to head motion instead of cancelling it, which reads as the board
     * swimming away from you — the single most reliable way to make an AR app
     * nauseating. If world-lock ever feels like it exaggerates head movement,
     * flip the offending constant here.
     */
    private val yawSign = -1f     // rig math below wants left-positive
    private val pitchSign = -1f   // rig math below wants look-down-positive
    private val rollSign = -1f

    // Per-eye matrices. Index 0 = left, 1 = right.
    val view = Array(2) { M4.identity() }
    val proj = Array(2) { M4.identity() }
    val viewProj = Array(2) { M4.identity() }

    /**
     * Camera basis in *world* space, for billboarding particles and trails.
     * Deliberately taken from the centre (cyclopean) view and shared by both
     * eyes: giving each eye its own billboard orientation would make sprites
     * disagree between eyes and break fusion.
     */
    var camRightX = 1f; var camRightY = 0f; var camRightZ = 0f
    var camUpX = 0f; var camUpY = 1f; var camUpZ = 0f
    var camFwdX = 0f; var camFwdY = 0f; var camFwdZ = 1f

    /** Smoothed camera state, so the view never snaps. */
    private var camX = 0f
    private var camPullback = 0f
    private var shakeX = 0f
    private var shakeY = 0f

    /**
     * Handedness bridge, world -> GL.
     *
     * GameState defines +X right, +Y up, +Z forward, which is a LEFT-handed
     * basis. OpenGL's eye space is right-handed with the camera looking down
     * -Z. Aiming a right-handed lookAt along +Z therefore cannot preserve both
     * "world right is screen right" and "world up is screen up" — one of them
     * inverts, and the whole scene renders mirrored (which is exactly how it
     * first came up on the glasses: legible geometry, backwards text).
     *
     * Negating Z on the way in converts the left-handed world into a
     * right-handed one, after which a textbook lookAt behaves. The cost is that
     * the determinant flips sign, so triangle winding reverses too — hence
     * glFrontFace(GL_CW) in GameRenderer.
     */
    private val flipZ = M4.scale(1f, 1f, -1f)
    private val lookTmp = M4.identity()

    private val viewCenter = M4.identity()
    private val scaled = M4.identity()
    private val headRot = M4.identity()
    private val tmpA = M4.identity()
    private val tmpB = M4.identity()
    private val eyeShiftM = M4.identity()

    private var aspect = 4f / 3f

    fun resize(eyeW: Int, eyeH: Int) {
        aspect = eyeW.toFloat() / eyeH.toFloat()
    }

    fun update(state: GameState, input: InputState, dt: Float) {
        // --- follow the car laterally, but lag it so steering reads as motion ---
        val targetX = state.player.x * Config.lateralFollow
        camX = damp(camX, targetX, 4.5f, dt)

        // --- pull back with speed ---
        val speedT = clamp((state.speed - 30f) / 55f, 0f, 1f)
        camPullback = damp(camPullback, speedT * Config.speedPullback, 3f, dt)

        // --- impact shake: two decorrelated noise bands so it never looks periodic ---
        val s = state.shake
        if (s > 0.001f) {
            val t = state.time * 34f
            shakeX = fbm(t, 2, 11) * s * 2.4f
            shakeY = fbm(t + 77f, 2, 29) * s * 1.7f
        } else {
            shakeX = 0f; shakeY = 0f
        }

        // ------------------------------------------------------------------
        // Centre view, in world metres, with the player at local Z = 0.
        // (The renderer subtracts player.z from all geometry — see GameState.)
        // ------------------------------------------------------------------
        val ex = camX + shakeX
        val ey = Config.camHeight + shakeY
        val ez = -(Config.camBack + camPullback)
        val tx = state.player.x * (Config.lateralFollow * 0.65f)
        val ty = Config.camAimY
        val tz = ez + Config.camAimAhead

        // Camera and target are expressed in the left-handed game space, so their
        // Z is negated here to place them in the right-handed space the lookAt
        // builds; flipZ then carries the rest of the scene across.
        Matrix.setLookAtM(lookTmp, 0, ex, ey, -ez, tx, ty, -tz, 0f, 1f, 0f)
        M4.mul(lookTmp, flipZ, viewCenter)

        // World-space camera basis = rows of the view matrix's rotation block.
        // Column-major FloatArray: element (row r, col c) is m[c*4 + r].
        camRightX = viewCenter[0]; camRightY = viewCenter[4]; camRightZ = viewCenter[8]
        camUpX = viewCenter[1]; camUpY = viewCenter[5]; camUpZ = viewCenter[9]
        camFwdX = -viewCenter[2]; camFwdY = -viewCenter[6]; camFwdZ = -viewCenter[10]

        // ------------------------------------------------------------------
        // Shrink to a tabletop board, then counter-rotate for world-locking.
        // ------------------------------------------------------------------
        Matrix.setIdentityM(scaled, 0)
        Matrix.scaleM(scaled, 0, Config.boardScale, Config.boardScale, Config.boardScale)
        M4.mul(scaled, viewCenter, tmpA)                       // S . V

        buildHeadRotation(input)
        M4.mul(headRot, tmpA, tmpB)                            // R . S . V

        // ------------------------------------------------------------------
        // Per-eye separation + matching off-axis frustum.
        // ------------------------------------------------------------------
        for (eye in 0..1) {
            val shift = if (eye == 0) -Config.ipd * 0.5f else Config.ipd * 0.5f
            Matrix.setIdentityM(eyeShiftM, 0)
            Matrix.translateM(eyeShiftM, 0, -shift, 0f, 0f)
            M4.mul(eyeShiftM, tmpB, view[eye])

            proj[eye] = M4.perspectiveOffAxis(
                Config.fovYDeg, aspect, Config.near, Config.far, shift, Config.convergence
            )
            M4.mul(proj[eye], view[eye], viewProj[eye])
        }
    }

    /**
     * Rotation applied to the board so it partially stays put in the room.
     *
     * Derivation, for yaw: a point straight ahead sits at eye-space (0,0,-d).
     * Rotating about +Y by theta sends its x to -d*sin(theta). Turning the head
     * LEFT must make a room-fixed board drift RIGHT (+x), which requires
     * theta < 0. HeadTracker reports right-positive yaw, so a left turn is a
     * negative headYaw and [yawSign] restores the needed sign.
     *
     * Pitch follows the same argument about +X: looking UP must send the board
     * DOWN (-y), which needs a negative angle for an up-positive input.
     */
    private fun buildHeadRotation(input: InputState) {
        val k = Config.worldLock
        if (k <= 0.001f) { Matrix.setIdentityM(headRot, 0); return }

        val yaw = -input.headYaw * yawSign * k
        val pitch = input.headPitch * pitchSign * k
        val roll = input.headRoll * rollSign * k

        Matrix.setIdentityM(headRot, 0)
        Matrix.rotateM(headRot, 0, yaw / DEG2RAD, 0f, 1f, 0f)
        Matrix.rotateM(headRot, 0, pitch / DEG2RAD, 1f, 0f, 0f)
        Matrix.rotateM(headRot, 0, roll / DEG2RAD, 0f, 0f, 1f)
    }

    /**
     * Board-space size of one world metre at the player's position — handy for
     * sizing HUD text and particles consistently regardless of Config changes.
     */
    fun boardMetresPerWorldMetre(): Float = Config.boardScale

    /** Distance from the viewer to the player's car, in real metres. */
    fun playerViewDistance(): Float =
        sqrt(Config.camHeight * Config.camHeight + Config.camBack * Config.camBack) * Config.boardScale

    /** Angular disparity of the car in degrees — sanity check for stereo strength. */
    fun playerDisparityDeg(): Float {
        val d = playerViewDistance()
        return (Config.ipd / d) / DEG2RAD
    }
}
