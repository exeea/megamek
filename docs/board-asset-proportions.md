# Building and bridge asset proportions

Buildings used the legacy coordinates: an 84 by 72 hex footprint, but only one
unit from the ground to the roof. Placement multiplied that unit by the board's
level height. This looked correct in game and flattened in an ordinary GLB viewer.

All 3,295 building GLBs now have their ground at local Z=0 and roof at Z=18
(glTF Y-up in the files). This is one default terrain level. It also applies to
fuel tanks and industrial structures. Placement fits the actual model bounds
to the game's level count, including when the board scale or level height changes.
Interior floors and columns use the shell's actual local height and share its
placement transform. Facade UVs and per-level texture repetition are unchanged.

## Bridge use

The bridge GLB is active, not replaced by procedural roads:

- `BoardFeatures.capture` emits one bridge feature, preserving its authoritative
  exit mask and elevation in the scene snapshot.
- `GpuAssets.model` loads the complete GLB for that mask; `GpuTerrain` places it
  at the bridge elevation and includes its actual triangles in picking.
- `GpuRoads` drapes roads over the ground. `BoardSurface` joins the approach
  to the bridge deck without raising the ground beneath the bridge.

There are 64 complete bridge shapes, one for every six-bit exit mask. `bridge.glb`
is the north/south straight span (mask 09); `bridges/bridge-exits-NN.glb` contains
each other mask. Each file has one LOD0 group. Exit patterns are not LOD levels.
The carriageway is 15 units wide. Deck Z=0 has a 1.5-thick slab below it and
outside concrete rails reaching Z=2.5. These are physical tile units.
Terrain-level height controls elevation only; board scale applies uniformly.
The baked shapes already meet the actual 84-by-72 hex edges in all directions.

The deck uses the same surface clearance as the adjacent road, instead of the
old 0.16-unit offset. Its asphalt color, normal and surface maps, wetness response
and world-space UVs come from the road material system, so choosing another exit
pattern does not rotate or restart the asphalt texture at the join. Sides and underside use
shared concrete with repeating face UVs. The old plan-view bridge tile is no
longer a material on this model.

The original arm assembly was incorrect for turns and junctions: its full-length
rails crossed the carriageway and turns left unfinished corners. The junction
audit confirmed these defects and found every topology in authored maps. That
assembly has been replaced with continuous decks and rails only on the exposed
outer boundary, with every connected exit left open. Five/six exits now use
roundabouts: tangent entry/exit curves, a full 15-unit circulating carriageway,
and a smaller solid concrete island. The island fits inside the carriageway;
the approach roads retain their width. No-exit and single-exit patterns have
closed terminal edges. Construction/repair during play still allows exactly
two exits, including turns; game rules were not changed.

The saved authoring polygons are in mm-data's `tools/board-models/bridge-shapes.json`.
`blender --background --python tools/build_bridge_assets.py` bakes the GLBs directly
from that file; no MegaMek export task or Java geometry generator is required.
The main board asset builder delegates bridge rebuilds to that same script.
The roundabout variants include finer curves around the island and entrances. Geometry is loaded from GLB
at runtime; textures remain shared. `BoardBridge` only selects the external model.
If road dimensions change, update the saved outlines/assets and run the bridge
geometry and approach checks to keep the two aligned.

## Original artwork beside each building

Each building is packaged as:

```text
building_hard_00.glb   # one-level mesh; processed roof texture embedded
building_hard_00.png   # exact original building image, with alpha and shadow
```

All 3,295 sibling PNGs are byte-identical to the original source images.
The loose processed `*-roof.png` siblings were removed. Their unchanged offline
inputs remain under `tools/board-models/roofs`; the footprint preparation tool
generates those inputs and copies the original image beside the GLB. The building
exporter embeds the processed input. Shared facade textures remain external.

## Verification

- After removing the Java bridge exporter and Gradle task, all 64 external-model
  `BoardBridgeTest` cases, the native `GpuBridgeSmokeTest` road-join/picking check,
  and scoped main Checkstyle passed. The saved outlines and GLBs were unchanged;
  this cleanup did not rerun the full project suite or Blender baking.
- Before the bridge material/dimension redesign, compared every rescaled mesh:
  385,915 vertices and 221,763 triangles across
  3,296 GLBs. Footprints, normals, UVs, colors, indices, nodes and materials are
  unchanged; vertical coordinates compensate to within 1e-7 of the old values.
- All 3,295 embedded roof images remain byte-identical. Validated every original
  image sibling against its source and confirmed no processed roof siblings remain.
- Khronos validation: all 3,295 building GLBs have zero errors. All 64 complete
  bridge GLBs have zero errors and warnings; they use the shared road/concrete images.
- The complete board catalog validator passes, including all plant LODs and
  texture references. Isolated preparation/export of all eight building families
  and the bridge passes with the new original-image packaging.
- The earlier proportions preview, before replacing the bridge arms with complete
  junctions, is `tmp/structure-proportions/structure-proportions-review.png`.
  It was imported/rendered in Blender using uniform display scale only.
- Native building/bridge review passed: one-to-four-level buildings, tanks,
  industry, default/modified board dimensions, bridge bounds and elevated deck
  picking, repeated after removing the processed image siblings. Both Java
  checkstyle tasks passed. The courtyard/interior checks passed at local heights 1 and 18 before
  the broader resource smoke test reached its separate terrain-picking failure.
- After replacing the arm assembly, all three native reviews passed:
  `GpuBridgeSmokeTest`, `GpuRoadSmokeTest` and `GpuBuildingMaterialsSmokeTest`.
  The bridge check tests every connected exit of all 64 patterns, at three points
  across each carriageway, at terrain-level heights 12 and 36, and verifies
  raised-side height separately. Captures cover straight, both turn types, T/Y,
  X, five/six exits, single exits and the isolated deck.
  The road review also checks normal maps, wetness and edits against a fresh build.
  Both checkstyle tasks passed again. Captures are under
  `megamek/build/gpu-board-review/bridges/exits-*-{top,oblique}.png`.
- The focused CPU rerun passed all 85 cases: 64 `BoardBridgeTest` cases comparing
  exported triangles to road carriageways/outer rails and rejecting phantom
  exits, nine feature cases and twelve parameterized road/bridge approach cases.
  This did not rerun the broader unrelated checks listed below.
- The main board builder's bridge-only rebuild reproduced all 64 complete GLBs
  byte for byte. The complete catalog validator passed all 3,295 buildings,
  87 feature entries and 66 plant LODs after the bridge replacement.
- The road/concrete follow-up on 2026-09-28 fixed the previously failing road
  contact and bridge-approach checks. All 17 `BoardRoadTest`, 66 `BoardSurfaceTest`
  and 64 `BoardBridgeTest` cases passed, together with the native road/bridge
  reviews. Concrete ramps retain flat slabs and vertical retaining walls, and
  ordinary road materials use masks on the existing terrain faces. Triangle
  measurements, complete targeted coverage and test-harness exclusions are
  recorded in [GPU road surfaces](gpu-roads.md#verification).

The broader `GpuResourcesSmokeTest` currently expects changing board scale to
change picking before `terrain.update`. The ongoing terrain snapshot changes
instead keep picking on the installed geometry until publication; the old assertion
returns null when aimed beyond that geometry. This asset change does not alter
that behavior. That broader test has not been reported as passing.
