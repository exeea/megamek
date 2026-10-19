# Tropical terrain

The `tropical` hex theme selects a distinct forest-floor material and tropical
vegetation in the shared 3D board scene. It does not add jungle rules: woods,
jungle density, foliage elevation, water, roads and movement remain game data.

`BoardFeatures` selects the existing palms and broadleaf trees for tall tropical
woods and jungle, plus low `foliage-jungle` plants between the canopy trees.
Level-one cover uses only the existing jungle understory model. Orchards retain
their orchard models, snow uses the existing snowy vegetation, and clear tropical
hexes do not gain tree cover. Placement, road clearance, grounding, wind, shadows
and tree LODs use the existing feature path in both camera views.

`BoardSurfaceBlend` preserves TROPICAL independently of GRASS, including
`ground_fluff:3` transitions. Each of the Racice River Delta map's 24 tropical
hexes has `ground_fluff:1:3`: half tropical forest floor and half desert ground.
Those proportions are retained by native material blending. SAND remains its
separate surface cover; snow, pavement and magma keep their existing precedence.
Tropical mixtures use the height-and-noise contacts to keep visible patches of
forest litter among the other material; a whole-material color fade would wash out
the dark moss against bright desert ground. Other authored gradients keep their
existing interpolation. Contacts between neighboring themes use the
shared wider, softened gradient. The tropical exception fades with its actual
material contribution, rather than switching on for an entire triangle palette.

Tropical terrain shares meadow soil-bank and granite-bedrock geometry. Its
forest-floor albedo/height and normal/AO maps are layers of the shared sculpt
texture array, alongside the dirt, soil mantle and bedrock maps it reuses. It has
no separate renderer, animation timeline or picking geometry.

## Assets in mm-data

- `tools/materials/terrain/surfaces/tropical-ground/tropical-ground.ptex`: Material
  Maker project and its `sources/` images. `python tools/materials/export.py`, run
  from the mm-data root, exports it with the other projects.
- `data/models/board/textures/terrain/surfaces/tropical-ground.tres` and its
  `_albedo`, `_heightmap`, `_normal` and `_orm` maps: a 512-pixel Godot 4 Standard
  material ([board materials](gpu-board-materials.md)), four-metre repeat.

Normal relief is an artistic estimate from the generated source, not a measured
scan.

## Verification

`BoardTropicalTest` captures the real Racice map and checks its tropical material,
authored desert mixture, vegetation, original terrain rules, density, foliage
height, road clearance and material precedence. `GpuTropicalSmokeTest` renders the
map, a 100% tropical board and a material-contact study through the native OpenGL
pipeline, checks picking in both projections, and captures top and oblique views.
An exposed-ground strip shows grass, tropical, desert, snow, fungus and volcano
contacts without trees hiding their transitions. Shore regression tests in
`GpuSurfaceBlendTest` exercise authored water mixtures at depths zero and one;
they also check banks zero, one, two and four levels above the water.
`BoardSurfaceBlendTest` checks water-bed continuity across all six hex directions.
Aligned pure desert, pure tropical and 50/50 material samples also check that the
mixed image retains visible patches of both sources. Captures are written beneath
`megamek/build/gpu-board-review/tropical/` by the smoke task.

```shell
.\gradlew.bat :megamek:test --tests '*BoardTropicalTest' --tests '*BoardFeaturesTest' --tests '*BoardGroundCaptureTest' --tests '*BoardSurfaceBlendTest' --tests '*BoardOrchardTest' --tests '*BoardScatterTest'
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuTropicalSmokeTest'
```
