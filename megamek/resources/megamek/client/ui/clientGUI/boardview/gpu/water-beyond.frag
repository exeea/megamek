#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The far side of a board-edge section, drawn before the water blends over it: the water goes on beyond the board.
// A view through the water that leaves the board gets no light back, as from water too deep to show its bed, instead
// of the sky: the surface alone never hides more than WATER_MAX_OPACITY of what lies behind it (water-optics.glsl).
void main() { fragColor = vec4(0.0, 0.0, 0.0, 1.0); }
