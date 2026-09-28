// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Surface-only treatment: reeds/crops stand on this same terrain; these small wet depressions do not change rules.
// Borrow the asset cache's earth maps on every terrain family, including array-backed boundary draws.
uniform sampler2D u_biomeSoil, u_biomeSoilNormal;
uniform float u_biomeSoilTile;
void biomeSurface(vec3 world, vec3 face, bool shore, float above, float foot, inout vec3 color, inout vec3 normal, inout float cavity,
      inout float grass, inout vec3 bounce, out float pool, out float damp) {
    pool = 0.0; damp = 0.0;
    if (shore && above <= -1.1) return;
    // The sculpted waterline can recede well inside the water hex. Reach it with sediment, not a hex-edge stripe.
    // Both sides of the bank use the same field. A different width/normal gate on water-owned triangles
    // leaves straight wedges wherever the sculpted shoreline crosses the original hex boundary.
    vec4 cover, fringe;
    biomeCoverage(world, 14.0, cover, fringe);
    cover.yzw *= min(3.0, 1.0 / max(dot(cover.yzw, vec3(1.0)), .0001));
    fringe.yzw *= min(3.0, 1.0 / max(dot(fringe.yzw, vec3(1.0)), .0001));
    float growing = smoothstep(.55, .92, face.z);
    // At the toe, deposited earth/peat meets the neighbouring floor even on a steep face.
    float bank = max(smoothstep(.08, .80, face.z), 1.0 - smoothstep(.1, 1.1, foot));
    cover *= growing;
    fringe *= bank;
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
        vec3 crop = mix(vec3(.30, .39, .09), vec3(.49, .46, .18), broad * .7) * mix(.85, 1.15, grain);
        color = mix(color, mix(earth, crop, canopy * planted * .95), fringe.x);
        if (u_normalMaps > .5) {
            normal = normalize(mix(normal, upNormal(planarNormal(u_biomeSoilNormal, soilUV, u_biomeSoilTile, broad).rgb, face), fringe.x));
        }
        normal = normalize(normal + vec3(direction * sin(row * 6.2831853) * .30 * visible * cover.x, 0.0));
        cavity *= 1.0 - cover.x * (1.0 - ridge) * .30 * visible;
        grass *= 1.0 - fringe.x;
        bounce = mix(bounce, vec3(.17, .15, .065), fringe.x);
    }
    float wetland = fringe.y + fringe.z + fringe.w;
    if (wetland > 0.0) {
        float wet = biomeWetness(world.xy);
        float quick = fringe.z / wetland;
        float bare = (fringe.z + fringe.w) / wetland;
        float aa = max(.003, fwidth(wet));
        float emerged = shore ? smoothstep(-.10, -.01, above) : 1.0;
        float supported = clamp(dot(cover.yzw, vec3(1.0)) / wetland, 0.0, 1.0);
        float hummock = smoothstep(.50, .67, wet) * (1.0 - bare) * emerged * supported;
        float water = (1.0 - smoothstep(.445 - aa, .458 + aa, wet)) * (1.0 - bare * .45 - quick * .2);
        water *= supported * smoothstep(.94, .995, face.z);
        // Reuse actual soil relief rather than replacing it with smooth coloured noise.
        vec2 soilUV = vec2(world.x, -world.y);
        vec4 soil = planar(u_biomeSoil, soilUV, u_biomeSoilTile, broad);
        float crumbs = mix(.60, 1.38, soil.a) * mix(.80, 1.20, grain);
        vec3 peat = mix(vec3(.20, .16, .105), vec3(.32, .275, .185), broad) * crumbs;
        vec3 silt = mix(vec3(.29, .25, .175), vec3(.40, .345, .245), broad) * crumbs;
        vec3 moss = mix(vec3(.20, .225, .085), vec3(.33, .315, .16), broad) * crumbs;
        vec3 mud = mix(peat, silt, quick);
        float depth = 1.0 - smoothstep(.22, .455, wet);
        vec3 shallow = mix(mud * .82, vec3(.13, .16, .13), depth * .83);
        float algae = smoothstep(.52, .66, broad) * smoothstep(.39, .44, wet) * (1.0 - bare);
        // Small duckweed rafts follow the sheltered margins, leaving the centres visibly open.
        algae *= smoothstep(.48, .64, grain) * .72;
        shallow = mix(shallow, moss, algae);
        vec3 marsh = mix(mix(mud, moss, hummock), shallow, water);
        color = mix(color, marsh, wetland);
        pool = water * (1.0 - algae) * wetland * emerged;
        damp = wetland * mix(.85, .28, hummock);
        // Low hummocks alter the shading normal without moving the shared picking/support surface.
        vec3 dx = dFdx(world), dy = dFdy(world);
        float bump = smoothstep(.45, .64, wet) * .23;
        float area = dot(dx, cross(dy, face));
        vec3 gradient = (cross(dy, face) * dFdx(bump) + cross(face, dx) * dFdy(bump)) / max(abs(area), .00001) * sign(area);
        if (u_normalMaps > .5) {
            vec3 soilNormal = upNormal(planarNormal(u_biomeSoilNormal, soilUV, u_biomeSoilTile, broad).rgb, face);
            normal = normalize(mix(normal, soilNormal, wetland));
        }
        normal = normalize(normal - gradient * wetland * (1.0 - bare));
        vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
        vec2 drift = wind * u_rainTime * .015;
        float windStrength = u_wind.z * mix(3.0, 1.0, bare);
        vec2 ripple = (texture2D(u_waterDetail, world.xy * .20 - drift).rg - .5) * (.025 + windStrength * .07)
              + wind * sin(dot(world.xy, wind) * 5.0 - u_rainTime * 1.6) * windStrength * .018
              + rainRipples(world.xy * .03) * .035;
        ripple *= 1.0 - smoothstep(.15, .55, pixelMetres);
        normal = normalize(mix(normal, normalize(vec3(-ripple, 1.0)), water * wetland));
        cavity = mix(cavity, mix(.78 + .22 * soil.a, 1.0, water), wetland);
        grass *= 1.0 - wetland;
        bounce = mix(bounce, vec3(.09, .10, .045), wetland);
    }
}
