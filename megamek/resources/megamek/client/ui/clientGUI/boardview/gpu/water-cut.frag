#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Optical section where the board edge cuts the water column.
// water-uniforms
// water-lighting-functions

void main() {
    float palette = u_waterMaterial.x;
    vec3 scatter = waterScatter(waterPalette(palette));
    WaterLighting illumination = waterLighting();
    vec3 color;
    float alpha;
    // A clean section through the water body: the light it scatters at each depth, dimmed as the daylight is
    // absorbed on its way down, hiding more of what lies behind it the deeper it runs. Waves, foam, glints, rain
    // and the grid belong to the surface. Seen from inside, through the surface, it is not drawn.
    if (!gl_FrontFacing) discard;
    vec3 kept = waterTransmission(palette, v_color.g * WATER_DEPTH_RANGE);
    alpha = 1.0 - (1.0 - WATER_MAX_OPACITY) * max(max(kept.r, kept.g), kept.b);
    color = scatter * illumination.light * kept * alpha;
    waterOutput(vec4(color, alpha));
}
