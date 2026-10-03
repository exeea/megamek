// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Every custom lit surface shares per-fragment lighting and geometry-shadow sampling, after light-model.glsl.
// Final material visibility attenuates direct illumination only; cavities never extinguish emitted heat.
float reliefVisibility = 1.0;
#ifdef lightingFlag
uniform vec3 u_ambientCubemap[6];
#if numDirectionalLights > 0
struct DirectionalLight {
    vec3 color;
    vec3 direction;
};
uniform DirectionalLight u_dirLights[numDirectionalLights];
#endif
#ifdef shadowMapFlag
uniform sampler2D u_shadowTexture;
uniform float u_shadowPCFOffset;
uniform mat4 u_shadowMapProjViewTrans;
in vec3 v_shadowMapUv;

float shadowSample(vec2 offset) {
    vec4 depthChannels = texture(u_shadowTexture, v_shadowMapUv.xy + offset);
    float depth = dot(depthChannels, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0));
    return step(v_shadowMapUv.z, depth);
}
#endif

vec3 surfaceAmbient(vec3 normal) {
    vec3 squared = normal * normal;
    vec3 positive = step(vec3(0.0), normal);
    return squared.x * mix(u_ambientCubemap[0], u_ambientCubemap[1], positive.x)
          + squared.y * mix(u_ambientCubemap[2], u_ambientCubemap[3], positive.y)
          + squared.z * mix(u_ambientCubemap[4], u_ambientCubemap[5], positive.z);
}

// Direct light on level ground, which the ground reflects into walls and undersides.
vec3 sunOnGround() {
#if numDirectionalLights > 0
    return u_dirLights[0].color * max(0.0, -u_dirLights[0].direction.z);
#else
    return vec3(0.0);
#endif
}

// Sky light on a face along normal: the sky from above, the sunlit ground of this albedo from below. A cloud's shadow
// over the face shades that ground too.
vec3 skyLight(vec3 normal, vec3 groundAlbedo) {
    vec3 ground = sunOnGround();
#ifdef cloudShadowFlag
    ground *= cloudTransmission(v_cloudPosition);
#endif
    return hemisphere(surfaceAmbient(vec3(0.0, 0.0, 1.0)), ground, groundAlbedo, normal.z);
}

float surfaceVisibility() {
#ifdef shadowMapFlag
    float offset = u_shadowPCFOffset;
    return 0.25 * (shadowSample(vec2(offset, offset)) + shadowSample(vec2(-offset, offset))
          + shadowSample(vec2(offset, -offset)) + shadowSample(vec2(-offset, -offset)));
#else
    return 1.0;
#endif
}

#ifdef impostorFlag
// The crown radius of an impostor card's tree, in world units (tree-instances.glsl).
in float v_impostorLift;
#endif

// The sculpted terrain and its trees filter the shadow map more finely than the 4-tap surfaceVisibility().
float sculptShadow(vec3 normal, vec3 light) {
#ifdef shadowMapFlag
    // Normal offset: the lookup moves out along the surface normal by half a shadow texel, and by up to two where the
    // light skims the surface, so a flat face lit at a grazing angle never shadows itself.
    vec3 row = vec3(u_shadowMapProjViewTrans[0][0], u_shadowMapProjViewTrans[1][0], u_shadowMapProjViewTrans[2][0]);
    float texel = 4.0 * u_shadowPCFOffset / max(length(row), 1e-6);
    float skim = 1.0 - abs(dot(normal, light));
    vec3 receiver = v_cloudPosition + normal * texel * (.5 + 1.5 * skim);
#ifdef impostorFlag
    // An impostor card stands in the middle of its crown, whose leaves face the light about a crown radius nearer:
    // looked up there, the card's own cards and crown cannot shade it, while other trees and buildings still do.
    receiver += light * v_impostorLift;
#endif
    vec4 lifted = u_shadowMapProjViewTrans * vec4(receiver, 1.0);
    vec3 shadowUv = lifted.xyz / lifted.w * .5 + .5;
    shadowUv.z = min(shadowUv.z, .998);
    // Rotated 12-tap disk, fixed to world position so it does not crawl when the camera moves.
    vec2 taps[12];
    taps[0] = vec2(-.326, -.406); taps[1] = vec2(-.840, -.074); taps[2] = vec2(-.696, .457);
    taps[3] = vec2(-.203, .621); taps[4] = vec2(.962, -.195); taps[5] = vec2(.473, -.480);
    taps[6] = vec2(.519, .767); taps[7] = vec2(.185, -.893); taps[8] = vec2(.507, .064);
    taps[9] = vec2(.896, .412); taps[10] = vec2(-.322, -.933); taps[11] = vec2(-.792, -.598);
    float angle = fract(sin(dot(floor(v_cloudPosition.xy * 3.0), vec2(12.9898, 78.233))) * 43758.5453) * 6.2832;
    mat2 rotation = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float radius = u_shadowPCFOffset * 3.0;
    // Receiver-plane depth: each tap compares against this surface's own depth at the tap, not at the pixel centre,
    // so walls and grazing ground do not shadow themselves across the filter disk.
    vec3 du = dFdx(shadowUv), dv = dFdy(shadowUv);
    float det = du.x * dv.y - du.y * dv.x;
    vec2 slope = abs(det) > 1e-14 ? vec2(dv.y * du.z - du.y * dv.z, du.x * dv.z - dv.x * du.z) / det : vec2(0.0);
    float lit = 0.0;
    for (int i = 0; i < 12; i++) {
        vec2 offset = rotation * taps[i] * radius;
        vec4 depthChannels = texture(u_shadowTexture, shadowUv.xy + offset);
        float depth = dot(depthChannels, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0));
        float receiver = shadowUv.z + clamp(dot(offset, slope), -.002, .002);
        lit += step(receiver, depth);
    }
    return lit / 12.0;
#else
    return 1.0;
#endif
}

float surfaceSpecular(vec3 normal, vec3 view, vec3 light, float film, float roughness) {
    float incidence = max(0.0, dot(normal, light));
    vec3 halfVector = normalize(light + view);
    float nv = max(.001, dot(normal, view)), nh = max(0.0, dot(normal, halfVector));
    float r = mix(clamp(roughness, .15, .98), .12, film);
    // Filter unresolved normal variance into roughness rather than sparkling as the camera moves.
    vec3 dx = dFdx(normal), dy = dFdy(normal);
    r = min(.98, sqrt(r * r + min(.18, .35 * (dot(dx, dx) + dot(dy, dy)))));
    float a2 = r * r * r * r;
    float denominator = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(3.14159265 * denominator * denominator, .0001);
    float k = (r + 1.0) * (r + 1.0) / 8.0;
    float visibility = nv / (nv * (1.0 - k) + k) * incidence / (incidence * (1.0 - k) + k);
    float fresnel = .04 + .96 * pow(1.0 - max(0.0, dot(view, halfVector)), 5.0);
    return distribution * visibility * fresnel / (4.0 * nv);
}

void surfaceLighting(vec3 normal, float film, float roughness, out vec3 ambient, out vec3 direct, out vec3 sheen) {
    ambient = skyLight(normal, GROUND_ALBEDO) + lavaIrradiance(v_cloudPosition, normal);
    direct = vec3(0.0);
    sheen = vec3(0.0);
#if numDirectionalLights > 0
    vec3 view = -viewDirection();
    for (int i = 0; i < numDirectionalLights; i++) {
        vec3 light = -u_dirLights[i].direction;
        float incidence = max(0.0, dot(normal, light));
        direct += u_dirLights[i].color * incidence;
        if (roughness >= 0.0) {
            // Dielectric GGX for materials with authored roughness. Wet pores develop a smoother water film.
            // Evaluate derivatives on both sides of the light terminator; zero incidence already returns zero.
            sheen += u_dirLights[i].color * surfaceSpecular(normal, view, light, film, roughness);
        } else if (film > 0.0 && incidence > 0.0) {
            vec3 halfVector = normalize(light + view);
            float exponent = mix(12.0, 96.0, film);
            float fresnel = 0.02 + 0.98 * pow(1.0 - max(0.0, dot(view, halfVector)), 5.0);
            float specular = pow(max(0.0, dot(normal, halfVector)), exponent) * (exponent + 2.0) / 8.0;
            sheen += u_dirLights[i].color * incidence * film * fresnel * specular;
        }
    }
#endif
#ifdef shadowMapFlag
    float visibility = surfaceVisibility();
    direct *= visibility;
    sheen *= visibility;
#endif
    direct *= reliefVisibility;
    sheen *= reliefVisibility;
}

// Existing surfaces keep their established water-film response until they supply a roughness map.
void surfaceLighting(vec3 normal, float film, out vec3 ambient, out vec3 direct, out vec3 sheen) {
    surfaceLighting(normal, film, -1.0, ambient, direct, sheen);
}
#endif

vec3 surfaceSunDirection() {
#if defined(lightingFlag) && numDirectionalLights > 0
    return -u_dirLights[0].direction;
#else
    return vec3(0.0, 0.0, 1.0);
#endif
}
