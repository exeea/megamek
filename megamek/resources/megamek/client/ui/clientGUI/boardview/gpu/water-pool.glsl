// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The pool and a waterfall's rounded crest use exactly the same surface calculation.
void waterHabitat(out vec4 habitat, out vec4 mixture) {
    habitat = vec4(0.0);
    mixture = waterPalette(u_waterMaterial.x);
    vec4 fringe, connected;
    terrainCoverage(v_cloudPosition / u_metre, 14.0, (v_cloudPosition.z + u_waterLine) / u_levelHeight,
          habitat, fringe, connected);
    if (dot(connected, vec4(1.0)) > .5) mixture = connected;
}

vec4 waterPool(bool falling, vec4 habitat, vec4 mixture, vec3 tint, vec3 authored, WaterLighting illumination) {
    bool procedural = u_waterMaterial.y > 0.5;
    vec2 rest = v_cloudPosition.xy;
#ifdef waterSurfaceFlag
    rest = v_waterRest;
#endif
    vec2 position = rest * u_rainScale;
    float effects = u_waterEffects;
    float detail = u_rainDetail * effects;
    float churn = mix(0.55, 1.0, u_rainDetail) * effects;
    vec3 scatter = waterScatter(mixture), froth = waterFroth(mixture, tint);
    vec3 view = -viewDirection(), color;
    float alpha;
    // The pool's own surface. A fall starts with exactly this look where it leaves its pool, from the same field,
    // ripples, foam and agitation at the same place, so the two join without a seam; only its curving lip then
    // turns into falling water.
    vec3 surfaceNormal = normalize(v_normal);
    vec4 field = texture(u_waterField, rest * u_waterFieldMap.xy + u_waterFieldMap.zw);
    float shore = (field.r * 2.0 - 1.0) * SHORE_RANGE;
    float depth = field.g * WATER_DEPTH_RANGE;
    float wetShore = falling ? 0.0 : clamp(dot(habitat.yzw, vec3(3.0)), 0.0, 1.0)
          * (1.0 - smoothstep(.12, .65, depth)) * mix(.45, 1.0, mixture.x);
    vec2 current = (field.ba * 2.0 - 1.0) * effects;
    float agitation = (falling ? v_color.a : v_color.r) * effects;

    vec2 wind = length(u_wind.xy) > 0.01 ? normalize(u_wind.xy) : vec2(0.8, 0.6);
    // Broad noise, slow enough never to shear an advected pattern: G staggers the flow's restarts, B gathers
    // rapids foam into clusters a hex or two across, R varies the deep water's tone and the surf along a bank.
    vec4 broad = texture(u_rainNoise, position * 0.008 + 0.29);
    // Gusts roll downwind in patches, so the wind never roughens a lake evenly.
    float gust = texture(u_rainNoise, position * 0.035 - wind * (u_rainTime * 0.006)).r;

    // Foam grain and, in rapids, fine chop. A current advects them in two phases, each restarting only while
    // its weight is zero, however weak the current, so no restart ever shows; in still water both phases read
    // the same texels and nothing pulses. Samples stay outside non-uniform branches: mip selection stays defined.
    float speed = length(current);
    float flowing = smoothstep(0.004, 0.02, speed);
    float phase = fract(u_rainTime / FLOW_CYCLE + broad.g);
    float weightA = 1.0 - abs(2.0 * phase - 1.0);
    float weightB = 1.0 - weightA;
    // Uncorrelated patterns blend with preserved variance, so flowing water never calms mid-cycle.
    float preserve = mix(1.0, inversesqrt(weightA * weightA + weightB * weightB), flowing);
    vec2 advectedA = position - current * ((phase - 0.5) * FLOW_CYCLE);
    vec2 advectedB = position - current * ((fract(phase + 0.5) - 0.5) * FLOW_CYCLE) + 0.37 * flowing;
    mat2 downwind = mat2(wind.x, -wind.y, wind.y, wind.x);
    mat2 crossing = CROSSING * downwind;
    vec2 driftB = vec2(0.37, -0.29) * (u_rainTime * 0.03);
    vec4 small = ((texture(u_waterDetail, crossing * advectedA * 3.8 + driftB) - 0.5) * weightA
          + (texture(u_waterDetail, crossing * advectedB * 3.8 + driftB) - 0.5) * weightB) * preserve;
    small.rg = small.rg * crossing;
    // Rapids churn in larger, faster-moving patches than the fine surface grain. Keep one clock and blend
    // their contribution by agitation: multiplying time by local agitation would shear the texture at joins.
    vec4 rapidDetail = ((texture(u_waterDetail, crossing * advectedA * 0.65 + driftB * 4.0) - 0.5) * weightA
          + (texture(u_waterDetail, crossing * advectedB * 0.65 + driftB * 4.0) - 0.5) * weightB) * preserve;
    rapidDetail.rg = rapidDetail.rg * crossing;

    // Shape, normals, crest light and foam share one spectrum at the undisplaced position. Fine capillary
    // ripples add surface detail without inventing unrelated broad crests or repeating bright plastic facets.
    vec2 oceanUV = rest * u_waterOceanScale;
    vec4 swell = vec4(0.0);
    float noise;
    if (u_waterOceanScale > 0.0) {
        swell = texture(u_waterOcean, oceanUV);
        vec4 longWave = texture(u_waterOcean, WATER_SWELL_TURN * oceanUV * WATER_SWELL_SCALE + .37);
        swell.xy += (longWave.xy * WATER_SWELL_TURN) * (WATER_SWELL_WEIGHT * WATER_SWELL_SCALE);
        swell.z += longWave.z * WATER_SWELL_WEIGHT;
        swell.w = max(swell.w, longWave.w * WATER_SWELL_WEIGHT);
        noise = small.b;
    } else {
        vec4 large = texture(u_waterDetail, downwind * position * 1.65 + vec2(u_rainTime * 0.018, 0.0)) - 0.5;
        swell.xy = large.rg * downwind * 0.5;
        noise = large.b * 0.6 + small.b * 0.4;
    }
    float waves = waterWaveEnergy(rest, field) * effects;
    float marshShare = habitat.y / max(dot(habitat.yzw, vec3(1.0)), .0001);
    waves *= 1.0 - wetShore * mix(.75, .25, marshShare);
    // Slopes are the surface's gradient: the normal leans away from the way the water rises.
    vec2 slope = swell.xy * waves + (small.rg * (0.015 + 0.035 * u_wind.z) * detail
          + rapidDetail.rg * (0.4 * agitation)) * effects;
    vec3 ripples = rainRippleField(position) * effects;
    slope += ripples.xy;

    // Surf: the swell feels the bottom near a bank and rolls in as bands whose crests march shoreward, broken up
    // along the bank. The bank's direction comes from the field itself, so the surf turns with every bay.
    vec2 fieldUV = rest * u_waterFieldMap.xy + u_waterFieldMap.zw;
    vec2 seaward = vec2(texture(u_waterField, fieldUV + vec2(u_waterFieldMap.x * 3.0, 0.0)).r,
          texture(u_waterField, fieldUV + vec2(0.0, u_waterFieldMap.y * 3.0)).r) - field.r;
    seaward /= max(length(seaward), 1e-5);
    // Surf needs open water to build over: a narrow pool or channel, whose far bank is close, stays without it.
    float beyond = (texture(u_waterField, fieldUV + seaward * u_waterFieldMap.xy * (0.35 / u_rainScale)).r
          * 2.0 - 1.0) * SHORE_RANGE;
    float fetch = smoothstep(0.12, 0.25, beyond - shore);
    float nearBank = (1.0 - smoothstep(0.05, 0.28, shore)) * smoothstep(0.0, 0.01, shore) * fetch;
    float march = shore * 65.0 + u_rainTime * 1.6 + broad.r * 11.0 + gust * 5.0;
    float surfEnergy = mix(.08, 1.0, u_wind.z) * fetch;
    slope += seaward * (cos(march) * 0.16 * nearBank * surfEnergy * effects * (1.0 - wetShore * .9));

    float wading = waterUnitWakes(v_cloudPosition.xy, noise, effects, slope);
    WaterImpact landing = waterFallImpacts(position, shore, noise, phase, vec2(weightA, weightB), effects, slope);
    float boil = landing.boil, bubbles = landing.bubbles, impact = landing.foam;
    vec3 normal = normalize(surfaceNormal + vec3(-slope, 0.0));

    // Banks: a crisp line where the water touches, never thinner than a pixel so it holds still from afar; the
    // surf washing up and back over a band that pulses with each wave, and the foam it leaves trailing in drifts.
    float pixel = fwidth(shore);
    float contact = smoothstep(0.0, 0.003 + pixel, shore)
          * (1.0 - smoothstep(0.006 + pixel, 0.016 + 2.0 * pixel, shore + noise * 0.012));
    // Foam covers the water densely at the bank and breaks into drifts further out; the band pulses with each
    // wave, varies along the bank and swells again where a roller breaks.
    float surge = 0.5 + 0.5 * sin(u_rainTime * 1.1 + broad.r * 13.0 + gust * 6.0);
    float width = mix(0.045, 0.11, gust) * (0.6 + 0.4 * surge) * (0.7 + 0.6 * broad.b) * mix(0.45, 1.0, fetch);
    float grainy = noise + 0.5;
    float coverage = exp(-shore / width) + 0.3 * smoothstep(0.55, 0.95, sin(march)) * nearBank * surfEnergy;
    float surf = smoothstep(1.0 - coverage, 1.2 - coverage, grainy) * smoothstep(0.0, 0.003, shore);
    // Whitecaps where the simulated crests fold over, in open water only, grained by the ripple foam.
    float whitecap = smoothstep(0.3, 0.85, swell.w * (0.5 + grainy)) * waves;
    // Strong rapids increase motion and the number of broken foam patches, while leaving water between them.
    // The fine grain frays their edges; the caustic network must not turn the entire reach into a white web.
    float threshold = 0.70 - 0.16 * agitation + 0.16 * (0.5 - broad.b);
    float rapids = smoothstep(threshold, threshold + 0.18, rapidDetail.b * 0.8 + small.b * 0.2 + 0.5)
          * smoothstep(0.05, 0.3, agitation);
    // The foam round a unit breaks up like any other: dense where the water piles up, in drifts further out.
    float stirred = smoothstep(1.0 - 1.25 * wading, 1.15 - 1.25 * wading, grainy);
    float foam = clamp(max(max(max(contact * .55, surf * mix(.25, .85, surfEnergy)), max(whitecap, stirred)), max(rapids, impact)) * churn,
          0.0, 1.0);
    // A thin wash over sand is mostly clear, with only a trace of foam. Use the actual water column so the
    // effect fades around emerging bars and rocks instead of painting the old hex-shaped shoreline white.
    float foamWater = falling ? 1.0 : mix(0.035, 1.0, smoothstep(0.06, 0.45, depth))
          * smoothstep(0.0, 0.006, shore);
    foam *= foamWater * (1.0 - wetShore * .94);

    vec3 kept = waterTransmission(mixture, depth);
    float facing = clamp(dot(normal, view), 0.0, 1.0);
    // Air/water Fresnel: clear from above, reflective at grazing angles.
    float fresnel = 0.02037 + 0.97963 * pow(1.0 - facing, 5.0);
    vec3 reflected = reflect(viewDirection(), normal);
    // A brighter band low in the sky, so waves reflecting lower show lighter.
    vec3 sky = mix(u_rainHorizon * 1.15, u_rainSky, smoothstep(0.0, 1.0, reflected.z));
#ifdef cloudShadowFlag
    // The clouds overhead stand in the water: where the reflected ray meets the cloud base, the shadow atlas
    // tells how much cloud there is.
    if (reflected.z > 0.05) {
        float base = -u_cloudProjection[3][2] / u_cloudProjection[2][2];
        vec3 overhead = v_cloudPosition + reflected * ((base - v_cloudPosition.z) / reflected.z);
        sky = mix(sky, illumination.whiteLight * 0.9, (1.0 - cloudTransmission(overhead)) * 0.8);
    }
#endif
    vec3 glint = vec3(0.0);
    vec3 glow = vec3(0.0);
    vec3 facets = illumination.light;
    vec2 key = vec2(-0.6, 0.8);
#if defined(lightingFlag) && numDirectionalLights > 0
    vec3 toSun = -u_dirLights[0].direction;
    key = normalize(toSun.xy + vec2(0.0, 0.001));
    // Wave faces turned toward the sun catch more of it: the waves read even where nothing reflects.
    facets += illumination.sunlight * (max(dot(normal, toSun), 0.0) - toSun.z) * 0.45;
    // The sun's glitter, from facets as rough as the waves a pixel averages: close up each wave flashes, far off
    // their spread becomes a broad path of glitter instead of aliasing into lines.
    vec2 texels = fwidth(u_waterOceanScale > 0.0 ? oceanUV : position * 2.0) * OCEAN_SIZE;
    float roughness = clamp(0.12 + 0.06 * log2(1.0 + max(texels.x, texels.y)), 0.12, 0.45);
    float a2 = roughness * roughness * roughness * roughness;
    vec3 halfway = normalize(view + toSun);
    float schlick = 0.02 + 0.98 * pow(1.0 - clamp(dot(halfway, view), 0.0, 1.0), 5.0);
    float nh = max(dot(normal, halfway), 0.0);
    float spread = nh * nh * (a2 - 1.0) + 1.0;
    glint = illumination.sunlight * schlick * a2 / (3.14159 * spread * spread) * step(0.0, dot(normal, toSun))
          / (4.0 * max(facing, 0.1)) * 0.6 * effects;
    // Thin crests let the sun through, brightest when the viewer looks toward it: they glow like the shallows.
    float behind = max(dot(normalize(viewDirection().xy + 1e-5), normalize(toSun.xy + 1e-5)), 0.0);
    float crest = smoothstep(.12, .8, swell.z) * smoothstep(.02, .25, length(swell.xy));
    glow = waterShallows(mixture) * illumination.sunlight * (crest * (0.12 + 0.88 * behind)
          * 0.18 * waves);
#endif
    // The brighter side of the sky lights the faces tilted toward it, even overcast or seen from straight above.
    facets *= clamp(1.0 - dot(slope, key) * 0.25, 0.7, 1.3);
    vec3 body;
    float bodyAlpha;
    if (procedural) {
        // Physical absorption sets the in-scattered light; pale shallows keep a floor of turquoise. The opacity
        // is capped, and the bed shaders take the rest of the absorption, so submerged units stay readable.
        float column = max(1.0 - max(max(kept.r, kept.g), kept.b), 0.3 * smoothstep(0.0, 0.1, depth));
        // Deep water varies a little in tone over a few hexes, as depth and silt do, instead of one flat blue.
        vec3 deep = scatter * mix(0.86, 1.12, broad.r);
        body = mix(waterShallows(mixture), deep, smoothstep(0.1, 1.6, depth)) * facets * column;
        bodyAlpha = min(column, WATER_MAX_OPACITY);
    } else {
        bodyAlpha = 1.0;
        body = authored * tint * facets;
    }
    // Suspended peat/silt joins shallow marsh pools and bare mud to open water over several metres.
    vec3 wetColor = mix(vec3(.105, .14, .072), vec3(.24, .195, .105),
          clamp((habitat.z + habitat.w) / max(dot(habitat.yzw, vec3(1.0)), .0001), 0.0, 1.0));
    body = mix(body, wetColor * facets * bodyAlpha, wetShore);
    body += glow * bodyAlpha * (1.0 - wetShore);
    // Wind-roughened faces scatter a soft image of the sky across the column: waves show under any light.
    body += sky * (bodyAlpha * (1.0 - fresnel) * 0.15);
    // Raindrop rings: each crest and trough catches the light differently.
    body *= 1.0 + clamp(ripples.z, -1.0, 1.0) * 0.35;
    // Churning water carries air: paler and more opaque long before it breaks into foam; spray thrown up by a
    // fall lights the water around it.
    float aerated = max(agitation * mix(0.10, 0.35, rapids), boil * boil * 0.5) * churn * foamWater;
    body = mix(body, froth * mix(facets, illumination.whiteLight, 0.5) * 0.85, aerated);
    bodyAlpha = mix(bodyAlpha, 0.85, aerated);
    // The column thins to nothing at the bank, so water meets its shore without a drawn edge.
    float edge = smoothstep(0.0, 0.02, shore);
    color = (body * (1.0 - fresnel) + sky * fresnel + glint * (1.0 - foam)) * edge;
    alpha = (bodyAlpha * (1.0 - fresnel) + fresnel) * edge;
    // Foam is never flat white: thicker and thinner froth, and bubbles churning below a fall, whose freshly
    // aerated water glows brightest.
    float grain = mix(noise + 0.5, bubbles, boil);
    color = mix(color, froth * illumination.whiteLight * ((0.78 + 0.34 * grain) * (1.0 + 0.25 * boil)), foam);
    alpha = mix(alpha, 1.0, foam);
    float grid = terrainGrid(position);
    color *= grid;
    alpha = 1.0 - (1.0 - alpha) * grid;
    return vec4(color, alpha);
}
