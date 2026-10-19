// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by geometry and shading: how much of each wave cascade this water carries. Long waves need depth, width
// and open water upwind to grow; bays, narrows and shallows keep only the shorter chop and ripples, and every
// cascade dies out right at a bank. Open water that runs off the board continues beyond it (GpuWaterExposure).
uniform sampler2D u_waterExposure; // GpuWaterExposure: open water from the west, east, south and north
uniform vec4 u_waterExposureMap;
const float WATER_FETCH = 600.0;   // metres of open water the exposure map encodes (GpuWaterExposure.FETCH_METRES)
const float SHORE_RANGE = 0.4;     // hex widths of bank distance the field encodes (GpuWaterShader.SHORE_RANGE)
const float HEX_METRES = 30.0;     // BoardRelief.metres: a hex is 30 m across at any scale
const float OCEAN_SIZE = 128.0;    // texels along a side of each ocean cascade (GpuOcean.SIZE)

// Metres of open water from the west, east, south and north; zero on descents, falls, ice and land.
vec4 waterFetch(vec2 world) {
    return textureLod(u_waterExposure, world * u_waterExposureMap.xy + u_waterExposureMap.zw, 0.0) * WATER_FETCH;
}

// Field.prepare stores still current as byte128. Keep filtering affine around that exact zero.
vec2 waterCurrent(vec4 field) { return (field.ba * 255.0 - 128.0) / 127.0; }

// Visual bottom drag: a shallow film carries slower, smaller ripples. Use the continuous optical column rather
// than the hex's depth label, so the motion eases over shelves and bars without another tile boundary.
vec2 waterSurfaceCurrent(vec4 field) {
    float depth = field.g * 4.0;
    return waterCurrent(field) * mix(.2, 1.0, smoothstep(.02, .45, depth));
}

// One blend for wave suppression, current detail and shore response. Use the whole ordinary current's range
// (0.10 hex widths/s), so a river mouth uses the field's full spatial fade instead of switching near its still end.
float waterFlowShare(vec2 current) { return smoothstep(0.0, .10, length(current)); }

// How much of the wind's waves the water carries for its current (field BA, hex widths per second): all of them on
// still water. Running water in calm or light air shows only its own current-borne ripples, so a river never seems to
// run backwards under a gentle opposing wind; a strong wind roughens it too, most of the way in a gale.
float waterWindShare(vec4 field) {
    float flowing = waterFlowShare(waterSurfaceCurrent(field));
    return mix(1.0, .85 * smoothstep(.25, .85, u_waterWind.z), flowing);
}

// Swell, chop and ripple shares, each 0..1.
vec3 waterWaveEnergy(vec2 world, vec4 field, vec4 fetch) {
    float bank = (field.r * 2.0 - 1.0) * SHORE_RANGE * HEX_METRES;
    float depth = field.g * 4.0 * u_levelHeight / u_metre;
    vec2 wind = dot(u_waterWind.xy, u_waterWind.xy) > .0001 ? normalize(u_waterWind.xy) : vec2(.8, .6);
    vec4 toward = max(vec4(wind.x, -wind.x, wind.y, -wind.y), vec4(0.0));
    float upwind = dot(fetch, toward) / max(dot(toward, vec4(1.0)), .001);
    // Residual swell comes from distant weather, so a zero local wind must not shelter the coast according to
    // an arbitrary wind bearing. As the local wind grows, its fetch takes over the long band too.
    float openReach = max(max(fetch.x, fetch.y), max(fetch.z, fetch.w));
    float swellFetch = mix(openReach, upwind, smoothstep(0.0, .25, u_waterWind.z));
    float across = min(fetch.x + fetch.y, fetch.z + fetch.w);
    return smoothstep(vec3(1.0, .3, 0.0), vec3(10.0, 4.0, 1.0), vec3(bank))
          * smoothstep(vec3(.5, .1, 0.0), vec3(6.0, 1.5, .25), vec3(depth))
          * smoothstep(vec3(25.0, 4.0, 0.0), vec3(110.0, 30.0, 4.0), vec3(across))
          * smoothstep(vec3(40.0, 5.0, 0.0), vec3(450.0, 90.0, 10.0), vec3(swellFetch, upwind, upwind))
          * waterWindShare(field);
}
