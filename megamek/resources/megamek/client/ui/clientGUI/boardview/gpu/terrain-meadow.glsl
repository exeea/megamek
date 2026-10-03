// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The fragment already samples these world fields. Share their coverage with the instanced tufts, so dry
// patches contain sparse straw and lush patches carry the denser, greener growth without extra fragment reads.
float meadowCoverFromFields(float broad, float fine) {
    return smoothstep(.32, .68, broad * .65 + fine * .35);
}

float meadowCover(vec2 metres) {
    float broad = texture(u_rainNoise, metres / 700.0).g * .6
          + texture(u_rainNoise, metres / 430.0 + .19).b * .4;
    float fine = texture(u_rainNoise, metres / 160.0 + .41).b;
    return meadowCoverFromFields(broad, fine);
}
