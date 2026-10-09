// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Both ground and projected surfaces keep the receiving material's grain and lighting.
void scarMaterial(vec4 scar, vec3 face, vec3 substrate, float normalMaps,
      inout vec3 albedo, inout vec3 normal, inout float cavity, inout float roughness) {
    if (scar.r <= 0.0) return;
    albedo = mix(albedo, substrate * (2.0 * scar.g / scar.r), scar.r);
    roughness = mix(roughness, .96, scar.r);
    roughness = mix(roughness, .30, scar.b);
    cavity *= 1.0 - scar.a * .08;
    if (normalMaps > .5) normal = normalize(mix(normal, face, scar.b * .65));
}
