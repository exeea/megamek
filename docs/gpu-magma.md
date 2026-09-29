# Magma crust and lava

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

The built-in image_gen tool created a concept and two material sources. Originals
and exact prompts are in the sibling `mm-data/tools/magma-reference/` directory.
`revised-prompts.txt` records the wider, varied crust fissures and active lava sheets
with drifting cooling skins requested after the first visual review.
`solid-crust-prompt.txt` records the subsequent crust edit: broad angular basalt
slabs, chipped ledges and cold fracture walls, with heat visible deep in the gaps.
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

## Verification

`python tools/build_magma_materials.py --check` (from mm-data) checks reproducibility,
wrapped seams, normal/height agreement, and the presence of both basalt and heat.
Crust additionally requires predominantly cold plate faces and limited fissure
coverage. The OpenGL test checks that most crust pixels stay dark without lighting,
while its cracks still emit, preventing a return to a uniformly glowing skin.

The focused JVM tests cover crust breakage/restoration/removal through the real
board capture, unchanged game elevation and picking, liquid connectivity, crust
slopes/cliffs at three detail levels in all six directions, watertight graded lava,
existing water flow, terrain detail and natural material boundaries. `GpuMagmaSmokeTest`
renders in OpenGL, checking stationary crust, moving lava, frozen-clock redraws,
flow-loop continuity, normal-map response, emission with all lights off, opaque
depth rendering, live cooling/reheating and release/recreation of the FFT targets.
`GpuOceanTest` checks wind independence, conjugate symmetry and suppression of short
waves in the lava spectrum. The smoke test saves day/night overview and close-up captures
through the production atmosphere composite in both camera projections.
`GpuLiquidSmokeTest` retains coverage of water, hazardous liquids and GIF animation.

Run from the MegaMek checkout:

```text
gradlew :megamek:test --tests *BoardLiquidTest --tests *BoardFlowTest --tests *BoardWaterSlopeTest --tests *GpuWaterFlowTest --tests *GpuOceanTest --tests *BoardSurfaceBlendTest
gradlew :megamek:gpuBoardSmoke --tests *GpuMagmaSmokeTest --tests *GpuLiquidSmokeTest
```

Review images are written under `megamek/build/gpu-board-review/magma-*.png`.
Verified on 2026-09-28: 34 focused JVM tests, both OpenGL smoke tests, main-source
Checkstyle and the material bake's `--check` passed. Verification used isolated
Gradle outputs/cache and the already staged assets while other work compiled in the
same checkout; all eight runtime maps matched their source assets. Day/night close-ups
were reviewed after reducing the active lava skin's estimated relief to about 6 cm.
The subsequent solid-crust refinement passed the material check and both OpenGL
smoke tests, including the cold-face coverage assertion, and was reviewed in both
camera projections. Its source heat map is 94% non-emissive, with about 4% visible
fissure coverage; the four lava maps remained byte-identical. That run excluded
the unrelated `GpuRoughSmokeTest` from test compilation because its ongoing changes
referenced fields removed by concurrent terrain work. No other test was skipped
within the selected magma/liquid smoke suites.
No performance improvement or cross-platform validation is claimed.
