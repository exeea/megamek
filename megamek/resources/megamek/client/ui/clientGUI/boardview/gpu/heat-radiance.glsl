// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Only strong warm HDR light bypasses the time-of-day surface grade. White reflections stay unchanged.
// This is a radiance selection, not a material ID; sufficiently hot fire can share the camera halo.
float heatWeight(vec3 radiance) {
    float hot = smoothstep(2.0, 3.0, radiance.r);
    float warm = 1.0 - smoothstep(0.18, 0.40, radiance.b / max(radiance.r, 0.0001));
    float red = smoothstep(0.80, 1.0, radiance.r / max(radiance.g, 0.0001));
    return hot * warm * red;
}
