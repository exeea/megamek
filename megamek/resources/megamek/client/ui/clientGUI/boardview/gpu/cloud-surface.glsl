// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Inserted in terrain fragment main before applying ambient + direct to albedo.
#ifdef cloudShadowFlag
float cloudLight = cloudTransmission(v_cloudPosition);
direct *= cloudLight;
sheen *= cloudLight;
#endif
