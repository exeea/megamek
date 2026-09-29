// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared volcanic material evaluation for liquid sheets and terrain-boundary palettes.
uniform sampler2D u_diffuseTexture;
uniform sampler2D u_normalTexture;
uniform sampler2D u_specularTexture; // height, roughness, AO, relief UV / .1
uniform sampler2D u_emissiveTexture; // heat, signed flow XY, crack-wall glow
struct Volcanic {
    vec3 albedo;
    vec3 normal;
    vec4 surface;
    vec2 heat;
};

vec2 magmaRelief(vec2 uv, vec2 dx, vec2 dy, vec4 surface, vec2 parallax);

Volcanic magmaSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, float phase, vec2 velocity) {
    uv += velocity * phase;
    vec4 surface = textureGrad(u_specularTexture, uv, dx, dy);
    // Relief uses the same height/range as the baked normals. March into solid crust so a plate
    // occludes its recessed fissure instead of folding one large UV offset around every edge.
    // Support, silhouettes and picking stay on the board's real triangles.
    vec2 parallax = eye.xy / max(abs(eye.z), .35) * surface.a * .1 * u_normalMaps;
    uv = magmaRelief(uv, dx, dy, surface, parallax);
    surface = textureGrad(u_specularTexture, uv, dx, dy);
    vec3 normal = (textureGrad(u_normalTexture, uv, dx, dy).rgb * 255.0 - 128.0) / 127.0;
    vec4 heat = textureGrad(u_emissiveTexture, uv, dx, dy);
    return Volcanic(textureGrad(u_diffuseTexture, uv, dx, dy).rgb, normal, surface, heat.ra);
}

Volcanic magmaMix(Volcanic a, Volcanic b, float weight) {
    return Volcanic(mix(a.albedo, b.albedo, weight), mix(a.normal, b.normal, weight),
          mix(a.surface, b.surface, weight), mix(a.heat, b.heat, weight));
}

// MAGMA_CONDITION

vec3 magmaHash(vec2 cell) {
    vec3 p = fract(vec3(cell, cell.x + cell.y) * vec3(.1031, .1030, .0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.xxy + p.yzz) * p.zyx);
}

Volcanic magmaPatch(vec2 uv, vec2 dx, vec2 dy, vec2 cell, vec3 eye, vec2 downhill) {
    vec3 random = magmaHash(cell + vec2(17.0, 83.0));
    float angle = random.x * 6.2831853;
    mat2 rotation = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float scale = mix(.8, 1.2, random.z);
    uv = rotation * uv * scale + random.yz;
    dx = rotation * dx * scale;
    dy = rotation * dy * scale;
    eye.xy = rotation * eye.xy * scale;
    Volcanic material = magmaPhaseSample(uv, dx, dy, eye, rotation * downhill * scale);
    // Rotate the height gradient back with the texture. Rotating colour alone mislights the fissures.
    material.normal.xy = transpose(rotation) * material.normal.xy * scale / max(material.normal.z, .15);
    material.normal.z = 0.0;
    return material;
}

Volcanic magmaProjection(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec2 downhill) {
    // Three overlapping, randomly rotated/offset patches on a triangular lattice. Every map uses the same
    // weights, derivatives and transforms; no hex-local reset, repeated 12 m motif or hard patch seam.
    vec2 skew = mat2(1.0, 0.0, -.57735027, 1.15470054) * (uv * .85);
    vec2 cell = floor(skew), f = fract(skew);
    vec3 weights;
    vec2 a, b, c;
    if (f.x + f.y < 1.0) {
        weights = vec3(1.0 - f.x - f.y, f.x, f.y);
        a = cell; b = cell + vec2(1.0, 0.0); c = cell + vec2(0.0, 1.0);
    } else {
        weights = vec3(f.x + f.y - 1.0, 1.0 - f.x, 1.0 - f.y);
        a = cell + 1.0; b = cell + vec2(0.0, 1.0); c = cell + vec2(1.0, 0.0);
    }
    weights *= weights;
    weights *= weights;
    weights /= dot(weights, vec3(1.0));
    Volcanic first = magmaPatch(uv, dx, dy, a, eye, downhill);
    Volcanic second = magmaPatch(uv, dx, dy, b, eye, downhill);
    Volcanic third = magmaPatch(uv, dx, dy, c, eye, downhill);
    weights = magmaWeights(weights, vec3(first.surface.r, second.surface.r, third.surface.r));
    return Volcanic(first.albedo * weights.x + second.albedo * weights.y + third.albedo * weights.z,
          first.normal * weights.x + second.normal * weights.y + third.normal * weights.z,
          first.surface * weights.x + second.surface * weights.y + third.surface * weights.z,
          first.heat * weights.x + second.heat * weights.y + third.heat * weights.z);
}

Volcanic magmaSurface(vec3 world, vec3 face, vec3 eye, vec3 uphill, float bank, vec4 waves) {
    vec3 position = world / 12.0;
    // Smooth triplanar projection covers every slope orientation, including curved lava lips and vertical cuts.
    // Derivatives are taken before the projection branches, retaining stable mip levels through their blends.
    vec3 dx = dFdx(position), dy = dFdy(position);
    vec3 weights = pow(abs(face), vec3(4.0));
    weights /= dot(weights, vec3(1.0));
    vec3 pigment = vec3(0.0), gradient = vec3(0.0);
    vec4 surface = vec4(0.0);
    vec2 heat = vec2(0.0);
    for (int axis = 0; axis < 3; axis++) {
        if (weights[axis] < .001) continue;
        vec3 tangent = axis == 0 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 bitangent = axis == 2 ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 0.0, -1.0);
        vec2 uv = vec2(dot(position, tangent), dot(position, bitangent));
        vec2 du = vec2(dot(dx, tangent), dot(dx, bitangent));
        vec2 dv = vec2(dot(dy, tangent), dot(dy, bitangent));
        vec3 localEye = vec3(dot(eye, tangent), dot(eye, bitangent), dot(eye, face));
        vec2 downhill = vec2(dot(uphill, tangent), dot(uphill, bitangent));
        Volcanic material = magmaProjection(uv, du, dv, localEye, downhill);
        pigment += material.albedo * weights[axis];
        surface += material.surface * weights[axis];
        heat += material.heat * weights[axis];
        gradient += (tangent * material.normal.x + bitangent * material.normal.y) * weights[axis];
    }
    gradient -= face * dot(face, gradient);
    vec3 swell = vec3(-waves.xy, 0.0) * 1.5 * bank;
    gradient += swell - face * dot(swell, face);
    vec3 normal = normalize(face + gradient * u_normalMaps);
    return Volcanic(pigment, normal, surface, heat);
}

vec3 magmaEmission(vec2 heat, float strength) {
    // Emission is added AFTER sunlight, occlusion and cloud/geometry shadowing. Night never extinguishes heat.
    // Stay below the scene target's LDR limit instead of clipping whole channels to white.
    vec3 hot = magmaHeatColor(heat.x);
    vec3 emission = hot * heat.x * strength;
    vec3 crackGlow = vec3(.25, .022, .0015) * heat.y * strength * (1.0 - heat.x);
    return emission + crackGlow;
}
