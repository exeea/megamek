// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted after libGDX's fragment fog. Emission stays display-encoded; unlit draws are untouched.
#ifdef lightingFlag
fragColor.rgb = toDisplay(fragColor.rgb) + displayEmissive;
#endif
