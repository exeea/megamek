// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One surface description and its one lighting entry, after surface-lighting.glsl and water-optics.glsl. A material
// fills a Surface; shadeSurface lights it once with the board's light model (light-model.glsl): the sun or moon with
// the shadow its caller looked up, the sky and its reflection (environment-lighting.glsl), and the local lights
// (lava-lighting.glsl). Specular antialiasing widens the lobe once, for every term. Emission follows the light, never
// scaled by it. Ice, lava and water layers keep their own evaluations; this lights the surface beneath them.
#ifdef lightingFlag
// The scene's one directional light, the sun or the moon. Without one its colour is zero, so every term it lights is
// zero in the same program.
vec3 sunColor() {
#if numDirectionalLights > 0
    return u_dirLights[0].color;
#else
    return vec3(0.0);
#endif
}

// Toward that light.
vec3 sunDirection() {
#if numDirectionalLights > 0
    return -u_dirLights[0].direction;
#else
    return vec3(0.0, 0.0, 1.0);
#endif
}

struct Surface {
    vec3 albedo;             // bounded linear diffuse reflectance (materialReflectance), graded
    vec3 normal;             // shading normal: mapped detail, after shadingNormal
    vec3 geometric;          // never mapped: its side decides whether direct light reaches the front
    float roughness;         // perceptual, dry; negative selects the matte fallback
    float film;              // water coat, 0 dry to 1 standing water: smooths the lobe, sets f0 and the diffuse budget
    vec3 f0;                 // reflectance of the metallic share at normal incidence: the metal's colour
    float metallic;          // share of the surface that is metal; the rest is a dielectric (f0 .04, or water's)
    float occlusion;         // ambient occlusion of the diffuse sky
    float specularOcclusion; // occlusion of the reflected environment
    float cavity;            // micro-occlusion of every light: sky, local lights, reflections and half the sun
    float visibility;        // of the sun: the shadow map and any self-shadowing, looked up by the caller
    vec3 front;              // diffuse share of light on the visible side; 1 for opaque surfaces
    vec3 back;               // share passed from the far side, relative to albedo (thinTransmission); 0 when opaque
    float heat;              // lava's matte hot share, lit like the crust programs; 0 elsewhere
    vec3 bounce;             // albedo of the sunlit ground below, which the sky reflects into undersides
    vec3 emission;           // radiance the light does not scale
};

// An opaque dielectric over GROUND_ALBEDO, without occlusion, transmission, heat or emission; callers set what they have.
Surface opaqueSurface(vec3 albedo, vec3 normal, vec3 geometric, float roughness, float film, float visibility) {
    return Surface(albedo, normal, geometric, roughness, film, vec3(.04), 0.0, 1.0, 1.0, 1.0, visibility, vec3(1.0),
          vec3(0.0), 0.0, GROUND_ALBEDO, vec3(0.0));
}

// Thin tissue, per channel: of the colour R it reflects from the front it passes T = min(share * R, 1 - R) from the
// far side, so R + T never exceeds one. Surface.back takes it relative to R (T / R).
vec3 thinTransmission(vec3 albedo, float share) {
    return min(vec3(share), (1.0 - albedo) / max(albedo, vec3(1e-4)));
}

// Reflected and emitted radiance. caustic and submerged describe water above the surface: the light its waves focus
// onto it and how many levels of water lie over it (water-optics.glsl).
vec3 shadeSurface(Surface s, float caustic, float submerged) {
#ifdef GROUND_DAMAGE_TILE
    // A nuclear blast's scorch (terrain-damage-mask.glsl), on every terrain surface lit here; models never read it.
    float scorch = groundScorchAt(v_cloudPosition.xy) * (1.0 - submerged);
    if (scorch > 0.0) {
        scorchGround(scorch, s.albedo, s.roughness);
        // The fused crust still glows after the blast, dimming as it cools.
        s.emission += vec3(1.1, .28, .06) * scorchGlow(v_cloudPosition.xy, scorch, u_groundScorch.y);
    }
#endif
    vec3 view = -viewDirection();
    vec3 normal = s.normal;
    // The water coat, the metallic share and specular antialiasing (surfaceVariance) set every lobe's roughness and
    // f0, once.
    float roughness = dielectricRoughness(s.film, s.roughness);
    vec3 f0 = mix(dielectricF0(s.film), s.f0, s.metallic);
    vec3 diffuseBudget = 1.0 - specularAlbedo(max(0.0, dot(normal, view)), roughness, f0);
    vec3 multiscatter = specularMultiscatter(max(0.0, dot(normal, view)), roughness, f0);
    // The sky's ambient occlusion, with the screen-space occlusion of the opaque scene (ambient-occlusion.glsl): once,
    // for every surface, on the sky alone. Local lights and the sun keep their own visibility.
    vec2 screen = screenOcclusion();
    float occlusion = s.occlusion * screen.x;
    vec3 localSpecular;
    vec3 localLight = lavaIrradiance(v_cloudPosition, normal, view, roughness, f0, localSpecular);
    // Sky from above; from below, light reflected by the sunlit ground: warm in the desert, white on snow.
    vec3 sky = skyLight(normal, s.bounce) * s.front;
    bool tissue = s.back != vec3(0.0);
    if (tissue) sky += skyLight(-normal, s.bounce) * s.back;
    vec3 ambient = (sky * occlusion * s.cavity + localLight * s.cavity) * diffuseBudget;
    vec3 light = sunDirection();
    // Contact shadows (ambient-occlusion.glsl) add the small occluders the shadow map is too coarse for.
    vec3 sun = sunColor() * s.visibility * screen.y;
    bool lit = dot(s.geometric, light) > 0.0;
    vec3 incidence = lit ? diffuseReflectance(normal, light, view, roughness, f0) : vec3(0.0);
    vec3 incident = incidence * s.front;
    if (tissue) {
        vec3 through = dot(s.geometric, light) < 0.0
              ? diffuseReflectance(-normal, light, -view, roughness, f0) : vec3(0.0);
        incident += through * s.back;
    }
    vec3 direct = sun * incident * mix(1.0, s.cavity, .5) * (1.0 + caustic);
    vec3 sheen = vec3(0.0);
    if (s.heat < 1.0 && lit && any(greaterThan(incidence, vec3(0.0)))) {
        sheen = sun * surfaceReflectance(normal, light, view, roughness, f0) * multiscatter * s.cavity;
    }
    if (s.heat > 0.0) {
        // The material response and spill light are identical on both sides of the blend. Only the sky bounce,
        // occlusion and shadow filter differ; reuse the expensive terms instead of shading twice.
        vec3 hotAmbient = (skyLight(normal, GROUND_ALBEDO) + localLight) * diffuseBudget;
        vec3 hotDirect = sunColor() * incident * surfaceVisibility();
        ambient = mix(ambient, hotAmbient * s.cavity, s.heat);
        direct = mix(direct, hotDirect, s.heat);
        sheen *= 1.0 - s.heat;
    }
    vec3 albedo = s.albedo;
    // Cloud shadows attenuate direct light and sheen here (inserted by GpuCloudShadow).
    albedo *= ambient + direct;
    albedo += sheen;
    // The hot share is matte, matching the dedicated crust and lava programs.
    albedo += localSpecular * multiscatter * s.cavity * (1.0 - s.heat);
    albedo += environmentSpecular(normal, view, roughness, f0, environmentGround(s.bounce),
          surfaceAmbient(vec3(0.0, 0.0, 1.0))) * multiscatter * s.specularOcclusion * s.cavity * (1.0 - s.heat);
    if (submerged > 0.0) {
        vec3 scattered = surfaceAmbient(vec3(0.0, 0.0, 1.0)) * occlusion + sunOnGround() * (1.0 + caustic);
        albedo = submergedLight(albedo, s.albedo, scattered, submerged);
    }
    return albedo + s.emission;
}

vec3 shadeSurface(Surface s) { return shadeSurface(s, 0.0, 0.0); }
#endif
