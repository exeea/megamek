#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Ground-truth ambient occlusion (Jimenez et al. 2016, "Practical Real-Time Strategies for Accurate Indirect
// Occlusion") at half resolution, from the depth pre-pass. Each pixel searches the horizon on both sides of SLICES
// screen directions, a few steps each within a world radius, and integrates the cosine-weighted visible arc around its
// normal, which is reconstructed from depth. The slice rotation and step offsets follow a 4 by 4 pattern that
// ambient-occlusion-blur.frag averages out. Contact shadows march a short way toward the sun through the same depth:
// the small occluders the shadow map is too coarse for (a foot, a rock, a trunk's root). Output: R visibility,
// G camera distance, B the sun's contact visibility (ambient-occlusion.glsl).
layout(location = 0) out vec4 fragColor;
uniform sampler2D u_depth; // the scene's hardware depth, full resolution
// CAMERA_DEPTH
uniform vec2 u_projectionScale; // projection M00, M11
uniform vec2 u_radius; // world radius, the largest screen radius in scene pixels
uniform vec4 u_contact; // the direction to the sun in view space; the march length in world units, 0 without a sun
uniform float u_contactThickness; // world depth an occluder is taken to have behind its surface

const int SLICES = 2;
const int STEPS = 4;
const int CONTACT_STEPS = 8;
const float PI = 3.14159265;
const float HALF_PI = 1.57079633;
// Bayer order: any 4 by 4 block holds every slice rotation once.
const float PATTERN[16] = float[16](0., 8., 2., 10., 12., 4., 14., 6., 3., 11., 1., 9., 15., 7., 13., 5.);
// The cleared depth: no surface drew there.
const float SKY = 1.0;

ivec2 last;

vec3 viewPosition(ivec2 texel) {
    float range = cameraDepth(texelFetch(u_depth, texel, 0).r);
    vec2 ndc = (vec2(texel) + .5) / vec2(last + 1) * 2.0 - 1.0;
    // Clip w: the distance in perspective, 1 in orthographic projection.
    float w = u_projectionDepth.w - u_projectionDepth.z * range;
    return vec3(ndc * w / u_projectionScale, -range);
}

// The depth texel a view-space point projects to.
ivec2 depthTexel(vec3 point) {
    float w = u_projectionDepth.w + u_projectionDepth.z * point.z;
    return ivec2(floor((point.xy * u_projectionScale / w * .5 + .5) * vec2(last + 1)));
}

// Whether the sun reaches this surface past the nearby depth: 0 when a march step falls behind a surface by less than
// an occluder's thickness, 1 when it leaves the screen or nothing stands in the way.
float contactShadow(vec3 position, vec3 normal, float pixelSize, float jitter) {
    if (u_contact.w < pixelSize) return 1.0;
    // Off the surface by a couple of pixels, so its own depth does not shadow it.
    vec3 start = position + normal * (2.0 * pixelSize);
    for (int index = 0; index < CONTACT_STEPS; index++) {
        vec3 point = start + u_contact.xyz * (u_contact.w * (float(index) + jitter) / float(CONTACT_STEPS));
        ivec2 texel = depthTexel(point);
        if (any(lessThan(texel, ivec2(0))) || any(greaterThan(texel, last))) return 1.0;
        float behind = -point.z - cameraDepth(texelFetch(u_depth, texel, 0).r);
        if (behind > pixelSize && behind < u_contactThickness) return 0.0;
    }
    return 1.0;
}

void main() {
    last = textureSize(u_depth, 0) - 1;
    ivec2 centre = min(ivec2(gl_FragCoord.xy) * 2, last);
    float depth = texelFetch(u_depth, centre, 0).r;
    vec3 position = viewPosition(centre);
    float range = -position.z;
    if (depth >= SKY) {
        fragColor = vec4(1.0, range, 1.0, 0.0);
        return;
    }
    // The normal from the nearer neighbour on each axis, so a depth edge does not bend it.
    vec3 left = viewPosition(max(centre - ivec2(1, 0), ivec2(0)));
    vec3 right = viewPosition(min(centre + ivec2(1, 0), last));
    vec3 below = viewPosition(max(centre - ivec2(0, 1), ivec2(0)));
    vec3 above = viewPosition(min(centre + ivec2(0, 1), last));
    vec3 across = abs(right.z - position.z) < abs(position.z - left.z) ? right - position : position - left;
    vec3 up = abs(above.z - position.z) < abs(position.z - below.z) ? above - position : position - below;
    vec3 view = normalize(-position);
    vec3 normal = normalize(cross(across, up));
    if (dot(normal, view) < 0.0) normal = -normal;
    float w = u_projectionDepth.w - u_projectionDepth.z * range;
    float pixels = u_radius.x * u_projectionScale.y * float(last.y + 1) * .5 / w;
    float visibility = 1.0;
    // Below a couple of pixels the occlusion would not show; far views cost nothing more.
    if (pixels > 2.0) {
        float scale = min(1.0, u_radius.y / pixels);
        float radius = u_radius.x * scale;
        pixels *= scale;
        ivec2 cell = ivec2(gl_FragCoord.xy) & 3;
        float rotation = PATTERN[cell.y * 4 + cell.x] / 16.0;
        float jitter = (PATTERN[cell.x * 4 + cell.y] + .5) / 16.0;
        float sum = 0.0;
        for (int slice = 0; slice < SLICES; slice++) {
            float angle = (float(slice) + rotation) * PI / float(SLICES);
            vec2 direction = vec2(cos(angle), sin(angle));
            vec3 tangent = vec3(direction, 0.0);
            vec3 ortho = normalize(tangent - dot(tangent, view) * view);
            vec3 axis = cross(ortho, view);
            vec3 projected = normal - axis * dot(normal, axis);
            float projectedLength = length(projected);
            float cosNormal = clamp(dot(projected, view) / max(projectedLength, 1e-4), -1.0, 1.0);
            float n = (dot(ortho, projected) < 0.0 ? -1.0 : 1.0) * acos(cosNormal);
            // Without an occluder each side's horizon is the surface's own tangent plane.
            float lowest0 = cos(n + HALF_PI), lowest1 = cos(n - HALF_PI);
            float horizon0 = lowest0, horizon1 = lowest1;
            for (int index = 0; index < STEPS; index++) {
                float s = (float(index) + jitter) / float(STEPS);
                ivec2 offset = ivec2(round(direction * (1.5 + s * s * (pixels - 1.5))));
                for (int side = 0; side < 2; side++) {
                    ivec2 texel = side == 0 ? centre + offset : centre - offset;
                    if (any(lessThan(texel, ivec2(0))) || any(greaterThan(texel, last))) continue;
                    vec3 delta = viewPosition(texel) - position;
                    float gap = length(delta);
                    float cosine = dot(delta, view) / max(gap, 1e-4);
                    // Occluders fade out over the outer 60 percent of the radius.
                    float weight = clamp((radius - gap) / (.6 * radius), 0.0, 1.0);
                    if (side == 0) horizon0 = max(horizon0, mix(lowest0, cosine, weight));
                    else horizon1 = max(horizon1, mix(lowest1, cosine, weight));
                }
            }
            float h0 = n + clamp(-acos(clamp(horizon1, -1.0, 1.0)) - n, -HALF_PI, HALF_PI);
            float h1 = n + clamp(acos(clamp(horizon0, -1.0, 1.0)) - n, -HALF_PI, HALF_PI);
            float sinNormal = sin(n);
            sum += projectedLength * .25 * (2.0 * cosNormal + 2.0 * (h0 + h1) * sinNormal
                  - cos(2.0 * h0 - n) - cos(2.0 * h1 - n));
        }
        visibility = clamp(sum / float(SLICES), 0.0, 1.0);
    }
    // A scene pixel's world size here; the contact march never goes below it.
    float pixelSize = 2.0 * w / (u_projectionScale.y * float(last.y + 1));
    ivec2 phase = ivec2(gl_FragCoord.xy) & 3;
    float contact = contactShadow(position, normal, pixelSize, (PATTERN[phase.y * 4 + phase.x] + .5) / 16.0);
    fragColor = vec4(visibility, range, contact, 0.0);
}
