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
Tropical mixtures use the existing height-and-noise contacts to retain visible
patches of forest litter among the other material. A whole-material color fade
washed out the dark moss against bright desert ground. Other authored gradients
keep their existing interpolation. Contacts between neighboring themes use the
shared wider, softened gradient. The tropical exception fades with its actual
material contribution, rather than switching on for an entire triangle palette.

Tropical terrain shares meadow soil-bank and granite-bedrock geometry. Its
forest-floor albedo/height and normal/AO maps enter the existing sculpt texture
array; dirt, soil mantle and bedrock maps are reused. No separate renderer,
animation timeline or picking geometry is introduced.

## Assets in mm-data

- `tools/terrain-contact-sources/tropical-reference.png`: built-in ImageGen visual
  reference based on the supplied Racice screenshot; not an engine capture.
- `tools/terrain-contact-sources/tropical-ground.png`: forest-floor source.
- `data/models/board/textures/sculpt/tropical-ground.png` and
  `tropical-ground-normal.png`: 512-pixel runtime maps, four-metre repeat.

Rebuild and verify with the existing authoring pipeline:

```shell
python tools/prepare_terrain_contact.py --only tropical-ground
python tools/prepare_terrain_contact.py --only tropical-ground --check
```

Normal relief is an artistic estimate from the generated source, not a measured
scan. The concept is a visual target; runtime reuses the shipped low-poly palms,
broadleaf trees and jungle foliage instead of reproducing every leaf in the image.

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
