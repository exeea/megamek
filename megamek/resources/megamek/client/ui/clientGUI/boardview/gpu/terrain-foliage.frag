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

void main() {
    vec3 face = normalize(v_normal);
    vec3 crownNormal = face;
#ifdef normalTextureFlag
    // Derive the frame from the authored UVs, so mapped ribs follow bent stems and rotated instances.
    // Mip filtering removes subpixel normal detail without adding a mesh level or draw pass.
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
    vec2 du = dFdx(v_diffuseUV), dv = dFdy(v_diffuseUV);
    float determinant = du.x * dv.y - du.y * dv.x;
    if (u_normalMaps > .5 && abs(determinant) > 1e-10) {
        vec3 tangent = (dx * dv.y - dy * du.y) / determinant;
        vec3 bitangent = (dy * du.x - dx * dv.x) / determinant;
        tangent -= face * dot(face, tangent);
        if (dot(tangent, tangent) > 1e-10) {
            tangent = normalize(tangent);
            vec3 perpendicular = cross(face, tangent);
            bitangent = dot(perpendicular, bitangent) < 0.0 ? -perpendicular : perpendicular;
            vec3 detail = (texture(u_normalTexture, v_diffuseUV).rgb * 255.0 - 128.0) / 127.0;
            face = normalize(tangent * detail.x + bitangent * detail.y + face * detail.z);
        }
    }
#endif
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
    vec3 albedo = diffuse.rgb;
    // The source models tint their snow; fresh snow stays neutral, as on the ground below.
    bool snow = abs(u_foliage - 2.0) < .5;
    if (snow) albedo = vec3(dot(albedo, vec3(.2126, .7152, .0722))) * vec3(.97, .98, 1.02);
    if (u_clay > .5) albedo = vec3(.52);
    albedo *= 1.0 - u_wetness * (snow ? 0.0 : .12);
    albedo = toLinear(albedo);
#ifdef lightingFlag
    vec3 surface = vec3(1.0, .58, .35);
#ifdef ambientTextureFlag
    surface = texture(u_ambientTexture, v_diffuseUV).rgb;
#endif
    // Inside and under a canopy the sky is hidden by the leaves above.
    vec3 ambient = skyLight(face, GROUND_ALBEDO) * mix(.85, mix(.7, 1.0, face.z * .5 + .5), leaves);
    ambient *= surface.r;
    vec3 direct = vec3(0.0);
    vec3 sheen = vec3(0.0);
#if numDirectionalLights > 0
    vec3 light = -u_dirLights[0].direction;
    float incidence = mix(max(0.0, dot(face, light)), max(0.0, dot(face, light) * .6 + .4), leaves);
    // Use the crown's smooth normal for stable shadow offsets; the detail normal is only leaf relief.
    float visibility = sculptShadow(crownNormal, light);
#ifdef impostorFlag
    visibility *= sunlit;
#endif
    vec3 view = -viewDirection();
    float backlight = pow(max(0.0, dot(-light, view)), 4.0);
    float transmitted = leaves * surface.b * backlight * .65;
    direct = u_dirLights[0].color * visibility * (incidence + transmitted);
    // Bark and snow scatter a broad reflection; leaves keep their authored roughness. The same mixture applies to
    // distant cards. Rain smooths bark and leaves; separately marked snow keeps its dry response like the ground.
    float roughness = mix(snow ? .95 : .90, surface.g, leaves);
    sheen = u_dirLights[0].color * visibility
          * dielectricSheen(face, light, view, snow ? 0.0 : u_wetness * .15, roughness);
#endif
#if defined(normalTextureFlag) && !defined(ambientTextureFlag)
    // Opaque cactus skin has a broad waxy highlight, using the terrain's existing dielectric light model.
    surfaceLighting(face, u_wetness * .15, .65, ambient, direct, sheen);
#else
    // Match the board's local light without evaluating the emitter loop on transparent leaf texels.
    // Keep derivative-dependent normal/shadow work above this branch; cactus already receives the light above.
    bool covered = true;
#if defined(blendedFlag) && defined(alphaTestFlag)
    covered = diffuse.a * v_opacity > v_alphaTest;
#endif
    if (covered) {
        ambient += lavaIrradiance(v_cloudPosition, face);
    }
#endif
    // Cloud shadows attenuate direct light here (inserted by GpuCloudShadow).
    albedo *= ambient + direct;
    albedo += sheen;
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
        float grain = smoothstep(.16, .62, tissue.r);
        float lamella = pow(smoothstep(.16, .49, tissue.r), 3.0);
        emission = vec3(1.0, .31, .085) * lip * (.06 + .48 * grain * grain)
              + mix(vec3(.8, .07, .20), vec3(1.0, .35, .16), lamella) * gills * (.015 + .38 * lamella)
              + vec3(.48, .06, .15) * spore * grain * .035;
#endif
        if (u_foliage > 3.5) emission = toLinear(diffuse.rgb) * 1.3;
        float breath = .94 + .06 * sin(u_rainTime * .65 + dot(v_cloudPosition.xy, vec2(.073, .051)));
        albedo += emission * breath;
    }
    fragColor.rgb = toDisplay(albedo);
#ifdef blendedFlag
    fragColor.a = diffuse.a * v_opacity;
#ifdef alphaTestFlag
    if (fragColor.a <= v_alphaTest) discard;
#endif
#else
    fragColor.a = 1.0;
#endif
}
