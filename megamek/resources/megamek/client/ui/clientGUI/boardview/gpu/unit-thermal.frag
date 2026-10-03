#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// False-color sensor presentation, independent of paint and sunlight. Only the wireframe unit pass uses it.
layout(location = 0) out vec4 fragColor;
in vec3 v_thermalPosition;
uniform vec3 u_thermal; // captured heat intensity, bottom, inverse height
uniform vec4 u_thermalShape; // horizontal center and inverse half-extents; zero scale for formations
uniform vec4 u_cameraPosition;
#ifdef normalFlag
in vec3 v_normal;
#endif
#ifdef diffuseTextureFlag
in vec2 v_diffuseUV;
uniform sampler2D u_diffuseTexture;
#endif
#ifdef diffuseColorFlag
uniform vec4 u_diffuseColor;
#endif
#ifdef colorFlag
in vec4 v_color;
#endif
#ifdef blendedFlag
in float v_opacity;
#ifdef alphaTestFlag
in float v_alphaTest;
#endif
#endif

vec3 thermalPalette(float heat) {
    const vec3 colors[7] = vec3[7](vec3(.025, .055, .85), vec3(.32, .015, 1.0),
          vec3(.85, .015, .58), vec3(1.0, .035, .06), vec3(1.0, .40, .015),
          vec3(1.0, .90, .12), vec3(1.0, 1.0, .94));
    float index = clamp(heat, 0.0, 1.0) * 6.0;
    int low = min(int(index), 5);
    return mix(colors[low], colors[low + 1], smoothstep(0.0, 1.0, index - float(low)));
}

void main() {
    // Preserve the ordinary shader's silhouette, including sprite cutouts and translucent model parts.
    float alpha = 1.0;
    #ifdef blendedFlag
        alpha = v_opacity;
        #ifdef diffuseTextureFlag
            alpha *= texture(u_diffuseTexture, v_diffuseUV).a;
        #endif
        #ifdef diffuseColorFlag
            alpha *= u_diffuseColor.a;
        #endif
        #ifdef colorFlag
            alpha *= v_color.a;
        #endif
        #ifdef alphaTestFlag
            if (alpha <= v_alphaTest) { discard; }
        #endif
    #endif

    // Broad body variation is visual styling, not simulated per-location heat. The warm upper mass and cool feet
    // also keep individual troopers readable. Normals provide gentle facets without sunlight or shadow sampling.
    float height = clamp((v_thermalPosition.z - u_thermal.y) * u_thermal.z, 0.0, 1.0);
    float core = smoothstep(.08, .65, height) * (1.0 - .18 * smoothstep(.82, 1.0, height));
    // A flat sprite has no vertical body extent; its silhouette still uses the full captured heat range.
    if (u_thermal.z == 0.0) { core = 1.0; }
    vec2 radial = (v_thermalPosition.xy - u_thermalShape.xy) * u_thermalShape.zw;
    core *= mix(.45, 1.0, exp(-1.4 * dot(radial, radial)));
    #ifdef normalFlag
        vec3 normal = normalize(v_normal);
    #else
        vec3 normal = normalize(cross(dFdx(v_thermalPosition), dFdy(v_thermalPosition)));
    #endif
    float facing = abs(dot(normal, normalize(u_cameraPosition.xyz - v_thermalPosition)));
    float heat = u_thermal.x * mix(.28, 1.0, core) + .035 * facing;
    fragColor = vec4(thermalPalette(heat) * mix(.78, 1.0, facing), alpha);
}
