#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Sculpted tops, cliffs and the rock kit. Geometry supplies the landforms; this shader layers each surface family's
// four materials (ground, debris, wall, mantle), mapped in world space, and lights them with the board's one light
// model (light-model.glsl, surface-lighting.glsl): linear albedo times linear light, encoded for display like every
// other lit surface, so the atmosphere composite grades and tones the whole frame once.
in vec2 v_diffuseUV;   // ground: rim / foot distance (drowned cliff: height above bed / foot distance);
                            // cliff: height above foot / depth below rim; rock: above root
                            // / below top. All in metres.
in vec3 v_normal;
in vec4 v_color;       // r: occlusion. g: game level (+64)/255, or for cliffs how much rock the face is (0
                            // bank, 1 cliff). b: kind (0 ground, .25 plant, .5 cliff, .75 tree pit, 1 rock). a: bed
                            // hardness (cliff), variation (rock, plant, a pit's earth below .5; its kerb is 1),
                            // nearest step height (.3 + .1 per level, dry ground), or below .25 on a water hex's banks
                            // and bed its water's palette and that height packed (GpuTerrain.shoreTint)
uniform sampler2D u_groundColor;
uniform sampler2D u_groundNormal;
uniform sampler2D u_debrisColor;
uniform sampler2D u_debrisNormal;
uniform sampler2D u_wallColor;
uniform sampler2D u_wallNormal;
uniform sampler2D u_mantleColor;
uniform sampler2D u_mantleNormal;
uniform vec4 u_sculptTiles; // metres per repeat: ground, debris, wall, mantle
uniform float u_metre;      // world units per metre
uniform float u_normalMaps;
uniform float u_groundResponse;
uniform float u_sculptFamily; // BoardScene.Surface: GRASS 0, DIRT 1, SAND 2, ROCK 3, CONCRETE 4, SNOW 5
uniform float u_levelHeight;
uniform float u_clay;
uniform vec3 u_wind;        // direction in xy, strength in z
uniform float u_waterEffects;
uniform float u_waterLine;  // world units the water surface lies below its hex's level
#ifdef terrainBlendFlag
in vec4 v_coverWeights;
uniform vec4 u_coverFamilies;
uniform vec4 u_coverResponses;
uniform sampler2DArray u_terrainLayers; // interleaved colour/height and normal/AO, shared with ordinary materials
uniform vec4 u_coverTiles0, u_coverTiles1, u_coverTiles2, u_coverTiles3;
uniform vec4 u_coverLayers0, u_coverLayers1, u_coverLayers2, u_coverLayers3;
#endif

// Per-level identity, so every level reads from straight above; saturation, lightness and contrast in percent per
// level. Higher ground turns lighter and paler toward cream, as drier, sun-bleached ground; lower ground darker and a
// little warmer. Neither turns any family's hue toward red or pink.
vec3 levelGrade(vec3 c, float level) {
    // Levels count almost fully near the ground and ease off further away, so no height grades to white or black.
    float up = 6.0 * (1.0 - exp(-max(level, 0.0) / 6.0)), down = 6.0 * (1.0 - exp(-max(-level, 0.0) / 6.0));
    float saturation = -3.0 * up + 4.0 * down;
    float lightness = 14.0 * up - 18.0 * down;
    float contrast = 2.0 * up;
    // Warmer below: less blue, a little less green.
    c *= vec3(1.0, 1.0 - .01 * down, 1.0 - .04 * down);
    float luma = dot(c, vec3(.299, .587, .114));
    c = mix(vec3(luma), c, 1.0 + saturation / 100.0);
    // Dark ground lifts less, so a meadow's upper levels keep their green and their texture instead of bleaching.
    c = lightness >= 0.0 ? mix(c, vec3(1.0, .97, .9), lightness / 100.0 * min(1.0, luma / .55))
          : c * (1.0 + lightness / 100.0);
    c = (c - .5) * (1.0 + contrast / 100.0) + .5;
    return clamp(c, 0.0, 1.0);
}

// terrain-projection-functions

// ---- Surface families --------------------------------------------------------------------------------------------

bool family(float f) { return abs(u_sculptFamily - f) < .5; }

// Meadows and snowfields lie on a mantle (earth, snow) that buries low steps and caps high rock cliffs.
bool mantled() { return family(0.0) || family(5.0); }

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
    if (family(1.0)) return vec4(.6, .9, .6, .45);     // dirt: gravel
    if (family(2.0)) return vec4(.85, .9, .5, .3);     // sand: desert pavement
    if (family(3.0)) return vec4(.5, .9, .6, .55);     // rock: scree
    if (family(4.0)) return vec4(0.0);                  // concrete: clean slab
    return vec4(.75, .9, .7, .12);                     // snow: wind-scoured rock
}

// Low shrubs: sage and olive in the desert, deep green in meadows; dry and fresh ends of each palette.
vec3 plantColor(float variation) {
    if (family(0.0)) return mix(vec3(.15, .25, .08), vec3(.27, .35, .12), variation);
    if (family(1.0)) return mix(vec3(.30, .33, .18), vec3(.44, .37, .24), variation);
    if (family(2.0)) return mix(vec3(.30, .36, .16), vec3(.46, .45, .25), variation);
    return mix(vec3(.26, .31, .20), vec3(.38, .37, .26), variation);
}

// Sunlit ground around a family reflects its own colour into shaded walls and undersides.
vec3 groundBounceFor(float f) {
    if (abs(f) < .5) return vec3(.15, .145, .06);
    if (abs(f - 1.0) < .5) return vec3(.22, .15, .10);
    if (abs(f - 2.0) < .5) return vec3(.42, .28, .16);
    if (abs(f - 3.0) < .5) return vec3(.22, .21, .19);
    if (abs(f - 4.0) < .5) return vec3(.30, .29, .27);
    if (f > 5.5) return vec3(.04, .025, .018);
    return vec3(.75, .78, .82);
}

vec3 groundBounce() { return groundBounceFor(u_sculptFamily); }

// Bed colour around the wall map's own tint: hard beds pale, soft beds deeper.
vec3 bedTintFor(float f, float hardness) {
    if (abs(f - 2.0) < .5) return mix(vec3(.90, .86, .83), vec3(1.06, 1.03, 1.0), hardness);
    if (abs(f - 1.0) < .5) return mix(vec3(.86, .80, .74), vec3(1.08, 1.04, 1.0), hardness);
    // The bedrock under a concrete slab: darker than the pale concrete it carries.
    if (abs(f - 4.0) < .5) return mix(vec3(.62, .61, .59), vec3(.80, .78, .75), hardness);
    return mix(vec3(.90, .91, .93), vec3(1.06, 1.04, 1.01), hardness);
}

vec3 bedTint(float hardness) { return bedTintFor(u_sculptFamily, hardness); }

// Broad variations in the ground's tone, so a large field reads neither as one flat colour nor as tiles: patches, the
// desert's iron-red thin sand, pale washes and flats and its dunes, a meadow's dry and lush turf. rim and foot weigh the
// nearness of a drop and of a rise. The ground and the cover drifted onto slopes and ledges share it, so they match.
vec3 groundToneFor(float f, vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    albedo *= mix(.94, 1.06, broad) * mix(.95, 1.05, region) * mix(.96, 1.04, fine);
    if (abs(f - 2.0) < .5) {
        // Desert ground: iron-red where the sand lies thin over its bedrock, paler washes where fines settle.
        albedo = mix(albedo, albedo * vec3(1.04, .86, .76), smoothstep(.5, .75, broad * .7 + region * .3) * .7);
        albedo = mix(albedo, albedo * vec3(1.06, 1.08, 1.1), smoothstep(.62, .85, fine * .5 + region * .5) * .5);
        // Broad flats of fine, pale sand between the orange drifts: lighter and less saturated.
        float luma = dot(albedo, vec3(.299, .587, .114));
        albedo = mix(albedo, mix(vec3(luma), albedo, .55) * 1.12, smoothstep(.4, .7, region * .6 + broad * .4) * .6);
        // Dunes: long, gentle swells of light and shade that run across the flats regardless of the hexes, bent and
        // broken up by the broad fields.
        float dune = sin(dot(world.xy, vec2(.8, .6)) / 19.0 + broad * 5.0 + region * 3.0);
        albedo *= 1.0 + .07 * dune * smoothstep(.2, .6, region + .3 * fine);
    }
    if (abs(f) < .5) {
        // Thin, dry turf on convex rims and in sunny patches; lush, dark grass where water gathers below cliffs.
        float dry = max(smoothstep(.55, .85, broad * .7 + fine * .3) * .5, rim * .6);
        albedo = mix(albedo, albedo * vec3(1.25, 1.12, .7), dry);
        albedo = mix(albedo, albedo * vec3(.78, .95, .82), max(foot * .6, (1.0 - smoothstep(.2, .45, broad)) * .4));
    }
    return albedo;
}

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
    bool pit = kind >= .625 && kind < .875;
    bool shore = ground && v_color.a < .25;
    // Rock-kind bytes 224..254 carry the fractional water level; 255 remains ordinary dry rock.
    bool wetRock = kind >= .875 && kind < .999;
    bool waterCovered = shore || wetRock;
    // Wet ground uses the spare ground-kind range for the fractional surface level of a descending stream.
    if (shore) level = v_color.g * 255.0 - 64.0 + kind * 8.0;
    if (wetRock) level = v_color.g * 255.0 - 64.0 + (kind * 255.0 - 224.0) / 30.0;
    // A water hex's ground packs its water's palette with the nearest step's height, which dry ground carries alone.
    float tintByte = v_color.a * 255.0;
    float palette = floor(tintByte / 16.0 + .03);
    float steps = shore ? (tintByte - 16.0 * palette) / 2.0 : (v_color.a - .3) / .1;
    vec3 world = v_cloudPosition / u_metre;
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
    float broad = texture(u_rainNoise, world.xy / 700.0).g * .6 + texture(u_rainNoise, world.xy / 430.0 + .19).b * .4;
    float fine = texture(u_rainNoise, world.xy / 160.0 + .41).b;
    float region = texture(u_rainNoise, world.xy / 2600.0 + .73).r;
    vec3 albedo = vec3(.52);
    vec3 normal = face;
    float cavity = 1.0;
    float materialHeight = .5;
    vec3 emission = vec3(0.0);
    float roughness = .9, volcanic = 0.0;
    float caustic = 0.0;
    float grass = family(0.0) ? 1.0 : 0.0;
    vec3 bounce = groundBounce();
    float response = u_groundResponse;
    float rainCover = step(0.0, response);
    bool natural = (ground || cliff) && !family(4.0);
    float sediment = shore ? 1.0 - smoothstep(-.25, .05, above) : 0.0;
    vec3 materialWorld = vec3(p.x, -p.y, world.z);
    // Levels of water over a submerged bed; none elsewhere.
    float submerged = 0.0;
    float biomePool = 0.0, biomeDamp = 0.0;
    // Cliffs grade continuously with height; the board's plinth stops darkening a little below ground.
    if (cliff) level = max(v_cloudPosition.z / u_levelHeight, -1.5);
    if (u_clay < .5) {
        vec4 detail = vec4(0.0, 0.0, 1.0, 1.0);
        if (natural) {
#ifndef terrainBlendFlag
            float foot = shore || cliff ? v_diffuseUV.x : v_diffuseUV.y;
            float rim = shore || cliff ? v_diffuseUV.y : v_diffuseUV.x;
            float rock = ground ? rockiness(steps) : v_color.g;
            TerrainMaterial material = naturalMaterial(materialWorld, face, foot, rim, rock, ground ? .5 : v_color.a,
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
            if (!shore) level = max(v_cloudPosition.z / u_levelHeight, -1.5);
        } else if (ground) {
            // A water hex's steep banks take their maps from the side, as walls do, so nothing stretches downhill.
            bool steep = shore && face.z < .6;
            vec2 q = !steep ? p : abs(face.x) > abs(face.y) ? vec2(world.y * sign(face.x), -world.z)
                  : vec2(-world.x * sign(face.y), -world.z);
            vec4 top = planar(u_groundColor, q, u_sculptTiles.x, broad);
            vec4 rubble = planar(u_debrisColor, q, u_sculptTiles.y, fine);
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
            if (u_normalMaps > .5 && !steep) {
                detail = mix(planarNormal(u_groundNormal, p, u_sculptTiles.x, broad),
                      planarNormal(u_debrisNormal, p, u_sculptTiles.y, fine), w);
            }
            albedo = groundTone(albedo, world, broad, fine, region, rim, foot);
        } else if (plant) {
            // Leafy mottling on each mass, browner and darker toward the root.
            float leaf = texture(u_rainNoise, world.xy / 7.0 + world.z * .13).r;
            albedo = plantColor(v_color.a) * mix(.78, 1.16, leaf) * mix(.7, 1.0, smoothstep(0.0, .5, v_diffuseUV.x));
        } else if (pit) {
            // A tree pit in the pavement: earth and bark mulch inside a kerb of paler, cleaner stone.
            if (v_color.a > .5) {
                albedo = planar(u_groundColor, p, u_sculptTiles.x, broad).rgb * 1.1;
            } else {
                vec4 grit = planar(u_debrisColor, p, u_sculptTiles.y * .6, fine);
                float mulch = texture(u_rainNoise, world.xy / 9.0 + v_color.a).r;
                albedo = mix(vec3(.27, .19, .13), vec3(.42, .30, .19), mulch) * mix(.8, 1.2, grit.a);
                if (u_normalMaps > .5) normal = upNormal(planarNormal(u_debrisNormal, p, u_sculptTiles.y * .6, fine).rgb, face);
            }
        } else {
            // Walls and rocks: the two vertical projections, V running down the face, never mirrored from outside.
            // Where a rounded corner turns between them, the projection whose relief stands higher shows through.
            vec3 axes = pow(abs(face), vec3(4.0));
            vec2 uvx, uvy;
            float side;
            vec4 wall = wallSample(world, face, uvx, uvy, side);
            // A boulder's crown needs a horizontal stone projection: the two wall projections collapse on a
            // flat top, otherwise leaving a single colour or stretched stripes under the moss/snow treatment.
            float crown = cliff ? 0.0 : smoothstep(.45, .85, face.z);
            if (crown > 0.0) wall = mix(wall, planar(u_wallColor, p, u_sculptTiles.z, fine), crown);
            albedo = wall.rgb;
            if (u_normalMaps > .5) {
                normal = wallNormal(u_wallNormal, uvx, uvy, face, side);
                cavity = mix(texture(u_wallNormal, uvy).a, texture(u_wallNormal, uvx).a, side) * .5 + .5;
                if (crown > 0.0) {
                    vec4 top = planarNormal(u_wallNormal, p, u_sculptTiles.z, fine);
                    normal = normalize(mix(normal, upNormal(top.rgb, face), crown));
                    cavity = mix(cavity, top.a * .5 + .5, crown);
                }
            }
            float h = v_diffuseUV.x, d = v_diffuseUV.y;
            if (cliff) {
                float drop = h + d;
                float rock = v_color.g;
                albedo *= bedTint(v_color.a);
                // Caprock: the hard top bed of a cliff weathers paler; varnish streaks run down from under it.
                float cap = 1.0 - smoothstep(.8, 2.2, d);
                albedo = mix(albedo, albedo * vec3(1.08, 1.06, 1.03), cap * .6);
                // Under a concrete slab the streaks run down from its underside.
                float below = family(4.0) ? d - u_levelHeight / u_metre : d;
                float streak = smoothstep(.55, .85, texture(u_rainNoise, vec2((world.x + world.y) / 7.0, world.z / 90.0)).r)
                      * (1.0 - smoothstep(2.0, 14.0, below)) * smoothstep(.5, 1.5, below);
                if (family(2.0) || family(3.0) || family(4.0)) albedo = mix(albedo, albedo * vec3(.52, .45, .42), streak * .6);

                // Talus: fallen rock on the apron at the foot, where the face lies back. A mantle's banks slump into
                // soil and turf instead; concrete walls stand clean, and only the bedrock under a slab has talus.
                float talus = min(.3 * drop, 6.0) * mix(.6, 1.0, fine);
                float apron = (1.0 - smoothstep(talus * .55, talus, h)) * smoothstep(.2, .5, face.z);
                apron = family(4.0) ? apron * rock : max(apron, 1.0 - smoothstep(.2, .6, h));
                if (apron > 0.0) {
                    float lying = smoothstep(.45, .8, face.z);
                    vec2 dx = vec2(world.y * sign(face.x), -world.z), dy = vec2(-world.x * sign(face.y), -world.z) + 3.1;
                    vec4 rubble = draped(u_debrisColor, p, dx, dy, side, u_sculptTiles.y, fine, lying);
                    if (family(4.0)) rubble.rgb *= bedTint(.5);
                    // A desert talus is the cliff's own sandstone, broken: redder and darker than the drifted sand.
                    if (family(2.0)) rubble.rgb = mix(rubble.rgb, wall.rgb * .92, .5);
                    float w = heightBlend(wall.a, rubble.a, apron);
                    albedo = mix(albedo, rubble.rgb, w);
                    if (u_normalMaps > .5) {
                        vec3 rubbleNormal = drapedNormal(u_debrisNormal, p, dx, dy, face, side, u_sculptTiles.y, fine, lying);
                        normal = normalize(mix(normal, rubbleNormal, w));
                    }
                }
                if (family(4.0)) {
                    concreteSlab(world, face, h, d, rock, albedo, normal, occlusion, cavity);
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
                vec4 top = planar(u_groundColor, p, u_sculptTiles.x, broad);
                float w = heightBlend(wall.a, top.a, ledge);
                albedo = mix(albedo, groundTone(top.rgb, world, broad, fine, region, 0.0, 0.0), w);
                if (u_normalMaps > .5) {
                    normal = normalize(mix(normal, upNormal(planarNormal(u_groundNormal, p, u_sculptTiles.x, broad).rgb, face), w));
                }
            }
            if (!cliff) {
                // Each block its own shade; on alpine meadows a thin moss stain keeps the stone texture visible. Rubble below a
                // concrete slab is the bedrock's.
                if (!wetRock) albedo *= mix(.78, 1.0, v_color.a);
                if (family(4.0)) albedo *= bedTint(.5);
                if (family(0.0)) {
                    float moss = smoothstep(.7, .94, face.z) * smoothstep(.6, .8, fine);
                    albedo *= mix(vec3(1.0), vec3(.9, .97, .82), moss * .22);
                }
            }
        }
        if (ground && !natural && u_normalMaps > .5) {
            normal = upNormal(detail.rgb, face);
            cavity = detail.a * .6 + .4;
        }
        if (ground && !shore && family(4.0)) {
            occlusion = 1.0 - .3 * exp(-v_diffuseUV.y / 3.5);
        }
        if (shore && !natural) {
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
                if (u_normalMaps > .5) {
                    normal = normalize(mix(normal, wallNormal(u_wallNormal, uvx, uvy, face, side), rock));
                    float relief = mix(texture(u_wallNormal, uvy).a, texture(u_wallNormal, uvx).a, side);
                    cavity = mix(cavity, relief * .5 + .5, rock);
                }
            }
        }
#ifdef terrainBlendFlag
        if (ground || cliff) {
            float foot = shore || cliff ? v_diffuseUV.x : v_diffuseUV.y;
            float rim = shore || cliff ? v_diffuseUV.y : v_diffuseUV.x;
            float rock = ground ? rockiness(steps) : v_color.g;
            // A bank blend must use sediment below the waterline, never repaint the bed with neighbouring turf.
            blendCovers(materialWorld, face, foot, rim, rock, ground ? .5 : v_color.a, sediment,
                  broad, fine, region, albedo, normal, cavity, materialHeight, grass, bounce, response, rainCover,
                  emission, roughness, volcanic);
        }
#endif
        if (natural) {
            biomeSurface(world, face, shore, above, cliff ? v_diffuseUV.x : 0.0,
                  cliff ? v_diffuseUV.y : v_diffuseUV.x, materialHeight,
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
        albedo = mix(levelGrade(albedo, level), albedo, volcanic);
        if (waterCovered) {
            // Wet in a band just above the waterline and below it; beneath it the bed keeps the hue its column of
            // water passes and catches caustics (water-optics.glsl), while the surface above removes the brightness
            // the column absorbs.
            albedo *= 1.0 - .25 * (1.0 - smoothstep(0.0, .12, above)) * (1.0 - smoothstep(0.0, .20, biomeDamp));
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
    float exposed = waterCovered ? 1.0 - smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) : 1.0;
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
    vec3 ambient = skyLight(normal, bounce) * occlusion * cavity;
    vec3 direct = vec3(0.0), sheen = vec3(0.0);
#if numDirectionalLights > 0
    vec3 light = -u_dirLights[0].direction;
    // Foliage scatters light around its masses: a wrapped response instead of a hard terminator.
    float incidence = plant ? max(0.0, dot(normal, light) * .6 + .4) : max(0.0, dot(normal, light));
    vec3 sun = u_dirLights[0].color * sculptShadow(face, light);
    direct = sun * incidence * mix(1.0, cavity, .5) * (1.0 + caustic);
    if (film > 0.0 && incidence > 0.0) {
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
    fragColor = vec4(result, 1.0);
}
