package com.rayneo.spyhunt.game

import com.rayneo.spyhunt.core.Rng

/**
 * ============================================================================
 *  SHARED CONTRACT — the integration surface between simulation, renderer,
 *  audio and input. Treat the shapes here as fixed.
 * ============================================================================
 *
 * COORDINATE SYSTEM (world units = metres)
 *   +X : right
 *   +Y : up
 *   +Z : forward, the direction of travel. Road extends toward +Z.
 *
 * The player's Z increases without bound; it doubles as the progress/odometer
 * value. Everything else is positioned in the same absolute space, and the
 * renderer subtracts the player's Z to keep geometry near the origin (float
 * precision stays comfortable even after kilometres of driving).
 *
 * SCALE: the road is ~12 m wide, cars are ~4.5 m long. The renderer shrinks the
 * whole scene to a ~1 m-wide hologram board floating ~2.5 m in front of the
 * viewer, which is what makes stereo depth readable — see StereoRig.
 */

const val ROAD_HALF_WIDTH_DEFAULT = 7.0f
const val LANE_WIDTH = 3.0f
const val CAR_LENGTH = 4.5f
const val CAR_WIDTH = 2.0f

enum class VehicleKind(
    val length: Float,
    val width: Float,
    val maxHealth: Int,
    val isHostile: Boolean,
    val isCivilian: Boolean
) {
    PLAYER(4.6f, 2.0f, 1, false, false),

    /** Slashes tyres from alongside; dies to one burst. */
    SWITCHBLADE(4.4f, 2.1f, 1, true, false),
    /** Armoured limo, rams from behind. Takes sustained fire. */
    ROAD_LORD(6.2f, 2.3f, 4, true, false),
    /** Bulletproof — cannot be shot, must be forced off the road. */
    ENFORCER(5.6f, 2.4f, 999, true, false),
    /** Fast, weaves, fragile. */
    MOTORCYCLE(2.2f, 0.9f, 1, true, false),
    /** Circles overhead and drops bombs; only missiles reach it. */
    HELICOPTER(6.0f, 6.0f, 2, true, false),
    /** Armed patrol boat — water sections only. */
    GUNBOAT(6.5f, 2.6f, 2, true, false),

    /** Innocent traffic. Shooting these costs you. */
    CIVILIAN_CAR(4.3f, 1.9f, 1, false, true),
    CIVILIAN_TRUCK(9.0f, 2.5f, 1, false, true),

    /** Drive into the back of it to receive a special weapon. */
    WEAPONS_VAN(8.0f, 2.4f, 1, false, false);

    val halfLength get() = length * 0.5f
    val halfWidth get() = width * 0.5f
}

enum class WeaponKind { MACHINE_GUN, OIL_SLICK, SMOKE_SCREEN, MISSILE }

enum class Surface { ROAD, WATER }

/** Reason an entity was removed — drives scoring, audio and particle effects. */
enum class DeathCause { NONE, GUNFIRE, MISSILE, RAN_OFF_ROAD, COLLISION, DESPAWN, BOMB }

/** Simple pooled actor. Reused rather than reallocated; check [alive] before use. */
class Vehicle {
    @JvmField var kind: VehicleKind = VehicleKind.CIVILIAN_CAR
    @JvmField var alive = false

    @JvmField var x = 0f          // lateral position, metres from world centreline
    @JvmField var z = 0f          // absolute forward position
    @JvmField var y = 0f          // height; non-zero only for HELICOPTER
    @JvmField var vx = 0f
    @JvmField var vz = 0f         // absolute forward speed, m/s
    @JvmField var yaw = 0f        // radians, 0 = facing +Z

    @JvmField var health = 1
    @JvmField var age = 0f

    /** Set when the vehicle loses control (shot, oiled, rammed) — it spins out. */
    @JvmField var spinning = false
    @JvmField var spinRate = 0f
    /** Counts down while the vehicle is burning/exploding before it despawns. */
    @JvmField var dyingFor = -1f
    @JvmField var deathCause = DeathCause.NONE

    /** Per-kind scratch: Switchblade attack phase, helicopter orbit angle, etc. */
    @JvmField var ai0 = 0f
    @JvmField var ai1 = 0f
    @JvmField var aiState = 0

    /** Rendering hints. */
    @JvmField var flashUntil = 0f     // hit flash, absolute game time
    @JvmField var wobble = 0f         // suspension/hover animation phase

    val isDying get() = dyingFor >= 0f

    fun reset() {
        alive = false; spinning = false; dyingFor = -1f; deathCause = DeathCause.NONE
        health = 1; age = 0f; vx = 0f; vz = 0f; y = 0f; yaw = 0f
        ai0 = 0f; ai1 = 0f; aiState = 0; flashUntil = 0f; wobble = 0f
    }
}

/** Bullets, missiles and dropped bombs. */
class Projectile {
    @JvmField var alive = false
    @JvmField var fromPlayer = true
    @JvmField var isMissile = false
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var z = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var vz = 0f
    @JvmField var life = 0f
    /** Homing target index into [GameState.vehicles], or -1. */
    @JvmField var target = -1
}

/** Oil slicks and smoke clouds left on the road. */
class Hazard {
    @JvmField var alive = false
    @JvmField var isSmoke = false
    @JvmField var x = 0f
    @JvmField var z = 0f
    @JvmField var radius = 0f
    @JvmField var life = 0f
    @JvmField var maxLife = 1f
}

/**
 * A sampled slice of the road at a given Z. The topology module fills these in;
 * both the simulation (for boundaries) and the renderer (for geometry) read
 * them, so they must agree exactly.
 */
class RoadSlice {
    @JvmField var z = 0f
    @JvmField var centerX = 0f
    @JvmField var halfWidth = ROAD_HALF_WIDTH_DEFAULT
    @JvmField var surface = Surface.ROAD
    /** Fork geometry: when [hasFork] the road splits and [forkGap] is the island half-width. */
    @JvmField var hasFork = false
    @JvmField var forkGap = 0f
    /** Bank angle in radians, for visual roll on curves. */
    @JvmField var bank = 0f
}

enum class Phase { CALIBRATING, ATTRACT, COUNTDOWN, PLAYING, DYING, GAME_OVER }

/**
 * Visual presentation only. Simulation, weapons, physics, scoring and difficulty
 * are byte-for-byte identical in both — the style is read exclusively by the
 * renderer, never by anything under `game/`.
 */
enum class RenderStyle {
    /** Emissive neon vector-arcade, built around the additive waveguide. */
    NEON,

    /**
     * 1983 arcade homage: flat sprite-era shading, a hard limited palette,
     * chunky pixels, grey tarmac with white dashes and a green verge.
     * The weapons are deliberately the modern set, not the original's.
     */
    VINTAGE;

    val title: String get() = when (this) {
        NEON -> "NEON PURSUIT"
        VINTAGE -> "VINTAGE 1983"
    }

    val blurb: String get() = when (this) {
        NEON -> "HDR NEON - BLOOM - LIGHT TRAILS"
        VINTAGE -> "ORIGINAL ARCADE LOOK - MODERN WEAPONS"
    }

    companion object {
        val ordered = arrayOf(NEON, VINTAGE)
    }
}

/** One-shot events raised by the sim and drained by audio/particles each frame. */
enum class GameEvent {
    PLAYER_FIRE, PLAYER_HIT, ENEMY_EXPLODE, CIVILIAN_KILLED, VEHICLE_SPLASH,
    WEAPON_PICKUP, OIL_DROP, SMOKE_DROP, MISSILE_FIRE, HELI_BOMB,
    TYRE_SCREECH, EXTRA_LIFE, GAME_OVER, LEVEL_UP, ENTER_WATER, ENTER_ROAD, COUNTDOWN_TICK
}

class EventQueue(cap: Int = 64) {
    private val buf = arrayOfNulls<GameEvent>(cap)
    private val px = FloatArray(cap)
    private val pz = FloatArray(cap)
    var count = 0; private set

    fun push(e: GameEvent, x: Float = 0f, z: Float = 0f) {
        if (count < buf.size) { buf[count] = e; px[count] = x; pz[count] = z; count++ }
    }
    fun event(i: Int) = buf[i]!!
    fun x(i: Int) = px[i]
    fun z(i: Int) = pz[i]
    fun clear() { count = 0 }
}

/** Everything the renderer and audio need to draw/hear one frame. */
class GameState {
    val rng = Rng()

    @JvmField var phase = Phase.ATTRACT
    @JvmField var time = 0f              // seconds since app start, advances always
    @JvmField var phaseTime = 0f         // seconds in the current phase

    /** Chosen presentation. Read only by the renderer; the sim never branches on it. */
    @JvmField var style = RenderStyle.NEON
    /** Highlighted entry on the start screen, an index into [RenderStyle.ordered]. */
    @JvmField var menuIndex = 0

    // ---- player ----
    @JvmField val player = Vehicle().apply { kind = VehicleKind.PLAYER; alive = true }
    /** Forward speed in m/s. Drives engine pitch, road scroll and camera pull-back. */
    @JvmField var speed = 42f
    @JvmField var targetSpeed = 42f
    /** -1..1 steering authority actually applied this frame. */
    @JvmField var steer = 0f
    @JvmField var onWater = false
    /** True while the car is morphing between road/boat form; drives the transform VFX. */
    @JvmField var morph = 0f

    @JvmField var lives = 3
    @JvmField var score = 0L
    @JvmField var nextExtraLifeAt = 10_000L
    @JvmField var distance = 0f
    @JvmField var level = 1

    @JvmField var weapon: WeaponKind = WeaponKind.MACHINE_GUN
    @JvmField var weaponAmmo = 0
    @JvmField var gunHeat = 0f
    @JvmField var gunCooldown = 0f

    /** Multiplier chain for consecutive kills without taking damage. */
    @JvmField var combo = 0
    @JvmField var comboTimer = 0f

    /** Screen shake impulse, decays each frame; renderer reads it. */
    @JvmField var shake = 0f

    // ---- pools ----
    @JvmField val vehicles = Array(48) { Vehicle() }
    @JvmField val projectiles = Array(160) { Projectile() }
    @JvmField val hazards = Array(24) { Hazard() }

    /** Road sampled ahead of the player; index 0 is nearest. Regenerated each frame. */
    @JvmField val road = Array(ROAD_SLICES) { RoadSlice() }

    @JvmField val events = EventQueue()

    fun spawnVehicle(): Vehicle? = vehicles.firstOrNull { !it.alive }?.also { it.reset() }
    fun spawnProjectile(): Projectile? = projectiles.firstOrNull { !it.alive }
    fun spawnHazard(): Hazard? = hazards.firstOrNull { !it.alive }

    companion object {
        /** Number of sampled road slices, spaced [ROAD_SLICE_SPACING] apart. */
        const val ROAD_SLICES = 96
        const val ROAD_SLICE_SPACING = 4.0f
        /** How far behind the player the road starts being generated. */
        const val ROAD_BEHIND = 40f
    }
}

/** Per-frame input, produced by the input module and consumed by the sim. */
class InputState {
    /** -1..1 lateral steering demand from swiping along the temple arm. */
    @JvmField var steer = 0f
    /** 0..1 throttle demand (swipe up/down across the arm). */
    @JvmField var throttle = 0.5f
    /** True on the frame a tap is recognised. */
    @JvmField var firePressed = false
    /** True while the pad is held — enables continuous fire. */
    @JvmField var fireHeld = false
    /** True on the frame a double-tap is recognised: deploy special weapon. */
    @JvmField var specialPressed = false
    /** True on the frame a long-press is recognised: pause / start. */
    @JvmField var menuPressed = false
    /** Head orientation in radians, already smoothed and re-centred. */
    @JvmField var headYaw = 0f
    @JvmField var headPitch = 0f
    @JvmField var headRoll = 0f

    fun clearEdges() { firePressed = false; specialPressed = false; menuPressed = false }
}
