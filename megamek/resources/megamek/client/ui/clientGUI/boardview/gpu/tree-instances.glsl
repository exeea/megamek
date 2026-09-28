// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by the colour and depth vertex shaders. Fixed instance locations keep their vertex array compatible.
layout(location = 14) in vec4 a_instance0;
layout(location = 15) in vec4 a_instance1;

vec3 instanceTurn(vec3 v) {
    return vec3(a_instance1.x * v.x - a_instance1.y * v.y, a_instance1.y * v.x + a_instance1.x * v.y, v.z);
}

vec3 instancePosition(vec3 p) {
    return instanceTurn(p * vec3(a_instance0.w, a_instance0.w, a_instance1.z)) + a_instance0.xyz;
}

vec3 instanceNormal(vec3 n) {
    return normalize(instanceTurn(n / vec3(a_instance0.w, a_instance0.w, a_instance1.z)));
}
