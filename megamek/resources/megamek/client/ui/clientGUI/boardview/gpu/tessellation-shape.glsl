// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// How far a relief vertex's patches round (GpuTessellation): colour carries the kind and a cliff's rockiness; UVs
// carry distances to its contacts. Soil rounds, rock barely rounds, and contacts, built panels and plants stay fixed.
// Coatings borrow these same inputs from their supporting terrain. An edge takes the lesser of its two vertices'. What
// else stays fixed (the board's cut, rails, bridges, water) comes as its own input (a_tessFixed).
const float SOIL_SHAPE = .75;
const float ROCK_SHAPE = .15;

float tessShape(vec4 color, vec2 contact) {
    float kind = color.b;
    if (kind >= .125 && kind < .375) return 0.0;
    // Tops, feet and cliff rims share their original edge even where shading normals or material kinds differ: both
    // ground and walls store distances from these contacts in UV.
    // Negative shore distances encode ownership; recover the distance before testing it.
    contact.y = contact.y < 0.0 ? -1.0 - contact.y : contact.y;
    float joined = smoothstep(0.0, .5, min(contact.x, contact.y));
    if (kind >= .375 && kind < .625) {
        // A cliff's green channel carries its rockiness; alpha is the hardness of its rock bed.
        return kind < .475 ? 0.0 : joined * mix(SOIL_SHAPE, ROCK_SHAPE, color.g);
    }
    return kind >= .875 ? ROCK_SHAPE : joined * SOIL_SHAPE;
}
