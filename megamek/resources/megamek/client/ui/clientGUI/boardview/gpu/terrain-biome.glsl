// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Surface-only treatment: reeds/crops stand on this same terrain; these small wet depressions do not change rules.
// Borrow the asset cache's earth maps on every terrain family, including array-backed boundary draws.
uniform sampler2D u_biomeSoil, u_biomeSoilNormal;
uniform float u_biomeSoilTile;
void biomeSurface(vec3 world, vec3 face, bool shore, float above, float foot, float rim, float materialHeight, float sand,
      inout vec3 color, inout vec3 normal, inout float cavity,
      inout float grass, inout vec3 bounce, out float pool, out float damp) {
    pool = 0.0; damp = 0.0;
    if (shore && above <= -1.1) return;
    // The sculpted waterline can recede well inside the water hex. Reach it with sediment, not a hex-edge stripe.
    // Both sides of the bank use the same field. A different width/normal gate on water-owned triangles
    // leaves straight wedges wherever the sculpted shoreline crosses the original hex boundary.
    vec4 cover, fringe;
    biomeCoverage(world, 14.0, cover, fringe);
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
        float visible = 1.0 - smoothstep(.25, .8, fwidth(row));
        float ridge = .5 + .5 * cos(row * 6.2831853);
        float canopy = mix(.60, smoothstep(.22, .70, ridge) * mix(.78, 1.0, grain), visible);
        // The broad field margin is weathered cultivated earth; rows/canopy stay on their supported ground.
        float planted = cover.x / max(fringe.x, .0001);
        vec2 soilUV = vec2(world.x, -world.y);
        vec4 soil = planar(u_biomeSoil, soilUV, u_biomeSoilTile, broad);
        vec3 earth = mix(vec3(.20, .14, .075), vec3(.33, .255, .14), soil.a) * mix(.78, 1.20, grain);
        // Under nearby plants the rows are in their shade. Where plants dissolve at distance, the ground takes the crop
        // artwork's own range, from its leaves to its golden heads, so a field keeps its colour.
        vec3 crop = mix(mix(vec3(.30, .39, .09), vec3(.49, .46, .18), broad * .7),
              mix(vec3(.40, .44, .17), vec3(.58, .54, .25), broad * .7), 1.0 - visible) * mix(.85, 1.15, grain);
        float amount = materialWeights(vec4(1.0 - fringe.x, fringe.x, 0.0, 0.0),
              vec4(materialHeight, soil.a, 0.0, 0.0)).y;
        color = mix(color, mix(earth, crop, canopy * planted * .95), amount);
        if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            vec3 soilNormal = upNormal(planarNormal(u_biomeSoilNormal, soilUV, u_biomeSoilTile, broad).rgb, face);
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
        float wet = biomeWetness(world.xy);
        float quick = fringe.z / wetland;
        float bare = (fringe.z + fringe.w) / wetland;
        float aa = max(.003, fwidth(wet));
        float emerged = shore ? smoothstep(-.10, -.01, above) : 1.0;
        float supported = clamp(dot(cover.yzw, vec3(1.0)) / wetland, 0.0, 1.0);
        // Moss can anchor to saturated banks between marsh levels; reflecting pools still need level support.
        float anchored = max(supported, smoothstep(.15, .80, face.z) * .9);
        float hummock = smoothstep(.50, .67, wet) * (1.0 - bare) * emerged * anchored;
        float water = (1.0 - smoothstep(.445 - aa, .458 + aa, wet)) * (1.0 - bare * .45 - quick * .2);
        water *= supported * smoothstep(.94, .995, face.z);
        // A draining lip breaks into peat shelves before the drop. Leave uphill/shore waterlines level.
        if (!shore) {
            // Low-step rim distances refer to the original seam through the bank, not its receded plateau lip.
            float retreat = mix(2.5, 7.0, biomeNoise(world.xy / 2.0 + 19.0));
            water *= smoothstep(retreat * .2, retreat, rim);
        }
        // Reuse actual soil relief rather than replacing it with smooth coloured noise.
        MaterialProjection projection = materialProjection(world, face);
        vec4 soil = draped(u_biomeSoil, projection.top, projection.x, projection.y,
              projection.side, u_biomeSoilTile, broad, projection.lying);
        // Use the same texture-height competition as grass/soil contacts, preserving the native slope's detail.
        float deposits = materialWeights(vec4(1.0 - wetland, wetland, 0.0, 0.0),
              vec4(materialHeight, soil.a, 0.0, 0.0)).y;
        // A bank drains into a damp apron before peat replaces its turf. Keep texture-height detail within
        // that gradual saturation change, rather than cutting a dark, solid tongue into dry grass.
        float draining = 1.0 - smoothstep(.82, .98, face.z);
        float saturation = smoothstep(0.0, .75, wetland) * draining;
        wetland = mix(deposits, wetland, draining * .55);
        float crumbs = mix(.60, 1.38, soil.a) * mix(.80, 1.20, grain);
        vec3 peat = mix(vec3(.20, .16, .105), vec3(.32, .275, .185), broad) * crumbs;
        vec3 silt = mix(vec3(.29, .25, .175), vec3(.40, .345, .245), broad) * crumbs;
        vec3 moss = mix(vec3(.20, .225, .085), vec3(.33, .315, .16), broad) * crumbs;
        vec3 mud = mix(peat, silt, quick);
        // SAND can coexist with swamp/mud. Keep damp mineral grains and ripples on its exposed margins.
        mud = mix(mud, color * .68, sand);
        float depth = 1.0 - smoothstep(.22, .455, wet);
        vec3 shallow = mix(mud * .82, vec3(.13, .16, .13), depth * .83);
        float algae = smoothstep(.52, .66, broad) * smoothstep(.39, .44, wet) * (1.0 - bare);
        // Small duckweed rafts follow the sheltered margins, leaving the centres visibly open.
        algae *= smoothstep(.48, .64, grain) * .72;
        shallow = mix(shallow, moss, algae);
        vec3 marsh = mix(mix(mud, moss, hummock), shallow, water);
        color *= 1.0 - saturation * (1.0 - wetland) * .25;
        color = mix(color, marsh, wetland);
        pool = water * (1.0 - algae) * wetland * emerged;
        damp = max(saturation * .60, wetland * mix(.85, .28, hummock));
        // Low hummocks alter the shading normal without moving the shared picking/support surface.
        vec3 dx = dFdx(world), dy = dFdy(world);
        float bump = smoothstep(.45, .64, wet) * .23;
        float area = dot(dx, cross(dy, face));
        vec3 gradient = (cross(dy, face) * dFdx(bump) + cross(face, dx) * dFdy(bump)) / max(abs(area), .00001) * sign(area);
        if (u_normalMaps > .5 && terrainNormalDetail > 0.0) {
            vec3 soilNormal = projection.lying >= 1.0
                  ? upNormal(planarNormal(u_biomeSoilNormal, projection.top, u_biomeSoilTile, broad).rgb, face)
                  : drapedNormal(u_biomeSoilNormal, projection.top, projection.x, projection.y,
                        face, projection.side, u_biomeSoilTile, broad, projection.lying);
            soilNormal = normalize(mix(face, soilNormal, terrainNormalDetail));
            normal = normalize(mix(normal, soilNormal, wetland * (1.0 - sand)));
        }
        normal = normalize(normal - gradient * wetland * (1.0 - bare));
        // The water's eased wind and integrated drift (GpuOcean): a change of wind never makes the ripples jump. The
        // swell's crests keep one world direction, as turning them would sweep them across the board.
        vec2 drift = u_waterDrift * .015;
        float windStrength = u_waterWind.z * mix(3.0, 1.0, bare);
        vec2 axis = vec2(.8, .6);
        vec2 ripple = (texture(u_waterDetail, world.xy * .20 - drift).rg - .5) * (.025 + windStrength * .07)
              + axis * sin(dot(world.xy, axis) * 5.0 - u_rainTime * 1.6) * windStrength * .018
              + rainRipples(world.xy * .03) * .035;
        ripple *= 1.0 - smoothstep(.15, .55, pixelMetres);
        normal = normalize(mix(normal, normalize(vec3(-ripple, 1.0)), water * wetland));
        cavity = mix(cavity, mix(.78 + .22 * soil.a, 1.0, water), wetland);
        grass *= 1.0 - wetland;
        bounce = mix(bounce, vec3(.09, .10, .045), wetland);
    }
}
