// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// After terrain-damage-mask.glsl, in the sculpt and the terrain's model programs. A scar patch (GpuSurfaceScars) draws
// with its receiver's program: u_surfaceScar is 1 for the patch's draws and 0 for the receiver's own, and v_scarUV holds
// the patch's page UV and image layer. SURFACE_SCARS tells later code (terrain-ice.glsl) the helpers are here.
#define SURFACE_SCARS
in vec3 v_scarUV;
uniform float u_surfaceScar;
vec4 surfaceScarMask = vec4(0.0);
vec4 surfaceScar() {
    vec2 uv = (v_scarUV.xy * GROUND_DAMAGE_INNER + GROUND_DAMAGE_BORDER) / GROUND_DAMAGE_TILE;
    return textureGrad(u_groundDamage, vec3(uv, v_scarUV.z), dFdx(uv), dFdy(uv));
}

// False where a patch's image leaves its receiver bare: its draw then ends with alpha 0, which its blending
// (GpuTerrain.bindMaterial) leaves unseen, and no discard, which would slow every draw of the program. Call it after
// the shading frame's derivatives.
bool surfaceScarCovers() {
    if (u_surfaceScar < .5) return true;
    surfaceScarMask = surfaceScar();
    return surfaceScarMask.r > 0.0;
}

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
