#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 0) out vec4 fragColor;
in vec3 v_normal;
uniform float u_metre;
uniform float u_normalMaps;
// magma-flow-functions
void main() {
    vec3 face = normalize(v_normal);
    if (!gl_FrontFacing) face = -face;
    vec3 position = v_cloudPosition / u_metre;
    vec3 eye = -viewDirection();
    vec2 current = vec2(0.0);
    float bank = 1.0;
    vec4 waves = vec4(0.0);
    if (u_magmaFieldMap.x > 0.0) {
        vec4 field = texture(u_magmaField, v_cloudPosition.xy * u_magmaFieldMap.xy + u_magmaFieldMap.zw);
        current = (field.ba * 255.0 - 128.0) / 127.0;
        bank = smoothstep(-.01, .08, (field.r * 2.0 - 1.0) * .4);
    }
    if (u_magmaOceanScale > 0.0) {
        waves = texture(u_magmaOcean, v_cloudPosition.xy * u_magmaOceanScale);
        // FFT displacement deforms the cooling skin and exposed melt together; its slopes light the folds.
        // Geometry, bank silhouettes and picking remain on the shared terrain surface.
        position.xy -= waves.zw * 1.5 * bank;
    }
    Volcanic material = magmaSurface(position, face, eye, magmaDrift(face, current, bank), bank, waves);
    fragColor = magmaOutput(material, bank, 1.0);
}
