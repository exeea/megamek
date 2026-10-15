#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A closed ultra-sublevel void absorbs all light, including environment and grazing-angle reflections.
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
