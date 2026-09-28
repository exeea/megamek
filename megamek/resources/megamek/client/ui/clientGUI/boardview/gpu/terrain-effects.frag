#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform sampler2D u_depth;
uniform sampler2D u_noise;
uniform mat4 u_inverseProjView;
uniform vec2 u_size;
uniform vec3 u_wind;
uniform vec3 u_offset;
uniform vec3 u_light;
uniform vec3 u_sun;
uniform vec2 u_heights; // Flame and smoke heights above the fuel, in world units.
uniform float u_spread;
in vec4 v_origin;
in vec4 v_shape;
in float v_lod;

// Same periodic, two-channel 3D lookup used by board clouds: one filtered fetch per octave.
float noise(vec3 p) {
    vec3 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv = cell.xy + vec2(37.0, 17.0) * cell.z + f.xy;
    vec2 pair = texture(u_noise, (uv + 0.5) / 256.0).rg;
    return mix(pair.x, pair.y, f.z);
}

vec3 localVector(vec3 p) {
    p.xy -= u_wind.xy * p.z;
    return p / vec3(v_origin.ww, v_shape.x);
}

vec3 palette(float type) {
    return type < 0.5 ? vec3(1.0) : type < 1.5 ? vec3(0.48, 0.82, 0.25)
          : type < 2.5 ? vec3(0.72, 0.78, 0.88) : vec3(0.88, 0.91, 0.95);
}

float flameHeight() {
    // Inferno extends the flame above the fuel; it must not multiply the building/tree height itself.
    return max(0.0, v_shape.x - u_heights.y) + u_heights.x * max(1.0, v_shape.y);
}

// Smoke extinction, flame extinction and temperature. Shared world-space turbulence runs continuously
// across sources and LODs; overlapping, compact kernels merge through ordinary optical-depth addition.
vec3 medium(vec3 world) {
    float h = world.z - v_origin.z, z = h / v_shape.x;
    if (z <= 0.0 || z >= 1.0) return vec3(0.0);
    float expansion = 1.0 + u_spread * z;
    vec2 radial = (world.xy - v_origin.xy - u_wind.xy * h * (0.25 + 0.75 * z)) / v_origin.w;
    float support = 1.0 - smoothstep(0.8, 1.0, length(radial) / expansion);
    if (support == 0.0) return vec3(0.0);
    vec3 flow = world / v_origin.w * vec3(2.2, 2.2, 2.4) - u_offset;
    float billow = noise(flow);
    float fine = noise(flow * 2.0 + vec3(13.0, 7.0, 19.0) + billow * 1.3);
    vec2 curl = (vec2(billow, fine) - 0.5) * 0.5;
    vec2 smokeRadial = radial / expansion + curl;
    float envelope = max(0.0, 1.0 - dot(smokeRadial, smokeRadial));
    // No positive density floor: erosion can cut real gaps between rising billows.
    float erosion = smoothstep(0.26 + z * 0.18, 0.72, billow * 0.65 + fine * 0.35);
    float dilution = (1.0 - z) * (1.0 - z) / (expansion * expansion);
    float smoke = v_shape.z * envelope * erosion * dilution * support * smoothstep(0.0, 0.035, z);
    // Fire's soot must leave the hot layer readable from above. Smoke-only hazards remain denser.
    if (v_shape.y > 0.0) smoke *= 0.35;
    float flame = 0.0, heat = 0.0;
    float flameTop = flameHeight();
    if (v_shape.y > 0.0 && h < flameTop) {
        float rise = h / flameTop;
        // Vertical structures advect upward; a rising threshold tapers them into separate flame tongues.
        vec3 fuelFlow = vec3(world.xy / v_origin.w * 4.5 + curl * 2.0,
              world.z / flameTop * 2.0 - u_offset.z * 3.0 + fine * 0.7);
        float flicker = noise(fuelFlow * 3.0 + vec3(13.0, 29.0, 7.0));
        float fuel = noise(fuelFlow) * 0.62 + fine * 0.23 + flicker * 0.15;
        float threshold = 0.28 + rise * 0.42;
        float edge = 1.0 - smoothstep(0.35, 1.0, length(radial + curl * rise));
        float burning = smoothstep(threshold, threshold + 0.32, fuel);
        flame = burning * burning * edge * (1.0 - smoothstep(0.3, 1.0, rise)) * 2.6;
        heat = smoothstep(threshold, 0.86, fuel) * (1.0 - rise * 0.45) * (0.75 + flicker * 0.4);
    }
    return vec3(smoke, flame, heat);
}

// Clip the ray against a half-space of the widening, wind-sheared coverage frustum.
bool clipPlane(vec3 normal, float limit, vec3 origin, vec3 direction, inout vec2 interval) {
    float slope = dot(normal, direction), remaining = limit - dot(normal, origin);
    if (abs(slope) < 0.0000001) return remaining >= 0.0;
    float t = remaining / slope;
    if (slope > 0.0) interval.y = min(interval.y, t);
    else interval.x = max(interval.x, t);
    return interval.y > interval.x;
}

void main() {
    vec2 uv = gl_FragCoord.xy / u_size;
    vec4 nearPoint = u_inverseProjView * vec4(uv * 2.0 - 1.0, -1.0, 1.0);
    vec4 endPoint = u_inverseProjView * vec4(uv * 2.0 - 1.0, texture(u_depth, uv).r * 2.0 - 1.0, 1.0);
    vec3 nearWorld = nearPoint.xyz / nearPoint.w;
    vec3 worldRay = endPoint.xyz / endPoint.w - nearWorld;
    float distance = length(worldRay);
    if (distance < 0.0001) discard;
    vec3 direction = localVector(worldRay / distance);
    vec3 origin = localVector(nearWorld - v_origin.xyz);
    vec2 interval = vec2(0.0, distance);
    vec2 margin = abs(u_wind.xy) * v_shape.x / v_origin.w * 0.1875;
    if (!clipPlane(vec3(0.0, 0.0, -1.0), 0.0, origin, direction, interval)
          || !clipPlane(vec3(0.0, 0.0, 1.0), 1.0, origin, direction, interval)
          || !clipPlane(vec3(1.0, 0.0, -u_spread), 1.0 + margin.x, origin, direction, interval)
          || !clipPlane(vec3(-1.0, 0.0, -u_spread), 1.0 + margin.x, origin, direction, interval)
          || !clipPlane(vec3(0.0, 1.0, -u_spread), 1.0 + margin.y, origin, direction, interval)
          || !clipPlane(vec3(0.0, -1.0, -u_spread), 1.0 + margin.y, origin, direction, interval)) discard;
    float nearT = interval.x, farT = interval.y;
    float steps = v_lod < 0.5 ? 24.0 : v_lod < 1.5 ? 12.0 : 6.0;
    // Reserve half the samples for the dense lower plume when a ray also crosses the dissipating upper part.
    // Otherwise a taller column would let distant top-down rays skip the flame layer entirely.
    float splitZ = min(0.5, flameHeight() / v_shape.x);
    float splitT = abs(direction.z) < 0.0000001 ? nearT : clamp((splitZ - origin.z) / direction.z, nearT, farT);
    bool splitRay = splitT > nearT + 0.0001 && splitT < farT - 0.0001;
    // Stationary per-pixel jitter: pause and camera switches do not advance the simulation.
    float jitter = 0.4 + 0.2 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 lightStep = u_sun * v_origin.w * 0.22;
    float transmission = 1.0;
    vec3 radiance = vec3(0.0);
    for (int i = 0; i < 24; i++) {
        if (float(i) >= steps || transmission < 0.025) break;
        float sampleIndex = float(i), start = nearT, end = farT, count = steps;
        if (splitRay) {
            count = steps * 0.5;
            if (sampleIndex < count) { end = splitT; }
            else { start = splitT; sampleIndex -= count; }
        }
        float stepSize = (end - start) / count;
        float t = start + (sampleIndex + jitter) * stepSize;
        vec3 p = nearWorld + worldRay / distance * t;
        vec3 sampleMedium = medium(p);
        float density = sampleMedium.x + sampleMedium.y;
        float absorption = 1.0 - exp(-density * stepSize / v_origin.w * 4.5);
        if (absorption < 0.0001) continue;
        float shadow = v_lod < 1.5 ? exp(-medium(p + lightStep).x * 3.0) : 0.6;
        vec3 smoke = mix(vec3(0.065, 0.07, 0.075), vec3(0.48, 0.49, 0.50), shadow) * palette(v_shape.w) * u_light;
        float heat = sampleMedium.z;
        vec3 fire = mix(vec3(0.85, 0.075, 0.003), vec3(1.65, 0.48, 0.018), smoothstep(0.05, 0.65, heat));
        fire = mix(fire, vec3(1.9, 1.4, 0.48), smoothstep(0.6, 0.95, heat));
        // Local ember light within soot; no scene lights or extra shadow maps.
        smoke += vec3(0.14, 0.025, 0.001) * sampleMedium.y;
        vec3 source = mix(smoke, fire, sampleMedium.y / density);
        radiance += transmission * absorption * source;
        transmission *= 1.0 - absorption;
    }
    fragColor = vec4(radiance, 1.0 - transmission);
}
