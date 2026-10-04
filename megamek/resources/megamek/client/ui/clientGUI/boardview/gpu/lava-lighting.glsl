// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Finite diffuse emitters from the installed board. These approximate area illumination, not obstacle shadows.
uniform int u_lavaCount;
uniform vec4 u_lavaLights[32]; // world position, emitted power; kept in sync with GpuLavaLighting.MAX_LIGHTS
uniform vec4 u_lavaColors[32]; // linear emission RGB, source size; lava and fungi share the same light budget

vec3 lavaIrradiance(vec3 position, vec3 normal) {
    if (u_lavaCount == 0) return vec3(0.0);
    float energy = 0.0;
    vec3 illumination = vec3(0.0);
    for (int i = 0; i < u_lavaCount; i++) {
        float width = max(u_lavaColors[i].w, .001);
        vec3 toLight = (u_lavaLights[i].xyz - position) / width;
        float distanceSquared = dot(toLight, toLight);
        if (distanceSquared > 4.0) continue;
        // Closest point on a broad emitting disk; the small source lift gives low banks a warm grazing wash.
        float horizontal = length(toLight.xy);
        toLight.xy *= max(0.0, 1.0 - .42 / max(horizontal, .001));
        float squared = dot(toLight, toLight);
        float facing = max(0.0, dot(normal, toLight * inversesqrt(max(squared, .00001))) * .92 + .08);
        float reach = max(0.0, 1.0 - distanceSquared * .25);
        float contribution = u_lavaLights[i].w * facing * reach * reach / (1.0 + squared * 9.0);
        energy += contribution;
        illumination += u_lavaColors[i].rgb * contribution;
    }
    // Keep the existing lava response, while allowing warm fungal gills and cyan mycelium to mix naturally.
    return illumination * ((1.0 - exp(-energy)) / max(energy, .00001));
}
