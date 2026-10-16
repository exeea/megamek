#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Sculpted tops, cliffs and the rock kit. Geometry supplies the landforms; this shader layers each surface family's
// four materials (ground, debris, wall, mantle), mapped in world space, and lights them with the board's one light
// model (shadeSurface, surface-shading.glsl): linear albedo times linear light. Scene radiance stays linear
// through blending and atmosphere; the final composite tones and encodes the whole frame once.
in vec2 v_diffuseUV;   // ground: rim / foot distance (drowned cliff: height above bed / foot distance);
                            // cliff: height above foot / depth below rim; rock: above root
                            // / below top. All in metres. Plants: authored red / green.
in vec3 v_normal;
in vec4 v_color;       // r: occlusion. g: game level (+64)/255, or for cliffs how much rock the face is (0
                            // bank, 1 cliff). b: kind (0 ground, .3 plant, .45 panel, .5 cliff, .6 abyss wall, .75 tree pit, 1 rock). a: bed
                            // hardness (cliff), authored blue (plant), variation (rock, a pit's earth below .5; its kerb is 1),
                            // nearest step height (.3 + .1 per level, dry ground), or below .25 on a water hex's banks
                            // and bed its water's palette and that height packed (GpuTerrain.shoreTint).
                            // Panels use alpha for a fixed texture azimuth, independent of the lighting normal.
// Aligned colour/height and normal/AO arrays; semantic IDs are mapped by the sampling helpers.
#define TERRAIN_LAYERS_DECLARED
uniform sampler2DArray u_terrainLayers;
uniform sampler2DArray u_terrainData;
uniform vec4 u_sculptLayers; // this family's colour/height layers: ground, debris, wall, mantle; normal/AO one up
uniform vec4 u_sculptTiles; // metres per repeat: ground, debris, wall, mantle
uniform float u_metre;      // world units per metre
uniform float u_normalMaps;
uniform float u_groundResponse;
uniform float u_sculptFamily; // BoardScene.Surface: GRASS 0, DIRT 1, SAND 2, ROCK 3, CONCRETE 4, SNOW 5, LUNAR 6
// A draw's families are compile-time constants in its family program (GpuFamilyPrograms), so the compiler
// keeps only their paths; the generic program of the same flags reads the same values from the uniforms.
#ifdef SCULPT_FAMILY
#define sculptFamily SCULPT_FAMILY
#else
#define sculptFamily u_sculptFamily
#endif
uniform float u_levelHeight;
uniform float u_clay;
uniform vec3 u_wind;        // direction in xy, strength in z
uniform float u_waterEffects;
uniform float u_waterLine;  // world units the water surface lies below its hex's level
uniform float u_gravity;    // scenario acceleration; without it there is no water (GpuTerrain.waterVisible)
#ifdef terrainBlendFlag
in vec4 v_coverWeights;
in float v_coverInterpolation;
uniform vec4 u_coverFamilies;
#ifdef COVER_FAMILIES
const vec4 coverFamilyConstants = COVER_FAMILIES;
#define coverFamilies coverFamilyConstants
#else
#define coverFamilies u_coverFamilies
#endif
uniform vec4 u_coverResponses;
uniform vec4 u_coverTiles0, u_coverTiles1, u_coverTiles2, u_coverTiles3;
uniform vec4 u_coverLayers0, u_coverLayers1, u_coverLayers2, u_coverLayers3;
#endif

// terrain-ground-color-functions

// terrain-projection-functions

// ---- Surface families --------------------------------------------------------------------------------------------

bool family(float f) { return abs(sculptFamily - f) < .5; }

// Grey lunar/volcanic sand reuses the shared loose-sand relief. Derive the colour from the supporting covers,
// so a theme boundary interpolates in world space instead of changing abruptly at a hex edge.
float greySand() {
#ifdef terrainBlendFlag
    vec4 weights = max(v_coverWeights, vec4(0.0));
    vec4 grey = (1.0 - step(vec4(.5), abs(coverFamilies - LUNAR_FAMILY)))
          + (1.0 - step(vec4(.5), abs(coverFamilies - VOLCANO_FAMILY)));
    vec4 substrate = step(vec4(.5), abs(coverFamilies - 2.0));
    return dot(weights, grey) / max(dot(weights, substrate), .0001);
#else
    return family(LUNAR_FAMILY) || family(VOLCANO_FAMILY) ? 1.0 : 0.0;
#endif
}

// Meadows and snowfields lie on a mantle (earth, snow) that buries low steps and caps high rock cliffs.
bool mantled() { return family(0.0) || family(5.0) || family(TROPICAL_FAMILY); }

// 0 for a step of up to two levels (a bank of the mantle), 1 from three levels (a rock cliff).
float rockiness(float levels) { return smoothstep(2.15, 2.9, levels); }

// A meadow worn through to its soil: the bare earth shows between the tussocks first.
vec3 worn(vec3 turf, float height, float amount) {
    vec3 soil = toLinear(vec3(.31, .25, .18)) * (.75 + .5 * height);
    return mix(turf, soil, clamp(amount * 1.6 - height * .9, 0.0, 1.0));
}

// How strongly debris shows on open ground: at rims, at cliff feet, on slopes and in scattered patches.
vec4 debrisWeights() {
    if (family(0.0)) return vec4(.55, 1.0, .45, 0.0);  // grass: rocky edges and scree, turf elsewhere
    if (family(TROPICAL_FAMILY)) return vec4(.35, .7, .4, .05);
    if (family(1.0)) return vec4(.6, .9, .6, .45);     // dirt: gravel
    if (family(2.0)) return vec4(.85, .9, .5, .3);     // sand: desert pavement
    if (family(DESERT_FAMILY) || family(MARS_FAMILY)) return vec4(.85, .9, .5, .3);
    if (family(3.0) || family(LUNAR_FAMILY) || family(VOLCANO_FAMILY)) return vec4(.5, .9, .6, .55);
    if (family(FUNGUS_FAMILY)) return vec4(.8, .4, .6, .2); // cyan crust in sheltered rock pockets
    if (family(4.0)) return vec4(0.0);                  // concrete: clean slab
    return vec4(.75, .9, .7, .12);                     // snow: wind-scoured rock
}

// Sunlit ground around a family reflects its own colour into shaded walls and undersides.
vec3 groundBounceFor(float f) {
    if (abs(f) < .5) return vec3(.15, .145, .06);
    if (abs(f - 1.0) < .5) return vec3(.22, .15, .10);
    if (abs(f - 2.0) < .5) return mix(vec3(.42, .28, .16), vec3(.23, .24, .26), greySand());
    if (abs(f - DESERT_FAMILY) < .5) return vec3(.35, .23, .13);
    if (abs(f - MARS_FAMILY) < .5) return vec3(.34, .14, .07);
    if (abs(f - VOLCANO_FAMILY) < .5) return vec3(.14, .16, .19);
    if (abs(f - TROPICAL_FAMILY) < .5) return vec3(.13, .15, .065);
    if (abs(f - 3.0) < .5 || abs(f - LUNAR_FAMILY) < .5) return vec3(.22, .21, .19);
    if (abs(f - FUNGUS_FAMILY) < .5) return vec3(.23, .20, .28);
    if (abs(f - 4.0) < .5) return vec3(.30, .29, .27);
    if (f > VOLCANIC_CRUST_FAMILY - .5) return vec3(.04, .025, .018);
    return vec3(.75, .78, .82);
}

vec3 groundBounce() { return groundBounceFor(sculptFamily); }

vec3 groundTone(vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    return groundToneFor(sculptFamily, albedo, world, broad, fine, region, rim, foot);
}

// sculpt-material-functions
void main() {
    vec3 face = normalize(v_normal);
    float occlusion = v_color.r;
    float level = floor(v_color.g * 255.0 + .5) - 64.0;
    float kind = v_color.b;
    bool ground = kind < .125, plant = kind >= .125 && kind < .375, cliff = kind >= .375 && kind < .625;
    bool leaf = plant && kind > .325; // Explicit authored tissue; legacy shrubs and wood remain opaque.
    bool panel = cliff && kind < .475;
    float projectionAngle = (v_color.a - .5) * 6.2831853;
    vec3 projection = panel ? vec3(cos(projectionAngle), sin(projectionAngle), 0.0) : face;
    terrainWallProjection = panel ? projection : vec3(0.0);
    float hardness = panel ? .5 : v_color.a;
    bool abyss = cliff && kind > .55;
    bool pit = kind >= .625 && kind < .875;
    bool shore = ground && v_color.a < .25;
    // Rock-kind bytes 224..254 carry the fractional water level; 255 remains ordinary dry rock.
    bool wetRock = kind >= .875 && kind < .999;
    bool waterCovered = shore || wetRock;
    // Without gravity nothing lies loose: the dry rock kit stands as bedrock outcrops (BoardScene.Tile.lunar), exposed
    // rock of the ground it breaks through, in that ground's natural material like a rock cliff. Its bedding planes
    // lie as open to the sky as the plain they continue; only its scarps keep the kit's occlusion toward the root.
    bool bedrock = kind >= .999 && u_gravity <= 0.0;
    if (bedrock) occlusion = mix(occlusion, 1.0, smoothstep(.55, .85, face.z));
    // Without gravity the water is gone: its bed keeps its stones, sediment and wet look, but neither the water's
    // colour nor its light, and draws its own grid.
    bool watered = waterCovered && u_gravity > 0.0;
    // Wet ground uses the spare ground-kind range for the fractional surface level of a descending stream.
    if (shore) level = v_color.g * 255.0 - 64.0 + kind * 8.0;
    if (wetRock) level = v_color.g * 255.0 - 64.0 + (kind * 255.0 - 224.0) / 30.0;
    // A water hex's ground packs its water's palette with the nearest step's height, which dry ground carries alone.
    float tintByte = v_color.a * 255.0;
    float palette = floor(tintByte / 16.0 + .03);
    float steps = shore ? (tintByte - 16.0 * palette) / 2.0 : (v_color.a - .3) / .1;
    vec3 world = v_cloudPosition / u_metre;
    vec3 worldDx = dFdx(world), worldDy = dFdy(world);
#ifdef lightingFlag
    vec3 geometric = visibleFace(worldDx, worldDy, -viewDirection());
#endif
    // surface-frame-ready
    terrainMaterialLod(world, face);
    // The area a pixel covers, as a length: a tilted view's foreshortening alone does not remove the detail.
    float footprint = sqrt(length(worldDx.xy) * length(worldDy.xy));
    farDetail = .8 * smoothstep(.18, .6, footprint);
    pixelMetres = max(max(length(worldDx), length(worldDy)), 1e-4);
    // Depth below this water hex's level, in metres, and height above its water, which lies u_waterLine lower.
    float depth = waterCovered ? (level * u_levelHeight - v_cloudPosition.z) / u_metre : -1.0;
    float above = waterCovered ? u_waterLine / u_metre - depth : 99.0;
    vec2 p = vec2(world.x, -world.y);
    if (shore && u_rainDetail > 0.0 && u_waterEffects > 0.0) {
        // The rippling surface bends the view of a submerged bed, so its detail sways with ripples above it. The bed
        // cannot tell whether a current or the wind moves the water, so this sway follows neither.
        vec2 swell = (texture(u_waterDetail, CROSSING * v_cloudPosition.xy * u_rainScale * 1.65
              + vec2(u_rainTime * .018, 0.0)).rg - .5) * CROSSING;
        float under = smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) * min(depth, 2.0);
        p += vec2(swell.x, -swell.y) * (under * .3 * u_waterEffects * u_rainDetail);
    }
    // Capture numerical-map gradients before varying material/coverage branches.
    mat2 groundGradient = mat2(dFdx(p), dFdy(p));
    mat2 wallXGradient = mat2(vec2(worldDx.y * sign(projection.x), -worldDx.z),
          vec2(worldDy.y * sign(projection.x), -worldDy.z));
    mat2 wallYGradient = mat2(vec2(-worldDx.x * sign(projection.y), -worldDx.z),
          vec2(-worldDy.x * sign(projection.y), -worldDy.z));
    // Smooth value fields from the shared 64-texel noise: broad has ~11 m cells, fine ~2.5 m, and region ~40 m.
    vec3 fields = groundFields(u_rainNoise, world);
    float broad = fields.x, fine = fields.y, region = fields.z;
    vec3 albedo = toLinear(vec3(.52));
    vec3 normal = face;
    float cavity = 1.0;
    float materialHeight = .5;
    vec3 emission = vec3(0.0);
    float roughness = .9, volcanic = 0.0;
#ifdef terrainBlendFlag
    vec4 materialCoverage = vec4(1.0, 0.0, 0.0, 0.0);
#endif
    float caustic = 0.0;
    float grass = family(0.0) ? 1.0 : 0.0;
    float sand = family(2.0) ? 1.0 : 0.0;
    vec3 bounce = groundBounce();
    float response = u_groundResponse;
    float shoreAbsorption = 0.0;
    float rainCover = step(0.0, response);
    bool natural = (ground || cliff || bedrock) && !family(4.0);
    float sediment = shore ? 1.0 - smoothstep(-.25, .05, above) : 0.0;
    vec3 materialWorld = vec3(p.x, -p.y, world.z);
    // Levels of water over a submerged bed; none elsewhere.
    float submerged = 0.0;
    vec3 transmission = vec3(1.0);
    float biomePool = 0.0, biomeDamp = 0.0;
    // Cliffs grade continuously with height.
    if (cliff) level = v_cloudPosition.z / u_levelHeight;
    if (u_clay < .5) {
        vec4 detail = vec4(0.0, 0.0, 1.0, 1.0);
        if (natural) {
#ifndef terrainBlendFlag
            float foot = shore || cliff || bedrock ? v_diffuseUV.x : v_diffuseUV.y;
            float rim = shore || cliff || bedrock ? v_diffuseUV.y : v_diffuseUV.x;
            float rock = ground ? rockiness(steps) : bedrock ? 1.0 : v_color.g;
            TerrainMaterial material = naturalMaterial(materialWorld, face, foot, rim, rock, ground ? .5 : hardness,
                  broad, fine, region, sediment);
            albedo = material.color;
            normal = material.normal;
            cavity = material.cavity;
            materialHeight = material.height;
            emission = material.emission;
            roughness = material.roughness;
            volcanic = material.volcanic;
#endif
            // Match a top to its slope at the same height; tactical level grading remains continuous.
            if (!shore) level = v_cloudPosition.z / u_levelHeight;
        } else if (ground) {
            // A water hex's steep banks take their maps from the side, as walls do, so nothing stretches downhill.
            bool steep = shore && face.z < .6;
            vec2 q = !steep ? p : abs(face.x) > abs(face.y) ? vec2(world.y * sign(face.x), -world.z)
                  : vec2(-world.x * sign(face.y), -world.z);
            // Pavement has no directional grain to preserve. Translate both aligned maps through a continuous
            // world field instead of repeating the same concrete cloud at two fixed scales (two reads either way).
            float concreteVariation = broad * .45 + fine * .55;
            mat2 qGradient = !steep ? groundGradient
                  : abs(face.x) > abs(face.y)
                  ? mat2(vec2(worldDx.y * sign(face.x), -worldDx.z), vec2(worldDy.y * sign(face.x), -worldDy.z))
                  : mat2(vec2(-worldDx.x * sign(face.y), -worldDx.z), vec2(-worldDy.x * sign(face.y), -worldDy.z));
            mat2 concreteGradient = qGradient / u_sculptTiles.x;
            vec4 top = family(4.0)
                  ? translatedTexel(u_sculptLayers.x, q / u_sculptTiles.x, concreteGradient, concreteVariation)
                  : planar(u_sculptLayers.x, q, u_sculptTiles.x, broad);
            vec4 rubble = planar(u_sculptLayers.y, q, u_sculptTiles.y, fine);
            vec4 weights = debrisWeights();
            float rim = 1.0 - smoothstep(.3, 2.2 + 1.5 * fine, v_diffuseUV.x);
            float foot = 1.0 - smoothstep(.4, 3.0 + 2.0 * broad, v_diffuseUV.y);
            float slope = 1.0 - smoothstep(.8, .96, face.z);
            float patches = smoothstep(.56, .78, fine * .7 + broad * .3);
            // Mantled ground shows scree only beside rock cliffs; beside the earth banks of lower steps a meadow wears
            // through to its soil, and snow stays whole. The vertex tint carries the nearest step's height.
            float rock = mantled() ? rockiness(steps) : 1.0;
            float edges = max(rim * weights.x, foot * weights.y);
            float want = clamp(max(edges * rock, slope * weights.z) + patches * weights.w, 0.0, 1.0);
            // A water hex's bed is stony from just above the waterline down, whatever grows on the land around; its
            // dry bank stays the land's own ground.
            if (shore) want = max(want, 1.0 - smoothstep(-.25, .05, above));
            float w = heightBlend(top.a, rubble.a, want);
            albedo = mix(top.rgb, rubble.rgb, w);
            materialHeight = mix(top.a, rubble.a, w);
            if (hasAuthoredRoughness(u_sculptLayers.x) || hasAuthoredRoughness(u_sculptLayers.y)) {
                float topRoughness = family(4.0)
                      ? translatedRoughness(u_sculptLayers.x, q / u_sculptTiles.x, concreteGradient, concreteVariation, roughness)
                      : planarRoughness(u_sculptLayers.x, q, qGradient, u_sculptTiles.x, broad, roughness);
                roughness = mix(topRoughness,
                      planarRoughness(u_sculptLayers.y, q, qGradient, u_sculptTiles.y, fine, roughness), w);
            }
            // In patches only: most of a bank's lip keeps its turf.
            if (family(0.0)) {
                float wear = max(rim * .55, foot * .75) * (1.0 - rock) * smoothstep(.35, .7, fine);
                albedo = mix(albedo, worn(albedo, top.a, wear), 1.0 - w);
            }
            if (u_normalMaps > .5 && terrainNormalDetail > 0.0 && !steep) {
                vec4 topNormal = family(4.0)
                      ? translatedTexel(u_terrainData, u_sculptLayers.x * .5, q / u_sculptTiles.x, concreteGradient, concreteVariation)
                      : planarNormal(u_sculptLayers.x + 1.0, p, u_sculptTiles.x, broad);
                if (family(4.0)) topNormal.rgb = mix(topNormal.rgb * 2.0 - 1.0, vec3(0.0, 0.0, 1.0), farDetail);
                detail = mix(topNormal,
                      planarNormal(u_sculptLayers.y + 1.0, p, u_sculptTiles.y, fine), w);
            }
            albedo = groundTone(albedo, world, broad, fine, region, rim, foot);
        } else if (plant) {
            // The bush GLB supplies distinct woody stems and leaves; UV/alpha carry its linear RGB.
            // Live shader reload can still encounter .25-kind meshes from the earlier height-based format.
            float leaf = texture(u_rainNoise, world.xy / 7.0 + world.z * .13).r;
            vec3 pigment = kind > .275 ? vec3(v_diffuseUV, v_color.a)
                  : family(0.0) ? toLinear(vec3(.27, .35, .12)) : toLinear(vec3(.40, .42, .26));
            albedo = pigment * mix(.92, 1.06, leaf);
        } else if (pit) {
            // A tree pit in the pavement: earth and bark mulch inside a kerb of paler, cleaner stone.
            if (v_color.a > .5) {
                albedo = planar(u_sculptLayers.x, p, u_sculptTiles.x, broad).rgb * 1.1;
                roughness = planarRoughness(u_sculptLayers.x, p, groundGradient, u_sculptTiles.x, broad, roughness);
            } else {
                vec4 grit = planar(u_sculptLayers.y, p, u_sculptTiles.y * .6, fine);
                roughness = planarRoughness(u_sculptLayers.y, p, groundGradient, u_sculptTiles.y * .6, fine, roughness);
                float mulch = texture(u_rainNoise, world.xy / 9.0 + v_color.a).r;
                albedo = mix(toLinear(vec3(.27, .19, .13)), toLinear(vec3(.42, .30, .19)), mulch) * mix(.8, 1.2, grit.a);
                if (u_normalMaps > .5 && terrainNormalDetail > 0.0) normal = upNormal(planarNormal(u_sculptLayers.y + 1.0, p, u_sculptTiles.y * .6, fine).rgb, face);
            }
        } else if (cliff && family(4.0)) {
            concreteSlab(world, worldDx, worldDy, face, projection, v_diffuseUV.x, v_diffuseUV.y,
                  albedo, normal, occlusion, cavity, roughness);
        } else {
            // Walls and rocks: the two vertical projections, V running down the face, never mirrored from outside.
            // Where a rounded corner turns between them, the projection whose relief stands higher shows through.
            vec3 axes = pow(abs(face), vec3(4.0));
            vec2 uvx, uvy;
            float side;
            vec4 wall = wallSample(world, projection, uvx, uvy, side);
            // A boulder's crown needs a horizontal stone projection: the two wall projections collapse on a
            // flat top, otherwise leaving a single colour or stretched stripes under the moss/snow treatment.
            float crown = cliff ? 0.0 : smoothstep(.45, .85, face.z);
            if (crown > 0.0) wall = mix(wall, planar(u_sculptLayers.z, p, u_sculptTiles.z, fine), crown);
            albedo = wall.rgb;
            if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
                normal = wallNormal(u_sculptLayers.z + 1.0, uvx, uvy, face, side);
                cavity = mix(normalTexel(u_sculptLayers.z + 1.0, uvy).a, normalTexel(u_sculptLayers.z + 1.0, uvx).a, side) * .5 + .5;
                if (crown > 0.0) {
                    vec4 top = planarNormal(u_sculptLayers.z + 1.0, p, u_sculptTiles.z, fine);
                    normal = normalize(mix(normal, upNormal(top.rgb, face), crown));
                    cavity = mix(cavity, top.a * .5 + .5, crown);
                }
            }
            roughness = mappedRoughness(stoneRoughness(sculptFamily, .5), wall.a);
            if (hasAuthoredRoughness(u_sculptLayers.z)) {
                float stone = wallRoughness(u_sculptLayers.z, uvx, uvy,
                      wallXGradient / u_sculptTiles.z, wallYGradient / u_sculptTiles.z, side, roughness);
                roughness = crown > 0.0 ? mix(stone,
                      planarRoughness(u_sculptLayers.z, p, groundGradient, u_sculptTiles.z, fine, roughness), crown) : stone;
            }
            float h = v_diffuseUV.x, d = v_diffuseUV.y;
            if (cliff) {
                float drop = h + d;
                // Caprock: the hard top bed of a cliff weathers paler; varnish streaks run down from under it.
                float cap = 1.0 - smoothstep(.8, 2.2, d);
                albedo = mix(albedo, albedo * vec3(1.08, 1.06, 1.03), cap * .6);
                float streak = smoothstep(.55, .85, texture(u_rainNoise, vec2((world.x + world.y) / 7.0, world.z / 90.0)).r)
                      * (1.0 - smoothstep(2.0, 14.0, d)) * smoothstep(.5, 1.5, d);
                if (family(2.0) || family(3.0) || family(LUNAR_FAMILY)) albedo = mix(albedo, albedo * toLinear(vec3(.52, .45, .42)), streak * .6);

                // Talus: fallen rock on the apron at the foot, where the face lies back. A mantle's banks slump into
                // soil and turf instead.
                float talus = min(.3 * drop, 6.0) * mix(.6, 1.0, fine);
                float apron = (1.0 - smoothstep(talus * .55, talus, h)) * smoothstep(.2, .5, face.z);
                apron = max(apron, 1.0 - smoothstep(.2, .6, h));
                if (apron > 0.0) {
                    float lying = smoothstep(.45, .8, face.z);
                    vec2 dx = vec2(world.y * sign(projection.x), -world.z), dy = vec2(-world.x * sign(projection.y), -world.z) + 3.1;
                    vec4 rubble = draped(u_sculptLayers.y, p, dx, dy, side, u_sculptTiles.y, fine, lying);
                    // A desert talus is the cliff's own sandstone, broken: redder and darker than the drifted sand.
                    if (family(2.0)) rubble.rgb = mix(rubble.rgb, wall.rgb * .92, .5);
                    float w = heightBlend(wall.a, rubble.a, apron);
                    albedo = mix(albedo, rubble.rgb, w);
                    if (hasAuthoredRoughness(u_sculptLayers.y)) {
                        float rubbleRoughness = wallRoughness(u_sculptLayers.y, dx / u_sculptTiles.y, dy / u_sculptTiles.y,
                              wallXGradient / u_sculptTiles.y, wallYGradient / u_sculptTiles.y, side, roughness);
                        if (lying > 0.0) rubbleRoughness = mix(rubbleRoughness,
                              planarRoughness(u_sculptLayers.y, p, groundGradient, u_sculptTiles.y, fine, roughness), lying);
                        roughness = mix(roughness, rubbleRoughness, w);
                    }
                    if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
                        vec3 rubbleNormal = drapedNormal(u_sculptLayers.y + 1.0, p, dx, dy, face, side, u_sculptTiles.y, fine, lying);
                        normal = normalize(mix(normal, rubbleNormal, w));
                    }
                }
            }
            // Broad ledges collect the ground cover (sand, snow, moss on alpine rock); on the rock kit only snow and
            // sand dust lie.
            float ledge = smoothstep(.8, .95, face.z) * smoothstep(.3, .6, fine) * (family(0.0) ? .7 : 1.0);
            // Snow caps the upper facets of a boulder, leaving its stone sides legible against the snowfield.
            if (family(5.0)) {
                ledge = cliff ? smoothstep(.5, .72, face.z) : smoothstep(.72, .92, face.z);
                if (!cliff) albedo *= .66;
            }
            if (!cliff) ledge *= family(5.0) ? smoothstep(.2, .9, h) : family(2.0) ? .45 : 0.0;
            if (ledge > 0.0 && !family(4.0)) {
                vec4 top = planar(u_sculptLayers.x, p, u_sculptTiles.x, broad);
                float w = heightBlend(wall.a, top.a, ledge);
                albedo = mix(albedo, groundTone(top.rgb, world, broad, fine, region, 0.0, 0.0), w);
                roughness = mix(roughness, planarRoughness(u_sculptLayers.x, p, groundGradient, u_sculptTiles.x, broad,
                      mappedRoughness(coverRoughness(sculptFamily), top.a)), w);
                if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
                    normal = normalize(mix(normal, upNormal(planarNormal(u_sculptLayers.x + 1.0, p, u_sculptTiles.x, broad).rgb, face), w));
                }
            }
            if (!cliff) {
                // Each block its own shade; on alpine meadows a thin moss stain keeps the stone texture visible.
                if (!wetRock) albedo *= mix(.78, 1.0, v_color.a);
                if (family(0.0)) {
                    float moss = smoothstep(.7, .94, face.z) * smoothstep(.6, .8, fine);
                    albedo *= mix(vec3(1.0), toLinear(vec3(.9, .97, .82)), moss * .22);
                }
            }
        }
        if (ground && !natural && u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            normal = upNormal(detail.rgb, face);
            cavity = detail.a * .6 + .4;
        }
        if (ground && !shore && family(4.0)) {
            occlusion = 1.0 - .3 * exp(-v_diffuseUV.y / 3.5);
        }
        if (shore && !natural && family(4.0) && face.z < .6) {
            concreteSlab(world, worldDx, worldDy, face, projection, v_diffuseUV.x, v_diffuseUV.y,
                  albedo, normal, occlusion, cavity, roughness);
        } else if (shore && !natural && !family(4.0)) {
            // A drowned wall stays sheer: exposed rock above, then a broken transition into the bed's sediment
            // near its foot. Its height above that bed is supplied by the same vertices that form the wall.
            float rock = rockiness(steps) * (1.0 - smoothstep(.2, .8, face.z));
            if (rock > 0.0) {
                vec2 uvx, uvy;
                float side;
                vec4 wall = wallSample(world, face, uvx, uvy, side);
                float foot = smoothstep(.05, 1.6 + 1.2 * fine + .6 * wall.a, v_diffuseUV.x);
                rock *= foot;
                albedo = mix(albedo, wall.rgb, rock);
                if (hasAuthoredRoughness(u_sculptLayers.z)) {
                    mat2 dx = mat2(vec2(worldDx.y * sign(face.x), -worldDx.z), vec2(worldDy.y * sign(face.x), -worldDy.z));
                    mat2 dy = mat2(vec2(-worldDx.x * sign(face.y), -worldDx.z), vec2(-worldDy.x * sign(face.y), -worldDy.z));
                    roughness = mix(roughness, wallRoughness(u_sculptLayers.z, uvx, uvy,
                          dx / u_sculptTiles.z, dy / u_sculptTiles.z, side, roughness), rock);
                }
                if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
                    normal = normalize(mix(normal, wallNormal(u_sculptLayers.z + 1.0, uvx, uvy, face, side), rock));
                    float relief = mix(normalTexel(u_sculptLayers.z + 1.0, uvy).a, normalTexel(u_sculptLayers.z + 1.0, uvx).a, side);
                    cavity = mix(cavity, relief * .5 + .5, rock);
                }
            }
        }
        // Natural materials fade within materialNormal; retained slab/rock paths share this final fade.
        // Vertex/contact occlusion remains separate, so losing fine pores does not remove cliff-foot shading.
        if (!natural && !plant && u_normalMaps > .5) {
            normal = normalize(mix(face, normal, terrainNormalDetail));
            cavity = mix(TERRAIN_DISTANT_CAVITY, cavity, terrainNormalDetail);
        }
#ifdef terrainBlendFlag
        if (ground || cliff) {
            float foot = shore || cliff ? v_diffuseUV.x : v_diffuseUV.y;
            float rim = shore || cliff ? v_diffuseUV.y : v_diffuseUV.x;
            float rock = ground ? rockiness(steps) : v_color.g;
            // A bank blend must use sediment below the waterline, never repaint the bed with neighbouring turf.
            blendCovers(materialWorld, face, foot, rim, rock, ground ? .5 : hardness, sediment,
                  broad, fine, region, albedo, normal, cavity, materialHeight, grass, sand, bounce, response, rainCover,
                  emission, roughness, volcanic, materialCoverage);
        }
#endif
        if (ground || cliff || bedrock) {
            biomeSurface(world, face, shore, above, cliff || bedrock ? v_diffuseUV.x : 0.0,
                  cliff || bedrock ? v_diffuseUV.y : v_diffuseUV.x, materialHeight, sand,
                  albedo, normal, cavity, roughness, grass, bounce, biomePool, biomeDamp);
        }
        if (ground && grass > 0.0 && !shore && u_wind.z > 0.0) {
            // Gusts roll across a meadow: the grass leans with them and catches the light differently.
            vec2 gust = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(1.0, 0.0);
            float sway = sin(dot(world.xy, gust) * .45 - u_rainTime * 1.9 + fine * 6.0)
                  * sin(dot(world.xy, vec2(-gust.y, gust.x)) * .21 - u_rainTime * .7);
            normal = normalize(normal + vec3(gust * sway * .12 * u_wind.z * grass, 0.0));
        }
        if (waterCovered) {
            // Wet in a band just above the waterline and below it.
            shoreAbsorption = .65 * (1.0 - smoothstep(0.0, .12, above)) * (1.0 - smoothstep(0.0, .20, biomeDamp));
        }
        if (watered) {
            // Beneath the waterline the bed keeps the hue its column of water passes and catches caustics
            // (water-optics.glsl), while the surface above removes the brightness the column absorbs.
            submerged = max(0.0, depth * u_metre - u_waterLine) / u_levelHeight;
            // Fine wetland sediment keeps its brown/olive tint in the thin shore wash; deeper bed optics are unchanged.
            vec4 mixture = liquidCoverage(world, level);
            if (dot(mixture, vec4(1.0)) < .5) mixture = waterPalette(palette);
            // Water-owned triangles include exposed bars. Apply suspended sediment only below the actual
            // waterline, or the dry marsh changes colour at the straight boundary of the bed mesh.
            vec3 bedTint = mix(waterBedTint(mixture, submerged), toLinear(vec3(.92, .88, .73)), clamp(biomeDamp * 2.0, 0.0, 1.0));
            transmission = mix(vec3(1.0), bedTint, smoothstep(0.0, .12, -above));
            if (u_rainDetail > 0.0 && u_waterEffects > 0.0) {
                caustic = waterBedCaustics(v_cloudPosition.xy * u_rainScale, submerged) * u_rainDetail * u_waterEffects;
            }
        }
    }
    // 1 above the water, 0 on a submerged bed: the water surface draws the grid and takes the rain for it.
    float exposed = watered ? 1.0 - smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) : 1.0;
    if (ground && u_clay < .5 && volcanic < .01) {
        // Follow the final material weights, including sand windows and authored mixtures. Reuse the blended
        // relief once; no extra maps or stamped motifs. Wetland pools suppress dry surface abrasion.
        if (u_terrainWear > 0.0 && terrainNormalDetail > 0.0) {
            vec4 wear = terrainWear(fields, materialHeight, cavity, face, normal);
#ifdef terrainBlendFlag
            vec3 pigment = materialWeathering(coverFamilies.x, wear, fields, cavity) * materialCoverage.x
                  + materialWeathering(coverFamilies.y, wear, fields, cavity) * materialCoverage.y
                  + materialWeathering(coverFamilies.z, wear, fields, cavity) * materialCoverage.z
                  + materialWeathering(coverFamilies.w, wear, fields, cavity) * materialCoverage.w;
#else
            vec3 pigment = materialWeathering(sculptFamily, wear, fields, cavity);
#endif
            // Snow's authored RGB already contains its cool granular crust. Do not add a second microscopic
            // color treatment that disappears with normal LOD. Keep the original wear response for roughness.
            vec3 colorPigment = pigment;
#ifdef terrainBlendFlag
            float snowCoverage = dot(materialCoverage, 1.0 - step(vec4(.5), abs(coverFamilies - 5.0)));
            if (snowCoverage > 0.0) colorPigment -= materialWeathering(5.0, wear, fields, cavity) * snowCoverage;
#else
            if (family(5.0)) colorPigment = vec3(0.0);
#endif
            float wearStrength = u_terrainWear * exposed * (1.0 - biomePool) * (1.0 - biomeDamp * .65);
            pigment *= wearStrength;
            albedo *= authoredPigmentGain(vec3(1.0) + colorPigment * wearStrength);
            roughness = mix(roughness, .98, max(max(abs(pigment.r), abs(pigment.g)), abs(pigment.b)));
        }
        groundDamage(world, face, grass, sand, exposed, albedo, normal, cavity, roughness);
    }
    #ifdef surfaceScarFlag
    if (!ground) groundDamage(world, face, 0.0, sand, exposed, albedo, normal, cavity, roughness);
    #endif
    // Rain darkens exposed ground and rock, and gathers in puddles on level ground.
    float wet = u_wetness * rainCover * exposed;
    // Moisture darkens porous peat, but does not turn its entire surface into standing water. Rain
    // coating and transient puddles both respect that absorption; permanent pools remain fully wet.
    float saturated = smoothstep(0.0, .28, biomeDamp);
    float rainRetention = mix(1.0, .15, saturated);
    float rainFilm = wet * max(0.0, response);
    float film = max(rainFilm * rainRetention, biomePool);
    if (ground && wet * u_rainDetail > 0.0 && face.z > .97) {
        vec2 position = v_cloudPosition.xy * u_rainScale;
        float rainPool = rainPuddle(position, wet, max(0.0, response))
              * smoothstep(.97, .999, face.z) * u_rainDetail;
        // Ripples only where a puddle shows them; elsewhere the weight is zero and the normal stays exactly.
        vec3 rippled = rainPool > 0.0 ? normalize(vec3(rainRipples(position), 1.0)) : normal;
        normal = normalize(mix(normal, rippled, rainPool * rainRetention));
        film = max(mix(rainFilm, 1.0, rainPool) * rainRetention, biomePool);
    }
    // Biome peat/mud already contains its wet pigment. Rain only darkens the remaining unsaturated share;
    // the existing film/pool response above still receives the full rain amount.
    albedo = wetAlbedo(albedo, max(groundAbsorption(wet * (1.0 - saturated), response), shoreAbsorption));
    if (ground) albedo *= mix(1.0, terrainGrid(v_cloudPosition.xy * u_rainScale), exposed * (1.0 - volcanic));
    albedo *= transmission;
    albedo = materialReflectance(albedo);
    // Grade the completed bounded pigment: later weathering or a reflectance bound must not flatten the lift.
    // Clay and molten material retain their own presentation; cliffs use the continuous height set above.
    if (u_clay < .5 && level != 0.0 && volcanic < 1.0) {
        float fungusGrade = family(FUNGUS_FAMILY) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
        // The final material coverage includes height competition and sediment transfer at mixed contacts.
        fungusGrade = dot(materialCoverage, 1.0 - step(vec4(.5), abs(coverFamilies - FUNGUS_FAMILY)));
#endif
        albedo = mix(levelGrade(albedo, level, fungusGrade), albedo, volcanic);
    }
#ifdef lightingFlag
    vec3 view = -viewDirection();
    normal = shadingNormal(normal, geometric, view);
    // Once per pixel, from the final normal: mapped relief, ripples and flat pools alike (light-model.glsl).
    surfaceVariance = normalVariance(dFdx(normal), dFdy(normal));
    // Thin tissue receives the shadow from its sun-facing side; other casters still occlude it.
    // Keep the single receiver-plane lookup outside the per-pixel tissue/volcanic branches.
    vec3 light = sunDirection();
    vec3 receiver = leaf && dot(face, light) < 0.0 ? -face : face;
    // Natural cliffs approximate a curved surface with shared vertex normals. Use that unmapped surface for
    // incident sidedness: a triangle cutoff exposes their tessellation at the light horizon. Planar panels and
    // other geometry retain triangle support; normal-map detail cannot move this incident hemisphere.
    Surface surface = opaqueSurface(albedo, normal, cliff && !panel ? face : geometric, roughness, film,
          sculptShadow(receiver, light));
    surface.occlusion = surface.specularOcclusion = occlusion;
    surface.cavity = cavity;
    // Match the leaves in terrain-foliage: they pass .35 of their colour from the other side, never a wrap lobe.
    if (leaf) surface.back = thinTransmission(albedo, .35);
    surface.heat = volcanic;
    surface.bounce = bounce;
    surface.emission = emission;
    vec3 result = shadeSurface(surface, caustic, submerged);
#else
    vec3 result = albedo + emission;
#endif
    // Fade through the final level above the black cap. All walls around one pit share this height reference,
    // even where their rims stand at different levels, so the fade cannot break into wedges at their corners.
    if (abyss) {
        float light = smoothstep(0.0, .9 * u_levelHeight / u_metre, v_diffuseUV.x);
        result *= light * light;
    }
    fragColor = vec4(result, 1.0);
}
