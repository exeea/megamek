// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform sampler2D u_waterShape;
uniform sampler2D u_waterField;
uniform vec4 u_waterFieldMap;
uniform float u_waterOceanScale;
uniform float u_waterEffects;
uniform float u_metre;
uniform float u_wavePixels;
uniform vec2 u_waveFade;
uniform vec2 u_waterMaterial;
uniform vec3 u_wind;
out vec2 v_waterRest;
// water-wave-functions

void waterDisplace(inout vec4 pos) {
    v_waterRest = pos.xy;
    if (u_waterOceanScale <= 0.0 || u_waterEffects <= 0.0 || u_waterMaterial.y < 0.5) return;
    vec4 field = textureLod(u_waterField, pos.xy * u_waterFieldMap.xy + u_waterFieldMap.zw, 0.0);
    float energy = waterWaveEnergy(pos.xy, field) * u_waterEffects;
    // The same continuous projected footprint at every shared vertex: changing chunk LOD cannot open a crack.
    float distance = max(abs((u_projViewTrans * pos).w), 1.0);
    float pixels = u_wavePixels / distance;
    float mip = clamp(log2(max(1.0, 160.0 / pixels)), 2.5, 5.0);
    vec2 uv = pos.xy * u_waterOceanScale;
    vec3 shape = textureLod(u_waterShape, uv, mip).xyz;
    vec3 swell = textureLod(u_waterShape, WATER_SWELL_TURN * uv * WATER_SWELL_SCALE + .37, max(1.5, mip - 1.2)).xyz;
    shape.xy += (swell.xy * WATER_SWELL_TURN) * (WATER_SWELL_WEIGHT / WATER_SWELL_SCALE);
    shape.z += swell.z * WATER_SWELL_WEIGHT;
    // Fade waves before they become smaller than a pixel; fine slopes remain in the filtered fragment normal.
    float visible = smoothstep(u_waveFade.x, u_waveFade.y, pixels);
    pos.xyz += shape * (u_metre * energy * visible);
}
