#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Trees: their models' own colours and detail maps, lit like the sculpted terrain they stand on (light-model.glsl,
// surface-lighting.glsl): linear light, sky and ground bounce and soft shadows; the composite rolls off their
// highlights. A canopy scatters light through its leaves, so the sun wraps around it instead of stopping at a hard
// terminator.
in vec3 v_normal;
#ifdef colorFlag
in vec4 v_color;
#endif
#ifdef diffuseTextureFlag
in vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
#ifdef normalTextureFlag
uniform sampler2D u_normalTexture;
uniform float u_normalMaps;
#endif
#ifdef ambientTextureFlag
// Leaf surface texture: red cavity occlusion, green roughness, blue thin-tissue transmission.
// Stored in glTF occlusionTexture (whose standard red channel remains valid).
uniform sampler2D u_ambientTexture;
#endif
#ifdef blendedFlag
in float v_opacity;
#ifdef alphaTestFlag
in float v_alphaTest;
#endif
#endif
uniform float u_foliage; // 0 solid, 1 canopy, 2 snow, 3 fungal fruiting body, 4 cyan mycelium
uniform float u_clay;
uniform float u_foliageProxy; // Exact authored branch-card roles, not all thin tissue.

void main() {
    vec3 baseNormal = normalize(v_normal);
    vec3 face = baseNormal;
    vec3 crownNormal = gl_FrontFacing ? face : -face;
    // Hoist derivative work before any alpha discard or mapped/proxy branch.
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
#ifdef normalTextureFlag
    vec2 du = dFdx(v_diffuseUV), dv = dFdy(v_diffuseUV);
#endif
    surfaceVariance = normalVariance(dFdx(baseNormal), dFdy(baseNormal));
    vec4 diffuse = vec4(1.0);
#ifdef diffuseTextureFlag
    diffuse = texture(u_diffuseTexture, v_diffuseUV);
#endif
    vec3 tissue = diffuse.rgb;
    // How much of the surface is leaves, which scatter light around, rather than bark, cactus or snow.
    float leaves = abs(u_foliage - 1.0) < .5 ? 1.0 : 0.0;
#ifdef impostorFlag
    // An impostor card's colour says how its plant takes the sun (prepare_tree_lods.py): red, the share its own leaves
    // let through; green, how much of what the card shows is bark, cactus or snow. The card's shadow lookup skips the
    // crown it stands in (surface-lighting.glsl), so the shadow map adds other casters alone.
    float sunlit = v_color.r;
    leaves = 1.0 - v_color.g;
#elif defined(colorFlag)
    diffuse *= v_color;
#endif
#ifdef diffuseColorFlag
    diffuse *= u_diffuseColor;
#endif
#ifdef blendedFlag
    float opacity = diffuse.a * v_opacity;
#else
    float opacity = 1.0;
#endif
#ifdef lightingFlag
    vec3 surface = vec3(1.0, .58, .35);
#ifdef ambientTextureFlag
    surface = texture(u_ambientTexture, v_diffuseUV).rgb;
#endif
#if numDirectionalLights > 0
    vec3 light = -u_dirLights[0].direction;
    // Use the crown's smooth normal for stable shadow offsets; the detail normal is only leaf relief.
    float visibility = sculptShadow(crownNormal, light);
#ifdef impostorFlag
    visibility *= sunlit;
#endif
#endif
#endif
    // Alpha-tested texels own no color or depth. Keep texture/receiver derivatives above this cutoff;
    // normal detail uses the captured gradients, and single-level shadow/cloud reads use explicit LOD.
#if defined(blendedFlag) && defined(alphaTestFlag)
    if (opacity <= v_alphaTest) discard;
#endif
#ifdef normalTextureFlag
    // Derive the frame from the authored UVs, so mapped ribs follow bent stems and rotated instances.
    // Mip filtering removes subpixel normal detail without adding a mesh level or draw pass.
    float determinant = du.x * dv.y - du.y * dv.x;
    if (u_normalMaps > .5 && abs(determinant) > 1e-10) {
        vec3 tangent = (dx * dv.y - dy * du.y) / determinant;
        vec3 bitangent = (dy * du.x - dx * dv.x) / determinant;
        tangent -= face * dot(face, tangent);
        if (dot(tangent, tangent) > 1e-10) {
            tangent = normalize(tangent);
            vec3 perpendicular = cross(face, tangent);
            bitangent = dot(perpendicular, bitangent) < 0.0 ? -perpendicular : perpendicular;
            vec3 detail = (textureGrad(u_normalTexture, v_diffuseUV, du, dv).rgb * 255.0 - 128.0) / 127.0;
            surfaceVariance += normalMapVariance(length(detail));
            face = normalize(tangent * detail.x + bitangent * detail.y + face * detail.z);
        }
    }
#endif
#ifdef impostorFlag
    bool proxy = true;
#else
    bool proxy = u_foliageProxy > .5;
#endif
    if (!proxy && !gl_FrontFacing) face = -face;
#ifdef lightingFlag
    vec3 geometric = visibleFace(dx, dy, -viewDirection());
    if (proxy) {
        // Botanical base and mapped detail share the visible virtual side; the card only carries coverage.
        geometric = baseNormal;
        if (dot(geometric, -viewDirection()) < 0.0) { geometric = -geometric; face = -face; }
    }
#endif
    vec3 albedo = diffuse.rgb;
    // Snow pigment is authored in the texture and model; keep its material response below.
    bool snow = abs(u_foliage - 2.0) < .5;
    if (u_clay > .5) albedo = toLinear(vec3(.52));
    albedo *= 1.0 - u_wetness * (snow ? 0.0 : .12);
    albedo = materialReflectance(albedo);
#ifdef lightingFlag
    float roughness = mix(snow ? .95 : .90, surface.g, leaves);
#if defined(normalTextureFlag) && !defined(ambientTextureFlag)
    roughness = .65;
#endif
    float film = snow ? 0.0 : u_wetness * .15;
    vec3 view = -viewDirection();
    // Both real surfaces and proxies use their own visible material frame for every repaired lobe.
    face = shadingNormal(face, geometric, view);
    float diffuseBudget = dielectricBudget(face, view, film, roughness);
    float transmitted = leaves * clamp(surface.b, 0.0, 1.0);
    // Leaf transmission uses part of the same energy budget. Canopy occlusion attenuates incoming sky only.
    vec3 ambient = mix(skyLight(face, GROUND_ALBEDO), skyLight(-face, GROUND_ALBEDO), transmitted)
          * diffuseBudget * mix(.85, mix(.7, 1.0, face.z * .5 + .5), leaves);
    ambient *= surface.r;
    vec3 direct = vec3(0.0);
    vec3 sheen = vec3(0.0);
#if numDirectionalLights > 0
    float reflected = dielectricDiffuse(face, light, view, film, roughness);
    float through = dielectricDiffuse(-face, light, -view, film, roughness);
#ifndef impostorFlag
    // Actual thin tissue reflects on the visible side and transmits from the opposite side.
    reflected *= float(dot(geometric, light) > 0.0);
    through *= float(dot(geometric, light) < 0.0);
#endif
    direct = u_dirLights[0].color * visibility * mix(reflected, through, transmitted);
    // Bark and snow scatter a broad reflection; leaves keep their authored roughness. The same mixture applies to
    // distant cards. Rain smooths bark and leaves; separately marked snow keeps its dry response like the ground.
    sheen = u_dirLights[0].color * visibility
          * dielectricSheen(face, light, view, film, roughness);
#ifndef impostorFlag
    sheen *= float(dot(geometric, light) > 0.0);
#endif
#endif
#if defined(normalTextureFlag) && !defined(ambientTextureFlag)
    // Opaque cactus skin has a broad waxy highlight, using the terrain's existing dielectric light model.
    #ifdef impostorFlag
    surfaceLighting(face, u_wetness * .15, .65, ambient, direct, sheen);
#else
    surfaceLighting(face, geometric, u_wetness * .15, .65, ambient, direct, sheen);
#endif
#else
    // Transparent leaf texels have already exited; cactus receives local light above.
    ambient += lavaIrradiance(v_cloudPosition, face) * diffuseBudget;
#endif
    // Cloud shadows attenuate direct light here (inserted by GpuCloudShadow).
    albedo *= ambient + direct;
    albedo += sheen;
    albedo += surfaceEnvironment(face, film, roughness, GROUND_ALBEDO) * surface.r;
#endif
    if (u_foliage > 2.5 && u_clay < .5) {
        // The authored atlas already separates skin, lips, gills/pores and spore bodies. Emission follows those
        // surfaces at every model LOD, including scatter; bark-like stems keep their natural opaque shading.
        vec3 emission = vec3(0.0);
#ifdef diffuseTextureFlag
        vec2 panel = step(vec2(.5), v_diffuseUV);
        float lip = panel.x * (1.0 - panel.y);
        float gills = (1.0 - panel.x) * panel.y;
        float spore = panel.x * panel.y;
        float grain = smoothstep(toLinear(vec3(.16)).r, toLinear(vec3(.62)).r, tissue.r);
        float lamella = pow(smoothstep(toLinear(vec3(.16)).r, toLinear(vec3(.49)).r, tissue.r), 3.0);
        emission = vec3(1.0, .31, .085) * lip * (.06 + .48 * grain * grain)
              + mix(vec3(.8, .07, .20), vec3(1.0, .35, .16), lamella) * gills * (.015 + .38 * lamella)
              + vec3(.48, .06, .15) * spore * grain * .035;
#endif
        if (u_foliage > 3.5) emission = diffuse.rgb * 1.3;
        float breath = .94 + .06 * sin(u_rainTime * .65 + dot(v_cloudPosition.xy, vec2(.073, .051)));
        albedo += emission * breath;
    }
    fragColor = vec4(albedo, opacity);
}
