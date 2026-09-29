# Modular buildings

The board's tileset still chooses building artwork. `BoardArtwork.customBuildingFile` maps the selected legacy
asset to `data/models/buildings/<family>/<name>.glb`, removing the `saxarba/` provenance directory. For example,
`buildings/saxarba/fortress_light/fortress_light_a_52` selects
`data/models/buildings/fortress_light/fortress_light_a_52.glb`.

`GpuAssets.building` tries that file first for BUILDING terrain. Missing kits keep the existing
`data/models/board/buildings/...` GLB/G3DJ loader, height fitting and wall textures. Fuel tanks and industrial
terrain keep their existing loaders. Discovery accepts a custom kit even if there is no legacy model.
Malformed custom kits fail with an authoring error rather than silently concealing invalid assets.

## Asset contract

One building kit per GLB, with identity root groups:

```
fortress_light_a_52-lod0
  fortress_light_a_52-lod0-floor0
  fortress_light_a_52-lod0-floor1
  fortress_light_a_52-lod0-floor2
  fortress_light_a_52-lod0-roof0
fortress_light_a_52-lod1     (optional; same module roles)
  ...
```

Author buildings with `lod0` and, when useful, `lod1`. Consider `lod0` alone for a simple kit, especially below
200 triangles. The fortress ships with two levels. This is an authoring budget, not a loader rejection rule:
if a GLB also supplies `lod2`, the loader uses it. Other asset types retain their existing LOD rules.
Each child is a rigid mesh node; multiple material primitives in that mesh are fine. Variant numbers may be
sparse. `floor0`, at least one upper floor, and at least one roof are required. Do not add helper mesh nodes.

Author each floor exactly one model level (18 units) high in Blender's Z-up space. The GLB exporter converts to
standard glTF Y-up. Build hollow walls with inward-facing geometry; runtime generates the interior slabs and
struts. Author the modules as a readable stack: this kit's floor0, floor1, floor2 and roof0 stand at Z=0,18,36,54.
The second LOD stands alongside at X=110, also assembled. Thus a viewer shows the building, not overlapping pieces.

The loader computes each module's transformed geometry bounds and removes its horizontal center and minimum Z.
Saved object origins, baked vertex translations and display offsets therefore do not determine placement.
Modules must share a centered horizontal envelope and footprint, including across LODs; asymmetric protrusions
must not shift one variant's bounding center. Group transforms remain identity, as required by the rigid importer.

For N levels, select floor0, then N-1 random upper floors, then one random roof. A placement seed derived from the
hex and selected artwork makes choices stable across camera movement, LOD changes and reloads. Roof selection is
independent of height, and a height edit preserves existing lower-floor choices. Offsets are `index * 18`.
Scale the assembled stack only by the board's configured level size; the roof is an additional cap, not a reason
to compress the floors. A five-level building has five floor modules plus its roof.

## Interiors and picking

Each roof must have a flat, downward-facing triangulated underside at its module base. That actual surface
provides the occupied footprint, including notches, courtyards and disconnected wings. It is extruded through
the stack height and passed to the shared `GpuBuildingInterior` floor/strut generator. This format assumes a
constant footprint over the floors; setbacks and differing upper-floor footprints are not supported yet.
Supports require their center and all four corners to lie inside the footprint. The roof cap's furniture does
not increase occupied floor height. Existing occupancy cutaways fade walls and slabs while struts remain opaque.

Picking keeps one triangle set per LOD0 module and offsets the ray for each story. It does not retain expanded
triangles for every possible building combination. Picking is independent of the displayed LOD.

## Cache and ownership

- A kit owns its shared module models and one normalized triangle set per module. Textures use the existing
  `ModelTextures`/`GpuAssets` cache. Concrete trim and wall tints use vertex colors, sharing one embedded concrete
  image; the roof uses the second image.
- An assembly cache contains a compact string of module indices, its kit reference and shared interior reference.
  Offsets are implicit. There is no cached expanded Model per combination. The common four-module kit needs six
  index bytes for a five-level recipe on the JVM's compact-string representation, plus object/map overhead.
- Live scene instances are constructed during terrain preparation/load/edit. LOD transitions replace lightweight
  node/material instances while borrowing the same GPU buffers. A single-LOD kit never switches models.
- Floors and struts are shared by roof footprint and level count. Scene commits discard recipes and interior
  buffers no installed or cached chunk uses. Native resources have explicit GL-thread disposal.
- New buildings bypass the whole-building chunk mesh merger. The existing `GpuTreeInstances` backend also draws
  building module ranges, sharing one compact range buffer per LOD/material/pass across all placements. The GPU
  stores ordinary instance transforms in addition to the small recipe; total rendering memory is not a few bytes.
- A weak map caches persistent renderable-to-batch lookups without retaining its keys. Weak references never own
  GPU resource disposal. Unchanged frames reuse meshes and upload no new instance data; visibility/LOD changes
  can update instance buffers. There is no per-frame building assembly or interior generation.

Adding or replacing a GLB in an already-open viewport requires the existing asset reload or reopening the view;
missing kits and loaded kits are cached for that renderer's lifetime. Editing a building's height uses the cache.

## First asset and provenance

The census uses `Board.load` and the actual Saxarba `HexTileset` matching rules on the current map collection.
It scanned 2,409 boards and 152,095 building hexes with at least one level. The largest count is
`fortress_light_a_52`: 2,441 placements, with 41 at level 1 and 2,400 at level 10. These are concentrated on
`unofficial/VictorMorson/152x176 Tian-Tan Industrial Center.board`; this is the most frequent placement,
not the most geographically distributed design. `building_light_42` is next with 2,077 placements on 15 maps.

Triangle counts in the final GLB (excluding the generated interior):

| Module | LOD0 | LOD1 |
| --- | ---: | ---: |
| floor0 | 14,560 | 162 |
| floor1 | 11,192 | 162 |
| floor2 | 11,612 | 162 |
| roof0 | 550 | 202 |
| Entire kit | 37,914 | 688 |

A five-level LOD1 building uses 1,012 triangles; ten levels use 1,822. Distant floors retain hollow walls,
planar window/door accents and a story band. Roof openings keep their silhouettes with simplified top accents.
LOD0 retains recessed windows, panel joints, door ribs/canopies, service details and roof repairs.

Reproducible census, exact image prompts, generated reference/albedos, the Blender source, authoring script and
renders live in `mm-data/tools/buildings/`. The concept and two bitmap materials used the built-in ImageGen tool.
The outline is an interpretation of Saxarba's original roof tile, preserving its front notch and diagonal row
of roof openings. The mm-data repository's asset license and source attribution still apply.

From mm-data, regenerate with:

```
blender --background --python tools/buildings/build_modular_fortress.py -- --render
blender --background --python tools/buildings/review_modular_fortress.py
```

The GLB is the runtime deliverable. `modular-fortress.blend` contains only the asset's two scenes, with packed
textures. `fortress-three-floors.png` is the source studio render; `fortress-glb.png` renders the exported GLB.
The importer currently consumes albedo and vertex colors, not PBR normal/roughness maps. Blender's source
studio adds fine bump and different roughness; game lighting also differs. Those previews are not a promise
that the game uses the same shading.

## Verification

`GpuBuildingTest` covers deterministic variants, five-level semantics, malformed roles, single-LOD and additional-authored-LOD support,
and the shipped GLB's visibly stacked modules. `RigidGlbTest` covers the shared importer. The on-demand
`GpuModularBuildingSmokeTest` exercises actual GPU import with translated origins and baked vertex offsets;
1/5/10-level assembly; shared buffers; recipe/interior eviction; legacy fallback and courtyard generation;
cutaway rendering; LOD-independent roof picking; height edits; and changed board level spacing. It checks that
an unchanged frame creates no additional instance uploads or buffers. `GpuTreeLodSmokeTest` checks the shared
instancing backend's existing tree behavior. Native screenshots are written under
`megamek/build/gpu-board-review/modular-buildings/`.

Run from megamek_temp:

```
./gradlew.bat :megamek:test --tests '*GpuBuildingTest' --tests '*RigidGlbTest'
./gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuModularBuildingSmokeTest' --tests '*GpuTreeLodSmokeTest'
./gradlew.bat :megamek:checkstyleMain :megamek:checkstyleTest
```

Broader checks in the current shared checkout also report bridge and terrain assertions: the resource smoke's
500-triangle cap for `bridges/bridge-exits-31`, a bridge-bounds assertion in `GpuBuildingMaterialsSmokeTest`, and
a BLDG_BASE_COLLAPSED ground-marking assertion in `BoardFeaturesTest`. These were not weakened for this change.
