# Shader sources and live editing

Shader resources live in
[resources/.../boardview/gpu](../megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/).
Programs are assembled by their Java renderers; many GLSL files are helpers
inserted into a larger program, so editing a helper can affect several materials.

## Which source does what

| Source family | Purpose and Java owner |
| --- | --- |
| `terrain-normal.frag`, `terrain-sculpt.frag` | Ground materials; `GpuTerrain` assembles the programs and their material helpers. |
| `terrain-road.frag` | Road masks/markings; `GpuTerrain` with `GpuRoads`. |
| `terrain-projection.glsl`, `terrain-materials.glsl`, `terrain-concrete.glsl` | World projection, common surface sampling/blending and concrete finish. |
| `terrain-biome.glsl`, `terrain-biome-mask.glsl`, `terrain-meadow.glsl` | Ground response to fields, wetlands and meadow growth. |
| `terrain-foliage.frag`, `terrain-vegetation.frag` | Tree/plant surface shading; programs configured by `GpuTerrain`. |
| `terrain-grass.glsl`, `terrain-biome-vegetation.glsl`, `terrain-vegetation-wind.glsl` | Instanced blade/crop shapes and shared wind; `GpuGroundCover` and `GpuBiomeVegetation` inject the vertex helpers. |
| `light-model.glsl`, `surface-lighting.glsl`, `rain-surface.glsl` | Shared light response, shadow/specular terms and wet surfaces. |
| `water-surface.frag`, `water-fall.frag`, `water-spray.frag`, `water-cut.frag` | Separate water draw modes; assembled by `GpuTerrain`, with attributes/uniforms from `GpuWaterShader`. |
| `water-optics.glsl`, `water-lighting.glsl`, `water-interactions.glsl`, `water-pool.glsl` | Water color/transmission, illumination and local motion/contacts. |
| `terrain-ice.glsl` | Lake ice and discovered ground/road glaze; injected and bound by `GpuIceShader`. |
| `terrain-magma-solid.frag`, `terrain-magma-flow.frag` | Separate crust and flowing-lava programs; `GpuMagmaShader` injects `terrain-magma.glsl` and the corresponding solid/flow helpers. |
| `ocean-spectrum.frag`, `ocean-fft.frag`, `ocean-water-finish.frag`, `ocean-lava-finish.frag` | Wave spectrum, inverse FFT and mode-specific output; `GpuOcean`. |
| `cloud-shadow.glsl` | Direct-light cloud attenuation; `GpuCloudShadow` inserts it into receiving programs. |
| `atmosphere-fog.frag`, `atmosphere-composite.frag` | Fog/shafts and final scene composition; `GpuAtmosphere` assembles their helpers. |
| `weather-particles.vert`, `weather-particles.frag`, `weather-rain.glsl`, `weather-snow.glsl`, `weather-hail.glsl` | Shared precipitation programs with per-kind motion/shape; `GpuWeatherParticles`. |
| `effects.vert`, `particles.frag`, `particles-smoke.glsl`, `particles-fire.glsl`, `particles-jet.glsl` | Batched combat/terrain effects; `GpuEffectBatch` selects and assembles each appearance. |
| `terrain-effects.vert`, `terrain-effects.frag`, `terrain-effects-composite.frag` | Persistent terrain fire/smoke volumes and their reduced-resolution composite; `GpuTerrainEffects`. |
| `shadow-depth.frag` | Shadow depth/cutout behavior; `GpuTerrain` configures the provider and `GpuTreeInstances` supplies instanced transforms. |

Unit materials are configured by `GpuUnitShader`, including the linear-light
adaptation of libGDX's base shader, normal maps and per-unit material inputs.
Do not assume every program starts from a standalone vertex file: some extend
the libGDX source with injected attributes and functions.

## Loading, drafts and saving

`GpuShaderSource` resolves a source through runtime `data/shaders` overrides,
checkout resource directories, then bundled classpath resources. A session draft
takes precedence during a managed compile. If a resource edit has no effect, check
the resolved source and any runtime override before changing unrelated code.

`GpuShaderManager` tracks source dependencies while programs are assembled.
It prepares affected replacements on the GL thread before committing a reload,
so a compile failure can retain the working programs. Standalone programs register
their replacement callback; material programs go through `GpuShaderProvider`,
whose stable handles keep existing materials connected to their replacements.

`GpuShaderEditor` is opened through Tuning → Edit shaders. Live preview compiles
after typing pauses; Apply/Ctrl+Enter compiles immediately. Save all/Ctrl+S applies
and writes the edited files; Revert file restores the saved text.
Saving checks for external
file changes rather than silently overwriting them. A live draft and a durable
source edit are distinct: use the displayed resolved path to know which file a
save will change.

## Uniforms, inputs and previews

`GpuShaderUniforms`, `GpuShaderInputs` and `GpuShaderValue` record typed inputs
from actual programs. `GpuShaderInputPanel` presents them; this list is not a
separate hand-maintained registry of every possible GLSL variable.
Rows distinguish the renderer's supplied value, the effective value and a local
override. Clearing an override resumes the current supplied value.
Inputs used before shading cannot be discovered as GLSL uniforms; supported
samples expose those under Preview setup (CPU inputs).
`GpuShaderPreview` and `GpuShaderPreviewPanel` render previews using the same
source-management path. `GpuGlsl` handles compilation/prefix compatibility;
`GpuGlslTokenMaker` supplies editor syntax highlighting.

For a new material input, update its Java attribute/setter, the assembled source
and the pass that binds any required texture. A declared uniform alone does not
supply its value. For a helper change, inspect all consumers recorded through
`GpuShaderSource.read`, not only the material visible in the editor.

## Reloading assets and exporting programs

Tuning → Reload assets rereads the running data directory's textures, models,
descriptors, tilesets and shaders. Edit the actual runtime copy for a quick local
preview, then keep durable asset changes in `mm-data` so staging can reproduce
them. Live shader drafts remain separate from an asset-file reload.

The JVM property `-Dmegamek.gpu.shaderExport=<directory>` writes assembled program
sources for external GLSL tools. Use these when inspecting the complete
libGDX prefix and injected helpers; resource snippets alone are not always a
standalone compilable shader.

## Boundaries to preserve

Material programs share lighting, world projection and contact fields. A surface
blend needs matching color, normal, height/roughness and occlusion weights.
Wetness must affect the intended receiving materials, and emission belongs after
ordinary lighting. Changes to alpha/cutouts may also require the shadow/depth path.

A shader edit normally replaces programs and invalidates affected render caches;
it does not change game state, terrain support or picking. Change Java geometry
when the intended result alters silhouettes or contact surfaces. Programs,
textures and previews must be installed and disposed by their GL-thread owner.
