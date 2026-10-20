// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Natural surfaces: tops, walls, banks and bedrock (cliffMaterial); volcanic programs keep naturalMaterialFor.
// Inserted after the lighting helpers.

uniform float u_terrainWear; // 0: no ambient weathering; 1: full material weathering and cover erosion
uniform vec4 u_bankMaterials; // existing soil-contact and gravel: colour layer / repeat in metres
bool riverBank = false; // set by the water-owned ground, before evaluating its material roles
float shallowSediment = -1.0; // Halfway depth-zero arid shore profile; negative on deeper water and dry land.

struct TerrainMaterial {
    vec3 color; // linear reflectance; color maps are decoded at upload, before filtering
    vec3 normal;
    float cavity;
    float height;
    vec3 emission;
    float roughness;
    float volcanic;
};

float materialBlendWidth() { return .13 + min(.2, pixelMetres * .16); }

// Natural wear follows the existing relief, modulated by continuous world fields instead of repeated stamps.
// Channels are recesses, low grains, chipped edges and high grains. Loose/organic covers favour the height
// channels; stone favours cavities and normal-map edges. Fine wear fades with the material's normal detail.
vec4 terrainWear(vec3 fields, float height, float cavity, vec3 face, vec3 normal) {
    float exposure = smoothstep(.30, .67, fields.x * .65 + fields.z * .35);
    float abrasion = smoothstep(.32, .73, fields.y * .7 + fields.z * .3);
    float low = (1.0 - smoothstep(.24, .53, height)) * .75;
    float recess = max(low, clamp((1.0 - cavity) * 2.2, 0.0, 1.0));
    float high = smoothstep(.40, .65, height);
    float edge = clamp((1.0 - dot(face, normal)) * 5.0, 0.0, 1.0) * high;
    return vec4(recess * exposure, low * exposure, edge * abrasion, high * abrasion) * terrainNormalDetail;
}

// Authored pigment-control deltas, calibrated once at the final albedo multiplication. Roughness keeps
// these original control strengths; changing pigment contrast must not change the physical surface response.
// Keep the softened desert baseline: the previous .30 darkening was too strong. These are visual material
// estimates, not simulated erosion or a second terrain layer. No new maps, geometry or sampling are needed.
vec3 materialWeathering(float familyId, vec4 wear, vec3 fields, float cavity) {
    vec3 recess, crest;
    vec2 grain;
    if (abs(familyId) < .5) { // Grass: soil in worn turf, straw-coloured tips.
        recess = vec3(.12, .24, .18); crest = vec3(.24, .11, .025); grain = vec2(.25, .70);
    } else if (abs(familyId - 1.0) < .5) { // Dirt: compacted pockets and dry, crumbling grains.
        recess = vec3(.21, .24, .27); crest = vec3(.18, .15, .09); grain = vec2(.15, .35);
    } else if (abs(familyId - 2.0) < .5) {
        return vec3(0.0); // SAND is a tile cover, not a theme. Only exposed substrate weathers.
    } else if (abs(familyId - 3.0) < .5) { // Rock: fissures and pale mineral edges.
        recess = vec3(.27, .28, .28); crest = vec3(.22, .21, .18); grain = vec2(0.0, .04);
    } else if (abs(familyId - 4.0) < .5) {
        // Ground concrete collects a light, uneven film of grime, strongest in pores. Its height is a float finish,
        // not rocky relief: brightening its crests made false raised outlines across the pavement.
        float grime = smoothstep(.32, .70, fields.x * .5 + fields.y * .2 + fields.z * .3);
        float pores = smoothstep(.005, .08, 1.0 - cavity);
        return -vec3(.15, .135, .115) * grime * (.4 + .6 * pores) * terrainNormalDetail;
    } else if (abs(familyId - 5.0) < .5) { // Snow: irregular grey-blue refrozen crust, without drawn wave motifs.
        recess = vec3(.16, .12, .075); crest = vec3(.025, .03, .035); grain = vec2(.30, .65);
    } else if (abs(familyId - LUNAR_FAMILY) < .5) { // Lunar: neutral dust pockets and pitted mineral rims.
        recess = vec3(.15); crest = vec3(.10); grain = vec2(.15, .20);
    } else if (abs(familyId - FUNGUS_FAMILY) < .5) { // Fungus: mottled ageing tissue and pale raised skin.
        recess = vec3(.15, .24, .17); crest = vec3(.18, .13, .17); grain = vec2(.15, .65);
    } else if (abs(familyId - DESERT_FAMILY) < .5) { // Desert: soft sandstone varnish and abraded plates.
        recess = vec3(.20); crest = vec3(.085, .08, .07); grain = vec2(0.0);
    } else if (abs(familyId - MARS_FAMILY) < .5) { // Mars: rusty dust hollows and lighter mineral ridges.
        recess = vec3(.14, .17, .18); crest = vec3(.095, .075, .06); grain = vec2(.25, .35);
    } else if (abs(familyId - VOLCANO_FAMILY) < .5) { // Cold basalt: ash in pores, cooler exposed edges.
        recess = vec3(.17, .17, .16); crest = vec3(.11, .115, .12); grain = vec2(0.0, .05);
    } else if (abs(familyId - TROPICAL_FAMILY) < .5) { // Tropical: organic hollows and restrained dry litter.
        recess = vec3(.12, .23, .18); crest = vec3(.22, .12, .035); grain = vec2(.30, .65);
    } else {
        return vec3(0.0); // Active magma and cooling crust have their own surface treatment.
    }
    return crest * mix(wear.z, wear.w, grain.y) - recess * mix(wear.x, wear.y, grain.x);
}

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

// Cut walls keep one texture frame across their facets; zero leaves natural landforms unchanged.
vec3 terrainWallProjection = vec3(0.0);

MaterialProjection materialProjection(vec3 world, vec3 face) {
    vec3 basis = dot(terrainWallProjection, terrainWallProjection) > .5 ? terrainWallProjection : face;
    MaterialProjection p;
    p.top = vec2(world.x, -world.y);
    p.x = vec2(world.y * sign(basis.x), -world.z);
    p.y = vec2(-world.x * sign(basis.y), -world.z);
    p.topGradient = mat2(dFdx(p.top), dFdy(p.top));
    p.xGradient = mat2(dFdx(p.x), dFdy(p.x));
    p.yGradient = mat2(dFdx(p.y), dFdy(p.y));
    vec2 axes = pow(abs(basis.xy), vec2(4.0));
    p.side = axes.x / max(axes.x + axes.y, .00001);
    p.lying = smoothstep(.25, .87, basis.z);
    return p;
}

// Projection is shared; callers select colour or numerical storage explicitly.
vec4 materialTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    return textureGrad(maps, vec3(uv, layer), gradient[0], gradient[1]);
}

// Overlapping translated samples break the fungal source's recognisable repeats. Use identical offsets for
// colour/height and normal/AO; translations keep tangent normals aligned and leave vertical cliff grain upright.
vec2 materialOffset(vec2 cell) {
    return fract(sin(vec2(dot(cell, vec2(127.1, 311.7)), dot(cell, vec2(269.5, 183.3)))) * 43758.5453) * 8.0;
}

vec4 fungusTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    vec2 cell = floor(uv), blend = smoothstep(.15, .85, fract(uv));
    vec4 a = materialTexel(maps, layer, uv + materialOffset(cell), gradient);
    vec4 b = materialTexel(maps, layer, uv + materialOffset(cell + vec2(1.0, 0.0)), gradient);
    vec4 c = materialTexel(maps, layer, uv + materialOffset(cell + vec2(0.0, 1.0)), gradient);
    vec4 d = materialTexel(maps, layer, uv + materialOffset(cell + vec2(1.0, 1.0)), gradient);
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

// Select neighbouring source offsets from a continuous world field, not the UV tile or hex ID. Two reads keep the
// original feature size and wind/bedding direction without the recognisable motif repeating on a square grid.
// Color/height and normal/AO must use the same selector; a translation does not rotate or resize their normals.
vec4 translatedTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
    float choice = variation * 8.0, index = floor(choice);
    vec4 a = materialTexel(maps, layer, uv + materialOffset(vec2(index, 0.0)), gradient);
    vec4 b = materialTexel(maps, layer, uv + materialOffset(vec2(index + 1.0, 0.0)), gradient);
    return mix(a, b, smoothstep(.2, .8, fract(choice)));
}

// The ground's variation field is constant down a vertical face. Advance the source selection with projected
// height as well, otherwise a tall cliff repeats the same two patches at every map period. Blends use a different
// interval from the source repeat; both maps keep their scale and upright grain, with the same two texture reads.
vec4 cliffTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
    return translatedTexel(maps, layer, uv, gradient, variation + uv.y * .173);
}

vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {
    return materialTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 fungusTexel(float layer, vec2 uv, mat2 gradient) {
    return fungusTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 translatedTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    return translatedTexel(u_terrainLayers, layer * .5, uv, gradient, variation);
}
vec4 cliffTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    return cliffTexel(u_terrainLayers, layer * .5, uv, gradient, variation);
}

#ifdef volcanicFlag
// Volcanic programs' natural material (naturalMaterialFor), on every face; elsewhere cliffMaterial draws them all.
// Every role shares projection and metre scale through a bend. No UV origin at an individual hex's rim or foot.
// One projection implementation for color/height and authored scalar maps. No pigment transforms here.
vec4 sampleMaterialMap(sampler2DArray maps, float layer, float tile, MaterialProjection p,
      float variation, bool fungus, bool singleScale) {
    vec4 pigment = vec4(0.0);
    if (p.lying > 0.0) {
        pigment = fungus ? fungusTexel(maps, layer, p.top / tile, p.topGradient / tile)
              : singleScale
              ? translatedTexel(maps, layer, p.top / tile, p.topGradient / tile, variation)
              : mix(materialTexel(maps, layer, p.top / tile, p.topGradient / tile),
              materialTexel(maps, layer, TURN * p.top / (tile * 2.37) + .31,
                    TURN * p.topGradient / (tile * 2.37)), variation);
        // Keep resolved sand ripples and cap fractures; their explicit gradients already select filtered mips.
        if (farDetail > 0.0 && !singleScale) {
            pigment = mix(pigment, materialTexel(maps, layer, p.top / tile, p.topGradient * (4096.0 / tile)), farDetail);
        }
    }
    if (p.lying < 1.0) {
        vec4 steep = fungus ? mix(fungusTexel(maps, layer, p.y / tile + .37, p.yGradient / tile),
              fungusTexel(maps, layer, p.x / tile, p.xGradient / tile), p.side)
              : mix(cliffTexel(maps, layer, p.y / tile + .37, p.yGradient / tile, variation),
                    cliffTexel(maps, layer, p.x / tile, p.xGradient / tile, variation), p.side);
        pigment = mix(steep, pigment, p.lying);
    }
    return pigment;
}

vec4 sampleMaterial(float colorMap, float tile, float amount, MaterialProjection p,
      vec3 world, float variation, float fine, float region, bool groundMap, bool singleScale,
      float familyId, vec2 contact) {
    if (amount < .0001) return vec4(0.0);
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    vec4 pigment = sampleMaterialMap(u_terrainLayers, colorMap * .5, tile, p, variation, fungus, singleScale);
    vec3 color = pigment.rgb;
    if (groundMap) color = groundToneFor(familyId, color, world, variation, fine, region, contact.x, contact.y);
    if (abs(familyId - 2.0) < .5) {
        float mineral = dot(color, vec3(.2126, .7152, .0722));
        color = mix(color, mineral * toLinear(vec3(.86, .92, .98)), greySand());
    }
    return vec4(color, pigment.a);
}

// Base surface authority is independent of optional normal detail and its distance fade. A role without weight
// contributes nothing.
float materialRoughness(float colorMap, float tile, float weight, MaterialProjection p,
      float variation, bool fungus, bool singleScale) {
    if (weight <= 0.0) return 0.0;
    vec2 authored = u_sculptRoughness[int(colorMap * .5)];
    if (authored.y < 0.0) return authored.x;
    return sampleMaterialMap(u_roughnessLayers, authored.y, tile, p, variation, fungus, singleScale).r;
}
#endif

// Legacy planar/slab/rock paths retain their own colour-map projection.
float planarRoughness(float layer, vec2 p, mat2 gradient, float tile, float mixer) {
    vec2 authored = u_sculptRoughness[int(layer * .5)];
    if (authored.y < 0.0) return authored.x;
    float value = mix(authoredRoughness(layer, p / tile, gradient / tile),
          authoredRoughness(layer, TURN * p / (tile * 2.37) + .31, TURN * gradient / (tile * 2.37)), mixer);
    return farDetail > 0.0 ? mix(value, authored.x, farDetail) : value;
}

float translatedRoughness(float layer, vec2 uv, mat2 gradient, float variation) {
    vec2 authored = u_sculptRoughness[int(layer * .5)];
    if (authored.y < 0.0) return authored.x;
    return translatedTexel(u_roughnessLayers, authored.y, uv, gradient, variation).r;
}

float wallRoughness(float layer, vec2 x, vec2 y, mat2 dx, mat2 dy, float side) {
    return mix(authoredRoughness(layer, y, dy), authoredRoughness(layer, x, dx), side);
}

#ifdef volcanicFlag
// Height blending often discards a present layer entirely. Its normal/cavity cannot affect the result then.
// Defer those texture reads until the colour/height samples have determined the final weights.
vec4 materialNormal(float normalMap, float tile, float weight, MaterialProjection p, vec3 face, float variation,
      bool fungus, bool singleScale) {
    if (weight <= 0.0 || u_normalMaps <= .5) return vec4(face, 1.0);
    if (terrainNormalDetail <= 0.0) return vec4(face, TERRAIN_DISTANT_CAVITY);
    vec3 normal = face;
    float cavity = 1.0;
    if (p.lying > 0.0) {
        vec4 near = fungus ? fungusTexel(u_terrainData, (normalMap - 1.0) * .5, p.top / tile, p.topGradient / tile)
              : singleScale ? translatedTexel(u_terrainData, (normalMap - 1.0) * .5, p.top / tile, p.topGradient / tile, variation)
              : materialTexel(u_terrainData, (normalMap - 1.0) * .5, p.top / tile, p.topGradient / tile);
        vec4 far = fungus || singleScale ? near : materialTexel(u_terrainData, (normalMap - 1.0) * .5, TURN * p.top / (tile * 2.37) + .31,
                    TURN * p.topGradient / (tile * 2.37));
        vec3 detail = fungus || singleScale ? mix(near.rgb * 2.0 - 1.0, vec3(0.0, 0.0, 1.0), farDetail)
              : planarNormal(near, far, variation).rgb;
        normal = upNormal(detail, face);
        cavity = mix(near.a, far.a, variation);
    }
    if (p.lying < 1.0) {
        vec4 nx = fungus ? fungusTexel(u_terrainData, (normalMap - 1.0) * .5, p.x / tile, p.xGradient / tile)
              : cliffTexel(u_terrainData, (normalMap - 1.0) * .5, p.x / tile, p.xGradient / tile, variation);
        vec4 ny = fungus ? fungusTexel(u_terrainData, (normalMap - 1.0) * .5, p.y / tile + .37, p.yGradient / tile)
              : cliffTexel(u_terrainData, (normalMap - 1.0) * .5, p.y / tile + .37, p.yGradient / tile, variation);
        vec3 x = nx.rgb * 2.0 - 1.0, y = ny.rgb * 2.0 - 1.0;
        vec3 wall = normalize(mix(vec3(-y.x * sign(face.y) + face.x, y.z * face.y, face.z - y.y),
              vec3(x.z * face.x, x.x * sign(face.x) + face.y, face.z - x.y), p.side));
        normal = normalize(mix(wall, normal, p.lying));
        cavity = mix(mix(ny.a, nx.a, p.side), cavity, p.lying);
    }
    return vec4(normalize(mix(face, normal, terrainNormalDetail)),
          mix(TERRAIN_DISTANT_CAVITY, mix(.55, 1.0, cavity), terrainNormalDetail));
}

TerrainMaterial volcanicMaterial(Volcanic material, float strength) {
    return TerrainMaterial(material.albedo, material.normal, material.surface.b, material.surface.r,
          magmaEmission(material.heat, strength), material.surface.g, 1.0);
}

TerrainMaterial volcanicMaterial(Volcanic material, float familyId, float bankHeat) {
    return volcanicMaterial(material, familyId > VOLCANIC_BANK_FAMILY - .5 ? bankHeat : 1.0);
}

// Shared world fields are sampled before varying palette coverage branches. The family test is draw-uniform.
vec2 materialNoise(vec3 world, vec4 families) {
    float pockets = texture(u_rainNoise, (world.xy + world.z * vec2(.43, .27)) / 92.0 + .57).g;
    float fray = 0.0;
    if (any(lessThan(abs(families), vec4(.5)))
          || any(lessThan(abs(families - TROPICAL_FAMILY), vec4(.5)))) {
        fray = texture(u_rainNoise, (world.xy + world.z * vec2(.29, .41)) / 28.0 + .217).r;
    }
    return vec2(pockets, fray);
}

// Ordinary families only: blendCovers handles volcanic slots with one shared surface sample.
TerrainMaterial naturalMaterialFor(MaterialProjection projection, vec2 noise, vec3 world, vec3 face,
      float aboveFoot, float belowRim, float rock, float hardness, float broad, float fine, float region,
      float familyId, vec4 tiles, vec4 layers, float sediment) {
    float up = clamp(face.z, 0.0, 1.0);
    // Loose sand keeps its tile-cover behaviour. Weathering controls erosion of the supporting material;
    // full intensity preserves the authored material mix, and zero keeps the slope's underlying geology.
    float erosion = abs(familyId - 2.0) < .5 ? 1.0 : u_terrainWear;
    float pockets = noise.x;
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
          * exposure * smoothstep(.30, .65, broad * .6 + pockets * .4) * erosion;
    // A sparse bank mesh carries its broad slope, not every eroded patch. Expose its mantle with the existing
    // world-space detail field, so sand and turf do not hide all the material detail when the mesh is simplified.
    // Level ground keeps its cover; hard cliffs already get their exposed rock from the existing material mix.
    float bank = (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up));
    float wear = bank * smoothstep(.32, .68, fine * .65 + pockets * .35) * erosion;
    float coverUp = up - .2 * wear;
    float cover = smoothstep(.4, .96, coverUp + (variation - .5) * .55);
    float soil = 0.0;
    float dust = 0.0;
    bool desert = abs(familyId - DESERT_FAMILY) < .5;
    bool arid = desert || abs(familyId - MARS_FAMILY) < .5 || abs(familyId - VOLCANO_FAMILY) < .5;
    bool plates = desert || abs(familyId - VOLCANO_FAMILY) < .5;
    // A continuous exposure field crosses plateau interiors as well as their rims. Each material retains its
    // own substrate and response: this is not a new terrain type or the same pale patch painted on every biome.
    float thin = smoothstep(.50, .72, region * .6 + broad * .3 + fine * .1);
    thin *= smoothstep(.82, .97, up) * (1.0 - deposit) * (1.0 - .75 * foot) * u_terrainWear;
    // Organic weathering also opens the existing cover onto its own soil/tissue. A tint alone disappears
    // in busy turf. Use continuous fields and existing material roles, not a separate decal or cut-out shape.
    float aged = u_terrainWear * terrainNormalDetail * smoothstep(.45, .68, broad * .5 + region * .35 + fine * .15)
          * smoothstep(.88, .98, up) * (1.0 - sediment);
    if (abs(familyId) < .5 || abs(familyId - TROPICAL_FAMILY) < .5) {
        // The two-level earth bank keeps a ragged turf lip and broken turf on its real intermediate shoulder.
        // Color remains legible from above when individual blades are too small to draw.
        float levelMetres = u_levelHeight / u_metre;
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((aboveFoot + belowRim) / levelMetres - 2.0));
        float fray = noise.y;
        float lipDepth = levelMetres * (mix(.2, .65, fine * .45 + pockets * .55) + .14 * (fray - .5));
        float lip = 1.0 - smoothstep(.05, lipDepth, belowRim);
        float middle = abs(aboveFoot / levelMetres - 1.0 - (pockets - .5) * .18);
        float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
        float shoulder = 1.0 - smoothstep(width * .25, width, middle);
        shoulder *= smoothstep(.35, .73, up) * smoothstep(.22, .55, pockets * .7 + fine * .3);
        cover = max(cover, max(lip, shoulder) * twoLevels * (1.0 - exposure));
        float wornCover = cover * aged * .48;
        cover -= wornCover;
        soil = (1.0 - cover) * ((1.0 - exposure) * .9 + rim * .5 * smoothstep(.25, .7, variation));
        soil = max(soil, wornCover); // Worn organic cover reveals soil even on a tall plateau, not pale cliff rock.
        deposit *= mix(.25, 1.0, rock);
    } else if (abs(familyId - FUNGUS_FAMILY) < .5) {
        // Preserve the two fungal skins: mauve lowlands, cyan crust above level zero. Weather each in its own
        // material: lowland skin wears through to violet bedrock, while gaps in the upper crust expose mauve tissue.
        float levelMetres = u_levelHeight / u_metre;
        float rise = smoothstep(.25, .9, (aboveFoot + belowRim) / levelMetres);
        float raised = smoothstep(.15, 1.3, v_cloudPosition.z / u_levelHeight);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * pockets, belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.28, .69, region * .5 + broad * .3 + pockets * .2);
        deposit = smoothstep(.48, .88, up) * mix(.10 * colonies, .28 + .70 * colonies, growth);
        deposit *= 1.0 - aged * .55;
        cover = smoothstep(.76, .97, up);
        cover *= 1.0 - .90 * thin * (1.0 - growth);
        soil = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    } else if (abs(familyId - 5.0) < .5) {
        cover = smoothstep(.36, .92, coverUp + (variation - .5) * .5 + deposit * .18);
        cover *= 1.0 - max(.85 * scour, .88 * thin * rim);
        deposit *= .35;
    } else if (abs(familyId - 2.0) < .5) {
        cover = smoothstep(.38, .94, coverUp + (variation - .5) * .45 + deposit * .15);
        cover *= 1.0 - .92 * scour;
        deposit *= .55;
    } else if (arid) {
        // Firm crust wears back to exposed rock on banks and a broken mineral shoulder at the crest.
        cover = smoothstep(.38, .96, coverUp + (variation - .5) * .22);
        float shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, belowRim))
              * smoothstep(.28, .65, pockets) * smoothstep(.55, .94, up) * u_terrainWear;
        // Thin hardpan/dust opens onto flush bedrock in broad connected patches, including plateau interiors.
        // Reuse the existing regional fields: no hex-local mask, new noise lookup or displaced playing surface.
        // Deposited material shelters the foot; small height-map detail breaks the final material contact.
        cover *= 1.0 - max(.90 * scour, max(.85 * shoulder, .92 * thin));
        // Horizontal cap maps show fractured plates; the wall maps keep their upright bedding or basalt columns.
        // Carry the cap around the shoulder, then fade with slope and depth below the rim. Distant faces keep
        // their wall material; level interiors keep the cap. Reuse the existing metre-scale rim field.
        float cap = smoothstep(.82, .97, up);
        if (plates) cap = max(cap, rim * smoothstep(.25, .90, up));
        soil = (1.0 - cover) * cap;
        // A light film of the local hardpan/ash remains on exposed horizontal stone. Keep its complete material,
        // rather than painting dark cliff patches onto a flat plain; submerged beds keep their sediment instead.
        dust = .24 * smoothstep(.88, .98, up) * (1.0 - sediment);
        deposit *= .55;
    } else if (abs(familyId - 1.0) < .5) {
        cover = smoothstep(.28, .9, coverUp + (variation - .5) * .16);
        cover *= 1.0 - .9 * thin;
        soil = (1.0 - cover) * (1.0 - exposure);
        // Weathered compact soil gives way to flush bedrock on level ground; its bank keeps its earthen mantle.
        soil *= 1.0 - smoothstep(.90, .99, up);
        dust = .18 * smoothstep(.88, .98, up) * (1.0 - sediment);
        deposit *= .6;
    } else if (abs(familyId - 3.0) < .5 || abs(familyId - 6.0) < .5) {
        // Rocky ground and lunar regolith retain their own loose fines between broad exposed rock plates.
        // Lunar coverage is visual only; it does not create gravity-driven deposits or extra rubble geometry.
        cover *= 1.0 - .92 * thin;
        dust = .12 * smoothstep(.88, .98, up) * (1.0 - sediment);
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
    // Cap and wall are orientations of the same rock, not competing covers. Let their combined height compete
    // with hardpan/deposits, then distribute the winning rock weight smoothly across all material channels.
    float capShare = plates ? roles.y / max(roles.y + roles.w, .00001) : 0.0;
    if (plates) roles = vec4(roles.x, 0.0, roles.z, roles.y + roles.w);
    // Height maps are in [0,1]. Even their largest possible score cannot rescue these layers, so avoid all
    // their texture work while retaining the original roles for the exact final blend calculation.
    float highestMinimum = max(max(roles.x, roles.y), max(roles.z, roles.w));
    float width = materialBlendWidth();
    vec4 candidates = step(vec4(highestMinimum - width), roles + .38 * (4.0 * roles * (1.0 - roles)));
    if (dust > 0.0) candidates.x = 1.0;
    bool sand = abs(familyId - 2.0) < .5;
    // These authored plates have one physical size. Reuse sand's translated sampling for color and normals;
    // mixing an enlarged copy makes overlapping fractures look like faint stains. Select across broad regions.
    float mantleVariation = plates ? broad : fine;
    vec4 a = sampleMaterial(layers.x, tiles.x, roles.x * candidates.x, projection,
          world, broad, fine, region, true, sand, familyId, vec2(rim, foot));
    float capAmount = plates ? roles.w * candidates.w * capShare : roles.y * candidates.y;
    vec4 b = sampleMaterial(layers.w, tiles.w, capAmount, projection,
          world, mantleVariation, fine, region, false, plates, familyId, vec2(0.0));
    vec4 c = sampleMaterial(layers.y, tiles.y, roles.z * candidates.z, projection,
          world, fine, fine, region, false, false, familyId, vec2(0.0));
    vec4 d = sampleMaterial(layers.z, tiles.z, roles.w * candidates.w * (1.0 - capShare), projection,
          world, broad, fine, region, false, false, familyId, vec2(0.0));
    if (desert) {
        // Pale abraded sandstone at the actual rim reads from above; deeper faces retain their warm brown.
        float abrasion = (1.0 - smoothstep(.15, 2.2, belowRim)) * smoothstep(.45, .95, up);
        vec3 weathering = mix(vec3(.98, .91, .85), vec3(1.14, 1.15, 1.13), abrasion);
        d.rgb *= authoredPigmentGain(mix(vec3(1.0), weathering, u_terrainWear));
    }
    float rockHeight = plates ? mix(d.a, b.a, capShare) : d.a;
    vec4 weights = materialWeights(roles, vec4(a.a, b.a, c.a, rockHeight));
    if (plates) weights.yw = weights.w * vec2(capShare, 1.0 - capShare);
    vec2 coating = weights.yw * dust;
    weights += vec4(coating.x + coating.y, -coating.x, 0.0, -coating.y);
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    vec4 na = materialNormal(layers.x + 1.0, tiles.x, weights.x, projection, face, broad, fungus, sand);
    vec4 nb = materialNormal(layers.w + 1.0, tiles.w, weights.y, projection, face, mantleVariation, fungus, plates);
    vec4 nc = materialNormal(layers.y + 1.0, tiles.y, weights.z, projection, face, fine, fungus, false);
    vec4 nd = materialNormal(layers.z + 1.0, tiles.z, weights.w, projection, face, broad, fungus, false);
    TerrainMaterial result;
    result.color = a.rgb * weights.x + b.rgb * weights.y + c.rgb * weights.z + d.rgb * weights.w;
    result.normal = normalize(na.rgb * weights.x + nb.rgb * weights.y + nc.rgb * weights.z + nd.rgb * weights.w);
    result.cavity = dot(vec4(na.a, nb.a, nc.a, nd.a), weights);
    result.height = dot(vec4(a.a, b.a, c.a, d.a), weights);
    result.emission = vec3(0.0);
    vec4 roughness = vec4(materialRoughness(layers.x, tiles.x, weights.x, projection, broad, fungus, sand),
          materialRoughness(layers.w, tiles.w, weights.y, projection, mantleVariation, fungus, plates),
          materialRoughness(layers.y, tiles.y, weights.z, projection, fine, fungus, false),
          materialRoughness(layers.z, tiles.z, weights.w, projection, broad, fungus, false));
    result.roughness = dot(roughness, weights);
    result.volcanic = 0.0;
    return result;
}

// Volcanic programs without a palette draw sculpt materials only for crust and bank families.
TerrainMaterial crustOrBankMaterial(vec3 world, vec3 face) {
    Volcanic material = magmaSurface(world, face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
    return volcanicMaterial(material,
          sculptFamily > VOLCANIC_BANK_FAMILY - .5 ? magmaBankHeat(v_cloudPosition.xy) : 1.0);
}
#endif

TerrainMaterial cliffMaterial(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim,
      float rock, float hardness, float broad, float fine, float region, float sediment, float familyId,
      vec4 tiles, vec4 layers, bool sides, bool single);

#ifdef terrainBlendFlag
float coverPatch(vec3 world, float familyId) {
    if (familyId >= VOLCANIC_CRUST_FAMILY) familyId = 11.0 + familyId - VOLCANIC_CRUST_FAMILY;
    vec2 offset = familyId * vec2(.17, .31);
    vec2 contact = world.xy + world.z * vec2(.47, .29);
    return .5 + (texture(u_rainNoise, contact / 21.0 + offset).r - .5) * .8;
}

// What a pixel's final cover weights make of its grass, sand, bounce light and rain response.
void coverEffects(vec4 weights, float sediment, out float grass, out float sand, out vec3 bounce, out float response,
      out float rainCover) {
    grass = dot(vec4(1.0) - step(vec4(.5), abs(coverFamilies)), weights) * (1.0 - sediment);
    sand = dot(vec4(1.0) - step(vec4(.5), abs(coverFamilies - 2.0)), weights);
    bounce = groundBounceFor(coverFamilies.x) * weights.x + groundBounceFor(coverFamilies.y) * weights.y
          + groundBounceFor(coverFamilies.z) * weights.z + groundBounceFor(coverFamilies.w) * weights.w;
    response = dot(max(u_coverResponses, vec4(0.0)), weights);
    rainCover = dot(step(vec4(0.0), u_coverResponses), weights);
}

void blendCovers(vec3 world, vec3 face, float foot, float rim, float rock, float hardness, float sediment,
      float broad, float fine, float region,
      inout vec3 color, inout vec3 normal, inout float cavity, inout float height, inout float grass, inout float sand,
      inout vec3 bounce, inout float response, inout float rainCover,
      out vec3 emission, inout float roughness, out float volcanic, out vec4 weights) {
    vec4 raw = max(v_coverWeights, vec4(0.0));
#ifndef volcanicFlag
    // A slot under 2 % coverage is left out; the remaining weights renormalize below.
    raw *= step(vec4(.02), raw);
#endif
    MaterialProjection projection = materialProjection(world, face);
#ifdef volcanicFlag
    // naturalMaterialFor's shared world fields, sampled before the varying coverage branches.
    vec2 noise = vec2(0.0);
    if (any(lessThan(coverFamilies, vec4(VOLCANIC_CRUST_FAMILY - .5)))) noise = materialNoise(world, coverFamilies);
    MagmaProjection magmaCoordinates = magmaProjectionFor(world);
    // A bank can acquire coverage here even if its incoming vertex weight was zero. Sample the field
    // before varying coverage branches; bank emission reuses the same derivative-safe shore sample.
    vec4 bank = vec4(greaterThan(coverFamilies, vec4(VOLCANIC_BANK_FAMILY - .5)));
    float shore = -.4;
    if (u_magmaFieldMap.x > 0.0 && dot(bank, vec4(1.0)) > 0.0) {
        shore = magmaShore(magmaField(v_cloudPosition.xy));
        raw = mix(raw, bank, 1.0 - smoothstep(0.0, MAGMA_MARGIN, -shore));
    }
    float bankHeat = magmaBankHeat(shore);
    // Crust and bank use this draw's same relief, maps and normal; only their emission strength differs.
    // Without sand transfer, zero coverage cannot become visible; skip its shared material entirely.
    vec4 volcanicNeeded = raw;
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY == 2
    volcanicNeeded = max(raw, vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5))));
    volcanicNeeded.x = 1.0;
#endif
    Volcanic magmaMaterial = Volcanic(vec3(0.0), face, vec4(0.0), vec2(0.0), vec3(0.0));
    if (dot(volcanicNeeded, vec4(greaterThan(coverFamilies, vec4(VOLCANIC_CRUST_FAMILY - .5)))) > 0.0) {
        magmaMaterial = magmaSurface(magmaCoordinates, face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
    }
#endif
#if defined(VOLCANIC_ORDINARY_FAMILY) && VOLCANIC_ORDINARY_FAMILY >= 0
    // This draw has one ordinary material identity. Its family is constant for compilation; maps/scales
    // remain the original uniforms. Repeated matching slots share all sampled material channels.
    vec4 ordinaryTiles = u_coverTiles0, ordinaryLayers = u_coverLayers0;
    if (coverFamilies.x != float(VOLCANIC_ORDINARY_FAMILY)) {
        if (coverFamilies.y == float(VOLCANIC_ORDINARY_FAMILY)) {
            ordinaryTiles = u_coverTiles1; ordinaryLayers = u_coverLayers1;
        } else if (coverFamilies.z == float(VOLCANIC_ORDINARY_FAMILY)) {
            ordinaryTiles = u_coverTiles2; ordinaryLayers = u_coverLayers2;
        } else {
            ordinaryTiles = u_coverTiles3; ordinaryLayers = u_coverLayers3;
        }
    }
    // Projection gradients and noise were captured above, before this varying coverage guard.
    vec4 ordinaryNeeded = raw;
#if VOLCANIC_ORDINARY_FAMILY == 2
    // Sand can expose a zero-weight substrate after height blending; retain the original base too.
    ordinaryNeeded = max(raw, vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5))));
    ordinaryNeeded.x = 1.0;
#endif
    TerrainMaterial ordinary = TerrainMaterial(vec3(0.0), face, 1.0, 0.0, vec3(0.0), 1.0, 0.0);
    if (dot(ordinaryNeeded, vec4(equal(coverFamilies, vec4(float(VOLCANIC_ORDINARY_FAMILY))))) > 0.0) {
        ordinary = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine,
              region, float(VOLCANIC_ORDINARY_FAMILY), ordinaryTiles, ordinaryLayers, sediment);
    }
#endif
    TerrainMaterial a;
#if defined(VOLCANIC_ORDINARY_FAMILY) && VOLCANIC_ORDINARY_FAMILY < 0
    a = volcanicMaterial(magmaMaterial, coverFamilies.x, bankHeat);
#else
    if (abs(coverFamilies.x - 4.0) < .5) {
        a = TerrainMaterial(color, normal, cavity, height, vec3(0.0), roughness, 0.0);
#ifdef volcanicFlag
    } else if (coverFamilies.x > VOLCANIC_CRUST_FAMILY - .5) {
        a = volcanicMaterial(magmaMaterial, coverFamilies.x, bankHeat);
#endif
    } else {
#ifdef VOLCANIC_ORDINARY_FAMILY
        a = ordinary;
#else
#ifdef volcanicFlag
        a = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
              coverFamilies.x, u_coverTiles0, u_coverLayers0, sediment);
#else
        a = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
              coverFamilies.x, u_coverTiles0, u_coverLayers0, false, false);
#endif
#endif
    }
#endif
    // Unused palette slots repeat the base family. Reuse its complete material instead of evaluating its
    // textures and volcanic relief again. Constructed concrete differs from transported aggregate; keep that
    // distinction, and retain any explicit per-slot texture/scale overrides. These branches are draw-uniform.
    TerrainMaterial b = a;
#ifndef COVER_B_IS_BASE
    if (coverFamilies.y != coverFamilies.x || abs(coverFamilies.x - 4.0) < .5
          || any(notEqual(u_coverTiles1, u_coverTiles0)) || any(notEqual(u_coverLayers1, u_coverLayers0))) {
#ifdef volcanicFlag
        if (coverFamilies.y > VOLCANIC_CRUST_FAMILY - .5) {
            // Zero coverage stays zero unless later sand exposure transfers weight into the substrate.
            if (raw.y > 0.0 || abs(coverFamilies.y - sculptFamily) < .5) {
                b = volcanicMaterial(magmaMaterial, coverFamilies.y, bankHeat);
            }
        }
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        else
#endif
#endif
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        if (raw.y > 0.0 || abs(coverFamilies.y - sculptFamily) < .5) {
            // An absent cover cannot affect height competition; retain any substrate sand can expose later.
#ifdef VOLCANIC_ORDINARY_FAMILY
            b = ordinary;
#else
#ifdef volcanicFlag
            b = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
                  coverFamilies.y, u_coverTiles1, u_coverLayers1, sediment);
#else
            b = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies.y, u_coverTiles1, u_coverLayers1, false, true);
#endif
#endif
        }
#endif
    }
#endif
    TerrainMaterial c = a;
#ifndef COVER_C_IS_BASE
    if (coverFamilies.z != coverFamilies.x || abs(coverFamilies.x - 4.0) < .5
          || any(notEqual(u_coverTiles2, u_coverTiles0)) || any(notEqual(u_coverLayers2, u_coverLayers0))) {
#ifdef volcanicFlag
        if (coverFamilies.z > VOLCANIC_CRUST_FAMILY - .5) {
            // Zero coverage stays zero unless later sand exposure transfers weight into the substrate.
            if (raw.z > 0.0 || abs(coverFamilies.z - sculptFamily) < .5) {
                c = volcanicMaterial(magmaMaterial, coverFamilies.z, bankHeat);
            }
        }
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        else
#endif
#endif
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        if (raw.z > 0.0 || abs(coverFamilies.z - sculptFamily) < .5) {
            // An absent cover cannot affect height competition; retain any substrate sand can expose later.
#ifdef VOLCANIC_ORDINARY_FAMILY
            c = ordinary;
#else
#ifdef volcanicFlag
            c = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
                  coverFamilies.z, u_coverTiles2, u_coverLayers2, sediment);
#else
            c = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies.z, u_coverTiles2, u_coverLayers2, false, true);
#endif
#endif
        }
#endif
    }
#endif
    TerrainMaterial d = a;
    if (coverFamilies.w != coverFamilies.x) {
#ifdef volcanicFlag
        if (coverFamilies.w > VOLCANIC_CRUST_FAMILY - .5) {
            // Zero coverage stays zero unless later sand exposure transfers weight into the substrate.
            if (raw.w > 0.0 || abs(coverFamilies.w - sculptFamily) < .5) {
                d = volcanicMaterial(magmaMaterial, coverFamilies.w, bankHeat);
            }
        }
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        else
#endif
#endif
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY >= 0
        if (raw.w > 0.0 || abs(coverFamilies.w - sculptFamily) < .5) {
            // An absent cover cannot affect height competition; retain any substrate sand can expose later.
#ifdef VOLCANIC_ORDINARY_FAMILY
            d = ordinary;
#else
#ifdef volcanicFlag
            d = naturalMaterialFor(projection, noise, world, face, foot, rim, rock, hardness, broad, fine, region,
                  coverFamilies.w, u_coverTiles3, u_coverLayers3, sediment);
#else
            d = cliffMaterial(projection, world, face, foot, rim, rock, hardness, broad, fine, region, sediment,
                  coverFamilies.w, u_coverTiles3, u_coverLayers3, false, true);
#endif
#endif
        }
#endif
    }
    // Broad coverage is shared with vegetation. Height adds small interlocking edges, never a new family.
    // Unused slots repeat the base family; their contact noise is identical regardless of material overrides.
    // Family comparisons are draw-uniform, so the retained texture derivatives remain defined.
    vec4 patches = vec4(coverPatch(world, coverFamilies.x));
    if (coverFamilies.y != coverFamilies.x) patches.y = coverPatch(world, coverFamilies.y);
    if (coverFamilies.z != coverFamilies.x) patches.z = coverPatch(world, coverFamilies.z);
    if (coverFamilies.w != coverFamilies.x) patches.w = coverPatch(world, coverFamilies.w);
    float patchStrength = mix(1.5, 2.4, 1.0 - smoothstep(.35, .85, face.z));
    weights = materialWeights(raw, vec4(a.height, b.height, c.height, d.height) * .45 + patches * patchStrength);
    // Soften neighbouring covers without losing their interlocking texture. Authored tropical mixtures keep
    // their dark litter patches in the interior; fade that exception with its actual contribution, never the
    // triangle's palette membership, so a zero-weight tropical slot cannot introduce a material seam.
    vec4 proportions = raw / max(dot(raw, vec4(1.0)), .00001);
    float authored = clamp(v_coverInterpolation, 0.0, 1.0);
    float tropical = dot(proportions, vec4(lessThan(abs(coverFamilies - TROPICAL_FAMILY), vec4(.5))));
    float gradient = mix(.55, 1.0, authored) * (1.0 - authored * smoothstep(0.0, .15, tropical));
    weights = mix(weights, proportions, gradient);
    // Loose deposits leave a few windows onto this tile's actual substrate. Transfer coverage, not just colour,
    // so exposed turf/rock keeps its own normals, height, cavity and wet response. The CPU uses this same field
    // for grass roots; neither the board's SAND flag nor the terrain/support mesh changes.
#if !defined(VOLCANIC_ORDINARY_FAMILY) || VOLCANIC_ORDINARY_FAMILY == 2
    // Without a sand slot the transfer is exactly zero. Skip its two noise fields; specialized palettes
    // can contain sand only when their sole ordinary family is SAND. Generic presence is draw-uniform.
    vec4 sandy = vec4(lessThan(abs(coverFamilies - 2.0), vec4(.5)));
    vec4 substrate = vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5)));
    float substrateCount = dot(substrate, vec4(1.0));
    if (dot(sandy, vec4(1.0)) > 0.0 && abs(sculptFamily - 2.0) > .5 && substrateCount > 0.0) {
        float exposed = sandExposure(world.xy) * (1.0 - sediment);
        float amount = dot(weights, sandy) * exposed;
        weights = weights * (1.0 - sandy * exposed) + substrate * (amount / substrateCount);
    }
#endif
    color = a.color * weights.x + b.color * weights.y + c.color * weights.z + d.color * weights.w;
    normal = normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z + d.normal * weights.w);
    cavity = dot(vec4(a.cavity, b.cavity, c.cavity, d.cavity), weights);
    height = dot(vec4(a.height, b.height, c.height, d.height), weights);
    emission = a.emission * weights.x + b.emission * weights.y + c.emission * weights.z + d.emission * weights.w;
    roughness = dot(vec4(a.roughness, b.roughness, c.roughness, d.roughness), weights);
    volcanic = dot(vec4(a.volcanic, b.volcanic, c.volcanic, d.volcanic), weights);
    coverEffects(weights, sediment, grass, sand, bounce, response, rainCover);
}
#endif

// ---- Cliffs and tops: one small natural material ------------------------------------------------------------------
// Natural tops, walls, banks, drowned walls and bedrock share one material: the family's wall rock, its ground on
// ledges and tops, its mantle on low banks (or its cap plates at an arid rim) and its debris at the foot. Only the two
// strongest roles are sampled. Walls read one texel per side projection; tops read the top projection twice (two
// repeats, or two offsets for translated materials). The geometry carries the landform; the bed hardness it stores
// per cliff vertex tints the strata on walls.

// Each family's cliff roles as data, from the role formulas of naturalMaterialFor.
// ramp: the ledge-cover smoothstep (low, high), its jitter, and how much a foot deposit raises it.
// trim: how much rim scour, thin cover (times the rim when z is 1) and an arid crest shoulder remove from the cover.
// kind: x the mantle (0 none, 1 meadow soil, 2 bank soil, 3 arid cap plates), y the foot deposit share, z 1 for a
// meadow's turf lip and shoulder on two-level banks, w 1 where erosion ignores the weathering setting (sand).
void cliffFamily(float f, out vec4 ramp, out vec4 trim, out vec4 kind) {
    // Rock and lunar.
    ramp = vec4(.4, .96, .55, 0.0); trim = vec4(0.0, .92, 0.0, 0.0); kind = vec4(0.0, 1.0, 0.0, 0.0);
    if (abs(f) < .5 || abs(f - TROPICAL_FAMILY) < .5) {
        trim = vec4(0.0); kind = vec4(1.0, 1.0, 1.0, 0.0);
    } else if (abs(f - 1.0) < .5) { // dirt
        ramp = vec4(.28, .9, .16, 0.0); trim = vec4(0.0, .9, 0.0, 0.0); kind = vec4(2.0, .6, 0.0, 0.0);
    } else if (abs(f - 2.0) < .5) { // sand
        ramp = vec4(.38, .94, .45, .15); trim = vec4(.92, 0.0, 0.0, 0.0); kind = vec4(0.0, .55, 0.0, 1.0);
    } else if (abs(f - 5.0) < .5) { // snow
        ramp = vec4(.36, .92, .5, .18); trim = vec4(.85, .88, 1.0, 0.0); kind = vec4(0.0, .35, 0.0, 0.0);
    } else if (abs(f - DESERT_FAMILY) < .5 || abs(f - MARS_FAMILY) < .5 || abs(f - VOLCANO_FAMILY) < .5) {
        ramp = vec4(.38, .96, .22, 0.0); trim = vec4(.9, .92, 0.0, .85); kind = vec4(3.0, .55, 0.0, 0.0);
    } else if (abs(f - FUNGUS_FAMILY) < .5) {
        ramp = vec4(.76, .97, 0.0, 0.0); trim = vec4(0.0, .9, 0.0, 0.0); kind = vec4(2.0, .3, 0.0, 0.0);
    }
}

// A top's two reads: two repeats of the map (the second 2.37 times larger and turned) mixed by a world field, or for
// translated materials (sand, cap plates, fungus) two offsets of one repeat, which keeps their single feature size.
void topCoordinates(MaterialProjection p, float tile, float variation, bool translated,
      out vec2 a, out vec2 b, out mat2 gradientB, out float t) {
    vec2 uv = p.top / tile;
    if (translated) {
        float choice = variation * 8.0, index = floor(choice);
        a = uv + materialOffset(vec2(index, 0.0));
        b = uv + materialOffset(vec2(index + 1.0, 0.0));
        gradientB = p.topGradient / tile;
        t = smoothstep(.2, .8, fract(choice));
    } else {
        a = uv;
        b = TURN * uv / 2.37 + .31;
        gradientB = TURN * p.topGradient / (tile * 2.37);
        t = variation;
    }
}

// Authored packed layers share translated coordinates for colour/height and normal XY/roughness/AO.
// Keep the reads separate so height competition can reject a layer before its surface data is fetched.
struct PackedTopCoordinates { vec2 a; vec2 b; mat2 gradient; float blend; };

PackedTopCoordinates packedTopCoordinates(MaterialProjection p, float tile, float variation) {
    PackedTopCoordinates uv;
    topCoordinates(p, tile, variation, true, uv.a, uv.b, uv.gradient, uv.blend);
    return uv;
}

vec4 packedTopColor(float layer, PackedTopCoordinates uv) {
    return mix(materialTexel(u_terrainLayers, layer * .5, uv.a, uv.gradient),
          materialTexel(u_terrainLayers, layer * .5, uv.b, uv.gradient), uv.blend);
}

// Returns RMS roughness and AO. Numeric maps remain necessary when normal detail is disabled or distant.
vec2 packedTopSurface(float layer, PackedTopCoordinates uv, out vec3 normal) {
    vec4 a = materialTexel(u_terrainData, layer * .5, uv.a, uv.gradient);
    vec4 b = materialTexel(u_terrainData, layer * .5, uv.b, uv.gradient);
    normal = vec3(0.0, 0.0, 1.0);
    if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
        vec2 xyA = a.rg * 2.0 - 1.0, xyB = b.rg * 2.0 - 1.0;
        vec3 nA = normalize(vec3(xyA, sqrt(max(1.0 - dot(xyA, xyA), .001))));
        vec3 nB = normalize(vec3(xyB, sqrt(max(1.0 - dot(xyB, xyB), .001))));
        normal = normalize(mix(nA, nB, uv.blend));
    }
    return vec2(sqrt(mix(a.b * a.b, b.b * b.b, uv.blend)), mix(a.a, b.a, uv.blend));
}

// Sides: one read per projection, the two side projections always and the top only where the face lies back.
// Tops (no sides): the top projection's two reads; far away two repeats settle to the map's average.
vec4 rockTexel(sampler2DArray maps, float layer, float tile, MaterialProjection p, bool sides, float variation,
      bool translated) {
    if (!sides) {
        vec2 a, b;
        mat2 gradientB;
        float t;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
        vec4 pigment = mix(materialTexel(maps, layer, a, p.topGradient / tile),
              materialTexel(maps, layer, b, gradientB), t);
        if (farDetail > 0.0 && !translated) {
            pigment = mix(pigment, materialTexel(maps, layer, p.top / tile, p.topGradient * (4096.0 / tile)),
                  farDetail);
        }
        return pigment;
    }
    vec4 steep = mix(materialTexel(maps, layer, p.y / tile + .37, p.yGradient / tile),
          materialTexel(maps, layer, p.x / tile, p.xGradient / tile), p.side);
    if (p.lying <= 0.0) return steep;
    return mix(steep, materialTexel(maps, layer, p.top / tile, p.topGradient / tile), p.lying);
}
vec4 rockTexel(float layer, float tile, MaterialProjection p, bool sides, float variation, bool translated) {
    return rockTexel(u_terrainLayers, layer, tile, p, sides, variation, translated);
}

// The authored roughness that goes with rockTexel's colour, from the same reads. Its grain fades out with the normal
// detail, as the relief does, and then the map's mean replaces the reads.
float rockRoughness(float layer, float tile, MaterialProjection p, bool sides, float variation, bool translated) {
    vec2 authored = u_sculptRoughness[int(layer + .5)];
    if (authored.y < 0.0 || terrainNormalDetail <= 0.0) return authored.x;
    return mix(authored.x, rockTexel(u_roughnessLayers, authored.y, tile, p, sides, variation, translated).r,
          terrainNormalDetail);
}

// The matching world normal and cavity from the normal/AO array; a top uses the same two reads as its colour.
vec4 rockNormal(float layer, float tile, MaterialProjection p, vec3 face, bool sides, float variation,
      bool translated) {
    if (u_normalMaps <= .5) return vec4(face, 1.0);
    if (terrainNormalDetail <= 0.0) return vec4(face, TERRAIN_DISTANT_CAVITY);
    vec3 normal;
    float cavity;
    if (!sides) {
        vec2 a, b;
        mat2 gradientB;
        float t;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
        vec4 n = materialTexel(u_terrainData, layer, a, p.topGradient / tile);
        vec4 m = materialTexel(u_terrainData, layer, b, gradientB);
        if (translated) {
            n = mix(n, m, t);
            normal = upNormal(mix(n.rgb * 2.0 - 1.0, vec3(0.0, 0.0, 1.0), farDetail), face);
            cavity = n.a;
        } else {
            // The enlarged repeat carries the broad fractures and plates: its relief must stay.
            vec4 relief = planarNormal(n, m, t);
            normal = upNormal(relief.rgb, face);
            cavity = relief.a;
        }
    } else {
        vec4 nx = materialTexel(u_terrainData, layer, p.x / tile, p.xGradient / tile);
        vec4 ny = materialTexel(u_terrainData, layer, p.y / tile + .37, p.yGradient / tile);
        normal = wallNormal(nx, ny, face, p.side);
        cavity = mix(ny.a, nx.a, p.side);
        if (p.lying > 0.0) {
            vec4 t = materialTexel(u_terrainData, layer, p.top / tile, p.topGradient / tile);
            normal = normalize(mix(normal, upNormal(t.rgb * 2.0 - 1.0, face), p.lying));
            cavity = mix(cavity, t.a, p.lying);
        }
    }
    return vec4(normalize(mix(face, normal, terrainNormalDetail)),
          mix(TERRAIN_DISTANT_CAVITY, mix(.55, 1.0, cavity), terrainNormalDetail));
}

// A light film of the local hardpan, ash or fines left on exposed level stone.
float familyDust(float f) {
    if (abs(f - DESERT_FAMILY) < .5 || abs(f - MARS_FAMILY) < .5 || abs(f - VOLCANO_FAMILY) < .5) return .24;
    if (abs(f - 1.0) < .5) return .18;
    if (abs(f - 3.0) < .5 || abs(f - LUNAR_FAMILY) < .5) return .12;
    return 0.0;
}

// The projection's gradients must come from before the caller's varying branches; the noise reads use explicit LOD.
TerrainMaterial cliffMaterial(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim,
      float rock, float hardness, float broad, float fine, float region, float sediment, float familyId,
      vec4 tiles, vec4 layers, bool sides, bool single) {
    // Desert/Mars depth-zero bars blend the original soft deposit edge halfway toward the broader shore deposit.
    if (shallowSediment >= 0.0 && (abs(familyId - DESERT_FAMILY) < .5 || abs(familyId - MARS_FAMILY) < .5)) {
        sediment = shallowSediment;
    }
    vec4 ramp, trim, kind;
    cliffFamily(familyId, ramp, trim, kind);
    bool fungus = abs(familyId - FUNGUS_FAMILY) < .5;
    float up = clamp(face.z, 0.0, 1.0);
    float erosion = max(u_terrainWear, kind.w);
    float pockets = textureLod(u_rainNoise, (world.xy + world.z * vec2(.43, .27)) / 92.0 + .57, 0.0).g;
    float variation = broad * .4 + fine * .25 + pockets * .35;
    float foot = exp(-max(aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, belowRim);
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    // Banks (rock 0) keep their mantle; rock cliffs (rock 1) expose the wall; a contact breaks into patches.
    float exposure = clamp(rock + (variation - .5) * 1.2 * (4.0 * rock * (1.0 - rock)), 0.0, 1.0);
    // Exposed shoulders lose loose cover at the rim; sheltered patches keep it. A bank's mantle wears open.
    float scour = (1.0 - smoothstep(.1, 3.0 + 4.0 * broad, belowRim)) * exposure
          * smoothstep(.30, .65, broad * .6 + pockets * .4) * erosion;
    float bank = (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up));
    float coverUp = up - .2 * bank * smoothstep(.32, .68, fine * .65 + pockets * .35) * erosion;
    float thin = smoothstep(.50, .72, region * .6 + broad * .3 + fine * .1) * smoothstep(.82, .97, up)
          * (1.0 - deposit) * (1.0 - .75 * foot) * u_terrainWear;
    float shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, belowRim)) * smoothstep(.28, .65, pockets)
          * smoothstep(.55, .94, up) * u_terrainWear;
    float cover = smoothstep(ramp.x, ramp.y, coverUp + (variation - .5) * ramp.z + deposit * ramp.w);
    cover *= 1.0 - max(trim.x * scour, max(trim.y * thin * mix(1.0, rim, trim.z), trim.w * shoulder));
    if (kind.z > 0.0) {
        // A meadow's two-level bank keeps a ragged turf lip and broken turf on its intermediate shoulder.
        float levelMetres = u_levelHeight / u_metre;
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((aboveFoot + belowRim) / levelMetres - 2.0));
        float fray = textureLod(u_rainNoise, (world.xy + world.z * vec2(.29, .41)) / 28.0 + .217, 0.0).r;
        float lipDepth = levelMetres * (mix(.2, .65, fine * .45 + pockets * .55) + .14 * (fray - .5));
        float lip = 1.0 - smoothstep(.05, lipDepth, belowRim);
        float middle = abs(aboveFoot / levelMetres - 1.0 - (pockets - .5) * .18);
        float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
        float turf = (1.0 - smoothstep(width * .25, width, middle)) * smoothstep(.35, .73, up)
              * smoothstep(.22, .55, pockets * .7 + fine * .3);
        cover = max(cover, max(lip, turf) * twoLevels * (1.0 - exposure));
        deposit *= mix(.25, 1.0, rock);
    }
    // Organic weathering opens a meadow's level cover onto its soil and thins fungal crust.
    float aged = u_terrainWear * terrainNormalDetail * smoothstep(.45, .68, broad * .5 + region * .35 + fine * .15)
          * smoothstep(.88, .98, up) * (1.0 - sediment);
    float worn = kind.z > 0.0 ? cover * aged * .48 : 0.0;
    cover -= worn;
    float mantle = 0.0;
    if (kind.x > .5 && kind.x < 1.5) {
        mantle = (1.0 - cover) * ((1.0 - exposure) * .9 + rim * .5 * smoothstep(.25, .7, variation));
    } else if (kind.x > 1.5 && kind.x < 2.5) {
        mantle = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    } else if (kind.x > 2.5) {
        mantle = (1.0 - cover) * max(smoothstep(.82, .97, up), rim * smoothstep(.25, .90, up));
    }
    mantle = max(mantle, worn);
    deposit *= kind.y;
    if (fungus) {
        // Mauve lowlands; cyan crust colonies above level zero and along raised rims.
        float levelMetres = u_levelHeight / u_metre;
        float rise = smoothstep(.25, .9, (aboveFoot + belowRim) / levelMetres);
        float raised = smoothstep(.15, 1.3, v_cloudPosition.z / u_levelHeight);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * pockets, belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.28, .69, region * .5 + broad * .3 + pockets * .2);
        deposit = max(deposit, smoothstep(.48, .88, up) * mix(.10 * colonies, .28 + .70 * colonies, growth)
              * (1.0 - aged * .55));
        cover = smoothstep(.76, .97, up) * (1.0 - .90 * thin * (1.0 - growth));
        mantle = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    }
    float capPlates = step(2.5, kind.x);
    vec4 roles = vec4(cover, clamp(mantle, 0.0, 1.0 - cover), deposit, 0.0);
    roles.xy *= 1.0 - deposit;
    roles.w = max(0.0, 1.0 - roles.x - roles.y - roles.z);
    // Concrete carried onto another surface is loose aggregate, never a second paved slab.
    if (abs(familyId - 4.0) < .5) roles = vec4(0.0, 0.0, 1.0, 0.0);
    // Ordinary river shores expose earth and gravel before reaching water. Reassign the existing mantle/deposit
    // slots, rather than sampling an extra layer; snow and dune sand must not become the submerged bed.
    bool soilBank = riverBank && (familyId < 2.5 || abs(familyId - 5.0) < .5
          || abs(familyId - TROPICAL_FAMILY) < .5);
    float coverEnd = abs(familyId) < .5 || abs(familyId - TROPICAL_FAMILY) < .5 ? .6 : .9;
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, aboveFoot);
    float bankBlend = 0.0;
    if (soilBank) {
        // Keep the original surface at the dry edge. Empty the two reused slots before changing their maps,
        // then feather native cover into soil and gravel; replacing live slots makes a hard material seam.
        float bankRock = bedRock > 0.0 ? roles.w : 0.0;
        roles = mix(roles, vec4(1.0 - bankRock, 0.0, 0.0, bankRock), smoothstep(0.0, .03, sediment));
        bankBlend = clamp((sediment - .03) / .97, 0.0, 1.0);
        if (bankBlend > 0.0) {
            layers.w = u_bankMaterials.x; tiles.w = u_bankMaterials.y;
            layers.y = u_bankMaterials.z; tiles.y = u_bankMaterials.w;
            // Grass gives way to soil earlier; gravel still starts beside the water. Keep only two roles on earth banks.
            // Rock cliffs retain their existing strongest-two-role treatment.
            roles = mix(roles, vec4(0.0, 1.0 - bedRock, 0.0, bedRock), clamp(bankBlend / coverEnd, 0.0, 1.0));
            roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), clamp((bankBlend - .9) / .1, 0.0, 1.0));
        }
    } else {
        roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    }
    // The two strongest roles only (the first of equals); weaker ones cannot show through a height blend.
    float first = max(max(roles.x, roles.y), max(roles.z, roles.w));
    vec4 isFirst = step(vec4(first), roles);
    isFirst *= 1.0 - step(.5, vec4(0.0, isFirst.x, isFirst.x + isFirst.y, isFirst.x + isFirst.y + isFirst.z));
    // Exposed level stone keeps a film of its cover: a dusty top always samples the cover as one of its two roles.
    float dust = single ? 0.0 : familyDust(familyId) * smoothstep(.88, .98, up) * (1.0 - sediment);
    if (soilBank) dust *= 1.0 - clamp(bankBlend / coverEnd, 0.0, 1.0);
    vec4 rest = roles * (1.0 - isFirst);
    if (dust > 0.0) rest.x += 2.0 * (1.0 - isFirst.x);
    float pick = max(max(rest.x, rest.y), max(rest.z, rest.w));
    vec4 isSecond = step(vec4(pick), rest) * (1.0 - isFirst);
    isSecond *= 1.0 - step(.5, vec4(0.0, isSecond.x, isSecond.x + isSecond.y, isSecond.x + isSecond.y + isSecond.z));
    float second = dot(isSecond, roles);
    // Roles in layer order: cover is the ground layer, mantle the mantle, deposit the debris, rock the wall.
    // Tops: cover and rock vary over broad regions, the deposit finely, a mantle finely (cap plates broadly).
    // Sand covers, the desert's and volcano's cap plates and fungus take translated reads of one repeat.
    float plates = abs(familyId - DESERT_FAMILY) < .5 || abs(familyId - VOLCANO_FAMILY) < .5 ? 1.0 : 0.0;
    vec4 variations = vec4(broad, mix(fine, broad, plates), fine, broad);
    vec4 translated = fungus ? vec4(1.0) : vec4(abs(familyId - 2.0) < .5 ? 1.0 : 0.0, plates, 0.0, 0.0);
    float layerA = dot(isFirst, vec4(layers.x, layers.w, layers.y, layers.z)) * .5;
    float tileA = dot(isFirst, vec4(tiles.x, tiles.w, tiles.y, tiles.z));
    float variationA = dot(isFirst, variations);
    bool translatedA = dot(isFirst, translated) > .5;
    vec4 a = rockTexel(layerA, tileA, p, sides, variationA, translatedA);
    vec4 b = a;
    float share = 0.0;
    float layerB = layerA, tileB = tileA, variationB = variationA;
    bool translatedB = translatedA;
    if ((!single || bankBlend > 0.0) && (second > .02 || dust > 0.0)) {
        layerB = dot(isSecond, vec4(layers.x, layers.w, layers.y, layers.z)) * .5;
        tileB = dot(isSecond, vec4(tiles.x, tiles.w, tiles.y, tiles.z));
        variationB = dot(isSecond, variations);
        translatedB = dot(isSecond, translated) > .5;
        b = rockTexel(layerB, tileB, p, sides, variationB, translatedB);
        // Height blend (materialWeights): where both are wanted, the layer whose relief stands higher shows.
        float wa = first + a.a * .38 * 4.0 * first * (1.0 - first);
        float wb = second + b.a * .38 * 4.0 * second * (1.0 - second);
        float width = materialBlendWidth();
        float ka = max(wa - max(wa, wb) + width, 0.0), kb = max(wb - max(wa, wb) + width, 0.0);
        share = kb / max(ka + kb, .00001);
        // The dry bank needs a visible fade, including blended-cover draws that normally pick one role.
        if (bankBlend > 0.0) {
            float feather = smoothstep(0.0, .03, bankBlend);
            share = mix(share, second / max(first + second, .00001), feather);
            if (single) share *= feather;
        }
        // The film: a share of the stone's weight goes to the cover, after the height blend.
        if (dust > 0.0) share = isFirst.x > .5 ? share * (1.0 - dust) : share + dust * (1.0 - share);
    }
    vec4 na = rockNormal(layerA, tileA, p, face, sides, variationA, translatedA);
    vec4 nb = share > 0.0 ? rockNormal(layerB, tileB, p, face, sides, variationB, translatedB) : na;
    vec4 roleShare = isFirst * (1.0 - share) + isSecond * share;
    TerrainMaterial result;
    result.color = mix(a.rgb, b.rgb, share);
    // Strata: harder beds weather paler and slightly cooler than the softer beds between them.
    if (sides) {
        float rockShare = roleShare.w + roleShare.y * capPlates;
        result.color *= mix(vec3(1.0), mix(vec3(.93, .92, .91), vec3(1.04, 1.04, 1.05), hardness), rockShare);
    }
    if (abs(familyId - DESERT_FAMILY) < .5) {
        // Pale abraded sandstone at the actual rim reads from above; deeper faces keep their warm brown.
        float abrasion = (1.0 - smoothstep(.15, 2.2, belowRim)) * smoothstep(.45, .95, up);
        vec3 weathering = mix(vec3(.98, .91, .85), vec3(1.14, 1.15, 1.13), abrasion);
        result.color *= mix(vec3(1.0), authoredPigmentGain(mix(vec3(1.0), weathering, u_terrainWear)), roleShare.w);
    }
    if (abs(familyId - 2.0) < .5) {
        float mineral = dot(result.color, vec3(.2126, .7152, .0722));
        result.color = mix(result.color, mineral * toLinear(vec3(.86, .92, .98)), greySand());
    }
    if (roleShare.x > 0.0) {
        result.color = mix(result.color, groundToneFor(familyId, result.color, world, broad, fine, region, rim, foot),
              roleShare.x);
    }
    result.normal = normalize(mix(na.rgb, nb.rgb, share));
    result.cavity = mix(na.a, nb.a, share);
    result.height = mix(a.a, b.a, share);
    result.emission = vec3(0.0);
    float roughnessA = rockRoughness(layerA, tileA, p, sides, variationA, translatedA);
    result.roughness = share > 0.0
          ? mix(roughnessA, rockRoughness(layerB, tileB, p, sides, variationB, translatedB), share) : roughnessA;
    result.volcanic = 0.0;
    return result;
}

#ifdef terrainBlendFlag
vec4 coverTilesAt(int i) {
    return i == 0 ? u_coverTiles0 : i == 1 ? u_coverTiles1 : i == 2 ? u_coverTiles2 : u_coverTiles3;
}
vec4 coverLayersAt(int i) {
    return i == 0 ? u_coverLayers0 : i == 1 ? u_coverLayers1 : i == 2 ? u_coverLayers2 : u_coverLayers3;
}

// A blended cliff: its two strongest palette slots, each as one cliff material, meeting in broken patches.
TerrainMaterial blendCliff(MaterialProjection p, vec3 world, vec3 face, float aboveFoot, float belowRim, float rock,
      float hardness, float broad, float fine, float region, float sediment, out vec4 weights) {
    vec4 raw = max(v_coverWeights, vec4(0.0));
    int first = 0;
    for (int i = 1; i < 4; i++) if (raw[i] > raw[first]) first = i;
    int second = first == 0 ? 1 : 0;
    for (int i = 0; i < 4; i++) if (i != first && raw[i] > raw[second]) second = i;
    float share = raw[second] / max(raw[first] + raw[second], .00001);
    if (coverFamilies[second] == coverFamilies[first]) share = 0.0;
    TerrainMaterial a = cliffMaterial(p, world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          sediment, coverFamilies[first], coverTilesAt(first), coverLayersAt(first), true, false);
    weights = vec4(0.0);
    weights[first] = 1.0;
    if (share < .02) return a;
    TerrainMaterial b = cliffMaterial(p, world, face, aboveFoot, belowRim, rock, hardness, broad, fine, region,
          sediment, coverFamilies[second], coverTilesAt(second), coverLayersAt(second), true, false);
    float patches = textureLod(u_rainNoise, (world.xy + world.z * vec2(.47, .29)) / 9.5, 0.0).b;
    float t = smoothstep(.35, .65, share + (b.height - a.height) * .25 + (patches - .5) * .3);
    weights[first] = 1.0 - t;
    weights[second] += t;
    a.color = mix(a.color, b.color, t);
    a.normal = normalize(mix(a.normal, b.normal, t));
    a.cavity = mix(a.cavity, b.cavity, t);
    a.height = mix(a.height, b.height, t);
    a.roughness = mix(a.roughness, b.roughness, t);
    return a;
}
#endif
