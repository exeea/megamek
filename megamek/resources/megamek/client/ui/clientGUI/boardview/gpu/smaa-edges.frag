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
// SMAA 1x, first pass: SMAALumaEdgeDetectionPS of SMAA.hlsl (https://github.com/iryoku/smaa, 71c806a) in GLSL, with
// its SMAA_PRESET_HIGH threshold and the vertex offsets computed here. The input is the display-encoded composite.
layout(location = 0) out vec4 fragColor;
in vec2 v_uv;
uniform sampler2D u_color;
uniform vec4 u_metrics; // SMAA_RT_METRICS: 1 / width, 1 / height, width, height.

#define SMAA_THRESHOLD 0.1
#define SMAA_LOCAL_CONTRAST_ADAPTATION_FACTOR 2.0

void main() {
    vec4 offset[3];
    offset[0] = u_metrics.xyxy * vec4(-1.0, 0.0, 0.0, -1.0) + v_uv.xyxy;
    offset[1] = u_metrics.xyxy * vec4(1.0, 0.0, 0.0, 1.0) + v_uv.xyxy;
    offset[2] = u_metrics.xyxy * vec4(-2.0, 0.0, 0.0, -2.0) + v_uv.xyxy;

    vec2 threshold = vec2(SMAA_THRESHOLD, SMAA_THRESHOLD);

    // Calculate lumas:
    vec3 weights = vec3(0.2126, 0.7152, 0.0722);
    float L = dot(texture(u_color, v_uv).rgb, weights);
    float Lleft = dot(texture(u_color, offset[0].xy).rgb, weights);
    float Ltop = dot(texture(u_color, offset[0].zw).rgb, weights);

    // We do the usual threshold:
    vec4 delta;
    delta.xy = abs(L - vec2(Lleft, Ltop));
    vec2 edges = step(threshold, delta.xy);

    // Then discard if there is no edge:
    if (dot(edges, vec2(1.0, 1.0)) == 0.0) discard;

    // Calculate right and bottom deltas:
    float Lright = dot(texture(u_color, offset[1].xy).rgb, weights);
    float Lbottom = dot(texture(u_color, offset[1].zw).rgb, weights);
    delta.zw = abs(L - vec2(Lright, Lbottom));

    // Calculate the maximum delta in the direct neighborhood:
    vec2 maxDelta = max(delta.xy, delta.zw);

    // Calculate left-left and top-top deltas:
    float Lleftleft = dot(texture(u_color, offset[2].xy).rgb, weights);
    float Ltoptop = dot(texture(u_color, offset[2].zw).rgb, weights);
    delta.zw = abs(vec2(Lleft, Ltop) - vec2(Lleftleft, Ltoptop));

    // Calculate the final maximum delta:
    maxDelta = max(maxDelta.xy, delta.zw);
    float finalDelta = max(maxDelta.x, maxDelta.y);

    // Local contrast adaptation:
    edges.xy *= step(finalDelta, SMAA_LOCAL_CONTRAST_ADAPTATION_FACTOR * delta.xy);

    fragColor = vec4(edges, 0.0, 0.0);
}
