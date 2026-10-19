// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Exact sRGB transfer for authored RGB and final display output. Texture color samples are already linear.
// Never apply to alpha, normals, roughness, metallic, heights or other numerical data. Matches GpuColor.
#ifndef BOARD_COLOR_SPACE
#define BOARD_COLOR_SPACE
vec3 toLinear(vec3 c) {
    c = max(c, vec3(0.0));
    return mix(c / 12.92, pow((c + .055) / 1.055, vec3(2.4)), step(vec3(.04045), c));
}

vec3 toDisplay(vec3 c) {
    c = max(c, vec3(0.0));
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - .055, step(vec3(.0031308), c));
}
#endif
