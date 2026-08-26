package com.rayneo.spyhunt.game

import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.damp
import com.rayneo.spyhunt.core.sinf
import com.rayneo.spyhunt.core.smoothstep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-kind driver AI. Every actor is steered by writing [Vehicle.vx] / [Vehicle.vz]
 * — never by teleporting position — so shoves, spins and hazards all compose
 * with the AI instead of fighting it.
 *
 * Scratch field conventions (the pool has no per-kind storage):
 *  - SWITCHBLADE  ai0 = phase timer,  ai1 = approach side (+1/-1), aiState = 0..3
 *  - ROAD_LORD    ai0 = charge/cooldown timer, aiState = 0 stalk / 1 charging
 *  - ENFORCER     ai0 = time spent alongside, ai1 = flank side
 *  - MOTORCYCLE   ai1 = weave phase offset
 *  - HELICOPTER   ai0 = orbit angle, ai1 = bomb cooldown
 *  - GUNBOAT      ai0 = weave phase
 *  - CIVILIAN_*   ai0 = cruise speed, ai1 = lane offset as a fraction of the
 *                 usable half-width (a fraction, not metres, so traffic tracks
 *                 the road correctly through narrowing sections)
 *  - WEAPONS_VAN  ai0 = cruise speed, ai1 = lane fraction, aiState 0 = loaded
 */
class Enemies(private val world: World) {

    companion object {
        /** Helicopter station-keeping altitude, metres. */
        const val HELI_ALT = 11f
    }

    fun update(dt: Float) {
        val st = world.state
        val p = st.player
        val pSpeed = st.speed
        // Aggression ramps with the level: faster closes, shorter wind-ups.
        val aggro = min(1.7f, 1f + (st.level - 1) * 0.08f)

        for (v in st.vehicles) {
            if (!v.alive) continue
            v.age += dt
            v.wobble += dt * (if (v.kind == VehicleKind.HELICOPTER) 40f else 6f)

            if (v.isDying) { dying(v, dt); continue }
            if (v.spinning) spin(v, dt) else drive(v, dt, p, pSpeed, aggro)
            integrate(v, dt)
            terrain(v, dt)
        }
    }

    private fun drive(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float, aggro: Float) {
        when (v.kind) {
            VehicleKind.SWITCHBLADE -> switchblade(v, dt, p, pSpeed, aggro)
            VehicleKind.ROAD_LORD -> roadLord(v, dt, p, pSpeed, aggro)
            VehicleKind.ENFORCER -> enforcer(v, dt, p, pSpeed, aggro)
            VehicleKind.MOTORCYCLE -> motorcycle(v, dt, p, pSpeed, aggro)
            VehicleKind.HELICOPTER -> helicopter(v, dt, p, pSpeed)
            VehicleKind.GUNBOAT -> gunboat(v, dt, p, pSpeed, aggro)
            VehicleKind.CIVILIAN_CAR, VehicleKind.CIVILIAN_TRUCK -> traffic(v, dt)
            VehicleKind.WEAPONS_VAN -> van(v, dt, pSpeed)
            VehicleKind.PLAYER -> {}
        }
    }

    // =====================================================================
    //  hostiles
    // =====================================================================

    /** Pulls level with the player, waits a beat, then swerves through the line. */
    private fun switchblade(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float, aggro: Float) {
        if (v.ai1 == 0f) v.ai1 = if (v.x >= p.x) 1f else -1f
        val dz = v.z - p.z
        when (v.aiState) {
            0 -> {
                keepStation(v, dt, pSpeed, dz, 0f, 0.7f * aggro, 14f, 1.5f)
                steerTo(v, onRoad(v, p.x + v.ai1 * 4.6f), dt, 3.5f, 11f)
                if (dz > -9f && dz < 9f) { v.aiState = 1; v.ai0 = 0f }
            }
            1 -> {
                keepStation(v, dt, pSpeed, dz, 0f, 0.5f, 8f, 3f)
                steerTo(v, onRoad(v, p.x + v.ai1 * 4.0f), dt, 4f, 10f)
                v.ai0 += dt
                if (v.ai0 > 1.3f / aggro) { v.aiState = 2; v.ai0 = 0f }
            }
            2 -> {
                keepStation(v, dt, pSpeed, dz, 1.5f, 0.5f, 8f, 3f)
                // Deliberately unclamped: the swing is committed, and a
                // switchblade that ditches itself on the kerb is a fair reward
                // for dodging late.
                steerTo(v, p.x - v.ai1 * 1.2f, dt, 7f, 16f)
                v.ai0 += dt
                if (v.ai0 > 1.1f) { v.aiState = 3; v.ai0 = 0f }
            }
            else -> {
                v.vz = damp(v.vz, pSpeed - 12f, 1.2f, dt)
                steerTo(v, onRoad(v, p.x + v.ai1 * 9f), dt, 3f, 10f)
            }
        }
    }

    /** Armoured limo: sits in the mirrors, then charges. Closing speed is the kill. */
    private fun roadLord(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float, aggro: Float) {
        val dz = v.z - p.z
        v.ai0 -= dt
        if (v.aiState == 0) {
            steerTo(v, onRoad(v, p.x), dt, 3f, 9f)
            keepStation(v, dt, pSpeed, dz, -14f, 0.8f * aggro, 16f, 1.2f)
            if (dz < -3f && dz > -22f && v.ai0 <= 0f) { v.aiState = 1; v.ai0 = 1.6f }
        } else {
            steerTo(v, p.x, dt, 5f, 12f)
            v.vz = damp(v.vz, pSpeed + 19f, 3f, dt)
            if (v.ai0 <= 0f || dz > 2f) { v.aiState = 0; v.ai0 = 2.2f / aggro }
        }
    }

    /**
     * Bulletproof squeeze play. It parks on the flank away from the nearest kerb
     * and then creeps across, trying to walk the player over the edge. That
     * shoving contest is symmetric — it is also the player's only way to score
     * one of these, so the AI never retreats out of contact range.
     */
    private fun enforcer(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float, aggro: Float) {
        val cx = world.centerAt(p.z)
        val edgeSide = if (p.x >= cx) 1f else -1f
        if (v.ai1 == 0f) v.ai1 = -edgeSide
        val dz = v.z - p.z

        keepStation(v, dt, pSpeed, dz, 0f, 0.8f * aggro, 12f, 1.6f)
        // Pressure only builds while it is actually alongside.
        v.ai0 = if (abs(dz) > 12f) 0f else v.ai0 + dt

        val press = if (abs(dz) < 7f) smoothstep(0f, 1.2f, v.ai0) * 2.4f else 0f
        steerTo(v, onRoad(v, p.x - edgeSide * (3.0f - press)), dt, 3.5f, 10f)
    }

    /** Fast and fragile; weaves so a held trigger will not simply mow it down. */
    private fun motorcycle(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float, aggro: Float) {
        val dz = v.z - p.z
        val weave = sinf(v.age * 3.2f + v.ai1) * 3.4f
        // Sits just ahead: it harasses from inside the firing arc, so it is a
        // target rather than an unanswerable nuisance in the blind spot.
        keepStation(v, dt, pSpeed, dz, 6f, 0.9f * aggro, 18f, 2.2f)
        steerTo(v, onRoad(v, p.x + weave), dt, 6f, 15f)
    }

    /** Orbits overhead out of gun reach and lobs bombs. Missiles only. */
    private fun helicopter(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float) {
        v.y = damp(v.y, HELI_ALT, 1.6f, dt)
        v.ai0 += dt * 1.3f
        steerTo(v, p.x + sinf(v.ai0) * 7.5f, dt, 4f, 14f)
        // Hold station just ahead so it stays inside the field of view.
        val wantZ = p.z + 14f
        v.vz = damp(v.vz, pSpeed + clamp((wantZ - v.z) * 0.8f, -14f, 14f), 2f, dt)

        v.ai1 -= dt
        if (v.ai1 <= 0f && abs(v.z - p.z) < 30f && v.y > 4f) {
            dropBomb(v, p)
            v.ai1 = max(0.9f, 2.4f - world.state.level * 0.12f)
        }
    }

    private fun dropBomb(v: Vehicle, p: Vehicle) {
        val pr = world.state.spawnProjectile() ?: return
        // Solve the fall time, then lead the player so the bomb is a real threat
        // rather than decoration.
        val fall = max(0.25f, sqrt(2f * max(v.y, 1f) / Weapons.BOMB_GRAVITY))
        pr.fromPlayer = false
        pr.isMissile = false
        pr.x = v.x; pr.y = v.y; pr.z = v.z
        pr.vx = clamp((p.x - v.x) / fall, -14f, 14f)
        pr.vy = 0f
        pr.vz = clamp((p.z + p.vz * fall - v.z) / fall, 0f, 140f)
        pr.life = fall + 2f
        pr.target = -1
        pr.alive = true
        world.ev(GameEvent.HELI_BOMB, v.x, v.z)
    }

    /**
     * Water-section blocker. It rams rather than shoots: every enemy weapon has
     * to announce itself through [GameEvent] for the audio layer, and there is
     * no event for boat guns — a silent tracer would be an unfair death.
     */
    private fun gunboat(v: Vehicle, dt: Float, p: Vehicle, pSpeed: Float, aggro: Float) {
        v.ai0 += dt
        val weave = sinf(v.ai0 * 1.6f) * 2.5f
        keepStation(v, dt, pSpeed, v.z - p.z, 6f, 0.7f * aggro, 14f, 1.6f)
        steerTo(v, onRoad(v, p.x + weave), dt, 3f, 9f)
        v.y = damp(v.y, 0f, 4f, dt)
    }

    // =====================================================================
    //  neutrals
    // =====================================================================

    // Lane authority has to exceed the centreline's own lateral drift rate
    // (~4 m/s at traffic speeds through the tightest curve) or traffic would
    // gradually lag out of its lane and ditch itself on a bend.
    private fun traffic(v: Vehicle, dt: Float) {
        v.vz = damp(v.vz, v.ai0, 1f, dt)
        steerTo(v, laneX(v), dt, 3f, 8f)
    }

    private fun van(v: Vehicle, dt: Float, pSpeed: Float) {
        // Once looted it floors it and leaves the play area.
        val cruise = if (v.aiState == 0) v.ai0 else pSpeed + 24f
        v.vz = damp(v.vz, cruise, 1.2f, dt)
        steerTo(v, laneX(v), dt, 3f, 9f)
    }

    // =====================================================================
    //  shared motion
    // =====================================================================

    /**
     * Converges on a fixed offset *from the player* rather than on a fixed
     * speed. A constant speed offset only works from the side the actor spawned
     * on: chasers would run away forever once past, and blockers could never be
     * caught. Station-keeping also keeps hostiles inside the forward firing arc
     * instead of parked in the blind spot where the gun cannot answer them.
     */
    private fun keepStation(
        v: Vehicle, dt: Float, pSpeed: Float, dz: Float,
        wantDz: Float, gain: Float, maxDelta: Float, rate: Float
    ) {
        v.vz = damp(v.vz, pSpeed + clamp((wantDz - dz) * gain, -maxDelta, maxDelta), rate, dt)
    }

    private fun steerTo(v: Vehicle, targetX: Float, dt: Float, rate: Float, maxVx: Float) {
        val want = clamp((targetX - v.x) * 2f, -maxVx, maxVx)
        v.vx = damp(v.vx, want, rate, dt)
    }

    private fun integrate(v: Vehicle, dt: Float) {
        v.x += v.vx * dt
        v.z += v.vz * dt
        if (!v.spinning) v.yaw = clamp(v.vx / max(abs(v.vz), 10f), -0.6f, 0.6f)
    }

    private fun spin(v: Vehicle, dt: Float) {
        v.yaw += v.spinRate * dt
        v.spinRate = damp(v.spinRate, 0f, 0.5f, dt)
        v.vz = damp(v.vz, 5f, 1.2f, dt)
        // A spin throws the tail out, which is usually what puts it over the kerb.
        v.vx = damp(v.vx, (if (v.spinRate >= 0f) 1f else -1f) * 6f, 1f, dt)
    }

    private fun dying(v: Vehicle, dt: Float) {
        v.dyingFor += dt
        v.yaw += v.spinRate * dt
        v.spinRate = damp(v.spinRate, 0f, 1.2f, dt)
        v.vz = damp(v.vz, 0f, 1.6f, dt)
        v.vx = damp(v.vx, 0f, 1.6f, dt)
        if (v.kind == VehicleKind.HELICOPTER) v.y = max(0f, v.y - 16f * dt)
        v.x += v.vx * dt
        v.z += v.vz * dt
        if (v.dyingFor > 1.6f) v.alive = false
    }

    // =====================================================================
    //  road awareness
    // =====================================================================

    /** Lane-keeping target for traffic: a fraction of the usable half-width. */
    private fun laneX(v: Vehicle): Float {
        val cx = world.centerAt(v.z)
        val usable = max(0f, world.halfWidthAt(v.z) - v.kind.width)
        return islandSafe(v, cx + v.ai1 * usable)
    }

    /** Clamps a desired X inside the tarmac and out of the fork island. */
    private fun onRoad(v: Vehicle, wantX: Float): Float {
        val cx = world.centerAt(v.z)
        val lim = max(0f, world.halfWidthAt(v.z) - v.kind.halfWidth - 0.2f)
        return islandSafe(v, cx + clamp(wantX - cx, -lim, lim))
    }

    private fun islandSafe(v: Vehicle, wantX: Float): Float {
        val gap = world.forkGapAt(v.z)
        if (gap <= 0.05f) return wantX
        val cx = world.centerAt(v.z)
        val d = wantX - cx
        val side = if (d >= 0f) 1f else -1f
        val lo = gap + v.kind.halfWidth + 0.3f
        val hi = max(lo, world.halfWidthAt(v.z) - v.kind.halfWidth - 0.2f)
        return cx + side * clamp(abs(d), lo, hi)
    }

    /** Surface and boundary rules. Leaving the tarmac is fatal to everyone. */
    private fun terrain(v: Vehicle, dt: Float) {
        if (v.kind == VehicleKind.HELICOPTER) return
        val water = world.surfaceAt(v.z) == Surface.WATER

        if (v.kind == VehicleKind.GUNBOAT) {
            // Beaching at the end of a water stretch is not the player's doing,
            // so it explodes for free — DESPAWN suppresses the score award.
            if (!water) { world.killVehicle(v, DeathCause.DESPAWN); return }
        } else if (water) {
            // Wheels do not float. DESPAWN rather than RAN_OFF_ROAD: the player
            // did not do this, the shoreline arrived. Scored as a kill it would
            // hand him full points *and* a combo step for every wheeled hostile
            // on the road the instant he enters a crossing — an Enforcer alone
            // is 5000 for driving in a straight line.
            world.killVehicle(v, DeathCause.DESPAWN)
            return
        }

        val cx = world.centerAt(v.z)
        val hw = world.halfWidthAt(v.z)
        val gap = world.forkGapAt(v.z)
        val d = abs(v.x - cx)

        if (d > hw + v.kind.halfWidth || (gap > 0.05f && d < gap - v.kind.halfWidth)) {
            world.killVehicle(v, DeathCause.RAN_OFF_ROAD)
            return
        }
        if (d > hw) {
            // Half the car is on the verge: traction gone, and a hostile that
            // gets here has effectively been beaten.
            v.vz = damp(v.vz, v.vz * 0.55f, 2.5f, dt)
            if (!v.spinning && v.kind.isHostile) {
                v.spinning = true
                v.spinRate = if (v.x >= cx) 4f else -4f
                world.ev(GameEvent.TYRE_SCREECH, v.x, v.z)
            }
        }
    }
}
