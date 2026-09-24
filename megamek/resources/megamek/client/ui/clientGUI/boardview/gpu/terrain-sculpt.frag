// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Sculpted tops, cliffs and the rock kit. Geometry supplies the landforms; this shader layers each surface family's
// four materials (ground, debris, wall, mantle), mapped in world space, and lights them in linear space. It writes
// display space like every other scene shader, so the atmosphere composite converts, exposes and encodes the frame once.
#ifdef GL_ES
precision highp float;
#endif
varying vec2 v_diffuseUV;   // ground: rim / foot distance; cliff: height above foot / depth below rim; rock: above root
                            // / below top. All in metres.
varying vec3 v_normal;
varying vec4 v_color;       // r: occlusion. g: game level (+64)/255, or for cliffs how much rock the face is (0
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

// The second repeat of every map is 2.37 times larger and turned by 34 degrees.
const mat2 TURN = mat2(.8253, .5646, -.5646, .8253);


// Per-level identity, in image-editor units: hue degrees, saturation/lightness/contrast percent per level.
vec3 levelGrade(vec3 c, float level) {
    float up = max(level, 0.0), down = max(-level, 0.0);
    float hue = -1.0 * up + 6.0 * down;
    float saturation = 2.0 * up;
    float lightness = 3.0 * up - 6.0 * down;
    float contrast = 3.0 * up;
    // Hue rotation in YIQ keeps luminance.
    float angle = radians(hue);
    vec3 yiq = vec3(dot(c, vec3(.299, .587, .114)), dot(c, vec3(.596, -.274, -.322)), dot(c, vec3(.211, -.523, .312)));
    float s = sin(angle), k = cos(angle);
    yiq.yz = vec2(yiq.y * k - yiq.z * s, yiq.y * s + yiq.z * k);
    c = vec3(dot(yiq, vec3(1.0, .956, .621)), dot(yiq, vec3(1.0, -.272, -.647)), dot(yiq, vec3(1.0, -1.106, 1.703)));
    float luma = dot(c, vec3(.299, .587, .114));
    c = mix(vec3(luma), c, 1.0 + saturation / 100.0);
    c = lightness >= 0.0 ? c + (1.0 - c) * lightness / 100.0 : c * (1.0 + lightness / 100.0);
    c = (c - .5) * (1.0 + contrast / 100.0) + .5;
    return clamp(c, 0.0, 1.0);
}

// ---- Material sampling -------------------------------------------------------------------------------------------

// Height-based blend: where both layers are wanted, the one whose relief stands higher shows.
float heightBlend(float lower, float upper, float want) {
    float a = lower + 1.0 - want, b = upper + want;
    float top = max(a, b) - .2;
    float wa = max(a - top, 0.0), wb = max(b - top, 0.0);
    return wb / (wa + wb);
}

// How far ground detail has faded towards its average colour: from about 18 cm per pixel, where a map's repeats
// would start to beat against each other, to 60 cm; a fifth of the detail remains. Set once per fragment in main().
float farDetail;

// A map on the ground plane at two repeats, mixed by a broad field, so no period shows. p is in metres with +V
// pointing to world -Y, as the maps are authored. From afar it settles to the map's average (its last mip level).
vec4 planar(sampler2D map, vec2 p, float tile, float mixer) {
    vec4 near = mix(texture2D(map, p / tile), texture2D(map, TURN * p / (tile * 2.37) + .31), mixer);
    return farDetail > 0.0 ? mix(near, texture2D(map, p / tile, 12.0), farDetail) : near;
}

// The matching tangent-space normal (x along +U, y along +V); the turned sample is turned back.
vec4 planarNormal(sampler2D map, vec2 p, float tile, float mixer) {
    vec4 near = texture2D(map, p / tile);
    vec4 far = texture2D(map, TURN * p / (tile * 2.37) + .31);
    vec3 a = near.rgb * 2.0 - 1.0, b = far.rgb * 2.0 - 1.0;
    b.xy = b.xy * TURN;
    vec4 result = vec4(mix(a, b, mixer), mix(near.a, far.a, mixer));
    return mix(result, vec4(0.0, 0.0, 1.0, result.a), farDetail);
}

// Tangent-space detail on a surface facing up: U is world +X, V is world -Y.
vec3 upNormal(vec3 detail, vec3 face) {
    return normalize(vec3(detail.x + face.x, face.y - detail.y, detail.z * face.z));
}

// A wall map's two vertical projections (U along the face, V down it), whiteout-blended with the face normal and
// expressed in world space. side weights the projection onto the YZ plane.
vec3 wallNormal(sampler2D map, vec2 uvx, vec2 uvy, vec3 face, float side) {
    vec3 nx = texture2D(map, uvx).rgb * 2.0 - 1.0, ny = texture2D(map, uvy).rgb * 2.0 - 1.0;
    vec3 wx = vec3(nx.z * face.x, nx.x * sign(face.x) + face.y, face.z - nx.y);
    vec3 wy = vec3(-ny.x * sign(face.y) + face.x, ny.z * face.y, face.z - ny.y);
    return normalize(mix(wy, wx, side));
}

// A ground or debris map on a sloping face: seen from above where the face lies back (lying 1), and from the side like
// the wall maps where it is steep, so it never stretches down the slope. dx, dy are the side coordinates in metres.
vec4 draped(sampler2D map, vec2 p, vec2 dx, vec2 dy, float side, float tile, float mixer, float lying) {
    vec4 steep = mix(texture2D(map, dy / tile), texture2D(map, dx / tile), side);
    return mix(steep, planar(map, p, tile, mixer), lying);
}

vec3 drapedNormal(sampler2D map, vec2 p, vec2 dx, vec2 dy, vec3 face, float side, float tile, float mixer, float lying) {
    vec3 steep = wallNormal(map, dx / tile, dy / tile, face, side);
    return normalize(mix(steep, upNormal(planarNormal(map, p, tile, mixer).rgb, face), lying));
}

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
vec3 groundBounce() {
    if (family(0.0)) return vec3(.10, .16, .06);
    if (family(1.0)) return vec3(.22, .15, .10);
    if (family(2.0)) return vec3(.42, .28, .16);
    if (family(3.0)) return vec3(.22, .21, .19);
    if (family(4.0)) return vec3(.30, .29, .27);
    return vec3(.75, .78, .82);
}

// Bed colour around the wall map's own tint: hard beds pale, soft beds deeper.
vec3 bedTint(float hardness) {
    if (family(2.0)) return mix(vec3(.90, .86, .83), vec3(1.06, 1.03, 1.0), hardness);
    if (family(1.0)) return mix(vec3(.86, .80, .74), vec3(1.08, 1.04, 1.0), hardness);
    // The bedrock under a concrete slab: darker than the pale concrete it carries.
    if (family(4.0)) return mix(vec3(.62, .61, .59), vec3(.80, .78, .75), hardness);
    return mix(vec3(.90, .91, .93), vec3(1.06, 1.04, 1.01), hardness);
}

// Broad variations in the ground's tone, so a large field reads neither as one flat colour nor as tiles: patches, the
// desert's iron-red thin sand, pale washes and flats and its dunes, a meadow's dry and lush turf. rim and foot weigh the
// nearness of a drop and of a rise. The ground and the cover drifted onto slopes and ledges share it, so they match.
vec3 groundTone(vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    albedo *= mix(.94, 1.06, broad) * mix(.95, 1.05, region) * mix(.96, 1.04, fine);
    if (family(2.0)) {
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
    if (family(0.0)) {
        // Thin, dry turf on convex rims and in sunny patches; lush, dark grass where water gathers below cliffs.
        float dry = max(smoothstep(.55, .85, broad * .7 + fine * .3) * .5, rim * .6);
        albedo = mix(albedo, albedo * vec3(1.25, 1.12, .7), dry);
        albedo = mix(albedo, albedo * vec3(.78, .95, .82), max(foot * .6, (1.0 - smoothstep(.2, .45, broad)) * .4));
    }
    return albedo;
}

// The surface length one pixel covers, in metres. Set once per fragment in main().
float pixelMetres;

float hash(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }

// Darkening of a joint of half-width w at distance x (metres) from its centre line. A joint narrower than a pixel
// darkens it only by the share it covers, so distant joints neither vanish nor flicker.
float joint(float x, float w) {
    return (1.0 - smoothstep(w - .5 * pixelMetres, w + .5 * pixelMetres, x)) * min(1.0, 2.0 * w / pixelMetres);
}

// Defined after main(): it uses declarations the renderer inserts before main().
vec2 slab(float u, float z, float h, float d, float projection, bool single, out float shade);

void main() {
    vec3 face = normalize(v_normal);
    float occlusion = v_color.r;
    float level = floor(v_color.g * 255.0 + .5) - 64.0;
    float kind = v_color.b;
    bool ground = kind < .125, plant = kind >= .125 && kind < .375, cliff = kind >= .375 && kind < .625;
    bool pit = kind >= .625 && kind < .875;
    bool shore = ground && v_color.a < .25;
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
    float depth = shore ? (level * u_levelHeight - v_cloudPosition.z) / u_metre : -1.0;
    float above = shore ? u_waterLine / u_metre - depth : 99.0;
    vec2 p = vec2(world.x, -world.y);
    if (shore) {
        // The rippling surface bends the view of a submerged bed, so its detail sways with the swell above
        // (water-surface.frag), turned downwind and drifting like it.
        vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
        mat2 downwind = mat2(wind.x, -wind.y, wind.y, wind.x);
        vec2 swell = (texture2D(u_waterDetail, downwind * v_cloudPosition.xy * u_rainScale * 1.65
              + vec2(u_rainTime * .018, 0.0)).rg - .5) * downwind;
        float under = smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) * min(depth, 2.0);
        p += vec2(swell.x, -swell.y) * (under * .3 * u_waterEffects * u_rainDetail);
    }
    // Smooth value fields from the shared 64-texel noise: broad has ~11 m cells, fine ~2.5 m, and region ~40 m.
    float broad = texture2D(u_rainNoise, world.xy / 700.0).g * .6 + texture2D(u_rainNoise, world.xy / 430.0 + .19).b * .4;
    float fine = texture2D(u_rainNoise, world.xy / 160.0 + .41).b;
    float region = texture2D(u_rainNoise, world.xy / 2600.0 + .73).r;
    vec3 albedo = vec3(.52);
    vec3 normal = face;
    float cavity = 1.0;
    float caustic = 0.0;
    // Levels of water over a submerged bed; none elsewhere.
    float submerged = 0.0;
    // Cliffs grade continuously with height; the board's plinth stops darkening a little below ground.
    if (cliff) level = max(v_cloudPosition.z / u_levelHeight, -1.5);
    if (u_clay < .5) {
        vec4 detail = vec4(0.0, 0.0, 1.0, 1.0);
        if (ground) {
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
            float leaf = texture2D(u_rainNoise, world.xy / 7.0 + world.z * .13).r;
            albedo = plantColor(v_color.a) * mix(.78, 1.16, leaf) * mix(.7, 1.0, smoothstep(0.0, .5, v_diffuseUV.x));
        } else if (pit) {
            // A tree pit in the pavement: earth and bark mulch inside a kerb of paler, cleaner stone.
            if (v_color.a > .5) {
                albedo = planar(u_groundColor, p, u_sculptTiles.x, broad).rgb * 1.1;
            } else {
                vec4 grit = planar(u_debrisColor, p, u_sculptTiles.y * .6, fine);
                float mulch = texture2D(u_rainNoise, world.xy / 9.0 + v_color.a).r;
                albedo = mix(vec3(.27, .19, .13), vec3(.42, .30, .19), mulch) * mix(.8, 1.2, grit.a);
                if (u_normalMaps > .5) normal = upNormal(planarNormal(u_debrisNormal, p, u_sculptTiles.y * .6, fine).rgb, face);
            }
        } else {
            // Walls and rocks: the two vertical projections, V running down the face, never mirrored from outside.
            // Where a rounded corner turns between them, the projection whose relief stands higher shows through.
            vec3 axes = pow(abs(face), vec3(4.0));
            float t = u_sculptTiles.z;
            vec2 uvx = vec2(world.y * sign(face.x), -world.z) / t;
            vec2 uvy = vec2(-world.x * sign(face.y), -world.z) / t + .37;
            vec4 wallX = texture2D(u_wallColor, uvx), wallY = texture2D(u_wallColor, uvy);
            float side = clamp((axes.x / max(axes.x + axes.y, 1e-4) - .5) * 3.0 + (wallX.a - wallY.a) * 1.2 + .5, 0.0, 1.0);
            vec4 wall = mix(wallY, wallX, side);
            albedo = wall.rgb;
            if (u_normalMaps > .5) {
                normal = wallNormal(u_wallNormal, uvx, uvy, face, side);
                cavity = mix(texture2D(u_wallNormal, uvy).a, texture2D(u_wallNormal, uvx).a, side) * .5 + .5;
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
                float streak = smoothstep(.55, .85, texture2D(u_rainNoise, vec2((world.x + world.y) / 7.0, world.z / 90.0)).r)
                      * (1.0 - smoothstep(2.0, 14.0, below)) * smoothstep(.5, 1.5, below);
                if (family(2.0) || family(3.0) || family(4.0)) albedo = mix(albedo, albedo * vec3(.52, .45, .42), streak * .6);
                if (mantled()) {
                    // The mantle buries low steps entirely and caps rock cliffs one to three metres deep; bedrock breaks
                    // through where its own relief stands high. Its horizons follow the depth below the rim.
                    float mantleDepth = mix(drop + 2.0, 1.0 + 2.0 * fine, rock);
                    float want = 1.0 - smoothstep(mantleDepth - 1.2, mantleDepth + .4, d);
                    float tm = u_sculptTiles.w;
                    vec2 mx = vec2(world.y * sign(face.x), d) / tm, my = vec2(-world.x * sign(face.y), d) / tm + .37;
                    vec4 cover = mix(texture2D(u_mantleColor, my), texture2D(u_mantleColor, mx), side);
                    float w = heightBlend(wall.a, cover.a, want);
                    albedo = mix(albedo, cover.rgb, w);
                    wall.a = mix(wall.a, cover.a, w);
                    if (u_normalMaps > .5) normal = normalize(mix(normal, wallNormal(u_mantleNormal, mx, my, face, side), w));
                    if (family(0.0)) {
                        // Turf hangs over the lip and grows down an earth bank in patches, thickest where the bank
                        // lies back and near its foot; open scars remain where it slumps. The patches vary over the
                        // face itself, not only over the map.
                        vec2 across = vec2(world.x * .7 + world.y * .7, world.z * 1.6) / 380.0;
                        float face2 = texture2D(u_rainNoise, across).g * .7 + texture2D(u_rainNoise, across * 3.1 + .3).b * .3;
                        float sod = smoothstep(.6, .78, face2) * .8 + (1.0 - smoothstep(.2, 1.6, h)) * .7
                              + (1.0 - smoothstep(.15, .6, d)) + smoothstep(.5, .8, face.z) * .6;
                        vec4 turf = planar(u_groundColor, p, u_sculptTiles.x, broad);
                        float g = heightBlend(cover.a, turf.a, clamp(sod, 0.0, 1.0) * w);
                        albedo = mix(albedo, turf.rgb * vec3(.85, .9, .8), g);
                    }
                }
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
                    vec3 rubbleNormal = drapedNormal(u_debrisNormal, p, dx, dy, face, side, u_sculptTiles.y, fine, lying);
                    if (mantled() && rock < 1.0) {
                        vec4 turf = draped(u_groundColor, p, dx, dy, side, u_sculptTiles.x, broad, lying);
                        if (family(0.0)) turf.rgb = worn(turf.rgb, turf.a, .7);
                        rubble = mix(turf, rubble, rock);
                        rubbleNormal = normalize(mix(drapedNormal(u_groundNormal, p, dx, dy, face, side, u_sculptTiles.x,
                              broad, lying), rubbleNormal, rock));
                    }
                    float w = heightBlend(wall.a, rubble.a, apron);
                    albedo = mix(albedo, rubble.rgb, w);
                    if (u_normalMaps > .5) normal = normalize(mix(normal, rubbleNormal, w));
                }
                // Hex transitions and padding lay steps of up to two levels back into slopes. Only where a slope lies
                // almost flat does the ground's own cover grow or drift over it (turf, sand, snow, soil); the face and
                // its crest stay bare, so a step reads against the ground above and below it.
                // At its foot a slope becomes the ground it meets: wholly covered, mapped from above, toned and
                // relieved like that ground, so no seam shows there.
                float toe = (1.0 - smoothstep(0.0, .8, h)) * (1.0 - rock);
                float lie = max(smoothstep(.62, .9, face.z) * (1.0 - rock) * smoothstep(.4, 1.6, d), toe);
                if (lie > 0.0) {
                    float lying = max(smoothstep(.45, .8, face.z), toe);
                    vec2 dx = vec2(world.y * sign(face.x), -world.z), dy = vec2(-world.x * sign(face.y), -world.z) + 3.1;
                    vec4 cover = draped(u_groundColor, p, dx, dy, side, u_sculptTiles.x, broad, lying);
                    float w = max(heightBlend(wall.a, cover.a, lie), toe);
                    albedo = mix(albedo, groundTone(cover.rgb, world, broad, fine, region, 0.0, 0.0), w);
                    if (u_normalMaps > .5) {
                        normal = normalize(mix(normal, drapedNormal(u_groundNormal, p, dx, dy, face, side,
                              u_sculptTiles.x, broad, lying), w));
                        float relief = planarNormal(u_groundNormal, p, u_sculptTiles.x, broad).a;
                        cavity = mix(cavity, relief * .6 + .4, w * lying);
                    }
                }
                if (family(4.0)) {
                    // Cast concrete: the whole face of a step of up to two levels, and from three levels the slab of
                    // the top level (its underside included) above the bedrock that carries it.
                    bool single = rock > .5;
                    float poured = 1.0 - smoothstep(-.02, .02, d - (single ? u_levelHeight / u_metre + .12 : drop + 1.0));
                    if (poured > 0.0) {
                        float along = clamp((axes.x / max(axes.x + axes.y, 1e-4) - .5) * 6.0 + .5, 0.0, 1.0);
                        float shadeX, shadeY;
                        vec2 sx = slab(world.y * sign(face.x), world.z, h, d, 1.0, single, shadeX);
                        vec2 sy = slab(-world.x * sign(face.y), world.z, h, d, 0.0, single, shadeY);
                        vec3 concrete = mix(texture2D(u_mantleColor, sy).rgb * shadeY,
                              texture2D(u_mantleColor, sx).rgb * shadeX, along);
                        albedo = mix(albedo, concrete, poured);
                        if (u_normalMaps > .5) {
                            normal = normalize(mix(normal, wallNormal(u_mantleNormal, sx, sy, face, along), poured));
                            float pores = mix(texture2D(u_mantleNormal, sy).a, texture2D(u_mantleNormal, sx).a, along);
                            cavity = mix(cavity, pores * .5 + .5, poured);
                        }
                    }
                }
            }
            // Broad ledges collect the ground cover (sand, snow, moss on alpine rock); on the rock kit only snow and
            // sand dust lie.
            float ledge = smoothstep(.8, .95, face.z) * smoothstep(.3, .6, fine) * (family(0.0) ? .7 : 1.0);
            if (family(5.0)) ledge = smoothstep(.5, .72, face.z);
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
                // Each block its own shade; on alpine meadows moss and turf settle on their tops. Rubble below a
                // concrete slab is the bedrock's.
                albedo *= mix(.78, 1.0, v_color.a);
                if (family(4.0)) albedo *= bedTint(.5);
                if (family(0.0)) {
                    float moss = smoothstep(.55, .85, face.z) * smoothstep(.35, .65, fine);
                    albedo = mix(albedo, vec3(.24, .30, .14), moss * .8);
                }
            }
        }
        if (ground && u_normalMaps > .5) {
            normal = upNormal(detail.rgb, face);
            cavity = detail.a * .6 + .4;
        }
        if (ground && family(0.0) && !shore && u_wind.z > 0.0) {
            // Gusts roll across a meadow: the grass leans with them and catches the light differently.
            vec2 gust = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(1.0, 0.0);
            float sway = sin(dot(world.xy, gust) * .45 - u_rainTime * 1.9 + fine * 6.0)
                  * sin(dot(world.xy, vec2(-gust.y, gust.x)) * .21 - u_rainTime * .7);
            albedo *= 1.0 + sway * .05 * u_wind.z;
            normal = normalize(normal + vec3(gust * sway * .12 * u_wind.z, 0.0));
        }
        albedo = levelGrade(albedo, level);
        if (shore) {
            // Wet in a band just above the waterline and below it; beneath it the bed keeps the hue its column of
            // water passes and catches caustics (water-optics.glsl), while the surface above removes the brightness
            // the column absorbs.
            albedo *= mix(1.0, .75, 1.0 - smoothstep(0.0, .12, above));
            submerged = max(0.0, depth * u_metre - u_waterLine) / u_levelHeight;
            albedo *= waterBedTint(palette, submerged);
            caustic = waterBedCaustics(v_cloudPosition.xy * u_rainScale, submerged) * u_rainDetail * u_waterEffects;
        }
    }
    // 1 above the water, 0 on a submerged bed: the water surface draws the grid and takes the rain for it.
    float exposed = shore ? 1.0 - smoothstep(0.0, 1.0, depth * u_metre - u_waterLine) : 1.0;
    if (ground) albedo *= mix(1.0, terrainGrid(v_cloudPosition.xy * u_rainScale), exposed);
    // Rain darkens exposed ground and rock, and gathers in puddles on level ground.
    float wet = u_wetness * step(0.0, u_groundResponse) * exposed;
    float film = wet * max(0.0, u_groundResponse);
    float puddle = 0.0;
    if (ground && wet * u_rainDetail > 0.0 && face.z > .97) {
        vec2 position = v_cloudPosition.xy * u_rainScale;
        puddle = rainPuddle(position, wet, max(0.0, u_groundResponse)) * smoothstep(.97, .999, face.z) * u_rainDetail;
        normal = normalize(mix(normal, normalize(vec3(rainRipples(position), 1.0)), puddle));
        film = mix(film, 1.0, puddle);
    }
    albedo *= 1.0 - wet * mix(.18, .11, max(0.0, u_groundResponse));
    albedo = toLinear(albedo);
#ifdef lightingFlag
    vec3 ambient = skyLight(normal) * occlusion * cavity;
    vec3 direct = vec3(0.0), sheen = vec3(0.0);
#if numDirectionalLights > 0
    vec3 light = -u_dirLights[0].direction;
    // Foliage scatters light around its masses: a wrapped response instead of a hard terminator.
    float incidence = plant ? max(0.0, dot(normal, light) * .6 + .4) : max(0.0, dot(normal, light));
    vec3 sunColor = sunLight();
    // Light reflected from the sunlit ground below reaches walls and overhangs, warm in the desert, white on snow.
    ambient += sunColor * max(0.0, light.z) * groundBounce() * (.5 - .5 * normal.z) * occlusion;
    vec3 sun = sunColor * sculptShadow(face, light);
    direct = sun * incidence * mix(1.0, cavity, .5) * (1.0 + caustic);
    if (film > 0.0 && incidence > 0.0) {
        vec3 halfVector = normalize(light - u_viewDirection);
        float exponent = mix(12.0, 96.0, film);
        float fresnel = .02 + .98 * pow(1.0 - max(0.0, dot(-u_viewDirection, halfVector)), 5.0);
        sheen = sun * incidence * film * fresnel * pow(max(0.0, dot(normal, halfVector)), exponent) * (exponent + 2.0) / 8.0;
    }
#endif
    // Cloud shadows attenuate direct light and sheen here (inserted by GpuCloudShadow).
    vec3 pigment = albedo;
    albedo *= ambient + direct;
    albedo += sheen;
    if (submerged > 0.0) {
#if numDirectionalLights > 0
        vec3 scattered = skyLight(vec3(0.0, 0.0, 1.0)) * occlusion + sunColor * max(0.0, light.z) * (1.0 + caustic);
#else
        vec3 scattered = skyLight(vec3(0.0, 0.0, 1.0)) * occlusion;
#endif
        albedo = submergedLight(albedo, pigment, scattered, submerged);
    }
    albedo = shoulder(albedo);
#endif
    vec3 result = toDisplay(albedo);
    if (puddle > 0.0) result = rainReflection(result, normal, puddle);
    gl_FragColor = vec4(result, 1.0);
}

// Cast concrete comes in slabs. On walls of up to two levels they stand in courses one level tall with staggered
// joints; from three levels one slab spans the top level, jointed every 12 m. Each slab samples its own window of the
// map and takes its own tone and grime, so the map's repeat never shows. u runs along the face and z up it; h and d
// are the height above the wall's foot and the depth below its rim, all in metres. Returns the map coordinates (V down
// the face) and writes the slab's colour factor, joints included, to shade.
vec2 slab(float u, float z, float h, float d, float projection, bool single, out float shade) {
    float levelMetres = u_levelHeight / u_metre;
    float course = floor(z / levelMetres + .001);
    float up = z / levelMetres + .001 - course;
    float width = single ? 12.0 : 4.8;
    float along = u / width + (single ? 0.0 : .5 * mod(course, 2.0)) + projection * .37;
    vec3 id = vec3(floor(along), course, projection);
    vec2 window = vec2(hash(id), hash(id + 17.3));
    // Each slab's own tone, grime settling toward its foot and run-off stains hanging from its top edge.
    shade = mix(.9, 1.07, hash(id + 5.1)) * mix(.84, 1.0, smoothstep(0.0, .3, up));
    float runoff = smoothstep(.55, .85, texture2D(u_rainNoise, vec2(u / 3.1 + window.x * 7.0, z / 45.0)).r);
    shade *= 1.0 - .14 * runoff * smoothstep(.45, 1.0, up);
    float seam = joint(min(fract(along), 1.0 - fract(along)) * width, .03);
    if (!single) seam = max(seam, joint(min(up, 1.0 - up) * levelMetres, .03) * smoothstep(.1, .3, d) * smoothstep(.1, .3, h));
    shade *= 1.0 - .55 * seam;
    return vec2(u, -z) / u_sculptTiles.w + window;
}
