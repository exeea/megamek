// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted after libGDX's ambient calculation in vertex main; light-model.glsl supplies hemisphere.
#if defined(ambientFlag) && defined(normalFlag)
vec3 sunOnGround = vec3(0.0);
#if numDirectionalLights > 0
sunOnGround = u_dirLights[0].color * max(0.0, -u_dirLights[0].direction.z);
#endif
v_groundBounce = hemisphere(vec3(0.0), sunOnGround, GROUND_ALBEDO, normal.z);
ambientLight = hemisphere(ambientLight, sunOnGround, GROUND_ALBEDO, normal.z);
#endif
