// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Every custom lit surface shares per-fragment lighting and geometry-shadow sampling, after light-model.glsl.
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
uniform sampler2DShadow u_shadowTexture;
uniform float u_shadowPCFOffset;
uniform mat4 u_shadowMapProjViewTrans;
in vec3 v_shadowMapUv;

float shadowSample(vec2 offset) {
    return texture(u_shadowTexture, vec3(v_shadowMapUv.xy + offset, v_shadowMapUv.z));
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
vec3 environmentGround(vec3 groundAlbedo) {
    vec3 ground = sunOnGround();
#ifdef cloudShadowFlag
    ground *= cloudTransmission(v_cloudPosition);
#endif
    return groundAlbedo * (surfaceAmbient(vec3(0.0, 0.0, 1.0)) + ground);
}

// The board's relief and buildings hide part of the sky in canyons, streets and at the feet of walls
// (sky-visibility.glsl). What hides it is not black: seen from shade, the faces hiding the sky mostly turn away from the
// sun, so the hidden share takes ground lit by the sky alone (sunlit ground, about as bright as the sky, erased the
// occlusion). Along direction the sky is the share (1 + direction.z) / 2; the ground below keeps its own bounce. Never
// above open, the light there unoccluded: a dim lighting image or snow's bounce must not turn occlusion into a lift.
vec3 hiddenSky(vec3 direction, vec3 ground, vec3 groundAlbedo, vec3 open) {
    float upper = .5 + .5 * direction.z;
    return min((1.0 - upper) * ground + upper * groundAlbedo * surfaceAmbient(vec3(0.0, 0.0, 1.0)), open);
}

vec3 skyLight(vec3 normal, vec3 groundAlbedo) {
    // At roughness one the normalized GGX prefilter is the cosine convolution: irradiance divided by pi.
    vec3 ground = environmentGround(groundAlbedo);
    vec3 open = environmentRadiance(normal, 1.0, ground, surfaceAmbient(vec3(0.0, 0.0, 1.0)));
    return mix(hiddenSky(normal, ground, groundAlbedo, open), open, skyVisibility(v_cloudPosition, normal));
}

// The environment's reflection, as environmentSpecular: the relief hides the reflected sky as it hides the sky, seen
// along the reflection, and the hidden share takes the light of what hides it.
vec3 skyReflection(vec3 normal, vec3 view, float roughness, vec3 f0, vec3 groundAlbedo, vec3 ground,
      vec3 fallbackSky) {
    if (dot(normal, view) <= 0.0) return vec3(0.0);
    roughness = clamp(roughness, MIN_ROUGHNESS, 1.0);
    vec3 reflected = reflect(-view, normal);
    vec3 radiance = environmentRadiance(reflected, roughness, ground, fallbackSky);
    float visible = skyVisibility(v_cloudPosition, reflected);
    if (visible < 1.0) radiance = mix(hiddenSky(reflected, ground, groundAlbedo, radiance), radiance, visible);
    return radiance * specularAlbedo(dot(normal, view), roughness, f0);
}

float surfaceVisibility() {
#ifdef shadowMapFlag
    float offset = u_shadowPCFOffset;
    return shadowVisibility(0.25 * (shadowSample(vec2(offset, offset)) + shadowSample(vec2(-offset, offset))
          + shadowSample(vec2(offset, -offset)) + shadowSample(vec2(-offset, -offset))));
#else
    return 1.0;
#endif
}

#ifdef impostorFlag
// The crown radius of an impostor card's tree, in world units (tree-instances.glsl).
in float v_impostorLift;
#endif

// The sculpted terrain and its trees filter the shadow map more finely than the 4-tap surfaceVisibility().
// The sun's disc, about half a degree across: a penumbra is this share of the distance from what casts it.
const float SUN_DIAMETER = .0093;

// metre: world units per metre. lift: how far the visible surface stands out along normal from the mesh, its relief
// (terrain parallax): raised grains catch the light at a shadow's edge and hollows fall into it first. fray: a shift
// of the lookup across the light, in metres: the grass, clods and stones that fray a natural shadow's edge.
float sculptShadow(vec3 normal, vec3 light, float metre, float lift, vec2 fray) {
#ifdef shadowMapFlag
    // Normal offset: the lookup moves out along the surface normal by half a shadow texel, and by up to two where the
    // light skims the surface, so a flat face lit at a grazing angle never shadows itself.
    vec3 row = vec3(u_shadowMapProjViewTrans[0][0], u_shadowMapProjViewTrans[1][0], u_shadowMapProjViewTrans[2][0]);
    float texel = 4.0 * u_shadowPCFOffset / max(length(row), 1e-6);
    float skim = 1.0 - abs(dot(normal, light));
    vec3 receiver = v_cloudPosition + normal * (texel * (.5 + 1.5 * skim) + lift);
#ifdef impostorFlag
    // An impostor card stands in the middle of its crown, whose leaves face the light about a crown radius nearer:
    // looked up there, the card's own cards and crown cannot shade it, while other trees and buildings still do.
    receiver += light * v_impostorLift;
#endif
    vec4 lifted = u_shadowMapProjViewTrans * vec4(receiver, 1.0);
    vec3 shadowUv = lifted.xyz / lifted.w * .5 + .5;
    shadowUv.z = min(shadowUv.z, .998);
    // Retain the world-fixed 12-tap disk. Each hardware lookup filters comparisons, not encoded depth.
    // Its four neighboring texels share this tap's receiver depth; the outer disk retains plane correction.
    vec2 taps[12];
    taps[0] = vec2(-.326, -.406); taps[1] = vec2(-.840, -.074); taps[2] = vec2(-.696, .457);
    taps[3] = vec2(-.203, .621); taps[4] = vec2(.962, -.195); taps[5] = vec2(.473, -.480);
    taps[6] = vec2(.519, .767); taps[7] = vec2(.185, -.893); taps[8] = vec2(.507, .064);
    taps[9] = vec2(.896, .412); taps[10] = vec2(-.322, -.933); taps[11] = vec2(-.792, -.598);
    float angle = fract(sin(dot(floor(v_cloudPosition.xy * 3.0), vec2(12.9898, 78.233))) * 43758.5453) * 6.2832;
    mat2 rotation = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    // Receiver-plane depth: each tap compares against this surface's own depth at the tap, not at the pixel centre,
    // so walls and grazing ground do not shadow themselves across the filter disk.
    vec3 du = dFdx(shadowUv), dv = dFdy(shadowUv);
    float det = du.x * dv.y - du.y * dv.x;
    vec2 slope = abs(det) > 1e-14 ? vec2(dv.y * du.z - du.y * dv.z, du.x * dv.z - dv.x * du.z) / det : vec2(0.0);
    // After the receiver plane: the fray moves where the edge falls, along the surface as the taps do, never into it.
    vec2 frayUv = fray * metre * .5 * length(row);
    shadowUv += vec3(frayUv, clamp(dot(frayUv, slope), -.002, .002));
    // Contact hardening: a bank's rim casts a crisp edge and a tower a soft one. What casts the shadow is found by
    // comparisons alone, without a second sampler: four taps over the widest disk, and where any is shadowed, the same
    // taps again with the receiver 3 m and 15 m nearer the sun; a blocker still found there stands farther away.
    float widest = u_shadowPCFOffset * 6.0;
    float blocked = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = rotation * taps[i * 3] * widest;
        blocked += 1.0 - texture(u_shadowTexture,
              vec3(shadowUv.xy + offset, shadowUv.z + clamp(dot(offset, slope), -.002, .002)));
    }
    // Fully lit at the widest disk: no tap of a narrower one is shadowed either.
    if (blocked <= 0.0) return 1.0;
    float depthPerUnit = .5 * length(vec3(u_shadowMapProjViewTrans[0][2], u_shadowMapProjViewTrans[1][2],
          u_shadowMapProjViewTrans[2][2]));
    float beyondNear = 0.0, beyondFar = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = rotation * taps[i * 3] * widest;
        float depth = shadowUv.z + clamp(dot(offset, slope), -.002, .002);
        beyondNear += 1.0 - texture(u_shadowTexture, vec3(shadowUv.xy + offset, depth - 3.0 * metre * depthPerUnit));
        beyondFar += 1.0 - texture(u_shadowTexture, vec3(shadowUv.xy + offset, depth - 15.0 * metre * depthPerUnit));
    }
    float caster = (1.5 + 5.5 * beyondNear / blocked + 23.0 * beyondFar / blocked) * metre;
    // Half the penumbra, from half a texel (the map resolves no finer) to the widest disk.
    float radius = clamp(.5 * SUN_DIAMETER * caster / texel, .5, 3.0) * 2.0 * u_shadowPCFOffset;
    float lit = 0.0;
    for (int i = 0; i < 12; i++) {
        vec2 offset = rotation * taps[i] * radius;
        float receiver = shadowUv.z + clamp(dot(offset, slope), -.002, .002);
        lit += texture(u_shadowTexture, vec3(shadowUv.xy + offset, receiver));
    }
    return shadowVisibility(lit / 12.0);
#else
    return 1.0;
#endif
}

// Shared dielectric response: callers can reuse their shadow sample for mapped leaves as well as opaque surfaces.
// The water film's smooth coat widens too: every lobe, budget and environment call takes the antialiased roughness.
float dielectricRoughness(float film, float roughness) {
    return antialiasedRoughness(mix(clamp(roughness < 0.0 ? .85 : roughness, MIN_ROUGHNESS, 1.0), .06,
          clamp(film, 0.0, 1.0)), surfaceVariance);
}

vec3 dielectricF0(float film) { return vec3(mix(.04, .02037, clamp(film, 0.0, 1.0))); }

float dielectricSheen(vec3 normal, vec3 light, vec3 view, float film, float roughness) {
    return surfaceReflectance(normal, light, view, dielectricRoughness(film, roughness), dielectricF0(film)).r;
}

float dielectricDiffuse(vec3 normal, vec3 light, vec3 view, float film, float roughness) {
    return diffuseReflectance(normal, light, view, dielectricRoughness(film, roughness), dielectricF0(film)).r;
}

float dielectricBudget(vec3 normal, vec3 view, float film, float roughness) {
    return 1.0 - specularAlbedo(max(0.0, dot(normal, view)), dielectricRoughness(film, roughness), dielectricF0(film)).r;
}

vec3 surfaceEnvironment(vec3 normal, float film, float roughness, vec3 groundAlbedo) {
    return skyReflection(normal, -viewDirection(), dielectricRoughness(film, roughness), dielectricF0(film),
          groundAlbedo, environmentGround(groundAlbedo), surfaceAmbient(vec3(0.0, 0.0, 1.0)));
}

// Irradiance for scattering volumes and optical coats, before their own reflection/transmission split. Without local,
// it leaves out the local lights, for water that walks them once with their reflection (lavaLighting).
void incidentLighting(vec3 normal, bool local, out vec3 ambient, out vec3 direct) {
    ambient = skyLight(normal, GROUND_ALBEDO);
    if (local) ambient += lavaIrradiance(v_cloudPosition, normal);
    direct = vec3(0.0);
#if numDirectionalLights > 0
    for (int i = 0; i < numDirectionalLights; i++) {
        direct += u_dirLights[i].color * max(0.0, dot(normal, -u_dirLights[i].direction));
    }
#endif
    direct *= surfaceVisibility();
}

void incidentLighting(vec3 normal, out vec3 ambient, out vec3 direct) {
    incidentLighting(normal, true, ambient, direct);
}

void surfaceLighting(vec3 normal, vec3 face, float film, float roughness,
      out vec3 ambient, out vec3 direct, out vec3 sheen) {
    vec3 view = -viewDirection();
    ambient = (skyLight(normal, GROUND_ALBEDO) + lavaIrradiance(v_cloudPosition, normal))
          * dielectricBudget(normal, view, film, roughness);
    direct = vec3(0.0);
    sheen = vec3(0.0);
#if numDirectionalLights > 0
    for (int i = 0; i < numDirectionalLights; i++) {
        vec3 light = -u_dirLights[i].direction;
        if (dot(face, light) > 0.0) {
            direct += u_dirLights[i].color * dielectricDiffuse(normal, light, view, film, roughness);
            sheen += u_dirLights[i].color * dielectricSheen(normal, light, view, film, roughness);
        }
    }
#endif
#ifdef shadowMapFlag
    float visibility = surfaceVisibility();
    direct *= visibility;
    sheen *= visibility;
#endif
}

// A proxy canopy's card is not an opaque incident-light boundary. Its signed botanical cosine owns the lobes.
void surfaceLighting(vec3 normal, float film, float roughness, out vec3 ambient, out vec3 direct, out vec3 sheen) {
    surfaceLighting(normal, normal, film, roughness, ambient, direct, sheen);
}

// Materials without a roughness map use a matte dielectric, with the same water-film boundary.
void surfaceLighting(vec3 normal, float film, out vec3 ambient, out vec3 direct, out vec3 sheen) {
    surfaceLighting(normal, film, -1.0, ambient, direct, sheen);
}
#endif
