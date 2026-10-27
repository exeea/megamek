# Modular weapon assets

Use the existing schema 2 GLB + JSON equipment format for turrets, Meks and vehicles.
The shared equipment catalogue resolves the real equipment identity to an art profile;
the chassis supplies its hardpoints and installation settings. Do not add another
weapon registry, derive gameplay rules from mesh names, or merge separate weapons
into one binding because their housings touch.

## Coordinates and installation

Author Z-up, +Y forward. Front-mounted unit modules have their rear mounting datum at
Y=0; turret weapon modules are centred on their trunnion. Export glTF Y-up, -Z forward;
`RigidGlb` performs the existing conversion once. JSON positions and bounds use the
engine's Z-up convention.

Use a consistent physical scale within a chassis/profile family. The current turret
profile uses board units; existing low-poly unit profiles use their existing scale.
Do not mix these profiles without an explicit size conversion and fit review.

Encasement applies to **all ranged weapons**. It is an installation property,
independent of weapon identity, damage, caliber and rack size. A turret weapon is one
GLB for every installation: `encased: false` on a roof mount (`turret-exposed` profile)
or `encased: true` in a front bay, which supplies the enclosing armored body. A gun's
module may carry a `receiver` node, shown only on the roof, and a `mantlet` node, shown
only in a bay; a planned mount's `omit` lists the nodes its installation leaves out,
and the fitter sizes the module without them. Translate a weapon backward to adjust insertion;
do not shorten it with nonuniform scaling or reduce the missile face to fit a deeper bay.

Authored **SRM/LRM launchers** default to exposed and, encased, sit with 75% of the
module's longitudinal length inside the host. Turret recipes use
`"installations": {"center": {"encased": true}}`. Numeric `insertion` is rejected for
these launchers. Both modes retain the complete rack layout, cell spacing, emitters and
scale in each LOD.

Other supported ranged weapons default to a front bay, their barrel leaving the face at
the socket (the module's `face` hardpoint), with adjustable `insertion` in the module's
authored units:

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
Logical socket keys stay stable when a weapon moves to its roof mount. Numeric
insertion is rejected on roof mounts. Any mix assembles: the roof holds two guns (a
third moves into a front bay) or up to four launchers, side arms take launchers, and
what a carrier cannot hold moves into a front bay. A roof gun above front bays stops
depressing at 6 degrees, clear of their barrels. Low auxiliary sockets remain available.

The receiving chassis must provide enough internal space and leave the muzzle or
launcher face clear. A bounding box alone cannot describe that cavity. For now,
choose and inspect the installation per chassis. Automatic cavity fitting and
shader clipping are not implemented; only the named `omit` nodes are left out.

This physical insertion is separate from the existing `Binding.embedded` fallback
flag. Recessed weapons still have their own mesh, binding and articulated emitters;
they are not the legacy emitter-only representation on a chassis surface.

## Articulation and effects

New articulated equipment uses `rig: "equipment-v1"` and these joint roles:

```text
module                    root: installation datum
  aim                     optional aiming joint
    collar                collar/axles, stationary during recoil
    receiver              turret gun's roof-only body, stationary during recoil
    mantlet               turret gun's bay-only shield, stationary during recoil
    recoil                optional barrel/breech joint
      geometry
      spin                optional rotary barrel cluster, turning about local +Y
```

For a weapon without recoil, geometry belongs directly to `aim`. A muzzle emitter
belongs to `spin` when present, else `recoil`, else `aim`. A `spin` joint (rotary
autocannons, hyper-assault Gauss rifles) runs up before the first round, turns through
the burst and runs down to a whole turn by the end of the attack (`UnitAttack.spin`). Use existing
`muzzle`, `beam` and `launcher` roles and effect names. Emitters describe local
position/direction, never a second copy of the weapon's game state.

Turret weapon modules also declare `elevation: "aim"`; this restricts their local aiming
to X-axis pitch about their trunnion. The bearing on the turret head supplies yaw. The
kit has a visual depression stop at -20 degrees and elevation stop at +25 degrees. These
do not change legal firing arcs.

The autocannon, artillery, Gauss, rotary and MG modules have a recoil joint. Laser,
PPC, plasma, flamer and missile modules have no mechanical recoil. Gauss rifles burn
no propellant: they fire with a blue-white electromagnetic discharge and a slug
streak instead of a muzzle fireball, smoke and tracer. Firing visuals continue to use the existing
resolved attack, burst timing and weapon profile. Ballistic travel follows the
existing caliber/rack-size recoil scale, not damage actually inflicted on a target.
The fixed attachment must never slide away from the chassis. Existing older modules
retain their previous animation behavior.

The building turret's `building-turret-v1` rig has separate `root` and `turret`
roles. Only the bearing yaws; weapon aim handles elevation. Its visual heading
remains at the last firing direction and survives rebinding/LOD changes. A wall
casemate's `building-casemate-v1` rig yaws its gun shield no more than 60 degrees
either side of its wall's normal, the 120-degree arc of a wall weapon, and rests facing
straight out. None of this modifies legal firing arcs, entity facing or damage.

Each turret weapon has a trunnion boss on each side (its `trunnion` hardpoint at the boss
tip). Shared mounts carry exposed weapons, their pins entering the bosses: `mount-post` on the
roof (a fork of two posts, the default, or one on the inboard side), `mount-pedestal` on the
roof (the weapon rests on its hinge pin and pitches about it) and, for launchers, `mount-arm`
(the side-arm bracket, symmetric top to bottom, so the left one is the right one rolled half a
turn). `BuildingTurretVisual` places them as fixtures of a planned mount from their `pin`,
`hinge` and `seat` hardpoints, and moves the weapon by the mount's points and its own (a
planned mount's `offsetX`/`offsetY`/`offsetZ`); the recipe's `roofMount` picks `posts`, `post`
or `pedestal`. The head yaws toward the shot, the mount turns with it, and the weapon pitches
toward where the shot lands, on the target or, for a miss, at the miss point.

## Appearance and LOD

- Identify weapons through silhouette: stepped cannon, ventilated laser housing,
  heavier PPC chamber, distinct missile cassette and exact rack layout.
- Keep laser/PPC apertures neutral and unlit at rest. Their firing effects supply
  the beam/discharge color. Small head optics and equipment searchlights are separate.
- Put `-lod0` and `-lod1` groups in one GLB, with identical logical node IDs and
  joints in both groups. The loader separates LODs before validating IDs.
- Preserve launcher cell counts, attachment surfaces and emitters in LOD1. Remove
  tiny bevels, service details and hidden surfaces first. LOD2 currently reuses LOD1.
- Give camouflaged surfaces (turret armor plates and covers) the `paint` role, which the
  player's camouflage paints. A `paint` role may be a board material, a painted plate: under
  camouflage its albedo only shades the paint (its light and dark against its mean), and its
  finish and normal map stay. Texture `authored/<name>` roles with board materials: the
  GLB references the role's albedo map (`structures/turret/turret-<role>_albedo.png`, or the
  shared `structures/common/steel_albedo.png` for gun steel, made in Material Maker from
  `mm-data/tools/materials/structures`) and never
  embeds images; the `.tres` beside it gives the finish and normal map. `detail` and
  `bark` retain their prior contracts. Prefer single-sided closed meshes.
- Report assembled triangle counts, not just body counts. Dense batteries in this
  first kit exceed the existing 5,000/2,000 soft targets; see the measured asset table.

## Current integration boundary

`BuildingTurretVisual` is an explicit recipe assembler using `UnitEquipmentAssembly`,
the existing catalogue, bindings, effect emitters and LOD machinery. Turret profiles sit
beside the Mek profiles in the same catalogue entry: `mm-data/tools/modular_turret_profiles.py`
maps each Mek weapon rule (`tools/unit-models/weapons.json`) to a turret module and takes
calibre and tube count from the rack size. That covers autocannons, rotary cannons, artillery,
Gauss, MG/AMS, lasers, PPCs, plasma, flamers, TAG and every launcher rack. Infantry weapons and
unsupported equipment
or an unfit loadout fails the new assembly so the existing model loader can fall
back; it must not silently hide or substitute a weapon.

The native palette test uses the production loader, assembler, shader and animator.
Gun emplacements and armed advanced buildings select the turret through `mekset.txt`'s
`default_turret` (`MekTileset.modelFor`, `UnitModelEquipment.armedStructure`); their generic
model stays the fallback for loadouts without authored profiles. Turrets are always 3D
models: the meeple display modes do not replace them (the Tactical View still shows
tokens).

An armed building covering several hexes is drawn hex by hex (`GpuBoardSource`), never
whole: each hex holding weapons is a part of its own, standing on that hex's roof; an
unarmed hex is only the board's building. Its attacks, the units held while they play
(`UnitPlayback`) and its wreck are those parts too: a shot at an unarmed hex has only its
impact point. A one-hex building is drawn whole. A hex's turret-mounted weapons (`(ST)`/
`(PT)`) ride one roof turret. Its wall weapons (a facing, 0-5 clockwise from the building's
front) fire from `head-casemate` (one gun) or `head-casemate-twin` casemates: alike weapons
pair up, a wall with more than two weapons holds several casemates side by side within 80%
of the face, and each sits at the middle of its floor with its back on the hex-edge facade
(`GpuUnitModel.walls`). The roof turret and casemates merge into one model per hex, and the
shot of a wall weapon leaves from its own hex's casemate. A building's own turret and
casemates do not open its walls as a unit inside would. No shipped building has wall
weapons; the test fort and large building do (`testresources/.../units`). Fixed/pintle
distinctions remain integration work. Existing Mek/vehicle selections are unchanged. Extend
their authored profiles and sockets through this same format when that art is ready.
