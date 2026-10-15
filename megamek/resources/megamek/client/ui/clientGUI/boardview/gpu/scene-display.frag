#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Isolated shader previews use the board's final highlight shoulder and display encoding.
layout(location = 0) out vec4 fragColor;
in vec2 v_uv;
uniform sampler2D u_scene;
// SCENE_RADIANCE
// ATMOSPHERE_GRADE

void main() {
    vec4 scene = texture(u_scene, v_uv);
    fragColor = vec4(displayScene(loadScene(scene.rgb) * u_exposure), scene.a);
}
