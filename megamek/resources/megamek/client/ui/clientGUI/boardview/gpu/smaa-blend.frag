#version 330 core
/**
 * Copyright (C) 2013 Jorge Jimenez (jorge@iryoku.com)
 * Copyright (C) 2013 Jose I. Echevarria (joseignacioechevarria@gmail.com)
 * Copyright (C) 2013 Belen Masia (bmasia@unizar.es)
 * Copyright (C) 2013 Fernando Navarro (fernandn@microsoft.com)
 * Copyright (C) 2013 Diego Gutierrez (diegog@unizar.es)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software. As clarification, there
 * is no requirement that the copyright notice and permission be included in
 * binary distributions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
// SMAA 1x, third pass: SMAANeighborhoodBlendingPS of SMAA.hlsl (https://github.com/iryoku/smaa, 71c806a) in GLSL,
// with the vertex offset computed here. The display-encoded input is read without sRGB decoding, so the blend is in
// gamma space, as the reference allows. Like the composite it replaces as the window's writer, it stores the scene's
// hardware depth for the overlays drawn after it.
layout(location = 0) out vec4 fragColor;
in vec2 v_uv;
uniform sampler2D u_color;
uniform sampler2D u_weights;
uniform sampler2D u_depth;
uniform vec4 u_metrics; // SMAA_RT_METRICS: 1 / width, 1 / height, width, height.

void main() {
    vec4 offset = u_metrics.xyxy * vec4(1.0, 0.0, 0.0, 1.0) + v_uv.xyxy;

    // Fetch the blending weights for current pixel:
    vec4 a;
    a.x = texture(u_weights, offset.xy).a; // Right
    a.y = texture(u_weights, offset.zw).g; // Top
    a.wz = texture(u_weights, v_uv).xz; // Bottom / Left

    // Is there any blending weight with a value greater than 0.0?
    if (dot(a, vec4(1.0, 1.0, 1.0, 1.0)) < 1e-5) {
        fragColor = textureLod(u_color, v_uv, 0.0);
    } else {
        bool h = max(a.x, a.z) > max(a.y, a.w); // max(horizontal) > max(vertical)

        // Calculate the blending offsets:
        vec4 blendingOffset = vec4(0.0, a.y, 0.0, a.w);
        vec2 blendingWeight = a.yw;
        if (h) {
            blendingOffset = vec4(a.x, 0.0, a.z, 0.0);
            blendingWeight = a.xz;
        }
        blendingWeight /= dot(blendingWeight, vec2(1.0, 1.0));

        // Calculate the texture coordinates:
        vec4 blendingCoord = blendingOffset * vec4(u_metrics.xy, -u_metrics.xy) + v_uv.xyxy;

        // We exploit bilinear filtering to mix current pixel with the chosen neighbor:
        vec4 color = blendingWeight.x * textureLod(u_color, blendingCoord.xy, 0.0);
        color += blendingWeight.y * textureLod(u_color, blendingCoord.zw, 0.0);
        fragColor = color;
    }
    gl_FragDepth = texture(u_depth, v_uv).r;
}
