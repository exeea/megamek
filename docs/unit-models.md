# Unit models

[Docs index](README.md) · [GPU architecture](gpu-board.md).
Runtime classes are in `client/ui/clientGUI/boardview/gpu`.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `MekTileset`, `UnitModelSelection` | Choose model identity and capture the unit's visible appearance/loadout. |
| `UnitModelDescriptor`, `RigidGlb`, `MeshLod` | Decode the descriptor, rigid geometry and compatible mesh levels. |
| `GpuUnitModels`, `GpuUnitModel`, `ModelTextures` | Cache shared model/texture resources. |
| `UnitEquipmentAssembly`, `UnitEquipmentModels` | Attach captured equipment through the descriptor's hardpoints/recipes. |
| `GpuUnitInstance`, `UnitRig`, `UnitAnimator` | Hold per-unit instances and pose named rigid parts. |
| `UnitPlayback`, `UnitMotion` | Sequence resolved events and evaluate the displayed movement route. |
| `MekVisual`, `FamilyVisual`, `InfantryVisual`, `BattleArmorVisual`, `SquadronVisual` | Apply body-family, troop-formation and squadron presentation. |
| `UnitGroundContact`, `UnitLandingSupports`, `UnitPicking` | Ground and intersect the actual posed models. |
| `UnitDamageDisplay`, `UnitCamouflage`, `GpuUnitCamouflage` | Apply an instance's visible damage and paint without changing shared geometry. |
| `FormationLod` | Choose projected detail with hysteresis while retaining the same animation state. |

## Model selection and assembly

`MekTileset` resolves model identity alongside sprite identity. The optional fourth
field in `mm-data/data/images/units/mekset.txt` supplies a model reference, with
type-specific defaults available for units without a dedicated asset.

`UnitModelSelection` captures the selected model and the unit's visible appearance
data: loadout, surviving personnel, facing and damage. A sensor-only contact has no
model identity. `GpuBoardSource` performs visibility filtering before this presentation
data reaches the renderer.

[GpuUnitModels](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuUnitModels.java)
loads and caches the shared assets. `UnitModelDescriptor` reads the JSON contract;
`UnitEquipmentAssembly` and `UnitEquipmentModels` assemble equipment using the
captured loadout. Missing or failed assets follow the existing fallback path so an
individual model does not prevent the board from opening.
`GpuUnitModels.ENABLED` controls authored models; disabling them retains the flat
sprite path in both camera views. Raised sensor symbols use `GpuCutout`
independently and do not reveal a concealed unit's model.

## Asset format

A component has a GLB containing named rigid geometry and a JSON descriptor describing
rig roles, location ownership, hardpoints, emitters and assembly recipes. The renderer
animates those rigid parts itself. Skins, baked animation clips and morph targets are
outside the supported format.

`RigidGlb` decodes geometry on the CPU and converts glTF's Y-up coordinates and linear
colors to the board's Z-up/display-color convention. Preserve mesh/node names when
editing a model: descriptors and damage/animation code use those names to find parts.

LOD0 is required. Optional `<component>-lod1` and `-lod2` groups provide simpler meshes;
`MeshLod` resolves missing levels toward an available higher-detail mesh when loading.
The levels retain compatible rig names so switching meshes can retain the same pose.
The `paint` material role receives runtime camouflage, while `detail` retains authored
artwork. Textures may be embedded PNG/JPEG data or local relative images;
`ModelTextures` shares them by image and sampler. Legacy custom G3DJ descriptors
remain readable, although the deployed library uses GLB.

The full format and authoring instructions are in
[mm-data/data/models/units/README.md](../../mm-data/data/models/units/README.md).
`MekModelCatalog` exports game data for authoring, and
[build_unit_models.ps1](../../mm-data/tools/build_unit_models.ps1) drives the offline
asset build. Python and Blender belong to that authoring workflow; gameplay loads the
generated assets directly.

## Scale and formation layout

Single-hex models share the Atlas authoring conversion:
`2 * LEVEL / 54.858`, uniformly on all axes. Bodies retain authored relative
sizes rather than each being normalized to its own height. Board level height,
unit scale and `UnitFamilyScale` size multipliers resize all axes; only the
explicit height multipliers stretch Z. Multi-hex units use occupied-footprint
fitting and their own uniform scale.

`UnitFamilyScale` is the source of family defaults and Mek weight-class
multipliers. Infantry and Battle Armor are authored at canonical scale and
enlarged for readability, with vehicles and formation spacing scaled alongside
them. Changing a body asset's height to compensate would affect all instances
using that asset.

`InfantryFootprint.layout` fits the authored formation onto the finished rim,
including slope room and shifted river corners. It contracts/shifts the layout
while retaining figure size and relative spacing. Troop clearance accounts for
all watch headings; vehicles retain their parked heading. A changed support
surface updates standing and arrival layouts.

## Animation and damage

`UnitPlayback` is the shared render timeline for movement, attacks and attachment
changes. It accepts resolved game events and controls when their visual consequences
appear. This lets a movement or salvo play coherently while the client continues to
receive newer game state.

`UnitMotion` evaluates movement along the captured route. `UnitAnimator` calculates
per-unit rigid poses from rest transforms and the playback clock. `UnitRig` supplies
the joint structure, while `MekVisual`, `InfantryVisual` and `BattleArmorVisual`
handle the corresponding body/formation presentation. Facing, torso twist, prone poses
and equipment effects are derived from the captured state and animation sequence.

A descriptor's `upperBodyNode` lets `UpperBodyTurn` twist the torso while legs
retain the unit's facing; without it the body turns as one piece. Walking gait
follows distance traveled, including acceleration/braking. Hull-down, prone,
forced falls and jump posture use the shared timeline, including pause/skip.
Troops share articulated base meshes, with stable member identities for idle
variation and casualties. Each member rises and settles around its own movement.

Lost-location node names match game location abbreviations. Damage overlays
prioritize destroyed over structure over armor and are applied after baked vertex
color; intact cockpit glazing keeps its authored appearance. Patterns are seeded
by unit and part so they remain attached through movement and instance rebuilds.
Tuning's damage preview changes presentation only and must restore live state
when disabled.

`UnitDamageDisplay` applies lost locations and damaged artwork to an individual
instance. Shared model buffers remain reusable by other units. Camouflage follows the
same ownership distinction through `UnitCamouflage` and `GpuUnitCamouflage`.

## Ground contact, formations and attachments

After board placement, `UnitGroundContact` and `UnitLandingSupports` adjust the model
against the shared terrain surfaces. `UnitPicking` intersects the current rigid parts
and their pose. Both cameras draw and pick the same instances.

Infantry and Battle Armor are represented by formations derived from surviving
personnel. Movement type can select troop/vehicle arrangements; these figures remain
a visual representation of one game entity. Infantry descriptors can provide
`movementFormations` keyed by `EntityMovementMode`; motorized/mechanized forms
use their corresponding vehicles, jump forms use articulated jump-equipped troops,
and unsupported modes retain the foot formation. Surviving personnel determine
the occupied slots, including an empty formation at zero survivors.

For exterior passengers and hostile swarmers, `GpuBoardSource` captures the carrier
relationship into `BoardScene`. `UnitAttachments` places figures on the carrier's
posed parts, and `UnitAttachmentMotion` supplies boarding/release motion.
`UnitPlayback` orders those transitions with carrier movement and combat so a packet
arrival does not detach riders at the wrong point in the displayed route.
[Battle Armor attachments](battle-armor-attachments.md) describes grip assignment,
visibility and the packet-order constraints for boarding, unloading and swarming.

## Detail and resource ownership

`FormationLod` selects a unit's mesh level from projected size with hysteresis;
formations measure an individual figure. Detail changes reuse the same animation
state and shared buffers. Selected units and attack-playback participants retain
the detailed representation. Current nominal LOD1/LOD2 boundaries are 96/32 pixels
for Meks and 48/16 for individual formation members, with 10% hysteresis.
Missing optional meshes fall back independently; a detail switch must retain
lost parts and the current pose.

Small attached equipment has its own projected-diameter culling in
`GpuUnitInstance`, shared by color, shadow and outline passes. Selection and
attack playback retain full equipment; culled drawing does not discard emitter
transforms or damage state.

`GpuUnitInstance` holds per-unit pose/material state while borrowing model geometry.
`ModelTextures` owns shared texture caching, and the asset library owns disposal.
Changing one unit's pose or damage must therefore operate on its instance, while
reloading or closing the renderer is responsible for shared resource lifetime.
