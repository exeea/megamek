// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Render-only wave geometry (GpuWaterWaves): the longest cascade lifts the surface and gathers it into sharp crests.
// Picking, unit support and the bed stay on the canonical level surface. The board-edge section shares this program,
// so its top follows the surface it meets.
invariant gl_Position;
uniform sampler2D u_waterShape;  // GpuOcean: choppy XY displacement and height in metres, crest compression
uniform sampler2D u_waterField;
uniform vec4 u_waterFieldMap;
uniform vec3 u_waterOceanScale;  // world XY to each cascade's UV; zero without the simulation
uniform float u_waterEffects;
uniform float u_metre;
uniform float u_levelHeight;
uniform float u_wavePixels;
uniform vec2 u_waveFade;
uniform vec2 u_waterMaterial;
uniform vec3 u_waterWind;
out vec3 v_waterRest;
out float v_waterCrest;          // how tightly the swell gathers into a crest here: 0 flat, 1 about to fold
// water-wave-functions

void waterDisplace(inout vec4 pos) {
    v_waterRest = pos.xyz;
    v_waterCrest = 0.0;
    if (u_waterOceanScale.x <= 0.0 || u_waterEffects <= 0.0 || u_waterMaterial.y < 0.5) return;
#ifdef colorFlag
    // Colour G is a board-edge section's depth below the surface: only its top, like the surface itself, is at zero.
    float attached = 1.0 - step(1e-4, a_color.g);
#else
    float attached = 1.0;
#endif
    vec4 field = textureLod(u_waterField, pos.xy * u_waterFieldMap.xy + u_waterFieldMap.zw, 0.0);
    float share = waterWaveEnergy(pos.xy, field, waterFetch(pos.xy)).x * u_waterEffects * attached;
    // On-screen pixels per hex width. Nothing here depends on the chunk, its LOD or the program: every mesh that
    // shares a vertex moves it identically, so neither LOD seams nor the depth prepass can open a crack.
    float pixels = u_wavePixels / max(abs((u_projViewTrans * pos).w), 1.0);
    // Filter out crests shorter than about 1.4 grid spacings of the mesh used at this distance.
    float filterMetres = max(3.5, 5.5 * pow(64.0 / max(pixels, 1.0), 0.6));
    float mip = clamp(log2(filterMetres * u_waterOceanScale.x * u_metre * OCEAN_SIZE), 0.0, 7.0);
    float visible = smoothstep(u_waveFade.x, u_waveFade.y, pixels);
    vec4 shape = textureLod(u_waterShape, pos.xy * u_waterOceanScale.x, mip);
    pos.xyz += shape.xyz * (u_metre * share * visible);
    v_waterCrest = shape.w * share;
}
