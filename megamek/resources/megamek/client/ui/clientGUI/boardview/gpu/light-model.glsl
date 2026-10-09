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

// GGX direct reflection, including the incident cosine. Terrain, foliage and imported models share this response.
// Roughness is perceptual; callers bound it to avoid subpixel highlights at board viewing distances.
vec3 surfaceReflectance(vec3 normal, vec3 light, vec3 view, float roughness, vec3 f0) {
    float incidence = max(0.0, dot(normal, light));
    if (incidence <= 0.0) return vec3(0.0);
    vec3 halfway = light + view;
    vec3 halfVector = halfway * inversesqrt(max(dot(halfway, halfway), 1e-8));
    float nv = max(.001, dot(normal, view)), nh = max(0.0, dot(normal, halfVector));
    float a2 = roughness * roughness * roughness * roughness;
    float denominator = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(3.14159265 * denominator * denominator, .0001);
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float visibility = nv / (nv * (1.0 - k) + k) * incidence / (incidence * (1.0 - k) + k);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(0.0, dot(view, halfVector)), 5.0);
    return distribution * visibility * fresnel / (4.0 * nv);
}
