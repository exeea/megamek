// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Added to the ground vertex shader; GpuRoads calls roadMaskCoordinates from main.
#ifdef roadMaskFlag
in vec2 a_roadMaskUV;
in vec4 a_roadMaskRegion;
// The supporting terrain's shape inputs. Coats keep their own colour and texture coordinates for shading.
#ifdef tessellationFlag
in vec4 a_tessColor;
in vec2 a_tessUV;
in float a_tessLift;
#endif
out vec2 v_roadMaskUV;
flat out vec4 v_roadMaskRegion;
#endif
#ifdef roadCoatFlag
// A merged coat's maps, finish and wet response (GpuRoads#coat), the same on all its vertices.
flat out vec4 v_roadCoat;
#endif

void roadMaskCoordinates() {
    #ifdef roadMaskFlag
    v_roadMaskUV = a_roadMaskUV;
    v_roadMaskRegion = a_roadMaskRegion;
    #endif
    #ifdef roadCoatFlag
    v_roadCoat = a_color;
    #endif
}
