// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Water surfaces, falls and spray. Output is premultiplied: the surface adds reflected and scattered light and
// removes only what its column absorbs from whatever lies behind it, whose hue the bed shader already shifted.
// One field sample supplies bank distance, depth and current, so every input is continuous across hexes; the wind
// waves come from the ocean simulation (GpuOcean), the fine ripples and foam grain from the shared detail map.
#ifdef GL_ES
precision highp float;
#endif
varying vec2 v_diffuseUV;
varying vec3 v_normal;
varying vec4 v_color;
varying float v_opacity;
uniform sampler2D u_diffuseTexture;
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
uniform sampler2D u_waterField;  // R signed bank distance, G optical depth, BA current
uniform vec4 u_waterFieldMap;    // world XY to field UV: scale XY, offset XY
uniform vec4 u_waterMaterial;    // palette, falling sheet, procedural color, spray particles
uniform float u_waterEffects;
uniform vec3 u_wind;
uniform int u_splashCount;
uniform vec4 u_splashLines[12];  // where falls land nearby: from XY, to XY, the pool to the right; hex widths
uniform float u_splashRadii[12]; // radius of each landing's boil, hex widths
uniform sampler2D u_waterOcean;  // GpuOcean: RG wave slope, B crest compression, A foam; tiles
uniform float u_waterOceanScale; // world XY to ocean UV; zero without the simulation
uniform float u_metre;           // world units per metre
uniform float u_levelHeight;     // world units per level
uniform int u_waderCount;
uniform vec4 u_waders[12];       // GpuWaders: centre XY, radius at the waterline, water level; world units
uniform vec4 u_waderMotion[12];  // velocity XY, world units per second
const float SHORE_RANGE = 0.4;   // hex widths encoded by the field's red channel
const float FLOW_CYCLE = 2.4;    // seconds per two-phase advection cycle
const float OCEAN_SIZE = 128.0;  // texels along a side of the ocean texture
// A fixed turn of the finer layer, so its wave trains cross the swell's. Being constant, it cannot shear.
const mat2 CROSSING = mat2(0.52, 0.85, -0.85, 0.52);
const mat2 CROSSING2 = mat2(-0.46, 0.888, -0.888, -0.46);

void main() {
    float palette = u_waterMaterial.x;
    bool falling = u_waterMaterial.y > 0.5;
    bool procedural = u_waterMaterial.z > 0.5;
    bool spray = u_waterMaterial.w > 0.5;
    // The water's cut face where the board's edge cuts it off (GpuTerrain.waterCut): blue marks it, green is its depth
    // below the surface over WATER_DEPTH_RANGE. A fall's vertex colour means other things.
    bool cut = !falling && v_color.b > 0.5;
    vec2 position = v_cloudPosition.xy * u_rainScale;
    float effects = u_waterEffects;
    float detail = u_rainDetail * effects;
    // White water keeps part of its strength far off, where its patterns blur into their mean instead of vanishing.
    float churn = mix(0.55, 1.0, u_rainDetail) * effects;
    vec3 tint = vec3(1.0);
#ifdef diffuseColorFlag
    tint = u_diffuseColor.rgb;
#endif
    vec3 scatter = waterScatter(palette);
    // Clear water foams white; silty and chemical water foams in the color of what it carries.
    vec3 froth = mix(vec3(0.94, 0.97, 1.0), scatter / max(max(scatter.r, scatter.g), scatter.b),
          palette < 0.5 ? 0.08 : 0.3) * tint;

    // Level-surface irradiance for the column, foam, mist and falls alike: white water scatters light through its
    // whole volume, so a fall's foam reads exactly like the foam it lands in.
    vec3 lightNormal = vec3(0.0, 0.0, 1.0);
    vec3 albedo = vec3(1.0);
    vec3 sunlight = vec3(0.0), sunLinear = vec3(0.0);
#ifdef lightingFlag
    vec3 ambient, direct, sheen;
    surfaceLighting(lightNormal, 0.0, ambient, direct, sheen);
    albedo *= ambient + direct;
    // The light arrives linear (light-model.glsl). Water's colours are authored display-encoded and multiply or add
    // display-equivalent light: toDisplay(toLinear(colour) * light) is exactly colour * toDisplay(light).
    albedo = toDisplay(albedo);
#if numDirectionalLights > 0
    // Shadowed, cloud-filtered sunlight arriving along the sun direction, for the reflected glints.
    sunLinear = direct / max(dot(lightNormal, -u_dirLights[0].direction), 0.05);
    sunlight = toDisplay(sunLinear);
#endif
#endif
    vec3 light = albedo;
    // White water scatters light through its whole volume: even in shade it passes on some of the sun's light.
    vec3 whiteLight = light;
#if defined(lightingFlag) && numDirectionalLights > 0
    whiteLight = max(light, toDisplay(ambient + sunOnGround() * 0.4));
#endif
    vec3 view = -viewDirection();
    vec3 color;
    float alpha;

    if (spray) {
        // Spray where a fall lands (GpuWaterfall): dense white puffs bursting up, bright droplets flung out in arcs
        // and mist billowing away, each particle a camera-facing quad that the vertex shader launched. Its colour
        // carries age, size seed, whether it is in the air and its kind.
        float age = v_color.r;
        float mist = step(0.75, v_color.a), droplet = step(0.25, v_color.a) - mist;
        vec2 corner = v_diffuseUV * 2.0 - 1.0;
        float ragged = texture2D(u_waterDetail, v_diffuseUV * mix(0.45, 0.3, mist) + v_color.g * 7.31 + age * 0.15).b;
        float grain = texture2D(u_waterDetail, v_diffuseUV * 1.3 + v_color.g * 3.7 - age * 0.3).b;
        // Puffs and mist are ragged and soft; a droplet is a small, crisp streak along its flight.
        float soft = 1.0 - smoothstep(mix(0.15, 0.0, mist), 1.0, length(corner) + (ragged - 0.5) * 0.9);
        float bead = (1.0 - smoothstep(0.1, 1.0, length(corner))) * (0.45 + 0.55 * smoothstep(-1.0, 0.6, corner.y));
        float shape = mix(soft, bead, droplet);
        // Puffs burst out dense and thin as they scatter; droplets stay bright until they drop back; mist swells and
        // fades away.
        float fade = smoothstep(0.0, 0.06, age) * (1.0 - smoothstep(mix(mix(0.55, 0.8, droplet), 0.3, mist), 1.0, age))
              * v_color.b;
        float density = mix(mix(0.95 * mix(0.65, 1.0, grain), 0.9, droplet), 0.3, mist);
        alpha = shape * fade * density * mix(0.6, 1.0, detail) * effects;
        // Spray scatters sunlight forward: it glows when seen against the sun.
        vec3 mistLight = whiteLight * 1.15;
#if defined(lightingFlag) && numDirectionalLights > 0
        float against = max(dot(normalize(viewDirection()), -u_dirLights[0].direction), 0.0);
        mistLight += sunlight * (against * against * 0.5);
#endif
        color = froth * mistLight * alpha * mix(mix(1.1, 1.25, droplet), 1.0, mist);
    } else if (cut) {
        // A clean section through the water body: the light it scatters at each depth, dimmed as the daylight is
        // absorbed on its way down, hiding more of what lies behind it the deeper it runs. Waves, foam, glints, rain
        // and the grid belong to the surface. Seen from inside, through the surface, it is not drawn.
        if (!gl_FrontFacing) discard;
        vec3 kept = waterTransmission(palette, v_color.g * WATER_DEPTH_RANGE);
        alpha = 1.0 - (1.0 - WATER_MAX_OPACITY) * max(max(kept.r, kept.g), kept.b);
        color = scatter * light * kept * alpha;
    } else {
        // The pool's own surface. A fall starts with exactly this look where it leaves its pool, from the same field,
        // ripples, foam and agitation at the same place, so the two join without a seam; only its curving lip then
        // turns into falling water.
        vec3 surfaceNormal = normalize(v_normal);
        vec4 field = texture2D(u_waterField, v_cloudPosition.xy * u_waterFieldMap.xy + u_waterFieldMap.zw);
        float shore = (field.r * 2.0 - 1.0) * SHORE_RANGE;
        float depth = field.g * WATER_DEPTH_RANGE;
        vec2 current = (field.ba * 2.0 - 1.0) * effects;
        float agitation = (falling ? v_color.a : v_color.r) * effects;

        vec2 wind = length(u_wind.xy) > 0.01 ? normalize(u_wind.xy) : vec2(0.8, 0.6);
        // Broad noise, slow enough never to shear an advected pattern: G staggers the flow's restarts, B gathers
        // rapids foam into clusters a hex or two across, R varies the deep water's tone and the surf along a bank.
        vec4 broad = texture2D(u_rainNoise, position * 0.008 + 0.29);
        // Gusts roll downwind in patches, so the wind never roughens a lake evenly.
        float gust = texture2D(u_rainNoise, position * 0.035 - wind * (u_rainTime * 0.006)).r;
        // Open water carries the full swell; shallows and the lee of a bank run calmer.
        float open = smoothstep(0.0, 0.7, depth) * smoothstep(0.0, 0.12, shore);

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
        vec4 small = ((texture2D(u_waterDetail, crossing * advectedA * 3.8 + driftB) - 0.5) * weightA
              + (texture2D(u_waterDetail, crossing * advectedB * 3.8 + driftB) - 0.5) * weightB) * preserve;
        small.rg = small.rg * crossing;
        // Rapids churn in larger, faster-moving patches than the fine surface grain. Keep one clock and blend
        // their contribution by agitation: multiplying time by local agitation would shear the texture at joins.
        vec4 rapidDetail = ((texture2D(u_waterDetail, crossing * advectedA * 0.65 + driftB * 4.0) - 0.5) * weightA
              + (texture2D(u_waterDetail, crossing * advectedB * 0.65 + driftB * 4.0) - 0.5) * weightB) * preserve;
        rapidDetail.rg = rapidDetail.rg * crossing;

        // Wind waves: every wave of the ocean simulation travels at its own speed, so the surface keeps changing
        // instead of sliding. Four scales, each turned against the others: long swells a few hexes across, the
        // simulated waves, a chop and, seen up close, ripples; together they never show the simulation's tile.
        // Without the simulation the static ripple map stands in.
        vec2 oceanUV = v_cloudPosition.xy * u_waterOceanScale;
        vec4 swell = vec4(0.0);
        float noise;
        if (u_waterOceanScale > 0.0) {
            vec4 wide = texture2D(u_waterOcean, CROSSING * oceanUV * 0.43 + 0.57);
            vec4 main = texture2D(u_waterOcean, oceanUV);
            vec4 chop = texture2D(u_waterOcean, oceanUV * CROSSING * 2.37 + 0.31);
            vec4 ripple = texture2D(u_waterOcean, oceanUV * CROSSING2 * 5.3 + 0.77);
            swell.xy = (wide.xy * CROSSING) * 0.9 + main.xy + (chop.xy * CROSSING) * (0.5 + 0.2 * detail)
                  + (ripple.xy * CROSSING2) * (0.2 + 0.4 * detail);
            swell.z = max(main.z, max(wide.z * 0.8, chop.z * 0.5));
            swell.w = max(main.w, max(wide.w, chop.w * 0.6));
            noise = small.b + (main.z - 0.1) * 0.5;
        } else {
            vec4 large = texture2D(u_waterDetail, downwind * position * 1.65 + vec2(u_rainTime * 0.018, 0.0)) - 0.5;
            swell.xy = large.rg * downwind * 0.5;
            noise = large.b * 0.6 + small.b * 0.4;
        }
        float waves = (mix(0.75, 1.35, gust) * mix(0.45, 1.0, open) + 0.9 * agitation) * effects;
        // Slopes are the surface's gradient: the normal leans away from the way the water rises.
        vec2 slope = swell.xy * waves + (small.rg * 0.06 + rapidDetail.rg * (0.4 * agitation)) * effects;
        vec3 ripples = rainRippleField(position) * effects;
        slope += ripples.xy;

        // Surf: the swell feels the bottom near a bank and rolls in as bands whose crests march shoreward, broken up
        // along the bank. The bank's direction comes from the field itself, so the surf turns with every bay.
        vec2 fieldUV = v_cloudPosition.xy * u_waterFieldMap.xy + u_waterFieldMap.zw;
        vec2 seaward = vec2(texture2D(u_waterField, fieldUV + vec2(u_waterFieldMap.x * 3.0, 0.0)).r,
              texture2D(u_waterField, fieldUV + vec2(0.0, u_waterFieldMap.y * 3.0)).r) - field.r;
        seaward /= max(length(seaward), 1e-5);
        // Surf needs open water to build over: a narrow pool or channel, whose far bank is close, stays without it.
        float beyond = (texture2D(u_waterField, fieldUV + seaward * u_waterFieldMap.xy * (0.35 / u_rainScale)).r
              * 2.0 - 1.0) * SHORE_RANGE;
        float fetch = smoothstep(0.12, 0.25, beyond - shore);
        float nearBank = (1.0 - smoothstep(0.05, 0.28, shore)) * smoothstep(0.0, 0.01, shore) * fetch;
        float march = shore * 95.0 + u_rainTime * 1.6 + broad.r * 11.0 + gust * 5.0;
        slope += seaward * (cos(march) * 0.5 * nearBank * mix(0.6, 1.0, gust) * effects);

        // Units standing in the water: it piles against each in a broken, swelling collar, sends ripples out and,
        // behind a moving one, spreads into a wake: two arms of foam at the angle every wake keeps, churned water
        // right behind it.
        float wading = 0.0;
        for (int i = 0; i < 12; i++) {
            if (i >= u_waderCount) break;
            vec2 delta = v_cloudPosition.xy - u_waders[i].xy;
            float radius = u_waders[i].z;
            float apart = length(delta);
            float outside = apart - radius;
            float swell = 0.5 + 0.5 * sin(u_rainTime * 2.1 + float(i) * 2.3 + noise * 3.0);
            float collar = (1.0 - smoothstep(0.0, radius * (0.6 + 0.5 * swell) + 3.0, outside))
                  * smoothstep(-radius * 0.5, 0.0, outside);
            // Each wave breaking against the unit sends a ring of foam out, which thins as it spreads.
            float wave = fract(u_rainTime * 0.6 + float(i) * 0.37);
            float ring = (1.0 - smoothstep(0.0, 1.5 + wave * 2.0, abs(outside - wave * (radius * 1.2 + 8.0))))
                  * (1.0 - wave);
            collar = max(collar, ring * step(0.0, outside));
            float spread = step(0.0, outside) * exp(-outside / (radius * 2.0 + 6.0));
            slope += delta / max(apart, 0.001) * (sin(outside * 0.8 - u_rainTime * 4.5) * spread * 0.25 * effects);
            vec2 velocity = u_waderMotion[i].xy;
            float speed = length(velocity);
            vec2 heading = velocity / max(speed, 0.001);
            float behind = dot(delta, -heading);
            float across = abs(dot(delta, vec2(-heading.y, heading.x)));
            float moving = smoothstep(0.5, 4.0, speed);
            float trail = max(behind, 0.0) / (radius * 2.0 + speed * 3.0);
            float arm = abs(across - radius * 0.6 - behind * 0.36);
            float arms = (1.0 - smoothstep(0.0, radius * 0.3 + behind * 0.06, arm)) * step(0.0, behind)
                  * (1.0 - smoothstep(0.3, 1.0, trail));
            float churned = (1.0 - smoothstep(radius * 0.6, radius * 1.1, across)) * step(0.0, behind)
                  * (1.0 - smoothstep(0.0, 0.5, trail));
            wading = max(wading, max(collar, max(arms * 0.8, churned) * moving));
        }
        wading *= effects;

        // A fall churns the pool it lands in: a boil round its landing line, rings pushed outward and the churned water
        // flowing away. The landing lines of every fall nearby meet at the corners they share, so the boil runs on
        // round those corners and across hexes without a seam; behind a curtain the water churns right to the wall.
        float boil = 0.0;
        vec2 push = vec2(0.0);
        for (int i = 0; i < 12; i++) {
            if (i >= u_splashCount) break;
            vec4 line = u_splashLines[i];
            float radius = u_splashRadii[i];
            vec2 along = line.zw - line.xy;
            float t = clamp(dot(position - line.xy, along) / max(dot(along, along), 1e-6), 0.0, 1.0);
            vec2 offset = position - (line.xy + along * t);
            float gap = length(offset);
            vec2 outward = offset / max(gap, 1e-4);
            // Between the landing and the wall behind the curtain, about as far as it is thrown, all churns.
            float behind = smoothstep(0.0, 0.5, -dot(outward, normalize(vec2(along.y, -along.x))));
            float reach = 1.0 - smoothstep(radius * 0.2, radius * 1.8,
                  max(gap - 0.085 * behind, 0.0) + noise * radius * 0.6);
            float ring = sin(gap * 70.0 - u_rainTime * 8.0 + noise * 6.0);
            slope += outward * ring * reach * 0.22 * effects;
            boil = max(boil, reach);
            push += outward * reach;
        }
        // The impact breaks up before reaching a bank; shoreline foam still follows the actual waterline.
        boil *= effects * smoothstep(0.004, 0.06, shore);
        float bubbles = 0.0, impact = 0.0;
        // Without a nearby landing boil is zero, so neither bubbles nor impact contributes. The uniform branch
        // keeps mip selection defined for every fragment of materials that do have falling water nearby.
        if (u_splashCount > 0) {
            // Foam patches ride the churned water outward: advected in two phases like the current, each restarting
            // only while unseen, so the foam keeps moving away from the falls without ever shearing or pulsing.
            vec2 drift = push * (0.06 / max(length(push), 1.0));
            vec2 bubbleA = position - drift * ((phase - 0.5) * FLOW_CYCLE);
            vec2 bubbleB = position - drift * ((fract(phase + 0.5) - 0.5) * FLOW_CYCLE) + 0.37;
            bubbles = 0.5 + ((texture2D(u_waterDetail, bubbleA * 3.4 + 0.61).b - 0.5) * weightA
                  + (texture2D(u_waterDetail, bubbleB * 3.4 + 0.61).b - 0.5) * weightB)
                  * inversesqrt(weightA * weightA + weightB * weightB);
            // Solid white right below the fall, breaking into patches and then lace as the foam spreads.
            float settle = 1.0 - boil;
            impact = smoothstep(settle, settle + 0.15, bubbles * 0.6 + (noise + 0.5) * 0.4)
                  * smoothstep(0.0, 0.15, boil);
        }
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
        float coverage = exp(-shore / width) + 0.45 * smoothstep(0.55, 0.95, sin(march)) * nearBank;
        float surf = smoothstep(1.0 - coverage, 1.2 - coverage, grainy) * smoothstep(0.0, 0.003, shore);
        // Whitecaps where the simulated crests fold over, in open water only, grained by the ripple foam.
        float whitecap = smoothstep(0.3, 0.85, swell.w * (0.5 + grainy)) * open;
        // Strong rapids increase motion and the number of broken foam patches, while leaving water between them.
        // The fine grain frays their edges; the caustic network must not turn the entire reach into a white web.
        float threshold = 0.70 - 0.16 * agitation + 0.16 * (0.5 - broad.b);
        float rapids = smoothstep(threshold, threshold + 0.18, rapidDetail.b * 0.8 + small.b * 0.2 + 0.5)
              * smoothstep(0.05, 0.3, agitation);
        // The foam round a unit breaks up like any other: dense where the water piles up, in drifts further out.
        float stirred = smoothstep(1.0 - 1.25 * wading, 1.15 - 1.25 * wading, grainy);
        float foam = clamp(max(max(max(contact, surf * 0.9), max(whitecap, stirred)), max(rapids, impact)) * churn,
              0.0, 1.0);
        // A thin wash over sand is mostly clear, with only a trace of foam. Use the actual water column so the
        // effect fades around emerging bars and rocks instead of painting the old hex-shaped shoreline white.
        float foamWater = falling ? 1.0 : mix(0.035, 1.0, smoothstep(0.06, 0.45, depth))
              * smoothstep(0.0, 0.006, shore);
        foam *= foamWater;

        vec3 kept = waterTransmission(palette, depth);
        float facing = clamp(dot(normal, view), 0.0, 1.0);
        // Reflection rises more gently than Fresnel's law toward grazing: waves tilted away from the steep board
        // camera catch the sky while those facing it show the water, which is what makes waves read from above.
        float fresnel = 0.03 + 0.97 * pow(1.0 - facing, 3.0);
        vec3 reflected = reflect(viewDirection(), normal);
        // A brighter band low in the sky, so waves reflecting lower show lighter.
        vec3 sky = mix(u_rainHorizon * 1.15, u_rainSky, smoothstep(0.0, 1.0, reflected.z));
#ifdef cloudShadowFlag
        // The clouds overhead stand in the water: where the reflected ray meets the cloud base, the shadow atlas
        // tells how much cloud there is.
        if (reflected.z > 0.05) {
            float base = -u_cloudProjection[3][2] / u_cloudProjection[2][2];
            vec3 overhead = v_cloudPosition + reflected * ((base - v_cloudPosition.z) / reflected.z);
            sky = mix(sky, whiteLight * 0.9, (1.0 - cloudTransmission(overhead)) * 0.8);
        }
#endif
        vec3 glint = vec3(0.0);
        vec3 glow = vec3(0.0);
        vec3 facets = light;
        vec2 key = vec2(-0.6, 0.8);
#if defined(lightingFlag) && numDirectionalLights > 0
        vec3 toSun = -u_dirLights[0].direction;
        key = normalize(toSun.xy + vec2(0.0, 0.001));
        // Wave faces turned toward the sun catch more of it: the waves read even where nothing reflects.
        facets += sunlight * (max(dot(normal, toSun), 0.0) - toSun.z) * 1.4;
        // The sun's glitter, from facets as rough as the waves a pixel averages: close up each wave flashes, far off
        // their spread becomes a broad path of glitter instead of aliasing into lines.
        vec2 texels = fwidth(u_waterOceanScale > 0.0 ? oceanUV * 2.37 : position * 2.0) * OCEAN_SIZE;
        float roughness = clamp(0.12 + 0.06 * log2(1.0 + max(texels.x, texels.y)), 0.12, 0.45);
        float a2 = roughness * roughness * roughness * roughness;
        vec3 halfway = normalize(view + toSun);
        float schlick = 0.02 + 0.98 * pow(1.0 - clamp(dot(halfway, view), 0.0, 1.0), 5.0);
        float nh = max(dot(normal, halfway), 0.0);
        float spread = nh * nh * (a2 - 1.0) + 1.0;
        glint = sunlight * schlick * a2 / (3.14159 * spread * spread) * step(0.0, dot(normal, toSun))
              / (4.0 * max(facing, 0.1)) * 0.6 * effects;
        // Thin crests let the sun through, brightest when the viewer looks toward it: they glow like the shallows.
        float behind = max(dot(normalize(viewDirection().xy + 1e-5), normalize(toSun.xy + 1e-5)), 0.0);
        glow = waterShallows(palette) * sunlight * (smoothstep(0.02, 0.35, swell.z) * (0.35 + 0.65 * behind)
              * 0.5 * effects);
#endif
        // The brighter side of the sky lights the faces tilted toward it, even overcast or seen from straight above.
        facets *= 1.0 - dot(slope, key) * 0.6;
        vec3 body;
        float bodyAlpha;
        if (procedural) {
            // Physical absorption sets the in-scattered light; pale shallows keep a floor of turquoise. The opacity
            // is capped, and the bed shaders take the rest of the absorption, so submerged units stay readable.
            float column = max(1.0 - max(max(kept.r, kept.g), kept.b), 0.3 * smoothstep(0.0, 0.1, depth));
            // Deep water varies a little in tone over a few hexes, as depth and silt do, instead of one flat blue.
            vec3 deep = scatter * mix(0.86, 1.12, broad.r);
            body = mix(waterShallows(palette), deep, smoothstep(0.1, 1.6, depth)) * facets * column;
            bodyAlpha = min(column, WATER_MAX_OPACITY);
        } else {
            bodyAlpha = 1.0;
            body = texture2D(u_diffuseTexture, v_diffuseUV).rgb * tint * facets;
        }
        body += glow * bodyAlpha;
        // Wind-roughened faces scatter a soft image of the sky across the column: waves show under any light.
        body += sky * (bodyAlpha * (1.0 - fresnel) * 0.15);
        // Raindrop rings: each crest and trough catches the light differently.
        body *= 1.0 + clamp(ripples.z, -1.0, 1.0) * 0.35;
        // Churning water carries air: paler and more opaque long before it breaks into foam; spray thrown up by a
        // fall lights the water around it.
        float aerated = max(agitation * mix(0.10, 0.35, rapids), boil * boil * 0.5) * churn * foamWater;
        body = mix(body, froth * mix(facets, whiteLight, 0.5) * 0.85, aerated);
        bodyAlpha = mix(bodyAlpha, 0.85, aerated);
        // The column thins to nothing at the bank, so water meets its shore without a drawn edge.
        float edge = smoothstep(0.0, 0.02, shore);
        color = (body * (1.0 - fresnel) + sky * fresnel + glint * (1.0 - foam)) * edge;
        alpha = (bodyAlpha * (1.0 - fresnel) + fresnel) * edge;
        // Foam is never flat white: thicker and thinner froth, and bubbles churning below a fall, whose freshly
        // aerated water glows brightest.
        float grain = mix(noise + 0.5, bubbles, boil);
        color = mix(color, froth * whiteLight * ((0.78 + 0.34 * grain) * (1.0 + 0.25 * boil)), foam);
        alpha = mix(alpha, 1.0, foam);
        float grid = terrainGrid(position);
        color *= grid;
        alpha = 1.0 - (1.0 - alpha) * grid;
        if (falling) {
            float drop = v_color.r;    // 0 where the sheet leaves the pool, 1 where it lands
            float fray = v_color.g;    // 0 at a free side, 1 inside it and where the next fall carries on
            float height = v_color.b;  // drop height, as a fraction of four levels
            // X along the crest, running on round corners shared with the next fall, where it agrees to a whole unit:
            // every pattern across the sheet repeats a whole number of times per unit, so none breaks at a corner.
            vec2 uv = v_diffuseUV;
            // Time of flight: water crosses the crest at about 1.5 m/s and then falls freely, so every feature speeds
            // up and stretches as it falls, while one steady clock scrolls them all without ever shearing the pattern.
            float metres = height * 4.0 * u_levelHeight / max(u_metre, 0.001);
            float fallen = drop * metres;
            float flow = (sqrt(2.25 + 19.62 * fallen) - 1.5) / 9.81 - u_rainTime;
            // The streaks wander slowly across the sheet as they fall, by where they are, so neighbouring sheets agree.
            float wander = texture2D(u_rainNoise, position * 1.4 + vec2(0.13, flow * 0.05)).r - 0.5;
            float x = uv.x + wander * 0.36;
            // Long ribbons and fine threads, stretched far along the flow so the white spreads as streaks rather than
            // blobs, and the clumps tumbling inside them, each at its own scale.
            float ribbons = texture2D(u_waterDetail, vec2(x, flow * 0.07)).b;
            float threads = texture2D(u_waterDetail, vec2(x * 3.0 + 0.71, flow * 0.22)).b;
            float clumps = texture2D(u_waterDetail, vec2(x + 0.37, flow * 0.9)).b;
            // Toward the foot the sheet breaks up into tumbling, rounded billows.
            float billows = texture2D(u_waterDetail, vec2(x + 0.19, flow * 1.8 + drop * 2.0)).b;
            float foot = smoothstep(0.55, 0.95, drop);
            float foamy = mix(ribbons * 0.52 + threads * 0.36 + clumps * 0.12, billows * 0.6 + clumps * 0.4, foot);
            // Water leaves the crest clear and blue and gathers air all the way down: the white spreads from a few
            // streaks under the crest, through streaks with blue gaps, to the whole sheet at the foot. Air mixes in
            // over the metres fallen, so a tall fall is white over most of its height and a short one stays bluer.
            float aerate = max(pow(drop, 1.3) * 0.9, 1.0 - exp(-fallen / 5.0));
            float threshold = mix(0.85, 0.02, aerate);
            float white = smoothstep(threshold, threshold + 0.22, foamy);
            white = max(white, smoothstep(0.85, 1.0, drop) * aerate) * mix(0.35, 1.0, effects);
            // Ragged free sides; where the next fall carries on round a corner the sheet stays whole.
            float sides = smoothstep(0.05, 0.9, fray + (clumps - 0.5) * 0.5);
            // The white foot plunges whole into the boil it raises and only vanishes, raggedly, in its last metre.
            float dissolve = smoothstep(0.0, 0.6, metres - fallen - 0.8 * (1.0 - billows));
            float sheetFresnel = 0.03 + 0.97 * pow(1.0 - clamp(dot(surfaceNormal, view), 0.0, 1.0), 5.0);
            vec3 sheetSky = mix(u_rainHorizon, u_rainSky,
                  smoothstep(-0.1, 0.7, reflect(viewDirection(), surfaceNormal).z));
            // Clear water pouring over the crest shows the pool's blue, lit through and mirroring the sky.
            vec3 glass = procedural ? mix(waterShallows(palette), scatter, 0.35) * light * 1.1
                  : texture2D(u_diffuseTexture, v_diffuseUV).rgb * tint * light;
            sheetFresnel = max(sheetFresnel, 0.3);
            glass = (glass * (1.0 - sheetFresnel) + sheetSky * sheetFresnel) * (0.85 + 0.35 * ribbons);
            vec3 fallLight = whiteLight;
#if defined(lightingFlag) && numDirectionalLights > 0
            vec3 sun = -u_dirLights[0].direction;
            float facingSun = dot(surfaceNormal, sun);
            // White water scatters light through its thickness: faces turned to the sun glow, and light passing through
            // lifts the shaded side. The glassy crest mirrors the sun in a bright line.
            fallLight = max(whiteLight, toDisplay(ambient + sunLinear * (0.3 * max(sun.z, 0.0)
                  + 0.7 * max(facingSun, 0.0) + 0.3 * max(-facingSun, 0.0))));
            // Only water still smooth mirrors the sun: bubbles scatter the glint away as the sheet aerates.
            vec3 halfSun = normalize(view + sun);
            float glassy = (1.0 - aerate) * (1.0 - aerate);
            glass += sunlight * (0.02 + 0.98 * pow(1.0 - clamp(dot(halfSun, view), 0.0, 1.0), 5.0))
                  * pow(max(dot(surfaceNormal, halfSun), 0.0), 60.0) * 2.5 * glassy * effects;
            // The crest's rounded edge catches the light from above: a bright line along the lip.
            glass += fallLight * (0.4 * smoothstep(0.25, 0.55, surfaceNormal.z)
                  * (1.0 - smoothstep(0.75, 0.97, surfaceNormal.z)));
#endif
            // Between the streaks the water itself turns milky and opaque as bubbles fill it.
            glass = mix(glass, froth * fallLight * 0.8, aerate * 0.5);
            vec3 sheet = mix(glass, froth * fallLight * mix(0.85, 1.35, foamy), white);
            float cover = mix(mix(0.55, 0.85, aerate) + 0.15 * ribbons, 0.97, white) * sides * dissolve;
            // Still level where it leaves the pool it keeps the pool's look; once it curves over, the fall's own.
            float lipping = procedural && drop < 0.5 ? smoothstep(0.88, 0.995, surfaceNormal.z) : 0.0;
            color = mix(sheet * cover, color, lipping);
            alpha = mix(cover, alpha, lipping);
        }
    }
    color *= v_opacity;
    alpha *= v_opacity;
    if (alpha < 0.002 && max(color.r, max(color.g, color.b)) < 0.002) discard;
    gl_FragColor = vec4(color, alpha);
}
