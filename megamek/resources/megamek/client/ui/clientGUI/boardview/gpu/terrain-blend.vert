// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Added to the sculpted terrain vertex shader; GpuSurfaceBlend calls terrainBlendWeights from main.
#ifdef terrainBlendFlag
// A height-field hex's weights come from its own attributes instead (field-morph.glsl, fieldCoverWeights).
#ifndef FIELD_COVER_LOCATION
in vec4 a_coverWeights;
in float a_coverInterpolation;
in vec4 a_coverFoot;
#endif
out vec4 v_coverWeights;
out float v_coverInterpolation;
out vec4 v_coverFoot;
#endif

void terrainBlendWeights() {
    #if defined(terrainBlendFlag) && !defined(FIELD_COVER_LOCATION)
    v_coverWeights = a_coverWeights;
    v_coverInterpolation = a_coverInterpolation;
    v_coverFoot = a_coverFoot;
    #endif
}
