// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Finite diffuse emitters from the installed board. These approximate area illumination, not obstacle shadows.
uniform int u_lavaCount;
uniform float u_lavaWidth;
uniform vec4 u_lavaLights[32]; // world position, emitted power; kept in sync with GpuLavaLighting.MAX_LIGHTS

vec3 lavaIrradiance(vec3 position, vec3 normal) {
    if (u_lavaCount == 0) return vec3(0.0);
    float width = max(u_lavaWidth, .001);
    float energy = 0.0;
    for (int i = 0; i < u_lavaCount; i++) {
        vec3 toLight = (u_lavaLights[i].xyz - position) / width;
        float distanceSquared = dot(toLight, toLight);
        if (distanceSquared > 4.0) continue;
        // Closest point on a broad emitting disk; the small source lift gives low banks a warm grazing wash.
        float horizontal = length(toLight.xy);
        toLight.xy *= max(0.0, 1.0 - .42 / max(horizontal, .001));
        float squared = dot(toLight, toLight);
        float facing = max(0.0, dot(normal, toLight * inversesqrt(max(squared, .00001))) * .92 + .08);
        float reach = max(0.0, 1.0 - distanceSquared * .25);
        energy += u_lavaLights[i].w * facing * reach * reach / (1.0 + squared * 9.0);
    }
    return vec3(1.8, .24, .025) * (1.0 - exp(-energy));
}
