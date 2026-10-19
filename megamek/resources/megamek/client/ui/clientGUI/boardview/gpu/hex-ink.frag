#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
flat in vec2 v_ink;
layout(location = 0) out vec2 fragColor;
void main() { fragColor = v_ink; }
