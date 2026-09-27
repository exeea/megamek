// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One surface evaluation for natural tops, slopes, rock faces and deposits. Inserted after the lighting helpers.

struct TerrainMaterial {
    vec3 color;
    vec3 normal;
    float cavity;
    float height;
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

#ifdef terrainBlendFlag
#define MaterialMap float
#define GROUND_MAP layers.x
#define MANTLE_MAP layers.w
#define DEBRIS_MAP layers.y
#define WALL_MAP layers.z
#define GROUND_NORMAL (layers.x + 1.0)
#define MANTLE_NORMAL (layers.w + 1.0)
#define DEBRIS_NORMAL (layers.y + 1.0)
#define WALL_NORMAL (layers.z + 1.0)
vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {
    return textureGrad(u_terrainLayers, vec3(uv, layer), gradient[0], gradient[1]);
}
#else
#define MaterialMap sampler2D
#define GROUND_MAP u_groundColor
#define MANTLE_MAP u_mantleColor
#define DEBRIS_MAP u_debrisColor
#define WALL_MAP u_wallColor
#define GROUND_NORMAL u_groundNormal
#define MANTLE_NORMAL u_mantleNormal
#define DEBRIS_NORMAL u_debrisNormal
#define WALL_NORMAL u_wallNormal
vec4 materialTexel(MaterialMap map, vec2 uv, mat2 gradient) {
    return textureGrad(map, uv, gradient[0], gradient[1]);
}
#endif

// Every role shares projection and metre scale through a bend. No UV origin at an individual hex's rim or foot.
vec4 sampleMaterial(MaterialMap colorMap, float tile, float amount, MaterialProjection p,
      vec3 world, float variation, float fine, float region, bool groundMap, float familyId) {
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
        vec4 steep = mix(materialTexel(colorMap, p.y / tile + .37, p.yGradient / tile),
              materialTexel(colorMap, p.x / tile, p.xGradient / tile), p.side);
        pigment = mix(steep, pigment, p.lying);
    }
    vec3 color = pigment.rgb;
    if (groundMap) color = groundToneFor(familyId, color, world, variation, fine, region, 0.0, 0.0);
    return vec4(toLinear(color), pigment.a);
}

// Height blending often discards a present layer entirely. Its normal/cavity cannot affect the result then.
// Defer those texture reads until the colour/height samples have determined the final weights.
vec4 materialNormal(MaterialMap normalMap, float tile, float weight, MaterialProjection p, vec3 face, float variation) {
    if (weight <= 0.0 || u_normalMaps <= .5) return vec4(face, 1.0);
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
        vec4 nx = materialTexel(normalMap, p.x / tile, p.xGradient / tile);
        vec4 ny = materialTexel(normalMap, p.y / tile + .37, p.yGradient / tile);
        vec3 x = nx.rgb * 2.0 - 1.0, y = ny.rgb * 2.0 - 1.0;
        vec3 wall = normalize(mix(vec3(-y.x * sign(face.y) + face.x, y.z * face.y, face.z - y.y),
              vec3(x.z * face.x, x.x * sign(face.x) + face.y, face.z - x.y), p.side));
        normal = normalize(mix(wall, normal, p.lying));
        cavity = mix(mix(ny.a, nx.a, p.side), cavity, p.lying);
    }
    return vec4(normal, mix(.55, 1.0, cavity));
}

TerrainMaterial naturalMaterialFor(vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region, float familyId, vec4 tiles, vec4 layers, float sediment) {
    MaterialProjection projection = materialProjection(world, face);
    float up = clamp(face.z, 0.0, 1.0);
    float pockets = texture2D(u_rainNoise, (world.xy + world.z * vec2(.43, .27)) / 92.0 + .57).g;
    float variation = broad * .4 + fine * .25 + pockets * .35;
    float foot = exp(-max(aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, belowRim);
    // Deposits occupy pockets and suitable slopes; a foot is an opportunity, not a mandatory painted ring.
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    float cover = smoothstep(.4, .96, up + (variation - .5) * .55);
    float soil = 0.0;
    if (abs(familyId) < .5) {
        soil = (1.0 - cover) * ((1.0 - rock) * .9 + rim * .5 * smoothstep(.25, .7, variation));
        deposit *= mix(.25, 1.0, rock);
    } else if (abs(familyId - 5.0) < .5) {
        cover = smoothstep(.36, .92, up + (variation - .5) * .5 + deposit * .18);
        deposit *= .35;
    } else if (abs(familyId - 2.0) < .5) {
        cover = smoothstep(.38, .94, up + (variation - .5) * .45 + deposit * .15);
        deposit *= .55;
    } else if (abs(familyId - 1.0) < .5) {
        cover = smoothstep(.28, .9, up + (variation - .5) * .16);
        deposit *= .6;
    }
    soil = clamp(soil, 0.0, 1.0 - cover);
    vec4 roles = vec4(cover, soil, deposit, max(0.0, 1.0 - cover - soil));
    roles.xyw *= 1.0 - deposit;
    // Submerged contacts exchange bed sediment and exposed stone, never living turf or windblown surface sand.
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, aboveFoot);
    roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    // Height maps are in [0,1]. Even their largest possible score cannot rescue these layers, so avoid all
    // their texture work while retaining the original roles for the exact final blend calculation.
    float highestMinimum = max(max(roles.x, roles.y), max(roles.z, roles.w));
    float width = materialBlendWidth();
    vec4 candidates = step(vec4(highestMinimum - width), roles + .38 * (4.0 * roles * (1.0 - roles)));
    vec4 a = sampleMaterial(GROUND_MAP, tiles.x, roles.x * candidates.x, projection,
          world, broad, fine, region, true, familyId);
    vec4 b = sampleMaterial(MANTLE_MAP, tiles.w, roles.y * candidates.y, projection,
          world, fine, fine, region, false, familyId);
    vec4 c = sampleMaterial(DEBRIS_MAP, tiles.y, roles.z * candidates.z, projection,
          world, fine, fine, region, false, familyId);
    vec4 d = sampleMaterial(WALL_MAP, tiles.z, roles.w * candidates.w, projection,
          world, broad, fine, region, false, familyId);
    d.rgb *= toLinear(bedTintFor(familyId, hardness));
    vec4 weights = materialWeights(roles, vec4(a.a, b.a, c.a, d.a));
    vec4 na = materialNormal(GROUND_NORMAL, tiles.x, weights.x, projection, face, broad);
    vec4 nb = materialNormal(MANTLE_NORMAL, tiles.w, weights.y, projection, face, fine);
    vec4 nc = materialNormal(DEBRIS_NORMAL, tiles.y, weights.z, projection, face, fine);
    vec4 nd = materialNormal(WALL_NORMAL, tiles.z, weights.w, projection, face, broad);
    TerrainMaterial result;
    result.color = toDisplay(a.rgb * weights.x + b.rgb * weights.y + c.rgb * weights.z + d.rgb * weights.w);
    result.normal = normalize(na.rgb * weights.x + nb.rgb * weights.y + nc.rgb * weights.z + nd.rgb * weights.w);
    result.cavity = dot(vec4(na.a, nb.a, nc.a, nd.a), weights);
    result.height = dot(vec4(a.a, b.a, c.a, d.a), weights);
    return result;
}

TerrainMaterial naturalMaterial(vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region, float sediment) {
#ifdef terrainBlendFlag
    vec4 layers = u_coverLayers0;
#else
    vec4 layers = vec4(0.0);
#endif
    return naturalMaterialFor(world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          u_sculptFamily, u_sculptTiles, layers, sediment);
}

#ifdef terrainBlendFlag
float coverPatch(vec3 world, float familyId) {
    vec2 offset = familyId * vec2(.17, .31);
    vec2 contact = world.xy + world.z * vec2(.47, .29);
    return texture2D(u_rainNoise, contact / 150.0 + offset).r * .65
          + texture2D(u_rainNoise, contact / 43.0 - offset).b * .35;
}

void blendCovers(vec3 world, vec3 face, float foot, float rim, float rock, float hardness, float sediment,
      float broad, float fine, float region,
      inout vec3 color, inout vec3 normal, inout float cavity, inout float grass,
      inout vec3 bounce, inout float response, inout float rainCover) {
    TerrainMaterial a = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
          u_coverFamilies.x, u_coverTiles0, u_coverLayers0, sediment);
    TerrainMaterial b = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
          u_coverFamilies.y, u_coverTiles1, u_coverLayers1, sediment);
    TerrainMaterial c = naturalMaterialFor(world, face, foot, rim, rock, hardness, broad, fine, region,
          u_coverFamilies.z, u_coverTiles2, u_coverLayers2, sediment);
    vec3 raw = max(v_coverWeights, vec3(0.0));
    // Broad coverage is shared with vegetation. Height adds small interlocking edges, never a new family.
    vec3 patches = vec3(coverPatch(world, u_coverFamilies.x), coverPatch(world, u_coverFamilies.y),
          coverPatch(world, u_coverFamilies.z));
    float patchStrength = mix(2.2, 4.0, 1.0 - smoothstep(.35, .85, face.z));
    vec3 weights = materialWeights(vec4(raw, 0.0),
          vec4(vec3(a.height, b.height, c.height) * .45 + patches * patchStrength, 0.0)).xyz;
    color = toDisplay(toLinear(a.color) * weights.x + toLinear(b.color) * weights.y + toLinear(c.color) * weights.z);
    normal = normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z);
    cavity = dot(vec3(a.cavity, b.cavity, c.cavity), weights);
    grass = dot(vec3(1.0) - step(vec3(.5), abs(u_coverFamilies)), weights) * (1.0 - sediment);
    bounce = groundBounceFor(u_coverFamilies.x) * weights.x + groundBounceFor(u_coverFamilies.y) * weights.y
          + groundBounceFor(u_coverFamilies.z) * weights.z;
    response = dot(max(u_coverResponses, vec3(0.0)), weights);
    rainCover = dot(step(vec3(0.0), u_coverResponses), weights);
}
#endif
