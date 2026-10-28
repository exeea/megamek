// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The relief's tessellation control stage (GpuTessellation): each edge of a triangle in as many whole segments as keep
// its Phong curve within TESS_TOLERANCE screen pixels of them, at most TESS_MAX. A curve strays from n segments by its
// bulge over n squared, the bulge measured at the edge's middle as the camera sees it. An edge that bulges less than
// TESS_BULGE (GpuTessellation.BULGE_METRES, which its build shares to put only the triangles that curve in patches), or
// that would sink rather than rise (the evaluation stage keeps it flat), keeps one segment, straight, as does one
// without normals. The split reads the edge's two vertices alone, so the triangles on either side split it alike and no
// crack opens; the colour pass and its depth twin split it alike from the same camera.
uniform mat4 u_tessView; // the camera with the part's placement
uniform vec2 u_tessViewport; // its target's size in pixels
const float TESS_TOLERANCE = .5;
const float TESS_MAX = 8.0;

float tessSegments(int i, int j) {
    vec3 na = vs_normal[i], nb = vs_normal[j];
    if (dot(na, na) == 0.0 || dot(nb, nb) == 0.0) return 1.0;
    vec3 halfEdge = .5 * (vs_position[j] - vs_position[i]);
    vec3 middle = vs_position[i] + halfEdge;
    // The curve's middle (tessellation-evaluation.glsl), as far as the edge's shape rounds it.
    vec3 bulge = -.5 * (dot(halfEdge, na) * na - dot(halfEdge, nb) * nb)
          * min(tessShape(vs_color[i]), tessShape(vs_color[j]));
    if (dot(bulge, na + nb) <= 0.0 || length(bulge) < TESS_BULGE) return 1.0;
    vec4 chord = u_tessView * vec4(middle, 1.0), curve = u_tessView * vec4(middle + bulge, 1.0);
    // Behind a perspective camera an edge has no size on screen.
    if (chord.w <= 0.0 || curve.w <= 0.0) return 1.0;
    float pixels = length((curve.xy / curve.w - chord.xy / chord.w) * .5 * u_tessViewport);
    return clamp(ceil(sqrt(pixels / TESS_TOLERANCE)), 1.0, TESS_MAX);
}

// Outer level i is the edge facing vertex i; the inside takes the edges' mean, whole.
void tessellationLevels() {
    float a = tessSegments(1, 2), b = tessSegments(2, 0), c = tessSegments(0, 1);
    gl_TessLevelOuter[0] = a;
    gl_TessLevelOuter[1] = b;
    gl_TessLevelOuter[2] = c;
    gl_TessLevelInner[0] = ceil((a + b + c) / 3.0);
}
