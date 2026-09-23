// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Linear lighting shared by the sculpted terrain and the trees standing on it, so both agree at every hour. Inserted
// after the renderer's lighting declarations.
uniform float u_sceneExposure; // the atmosphere composite's exposure; it clips linear values above its inverse

// Linear light calibration: the atmosphere's colours are authored for display-space shading. These gains keep
// sunlit level ground at its established brightness while shade, walls and crevices get physically ordered light.
const float SUN_GAIN = 3.6;
const float SKY_GAIN = 1.75;
const float BOUNCE_GAIN = .55;

vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }

// The composite exposes the frame and clips at white. Sunlit snow and bright sand would clip flat there, so they
// roll off smoothly towards the white point instead, keeping their hue and their detail.
vec3 shoulder(vec3 c) {
    float white = .98 / max(u_sceneExposure, .001), knee = .7 * white;
    float peak = max(c.r, max(c.g, c.b));
    if (peak <= knee) return c;
    return c * ((knee + (white - knee) * (1.0 - exp(-(peak - knee) / (white - knee)))) / peak);
}
vec3 toDisplay(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

#ifdef lightingFlag
// Sky light over a surface facing along normal: the sky's colour from above, bounce from the ground below.
vec3 skyLight(vec3 normal) {
    vec3 sky = toLinear(surfaceAmbient(vec3(0.0, 0.0, 1.0)));
    return mix(sky * BOUNCE_GAIN * vec3(1.08, 1.0, .88), sky * SKY_GAIN * vec3(.94, 1.0, 1.1), normal.z * .5 + .5);
}

#if numDirectionalLights > 0
vec3 sunLight() { return toLinear(u_dirLights[0].color) * SUN_GAIN; }
#endif
#endif

#ifdef shadowMapFlag
uniform mat4 u_shadowMapProjViewTrans;
#endif

float sculptShadow(vec3 normal, vec3 light) {
#ifdef shadowMapFlag
    // Normal offset: the lookup moves out along the surface normal by half a shadow texel, and by up to two where the
    // light skims the surface, so a flat face lit at a grazing angle never shadows itself.
    vec3 row = vec3(u_shadowMapProjViewTrans[0][0], u_shadowMapProjViewTrans[1][0], u_shadowMapProjViewTrans[2][0]);
    float texel = 4.0 * u_shadowPCFOffset / max(length(row), 1e-6);
    float skim = 1.0 - abs(dot(normal, light));
    vec4 lifted = u_shadowMapProjViewTrans * vec4(v_cloudPosition + normal * texel * (.5 + 1.5 * skim), 1.0);
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
        vec4 depthChannels = texture2D(u_shadowTexture, shadowUv.xy + offset);
        float depth = dot(depthChannels, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0));
        float receiver = shadowUv.z + clamp(dot(offset, slope), -.002, .002);
        lit += step(receiver, depth);
    }
    return lit / 12.0;
#else
    return 1.0;
#endif
}
