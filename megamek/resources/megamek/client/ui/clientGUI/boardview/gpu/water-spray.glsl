// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Added to the water vertex shader. GpuWaterfall supplies the speed used to encode the particle vertices.
#if defined(colorFlag) && defined(normalFlag) && defined(diffuseTextureFlag)
#define waterSprayFlag
uniform vec4 u_waterMaterial;
uniform float u_rainTime;
uniform float u_metre;
uniform vec3 u_wind;
uniform vec3 u_cameraDirection;
uniform vec3 u_cameraUp;
#endif

void waterSpray(inout vec4 pos) {
    #ifdef waterSprayFlag
    if (u_waterMaterial.w > 0.5) {
        float gravity = 9.81 * u_metre;
        float mist = step(0.75, a_color.a), droplet = step(0.25, a_color.a) - mist;
        float speed = a_color.b * @SPRAY_SPEED@ * u_metre;
        vec3 aim = normalize(a_normal);
        // A droplet lives until it falls back into the water; mist until it has thinned away. Each rests a
        // moment below the surface, then launches again with a fresh aim, never twice on the same arc.
        float life = mix(2.0 * speed * max(aim.z, 0.2) / gravity, 2.4 + 1.8 * a_color.g, mist);
        float period = life * 1.3 + 0.15;
        float cycle = u_rainTime / period + a_color.r;
        float t = fract(cycle) * period;
        float launch = floor(cycle);
        vec2 jitter = fract(sin(vec2(launch * 12.9898 + a_color.r * 78.233,
              launch * 39.346 + a_color.g * 11.135)) * 43758.5453) - 0.5;
        vec3 flight = normalize(aim + vec3(jitter * 0.4, 0.0));
        vec3 p = pos.xyz + vec3(jitter * 2.0 * u_metre, 0.0);
        p += flight * (speed * t) * (1.0 - mist);
        p.z -= 0.5 * gravity * t * t * (1.0 - mist);
        vec3 drift = vec3(flight.xy * 0.8 * u_metre + u_wind.xy * u_wind.z * 2.0 * u_metre, speed);
        p += drift * t * mist;
        float age = t / life;
        float alive = step(t, life);
        float puff = mix(0.9, 2.0, a_color.g) * (0.7 + 0.9 * age);
        float size = mix(mix(puff, mix(0.12, 0.24, a_color.g), droplet),
              mix(2.2, 4.2, a_color.g) * (0.6 + 1.1 * age), mist) * u_metre * alive;
        // The quad faces the camera; a droplet's is drawn out along its flight on screen, a short streak.
        vec3 velocity = flight * speed - vec3(0.0, 0.0, gravity * t);
        vec3 onScreen = velocity - u_cameraDirection * dot(velocity, u_cameraDirection);
        vec3 across = normalize(cross(u_cameraDirection, u_cameraUp));
        vec3 along = normalize(cross(across, u_cameraDirection));
        if (droplet > 0.5 && dot(onScreen, onScreen) > 1e-4) {
            along = normalize(onScreen);
            across = normalize(cross(u_cameraDirection, along));
        }
        float stretch = size + droplet * length(onScreen) * 0.045 * alive;
        vec2 corner = a_texCoord0 * 2.0 - 1.0;
        pos.xyz = p + across * (corner.x * size) + along * (corner.y * stretch);
        v_color = vec4(age, a_color.g, alive, a_color.a);
    }
    #endif
}
