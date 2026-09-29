// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared reflected light and unshadowed heat for both dedicated magma programs.
vec4 magmaOutput(Volcanic material, float bank, float strength) {
    vec3 albedo = toLinear(material.albedo);
    vec3 ambient = vec3(1.0), direct = vec3(0.0), sheen = vec3(0.0);
#ifdef lightingFlag
    surfaceLighting(material.normal, 0.0, material.surface.g, ambient, direct, sheen);
    ambient *= material.surface.b;
#endif
    albedo *= ambient + direct;
    vec2 heat = material.heat * mix(.72, 1.0, bank);
    return vec4(toDisplay(albedo + sheen + magmaEmission(heat, strength)), 1.0);
}
