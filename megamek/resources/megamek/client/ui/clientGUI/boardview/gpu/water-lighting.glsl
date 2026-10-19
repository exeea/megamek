// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Linear lighting and premultiplied output for pools, waterfall curtains, spray and board-edge sections.
struct WaterLighting {
    vec3 light;
    vec3 whiteLight;
    vec3 sunlight;
    vec3 ambient;
    vec3 ground;
};

// Capture full position derivatives before coverage rejection: a fall's height also moves its reflected cloud ray.
vec3 waterPositionDx = vec3(0.0), waterPositionDy = vec3(0.0);

// Orthographic rays all share a view direction, so a whole level lake can hit the sun's mirror angle at once.
// Limit that highlight to this share of sunlight on white level ground, keeping water readable at default exposure.
// This is a presentation choice, not a physical limit on water reflectance; perspective keeps the physical lobe.
const float WATER_ORTHOGRAPHIC_GLARE = .45;
vec3 waterDirectReflection(vec3 normal, vec3 light, vec3 view, float roughness) {
    // Still water keeps direct reflection, using the point-source lobe instead of the finite-source filter.
    vec3 reflected = u_waterEffects > 0.0 ? surfaceReflectance(normal, light, view, roughness, vec3(.02037))
          : pointReflectance(normal, light, view, roughness, vec3(.02037));
    if (u_perspective < .5) {
        float ceiling = WATER_ORTHOGRAPHIC_GLARE * max(light.z, 0.0);
        float knee = ceiling * .5;
        float peak = max(reflected.r, max(reflected.g, reflected.b));
        if (peak > knee) {
            // Identity below the knee, matching its slope at the join and approaching the ceiling smoothly.
            // Scale the whole colour together; per-channel clipping would bleach warm highlights.
            float excess = peak - knee, headroom = ceiling - knee;
            reflected *= (knee + headroom * excess / (headroom + excess)) / peak;
        }
    }
    return reflected;
}

// The local lights (lava-lighting.glsl), in one walk. Their irradiance on a level surface joins the light the water
// scatters, as the sky's does through incidentLighting in waterLighting(false). The facets (normal, roughness) mirror
// them as they mirror the sun, each source through its own size-widened lobe and a beam along its whole length, and
// so does a fall's sheet in sheetGlint.
// The orthographic glare ceiling stays the sun's: no local light meets a whole lake at its mirror angle at once.
vec3 waterLocalLighting(inout WaterLighting illumination, vec3 normal, vec3 view, float roughness, bool falling,
      vec3 sheet, float sheetRoughness, out vec3 sheetGlint) {
    vec3 glint = vec3(0.0);
    sheetGlint = vec3(0.0);
#ifdef lightingFlag
    vec3 irradiance = lavaLighting(v_cloudPosition, vec3(0.0, 0.0, 1.0), view, vec3(.02037), falling ? 2 : 1, normal,
          roughness, glint, sheet, sheetRoughness, sheetGlint);
    illumination.light += irradiance;
    illumination.whiteLight += irradiance;
    illumination.ambient += irradiance;
#endif
    return glint;
}

vec3 waterEnvironmentReflection(vec3 normal, vec3 view, float roughness, WaterLighting illumination) {
    if (dot(normal, view) <= 0.0) return vec3(0.0);
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    vec3 reflected = reflect(-view, normal);
    vec3 radiance = environmentRadiance(reflected, roughness, illumination.ground, illumination.ambient);
#if defined(cloudShadowFlag) && defined(environmentLightingFlag) && defined(lightingFlag)
    // Imported panoramas retain their own sky. This is an optional approximation of the procedural cloud layer.
    if (u_waterEffects > 0.0 && u_cloudReflection > .5 && u_environmentImage.x < .5 && reflected.z > .02) {
        float base = -u_cloudProjection[3][2] / u_cloudProjection[2][2];
        float height = base - v_cloudPosition.z;
        vec3 overhead = v_cloudPosition + reflected * (max(height, 0.0) / reflected.z);
        vec2 uv = (u_cloudProjection * vec4(overhead, 1.0)).xy;
        if (height > 0.0 && min(uv.x, uv.y) > 0.0 && max(uv.x, uv.y) < 1.0) {
            // A GGX-alpha cone projected to a horizontal layer. Near the horizon its footprint widens quadratically.
            // This mip approximation blurs opacity, not a second reflected image or a per-pixel volume march.
            vec2 scale = vec2(u_cloudProjection[0][0], u_cloudProjection[1][1]);
            float cone = height * roughness * roughness / (reflected.z * reflected.z);
            // Differentiate P.xy + (base - P.z) * R.xy/R.z at the local reflected direction.
            // Keeping dP.z matters on vertical sheets, even when their horizontal pixel footprint is nearly zero.
            vec2 raySlope = reflected.xy / reflected.z;
            vec2 projectedDx = waterPositionDx.xy - raySlope * waterPositionDx.z;
            vec2 projectedDy = waterPositionDy.xy - raySlope * waterPositionDy.z;
            vec2 footprint = (abs(projectedDx) + abs(projectedDy) + vec2(cone)) * abs(scale);
            vec2 texels = footprint * vec2(textureSize(u_cloudShadow, 0));
            float lod = max(0.0, log2(max(max(texels.x, texels.y), 1.0)));
            float coverage = textureLod(u_cloudShadow, uv, lod).g;
            vec2 border = smoothstep(vec2(0.0), max(footprint, 1.0 / vec2(textureSize(u_cloudShadow, 0))),
                  min(uv, 1.0 - uv));
            coverage *= border.x * border.y * smoothstep(.02, .12, reflected.z);
            // A unit-albedo isotropic slab source. The upper-hemisphere angular mean of H+(S-H)*sqrt(z)
            // is (H+2*S)/3. Ground uses the existing neutral bounce; neither receiver shadows nor lava light it.
            vec3 skyMean = (u_environmentHorizon + 2.0 * u_environmentSky) / 3.0;
            vec3 ground = GROUND_ALBEDO * (surfaceAmbient(vec3(0.0, 0.0, 1.0)) + sunOnGround());
            vec3 cloud = .5 * (skyMean + ground);
#if numDirectionalLights > 0
            // Directional uniforms are E/pi; isotropic phase 1/(4*pi) therefore contributes color/4.
            cloud += .25 * u_dirLights[0].color;
#endif
            float skyShare = environmentWeights(reflected, roughness).x;
            radiance = mix(radiance, cloud * skyShare + illumination.ground * (1.0 - skyShare), coverage);
        }
    }
#endif
    return radiance * specularAlbedo(max(0.0, dot(normal, view)), roughness, vec3(.02037));
}

vec3 waterTint(vec4 mixture) {
    bool procedural = u_waterMaterial.y > 0.5;
    vec3 tint = vec3(1.0);
#ifdef diffuseColorFlag
    tint = u_diffuseColor.rgb;
#endif
    if (procedural) tint = mix(vec3(1.0), toLinear(vec3(.4, 1.0, .12)), mixture.w);
    return tint;
}

vec3 waterFroth(vec4 mixture, vec3 tint) {
    vec3 scatter = waterScatter(mixture);
    // Clear water foams white; silty and chemical water foams in the color of what it carries.
    return mix(toLinear(vec3(0.94, 0.97, 1.0)), scatter / max(max(scatter.r, scatter.g), scatter.b),
          mix(.3, .08, mixture.x)) * tint;
}

WaterLighting waterLighting(bool local) {
    // Level-surface irradiance for the column, foam, mist and falls alike: white water scatters light through its
    // whole volume, so a fall's foam reads exactly like the foam it lands in. Pools and falls leave out the local
    // lights here and add them with their reflection (waterLocalLighting).
    vec3 ambient = vec3(0.0);
    vec3 lightNormal = vec3(0.0, 0.0, 1.0);
    vec3 albedo = vec3(1.0);
    vec3 sunlight = vec3(0.0);
#ifdef lightingFlag
    // This is incident irradiance for a scattering volume, before any particular surface BRDF/Fresnel split.
    vec3 direct = vec3(0.0), sheen = vec3(0.0);
    incidentLighting(lightNormal, local, ambient, direct);
    albedo *= ambient + direct;
#if numDirectionalLights > 0
    // Shadowed, cloud-filtered sunlight arriving along the sun direction, for the reflected glints.
    sunlight = direct / max(dot(lightNormal, -u_dirLights[0].direction), .0001);
#endif
#endif
    vec3 light = albedo;
    // White water scatters light through its whole volume: even in shade it passes on some of the sun's light.
    vec3 whiteLight = light;
#if defined(lightingFlag) && numDirectionalLights > 0
    whiteLight = max(light, ambient + sunOnGround() * 0.4);
#endif
    vec3 ground = GROUND_ALBEDO * light;
#ifdef lightingFlag
    ground = environmentGround(GROUND_ALBEDO);
#endif
    return WaterLighting(light, whiteLight, sunlight, ambient, ground);
}

WaterLighting waterLighting() { return waterLighting(true); }

void waterOutput(vec4 color) {
    color *= v_opacity;
    if (color.a < 0.002 && max(color.r, max(color.g, color.b)) < 0.002) discard;
    fragColor = color;
}
