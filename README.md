# Spy Hunt XR

A recreation of the 1983 arcade driving-and-shooting game for the **RayNeo X3 Pro**
AR glasses (`ARGF20` / `MercuryLiteXR`), rendered in stereo with a modern
emissive-neon look and fully procedural audio.

Personal homage / fan recreation. "Spy Hunter" is a trademark of its respective
owner; none of its assets, code or music are used here. The soundtrack is
original — see [Audio](#audio).

---

## The device, and why the design looks like this

Everything about this build follows from four measured facts about the hardware:

| Measured | Consequence |
|---|---|
| Framebuffer is **1280×480** — a side-by-side stereo pair, **640×480 per eye** | The app renders both eyes itself; there is no compositor doing the duplication. Each eye gets its own viewport and its own post chain. |
| The waveguide is **additive** — **black is transparent** | There is no "dark grey". The art direction is emissive neon on pure black, the road surface is left unpainted so the real floor shows through, and the frame edge is faded to black so the app dissolves into the room instead of hanging there as a rectangle. |
| Optics are ~**50° diagonal** (~40.5° H / ~31.2° V per eye) | The projection matches the true optical FOV, which is what stops the board sliding around as you move. |
| **Adreno 621**, 4 cores, 60 Hz | 614k pixels total. Comfortable for HDR + two bloom octaves per eye, so the budget went into light rather than polygons. |

### The scale trick

An overhead racing camera naturally sits ~60 m from the action. Stereo disparity
falls off as 1/distance, so at 60 m a 63 mm IPD gives about **0.06° of
disparity — invisible**. Rendered at true world scale this would be technically
stereo and perceptually flat.

So the entire scene, camera included, is uniformly scaled in eye space. Uniform
scaling leaves every projected angle identical — the framing is untouched — but
pulls the content to **~3.4 m**, where disparity is **~1.06°** and reads clearly.
The game becomes a hologram diorama floating in the room, which is a far more
natural thing for AR glasses to show than a simulated flat screen.

`StereoRig.playerViewDistance()` and `playerDisparityDeg()` print these at
startup so the numbers can be checked rather than trusted.

Projection is **off-axis** (parallel-axis asymmetric frustum), not toed-in —
toe-in introduces vertical disparity, which is a reliable way to give people
eye strain.

---

## Modes

The start screen offers two presentations. **They differ only in how the game is
drawn** — simulation, weapons, physics, scoring and difficulty are identical, and
nothing under `game/` ever branches on the style.

| | |
|---|---|
| **Neon Pursuit** | HDR emissive neon, bloom, light trails. Built around the additive waveguide. |
| **Vintage 1983** | Homage to the original arcade cabinet: flat sprite-era shading, hard limited palette, chunky pixels, scanlines, grey tarmac with white dashes and a green verge — but the **modern weapon set** (oil slick, smoke screen, homing missiles), not the original's. |

Swipe the arm to change the highlight, tap to start. The attract demo behind the
menu renders in the highlighted style, so you preview the look before committing.

At game over, **tap** replays the same mode and a **long press** returns to the
start screen — the only place the mode can be changed.

Vintage's green verge is deliberately muted rather than the cabinet's saturated
green. A large bright fill on an additive display is a glowing rectangle hanging
in the room, so the verge is held well under the road's brightness and fades to
black (transparent) at its outer edge.

---

## Controls

The right temple touchpad drives everything.

| Gesture | Action |
|---|---|
| Swipe **along** the arm | Steer. Forward (toward the lens) = right, back (toward the ear) = left. Relative and spring-centred, so a flick changes lane and a slow drag trims. |
| Swipe **across** the arm | Throttle / brake |
| **Tap** | Fire machine guns |
| **Hold** after a tap | Continuous fire |
| **Double tap** | Deploy the current special weapon |
| **Long press** | Start / pause |
| Volume up / down | Recenter the board / menu |

### First-run calibration

The glasses expose **two** electrically identical touchpads (`cyttsp5_mt` on
`/dev/input/event2` and `cyttsp6_mt` on `/dev/input/event4`), and nothing in the
device names says which arm is which. Rather than guess, the first launch asks
you to **swipe your right arm**, latches whichever pad responds, and stores it in
`SharedPreferences` under `right_pad_device_name`. Events from the other pad are
then ignored. This also makes the game work for a left-handed setup for free.

To redo it: `TouchpadController.resetCalibration()`, or clear app data.

---

## Rendering

Per eye, into an `RGBA16F` target:

```
road ribbon → vehicles → scenery → light trails → particles → HUD
      ↓
bright pass (soft knee) → blur ½ → blur ¼ → blur ⅛ → ACES composite → screen half
```

- **Bloom is run per eye, never on the full 1280×480 buffer.** A shared blur
  would smear highlights across the seam at x=640, leaking the left eye's light
  into the right eye's image — which the visual system reads as a ghost and
  refuses to fuse.
- The simulation steps **once** per frame, before either eye is drawn, so both
  views show the same instant. Stepping physics between eyes produces a subtle
  shimmer that is miserable to track down later.
- Billboards use the **cyclopean** camera basis, shared by both eyes. Per-eye
  billboard orientation makes sprites disagree between eyes and breaks fusion.
- The road surface is drawn procedurally in the fragment shader — edge rails,
  dashed centre line, transverse scan lines, animated water — over a black
  (transparent) surface.

### Tuning knobs

| Where | What |
|---|---|
| `StereoRig.Config.ipd` | Interpupillary distance, default 63 mm |
| `StereoRig.Config.fovYDeg` | Per-eye vertical FOV — lower it if the board feels like it slides when you move |
| `StereoRig.Config.boardScale` | How large/close the hologram is. Bigger = closer and more depth |
| `StereoRig.Config.convergence` | Zero-parallax distance; content beyond it sits behind the screen plane |
| `StereoRig.Config.worldLock` | 0 = board welded to your face, 1 = fully room-locked. Default 0.22 |
| `PostChain.Tune.*` | Bloom threshold/knee/amount, exposure, gamma, edge fade |

If world-lock ever feels like it *amplifies* head motion rather than cancelling
it, the sign constants are at the top of `StereoRig` with the derivation written
out.

---

## Audio

**Every sound effect is synthesised at runtime** — engine, gunfire, explosions,
metal impacts, tyre screech, splashes, pickups. There are no SFX assets. A
low-latency `AudioTrack` (48 kHz, float, `PERFORMANCE_MODE_LOW_LATENCY`, buffer
aligned to the HAL burst size) is fed by a dedicated mixer thread that never
locks and never allocates; the game thread only publishes scalars and posts
triggers into a lock-free SPSC ring. The engine note tracks speed and effects are
panned by their world position.

**Music** is the supplied track at `app/src/main/assets/music_gunnrunner.mp3`,
streamed via `MediaPlayer` (`audio/MusicTrack.kt`) so the platform decodes it on
its offload path rather than spending frame budget on it. It ducks under loud
events through the same sidechain feel as the mixer. If that asset is ever
missing, `audio/Music.kt` — a complete original synthwave sequencer — takes over
automatically, so there is always a soundtrack.

> The arcade original used Henry Mancini's *Peter Gunn* theme, which is
> copyrighted. Nothing here reproduces it: the fallback sequencer is original
> work, and the shipped track is a file you supplied.

The glasses have small open-ear speakers with very little bass, so the mix is
voiced mid-forward with its weight around 150–400 Hz rather than in the sub, and
a master high-pass discards sub content the drivers cannot reproduce anyway.

---

## Build and run

```bash
cd ~/Projects/RayNeoSpyHunt && ./gradlew :app:installDebug
```

Launch it:

```bash
adb -s A06B4A96A733283 shell am start -n com.rayneo.spyhunt/.MainActivity
```

Grab a framebuffer capture (shows the side-by-side pair):

```bash
adb -s A06B4A96A733283 exec-out screencap -p > shot.png
```

Watch the frame-time and stereo diagnostics:

```bash
adb -s A06B4A96A733283 logcat -s SpyHunt
```

Toolchain: AGP 8.13.2, Kotlin 2.2.21, Gradle 8.14.3, JDK 17, `compileSdk` 36,
`minSdk` 29, `targetSdk` 32 (the glasses run Android 12 / API 32). No NDK, no
Compose, no third-party dependencies.

`tools/run.sh` builds with Gradle but installs with `adb -s` against the RayNeo
specifically. `:app:installDebug` fans out to *every* attached device, which
fails as soon as a phone is plugged in alongside the glasses.

---

## Testing without wearing them

Three device behaviours get in the way of driving this from a laptop:

**The glasses doze when they are off your head**, and the launcher's
`BackgroundAppManager` then force-stops whatever is running — the app dies about
two seconds after launch, looking exactly like a crash. The wear state lives in a
settings flag:

```bash
adb shell settings put global device_wearing 1
```

Set it back to `0` when you are done, or they will never sleep.

**The lockscreen sits on top** even once the device is awake. `adb shell wm
dismiss-keyguard` clears it.

**Synthetic input is rejected by design.** `TouchpadController` only accepts
events from the calibrated pad, and `adb shell input` events come from a
different device. `adb shell sendevent` cannot reach the real pad either — the
shell user is in the `input` group but SELinux denies the write. So there is a
deliberate, default-off test hatch:

```bash
adb shell settings put system spyhunt_any_input 1
```

With that set, the device filter is bypassed and `adb shell input tap` /
`input swipe` drive the game normally. Delete the key to restore normal
behaviour.

---

## Layout

```
core/     Mathx.kt      vectors, matrices, off-axis frustum, PRNG, value noise
          GLUtils.kt    Program, Mesh, DynamicMesh, Fbo — allocation-free hot paths
game/     GameState.kt  the shared contract every module compiles against
          World.kt      simulation step
          Enemies.kt    per-kind AI
          Weapons.kt    guns, oil, smoke, missiles
          RoadTopology.kt  curves, narrows, forks, water — a pure function of Z
          Spawner.kt    difficulty director
render/   StereoRig.kt  per-eye view/projection and the miniature-board transform
          Shaders.kt    all GLSL
          PostChain.kt  HDR + bloom + ACES composite, sized for one eye
          GameRenderer.kt  frame graph
          RoadRenderer.kt  road ribbon + roadside scenery
          Meshes.kt / MeshBuilder.kt  procedural low-poly vehicles
          Particles.kt / Trails.kt / VectorFont.kt / Hud.kt
audio/    AudioEngine.kt / Synth.kt / Music.kt
input/    TouchpadController.kt  calibration + gesture recognition
          HeadTracker.kt         rotation vector, re-centred and clamped
```
