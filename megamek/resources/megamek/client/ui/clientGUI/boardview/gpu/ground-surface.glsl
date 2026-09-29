// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared mapped normal, rain film and lighting for authored ground and road materials.
#if defined(normalTextureFlag) || defined(roadCoatFlag)
// A normal map texel applied to the surface normal, when normal maps are on.
vec3 mappedNormal(vec4 texel, float strength) {
    vec3 normal = normalize(v_normal);
    if (u_normalMaps > 0.5) {
        // Neutral is exactly (128,128,255); world X/right and -Y/down also cover slopes.
        vec3 detail = (texel.rgb * 255.0 - 128.0) / 127.0;
        detail.xy *= strength;
        vec3 tangent = normalize(vec3(normal.z, 0.0, -normal.x));
        normal = normalize(tangent * detail.x - cross(normal, tangent) * detail.y + normal * detail.z);
    }
    return normal;
}
#endif

vec3 groundNormal(vec2 uv, float strength) {
#ifdef normalTextureFlag
    return mappedNormal(texture(u_normalTexture, uv), strength);
#else
    return normalize(v_normal);
#endif
}

float groundPuddle(float wet, float response) {
    if (wet * u_rainDetail <= 0.0) return 0.0;
    return rainPuddle(v_cloudPosition.xy * u_rainScale, wet, response)
          * smoothstep(0.97, 0.999, v_normal.z) * u_rainDetail;
}

void groundFilm(inout vec3 albedo, inout vec3 normal, float puddle) {
    if (puddle > 0.0) {
        albedo *= 1.0 - 0.18 * puddle;
        vec3 waterNormal = normalize(vec3(rainRipples(v_cloudPosition.xy * u_rainScale), 1.0));
        normal = normalize(mix(normal, waterNormal, puddle));
    }
}

vec3 groundLighting(vec3 albedo, vec3 normal, float wet, float response, float puddle, float roughness, float occlusion) {
#ifdef lightingFlag
    albedo = toLinear(albedo);
    vec3 ambient, direct, sheen;
    surfaceLighting(normal, wet * mix(response, 1.0, puddle), roughness, ambient, direct, sheen);
    ambient *= occlusion;
    albedo *= ambient + direct;
    albedo += sheen;
    albedo = toDisplay(albedo);
    if (puddle > 0.0) { albedo = rainReflection(albedo, normal, puddle); }
#endif
    return albedo;
}
