// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared inverse-FFT centering; each cascade's power-of-two block wraps at every neighbour lookup.
uniform sampler2D u_source; // packed spatial fields from ocean-spectrum, each times the centering sign
uniform int u_size;         // texels along a side of one cascade

vec4 oceanSample(int cascade, ivec2 texel) {
    float parity = ((texel.x + texel.y) & 1) == 0 ? 1.0 : -1.0;
    ivec2 wrapped = texel & ivec2(u_size - 1);
    return texelFetch(u_source, ivec2(wrapped.x + cascade * u_size, wrapped.y), 0) * parity;
}
