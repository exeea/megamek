// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared inverse-FFT centering; the power-of-two texture wraps at every neighbour lookup.
uniform sampler2D u_source; // packed spatial fields from ocean-spectrum, each times the centering sign
uniform int u_size;

vec4 oceanSample(ivec2 texel) {
    float parity = ((texel.x + texel.y) & 1) == 0 ? 1.0 : -1.0;
    return texelFetch(u_source, texel & ivec2(u_size - 1), 0) * parity;
}
