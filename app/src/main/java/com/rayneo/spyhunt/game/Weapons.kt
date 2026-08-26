package com.rayneo.spyhunt.game

import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.cosf
import com.rayneo.spyhunt.core.lerp
import com.rayneo.spyhunt.core.sinf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Everything that leaves a vehicle: the player's machine gun, the special
 * weapons handed out by the weapons van, and the flight and impact of every
 * projectile and road hazard in the world.
 *
 * The gun is deliberately not free. Heat accumulates faster than it bleeds off,
 * so a held trigger overheats in about four seconds and then locks out until it
 * has cooled — the player has to shoot in bursts and pick targets.
 */
class Weapons(private val world: World) {

    companion object {
        /** Shared with [Enemies.dropBomb] so the lead calculation matches the arc. */
        const val BOMB_GRAVITY = 18f

        private const val BULLET_SPEED = 150f       // relative to the car
        private const val BULLET_LIFE = 0.9f
        private const val GUN_INTERVAL = 0.085f
        private const val HEAT_PER_SHOT = 0.05f
        private const val HEAT_COOL = 0.35f
        private const val HEAT_RESET = 0.25f

        private const val MISSILE_SPEED = 95f
        private const val MISSILE_TURN = 3.2f
        private const val MISSILE_LIFE = 4f
    }

    private var overheated = false

    fun resetHeat() { overheated = false }

    fun update(dt: Float, input: InputState) {
        val st = world.state
        st.gunCooldown = max(0f, st.gunCooldown - dt)
        st.gunHeat = max(0f, st.gunHeat - HEAT_COOL * dt)
        if (overheated && st.gunHeat <= HEAT_RESET) overheated = false

        if (st.phase == Phase.PLAYING) {
            if ((input.fireHeld || input.firePressed) && !overheated && st.gunCooldown <= 0f) fireGun()
            if (input.specialPressed) deploySpecial()
        }
        updatePassive(dt)
    }

    /** Ordnance already in the air keeps flying through death and attract phases. */
    fun updatePassive(dt: Float) {
        updateProjectiles(dt)
        updateHazards(dt)
    }

    /** Called by [World] when the player touches the back of a weapons van. */
    fun pickup(van: Vehicle) {
        if (van.aiState != 0) return
        van.aiState = 1                       // emptied; the van then bolts
        val st = world.state
        val r = st.rng.f()
        when {
            r < 0.34f -> { st.weapon = WeaponKind.OIL_SLICK; st.weaponAmmo = 5 }
            r < 0.68f -> { st.weapon = WeaponKind.SMOKE_SCREEN; st.weaponAmmo = 4 }
            else -> { st.weapon = WeaponKind.MISSILE; st.weaponAmmo = 3 }
        }
        world.ev(GameEvent.WEAPON_PICKUP, van.x, van.z)
    }

    // =====================================================================
    //  firing
    // =====================================================================

    private fun fireGun() {
        val st = world.state
        val p = st.player
        val sy = sinf(p.yaw)
        val cy = cosf(p.yaw)
        val hl = VehicleKind.PLAYER.halfLength
        var fired = 0

        // Two muzzles, offset along the car's own right vector so the tracers
        // stay bolted to the bonnet while the car is yawed.
        for (s in 0 until 2) {
            val pr = st.spawnProjectile() ?: break
            val side = if (s == 0) -0.6f else 0.6f
            pr.fromPlayer = true
            pr.isMissile = false
            pr.x = p.x + cy * side + sy * hl
            pr.y = 0.7f
            pr.z = p.z - sy * side + cy * hl
            pr.vx = sy * BULLET_SPEED
            pr.vy = 0f
            pr.vz = cy * BULLET_SPEED + st.speed
            pr.life = BULLET_LIFE
            pr.target = -1
            pr.alive = true
            fired++
        }
        if (fired == 0) return

        st.gunCooldown = GUN_INTERVAL
        st.gunHeat = min(1f, st.gunHeat + HEAT_PER_SHOT)
        if (st.gunHeat >= 1f) overheated = true
        world.ev(GameEvent.PLAYER_FIRE, p.x, p.z)
    }

    private fun deploySpecial() {
        val st = world.state
        if (st.weaponAmmo <= 0) return
        val ok = when (st.weapon) {
            WeaponKind.OIL_SLICK -> dropHazard(false, 2.7f, 9f, GameEvent.OIL_DROP)
            WeaponKind.SMOKE_SCREEN -> dropHazard(true, 5.2f, 5.5f, GameEvent.SMOKE_DROP)
            WeaponKind.MISSILE -> fireMissile()
            WeaponKind.MACHINE_GUN -> false
        }
        if (!ok) return                      // pool was full: do not eat the ammo

        st.weaponAmmo--
        if (st.weaponAmmo <= 0) {
            st.weaponAmmo = 0
            st.weapon = WeaponKind.MACHINE_GUN
        }
    }

    private fun dropHazard(smoke: Boolean, radius: Float, life: Float, e: GameEvent): Boolean {
        val st = world.state
        val h = st.spawnHazard() ?: return false
        val p = st.player
        h.isSmoke = smoke
        h.x = p.x
        h.z = p.z - VehicleKind.PLAYER.halfLength - 1.5f
        h.radius = radius
        h.maxLife = life
        h.life = life
        h.alive = true
        world.ev(e, h.x, h.z)
        return true
    }

    private fun fireMissile(): Boolean {
        val st = world.state
        val p = st.player
        val pr = st.spawnProjectile() ?: return false

        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in st.vehicles.indices) {
            val v = st.vehicles[i]
            if (!v.alive || v.isDying || !v.kind.isHostile) continue
            val dz = v.z - p.z
            if (dz < -12f || dz > 220f) continue
            var s = dz + abs(v.x - p.x)
            // Helicopters are the only thing the gun cannot touch, so the
            // missile always prefers them when one is in range.
            if (v.kind == VehicleKind.HELICOPTER) s *= 0.4f
            if (s < bestScore) { bestScore = s; best = i }
        }

        val sy = sinf(p.yaw)
        val cy = cosf(p.yaw)
        pr.fromPlayer = true
        pr.isMissile = true
        pr.x = p.x
        pr.y = 0.8f
        pr.z = p.z + VehicleKind.PLAYER.halfLength
        pr.vx = sy * MISSILE_SPEED
        pr.vy = 0f
        pr.vz = cy * MISSILE_SPEED + st.speed
        pr.life = MISSILE_LIFE
        pr.target = best
        pr.alive = true
        world.ev(GameEvent.MISSILE_FIRE, p.x, pr.z)
        return true
    }

    // =====================================================================
    //  flight
    // =====================================================================

    private fun updateProjectiles(dt: Float) {
        val st = world.state
        val p = st.player
        val cull = p.z - 45f

        for (pr in st.projectiles) {
            if (!pr.alive) continue
            pr.life -= dt
            if (pr.life <= 0f || pr.z < cull) { pr.alive = false; continue }

            if (pr.isMissile) home(pr, dt)
            if (!pr.fromPlayer) pr.vy -= BOMB_GRAVITY * dt

            pr.x += pr.vx * dt
            pr.y += pr.vy * dt
            pr.z += pr.vz * dt

            if (pr.fromPlayer) {
                hitVehicles(pr)
            } else if (pr.y <= 0.25f) {
                detonate(pr)
            } else {
                hitPlayer(pr)
            }
        }
    }

    private fun home(pr: Projectile, dt: Float) {
        if (pr.target < 0) return
        val t = world.state.vehicles[pr.target]
        if (!t.alive || t.isDying) { pr.target = -1; return }
        val dx = t.x - pr.x
        val dy = (t.y + 0.8f) - pr.y
        val dz = t.z - pr.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.001f) return
        // Rotate the velocity toward the target while preserving its magnitude.
        val sp = sqrt(pr.vx * pr.vx + pr.vy * pr.vy + pr.vz * pr.vz)
        val k = clamp(MISSILE_TURN * dt, 0f, 1f)
        val inv = sp / len
        pr.vx = lerp(pr.vx, dx * inv, k)
        pr.vy = lerp(pr.vy, dy * inv, k)
        pr.vz = lerp(pr.vz, dz * inv, k)
    }

    private fun hitVehicles(pr: Projectile) {
        for (v in world.state.vehicles) {
            if (!v.alive || v.isDying) continue
            val k = v.kind
            if (abs(pr.z - v.z) > k.halfLength + 0.6f) continue
            if (abs(pr.x - v.x) > k.halfWidth + 0.5f) continue
            // Vertical gate — this is what keeps bullets under the helicopters.
            if (abs(pr.y - v.y) > max(2f, k.halfWidth)) continue

            pr.alive = false
            if (pr.isMissile) {
                world.damage(v, 4, DeathCause.MISSILE)
                world.ev(GameEvent.ENEMY_EXPLODE, pr.x, pr.z)
                world.bumpShake(0.3f)
            } else {
                // ENFORCER and HELICOPTER armour is enforced inside damage();
                // the round is spent regardless, which is the feedback.
                world.damage(v, 1, DeathCause.GUNFIRE)
            }
            return
        }
    }

    private fun hitPlayer(pr: Projectile) {
        val st = world.state
        if (st.phase != Phase.PLAYING || pr.y > 2.4f) return
        val p = st.player
        if (abs(pr.x - p.x) < VehicleKind.PLAYER.halfWidth + 0.4f &&
            abs(pr.z - p.z) < VehicleKind.PLAYER.halfLength + 0.4f
        ) {
            pr.alive = false
            world.ev(GameEvent.ENEMY_EXPLODE, pr.x, pr.z)
            world.playerDeath(DeathCause.BOMB)
        }
    }

    /** Bomb reaching the deck: blast radius, splash and shake. */
    private fun detonate(pr: Projectile) {
        val st = world.state
        val p = st.player
        pr.alive = false
        pr.y = 0f
        world.ev(GameEvent.ENEMY_EXPLODE, pr.x, pr.z)
        if (world.surfaceAt(pr.z) == Surface.WATER) world.ev(GameEvent.VEHICLE_SPLASH, pr.x, pr.z)

        val d = abs(pr.x - p.x) + abs(pr.z - p.z)
        if (d < 16f) world.bumpShake(0.55f * (1f - d / 16f))
        if (abs(pr.x - p.x) < 3.2f && abs(pr.z - p.z) < 4.6f) world.playerDeath(DeathCause.BOMB)
    }

    // =====================================================================
    //  hazards
    // =====================================================================

    private fun updateHazards(dt: Float) {
        val st = world.state
        val cull = st.player.z - World.DESPAWN_BEHIND
        for (h in st.hazards) {
            if (!h.alive) continue
            h.life -= dt
            if (h.life <= 0f || h.z < cull) { h.alive = false; continue }

            val r2 = h.radius * h.radius
            for (v in st.vehicles) {
                if (!v.alive || v.isDying || v.spinning) continue
                // Civilians are spared: spinning one out would fire the penalty
                // for a weapon the player used defensively.
                if (!v.kind.isHostile || v.kind == VehicleKind.HELICOPTER) continue
                val dx = v.x - h.x
                val dz = v.z - h.z
                if (dx * dx + dz * dz > r2) continue
                v.spinning = true
                v.spinRate = if (dx >= 0f) 5.5f else -5.5f
                world.ev(GameEvent.TYRE_SCREECH, v.x, v.z)
            }
        }
    }
}
