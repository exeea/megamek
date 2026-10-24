// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// What a board material is beyond its colour and relief, from its Godot StandardMaterial3D: perceptual roughness,
// metallic, subsurface scattering (0 to 1) and the radiance it emits. Terrain, turf, roads, foliage and models read the
// same channels and blend them the same way.
struct Finish { float roughness; float metallic; float scatter; vec3 emission; };

Finish finishOf(vec3 surface, vec3 emission) { return Finish(surface.x, surface.y, surface.z, emission); }

Finish mixFinish(Finish a, Finish b, float t) {
    return Finish(mix(a.roughness, b.roughness, t), mix(a.metallic, b.metallic, t), mix(a.scatter, b.scatter, t),
          mix(a.emission, b.emission, t));
}

Finish weighFinish(Finish a, Finish b, Finish c, Finish d, vec4 weights) {
    return Finish(dot(vec4(a.roughness, b.roughness, c.roughness, d.roughness), weights),
          dot(vec4(a.metallic, b.metallic, c.metallic, d.metallic), weights),
          dot(vec4(a.scatter, b.scatter, c.scatter, d.scatter), weights),
          a.emission * weights.x + b.emission * weights.y + c.emission * weights.z + d.emission * weights.w);
}

// A running weighted sum of finishes.
Finish addFinish(Finish sum, Finish f, float weight) {
    return Finish(sum.roughness + f.roughness * weight, sum.metallic + f.metallic * weight,
          sum.scatter + f.scatter * weight, sum.emission + f.emission * weight);
}

// A cover over the given share of a finish brings no metallic, scattering or emission of its own; its roughness is the
// caller's.
void coverFinish(inout Finish f, float amount) {
    f.metallic *= 1.0 - amount;
    f.scatter *= 1.0 - amount;
    f.emission *= 1.0 - amount;
}
