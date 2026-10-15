// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
uniform sampler2D u_contrastInk;
uniform float u_contrastEnabled; // Zero preserves authored ink, without fetching the atlas.

// Every glyph and grid vertex of an owner reads the same binary, hysteretic choice.
float overlayInk(vec2 hex) { return texelFetch(u_contrastInk, ivec2(hex), 0).r; }
