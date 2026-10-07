#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Sculpted tops, cliffs and the rock kit. Geometry supplies the landforms; this shader layers each surface family's
// four materials (ground, debris, wall, mantle), mapped in world space, and lights them with the board's one light
// model (light-model.glsl, surface-lighting.glsl): linear albedo times linear light, encoded for display like every
// other lit surface, so the atmosphere composite grades and tones the whole frame once.
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
// Every family's maps, interleaved: colour/height at a map's layer and normal/AO at the next (GpuAssets.sculptArray).
uniform sampler2DArray u_terrainLayers;
uniform vec4 u_sculptLayers; // this family's colour/height layers: ground, debris, wall, mantle; normal/AO one up
uniform vec4 u_sculptTiles; // metres per repeat: ground, debris, wall, mantle
uniform float u_metre;      // world units per metre
uniform float u_normalMaps;
uniform float u_groundResponse;
uniform float u_sculptFamily; // BoardScene.Surface: GRASS 0, DIRT 1, SAND 2, ROCK 3, CONCRETE 4, SNOW 5, LUNAR 6
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
uniform vec4 u_coverResponses;
uniform vec4 u_coverTiles0, u_coverTiles1, u_coverTiles2, u_coverTiles3;
uniform vec4 u_coverLayers0, u_coverLayers1, u_coverLayers2, u_coverLayers3;
#endif

// terrain-ground-color-functions

// terrain-projection-functions

// ---- Surface families --------------------------------------------------------------------------------------------

bool family(float f) { return abs(u_sculptFamily - f) < .5; }

// Grey lunar/volcanic sand reuses the shared loose-sand relief. Derive the colour from the supporting covers,
// so a theme boundary interpolates in world space instead of changing abruptly at a hex edge.
float greySand() {
#ifdef terrainBlendFlag
    vec4 weights = max(v_coverWeights, vec4(0.0));
    vec4 grey = (1.0 - step(vec4(.5), abs(u_coverFamilies - LUNAR_FAMILY)))
          + (1.0 - step(vec4(.5), abs(u_coverFamilies - VOLCANO_FAMILY)));
    vec4 substrate = step(vec4(.5), abs(u_coverFamilies - 2.0));
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
    vec3 soil = vec3(.31, .25, .18) * (.75 + .5 * height);
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

vec3 groundBounce() { return groundBounceFor(u_sculptFamily); }

// Bed colour around the wall map's own tint: hard beds pale, soft beds deeper.
vec3 bedTintFor(float f, float hardness) {
    if (abs(f - 2.0) < .5 || abs(f - DESERT_FAMILY) < .5 || abs(f - MARS_FAMILY) < .5) {
        return mix(vec3(.90, .86, .83), vec3(1.06, 1.03, 1.0), hardness);
    }
    if (abs(f - 1.0) < .5) return mix(vec3(.86, .80, .74), vec3(1.08, 1.04, 1.0), hardness);
    return mix(vec3(.90, .91, .93), vec3(1.06, 1.04, 1.01), hardness);
}

vec3 bedTint(float hardness) { return bedTintFor(u_sculptFamily, hardness); }


vec3 groundTone(vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    return groundToneFor(u_sculptFamily, albedo, world, broad, fine, region, rim, foot);
}

// sculpt-material-functions
void main() {
    vec3 face = normalize(v_normal);
    float occlusion = v_color.r;
    float level = floor(v_color.g * 255.0 + .5) - 64.0;
    float kind = v_color.b;
    bool ground = kind < .125, plant = kind >= .125 && kind < .375, cliff = kind >= .375 && kind < .625;
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
    terrainMaterialLod(world, face);
    // The area a pixel covers, as a length: a tilted view's foreshortening alone does not remove the detail.
    float footprint = sqrt(length(dFdx(world.xy)) * length(dFdy(world.xy)));
    farDetail = .8 * smoothstep(.18, .6, footprint);
    pixelMetres = max(max(length(dFdx(world)), length(dFdy(world))), 1e-4);
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
    // Smooth value fields from the shared 64-texel noise: broad has ~11 m cells, fine ~2.5 m, and region ~40 m.
    vec3 fields = groundFields(u_rainNoise, world);
    float broad = fields.x, fine = fields.y, region = fields.z;
    vec3 albedo = vec3(.52);
    vec3 normal = face;
    float cavity = 1.0;
    float materialHeight = .5;
    vec3 emission = vec3(0.0);
    float roughness = .9, volcanic = 0.0;
    float caustic = 0.0;
    float grass = family(0.0) ? 1.0 : 0.0;
    float sand = family(2.0) ? 1.0 : 0.0;
    vec3 bounce = groundBounce();
    float response = u_groundResponse;
    float rainCover = step(0.0, response);
    bool natural = (ground || cliff || bedrock) && !family(4.0);
    float sediment = shore ? 1.0 - smoothstep(-.25, .05, above) : 0.0;
    vec3 materialWorld = vec3(p.x, -p.y, world.z);
    // Levels of water over a submerged bed; none elsewhere.
    float submerged = 0.0;
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
            vec4 top = planar(u_sculptLayers.x, q, u_sculptTiles.x, broad);
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
            // In patches only: most of a bank's lip keeps its turf.
            if (family(0.0)) {
                float wear = max(rim * .55, foot * .75) * (1.0 - rock) * smoothstep(.35, .7, fine);
                albedo = mix(albedo, worn(albedo, top.a, wear), 1.0 - w);
            }
            if (u_normalMaps > .5 && terrainNormalDetail > 0.0 && !steep) {
                detail = mix(planarNormal(u_sculptLayers.x + 1.0, p, u_sculptTiles.x, broad),
                      planarNormal(u_sculptLayers.y + 1.0, p, u_sculptTiles.y, fine), w);
            }
            albedo = groundTone(albedo, world, broad, fine, region, rim, foot);
        } else if (plant) {
            // The bush GLB supplies distinct woody stems and leaves; UV/alpha carry its authored RGB.
            // Live shader reload can still encounter .25-kind meshes from the earlier height-based format.
            float leaf = texture(u_rainNoise, world.xy / 7.0 + world.z * .13).r;
            vec3 pigment = kind > .275 ? vec3(v_diffuseUV, v_color.a)
                  : family(0.0) ? vec3(.27, .35, .12) : vec3(.40, .42, .26);
            albedo = pigment * mix(.92, 1.06, leaf);
        } else if (pit) {
            // A tree pit in the pavement: earth and bark mulch inside a kerb of paler, cleaner stone.
            if (v_color.a > .5) {
                albedo = planar(u_sculptLayers.x, p, u_sculptTiles.x, broad).rgb * 1.1;
            } else {
                vec4 grit = planar(u_sculptLayers.y, p, u_sculptTiles.y * .6, fine);
                float mulch = texture(u_rainNoise, world.xy / 9.0 + v_color.a).r;
                albedo = mix(vec3(.27, .19, .13), vec3(.42, .30, .19), mulch) * mix(.8, 1.2, grit.a);
                if (u_normalMaps > .5 && terrainNormalDetail > 0.0) normal = upNormal(planarNormal(u_sculptLayers.y + 1.0, p, u_sculptTiles.y * .6, fine).rgb, face);
            }
        } else if (cliff && family(4.0)) {
            concreteSlab(world, face, projection, v_diffuseUV.x, v_diffuseUV.y, albedo, normal, occlusion, cavity);
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
                cavity = mix(mapTexel(u_sculptLayers.z + 1.0, uvy).a, mapTexel(u_sculptLayers.z + 1.0, uvx).a, side) * .5 + .5;
                if (crown > 0.0) {
                    vec4 top = planarNormal(u_sculptLayers.z + 1.0, p, u_sculptTiles.z, fine);
                    normal = normalize(mix(normal, upNormal(top.rgb, face), crown));
                    cavity = mix(cavity, top.a * .5 + .5, crown);
                }
            }
            roughness = mappedRoughness(stoneRoughness(u_sculptFamily, .5), wall.a, cavity);
            float h = v_diffuseUV.x, d = v_diffuseUV.y;
            if (cliff) {
                float drop = h + d;
                albedo *= bedTint(hardness);
                // Caprock: the hard top bed of a cliff weathers paler; varnish streaks run down from under it.
                float cap = 1.0 - smoothstep(.8, 2.2, d);
                albedo = mix(albedo, albedo * vec3(1.08, 1.06, 1.03), cap * .6);
                float streak = smoothstep(.55, .85, texture(u_rainNoise, vec2((world.x + world.y) / 7.0, world.z / 90.0)).r)
                      * (1.0 - smoothstep(2.0, 14.0, d)) * smoothstep(.5, 1.5, d);
                if (family(2.0) || family(3.0) || family(LUNAR_FAMILY)) albedo = mix(albedo, albedo * vec3(.52, .45, .42), streak * .6);

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
                roughness = mix(roughness, mappedRoughness(coverRoughness(u_sculptFamily), top.a, cavity), w);
                if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
                    normal = normalize(mix(normal, upNormal(planarNormal(u_sculptLayers.x + 1.0, p, u_sculptTiles.x, broad).rgb, face), w));
                }
            }
            if (!cliff) {
                // Each block its own shade; on alpine meadows a thin moss stain keeps the stone texture visible.
                if (!wetRock) albedo *= mix(.78, 1.0, v_color.a);
                if (family(0.0)) {
                    float moss = smoothstep(.7, .94, face.z) * smoothstep(.6, .8, fine);
                    albedo *= mix(vec3(1.0), vec3(.9, .97, .82), moss * .22);
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
            concreteSlab(world, face, projection, v_diffuseUV.x, v_diffuseUV.y, albedo, normal, occlusion, cavity);
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
                albedo = mix(albedo, wall.rgb * bedTint(.5), rock);
                if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
                    normal = normalize(mix(normal, wallNormal(u_sculptLayers.z + 1.0, uvx, uvy, face, side), rock));
                    float relief = mix(mapTexel(u_sculptLayers.z + 1.0, uvy).a, mapTexel(u_sculptLayers.z + 1.0, uvx).a, side);
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
                  emission, roughness, volcanic);
        }
#endif
        if (natural) {
            biomeSurface(world, face, shore, above, cliff || bedrock ? v_diffuseUV.x : 0.0,
                  cliff || bedrock ? v_diffuseUV.y : v_diffuseUV.x, materialHeight, sand,
                  albedo, normal, cavity, grass, bounce, biomePool, biomeDamp);
        }
        if (ground && grass > 0.0 && !shore && u_wind.z > 0.0) {
            // Gusts roll across a meadow: the grass leans with them and catches the light differently.
            vec2 gust = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(1.0, 0.0);
            float sway = sin(dot(world.xy, gust) * .45 - u_rainTime * 1.9 + fine * 6.0)
                  * sin(dot(world.xy, vec2(-gust.y, gust.x)) * .21 - u_rainTime * .7);
            albedo *= 1.0 + sway * .05 * u_wind.z * grass;
            normal = normalize(normal + vec3(gust * sway * .12 * u_wind.z * grass, 0.0));
        }
        float fungusGrade = family(FUNGUS_FAMILY) ? 1.0 : 0.0;
#ifdef terrainBlendFlag
        // Follow the shared contact weights at theme boundaries rather than tinting a neighbouring material abruptly.
        vec4 fungal = 1.0 - step(vec4(.5), abs(u_coverFamilies - FUNGUS_FAMILY));
        vec4 coverWeights = max(v_coverWeights, vec4(0.0));
        fungusGrade = dot(coverWeights, fungal) / max(dot(coverWeights, vec4(1.0)), .0001);
#endif
        albedo = mix(levelGrade(albedo, level, fungusGrade), albedo, volcanic);
        if (waterCovered) {
            // Wet in a band just above the waterline and below it.
            albedo *= 1.0 - .25 * (1.0 - smoothstep(0.0, .12, above)) * (1.0 - smoothstep(0.0, .20, biomeDamp));
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
            vec3 bedTint = mix(waterBedTint(mixture, submerged), vec3(.92, .88, .73), clamp(biomeDamp * 2.0, 0.0, 1.0));
            albedo *= mix(vec3(1.0), bedTint, smoothstep(0.0, .12, -above));
            if (u_rainDetail > 0.0 && u_waterEffects > 0.0) {
                caustic = waterBedCaustics(v_cloudPosition.xy * u_rainScale, submerged) * u_rainDetail * u_waterEffects;
            }
        }
    }
    // 1 above the water, 0 on a submerged bed: the water surface draws the grid and takes the rain for it.
    float exposed = watered ? 1.0 - smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) : 1.0;
    if (ground) albedo *= mix(1.0, terrainGrid(v_cloudPosition.xy * u_rainScale), exposed * (1.0 - volcanic));
    // Rain darkens exposed ground and rock, and gathers in puddles on level ground.
    float wet = u_wetness * rainCover * exposed;
    float film = max(wet * max(0.0, response), max(biomeDamp, biomePool));
    float puddle = biomePool;
    if (ground && wet * u_rainDetail > 0.0 && face.z > .97) {
        vec2 position = v_cloudPosition.xy * u_rainScale;
        puddle = max(puddle, rainPuddle(position, wet, max(0.0, response)) * smoothstep(.97, .999, face.z) * u_rainDetail);
        normal = normalize(mix(normal, normalize(vec3(rainRipples(position), 1.0)), puddle));
        film = mix(film, 1.0, puddle);
    }
    albedo *= 1.0 - wet * mix(.18, .11, max(0.0, response));
    albedo = toLinear(albedo);
#ifdef lightingFlag
    // Sky from above; from below, light reflected by the sunlit ground: warm in the desert, white on snow.
    vec3 ambient = skyLight(normal, bounce) * occlusion * cavity + lavaIrradiance(v_cloudPosition, normal) * cavity;
    vec3 direct = vec3(0.0), sheen = vec3(0.0);
#if numDirectionalLights > 0
    vec3 light = -u_dirLights[0].direction;
    // Foliage scatters light around its masses: a wrapped response instead of a hard terminator.
    float incidence = plant ? max(0.0, dot(normal, light) * .6 + .4) : max(0.0, dot(normal, light));
    vec3 sun = u_dirLights[0].color * sculptShadow(face, light);
    direct = sun * incidence * mix(1.0, cavity, .5) * (1.0 + caustic);
    if (!plant && u_clay < .5 && volcanic < 1.0 && incidence > 0.0) {
        // Reuse the same shadowed sun and dielectric response as the other mapped materials. No additional
        // shadow pass or surface-map fetch: roughness follows the already blended height/cavity channels.
        sheen = sun * dielectricSheen(normal, light, -viewDirection(), film, roughness) * cavity;
    } else if (film > 0.0 && incidence > 0.0) {
        vec3 halfVector = normalize(light - viewDirection());
        float exponent = mix(12.0, 96.0, film);
        float fresnel = .02 + .98 * pow(1.0 - max(0.0, dot(-viewDirection(), halfVector)), 5.0);
        sheen = sun * incidence * film * fresnel * pow(max(0.0, dot(normal, halfVector)), exponent) * (exponent + 2.0) / 8.0;
    }
#endif
    if (volcanic > 0.0) {
        vec3 hotAmbient, hotDirect, hotSheen;
        surfaceLighting(normal, film, roughness, hotAmbient, hotDirect, hotSheen);
        ambient = mix(ambient, hotAmbient * cavity, volcanic);
        direct = mix(direct, hotDirect, volcanic);
        sheen = mix(sheen, hotSheen, volcanic);
    }
    // Cloud shadows attenuate direct light and sheen here (inserted by GpuCloudShadow).
    vec3 pigment = albedo;
    albedo *= ambient + direct;
    albedo += sheen;
    if (submerged > 0.0) {
        vec3 scattered = surfaceAmbient(vec3(0.0, 0.0, 1.0)) * occlusion + sunOnGround() * (1.0 + caustic);
        albedo = submergedLight(albedo, pigment, scattered, submerged);
    }
#endif
    vec3 result = toDisplay(albedo + emission);
    if (puddle > 0.0) result = rainReflection(result, normal, puddle);
    // Fade through the final level above the black cap. All walls around one pit share this height reference,
    // even where their rims stand at different levels, so the fade cannot break into wedges at their corners.
    if (abyss) {
        float light = smoothstep(0.0, .9 * u_levelHeight / u_metre, v_diffuseUV.x);
        result *= light * light;
    }
    fragColor = vec4(result, 1.0);
}
