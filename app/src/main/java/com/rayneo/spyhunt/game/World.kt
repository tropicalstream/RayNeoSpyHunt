package com.rayneo.spyhunt.game

import com.rayneo.spyhunt.core.clamp
import com.rayneo.spyhunt.core.cosf
import com.rayneo.spyhunt.core.damp
import com.rayneo.spyhunt.core.lerp
import com.rayneo.spyhunt.core.sinf
import com.rayneo.spyhunt.core.smoothstep
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The simulation. Owns the frame loop for [GameState] and delegates the three
 * busy subsystems to [Enemies], [Weapons] and [Spawner], which all reach back
 * through this class for road queries, scoring and damage so those rules live
 * in exactly one place.
 *
 * Everything here is allocation-free per frame: the pools in [GameState] are
 * fixed, the road is regenerated in place, and the only scratch storage is the
 * two extent arrays used by the collision pass.
 */
class World(val state: GameState) {

    companion object {
        const val MIN_SPEED = 30f
        const val MAX_SPEED = 85f

        /** Lateral authority in m/s, at min and max speed. */
        private const val STEER_LOW = 8f
        private const val STEER_HIGH = 16f
        private const val STEER_RESPONSE = 12f

        const val LEVEL_DISTANCE = 2000f
        const val DEATH_TIME = 2.5f
        const val COUNTDOWN_TIME = 3.4f
        const val COMBO_WINDOW = 4f
        const val MAX_COMBO = 30

        /** Anything further behind the player than this is recycled. */
        const val DESPAWN_BEHIND = 60f
        const val DESPAWN_AHEAD = 260f

        /** Closing speed above which a front/rear impact is fatal, m/s. */
        private const val RAM_LETHAL = 15f
        private const val SHOVE = 7f

        private const val POINTS_PER_METRE = 1f
        private const val CIVILIAN_PENALTY = 750L

        /** Invulnerability after a respawn so you never die in the same breath. */
        private const val RESPAWN_GRACE = 1.6f
    }

    private val enemies = Enemies(this)
    private val weapons = Weapons(this)
    private val spawner = Spawner(this)

    /** Sub-point distance score carried between frames; Long truncation would eat it. */
    private var scoreFrac = 0f
    private var screechCd = 0f
    private var countdownTicks = 0
    private var grace = 0f

    /** Rotated-AABB half extents, rebuilt once per frame by [collisions]. */
    private val exArr = FloatArray(state.vehicles.size)
    private val ezArr = FloatArray(state.vehicles.size)

    init {
        // The renderer can draw before the first update lands (calibration holds
        // the sim), so the road strip must never be left at its default zeros.
        RoadTopology.generate(state)
    }

    // =====================================================================
    //  frame
    // =====================================================================

    fun update(dt: Float, input: InputState) {
        // Clamp: a GC hitch or a resume must not teleport actors through walls.
        val d = clamp(dt, 0f, 0.05f)
        state.time += d
        state.phaseTime += d
        if (grace > 0f) grace -= d

        when (state.phase) {
            Phase.CALIBRATING -> {
                RoadTopology.generate(state)
                decay(d)
            }
            Phase.ATTRACT -> {
                updateAttract(d)
                // The start screen owns the tap: it commits the highlighted mode.
                // Selection itself is stepped by the renderer before this runs.
                if (input.menuPressed || input.firePressed) {
                    state.style = RenderStyle.ordered[
                        ((state.menuIndex % RenderStyle.ordered.size) + RenderStyle.ordered.size) %
                            RenderStyle.ordered.size
                    ]
                    startGame()
                }
            }
            Phase.COUNTDOWN -> updateCountdown(d)
            Phase.PLAYING -> updatePlaying(d, input)
            Phase.DYING -> updateDying(d)
            Phase.GAME_OVER -> {
                updateGameOver(d)
                // Short lockout so the shot that killed you cannot restart the run.
                if (state.phaseTime > 1.5f) {
                    // Tap replays the same mode immediately; a long press goes back to
                    // the start screen, which is the only way to change mode.
                    if (input.menuPressed) setPhase(Phase.ATTRACT)
                    else if (input.firePressed) startGame()
                }
            }
        }
    }

    /** Full reset into the pre-run countdown. */
    fun startGame() {
        val p = state.player
        for (v in state.vehicles) { v.reset(); v.alive = false }
        for (pr in state.projectiles) pr.alive = false
        for (h in state.hazards) h.alive = false

        p.reset()
        p.kind = VehicleKind.PLAYER
        p.alive = true
        p.x = RoadTopology.sampleCenterX(0f, 1)
        p.z = 0f
        p.y = 0f

        state.speed = 0f
        state.targetSpeed = MIN_SPEED
        state.steer = 0f
        state.onWater = false
        state.morph = 0f
        state.lives = 3
        state.score = 0L
        state.nextExtraLifeAt = 10_000L
        state.distance = 0f
        state.level = 1
        state.weapon = WeaponKind.MACHINE_GUN
        state.weaponAmmo = 0
        state.gunHeat = 0f
        state.gunCooldown = 0f
        state.combo = 0
        state.comboTimer = 0f
        state.shake = 0f

        scoreFrac = 0f
        screechCd = 0f
        countdownTicks = 0
        grace = 0f
        weapons.resetHeat()
        spawner.reset()

        RoadTopology.generate(state)
        setPhase(Phase.COUNTDOWN)
    }

    // =====================================================================
    //  phases
    // =====================================================================

    private fun updatePlaying(dt: Float, input: InputState) {
        updatePlayer(dt, input)
        updateSurface(dt)
        RoadTopology.generate(state)
        spawner.update(dt, true)
        enemies.update(dt)
        weapons.update(dt, input)
        collisions()
        updateProgress(dt)
        decay(dt)
    }

    private fun updateCountdown(dt: Float) {
        val p = state.player
        // Roll forward gently so the scene has motion behind the GET READY card.
        state.speed = damp(state.speed, MIN_SPEED * 0.55f, 1.1f, dt)
        p.z += state.speed * dt
        p.vz = state.speed
        p.wobble += dt * (3f + state.speed * 0.12f)
        p.x = damp(p.x, centerAt(p.z), 3f, dt)
        RoadTopology.generate(state)

        val want = min(4, floor(state.phaseTime).toInt() + 1)
        while (countdownTicks < want) {
            countdownTicks++
            ev(GameEvent.COUNTDOWN_TICK, p.x, p.z)
        }
        if (state.phaseTime >= COUNTDOWN_TIME) {
            grace = RESPAWN_GRACE
            setPhase(Phase.PLAYING)
        }
        decay(dt)
    }

    private fun updateDying(dt: Float) {
        val p = state.player
        p.yaw += p.spinRate * dt
        p.spinRate = damp(p.spinRate, 0f, 0.8f, dt)
        state.speed = damp(state.speed, 0f, 1.4f, dt)
        p.vz = state.speed
        p.vx = damp(p.vx, 0f, 1.5f, dt)
        p.x += p.vx * dt
        p.z += state.speed * dt
        p.wobble += dt * 8f

        RoadTopology.generate(state)
        spawner.update(dt, false)
        enemies.update(dt)
        weapons.updatePassive(dt)
        decay(dt)

        if (state.phaseTime >= DEATH_TIME) {
            if (state.lives <= 0) {
                ev(GameEvent.GAME_OVER, p.x, p.z)
                setPhase(Phase.GAME_OVER)
            } else {
                respawn()
            }
        }
    }

    private fun updateGameOver(dt: Float) {
        val p = state.player
        state.speed = damp(state.speed, 0f, 1.2f, dt)
        p.vz = state.speed
        p.z += state.speed * dt
        p.yaw += p.spinRate * dt
        p.spinRate = damp(p.spinRate, 0f, 1.2f, dt)
        RoadTopology.generate(state)
        spawner.update(dt, false)
        enemies.update(dt)
        weapons.updatePassive(dt)
        decay(dt)
    }

    /** Autopilot demo drive so the attract screen is never a static picture. */
    private fun updateAttract(dt: Float) {
        val p = state.player
        state.speed = damp(state.speed, 46f, 0.8f, dt)
        state.targetSpeed = 46f
        // Aim a little ahead — reads as a driven line, not a snap. Not at the
        // raw centreline: on a forked stretch that is the island, and the demo
        // car would drive straight down the undrivable middle.
        val aim = drivableX(p.z + 26f, p.x)
        p.vx = damp(p.vx, clamp((aim - p.x) * 1.3f, -14f, 14f), 5f, dt)
        p.x += p.vx * dt
        p.z += state.speed * dt
        p.vz = state.speed
        p.yaw = clamp(p.vx / max(state.speed, 12f), -0.5f, 0.5f)
        p.wobble += dt * (3f + state.speed * 0.12f)

        updateSurface(dt)
        RoadTopology.generate(state)
        spawner.update(dt, false)
        enemies.update(dt)
        weapons.updatePassive(dt)
        decay(dt)
    }

    private fun setPhase(p: Phase) {
        state.phase = p
        state.phaseTime = 0f
    }

    private fun decay(dt: Float) {
        state.shake = damp(state.shake, 0f, 4.5f, dt)
        if (screechCd > 0f) screechCd -= dt
    }

    private fun respawn() {
        val p = state.player
        p.spinning = false
        p.spinRate = 0f
        p.yaw = 0f
        p.vx = 0f
        p.dyingFor = -1f
        p.deathCause = DeathCause.NONE
        p.x = drivableX(p.z, p.x)
        state.speed = MIN_SPEED
        state.steer = 0f
        state.gunHeat = 0f
        state.gunCooldown = 0f
        state.weapon = WeaponKind.MACHINE_GUN
        state.weaponAmmo = 0
        state.combo = 0
        state.comboTimer = 0f
        weapons.resetHeat()

        // Clear the immediate neighbourhood — respawning inside a roadblock is
        // an instant second death and reads as a bug.
        for (v in state.vehicles) {
            if (v.alive && v.z > p.z - 40f && v.z < p.z + 90f) v.alive = false
        }
        for (pr in state.projectiles) pr.alive = false

        grace = RESPAWN_GRACE
        setPhase(Phase.PLAYING)
    }

    // =====================================================================
    //  player
    // =====================================================================

    private fun updatePlayer(dt: Float, input: InputState) {
        val p = state.player
        val cx = centerAt(p.z)
        val hw = halfWidthAt(p.z)
        val gap = forkGapAt(p.z)
        val carHalf = VehicleKind.PLAYER.halfWidth
        val carW = VehicleKind.PLAYER.width
        val dist = abs(p.x - cx)

        // 0 = fully on the tarmac, 1 = every wheel past the edge (or buried in
        // the fork island). Both hazards feed one number so the handling,
        // audio and crash rules only have to reason about one thing.
        val edgeOut = clamp((dist + carHalf - hw) / carW, 0f, 1f)
        val islandOut = if (gap > 0.01f) clamp((gap + carHalf - dist) / carW, 0f, 1f) else 0f
        val bad = max(edgeOut, islandOut)

        // ---- longitudinal ----
        val thr = clamp(input.throttle, 0f, 1f)
        var tgt = lerp(MIN_SPEED, MAX_SPEED, thr)
        if (bad > 0f) tgt = lerp(tgt, MIN_SPEED * 0.4f, bad)   // dirt/verge drags hard
        state.targetSpeed = tgt
        val rate = if (tgt < state.speed) 1.6f + 4f * bad else 0.75f
        state.speed = damp(state.speed, tgt, rate, dt)

        // ---- lateral ----
        val spd01 = smoothstep(MIN_SPEED, MAX_SPEED, state.speed)
        val authority = lerp(STEER_LOW, STEER_HIGH, spd01)
        state.steer = damp(state.steer, clamp(input.steer, -1f, 1f), STEER_RESPONSE, dt)
        // Off the tarmac you keep momentum but lose bite; a boat hull is looser still.
        val targetVx = state.steer * authority * lerp(1f, 0.4f, bad)
        p.vx = damp(p.vx, targetVx, lerp(9f, 4.5f, state.morph), dt)
        p.x += p.vx * dt

        p.vz = state.speed
        p.z += state.speed * dt
        state.distance += state.speed * dt
        p.yaw = clamp(p.vx / max(state.speed, 12f), -0.55f, 0.55f)
        p.wobble += dt * (3f + state.speed * 0.12f)
        p.age += dt

        // ---- feedback ----
        if (bad > 0f) {
            state.shake = max(state.shake, bad * 0.35f)
            if (screechCd <= 0f) {
                ev(GameEvent.TYRE_SCREECH, p.x, p.z)
                screechCd = 0.22f
            }
        } else if (abs(state.steer) > 0.82f && spd01 > 0.55f && screechCd <= 0f) {
            ev(GameEvent.TYRE_SCREECH, p.x, p.z)
            screechCd = 0.35f
        }

        if (bad >= 0.999f) playerDeath(DeathCause.RAN_OFF_ROAD)
    }

    /** Road/water transition and the car-to-boat morph that rides on it. */
    private fun updateSurface(dt: Float) {
        val p = state.player
        val water = surfaceAt(p.z) == Surface.WATER
        if (water != state.onWater) {
            state.onWater = water
            if (water) {
                ev(GameEvent.ENTER_WATER, p.x, p.z)
                ev(GameEvent.VEHICLE_SPLASH, p.x, p.z)
                bumpShake(0.35f)
            } else {
                ev(GameEvent.ENTER_ROAD, p.x, p.z)
                bumpShake(0.25f)
            }
        }
        state.morph = damp(state.morph, if (state.onWater) 1f else 0f, 5.5f, dt)
    }

    private fun updateProgress(dt: Float) {
        val p = state.player

        scoreFrac += state.speed * dt * POINTS_PER_METRE
        if (scoreFrac >= 1f) {
            val whole = floor(scoreFrac)
            scoreFrac -= whole
            addScore(whole.toLong())
        }

        val wantLevel = 1 + (state.distance / LEVEL_DISTANCE).toInt()
        while (state.level < wantLevel) {
            state.level++
            ev(GameEvent.LEVEL_UP, p.x, p.z)
        }

        if (state.combo > 0) {
            state.comboTimer -= dt
            if (state.comboTimer <= 0f) {
                state.combo = 0
                state.comboTimer = 0f
            }
        }
    }

    // =====================================================================
    //  collision
    // =====================================================================

    /**
     * Player against everything else. Boxes are axis-aligned but their extents
     * are the *rotated* box's bounds, so a yawed car sweeps a wider footprint
     * and side-swipes trigger where they look like they should.
     */
    private fun collisions() {
        if (state.phase != Phase.PLAYING) return
        val p = state.player
        val vs = state.vehicles

        val pc = abs(cosf(p.yaw)); val ps = abs(sinf(p.yaw))
        val pex = VehicleKind.PLAYER.halfWidth * pc + VehicleKind.PLAYER.halfLength * ps
        val pez = VehicleKind.PLAYER.halfLength * pc + VehicleKind.PLAYER.halfWidth * ps

        for (i in vs.indices) {
            val v = vs[i]
            if (!v.alive) { exArr[i] = 0f; ezArr[i] = 0f; continue }
            val c = abs(cosf(v.yaw)); val s = abs(sinf(v.yaw))
            exArr[i] = v.kind.halfWidth * c + v.kind.halfLength * s
            ezArr[i] = v.kind.halfLength * c + v.kind.halfWidth * s
        }

        for (i in vs.indices) {
            val v = vs[i]
            if (!v.alive || v.isDying) continue
            if (v.y > 2.5f) continue                       // airborne, nothing to hit
            val ddx = v.x - p.x
            val ddz = v.z - p.z
            // 0.92 shrink: forgiving hitboxes read as fair at this speed.
            val sx = (pex + exArr[i]) * 0.92f
            val sz = (pez + ezArr[i]) * 0.92f
            if (abs(ddx) < sx && abs(ddz) < sz) contact(v, ddx, ddz, sx, sz)
        }
    }

    private fun contact(v: Vehicle, ddx: Float, ddz: Float, sx: Float, sz: Float) {
        val p = state.player
        val penX = sx - abs(ddx)
        val penZ = sz - abs(ddz)
        // Shallower overlap on X means the boxes met side-on.
        val lateral = penX < penZ
        val dirX = if (ddx >= 0f) 1f else -1f

        if (v.kind.isCivilian) {
            killVehicle(v, DeathCause.COLLISION)
            state.speed *= 0.8f
            bumpShake(0.6f)
            return
        }

        if (v.kind == VehicleKind.WEAPONS_VAN) {
            // Only the rear doors hand out hardware.
            if (ddz > 0f) weapons.pickup(v) else shove(v, dirX, penX)
            return
        }

        if (!v.kind.isHostile) return

        if (grace > 0f) { shove(v, dirX, penX); return }

        // A switchblade in its attack swing takes the tyres out on any contact.
        if (v.kind == VehicleKind.SWITCHBLADE && v.aiState == 2) {
            playerDeath(DeathCause.COLLISION)
            v.aiState = 3
            return
        }

        val closing = abs(v.vz - state.speed)
        if (!lateral && closing > RAM_LETHAL) {
            playerDeath(DeathCause.COLLISION)
            damage(v, 2, DeathCause.COLLISION)
            return
        }

        if (lateral) {
            shove(v, dirX, penX)
        } else {
            // Soft nose-to-tail nudge: both lose speed, nobody dies.
            //
            // The boxes must be separated along Z as well. Unlike shove() this
            // branch used to resolve nothing, so a rear-end stayed interpenetrated:
            // the 0.88 loss compounded once per frame (0.88^60 ≈ 0.0005/s) and the
            // car stopped dead, while the ungated screech pushed 60 events/s into
            // the 64-slot EventQueue and starved every other event that frame.
            val dirZ = if (ddz >= 0f) 1f else -1f
            v.z += dirZ * (penZ * 0.5f + 0.05f)
            state.speed *= 0.88f
            v.vz = lerp(v.vz, state.speed, 0.25f)
            bumpShake(0.4f)
            if (screechCd <= 0f) {
                ev(GameEvent.TYRE_SCREECH, p.x, p.z)
                screechCd = 0.18f
            }
        }
    }

    /**
     * Separates two overlapping cars laterally. The split is weighted by how
     * immovable the other vehicle is, which is the whole mechanic behind the
     * ENFORCER: lean on it long enough and it goes over the kerb.
     */
    private fun shove(v: Vehicle, dirX: Float, pen: Float) {
        val p = state.player
        val m = immovability(v.kind)
        val sep = pen * 0.5f + 0.05f
        v.x += dirX * sep * (1f - m)
        p.x -= dirX * sep * m
        v.vx += dirX * SHOVE * (1f - m)
        p.vx -= dirX * SHOVE * m
        state.speed *= 0.96f
        bumpShake(0.45f)
        if (screechCd <= 0f) {
            ev(GameEvent.TYRE_SCREECH, p.x, p.z)
            screechCd = 0.18f
        }
    }

    /** 0 = feather, 1 = immovable. Kept below 0.75 so nothing is un-pushable. */
    private fun immovability(k: VehicleKind): Float = when (k) {
        VehicleKind.MOTORCYCLE -> 0.2f
        VehicleKind.ENFORCER -> 0.62f
        VehicleKind.ROAD_LORD -> 0.7f
        VehicleKind.CIVILIAN_TRUCK -> 0.75f
        VehicleKind.GUNBOAT -> 0.6f
        else -> 0.5f
    }

    // =====================================================================
    //  damage, death, scoring — the shared rulebook
    // =====================================================================

    /** Applies [amount] damage unless the target's armour says otherwise. */
    fun damage(v: Vehicle, amount: Int, cause: DeathCause) {
        if (!v.alive || v.isDying) return
        // Armoured, out of reach, and friendly respectively: bullets spark and
        // stop. The van especially — it has one hit point and sits dead ahead,
        // so without this a player holding the trigger destroys his own resupply
        // before he can reach it and never sees a special weapon at all.
        if (cause == DeathCause.GUNFIRE &&
            (v.kind == VehicleKind.ENFORCER ||
                v.kind == VehicleKind.HELICOPTER ||
                v.kind == VehicleKind.WEAPONS_VAN)
        ) {
            v.flashUntil = state.time + 0.07f
            return
        }
        v.health -= amount
        v.flashUntil = state.time + 0.1f
        if (v.health <= 0) killVehicle(v, cause)
    }

    /** Starts the death animation and settles the scoring consequences. */
    fun killVehicle(v: Vehicle, cause: DeathCause) {
        if (!v.alive || v.isDying) return
        v.deathCause = cause
        v.dyingFor = 0f
        v.spinning = true
        v.spinRate = state.rng.range(-7f, 7f)
        v.health = 0

        when {
            v.kind.isCivilian -> {
                // Fine him only when he actually did it. Traffic that drowns at
                // a shoreline, or ditches itself on a bend, is not the player's
                // doing — the guilt sting plus 750 points and a broken combo for
                // something he never touched reads as the game punishing him at
                // random. Entering a crossing wipes every civilian on the road
                // at once, so this was up to 3000 points a river.
                if (cause == DeathCause.DESPAWN || cause == DeathCause.RAN_OFF_ROAD) {
                    ev(GameEvent.ENEMY_EXPLODE, v.x, v.z)
                } else {
                    ev(GameEvent.CIVILIAN_KILLED, v.x, v.z)
                    state.combo = 0
                    state.comboTimer = 0f
                    addScore(-CIVILIAN_PENALTY)
                }
            }
            v.kind.isHostile -> {
                ev(GameEvent.ENEMY_EXPLODE, v.x, v.z)
                if (cause != DeathCause.DESPAWN) {
                    state.combo = min(state.combo + 1, MAX_COMBO)
                    state.comboTimer = COMBO_WINDOW
                    val mult = 1L + min(state.combo - 1, 9).toLong()
                    addScore(killPoints(v.kind, cause) * mult)
                }
            }
            else -> ev(GameEvent.ENEMY_EXPLODE, v.x, v.z)
        }

        if (surfaceAt(v.z) == Surface.WATER) ev(GameEvent.VEHICLE_SPLASH, v.x, v.z)
    }

    private fun killPoints(k: VehicleKind, cause: DeathCause): Long = when (k) {
        VehicleKind.SWITCHBLADE -> 500L
        VehicleKind.MOTORCYCLE -> 300L
        VehicleKind.ROAD_LORD -> 1500L
        VehicleKind.GUNBOAT -> 800L
        VehicleKind.HELICOPTER -> 3000L
        // The Enforcer cannot be shot, so ditching it is the trophy shot.
        VehicleKind.ENFORCER -> if (cause == DeathCause.RAN_OFF_ROAD) 5000L else 1000L
        else -> 100L
    }

    fun addScore(points: Long) {
        if (points == 0L) return
        state.score = max(0L, state.score + points)
        while (state.score >= state.nextExtraLifeAt) {
            state.lives++
            ev(GameEvent.EXTRA_LIFE, state.player.x, state.player.z)
            // Roughly double each time, so extra lives thin out as you get good.
            state.nextExtraLifeAt = state.nextExtraLifeAt * 2L + 5_000L
        }
    }

    /** Loses a life and enters the death spin. No-op during respawn grace. */
    fun playerDeath(cause: DeathCause) {
        if (state.phase != Phase.PLAYING || grace > 0f) return
        val p = state.player
        state.lives--
        state.combo = 0
        state.comboTimer = 0f
        p.deathCause = cause
        p.spinning = true
        p.spinRate = state.rng.range(-9f, 9f)
        if (p.spinRate > -3f && p.spinRate < 3f) p.spinRate += 5f
        bumpShake(1.4f)
        ev(GameEvent.PLAYER_HIT, p.x, p.z)
        if (cause == DeathCause.RAN_OFF_ROAD) ev(GameEvent.TYRE_SCREECH, p.x, p.z)
        if (surfaceAt(p.z) == Surface.WATER) ev(GameEvent.VEHICLE_SPLASH, p.x, p.z)
        setPhase(Phase.DYING)
    }

    fun bumpShake(a: Float) {
        state.shake = min(1.5f, state.shake + a)
    }

    // =====================================================================
    //  queries used by the sibling modules
    // =====================================================================

    /**
     * A drivable X at [z], biased toward [preferX]. Where the road forks the
     * centreline is the *island*, not tarmac, so anything that "recentres" the
     * car has to go through here — dropping it on the centreline mid-fork
     * buries it in undrivable ground and spends the next life too.
     */
    private fun drivableX(z: Float, preferX: Float): Float {
        val cx = centerAt(z)
        val gap = forkGapAt(z)
        if (gap <= 0.05f) return cx
        val half = VehicleKind.PLAYER.halfWidth
        val lo = gap + half + 0.4f
        val hi = max(lo, halfWidthAt(z) - half - 0.2f)
        val side = if (preferX >= cx) 1f else -1f
        return cx + side * clamp(abs(preferX - cx), lo, hi)
    }

    fun centerAt(z: Float): Float = RoadTopology.sampleCenterX(z, state.level)
    fun halfWidthAt(z: Float): Float = RoadTopology.sampleHalfWidth(z, state.level)
    fun surfaceAt(z: Float): Surface = RoadTopology.sampleSurface(z, state.level)
    fun forkGapAt(z: Float): Float = RoadTopology.sampleForkGap(z, state.level)

    fun ev(e: GameEvent, x: Float, z: Float) = state.events.push(e, x, z)

    /** True while the player is briefly untouchable after a respawn. */
    fun graceActive(): Boolean = grace > 0f
}
