// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Added to the ground vertex shader; GpuRoads calls roadMaskCoordinates from main.
#ifdef roadMaskFlag
in vec2 a_roadMaskUV;
out vec2 v_roadMaskUV;
#endif

void roadMaskCoordinates() {
    #ifdef roadMaskFlag
    v_roadMaskUV = a_roadMaskUV;
    #endif
}
