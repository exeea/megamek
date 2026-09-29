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
| Fixed sun/moon | Uses the camera basis for a stable screen direction; terrain, units, clouds and shafts must receive that same direction. |
| Air pressure, temperature, taint | Scenario mapping controls weather eligibility, rain wetness and the atmospheric palette. |
| Gravity | Changes newly started jump arcs; an airborne jump finishes its captured arc. Movement rules remain with the game. |
| Cloud cover and shadow strength | `GpuClouds` changes the direct-light shadow field and the atmosphere's sky veil. |
| Ground fog, layer height, variation and calm drift | `GpuAtmosphere` controls height-dependent density, banks and movement. |
| Rain, snow, hail, sand, lightning and wind | Select effect populations/intensity and motion; sand uses the atmosphere rather than a precipitation particle type. |
| Haze, god rays, glare | Control uniform extinction, depth-limited shafts and the lens-facing sun effect. |

Ground wetness follows liquid rain automatically. Freezing temperature and very
thin/airless conditions suppress it. `rain-surface.glsl` uses the same wetness for
ground and other receiving materials; puddle response also depends on the surface.

## Light and color contract

`BoardAtmosphere.lighting()` supplies linear, pre-exposed direct and ambient
colors. Day/night adaptation is already included there. Shared surface lighting
uses these values consistently; user EV compensation and lightning are applied
during composition. Decode material colors and encode the output once, rather
than applying an extra gamma or exposure correction inside each material.

Sun/moon handover uses one directional shadow source. Direct illumination and
shadow contrast fade with its visibility. Full Moon enables directional night
lighting; Moonless and Pitch Black use ambient light without a geometry shadow
pass. Fixed sun/moon must be resolved before both shadow and color draws.

Taint changes the sky/horizon, existing fog and the display grade while preserving
luminance. It does not create emission or weather. Pressure and time of day scale
the palette. Tactical labels and UI are drawn after composition and retain their
own colors. Palette constants and mapping belong in `BoardAtmosphere`, rather
than separate copies in each shader.

## Scene depth, fog and composition

The scene is captured into an RGBA8 color target with sampleable depth.
The fog pass reconstructs world positions from that depth and the camera.
It integrates the height-dependent layer at reduced resolution, then
depth-aware upsampling avoids leaking foreground fog into unrelated surfaces.

Fog and blowing sand share the ground-layer height, measured from the board's
lowest terrain level. Cloud altitude is separate. Spatial density/height
variation forms banks, and an integrated wind/calm-drift offset moves them
without a phase jump when wind changes. Ground fog's opacity is capped.

Sun shafts sample the cloud field and captured geometry depth along the light
direction. They reuse the scene depth rather than drawing the board into a
second camera-depth pass. The composite combines scene color, fog, shafts,
grading, exposure and glare, then restores scene depth for later draws.
Weather particles and tactical/UI passes must retain the intended depth/order.

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
