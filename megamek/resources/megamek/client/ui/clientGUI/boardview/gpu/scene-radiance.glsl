// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifndef SCENE_RADIANCE_GLSL
#define SCENE_RADIANCE_GLSL
// A change of linear units, not a transfer curve. Alpha and all numerical material/depth data stay unchanged.
const float SCENE_STORAGE_SCALE = RADIANCE_SCALE_VALUE;
vec3 storeScene(vec3 radiance) { return radiance * SCENE_STORAGE_SCALE; }
vec3 loadScene(vec3 stored) { return stored / SCENE_STORAGE_SCALE; }
#endif
