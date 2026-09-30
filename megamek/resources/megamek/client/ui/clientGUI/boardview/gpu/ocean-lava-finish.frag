#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Lava: retain slopes and horizontal displacement for the moving cooling skin.
// OCEAN_FINISH
void main() {
    fragColor = oceanSample(0, ivec2(gl_FragCoord.xy));
}
