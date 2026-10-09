// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted before libGDX's fragment lighting. Authored albedo and emission are both display-encoded.
#ifdef lightingFlag
emissive.rgb = toLinear(emissive.rgb);
diffuse.rgb = toLinear(diffuse.rgb);
#endif
