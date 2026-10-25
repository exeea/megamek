// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform float u_gridShade;
// BOARD_HEX

// Shared by physical ground and its living cover: the grid stays visible through the grass. Position in hex widths.
float terrainGrid(vec2 position) {
    // A hex's height in widths, and the run of its diagonal edges per unit of rise.
    const float HEIGHT = BOARD_HEX.y / BOARD_HEX.x, SLOPE = .5 / HEIGHT;
    float column = floor((position.x - .5) / .75 + .5);
    float interior = -1.0;
    for (int i = -1; i <= 1; i++) {
        float col = column + float(i);
        float row = floor(-position.y / HEIGHT - mod(col, 2.0) * .5);
        vec2 center = vec2(col * .75 + .5, -(row + mod(col, 2.0) * .5 + .5) * HEIGHT);
        vec2 p = abs(position - center);
        float edge = min(HEIGHT * .5 - p.y, (.5 - p.x - p.y * SLOPE) * inversesqrt(1.0 + SLOPE * SLOPE));
        interior = max(interior, edge);
    }
    float aa = max(fwidth(interior), .0006);
    return mix(u_gridShade * u_gridShade, 1.0, smoothstep(.0035 - aa, .0035 + aa, interior));
}
