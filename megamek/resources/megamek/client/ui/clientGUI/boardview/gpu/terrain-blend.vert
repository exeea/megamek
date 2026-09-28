// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Added to the sculpted terrain vertex shader; GpuSurfaceBlend calls terrainBlendWeights from main.
#ifdef terrainBlendFlag
in vec3 a_coverWeights;
out vec3 v_coverWeights;
#endif

void terrainBlendWeights() {
    #ifdef terrainBlendFlag
    v_coverWeights = a_coverWeights;
    #endif
}
