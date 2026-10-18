// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Surface-only treatment: reeds/crops stand on this same terrain; these small wet depressions do not change rules.
// Earth shares the already-bound terrain array; projection and metre scale are unchanged.
uniform float u_biomeSoilLayer;
uniform float u_biomeSoilTile;

// Explicit projection gradients keep texture mip selection valid at wetland/field edges and between slope modes.
vec4 biomeTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
    return textureGrad(maps, vec3(uv, layer), gradient[0], gradient[1]);
}
vec4 biomeTexel(float layer, vec2 uv, mat2 gradient) {
    return biomeTexel(u_terrainLayers, layer * .5, uv, gradient);
}
vec4 biomePlanar(sampler2DArray maps, float map, MaterialProjection p, float mixer, bool normalMap) {
    float tile = u_biomeSoilTile;
    vec4 near = biomeTexel(maps, map, p.top / tile, p.topGradient / tile);
    // Relief: the first repeat only.
    if (normalMap) return vec4(mix(near.rgb * 2.0 - 1.0, vec3(0.0, 0.0, 1.0), farDetail), near.a);
    vec4 far = biomeTexel(maps, map, TURN * p.top / (tile * 2.37) + .31, TURN * p.topGradient / (tile * 2.37));
    vec4 pigment = mix(near, far, mixer);
    return farDetail > 0.0 ? mix(pigment,
          biomeTexel(maps, map, p.top / tile, p.topGradient * (4096.0 / tile)), farDetail) : pigment;
}
vec4 biomePlanar(float map, MaterialProjection p, float mixer, bool normalMap) {
    if (normalMap) return biomePlanar(u_terrainData, (map - 1.0) * .5, p, mixer, true);
    return biomePlanar(u_terrainLayers, map * .5, p, mixer, false);
}
vec4 biomeSoil(MaterialProjection p, float mixer) {
    if (p.lying >= 1.0) return biomePlanar(u_biomeSoilLayer, p, mixer, false);
    float tile = u_biomeSoilTile;
    vec4 steep = mix(biomeTexel(u_biomeSoilLayer, p.y / tile, p.yGradient / tile),
          biomeTexel(u_biomeSoilLayer, p.x / tile, p.xGradient / tile), p.side);
    return p.lying <= 0.0 ? steep : mix(steep, biomePlanar(u_biomeSoilLayer, p, mixer, false), p.lying);
}
vec3 biomeSoilNormal(MaterialProjection p, vec3 face, float mixer) {
    if (p.lying >= 1.0) return upNormal(biomePlanar(u_biomeSoilLayer + 1.0, p, mixer, true).rgb, face);
    float tile = u_biomeSoilTile;
    vec3 steep = wallNormal(biomeTexel(u_terrainData, u_biomeSoilLayer * .5, p.x / tile, p.xGradient / tile),
          biomeTexel(u_terrainData, u_biomeSoilLayer * .5, p.y / tile, p.yGradient / tile), face, p.side);
    if (p.lying <= 0.0) return normalize(steep);
    return normalize(mix(steep, upNormal(biomePlanar(u_biomeSoilLayer + 1.0, p, mixer, true).rgb, face), p.lying));
}

void biomeSurface(vec3 world, vec3 face, bool shore, float above, float foot, float rim, float materialHeight, float sand,
      inout vec3 color, inout vec3 normal, inout float cavity, inout float roughness,
      inout float grass, inout vec3 bounce, out float pool, out float damp) {
    pool = 0.0; damp = 0.0;
    // Capture every quad derivative before any spatial return/branch. Projection already contains world XYZ.
    MaterialProjection projection = materialProjection(world, face);
    vec3 worldDx = vec3(projection.topGradient[0] * vec2(1.0, -1.0), -projection.xGradient[0].y);
    vec3 worldDy = vec3(projection.topGradient[1] * vec2(1.0, -1.0), -projection.xGradient[1].y);
    vec2 rippleDx = worldDx.xy * .20, rippleDy = worldDy.xy * .20;
    if (shore && above <= -1.1) return;
    // The sculpted waterline can recede well inside the water hex. Reach it with sediment, not a hex-edge stripe.
    // Both sides of the bank use the same field. A different width/normal gate on water-owned triangles
    // leaves straight wedges wherever the sculpted shoreline crosses the original hex boundary.
    vec4 cover, fringe;
    float tundra;
    biomeCoverage(world, 14.0, length(abs(worldDx.xy) + abs(worldDy.xy)), cover, fringe, tundra);
    if (!shore) { tundraSurface(world, face, tundra, color, normal, cavity, grass, bounce); }
    if (family(4.0)) return;
    cover.yzw *= min(3.0, 1.0 / max(dot(cover.yzw, vec3(1.0)), .0001));
    // Broad reach on level ground connects shore patches. On a draining face retain the graded coverage;
    // amplifying it to a solid mask makes isolated peat fingers look pasted onto the neighbouring turf.
    float spread = mix(1.4, 3.0, smoothstep(.70, .97, face.z));
    fringe.yzw *= min(spread, 1.0 / max(dot(fringe.yzw, vec3(1.0)), .0001));
    float growing = smoothstep(.55, .92, face.z);
    // At the toe, deposited earth/peat meets the neighbouring floor even on a steep face.
    float bank = max(smoothstep(.08, .80, face.z), 1.0 - smoothstep(.1, 1.1, foot));
    cover *= growing;
    fringe *= bank;
    // Cultivation belongs on the plateau. The existing grass/soil slope material remains visible as the bank turns.
    cover.x *= smoothstep(.85, .98, face.z);
    fringe.x *= smoothstep(.65, .97, face.z);
    // Only wet sediment reaches into shallow water. Crops stay on dry land, and deeper beds retain their material.
    if (shore) {
        cover.x = 0.0;
        cover *= smoothstep(-1.1, -.15, above);
        fringe.x = 0.0;
        fringe *= smoothstep(-1.1, -.15, above);
    }
    // The bank's broad sediment reach must not flood neighbouring cultivated rows.
    fringe = max(fringe, cover);
    cover.yzw *= 1.0 - cover.x;
    fringe.yzw *= (1.0 - fringe.x) * min(1.0, 1.0 / max(dot(fringe.yzw, vec3(1.0)), .0001));
    float strength = max(max(fringe.x, fringe.y), max(fringe.z, fringe.w));
    if (strength < .0001) return;
    float broad = biomeNoise(world.xy / 4.7 + 13.0);
    float fine = biomeNoise(world.xy * 12.0);
    float grain = mix(fine, .5, smoothstep(.03, .15, pixelMetres));
    if (fringe.x > 0.0) {
        vec2 direction = vec2(.9396926, .3420201);
        float row = dot(world.xy, direction) / 1.15;
        float rowWidth = (abs(dot(worldDx.xy, direction)) + abs(dot(worldDy.xy, direction))) / 1.15;
        float visible = 1.0 - smoothstep(.25, .8, rowWidth);
        float ridge = .5 + .5 * cos(row * 6.2831853);
        float canopy = mix(.60, smoothstep(.22, .70, ridge) * mix(.78, 1.0, grain), visible);
        // The broad field margin is weathered cultivated earth; rows/canopy stay on their supported ground.
        float planted = cover.x / max(fringe.x, .0001);
        vec4 soil = biomePlanar(u_biomeSoilLayer, projection, broad, false);
        vec3 earth = mix(toLinear(vec3(.20, .14, .075)), toLinear(vec3(.33, .255, .14)), soil.a) * mix(.78, 1.20, grain);
        // Under nearby plants the rows are in their shade. Where plants dissolve at distance, the ground takes the crop
        // artwork's own range, from its leaves to its golden heads, so a field keeps its colour.
        vec3 crop = mix(mix(toLinear(vec3(.30, .39, .09)), toLinear(vec3(.49, .46, .18)), broad * .7),
              mix(toLinear(vec3(.40, .44, .17)), toLinear(vec3(.58, .54, .25)), broad * .7), 1.0 - visible) * mix(.85, 1.15, grain);
        float amount = materialWeights(vec4(1.0 - fringe.x, fringe.x, 0.0, 0.0),
              vec4(materialHeight, soil.a, 0.0, 0.0)).y;
        color = mix(color, mix(earth, crop, canopy * planted * .95), amount);
        if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            vec3 soilNormal = upNormal(biomePlanar(u_biomeSoilLayer + 1.0, projection, broad, true).rgb, face);
            soilNormal = normalize(mix(face, soilNormal, terrainNormalDetail));
            normal = normalize(mix(normal, soilNormal, amount));
        }
        normal = normalize(normal + vec3(direction * sin(row * 6.2831853) * .30 * visible * cover.x, 0.0));
        cavity *= 1.0 - cover.x * (1.0 - ridge) * .30 * visible;
        grass *= 1.0 - amount;
        bounce = mix(bounce, vec3(.17, .15, .065), amount);
    }
    float wetland = fringe.y + fringe.z + fringe.w;
    if (wetland > 0.0) {
        vec3 wetness = biomeWetnessGradient(world.xy);
        float wet = wetness.x;
        float quick = fringe.z / wetland;
        float bare = (fringe.z + fringe.w) / wetland;
        float aa = max(.003, abs(dot(wetness.yz, worldDx.xy)) + abs(dot(wetness.yz, worldDy.xy)));
        float emerged = shore ? smoothstep(-.10, -.01, above) : 1.0;
        float supported = clamp(dot(cover.yzw, vec3(1.0)) / wetland, 0.0, 1.0);
        // Moss can anchor to saturated banks between marsh levels; reflecting pools still need level support.
        float anchored = max(supported, smoothstep(.15, .80, face.z) * .9);
        float hummock = smoothstep(.50, .67, wet) * (1.0 - bare) * emerged * anchored;
        float water = (1.0 - smoothstep(.39 - aa, .43 + aa, wet)) * (1.0 - bare * .45 - quick * .2);
        water *= supported * smoothstep(.94, .995, face.z);
        // A draining lip breaks into peat shelves before the drop. Leave uphill/shore waterlines level.
        if (!shore) {
            // Low-step rim distances refer to the original seam through the bank, not its receded plateau lip.
            float retreat = mix(2.5, 7.0, biomeNoise(world.xy / 2.0 + 19.0));
            water *= smoothstep(retreat * .2, retreat, rim);
        }
        // Reuse actual soil relief rather than replacing it with smooth coloured noise.
        vec4 soil = biomeSoil(projection, broad);
        // Use the same texture-height competition as grass/soil contacts, preserving the native slope's detail.
        float deposits = materialWeights(vec4(1.0 - wetland, wetland, 0.0, 0.0),
              vec4(materialHeight, soil.a, 0.0, 0.0)).y;
        // A bank drains into a damp apron before peat replaces its turf. Keep texture-height detail within
        // that gradual saturation change, rather than cutting a dark, solid tongue into dry grass.
        float draining = 1.0 - smoothstep(.82, .98, face.z);
        float saturation = smoothstep(0.0, .75, wetland) * draining;
        wetland = mix(deposits, wetland, draining * .55);
        float crumbs = mix(.60, 1.38, soil.a) * mix(.80, 1.20, grain);
        vec3 peat = mix(toLinear(vec3(.20, .16, .105)), toLinear(vec3(.32, .275, .185)), broad) * crumbs;
        vec3 silt = mix(toLinear(vec3(.29, .25, .175)), toLinear(vec3(.40, .345, .245)), broad) * crumbs;
        vec3 moss = mix(toLinear(vec3(.20, .225, .085)), toLinear(vec3(.33, .315, .16)), broad) * crumbs;
        vec3 mud = mix(peat, silt, quick);
        // SAND can coexist with swamp/mud. Keep damp mineral grains and ripples on its exposed margins.
        mud = mix(mud, color * .68, sand);
        float depth = 1.0 - smoothstep(.22, .455, wet);
        vec3 shallow = mix(mud * .82, toLinear(vec3(.13, .16, .13)), depth * .83);
        float algae = smoothstep(.52, .66, broad) * smoothstep(.39, .44, wet) * (1.0 - bare);
        // Small duckweed rafts follow the sheltered margins, leaving the centres visibly open.
        algae *= smoothstep(.48, .64, grain) * .72;
        shallow = mix(shallow, moss, algae);
        vec3 marsh = mix(mix(mud, moss, hummock), shallow, water);
        color *= 1.0 - saturation * (1.0 - wetland) * .25;
        color = mix(color, marsh, wetland);
        pool = water * (1.0 - algae) * wetland * emerged;
        damp = max(saturation * .60, wetland * mix(.85, .28, hummock));
        // Saturated peat is porous and fibrous, not a continuous glossy water coating. Moss and duckweed
        // retain their rough surface; only the exposed pool mask below receives the water BRDF.
        float peatRoughness = mix(.80, .72, quick) + (grain - .5) * .06;
        roughness = mix(roughness, mix(peatRoughness, .93, max(hummock, algae * water)), wetland);
        // Low hummocks alter the shading normal without moving the shared picking/support surface.
        float bumpT = clamp((wet - .45) / (.64 - .45), 0.0, 1.0);
        vec2 bumpGradient = wetness.yz * (.23 * 6.0 * bumpT * (1.0 - bumpT) / (.64 - .45));
        float area = dot(worldDx, cross(worldDy, face));
        vec3 gradient = (cross(worldDy, face) * dot(bumpGradient, worldDx.xy)
              + cross(face, worldDx) * dot(bumpGradient, worldDy.xy)) / max(abs(area), .00001) * sign(area);
        if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            vec3 soilNormal = biomeSoilNormal(projection, face, broad);
            soilNormal = normalize(mix(face, soilNormal, terrainNormalDetail));
            normal = normalize(mix(normal, soilNormal, wetland * (1.0 - sand)));
        }
        normal = normalize(normal - gradient * wetland * (1.0 - bare));
        // The water's eased wind and integrated drift (GpuOcean): a change of wind never makes the ripples jump. The
        // swell's crests keep one world direction, as turning them would sweep them across the board.
        if (pool > 0.0) {
            vec2 ripple = vec2(0.0);
            float rippleDetail = 1.0 - smoothstep(.15, .55, pixelMetres);
            if (rippleDetail > 0.0) {
                vec2 drift = u_waterDrift * .015;
                float windStrength = u_waterWind.z * mix(.35, 1.0, bare);
                vec2 axis = vec2(.8, .6);
                ripple = (textureGrad(u_waterDetail, world.xy * .20 - drift, rippleDx, rippleDy).rg - .5)
                      * (.012 + windStrength * .035)
                      + axis * sin(dot(world.xy, axis) * 5.0 - u_rainTime * 1.6) * windStrength * .012
                      + rainRipples(world.xy * .03) * .035;
                ripple *= rippleDetail;
            }
            // Distant pools still flatten the hummock normal even when their ripples are subpixel.
            normal = normalize(mix(normal, normalize(vec3(-ripple, 1.0)), pool));
        }
        cavity = mix(mix(cavity, .78 + .22 * soil.a, wetland), 1.0, pool);
        grass *= 1.0 - wetland;
        bounce = mix(bounce, vec3(.09, .10, .045), wetland);
    }
}
