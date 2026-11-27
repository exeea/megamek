// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The compact natural-ground material: one evaluator for every surface family, the families' rules as numbers in the
// board's material table (BoardMaterials, GroundFamilies.java), two roles a pixel, one projection a role (two only in
// the narrow bands where a face turns between them), a height blend between the roles and the family's tone. It returns
// the material before the live layers (weathering, damage, scorch, rain, the grid and the level grade) and the light.
// Every sculpt program holds it unless the old path is asked for (GroundFamilies.ON; old-ground-material.glsl then
// takes its place: never both). Its texture arrays and noise are named below; the includer gives GROUND_FAMILIES, the
// table's rows (BoardScene.Surface's count), authoredPigmentGain (terrain-ground-color.glsl) and optionally
// BOARD_MATERIALS_LAYOUT, the block's layout with an explicit binding where the language has one. The rules only some
// families use are kinds, each compiled in by its define (ground<Kind>Rule, GroundFamilies.Rule) on a board with a
// family that uses it and, where it has a quality switch, while that is on. The foot deposit, the three fields,
// strata and the projections' bands are always compiled in.
// Colour/height and normal/AO (the same layer), and the tiling 64-texel noise read at uv in repeats.
#define GROUND_COLOUR u_terrainLayers
#define GROUND_DATA u_terrainData
#define GROUND_NOISE(uv) textureLod(u_surfaceNoise, rainNoiseAt(uv), 0.0)
#ifndef BOARD_MATERIALS_LAYOUT
#define BOARD_MATERIALS_LAYOUT layout(std140)
#endif

// One family's rules (GroundFamilies.Row): 19 vec4, 304 bytes. Roles in order cover, mantle, deposit, rock.
struct GroundFamily {
    vec4 layers;       // colour/height layer of each role (its normal/AO: the same layer of GROUND_DATA)
    vec4 tiles;        // metres per repeat of each role
    vec4 roughness;    // each role map's mean perceptual roughness
    vec4 cover;        // the cover's smoothstep of the slope: low, high, jitter by variation, lift by a foot deposit
    vec4 trim;         // what thins the cover: rim scour, thin patches (only on an exposed rim where z is 1), crest shoulder
    vec4 mantle;       // the bare share's mantle: bank weight (by 1 - exposure), its fade on level ground, rim share, crest cap
    vec4 deposit;      // the foot deposit's share, its share on banks (mix(y, 1, rock)), dust on level stone, wear-through
    vec4 edges;        // two-level turf lip (0/1), crest cornice (0/1), where soil banks end the cover, soil river banks (0/1)
    vec4 shore;        // sediment exponent on shallow coasts, the shallow arid profile (0/1), wall weathering, rim abrasion
    vec4 toneTintA;    // a tone patch: rgb gain where it is whole, a amount
    vec4 toneFieldA;   // its field: weights of broad, region and fine, w the smoothstep's low
    vec4 toneEdgeA;    // x the smoothstep's high, y rim weight, z foot weight, w drift ripples (for A only)
    vec4 toneTintB;    // a second tone patch, as A
    vec4 toneFieldB;
    vec4 toneEdgeB;
    vec4 floor;        // a sunken floor from level -1 down: colour layer (< 0 none), repeat; z crust colonies (0/1);
                       // w walls read in four offset cells (0/1, groundWallOffsets)
    vec4 rootMat;      // rgb the tint a two-level lip's sod leaves on the bank below it
    vec4 abrasionLow;  // rgb a rim's abraded rock below its crest; a the roles whose tops take translated reads
                       // (bits: cover 1, mantle 2, deposit 4, rock 8)
    vec4 abrasionHigh; // rgb the same at the crest; a 1 where the mantle's second read varies with the broad field
};

BOARD_MATERIALS_LAYOUT uniform BoardMaterials {
    GroundFamily groundFamilies[GROUND_FAMILIES];
    vec4 groundRiverBank; // a soil river bank's layers: soil layer, repeat, gravel layer, repeat
    vec4 groundFlatRock;  // the shared cliff's layer, the rock tops' layer and repeat, which level bedrock shows instead
};

// What the caller knows at the pixel. Gradients come from before any varying branch.
struct GroundPixel {
    vec3 world;              // metres
    vec3 face;               // unit smooth normal
    vec2 top, x, y;          // the projections' coordinates in metres: the plane, and the two sides (MaterialProjection)
    mat2 topGradient, xGradient, yGradient;
    float topTexels;         // the top projection's texels a pixel at a one-metre repeat (MaterialProjection)
    float side;              // the share of the x side's projection (MaterialProjection.side)
    vec2 facing;             // the side projections' signs along X and Y
    float aboveFoot, belowRim; // metres
    float rock;              // 0 an earth bank .. 1 a rock cliff
    float hardness;          // bed hardness (walls), .5 on ground
    float sediment;          // 0 dry .. 1 a water bed (the caller's shore rule)
    float shallowSediment;   // a depth-zero arid shore's halfway profile, < 0 elsewhere
    bool riverBank;          // a river's ordinary bank
    float role;              // 0 top cover .. 1 step face (walls, steep banks, bedrock), continuous across their edge
    bool covered;            // a biome deposit replaces the colour and normal (terrainCovered)
    float levelMetres;       // metres a level
    float pixelMetres;       // metres a screen pixel
    bool normalMaps;
    bool cellWalls;          // its walls read in four offset cells (the family's floor.w; groundSurface sets it)
};

struct GroundSurface {
    vec3 albedo;
    vec3 normal;
    float cavity;
    float height;
    float roughness;
    float relief;            // the agreement of the normals under the pixel (sunOnRelief)
    vec4 roles;              // the final share of cover, mantle, deposit and rock
#ifdef groundFinishRule
    vec2 surfaceFinish;      // metallic and scattering where a role's material declares them, else 0
    vec3 emission;           // its emission, in the region field's patches
#endif
};

// The ground's fields (broad, fine, region): broad patches (~9 m cells) as two independent channels mixed, fine ones
// (~2.5 m) and the region (~40 m). With two scales the broad channels are read apart, as today's ground reads them
// (terrain-ground-color.glsl), the finer one breaking the patches up; else both from one read, one read less.
vec3 compactFields(vec3 world) {
#ifdef groundTwoScaleRule
    float broad = GROUND_NOISE(world.xy / 700.0).g * .6 + GROUND_NOISE(world.xy / 430.0 + .19).b * .4;
#else
    vec4 texel = GROUND_NOISE(world.xy / 600.0 + .19);
    float broad = texel.g * .6 + texel.b * .4;
#endif
    return vec3(broad, GROUND_NOISE(world.xy / 160.0 + .41).b, GROUND_NOISE(world.xy / 2600.0 + .73).r);
}

float groundSpread(vec4 texel) { return .5 * (1.0 - min(length(texel.rgb * 2.0 - 1.0), 1.0)); }

// The drawn triangle's own up component where the includer gives it (GROUND_FACE_SLOPE: the lit sculpt program, on
// ground and step faces of a hex's own mesh); 1 elsewhere, which leaves the shading normal to decide.
#define GROUND_FACE_SLOPE
float groundFaceSlope = 1.0;
// 1 on a bare ledge, a step face its maker marks as rock lying level (the cliff byte 138, GpuTerrain#shadeColor), where
// the same includer reads the mark; 0 elsewhere.
float groundBareLedge = 0.0;

// How much of a read lies on the plane: by the face's slope alone, so a step face reads the side it faces and the
// plane only where it lies back, with no seam where the rocky test changes its mind (z .6). An earth bank's shading
// normal turns to the ground's at its rim and foot (ChunkField, as BoardRelief#canonicalWall): there the triangle's
// own slope decides once it is steeper than about 50 degrees, or the plane's read would stretch down the face (2.6
// times at 67 degrees).
float groundLying(GroundPixel px) { return smoothstep(.6, .8, min(px.face.z, groundFaceSlope + .15)); }

// A side projection's reads on a wall (uv in repeats, y up the face): where in the map they come from. The ground's
// variation field is constant down a vertical face, so the source moves with height too, or a tall face repeats its
// patches at every map period: two offsets of one repeat mixed, a fresh pair about every .7 repeats up (the old path's
// cliffTexel); a family whose walls read in cells (its row's floor.w: fungus) blends four offset cells instead
// (fungusTexel). Colour/height, normal/AO and the parallax's height take the same offsets. A quality switch
// (BoardSwitch.GROUND_WALL_READS, groundWallReadsRule: High's); without it a wall reads the map as it is, once.
#ifdef groundWallReadsRule
void groundWallPair(vec2 uv, float variation, out vec2 a, out vec2 b, out float t) {
    float choice = (variation + uv.y * .173) * 8.0, index = floor(choice);
    a = uv + materialOffset(vec2(index, 0.0));
    b = uv + materialOffset(vec2(index + 1.0, 0.0));
    t = smoothstep(.2, .8, fract(choice));
}

#ifdef groundWallCellsRule
// The four offset cells of a wall that reads in cells, and their weights. Its own rule kind (groundWallCellsRule): only
// the programs of a family that reads so, and the generic programs of a board that has one, carry it.
void groundWallCells(vec2 uv, out vec2 a, out vec2 b, out vec2 c, out vec2 d, out vec4 weights) {
    vec2 cell = floor(uv), blend = smoothstep(.15, .85, fract(uv)), keep = 1.0 - blend;
    a = uv + materialOffset(cell);
    b = uv + materialOffset(cell + vec2(1.0, 0.0));
    c = uv + materialOffset(cell + vec2(0.0, 1.0));
    d = uv + materialOffset(cell + vec2(1.0, 1.0));
    weights = vec4(keep.x * keep.y, blend.x * keep.y, keep.x * blend.y, blend.x * blend.y);
}
#endif
#endif

// One side projection's colour/height and normal/AO on a wall, and its normals' spread: the pair through mixedTexel
// (its read of weight zero skipped), the cells (a draw's whole family) four reads each.
void groundWallRead(vec2 uv, mat2 gradient, float layer, float variation, bool cells, bool normalMaps, out vec4 colour,
      out vec4 data, out float spread) {
    data = vec4(.5, .5, 1.0, 1.0);
    spread = 0.0;
#ifdef groundWallReadsRule
#ifdef groundWallCellsRule
    if (cells) {
        vec2 a, b, c, d;
        vec4 w;
        groundWallCells(uv, a, b, c, d, w);
        colour = w.x * materialTexel(GROUND_COLOUR, layer, a, gradient) + w.y * materialTexel(GROUND_COLOUR, layer, b, gradient)
              + w.z * materialTexel(GROUND_COLOUR, layer, c, gradient) + w.w * materialTexel(GROUND_COLOUR, layer, d, gradient);
        if (normalMaps) {
            vec4 da = materialTexel(GROUND_DATA, layer, a, gradient), db = materialTexel(GROUND_DATA, layer, b, gradient);
            vec4 dc = materialTexel(GROUND_DATA, layer, c, gradient), dd = materialTexel(GROUND_DATA, layer, d, gradient);
            data = w.x * da + w.y * db + w.z * dc + w.w * dd;
            spread = dot(w * w, vec4(groundSpread(da), groundSpread(db), groundSpread(dc), groundSpread(dd)));
        }
        return;
    }
#endif
    vec2 a, b;
    float t;
    groundWallPair(uv, variation, a, b, t);
    colour = mixedTexel(GROUND_COLOUR, layer, a, gradient, b, gradient, t);
    if (normalMaps) data = mixedTexel(GROUND_DATA, layer, a, gradient, b, gradient, t, spread);
#else
    colour = materialTexel(GROUND_COLOUR, layer, uv, gradient);
    if (normalMaps) {
        data = materialTexel(GROUND_DATA, layer, uv, gradient);
        spread = groundSpread(data);
    }
#endif
}

// The wall's height at its reads (groundWallRead), for the parallax.
float groundWallHeight(vec2 uv, mat2 gradient, float layer, float variation, bool cells) {
#ifdef groundWallReadsRule
#ifdef groundWallCellsRule
    if (cells) {
        vec2 a, b, c, d;
        vec4 w;
        groundWallCells(uv, a, b, c, d, w);
        return w.x * heightTexel(layer, a, gradient) + w.y * heightTexel(layer, b, gradient)
              + w.z * heightTexel(layer, c, gradient) + w.w * heightTexel(layer, d, gradient);
    }
#endif
    vec2 a, b;
    float t;
    groundWallPair(uv, variation, a, b, t);
    return mixedHeight(layer, a, gradient, b, gradient, t);
#else
    return heightTexel(layer, uv, gradient);
#endif
}

// One role's colour/height and normal/AO, cavity in data.a. Tops read the plane. Step faces read the side they face,
// both sides only where the face turns between them, and the plane too where they lie back; between a top and a face
// (role between 0 and 1) both. A top takes the old path's second read (topCoordinates): a second, enlarged and turned
// repeat mixed in by the world field variation where the first repeat's texels pass a pixel, or for a translated role
// two offsets of one repeat; under Cheaper shading the first repeat alone. A wall's reads move with height
// (groundWallRead).
void groundRead(GroundPixel px, float layer, float tile, float variation, bool translated, out vec4 colour,
      out vec3 normal, out float cavity, out float spread) {
    tile = max(tile, 1e-4);
    float lying = groundLying(px);
    float side = smoothstep(.3, .7, px.side);
    colour = vec4(0.0);
    vec3 n = vec3(0.0);
    vec4 data = vec4(0.0);
    cavity = 0.0;
    spread = 0.0;
    if (lying > 0.0) {
        vec2 a = px.top / tile, b = a;
        mat2 gradientA = px.topGradient / tile, gradientB = gradientA;
        float t = 0.0;
#ifndef mediumQualityFlag
        MaterialProjection p;
        p.top = px.top;
        p.topGradient = px.topGradient;
        p.topTexels = px.topTexels;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
#endif
        vec4 c = mixedTexel(GROUND_COLOUR, layer, a, gradientA, b, gradientB, t);
        vec4 d = vec4(.5, .5, 1.0, 1.0), e = d;
        if (px.normalMaps) {
            if (t < 1.0) d = textureGrad(GROUND_DATA, vec3(a, layer), gradientA[0], gradientA[1]);
            e = t > 0.0 ? textureGrad(GROUND_DATA, vec3(b, layer), gradientB[0], gradientB[1]) : d;
            if (t >= 1.0) d = e;
        }
        // Two offsets mix their texels; an enlarged repeat's relief turns back and flattens (planarNormal).
        vec4 relief = translated ? vec4(mix(d, e, t).rgb * 2.0 - 1.0, mix(d.a, e.a, t)) : planarNormal(d, e, t);
        colour += c * lying;
        n += upNormal(relief.rgb, px.face) * lying;
        cavity += relief.a * lying;
        // An enlarged repeat's slopes are 2.37 times shallower: its spread 2.37^2 times smaller (the old path's).
        spread += ((1.0 - t) * (1.0 - t) * groundSpread(d) + t * t * (translated ? 1.0 : .178) * groundSpread(e))
              * lying * lying;
    }
    if (lying < 1.0) {
        float weight = 1.0 - lying;
        if (side > 0.0) {
            vec4 c, d;
            float s;
            groundWallRead(px.x / tile, px.xGradient / tile, layer, variation, px.cellWalls, px.normalMaps, c, d, s);
            vec3 detail = d.rgb * 2.0 - 1.0;
            float w = weight * side;
            colour += c * w;
            n += normalize(vec3(detail.z * px.face.x, detail.x * px.facing.x + px.face.y, px.face.z - detail.y)) * w;
            cavity += d.a * w;
            spread += s * w * w;
        }
        if (side < 1.0) {
            vec4 c, d;
            float s;
            groundWallRead(px.y / tile + .37, px.yGradient / tile, layer, variation, px.cellWalls, px.normalMaps, c, d,
                  s);
            vec3 detail = d.rgb * 2.0 - 1.0;
            float w = weight * (1.0 - side);
            colour += c * w;
            n += normalize(vec3(-detail.x * px.facing.y + px.face.x, detail.z * px.face.y, px.face.z - detail.y)) * w;
            cavity += d.a * w;
            spread += s * w * w;
        }
    }
    normal = px.normalMaps && !px.covered ? normalize(n) : px.face;
    cavity = px.normalMaps && !px.covered ? mix(.55, 1.0, cavity) : 1.0;
}

// Godot's heightmap parallax on the role read first (the old path's relievedProjection): its reads move against the
// view, in each projection's frame, by its depth below the heightmap's white at its unmoved reads, one height read a
// projection it reads. A move under half a pixel costs no read (planeReach); the Terrain parallax switch off, or
// Cheaper shading on, compiles it out.
GroundPixel groundRelieved(GroundPixel px, float layer, float tile, float variation, bool translated) {
#if !defined(mediumQualityFlag) && defined(terrainParallaxFlag)
    tile = max(tile, 1e-4);
    float lying = groundLying(px);
    float side = smoothstep(.3, .7, px.side);
    // The shares groundRead gives the plane and the two sides.
    float wx = (1.0 - lying) * side, wy = (1.0 - lying) * (1.0 - side);
    vec2 top = vec2(0.0), x = vec2(0.0), y = vec2(0.0);
    if (lying > 0.0) top = planeReach(layer, tile, vec2(terrainView.x, -terrainView.y), px.topGradient);
    if (wx > 0.0) x = planeReach(layer, tile, vec2(terrainView.y * px.facing.x, -terrainView.z), px.xGradient);
    if (wy > 0.0) y = planeReach(layer, tile, vec2(-terrainView.x * px.facing.y, -terrainView.z), px.yGradient);
    if (dot(top, top) + dot(x, x) + dot(y, y) <= 0.0) return px;
    float height = 0.0;
    if (lying > 0.0) {
        // A top's height at its colour's coordinates: its second read's too (groundRead), as the old path's rockHeight.
        vec2 a, b;
        mat2 gradientB;
        float t;
        MaterialProjection p;
        p.top = px.top;
        p.topGradient = px.topGradient;
        p.topTexels = px.topTexels;
        topCoordinates(p, tile, variation, translated, a, b, gradientB, t);
        height += lying * mixedHeight(layer, a, px.topGradient / tile, b, gradientB, t);
    }
    if (wx > 0.0) height += wx * groundWallHeight(px.x / tile, px.xGradient / tile, layer, variation, px.cellWalls);
    if (wy > 0.0) height += wy * groundWallHeight(px.y / tile + .37, px.yGradient / tile, layer, variation, px.cellWalls);
    float sink = 1.0 - height;
    px.top -= top * sink;
    px.x -= x * sink;
    px.y -= y * sink;
#endif
    return px;
}

// A tone patch's share at the pixel: its field's smoothstep times its amount, or its rim or foot weight there.
float groundPatch(vec4 tint, vec4 field, vec4 edge, vec3 fields, float rim, float foot) {
    float spread = smoothstep(field.w, edge.x, dot(field.xyz, fields.xzy)) * tint.a;
    return max(spread, max(edge.y * rim * smoothstep(.3, .75, fields.y), edge.z * foot));
}

// Broad variation of the ground's tone, so a field reads neither flat nor tiled, and the family's own patches.
vec3 groundTone(GroundFamily f, vec3 colour, vec3 world, vec3 fields, float rim, float foot) {
    vec3 gain = vec3(mix(.94, 1.06, fields.x) * mix(.95, 1.05, fields.z) * mix(.96, 1.04, fields.y));
    if (f.toneEdgeA.w > 0.0) {
        gain *= 1.0 + f.toneEdgeA.w * sin(dot(world.xy, vec2(.8, .6)) / 19.0 + fields.x * 5.0 + fields.z * 3.0)
              * smoothstep(.2, .6, fields.z + .3 * fields.y);
    }
    gain *= mix(vec3(1.0), f.toneTintA.rgb, groundPatch(f.toneTintA, f.toneFieldA, f.toneEdgeA, fields, rim, foot));
    gain *= mix(vec3(1.0), f.toneTintB.rgb, groundPatch(f.toneTintB, f.toneFieldB, f.toneEdgeB, fields, rim, foot));
    return colour * authoredPigmentGain(gain);
}

#ifdef groundFinishRule
// A role's finish where its material declares one (GroundFamilies FINISH: compiled only into the programs of
// families that have such a material): read on the projection the role is mostly read on; a role whose material
// declares none costs its uniform test.
Finish groundFinish(GroundPixel px, float layer, float tile) {
    int material = int(layer + .5);
    if (u_finishSurface[material].a < 0.0 && u_finishEmission[material].a < 0.0) return meanFinish(material);
    tile = max(tile, 1e-4);
    if (groundLying(px) >= .5) return finishTexel(material, px.top / tile, px.topGradient / tile);
    return px.side > .5 ? finishTexel(material, px.x / tile, px.xGradient / tile)
          : finishTexel(material, px.y / tile + .37, px.yGradient / tile);
}
#endif

GroundSurface groundSurface(GroundPixel px, int family, vec3 fields) {
    // Within the table: a family program's palette is constant, and a volcanic one's crust and bank (12, 13) reach this
    // call's text though never the call (compactSlot), which a constant index past the table fails to compile.
    GroundFamily f = groundFamilies[min(family, GROUND_FAMILIES - 1)];
    px.cellWalls = f.floor.w > .5;
    float broad = fields.x, fine = fields.y, region = fields.z;
    // The cover holds by the slope the read sees (groundLying): a face near vertical carries none, whatever its
    // shading normal says at a rim or foot.
    float up = clamp(min(px.face.z, groundFaceSlope + .15), 0.0, 1.0);
#ifdef groundPocketsRule
    // Small pockets (~1.4 m), as today's cliff material reads them: they break a cover's edge into a fringe.
    float pockets = GROUND_NOISE((px.world.xy + px.world.z * vec2(.43, .27)) / 92.0 + .57).g, shift = pockets;
    float variation = broad * .4 + fine * .25 + pockets * .35;
#else
    // Without them their place takes the fine field, the turf's shoulder the broad one.
    float pockets = fine, shift = broad;
    float variation = broad * .4 + fine * .6;
#endif
    float sediment = px.sediment;
    if (px.shallowSediment >= 0.0) {
        if (f.shore.y > .5) sediment = px.shallowSediment;
        sediment = pow(sediment, f.shore.x);
    }
    float foot = exp(-max(px.aboveFoot, 0.0) / (1.5 + 6.0 * variation));
    float deposit = foot * smoothstep(.28, .64, variation) * smoothstep(.1, .6, up);
    float rock = px.rock;
    float exposure = clamp(rock + (variation - .5) * 1.2 * (4.0 * rock * (1.0 - rock)), 0.0, 1.0);
    float rim = 0.0, scour = 0.0, shoulder = 0.0;
    if (px.belowRim < 7.0) {
        rim = 1.0 - smoothstep(.1, 1.0 + 2.0 * broad, px.belowRim);
        scour = (1.0 - smoothstep(.1, 3.0 + 4.0 * broad, px.belowRim)) * exposure * smoothstep(.30, .65, broad * .6 + pockets * .4);
        shoulder = (1.0 - smoothstep(.1, 1.3 + 1.6 * broad, px.belowRim)) * smoothstep(.28, .65, pockets) * smoothstep(.55, .94, up);
    }
    float coverUp = up;
    if (up < .985) coverUp -= .2 * (1.0 - exposure) * (1.0 - smoothstep(.87, .985, up)) * smoothstep(.32, .68, fine * .65 + pockets * .35);
    float thin = smoothstep(.50, .72, region * .6 + broad * .3 + fine * .1) * smoothstep(.82, .97, up)
          * (1.0 - deposit) * (1.0 - .75 * foot);
    float cover = smoothstep(f.cover.x, f.cover.y, coverUp + (variation - .5) * f.cover.z + deposit * f.cover.w);
#ifdef groundTrimsRule
    cover *= 1.0 - max(f.trim.x * scour, max(f.trim.y * thin * mix(1.0, rim * exposure, f.trim.z), f.trim.w * shoulder));
#endif
    // Volcanic palettes keep today's crests, which carry no cornice (naturalMaterialFor): a wall's palette there can be
    // snow under another family's top.
#if defined(groundCorniceRule) && !defined(volcanicFlag)
    if (f.edges.y > 0.0 && px.role > 0.0) {
        // A crest's ragged cornice: the cover caps a bank's rim, the rock breaks out below it; rock cliffs keep their edge.
        float lip = mix(.5, 2.2, fine * .4 + pockets * .6);
        cover = max(cover, f.edges.y * px.role * (1.0 - smoothstep(lip * .4, lip, px.belowRim)) * (1.0 - exposure));
    }
#endif
    float sod = 0.0;
#ifdef groundTurfRule
    if (f.edges.x > 0.0) {
        // A two-level bank keeps a ragged turf lip and broken turf on its intermediate shoulder.
        float twoLevels = 1.0 - smoothstep(.05, .3, abs((px.aboveFoot + px.belowRim) / px.levelMetres - 2.0));
        if (twoLevels > 0.0) {
            float fray = GROUND_NOISE((px.world.xy + px.world.z * vec2(.29, .41)) / 28.0 + .217).r;
            float lipDepth = px.levelMetres * (mix(.2, .65, fine * .45 + pockets * .55) + .14 * (fray - .5));
            float lip = 1.0 - smoothstep(.05, lipDepth, px.belowRim);
            float middle = abs(px.aboveFoot / px.levelMetres - 1.0 - (shift - .5) * .18);
            float width = mix(.1, .24, fine) * mix(.55, 1.45, fray);
            float turf = (1.0 - smoothstep(width * .25, width, middle)) * smoothstep(.35, .73, up)
                  * smoothstep(.22, .55, pockets * .7 + fine * .3);
            cover = max(cover, f.edges.x * max(lip, turf) * twoLevels * (1.0 - exposure));
            float edge = .52 * lipDepth, depth = px.levelMetres * mix(.08, .16, fray);
            sod = (1.0 - smoothstep(edge + .4 * depth, edge + depth, px.belowRim)) * twoLevels * (1.0 - exposure)
                  * (1.0 - smoothstep(.85, .97, up));
        }
    }
#endif
    deposit *= mix(f.deposit.y, 1.0, rock);
    // Organic wear opens level cover onto its soil.
    float aged = smoothstep(.45, .68, broad * .5 + region * .35 + fine * .15) * smoothstep(.88, .98, up) * (1.0 - sediment);
#ifdef groundWearRule
    float worn = cover * aged * f.deposit.w;
    cover -= worn;
#else
    float worn = 0.0;
#endif
#ifdef groundMantleRule
    float bare = 1.0 - cover;
    float mantle = bare * (f.mantle.x * (1.0 - exposure) * (1.0 - f.mantle.y * smoothstep(.90, .99, up))
          + f.mantle.z * rim * smoothstep(.25, .7, variation));
    // A cap crowns a crest: on a wall it keeps to the rim's first metres; a ledge lower down shows its rock.
    float capped = px.role > 0.0 ? 1.0 - smoothstep(.15, 2.2, px.belowRim) : 1.0;
    mantle = max(mantle, bare * f.mantle.w * capped * max(smoothstep(.82, .97, up), rim * smoothstep(.25, .90, up)));
    mantle = max(mantle, worn);
#else
    float mantle = worn;
#endif
    deposit *= f.deposit.x;
#ifdef groundColoniesRule
    if (f.floor.z > 0.0) {
        // Crust colonies: lowland skin below level zero, crust colonies above it and along raised rims.
        float rise = smoothstep(.25, .9, (px.aboveFoot + px.belowRim) / px.levelMetres);
        float raised = smoothstep(.15, 1.3, px.world.z / px.levelMetres);
        float lip = 1.0 - smoothstep(.1, 1.4 + 1.6 * pockets, px.belowRim);
        float growth = max(raised, lip * rise);
        float colonies = smoothstep(.28, .69, region * .5 + broad * .3 + pockets * .2);
        deposit = max(deposit, smoothstep(.48, .88, up) * mix(.10 * colonies, .28 + .70 * colonies, growth)
              * (1.0 - aged * .55));
        cover = smoothstep(.76, .97, up) * (1.0 - .90 * thin * (1.0 - growth));
        mantle = (1.0 - cover) * (1.0 - exposure) * (1.0 - smoothstep(.90, .99, up));
    }
#endif
    vec4 layers = f.layers, tiles = f.tiles, roughness = f.roughness;
    // A family's floor takes its cover's place from level -1 down, along contours its fields break.
    float floorShare = 0.0;
#ifdef groundFloorRule
    if (f.floor.x >= 0.0) {
        float contour = .36 * (broad - .5) + .2 * (fine - .5);
        floorShare = smoothstep(.3, .72, contour - px.world.z / px.levelMetres) * smoothstep(.55, .85, up);
        if (floorShare > 0.0) cover = mix(cover, 1.0, .5 * floorShare * smoothstep(.82, .97, up));
    }
#endif
    // A bare ledge of whole rock (a fall's dry rim, which cliffs' FieldBoard marks) shows the cliff's rock seen from
    // above, not the cover its slope would give.
    float bareRock = px.role * groundBareLedge * smoothstep(.8, .95, rock);
    // Level bedrock of the shared cliff shows the rock tops' map: its joints stand upright.
    if ((px.role < .5 || bareRock > .5) && abs(layers.w - groundFlatRock.x) < .5) {
        layers.w = groundFlatRock.y;
        tiles.w = groundFlatRock.z;
    }
    vec4 roles = vec4(cover, clamp(mantle, 0.0, 1.0 - cover), deposit, 0.0);
    roles.xy *= 1.0 - deposit;
    roles.w = max(0.0, 1.0 - roles.x - roles.y - roles.z);
    roles = mix(roles, vec4(0.0, 0.0, 0.0, 1.0), bareRock);
    // Concrete carried onto another surface is loose aggregate, never a second paved slab (as cliffMaterial).
    if (family == 4) roles = vec4(0.0, 0.0, 1.0, 0.0);
    float bedRock = rock * (1.0 - smoothstep(.2, .8, up)) * smoothstep(.05, 2.2, px.aboveFoot);
    float bankBlend = 0.0;
#ifdef groundBanksRule
    if (px.riverBank && f.edges.w > .5) {
        // An ordinary river's bank: native cover feathers into soil, then gravel, before the water.
        float bankRock = bedRock > 0.0 ? roles.w : 0.0;
        roles = mix(roles, vec4(1.0 - bankRock, 0.0, 0.0, bankRock), smoothstep(0.0, .03, sediment));
        bankBlend = clamp((sediment - .03) / .97, 0.0, 1.0);
        if (bankBlend > 0.0) {
            layers.y = groundRiverBank.x; tiles.y = groundRiverBank.y;
            layers.z = groundRiverBank.z; tiles.z = groundRiverBank.w;
            roles = mix(roles, vec4(0.0, 1.0 - bedRock, 0.0, bedRock), clamp(bankBlend / f.edges.z, 0.0, 1.0));
            roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), clamp((bankBlend - .9) / .1, 0.0, 1.0));
        }
    } else
#endif
    {
        roles = mix(roles, vec4(0.0, 0.0, 1.0 - bedRock, bedRock), sediment);
    }
    // The two strongest roles; a dusty level top keeps its cover as the second.
    float first = max(max(roles.x, roles.y), max(roles.z, roles.w));
    vec4 isFirst = step(vec4(first), roles);
    isFirst *= 1.0 - step(.5, vec4(0.0, isFirst.x, isFirst.x + isFirst.y, isFirst.x + isFirst.y + isFirst.z));
#ifdef groundDustRule
    float dust = f.deposit.z * smoothstep(.88, .98, up) * (1.0 - sediment);
    if (bankBlend > 0.0) dust *= 1.0 - clamp(bankBlend / f.edges.z, 0.0, 1.0);
#else
    float dust = 0.0;
#endif
    vec4 rest = roles * (1.0 - isFirst);
    if (dust > 0.0) rest.x += 2.0 * (1.0 - isFirst.x);
    float pick = max(max(rest.x, rest.y), max(rest.z, rest.w));
    vec4 isSecond = step(vec4(pick), rest) * (1.0 - isFirst);
    isSecond *= 1.0 - step(.5, vec4(0.0, isSecond.x, isSecond.x + isSecond.y, isSecond.x + isSecond.y + isSecond.z));
    float second = dot(isSecond, roles);
    // The floor stands in for the cover role where it lies whole; inside its fringe the pair is ground and floor.
#if defined(groundFloorRule) && defined(groundSecondRule)
    bool floorPair = floorShare > 0.0 && floorShare < 1.0 && isFirst.x > .5;
#else
    bool floorPair = false;
#endif
#ifdef groundFloorRule
    if (floorShare >= 1.0) { layers.x = f.floor.x; tiles.x = f.floor.y; }
#endif
    float layerA = dot(isFirst, layers), tileA = dot(isFirst, tiles);
    // The first role's second read (groundRead): the field it varies by and whether it is translated, as the old path's
    // cliff material has them; the table holds which roles translate and whether the mantle varies broadly.
    int translating = int(f.abrasionLow.a + .5);
    vec4 translatedRoles = vec4(translating & 1, (translating >> 1) & 1, (translating >> 2) & 1,
          (translating >> 3) & 1);
    float variationA = dot(isFirst, vec4(broad, mix(fine, broad, f.abrasionHigh.a), fine, broad));
    bool translatedA = dot(isFirst, translatedRoles) > .5;
    vec4 a;
    vec3 na;
    float cavityA, spreadA;
    groundRead(groundRelieved(px, layerA, tileA, variationA, translatedA), layerA, tileA, variationA, translatedA, a,
          na, cavityA, spreadA);
    vec4 b = a;
    vec3 nb = na;
    float cavityB = cavityA, spreadB = spreadA, share = 0.0;
    float roughA = dot(isFirst, roughness), roughB = roughA;
    float wa = first + a.a * .38 * 4.0 * first * (1.0 - first);
    float blendWidth = .13 + min(.2, px.pixelMetres * .16);
#ifdef groundSecondRule
    if (floorPair) {
#ifdef groundFloorRule
        groundRead(groundRelieved(px, f.floor.x, f.floor.y, variationA, translatedA), f.floor.x, f.floor.y,
              variationA, translatedA, b, nb, cavityB, spreadB);
        float spreading = .25 * 4.0 * floorShare * (1.0 - floorShare);
        float ga = 1.0 - floorShare + a.a * spreading, gb = floorShare + b.a * spreading;
        float width = 1.6 * blendWidth;
        float ka = max(ga - max(ga, gb) + width, 0.0), kb = max(gb - max(ga, gb) + width, 0.0);
        share = kb / max(ka + kb, .00001);
#endif
    } else if ((second > .02 || dust > 0.0 || bankBlend > 0.0)
          && (bankBlend > 0.0 || dust > 0.0 || second + .38 * 4.0 * second * (1.0 - second) > wa - blendWidth - 1e-4)) {
        // The second role shows where its weight at full relief comes within the blend's width of the first's.
        groundRead(px, dot(isSecond, layers), dot(isSecond, tiles), 0.0, false, b, nb, cavityB, spreadB);
        roughB = dot(isSecond, roughness);
        float wb = second + b.a * .38 * 4.0 * second * (1.0 - second);
        float ka = max(wa - max(wa, wb) + blendWidth, 0.0), kb = max(wb - max(wa, wb) + blendWidth, 0.0);
        share = kb / max(ka + kb, .00001);
        if (bankBlend > 0.0) share = mix(share, second / max(first + second, .00001), smoothstep(0.0, .03, bankBlend));
        if (dust > 0.0) share = isFirst.x > .5 ? share * (1.0 - dust) : share + dust * (1.0 - share);
    }
#endif
    vec4 roleShare = isFirst * (1.0 - share) + (floorPair ? isFirst : isSecond) * share;
    GroundSurface result;
    result.albedo = vec3(0.0);
#ifdef groundFinishRule
    result.surfaceFinish = vec2(0.0);
    result.emission = vec3(0.0);
#endif
    if (!px.covered) {
        vec3 colour = mix(a.rgb, b.rgb, share);
        if (px.role > 0.0) {
            // Strata: harder beds weather paler and slightly cooler than the softer beds between them.
            float rockShare = (roleShare.w + roleShare.y * f.mantle.w) * px.role;
            colour *= mix(vec3(1.0), mix(vec3(.93, .92, .91), vec3(1.04, 1.04, 1.05), px.hardness), rockShare);
        }
#ifdef groundAbrasionRule
        if (f.shore.w > 0.0) {
            // A rim's abraded rock pales at its crest and keeps its warmer face below.
            float abrasion = (1.0 - smoothstep(.15, 2.2, px.belowRim)) * smoothstep(.45, .95, up);
            colour *= mix(vec3(1.0), authoredPigmentGain(mix(f.abrasionLow.rgb, f.abrasionHigh.rgb, abrasion)),
                  roleShare.w * f.shore.w);
        }
#endif
#ifdef groundWeatheringRule
        if (f.shore.w <= 0.0 && px.role > 0.0 && f.shore.z > 0.0) {
            // A wall weathers by its height: its rim bleached, streaks down from it, grime toward its foot.
            float faceHeight = max(px.aboveFoot + px.belowRim, 1.0);
            float low = (1.0 - smoothstep(0.0, .45, max(px.aboveFoot, 0.0) / faceHeight)) * (.6 + .4 * variation);
            float bleach = (1.0 - smoothstep(.3, 3.0 + 3.0 * broad, px.belowRim)) * (1.0 - .5 * up);
            vec2 stretch = vec2(1.0 / 40.0, 1.0 / 400.0);
            vec2 along = px.side > .5 ? px.x : px.y;
            float streak = GROUND_NOISE(along * stretch + .41).r;
            float streaks = smoothstep(.5, .8, streak) * (1.0 - smoothstep(0.0, .6 * faceHeight + 4.0, max(px.belowRim, 0.0)))
                  * (1.0 - smoothstep(.3, .7, up));
            vec3 weathering = mix(vec3(1.0), vec3(1.10, 1.10, 1.09), bleach) * mix(vec3(1.0), vec3(.84, .84, .86), streaks)
                  * mix(vec3(1.0), vec3(.84, .85, .88), low);
            colour *= mix(vec3(1.0), authoredPigmentGain(weathering), roleShare.w * f.shore.z * px.role);
        }
#endif
#ifdef groundToneRule
        if (roleShare.x > 0.0) colour = mix(colour, groundTone(f, colour, px.world, fields, rim, foot), roleShare.x);
#endif
#ifdef groundTurfRule
        colour *= mix(vec3(1.0), f.rootMat.rgb, sod * (1.0 - roleShare.x));
#endif
#ifdef groundFinishRule
        // The roles' finish mixed as their colours are. Ground glow lies in patches of the region field: about a
        // quarter of the ground lit, in blobs 3 to 6 hexes across (design.md 8.26).
        Finish finished = groundFinish(px, layerA, tileA);
        if (share > 0.0) {
            finished = mixFinish(finished, groundFinish(px, floorPair ? f.floor.x : dot(isSecond, layers),
                  floorPair ? f.floor.y : dot(isSecond, tiles)), share);
        }
        result.surfaceFinish = vec2(finished.metallic, finished.scatter);
        result.emission = finished.emission * smoothstep(.5, .65, region);
#endif
        result.albedo = colour;
    }
    vec3 normalA = na * (1.0 - spreadA), normalB = nb * (1.0 - spreadB);
    result.normal = normalize(mix(normalA, normalB, share));
    result.cavity = mix(cavityA, cavityB, share);
    result.relief = px.normalMaps ? mix(length(normalA), length(normalB), share) : 1.0;
    result.height = mix(a.a, b.a, share);
    result.roughness = mix(roughA, roughB, share);
    result.roles = roleShare;
    return result;
}

// The compact material's inputs at this pixel (ground-material.glsl): the single-family draws' and the cover hexes'.
GroundPixel groundPixel(MaterialProjection projection, vec3 world, vec3 face, float foot, float rim, float rock,
      float bed, float sediment, bool rocky) {
    GroundPixel px;
    px.world = world; px.face = face; px.top = projection.top; px.x = projection.x;
    px.y = projection.y; px.topGradient = projection.topGradient;
    px.xGradient = projection.xGradient; px.yGradient = projection.yGradient;
    px.topTexels = projection.topTexels;
    px.side = projection.side; px.facing = projection.facing; px.aboveFoot = foot; px.belowRim = rim;
    px.rock = rock; px.hardness = bed; px.sediment = sediment; px.shallowSediment = shallowSediment;
    px.riverBank = riverBank; px.role = rocky ? 1.0 : 0.0; px.covered = terrainCovered;
    px.levelMetres = u_levelHeight / u_metre; px.pixelMetres = pixelMetres; px.normalMaps = u_normalMaps > .5;
    px.cellWalls = false;
    return px;
}

TerrainMaterial groundMaterial(GroundSurface surface) {
#ifdef groundFinishRule
    Finish finish = Finish(surface.roughness, surface.surfaceFinish.x, surface.surfaceFinish.y, surface.emission);
#else
    Finish finish = Finish(surface.roughness, 0.0, 0.0, vec3(0.0));
#endif
    return TerrainMaterial(surface.albedo, surface.normal, surface.cavity, surface.height, finish, 0.0,
          surface.relief);
}

#ifdef terrainBlendFlag
#ifdef volcanicFlag
// compactBlend's magma surface, its bank heat and a one-ordinary-family program's ground, read once a pixel.
Volcanic compactMagma;
float compactBankHeat;
TerrainMaterial compactOrdinary;
#endif

// One palette slot of compactBlend: a paved base is the slab drawn before the blend (incoming); paving
// elsewhere is loose aggregate (groundSurface); a volcanic program's crust and bank are its magma surface.
TerrainMaterial compactSlot(GroundPixel px, int slot, vec3 fields, TerrainMaterial incoming) {
    float family = coverFamilies[slot];
    if (slot == 0 && abs(family - 4.0) < .5) return incoming;
#ifdef volcanicFlag
    if (family > VOLCANIC_CRUST_FAMILY - .5) return volcanicMaterial(compactMagma, family, compactBankHeat);
#endif
#ifdef VOLCANIC_ORDINARY_FAMILY
    return compactOrdinary;
#else
    return groundMaterial(groundSurface(px, int(family + .5), fields));
#endif
}

// A cover hex's ground (design.md 8.17): each palette slot it carries (over 2 %) through compactSlot, weighed by their
// heights as today's blend weighs them (materialWeights), the fields already read breaking the contacts where today's
// read a patch noise a family. Loose sand leaves windows onto the tile's own ground, as today's. raw: the slots'
// weights; weights: their final shares, for the live layers.
TerrainMaterial compactBlend(GroundPixel px, vec4 raw, vec3 fields, TerrainMaterial incoming, out vec4 weights) {
#ifdef volcanicFlag
    // Near molten ground the bank slots take the weight; the shore is sampled before the slots' branches.
    MagmaProjection magmaCoordinates = magmaProjectionFor(px.world);
    vec4 bank = vec4(greaterThan(coverFamilies, vec4(VOLCANIC_BANK_FAMILY - .5)));
    float shore = -.4;
    if (u_magmaFieldMap.x > 0.0 && dot(bank, vec4(1.0)) > 0.0) {
        shore = magmaShore(magmaField(v_cloudPosition.xy));
        raw = mix(raw, bank, 1.0 - smoothstep(0.0, MAGMA_MARGIN, -shore));
    }
    compactBankHeat = magmaBankHeat(shore);
#else
    raw *= step(vec4(.02), raw);
#endif
    vec4 sandy = vec4(lessThan(abs(coverFamilies - 2.0), vec4(.5)));
    vec4 substrate = vec4(lessThan(abs(coverFamilies - sculptFamily), vec4(.5)));
    float substrates = dot(substrate, vec4(1.0));
    float exposed = abs(sculptFamily - 2.0) > .5 && dot(sandy, raw) > 0.0 && substrates > 0.0
          ? sandExposure(px.world.xy) * (1.0 - px.sediment) : 0.0;
    vec4 needed = max(step(vec4(.0001), raw), exposed > 0.0 ? substrate : vec4(0.0));
    TerrainMaterial none = TerrainMaterial(vec3(0.0), px.face, 1.0, .5, Finish(1.0, 0.0, 0.0, vec3(0.0)), 0.0, 1.0);
#ifdef volcanicFlag
    compactMagma = Volcanic(vec3(0.0), px.face, vec4(0.0), vec2(0.0), vec3(0.0));
    if (dot(needed, vec4(greaterThan(coverFamilies, vec4(VOLCANIC_CRUST_FAMILY - .5)))) > 0.0) {
        compactMagma = magmaSurface(magmaCoordinates, px.face, -viewDirection(), vec3(0.0), 1.0, vec4(0.0));
    }
    compactOrdinary = none;
#if defined(VOLCANIC_ORDINARY_FAMILY) && VOLCANIC_ORDINARY_FAMILY >= 0
    if (dot(needed, vec4(equal(coverFamilies, vec4(float(VOLCANIC_ORDINARY_FAMILY))))) > 0.0) {
        compactOrdinary = groundMaterial(groundSurface(px, VOLCANIC_ORDINARY_FAMILY, fields));
    }
#endif
#endif
    // A family repeated in the palette is evaluated once, unless it is paving (the base slab, aggregate elsewhere).
    bool reuse = needed.x > 0.0 && abs(coverFamilies.x - 4.0) > .5;
#ifdef COVER_FAMILIES
    // A family program's palette is constant: a call a slot, each folded to its family.
    TerrainMaterial a = needed.x > 0.0 ? compactSlot(px, 0, fields, incoming) : none;
    TerrainMaterial b = needed.y <= 0.0 ? none : reuse && abs(coverFamilies.y - coverFamilies.x) < .5 ? a
          : compactSlot(px, 1, fields, incoming);
    TerrainMaterial c = needed.z <= 0.0 ? none : reuse && abs(coverFamilies.z - coverFamilies.x) < .5 ? a
          : compactSlot(px, 2, fields, incoming);
    TerrainMaterial d = needed.w <= 0.0 ? none : reuse && abs(coverFamilies.w - coverFamilies.x) < .5 ? a
          : compactSlot(px, 3, fields, incoming);
#else
    // A generic program's palette is a uniform: its slots through one call, in a loop bounded by the last slot asked
    // (no compiler folds it, so none unrolls it), or the program carries the whole material once a slot.
    TerrainMaterial a = none, b = none, c = none, d = none;
    int slots = needed.w > 0.0 ? 4 : needed.z > 0.0 ? 3 : needed.y > 0.0 ? 2 : needed.x > 0.0 ? 1 : 0;
    for (int slot = 0; slot < slots; slot++) {
        if (needed[slot] <= 0.0) continue;
        TerrainMaterial m = slot > 0 && reuse && abs(coverFamilies[slot] - coverFamilies.x) < .5 ? a
              : compactSlot(px, slot, fields, incoming);
        if (slot == 0) a = m; else if (slot == 1) b = m; else if (slot == 2) c = m; else d = m;
    }
#endif
    vec4 heights = vec4(a.height, b.height, c.height, d.height);
    vec4 patches = vec4(fields.y, 1.0 - fields.y, fields.x, 1.0 - fields.x);
    weights = materialWeights(raw, heights * .45 + patches * mix(1.5, 2.4, 1.0 - smoothstep(.35, .85, px.face.z)));
    vec4 proportions = raw / max(dot(raw, vec4(1.0)), .00001);
    weights = mix(weights, proportions, coverGradient(proportions));
    if (exposed > 0.0) {
        float amount = dot(weights, sandy) * exposed;
        weights = weights * (1.0 - sandy * exposed) + substrate * (amount / substrates);
    }
    return TerrainMaterial(a.color * weights.x + b.color * weights.y + c.color * weights.z + d.color * weights.w,
          normalize(a.normal * weights.x + b.normal * weights.y + c.normal * weights.z + d.normal * weights.w
                + px.face * 1e-4),
          dot(vec4(a.cavity, b.cavity, c.cavity, d.cavity), weights), dot(heights, weights),
          weighFinish(a.finish, b.finish, c.finish, d.finish, weights),
          dot(vec4(a.volcanic, b.volcanic, c.volcanic, d.volcanic), weights),
          dot(vec4(a.relief, b.relief, c.relief, d.relief), weights));
}
#endif

// ---- The compact ground's three entries (terrain-sculpt.frag's main calls them).

vec3 groundPathFields(vec3 world) { return compactFields(world); }

#ifdef terrainBlendFlag
// A cover hex's ground with its live layers' shares (design.md 8.17). walls: a blended wall, steep bank or bedrock,
// whose cover is its foot's share by height (footCover). One call site for both: a program carries the material once.
void groundPathCovers(MaterialProjection p, vec3 world, vec3 face, float foot, float rim, float rock, float hardness,
      float sediment, vec3 fields, bool sides, bool walls,
      inout vec3 color, inout vec3 normal, inout float cavity, inout float relief, inout float height,
      inout float grass, inout float sand, inout vec3 bounce, inout float response, inout float rainCover,
      inout Finish finish, out float volcanic, out vec4 weights) {
    vec4 raw = max(v_coverWeights, vec4(0.0));
    if (walls) raw = footCover(raw, world, foot);
    TerrainMaterial blended = compactBlend(groundPixel(p, world, face, foot, rim, rock, hardness, sediment, sides),
          raw, fields, TerrainMaterial(color, normal, cavity, height, finish, 0.0, relief), weights);
    color = blended.color; normal = blended.normal; cavity = blended.cavity; relief = blended.relief;
    height = blended.height; finish = blended.finish; volcanic = blended.volcanic;
    coverEffects(weights, sediment, grass, sand, bounce, response, rainCover);
}
#elif !defined(volcanicFlag)
// A single family's ground: its rules from its table row.
TerrainMaterial groundPathMaterial(MaterialProjection p, vec3 world, vec3 face, float foot, float rim, float rock,
      float bed, float sediment, vec3 fields, bool rocky) {
    return groundMaterial(groundSurface(groundPixel(p, world, face, foot, rim, rock, bed, sediment, rocky),
          int(sculptFamily + .5), fields));
}
#endif
