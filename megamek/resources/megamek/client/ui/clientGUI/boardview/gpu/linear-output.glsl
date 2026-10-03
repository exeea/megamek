// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted after libGDX's fragment fog. Emission stays display-encoded; unlit draws are untouched.
#ifdef lightingFlag
#ifdef normalFlag
fragColor.rgb += diffuse.rgb * lavaIrradiance(v_cloudPosition, normalize(v_normal));
#endif
fragColor.rgb = toDisplay(fragColor.rgb) + displayEmissive;
#endif
