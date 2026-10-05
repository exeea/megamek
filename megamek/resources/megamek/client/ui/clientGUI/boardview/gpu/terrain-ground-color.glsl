// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by the terrain surface and the turf growing from it.
// The second repeat of every map is 2.37 times larger and turned by 34 degrees.
const mat2 TURN = mat2(.8253, .5646, -.5646, .8253);

vec3 groundFields(sampler2D noiseMap, vec3 world) {
    return vec3(texture(noiseMap, world.xy / 700.0).g * .6 + texture(noiseMap, world.xy / 430.0 + .19).b * .4,
          texture(noiseMap, world.xy / 160.0 + .41).b, texture(noiseMap, world.xy / 2600.0 + .73).r);
}
// A restrained per-level cue for the overhead view; light and material cover still establish the landform.
// The same rock or soil must not bleach toward cream simply because it stands on a taller hex.
vec3 levelGrade(vec3 c, float level, float fungus) {
    // Ground, cliffs and rocks share one lower limit, including deep valleys and the board's plinth.
    // Keep it local to colour grading: water optics still need the actual surface level.
    level = max(level, mix(-1.5, -6.0, fungus));
    // Levels count almost fully near the ground and ease off further away, so no height grades to white or black.
    float up = 6.0 * (1.0 - exp(-max(level, 0.0) / 6.0)), down = 6.0 * (1.0 - exp(-max(-level, 0.0) / 6.0));
    float saturation = mix(-up + down, 22.0 - 12.0 * up + 2.0 * down, fungus);
    float lightness = mix(4.0, 7.5, fungus) * up - mix(6.0, 7.0, fungus) * down;
    float contrast = .5 * up;
    // Warmer below: less blue, a little less green.
    // Begin fungus darker and richer, leaving room for the cyan crust to lighten and lose saturation uphill.
    // Grade the completed material mix, so the crust, underlying skin and slopes all follow the same height.
    c *= mix(1.0, .82, fungus);
    c *= mix(vec3(1.0, 1.0 - .01 * down, 1.0 - .04 * down),
          vec3(1.0, 1.0 - .025 * down, 1.0 - .006 * down), fungus);
    float luma = dot(c, vec3(.299, .587, .114));
    c = mix(vec3(luma), c, 1.0 + saturation / 100.0);
    // Dark ground lifts less, so a meadow's upper levels keep their green and their texture instead of bleaching.
    vec3 highlight = mix(vec3(1.0, .97, .9), vec3(.94, .96, 1.0), fungus);
    c = lightness >= 0.0 ? mix(c, highlight, lightness / 100.0 * min(1.0, luma / .55))
          : c * (1.0 + lightness / 100.0);
    c = (c - .5) * (1.0 + contrast / 100.0) + .5;
    return clamp(c, 0.0, 1.0);
}

vec3 levelGrade(vec3 c, float level) { return levelGrade(c, level, 0.0); }

// Broad variations in the ground's tone, so a large field reads neither as one flat colour nor as tiles: patches, the
// desert's compact ochre earth, loose sand's drifts, a meadow's dry and lush turf. rim and foot weigh the
// nearness of a drop and of a rise. The ground and the cover drifted onto slopes and ledges share it, so they match.
vec3 groundToneFor(float f, vec3 albedo, vec3 world, float broad, float fine, float region, float rim, float foot) {
    albedo *= mix(.94, 1.06, broad) * mix(.95, 1.05, region) * mix(.96, 1.04, fine);
    if (abs(f - 2.0) < .5) {
        // Shared loose sand over any theme. No drift pattern is added to clear desert or Martian hardpan.
        float dune = sin(dot(world.xy, vec2(.8, .6)) / 19.0 + broad * 5.0 + region * 3.0);
        albedo *= 1.0 + .04 * dune * smoothstep(.2, .6, region + .3 * fine);
    }
    if (abs(f - DESERT_FAMILY) < .5) {
        albedo = mix(albedo, albedo * vec3(1.03, .91, .85), smoothstep(.4, .75, broad * .6 + region * .4) * .45);
    }
    if (abs(f - MARS_FAMILY) < .5) {
        albedo = mix(albedo, albedo * vec3(.92, .82, .77), smoothstep(.55, .8, region * .6 + fine * .4) * .3);
    }
    if (abs(f) < .5) {
        // Thin, dry turf on convex rims and in sunny patches; lush, dark grass where water gathers below cliffs.
        float dry = max(smoothstep(.55, .85, broad * .7 + fine * .3) * .5, rim * .6);
        albedo = mix(albedo, albedo * vec3(1.25, 1.12, .7), dry);
        albedo = mix(albedo, albedo * vec3(.78, .95, .82), max(foot * .6, (1.0 - smoothstep(.2, .45, broad)) * .4));
    }
    return albedo;
}
