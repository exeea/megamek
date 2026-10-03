#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Wireframe view: every edge of the opaque board geometry in one green. Cutout cards and sprites keep their full
// quads, so their lines and hidden-line fill show the actual triangles.

void main() {
    fragColor = vec4(0.3, 1.0, 0.45, 1.0);
}
