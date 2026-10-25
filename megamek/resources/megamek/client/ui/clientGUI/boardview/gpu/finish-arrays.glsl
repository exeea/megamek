// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The finish of every board material the terrain draws from arrays, as Godot reads its StandardMaterial3D: the sculpted
// surfaces at their sculpt layer's index, then the road materials from ROAD_FINISH (GpuTerrain.FINISHES). Perceptual
// roughness, metallic and subsurface scattering share one layer, or are one value each where the material is uniform:
// raw data, independent of normal-map detail. Emission is an emission layer times its tint (the energy, and the colour
// under Godot's multiply operator), plus a constant glow (the colour under Godot's add operator). Both kinds of layer
// follow the sculpt normal/AO layers in the sculpt data array (GpuAssets.finishArrays), so a program that already samples
// that array names it FINISH_LAYERS before this file and spends no texture unit on the finish; any other binds it as
// u_surfaceLayers. Emission layers hold sRGB bytes, decoded after filtering.
// COLOR_SPACE
#ifndef FINISH_LAYERS
uniform sampler2DArray u_surfaceLayers;
#define FINISH_LAYERS u_surfaceLayers
#endif
uniform vec4 u_finishSurface[FINISH_COUNT]; // roughness, metallic, scattering (or means); layer, -1 if uniform
uniform vec4 u_finishEmission[FINISH_COUNT]; // map tint; layer, -1 without an emission map
uniform vec3 u_finishGlow[FINISH_COUNT];     // emission without a map
// Each sculpted surface's heightmap parallax (terrain-materials.glsl, relievedProjection): its depth along U and V in
// repeats (Godot's heightmap_scale / 100, negative where it flips the tangent or binormal), 0 without a heightmap.
uniform vec2 u_finishRelief[ROAD_FINISH];
// Each sculpted surface where distance flattens its maps (terrain-materials.glsl, distantLayer): its normal/AO layer's
// mean occlusion and the length of its mean tangent normal, read once from the layer's smallest mip at load.
uniform vec2 u_finishDistant[ROAD_FINISH];

// A material's finish without its maps: the uniform values (or the map's means) and the constant glow.
Finish meanFinish(int material) { return finishOf(u_finishSurface[material].rgb, u_finishGlow[material]); }

// A material's finish at one point; material is its index in the arrays (half its colour layer).
Finish finishTexel(int material, vec2 uv, mat2 gradient) {
    vec4 surface = u_finishSurface[material], emission = u_finishEmission[material];
    Finish texel = meanFinish(material);
    if (surface.a >= 0.0) {
        vec3 map = textureGrad(FINISH_LAYERS, vec3(uv, surface.a), gradient[0], gradient[1]).rgb;
        texel = finishOf(map, texel.emission);
    }
    if (emission.a >= 0.0) {
        texel.emission += emission.rgb
              * toLinear(textureGrad(FINISH_LAYERS, vec3(uv, emission.a), gradient[0], gradient[1]).rgb);
    }
    return texel;
}

Finish finishTexel(float colorLayer, vec2 uv, mat2 gradient) { return finishTexel(int(colorLayer * .5), uv, gradient); }
