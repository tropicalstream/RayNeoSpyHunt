package com.rayneo.spyhunt.render

/**
 * GLSL ES 3.00 sources.
 *
 * Two facts about the target display drive every shader here:
 *
 *  1. The waveguide is ADDITIVE — black is transparent, there is no "dark grey".
 *     So surfaces are lit toward emissive, silhouettes are drawn with rim light
 *     rather than shading, and large dim fills are avoided entirely.
 *  2. Colour is HDR (RGBA16F) so emissive materials can exceed 1.0 and drive a
 *     real bright-pass. Tone mapping happens once, in the composite.
 */
object Shaders {

    // ------------------------------------------------------------------
    // Scene geometry: vehicles, scenery. layout matches Meshes' vertex format.
    // ------------------------------------------------------------------
    const val SCENE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec3 aCol;
layout(location = 3) in float aGlow;

uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat4 uNrmMat;

out vec3 vNrm;
out vec3 vCol;
out float vGlow;
out vec3 vWorld;

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vWorld = wp.xyz;
    vNrm = normalize((uNrmMat * vec4(aNrm, 0.0)).xyz);
    vCol = aCol;
    vGlow = aGlow;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val SCENE_FS = """#version 300 es
precision mediump float;

in vec3 vNrm;
in vec3 vCol;
in float vGlow;
in vec3 vWorld;

uniform vec3 uCamPos;
uniform vec3 uLightDir;
uniform float uRim;        // rim-light strength
uniform float uFlash;      // 0..1 white hit-flash
uniform vec3 uTint;        // per-instance multiplier
uniform float uFade;       // distance/spawn fade

out vec4 fragColor;

void main() {
    vec3 N = normalize(vNrm);
    vec3 V = normalize(uCamPos - vWorld);

    // Half-lambert: keeps the unlit side from collapsing to invisible black,
    // which on an additive display would punch a hole in the silhouette.
    float ndl = dot(N, uLightDir) * 0.5 + 0.5;
    vec3 lit = vCol * (0.10 + 0.90 * ndl * ndl);

    // Rim light does the heavy lifting for readability: it traces the outline
    // of every object, which is what survives at this angular resolution.
    float rim = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 2.2);

    vec3 col = mix(lit, vCol, vGlow);
    col += vCol * rim * uRim * (0.30 + 0.70 * vGlow);
    col *= uTint;
    col += vec3(uFlash) * 3.0;

    fragColor = vec4(col * uFade, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Road: procedurally marked in the fragment shader.
    // aUv.x = -1..1 across the road, aUv.y = world Z (metres).
    // ------------------------------------------------------------------
    const val ROAD_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aSurf;   // 0 = tarmac, 1 = water

uniform mat4 uMVP;

// highp: vUv.y carries ABSOLUTE world Z, which grows without bound as the player
// drives. At mediump (~10-bit mantissa) a value near 1000 quantises to ~0.5 m
// steps, and fract(z / period) for the lane markings collapses into moire.
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
// vUv.y already carries absolute world Z, so markings are anchored in the world
// and scroll for free as the player advances. (Adding the player's Z on top of it
// double-counts and makes them slide past at twice road speed.)
uniform vec3  uEdgeCol;
uniform vec3  uLaneCol;
uniform vec3  uWaterCol;
uniform float uFogStart;
uniform float uFogEnd;

out vec4 fragColor;

// Antialiased periodic stripe: 1.0 inside the stripe, 0.0 outside.
float stripe(float x, float period, float duty, float aa) {
    float f = fract(x / period);
    float s = smoothstep(duty + aa, duty - aa, f);
    // Once a pixel spans more than the stripe itself there is no signal left to
    // resolve, so fade out rather than alias. Without this the markings turn into
    // a shimmering moire wherever the road is near edge-on.
    return s * (1.0 - smoothstep(duty * 0.9, duty * 2.5, aa));
}

void main() {
    float u = vUv.x;               // -1 .. 1 across the road
    float z = vUv.y;               // world Z
    float au = abs(u);

    // Derivative-based AA width, so markings stay crisp near and soft far.
    float wu = fwidth(u) * 1.5 + 1e-4;
    float wz = fwidth(z) * 1.5 + 1e-3;

    vec3 col = vec3(0.0);

    // ---------------- tarmac ----------------
    // The road surface itself stays black: on this display that means the real
    // world shows through, and only the neon markings are drawn. Far more
    // convincing than trying to paint grey asphalt on a transparent screen.

    // Bright edge rails.
    float edge = smoothstep(0.90, 0.985, au) * (1.0 - smoothstep(0.985, 1.0, au) * 0.15);
    col += uEdgeCol * edge * 4.0;

    // Dashed centre line.
    float centre = smoothstep(0.055, 0.0, au) * stripe(z, 9.0, 0.45, wz / 9.0);
    col += uLaneCol * centre * 2.6;

    // Solid lane divisions at +/- 0.5 of the half-width, dimmer.
    float lane = (smoothstep(0.025, 0.0, abs(au - 0.5)));
    col += uLaneCol * lane * stripe(z, 6.0, 0.35, wz / 6.0) * 0.9;

    // Faint transverse scan lines: gives the road a sense of speed and depth
    // even where there is no other geometry.
    float scan = stripe(z, 24.0, 0.06, wz / 24.0);
    col += uEdgeCol * scan * 0.35 * (1.0 - smoothstep(0.6, 1.0, au));

    vec3 tarmac = col;

    // ---------------- water ----------------
    vec3 water = vec3(0.0);
    {
        float t = uTime;
        float zz = z;
        // Crossing wave trains, cheap but reads as moving water.
        float w = sin(zz * 0.55 - t * 3.1) * 0.5
                + sin(zz * 0.23 + u * 3.7 + t * 1.7) * 0.3
                + sin(u * 9.0 - t * 2.3) * 0.2;
        float crest = smoothstep(0.35, 0.95, w);
        water += uWaterCol * (0.16 + crest * 1.9);
        // Channel markers instead of lane lines.
        float chan = smoothstep(0.93, 1.0, au);
        water += uEdgeCol * chan * 3.2;
        // Glitter.
        float g = smoothstep(0.86, 1.0, sin(zz * 4.1 + t * 5.0) * sin(u * 17.0 - t * 3.0));
        water += vec3(0.6, 0.9, 1.0) * g * 2.0;
    }

    vec3 outCol = mix(tarmac, water, vSurf);

    // Distance fade to black = fade to transparent. The road dissolves into the
    // room instead of ending at a hard line.
    float fog = 1.0 - smoothstep(uFogStart, uFogEnd, vDepth);
    fragColor = vec4(outCol * fog, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Additive sprites: particles and trail ribbons.
    // ------------------------------------------------------------------
    const val SPRITE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aCol;

uniform mat4 uMVP;

out vec2 vUv;
out vec4 vCol;

void main() {
    vUv = aUv;
    vCol = aCol;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val SPRITE_FS = """#version 300 es
precision mediump float;

in vec2 vUv;
in vec4 vCol;

out vec4 fragColor;

void main() {
    // Soft radial falloff with a hot core — cheaper and sharper than a texture,
    // and the core is what produces the bloom streaks.
    vec2 d = vUv * 2.0 - 1.0;
    float r2 = dot(d, d);
    if (r2 > 1.0) discard;
    float fall = 1.0 - r2;
    float a = fall * fall;
    float core = pow(fall, 8.0);
    fragColor = vec4(vCol.rgb * (a + core * 2.5) * vCol.a, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Flat emissive geometry: vector font and HUD.
    // ------------------------------------------------------------------
    const val HUD_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aCol;

uniform mat4 uMVP;

out vec4 vCol;

void main() {
    vCol = aCol;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val HUD_FS = """#version 300 es
precision mediump float;
in vec4 vCol;
out vec4 fragColor;
void main() {
    fragColor = vec4(vCol.rgb * vCol.a, 1.0);
}
"""

    // ------------------------------------------------------------------
    // Post chain.
    // ------------------------------------------------------------------
    const val POST_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Bright pass with a soft knee, combined with a 4-tap box downsample. */
    const val BRIGHT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uThreshold;
uniform float uKnee;
out vec4 fragColor;

vec3 tap(vec2 o) { return texture(uTex, vUv + o * uTexel).rgb; }

void main() {
    vec3 c = (tap(vec2(-1.0, -1.0)) + tap(vec2(1.0, -1.0)) +
              tap(vec2(-1.0,  1.0)) + tap(vec2(1.0,  1.0))) * 0.25;

    float b = max(c.r, max(c.g, c.b));
    // Quadratic soft knee: avoids the hard flickering edge a plain step gives
    // when a bright object crosses the threshold.
    float soft = clamp(b - uThreshold + uKnee, 0.0, 2.0 * uKnee);
    soft = soft * soft / (4.0 * uKnee + 1e-4);
    float contrib = max(soft, b - uThreshold) / max(b, 1e-4);

    fragColor = vec4(c * contrib, 1.0);
}
"""

    /** Separable 9-tap Gaussian, using bilinear taps to halve the sample count. */
    const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;      // (texelX, 0) or (0, texelY)
out vec4 fragColor;

void main() {
    // Weights/offsets for a 9-tap Gaussian folded into 5 bilinear fetches.
    const float o1 = 1.3846153846;
    const float o2 = 3.2307692308;
    const float w0 = 0.2270270270;
    const float w1 = 0.3162162162;
    const float w2 = 0.0702702703;

    vec3 c = texture(uTex, vUv).rgb * w0;
    c += texture(uTex, vUv + uDir * o1).rgb * w1;
    c += texture(uTex, vUv - uDir * o1).rgb * w1;
    c += texture(uTex, vUv + uDir * o2).rgb * w2;
    c += texture(uTex, vUv - uDir * o2).rgb * w2;
    fragColor = vec4(c, 1.0);
}
"""

    /** Simple bilinear downsample between bloom mip levels. */
    const val DOWN_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexel;
out vec4 fragColor;
void main() {
    vec3 c = texture(uTex, vUv + vec2(-1.0, -1.0) * uTexel).rgb
           + texture(uTex, vUv + vec2( 1.0, -1.0) * uTexel).rgb
           + texture(uTex, vUv + vec2(-1.0,  1.0) * uTexel).rgb
           + texture(uTex, vUv + vec2( 1.0,  1.0) * uTexel).rgb;
    fragColor = vec4(c * 0.25, 1.0);
}
"""

    /** Composite: scene + two bloom octaves, tone map, then melt the frame edge. */
    const val COMPOSITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;

uniform sampler2D uScene;
uniform sampler2D uBloomNear;
uniform sampler2D uBloomWide;
uniform float uBloomNearAmt;
uniform float uBloomWideAmt;
uniform float uExposure;
uniform float uGamma;
uniform float uEdgeFade;    // where the soft border starts, 0..1
uniform float uFlash;       // full-screen white flash (damage, explosions)

out vec4 fragColor;

// Narkowicz ACES approximation — filmic rolloff without an LUT.
vec3 aces(vec3 x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec3 c = texture(uScene, vUv).rgb;
    c += texture(uBloomNear, vUv).rgb * uBloomNearAmt;
    c += texture(uBloomWide, vUv).rgb * uBloomWideAmt;
    c += vec3(uFlash);

    c = aces(c * uExposure);
    c = pow(max(c, 0.0), vec3(1.0 / uGamma));

    // Superelliptical edge falloff. Fading to black on an additive display
    // fades to fully transparent, so the rectangular frame of the app
    // dissolves into the room rather than hanging in space as a hard box.
    vec2 q = abs(vUv - 0.5) * 2.0;
    float q6 = pow(q.x, 6.0) + pow(q.y, 6.0);
    float r = pow(q6, 1.0 / 6.0);
    c *= 1.0 - smoothstep(uEdgeFade, 1.0, r);

    fragColor = vec4(c, 1.0);
}
"""
}
