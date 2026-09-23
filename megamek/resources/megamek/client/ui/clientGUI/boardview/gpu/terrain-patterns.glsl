// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform float u_gridShade;

// Color uses the continuous field; blade density alone thresholds it into meadow clearings.
float meadowField(vec2 metres) {
    vec2 warp = vec2(sin(metres.y * .31), cos(metres.x * .29)) * .025;
    float broad = texture2D(u_rainNoise, metres * .009 + warp).g;
    float detail = texture2D(u_rainNoise, metres * .027 + .317).b;
    return broad * .7 + detail * .3;
}

float meadowCover(vec2 metres) {
    return smoothstep(.20, .66, meadowField(metres));
}

// Shared by physical ground and its living cover: the grid stays visible through the grass.
float terrainGrid(vec2 position) {
    float column = floor((position.x - .5) / .75 + .5);
    float interior = -1.0;
    for (int i = -1; i <= 1; i++) {
        float col = column + float(i);
        float row = floor(-position.y * (84.0 / 72.0) - mod(col, 2.0) * .5);
        vec2 center = vec2(col * .75 + .5, -(row + mod(col, 2.0) * .5 + .5) * (72.0 / 84.0));
        vec2 p = abs(position - center);
        float edge = min(36.0 / 84.0 - p.y, (.5 - p.x - p.y * (21.0 / 36.0)) / 1.1577);
        interior = max(interior, edge);
    }
    float aa = max(fwidth(interior), .0006);
    return mix(u_gridShade * u_gridShade, 1.0, smoothstep(.0035 - aa, .0035 + aa, interior));
}
