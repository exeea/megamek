#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Open water; premultiplied reflection and in-scattered light over the visible bed.
#define waterSurfaceFlag
in vec3 v_waterRest;
in float v_waterCrest;
uniform sampler2D u_waterNearest; // GpuWaterDepth: this frame's nearest displaced water surface
// water-uniforms
// water-lighting-functions
// biome-water-functions
// water-pool-functions

void main() {
    // Only the nearest wave: at grazing angles a crest hides the water behind it.
    if (gl_FragCoord.z > texelFetch(u_waterNearest, ivec2(gl_FragCoord.xy), 0).r + 1e-6) discard;
    vec4 habitat, mixture;
    waterHabitat(habitat, mixture);
    vec3 tint = waterTint(mixture);
    bool procedural = u_waterMaterial.y > 0.5;
    vec3 authored = procedural ? vec3(0.0) : texture(u_diffuseTexture, v_diffuseUV).rgb;
    WaterLighting illumination = waterLighting();
    waterOutput(waterPool(false, habitat, mixture, tint, authored, illumination));
}
