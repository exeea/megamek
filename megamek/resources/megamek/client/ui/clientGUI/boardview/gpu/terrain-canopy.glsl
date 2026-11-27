// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The forest from far above (GpuCanopy): where its trees are too small to draw, their crowns' colour in the ground.
// After terrain-hexes.glsl. The canopy's table lies in the local lights' texture as the hex table does, one texel a
// hex: the crowns' mean colour, linear, and the share of the hex they cover.
#ifdef canopyFlag
// The table's first row, the board's width and height in hexes, and how far the tint has come in: 0 while the trees
// draw, 1 once they are too small to (GpuTerrain#canopyTable).
uniform vec4 u_canopy;

vec4 canopyHex(ivec2 hex) {
    if (any(lessThan(hex, ivec2(0))) || any(greaterThanEqual(hex, ivec2(u_canopy.yz)))) return vec4(0.0);
    return texelFetch(u_lavaLights, lavaTexel(hex.y * int(u_canopy.y) + hex.x) + ivec2(0, int(u_canopy.x)), 0);
}

// The canopy at a point, in metres: between the hexes' centres, along each of the two nearest columns and across
// them, so a wood's edge is as soft as a hex is wide and a lone wooded hex is a round patch. Colour is weighted by
// cover: a bare neighbour lends none.
vec4 canopyTint(vec2 world) {
    const float width = 30.0, height = 30.0 * BOARD_HEX.y / BOARD_HEX.x;
    float across = (world.x - width * .5) / (width * .75);
    float column = floor(across);
    vec4 sum = vec4(0.0);
    for (int side = 0; side < 2; side++) {
        float c = column + float(side);
        float along = -world.y / height - mod(c, 2.0) * .5 - .5;
        float row = floor(along), t = smoothstep(0.0, 1.0, along - row);
        float share = side == 0 ? 1.0 - smoothstep(0.0, 1.0, across - column) : smoothstep(0.0, 1.0, across - column);
        vec4 near = canopyHex(ivec2(int(c), int(row))), far = canopyHex(ivec2(int(c), int(row) + 1));
        sum += share * (vec4(near.rgb * near.a, near.a) * (1.0 - t) + vec4(far.rgb * far.a, far.a) * t);
    }
    return sum.a > 0.0 ? vec4(sum.rgb / sum.a, sum.a) : vec4(0.0);
}
#endif
