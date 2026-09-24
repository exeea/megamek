// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Macro relief is real geometry. Two aligned material samples supply grain without per-pixel ray marching.
#ifdef GL_ES
precision highp float;
#endif
varying vec2 v_diffuseUV;
varying vec3 v_normal;
varying vec4 v_color;
uniform sampler2D u_diffuseTexture;
uniform sampler2D u_normalTexture;
uniform sampler2D u_cliffSurface;
uniform float u_normalMaps;
uniform float u_groundResponse;
uniform float u_materialFamily;
uniform float u_waterEffects;
uniform vec3 u_wind;
vec2 materialA, materialB;
float materialBlend;
float materialBias;

vec2 materialOffset(float seed) {
    return fract(sin(vec2(seed * 127.1 + 23.4, seed * 269.5 + 71.3)) * 43758.5453) * 7.0;
}
vec4 cliffSample(sampler2D map) {
    return mix(texture2D(map, materialA, materialBias), texture2D(map, materialB, materialBias), materialBlend);
}

// The material's luminance retains its grain. Broad, saturated palettes read at strategy-camera distance.
vec3 terrainPalette(vec3 source, float field, bool wall, float family) {
    float grain = clamp(dot(source, vec3(.25, .60, .15)) * 1.6, 0.0, 1.0);
    float tone = clamp(wall ? grain * .64 + field * .36 : .30 + grain * .08 + field * .48, 0.0, 1.0);
    if (family > 8.5) {
        // The silhouette and fault planes carry the outcrop. Keep its texture quiet at play distance.
        vec3 low = family < 9.5 ? vec3(.30, .35, .33) : vec3(.56, .28, .105);
        vec3 high = family < 9.5 ? vec3(.68, .69, .59) : vec3(.90, .61, .29);
        if (family > 10.5) { low = vec3(.30, .39, .44); high = vec3(.66, .73, .75); }
        return mix(low, high, .24 + field * .58 + grain * .08);
    }
    if (family < .5 && !wall) {
        vec3 meadow = mix(vec3(.12, .27, .10), vec3(.58, .68, .32), .10 + field * .80 + grain * .06);
        meadow = mix(meadow, vec3(.52, .45, .24), smoothstep(.68, .90, field) * .34);
        return mix(meadow, vec3(.40, .30, .16) * (.9 + grain * .15), (1.0 - smoothstep(.15, .32, field)) * .8);
    }
    if (family > 1.5 && family < 2.5) {
        return wall ? mix(vec3(.52, .18, .045), vec3(.98, .61, .23), tone)
              : mix(vec3(.77, .40, .10), vec3(1.0, .81, .43), tone);
    }
    if (family > .5 && family < 1.5) {
        return mix(vec3(.43, .17, .055), vec3(.87, .52, .22), tone);
    }
    if (family > 4.5 && family < 5.5) {
        return mix(vec3(.64, .79, .89), vec3(.92, .95, .96), .28 + field * .6 + grain * .03);
    }
    if (family > 3.5 && family < 4.5 && !wall) {
        return mix(vec3(.38, .46, .54), vec3(.80, .82, .79), tone);
    }
    if (family > 7.5) return mix(vec3(.25, .22, .14), vec3(.63, .56, .37), tone);
    if (family > 5.5) return source * vec3(1.17, 1.10, .9);
    vec3 stone = mix(vec3(.25, .32, .39), vec3(.77, .76, .65), tone);
    return mix(stone, stone * vec3(1.12, 1.08, .73), smoothstep(.65, .9, field) * .4);
}

void main() {
    vec3 face = normalize(v_normal);
    vec3 dp1 = dFdx(v_cloudPosition), dp2 = dFdy(v_cloudPosition);
    vec2 duv1 = dFdx(v_diffuseUV), duv2 = dFdy(v_diffuseUV);
    float orientation = duv1.x * duv2.y - duv1.y * duv2.x < 0.0 ? -1.0 : 1.0;
    vec3 tangent = normalize((dp1 * duv2.y - dp2 * duv1.y) * orientation);
    vec3 bitangent = normalize((dp2 * duv1.x - dp1 * duv2.x) * orientation);
    float footprint = max(length(dFdx(v_diffuseUV)), length(dFdy(v_diffuseUV)));
    vec2 world = v_cloudPosition.xy * u_rainScale * 5.0;
    bool wall = u_materialFamily < -.5;
    bool outcrop = u_materialFamily > 8.5;
    // A steep bank below the water shows through it: projected like a wall, its gravel never stretches downhill.
    bool steep = u_materialFamily > 5.5 && u_materialFamily < 6.5 && face.z < .6;
    materialBias = wall ? 0.0 : steep ? 1.0 : outcrop ? 3.0 : 2.5;
    if (wall || steep) {
        // Continuous projections across every hex edge. Their blend never switches a face's UV orientation.
        vec3 p = v_cloudPosition * u_rainScale * (wall ? 5.0 / 8.0 : 5.0 / 3.0);
        p += sin(p.yzx * vec3(.67, .53, .71) + p.zxy * .31) * .08;
        materialA = vec2(p.y, -p.z);
        materialB = vec2(p.x, -p.z) + vec2(.37, .11);
        materialBlend = abs(face.y) / max(abs(face.x) + abs(face.y), .001);
        footprint = max(length(dFdx(p)), length(dFdy(p)));
    } else {
        float cell = texture2D(u_rainNoise, v_diffuseUV * .115).g * 8.0;
        float index = floor(cell);
        vec2 warp = vec2(sin(world.y * .37 + world.x * .19), cos(world.x * .27 - world.y * .23)) * .11;
        materialA = v_diffuseUV + warp + materialOffset(index);
        materialB = v_diffuseUV + warp + materialOffset(index + 1.0);
        materialBlend = smoothstep(.18, .82, fract(cell));
    }
    float detail = smoothstep(55.0, 240.0, 1.0 / max(footprint, .00001)) * u_normalMaps;
    vec4 properties = cliffSample(u_cliffSurface);
    vec3 albedo = cliffSample(u_diffuseTexture).rgb;
    float family = wall ? max(0.0, -u_materialFamily - 2.0) : u_materialFamily;
    float field = meadowField(world);
    albedo = terrainPalette(albedo, field, wall, family);
    vec3 normal = face;
    if (u_normalMaps > .5 && !outcrop) {
        if (wall || steep) {
            vec3 xMap = (texture2D(u_normalTexture, materialA).rgb * 255.0 - 128.0) / 127.0;
            vec3 yMap = (texture2D(u_normalTexture, materialB).rgb * 255.0 - 128.0) / 127.0;
            vec3 perturb = mix(vec3(0.0, xMap.x, -xMap.y), vec3(yMap.x, 0.0, -yMap.y), materialBlend);
            perturb -= face * dot(face, perturb);
            normal = normalize(face + perturb * 1.4 * detail);
        } else {
            vec3 mapped = (cliffSample(u_normalTexture).rgb * 255.0 - 128.0) / 127.0;
            mapped.xy *= outcrop ? .09 : family > 4.5 && family < 5.5 ? .07
                  : family < .5 ? .10 : family > 1.5 && family < 2.5 ? .16 : .22;
            normal = normalize(mix(face, normalize(tangent * mapped.x + bitangent * mapped.y + face * mapped.z), detail));
        }
    }
    bool grass = !wall && family < .5;
    if (grass) {
        // Larger bare patches follow the actual rise of the ground, so color helps describe its landforms.
        float slope = 1.0 - smoothstep(.84, .98, face.z);
        albedo = mix(albedo, vec3(.43, .40, .25), slope * .48);
    }
    if (!wall && family > 7.5 && family < 8.5) {
        float turf = smoothstep(.40, .70, field) * smoothstep(.68, .94, face.z);
        albedo = mix(albedo, terrainPalette(vec3(.5), field, false, 0.0), turf * .72);
    }
    if (outcrop && family > 10.5) {
        albedo = mix(albedo, vec3(.83, .90, .94), smoothstep(.48, .86, face.z) * .93);
    }
    bool bed = family > 5.5 && family < 6.5;
    bool shore = family > 6.5 && family < 7.5;
    if (grass) {
        float sway = sin(world.x * 2.8 + world.y * 1.6 - u_rainTime * 1.9)
              * sin(world.y * 3.7 - u_rainTime * 1.1);
        normal = normalize(normal + vec3(u_wind.xy * .045 * sway * u_wind.z, 0.0) * detail);
        albedo *= 1.0 + sway * .025 * u_wind.z;
    }
    if (!wall && family < 5.5 && face.z > .65) albedo *= terrainGrid(v_cloudPosition.xy * u_rainScale);
    // Anything below the water line, bed or drowned wall, keeps only the hue its column of water passes; the
    // surface above removes the rest. Caustics focus the sunlight that still reaches shallow ground. Rain wets only
    // what stands above the water.
    float submerged = shore ? 0.0 : (1.0 - v_color.b) * WATER_DEPTH_RANGE;
    float wet = u_wetness * step(0.0, u_groundResponse) * (1.0 - smoothstep(0.0, 0.05, submerged));
    if (shore) wet = max(wet, 1.0 - v_color.b);
    float film = wet * max(0.0, u_groundResponse);
    float puddle = 0.0;
    if (wet * u_rainDetail > 0.0 && face.z > .97 && !bed && !shore) {
        vec2 position = v_cloudPosition.xy * u_rainScale;
        puddle = rainPuddle(position, wet, max(0.0, u_groundResponse))
              * smoothstep(.97, .999, face.z) * u_rainDetail;
        normal = normalize(mix(normal, normalize(vec3(rainRipples(position), 1.0)), puddle));
        film = mix(film, 1.0, puddle);
    }
    albedo *= 1.0 - wet * mix(.18, .11, max(0.0, u_groundResponse));
    float caustic = 0.0;
    if (submerged > 0.0) {
        albedo *= waterBedTint(floor(v_color.r * 4.0 + 0.5), submerged);
        caustic = waterBedCaustics(v_cloudPosition.xy * u_rainScale, submerged) * u_rainDetail * u_waterEffects;
    }
#ifdef lightingFlag
    // Linear light, as on every lit surface. The bed tint above linearises with the colour it tints.
    albedo = toLinear(albedo);
    vec3 ambient, direct, sheen;
    surfaceLighting(normal, film, ambient, direct, sheen);
    direct *= 1.0 + caustic;
    // Packed occlusion only accents material crevices.
    ambient *= mix(1.0, mix(wall ? .70 : .94, 1.0, properties.b), u_normalMaps);
    vec3 pigment = albedo;
    albedo *= ambient + direct;
    if (submerged > 0.0) {
        // Water scatters daylight in every direction: below the surface, orientation and shadows soften with depth.
        vec3 sun = sunOnGround() * (1.0 + caustic);
#ifdef cloudShadowFlag
        sun *= cloudLight;
#endif
        vec3 scattered = surfaceAmbient(vec3(0.0, 0.0, 1.0)) + sun;
        albedo = submergedLight(albedo, pigment, scattered, submerged);
    }
    albedo += sheen * mix(.35, 1.0, film);
    albedo = toDisplay(albedo);
    if (puddle > 0.0) albedo = rainReflection(albedo, normal, puddle);
#endif
    gl_FragColor = vec4(albedo, 1.0);
}
