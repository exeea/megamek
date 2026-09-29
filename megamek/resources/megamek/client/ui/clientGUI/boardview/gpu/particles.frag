#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 v_uv;
in vec2 v_effect;
// Kind: 0 smoke, 1 blue jet flame, 2+ turbulent fire (integer packet seed plus fractional age).
// PARTICLE_APPEARANCE

void main() {
    if (v_effect.x < 0.5) {
        fragColor = smokeParticle();
    } else if (v_effect.x > 1.5) {
        fragColor = fireParticle();
    } else {
        fragColor = jetParticle();
    }
}
