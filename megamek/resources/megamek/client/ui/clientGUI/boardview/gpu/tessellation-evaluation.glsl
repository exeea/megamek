// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The relief's tessellation evaluation stage (GpuTessellation): the patch's Phong surface (Boubekeur and Alexa 2008), the
// flat point moved toward the mean of its projections onto the three vertices' tangent planes, by the patch's shape
// (tessellation-shape.glsl), and only outward, along its normal: a rim rounds, a foot keeps its plane. The shadow map
// draws the flat triangles, which the drawn surface so never sinks below. On an edge the shape is that edge's own and the
// point reads the edge's two vertices alone, so neighbouring patches meet; inside, the three edges' shapes blend. A
// vertex without a normal keeps the plane.

vec3 phongNormal() {
    vec3 normal = gl_TessCoord.x * tc_normal[0] + gl_TessCoord.y * tc_normal[1] + gl_TessCoord.z * tc_normal[2];
    return dot(normal, normal) > 0.0 ? normalize(normal) : normal;
}

vec3 phongPoint() {
    vec3 b = gl_TessCoord;
    float s0 = tessShape(tc_color[0]), s1 = tessShape(tc_color[1]), s2 = tessShape(tc_color[2]);
    // The edge facing each vertex.
    float e0 = min(s1, s2), e1 = min(s2, s0), e2 = min(s0, s1);
    float shape;
    if (b.x == 0.0) shape = e0;
    else if (b.y == 0.0) shape = e1;
    else if (b.z == 0.0) shape = e2;
    else {
        float w0 = b.y * b.z, w1 = b.z * b.x, w2 = b.x * b.y;
        shape = (w0 * e0 + w1 * e1 + w2 * e2) / (w0 + w1 + w2);
    }
    precise vec3 chord = b.x * tc_position[0] + b.y * tc_position[1] + b.z * tc_position[2];
    precise vec3 curved = b.x * (chord - dot(chord - tc_position[0], tc_normal[0]) * tc_normal[0])
          + b.y * (chord - dot(chord - tc_position[1], tc_normal[1]) * tc_normal[1])
          + b.z * (chord - dot(chord - tc_position[2], tc_normal[2]) * tc_normal[2]);
    vec3 normal = phongNormal();
    precise vec3 point = chord + max(dot(curved - chord, normal), 0.0) * shape * normal;
    return point;
}
