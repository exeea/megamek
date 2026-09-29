# Sprite-referenced 3D units

Deployed modular bodies, troops, transports and equipment use GLB (binary glTF 2.0).
Their existing JSON descriptors still own rig roles, hardpoints, emitters and
assembly recipes. `RigidGlb` uses JglTF to parse geometry without OpenGL, converts
Y-up coordinates and linear vertex colors into the renderer's Z-up/display-color
convention, and preserves named rigid nodes and material roles. Unit animation,
camouflage, damage and loadout assembly keep their existing owners. Legacy custom
G3DJ descriptors remain readable. The migrated library contains 718 GLBs with 723 authored mesh levels;
its existing geometry and triangle budgets were preserved.

All 475 authoring and historical reference meshes under `mm-data/tools/` also
use GLB. Their descriptors, manifests and review tools use the converted files;
empty squad references retain their hierarchy without a geometry buffer.
Neither deployed `data/` nor authoring/reference `tools/` contains G3DJ assets.

Each component uses one `<component>.glb`, containing identity groups named
`<component>-lod0`, optionally `-lod1` and `-lod2`. Their child nodes retain the
rig's original names. For example, `atlas.glb` contains `atlas-lod0`, while
`phoenix-hawk.glb` contains its own `phoenix-hawk-lod0` and `phoenix-hawk-lod1`.
Phoenix Hawk IIC is a separate component with its own levels. Recipes refer only
to the component's `body`/`trooper` descriptor. The importer resolves missing
optional levels toward LOD0 once and the library shares the resulting buffers.
LOD0 is required; malformed declared levels report an asset error. Unit drawing
switches between LOD0, LOD1 and LOD2. `detail: lod0` grants the detailed-body
triangle allowance. Older custom separate-level references remain readable.

Detail selection measures standing height in framebuffer pixels at the unit's camera
depth and current scale. Formations measure one figure's height. Both camera views
use the same selection and posed meshes for drawing, picking, outlines and shadows.

| Unit | LOD1 nominal boundary | LOD2 nominal boundary | Enter LOD2 when shrinking | Leave LOD2 when enlarging |
|---|---:|---:|---:|---:|
| Mek | 96 px | 32 px | Below 28.8 px | At least 35.2 px |
| Infantry / battle armor formation | 48 px | 16 px | Below 14.4 px | At least 17.6 px |

Both boundaries have a 10% hysteresis margin; within the band the previous requested
level remains active. Large zoom changes may switch directly between LOD0 and LOD2.
Selected units and attack-playback participants retain LOD0. Each component without
an optional level keeps its preceding mesh, including mixed formations. Missing
LOD1 does not prevent an authored LOD2 from being used. Mek equipment shares the
unit's distant boundary while retaining the existing small-attachment visibility rule.
Changing LOD does not restore removed limbs or create a separate animation rig.
These changes select authored meshes; they do not generate unit geometry or enforce
new triangle budgets. Existing units without LOD2 continue using their earlier meshes.

Authored diffuse textures may be embedded PNG/JPEG images or local relative image
references inside the model directory. The library caches textures by image and
sampler and owns disposal; models and instances borrow them. Embedded images keep
encoded bytes for graphics-context restoration. The `detail` role retains fixed
artwork; runtime camouflage can replace the diffuse map of the `paint` role.
Animated/skinned glTF, morph targets and required extensions remain unsupported.

`RigidGlbTest` exercises the CPU import boundary; `UnitModelDescriptorTest` checks
the deployed component descriptors and meshes. Run
`./gradlew :megamek:gpuBoardSmoke --tests '*GpuGlbUnitsSmokeTest'` for native GLB
assembly, animation, effects, camouflage and damage-material checks. The broader
`GpuModularUnitModelsSmokeTest` also covers terrain contacts and collectable limbs.

Authored 3D unit models are controlled by the code switch
`GpuUnitModels.ENABLED`, currently `true`. Set it to `false` to use the existing
sprite rendering in both GPU camera views. Asset generation and direct asset
review tests remain available while the switch is off.

The GPU board can resolve 3D models from the optional fourth field in
`mm-data/data/images/units/mekset.txt`. Exact model overrides take precedence;
sprite-only overrides still inherit a chassis model. Type-specific defaults
provide biped, quad, tripod, infantry and Battle Armor fallbacks.

The first asset library includes four authored Mek chassis and all 112 of their
catalogued variants, plus four infantry poses and four BA poses. Infantry uses
up to six figures and BA up to four, derived from surviving personnel. The
largest complete asset is 996 triangles; the 112 Mek assemblies use 310–672
triangles. Their silhouettes are authored from the existing chassis illustrations
as well as the top-view sprites. The build records both reference hashes and
provides front, side, top, and three-quarter review renders. Other Mek chassis
use generic models; 734 remain on the explicit authoring queue.

Conventional infantry selects its artwork from the unit's movement mode:
motorized uses reinforced jeeps; mechanized uses tracked, wheeled, or hover APCs;
jump infantry carries small back-mounted jump jets. With 1–4 compressed slots,
one vehicle replaces a troop; with 5–6 slots, two vehicles replace troops. Zero
survivors produce an empty formation. Foot infantry and Battle Armor keep their
existing geometry, and unsupported infantry movement types use foot poses.
These are baked visual groups, not additional entities or transport game rules.
The optional `movementFormations` map in the infantry descriptor is keyed by
`EntityMovementMode` names; old descriptors keep their numeric `formations`.

`MekModelCatalog` exports the current unit/equipment data and actual selected
sprites through MegaMek's existing loaders. Blender builds the source chassis
and equipment modules offline. The current modular exporter writes reusable GLBs;
the historical baked variants remain review references. The renderer
uses the same movement timeline, placement, shadows and camera scene as before.
It receives immutable model-selection data after visibility filtering. A sensor
contact has no model identity. Invalid assets fall back without preventing the
board from opening.

From mm-data, run `tools/build_unit_models.ps1 -Preview` to export, build and
validate. Full format, editing instructions, scope and limitations are in
`mm-data/data/models/units/README.md`. The generated Blender review scene is at
`mm-data/.work/mek-models/review/unit-models.blend`.
Run Blender with `--python tools/render_unit_variants.py -- --infantry` in mm-data
for movement and slot-count review sheets under `.work/mek-models/infantry`.

Focused checks from this checkout:

```text
./gradlew :megamek:test --tests '*MekTilesetModelsTest' --tests '*UnitModelSelectionTest' --tests '*MekModelCatalogTest'
./gradlew :megamek:gpuBoardSmoke --tests '*GpuUnitModelsSmokeTest'
```

No Blender or Python installation is required at runtime. The normal data-staging
tasks include all generated unit assets.
