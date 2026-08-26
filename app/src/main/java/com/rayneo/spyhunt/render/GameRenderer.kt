package com.rayneo.spyhunt.render

import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.util.Log
import com.rayneo.spyhunt.audio.AudioEngine
import com.rayneo.spyhunt.core.Attr
import com.rayneo.spyhunt.core.DynamicMesh
import com.rayneo.spyhunt.core.M4
import com.rayneo.spyhunt.core.Mesh
import com.rayneo.spyhunt.core.Program
import com.rayneo.spyhunt.core.TAG
import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.damp
import com.rayneo.spyhunt.game.DeathCause
import com.rayneo.spyhunt.game.GameEvent
import com.rayneo.spyhunt.game.GameState
import com.rayneo.spyhunt.game.InputState
import com.rayneo.spyhunt.game.Phase
import com.rayneo.spyhunt.game.RenderStyle
import com.rayneo.spyhunt.game.VehicleKind
import com.rayneo.spyhunt.game.World
import com.rayneo.spyhunt.input.HeadTracker
import com.rayneo.spyhunt.input.TouchpadController
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Frame graph, once per eye:
 *
 *   road -> vehicles -> scenery -> trails -> particles -> HUD   (into RGBA16F)
 *   bright pass -> two blur octaves -> ACES composite            (into screen half)
 *
 * The simulation runs exactly once per frame, before either eye is drawn, so
 * the two views are guaranteed to show the same instant. Stepping physics
 * between eyes is the classic way to give a stereo renderer a subtle,
 * miserable-to-debug shimmer.
 */
class GameRenderer(
    private val touch: TouchpadController,
    private val head: HeadTracker,
    private val audio: AudioEngine
) : GLSurfaceView.Renderer {

    val state = GameState()
    private val world = World(state)
    private val rig = StereoRig()

    private val particles = Particles()
    private val trails = Trails()
    private val hud = Hud()

    // Owns GL objects (a Program and a VAO), so it cannot be built until
    // onSurfaceCreated — this class is constructed on the main thread in
    // Activity.onCreate, where there is no current EGL context and every
    // glCreate* call silently returns 0.
    private var roadRenderer: RoadRenderer? = null

    private var post: PostChain? = null

    private lateinit var pScene: Program
    private lateinit var pSceneVintage: Program
    private lateinit var pSprite: Program
    private lateinit var pHud: Program

    /**
     * Scene program for the currently selected presentation. Two compiled programs
     * rather than one shader branching on a uniform: a per-fragment branch would
     * cost more every frame than simply binding the right program once.
     */
    private val sceneProg: Program
        get() = if (state.style == RenderStyle.VINTAGE) pSceneVintage else pScene

    /** Hysteresis latch so one arm swipe steps the menu exactly once. */
    private var menuLatched = false

    private lateinit var spriteMesh: DynamicMesh
    private lateinit var hudMesh: DynamicMesh

    private var meshesReady = false
    private var lastNanos = 0L
    private var screenW = 1280
    private var screenH = 480
    private var eyeW = 640

    /** Rolling average frame time, surfaced in logcat for perf checks. */
    private var avgFrameMs = 16.7f
    private var frameCounter = 0
    private var roadVertsLastFrame = 0

    private val modelTmp = FloatArray(16)
    private val mvpTmp = FloatArray(16)
    private val nrmTmp = FloatArray(16)
    private val scratch = FloatArray(16)

    // ------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ------------------------------------------------------------------

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        Log.i(TAG, "GL vendor=${GL.glGetString(GL.GL_VENDOR)} renderer=${GL.glGetString(GL.GL_RENDERER)}")
        Log.i(TAG, "GL version=${GL.glGetString(GL.GL_VERSION)}")

        pScene = Program(Shaders.SCENE_VS, Shaders.SCENE_FS, "scene")
        pSceneVintage = Program(VintageShaders.SCENE_VS, VintageShaders.SCENE_FS, "sceneVintage")
        pSprite = Program(Shaders.SPRITE_VS, Shaders.SPRITE_FS, "sprite")
        pHud = Program(Shaders.HUD_VS, Shaders.HUD_FS, "hud")

        spriteMesh = DynamicMesh(
            maxVerts = 12000,
            attrs = listOf(Attr("aPos", 3), Attr("aUv", 2), Attr("aCol", 4)),
            maxIdx = 18000
        )
        hudMesh = DynamicMesh(
            maxVerts = 8000,
            attrs = listOf(Attr("aPos", 3), Attr("aCol", 4)),
            maxIdx = 12000
        )

        roadRenderer?.release()
        roadRenderer = RoadRenderer()

        Meshes.build()
        meshesReady = true

        GL.glDisable(GL.GL_DITHER)
        GL.glClearColor(0f, 0f, 0f, 1f)

        lastNanos = System.nanoTime()
        Log.i(
            TAG,
            "stereo: board at %.2f m, disparity %.2f deg"
                .format(rig.playerViewDistance(), rig.playerDisparityDeg())
        )
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        screenW = w; screenH = h
        // The framebuffer is a side-by-side stereo pair; each eye owns one half.
        eyeW = w / 2
        rig.resize(eyeW, h)
        post?.release()
        post = PostChain(eyeW, h)
        Log.i(TAG, "surface ${w}x$h -> per-eye ${eyeW}x$h")
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        // Clamp so a hitch (GC, thermal stall, app resume) cannot tunnel the car
        // through traffic or fling the camera.
        dt = clamp(dt, 0f, 0.05f)

        step(dt)
        render()

        avgFrameMs = damp(avgFrameMs, dt * 1000f, 2f, dt)
        if (++frameCounter % 600 == 0) {
            val s0 = state.road[0]
            val sMid = state.road[GameState.ROAD_SLICES / 2]
            var live = 0
            for (v in state.vehicles) if (v.alive) live++
            Log.i(
                TAG, ("%.1fms ph=%s sc=%d lives=%d spd=%.0f wpn=%s/%d | player x=%.1f z=%.0f | " +
                    "road[0] z=%.0f cx=%.1f hw=%.1f bank=%.2f | road[48] z=%.0f cx=%.1f | " +
                    "verts=%d live=%d part=%d")
                    .format(
                        avgFrameMs, state.phase, state.score, state.lives, state.speed,
                        state.weapon, state.weaponAmmo,
                        state.player.x, state.player.z,
                        s0.z, s0.centerX, s0.halfWidth, s0.bank,
                        sMid.z, sMid.centerX,
                        roadVertsLastFrame, live, particles.activeCount
                    )
            )
        }
    }

    // ------------------------------------------------------------------
    // Simulation step
    // ------------------------------------------------------------------

    private fun step(dt: Float) {
        val input = touch.input
        head.applyTo(input)
        touch.update(dt)

        // Calibration gates play: until the right temple pad is identified we
        // cannot steer, so hold the game in its calibration phase.
        if (!touch.calibrated) {
            state.phase = Phase.CALIBRATING
            state.time += dt
            state.phaseTime += dt
        } else {
            if (state.phase == Phase.CALIBRATING) {
                state.phase = Phase.ATTRACT
                state.phaseTime = 0f
            }
            stepMenu(input)
            world.update(dt, input)
        }

        rig.update(state, input, dt)
        audio.update(state)
        consumeEvents(dt)

        particles.update(dt, state.player.z)
        trails.update(dt)
        pushTrails()

        input.clearEdges()
        state.events.clear()
    }

    /**
     * Start-screen selection. The steering axis doubles as the menu control while
     * the attract demo is running (the autopilot ignores steering there, so the
     * axis is free), with hysteresis so one deliberate swipe moves one entry
     * instead of the axis chattering across the threshold.
     *
     * The highlighted style is applied to [GameState.style] immediately, so the
     * attract demo behind the menu previews the look you are about to pick.
     * World.update re-commits it on the tap, which is the authoritative point.
     */
    private fun stepMenu(input: InputState) {
        if (state.phase != Phase.ATTRACT) { menuLatched = false; return }

        val s = input.steer
        val n = RenderStyle.ordered.size
        if (!menuLatched && kotlin.math.abs(s) > 0.45f) {
            state.menuIndex = ((state.menuIndex + if (s > 0f) 1 else -1) % n + n) % n
            menuLatched = true
        } else if (kotlin.math.abs(s) < 0.18f) {
            menuLatched = false
        }
        state.style = RenderStyle.ordered[((state.menuIndex % n) + n) % n]
    }

    /** Turn simulation events into particle bursts and screen flash. */
    private fun consumeEvents(dt: Float) {
        val p = post ?: return
        p.flash = damp(p.flash, 0f, 9f, dt)

        for (i in 0 until state.events.count) {
            val x = state.events.x(i)
            val z = state.events.z(i)
            when (state.events.event(i)) {
                GameEvent.PLAYER_FIRE -> particles.spawnMuzzle(x, 0.9f, z)
                GameEvent.MISSILE_FIRE -> particles.spawnMuzzle(x, 0.9f, z)
                GameEvent.ENEMY_EXPLODE -> {
                    particles.spawnExplosion(x, 1.0f, z, 1.0f, true)
                    particles.spawnDebris(x, 1.0f, z, 1.6f, 0.7f, 0.15f, 14)
                    p.flash = maxOf(p.flash, 0.05f)
                }
                GameEvent.CIVILIAN_KILLED -> {
                    particles.spawnExplosion(x, 1.0f, z, 0.8f, false)
                    p.flash = maxOf(p.flash, 0.09f)
                }
                GameEvent.PLAYER_HIT -> {
                    particles.spawnSparks(x, 0.8f, z, 0f, -1f, 26)
                    p.flash = maxOf(p.flash, 0.16f)
                }
                GameEvent.VEHICLE_SPLASH, GameEvent.ENTER_WATER ->
                    particles.spawnSplash(x, 0.2f, z, 1.2f)
                GameEvent.HELI_BOMB -> particles.spawnExplosion(x, 0.4f, z, 1.3f, true)
                GameEvent.OIL_DROP -> particles.spawnDebris(x, 0.1f, z, 0.4f, 0.3f, 0.9f, 8)
                GameEvent.SMOKE_DROP -> particles.spawnDebris(x, 0.4f, z, 0.7f, 0.7f, 0.8f, 12)
                GameEvent.WEAPON_PICKUP, GameEvent.EXTRA_LIFE, GameEvent.LEVEL_UP ->
                    particles.spawnSparks(x, 1.2f, z, 0f, 1f, 30)
                GameEvent.TYRE_SCREECH -> particles.spawnSparks(x, 0.15f, z, 0f, -1f, 4)
                GameEvent.ENTER_ROAD, GameEvent.COUNTDOWN_TICK, GameEvent.GAME_OVER -> Unit
            }
        }
    }

    /** Feed a light ribbon for every moving vehicle. */
    private fun pushTrails() {
        // Glowing light ribbons are the single most anachronistic thing on screen
        // in vintage — 1983 sprite hardware had nothing of the sort. Retire every
        // ribbon rather than just skipping the draw, so none are left mid-flight
        // to reappear on switching back.
        if (state.style == RenderStyle.VINTAGE) {
            trails.expire(0)
            for (i in state.vehicles.indices) trails.expire(i + 1)
            return
        }

        val pl = state.player
        if (state.phase == Phase.PLAYING) {
            trails.push(0, pl.x, 0.25f, pl.z, 0.2f, 2.4f, 3.0f, 0.55f)
        } else {
            trails.expire(0)
        }
        for (i in state.vehicles.indices) {
            val v = state.vehicles[i]
            val id = i + 1
            if (!v.alive || v.isDying) { trails.expire(id); continue }
            when (v.kind) {
                VehicleKind.SWITCHBLADE -> trails.push(id, v.x, 0.25f, v.z, 2.6f, 1.1f, 0.15f, 0.42f)
                VehicleKind.ROAD_LORD -> trails.push(id, v.x, 0.25f, v.z, 2.8f, 0.25f, 0.35f, 0.48f)
                VehicleKind.MOTORCYCLE -> trails.push(id, v.x, 0.22f, v.z, 2.4f, 0.5f, 2.6f, 0.3f)
                VehicleKind.ENFORCER -> trails.push(id, v.x, 0.3f, v.z, 1.6f, 1.7f, 1.9f, 0.55f)
                VehicleKind.GUNBOAT -> trails.push(id, v.x, 0.15f, v.z, 0.6f, 1.6f, 2.6f, 0.7f)
                VehicleKind.WEAPONS_VAN -> trails.push(id, v.x, 0.3f, v.z, 0.4f, 2.8f, 0.6f, 0.5f)
                else -> trails.expire(id)
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render() {
        val p = post ?: return

        // Build view-independent geometry once, then draw it twice.
        val road = roadRenderer ?: return
        road.build(state)
        roadVertsLastFrame = road.vertCount
        hudMesh.reset()
        hud.build(hudMesh, state, if (!touch.calibrated) touch.calibrationHint else null)

        spriteMesh.reset()
        trails.emit(
            spriteMesh, rig.camRightX, rig.camRightY, rig.camRightZ,
            rig.camUpX, rig.camUpY, rig.camUpZ, state.player.z
        )
        particles.emit(
            spriteMesh, rig.camRightX, rig.camRightY, rig.camRightZ,
            rig.camUpX, rig.camUpY, rig.camUpZ, state.player.z
        )
        emitProjectiles()
        emitHazards()

        p.vintage = state.style == RenderStyle.VINTAGE

        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, screenW, screenH)
        GL.glClearColor(0f, 0f, 0f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)

        for (eye in 0..1) {
            p.beginScene()
            drawEye(rig.viewProj[eye])
            p.resolveTo(eye * eyeW, 0, eyeW, screenH)
        }
    }

    private fun drawEye(viewProj: FloatArray) {
        // --- road ribbon ---
        roadRenderer?.draw(state, viewProj)

        // --- lit geometry ---
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthMask(true)
        GL.glDisable(GL.GL_BLEND)
        GL.glEnable(GL.GL_CULL_FACE)
        GL.glCullFace(GL.GL_BACK)
        // StereoRig negates Z to bridge the left-handed game space into GL's
        // right-handed eye space, which reverses triangle winding.
        GL.glFrontFace(GL.GL_CW)

        sceneProg.use()
        sceneProg.u3f("uCamPos", 0f, StereoRig.Config.camHeight, -StereoRig.Config.camBack)
        sceneProg.u3f("uLightDir", 0.28f, 0.86f, -0.43f)
        sceneProg.u1f("uRim", 1.35f)

        if (meshesReady) {
            drawVehicles(viewProj)
            roadRenderer?.drawScenery(state, viewProj, sceneProg)
        }

        // --- additive sprites: trails, particles, projectiles, hazards ---
        GL.glDepthMask(false)
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE)
        GL.glDisable(GL.GL_CULL_FACE)

        pSprite.use()
        pSprite.uMat4("uMVP", viewProj)
        spriteMesh.flushAndDraw()

        // --- HUD, drawn last and depth-free so it is never occluded ---
        GL.glDisable(GL.GL_DEPTH_TEST)
        pHud.use()
        pHud.uMat4("uMVP", viewProj)
        hudMesh.flushAndDraw()

        GL.glDisable(GL.GL_BLEND)
        GL.glEnable(GL.GL_DEPTH_TEST)
        GL.glDepthMask(true)
    }

    private fun drawVehicles(viewProj: FloatArray) {
        val originZ = state.player.z

        // Player, morphing between car and boat across water transitions.
        val pl = state.player
        if (state.phase != Phase.GAME_OVER) {
            val boat = state.morph > 0.5f
            val m = if (boat) Meshes.playerBoat else Meshes.playerCar
            // Squash through the transition so the swap is hidden inside a pulse.
            val squash = 1f - 0.35f * kotlin.math.sin(state.morph * Math.PI.toFloat())
            drawOne(
                m, viewProj, pl.x, pl.y, pl.z - originZ, pl.yaw,
                1f, squash, 1f,
                flash = if (state.time < pl.flashUntil) 1f else 0f,
                tintR = 1f, tintG = 1f, tintB = 1f,
                fade = if (state.phase == Phase.DYING) clamp(1f - state.phaseTime * 0.6f, 0.1f, 1f) else 1f
            )
        }

        for (v in state.vehicles) {
            if (!v.alive) continue
            val zLocal = v.z - originZ
            if (zLocal < -45f || zLocal > 115f) continue

            val mesh: Mesh = when (v.kind) {
                VehicleKind.SWITCHBLADE -> Meshes.switchblade
                VehicleKind.ROAD_LORD -> Meshes.roadLord
                VehicleKind.ENFORCER -> Meshes.enforcer
                VehicleKind.MOTORCYCLE -> Meshes.motorcycle
                VehicleKind.HELICOPTER -> Meshes.helicopter
                VehicleKind.GUNBOAT -> Meshes.gunboat
                VehicleKind.CIVILIAN_CAR -> Meshes.civilianCar
                VehicleKind.CIVILIAN_TRUCK -> Meshes.civilianTruck
                VehicleKind.WEAPONS_VAN -> Meshes.weaponsVan
                VehicleKind.PLAYER -> continue
            }

            // Dying vehicles sink, tumble and dim rather than vanishing outright.
            var fade = 1f
            var lift = 0f
            var roll = 0f
            if (v.isDying) {
                val t = clamp(v.dyingFor / 1.4f, 0f, 1f)
                fade = 1f - t
                lift = -t * 1.2f
                roll = t * 140f
            }

            drawOne(
                mesh, viewProj, v.x, v.y + lift, zLocal, v.yaw,
                1f, 1f, 1f,
                flash = if (state.time < v.flashUntil) 1f else 0f,
                tintR = 1f, tintG = 1f, tintB = 1f,
                fade = fade, rollDeg = roll
            )

            // Helicopter rotor gets its own fast spin.
            if (v.kind == VehicleKind.HELICOPTER) {
                val spin = (state.time * 1500f) % 360f
                M4.trsInto(modelTmp, v.x, v.y + lift + 2.1f, zLocal, spin, 1f, 1f, 1f)
                M4.normalMatrixInto(nrmTmp, modelTmp, scratch)
                sceneProg.uMat4("uModel", modelTmp)
                sceneProg.uMat4("uNrmMat", nrmTmp)
                sceneProg.uMat4("uMVP", M4.mul(viewProj, modelTmp, mvpTmp))
                sceneProg.u1f("uFlash", 0f)
                sceneProg.u1f("uFade", fade)
                sceneProg.u3f("uTint", 1f, 1f, 1f)
                Meshes.rotor.draw()
            }
        }
    }

    private fun drawOne(
        mesh: Mesh, viewProj: FloatArray,
        x: Float, y: Float, z: Float, yawRad: Float,
        sx: Float, sy: Float, sz: Float,
        flash: Float, tintR: Float, tintG: Float, tintB: Float,
        fade: Float, rollDeg: Float = 0f
    ) {
        M4.trsInto(modelTmp, x, y, z, yawRad / com.rayneo.spyhunt.core.DEG2RAD, sx, sy, sz)
        if (rollDeg != 0f) {
            android.opengl.Matrix.rotateM(modelTmp, 0, rollDeg, 0f, 0f, 1f)
        }
        M4.normalMatrixInto(nrmTmp, modelTmp, scratch)
        sceneProg.uMat4("uModel", modelTmp)
        sceneProg.uMat4("uNrmMat", nrmTmp)
        sceneProg.uMat4("uMVP", M4.mul(viewProj, modelTmp, mvpTmp))
        sceneProg.u1f("uFlash", flash)
        sceneProg.u1f("uFade", fade)
        sceneProg.u3f("uTint", tintR, tintG, tintB)
        mesh.draw()
    }

    /** Bullets and missiles as stretched additive sprites — cheap and they bloom. */
    private fun emitProjectiles() {
        val originZ = state.player.z
        for (p in state.projectiles) {
            if (!p.alive) continue
            if (!spriteMesh.hasRoom(4, 6)) return
            val z = p.z - originZ
            val half = if (p.isMissile) 1.6f else 1.1f
            val wide = if (p.isMissile) 0.30f else 0.16f
            val r: Float; val g: Float; val b: Float
            if (p.isMissile) { r = 6f; g = 2.2f; b = 0.5f }
            else if (p.fromPlayer) { r = 0.6f; g = 5.5f; b = 6.5f }
            else { r = 6.5f; g = 1.2f; b = 0.6f }

            // Aligned to the road rather than the camera: a tracer should read as
            // a streak along its direction of travel, not a round blob.
            val v0 = spriteMesh.vertCount
            spriteMesh.push(p.x - wide, p.y, z - half, 0f, 0f, r, g, b, 1f)
            spriteMesh.push(p.x + wide, p.y, z - half, 1f, 0f, r, g, b, 1f)
            spriteMesh.push(p.x + wide, p.y, z + half, 1f, 1f, r, g, b, 1f)
            spriteMesh.push(p.x - wide, p.y, z + half, 0f, 1f, r, g, b, 1f)
            spriteMesh.quadIndices(v0)
        }
    }

    /** Oil slicks and smoke clouds, as flat discs lying on the road. */
    private fun emitHazards() {
        val originZ = state.player.z
        for (h in state.hazards) {
            if (!h.alive) continue
            if (!spriteMesh.hasRoom(4, 6)) return
            val z = h.z - originZ
            val a = clamp(h.life / maxOf(h.maxLife, 0.001f), 0f, 1f)
            val rad = h.radius
            val r: Float; val g: Float; val b: Float
            if (h.isSmoke) { r = 0.55f; g = 0.6f; b = 0.75f }
            else { r = 0.35f; g = 0.2f; b = 0.9f }

            val v0 = spriteMesh.vertCount
            spriteMesh.push(h.x - rad, 0.06f, z - rad, 0f, 0f, r, g, b, a)
            spriteMesh.push(h.x + rad, 0.06f, z - rad, 1f, 0f, r, g, b, a)
            spriteMesh.push(h.x + rad, 0.06f, z + rad, 1f, 1f, r, g, b, a)
            spriteMesh.push(h.x - rad, 0.06f, z + rad, 0f, 1f, r, g, b, a)
            spriteMesh.quadIndices(v0)
        }
    }

    /** Called from the GL thread when the surface goes away. */
    fun release() {
        post?.release(); post = null
        roadRenderer?.release()
        roadRenderer = null
        if (meshesReady) { Meshes.release(); meshesReady = false }
    }
}
