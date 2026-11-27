// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Unit wakes and waterfall impacts share the pool's noise, animation and surface slope.

// Units standing in the water: it piles against each in a broken, swelling collar, sends ripples out and,
// behind a moving one, spreads into a wake: two arms of foam at the angle every wake keeps, churned water
// right behind it. Unit positions and radii are in world units.
float waterUnitWakes(vec2 world, float noise, float effects, inout vec2 slope) {
    float wading = 0.0;
    for (int i = 0; i < 12; i++) {
        if (i >= u_waderCount) break;
        vec2 delta = world - u_waders[i].xy;
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
    return wading * effects;
}

struct WaterImpact {
    float boil;
    float bubbles;
    float foam;
};

// The foam noise read in a pool's two restarting phases, blended so that its contrast holds through each restart.
float waterChurn(vec2 a, vec2 b, vec2 weights) {
    return 0.5 + ((texture(u_surfaceNoise, vec3(a, WATER_DETAIL)).b - 0.5) * weights.x
          + (texture(u_surfaceNoise, vec3(b, WATER_DETAIL)).b - 0.5) * weights.y)
          * inversesqrt(weights.x * weights.x + weights.y * weights.y);
}

// A fall churns the pool it lands in: a boil round its landing line, rings pushed outward and the churned water
// flowing away. The landing lines of every fall nearby meet at the corners they share, so the boil runs on
// round those corners and across hexes without a seam. A boil is as large as its fall: it fades out over a little
// more than twice the fall's radius from the landing and, behind the curtain, reaches back toward the wall over a
// band that grows with it (GpuWaterShader.BOIL_REACH lists the falls whose boil reaches a pool), so a thread churns a
// small boil and a whole crest a wide one. Its white water breaks into lobes about the boil's own size and frays
// into lace: dense at the landing, yet never flat or solid.
// Position, shoreline distance and landing lines are in hex widths; phase and weights come from pool advection.
WaterImpact waterFallImpacts(vec2 position, float shore, float noise, float phase, vec2 weights,
      float effects, inout vec2 slope) {
    float strength = 0.0, radii = 0.0, reaches = 0.0;
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
        float behind = smoothstep(0.0, 0.5, -dot(outward, normalize(vec2(along.y, -along.x))));
        float reach = 1.0 - smoothstep(radius * 0.3, radius * 2.4, max(gap - radius * 0.7 * behind, 0.0));
        float ring = sin(gap * 70.0 - u_rainTime * 8.0 + noise * 6.0);
        slope += outward * ring * reach * 0.22 * effects;
        strength = max(strength, reach);
        push += outward * reach;
        radii += radius * reach;
        reaches += reach;
    }
    float boil = 0.0, bubbles = 0.0, impact = 0.0;
    // Without a nearby landing boil is zero, so neither bubbles nor impact contributes. The uniform branch
    // keeps mip selection defined for every fragment of materials that do have falling water nearby.
    if (u_splashCount > 0) {
        // Foam patches ride the churned water outward: advected in two phases like the current, each restarting
        // only while unseen, so the foam keeps moving away from the falls without ever shearing or pulsing.
        vec2 drift = push * (0.06 / max(length(push), 1.0));
        vec2 bubbleA = position - drift * ((phase - 0.5) * FLOW_CYCLE);
        vec2 bubbleB = position - drift * ((fract(phase + 0.5) - 0.5) * FLOW_CYCLE) + 0.37;
        bubbles = waterChurn(bubbleA * 3.4 + 0.61, bubbleB * 3.4 + 0.61, weights);
        // Lobes about the boil's own size: the bubbles' scale for a thread's, a coarser one for a whole crest's;
        // lace finer than both frays them.
        float coarse = waterChurn(bubbleA * 0.85 + 0.29, bubbleB * 0.85 + 0.29, weights);
        float lace = waterChurn(bubbleA * 9.0 + 0.43, bubbleB * 9.0 + 0.43, weights);
        float churned = mix(bubbles, coarse, smoothstep(0.05, 0.15, radii / max(reaches, 1e-4))) * 0.6
              + lace * 0.25 + (noise + 0.5) * 0.15;
        // The impact breaks up raggedly before reaching a bank; shoreline foam still follows the actual waterline.
        strength *= effects * smoothstep(0.004, 0.06, shore + (churned - 0.5) * 0.06);
        // Dense white water at the landing, with holes, breaking into patches and then lace as the foam spreads; even
        // where it is densest the lace lets the water show through, so in a fall's shadow it never lies flat.
        float settle = mix(0.78, 0.32, strength);
        impact = smoothstep(settle, settle + 0.1, churned) * smoothstep(0.0, 0.1, strength)
              * mix(0.7, 1.0, smoothstep(0.35, 0.65, lace));
        boil = strength * mix(0.55, 1.0, smoothstep(0.3, 0.7, churned));
    }
    return WaterImpact(boil, bubbles, impact);
}
