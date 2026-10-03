// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Moving pools and falls: two-phase advection, convection and orange molten heat.
uniform float u_magmaTime; // gravity-scaled flow time, advanced once by the board's shared animation
uniform sampler2D u_magmaOcean; // shared inverse FFT: RG slope, BA horizontal displacement in metres
uniform float u_magmaOceanScale;
const float MAGMA_CYCLE = 4.0;
// The molten pattern spans larger, readable rafts. Solid crust retains its authored 12 m repeat.
float magmaRepeatMetres() { return 24.0; }
// BoardFlow already slows lava to a quarter of water's speed. This keeps its ordinary reach near 2.4 m/s at 1g.
const float MAGMA_CURRENT_SPEED = 3.2;

Volcanic magmaPhaseSample(vec2 uv, vec2 dx, vec2 dy, vec3 eye, vec3 sun, vec2 velocity, vec3 random) {
    // Both phases carry the complete material downstream, including heat. Refreshing the source window at hidden
    // resets avoids repeating the same short loop while keeping local displacement bounded around bends.
    // Water staggers resets with its broad noise map. Lava uses a broad analytic seed without another map read.
    float stagger = random.z + .21 * sin(dot(uv, vec2(.71, .43))) + .17 * sin(dot(uv, vec2(-.37, .91)));
    LiquidFlow flow = liquidFlow(u_magmaTime, MAGMA_CYCLE, stagger);
    // Source offsets are uniform in texture space, so they cannot accumulate shear in a varying current field.
    // A new offset is revealed only as its phase fades in. Still pools retain only their physical convection.
    float moving = smoothstep(.002, .02, length(velocity));
    vec2 jump = vec2(.75487766, .56984029);
    vec2 first = liquidFlowUv(uv, velocity, flow, 0, jump, moving);
    vec2 second = liquidFlowUv(uv, velocity, flow, 1, jump, moving);
    Volcanic a = magmaSample(first, dx, dy, eye, sun);
    Volcanic b = magmaSample(second, dx, dy, eye, sun);
    // Blend emitted light rather than applying a nonlinear heat ramp to an averaged temperature: that would
    // pulse at each crossfade. Each phase's cooling skin remains opaque and dark.
    a.emission = magmaEmission(a.heat, smoothstep(.28, .56, a.heat.x));
    b.emission = magmaEmission(b.heat, smoothstep(.28, .56, b.heat.x));
    return magmaMix(a, b, flow.weight.y);
}

mat3 magmaDomain(inout vec3 position) {
    // Two successive shears bend the source's long cooling rafts into irregular currents.
    // Their different wavelengths break up the visible tile grid. Each shear is invertible,
    // even at this strength: the texture bends without folding or adding material samples.
    vec2 phaseX = position.y * vec2(1.63, .57) + position.z * vec2(.41, 1.21) + vec2(.1, 2.7);
    vec2 slopeX = vec2(.30, .12) * cos(phaseX);
    position.x += dot(vec2(.30, .12), sin(phaseX));
    mat3 shearX = mat3(vec3(1.0, 0.0, 0.0), vec3(dot(slopeX, vec2(1.63, .57)), 1.0, 0.0),
          vec3(dot(slopeX, vec2(.41, 1.21)), 0.0, 1.0));
    vec2 phaseY = position.x * vec2(1.37, .63) + position.z * vec2(-.53, 1.07) + vec2(1.3, 3.1);
    vec2 slopeY = vec2(.25, .10) * cos(phaseY);
    position.y += dot(vec2(.25, .10), sin(phaseY));
    mat3 shearY = mat3(vec3(1.0, dot(slopeY, vec2(1.37, .63)), 0.0), vec3(0.0, 1.0, 0.0),
          vec3(0.0, dot(slopeY, vec2(-.53, 1.07)), 1.0));
    return shearY * shearX;
}

vec3 magmaDrift(vec3 face, vec2 current, float bank, vec4 waves) {
    // The same bank/current field as water carries branches and bends in world XY. Keep that direction on
    // level reaches; gravity takes over on steep falls. Projection works on either side of a liquid sheet.
    vec3 velocity = vec3(current * (30.0 * MAGMA_CURRENT_SPEED), 0.0);
    velocity = (velocity - face * dot(velocity, face)) * bank;
    // The field also contains slope acceleration. Bound its artistic speed before phase offsets can span a raft.
    velocity *= min(1.0, 8.4 / max(length(velocity), .001));
    vec3 down = vec3(0.0, 0.0, -1.0) + face * face.z;
    velocity = mix(velocity, down * 4.8, smoothstep(.05, .7, length(down)));
    // A still pool is still molten. The existing wave-height gradient rotated by 90 degrees gives local
    // circulation, rather than translating every pool in an arbitrary direction. Carry the whole material with
    // it through the shared advection; fade it out on currents, banks and falls so downhill transport wins.
    vec3 convection = vec3(-waves.y, waves.x, 0.0) * 6.0;
    convection *= min(1.0, 1.2 / max(length(convection), .001));
    convection -= face * dot(convection, face);
    velocity += convection * bank * (1.0 - smoothstep(.004, .020, length(current)))
          * smoothstep(.75, .95, abs(face.z));
    // The shared liquid engine samples against this physical velocity (texture repeats per second).
    // magmaPatch transforms it with the same domain/rotation as every map, preventing vertical counterflow.
    return velocity / magmaRepeatMetres();
}

// The existing shore distance makes a coherent hot interior and a cooler red margin around retained dark rafts.
Volcanic magmaChill(Volcanic material, float shore) {
    // Exposed melt is glossier than its rough cooling rafts, using the existing heat sample.
    float melt = smoothstep(.45, .80, material.heat.x);
    material.surface.g = mix(material.surface.g, min(material.surface.g, .4), melt);
    float chill = 1.0 - smoothstep(0.0, MAGMA_MARGIN, shore);
    // A wider thermal margin follows the actual channel. Scale linear radiance, keeping phase blending
    // energy-preserving and cold rafts cold instead of adding a uniform yellow layer over the texture.
    float core = smoothstep(.02, .25, shore);
    material.emission *= mix(vec3(.30, .035, .06), vec3(1.0), core);
    material.albedo = mix(material.albedo, vec3(dot(material.albedo, vec3(.3, .59, .11)) * .6), chill);
    material.surface.g = mix(material.surface.g, max(material.surface.g, .8), chill);
    return material;
}

vec3 magmaHeatColor(float heat) {
    // Linear radiance, deliberately above diffuse white: the scene keeps this energy for its
    // highlight shoulder and heat halo. Cooling skin stays red; exposed melt reaches orange/yellow.
    vec3 body = mix(vec3(1.2, .008, .0005), vec3(4.2, .40, .006), smoothstep(.15, .70, heat));
    return mix(body, vec3(10.0, 5.5, .14), smoothstep(.68, .94, heat));
}

vec3 magmaRadiance(Volcanic material, float bank, float strength) {
    return material.emission * (mix(.72, 1.0, bank) * strength);
}
