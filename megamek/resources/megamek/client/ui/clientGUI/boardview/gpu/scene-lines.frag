// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#version 330 core
in vec4 v_color;
layout(location=0) out vec4 fragColor;
// SCENE_RADIANCE

void main() {
    fragColor = vec4(storeScene(v_color.rgb), v_color.a);
}
