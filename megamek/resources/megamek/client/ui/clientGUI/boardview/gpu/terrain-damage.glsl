// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Authored luminance and transparency survive into the material; the source is not reduced to a soot silhouette.
void groundDamage(vec3 world, vec3 face, float grass, float sand, float exposed,
      inout vec3 albedo, inout vec3 normal, inout float cavity, inout float roughness) {
    #ifdef surfaceScarFlag
    vec4 scar = surfaceScarMask * exposed;
    #else
    vec4 scar = groundDamageAt(v_cloudPosition.xy) * exposed;
    #endif
    float coverage = scar.r;
    // Keep the receiving material's grain at its own scale instead of covering it with a flat grey image.
    // Grass reveals this terrain family's existing soil map. Capture gradients before the scar branch.
    vec2 soilUV = vec2(world.x, -world.y) / u_sculptTiles.w;
    mat2 soilGradient = mat2(dFdx(soilUV), dFdy(soilUV));
    if (coverage <= 0.0) return;
    vec3 substrate = albedo;
    if (grass > .5) substrate = materialTexel(u_sculptLayers.w, soilUV, soilGradient).rgb;
    // The authored broad char/debris pattern darkens that material; alpha supplies the irregular fade.
    scarMaterial(scar, face, substrate, u_normalMaps, albedo, normal, cavity, roughness);
}
