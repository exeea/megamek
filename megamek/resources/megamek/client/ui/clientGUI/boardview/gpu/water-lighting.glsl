// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Linear lighting and premultiplied output for pools, waterfall curtains, spray and board-edge sections.
struct WaterLighting {
    vec3 light;
    vec3 whiteLight;
    vec3 sunlight;
    vec3 ambient;
    vec3 ground;
};

vec3 waterTint(vec4 mixture) {
    bool procedural = u_waterMaterial.y > 0.5;
    vec3 tint = vec3(1.0);
#ifdef diffuseColorFlag
    tint = u_diffuseColor.rgb;
#endif
    if (procedural) tint = mix(vec3(1.0), toLinear(vec3(.4, 1.0, .12)), mixture.w);
    return tint;
}

vec3 waterFroth(vec4 mixture, vec3 tint) {
    vec3 scatter = waterScatter(mixture);
    // Clear water foams white; silty and chemical water foams in the color of what it carries.
    return mix(toLinear(vec3(0.94, 0.97, 1.0)), scatter / max(max(scatter.r, scatter.g), scatter.b),
          mix(.3, .08, mixture.x)) * tint;
}

WaterLighting waterLighting() {
    // Level-surface irradiance for the column, foam, mist and falls alike: white water scatters light through its
    // whole volume, so a fall's foam reads exactly like the foam it lands in.
    vec3 ambient = vec3(0.0);
    vec3 lightNormal = vec3(0.0, 0.0, 1.0);
    vec3 albedo = vec3(1.0);
    vec3 sunlight = vec3(0.0);
#ifdef lightingFlag
    // This is incident irradiance for a scattering volume, before any particular surface BRDF/Fresnel split.
    vec3 direct = vec3(0.0), sheen = vec3(0.0);
    incidentLighting(lightNormal, ambient, direct);
    albedo *= ambient + direct;
#if numDirectionalLights > 0
    // Shadowed, cloud-filtered sunlight arriving along the sun direction, for the reflected glints.
    sunlight = direct / max(dot(lightNormal, -u_dirLights[0].direction), .0001);
#endif
#endif
    vec3 light = albedo;
    // White water scatters light through its whole volume: even in shade it passes on some of the sun's light.
    vec3 whiteLight = light;
#if defined(lightingFlag) && numDirectionalLights > 0
    whiteLight = max(light, ambient + sunOnGround() * 0.4);
#endif
    vec3 ground = GROUND_ALBEDO * light;
#ifdef lightingFlag
    ground = environmentGround(GROUND_ALBEDO);
#endif
    return WaterLighting(light, whiteLight, sunlight, ambient, ground);
}

void waterOutput(vec4 color) {
    color *= v_opacity;
    if (color.a < 0.002 && max(color.r, max(color.g, color.b)) < 0.002) discard;
    fragColor = color;
}
