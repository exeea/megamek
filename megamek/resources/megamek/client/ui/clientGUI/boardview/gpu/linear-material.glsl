// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted before libGDX's fragment lighting. Preserve authored emission for linear-output.glsl.
#ifdef lightingFlag
vec3 displayEmissive = emissive.rgb;
emissive.rgb = vec3(0.0);
diffuse.rgb = toLinear(diffuse.rgb);
#endif
