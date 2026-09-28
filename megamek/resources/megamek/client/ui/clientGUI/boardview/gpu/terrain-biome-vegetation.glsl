// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 14) in vec4 a_coverRoot;
uniform float u_biomeKind;
uniform float u_biomeLod;
uniform float u_coverPixels;
uniform float u_levelHeight;
uniform float u_metre;
out vec2 v_coverData;
out vec2 v_coverRoot;
out vec2 v_coverFade;

void biomePlant(vec3 point, vec3 sourceNormal, vec4 pigment, out vec3 position, out vec3 normal, out vec4 color) {
    float seed = a_coverRoot.w, angle = seed * 97.71;
    mat2 turn = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    bool crop = u_biomeKind < 1.5;
    float height = crop ? u_levelHeight * mix(.43, .52, seed) : u_metre * mix(.90, 1.65, fract(seed * 19.37));
    float top = crop ? 1.04 : 1.1;
    // Vary the clump's proportions, with every root fixed to its supporting surface.
    if (!crop && fract(seed * 71.13) < .55) {
        point *= vec3(.8, .8, .60);
        sourceNormal /= vec3(.8, .8, .60);
        top *= .60;
    }
    vec3 root = a_coverRoot.xyz;
    float pixels = u_coverPixels / max(.001, abs((u_projViewTrans * vec4(root, 1.0)).w));
    vec3 coverage = smoothstep(vec3(@START0@, @START1@, @START2@), vec3(@FULL0@, @FULL1@, @FULL2@), vec3(pixels));
    v_coverFade = u_biomeLod < .5 ? vec2(0.0, coverage.x)
          : u_biomeLod < 1.5 ? coverage.xy : coverage.yz;
    vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
    float gust = vegetationGust(root.xy);
    // Stiffer crops bow less than flexible marsh leaves. Strength scales both lean and angular excursion.
    float flex = u_wind.z * (crop ? .78 + .22 * gust : 1.0 + .28 * gust);
    // Crop cards share one stalk angle, preserving the plant image through every mesh LOD.
    // Marsh leaves form a flexible arch, with no displacement at the grounded base.
    float angleAtHeight = crop ? flex : flex * point.z / top;
    float sine = sin(angleAtHeight), cosine = cos(angleAtHeight);
    vec2 bend = wind * sine * point.z;
    float lifted = cosine * point.z;
    // Bend the centreline without stretching its tip away from the root; retain the clump's cross-section.
    position = root + vec3(turn * point.xy + bend, lifted) * height;
    vec2 leaf = turn * sourceNormal.xy;
    float curvature = crop ? 0.0 : angleAtHeight;
    vec2 tangentXY = wind * (sine + curvature * cosine);
    float tangentZ = cosine - curvature * sine;
    normal = vec3(leaf * tangentZ, sourceNormal.z - dot(tangentXY, leaf));
    if (u_biomeLod > 1.5) {
        // At a few pixels a crop stalk or clump needs one quad. Face the camera so rotating or looking straight down
        // cannot turn it edge-on. Keep the base horizontal and lift the top out of its supporting terrain.
        vec3 right = normalize(vec3(u_projViewTrans[0].x, u_projViewTrans[1].x, 0.0));
        vec3 up = normalize(vec3(u_projViewTrans[0].y, u_projViewTrans[1].y, u_projViewTrans[2].y));
        up = normalize(vec3(up.xy, max(up.z, .35)));
        position = root + (right * point.x + up * lifted + vec3(bend, 0.0)) * height;
        normal = cross(right, up * tangentZ + vec3(tangentXY, 0.0));
    }
    normal = dot(normal, normal) > .00000001 ? normalize(normal) : vec3(0.0, 0.0, 1.0);
    // A perspective tile can straddle a handoff. Collapse its invisible roots before rasterization.
    if (v_coverFade.y <= v_coverFade.x) position = root;
    color = vec4(pigment.rgb * mix(.80, 1.17, fract(seed * 31.7)), 1.0);
    if (!crop) color.rgb *= mix(.75, 1.04, smoothstep(0.0, .6, point.z));
    v_coverData = vec2(height, clamp(point.z, 0.0, 1.0));
    v_coverRoot = root.xy / u_worldMetre;
}
