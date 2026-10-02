#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 0) out vec4 fragColor;
in vec2 v_board;
// TERRAIN_PATTERNS
void main() {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0 - terrainGrid(v_board));
}
