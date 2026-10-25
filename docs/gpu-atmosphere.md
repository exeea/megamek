# Lighting, atmosphere and weather

`BoardAtmosphere` turns scenario conditions into a visual snapshot.
`GpuAtmosphere` applies that snapshot to the rendered scene. The GPU window can
preview different conditions without changing gameplay visibility, orders or
the scenario's planetary conditions.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardAtmosphere` | Scenario mapping, sampled time, sun/moon direction, colors, pressure and lighting values. |
| `AtmospherePreset`, `GpuAtmosphereControls` | Visual presets and the Tuning → Atmosphere controls. |
| `GpuBattleView` | Applies current lighting/camera state and orders scene, atmosphere and overlay passes. |
| `GpuAtmosphere` | Scene framebuffer/depth, fog, sun shafts, exposure, grading and composition. |
| `GpuClouds`, `GpuCloudShadow` | Animated cloud shadow field and bindings used by lit surfaces. |
| `GpuWeatherParticles` | Bounded rain, snow and hail populations and their shader programs. |
| `GpuTerrain` | Shared wetness/noise inputs and static terrain shadow resources. |

For cloud density, projection and shadow sampling, use [cloud shadows](gpu-clouds.md).
Shader file ownership is listed in [shaders](gpu-shaders.md).

## Scenario values and visual overrides

Opening a window initializes its preview from the game's effective conditions.
Each scenario-derived field continues following game updates until the user
overrides that visual control. Applying a complete preset or the conditions
dialog replaces the visual selection. **Defaults** restores the current game
conditions plus the geometry and additional effect defaults.

Presets create private `PlanetaryConditions` values and call
`BoardAtmosphere.fromScenario`, sharing the game's mapping. The conditions dialog
retains the selected conditions; arbitrary slider mixtures do not create a second
weather classification. Apply resets overrides even when the conditions are
unchanged; Cancel preserves the preview.

Lighting categories supply a time window rather than an exact clock.
The window samples a time once and reuses that choice through refreshes and
category changes. Manual time overrides persist through routine updates.
The preview is window-local and is not persisted across reopening. Time and
weather classifications do not advance automatically; their clouds, particles,
fog banks and lightning still animate.

## What the controls affect

| Controls | Effect and code to inspect |
| --- | --- |
| Time, moonlight, exposure, moon shadow contrast | `BoardAtmosphere.lighting()` determines the light; the composite applies user exposure. |
| Shadows | Geometry shadow strength, 0–100%, independent of moon contrast and cloud shadows; default 100%. |
| Ambient light | Ambient/direct balance, default 20% (the existing clear-noon 1:4 balance); 0 selects direct only, 100 ambient only. |
| Sky contribution | Scales environment diffuse illumination and reflections, 0–200%, default 100%; leaves the visible sky unchanged. |
| Lighting environment | Optional 2:1 panoramic PNG/JPEG or Radiance HDR, up to 8192×4096. Used only for lighting/reflections; clearing it restores generated sky lighting. |
| Fixed sun/moon | Uses the camera basis for a stable screen direction; terrain, units, clouds and shafts must receive that same direction. |
| Air pressure, temperature, taint | Scenario mapping controls weather eligibility, rain wetness and the atmospheric palette. |
| Gravity | Changes newly started jump arcs; an airborne jump finishes its captured arc. Movement rules remain with the game. |
| Cloud cover and shadow strength | `GpuClouds` changes the direct-light shadow field and the atmosphere's sky veil. |
| Ground fog, layer height, variation and calm drift | `GpuAtmosphere` controls height-dependent density, banks and movement. |
| Rain, snow, hail, sand, lightning and wind | Select effect populations/intensity and motion; sand uses the atmosphere rather than a precipitation particle type. |
| Haze, god rays, glare | Control uniform extinction, depth-limited shafts and the lens-facing sun effect. |

Lighting controls are window-local and reset with Defaults or an atmosphere preset.
Manual lighting changes keep the scenario's exposure adaptation, so it cannot
immediately cancel the chosen strength. The optional panorama is decoded and GGX
filtered on a background CPU task; a failed load keeps the previous lighting.
PNG/JPEG RGB is decoded from sRGB once; HDR keeps linear radiance above one.
Six 128×64 roughness levels share the existing standalone RGBA16F environment atlas,
adding no material sampler or per-frame filtering pass. The directional sun remains
separate. Imported panoramas replace the generated environment only when selected.

Generated lighting uses a 128×64 linear panorama based on the current light direction,
time, pressure, haze/clouds and taint type/strength. It is independent of the illustrated
background. Clear-air diffuse illumination retains the calibrated lighting budget;
taint changes its chroma and directional distribution through an approximate optical
medium. Moonless readability uses the same medium without a directional source,
relative to its uniform clear-air fill; its integrated luminance stays unchanged.
Vacuum readability remains explicit uniform gameplay fill. The panorama
contains no sun/moon disk, and receiving materials retain their local ground bounce.
Generation and compact filtering run synchronously when relevant settings change;
camera movement, exposure and fog-only changes reuse the result. This is a calibrated
approximation, not measured atmospheric radiometry or a per-frame sky simulation.

The normal visible background retains the UI palette. Tainted/toxic horizon colors
remain visible at night, sunrise and sunset without increasing that palette's luminance.
**Show lighting sky** displays the environment used for lighting instead. The toggle
does not change the board's illumination. The old whole-scene taint and automatic night
desaturation are removed; light colors and the user's explicit display controls remain.

Ground wetness follows liquid rain automatically. Freezing temperature and very
thin/airless conditions suppress it. `rain-surface.glsl` uses the same wetness for
ground and other receiving materials; puddle response also depends on the surface.

At zero gravity, `BoardAtmosphere.Settings` forces visual pressure to VACUUM.
The existing vacuum limits remove clouds, fog, haze, precipitation, sand, lightning,
wind and rain wetness for both scenario settings and local previews. Tuning shows
VACUUM and disables incompatible weather controls; pressure is locked while gravity is zero.

## Light and color contract

`BoardAtmosphere.lighting()` supplies canonical linear direct and ambient
colors. Its adaptation meters ambient plus direct light weighted by
`max(0.5, -direction.z)`, so grazing light on slopes contributes alongside level
ground. The meter includes vacuum readability and the daylight white point,
before camera-relative light placement or manual light controls. Composition applies
that one gain with user EV compensation to the complete scene, including emission,
local spill and selected heat. Scene material evaluation, interpolation and blending stay
linear. Exact piecewise sRGB conversion is shared by `GpuColor` and
`color-space.glsl`; the final composite encodes display RGB once.

Color-only images use `GL_SRGB8_ALPHA8`: hardware decodes RGB before filtering,
while alpha remains numerical. Repeating materials use separate compact arrays:
`GL_SRGB8_ALPHA8` for color/height and `GL_RGBA8` for numerical maps. The separate
authored sculpt roughness array uses `GL_R8`. Required CPU resizing uses `GpuLinearImage` to filter
color RGB in linear light, then re-encodes it for sRGB storage. Numerical channels
remain linear throughout preparation. Normal, roughness, metallic,
height, mask and coordinate channels never pass through a color transfer curve.
An image used for both color and data has a separate cached texture for each role.
File decoding also resolves grayscale channel layout before upload: libGDX's
one-channel `Alpha` result means opaque grayscale for PNG/JPEG files, whereas
caller-created alpha Pixmaps still mean coverage. Numerical grayscale maps use
ordinary RGBA storage and retain their samples without an sRGB transfer.
Imported glTF factors and vertex colors are already linear; `GpuLinearColors`
marks that contract. Procedural display-authored vertex colors decode before
interpolation. Packed vertex metadata is explicitly excluded.

The illustrated background retains UI-authored color interpolation in
`BoardAtmosphere.skyPalette`. Its display mapping is confined to the background;
it is decoded for linear scene composition and encoded with the final frame.
Both camera modes use the screen-height gradient around the finite board.
Time and clouds change its endpoints; taint keeps its lower band at night and
twilight while preserving endpoint luminance. These colors do not illuminate
materials. The native compositor checks the authored palette in both cameras.

The generated incident environment instead uses world directions and linear
radiance. Its upper hemisphere supplies diffuse and roughness-filtered specular
lighting. Its lower hemisphere is zero; the receiving material contributes the
existing local ground reflection once. Imported panoramas retain their own
lower hemisphere. Vacuum has no atmospheric scattering, but retains the explicit
readability allowance.

The clear-noon sun plus sky is calibrated to a neutral unit white on level
ground. A single RGB gain, derived from that reference, transforms direct light,
diffuse fill together. Its strength follows daylight;
full night keeps its existing palette. This corrects the overly blue illumination
introduced when the visible gradient began lighting materials, including terrain
at level zero. Cloud and pressure changes do not renormalize the sun. The generated
sky receives this white-point-adjusted diffuse budget: clear air retains its RGB,
and optical taint changes chroma while preserving its luminance. No terrain saturation multiplier,
extra color-space conversion or framebuffer format change is involved.

Direct reflection uses correlated Smith GGX and Schlick Fresnel. Directional
colors are irradiance divided by pi, so the specular helper includes the matching
pi factor. The matte lobe uses reciprocal directional-albedo energy coupling.
`GpuEnvironmentLighting` loads a precomputed 256×129 GGX sky/ground basis and
split-sum BRDF table; its right half contains the finite directional source's
energy correction. This table occupies the top-left of one standalone 512×512
RGBA16F atlas. `GpuLightingEnvironment` places six filtered 128×64 environment
tiles with seam/pole gutters below it, starting at row 130. Both share
`u_environmentLut` and are sampled at mip zero; neither occupies a material-array layer.
The directional sun/moon is excluded from the environment, avoiding double light.
Specular anti-aliasing (`light-model.glsl`, Display tab, on by default) adds the pixel's normal variance to GGX
alpha², at most .18: once per pixel, from the screen derivatives of the final shading normal (sculpted ground and
models include their mapped detail; foliage, roads and water use their base normal before any discard), plus
water's footprint term. It is
compiled in (`specularAntialiasingFlag`); off, its helpers are constants. An environment with the board's lighting
carries `GpuEnvironmentLighting.Antialiasing` while it is on, so a switch is a lighting change: terrain materials
and family programs rebuild behind the loading stage, while water, plant, prop and unit programs compile at their
first draw, from the program cache after the first time. Movement and editor ghosts, lit without the board's
environment lighting, never compile it.
See [Filament's image-based lighting](https://google.github.io/filament/Filament.html#lighting/imagebasedlights)
and the [energy-coupled matte model](https://diglib.eg.org/bitstream/handle/10.2312/egs20011003/short16.pdf).

Sun/moon handover uses one directional shadow source. Direct illumination and
shadow contrast fade with its visibility. Full Moon enables directional night
lighting; Moonless and Pitch Black use ambient light without a geometry shadow
pass. Fixed sun/moon must be resolved before both shadow and color draws.
Free flight ignores Fixed sun/moon and keeps the world-space light. Ordinary
orbit poses retain the optional screen bearing. The generated environment stays
world-oriented, as imported panoramas do; fixing the direct light is a presentation
override, not a rotation of the environment.

Taint changes the incident environment, illustrated horizon and existing fog.
It does not add a whole-scene display tint, emission or weather. Pressure controls
scattering; time changes illumination and background brightness. Tactical labels
and UI retain their own contrast controls. Palette mapping belongs in
`BoardAtmosphere`; optical-medium coefficients belong in `BoardLightingSky`.

## Scene depth, fog and composition

GPU Tuning → Atmosphere → Lighting places four local color controls beside
Exposure: **White balance** (-1 cool to +1 warm), **Tint** (-1 green to +1
magenta), **Saturation** (0 grayscale, 1 unchanged, 2 stronger color), and
**Contrast** (0.5–1.5, with 1 unchanged). White balance and tint default to zero.
Defaults and atmosphere presets restore these neutral values; the controls are
local to the GPU window, like the other atmosphere rendering options.

The existing final composite applies white balance and saturation in linear
light to the complete scene, including sky, weather and emissive effects.
White balance uses relative RGB gains normalized to preserve neutral-gray
luminance; it is a cool/warm adjustment, not a calibrated Kelvin setting.
Contrast acts around display mid-gray after the highlight shoulder and sRGB
conversion, before the existing vignette. Tactical overlays and HUD colors stay
unchanged. These controls add arithmetic and two uniforms, with no additional
render pass, texture lookup, framebuffer, or terrain/shadow rebuild. The balance
gains are calculated on the CPU only when white balance or tint changes.

Verified on 2026-10-13 with `GpuColorGradingSmokeTest` and the native tuning and
atmosphere smoke tests: neutral output, both white-balance/tint directions,
saturation including grayscale sky and HDR colors, contrast, transparent
coverage, framebuffer/light reuse, preset/default reset, and the displayed
slider readings. The optional `compositeColorAdjustmentTimings` benchmark takes
a saved earlier `atmosphere-grade.glsl` via `megamek.gpu.colorBaseline`.
On an NVIDIA GeForce RTX 4070 Laptop GPU, 160 samples per variant after 64 warmup
samples (eight draws per sample, alternating variant order, GPU timestamps)
gave the following medians per composite draw:

| Resolution | Original shader | Neutral controls | Adjusted controls |
| --- | ---: | ---: | ---: |
| 1920×1080 | 0.076 ms | 0.076 ms | 0.076 ms |
| 3840×2160 | 0.302 ms | 0.302 ms | 0.302 ms |

This isolated clear-scene composite comparison found no measurable increase;
these are pass timings, not whole-game frame times or a guarantee for other GPUs.

Every main 3D scene is captured into an RGBA16F color target with sampleable depth,
including boards without lava. Values above diffuse white survive until the
composite applies exposure and the shared highlight shoulder. Adding or removing
molten terrain enables or disables the heat halo without replacing scene color
or depth. Top and isometric cameras use this same path.

Material outputs, alpha blending and additive scene effects now remain linear in
the RGBA16F target. Fog, heat extraction, grading and exposure consume
that radiance directly. Water/ice combine linear reflection, transmission and
scattering. The reduced-resolution terrain fire/smoke target also uses RGBA16F
to preserve bright emission. Weather draws into a separate, lazily allocated
RGBA16F layer sharing the opaque scene depth. The composite blends it over
fogged terrain in linear space before the common grade/display conversion;
foreground drops no longer inherit the distant terrain's fog column. Weather
itself retains the existing approximation without per-particle volumetric fog.
Tactical overlays and UI retain display output. Shader samples,
model previews and the editor's section widget
use a linear target and the shared final display conversion before presentation.
Ordinary scene transparency accumulates alpha as coverage, including particles
and faded models; additive light preserves that coverage so the uncovered sky
gradient remains visible behind the effect.

`GpuLightingConsistencySmokeTest` checks raw HDR radiance, material alpha blending,
and the sky gradient's endpoints and midpoint against independent linear-light
references. `GpuParticleShaderSmokeTest` checks overlapping smoke coverage and
additive emission above one in the floating-point target.

RGBA16F is appropriate for bounded HDR, but its maximum finite value is 65,504.
The original point-source model produced a supported larger value: at clear 06:30, an open ice lead with
roughness .045 and F0 .02037 reflects the sun toward an orbit camera tilted
78.39 degrees (within its 80-degree limit). The GGX response times the adapted
sun gives approximately (180380, 137941, 73770), before exposure. These are
reflection values, not invalid input or artificially increased emission. The
sun/moon now have a .266-degree angular radius. A normalized anisotropic GGX
lobe approximates convolution with that source; a preintegrated correction
retains the material's directional energy budget. This is an approximation to
a circular source, not exact disk quadrature. Independent numerical checks
measure its peak and off-peak errors.

Scene radiance and heat extraction/blur retain RGBA16F. Scene writers store RGB
in fixed units of one thirty-second of canonical radiance, extending finite storage
to 2,096,128 canonical units. `GpuSceneRadiance` owns the power-of-two constant;
`scene-radiance.glsl` supplies the matching shader operations. Material, BRDF,
environment and palette calculations remain canonical linear values. There is
no intermediate gamma encoding or tone mapping. RGB blending, the fire/smoke
upsample and heat blur operate directly on those same stored units; alpha is
never scaled. The display composite restores each scene/fog/weather/heat input
once, then applies the common exposure and display boundary. Heat extraction
restores only the radiance used for its threshold and keeps its output scaled.
Previews follow the same contract; tactical overlays retain display units.
Below canonical radiance .001953125, half-float storage is subnormal and its
step is about 1.91e-6. Relative precision is therefore not unchanged at the
darkest values. OpenGL permits conversion to either adjacent representable value
([OpenGL 4.6, section 17.3.8](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf)).
Adaptation is capped at `112^.6` (about 16.9641); together with +2 EV its maximum
gain is about 67.8564. This bounded readability policy is not a calibrated camera
or measured starlight model. A full subnormal step then changes the neutral sRGB
output by at most .4265 of an 8-bit display level. The current Iris Xe probe
preserves exact positive subnormals and selects the lower adjacent half for
intermediate inputs. Both Iris Xe and NVIDIA regressions check all 26 inputs from
zero through .004 at noon and maximum-gain moonless vacuum, including +2 EV:
storage selects an adjacent half and display output remains within one byte of
the independent input-radiance reference. Neither check requires nearest rounding.

The configured-light sweep includes every atmosphere/taint, five-minute time
steps, moon modes and camera pitches, together with a dense view/roughness grid.
For those sampled states, incident direction and every Fresnel F0 are bounded
analytically, including the normalization clamp/floor transitions. Independently
rounding each lookup coefficient toward the more conservative adjacent half
gives 49,098.43 stored units with the current canonical sources, below 65,504.
This bounds the specified isolated directional contribution over sampled states,
not continuous time, every material input or arbitrary overlapping emitters.
The native bright-light regression also exercises 1.5 million
canonical units through blending and heat extraction without clipping or
altering the source scene. These checks bound the supported renderer inputs;
they are not permission for arbitrary unbounded user emission values.

R11G11B10F lacks the alpha required by this compositor. Fog, weather, terrain
fire/smoke and the standalone environment atlas also use RGBA16F. The atlas
stores canonical lighting radiance and BRDF coefficients, not scaled scene
radiance. Material arrays retain compact sRGB or raw numerical storage.
See [Unity's HDR precision tradeoff](https://docs.unity3d.com/Packages/com.unity.render-pipelines.universal@14.0/api/UnityEngine.Rendering.Universal.HDRColorBufferPrecision.html).

The scene uses eight bytes per pixel, and the two quarter-size
heat targets use the same format. Each color or numerical RGBA material-array
texel uses four bytes; scalar roughness uses one. The standalone environment
atlas uses eight bytes per texel and has no mip chain; its roughness levels are
explicit tiles. Fog and terrain fire/smoke each use eight bytes per reduced-resolution
pixel, four more than their former RGBA8 targets. Active weather adds one eight-byte-per-pixel color layer, with no extra
depth allocation; isolated previews own a scene and display target. No performance gain or
cross-platform result is implied by the format choice.

The environment is a generated or imported distant-light approximation, not nearby-object
reflection probes or global illumination. GGX uses single scattering (rough
metals can lose energy); split-sum reflection assumes N=V=R during prefiltering.
Diffuse environment coupling is exact for a constant environment and approximates
the directional matte kernel under a varying sky. Local lava/fungus light remains
a diffuse illumination approximation. Terrain roughness is calibrated by material
family but remains an artistic estimate rather than measured scan data.

The fog pass reconstructs world positions from that depth and the camera.
It integrates the height-dependent layer at reduced resolution, then
depth-aware upsampling avoids leaking foreground fog into unrelated surfaces.

Fog and blowing sand share the ground-layer height, measured from the board's
lowest terrain level. Cloud altitude is separate. Spatial density/height
variation forms banks, and an integrated wind/calm-drift offset moves them
without a phase jump when wind changes. Ground fog's opacity is capped.

The board's display plinth stays clear of fog and sand. `groundBaseSide` in
`ground-layer.glsl` treats a surface as plinth when its derivative normal is
steep and it lies outside the hex footprint, or deeper below the lowest level
than any sculpted hollow (`BoardRelief.headroom`). Grass blades and silhouette
edges also give steep derivative normals. With a smaller allowance, blades in
hollows below the lowest level lost their fog and flickered as dark specks in
wind (`GpuGrassFogSmokeTest`).

Sun shafts sample the cloud field and captured geometry depth along the light
direction. They reuse the scene depth rather than drawing the board into a
second camera-depth pass. The composite combines scene color, fog, shafts,
grading, exposure and glare, then restores scene depth for later draws.
Weather particles and tactical/UI passes must retain the intended depth/order.

**Depth cues** (Tuning → Display, off until switched on, saved) separate nearer terrain from farther terrain in
clear air, in either projection; the Tactical View keeps clear air. `GpuDepthCues` measures the focus, the mean camera
distance of the surfaces in the middle two fifths of the view less the orbit pivot's, in a one-texel pass over the
scene depth, eased over 0.3 s. Before fog, the composite hazes what lies behind the focus towards the fog colour,
reaching `GpuDepthCues.STRENGTH` over half the depth the view spans (its height times the tangent of the tilt); the
focus and all in front of it keep their colour. Each pixel depends only on its own depth, so nothing slides across
the terrain as the camera moves; panning across a cliff edge refocuses the haze over that 0.3 s.

The strength was chosen from `GpuDepthCuesSmokeTest` captures of Crystalline Canyon on NVIDIA; the pass is not timed
on Iris Xe. A tiled print measures a focus per tile. The program links during loading when the option is on,
otherwise at its first frame.

Relevant helpers are `camera-depth.glsl`, `ground-layer.glsl`,
`scattering-phase.glsl`, `sun-visibility.glsl`, `weather-sand.glsl`,
`atmosphere-fov.glsl`, `atmosphere-grade.glsl` and `atmosphere-glare.glsl`.
`GpuAtmosphere` assembles them into `atmosphere-fog.frag` and
`atmosphere-composite.frag`.

## Weather and lifetime

`GpuWeatherParticles` selects `weather-rain.glsl`, `weather-snow.glsl` or
`weather-hail.glsl` inside the shared particle vertex/fragment programs.
Its bounded pools and projected coverage belong to the view; they are not
per-hex weather simulations. The atmosphere handles sand extinction and the
composite handles lightning brightness.

Atmosphere targets resize with the physical framebuffer and are disposed with
their owner. Cloud motion, exposure and weather intensity update their existing
resources; they should not rebuild terrain geometry. Geometry shadow invalidation
is separate from an animated cloud atlas.
