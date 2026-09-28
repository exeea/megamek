# 3D unit models on the GPU board

When the GPU board draws an Atlas, it does not load a finished Atlas model. It loads the bare Atlas body, reads the
unit's real equipment list, and fits a weapon piece to the body for each weapon the unit carries. A stock AS7-D and a
custom refit share the body and differ only in what is attached. This page covers how MegaMek finds, loads, checks
and assembles the files; mm-data's `data/models/units/README.md` describes the files and how to rebuild them.

The switch is `GpuUnitModels.ENABLED`, currently `true`. Both GPU camera views use the same models. Python and
Blender are authoring tools only; the game runs neither.

## Finding the model for a unit

`data/images/units/mekset.txt` has an optional fourth field naming a recipe, relative to `data/models`:

```text
chassis "Atlas" "meks/Atlas.png" "units/modular/meks/atlas.json"
exact "default_medium" "defaults/default_medium.png" "units/modular/meks/fallback-biped-{weightClass}.json"
```

Exact entries win over chassis entries, as they do for sprites. `MekTileset` fills in `{weightClass}` (light,
medium, heavy, assault or superheavy) from the Mek. Each unit gets two candidates: its own recipe and the generic
recipe for its type. `GpuUnitModels.get` tries the first, then the second, and if both fail the unit is drawn as
its flat sprite. A sensor contact never reveals which model a unit uses.

## Descriptors: schema 2 only

Every descriptor must say `"schema": 2`; anything else is refused and the unit falls back. There are two layers:

- Recipes, which `GpuUnitModels.load` reads: `"kind": "mek"` (assembled by `MekVisual`), `"family"` (vehicles,
  aircraft and other non-Meks, assembled by `FamilyVisual`), `"squadron"` (`SquadronVisual`) and `"formation"`
  (infantry and battle armour).
- Parts, which `UnitModelDescriptor` validates: `"kind"` is `body`, `troop` or `equipment`, and `"mesh"` must end in
  `.glb`. The descriptor checks that every joint, armour location, hardpoint and emitter it names exists in the
  mesh, that meshes carry position, normal, colour and texture coordinates, and that materials use only the `paint`,
  `detail` and `bark` roles.

A bad part is logged once ("Cannot load modular unit asset ...; using its fallback") and not retried while the
board is open, so one broken custom file cannot stop the board from opening.

## Loading a GLB: `RigidGlb`

`RigidGlb` reads GLB (binary glTF 2.0) through JglTF without touching OpenGL, so every check runs before any GPU
memory is allocated. It converts glTF's Y-up axes and linear colours into the board's Z-up, display-colour
convention, and keeps node names, part names and material roles. It refuses skins, animation clips, morph targets,
external geometry buffers, required extensions, and materials that are not opaque and single-sided. Diffuse images
may be embedded PNG or JPEG, or local files inside the model folder. G3DJ is no longer read.

Unit GLBs must hold their levels of detail as named groups. `RigidGlb.loadLods` requires top-level groups named
after the file: `atlas.glb` must contain `atlas-lod0`, and may contain `atlas-lod1` and `atlas-lod2`. A file
without them is refused with "Name the levels of atlas.glb as groups atlas-lod0 ...". The groups must be empty
nodes with identity transforms. `phoenix-hawk.glb` is the working example with two levels.

Missing levels fall back toward more detail: without `-lod2`, LOD1 is used in its place, and without `-lod1`, LOD0.
`MeshLod.load` does this once at load time, and `GpuUnitModels.modular` shares the resulting GPU mesh, so a
single-level file costs no extra memory. The board's scatter kit, `scatter.glb`, is a separate case: it names each
shape's levels `<shape>-lodN` inside one file (`BoardShape.loadKit`).

## Assembly and level switching

`MekVisual` builds the body, removes arm parts that the Mek's actuators do not have, and attaches the LOD1 body's
parts, switched off, to the matching LOD0 joints so both levels ride the same animated rig. `UnitEquipmentAssembly`
then fits each real weapon to a hardpoint using the recipe and `equipment.json`, with weapon-family fallbacks for
unmapped weapons. `FamilyVisual` does the same for vehicles and aircraft, choosing the authored form for the unit's
movement mode and hiding turrets it lacks. Formations place one figure per trooper, with transports for motorized
and mechanized infantry.

`GpuUnitInstance` switches a Mek to LOD1 below 96 pixels tall and infantry or battle armour below 48
(`FormationLod`). The selected unit and both sides of an attack being played back stay at LOD0. Vehicles and other
families draw LOD0 only for now, and LOD2 switching is being added. Small equipment is hidden below a few pixels
across. The `[MekLod]` and `[FormationLod]` log tags record these choices.

## Triangle budget and sanity ceiling

The art budget is for the whole assembled unit, body plus every fitted weapon, per level:
`UnitModelDescriptor.UNIT_TRIANGLE_BUDGETS` is 5,000 for LOD0, 2,000 for LOD1 and 500 for LOD2. It is the same
budget the mm-data exporter enforces on bare bodies.

MegaMek does not refuse a unit over budget. After fitting the weapons, `UnitEquipmentAssembly.warnOverUnitBudget`
counts each level the body really has and logs a line like this one (the numbers are made up):

```text
[UnitBudget] units/modular/bodies/atlas.json: LOD0 draws 5312 triangles (body 767 + 14 fitted modules 4545), over the 5000 budget; drawn anyway
```

The unit is drawn in full; the split shows whether the body or the loadout needs lightening. Formations and
squadrons are not checked.

The only hard refusal is a sanity ceiling, `UnitModelDescriptor.MAX_TRIANGLES`, one million triangles per asset,
which catches a wrong export. Separately, each level loads as one mesh with 16-bit indices, so it can hold at most
65,535 vertices, about 21,000 flat-shaded triangles.

## Tests

- `UnitModelDescriptorTest` loads every deployed part through the real loader, and checks the sanity ceiling, GLB-only
  meshes, the LOD0 marker and bad sockets.
- `RigidGlbTest` covers the importer: axis and colour conversion, required `-lod0` groups, identity group transforms,
  level fallback, textures, and refusal of external buffers.
- `UnitEquipmentAssemblyTest` includes a unit over its budget being drawn in full.
- `GpuModularUnitModelsSmokeTest`, `GpuGlbUnitsSmokeTest` and `GpuMekLodSmokeTest` need a real OpenGL window. Run
  one with `./gradlew :megamek:gpuBoardSmoke --tests '*GpuModularUnitModelsSmokeTest'`. Review images go to
  `megamek/build/gpu-board-review/`. The first two call `GpuMekAssemblyReview`, which renders the runtime assembly
  of real stock units and a custom refit.
- `GpuUnitModelBenchmarkSmokeTest` is a bounded probe of instance and draw cost, not a frame-rate promise.

`gpuBoardSmoke` runs `stageDataFiles` first, which copies from the `../mm-data` folder next to this checkout. Make
sure that checkout holds the models you mean to test.
