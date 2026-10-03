// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Solid crust and cooled banks: occluding relief, stable plates and deep red fissures.
float magmaRepeatMetres() { return 12.0; }
Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec3 sun, vec2 downhill, vec3 random) {
    return magmaSample(uv, dx, dy, eye, sun);
}

mat3 magmaDomain(inout vec3 position) {
    // Cooled crust retains its gentle deformation and authored plate scale.
    vec3 phase = position.yzx * vec3(.63, .57, .69) + position.zxy * .17 + vec3(.1, 1.3, 2.7);
    vec3 slope = .22 * cos(phase);
    position += .22 * sin(phase);
    return mat3(vec3(1.0, .17 * slope.y, .69 * slope.z),
          vec3(.63 * slope.x, 1.0, .17 * slope.z), vec3(.17 * slope.x, .57 * slope.y, 1.0));
}

vec3 magmaHeatColor(float heat) {
    return mix(vec3(.55, .006, .0002), vec3(.95, .24, .004), heat * heat * heat);
}

vec3 magmaRadiance(Volcanic material, float bank, float strength) {
    return magmaEmission(material.heat * mix(.72, 1.0, bank), strength);
}
