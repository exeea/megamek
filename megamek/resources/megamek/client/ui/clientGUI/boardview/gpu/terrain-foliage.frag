// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Trees: their models' own colours and detail maps, lit like the sculpted terrain they stand on (light-model.glsl,
// surface-lighting.glsl): linear light, sky and ground bounce and soft shadows; the composite rolls off their
// highlights. A canopy scatters light through its leaves, so the sun wraps around it instead of stopping at a hard
// terminator.
#ifdef GL_ES
precision highp float;
#endif
varying vec3 v_normal;
#ifdef colorFlag
varying vec4 v_color;
#endif
#ifdef diffuseTextureFlag
varying vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
#ifdef blendedFlag
varying float v_opacity;
#ifdef alphaTestFlag
varying float v_alphaTest;
#endif
#endif
uniform float u_foliage; // the part: 0 solid (bark, cactus stems), 1 canopy (leaves, needles, fronds), 2 snow
uniform float u_clay;

void main() {
    vec3 face = normalize(v_normal);
    vec4 diffuse = vec4(1.0);
#ifdef diffuseTextureFlag
    diffuse = texture2D(u_diffuseTexture, v_diffuseUV);
#endif
#ifdef colorFlag
    diffuse *= v_color;
#endif
#ifdef diffuseColorFlag
    diffuse *= u_diffuseColor;
#endif
    vec3 albedo = diffuse.rgb;
    bool canopy = abs(u_foliage - 1.0) < .5;
    // The source models tint their snow; fresh snow stays neutral, as on the ground below.
    if (u_foliage > 1.5) albedo = vec3(dot(albedo, vec3(.2126, .7152, .0722))) * vec3(.97, .98, 1.02);
    if (u_clay > .5) albedo = vec3(.52);
    albedo *= 1.0 - u_wetness * (u_foliage > 1.5 ? 0.0 : .12);
    albedo = toLinear(albedo);
#ifdef lightingFlag
    // Inside and under a canopy the sky is hidden by the leaves above.
    vec3 ambient = skyLight(face, GROUND_ALBEDO) * (canopy ? mix(.7, 1.0, face.z * .5 + .5) : .85);
    vec3 direct = vec3(0.0);
#if numDirectionalLights > 0
    vec3 light = -u_dirLights[0].direction;
    float incidence = canopy ? max(0.0, dot(face, light) * .6 + .4) : max(0.0, dot(face, light));
    direct = u_dirLights[0].color * sculptShadow(face, light) * incidence;
#endif
    // Cloud shadows attenuate direct light here (inserted by GpuCloudShadow).
    vec3 sheen = vec3(0.0);
    albedo *= ambient + direct;
    albedo += sheen;
#endif
    gl_FragColor.rgb = toDisplay(albedo);
#ifdef blendedFlag
    gl_FragColor.a = diffuse.a * v_opacity;
#ifdef alphaTestFlag
    if (gl_FragColor.a <= v_alphaTest) discard;
#endif
#else
    gl_FragColor.a = 1.0;
#endif
}
