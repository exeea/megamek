# Magma crust and lava

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardLiquid` | Classify crust versus molten lava from captured terrain; preserve the game's level/depth. |
| `BoardSurface / BoardRelief` | Construct beds, banks and shared contacts. |
| `GpuMagmaShader` | Select solid or flowing terrain material programs and their textures/uniforms. |
| `GpuOcean` | Generate the separate lava wave field using the shared wave implementation. |
| `magma-solid.glsl / magma-flow.glsl / magma-lighting.glsl` | Own crust relief, molten advection and heat/lighting; terrain boundaries reuse these evaluations. |

MAGMA level 1 is solid basalt crust with incandescent fissures. Level 2 is opaque
flowing lava, including falling sheets and exposed board-edge cuts. The captured
`BoardLiquid` distinguishes the states; `present()` is false for crust. Neither
material supplies movement rules, water depth, support heights or new picking
geometry. Existing terrain edits rebuild the affected surfaces. Crust, lava banks
and cliffs use the shared sculpted terrain. Connected lava uses the same graded
surface as water for one/two-level descents, and falling sheets from three levels.
Volcanic banks and crust join ordinary ground through the shared
[terrain contact field](gpu-terrain-contacts.md). Marsh plants stop at volcanic ground.

`GpuMagmaShader` selects `terrain-magma-solid.frag` for crust/banks and
`terrain-magma-flow.frag` for pools/falls in the existing terrain pass. Edit solid
relief and fissures in `magma-solid.glsl`, and advection, convection and molten heat
in `magma-flow.glsl`. Both use `terrain-magma.glsl` for sampling/projection and
`magma-lighting.glsl` for reflected light and emission. Terrain-boundary palettes
use the same solid evaluation. World-space coordinates continue across adjoining hexes. Three
overlapping material patches use deterministic offsets, rotations and scale
variation to suppress recognizable repeats. Colour, heat, height and normals keep
the same blend; normal gradients rotate with their patches. Solid crust uses
height-aware patch weights so raised rock covers adjacent fissures instead of
crossfading their heat over cold plate faces. Smooth triplanar
projection covers tops, slopes, curved fall lips and vertical cuts without switching
projection abruptly.

Lava reuses `GpuOcean`'s actual 128-square GPU inverse FFT with a separate, slow,
isotropic spectrum that suppresses short waves. `ocean-lava-finish.frag` retains
displacement; `ocean-water-finish.frag` computes water's compression and foam. Their
centering helper, spectrum and FFT stages stay shared. FFT horizontal displacement deforms
the cooling skin and hot channels together; FFT slopes light the folds. It is
independent of wind and only runs while loaded chunks contain lava. The same
`BoardFlow` currents and `GpuWaterShader.Field` sampler supply downhill flow, bank
distance and approach/junction currents. Lava and water have separate fields, so
currents cannot cross between them. Rebuilt chunks rebind reused materials to their
new field and dispose the old texture. The board's shared clock also advances two
staggered material advections, hiding their reset; solid crust remains stationary.
This is a viscous surface approximation, not a volume-conserving or thermal fluid
solver. It introduces no separate board scene, animation thread or game state.

Basalt uses the board's linear lighting, shadows, normal-map toggle and GGX
roughness response. Heat and the local glow on fissure walls are added after
ambient, sunlight and shadowing, so both materials remain emissive at night. The
existing atmosphere composite applies fog and exposure. The scene uses an LDR
target; emission is bounded to retain orange detail. There is no new screen-space
bloom or dynamic illumination of neighbouring units/terrain. Relief uses normals
and bounded parallax, not geometric displacement, so silhouettes and picking
remain the existing board geometry. Crust traces twelve depth layers with a refined
intersection to preserve its occluding slab edges; lava keeps its thin-skin offset.
The crust bake concentrates relief in the thick plates and gives fine rock grain
only shallow relief. Warm reflections are excluded from emission, and fissure-wall
glow stays close to the gaps instead of spreading over the basalt.

## Assets

`mm-data/tools/build_magma_materials.py` reuses the existing periodic terrain and
normal-map baker. It separates neutral basalt reflectance from heat and estimates
recessed fissure height. This is artistic relief, not measured photogrammetry.

Each material (`crust` and `lava`) has four aligned, repeating 1024px maps in
`mm-data/data/models/board/textures/magma/`. The base sampling scale is 12 metres;
the stochastic patches prevent that source tile from repeating on a visible grid.

| Map | Channels |
| --- | --- |
| `NAME.png` | sRGB basalt albedo, with heat removed |
| `NAME-normal.png` | Linear tangent normal, U right and V down |
| `NAME-surface.png` | R height, G perceptual roughness, B cavity AO, A relief UV / 0.1 |
| `NAME-heat.png` | R emission intensity, GB signed flow encoded into 0–1, A local fissure glow |

Metalness is zero and opacity is one. Height, normals and the shader's relief range
use the same quantized field. The shader samples every material map at the same
advected/parallax coordinates. Mipmaps and supported anisotropic filtering handle
distance. `GpuAssets` owns and disposes the maps; materials borrow them. Missing or
partial volcanic sets retain the existing crust artwork or lava GIF rendering.
Gradle's existing `stageDataFiles` copies the runtime maps into MegaMek's data folder.
