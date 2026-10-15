// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted after libGDX's fragment fog. Scene radiance stays linear through blending and the final composite.
// Tactical draws after the scene composite may borrow a scene color texture or an imported material.
// Their samples/factors are linear too, but their destination is already display encoded.
#if defined(displayOutputFlag) || (!defined(lightingFlag) && !defined(sceneUnlitFlag) && (defined(linearTextureFlag) || defined(linearColorsFlag)))
fragColor.rgb = toDisplay(fragColor.rgb);
#endif
