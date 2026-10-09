#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
#extension GL_EXT_frag_depth : require
precision highp float;
#define DEPTH gl_FragDepthEXT
#else
#define DEPTH gl_FragDepth
#endif
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_depth;
uniform sampler2D u_waterDepth;
uniform sampler2D u_fog;
uniform sampler2D u_heatGlow;
uniform float u_heatEnabled;
uniform float u_heatGlowStrength;
uniform float u_fogEnabled;
uniform vec4 u_scatteringBounds;
uniform vec2 u_fogSize;
uniform float u_edgeScale;
uniform vec3 u_sky;
uniform vec3 u_horizon;
// GROUND_LAYER
// WEATHER_SAND

// ATMOSPHERE_FOV

// ATMOSPHERE_GRADE

// HEAT_RADIANCE

float depthAt(vec2 uv) {
    return texture(u_depth, uv).r;
}

// Fog ends at the first visible surface, including water. The opaque bed depth remains available for tactical depth.
float atmosphereDepthAt(vec2 uv) {
    return min(depthAt(uv), texture(u_waterDepth, uv).r);
}

// ATMOSPHERE_GLARE

void main() {
    float depth = depthAt(v_uv);
    float surfaceDepth = u_fogEnabled > 0.5 || u_sand.x > 0.0 ? atmosphereDepthAt(v_uv) : depth;
    vec4 scene = texture(u_scene, v_uv);
    vec4 atmosphere = vec4(0, 0, 0, 1);
    bool solidBase = false;
    if (u_fogEnabled > 0.5) solidBase = groundBaseSide(world(surfaceDepth), surfaceDepth, u_sand.z);
    if (u_fogEnabled > 0.5 && !solidBase && v_uv.x >= u_scatteringBounds.x && v_uv.y >= u_scatteringBounds.y
          && v_uv.x <= u_scatteringBounds.z && v_uv.y <= u_scatteringBounds.w) {
        // Bilateral upsampling keeps low-resolution fog from bleeding across roofs and unit silhouettes.
        vec2 pixel = v_uv * u_fogSize - 0.5;
        vec2 base = floor(pixel);
        vec2 f = fract(pixel);
        atmosphere = vec4(0.0);
        float weights = 0.0;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                vec2 offset = vec2(float(x), float(y));
                vec2 uv = (base + offset + 0.5) / u_fogSize;
                vec2 bilinear = mix(1.0 - f, f, offset);
                float difference = abs(cameraDepth(surfaceDepth) - cameraDepth(atmosphereDepthAt(uv)));
                float weight = bilinear.x * bilinear.y / (1.0 + difference * difference / (u_edgeScale * u_edgeScale));
                atmosphere += texture(u_fog, uv) * weight;
                weights += weight;
            }
        }
        atmosphere = weights > 0.00001 ? atmosphere / weights : texture(u_fog, v_uv);
    }
    // Scene clear RGB contains u_sky. Replace its uncovered part with the sky gradient continuously;
    // treating any nonzero alpha as opaque leaves a hard sky-colored border around dissipating volumes.
    float coverage = depth < 1.0 ? 1.0 : clamp(scene.a, 0.0, 1.0);
    vec3 sky = mix(u_horizon, u_sky, smoothstep(0.0, 1.0, v_uv.y));
    vec3 color = scene.rgb + (sky - u_sky) * (1.0 - coverage);
    vec3 linear = pow(max(color, vec3(0.0)), vec3(2.2));
    vec3 heat = vec3(0.0);
    if (u_heatEnabled > 0.5) {
        float hot = heatWeight(linear);
        heat = linear * hot;
        // Only selected heat bypasses night desaturation. Ordinary reflected highlights retain their range too.
        linear *= 1.0 - hot;
    }
    vec4 sand = sandLayer(surfaceDepth);
    linear = (linear * sand.a + sand.rgb) * atmosphere.a + atmosphere.rgb;
    linear = gradeScene(linear, coverage);
    linear += heat * sand.a * atmosphere.a * u_exposure;
    if (u_heatGlowStrength > 0.0) {
        // Fog already attenuated the visible source before blur. Sand also dims the camera halo.
        linear += texture(u_heatGlow, v_uv).rgb * (u_heatGlowStrength * sand.a * u_exposure);
    }
    // Lens glare borrows the active sun's color, after terrain grading and before display conversion/FoV.
    linear += sunGlare() * u_exposure;
    color = displayScene(linear);
    if (u_fovEnabled > 0.5) color = fieldOfView(color, depth);
    fragColor = vec4(color, 1.0);
    // Reuse the scene's hardware depth for weather and tactical occlusion in this same draw.
    DEPTH = depth;
}
