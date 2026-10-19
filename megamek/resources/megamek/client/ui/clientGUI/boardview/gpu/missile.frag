#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec4 v_color;
// Imported GLB factors and vertex colors are linear before the batch multiplies and interpolates them.
void main() { fragColor = v_color; }
