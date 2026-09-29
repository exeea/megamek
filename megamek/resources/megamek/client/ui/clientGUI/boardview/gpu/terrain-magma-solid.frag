#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 0) out vec4 fragColor;
in vec3 v_normal;
uniform float u_metre;
uniform float u_normalMaps;
uniform float u_magmaMode; // solid crust 0, cooled bank 3
// magma-solid-functions
void main() {
    vec3 face = normalize(v_normal);
    if (!gl_FrontFacing) face = -face;
    vec3 position = v_cloudPosition / u_metre;
    vec3 eye = -viewDirection();
    Volcanic material = magmaSurface(position, face, eye, vec3(0.0), 1.0, vec4(0.0));
    fragColor = magmaOutput(material, 1.0, u_magmaMode > 2.5 ? .10 : 1.0);
}
