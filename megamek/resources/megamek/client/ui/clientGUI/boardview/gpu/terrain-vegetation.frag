#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec3 v_normal;
in vec4 v_color;
in vec2 v_coverData;
in vec2 v_coverRoot;
#ifdef biomeVegetationFlag
in vec2 v_coverFade;
#endif
#ifdef diffuseTextureFlag
in vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif

#if !defined(biomeVegetationFlag) && !defined(bankTurfFlag)
// The meadow's grass material: its place in the finish arrays (finish-arrays.glsl), and the metres and world units
// one repeat spans.
uniform vec3 u_meadowFinish;
#endif
#ifdef biomeVegetationFlag
// Sedge's finish (GpuBiomeVegetation.Kind): roughness, metallic and scattering; emission.
uniform vec3 u_biomeFinish, u_biomeGlow;
#endif

void main() {
    // An upward-biased normal approximates the many sunlit leaves in a tuft without black card-like speckles.
    vec3 leaf = normalize(v_normal) * (gl_FrontFacing ? 1.0 : -1.0);
    vec3 normal = normalize(mix(vec3(0.0, 0.0, 1.0), leaf, .45));
#ifdef lightingFlag
    // Actual grass blades keep this triangle frame; the flagged clump proxies select their virtual frame below.
    vec3 geometric = visibleFace(dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
    // Before the coverage discards below, like the face above.
    surfaceVariance = normalVariance(dFdx(normal), dFdy(normal));
#endif
#ifdef bankTurfFlag
    vec4 plant = texture(u_diffuseTexture, v_diffuseUV, -.65);
    vec2 turfUv = v_cloudPosition.xy * vec2(1.0, -1.0) / u_metre;
    mat2 turfGradient = mat2(dFdx(turfUv), dFdy(turfUv));
#endif
    Finish finish = Finish(.86, 0.0, 0.0, vec3(0.0));
#if !defined(biomeVegetationFlag) && !defined(bankTurfFlag)
    // A meadow's blades are its grass: they take its material's finish where they root, filtered over the blade's own
    // screen footprint, so every fragment of a blade reads the same texel at the level its size needs. A field's
    // plants (v_coverData.x, one per draw instance, so whole quads branch alike) keep a dry leaf's.
    if (v_coverData.x < .5) {
        vec2 meadowFootprint = v_cloudPosition.xy * vec2(1.0, -1.0) / u_meadowFinish.z;
        finish = finishTexel(int(u_meadowFinish.x + .5), v_coverRoot * vec2(1.0, -1.0) / u_meadowFinish.y,
              mat2(dFdx(meadowFootprint), dFdy(meadowFootprint)));
    }
#endif
#ifdef biomeVegetationFlag
    // Their material's finish, as the means of its maps: their artwork is composited at run time.
    finish = Finish(u_biomeFinish.x, u_biomeFinish.y, u_biomeFinish.z, u_biomeGlow);
    // Sample before any coverage discard: neighbouring fragments must agree on derivatives during an LOD fade.
    vec4 plant = texture(u_diffuseTexture, v_diffuseUV);
    // Complementary coverage keeps overlapping LODs opaque without doubling leaves or changing depth ownership.
    float coverage = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(.06711056, .00583715))));
    if (coverage < v_coverFade.x || coverage >= v_coverFade.y) discard;
#endif
    vec3 albedo = v_color.rgb;
#if (defined(biomeVegetationFlag) || defined(bankTurfFlag)) && defined(diffuseTextureFlag)
    // Opaque depth ownership avoids ordering halos between intersecting clumps; mipmaps filter distant leaves.
#ifdef bankTurfFlag
    plant.a *= 1.0 - scorchedGrass(v_cloudPosition.xy);
#endif
    if (plant.a < .32) discard;
#ifdef bankTurfFlag
    albedo *= turfColor(plant.rgb, turfGradient, finish);
#else
    albedo *= plant.rgb;
#endif
#endif
    albedo = wetAlbedo(albedo, u_wetness * .25);
    albedo = materialReflectance(albedo);
#ifdef bankTurfFlag
    // The draped turf follows its supporting bank's continuous height after artwork/vertex pigment gains.
    albedo = levelGrade(albedo, v_cloudPosition.z / u_levelHeight);
#endif
#ifdef lightingFlag
#if defined(biomeVegetationFlag) || defined(bankTurfFlag)
    // The cutout contains a whole clump. Its existing aggregate normal is the represented material frame.
    // Unflagged, individually curved grass blades retain their real triangle frame.
    if (dot(normal, -viewDirection()) < 0.0) normal = -normal;
    geometric = normal;
#endif
    normal = shadingNormal(normal, geometric, -viewDirection());
    // Dry blades have a broad, rough reflection; rain smooths the same dielectric response into brighter glints. The
    // blade's own normal decides its lit side.
    Surface blade = opaqueSurface(albedo, normal, normal, finish.roughness, .6 * u_wetness, surfaceVisibility());
    metallicShare(blade, finish.metallic);
    blade.scatter = finish.scatter;
    blade.emission = finish.emission;
    // Thin leaves transmit part of their diffuse budget instead of adding another light source.
    blade.front = vec3(.85);
    blade.back = vec3(.15);
#if !defined(biomeVegetationFlag) && !defined(bankTurfFlag)
    // Root shelter reduces diffuse sky visibility, not authored pigment or direct light.
    blade.occlusion = mix(.80, 1.0, clamp(v_coverData.y, 0.0, 1.0));
#endif
    // Reflected sky reaches a blade only above the sward around it.
    blade.specularOcclusion = v_coverData.y;
    albedo = shadeSurface(blade);
#endif
    fragColor = vec4(albedo * terrainGrid(v_coverRoot * .2), 1.0);
}
