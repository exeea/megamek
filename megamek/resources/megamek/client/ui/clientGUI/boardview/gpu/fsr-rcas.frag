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
// FSR 1 RCAS: FsrRcasF of ffx_fsr1.h (FidelityFX FSR 1.0.2) in GLSL, without its optional denoise and with its limiters'
// divisions guarded against a black or white ring. It sharpens EASU's output into the window, which also receives the
// scene's hardware depth for overlay occlusion.
layout(location = 0) out vec4 fragColor;
in vec2 v_uv;
uniform sampler2D u_color;
uniform sampler2D u_depth;
// FsrRcasCon's strength for a sharpness of 1 stop below the maximum, exp2(-1): at AMD's .2 stops, photographed rock
// came out crunchier than at 100% render resolution.
const float SHARPNESS = .5;
// The most negative lobe, FSR_RCAS_LIMIT.
const float LIMIT = .25 - 1.0 / 16.0;

void main() {
    ivec2 last = textureSize(u_color, 0) - 1;
    ivec2 at = ivec2(v_uv * vec2(last + 1));
    //    b
    //  d e f
    //    h
    vec3 b = texelFetch(u_color, clamp(at + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 d = texelFetch(u_color, clamp(at - ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 e = texelFetch(u_color, at, 0).rgb;
    vec3 f = texelFetch(u_color, clamp(at + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 h = texelFetch(u_color, clamp(at - ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 ringMin = min(min(b, d), min(f, h)), ringMax = max(max(b, d), max(f, h));
    // The most negative lobe that keeps every channel within 0 and 1; a black or white ring leaves no room to sharpen.
    vec3 hitMin = min(ringMin, e) / max(4.0 * ringMax, 1e-5);
    vec3 hitMax = (1.0 - max(ringMax, e)) / min(4.0 * ringMin - 4.0, -1e-5);
    vec3 lobes = max(-hitMin, hitMax);
    float lobe = max(-LIMIT, min(max(lobes.r, max(lobes.g, lobes.b)), 0.0)) * SHARPNESS;
    fragColor = vec4((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 1.0);
    gl_FragDepth = texture(u_depth, v_uv).r;
}
