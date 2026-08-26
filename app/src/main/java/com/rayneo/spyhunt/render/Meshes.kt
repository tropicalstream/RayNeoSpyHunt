package com.rayneo.spyhunt.render

import com.rayneo.spyhunt.core.Mesh
import com.rayneo.spyhunt.game.VehicleKind

/**
 * Every piece of geometry in the game, generated in code — there are no model
 * files. Built once on the GL thread by [build].
 *
 * DESIGN RULES that the numbers below all obey:
 *  - Vehicles face +Z, sit on y=0 and are centred on x=0. Extents come straight
 *    from [VehicleKind] so the silhouette and the collision box agree.
 *  - The waveguide is additive: black is *transparent*, so nothing gets a large
 *    dim fill. Body panels are saturated and carry a small baseline glow so they
 *    stay legible when the key light is behind them; accents are HDR (2..6) at
 *    glow=1 and are what the bloom pass turns into streaks.
 *  - Silhouette does the identification work at 640x480 per eye: interceptor =
 *    low wedge, limo = long, enforcer = tall slab, van = big box with a lit
 *    hole in the back. Colour is the second cue, never the only one.
 *  - Civilians are deliberately quiet — desaturated, glow ~0.12, and no colour
 *    component ever reaches 1.0, so they never enter the bloom bright-pass.
 *    "Don't shoot" has to be readable in a glance.
 *  - 60..260 tris per vehicle. Adreno 621, and everything is drawn twice.
 */
object Meshes {

    /**
     * Height of the main-rotor hub above the helicopter's own origin. The rotor
     * is a separate mesh built around *its* origin, on the Y axis, so the
     * renderer can lift it here and spin it with a plain rotateY; the mast on
     * the fuselage reaches exactly this high to meet it.
     */
    const val HELI_ROTOR_Y = 2.1f

    /** Rotor span *is* the helicopter's collision width — keep the two tied together. */
    val HELI_ROTOR_RADIUS = VehicleKind.HELICOPTER.halfWidth

    private var built = false

    lateinit var playerCar: Mesh
    lateinit var playerBoat: Mesh
    lateinit var switchblade: Mesh
    lateinit var roadLord: Mesh
    lateinit var enforcer: Mesh
    lateinit var motorcycle: Mesh
    lateinit var helicopter: Mesh
    lateinit var rotor: Mesh
    lateinit var gunboat: Mesh
    lateinit var civilianCar: Mesh
    lateinit var civilianTruck: Mesh
    lateinit var weaponsVan: Mesh
    lateinit var missile: Mesh
    lateinit var pylon: Mesh
    lateinit var tree: Mesh
    lateinit var building: Mesh
    lateinit var buoy: Mesh

    /**
     * Builds every mesh. Deliberately NOT guarded by [built]: GLSurfaceView only
     * re-enters onSurfaceCreated when the EGL context has actually been
     * recreated (`preserveEGLContextOnPause` is best-effort and the glasses do
     * lose the context across sleep), so every VAO and VBO below is already dead
     * by the time we get here. Early-returning would leave the vehicle meshes
     * pointing at handles from a context that no longer exists and they would
     * simply stop drawing on resume.
     *
     * The stale meshes are not released first either — in the new context those
     * same integer names may already have been handed to somebody else's
     * buffers, and deleting them would take out live geometry.
     */
    fun build() {
        playerCar = buildPlayerCar()
        playerBoat = buildPlayerBoat()
        switchblade = buildSwitchblade()
        roadLord = buildRoadLord()
        enforcer = buildEnforcer()
        motorcycle = buildMotorcycle()
        helicopter = buildHelicopter()
        rotor = buildRotor()
        gunboat = buildGunboat()
        civilianCar = buildCivilianCar()
        civilianTruck = buildCivilianTruck()
        weaponsVan = buildWeaponsVan()
        missile = buildMissile()
        pylon = buildPylon()
        tree = buildTree()
        building = buildBuilding()
        buoy = buildBuoy()
        built = true
    }

    fun release() {
        if (!built) return
        playerCar.release(); playerBoat.release(); switchblade.release()
        roadLord.release(); enforcer.release(); motorcycle.release()
        helicopter.release(); rotor.release(); gunboat.release()
        civilianCar.release(); civilianTruck.release(); weaponsVan.release()
        missile.release(); pylon.release(); tree.release()
        building.release(); buoy.release()
        built = false
    }

    // =====================================================================
    //  cross-sections — x in [-1,1], y in [0,1], scaled per station by the loft
    // =====================================================================

    /** Car body: flat floor, sills kicked out at the waist, tapered roof. */
    private val SEC_WEDGE = floatArrayOf(
        -0.74f, 0.00f, 0.74f, 0.00f, 1.00f, 0.34f,
        0.66f, 1.00f, -0.66f, 1.00f, -1.00f, 0.34f
    )

    /** Truck/van: near-rectangular with small chamfers so the edges still catch light. */
    private val SEC_BOXY = floatArrayOf(
        -0.86f, 0.00f, 0.86f, 0.00f, 1.00f, 0.12f,
        1.00f, 0.88f, 0.86f, 1.00f, -0.86f, 1.00f,
        -1.00f, 0.88f, -1.00f, 0.12f
    )

    /** Deep-V planing hull: keel at y=0, hard chine, flat deck. */
    private val SEC_HULL_V = floatArrayOf(
        0.00f, 0.00f, 0.70f, 0.30f, 1.00f, 0.62f,
        0.60f, 1.00f, -0.60f, 1.00f, -1.00f, 0.62f,
        -0.70f, 0.30f
    )

    /** Rounded fuselage pod, widest at the waist. */
    private val SEC_POD = floatArrayOf(
        -0.55f, 0.00f, 0.55f, 0.00f, 1.00f, 0.32f,
        0.86f, 0.82f, 0.00f, 1.00f, -0.86f, 0.82f,
        -1.00f, 0.32f
    )

    /** Flat blade/fin section — a rectangle, thickness comes from the yScale. */
    private val SEC_BLADE = floatArrayOf(-1f, 0f, 1f, 0f, 1f, 1f, -1f, 1f)

    // =====================================================================
    //  player
    // =====================================================================

    /** Sleek interceptor: 176 tris. Cyan body, magenta sill strips, twin thrusters. */
    private fun buildPlayerCar(): Mesh {
        val k = VehicleKind.PLAYER
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(400, 1200)

        val br = 0.10f; val bg = 0.80f; val bb = 1.10f
        mb.loft(
            SEC_WEDGE,
            floatArrayOf(
                -hl, 0.78f * hw, 0.44f, 0.09f,
                -0.54f * hl, 1.00f * hw, 0.56f, 0.05f,
                0.09f * hl, 1.00f * hw, 0.52f, 0.04f,
                0.63f * hl, 0.70f * hw, 0.34f, 0.04f,
                hl, 0.20f * hw, 0.14f, 0.05f
            ),
            br, bg, bb, 0.30f
        )

        // canopy — brighter than the body so the "cockpit" end of the wedge reads
        mb.taperedBox(
            zBack = -0.75f, zFront = 0.55f, hwBack = 0.44f, hwFront = 0.30f,
            yLoBack = 0.42f, yHiBack = 0.86f, yLoFront = 0.40f, yHiFront = 0.66f,
            cx = 0f, r = 0.50f, g = 1.80f, b = 2.40f, glow = 0.50f
        )

        // rear fins: vertical mass at the tail, which is what tells the eye
        // instantly which way the car is pointing when it slides sideways
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.taperedBox(
                zBack = -hl, zFront = -0.90f, hwBack = 0.07f, hwFront = 0.05f,
                yLoBack = 0.30f, yHiBack = 0.80f, yLoFront = 0.30f, yHiFront = 0.50f,
                cx = sx * 0.70f, r = 0.40f, g = 1.60f, b = 2.20f, glow = 0.55f
            )
        }

        // magenta underglow strips — the streaks the bloom pass smears into the road
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * 0.88f, 0.045f, 0f, 0.055f, 0.030f, hl * 0.76f, 3.2f, 0.25f, 4.6f, 1f)
        }
        // tail bar + thrusters
        mb.box(0f, 0.30f, -hl + 0.02f, 0.60f, 0.06f, 0.04f, 2.5f, 5.0f, 6.0f, 1f)
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * 0.40f, 0.30f, -hl + 0.06f, 0.15f, 0.10f, 0.07f, 1.5f, 4.0f, 6.0f, 1f)
            mb.box(sx * 0.42f, 0.20f, hl - 0.28f, 0.13f, 0.05f, 0.06f, 3.0f, 4.5f, 5.0f, 1f)
        }
        return mb.build()
    }

    /** Boat form for water sections: 124 tris. Same footprint, deep-V hull, smooth-shaded. */
    private fun buildPlayerBoat(): Mesh {
        val k = VehicleKind.PLAYER
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(400, 1200)

        mb.loft(
            SEC_HULL_V,
            floatArrayOf(
                -hl, 0.95f * hw, 0.60f, 0.00f,
                -0.26f * hl, 1.00f * hw, 0.60f, 0.04f,
                0.52f * hl, 0.78f * hw, 0.54f, 0.12f,
                hl, 0.16f * hw, 0.34f, 0.22f
            ),
            0.10f, 0.80f, 1.10f, 0.30f, smooth = true
        )

        mb.taperedBox(
            zBack = -1.10f, zFront = 0.30f, hwBack = 0.46f, hwFront = 0.34f,
            yLoBack = 0.55f, yHiBack = 0.94f, yLoFront = 0.55f, yHiFront = 0.80f,
            cx = 0f, r = 0.50f, g = 1.80f, b = 2.40f, glow = 0.50f
        )

        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            // waterline strip doubles as the spray rail
            mb.box(sx * (hw - 0.04f), 0.24f, -0.20f, 0.05f, 0.035f, hl * 0.80f, 3.2f, 0.25f, 4.6f, 1f)
            mb.box(sx * 0.45f, 0.28f, -hl - 0.02f, 0.16f, 0.10f, 0.08f, 2.0f, 4.5f, 6.0f, 1f)
        }
        mb.box(0f, 0.60f, hl - 0.30f, 0.10f, 0.06f, 0.10f, 2.5f, 5.0f, 6.0f, 1f)
        return mb.build()
    }

    // =====================================================================
    //  hostiles
    // =====================================================================

    /** Switchblade: 128 tris. Amber, with the wheel-blades extended — its whole tell. */
    private fun buildSwitchblade(): Mesh {
        val k = VehicleKind.SWITCHBLADE
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(360, 1100)

        mb.loft(
            SEC_WEDGE,
            floatArrayOf(
                -hl, 0.94f * hw, 0.50f, 0.06f,
                -0.41f * hl, 1.00f * hw, 0.58f, 0.04f,
                0.27f * hl, 0.92f * hw, 0.46f, 0.04f,
                hl, 0.32f * hw, 0.20f, 0.05f
            ),
            1.30f, 0.52f, 0.04f, 0.30f
        )
        mb.taperedBox(
            zBack = -1.00f, zFront = 0.00f, hwBack = 0.40f, hwFront = 0.30f,
            yLoBack = 0.44f, yHiBack = 0.80f, yLoFront = 0.42f, yHiFront = 0.62f,
            cx = 0f, r = 1.20f, g = 0.90f, b = 0.20f, glow = 0.45f
        )

        // The blades: flat spikes off each hub, hot enough to smear in bloom.
        // They are the one place geometry knowingly overhangs the collision box
        // — a weapon that stops at the bodywork would not read as a threat.
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            for (zi in -1 until 2 step 2) {
                blade(
                    mb, sx, zi * 1.15f, hw - 0.06f, hw + 0.34f,
                    0.16f, 0.24f, 0.34f, 0.09f, 6.0f, 3.0f, 0.6f
                )
            }
            mb.box(sx * 0.55f, 0.34f, -hl - 0.02f, 0.20f, 0.07f, 0.05f, 6.0f, 1.6f, 0.15f, 1f)
        }
        return mb.build()
    }

    /** Road Lord: 144 tris. Long armoured limo, deep red — length alone identifies it. */
    private fun buildRoadLord(): Mesh {
        val k = VehicleKind.ROAD_LORD
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(400, 1200)

        mb.loft(
            SEC_WEDGE,
            floatArrayOf(
                -hl, 0.88f * hw, 0.62f, 0.06f,
                -0.65f * hl, 1.00f * hw, 0.74f, 0.05f,
                0.13f * hl, 1.00f * hw, 0.74f, 0.05f,
                0.68f * hl, 0.96f * hw, 0.62f, 0.05f,
                hl, 0.70f * hw, 0.44f, 0.06f
            ),
            1.30f, 0.05f, 0.12f, 0.28f
        )
        // long low greenhouse — a limo is a slab with a small cabin far back
        mb.taperedBox(
            zBack = -1.90f, zFront = 0.90f, hwBack = 0.72f, hwFront = 0.62f,
            yLoBack = 0.78f, yHiBack = 1.16f, yLoFront = 0.76f, yHiFront = 1.02f,
            cx = 0f, r = 0.90f, g = 0.10f, b = 0.16f, glow = 0.40f
        )
        // ram bar
        mb.box(0f, 0.42f, hl - 0.04f, hw * 0.92f, 0.10f, 0.09f, 1.4f, 0.9f, 0.9f, 0.55f)

        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.neonEdge(sx * (hw - 0.01f), 0.30f, -2.55f, sx * (hw - 0.01f), 0.30f, 2.30f, 0.045f, 4.0f, 0.4f, 0.6f)
            mb.box(sx * 0.72f, 0.55f, -hl - 0.02f, 0.24f, 0.08f, 0.05f, 6.0f, 0.5f, 0.6f, 1f)
            mb.box(sx * 0.78f, 0.40f, hl - 0.06f, 0.17f, 0.06f, 0.05f, 5.0f, 2.2f, 0.6f, 1f)
        }
        return mb.build()
    }

    /** Enforcer: 160 tris. Tall, wide, flat-topped slab — the "you cannot shoot this" shape. */
    private fun buildEnforcer(): Mesh {
        val k = VehicleKind.ENFORCER
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(420, 1300)

        mb.loft(
            SEC_BOXY,
            floatArrayOf(
                -hl, 0.94f * hw, 1.40f, 0.16f,
                -0.50f * hl, 1.00f * hw, 1.58f, 0.14f,
                0.50f * hl, 1.00f * hw, 1.45f, 0.14f,
                hl, 0.78f * hw, 0.85f, 0.16f
            ),
            0.85f, 0.95f, 1.15f, 0.22f
        )
        // ram plate across the whole nose
        mb.box(0f, 0.55f, hl - 0.10f, hw * 0.98f, 0.42f, 0.10f, 1.6f, 1.8f, 2.2f, 0.60f)
        // Roof hoops read as a roll cage from any angle. They must clear the
        // roofline, which the loft puts at yOffset + yScale = 1.72 at z=-1.4
        // falling to ~1.63 by z=0.6; a neonEdge prism also hangs `thick` below
        // its own axis, so the axis sits roof + thick + a hair.
        mb.neonEdge(-hw * 0.86f, 1.80f, -1.10f, hw * 0.86f, 1.80f, -1.10f, 0.06f, 2.6f, 3.2f, 4.4f)
        mb.neonEdge(-hw * 0.86f, 1.70f, 0.60f, hw * 0.86f, 1.70f, 0.60f, 0.06f, 2.6f, 3.2f, 4.4f)

        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * (hw - 0.02f), 0.95f, -0.10f, 0.04f, 0.10f, hl * 0.80f, 2.6f, 3.2f, 4.4f, 1f)
            mb.box(sx * 0.80f, 1.10f, -hl - 0.02f, 0.22f, 0.09f, 0.05f, 4.5f, 1.6f, 0.20f, 1f)
            mb.box(sx * 0.85f, 0.75f, hl - 0.02f, 0.19f, 0.08f, 0.05f, 4.0f, 4.5f, 5.0f, 1f)
        }
        return mb.build()
    }

    /** Motorcycle: 108 tris. Thin, tall, two hard wheel discs — nothing else looks like it. */
    private fun buildMotorcycle(): Mesh {
        val k = VehicleKind.MOTORCYCLE
        val hl = k.halfLength
        val mb = MeshBuilder(300, 900)

        mb.taperedBox(
            zBack = -hl + 0.05f, zFront = hl - 0.15f, hwBack = 0.20f, hwFront = 0.13f,
            yLoBack = 0.40f, yHiBack = 0.76f, yLoFront = 0.44f, yHiFront = 0.64f,
            cx = 0f, r = 1.00f, g = 0.15f, b = 1.30f, glow = 0.35f
        )
        // wheels: 6 segments is plenty — faceted rims suit the vector look
        mb.wheelX(0f, 0.34f, 0.72f, 0.34f, 0.07f, 6, 0.45f, 0.50f, 0.75f, 0.20f)
        mb.wheelX(0f, 0.34f, -0.72f, 0.34f, 0.07f, 6, 0.45f, 0.50f, 0.75f, 0.20f)
        // rider silhouette — the hunched mass is what sells the scale
        mb.taperedBox(
            zBack = -0.35f, zFront = 0.30f, hwBack = 0.22f, hwFront = 0.17f,
            yLoBack = 0.72f, yHiBack = 1.30f, yLoFront = 0.70f, yHiFront = 1.12f,
            cx = 0f, r = 0.70f, g = 0.10f, b = 0.95f, glow = 0.30f
        )
        mb.box(0f, 1.42f, -0.06f, 0.13f, 0.13f, 0.15f, 0.70f, 0.10f, 0.95f, 0.30f)
        mb.box(0f, 0.70f, hl - 0.08f, 0.10f, 0.07f, 0.05f, 5.0f, 5.0f, 6.0f, 1f)
        mb.box(0f, 0.62f, -hl + 0.04f, 0.12f, 0.06f, 0.04f, 6.0f, 0.6f, 1.5f, 1f)
        return mb.build()
    }

    /** Helicopter body: 188 tris. Fat cabin, thin tail boom, skids. Rotor is separate. */
    private fun buildHelicopter(): Mesh {
        val mb = MeshBuilder(460, 1400)
        val hl = VehicleKind.HELICOPTER.halfLength
        val hw = 0.85f          // cabin half-width; the VehicleKind width is the rotor span

        mb.loft(
            SEC_POD,
            floatArrayOf(
                -hl, 0.16f * hw, 0.30f, 0.95f,
                -0.37f * hl, 0.34f * hw, 0.50f, 0.78f,
                0.10f * hl, 1.00f * hw, 1.20f, 0.30f,
                hl, 0.40f * hw, 0.66f, 0.45f
            ),
            0.55f, 0.70f, 0.95f, 0.25f
        )
        // canopy
        mb.taperedBox(
            zBack = 0.60f, zFront = hl - 0.35f, hwBack = 0.72f, hwFront = 0.34f,
            yLoBack = 0.62f, yHiBack = 1.30f, yLoFront = 0.55f, yHiFront = 0.95f,
            cx = 0f, r = 0.80f, g = 1.80f, b = 2.40f, glow = 0.50f
        )
        // tail fin and stabiliser
        mb.taperedBox(
            zBack = -hl, zFront = -0.77f * hl, hwBack = 0.07f, hwFront = 0.06f,
            yLoBack = 0.95f, yHiBack = 1.90f, yLoFront = 0.95f, yHiFront = 1.45f,
            cx = 0f, r = 0.55f, g = 0.70f, b = 0.95f, glow = 0.25f
        )
        mb.box(0f, 1.05f, -2.55f, 0.60f, 0.05f, 0.18f, 0.55f, 0.70f, 0.95f, 0.25f)
        // mast, reaching up to where the separate rotor mesh is placed
        mb.box(0f, HELI_ROTOR_Y * 0.5f + 0.72f, 0f, 0.10f, HELI_ROTOR_Y * 0.5f - 0.72f, 0.10f, 1.0f, 1.2f, 1.6f, 0.40f)
        // tail-rotor disc, always drawn as blur — cheaper and truer than blades
        mb.push(); mb.translate(0.16f, 1.45f, -2.92f); mb.rotateZ(90f)
        mb.ring(0f, 0f, 0f, 0.28f, 0.52f, 6, 0.30f, 0.80f, 1.20f, 1f)
        mb.pop()

        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * 0.72f, 0.06f, 0.10f, 0.05f, 0.05f, 1.35f, 0.55f, 0.70f, 0.95f, 0.25f)
            mb.neonEdge(sx * 0.72f, 0.10f, 0.85f, sx * 0.26f, 0.34f, 0.60f, 0.05f, 1.0f, 1.4f, 2.0f, 0.8f)
        }
        // port red / starboard green — instant read on which way it is turning
        mb.box(-0.86f, 0.88f, 0.80f, 0.07f, 0.07f, 0.07f, 6.0f, 0.30f, 0.30f, 1f)
        mb.box(0.86f, 0.88f, 0.80f, 0.07f, 0.07f, 0.07f, 0.30f, 6.00f, 0.40f, 1f)
        return mb.build()
    }

    /**
     * Main rotor: 132 tris. Modelled around its own origin — the renderer
     * translates it to [HELI_ROTOR_Y] and spins it about Y, so the disc has to
     * be centred on the axis or it would wobble instead of turning. Blades are
     * smooth-shaded; every other surface in the game is faceted.
     */
    private fun buildRotor(): Mesh {
        val mb = MeshBuilder(300, 900)
        val r = HELI_ROTOR_RADIUS
        for (i in 0 until 4) {
            mb.push()
            mb.rotateY(i * 90f)
            mb.loft(
                SEC_BLADE,
                floatArrayOf(
                    0.30f, 0.16f, 0.05f, -0.025f,
                    0.40f * r, 0.20f, 0.05f, -0.025f,
                    r, 0.13f, 0.04f, -0.020f
                ),
                0.70f, 0.85f, 1.10f, 0.30f, smooth = true
            )
            mb.pop()
        }
        mb.lathe(
            floatArrayOf(0.16f, -0.10f, 0.20f, 0.06f), 6,
            0f, 0f, 0f, 1.0f, 1.2f, 1.6f, 0.40f
        )
        // faint disc: at spin speed this is what the eye actually sees
        mb.ring(0f, 0f, 0f, r * 0.42f, r * 0.98f, 8, 0.35f, 0.90f, 1.30f, 1f)
        return mb.build()
    }

    /** Gunboat: 148 tris. Deep-V hull like the player boat, but bristling and red-lit. */
    private fun buildGunboat(): Mesh {
        val k = VehicleKind.GUNBOAT
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(420, 1300)

        mb.loft(
            SEC_HULL_V,
            floatArrayOf(
                -hl, 0.95f * hw, 0.72f, 0.00f,
                -0.37f * hl, 1.00f * hw, 0.72f, 0.04f,
                0.40f * hl, 0.82f * hw, 0.66f, 0.10f,
                hl, 0.18f * hw, 0.40f, 0.22f
            ),
            0.30f, 0.55f, 0.95f, 0.25f, smooth = true
        )
        mb.taperedBox(
            zBack = -1.60f, zFront = 0.20f, hwBack = 0.62f, hwFront = 0.50f,
            yLoBack = 0.72f, yHiBack = 1.42f, yLoFront = 0.70f, yHiFront = 1.20f,
            cx = 0f, r = 0.35f, g = 0.60f, b = 1.00f, glow = 0.35f
        )
        // foredeck gun — the one feature that separates it from the player's boat
        mb.box(0f, 1.05f, 1.30f, 0.30f, 0.22f, 0.35f, 0.45f, 0.60f, 0.90f, 0.30f)
        mb.box(0f, 1.10f, 1.95f, 0.06f, 0.06f, 0.45f, 0.60f, 0.70f, 0.95f, 0.35f)
        mb.box(0f, 1.10f, 2.42f, 0.09f, 0.09f, 0.09f, 6.0f, 2.0f, 0.4f, 1f)

        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * (hw - 0.04f), 0.32f, -0.40f, 0.05f, 0.05f, hl * 0.72f, 4.0f, 1.2f, 0.2f, 1f)
            mb.box(sx * 0.50f, 0.30f, -hl + 0.03f, 0.20f, 0.10f, 0.06f, 1.5f, 3.5f, 5.5f, 1f)
        }
        return mb.build()
    }

    // =====================================================================
    //  neutrals — quiet on purpose: no component over 1.0, so no bloom at all
    // =====================================================================

    /** Civilian car: 80 tris. Upright, dull teal, tall greenhouse. Reads as "not a threat". */
    private fun buildCivilianCar(): Mesh {
        val k = VehicleKind.CIVILIAN_CAR
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(240, 700)

        mb.loft(
            SEC_WEDGE,
            floatArrayOf(
                -hl, 0.90f * hw, 0.66f, 0.10f,
                -0.56f * hl, 1.00f * hw, 0.72f, 0.08f,
                0.51f * hl, 1.00f * hw, 0.70f, 0.08f,
                hl, 0.86f * hw, 0.58f, 0.10f
            ),
            0.22f, 0.52f, 0.42f, 0.12f
        )
        // deliberately boxy and tall — the opposite proportion to every hostile
        mb.taperedBox(
            zBack = -0.95f, zFront = 0.75f, hwBack = 0.80f, hwFront = 0.70f,
            yLoBack = 0.78f, yHiBack = 1.24f, yLoFront = 0.76f, yHiFront = 1.10f,
            cx = 0f, r = 0.18f, g = 0.38f, b = 0.46f, glow = 0.14f
        )
        // tail lights stay under 1.0 so they never make it into the bright pass:
        // a civilian must not produce the bloom streak that says "target"
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * 0.66f, 0.62f, -hl - 0.01f, 0.15f, 0.07f, 0.04f, 0.90f, 0.12f, 0.10f, 0.50f)
        }
        return mb.build()
    }

    /** Civilian truck: 80 tris. Just a cab and a long box — no lights worth shooting at. */
    private fun buildCivilianTruck(): Mesh {
        val k = VehicleKind.CIVILIAN_TRUCK
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(240, 700)

        mb.chamferedBox(0f, 1.45f, -0.90f, hw, 1.30f, hl - 0.95f, 0.22f, 0.38f, 0.44f, 0.52f, 0.12f)
        mb.chamferedBox(0f, 1.05f, hl - 1.10f, hw * 0.96f, 0.95f, 1.10f, 0.22f, 0.28f, 0.38f, 0.52f, 0.10f)
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.box(sx * 0.90f, 0.55f, -hl - 0.02f, 0.13f, 0.07f, 0.05f, 0.85f, 0.28f, 0.07f, 0.45f)
        }
        return mb.build()
    }

    /**
     * Weapons van: 122 tris. Unmistakable — big bright green box with a lit hole
     * in the back and a ramp on the ground, so "drive into the rear" is obvious.
     */
    private fun buildWeaponsVan(): Mesh {
        val k = VehicleKind.WEAPONS_VAN
        val hw = k.halfWidth
        val hl = k.halfLength
        val mb = MeshBuilder(360, 1100)

        mb.loft(
            SEC_BOXY,
            floatArrayOf(
                -hl, 1.00f * hw, 1.75f, 0.22f,
                0.55f * hl, 1.00f * hw, 1.75f, 0.22f,
                hl, 0.82f * hw, 1.05f, 0.24f
            ),
            0.20f, 1.80f, 0.55f, 0.55f, capBack = false
        )
        // inward-facing shell: seen only through the open tail, so the opening
        // reads as a lit cargo bay instead of a hole straight through the model
        mb.boxInside(0f, 1.15f, -0.95f, hw * 0.72f, 0.60f, hl * 0.74f, 0.05f, 0.90f, 0.30f, 0.85f)
        // frame around the opening
        val zr = -hl + 0.03f
        val fx = hw * 0.94f
        mb.neonEdge(-fx, 0.26f, zr, fx, 0.26f, zr, 0.06f, 0.4f, 5.0f, 1.2f)
        mb.neonEdge(-fx, 1.94f, zr, fx, 1.94f, zr, 0.06f, 0.4f, 5.0f, 1.2f)
        mb.neonEdge(-fx, 0.26f, zr, -fx, 1.94f, zr, 0.06f, 0.4f, 5.0f, 1.2f)
        mb.neonEdge(fx, 0.26f, zr, fx, 1.94f, zr, 0.06f, 0.4f, 5.0f, 1.2f)
        // ramp down to the road
        val ramp = floatArrayOf(
            -0.85f, 0.28f, -hl, 0.85f, 0.28f, -hl, 0.85f, 0.02f, -hl - 1.30f, -0.85f, 0.02f, -hl - 1.30f,
            -0.85f, 0.34f, -hl, 0.85f, 0.34f, -hl, 0.85f, 0.08f, -hl - 1.30f, -0.85f, 0.08f, -hl - 1.30f
        )
        mb.hull8(ramp, 0.4f, 4.0f, 1.0f, 0.9f)
        mb.box(0f, 2.02f, 0.60f, 0.70f, 0.06f, 0.30f, 0.4f, 6.0f, 1.2f, 1f)
        for (s in -1 until 2 step 2) {
            val sx = s.toFloat()
            mb.neonEdge(sx * (hw + 0.01f), 0.40f, -1.20f, sx * (hw + 0.01f), 1.70f, 1.40f, 0.05f, 0.3f, 4.0f, 0.9f)
        }
        return mb.build()
    }

    // =====================================================================
    //  projectile and scenery
    // =====================================================================

    /** Missile: 82 tris. Centred on the origin (it flies, it does not sit on the ground). */
    private fun buildMissile(): Mesh {
        val mb = MeshBuilder(200, 600)
        // lathed along Y, then swung to +Z so the model matches travel direction
        mb.push(); mb.rotateX(90f)
        mb.lathe(
            floatArrayOf(0.06f, -0.70f, 0.11f, -0.52f, 0.11f, 0.34f, 0.00f, 0.70f), 6,
            0f, 0f, 0f, 1.6f, 1.8f, 2.4f, 0.50f
        )
        mb.pop()
        // two crossed fins keep the silhouette alive from every roll angle
        mb.taperedBox(
            zBack = -0.70f, zFront = -0.34f, hwBack = 0.28f, hwFront = 0.11f,
            yLoBack = -0.015f, yHiBack = 0.015f, yLoFront = -0.015f, yHiFront = 0.015f,
            cx = 0f, r = 5.0f, g = 2.0f, b = 0.30f, glow = 1f
        )
        mb.push(); mb.rotateZ(90f)
        mb.taperedBox(
            zBack = -0.70f, zFront = -0.34f, hwBack = 0.28f, hwFront = 0.11f,
            yLoBack = -0.015f, yHiBack = 0.015f, yLoFront = -0.015f, yHiFront = 0.015f,
            cx = 0f, r = 5.0f, g = 2.0f, b = 0.30f, glow = 1f
        )
        mb.pop()
        mb.box(0f, 0f, -0.76f, 0.09f, 0.09f, 0.06f, 6.0f, 3.5f, 1.2f, 1f)
        mb.box(0f, 0f, 0.62f, 0.05f, 0.05f, 0.08f, 6.0f, 3.0f, 0.5f, 1f)
        return mb.build()
    }

    /**
     * Roadside light pylon: 64 tris. Base at y=0. The arm reaches along +Z
     * because RoadRenderer yaws scenery by +90 on the left verge and -90 on the
     * right — both of which swing model +Z inward, over the road.
     */
    private fun buildPylon(): Mesh {
        val mb = MeshBuilder(200, 600)
        mb.box(0f, 3.20f, 0f, 0.10f, 3.20f, 0.10f, 0.60f, 0.70f, 0.90f, 0.25f)
        mb.box(0f, 0.10f, 0f, 0.28f, 0.10f, 0.28f, 0.50f, 0.60f, 0.80f, 0.25f)
        mb.box(0f, 6.25f, 0.80f, 0.07f, 0.07f, 0.80f, 0.60f, 0.70f, 0.90f, 0.30f)
        mb.box(0f, 6.10f, 1.55f, 0.30f, 0.10f, 0.22f, 6.0f, 3.2f, 0.8f, 1f)
        mb.neonEdge(0f, 0.50f, -0.11f, 0f, 6.10f, -0.11f, 0.04f, 0.5f, 3.0f, 4.2f)
        mb.neonEdge(0f, 5.70f, 0.15f, 0f, 6.18f, 0.75f, 0.04f, 0.5f, 3.0f, 4.2f)
        return mb.build()
    }

    /** Stylised conifer: 54 tris. Three cone tiers with a lit tip. */
    private fun buildTree(): Mesh {
        val mb = MeshBuilder(200, 600)
        mb.box(0f, 0.55f, 0f, 0.13f, 0.55f, 0.13f, 0.35f, 0.22f, 0.10f, 0.10f)
        mb.lathe(floatArrayOf(1.15f, 0f, 0f, 1.70f), 6, 0f, 0.95f, 0f, 0.10f, 0.70f, 0.42f, 0.22f)
        mb.lathe(floatArrayOf(0.85f, 0f, 0f, 1.50f), 6, 0f, 1.95f, 0f, 0.10f, 0.70f, 0.42f, 0.22f)
        mb.lathe(floatArrayOf(0.55f, 0f, 0f, 1.30f), 6, 0f, 2.90f, 0f, 0.10f, 0.70f, 0.42f, 0.22f)
        mb.box(0f, 4.28f, 0f, 0.06f, 0.10f, 0.06f, 0.6f, 4.0f, 2.0f, 1f)
        return mb.build()
    }

    /**
     * Building: 108 tris. Outline only — a filled block would either be an
     * invisible black hole or a murky haze on an additive display, so the edges
     * carry the whole form. Base at y=0.
     */
    private fun buildBuilding(): Mesh {
        val mb = MeshBuilder(400, 1200)
        val hx = 3.6f
        val hz = 3.6f
        val h = 11.0f
        mb.edgeFrame(
            0f, h * 0.5f, 0f, hx, h * 0.5f, hz, 0.10f,
            0.40f, 2.60f, 4.00f, 3.00f, 0.40f, 4.50f
        )
        // a few lit windows, flat on the two faces the player usually sees
        for (i in 0 until 3) {
            val y = 2.2f + i * 3.0f
            mb.quadFace(
                -1.4f, y, hz + 0.02f, 1.4f, y, hz + 0.02f,
                1.4f, y + 0.9f, hz + 0.02f, -1.4f, y + 0.9f, hz + 0.02f,
                0.15f, 1.10f, 1.60f, 0.85f, 0f, 0f, 1f
            )
            mb.quadFace(
                hx + 0.02f, y, -1.4f, hx + 0.02f, y, 1.4f,
                hx + 0.02f, y + 0.9f, 1.4f, hx + 0.02f, y + 0.9f, -1.4f,
                0.15f, 1.10f, 1.60f, 0.85f, 1f, 0f, 0f
            )
        }
        return mb.build()
    }

    /** Water-section marker buoy: 86 tris. Base at y=0, light on top. */
    private fun buildBuoy(): Mesh {
        val mb = MeshBuilder(200, 600)
        mb.lathe(
            floatArrayOf(0.00f, -0.55f, 0.42f, -0.35f, 0.42f, 0.15f, 0.16f, 0.55f), 8,
            0f, 0.60f, 0f, 2.0f, 0.6f, 0.1f, 0.50f
        )
        mb.ring(0f, 0.35f, 0f, 0.42f, 0.60f, 8, 0.30f, 2.20f, 3.00f, 1f, doubleSided = false)
        mb.box(0f, 1.22f, 0f, 0.04f, 0.12f, 0.04f, 1.0f, 0.8f, 0.6f, 0.40f)
        mb.box(0f, 1.42f, 0f, 0.10f, 0.14f, 0.10f, 6.0f, 2.0f, 0.4f, 1f)
        return mb.build()
    }

    // =====================================================================
    //  shared shape helpers
    // =====================================================================

    /** Flat spike jutting sideways from a wheel hub. */
    private fun blade(
        mb: MeshBuilder, sign: Float, zc: Float,
        xIn: Float, xOut: Float, y0: Float, y1: Float,
        rootHalf: Float, tipHalf: Float,
        r: Float, g: Float, b: Float
    ) {
        val xi = sign * xIn
        val xo = sign * xOut
        val c = floatArrayOf(
            xi, y0, zc - rootHalf, xo, y0, zc - tipHalf, xo, y0, zc + tipHalf, xi, y0, zc + rootHalf,
            xi, y1, zc - rootHalf, xo, y1, zc - tipHalf, xo, y1, zc + tipHalf, xi, y1, zc + rootHalf
        )
        mb.hull8(c, r, g, b, 1f)
    }
}
