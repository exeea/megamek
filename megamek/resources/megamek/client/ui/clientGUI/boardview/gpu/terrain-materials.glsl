// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One surface evaluation for natural tops, slopes, rock faces and deposits. Inserted after the lighting helpers.

struct TerrainMaterial {
    vec3 color;
    vec3 normal;
    float cavity;
    float height;
    vec3 emission;
    float roughness;
    float volcanic;
};

float materialBlendWidth() { return .13 + min(.2, pixelMetres * .16); }

// Competing covers retain their texture rather than becoming a muddy colour crossfade. All channels use the same
// weights; filtering broadens the contact only when its detail becomes subpixel.
vec4 materialWeights(vec4 coverage, vec4 height) {
    vec4 present = step(vec4(.0001), coverage);
    vec4 score = coverage + height * .38 * (4.0 * coverage * (1.0 - coverage)) - (1.0 - present) * 2.0;
    float highest = max(max(score.x, score.y), max(score.z, score.w));
    float width = materialBlendWidth();
    vec4 weights = max(score - highest + width, vec4(0.0)) * present;
    return weights / max(dot(weights, vec4(1.0)), .00001);
}

// Calculate derivatives before branching on material weights. Implicit mip derivatives inside those branches
// are undefined when neighbouring fragments choose different layers, producing speckles at their boundaries.
struct MaterialProjection {
    vec2 top, x, y;
    mat2 topGradient, xGradient, yGradient;
    float side, lying;
};

MaterialProjection materialProjection(vec3 world, vec3 face) {
    MaterialProjection p;
    p.top = vec2(world.x, -world.y);
    p.x = vec2(world.y * sign(face.x), -world.z);
    p.y = vec2(-world.x * sign(face.y), -world.z);
    p.topGradient = mat2(dFdx(p.top), dFdy(p.top));
    p.xGradient = mat2(dFdx(p.x), dFdy(p.x));
    p.yGradient = mat2(dFdx(p.y), dFdy(p.y));
    vec2 axes = pow(abs(face.xy), vec2(4.0));
    p.side = axes.x / max(axes.x + axes.y, .00001);
    p.lying = smoothstep(.25, .87, face.z);
    return p;
}

// A role's map is a layer of u_terrainLayers: colour/height, and normal/AO at the next layer.
vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {
    return textureGrad(u_terrainLayers, vec3(uv, layer), gradient[0], gradient[1]);
}

// Earth banks need the same repeat breakup as flat dirt. Keep both vertical projections upright, so erosion
// runs downhill; translating and rescaling also lets color/height and normal/AO share this exact sampling.
vec4 earthSideTexel(float layer, vec2 uv, mat2 gradient, float breakup) {
    vec4 texel = materialTexel(layer, uv, gradient);
    if (breakup > 0.0) {
        texel = mix(texel, materialTexel(layer, uv / 2.37 + vec2(.31, .57), gradient / 2.37), breakup);
    }
    return texel;
}

// Every role shares projection and metre scale through a bend. No UV origin at an individual hex's rim or foot.
vec4 sampleMaterial(float colorMap, float tile, float amount, MaterialProjection p,
      vec3 world, float variation, float fine, float region, bool groundMap, float familyId, bool earth) {
    if (amount < .0001) return vec4(0.0);
    vec4 pigment = vec4(0.0);
    if (p.lying > 0.0) {
        pigment = mix(materialTexel(colorMap, p.top / tile, p.topGradient / tile),
              materialTexel(colorMap, TURN * p.top / (tile * 2.37) + .31,
                    TURN * p.topGradient / (tile * 2.37)), variation);
        if (farDetail > 0.0) {
            pigment = mix(pigment, materialTexel(colorMap, p.top / tile, p.topGradient * (4096.0 / tile)), farDetail);
        }
    }
    if (p.lying < 1.0) {
        float breakup = earth ? variation : 0.0;
        vec4 steep = mix(earthSideTexel(colorMap, p.y / tile + .37, p.yGradient / tile, breakup),
              earthSideTexel(colorMap, p.x / tile, p.xGradient / tile, breakup), p.side);
        pigment = mix(steep, pigment, p.lying);
    }
    vec3 color = pigment.rgb;
    if (groundMap) color = groundToneFor(familyId, color, world, variation, fine, region, 0.0, 0.0);
    return vec4(toLinear(color), pigment.a);
}

// Height blending often discards a present layer entirely. Its normal/cavity cannot affect the result then.
// Defer those texture reads until the colour/height samples have determined the final weights.
vec4 materialNormal(float normalMap, float tile, float weight, MaterialProjection p, vec3 face, float variation, bool earth) {
    if (weight <= 0.0 || u_normalMaps <= .5) return vec4(face, 1.0);
    if (terrainNormalDetail <= 0.0) return vec4(face, TERRAIN_DISTANT_CAVITY);
    vec3 normal = face;
    float cavity = 1.0;
    if (p.lying > 0.0) {
        vec4 near = materialTexel(normalMap, p.top / tile, p.topGradient / tile);
        vec4 far = materialTexel(normalMap, TURN * p.top / (tile * 2.37) + .31,
              TURN * p.topGradient / (tile * 2.37));
        vec3 a = near.rgb * 2.0 - 1.0, b = far.rgb * 2.0 - 1.0;
        b.xy = b.xy * TURN;
        normal = upNormal(mix(mix(a, b, variation), vec3(0.0, 0.0, 1.0), farDetail), face);
        cavity = mix(near.a, far.a, variation);
    }
    if (p.lying < 1.0) {
        float breakup = earth ? variation : 0.0;
        vec4 nx = earthSideTexel(normalMap, p.x / tile, p.xGradient / tile, breakup);
        vec4 ny = earthSideTexel(normalMap, p.y / tile + .37, p.yGradient / tile, breakup);
        vec3 x = nx.rgb * 2.0 - 1.0, y = ny.rgb * 2.0 - 1.0;
        vec3 wall = normalize(mix(vec3(-y.x * sign(face.y) + face.x, y.z * face.y, face.z - y.y),
              vec3(x.z * face.x, x.x * sign(face.x) + face.y, face.z - x.y), p.side));
        normal = normalize(mix(wall, normal, p.lying));
        cavity = mix(mix(ny.a, nx.a, p.side), cavity, p.lying);
    }
    return vec4(normalize(mix(face, normal, terrainNormalDetail)),
          mix(TERRAIN_DISTANT_CAVITY, mix(.55, 1.0, cavity), terrainNormalDetail));
}

TerrainMaterial naturalMaterialFor(vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region, float familyId, vec4 tiles, vec4 layers, float sediment) {
#ifdef volcanicFlag
    if (familyId > VOLCANIC_CRUST_FAMILY - .5) {
        Volcanic material = magmaSurface(world, face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
        return TerrainMaterial(material.albedo, material.normal, material.surface.b, material.surface.r,
              magmaEmission(material.heat, familyId > VOLCANIC_BANK_FAMILY - .5 ? magmaBankHeat(v_cloudPosition.xy) : 1.0),
              material.surface.g, 1.0);
    }
#endif
    MaterialProjection projection = materialProjection(world, face);
    float up = clamp(face.z, 0.0, 1.0);
    float pockets = texture(u_rainNoise, (world.xy + world.z * vec2(.43, .27)) / 92.0 + .57).g;
    float variation = broad * .4 + fine * .25 + pockets * .35;
    float foot = exp(-max(aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, belowRim);
    // Deposits occupy pockets and suitable slopes; a foot is an opportunity, not a mandatory painted ring.
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    // Break a bank-to-cliff contact into soil pockets and exposed rock. Both sides use the same world field;
    // pure banks/cliffs stay pure, and distance filtering already controls the final height blend.
    float exposure = clamp(rock + (variation - .5) * 1.2 * (4.0 * rock * (1.0 - rock)), 0.0, 1.0);
    // A sparse bank mesh carries its broad slope, not every eroded patch. Expose its mantle with the existing
    // world-space detail field, so sand and turf do not hide all the material detail when the mesh is simplified.
    // Level ground keeps its cover; hard cliffs already get their exposed rock from the existing material mix.
    float bank = (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up));
    float wear = bank * smoothstep(.32, .68, fine * .65 + pockets * .35);
    float coverUp = up - .2 * wear;
    float cover = smoothstep(.4, .96, coverUp + (variation - .5) * .55);
    float soil = 0.0;
    if (abs(familyId) < .5) {
        // The two-level earth bank keeps a ragged turf lip and broken turf on its real intermediate shoulder.
        // Color remains legible from above when individual blades are too small to draw.
        float levelMetres = u_levelHeight / u_metre;
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((aboveFoot + belowRim) / levelMetres - 2.0));
        float fray = texture(u_rainNoise, (world.xy + world.z * vec2(.29, .41)) / 28.0 + .217).r;
        float lipDepth = levelMetres * (mix(.2, .65, fine * .45 + pockets * .55) + .14 * (fray - .5));
        float lip = 1.0 - smoothstep(.05, lipDepth, belowRim);
        float middle = abs(aboveFoot / levelMetres - 1.0 - (pockets - .5) * .18);
        float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
        float shoulder = 1.0 - smoothstep(width * .25, width, middle);
        shoulder *= smoothstep(.35, .73, up) * smoothstep(.22, .55, pockets * .7 + fine * .3);
        cover = max(cover, max(lip, shoulder) * twoLevels * (1.0 - exposure));
        soil = (1.0 - cover) * ((1.0 - exposure) * .9 + rim * .5 * smoothstep(.25, .7, variation));
        deposit *= mix(.25, 1.0, rock);
    } else if (abs(familyId - 5.0) < .5) {
        cover = smoothstep(.36, .92, coverUp + (variation - .5) * .5 + deposit * .18);
        deposit *= .35;
    } else if (abs(familyId - 2.0) < .5) {
        cover = smoothstep(.38, .94, coverUp + (variation - .5) * .45 + deposit * .15);
        deposit *= .55;
    } else if (abs(familyId - 1.0) < .5) {
        cover = smoothstep(.28, .9, coverUp + (variation - .5) * .16);
        soil = (1.0 - cover) * (1.0 - exposure);
        deposit *= .6;
    }
    soil = clamp(soil, 0.0, 1.0 - cover);
    vec4 roles = vec4(cover, soil, deposit, max(0.0, 1.0 - cover - soil));
    roles.xyw *= 1.0 - deposit;
    // Concrete transported onto another surface is loose aggregate, never a second paved slab.
    // The constructed face itself keeps the existing panel material, supplied by blendCovers below.
    if (abs(familyId - 4.0) < .5) roles = vec4(0.0, 0.0, 1.0, 0.0);
    // Submerged contacts exchange bed sediment and exposed stone, never living turf or windblown surface sand.
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, aboveFoot);
    roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    // Height maps are in [0,1]. Even their largest possible score cannot rescue these layers, so avoid all
    // their texture work while retaining the original roles for the exact final blend calculation.
    float highestMinimum = max(max(roles.x, roles.y), max(roles.z, roles.w));
    float width = materialBlendWidth();
    vec4 candidates = step(vec4(highestMinimum - width), roles + .38 * (4.0 * roles * (1.0 - roles)));
    bool dirt = abs(familyId - 1.0) < .5;
    bool earthMantle = dirt || abs(familyId) < .5;
    vec4 a = sampleMaterial(layers.x, tiles.x, roles.x * candidates.x, projection,
          world, broad, fine, region, true, familyId, dirt);
    vec4 b = sampleMaterial(layers.w, tiles.w, roles.y * candidates.y, projection,
          world, fine, fine, region, false, familyId, earthMantle);
    vec4 c = sampleMaterial(layers.y, tiles.y, roles.z * candidates.z, projection,
          world, fine, fine, region, false, familyId, false);
    vec4 d = sampleMaterial(layers.z, tiles.z, roles.w * candidates.w, projection,
          world, broad, fine, region, false, familyId, false);
    d.rgb *= toLinear(bedTintFor(familyId, hardness));
    vec4 weights = materialWeights(roles, vec4(a.a, b.a, c.a, d.a));
    vec4 na = materialNormal(layers.x + 1.0, tiles.x, weights.x, projection, face, broad, dirt);
    vec4 nb = materialNormal(layers.w + 1.0, tiles.w, weights.y, projection, face, fine, earthMantle);
    vec4 nc = materialNormal(layers.y + 1.0, tiles.y, weights.z, projection, face, fine, false);
    vec4 nd = materialNormal(layers.z + 1.0, tiles.z, weights.w, projection, face, broad, false);
    TerrainMaterial result;
    result.color = toDisplay(a.rgb * weights.x + b.rgb * weights.y + c.rgb * weights.z + d.rgb * weights.w);
    result.normal = normalize(na.rgb * weights.x + nb.rgb * weights.y + nc.rgb * weights.z + nd.rgb * weights.w);
    result.cavity = dot(vec4(na.a, nb.a, nc.a, nd.a), weights);
    result.height = dot(vec4(a.a, b.a, c.a, d.a), weights);
    result.emission = vec3(0.0);
    result.roughness = .9;
    result.volcanic = 0.0;
    return result;
}

TerrainMaterial naturalMaterial(vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region, float sediment) {
    return naturalMaterialFor(world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          u_sculptFamily, u_sculptTiles, u_sculptLayers, sediment);
}

#ifdef terrainBlendFlag
float coverPatch(vec3 world, float familyId) {
    vec2 offset = familyId * vec2(.17, .31);
    vec2 contact = world.xy + world.z * vec2(.47, .29);
    return texture(u_rainNoise, contact / 37.0 + offset).r * .65
          + texture(u_rainNoise, contact / 9.5 - offset).b * .35;
}

void blendCovers(vec3 world, vec3 face, float foot, float rim, float rock, float hardness, float sediment,
      float broad, float fine, float region,
      inout vec3 color, inout vec3 normal, inout float cavity, out float height, inout float grass,
      inout vec3 bounce, inout float response, inout float rainCover,
      out vec3 emission, out float roughness, out float volcanic) {
    TerrainMaterial a;
    if (abs(u_coverFamilies.x - 4.0) < .5) {
        a = TerrainMaterial(color, normal, cavity, .5, vec3(0.0), .9, 0.0);
    } else {
        a = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
              u_coverFamilies.x, u_coverTiles0, u_coverLayers0, sediment);
    }
    TerrainMaterial b = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
          u_coverFamilies.y, u_coverTiles1, u_coverLayers1, sediment);
    TerrainMaterial c = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
          u_coverFamilies.z, u_coverTiles2, u_coverLayers2, sediment);
    TerrainMaterial d = a;
    if (u_coverFamilies.w != u_coverFamilies.x) {
        d = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
              u_coverFamilies.w, u_coverTiles3, u_coverLayers3, sediment);
    }
    vec4 raw = max(v_coverWeights, vec4(0.0));
#ifdef volcanicFlag
    // Within the lava's margin a cooled bank in the palette takes the contact, and magmaBankHeat keeps its
    // fissures glowing: a buffer of hot crust between the melt and the ground beside it.
    vec4 bank = vec4(greaterThan(u_coverFamilies, vec4(VOLCANIC_BANK_FAMILY - .5)));
    if (u_magmaFieldMap.x > 0.0 && dot(bank, vec4(1.0)) > 0.0) {
        raw = mix(raw, bank, 1.0 - smoothstep(0.0, MAGMA_MARGIN, -magmaShore(magmaField(v_cloudPosition.xy))));
    }
#endif
    // Broad coverage is shared with vegetation. Height adds small interlocking edges, never a new family.
    vec4 patches = vec4(coverPatch(world, u_coverFamilies.x), coverPatch(world, u_coverFamilies.y),
          coverPatch(world, u_coverFamilies.z), coverPatch(world, u_coverFamilies.w));
    float patchStrength = mix(1.5, 2.4, 1.0 - smoothstep(.35, .85, face.z));
    vec4 weights = materialWeights(raw, vec4(a.height, b.height, c.height, d.height) * .45 + patches * patchStrength);
    // Authored gradients interpolate whole materials, including color; retain sharp height contacts elsewhere.
    vec4 proportions = raw / max(dot(raw, vec4(1.0)), .00001);
    weights = mix(weights, proportions, clamp(v_coverInterpolation, 0.0, 1.0));
    color = toDisplay(toLinear(a.color) * weights.x + toLinear(b.color) * weights.y
          + toLinear(c.color) * weights.z + toLinear(d.color) * weights.w);
    normal = normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z + d.normal * weights.w);
    cavity = dot(vec4(a.cavity, b.cavity, c.cavity, d.cavity), weights);
    height = dot(vec4(a.height, b.height, c.height, d.height), weights);
    emission = a.emission * weights.x + b.emission * weights.y + c.emission * weights.z + d.emission * weights.w;
    roughness = dot(vec4(a.roughness, b.roughness, c.roughness, d.roughness), weights);
    volcanic = dot(vec4(a.volcanic, b.volcanic, c.volcanic, d.volcanic), weights);
    grass = dot(vec4(1.0) - step(vec4(.5), abs(u_coverFamilies)), weights) * (1.0 - sediment);
    bounce = groundBounceFor(u_coverFamilies.x) * weights.x + groundBounceFor(u_coverFamilies.y) * weights.y
          + groundBounceFor(u_coverFamilies.z) * weights.z + groundBounceFor(u_coverFamilies.w) * weights.w;
    response = dot(max(u_coverResponses, vec4(0.0)), weights);
    rainCover = dot(step(vec4(0.0), u_coverResponses), weights);
}
#endif
