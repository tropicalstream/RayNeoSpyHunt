package com.rayneo.spyhunt.render

/**
 * GLSL ES 3.00 sources for the 1983 arcade presentation.
 *
 * These are SEPARATE programs from [Shaders], not branches inside them. A style
 * switch happens at most once per frame, whereas a `if (uVintage)` in the fragment
 * shader would be re-evaluated a few million times per frame for a value that
 * never changes inside a draw — two programs are strictly cheaper.
 *
 * Three facts shape everything below.
 *
 *  1. The waveguide is ADDITIVE — black is fully transparent and there is no dark
 *     grey. The neon look answered that by drawing almost nothing but emissive
 *     strokes on black. The vintage look cannot: its whole identity is filled
 *     areas (grey tarmac, green verge, flat sprite bodies). So the filled areas
 *     are kept deliberately dim, and their *texture* is punched to true black —
 *     which means the wearer's room shows through the texture rather than the
 *     verge being one more lit surface.
 *
 *  2. Everything is graded for the composite that runs after it: exposure ~1.0
 *     and gamma 2.2. Gamma lifts hard at the bottom (0.09 linear reads as 0.33 on
 *     screen), so the linear constants here look far darker than the result. Each
 *     one is annotated with the value it actually lands on.
 *
 *  3. The composite posterises to ~6 levels per channel, so anything separated by
 *     less than one level (~0.17 in output space) collapses into a single flat
 *     tone. Contrasts that are meant to survive are sized to cross a level
 *     boundary; contrasts that are meant to vanish at distance are allowed to.
 *
 * Vertex layouts are fixed by meshes that already exist and must not change.
 */
object VintageShaders {

    // ------------------------------------------------------------------
    // Scene geometry: vehicles and props. Flat sprite-era shading.
    // ------------------------------------------------------------------
    const val SCENE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec3 aCol;
layout(location = 3) in float aGlow;

uniform mat4 uMVP;
uniform mat4 uNrmMat;

// `flat` is the whole trick: it takes the provoking vertex instead of
// interpolating, so every facet gets exactly one normal and therefore exactly one
// brightness step. Meshes built with smooth normals (the lathed and lofted hulls)
// come out visibly faceted, which is the sprite-era look rather than a defect.
flat out vec3 vNrm;
out vec3 vCol;
out float vGlow;

void main() {
    vNrm = (uNrmMat * vec4(aNrm, 0.0)).xyz;
    vCol = aCol;
    vGlow = aGlow;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val SCENE_FS = """#version 300 es
precision mediump float;

flat in vec3 vNrm;
in vec3 vCol;
in float vGlow;

uniform vec3 uLightDir;
uniform vec3 uTint;     // per-instance multiplier (scenery is dimmed with this)
uniform float uFlash;   // 0..1 white hit-flash
uniform float uFade;    // distance/spawn fade

out vec4 fragColor;

// One ink brightness for everything. 0.62 linear reads as ~0.80 after the
// composite's gamma, which is well clear of the road slab (~0.33) and well under
// the bright-pass threshold, so vintage geometry never blooms.
const float INK = 0.62;

// Colour levels per channel for the material palette. Four gives five values per
// channel; in practice the meshes only ever land on a couple of dozen distinct
// inks, which is roughly what the original hardware had.
const float PALETTE = 4.0;

void main() {
    vec3 N = normalize(vNrm);

    // ---- ink: keep the hue, throw away the HDR magnitude ----
    // The meshes carry emissive colours up to ~6.0 for the neon pass. Used as-is
    // they would clip to white here and every vehicle would be the same blank
    // shape. Normalising by the max channel keeps each vehicle's identifying hue
    // and hands the brightness back to the shading ramp, where it belongs.
    vec3 c = max(vCol, vec3(0.0));
    float m = max(max(c.r, c.g), max(c.b, 1e-4));
    vec3 ink = floor((c / m) * PALETTE + 0.5) / PALETTE;

    // ---- three hard brightness steps ----
    // No half-lambert, no rim, no fresnel: a facet is one of three flat tones.
    // The darkest step is 0.40 rather than 0 because on an additive display an
    // unlit facet would be a transparent hole punched through the silhouette.
    // uLightDir is assumed unit length (as in the neon path); if it is left unset
    // this degrades to the darkest step rather than to NaN.
    float ndl = max(dot(N, uLightDir), 0.0);
    float shade = 0.40 + 0.28 * step(0.22, ndl) + 0.32 * step(0.62, ndl);

    vec3 col = ink * (INK * shade);

    // Emissive parts (headlights, thrusters, sirens) skip the ramp entirely and
    // sit one posterise level above the brightest lit facet, so they read as solid
    // blocks of light rather than as a gradient.
    col = mix(col, ink * (INK * 1.55), clamp(vGlow, 0.0, 1.0));

    col *= uTint;

    // Hit flash goes fully white by replacement, not by addition: the sprite
    // blanking to a solid white silhouette is the era-correct hit cue, and adding
    // to a colour that is already near the clamp would only shift its hue.
    col = mix(col, vec3(1.15), clamp(uFlash, 0.0, 1.0));

    fragColor = vec4(col * uFade, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Road: solid slab with painted markings.
    // aUv.x = -1..1 across the carriageway, aUv.y = ABSOLUTE world Z in metres.
    // ------------------------------------------------------------------
    const val ROAD_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aSurf;   // 0 = tarmac, 1 = water

uniform mat4 uMVP;

// highp is mandatory here: vUv.y carries ABSOLUTE world Z and grows without bound
// as the player drives. At mediump (~10-bit mantissa) a value near 1000 quantises
// to ~0.5 m, and every fract(z / period) marking collapses into shimmering moire.
out highp vec2 vUv;
out float vSurf;
out highp float vDepth;

void main() {
    vUv = aUv;
    vSurf = aSurf;
    vDepth = aPos.z;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val ROAD_FS = """#version 300 es
precision highp float;

in highp vec2 vUv;
in float vSurf;
in highp float vDepth;

uniform float uTime;
uniform vec3 uWaterCol;
uniform float uFogStart;
uniform float uFogEnd;

out vec4 fragColor;

// Antialiased periodic stripe: 1.0 inside the stripe, 0.0 outside.
// Same guarded form as the neon road — once a pixel spans more than the stripe
// itself there is no signal left to resolve, so it fades out instead of aliasing.
float stripe(float x, float period, float duty, float aa) {
    float f = fract(x / period);
    float s = smoothstep(duty + aa, duty - aa, f);
    return s * (1.0 - smoothstep(duty * 0.9, duty * 2.5, aa));
}

// A filled slab, which the neon pass deliberately never draws. 0.086 linear reads
// as ~0.33 on screen: unmistakably a grey road surface, but roughly a third of the
// panel's output, so it stays a surface the wearer looks *at* rather than a lamp
// pointed into their room. This is the brightest large fill in the whole style,
// and everything else is graded relative to it.
const vec3 TARMAC = vec3(0.082, 0.086, 0.098);

// Paint. 0.95 linear lands at ~0.98 on screen, i.e. the top posterise level, so
// the markings come out as pure flat white with no shading on them at all.
const float PAINT = 0.95;

void main() {
    float u = vUv.x;          // -1 .. 1 across the carriageway
    float z = vUv.y;          // ABSOLUTE world Z, metres
    float au = abs(u);

    // Derivative-based AA widths, so markings stay crisp near and soften far
    // instead of breaking up.
    float wu = fwidth(u) * 1.5 + 1e-4;
    float wz = fwidth(z) * 1.5 + 1e-3;

    // ---------------- tarmac ----------------
    vec3 road = TARMAC;

    // Solid white edge lines running all the way to the kerb. These, not a neon
    // rail, are what defines the road edge in the original.
    float edge = smoothstep(0.940 - wu, 0.940 + wu, au);
    road = mix(road, vec3(PAINT), clamp(edge, 0.0, 1.0));

    // Dashed white centre line. z is already absolute world Z, so the dashes are
    // anchored in the world and scroll for free as the player advances — adding
    // the player's Z on top would double-count and slide them at twice road speed.
    float centre = smoothstep(0.055 + wu, 0.055 - wu, au) * stripe(z, 9.0, 0.45, wz / 9.0);
    road = mix(road, vec3(PAINT), clamp(centre, 0.0, 1.0));

    // Dashed lane divisions at half the half-width, slightly duller so the centre
    // line still reads as the centre line.
    float lane = smoothstep(0.030 + wu, 0.030 - wu, abs(au - 0.5))
               * stripe(z, 6.0, 0.30, wz / 6.0);
    road = mix(road, vec3(PAINT * 0.78), clamp(lane, 0.0, 1.0));

    // ---------------- water ----------------
    vec3 water;
    {
        // uWaterCol left unset would be (0,0,0) and the river would simply not be
        // there, so fall back to an arcade blue rather than to nothing.
        vec3 wc = mix(vec3(0.10, 0.30, 0.78), uWaterCol,
                      step(1e-4, dot(uWaterCol, uWaterCol)));

        // Flat blocky bands, not a ripple. Built from fract() rather than sin() so
        // it stays exact at four-digit world Z, and quantised to three levels so
        // the surface reads as stacked bars of blue drifting toward the player.
        float phase = z * 0.048 + u * 0.05 - uTime * 0.055;
        float f = fract(phase);
        float band = step(0.34, f) + step(0.67, f);        // 0, 1, 2
        float lvl = 0.42 + 0.29 * band;                     // 0.42 / 0.71 / 1.00

        // Same aliasing guard as the stripes: collapse to a flat mid tone once the
        // bands are finer than a pixel.
        float bandFade = 1.0 - smoothstep(0.12, 0.40, fwidth(phase));
        water = wc * mix(0.62, lvl, bandFade);

        // Dashed white channel markers stand in for the lane paint.
        float chan = smoothstep(0.930 - wu, 0.930 + wu, au) * stripe(z, 14.0, 0.35, wz / 14.0);
        water = mix(water, vec3(PAINT), clamp(chan, 0.0, 1.0));
    }

    // Hard switch rather than a blend: vSurf interpolates across a whole 4 m road
    // slice, and a gradient from tarmac to river is the one thing the original
    // would never have drawn.
    vec3 outCol = mix(road, water, step(0.5, vSurf));

    // Distance fade to black = fade to transparent, so the road dissolves into the
    // room instead of ending at a hard line in mid-air. Left smooth on purpose:
    // the composite's posterise turns it into a handful of flat depth bands, which
    // is a better horizon than anything stepped by hand here.
    float fog = 1.0 - smoothstep(uFogStart, uFogEnd, vDepth);
    fragColor = vec4(outCol * fog, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Ground: the green verge either side of the road.
    // aUv.x = -1..1 across the strip, aUv.y = ABSOLUTE world Z in metres.
    // ------------------------------------------------------------------
    const val GROUND_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;

uniform mat4 uMVP;

// highp for the same reason as the road: vUv.y is absolute world Z.
out highp vec2 vUv;
out highp float vDepth;

void main() {
    vUv = aUv;
    vDepth = aPos.z;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val GROUND_FS = """#version 300 es
precision highp float;

in highp vec2 vUv;
in highp float vDepth;

uniform float uTime;
uniform float uFogStart;
uniform float uFogEnd;

out vec4 fragColor;

float stripe(float x, float period, float duty, float aa) {
    float f = fract(x / period);
    float s = smoothstep(duty + aa, duty - aa, f);
    return s * (1.0 - smoothstep(duty * 0.9, duty * 2.5, aa));
}

// Cheap 2-D hash. Fed only small wrapped cell indices, so it stays well-behaved at
// highp no matter how far the player has driven.
float hash21(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// The single most dangerous surface in this style: it is large, it is saturated,
// and on an additive waveguide a large saturated fill is a glowing rectangle
// hanging in the wearer's room. So it is graded to ~0.24 on screen — clearly below
// the road's ~0.33 — and it is mostly holes.
// The channel SPREAD matters more than the absolute level, because the composite
// posterises AFTER gamma. With r/g/b this close together all three land in the same
// quantisation bucket and the verge resolves as flat grey — which is exactly what
// the first tuning did on device. Green is lifted and red/blue pushed down so the
// hue survives pow(1/2.2) followed by 6-level quantisation.
const vec3 GRASS = vec3(0.0030, 0.0760, 0.0115);

void main() {
    float u = vUv.x;
    float z = vUv.y;          // ABSOLUTE world Z, metres
    float au = abs(u);

    // Epsilon matches the road shader's guard: stripe() feeds aa into
    // smoothstep(duty+aa, duty-aa, f), and a zero aa makes edge0 == edge1, which is
    // undefined and can surface as a NaN (i.e. a transparent hole) in the verge.
    float wz = fwidth(z) + 1e-3;
    float wu = fwidth(u) + 1e-5;

    // ---- coarse world-anchored dither: clumps of grass ----
    // Cells must stay small in SCREEN terms, not just world terms. At 0.16 of a
    // 92 m strip a cell is ~15 m across and projects to a broad slab, which reads
    // as glitch banding rather than ground texture once the composite pixelates it.
    const float CELL_Z = 1.6;     // metres per cell
    const float CELL_U = 0.05;    // fraction of the strip per cell
    // mod() keeps the hash input small and exact; a 89-cell repeat is ~214 m,
    // which is far past the fog and so never reads as a repeat.
    vec2 cell = vec2(floor(u / CELL_U), mod(floor(z / CELL_Z), 89.0));
    float h = hash21(cell);

    // Once a pixel covers a whole cell there is no signal left to resolve, so the
    // dither is faded out rather than left to boil — the same guard the stripes use.
    float dFade = (1.0 - smoothstep(0.35, 0.90, wz / CELL_Z))
                * (1.0 - smoothstep(0.35, 0.90, wu / CELL_U));
    float dither = step(h, 0.16) * dFade;

    // ---- long transverse bands: the "rushing past" cue ----
    // Anchored in world Z, so they scroll at exactly road speed for free.
    float bands = stripe(z, 11.0, 0.09, wz / 11.0);

    // The texture is punched to near-black rather than merely dimmed, for two
    // reasons. Black is transparent here, so the verge shows the wearer's room
    // through its own texture and costs half the light output of a solid fill. And
    // a near-zero survives posterisation at any level setting, whereas a subtle
    // dim sits inside one quantisation bucket and is erased entirely — a flat
    // glowing slab is exactly what this must not become.
    float holes = clamp(dither + bands, 0.0, 1.0);
    float mask = 1.0 - 0.70 * holes;

    // Very slow brightness sway, ~105 m per cycle: far below any frequency that
    // could alias, but enough that the verge is never completely static.
    float wind = 1.0 + 0.10 * sin(z * 0.06 + uTime * 1.3);

    vec3 col = GRASS * (mask * wind);

    // Fade to black across the strip edge. Black = transparent, so the verge
    // dissolves into the room instead of ending on a hard bright rectangle edge.
    // Deliberately symmetric in |u|: the mesh may run -1..1 outward on one side and
    // inward on the other, and a symmetric taper is correct either way. The
    // kerb-side taper it costs is hidden underneath the road's white edge line.
    col *= 1.0 - smoothstep(0.70, 1.0, au);

    float fog = 1.0 - smoothstep(uFogStart, uFogEnd, vDepth);
    fragColor = vec4(col * fog, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Composite. Pairs with the EXISTING Shaders.POST_VS.
    // This pass is what actually sells the era.
    // ------------------------------------------------------------------
    const val COMPOSITE_FS = """#version 300 es
// highp throughout, unlike the neon composite. The pixel snap evaluates
// floor(vUv * uPixelGrid) where the product reaches ~224; at mediump the absolute
// error up there is a fifth of a block, so block boundaries would wobble from one
// pixel to the next and the grid would come out with ragged, crawling edges.
precision highp float;

in highp vec2 vUv;

uniform sampler2D uScene;
uniform sampler2D uBloomNear;
uniform sampler2D uBloomWide;
uniform float uBloomNearAmt;
uniform float uBloomWideAmt;
uniform float uExposure;
uniform float uGamma;
uniform float uEdgeFade;    // where the soft border starts, 0..1
uniform float uFlash;       // full-screen white flash

// Vintage-only.
uniform vec2 uPixelGrid;    // horizontal/vertical block counts, e.g. 224 x 168
uniform float uPosterize;   // colour levels per channel, e.g. 6
uniform float uScanline;    // 0..1 strength

out vec4 fragColor;

// Box-average the block rather than point-sampling its centre. The scene buffer is
// ~2.9x finer than the block grid, and a single tap makes every thin bright thing
// — lane dashes, distant cars, HUD strokes — crawl and sparkle as the blocks slide
// over them. Four taps integrate the block properly; the result is still one flat
// colour per block, just a stable one.
vec3 sceneBlock(vec2 uv, vec2 h) {
    return (texture(uScene, uv + vec2(-h.x, -h.y)).rgb +
            texture(uScene, uv + vec2( h.x, -h.y)).rgb +
            texture(uScene, uv + vec2(-h.x,  h.y)).rgb +
            texture(uScene, uv + vec2( h.x,  h.y)).rgb) * 0.25;
}

void main() {
    // ---- 1. chunky pixels: the strongest single vintage cue ----
    // The fallback matters: an unset uniform is (0,0), and simply max()-ing that up
    // to 1 would collapse the entire eye into one block instead of degrading.
    vec2 grid = mix(vec2(224.0, 168.0), uPixelGrid,
                    step(2.0, min(uPixelGrid.x, uPixelGrid.y)));
    vec2 snap = (floor(vUv * grid) + 0.5) / grid;

    vec3 c = sceneBlock(snap, 0.25 / grid);

    // Bloom is sampled at the same snapped UV so it cannot reintroduce sub-block
    // detail. The integrator drives these near zero for vintage, but the uniform
    // set stays identical to the neon composite's so one call site serves both.
    c += texture(uBloomNear, snap).rgb * uBloomNearAmt;
    c += texture(uBloomWide, snap).rgb * uBloomWideAmt;
    c += vec3(uFlash);

    // ---- 2. flat tonemap, NOT filmic ----
    // An ACES shoulder rolls the top end into a smooth gradient, which is exactly
    // the signal posterisation needs in order to show up. A hard clamp keeps every
    // level a flat plateau, which is what sprite hardware produced.
    c = clamp(c * uExposure, 0.0, 1.0);
    c = pow(c, vec3(1.0 / max(uGamma, 0.01)));

    // ---- 3. posterise ----
    // Round-to-nearest gives levels+1 plateaus spanning 0..1 inclusive and can
    // never exceed 1.0, unlike the floor(c*n)/(n-1) form.
    float levels = mix(6.0, uPosterize, step(2.0, uPosterize));
    c = floor(c * levels + 0.5) / levels;

    // ---- 4. scanlines, in OUTPUT space ----
    // gl_FragCoord is window space, so the lines lock to the physical panel rows
    // and stay put while the pixel blocks scroll underneath — which is why the snap
    // has to happen before this and not after. Darkening on an additive waveguide
    // means going transparent, so these are genuine gaps with the wearer's room
    // behind them rather than the muddy grey they would be on an emissive panel.
    float odd = mod(floor(gl_FragCoord.y), 2.0);
    c *= 1.0 - clamp(uScanline, 0.0, 1.0) * odd;

    // ---- 5. superelliptical border ----
    // Kept from the neon composite, and needed just as much: fading to black on an
    // additive display fades to fully transparent, so the rectangular frame of the
    // app dissolves into the room rather than hanging in space as a hard box.
    // Computed from the UNSNAPPED vUv and applied after posterisation on purpose —
    // a quantised fade would come back as visible concentric rings.
    vec2 q = abs(vUv - 0.5) * 2.0;
    float q6 = pow(q.x, 6.0) + pow(q.y, 6.0);
    float r = pow(q6, 1.0 / 6.0);
    c *= 1.0 - smoothstep(uEdgeFade, 1.0, r);

    fragColor = vec4(c, 1.0);
}
"""
}
