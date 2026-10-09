// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported glTF surfaces use the same sun, hemisphere and direct GGX response as the terrain.
#if defined(modelSurfaceFlag) && defined(lightingFlag)
uniform vec3 u_modelSurface; // roughness, metalness, packed map present
uniform sampler2D u_modelSurfaceMap;
uniform vec4 u_modelView; // camera direction, perspective flag
uniform vec4 u_cameraPosition;
uniform vec3 u_ambientCubemap[6];
in vec2 v_modelUV;
#ifdef normalTextureFlag
uniform float u_normalMaps;
#endif
#ifdef ambientTextureFlag
uniform sampler2D u_ambientTexture;
#endif
#if numDirectionalLights > 0
struct DirectionalLight {
    vec3 color;
    vec3 direction;
};
uniform DirectionalLight u_dirLights[numDirectionalLights];
#endif

vec3 modelNormal() {
    vec3 normal = normalize(v_normal);
#ifdef normalTextureFlag
    // Derive the glTF tangent frame from the original mesh UVs, independent of projected unit camouflage.
    // Degenerate UVs keep the geometric normal; mirrored UVs preserve their handedness.
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
    vec2 ux = dFdx(v_modelUV), uy = dFdy(v_modelUV);
    float determinant = ux.x * uy.y - ux.y * uy.x;
    if (u_normalMaps > .5 && abs(determinant) > 1e-10) {
        vec3 tangent = (dx * uy.y - dy * ux.y) / determinant;
        tangent -= normal * dot(normal, tangent);
        if (dot(tangent, tangent) > 1e-10) {
            tangent = normalize(tangent);
            vec3 bitangent = cross(normal, tangent);
            vec3 uvBitangent = (dy * ux.x - dx * uy.x) / determinant;
            bitangent *= dot(bitangent, uvBitangent) < 0.0 ? -1.0 : 1.0;
            vec3 mapped = texture(u_normalTexture, v_modelUV).xyz * 2.0 - 1.0;
            normal = normalize(mat3(tangent, bitangent, normal) * mapped);
        }
    }
#endif
    return normal;
}

vec3 modelSurface(vec3 albedo, vec3 emission) {
    float roughness = u_modelSurface.x, metallic = u_modelSurface.y;
    if (u_modelSurface.z > .5) {
        vec2 channels = texture(u_modelSurfaceMap, v_modelUV).gb;
        roughness *= channels.x;
        metallic *= channels.y;
    }
    roughness = clamp(roughness, .15, .98);
    vec3 normal = modelNormal();
    vec3 view = u_modelView.w > .5 ? normalize(u_cameraPosition.xyz - v_cloudPosition) : -u_modelView.xyz;
    float clouds = 1.0;
#ifdef cloudShadowFlag
    clouds = cloudTransmission(v_cloudPosition);
#endif
    vec3 ground = vec3(0.0);
#if numDirectionalLights > 0
    ground = u_dirLights[0].color * max(0.0, -u_dirLights[0].direction.z) * clouds;
#endif
    vec3 sky = u_ambientCubemap[5];
    vec3 diffuse = albedo * (1.0 - metallic);
    vec3 ambient = hemisphere(sky, ground, GROUND_ALBEDO, normal.z) * diffuse;
    // Broad sky/ground reflection keeps metals readable without an environment cubemap or another render pass.
    // This is a hemisphere approximation, not image-based lighting or a reflection of nearby objects.
    vec3 reflection = mix(reflect(-view, normal), normal, roughness * roughness);
    ambient += hemisphere(sky, ground, GROUND_ALBEDO, reflection.z) * albedo * metallic;
#ifdef ambientTextureFlag
    ambient *= texture(u_ambientTexture, v_modelUV).r;
#endif
    vec3 direct = vec3(0.0);
#if numDirectionalLights > 0
    vec3 f0 = mix(vec3(.04), albedo, metallic);
    for (int i = 0; i < numDirectionalLights; i++) {
        vec3 light = -u_dirLights[i].direction;
        direct += u_dirLights[i].color * (diffuse * max(0.0, dot(normal, light))
              + surfaceReflectance(normal, light, view, roughness, f0));
    }
#endif
#ifdef shadowMapFlag
    direct *= getShadow();
#endif
    return ambient + direct * clouds + diffuse * lavaIrradiance(v_cloudPosition, normal) + emission;
}
#endif
