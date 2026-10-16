// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Finite diffuse emitters from the installed board. These approximate area illumination, not obstacle shadows.
// GpuLavaLighting bins them on the board plane; a pixel evaluates only the emitters listed for its grid cell.
uniform sampler2D u_lavaLights; // RGBA32F, 1024 texels a row: per cell (first entry texel, count), then per entry
                                // world position + emitted power, linear emission RGB + source size
uniform vec3 u_lavaGrid;        // world XY of the grid's corner, cells per world unit
uniform ivec2 u_lavaCells;      // grid columns and rows; zero without emitters

ivec2 lavaTexel(int index) { return ivec2(index & 1023, index >> 10); }

vec3 lavaIrradiance(vec3 position, vec3 normal) {
    ivec2 cell = ivec2(floor((position.xy - u_lavaGrid.xy) * u_lavaGrid.z));
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, u_lavaCells))) return vec3(0.0);
    vec2 list = texelFetch(u_lavaLights, lavaTexel(cell.y * u_lavaCells.x + cell.x), 0).xy;
    int first = int(list.x), end = first + 2 * int(list.y);
    if (first == end) return vec3(0.0);
    float energy = 0.0;
    vec3 illumination = vec3(0.0);
    for (int at = first; at < end; at += 2) {
        ivec2 texel = lavaTexel(at);
        vec4 light = texelFetch(u_lavaLights, texel, 0);
        vec4 color = texelFetch(u_lavaLights, texel + ivec2(1, 0), 0);
        float width = max(color.w, .001);
        vec3 toLight = (light.xyz - position) / width;
        float distanceSquared = dot(toLight, toLight);
        if (distanceSquared > 4.0) continue;
        // Closest point on a broad emitting disk; the small source lift gives low banks a warm grazing wash.
        float horizontal = length(toLight.xy);
        toLight.xy *= max(0.0, 1.0 - .42 / max(horizontal, .001));
        float squared = dot(toLight, toLight);
        float facing = max(0.0, dot(normal, toLight * inversesqrt(max(squared, .00001))) * .92 + .08);
        float reach = max(0.0, 1.0 - distanceSquared * .25);
        float contribution = light.w * facing * reach * reach / (1.0 + squared * 9.0);
        energy += contribution;
        illumination += color.rgb * contribution;
    }
    // Keep the existing lava response, while allowing warm fungal gills and cyan mycelium to mix naturally.
    return illumination * ((1.0 - exp(-energy)) / max(energy, .00001));
}
