#version 330 core
/**
 * Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
// FSR 1 EASU: FsrEasuF of ffx_fsr1.h (FidelityFX FSR 1.0.2) in GLSL, with exact reciprocals and its twelve taps as
// clamped texel fetches, which GLSL 3.30 has. The input is the display-encoded composite at the render resolution; this
// pass writes the window's pixels.
layout(location = 0) out vec4 fragColor;
in vec2 v_uv;
uniform sampler2D u_color;

float luma(vec3 c) { return c.b * .5 + (c.r * .5 + c.g); }

// One bilinear corner's gradient direction and edge length from the '+' around its centre c: a above, b left, d right,
// e below, weighted by the corner's bilinear weight w (FsrEasuSetF).
void edge(inout vec2 dir, inout float len, float w, float a, float b, float c, float d, float e) {
    float lenX = max(abs(d - c), abs(c - b));
    float dirX = d - b;
    dir.x += dirX * w;
    lenX = lenX > 0.0 ? clamp(abs(dirX) / lenX, 0.0, 1.0) : 0.0;
    len += lenX * lenX * w;
    float lenY = max(abs(e - c), abs(c - a));
    float dirY = e - a;
    dir.y += dirY * w;
    lenY = lenY > 0.0 ? clamp(abs(dirY) / lenY, 0.0, 1.0) : 0.0;
    len += lenY * lenY * w;
}

// One tap of the rotated, anisotropic, windowed Lanczos-2 approximation (FsrEasuTapF).
void tap(inout vec3 color, inout float weight, vec2 offset, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(dot(offset, dir), dot(offset, vec2(-dir.y, dir.x))) * len;
    float d2 = min(dot(v, v), clp);
    float wB = .4 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    float w = (25.0 / 16.0 * wB * wB - (25.0 / 16.0 - 1.0)) * wA * wA;
    color += c * w;
    weight += w;
}

void main() {
    ivec2 last = textureSize(u_color, 0) - 1;
    vec2 pp = v_uv * vec2(last + 1) - .5;
    vec2 fp = floor(pp);
    pp -= fp;
    // The twelve texels around the output pixel:
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    ivec4 x = clamp(int(fp.x) + ivec4(-1, 0, 1, 2), 0, last.x);
    ivec4 y = clamp(int(fp.y) + ivec4(-1, 0, 1, 2), 0, last.y);
    vec3 b = texelFetch(u_color, ivec2(x.y, y.x), 0).rgb, c = texelFetch(u_color, ivec2(x.z, y.x), 0).rgb;
    vec3 e = texelFetch(u_color, ivec2(x.x, y.y), 0).rgb, f = texelFetch(u_color, ivec2(x.y, y.y), 0).rgb;
    vec3 g = texelFetch(u_color, ivec2(x.z, y.y), 0).rgb, h = texelFetch(u_color, ivec2(x.w, y.y), 0).rgb;
    vec3 i = texelFetch(u_color, ivec2(x.x, y.z), 0).rgb, j = texelFetch(u_color, ivec2(x.y, y.z), 0).rgb;
    vec3 k = texelFetch(u_color, ivec2(x.z, y.z), 0).rgb, l = texelFetch(u_color, ivec2(x.w, y.z), 0).rgb;
    vec3 n = texelFetch(u_color, ivec2(x.y, y.w), 0).rgb, o = texelFetch(u_color, ivec2(x.z, y.w), 0).rgb;
    float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);
    float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);
    vec2 dir = vec2(0.0);
    float len = 0.0;
    edge(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    edge(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    edge(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    edge(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
    // Normalize the direction, with a flat neighbourhood's facing along x.
    float dirR = dot(dir, dir);
    dir = dirR < 1.0 / 32768.0 ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
    // The edge length from {0 to 2} to {0 to 1}, shaped with a square.
    len *= .5;
    len *= len;
    // On an edge, distances along the gradient scale from 1 to 'stretch' (1 on an axis, sqrt(2) on a diagonal) and
    // along the edge from 1 to 1/2: a kernel twice as long.
    float stretch = 1.0 / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - .5 * len);
    // The window widens from +/- sqrt(2) to slightly beyond 2 with the edge; distance^2 clips at its end.
    float lob = .5 + ((1.0 / 4.0 - .04) - .5) * len;
    float clp = 1.0 / lob;
    vec3 color = vec3(0.0);
    float weight = 0.0;
    tap(color, weight, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    tap(color, weight, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    tap(color, weight, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    tap(color, weight, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    tap(color, weight, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    tap(color, weight, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    tap(color, weight, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    tap(color, weight, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    tap(color, weight, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    tap(color, weight, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    tap(color, weight, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    tap(color, weight, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
    // Normalize, and dering to the four nearest texels' range.
    vec3 lo = min(min(f, g), min(j, k)), hi = max(max(f, g), max(j, k));
    fragColor = vec4(clamp(color / weight, lo, hi), 1.0);
}
