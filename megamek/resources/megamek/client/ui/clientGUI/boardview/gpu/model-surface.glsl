// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported glTF surfaces share the terrain's energy-conserving direct and prefiltered environment response.
#if defined(normalFlag) && defined(lightingFlag)
#ifdef modelSurfaceFlag
uniform vec3 u_modelSurface; // roughness, metalness, packed map present
uniform sampler2D u_modelSurfaceMap;
in vec2 v_modelUV;
#endif
uniform vec4 u_modelView; // camera direction, perspective flag
uniform vec4 u_cameraPosition;
uniform vec3 u_ambientCubemap[6];
#if defined(modelSurfaceFlag) && defined(normalTextureFlag)
uniform float u_normalMaps;
#endif
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
uniform sampler2D u_ambientTexture;
#endif
#if numDirectionalLights > 0
struct DirectionalLight {
    vec3 color;
    vec3 direction;
};
uniform DirectionalLight u_dirLights[numDirectionalLights];
#endif

vec3 modelNormal(vec3 view, out vec3 geometric) {
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
    geometric = visibleFace(dx, dy, view);
    vec3 normal = normalize(v_normal);
#if defined(modelSurfaceFlag) && defined(normalTextureFlag)
    // Derive the glTF tangent frame from the original mesh UVs, independent of projected unit camouflage.
    // Degenerate UVs keep the geometric normal; mirrored UVs preserve their handedness.
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
            vec3 mapped = textureGrad(u_normalTexture, v_modelUV, ux, uy).xyz * 2.0 - 1.0;
            normal = normalize(mat3(tangent, bitangent, normal) * mapped);
        }
    }
#endif
    // main() calls this before any discard, and every branch above has joined again.
    surfaceVariance += normalVariance(dFdx(normal), dFdy(normal));
    return gl_FrontFacing ? normal : -normal;
}

vec3 modelSurface(vec3 albedo, vec3 emission, vec3 normal, vec3 geometric, vec3 view) {
    albedo = materialReflectance(albedo);
    float roughness = .85, metallic = 0.0;
#ifdef modelSurfaceFlag
    roughness = u_modelSurface.x; metallic = u_modelSurface.y;
    if (u_modelSurface.z > .5) {
        vec2 channels = texture(u_modelSurfaceMap, v_modelUV).gb;
        roughness *= channels.x;
        metallic *= channels.y;
    }
#endif
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    metallic = clamp(metallic, 0.0, 1.0);
    // Finish any scar normal before repairing the shared reflection frame.
    normal = shadingNormal(normal, geometric, view);
    roughness = antialiasedRoughness(roughness, surfaceVariance);
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
    vec3 f0 = mix(vec3(.04), albedo, metallic);
    vec3 remaining = 1.0 - specularAlbedo(max(0.0, dot(normal, view)), roughness, f0);
    vec3 groundRadiance = GROUND_ALBEDO * (sky + ground);
    vec3 ambient = environmentRadiance(normal, 1.0, groundRadiance, sky) * diffuse * remaining;
    ambient += environmentSpecular(normal, view, roughness, f0, groundRadiance, sky);
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
    ambient *= texture(u_ambientTexture, v_modelUV).r;
#endif
    vec3 direct = vec3(0.0);
#if numDirectionalLights > 0
    for (int i = 0; i < numDirectionalLights; i++) {
        vec3 light = -u_dirLights[i].direction;
        if (dot(geometric, light) > 0.0) {
            direct += u_dirLights[i].color * (diffuse * diffuseReflectance(normal, light, view, roughness, f0)
                  + surfaceReflectance(normal, light, view, roughness, f0));
        }
    }
#endif
#ifdef shadowMapFlag
    direct *= shadowVisibility(getShadow());
#endif
    return ambient + direct * clouds + diffuse * remaining * lavaIrradiance(v_cloudPosition, normal) + emission;
}
#endif
