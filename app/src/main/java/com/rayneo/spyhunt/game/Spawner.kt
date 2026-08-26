package com.rayneo.spyhunt.game

import com.rayneo.spyhunt.core.Rng
import com.rayneo.spyhunt.core.clamp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Difficulty director. Keeps a sensible population alive around the player,
 * matched to the surface under the spawn point, and quietly recycles anything
 * that has fallen out of play.
 *
 * Almost everything enters ahead of the player and lets him close: the gun only
 * fires forward, so anything introduced behind is unanswerable until its own AI
 * brings it back into the arc. The ROAD_LORD is the deliberate exception — it
 * is the one enemy whose whole act is arriving in the mirrors. Nothing is
 * spawned on top of another actor, and a failed attempt simply retries on the
 * next tick rather than forcing a bad placement.
 */
class Spawner(private val world: World) {

    companion object {
        private const val MAX_ACTIVE_HOSTILES = 9
        private const val MAX_CIVILIANS = 4
        private const val TWO_PI = 6.2831855f

        /**
         * Retry delay after a placement is refused (wrong surface, occupied).
         * Burning the full interval on a refusal is how a whole set-piece goes
         * missing: a water crossing sitting where the weapons van wanted to
         * appear silently costs the player their special weapon for half a
         * minute, and they never learn why.
         */
        private const val RETRY = 0.4f
    }

    private var hostileTimer = 2.5f
    private var civTimer = 1.2f
    private var vanTimer = 14f

    /** Reusable weighted roster — rebuilt in place so picking allocates nothing. */
    private val wKind = arrayOfNulls<VehicleKind>(6)
    private val wVal = FloatArray(6)
    private var wCount = 0

    fun reset() {
        hostileTimer = 2.5f
        civTimer = 1.2f
        vanTimer = 14f
        wCount = 0
    }

    /**
     * @param hostilesAllowed false during attract/death/game-over, where traffic
     *        should keep flowing but nothing may attack.
     */
    fun update(dt: Float, hostilesAllowed: Boolean) {
        val st = world.state
        recycle()

        var hostiles = 0
        var civilians = 0
        var vans = 0
        var helis = 0
        var enforcers = 0
        for (v in st.vehicles) {
            if (!v.alive || v.isDying) continue
            when {
                v.kind == VehicleKind.WEAPONS_VAN -> vans++
                v.kind.isCivilian -> civilians++
                v.kind.isHostile -> {
                    hostiles++
                    if (v.kind == VehicleKind.HELICOPTER) helis++
                    if (v.kind == VehicleKind.ENFORCER) enforcers++
                }
                else -> {}
            }
        }

        val rng = st.rng
        val lvl = st.level
        val maxHostiles = min(MAX_ACTIVE_HOSTILES, 2 + lvl)
        val interval = max(0.55f, 2.4f - 0.16f * (lvl - 1))

        civTimer -= dt
        if (civTimer <= 0f && civilians < MAX_CIVILIANS) {
            civTimer = if (spawnCivilian(rng)) rng.range(1.6f, 3.6f) else RETRY
        }

        if (!hostilesAllowed) return

        hostileTimer -= dt
        if (hostileTimer <= 0f && hostiles < maxHostiles) {
            hostileTimer = if (spawnHostile(rng, lvl, helis, enforcers)) {
                interval * rng.range(0.7f, 1.35f)
            } else RETRY
        }

        vanTimer -= dt
        // Only offer a resupply when the player has nothing special left.
        if (vanTimer <= 0f && vans == 0 &&
            (st.weapon == WeaponKind.MACHINE_GUN || st.weaponAmmo <= 0)
        ) {
            vanTimer = if (spawnVan(rng)) rng.range(22f, 34f) else RETRY
        }
    }

    // =====================================================================
    //  spawning
    // =====================================================================

    /** @return true if a vehicle was actually placed. */
    private fun spawnHostile(rng: Rng, lvl: Int, helis: Int, enforcers: Int): Boolean {
        val st = world.state
        val p = st.player
        // Choose the roster from the surface the player is actually driving into.
        val water = world.surfaceAt(p.z + 40f) == Surface.WATER
        val kind = pickKind(rng, lvl, water, helis, enforcers) ?: return false

        // Only the rammer comes up in the mirrors. Everything else enters ahead
        // so the player sees it coming and can answer it with the gun; the AI
        // station-keeps back to wherever it wants to be from there.
        val behind = kind == VehicleKind.ROAD_LORD
        val z = if (behind) p.z - rng.range(22f, 55f) else p.z + rng.range(65f, 125f)

        // The surface can change between here and the player; bail rather than
        // drop a car into a river or a gunboat onto tarmac.
        val zWater = world.surfaceAt(z) == Surface.WATER
        if (kind == VehicleKind.GUNBOAT && !zWater) return false
        if (kind != VehicleKind.GUNBOAT && kind != VehicleKind.HELICOPTER && zWater) return false

        val x = lateral(z, kind, if (rng.chance(0.5f)) rng.range(-0.7f, -0.15f) else rng.range(0.15f, 0.7f))
        if (occupied(x, z, 12f)) return false

        val vz = if (behind) st.speed + rng.range(4f, 12f)
        else max(World.MIN_SPEED, st.speed - rng.range(0f, 8f))
        val v = place(kind, x, z, vz) ?: return false

        when (kind) {
            VehicleKind.SWITCHBLADE -> v.ai1 = if (x >= p.x) 1f else -1f
            VehicleKind.MOTORCYCLE -> v.ai1 = rng.range(0f, TWO_PI)
            VehicleKind.GUNBOAT -> v.ai0 = rng.range(0f, TWO_PI)
            VehicleKind.HELICOPTER -> {
                v.y = 18f                       // drops into its orbit altitude
                v.ai0 = rng.range(0f, TWO_PI)
                v.ai1 = 1.5f                    // first bomb is telegraphed
            }
            else -> {}
        }
        return true
    }

    /** @return true if a vehicle was actually placed. */
    private fun spawnCivilian(rng: Rng): Boolean {
        val p = world.state.player
        val z = p.z + rng.range(80f, 170f)
        if (world.surfaceAt(z) != Surface.ROAD) return false
        val kind = if (rng.chance(0.25f)) VehicleKind.CIVILIAN_TRUCK else VehicleKind.CIVILIAN_CAR
        val frac = laneFraction(rng)
        val x = lateral(z, kind, frac)
        if (occupied(x, z, 16f)) return false
        // Always slower than the player's floor speed, so traffic is something
        // you overtake rather than something that ambushes you.
        val cruise = rng.range(20f, 29f)
        val v = place(kind, x, z, cruise) ?: return false
        v.ai0 = cruise
        v.ai1 = frac
        return true
    }

    /** @return true if a vehicle was actually placed. */
    private fun spawnVan(rng: Rng): Boolean {
        val st = world.state
        val p = st.player
        val z = p.z + rng.range(90f, 150f)
        // The chase takes several seconds, so the whole stretch has to stay dry.
        // Spawning just short of a river means the van drowns before the player
        // can reach it and the resupply silently never happens.
        if (world.surfaceAt(z) != Surface.ROAD || world.surfaceAt(z + 170f) != Surface.ROAD) return false
        val frac = if (rng.chance(0.5f)) -0.3f else 0.3f
        val x = lateral(z, VehicleKind.WEAPONS_VAN, frac)
        if (occupied(x, z, 22f)) return false
        // A fixed 14 m/s slower than the player, so the chase takes the same few
        // seconds whatever throttle he is holding. Clamping the top end tighter
        // than this makes the van uncatchable at speed for no design reason.
        val cruise = clamp(st.speed - 14f, World.MIN_SPEED - 8f, World.MAX_SPEED - 14f)
        val v = place(VehicleKind.WEAPONS_VAN, x, z, cruise) ?: return false
        v.ai0 = cruise
        v.ai1 = frac
        v.aiState = 0
        return true
    }

    // =====================================================================
    //  placement helpers
    // =====================================================================

    /** Turns a signed lane fraction into a world X that clears the fork island. */
    private fun lateral(z: Float, kind: VehicleKind, frac: Float): Float {
        val cx = world.centerAt(z)
        val hw = world.halfWidthAt(z)
        val gap = world.forkGapAt(z)
        var off = frac * max(0f, hw - kind.width)
        if (gap > 0.05f) {
            val side = if (off >= 0f) 1f else -1f
            val lo = gap + kind.halfWidth + 0.4f
            val hi = max(lo, hw - kind.halfWidth - 0.2f)
            off = side * clamp(abs(off), lo, hi)
        }
        return cx + off
    }

    private fun occupied(x: Float, z: Float, zPad: Float): Boolean {
        for (v in world.state.vehicles) {
            if (!v.alive) continue
            if (abs(v.z - z) < zPad && abs(v.x - x) < 3.5f) return true
        }
        return false
    }

    private fun place(kind: VehicleKind, x: Float, z: Float, vz: Float): Vehicle? {
        val v = world.state.spawnVehicle() ?: return null
        v.kind = kind
        v.x = x
        v.z = z
        v.y = 0f
        v.vx = 0f
        v.vz = vz
        v.health = kind.maxHealth
        v.alive = true
        return v
    }

    /** Recycled silently — a despawn is bookkeeping, not an event worth hearing. */
    private fun recycle() {
        val st = world.state
        val back = st.player.z - World.DESPAWN_BEHIND
        val front = st.player.z + World.DESPAWN_AHEAD
        for (v in st.vehicles) {
            if (!v.alive) continue
            if (v.z < back || v.z > front) v.alive = false
        }
    }

    private fun laneFraction(rng: Rng): Float = when (rng.int(4)) {
        0 -> -0.62f
        1 -> -0.22f
        2 -> 0.22f
        else -> 0.62f
    }

    // =====================================================================
    //  roster
    // =====================================================================

    private fun pickKind(rng: Rng, lvl: Int, water: Boolean, helis: Int, enforcers: Int): VehicleKind? {
        wCount = 0
        if (water) {
            addW(VehicleKind.GUNBOAT, 1f)
            if (lvl >= 3 && helis < 2) addW(VehicleKind.HELICOPTER, 0.35f)
        } else {
            addW(VehicleKind.SWITCHBLADE, 3f)
            addW(VehicleKind.MOTORCYCLE, if (lvl >= 2) 2.2f else 1.2f)
            if (lvl >= 2) addW(VehicleKind.ROAD_LORD, 2f)
            // One Enforcer at a time: two of them is a wall, not a duel.
            if (lvl >= 2 && enforcers < 1) addW(VehicleKind.ENFORCER, 1.5f)
            if (lvl >= 3 && helis < 2) addW(VehicleKind.HELICOPTER, 1.2f)
        }
        return pick(rng)
    }

    private fun addW(k: VehicleKind, w: Float) {
        if (w <= 0f || wCount >= wVal.size) return
        wKind[wCount] = k
        wVal[wCount] = w
        wCount++
    }

    private fun pick(rng: Rng): VehicleKind? {
        if (wCount == 0) return null
        var total = 0f
        for (i in 0 until wCount) total += wVal[i]
        if (total <= 0f) return null
        var r = rng.f() * total
        for (i in 0 until wCount) {
            r -= wVal[i]
            if (r <= 0f) return wKind[i]
        }
        return wKind[wCount - 1]
    }
}
