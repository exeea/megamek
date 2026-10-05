// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// One optical model for frozen lake slabs, land ice and detected black ice on pavement/roads/bridge decks. Every mode
// samples the same world-anchored floes, so a lake slab and the land ice beside it read as one sheet over other ground.
// After terrain-hexes.glsl: land ice meets dry ground along a jagged, shattered border that straddles their shared
// hex edge; the dry side draws its part of it.
#ifdef iceFlag
uniform sampler2D u_iceColor;
uniform sampler2D u_iceNormal;
uniform sampler2D u_iceSurface; // R surface height, G roughness, B cracks and bubbles, A cloudy ice
// 1 land ice, 2 detected thin black ice, 3 floating lake slab; 4 dry ground beside land ice, 5 paved ground beside
// land or black ice, which draw their side of the shared border.
uniform float u_iceMode;
uniform float u_iceMetre;
uniform float u_iceLevel;      // world units per level
uniform float u_iceWaterline;  // world units from a water hex's level down to its surface
uniform float u_iceNormals;
uniform vec2 u_iceBoard;       // the per-hex texture's size where any hex shows ice, else zero

// Linear albedos. Clear ice returns almost no light of its own over deep water; wind-packed snow and the grey
// refrozen slush in the joints between floes scatter it.
const vec3 ICE_DEEP = vec3(.012, .034, .048);
const vec3 ICE_BARE = vec3(.40, .50, .56);
const vec3 ICE_SNOW = vec3(.84, .88, .91);
const vec3 ICE_JOINT = vec3(.46, .54, .59);

// The normal map is baked for the unturned 24 m field. A transformed sample needs the inverse basis change and
// the matching gradient scale, including the anisotropic wind stretch. Offsets only change the sampled position.
vec3 iceMappedNormal(vec2 uv, mat2 transform) {
    vec3 normal = (texture(u_iceNormal, uv).xyz * 255.0 - 128.0) / 127.0;
    return normalize(vec3(transpose(transform) * normal.xy, normal.z));
}

// The shared 64² noise with smoothed cells, texels the given spacing in metres apart. The base level avoids mip
// selection jumps at the cell borders; the fields are broad enough not to alias.
vec3 iceNoise(vec2 metres, float spacing, vec2 offset) {
    vec2 texel = metres / spacing + offset, cell = floor(texel), f = fract(texel);
    return textureLod(u_rainNoise, (cell + f * f * (3.0 - 2.0 * f) + .5) / 64.0, 0.0).rgb;
}

// Floes on a jittered lattice, in cell units: x is the distance between the nearest two sites (twice the distance to
// their shared joint), y a stable value per floe, zw the offset from the point to its floe's site.
vec4 iceFloes(vec2 p) {
    vec2 cell = floor(p), f = fract(p), toSite = vec2(0.0);
    float first = 8.0, second = 8.0;
    ivec2 nearest = ivec2(0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 key = ivec2(cell) + ivec2(x, y);
            vec2 site = vec2(x, y) + .1 + .8 * vec2(biomeHash(key), biomeHash(key + ivec2(71, 13))) - f;
            float d = dot(site, site);
            if (d < first) { second = first; first = d; nearest = key; toSite = site; } else if (d < second) { second = d; }
        }
    }
    return vec4(sqrt(second) - sqrt(first), biomeHash(nearest + ivec2(29, 53)), toSite);
}

// The border of the iced area, from the per-hex texture, in metres: x is signed to the border shared with dry
// neighbours at this level, which draw their side of it (positive in the ice); y is to ground that cannot (water,
// another level); z is 1 where the nearest ice is thin black ice. Beneath the surface of the water hex a point lies
// in, ice has no footing: x is then far outside. A dry point measures to the ice it borders (fringe), which is
// frozen land, or black ice as well for paved ground (glazed); a frozen lake ends at its own shore.
vec3 iceBorder(vec2 world, float z, bool fringe, bool glazed) {
    if (u_iceBoard.x < 1.0) return vec3(1e4, 1e4, 0.0);
    const float width = 30.0, height = 30.0 * 72.0 / 84.0;
    int column = int(floor(world.x / (width * .75)));
    int kinds[9];
    float levels[9], distances[9];
    bool wet[9];
    float own = 1e4, ownDistance = 1e4;
    bool ownWet = false;
    for (int i = 0; i < 9; i++) {
        int x = column + i / 3 - 1;
        int y = int(floor(-world.y / height - mod(float(x), 2.0) * .5)) + i % 3 - 1;
        kinds[i] = -1;
        if (x < 0 || y < 0 || float(x) >= u_iceBoard.x || float(y) >= u_iceBoard.y) continue;
        vec4 tile = texelFetch(u_biomeHexes, ivec2(x, y), 0);
        kinds[i] = int(tile.b * 255.0 + .5);
        levels[i] = tile.a * 255.0 - 64.0;
        distances[i] = biomeHexDistance(world - boardHexCenter(x, y));
        wet[i] = tile.g > .002 || kinds[i] == 2;
        if (distances[i] < ownDistance) { ownDistance = distances[i]; own = levels[i]; ownWet = wet[i]; }
    }
    if (ownWet && z < own * u_iceLevel - u_iceWaterline) return vec3(-1e4, -1e4, 0.0);
    float ice = 1e4, soft = 1e4, hard = 1e4, glaze = 0.0;
    for (int i = 0; i < 9; i++) {
        if (kinds[i] < 0) continue;
        int kind = kinds[i];
        bool iced = fringe ? (kind == 1 || glazed && kind == 3) && levels[i] == own : kind != 0;
        if (iced) {
            if (distances[i] < ice) { ice = distances[i]; glaze = kind == 3 ? 1.0 : 0.0; }
        } else if (fringe || !wet[i] && levels[i] == own) {
            soft = min(soft, distances[i]);
        } else {
            hard = min(hard, distances[i]);
        }
    }
    return vec3(.5 * (soft - ice), .5 * (hard - ice), glaze);
}

vec3 iceFinish(vec3 underlying) {
    vec3 face = normalize(v_normal);
    float slab = step(2.5, u_iceMode) * (1.0 - step(3.5, u_iceMode));
    bool fringe = u_iceMode > 3.5;
    float thin = step(1.5, u_iceMode) * (1.0 - step(2.5, u_iceMode));
    // Snow and ice cover ground up to steep banks; a slab's own cut edges are ice as well.
    float coverage = max(slab, smoothstep(.2, .45, face.z));
    if (coverage <= 0.0) { return underlying; }
    vec2 world = v_cloudPosition.xy / u_iceMetre;
    float pixel = max(length(fwidth(world)), 1e-4);
    // How far inside the ice a point on land lies, the ice's thickness there, and its broken edge.
    float inside = 1e4, thickness = 1.0, rim = 0.0, shattered = 0.0;
    vec2 tilt = vec2(0.0);
    if (slab < .5) {
        vec3 border = iceBorder(world, v_cloudPosition.z, fringe, u_iceMode > 4.5);
        if (fringe) { thin = border.z; }
        // The border runs jaggedly either side of the hex edge: each shard of broken ice has its own offset and lean,
        // with finer splinters along it. Where the far side cannot draw its part, the ice breaks off short instead.
        vec4 shard = iceFloes(world / 3.2 + vec2(17.3, 4.1));
        vec4 splinter = iceFloes(world / 1.1 + vec2(5.9, 23.3));
        float lean = biomeHash(ivec2(floor(shard.y * 7919.0), 3)) * 6.2832;
        float jag = 3.2 * (2.0 * shard.y - 1.0) + .7 * dot(shard.zw, vec2(cos(lean), sin(lean)))
              + .8 * (2.0 * splinter.y - 1.0);
        inside = min(border.x - jag, border.y - abs(jag) - .4);
        // Cracks open between the shards toward the broken edge and close a few metres in.
        float near = 1.0 - smoothstep(.5, 4.0, inside);
        float crack = .2 * near;
        shattered = (1.0 - smoothstep(crack, crack + pixel, shard.x * 1.6)) * step(.02, near);
        coverage *= smoothstep(-pixel, pixel, inside) * (1.0 - shattered);
        if (coverage <= 0.0) {
            // Meltwater darkens the ground just off the ice.
            float wet = (1.0 - smoothstep(0.0, 1.2 + pixel, -inside)) * (1.0 - thin);
            return underlying * (1.0 - .22 * wet);
        }
        thickness = smoothstep(0.0, 5.0, inside);
        // Fracture faces at the edge and along the open cracks catch the light.
        rim = max(1.0 - smoothstep(0.0, .12 + pixel, inside),
              near * (1.0 - smoothstep(crack, crack + .1 + pixel, shard.x * 1.6)));
        tilt = (vec2(shard.y, biomeHash(ivec2(floor(shard.y * 6151.0), 11))) - .5) * .16 * (1.0 - thickness);
    }
    vec2 metres = vec2(world.x, -world.y);
    vec2 uv = metres / 24.0;
    vec2 footprint = max(abs(dFdx(uv)), abs(dFdy(uv)));
    // Shading LOD follows projected texel footprint; mipmaps filter every map with the same derivatives.
    float detail = 1.0 - smoothstep(.006, .07, max(footprint.x, footprint.y));
    // Broad world fields, and which of two differently rotated samples of the maps shows, so the 24 m repeat does not
    // read across a lake.
    vec3 broad = iceNoise(metres, 12.0, vec2(9.3, 41.7)) * .65 + iceNoise(metres, 4.0, vec2(27.1, 3.9)) * .35;
    mat2 iceTurn = mat2(.81, -.59, .59, .81) * 1.37;
    vec2 turned = iceTurn * uv + vec2(.41, .17);
    float swap = smoothstep(.40, .60, broad.x);
    vec4 properties = mix(texture(u_iceSurface, uv), texture(u_iceSurface, turned), swap);
    float margin = slab * (1.0 - v_color.a);
    // Floes of two sizes. Most carry wind-packed snow; some are blown bare to grey ice. Refrozen slush fills the joints
    // of the large ones, and of the small ones in places; many joints have healed. Toward open water they open.
    // A gentle warp bends the joints the way pressure cracks run, instead of straight lattice seams.
    vec2 bent = world + (iceNoise(metres, 7.0, vec2(51.3, 12.7)).xy - .5) * 3.5;
    vec4 plate = iceFloes(bent / 17.0);
    vec4 floe = iceFloes(bent / 6.5 + vec2(3.7, 8.1));
    vec3 along = iceNoise(metres, 2.5, vec2(7.7, 30.1));
    float healed = smoothstep(.25, .65, along.x);
    float fade = (1.0 - smoothstep(.2, .8, pixel)) * (1.0 - thin);
    // Drifted snow: shallow wind streaks and the ice's relief showing through where it is thin.
    mat2 windStretch = mat2(.28, 0.0, 0.0, 1.0) * mat2(.94, .34, -.34, .94);
    vec2 wind = windStretch * metres;
    float drift = iceNoise(wind, 1.6, vec2(11.3, 5.5)).y;
    // Snow lies on most floes, blown into soft-edged patches that cross their joints; thin ice at a broken edge
    // carries less.
    float snow = (1.0 - thin) * (1.0 - .6 * margin) * mix(.3, 1.0, thickness) * smoothstep(.22, .48, plate.y * .5
          + floe.y * .2 + (broad.z - .5) * .55 + (properties.a - .5) * .35 + (drift - .5) * .35 + .15);
    float depth = clamp(mix(.55, 1.0, smoothstep(.3, .7, broad.y)) + (drift - .5) * .35, .4, 1.0);
    // Joints vary in width along their run; snow partly buries them.
    float width = .09 * mix(.35, 1.7, along.z);
    float joint = fade * max((1.0 - smoothstep(width, width + pixel + .55 * margin, plate.x * 8.5))
          * mix(.3, 1.0, healed),
          (1.0 - smoothstep(.05, .05 + pixel, floe.x * 3.25)) * .55 * smoothstep(.5, .8, broad.y) * (1.0 - healed));
    joint *= 1.0 - .7 * snow * smoothstep(.45, .8, drift);
    joint = max(joint, fade * margin * (1.0 - smoothstep(.05, .05 + pixel + .3, floe.x * 3.25)));
    float cloudy = clamp(properties.a * mix(.3, .9, broad.y), 0.0, 1.0);
    float cracks = properties.b * detail * (1.0 - snow);
    vec3 normal = face;
    if (detail * u_iceNormals > .001 && face.z > .5) {
        // Polished bare ice undulates gently. Snow has its own broader, softer dunes: the same map, stretched
        // along the wind. Shards at a broken edge lean each their own way.
        vec3 polished = mix(iceMappedNormal(uv, mat2(1.0)), iceMappedNormal(turned, iceTurn), swap);
        vec3 dunes = iceMappedNormal(wind / 9.0, windStretch * (24.0 / 9.0));
        vec3 micro = mix(polished, dunes, snow);
        vec3 tangent = normalize(vec3(face.z, 0.0, -face.x));
        micro.xy = micro.xy * detail * mix(.45, .8, snow) + tilt;
        normal = normalize(tangent * micro.x - cross(face, tangent) * micro.y + face * micro.z);
    }
    bool lead = slab > .5 && v_color.r < .5;
    float roughness = lead ? .04 : mix(mix(.06, .22, cloudy), .8, snow * depth);
    roughness = mix(roughness, max(roughness, .22), (1.0 - detail) * (lead ? 0.0 : 1.0));
    vec3 ambient = vec3(1.0), direct = vec3(0.0), sheen = vec3(0.0);
#ifdef lightingFlag
    surfaceLighting(lead ? face : normal, 0.0, roughness, ambient, direct, sheen);
#ifdef cloudShadowFlag
    float cloudLight = cloudTransmission(v_cloudPosition);
    direct *= cloudLight;
    sheen *= cloudLight;
#endif
#endif
    vec3 lit = ambient + direct;
    vec3 view = -viewDirection();
    vec3 reflected = reflect(-view, lead ? face : normal);
    vec3 sky = toLinear(mix(u_rainHorizon, u_rainSky, sqrt(clamp(reflected.z, 0.0, 1.0))));
    // Rough snow blurs the sky into the ambient light it already receives.
    sky = mix(sky, ambient * .85, smoothstep(.12, .6, roughness));
    float fresnel = (.018 + .982 * pow(1.0 - max(0.0, dot(view, lead ? face : normal)), 5.0))
          * (1.0 - .7 * smoothstep(.15, .8, roughness));
    vec3 tint = toLinear(mix(texture(u_iceColor, uv).rgb, texture(u_iceColor, turned).rgb, swap));
    tint /= max(dot(tint, vec3(.333)), .05);
    vec3 bare = ICE_BARE * mix(vec3(1.0), tint, .35) * (1.0 + cloudy * .35) + vec3(.55) * cracks;
    // Snow grain from the drift streaks and the ice's height field.
    vec3 snowColor = mix(ICE_BARE, ICE_SNOW, depth) * (.92 + .12 * drift + .12 * (properties.r - .5));
    if (slab > .5) {
        if (lead) {
            // An open lead along the shore: still, dark water under a skim of slush, mirroring the sky.
            float slush = smoothstep(.5, .85, properties.a) * .55;
            vec3 water = mix(ICE_DEEP * lit, ICE_BARE * lit * .8, slush);
            float alpha = mix(.86, .95, slush);
            fragColor.a = alpha;
            return toDisplay((water * alpha * (1.0 - fresnel) + sky * fresnel + sheen * (1.0 - slush)) / alpha);
        }
        if (face.z < .5) {
            // The slab's cut edge: light scattered through the ice leaves it blue-green, whiter where it is frosted.
            vec3 edge = mix(vec3(.42, .70, .76), ICE_SNOW, .25 + .4 * cloudy) * lit;
            fragColor.a = .94;
            return toDisplay(edge * (1.0 - fresnel) + sky * fresnel + sheen);
        }
        // Over the already-drawn bed and water: bare ice scatters part of the light and darkens what it transmits;
        // snow and slush replace it; open leads show the water; reflection takes over at grazing angles.
        float bareAlpha = .5 + .25 * cloudy + .3 * cracks;
        vec3 body = mix(mix(ICE_DEEP, bare, .8) * lit * bareAlpha, snowColor * lit, snow);
        float bodyAlpha = mix(bareAlpha, 1.0, snow);
        float jointAlpha = mix(.85, .1, margin);
        body = mix(body, mix(ICE_DEEP, ICE_JOINT, 1.0 - margin) * lit * jointAlpha, joint);
        bodyAlpha = mix(bodyAlpha, jointAlpha, joint);
        float alpha = fresnel + (1.0 - fresnel) * bodyAlpha;
        fragColor.a = alpha;
        return toDisplay((body * (1.0 - fresnel) + sky * fresnel + sheen) / max(alpha, .001));
    }
    // Land ice and glaze over their opaque support: the ground shows darker and cooler beneath bare ice, and more
    // plainly where the ice thins toward a broken edge, whose fracture faces catch the light.
    vec3 beneath = toLinear(underlying) * mix(vec3(.55, .63, .70), vec3(.80, .85, .88), thin);
    float glazing = mix(.45 + .25 * cloudy, .08 + .3 * cracks, thin) * mix(.35, 1.0, thickness);
    vec3 glaze = mix(beneath, bare * lit, glazing);
    vec3 body = mix(mix(glaze, snowColor * lit, snow), ICE_JOINT * lit, joint);
    body += ICE_SNOW * lit * rim * .35 * (1.0 - thin * .6);
    vec3 iced = body * (1.0 - fresnel) + sky * fresnel + sheen;
    return mix(underlying, toDisplay(iced), coverage);
}
#endif
