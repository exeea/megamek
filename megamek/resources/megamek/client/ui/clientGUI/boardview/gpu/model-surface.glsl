// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Imported glTF surfaces fill a Surface from their material maps and are lit by shadeSurface, as the terrain is.
#ifdef lightingFlag
#ifdef modelSurfaceFlag
uniform vec3 u_modelSurface; // roughness, metalness, packed map present
uniform sampler2D u_modelSurfaceMap;
in vec2 v_modelUV;
#endif
#if defined(modelSurfaceFlag) && defined(normalTextureFlag)
uniform float u_normalMaps;
#endif
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
uniform sampler2D u_ambientTexture;
#endif

// The interpolated vertex normal; a model without normals shades its visible faces.
vec3 modelVertexNormal(vec3 geometric) {
#ifdef normalFlag
    return normalize(v_normal);
#else
    return geometric;
#endif
}

vec3 modelNormal(vec3 view, out vec3 geometric) {
    vec3 dx = dFdx(v_cloudPosition), dy = dFdy(v_cloudPosition);
    geometric = visibleFace(dx, dy, view);
    vec3 normal = modelVertexNormal(geometric);
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
#ifdef normalFlag
    return gl_FrontFacing ? normal : -normal;
#else
    return normal;
#endif
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
    // The metallic share reflects its own colour and diffuses none.
    Surface surface = opaqueSurface(albedo * (1.0 - metallic), normal, geometric, roughness, 0.0, surfaceVisibility());
    surface.f0 = albedo;
    surface.metallic = metallic;
#if defined(modelSurfaceFlag) && defined(ambientTextureFlag)
    surface.occlusion = surface.specularOcclusion = texture(u_ambientTexture, v_modelUV).r;
#endif
    surface.emission = emission;
    return shadeSurface(surface);
}
#endif
