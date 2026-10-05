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

// Overlapping translated samples break the fungal source's recognisable repeats. Use identical offsets for
// colour/height and normal/AO; translations keep tangent normals aligned and leave vertical cliff grain upright.
vec2 materialOffset(vec2 cell) {
    return fract(sin(vec2(dot(cell, vec2(127.1, 311.7)), dot(cell, vec2(269.5, 183.3)))) * 43758.5453) * 8.0;
}

vec4 fungusTexel(float layer, vec2 uv, mat2 gradient) {
    vec2 cell = floor(uv), blend = smoothstep(.15, .85, fract(uv));
    vec4 a = materialTexel(layer, uv + materialOffset(cell), gradient);
    vec4 b = materialTexel(layer, uv + materialOffset(cell + vec2(1.0, 0.0)), gradient);
    vec4 c = materialTexel(layer, uv + materialOffset(cell + vec2(0.0, 1.0)), gradient);
    vec4 d = materialTexel(layer, uv + materialOffset(cell + vec2(1.0, 1.0)), gradient);
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

// Select neighbouring source offsets from a continuous world field, not the UV tile or hex ID. Two reads keep the
// original feature size and wind/bedding direction without the recognisable motif repeating on a square grid.
// Color/height and normal/AO must use the same selector; a translation does not rotate or resize their normals.
vec4 translatedTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    float choice = variation * 8.0, index = floor(choice);
    vec4 a = materialTexel(layer, uv + materialOffset(vec2(index, 0.0)), gradient);
    vec4 b = materialTexel(layer, uv + materialOffset(vec2(index + 1.0, 0.0)), gradient);
    return mix(a, b, smoothstep(.2, .8, fract(choice)));
}

// The ground's variation field is constant down a vertical face. Advance the source selection with projected
// height as well, otherwise a tall cliff repeats the same two patches at every map period. Blends use a different
// interval from the source repeat; both maps keep their scale and upright grain, with the same two texture reads.
vec4 cliffTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    return translatedTexel(layer, uv, gradient, variation + uv.y * .173);
}

// Every role shares projection and metre scale through a bend. No UV origin at an individual hex's rim or foot.
vec4 sampleMaterial(float colorMap, float tile, float amount, MaterialProjection p,
      vec3 world, float variation, float fine, float region, bool groundMap, float familyId) {
    if (amount < .0001) return vec4(0.0);
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    vec4 pigment = vec4(0.0);
    if (p.lying > 0.0) {
        pigment = fungus ? fungusTexel(colorMap, p.top / tile, p.topGradient / tile)
              : groundMap && abs(familyId - 2.0) < .5
              ? translatedTexel(colorMap, p.top / tile, p.topGradient / tile, variation)
              : mix(materialTexel(colorMap, p.top / tile, p.topGradient / tile),
              materialTexel(colorMap, TURN * p.top / (tile * 2.37) + .31,
                    TURN * p.topGradient / (tile * 2.37)), variation);
        if (farDetail > 0.0) {
            pigment = mix(pigment, materialTexel(colorMap, p.top / tile, p.topGradient * (4096.0 / tile)), farDetail);
        }
    }
    if (p.lying < 1.0) {
        vec4 steep = fungus ? mix(fungusTexel(colorMap, p.y / tile + .37, p.yGradient / tile),
              fungusTexel(colorMap, p.x / tile, p.xGradient / tile), p.side)
              : mix(cliffTexel(colorMap, p.y / tile + .37, p.yGradient / tile, variation),
                    cliffTexel(colorMap, p.x / tile, p.xGradient / tile, variation), p.side);
        pigment = mix(steep, pigment, p.lying);
    }
    vec3 color = pigment.rgb;
    if (groundMap) color = groundToneFor(familyId, color, world, variation, fine, region, 0.0, 0.0);
    if (abs(familyId - 2.0) < .5) {
        float mineral = dot(color, vec3(.2126, .7152, .0722));
        color = mix(color, mineral * vec3(.86, .92, .98), greySand());
    }
    return vec4(toLinear(color), pigment.a);
}

// Height blending often discards a present layer entirely. Its normal/cavity cannot affect the result then.
// Defer those texture reads until the colour/height samples have determined the final weights.
vec4 materialNormal(float normalMap, float tile, float weight, MaterialProjection p, vec3 face, float variation,
      bool fungus, bool sand) {
    if (weight <= 0.0 || u_normalMaps <= .5) return vec4(face, 1.0);
    if (terrainNormalDetail <= 0.0) return vec4(face, TERRAIN_DISTANT_CAVITY);
    vec3 normal = face;
    float cavity = 1.0;
    if (p.lying > 0.0) {
        vec4 near = fungus ? fungusTexel(normalMap, p.top / tile, p.topGradient / tile)
              : sand ? translatedTexel(normalMap, p.top / tile, p.topGradient / tile, variation)
              : materialTexel(normalMap, p.top / tile, p.topGradient / tile);
        vec4 far = fungus || sand ? near : materialTexel(normalMap, TURN * p.top / (tile * 2.37) + .31,
                    TURN * p.topGradient / (tile * 2.37));
        vec3 detail = fungus || sand ? mix(near.rgb * 2.0 - 1.0, vec3(0.0, 0.0, 1.0), farDetail)
              : planarNormal(near, far, variation).rgb;
        normal = upNormal(detail, face);
        cavity = mix(near.a, far.a, variation);
    }
    if (p.lying < 1.0) {
        vec4 nx = fungus ? fungusTexel(normalMap, p.x / tile, p.xGradient / tile)
              : cliffTexel(normalMap, p.x / tile, p.xGradient / tile, variation);
        vec4 ny = fungus ? fungusTexel(normalMap, p.y / tile + .37, p.yGradient / tile)
              : cliffTexel(normalMap, p.y / tile + .37, p.yGradient / tile, variation);
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
    // Exposed rock shoulders lose loose cover, while sheltered patches retain it. Plateau and face use the
    // same rim distance and world field, so the weathered contact continues over the crest.
    float scour = (1.0 - smoothstep(.1, 3.0 + 4.0 * broad, belowRim))
          * exposure * smoothstep(.30, .65, broad * .6 + pockets * .4);
    // A sparse bank mesh carries its broad slope, not every eroded patch. Expose its mantle with the existing
    // world-space detail field, so sand and turf do not hide all the material detail when the mesh is simplified.
    // Level ground keeps its cover; hard cliffs already get their exposed rock from the existing material mix.
    float bank = (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up));
    float wear = bank * smoothstep(.32, .68, fine * .65 + pockets * .35);
    float coverUp = up - .2 * wear;
    float cover = smoothstep(.4, .96, coverUp + (variation - .5) * .55);
    float soil = 0.0;
    if (abs(familyId) < .5 || abs(familyId - TROPICAL_FAMILY) < .5) {
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
    } else if (abs(familyId - FUNGUS_FAMILY) < .5) {
        // A's cyan crust caps the ridges; mauve fibrous tissue drapes their slopes, with exposed violet rock on
        // tall cliffs. The level plain keeps its own quiet mineral skin instead of coating every face alike.
        float levelMetres = u_levelHeight / u_metre;
        float rise = smoothstep(.25, .9, (aboveFoot + belowRim) / levelMetres);
        float raised = smoothstep(.15, 1.3, v_cloudPosition.z / u_levelHeight);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * pockets, belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.23, .64, pockets * .65 + broad * .35);
        deposit = smoothstep(.48, .88, up) * mix(.10 * colonies, .62 + .36 * colonies, growth);
        cover = smoothstep(.76, .97, up);
        soil = (1.0 - cover) * (1.0 - exposure);
    } else if (abs(familyId - 5.0) < .5) {
        cover = smoothstep(.36, .92, coverUp + (variation - .5) * .5 + deposit * .18);
        cover *= 1.0 - .85 * scour;
        deposit *= .35;
    } else if (abs(familyId - 2.0) < .5) {
        cover = smoothstep(.38, .94, coverUp + (variation - .5) * .45 + deposit * .15);
        cover *= 1.0 - .92 * scour;
        deposit *= .55;
    } else if (abs(familyId - DESERT_FAMILY) < .5 || abs(familyId - MARS_FAMILY) < .5
          || abs(familyId - VOLCANO_FAMILY) < .5) {
        // Firm crust wears back to exposed rock on banks and a broken mineral shoulder at the crest.
        cover = smoothstep(.38, .96, coverUp + (variation - .5) * .22);
        float shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, belowRim))
              * smoothstep(.28, .65, pockets) * smoothstep(.55, .94, up);
        cover *= 1.0 - max(.90 * scour, .85 * shoulder);
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
    vec4 a = sampleMaterial(layers.x, tiles.x, roles.x * candidates.x, projection,
          world, broad, fine, region, true, familyId);
    vec4 b = sampleMaterial(layers.w, tiles.w, roles.y * candidates.y, projection,
          world, fine, fine, region, false, familyId);
    vec4 c = sampleMaterial(layers.y, tiles.y, roles.z * candidates.z, projection,
          world, fine, fine, region, false, familyId);
    vec4 d = sampleMaterial(layers.z, tiles.z, roles.w * candidates.w, projection,
          world, broad, fine, region, false, familyId);
    d.rgb *= toLinear(bedTintFor(familyId, hardness));
    if (abs(familyId - DESERT_FAMILY) < .5) {
        // Pale abraded sandstone at the actual rim reads from above; deeper faces retain their warm brown.
        float abrasion = (1.0 - smoothstep(.15, 2.2, belowRim)) * smoothstep(.45, .95, up);
        d.rgb *= toLinear(mix(vec3(.98, .91, .85), vec3(1.14, 1.15, 1.13), abrasion));
    }
    vec4 weights = materialWeights(roles, vec4(a.a, b.a, c.a, d.a));
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    vec4 na = materialNormal(layers.x + 1.0, tiles.x, weights.x, projection, face, broad, fungus,
          abs(familyId - 2.0) < .5);
    vec4 nb = materialNormal(layers.w + 1.0, tiles.w, weights.y, projection, face, fine, fungus, false);
    vec4 nc = materialNormal(layers.y + 1.0, tiles.y, weights.z, projection, face, fine, fungus, false);
    vec4 nd = materialNormal(layers.z + 1.0, tiles.z, weights.w, projection, face, broad, fungus, false);
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
    if (familyId >= VOLCANIC_CRUST_FAMILY) familyId = 11.0 + familyId - VOLCANIC_CRUST_FAMILY;
    vec2 offset = familyId * vec2(.17, .31);
    vec2 contact = world.xy + world.z * vec2(.47, .29);
    return texture(u_rainNoise, contact / 37.0 + offset).r * .65
          + texture(u_rainNoise, contact / 9.5 - offset).b * .35;
}

void blendCovers(vec3 world, vec3 face, float foot, float rim, float rock, float hardness, float sediment,
      float broad, float fine, float region,
      inout vec3 color, inout vec3 normal, inout float cavity, out float height, inout float grass, inout float sand,
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
    // Soften neighbouring covers without losing their interlocking texture. Authored tropical mixtures keep
    // their dark litter patches in the interior; fade that exception with its actual contribution, never the
    // triangle's palette membership, so a zero-weight tropical slot cannot introduce a material seam.
    vec4 proportions = raw / max(dot(raw, vec4(1.0)), .00001);
    float authored = clamp(v_coverInterpolation, 0.0, 1.0);
    float tropical = dot(proportions, vec4(lessThan(abs(u_coverFamilies - TROPICAL_FAMILY), vec4(.5))));
    float gradient = mix(.55, 1.0, authored) * (1.0 - authored * smoothstep(0.0, .15, tropical));
    weights = mix(weights, proportions, gradient);
    // Loose deposits leave a few windows onto this tile's actual substrate. Transfer coverage, not just colour,
    // so exposed turf/rock keeps its own normals, height, cavity and wet response. The CPU uses this same field
    // for grass roots; neither the board's SAND flag nor the terrain/support mesh changes.
    vec4 sandy = vec4(lessThan(abs(u_coverFamilies - 2.0), vec4(.5)));
    vec4 substrate = vec4(lessThan(abs(u_coverFamilies - u_sculptFamily), vec4(.5)));
    float substrateCount = dot(substrate, vec4(1.0));
    if (abs(u_sculptFamily - 2.0) > .5 && substrateCount > 0.0) {
        float exposed = sandExposure(world.xy) * (1.0 - sediment);
        float amount = dot(weights, sandy) * exposed;
        weights = weights * (1.0 - sandy * exposed) + substrate * (amount / substrateCount);
    }
    color = toDisplay(toLinear(a.color) * weights.x + toLinear(b.color) * weights.y
          + toLinear(c.color) * weights.z + toLinear(d.color) * weights.w);
    normal = normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z + d.normal * weights.w);
    cavity = dot(vec4(a.cavity, b.cavity, c.cavity, d.cavity), weights);
    height = dot(vec4(a.height, b.height, c.height, d.height), weights);
    emission = a.emission * weights.x + b.emission * weights.y + c.emission * weights.z + d.emission * weights.w;
    roughness = dot(vec4(a.roughness, b.roughness, c.roughness, d.roughness), weights);
    volcanic = dot(vec4(a.volcanic, b.volcanic, c.volcanic, d.volcanic), weights);
    grass = dot(vec4(1.0) - step(vec4(.5), abs(u_coverFamilies)), weights) * (1.0 - sediment);
    sand = dot(vec4(1.0) - step(vec4(.5), abs(u_coverFamilies - 2.0)), weights);
    bounce = groundBounceFor(u_coverFamilies.x) * weights.x + groundBounceFor(u_coverFamilies.y) * weights.y
          + groundBounceFor(u_coverFamilies.z) * weights.z + groundBounceFor(u_coverFamilies.w) * weights.w;
    response = dot(max(u_coverResponses, vec4(0.0)), weights);
    rainCover = dot(step(vec4(0.0), u_coverResponses), weights);
}
#endif
