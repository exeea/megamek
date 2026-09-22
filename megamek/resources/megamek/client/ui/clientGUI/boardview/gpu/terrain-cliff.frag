// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Exposed vertical hex sides. Geometry, picking and shadow silhouettes remain BoardSurface's planes.
#ifdef GL_ARB_shader_texture_lod
#extension GL_ARB_shader_texture_lod : enable
#endif
#ifdef GL_ES
precision highp float;
#endif

varying vec2 v_diffuseUV;
varying vec3 v_normal;
varying vec4 v_color;
uniform sampler2D u_diffuseTexture;
uniform sampler2D u_normalTexture;
uniform sampler2D u_cliffSurface;
uniform float u_normalMaps;
uniform float u_groundResponse;

// Explicit gradients keep mip selection stable during the divergent relief traversal.
vec4 cliffSample(sampler2D map, vec2 uv, vec2 dx, vec2 dy) {
#ifdef GL_ARB_shader_texture_lod
    return texture2DGradARB(map, uv, dx, dy);
#else
    return texture2D(map, uv);
#endif
}

vec2 cliffRelief(vec2 uv, vec3 view, float depth, vec2 dx, vec2 dy) {
    if (depth < 0.00001) return uv;
#ifdef GL_ARB_shader_texture_lod
    float layers = mix(24.0, 12.0, clamp(view.z, 0.0, 1.0));
    float stepDepth = 1.0 / layers;
    vec2 stepUV = view.xy / max(view.z, 0.22) * depth * stepDepth;
    vec2 position = uv;
    float ray = 0.0;
    float surface = 1.0 - cliffSample(u_cliffSurface, position, dx, dy).r;
    float previousSurface = surface;
    for (int i = 0; i < 24; i++) {
        if (ray >= surface) break;
        previousSurface = surface;
        position -= stepUV;
        ray += stepDepth;
        surface = 1.0 - cliffSample(u_cliffSurface, position, dx, dy).r;
    }
    // Interpolate the intersection between the last two samples instead of exposing depth stair steps.
    float after = ray - surface;
    float before = ray - stepDepth - previousSurface;
    return position + stepUV * clamp(after / max(after - before, 0.0001), 0.0, 1.0);
#else
    // Compatibility path requires no shader texture-LOD extension and no divergent texture lookups.
    return uv - view.xy / max(view.z, 0.22) * depth * (1.0 - texture2D(u_cliffSurface, uv).r);
#endif
}

float cliffOcclusion(vec2 uv, float height, vec3 light, float depth, vec2 dx, vec2 dy) {
#ifdef GL_ARB_shader_texture_lod
    if (depth > 0.00001 && light.z > 0.08) {
        float stepHeight = (1.0 - height) / 6.0;
        vec2 stepUV = light.xy / max(light.z, 0.18) * depth * stepHeight;
        float obstruction = 0.0;
        for (int i = 1; i <= 6; i++) {
            float rayHeight = height + stepHeight * float(i);
            float blocker = cliffSample(u_cliffSurface, uv + stepUV * float(i), dx, dy).r;
            obstruction = max(obstruction, (blocker - rayHeight - 0.035) / (0.12 + float(i) * 0.04));
        }
        return 1.0 - 0.8 * clamp(obstruction, 0.0, 1.0);
    }
#endif
    return 1.0;
}

void main() {
    vec3 face = normalize(v_normal);
    // Matches wall(): U follows the directed hex edge, V points down, for all six orientations.
    vec3 tangent = normalize(vec3(-face.y, face.x, 0.0));
    vec3 bitangent = vec3(0.0, 0.0, -1.0);
    vec3 view = normalize(-u_viewDirection);
    vec3 localView = vec3(dot(view, tangent), dot(view, bitangent), dot(view, face));
    vec2 dx = dFdx(v_diffuseUV), dy = dFdy(v_diffuseUV);
    float footprint = max(length(dx), length(dy));
    float detail = smoothstep(90.0, 360.0, 1.0 / max(footprint, 0.00001)) * u_normalMaps;
    vec2 edge = min(v_color.rg, 1.0 - v_color.rg);
    float boundary = smoothstep(0.0, 0.12, min(edge.x, edge.y));
    vec4 properties = cliffSample(u_cliffSurface, v_diffuseUV, dx, dy);
    // Alpha encodes the maximum relief in tenths of one world-space texture repeat.
    float depth = properties.a * 0.1 * detail * boundary * smoothstep(0.08, 0.25, localView.z);
    vec2 uv = cliffRelief(v_diffuseUV, localView, depth, dx, dy);
    properties = cliffSample(u_cliffSurface, uv, dx, dy);
    vec3 albedo = cliffSample(u_diffuseTexture, uv, dx, dy).rgb;
    vec3 normal = face;
    if (u_normalMaps > 0.5) {
        vec3 mapped = (cliffSample(u_normalTexture, uv, dx, dy).rgb * 255.0 - 128.0) / 127.0;
        normal = normalize(tangent * mapped.x + bitangent * mapped.y + face * mapped.z);
    }
    float wet = u_wetness * step(0.0, u_groundResponse);
    float film = wet * max(0.0, u_groundResponse);
    albedo *= 1.0 - wet * mix(0.18, 0.11, max(0.0, u_groundResponse));
    float roughness = clamp(mix(properties.g, 0.24, film * 0.8), 0.2, 0.98);

#ifdef lightingFlag
    vec3 ambient = surfaceAmbient(normal) * mix(1.0, properties.b, u_normalMaps);
    vec3 direct = vec3(0.0), sheen = vec3(0.0);
    float nv = max(dot(normal, view), 0.02);
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
#if numDirectionalLights > 0
    for (int i = 0; i < numDirectionalLights; i++) {
        vec3 light = -u_dirLights[i].direction;
        float nl = max(dot(normal, light), 0.0);
        vec3 halfVector = light + view;
        halfVector /= max(length(halfVector), 0.0001);
        float nh = max(dot(normal, halfVector), 0.0);
        float vh = max(dot(view, halfVector), 0.0);
        float denominator = nh * nh * (alpha2 - 1.0) + 1.0;
        float distribution = alpha2 / max(3.141593 * denominator * denominator, 0.0001);
        float geometry = nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k);
        float fresnel = 0.04 + 0.96 * pow(1.0 - vh, 5.0);
        vec3 localLight = vec3(dot(light, tangent), dot(light, bitangent), dot(light, face));
        float visibility = smoothstep(0.0, 0.15, localLight.z);
        visibility *= cliffOcclusion(uv, properties.r, localLight, depth, dx, dy);
        vec3 irradiance = u_dirLights[i].color * nl * visibility;
        direct += irradiance * (1.0 - fresnel);
        sheen += irradiance * distribution * geometry * fresnel / max(4.0 * nv * nl, 0.001);
    }
#endif
    float visibility = surfaceVisibility();
    direct *= visibility;
    sheen *= visibility;
    albedo *= ambient + direct;
    albedo += sheen;
#endif
    gl_FragColor = vec4(albedo, 1.0);
}
