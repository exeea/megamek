#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// What lies on the ground map's ground (GpuGroundMap): a road, paint, water, an object's footprint. Nothing grows
// through it: blended over the map, its alpha takes that share of every kind's growth away, so a road's shoulder or
// a decal's soft edge thins the cover as it is drawn.
uniform int u_mask;
uniform sampler2D u_maskTexture;
// A road's mask (u_mask 2) lies in the board's mask array (GpuRoadMaskAtlas).
uniform sampler2DArray u_roadMasks;
uniform float u_opacity;
in vec2 v_maskUV;
flat in float v_maskLayer;
in vec2 v_roadMaskUV;
layout(location = 0) out vec4 fragColor;

void main() {
    // A road covers only inside its mask, as its coat draws (terrain-road.frag): past it lie other masks.
    bool outside = any(lessThan(v_roadMaskUV, vec2(0.0))) || any(greaterThan(v_roadMaskUV, vec2(1.0)));
    float mask = u_mask == 0 ? 1.0 : u_mask == 2 ? (outside ? 0.0 : texture(u_roadMasks, vec3(v_maskUV, v_maskLayer)).a)
          : texture(u_maskTexture, v_maskUV).a;
    fragColor = vec4(0.0, 0.0, 0.0, u_opacity * mask);
}
