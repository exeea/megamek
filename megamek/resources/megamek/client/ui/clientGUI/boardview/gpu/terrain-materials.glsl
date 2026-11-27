// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Natural surfaces: what every sculpt program holds before its ground path's own file (ground-material.glsl, or
// old-ground-material.glsl for an A/B): the material's type, the projections, wear and weathering, the translated and
// packed reads, the cover weights and lava beside ground. Inserted after the lighting helpers.

// A full biome deposit replaces substrate colour, finish and normal direction. Its height still contributes
// weathering and shadow lift, and its filtered normal length still contributes distant lighting.
bool terrainCovered = false;
bool riverBank = false; // set by the water-owned ground, before evaluating its material roles
float shallowSediment = -1.0; // Halfway depth-zero arid shore profile; negative on deeper water and dry land.

struct TerrainMaterial {
    vec3 color; // linear reflectance; color maps are decoded at upload, before filtering
    vec3 normal;
    float cavity;
    float height;
    Finish finish;
    float volcanic;
    float relief; // how well its normals under the pixel agree, their mean's length: 1 for one (sunOnRelief)
};

float materialBlendWidth() { return .13 + min(.2, pixelMetres * .16); }

// Natural wear follows the existing relief, modulated by continuous world fields instead of repeated stamps.
// Channels are recesses, low grains, chipped edges and high grains. Loose/organic covers favour the height
// channels; stone favours cavities and normal-map edges.
vec4 terrainWear(vec3 fields, float height, float cavity, vec3 face, vec3 normal) {
    float exposure = smoothstep(.30, .67, fields.x * .65 + fields.z * .35);
    float abrasion = smoothstep(.32, .73, fields.y * .7 + fields.z * .3);
    float low = (1.0 - smoothstep(.24, .53, height)) * .75;
    float recess = max(low, clamp((1.0 - cavity) * 2.2, 0.0, 1.0));
    float high = smoothstep(.40, .65, height);
    float edge = clamp((1.0 - dot(face, normal)) * 5.0, 0.0, 1.0) * high;
    return vec4(recess * exposure, low * exposure, edge * abrasion, high * abrasion);
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
        return -vec3(.15, .135, .115) * grime * (.4 + .6 * pores);
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
    vec2 facing; // the side projections' signs along X and Y
    // The sides' horizontal normals, x's then y's: (facing.x, 0) and (0, facing.y), or a step face's own two
    // (terrainFaceFrames). A side's U axis is its normal turned a quarter left.
    vec4 frames;
    float topTexels; // the top projection's texels a pixel at a one-unit repeat (enlargedShare)
};

// Cut walls keep one texture frame across their facets; zero leaves natural landforms unchanged.
vec3 terrainWallProjection = vec3(0.0);
// A step face without a frame of its own (a field bank's), set by the includer: its two side planes are the directions
// a twelfth of a turn apart that its horizontal normal lies between, so a straight face reads along its own strike and
// the material's grain runs straight down it. False: the two fixed planes.
bool terrainFaceFrames = false;

MaterialProjection materialProjection(vec3 world, vec3 face) {
    vec3 basis = dot(terrainWallProjection, terrainWallProjection) > .5 ? terrainWallProjection : face;
    MaterialProjection p;
    p.top = vec2(world.x, -world.y);
    vec2 frameX = vec2(sign(basis.x), 0.0), frameY = vec2(0.0, sign(basis.y));
    float turned = -1.0;
    if (terrainFaceFrames && dot(face.xy, face.xy) > 1e-6) {
        float turn = atan(face.y, face.x) / .5235988, first = floor(turn);
        frameX = vec2(cos(first * .5235988), sin(first * .5235988));
        frameY = vec2(cos((first + 1.0) * .5235988), sin((first + 1.0) * .5235988));
        turned = turn - first;
    }
    p.frames = vec4(frameX, frameY);
    vec2 strikeX = vec2(-frameX.y, frameX.x), strikeY = vec2(-frameY.y, frameY.x);
    p.x = vec2(dot(world.xy, strikeX), -world.z);
    p.y = vec2(dot(world.xy, strikeY), -world.z);
    p.topGradient = mat2(dFdx(p.top), dFdy(p.top));
    p.topTexels = max(length(p.topGradient[0]), length(p.topGradient[1])) * float(textureSize(u_terrainLayers, 0).x);
    // From the position's own derivatives: a face's frames turn with it, which a derivative of p.x would take in.
    vec3 worldDx = dFdx(world), worldDy = dFdy(world);
    p.xGradient = mat2(vec2(dot(worldDx.xy, strikeX), -worldDx.z), vec2(dot(worldDy.xy, strikeX), -worldDy.z));
    p.yGradient = mat2(vec2(dot(worldDx.xy, strikeY), -worldDx.z), vec2(dot(worldDy.xy, strikeY), -worldDy.z));
    vec2 axes = pow(abs(basis.xy), vec2(4.0));
    p.side = turned >= 0.0 ? 1.0 - turned : axes.x / max(axes.x + axes.y, .00001);
    p.lying = smoothstep(.25, .87, basis.z);
    p.facing = sign(basis.xy);
    return p;
}

// Godot's heightmap parallax as StandardMaterial3D draws it without deep parallax: every read of a role moves against
// the view, in its projection's frame, by the role's depth below the heightmap's white at its unmoved reads. Each
// projection is the plane it is read in (a top the ground's, a side the wall's); the depth is the heightmap's
// (u_finishRelief, in repeats) in metres. A relief that would move less than half a pixel moves nothing visible and
// costs no read. The Terrain parallax switch (terrainParallaxFlag) compiles it in, apart from the normal maps.
vec3 terrainView = vec3(0.0); // from the surface to the eye, which lit programs' main sets; zero moves nothing

// A projection's move at the full depth, in its coordinates: the view along its U and V axes times the depth. Zero
// where that is under half a pixel.
vec2 projectedReach(vec2 view, vec2 depth, mat2 gradient) {
    vec2 reach = view * depth;
    return dot(reach, reach) < .25 * max(dot(gradient[0], gradient[0]), dot(gradient[1], gradient[1])) ? vec2(0.0) : reach;
}

// A role's move at the full depth in one projection, at a repeat of tile metres, given the view along its U and V axes
// and the projection's gradient in metres.
vec2 planeReach(float layer, float tile, vec2 view, mat2 gradient) {
#ifdef terrainParallaxFlag
    return projectedReach(view, u_finishRelief[int(layer + .5)] * tile, gradient);
#else
    // A constant: the callers' moves and their height reads fold away.
    return vec2(0.0);
#endif
}

// Projection is shared; callers select colour or numerical storage explicitly.
vec4 materialTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    return textureGrad(maps, vec3(uv, layer), gradient[0], gradient[1]);
}

// How far the close view's normals under a filtered normal/AO read disagree: about half their slopes' variance, from
// 1 less the length of their mean, which the mips keep (GpuAssets.mipmaps). Zero within a texel. The factor .5 is an
// empirical calibration, fitted on NVIDIA to GpuMaterialLodSmokeTest's seven materials (far ground at 0.8 m a pixel
// against the 0.1 m close view averaged): with the read's full shortening rock and dirt came out 9.5% and 6.0% darker,
// with half of it 3.3% and 3.8%; HEAD's fixed distant relief was 5.5% and 3.4% brighter. Mixing independent reads
// with weights w before normalising mixes their slopes, so the result's spread is the sum of w^2 times theirs: the
// close view's normals agree better than any one map's (relief, sunOnRelief). The reads that take a spread give it
// out.
float normalSpread(vec4 texel) { return .5 * (1.0 - min(length(texel.rgb * 2.0 - 1.0), 1.0)); }

// Two reads mixed by t, without the read whose weight is exactly zero: it would change nothing. Gradients are explicit,
// so the reads may sit in varying branches.
vec4 mixedTexel(sampler2DArray maps, float layer, vec2 a, mat2 gradientA, vec2 b, mat2 gradientB, float t,
      out float spread) {
    if (t <= 0.0 || t >= 1.0) {
        vec4 only = t <= 0.0 ? materialTexel(maps, layer, a, gradientA) : materialTexel(maps, layer, b, gradientB);
        spread = normalSpread(only);
        return only;
    }
    vec4 first = materialTexel(maps, layer, a, gradientA), second = materialTexel(maps, layer, b, gradientB);
    spread = (1.0 - t) * (1.0 - t) * normalSpread(first) + t * t * normalSpread(second);
    return mix(first, second, t);
}

vec4 mixedTexel(sampler2DArray maps, float layer, vec2 a, mat2 gradientA, vec2 b, mat2 gradientB, float t) {
    float spread;
    return mixedTexel(maps, layer, a, gradientA, b, gradientB, t, spread);
}

// The enlarged repeat (2.37 times larger, turned) hides the first repeat's tiling from afar. Up close it only magnifies
// its texels: its share fades out as the first repeat's texels pass one screen pixel, from 2.37 a pixel to one, where a
// view spans about two repeats and no tiling shows.
float enlargedShare(float variation, MaterialProjection p, float tile) {
    return variation * smoothstep(1.0, 2.37, p.topTexels / max(tile, 1e-4));
}

// Overlapping translated samples break the fungal source's recognisable repeats. Use identical offsets for
// colour/height and normal/AO; translations keep tangent normals aligned and leave vertical cliff grain upright.
vec2 materialOffset(vec2 cell) {
    return fract(sin(vec2(dot(cell, vec2(127.1, 311.7)), dot(cell, vec2(269.5, 183.3)))) * 43758.5453) * 8.0;
}

// Select neighbouring source offsets from a continuous world field, not the UV tile or hex ID. Two reads keep the
// original feature size and wind/bedding direction without the recognisable motif repeating on a square grid.
// Color/height and normal/AO must use the same selector; a translation does not rotate or resize their normals.
vec4 translatedTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation, out float spread) {
    float choice = variation * 8.0, index = floor(choice);
    return mixedTexel(maps, layer, uv + materialOffset(vec2(index, 0.0)), gradient,
          uv + materialOffset(vec2(index + 1.0, 0.0)), gradient, smoothstep(.2, .8, fract(choice)), spread);
}

vec4 translatedTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
    float spread;
    return translatedTexel(maps, layer, uv, gradient, variation, spread);
}

// translatedTexel's height, for the parallax.
float translatedHeight(float layer, vec2 uv, mat2 gradient, float variation) {
    float choice = variation * 8.0, index = floor(choice);
    return mixedHeight(layer, uv + materialOffset(vec2(index, 0.0)), gradient,
          uv + materialOffset(vec2(index + 1.0, 0.0)), gradient, smoothstep(.2, .8, fract(choice)));
}

vec4 materialTexel(float layer, vec2 uv, mat2 gradient) {
    return materialTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 translatedTexel(float layer, vec2 uv, mat2 gradient, float variation) {
    return translatedTexel(u_terrainLayers, layer * .5, uv, gradient, variation);
}

// Direct sunlight on flattened relief whose normals average `relief`, relative to its flat face lit at
// s = cos(incidence): facets tilted acos(relief) every way round, those turned from the sun taking none. A high sun
// loses the tilted share; a low one, caught by the facets facing it, loses less, and never gains (nor does the close
// view).
float sunOnRelief(float relief, float s) {
    if (relief > .999 || s <= 0.0) return 1.0;
    float a = sqrt((1.0 - relief * relief) * (1.0 - s * s)), b = relief * s;
    if (b >= a) return relief;
    float turn = acos(-b / a);
    return min(1.0, (a * sin(turn) + b * turn) / (PI * s));
}

// Legacy planar/slab/rock paths retain their own colour-map projection.
Finish planarFinish(float layer, vec2 p, mat2 gradient, float tile, float mixer) {
    return mixFinish(finishTexel(layer, p / tile, gradient / tile),
          finishTexel(layer, TURN * p / (tile * 2.37) + .31, TURN * gradient / (tile * 2.37)), mixer);
}

Finish translatedFinish(float layer, vec2 uv, mat2 gradient, float variation) {
    int material = int(layer * .5);
    vec4 surface = u_finishSurface[material], emission = u_finishEmission[material];
    Finish texel = meanFinish(material);
    if (surface.a >= 0.0) {
        texel = finishOf(translatedTexel(FINISH_LAYERS, surface.a, uv, gradient, variation).rgb, texel.emission);
    }
    if (emission.a >= 0.0) {
        texel.emission += emission.rgb * toLinear(translatedTexel(FINISH_LAYERS, emission.a, uv, gradient, variation).rgb);
    }
    return texel;
}

Finish wallFinish(float layer, vec2 x, vec2 y, mat2 dx, mat2 dy, float side) {
    return mixFinish(finishTexel(layer, y, dy), finishTexel(layer, x, dx), side);
}

#ifdef volcanicFlag

TerrainMaterial volcanicMaterial(Volcanic material, float strength) {
    return TerrainMaterial(material.albedo, material.normal, material.surface.b, material.surface.r,
          Finish(material.surface.g, 0.0, 0.0, magmaEmission(material.heat, strength)), 1.0, 1.0);
}

TerrainMaterial volcanicMaterial(Volcanic material, float familyId, float bankHeat) {
    return volcanicMaterial(material, familyId > VOLCANIC_BANK_FAMILY - .5 ? bankHeat : 1.0);
}

// Volcanic programs without a palette draw sculpt materials only for crust and bank families.
TerrainMaterial crustOrBankMaterial(vec3 world, vec3 face) {
    Volcanic material = magmaSurface(world, face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
    return volcanicMaterial(material,
          sculptFamily > VOLCANIC_BANK_FAMILY - .5 ? magmaBankHeat(v_cloudPosition.xy) : 1.0);
}
#endif

#ifdef terrainBlendFlag

// How far the covers' plain proportions replace their height-blended patches: fully in an authored transition
// (v_coverInterpolation), whose mixture is the hex's own look, except a tropical mixture's dark interior litter; about
// half between neighbouring covers on level ground, softening them without losing their interlocking texture. Banks
// take an authored transition's part (blendCliff), so a bank meets the ground at its foot in the same mixture.
float coverGradient(vec4 proportions) {
    float authored = clamp(v_coverInterpolation, 0.0, 1.0);
    float tropical = dot(proportions, vec4(lessThan(abs(coverFamilies - TROPICAL_FAMILY), vec4(.5))));
    return mix(.55, 1.0, authored) * (1.0 - authored * smoothstep(0.0, .15, tropical));
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
#endif

// A top's two reads: two repeats of the map (the second 2.37 times larger and turned) mixed by a world field, or for
// translated materials (sand, cap plates, fungus) two offsets of one repeat, which keeps their single feature size.
bool oneRepeat = false; // Medium quality: this role's tops read their first repeat alone (cliffMaterialOf).

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
        t = oneRepeat ? 0.0 : enlargedShare(variation, p, tile);
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
    return mixedTexel(u_terrainLayers, layer * .5, uv.a, uv.gradient, uv.b, uv.gradient, uv.blend);
}

// The packed layer's finish from its colour's reads.
Finish packedTopFinish(float layer, PackedTopCoordinates uv) {
    if (uv.blend <= 0.0) return finishTexel(layer, uv.a, uv.gradient);
    if (uv.blend >= 1.0) return finishTexel(layer, uv.b, uv.gradient);
    return mixFinish(finishTexel(layer, uv.a, uv.gradient), finishTexel(layer, uv.b, uv.gradient), uv.blend);
}

// Returns RMS roughness and AO. Numeric maps remain necessary when normal maps are off.
vec2 packedTopSurface(float layer, PackedTopCoordinates uv, out vec3 normal) {
    // Without the read whose weight is exactly zero: the other stands for both.
    vec4 a = uv.blend < 1.0 ? materialTexel(u_terrainData, layer * .5, uv.a, uv.gradient) : vec4(0.0);
    vec4 b = uv.blend > 0.0 ? materialTexel(u_terrainData, layer * .5, uv.b, uv.gradient) : a;
    if (uv.blend >= 1.0) a = b;
    normal = vec3(0.0, 0.0, 1.0);
    if (u_normalMaps > .5) {
        vec2 xyA = a.rg * 2.0 - 1.0, xyB = b.rg * 2.0 - 1.0;
        vec3 nA = normalize(vec3(xyA, sqrt(max(1.0 - dot(xyA, xyA), .001))));
        vec3 nB = normalize(vec3(xyB, sqrt(max(1.0 - dot(xyB, xyB), .001))));
        normal = normalize(mix(nA, nB, uv.blend));
    }
    return vec2(sqrt(mix(a.b * a.b, b.b * b.b, uv.blend)), mix(a.a, b.a, uv.blend));
}

#ifdef terrainBlendFlag

// BoardSurfaceBlend.footTongue/footReach per pixel: the ground below a bank climbs it in tongues. The bank's vertices carry
// that ground's share of their cover uncut (v_coverFoot), and each pixel cuts it back by its height above the foot, so
// the tongues keep their shape at any mesh density.
vec4 footCover(vec4 raw, vec3 world, float aboveFoot) {
    vec4 foot = clamp(v_coverFoot, vec4(0.0), raw);
    if (dot(foot, vec4(1.0)) <= 0.0) return raw;
    float tongue = .7 * biomeNoise(world.xy / 5.3 + vec2(37.0, -23.0)) + .3 * biomeNoise(world.xy / 1.7 + vec2(-11.0, 29.0));
    vec4 kept = raw - foot * smoothstep(0.0, 1.0, max(aboveFoot, 0.0) / (FOOT_METRES * (.2 + .8 * tongue)));
    return kept / max(dot(kept, vec4(1.0)), .00001);
}
#endif
