// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Surface-only treatment: reeds/crops stand on this same terrain; these small wet depressions do not change rules.
void biomeSurface(vec3 world, vec3 face, bool shore, float above, inout vec3 color, inout vec3 normal, inout float cavity,
      inout float grass, inout vec3 bounce, out float pool, out float damp) {
    pool = 0.0; damp = 0.0;
    if (shore && above <= -1.1) return;
    // The sculpted waterline can recede well inside the water hex. Reach it with sediment, not a hex-edge stripe.
    vec4 cover = biomeCoverage(world, shore ? 14.0 : 2.2) * smoothstep(shore ? .05 : .40, .90, face.z);
    // Only wet sediment reaches into shallow water. Crops stay on dry land, and deeper beds retain their material.
    if (shore) {
        cover.x = 0.0;
        cover *= min(3.0, 1.0 / max(dot(cover, vec4(1.0)), .0001)) * smoothstep(-1.1, -.15, above);
    }
    float strength = max(max(cover.x, cover.y), max(cover.z, cover.w));
    if (strength < .0001) return;
    float broad = biomeNoise(world.xy / 4.7 + 13.0);
    float fine = biomeNoise(world.xy * 12.0);
    float grain = mix(fine, .5, smoothstep(.03, .15, pixelMetres));
    float substrate = clamp(dot(color, vec3(.3, .59, .11)) * 3.5, .65, 1.45);
    if (cover.x > 0.0) {
        vec2 direction = vec2(.9396926, .3420201);
        float row = dot(world.xy, direction) / 1.15;
        float visible = 1.0 - smoothstep(.25, .8, fwidth(row));
        float ridge = .5 + .5 * cos(row * 6.2831853);
        float canopy = mix(.48, smoothstep(.38, .78, ridge), visible);
        vec3 earth = mix(color * vec3(.59, .51, .38), vec3(.17, .115, .060), .55) * mix(.65, 1.26, grain) * substrate;
        vec3 crop = mix(vec3(.22, .285, .075), vec3(.46, .40, .17), broad * .7) * mix(.75, 1.16, grain);
        color = mix(color, mix(earth, crop, canopy * .85), cover.x);
        normal = normalize(mix(normal, normalize(face + vec3(direction * sin(row * 6.2831853) * .22 * visible, 0.0)), cover.x));
        cavity *= 1.0 - cover.x * (1.0 - ridge) * .22 * visible;
        grass *= 1.0 - cover.x;
        bounce = mix(bounce, vec3(.17, .15, .065), cover.x);
    }
    float wetland = cover.y + cover.z + cover.w;
    if (wetland > 0.0) {
        float wet = biomeWetness(world.xy);
        float quick = cover.z / wetland;
        float bare = (cover.z + cover.w) / wetland;
        float hummock = smoothstep(.43, .59, wet) * (1.0 - bare) * (shore ? smoothstep(-.03, .18, above) : 1.0);
        float water = (1.0 - smoothstep(.36, .46, wet)) * (1.0 - bare * .5 - quick * .2);
        vec3 peat = mix(vec3(.13, .105, .056), vec3(.29, .235, .13), broad) * mix(.7, 1.2, grain) * substrate;
        vec3 silt = mix(vec3(.25, .215, .145), vec3(.37, .315, .205), broad) * mix(.85, 1.08, grain);
        vec3 moss = mix(vec3(.145, .18, .065), vec3(.27, .29, .11), broad) * mix(.65, 1.26, grain) * mix(.8, 1.2, substrate);
        vec3 mud = mix(peat, silt, quick);
        float depth = 1.0 - smoothstep(.20, .43, wet);
        vec3 shallow = mix(mud * .86, vec3(.072, .092, .053), depth);
        float algae = smoothstep(.52, .73, broad) * (1.0 - smoothstep(.36, .40, wet)) * smoothstep(.25, .32, wet) * (1.0 - bare);
        shallow = mix(shallow, moss * .72, algae * .48);
        vec3 marsh = mix(mix(mud, moss, hummock), shallow, water);
        color = mix(color, marsh, wetland);
        pool = water * wetland * (shore ? smoothstep(-.02, .12, above) : 1.0);
        damp = wetland * mix(.55, .20, hummock);
        // Low hummocks alter the shading normal without moving the shared picking/support surface.
        vec3 dx = dFdx(world), dy = dFdy(world);
        float bump = smoothstep(.39, .64, wet) * .16;
        float area = dot(dx, cross(dy, face));
        vec3 gradient = (cross(dy, face) * dFdx(bump) + cross(face, dx) * dFdy(bump)) / max(abs(area), .00001) * sign(area);
        normal = normalize(normal - gradient * wetland * (1.0 - bare));
        vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
        vec2 ripple = wind * sin(dot(world.xy, wind) * 5.0 - u_rainTime * 1.6) * u_wind.z * .018
              + rainRipples(world.xy * .03) * .035;
        ripple *= 1.0 - smoothstep(.15, .55, pixelMetres);
        normal = normalize(mix(normal, normalize(vec3(-ripple, 1.0)), water * wetland));
        cavity = mix(cavity, mix(.74, 1.0, water), wetland);
        grass *= 1.0 - wetland;
        bounce = mix(bounce, vec3(.09, .10, .045), wetland);
    }
}
