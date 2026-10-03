#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Authored ground artwork. DefaultShader owns the uniforms, material binding and directional shadow map.

in vec2 v_diffuseUV;
in vec3 v_normal;
in vec4 v_color;
uniform sampler2D u_diffuseTexture;
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
uniform float u_normalMaps;
#endif
uniform float u_groundResponse;
// ground-surface-functions

void main() {
    vec3 albedo = texture(u_diffuseTexture, v_diffuseUV).rgb * v_color.rgb;
    vec3 normal = groundNormal(v_diffuseUV, 1.0);
    float wet = u_wetness * step(0.0, u_groundResponse) * smoothstep(0.2, 0.8, v_normal.z);
    float response = max(0.0, u_groundResponse);
    albedo *= 1.0 - wet * mix(0.175, 0.10, response);
    float puddle = groundPuddle(wet, response);
    groundFilm(albedo, normal, puddle);
    fragColor = vec4(groundLighting(albedo, normal, wet, response, puddle, -1.0, 1.0), 1.0);
}
