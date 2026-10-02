#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_depth;
uniform sampler2D u_fog;
uniform float u_fogEnabled;
uniform vec2 u_sourcePixel;
// HEAT_RADIANCE
// GROUND_LAYER
// ATMOSPHERE_FOV

vec3 hotColor(vec3 color, vec2 uv, float visibility) {
    vec3 radiance = pow(max(color, vec3(0.0)), vec3(2.2));
    float transmission = u_fogEnabled > 0.5 ? texture(u_fog, uv).a : 1.0;
    return radiance * heatWeight(radiance) * transmission * visibility;
}

vec3 hotAt(vec2 uv) {
    return hotColor(texture(u_scene, uv).rgb, uv, 1.0);
}

void main() {
    vec3 radiance = vec3(0.0);
    if (u_fovEnabled > 0.5) {
        // Mask each source texel before downsampling: bilinear RGB can mix hidden lava into a visible neighbour.
        ivec2 first = ivec2(gl_FragCoord.xy) * 4;
        ivec2 last = textureSize(u_scene, 0) - 1;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                ivec2 pixel = min(first + ivec2(x, y), last);
                vec2 uv = (vec2(pixel) + 0.5) * u_sourcePixel;
                float visibility = fovHeatTransmission(uv, texelFetch(u_depth, pixel, 0).r);
                radiance += hotColor(texelFetch(u_scene, pixel, 0).rgb, uv, visibility);
            }
        }
        radiance *= 0.0625;
    } else {
        // Four bilinear taps cover the sixteen source texels without tactical visibility lookups.
        radiance = hotAt(v_uv + u_sourcePixel * vec2(-1, -1));
        radiance += hotAt(v_uv + u_sourcePixel * vec2(1, -1));
        radiance += hotAt(v_uv + u_sourcePixel * vec2(-1, 1));
        radiance += hotAt(v_uv + u_sourcePixel * vec2(1, 1));
        radiance *= 0.25;
    }
    fragColor = vec4(radiance, 1.0);
}
