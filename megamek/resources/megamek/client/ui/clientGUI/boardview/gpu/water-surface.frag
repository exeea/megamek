#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Open water; premultiplied reflection and in-scattered light over the visible bed.
#define waterSurfaceFlag
in vec3 v_waterRest;
// water-uniforms
// water-lighting-functions
// biome-water-functions
// water-pool-functions

void main() {
    vec4 habitat, mixture;
    waterHabitat(habitat, mixture);
    vec3 tint = waterTint(mixture);
    bool procedural = u_waterMaterial.y > 0.5;
    vec3 authored = procedural ? vec3(0.0) : texture(u_diffuseTexture, v_diffuseUV).rgb;
    WaterLighting illumination = waterLighting();
    waterOutput(waterPool(false, habitat, mixture, tint, authored, illumination));
}
