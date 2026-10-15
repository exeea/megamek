// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifndef cloudVolumeFlag
in vec3 v_cloudPosition;
#endif
#ifdef cloudShadowFlag
uniform sampler2D u_cloudShadow;
uniform mat4 u_cloudProjection;
uniform float u_cloudEnabled;

float cloudTransmission(vec3 position) {
    if (u_cloudEnabled < 0.5) return 1.0;
    vec3 uv = (u_cloudProjection * vec4(position, 1.0)).xyz;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0 || uv.z >= 1.0) return 1.0;
    // The atlas integrates the layer from its base. Surfaces within/above it receive only the remaining column.
    return pow(max(textureLod(u_cloudShadow, uv.xy, 0.0).r, 0.003), clamp(1.0 - uv.z, 0.0, 1.0));
}
#endif
