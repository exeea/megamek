# GLB migration verification

The migration is applied in the sibling `mm-data` checkout. There are 4,056
deployed GLBs and 475 authoring/reference GLBs. Neither `data/` nor `tools/`
contains a G3DJ file. Temporary ignored work directories and migration backups
are outside the asset catalog and were retained for recovery.

A subsequent [HEAD-source audit](unit-head-conversion-audit.md) retrieved all
1,176 committed unit G3DJ sources and converted them directly again. The existing
GLBs already matched their geometry, including all fallback/family bodies;
no lost committed edits were found. Unit generators remain enabled and unchanged,
following the user's conditional instruction after that comparison.

## Packaging

| Assets | Files | Packaging |
|---|---:|---|
| Unit components | 718 | One file per body, troop, transport or equipment component; 723 authored levels total |
| Plants | 22 | One file per plant; internal `-lod0`, `-lod1`, `-lod2` groups |
| Saxarba buildings | 3,295 | One file per building; one `-lod0` group |
| Bridge and field | 2 | One file per feature; one `-lod0` group |
| Terrain rocks | 16 | One file per rock variant with three named LOD meshes |
| Small scatter kit | 1 | Named reusable templates; 16 distinct eight-triangle stones with open undersides |
| Effect meshes | 2 | One GLB each for the 36-triangle sphere and four-triangle missile body; internal `-lod0` groups |
| Authoring and reference meshes | 475 | GLB sources and historical comparison fixtures, including seven empty squads |

For example, `atlas.glb` contains `atlas-lod0`; `phoenix-hawk.glb` contains
`phoenix-hawk-lod0` and `phoenix-hawk-lod1`. Phoenix Hawk IIC remains a separate
unit with its own GLB and authored levels. No level is manufactured by borrowing
a different unit. Optional missing LODs reuse the nearest available more detailed
level once at load time. LOD0 is required, and invalid declared levels fail validation.
The unit renderer currently switches between LOD0 and LOD1; the file loader
also accepts LOD2. JSON descriptors retain rig and assembly information.

Plant GLBs now preserve natural proportions at local height 30. The legacy
files had normalized height to one while keeping full horizontal dimensions,
so ordinary viewers showed flattened trees. All 22 deployed plants, their 66
LOD meshes and 22 authoring GLBs were corrected together. Placement uses the
actual LOD0 height, keeping existing board dimensions and LOD transitions.
The exporter and LOD tool also preserve proportions on future rebuilds.

The 16 scatter stones were subsequently redesigned at eight triangles each,
with broad triangular crowns or offset ridges and individually authored outlines.
They retain open undersides and their existing small placement footprint.
The other ten scatter templates are unchanged; the kit totals 498 triangles.

## Images and resource ownership

The [building/bridge proportions and artwork report](board-asset-proportions.md)
documents their standalone dimensions, runtime placement and original image siblings.

GLBs can embed PNG/JPEG diffuse images or reference shared local images.
The same importer and image upload path serves units, plants and buildings.
Filters and wrapping are preserved. The asset library owns cached textures;
models borrow them. Encoded embedded bytes remain available for context restoration.

Each Saxarba building embeds its processed roof PNG with its existing bytes.
Beside `<building>.glb`, `<building>.png` is the unprocessed original tileset
artwork, including transparency and shadows, copied byte for byte for inspection.
There are no loose processed `*-roof.png` files under deployed buildings.
The footprint tool prepares those roof inputs under `tools/board-models/roofs`
for the exporter to embed. Eight facade images remain shared external files, and runtime wall UV
scaling still follows building height. Plant textures remain shared external
images under `textures/foliage`. Authoring plant GLBs reference those same files
relative to their source directory. Unit paint textures can be replaced by
camouflage; fixed detail textures retain their artwork.

## Verified conversion and tooling

- Normal Java compilation, all 39 focused CPU tests and both checkstyle tasks
  passed. Eleven distinct native GPU reviews passed across the migration runs:
  embedded textures, GLB units, formations, landing/damage, terrain detail,
  scatter, building materials, Mek LOD, tree LOD, historical units and modular
  units. The final run repeated the historical, tree and modular reviews after
  converting the reference fixtures.
- Khronos validation: all 4,531 GLBs, zero errors. The bridge subsequently
  received proper slab/rail proportions and shared asphalt/concrete materials;
  its separate validation now has zero errors and warnings. It no longer uses
  the plan-view PNG that produced the ancillary-image-metadata warning.
- Actual Java importer comparison against the pre-conversion geometry:
  4,086 deployed mesh levels / 321,290 triangles, plus all 475 reference/source
  files / 183,282 triangles. Vertex attributes, rigid hierarchies and material
  bindings were preserved within float storage tolerance.
- Embedded roof image bytes were preserved for all 3,295 buildings. All 3,295
  sibling PNGs match their original tileset artwork byte for byte. Board validation
  checks the original siblings, embedded roof fidelity, geometry, roof winding,
  texture dependencies and all 66 plant levels.
- Blender rebuilt all 22 source plants into 66 named levels in a separate review
  directory. Its historical unit review importer loaded all 261 baked reference
  models, including empty squads. The deployed artwork was preserved.
- The shared exporter writes GLB; the reference exporter, plant tools, review
  importers, JSON links and manifest hashes were updated to use it.
- The modular exporter produces 707 procedural components. The deployed library
  retains 11 additional authored components; regenerating the procedural set does
  not replace those artist assets.
- The effect follow-up passed Java compilation, both checkstyle tasks and three
  native GPU reviews: explosion/smoke, missile volleys and destruction playback.
  The new shader review verifies both camera types, repeatable paused frames,
  cooling fire, visible smoke, opaque occlusion and a camera inside the volume.
  Both effect GLBs pass Khronos validation without warnings.
- The plant-proportion and scatter follow-up passed eight CPU rock tests, native
  tree/scatter GPU reviews and both checkstyle tasks. All 45 changed GLBs pass
  Khronos validation with zero errors or warnings. Vertex comparison preserves
  plant XY, topology, UVs, colors and materials, with corrected Z and normals;
  the ten non-stone scatter meshes retain their exact triangle attributes.
  An isolated Blender rebuild from the original sources regenerated all 22
  plants and 66 LODs with the updated authoring tools.

## Failures corrected during integration

| Finding | Location of fix |
|---|---|
| Building selection still required a G3DJ file to exist | Implementation: `BoardView` now accepts GLB |
| A landing pad could not compress slightly when sculpted terrain was above its rest position | Implementation: landing supports allow valid compression and reject nonpositive/nonfinite lengths |
| An empty squad GLB has no binary geometry buffer | Implementation: exporter/importer preserve the empty hierarchy without allocating an empty mesh |
| Damage review applied queued fixture events after initializing the view | Test: drain those events before creating the view |
| Ground contact review assumed flat ground and exact floating-point translation | Test: sample actual terrain; permit only the measured float tolerance in vertical translation |
| Building review counted scatter as buildings | Test: count and inspect building features only |
| Mek LOD review counted arm alternatives removed by assembly | Test: count the actual assembled body parts |
| Tree comparison required identical pixels across display/linear float conversion | Test: permit one 8-bit channel step; larger pixel changes still fail |
| A missile surface hit was 0.000008 units outside the transformed arm bounds because of float rounding | Test: expand those review bounds by 0.00001 units; targeting implementation unchanged |
| Standalone plant GLBs retained the old height-one normalization and looked flattened in viewers | Assets and implementation: restore natural proportions, correct normals, update authoring tools and fit placement using LOD0's actual height |
| Four-triangle scatter stones all looked like pyramids | Assets: replace all 16 with distinct eight-triangle crowns and ridges; retain open bases |
| Buildings and the bridge retained compressed height-one coordinates in standalone GLBs | Assets and implementation: buildings stand one default level tall with matching placement/interiors; the bridge uses fixed slab/rail dimensions independent of terrain-level height, sits flush with roads and shares their asphalt material |
| Repeated straight bridge arms left gaps in turns and rails crossing junctions | Assets and implementation: one complete GLB per exit pattern, baked from the existing road curves/unioned outlines, with outside rails only; all 64 patterns validated against the carriageway and checked in native rendering at two level heights |

## Remaining runtime geometry

The [runtime mesh audit](gpu-runtime-mesh-audit.md) records the two requested
effect exports and the remaining generated shapes. Explosions and impact/death
smoke now use animated volumetric density, hot emission, local self-shadowing
and scene-depth clipping. The exported sphere supplies raster coverage; it
does not define the visible smoke surface. This is a procedural visual effect,
not a fluid simulation, and large-battle frame-time impact remains unmeasured.
Board-dependent terrain, roads, building interiors, placed grass, sprite
cutouts and dynamic effect primitives remain generated.
