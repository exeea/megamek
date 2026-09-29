// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by frozen lake sheets, land ice and detected black ice on pavement/roads/bridge decks.
#ifdef iceFlag
uniform sampler2D u_iceColor;
uniform sampler2D u_iceNormal;
uniform sampler2D u_iceSurface; // R micro-height, G roughness, B optical thickness, A cloudy/cracked fraction
uniform float u_iceMode;       // 1 land ice, 2 detected thin black ice, 3 translucent lake sheet
uniform float u_iceMetre;
uniform float u_iceNormals;

vec3 iceFinish(vec3 underlying) {
    vec3 face = normalize(v_normal);
    float coverage = smoothstep(.45, .88, face.z);
    if (coverage <= 0.0) { return underlying; }
    vec2 uv = vec2(v_cloudPosition.x, -v_cloudPosition.y) / (24.0 * u_iceMetre);
    vec2 footprint = max(abs(dFdx(uv)), abs(dFdy(uv)));
    // Shading LOD follows projected texel footprint; mipmaps filter every map with the same derivatives.
    float detail = 1.0 - smoothstep(.006, .07, max(footprint.x, footprint.y));
    vec4 properties = texture(u_iceSurface, uv);
    vec3 normal = face;
    if (detail * u_iceNormals > .001) {
        vec3 micro = (texture(u_iceNormal, uv).xyz * 255.0 - 128.0) / 127.0;
        vec3 tangent = normalize(vec3(face.z, 0.0, -face.x));
        micro.xy *= detail;
        normal = normalize(tangent * micro.x - cross(face, tangent) * micro.y + face * micro.z);
    }
    float thin = step(1.5, u_iceMode) * (1.0 - step(2.5, u_iceMode));
    vec3 tint = texture(u_iceColor, uv).rgb;
    // Broad clear and cloudy fields interrupt the repeating fine fractures across a connected lake.
    float clearField = smoothstep(.38, .68, texture(u_iceColor, uv * .217 + vec2(.31, .73)).g);
    tint = mix(tint * vec3(.59, .76, .84), tint, .4 + .6 * clearField);
    // A tiny refractive offset makes the buried cloudy layer read beneath the smooth skin at close zoom.
    if (detail > .15) {
        vec2 offset = normal.xy * properties.r * .012 * detail;
        tint = mix(tint, texture(u_iceColor, uv + offset).rgb, .4);
    }
    float cloudy = properties.a;
    float opacity = mix(.94, .08 + .27 * cloudy, thin);
    vec3 body = toLinear(tint);
    vec3 sheen = vec3(0.0);
#ifdef lightingFlag
    vec3 ambient, direct;
    surfaceLighting(normal, 1.0, mix(properties.g * (.55 + .45 * clearField), .30, 1.0 - detail), ambient, direct, sheen);
#ifdef cloudShadowFlag
    float cloudLight = cloudTransmission(v_cloudPosition);
    direct *= cloudLight;
    sheen *= cloudLight;
#endif
    // Pale embedded frost scatters light; the clear part transmits the existing lit road without losing markings.
    body *= ambient + direct;
#endif
    vec3 transmitted = toLinear(underlying) * mix(vec3(.88, .95, .98), vec3(.74, .88, .94), properties.b * .35);
    vec3 coated = mix(transmitted, body, opacity) + sheen;
    vec3 reflected = reflect(viewDirection(), normal);
    vec3 sky = mix(u_rainHorizon, u_rainSky, sqrt(clamp(reflected.z, 0.0, 1.0)));
    float grazing = 1.0 - max(0.0, dot(-viewDirection(), normal));
    float fresnel = .018 + .982 * pow(grazing, 5.0);
    if (u_iceMode > 2.5) {
        // Actual transmission through the sheet into the existing opaque scene. Clear zones transmit
        // more; buried fractures and frost scatter more. Reflection replaces transmission at grazing angles.
        float scatter = clamp(.24 + .38 * cloudy + .18 * properties.b, .24, .82);
        float reflection = fresnel * (1.0 - properties.g * .7);
        float alpha = scatter + reflection * (1.0 - scatter);
        fragColor.a = alpha;
        return toDisplay((body * scatter + toLinear(sky) * reflection * (1.0 - scatter) + sheen) / alpha);
    }
    coated = mix(coated, toLinear(sky), fresnel * (1.0 - properties.g * .7));
    return mix(underlying, toDisplay(coated), coverage);
}
#endif

void main() {
    iceBaseSurface();
#ifdef iceFlag
    fragColor.rgb = iceFinish(fragColor.rgb);
#endif
}
