// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// How far a relief vertex's patches round (GpuTessellation), from its vertex colour (terrain-sculpt.frag: blue the kind,
// alpha the step it stands on): soil and turf fully, as creep rounds them; a cliff by its height, a bank of up to two
// levels as soil and from three levels as rock (rockiness), which barely rounds, so its faces stay planes and only
// its corners soften; built panels and plants not at all. The colour alone decides, so the colour pass and the shadow
// pass round alike. An edge takes the lesser of its two vertices'.
const float SOIL_SHAPE = .75;
const float ROCK_SHAPE = .15;

float tessShape(vec4 color) {
    float kind = color.b;
    if (kind >= .125 && kind < .375) return 0.0;
    if (kind >= .375 && kind < .625) {
        return kind < .475 ? 0.0 : mix(SOIL_SHAPE, ROCK_SHAPE, rockiness((color.a - .3) / .1));
    }
    return kind >= .875 ? ROCK_SHAPE : SOIL_SHAPE;
}
