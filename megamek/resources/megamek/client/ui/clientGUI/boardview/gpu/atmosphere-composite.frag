#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
#extension GL_EXT_frag_depth : require
precision highp float;
#define DEPTH gl_FragDepthEXT
#else
#define DEPTH gl_FragDepth
#endif
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_depth;
uniform sampler2D u_waterDepth;
uniform sampler2D u_fog;
uniform sampler2D u_heatGlow;
uniform sampler2D u_weather;
uniform float u_weatherEnabled;
uniform float u_heatEnabled;
uniform float u_heatGlowStrength;
uniform float u_fogEnabled;
uniform vec4 u_scatteringBounds;
uniform vec2 u_fogSize;
uniform float u_edgeScale;
uniform vec3 u_sky;
uniform vec3 u_horizon;
uniform float u_transparentBackground;
uniform float u_sceneScaled; // 1 at a render resolution below 100%: scale the scene up; 0 reads it 1:1.
// SCENE_RADIANCE
// GROUND_LAYER
// WEATHER_SAND

// ATMOSPHERE_FOV

// ATMOSPHERE_GRADE

// HEAT_RADIANCE

// The lighting image's panorama for "Show lighting sky" (environment-lighting.glsl), which needs the light model's two
// constants; the whole light model would bring the colour-space functions a second time.
#define environmentLightingFlag
const float PI = 3.141592653589793;
const float MIN_ROUGHNESS = .045;
// ENVIRONMENT_LIGHTING

float depthAt(vec2 uv) {
    return texture(u_depth, uv).r;
}

// Fog ends at the first visible surface, including water. The opaque bed depth remains available for tactical depth.
float atmosphereDepthAt(vec2 uv) {
    return min(depthAt(uv), texture(u_waterDepth, uv).r);
}

// A linear scene-sized target at this display pixel: bilinear when the render resolution scales the scene up, which
// the targets' own nearest filtering would not do. Depth stays the nearest texel.
vec4 sceneSample(sampler2D source, vec2 uv) {
    if (u_sceneScaled < 0.5) return texture(source, uv);
    ivec2 last = textureSize(source, 0) - 1;
    vec2 texel = uv * vec2(last + 1) - 0.5;
    ivec2 base = ivec2(floor(texel));
    vec2 f = texel - floor(texel);
    vec4 below = mix(texelFetch(source, clamp(base, ivec2(0), last), 0),
          texelFetch(source, clamp(base + ivec2(1, 0), ivec2(0), last), 0), f.x);
    vec4 above = mix(texelFetch(source, clamp(base + ivec2(0, 1), ivec2(0), last), 0),
          texelFetch(source, clamp(base + ivec2(1, 1), ivec2(0), last), 0), f.x);
    return mix(below, above, f.y);
}

// ATMOSPHERE_GLARE

void main() {
    float depth = depthAt(v_uv);
    float surfaceDepth = u_fogEnabled > 0.5 || u_sand.x > 0.0 ? atmosphereDepthAt(v_uv) : depth;
    vec4 scene = sceneSample(u_scene, v_uv);
    scene.rgb = loadScene(scene.rgb);
    vec4 atmosphere = vec4(0, 0, 0, 1);
    bool solidBase = false;
    if (u_fogEnabled > 0.5) solidBase = groundBaseSide(world(surfaceDepth), surfaceDepth, u_sand.z);
    if (u_fogEnabled > 0.5 && !solidBase && v_uv.x >= u_scatteringBounds.x && v_uv.y >= u_scatteringBounds.y
          && v_uv.x <= u_scatteringBounds.z && v_uv.y <= u_scatteringBounds.w) {
        // Bilateral upsampling keeps low-resolution fog from bleeding across roofs and unit silhouettes.
        vec2 pixel = v_uv * u_fogSize - 0.5;
        vec2 base = floor(pixel);
        vec2 f = fract(pixel);
        atmosphere = vec4(0.0);
        float weights = 0.0;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                vec2 offset = vec2(float(x), float(y));
                vec2 uv = (base + offset + 0.5) / u_fogSize;
                vec2 bilinear = mix(1.0 - f, f, offset);
                float difference = abs(cameraDepth(surfaceDepth) - cameraDepth(atmosphereDepthAt(uv)));
                float weight = bilinear.x * bilinear.y / (1.0 + difference * difference / (u_edgeScale * u_edgeScale));
                atmosphere += texture(u_fog, uv) * weight;
                weights += weight;
            }
        }
        atmosphere = weights > 0.00001 ? atmosphere / weights : texture(u_fog, v_uv);
    }
    atmosphere.rgb = loadScene(atmosphere.rgb);
    // Restore storage units once; scene clear and sky/horizon uniforms now share canonical linear radiance.
    // Replace the uncovered clear with the gradient;
    // treating any nonzero alpha as opaque leaves a hard sky-colored border around dissipating volumes.
    float coverage = depth < 1.0 ? 1.0 : clamp(scene.a, 0.0, 1.0);
    // Parallel board cameras look down: all their rays select the same below-horizon direction. Keep the
    // finite board's horizon-to-zenith backdrop in screen space there. Free flight uses the actual sky direction.
    // Both blend the shared linear sky radiance; the display conversion remains at the end of this pass.
    float skyBlend = smoothstep(0.0, 1.0, v_uv.y);
    vec3 skyDirection = normalize(world(1.0) - world(0.0));
    if (u_projectionDepth.w < .5) {
        skyBlend = sqrt(max(0.0, skyDirection.z));
    } else {
        // A parallel view's backdrop direction for a lighting image: the height its blend stands for, the azimuth
        // the camera faces.
        float height = skyBlend * skyBlend;
        vec2 facing = dot(skyDirection.xy, skyDirection.xy) > 1e-8 ? normalize(skyDirection.xy) : vec2(0.0, 1.0);
        skyDirection = vec3(facing * sqrt(1.0 - height * height), height);
    }
    vec3 sky = u_environmentImage.x > .5 ? imageEnvironment(skyDirection, 0.0) : mix(u_horizon, u_sky, skyBlend);
    vec3 color = scene.rgb + (sky - u_sky) * (1.0 - coverage);
    // Exports store straight alpha: remove the clear sky from partially covered silhouette pixels before grading.
    if (u_transparentBackground > 0.5) color = (scene.rgb - u_sky * (1.0 - coverage)) / max(coverage, 0.00001);
    vec3 linear = max(color, vec3(0.0));
    vec3 heat = vec3(0.0);
    if (u_heatEnabled > 0.5) {
        float hot = heatWeight(linear);
        heat = linear * hot;
        // Only selected heat bypasses night desaturation. Ordinary reflected highlights retain their range too.
        linear *= 1.0 - hot;
    }
    vec4 sand = sandLayer(surfaceDepth);
    linear = (linear * sand.a + sand.rgb) * atmosphere.a + atmosphere.rgb;
    // Weather keeps its opaque-depth test, but foreground flakes/drops do not inherit the background's fog column.
    // Its separate target stores premultiplied linear RGB; all color still reaches the same grade/display boundary.
    if (u_weatherEnabled > 0.5) {
        vec4 weather = sceneSample(u_weather, v_uv);
        weather.rgb = loadScene(weather.rgb);
        float behind = 1.0 - weather.a;
        if (u_transparentBackground > 0.5) {
            float backgroundCoverage = coverage * behind;
            coverage = backgroundCoverage + weather.a;
            linear = (linear * backgroundCoverage + weather.rgb) / max(coverage, .00001);
            heat *= backgroundCoverage / max(coverage, .00001);
        } else {
            linear = linear * behind + weather.rgb;
            coverage = coverage * behind + weather.a;
            heat *= behind;
        }
    }
    linear = gradeScene(linear, coverage);
    linear += heat * sand.a * atmosphere.a * u_exposure;
    if (u_heatGlowStrength > 0.0) {
        // Fog already attenuated the visible source before blur. Sand also dims the camera halo.
        linear += loadScene(texture(u_heatGlow, v_uv).rgb) * (u_heatGlowStrength * sand.a * u_exposure);
    }
    // Lens glare borrows the active sun's color, after terrain grading and before display conversion/FoV.
    linear += sunGlare() * u_exposure;
    color = displayScene(linear);
    // Board camera presentation; isolated material previews share only the color conversion.
    vec2 edge = (v_uv - 0.5) * 2.0;
    color *= 1.0 - 0.09 * dot(edge, edge) * 0.5;
    if (u_fovEnabled > 0.5) color = fieldOfView(color, depth);
    fragColor = vec4(color, mix(1.0, coverage, u_transparentBackground));
    // Reuse the scene's hardware depth for tactical occlusion in this same draw.
    DEPTH = depth;
}
