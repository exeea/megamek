// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// 0 for a step of up to two levels (a bank of the mantle), 1 from three levels (a rock cliff). terrain-sculpt.frag
// shades by it; the relief's tessellation rounds by it (tessellation-shape.glsl).
float rockiness(float levels) { return smoothstep(2.15, 2.9, levels); }
