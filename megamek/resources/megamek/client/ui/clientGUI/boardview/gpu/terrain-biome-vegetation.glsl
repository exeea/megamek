// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 14) in vec4 a_coverRoot;
layout(location = 15) in vec4 a_coverRow;
uniform float u_biomeKind;
uniform float u_biomeLod;
uniform float u_coverPixels;
uniform float u_levelHeight;
uniform float u_metre;
out vec2 v_coverData;
out vec2 v_coverRoot;
out vec2 v_coverFade;

// False when the plant cannot contribute to this template: the caller skips the rest of the vertex program.
bool biomePlant(vec3 point, vec3 sourceNormal, vec4 pigment, out vec3 position, out vec3 normal, out vec4 color) {
    vec3 root = a_coverRoot.xyz;
    bool crop = u_biomeKind < 1.5;
    bool row = crop && a_coverRow.x > 0.0;
    vec2 across = vec2(@ROW_X@, @ROW_Y@), along = vec2(-across.y, across.x);
    // A crop strip or distant canopy run spans from one end to the other; a reed clump stands at its root.
    vec3 halfRow = row ? vec3(along * (.5 * a_coverRow.x), .5 * a_coverRow.y) : vec3(0.0);
    vec4 first = u_projViewTrans * vec4(root - halfRow, 1.0), last = u_projViewTrans * vec4(root + halfRow, 1.0);
    float nearest = first.w * last.w <= 0.0 ? 1e9 : u_coverPixels / max(.001, min(abs(first.w), abs(last.w)));
    float farthest = u_coverPixels / max(.001, max(abs(first.w), abs(last.w)));
    // Projected hex widths at which this template's dissolve can keep any fragment.
    vec2 band = crop ? (u_biomeLod < 1.5 ? vec2(@START1@, 1e9) : vec2(@START2@, @FULL1@))
          : u_biomeLod < .5 ? vec2(@START0@, 1e9) : u_biomeLod < 1.5 ? vec2(@START1@, @FULL0@) : vec2(@START2@, @FULL1@);
    // Per-chunk and board-wide batches keep plants outside this template's detail band or the view.
    if (nearest <= band.x || farthest >= band.y) { return false; }
    // Beyond one clip plane by more than a plant's reach (half a row's width plus its height) at both ends of its
    // row, nothing of it can enter the view: a clip coordinate changes by at most its matrix row's length per unit
    // moved, and a row is convex.
    float reach = 3.0 * u_metre + u_levelHeight;
    float perspective = length(vec3(u_projViewTrans[0].w, u_projViewTrans[1].w, u_projViewTrans[2].w));
    vec2 margin = reach * (perspective + vec2(length(vec3(u_projViewTrans[0].x, u_projViewTrans[1].x, u_projViewTrans[2].x)),
          length(vec3(u_projViewTrans[0].y, u_projViewTrans[1].y, u_projViewTrans[2].y))));
    vec4 outside = min(vec4(first.xy, -first.xy) - first.w, vec4(last.xy, -last.xy) - last.w) - margin.xyxy;
    if (max(max(outside.x, outside.y), max(outside.z, outside.w)) > 0.0) { return false; }
    // Dissolve at this vertex's own place along the row, so a long distant run hands over smoothly along its length.
    // Clip coordinates are linear along the row.
    vec4 here = mix(first, last, point.x + .5);
    float pixels = u_coverPixels / max(.001, abs(here.w));
    vec3 coverage = smoothstep(vec3(@START0@, @START1@, @START2@), vec3(@FULL0@, @FULL1@, @FULL2@), vec3(pixels));
    v_coverFade = crop ? (u_biomeLod < 1.5 ? vec2(0.0, coverage.y) : coverage.yz)
          : u_biomeLod < .5 ? vec2(0.0, coverage.x)
          : u_biomeLod < 1.5 ? coverage.xy : coverage.yz;
    v_coverRoot = root.xy / u_worldMetre;
    float seed = a_coverRoot.w, angle = seed * 97.71;
    mat2 turn = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    // Every segment of a furrow shares its height and wind field, including clipped segments on a hex boundary.
    if (row) {
        float rowNumber = round(dot(a_coverRoot.xy / u_metre, across) / @ROW_METRES@);
        seed = fract(sin(rowNumber * 12.9898) * 43758.5453);
        turn = mat2(along, -across);
    }
    float height = crop ? u_levelHeight * mix(.43, .52, seed) * @CROP_SCALE@
          : u_metre * mix(.90, 1.65, fract(seed * 19.37));
    float top = crop ? 1.04 : 1.1;
    if (row && abs(sourceNormal.z) < .5) {
        // Cross the complete row faces instead of leaving an isolated end card between edge-on strips.
        // Global UV keeps clipped fragments aligned. Tapering to zero at the base keeps stems on their support.
        float side = point.y;
        float rowU = mix(a_coverRow.z, a_coverRow.w, point.x + .5);
        point.y *= (2.0 * rowU - 1.0) * point.z / top;
        sourceNormal = vec3(2.0 * side * u_metre * (a_coverRow.w - a_coverRow.z)
              / a_coverRow.x * point.z / top, -1.0, side * (2.0 * rowU - 1.0) * u_metre / (top * height));
    }
    if (row && u_biomeLod > 1.5) {
        // Seen at an angle, rows as tall as these hide the furrows between them; only from overhead does the ground show.
        // Widen the distant canopy from its own width to the full row spacing as the view leaves the vertical.
        // The matrix's depth row is the camera's facing in both projections.
        vec3 facing = normalize(vec3(u_projViewTrans[0].z, u_projViewTrans[1].z, u_projViewTrans[2].z));
        point.y = sign(point.y) * mix(abs(point.y), @ROW_METRES@, sqrt(max(0.0, 1.0 - facing.z * facing.z)));
    }
    // Vary the clump's proportions, with every root fixed to its supporting surface.
    if (!crop && fract(seed * 71.13) < .55) {
        point *= vec3(.8, .8, .60);
        sourceNormal /= vec3(.8, .8, .60);
        top *= .60;
    }
    vec2 wind = length(u_wind.xy) > .01 ? normalize(u_wind.xy) : vec2(.8, .6);
    vec2 anchor = root.xy + (row ? along * point.x * a_coverRow.x : vec2(0.0));
    float gust = vegetationGust(anchor);
    // Stiffer crops bow less than flexible marsh leaves. Strength scales both lean and angular excursion.
    float flex = u_wind.z * (crop ? .78 + .22 * gust : 1.0 + .28 * gust);
    // Crop stems remain straight while swaying; row segments sample the same world-space gust at shared endpoints.
    // Marsh leaves form a flexible arch, with no displacement at the grounded base.
    float angleAtHeight = crop ? flex : flex * point.z / top;
    float sine = sin(angleAtHeight), cosine = cos(angleAtHeight);
    vec2 bend = wind * sine * point.z;
    float lifted = cosine * point.z;
    // Bend the centreline without stretching its tip away from the root; retain the clump's cross-section.
    position = root + vec3(turn * point.xy + bend, lifted) * height;
    if (row) {
        position = vec3(anchor - across * point.y * u_metre + bend * height,
              root.z + point.x * a_coverRow.y + lifted * height);
    }
    vec2 leaf = turn * sourceNormal.xy;
    float curvature = crop ? 0.0 : angleAtHeight;
    vec2 tangentXY = wind * (sine + curvature * cosine);
    float tangentZ = cosine - curvature * sine;
    normal = vec3(leaf * tangentZ, sourceNormal.z - dot(tangentXY, leaf));
    if (u_biomeLod > 1.5 && !row) {
        // At a few pixels a crop stalk or clump needs one quad. Face the camera so rotating or looking straight down
        // cannot turn it edge-on. Keep the base horizontal and lift the top out of its supporting terrain.
        vec3 right = normalize(vec3(u_projViewTrans[0].x, u_projViewTrans[1].x, 0.0));
        vec3 up = normalize(vec3(u_projViewTrans[0].y, u_projViewTrans[1].y, u_projViewTrans[2].y));
        up = normalize(vec3(up.xy, max(up.z, .35)));
        position = root + (right * point.x + up * lifted + vec3(bend, 0.0)) * height;
        normal = cross(right, up * tangentZ + vec3(tangentXY, 0.0));
    }
    normal = dot(normal, normal) > .00000001 ? normalize(normal) : vec3(0.0, 0.0, 1.0);
    color = vec4(pigment.rgb * mix(.80, 1.17, fract(seed * 31.7)), 1.0);
    if (!crop) color.rgb *= mix(.75, 1.04, smoothstep(0.0, .6, point.z));
    v_coverData = vec2(height, clamp(point.z, 0.0, 1.0));
    return true;
}
