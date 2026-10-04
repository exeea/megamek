# Mars coral vegetation

[Docs index](README.md) · [Terrain overview](gpu-terrain.md)

The MARS theme replaces woods and jungle with mineral coral colonies inspired by
1970s science-fiction miniatures. Snow, pavement, orchard fluff and level-one
foliage retain Martian corals. Bare ground does not acquire gameplay cover.

`BoardFeatures` selects six authored assets: two ivory finger colonies, two mauve
perforated fans, and two hollow tube colonies with muted turquoise mouths. Light,
heavy and ultra-heavy cover use two, four and six colonies. One colony reaches the
hex's foliage height; its companions are smaller. Selection and placement remain
deterministic and use the existing road clearance and terrain grounding paths.
Martian colonies also vary their rotation by hex, avoiding identical fan alignment.
The original woods/jungle terrain, density and foliage elevation are never changed.

`GpuTerrain` fits corals uniformly to that foliage height and marks their `coral`
material as solid vegetation. The same scene, asset cache, instancing, picking and
color/depth/shadow paths serve both camera projections. Other themes retain their
existing vegetation; fungal cliff growth and scatter are unchanged.

Coral placement derives clearance from the root geometry beside rims, retains
captured road clearance and samples the drawn terrain around it, including adjacent
hexes. The bottom six percent of the uneven mineral base is embedded, with uniform
scale adjusted to preserve the exposed foliage height. Root radii are derived once
per model during chunk construction; upper branches do not enlarge that footprint.
This prevents the colony from balancing on one low vertex or a raised ground point.
Mars scatter stones use the same `mars-bedrock` texture as larger rocks in the
sixth slot of the existing scatter atlas, without the old red sandstone tint.

The sibling `mm-data` repository contains:

| File | Purpose |
| --- | --- |
| `tools/board-models/mars/coral-concept.png` | Imagegen concept, using the supplied Mars board as a reference. |
| `tools/build_mars_corals.py` | Reproducible Blender authoring and rigid GLB export. |
| `tools/board-mars-corals.blend` | Editable independent scene with all mesh levels and a review camera. |
| `tools/board-models/mars/coral-library.png` | Blender kit preview. |
| `tools/board-models/mars/mesh-report.json` | Measured bounds and triangle counts. |
| `data/models/board/mars/*.glb` | Six runtime models. |
| `data/models/board/textures/foliage/mars/coral-mineral-atlas.png` | Shared opaque Imagegen atlas: porous ivory, veined mauve, weathered tubes and dusty basal crust. |

Build through Blender MCP by executing
`runpy.run_path('.../mm-data/tools/build_mars_corals.py')['build']()` in Object mode.
The builder adds an independent scene and saves only that scene and its dependencies;
it does not replace existing Blender scenes. The normal Gradle `stageData` task
copies the GLBs and texture into the game's data directory.

Each asset targets 16,000 / 4,200 / 1,000 / 240 triangles across four named mesh levels.
Every level is grounded at local Z=0 and is 30 board units tall. Fans have fewer
openings at distance, so their topology can simplify while keeping a fan silhouette.
The near meshes have sculpted mineral pits, irregular fan cells and structural
veins, curved trumpet bodies with rolled mouths, and encrusted bases. Their atlas
adds small pores, weathering and Martian dust without changing the shared shader.
The far level is an opaque mesh, not an impostor card. No performance improvement
is claimed. Snow uses the same mineral models on snowy ground; this kit does not
add separate modeled snow caps or cosmetic cliff/scatter colonies.

`BoardMarsCoralTest` covers theme selection and overrides, densities, deterministic
placement, unchanged rules, road clearance, bare ground, and real GLB loading,
textures, grounding, height and decreasing mesh counts. `GpuMarsCoralSmokeTest`
captures dry and snowy colonies in both projections and at close range, renders
the far view, checks board picking and checks for OpenGL errors.

Focused verification commands:

```powershell
.\gradlew.bat :megamek:test --tests '*BoardMarsCoralTest' --tests '*BoardFungusTest' --tests '*BoardFeaturesTest' --tests '*TreeLodTest'
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuMarsCoralSmokeTest'
```

Native screenshots are written under `megamek/build/gpu-board-review/mars-corals/`.

Verified on 2026-10-04: all 44 focused tests, the native GPU coral smoke test and
`checkstyleMain` passed. The dry-ground, snow and material-detail captures were
visually reviewed against the final detailed assets.

The subsequent scatter-color and grounding fix passed 26 focused tests across
`BoardScatterTest`, `BoardMarsCoralTest` and `BoardObstaclesTest`, plus
`GpuMarsCoralSmokeTest` and `checkstyleMain`. The native test now checks transformed
root geometry against the rendered terrain, including adjacent hexes, rejecting
both floating roots and excessive burial on narrow terraces. Updated captures
include `mars-root-contact.png` and `mars-scatter-bedrock.png`.
