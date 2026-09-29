// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The surface length one pixel covers, in metres. Set once per fragment in main().
float pixelMetres;

float hash(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }

// Darkening of a joint of half-width w at distance x (metres) from its centre line. A joint narrower than a pixel
// darkens it only by the share it covers, so distant joints neither vanish nor flicker.
float joint(float x, float w) {
    return (1.0 - smoothstep(w - .5 * pixelMetres, w + .5 * pixelMetres, x)) * min(1.0, 2.0 * w / pixelMetres);
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
    float runoff = smoothstep(.55, .85, texture(u_rainNoise, vec2(u / 3.1 + window.x * 7.0, z / 45.0)).r);
    shade *= 1.0 - .14 * runoff * smoothstep(.45, 1.0, up);
    float seam = joint(min(fract(along), 1.0 - fract(along)) * width, .03);
    if (!single) seam = max(seam, joint(min(up, 1.0 - up) * levelMetres, .03) * smoothstep(.1, .3, d) * smoothstep(.1, .3, h));
    shade *= 1.0 - .55 * seam;
    return vec2(u, -z) / u_sculptTiles.w + window;
}

void concreteSlab(vec3 world, vec3 face, float h, float d, float rock,
      inout vec3 albedo, inout vec3 normal, inout float occlusion, inout float cavity) {
    float drop = h + d;
    vec3 axes = pow(abs(face), vec3(4.0));
    // Cast concrete: the whole face of a step of up to two levels, and from three levels the slab of
    // the top level (its underside included) above the bedrock that carries it.
    bool single = rock > .5;
    float poured = 1.0 - smoothstep(-.02, .02, d - (single ? u_levelHeight / u_metre + .12 : drop + 1.0));
    if (poured > 0.0) {
        // Flat panels need only boundary vertices. Evaluate contact shading per fragment so it
        // stays at the foot and under the rim instead of stretching across a large triangle.
        float shelterDistance = (d - 2.2) / 1.6;
        float shelter = max(0.0, 1.0 - shelterDistance * shelterDistance);
        float castOcclusion = (1.0 - .4 * exp(-h / 1.6)) * (1.0 - .25 * shelter * shelter);
        occlusion = mix(occlusion, castOcclusion, poured);
        float along = clamp((axes.x / max(axes.x + axes.y, 1e-4) - .5) * 6.0 + .5, 0.0, 1.0);
        float shadeX, shadeY;
        vec2 sx = slab(world.y * sign(face.x), world.z, h, d, 1.0, single, shadeX);
        vec2 sy = slab(-world.x * sign(face.y), world.z, h, d, 0.0, single, shadeY);
        vec3 concrete = mix(texture(u_mantleColor, sy).rgb * shadeY,
              texture(u_mantleColor, sx).rgb * shadeX, along);
        albedo = mix(albedo, concrete, poured);
        if (u_normalMaps > .5) {
            normal = normalize(mix(normal, wallNormal(u_mantleNormal, sx, sy, face, along), poured));
            float pores = mix(texture(u_mantleNormal, sy).a, texture(u_mantleNormal, sx).a, along);
            cavity = mix(cavity, pores * .5 + .5, poured);
        }
    }
}
