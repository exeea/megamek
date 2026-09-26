// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One surface evaluation for natural tops, slopes, rock faces and deposits. Inserted after the lighting helpers.

struct TerrainMaterial {
    vec3 color;
    vec3 normal;
    float cavity;
    float height;
};

// Competing covers retain their texture rather than becoming a muddy colour crossfade. All channels use the same
// weights; filtering broadens the contact only when its detail becomes subpixel.
vec4 materialWeights(vec4 coverage, vec4 height) {
    vec4 present = step(vec4(.0001), coverage);
    vec4 score = coverage + height * .38 * (4.0 * coverage * (1.0 - coverage)) - (1.0 - present) * 2.0;
    float highest = max(max(score.x, score.y), max(score.z, score.w));
    float width = .13 + min(.2, pixelMetres * .16);
    vec4 weights = max(score - highest + width, vec4(0.0)) * present;
    return weights / max(dot(weights, vec4(1.0)), .00001);
}

// Every role shares projection and metre scale through a bend. No UV origin at an individual hex's rim or foot.
TerrainMaterial sampleMaterial(sampler2D colorMap, sampler2D normalMap,
      float tile, float amount, vec3 world, vec3 face, float variation, float fine, float region, bool groundMap) {
    TerrainMaterial result;
    result.color = vec3(0.0); result.normal = face; result.cavity = 1.0; result.height = 0.0;
    if (amount < .0001) return result;
    vec2 p = vec2(world.x, -world.y);
    vec2 dx = vec2(world.y * sign(face.x), -world.z), dy = vec2(-world.x * sign(face.y), -world.z) + .37 * tile;
    vec2 axes = pow(abs(face.xy), vec2(4.0));
    float side = axes.x / max(axes.x + axes.y, .00001);
    float lying = smoothstep(.25, .87, face.z);
    vec4 pigment = draped(colorMap, p, dx, dy, side, tile, variation, lying);
    vec3 normal = face;
    float cavity = 1.0;
    if (u_normalMaps > .5) {
        if (lying > 0.0) {
            vec4 top = planarNormal(normalMap, p, tile, variation);
            normal = upNormal(top.rgb, face);
            cavity = top.a;
        }
        if (lying < 1.0) {
            vec4 nx = texture2D(normalMap, dx / tile), ny = texture2D(normalMap, dy / tile);
            vec3 x = nx.rgb * 2.0 - 1.0, y = ny.rgb * 2.0 - 1.0;
            vec3 wall = normalize(mix(vec3(-y.x * sign(face.y) + face.x, y.z * face.y, face.z - y.y),
                  vec3(x.z * face.x, x.x * sign(face.x) + face.y, face.z - x.y), side));
            normal = normalize(mix(wall, normal, lying));
            cavity = mix(mix(ny.a, nx.a, side), cavity, lying);
        }
    }
    vec3 color = pigment.rgb;
    if (groundMap) color = groundTone(color, world, variation, fine, region, 0.0, 0.0);
    result.color = toLinear(color);
    result.normal = normal;
    result.cavity = mix(.55, 1.0, cavity);
    result.height = pigment.a;
    return result;
}

TerrainMaterial naturalMaterial(vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region) {
    float up = clamp(face.z, 0.0, 1.0);
    float pockets = texture2D(u_rainNoise, (world.xy + world.z * vec2(.43, .27)) / 92.0 + .57).g;
    float variation = broad * .4 + fine * .25 + pockets * .35;
    float foot = exp(-max(aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, belowRim);
    // Deposits occupy pockets and suitable slopes; a foot is an opportunity, not a mandatory painted ring.
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    float cover = smoothstep(.4, .96, up + (variation - .5) * .55);
    float soil = 0.0;
    if (family(0.0)) {
        soil = (1.0 - cover) * ((1.0 - rock) * .9 + rim * .5 * smoothstep(.25, .7, variation));
        deposit *= mix(.25, 1.0, rock);
    } else if (family(5.0)) {
        cover = smoothstep(.36, .92, up + (variation - .5) * .5 + deposit * .18);
        deposit *= .35;
    } else if (family(2.0)) {
        cover = smoothstep(.38, .94, up + (variation - .5) * .45 + deposit * .15);
        deposit *= .55;
    } else if (family(1.0)) {
        cover = smoothstep(.28, .9, up + (variation - .5) * .16);
        deposit *= .6;
    }
    soil = clamp(soil, 0.0, 1.0 - cover);
    vec4 roles = vec4(cover, soil, deposit, max(0.0, 1.0 - cover - soil));
    roles.xyw *= 1.0 - deposit;
    TerrainMaterial a = sampleMaterial(u_groundColor, u_groundNormal, u_sculptTiles.x, roles.x,
          world, face, broad, fine, region, true);
    TerrainMaterial b = sampleMaterial(u_mantleColor, u_mantleNormal, u_sculptTiles.w, roles.y,
          world, face, fine, fine, region, false);
    TerrainMaterial c = sampleMaterial(u_debrisColor, u_debrisNormal, u_sculptTiles.y, roles.z,
          world, face, fine, fine, region, false);
    TerrainMaterial d = sampleMaterial(u_wallColor, u_wallNormal, u_sculptTiles.z, roles.w,
          world, face, broad, fine, region, false);
    d.color *= toLinear(bedTint(hardness));
    vec4 weights = materialWeights(roles, vec4(a.height, b.height, c.height, d.height));
    TerrainMaterial result;
    result.color = toDisplay(a.color * weights.x + b.color * weights.y + c.color * weights.z + d.color * weights.w);
    result.normal = normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z + d.normal * weights.w);
    result.cavity = dot(vec4(a.cavity, b.cavity, c.cavity, d.cavity), weights);
    result.height = dot(vec4(a.height, b.height, c.height, d.height), weights);
    return result;
}

#ifdef terrainBlendFlag
float coverPatch(vec3 world, float familyId) {
    vec2 offset = familyId * vec2(.17, .31);
    return texture2D(u_rainNoise, world.xy / 150.0 + offset).r * .65
          + texture2D(u_rainNoise, world.xy / 43.0 - offset).b * .35;
}

void blendCovers(vec3 world, vec2 p, vec3 face, float broad, float fine, float region,
      inout vec3 color, inout vec3 normal, inout float cavity, inout float grass,
      inout vec3 bounce, inout float response, inout float rainCover) {
    vec4 a = planar(u_groundColor, p, u_sculptTiles.x, broad);
    vec4 b = planar(u_coverColor1, p, u_coverFamilies.y, broad);
    vec4 c = planar(u_coverColor2, p, u_coverFamilies.w, broad);
    vec3 raw = max(v_coverWeights, vec3(0.0));
    // Broad coverage is shared with vegetation. Height adds small interlocking edges, never a new family.
    vec3 patches = vec3(coverPatch(world, u_sculptFamily), coverPatch(world, u_coverFamilies.x),
          coverPatch(world, u_coverFamilies.z));
    vec3 weights = materialWeights(vec4(raw, 0.0), vec4(vec3(a.a, b.a, c.a) * .45 + patches * 2.2, 0.0)).xyz;
    vec3 ca = groundToneFor(u_sculptFamily, a.rgb, world, broad, fine, region, 0.0, 0.0);
    vec3 cb = groundToneFor(u_coverFamilies.x, b.rgb, world, broad, fine, region, 0.0, 0.0);
    vec3 cc = groundToneFor(u_coverFamilies.z, c.rgb, world, broad, fine, region, 0.0, 0.0);
    vec3 pigment = toDisplay(toLinear(ca) * weights.x + toLinear(cb) * weights.y + toLinear(cc) * weights.z);
    // Where an adjacent cover is substantial, all emitters use exactly the same cover evaluation. At the interior
    // this eases back to that family's full ground/rock/deposit treatment without a special boundary stripe.
    float amount = smoothstep(0.0, .24, raw.y + raw.z);
    color = mix(color, pigment, amount);
    if (u_normalMaps > .5) {
        vec4 na = planarNormal(u_groundNormal, p, u_sculptTiles.x, broad);
        vec4 nb = planarNormal(u_coverNormal1, p, u_coverFamilies.y, broad);
        vec4 nc = planarNormal(u_coverNormal2, p, u_coverFamilies.w, broad);
        normal = normalize(mix(normal, upNormal(na.rgb * weights.x + nb.rgb * weights.y + nc.rgb * weights.z, face), amount));
        cavity = mix(cavity, mix(.55, 1.0, na.a * weights.x + nb.a * weights.y + nc.a * weights.z), amount);
    }
    grass = (1.0 - step(.5, abs(u_sculptFamily))) * weights.x
          + (1.0 - step(.5, abs(u_coverFamilies.x))) * weights.y
          + (1.0 - step(.5, abs(u_coverFamilies.z))) * weights.z;
    bounce = groundBounceFor(u_sculptFamily) * weights.x + groundBounceFor(u_coverFamilies.x) * weights.y
          + groundBounceFor(u_coverFamilies.z) * weights.z;
    vec3 responses = vec3(u_groundResponse, u_coverResponses);
    response = dot(max(responses, vec3(0.0)), weights);
    rainCover = dot(step(vec3(0.0), responses), weights);
}
#endif
