// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One light model for every lit surface. Colours are authored display-encoded; BoardAtmosphere's light arrives linear
// and already exposed (clear noon light gives white level ground 1). A surface linearises its colour, multiplies it by
// the light and encodes the result. Pure functions without uniforms, so the terrain shaders and libGDX's DefaultShader
// (GpuUnitShader.linearVertex/linearFragment) share this one file.
vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
vec3 toDisplay(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

// Reflectance of the ground below surfaces that do not know their terrain family.
const vec3 GROUND_ALBEDO = vec3(0.2);

// Light on a face whose normal has this up component: the sky from above, the lit ground's reflection from below.
vec3 hemisphere(vec3 sky, vec3 sunOnGround, vec3 groundAlbedo, float up) {
    return mix(groundAlbedo * (sky + sunOnGround), sky, up * 0.5 + 0.5);
}
