// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 14) in vec4 a_coverRoot;
uniform float u_biomeKind;
uniform float u_biomeLod;
uniform float u_coverPixels;
uniform float u_levelHeight;
uniform float u_metre;
uniform float u_worldMetre;
uniform float u_rainTime;
uniform vec3 u_wind;
varying vec2 v_coverData;
varying vec2 v_coverRoot;
varying vec2 v_coverFade;

void biomePlant(vec3 point, vec3 sourceNormal, vec4 pigment, out vec3 position, out vec3 normal, out vec4 color) {
    float seed = a_coverRoot.w, angle = seed * 97.71;
    mat2 turn = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    bool crop = u_biomeKind < 1.5;
    float height = crop ? u_levelHeight * mix(.43, .52, seed) : u_metre * mix(.90, 1.65, fract(seed * 19.37));
    // Most clumps are low sedge; emergent reeds gather among them. Basal leaves retain their full width.
    if (!crop && pigment.a > .6 && fract(seed * 71.13) < .55) point *= vec3(.8, .8, .38);
    vec3 root = a_coverRoot.xyz;
    float pixels = u_coverPixels / max(.001, abs((u_projViewTrans * vec4(root, 1.0)).w));
    vec3 coverage = smoothstep(vec3(@START0@, @START1@, @START2@), vec3(@FULL0@, @FULL1@, @FULL2@), vec3(pixels));
    v_coverFade = u_biomeLod < .5 ? vec2(0.0, coverage.x)
          : u_biomeLod < 1.5 ? coverage.xy : coverage.yz;
    vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
    float gust = sin(dot(root.xy / u_metre, vec2(.21, .12)) - u_rainTime * 1.6);
    vec2 flex = wind * (.012 + u_wind.z * ((crop ? .055 : .080) + .025 * gust));
    vec2 bend = flex * point.z * point.z;
    position = root + vec3(turn * point.xy + bend, point.z) * height;
    // A perspective tile can straddle a handoff. Collapse its invisible roots before rasterization.
    if (v_coverFade.y <= v_coverFade.x) position = root;
    vec2 leaf = turn * sourceNormal.xy;
    normal = normalize(vec3(leaf, sourceNormal.z - dot(flex * 2.0 * point.z, leaf)));
    color = vec4(pigment.rgb * mix(.80, 1.17, fract(seed * 31.7)), 1.0);
    if (!crop) color.rgb *= mix(.46, 1.08, smoothstep(0.0, .6, point.z));
    v_coverData = vec2(height, clamp(point.z, 0.0, 1.0));
    v_coverRoot = root.xy / u_worldMetre;
}
