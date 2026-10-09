// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// RigidGlb.recolour on the GPU, for a recolourable model's one shared mesh (RigidGlb.colourSlotMesh): a_colourSlot is a
// vertex's shade relative to its slot's default colour (linear rgb; 1 where the default channel is 0) and its slot (w,
// 0 for none); a placement's colours are 0xRRGGBB per slot, negative to keep the model's own. Exact sRGB like the CPU
// rule, not light-model.glsl's 2.2 approximation; values above 1 stay unclamped as on the CPU.
#ifdef colourSlotsFlag
in vec4 a_colourSlot;
uniform vec4 u_slotColours;

vec3 slotLinear(vec3 c) { return mix(c / 12.92, pow((c + .055) / 1.055, vec3(2.4)), step(.04045, c)); }

vec3 slotDisplay(vec3 c) { return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - .055, step(.0031308, c)); }

vec3 slotColour(vec3 colour, vec4 slot, vec4 colours) {
    int index = int(slot.w + .5) - 1;
    if (index < 0 || colours[index] < 0.0) return colour;
    uint rgb = uint(colours[index]); // an exact integer: + .5 rounds odd values above 2^23 up a step
    vec3 replacement = vec3((rgb >> 16) & 255u, (rgb >> 8) & 255u, rgb & 255u) / 255.0;
    return slotDisplay(slot.rgb * slotLinear(replacement));
}
#endif
