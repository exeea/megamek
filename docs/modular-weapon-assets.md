# Modular weapon assets

Use the existing schema 2 GLB + JSON equipment format for turrets, Meks and vehicles.
The shared equipment catalogue resolves the real equipment identity to an art profile;
the chassis supplies its hardpoints and installation settings. Do not add another
weapon registry, derive gameplay rules from mesh names, or merge separate weapons
into one binding because their housings touch.

## Coordinates and installation

Author Z-up, +Y forward. Front-mounted modules have their rear mounting datum at
Y=0. Complete external modules have a pedestal datum at Z=0 and a separate elevated
side-bearing pivot. Export glTF Y-up, -Z forward; `RigidGlb` performs the existing
conversion once. JSON positions and bounds use the engine's Z-up convention.

Use a consistent physical scale within a chassis/profile family. The current turret
profile uses board units; existing low-poly unit profiles use their existing scale.
Do not mix these profiles without an explicit size conversion and fit review.

Encasement applies to **all ranged weapons**. It is an installation property,
independent of weapon identity, damage, caliber and rack size. An external installation
uses a complete receiver/power body on a supported mount (`turret-exposed` profile),
without front carrier plates. A front bay supplies the enclosing armored body. Translate
its weapon backward to adjust insertion; do not shorten it with nonuniform scaling or
reduce the missile face to fit a deeper bay. These two arrangements use distinct GLBs.

Authored **SRM/LRM launchers have exactly two modes**: exposed in a roof cradle and
encased (75% of the module's longitudinal length inside the host). Specify
`"encased": false` or `"encased": true` on the mount; omitted means exposed.
Turret recipes use `"installations": {"center": {"encased": true}}`.
Numeric `insertion` is rejected for these launchers. Both modes retain the complete
rack layout, cell spacing, emitters and scale in each LOD.

Other supported ranged weapons use `encased: false` for their complete exposed
body/shoulders and `encased: true` (the default) for a front bay. The bay supports
adjustable `insertion` in the module's authored units:

```json
{
  "equipmentIndex": 3,
  "hardpoint": "left",
  "area": "left",
  "profile": "turret",
  "insertion": 4.0
}
```

Zero insertion means flush with the bay; it does not select an external installation.
The shared attachment transform applies insertion along
local -Y, after choosing its ordinary fit scale. Emitter positions follow the same
transform. The turret recipe additionally accepts per-socket settings, for example
`"installations": {"center": {"insertion": 4.0}}` or
`"installations": {"left": {"encased": false}, "right": {"encased": false}}`.
Logical socket keys stay stable when a weapon moves to its external mount. Numeric
insertion is rejected on external mounts. Missile-only external batteries support
up to four launchers with up to three front main weapons. A battery containing
exposed guns supports one or two external modules; larger banks use fallback until
their shoulder supports are authored. Exposed guns also cannot coexist with front
main weapons on this kit: their depressed barrels cross the front weapon. These
combinations use fallback; low auxiliary sockets remain available.
Gun feeds pass through protected shoulder
channels into the transverse bearings, leaving clearance below the moving receiver.

The receiving chassis must provide enough internal space and leave the muzzle or
launcher face clear. A bounding box alone cannot describe that cavity. For now,
choose and inspect the installation per chassis. Automatic cavity fitting,
shader clipping and separate shortened variants are not implemented. A later
performance variant may omit permanently hidden geometry while retaining the same
datum, visible dimensions, animation roles and emitters.

This physical insertion is separate from the existing `Binding.embedded` fallback
flag. Recessed weapons still have their own mesh, binding and articulated emitters;
they are not the legacy emitter-only representation on a chassis surface.

## Articulation and effects

New articulated equipment uses `rig: "equipment-v1"` and these joint roles:

```text
module                    root: installation datum
  cradle                  shoulders or launcher cradle, fixed during elevation and recoil
  aim                     optional aiming joint
    collar                receiver/collar/axles, stationary during recoil
    recoil                optional barrel/breech joint
      geometry
```

For a weapon without recoil, geometry belongs directly to `aim`. A muzzle emitter
belongs to `recoil` when present; otherwise it belongs to `aim`. Use existing
`muzzle`, `beam` and `launcher` roles and effect names. Emitters describe local
position/direction, never a second copy of the weapon's game state.

External modules also declare `elevation: "aim"`; this restricts their local aiming
to X-axis pitch so the side axles remain inside the cradle bearings. The bearing
on the turret head supplies yaw. The authored external kit has a visual depression
stop at -20 degrees and elevation stop at +25 degrees. These do not change legal
firing arcs. Receiver clearance was checked at center and twin positions in both LODs.

The current autocannon and MG modules have a recoil joint. Laser, PPC and missile
modules have no mechanical recoil. Firing visuals continue to use the existing
resolved attack, burst timing and weapon profile. Ballistic travel follows the
existing caliber/rack-size recoil scale, not damage actually inflicted on a target.
The fixed attachment must never slide away from the chassis. Existing older modules
retain their previous animation behavior.

The building turret's `building-turret-v1` rig has separate `root` and `turret`
roles. Only the bearing yaws; weapon aim handles elevation. Its visual heading
remains at the last firing direction and survives rebinding/LOD changes. This
does not modify legal firing arcs, entity facing or damage.

## Appearance and LOD

- Identify weapons through silhouette: stepped cannon, ventilated laser housing,
  heavier PPC chamber, distinct missile cassette and exact rack layout.
- Keep laser/PPC apertures neutral and unlit at rest. Their firing effects supply
  the beam/discharge color. Small head optics and equipment searchlights are separate.
- Put `-lod0` and `-lod1` groups in one GLB, with identical logical node IDs and
  joints in both groups. The loader separates LODs before validating IDs.
- Preserve launcher cell counts, attachment surfaces and emitters in LOD1. Remove
  tiny bevels, service details and hidden surfaces first. LOD2 currently reuses LOD1.
- Texture `authored/<name>` material roles with board materials: the GLB references
  the role's albedo map (`structures/turret/turret-<role>_albedo.png`, made in Material
  Maker from `mm-data/tools/materials/structures/turret`) and never embeds images; the
  `.tres` beside it gives the tint, finish and normal map. Existing `paint`, `detail` and
  `bark` roles retain their prior contracts. Prefer single-sided closed meshes.
- Report assembled triangle counts, not just body counts. Dense batteries in this
  first kit exceed the existing 5,000/2,000 soft targets; see the measured asset table.

## Current integration boundary

`BuildingTurretVisual` is an explicit recipe assembler using `UnitEquipmentAssembly`,
the existing catalogue, bindings, effect emitters and LOD machinery. It supports
the authored AC (four calibre tiers chosen by rack size), MG, laser, PPC and standard
SRM/LRM profiles. Unsupported equipment
or an unfit loadout fails the new assembly so the existing model loader can fall
back; it must not silently hide or substitute a weapon.

The native palette test uses the production loader, assembler, shader and animator.
Automatic replacement of gameplay emplacements, grouping multiple building turrets,
fixed/pintle distinctions, wall/support placement and board-to-unit scale conversion
remain integration work. Existing Mek/vehicle selections are unchanged. Extend their
authored profiles and sockets through this same format when that art is ready.
