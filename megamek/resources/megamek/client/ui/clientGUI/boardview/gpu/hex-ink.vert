#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec3 a_hex;
uniform sampler2D u_history;
uniform sampler2D u_scene;
uniform mat4 u_projection;
uniform vec3 u_hexSize;
uniform vec2 u_boardSize;
uniform vec3 u_thresholds; // Initial, black-to-white, white-to-black; all linear.
uniform float u_exposure;
flat out vec2 v_ink;
const vec2 corners[6] = vec2[6](vec2(.5, 0), vec2(.25, .5), vec2(-.25, .5),
      vec2(-.5, 0), vec2(-.25, -.5), vec2(.25, -.5));
// SCENE_RADIANCE

// The scene uses nearest sampling for depth-aware composition. Filter these sparse brightness taps
// explicitly so subpixel panning cannot jump between unrelated HDR texels; do not change scene state.
vec3 filteredScene(vec2 uv) {
    ivec2 last = textureSize(u_scene, 0) - ivec2(1);
    vec2 pixel = uv * vec2(last + ivec2(1)) - .5;
    ivec2 base = ivec2(floor(pixel));
    vec2 weight = fract(pixel);
    vec3 a = texelFetch(u_scene, clamp(base, ivec2(0), last), 0).rgb;
    vec3 b = texelFetch(u_scene, clamp(base + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 c = texelFetch(u_scene, clamp(base + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 d = texelFetch(u_scene, clamp(base + ivec2(1), ivec2(0), last), 0).rgb;
    return mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y);
}

vec4 source(vec3 world, bool edge) {
    vec4 clip = u_projection * vec4(world, 1.0);
    if (clip.w <= 0.0) return vec4(0.0);
    vec3 ndc = clip.xyz / clip.w;
    if (ndc.z < -1.0 || ndc.z > 1.0) return vec4(0.0);
    if (!edge && (any(lessThan(ndc.xy, vec2(-1.0))) || any(greaterThan(ndc.xy, vec2(1.0))))) return vec4(0.0);
    vec2 uv = clamp(ndc.xy * .5 + .5, vec2(0.0), vec2(1.0));
    return vec4(filteredScene(uv), 1.0);
}

// Only needed to initialize a sliver with no inset tap. Test the full convex hex against the viewport:
// the viewport axes alone would incorrectly learn offscreen hexes beside a diagonal screen corner.
bool footprintVisible(vec3 center) {
    vec2 points[6];
    vec2 low = vec2(2.0), high = vec2(-2.0);
    vec4 projected = u_projection * vec4(center, 1.0);
    vec2 middle = projected.xy / projected.w;
    for (int corner = 0; corner < 6; corner++) {
        vec4 clip = u_projection * vec4(center + vec3(corners[corner] * u_hexSize.xy, 0.0), 1.0);
        if (clip.w <= 0.0) return false;
        points[corner] = clip.xy / clip.w;
        low = min(low, points[corner]);
        high = max(high, points[corner]);
    }
    if (any(lessThan(high, vec2(-1.0))) || any(greaterThan(low, vec2(1.0)))) return false;
    for (int corner = 0; corner < 6; corner++) {
        vec2 edge = points[(corner + 1) % 6] - points[corner];
        vec2 inward = vec2(-edge.y, edge.x);
        if (dot(inward, middle - points[corner]) < 0.0) inward = -inward;
        if (dot(inward, sign(inward) - points[corner]) < 0.0) return false;
    }
    return true;
}

void main() {
    gl_Position = vec4((a_hex.xy + .5) / u_boardSize * 2.0 - 1.0, 0.0, 1.0);
    gl_PointSize = 1.0;
    v_ink = texelFetch(u_history, ivec2(a_hex.xy), 0).rg;
    vec3 center = vec3(a_hex.x * .75 + .5, -(a_hex.y + mod(a_hex.x, 2.0) * .5 + .5), a_hex.z) * u_hexSize;
    vec4 samples = source(center, false);
    for (int corner = 0; corner < 6; corner++) {
        samples += source(center + vec3(corners[corner] * u_hexSize.xy * .65, 0.0), false);
    }
    // A clipped footprint is a different average, not changed illumination. Keep a learned decision
    // until all taps return; a newly visible owner can still choose from its available first sample.
    if (samples.a < 7.0 && v_ink.g > .5) return;
    bool visible = samples.a > 0.0;
    if (!visible) {
        if (v_ink.g > .5) return; // An offscreen hex retains its learned choice.
        samples = source(center, true); // A visible sliver may miss all seven inset taps.
        if (samples.a == 0.0) { v_ink = vec2(0.0); return; }
        visible = footprintVisible(center); // Learn this fallback once only when the full hex really intersects.
    }
    float luminance = dot(max(loadScene(samples.rgb / samples.a), vec3(0.0)), vec3(.2126, .7152, .0722)) * u_exposure;
    float ink = luminance < u_thresholds.x ? 1.0 : 0.0;
    if (v_ink.g > .5) {
        ink = v_ink.r > .5 ? (luminance > u_thresholds.z ? 0.0 : 1.0)
              : (luminance < u_thresholds.y ? 1.0 : 0.0);
    }
    v_ink = vec2(ink, visible ? 1.0 : 0.0);
}
