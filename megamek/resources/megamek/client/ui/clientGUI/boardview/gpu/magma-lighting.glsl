// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Matte cooling skin and unshadowed heat for both dedicated magma programs.
vec4 magmaOutput(Volcanic material, vec3 geometric, float bank, float strength) {
    vec3 albedo = materialReflectance(material.albedo);
    vec3 ambient = vec3(1.0), direct = vec3(0.0), sheen = vec3(0.0);
#ifdef lightingFlag
    // Both programs call this unconditionally at the end of main, and neither discards.
    surfaceVariance = normalVariance(dFdx(material.normal), dFdy(material.normal));
    material.normal = shadingNormal(material.normal, geometric, -viewDirection());
    surfaceLighting(material.normal, geometric, 0.0, material.surface.g, ambient, direct, sheen);
    // Lava has no reflective coat. Keep cold-skin diffuse lighting and its shadow/occlusion response;
    // the unused specular lobe can be eliminated by the compiler without another lighting implementation.
    sheen = vec3(0.0);
    ambient *= material.surface.b;
#endif
    albedo *= ambient + direct;
    return vec4(albedo + sheen + magmaRadiance(material, bank, strength), 1.0);
}
