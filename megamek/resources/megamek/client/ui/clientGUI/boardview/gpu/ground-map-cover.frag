#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// What lies on the ground map's ground (GpuGroundMap): a road, paint, water, an object's footprint. Nothing grows
// through it: blended over the map, its alpha takes that share of every kind's growth away, so a road's shoulder or
// a decal's soft edge thins the cover as it is drawn.
uniform int u_mask;
uniform sampler2D u_maskTexture;
// A road's mask (u_mask 2) lies in the board's mask array (GpuRoadMaskAtlas): its coverage in one BC4 channel.
uniform sampler2DArray u_roadMasks;
uniform float u_opacity;
in vec2 v_maskUV;
flat in float v_maskLayer;
in vec2 v_roadMaskUV;
flat in float v_wear;
flat in float v_track;
flat in vec2 v_trackCell;
layout(location = 0) out vec4 fragColor;
// A loose road's track across it: the sizes and the fringe its coat draws with (terrain-road.frag).
// ROAD_TRACK

void main() {
    // A road covers only inside its mask, as its coat draws (terrain-road.frag): past it lie other masks.
    bool outside = any(lessThan(v_roadMaskUV, vec2(0.0))) || any(greaterThan(v_roadMaskUV, vec2(1.0)));
    float mask = u_mask == 0 ? 1.0 : u_mask == 2 ? (outside ? 0.0 : texture(u_roadMasks, vec3(v_maskUV, v_maskLayer)).r)
          : texture(u_maskTexture, v_maskUV).a;
    // A paved carriageway (its coat carries the wear, ground-map.vert) clears its ground to its mask's half-coverage
    // line: a coarser mask softens its edge, and the grass would follow that softness onto the asphalt's edge.
    if (v_wear > .5) { mask = smoothstep(.35, .65, mask); }
    if (v_track > .5 && !outside) {
        // A loose road's coat reaches a shoulder past what it shows: its ground clears as far as its stone or soil lies,
        // and thins out over the fringe beyond, as the coat's own edge does (terrain-road.frag looseTrack, dirtTrack),
        // without their grain: the edge unbroken, the end at its mean. Gravel: its graded edge. Dirt: its two ruts and
        // the loose soil beside them; grass grows on its crown.
        float across = texture(u_roadMasks, vec3(v_maskUV + v_trackCell, v_maskLayer)).r, ends = 2.0 * mask - .5;
        float rut = across * RUT_RANGE - RUT_OFFSET;
        mask = v_track > 1.5 ? trackFringe(GRAVEL_EDGE - across * TRACK_HALF, ends)
              : max(rutPressed(abs(rut)) * step(1.0, ends), trackFringe(dirtSoil(rut, vec2(.5), vec2(.5), .5), ends));
    }
    fragColor = vec4(0.0, 0.0, 0.0, u_opacity * mask);
}
