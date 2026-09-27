# Unit meshes converted directly from Git HEAD

Source revision in `mm-data`: `a0bb82f65a62cd325c4f137887180dbe50ba7d01`
(`Seat MiniMek weapons on the outer skin`).

All unit G3DJ files at that revision were retrieved using Git's object database
into `tmp/unit-head-restore/head/`. Conversion used those files directly with
`glb_geometry.write_glb`; no procedural unit generator was executed.

| Catalog | HEAD G3DJ sources | Resulting GLBs |
|---|---:|---:|
| Deployed unit components | 723 | 718 |
| Historical, verification and preserved unit references | 453 | 453 |
| Total | 1,176 | 1,171 |

Five optional detail meshes stay inside their own component's GLB as LOD1,
preserving the current packaging and source descriptor relationships. Phoenix
Hawk and Phoenix Hawk IIC remain separate units. Current JSON rigs, loadout
mappings and custom additions were retained. Two mesh manifests were updated
with the converted file hashes and counts.

## Comparison with the GLBs already present

No authored geometry difference from HEAD was found. This includes Atlas,
Archer, the battle-armour figures, all 20 weight-class fallback bodies and all
25 family bodies. Triangle corners, normals, UVs, alpha, rigid node hierarchy,
translations, part bindings and material IDs match. At float32 runtime precision,
positions, normals, UVs, alpha and translations are identical (signed zero is
the same coordinate). The largest RGB difference is `5.9604645e-8`, from glTF's
linear-colour conversion, far below one 8-bit colour step.

This comparison concerns the committed HEAD artwork. The retrieved sources and
the previous GLBs are retained under `tmp/unit-head-restore/head/` and
`tmp/unit-head-restore/originals/`, respectively. The detailed report records
each source path, source SHA-256, target file and LOD.

## Applied conversion and verification

- All 1,171 unit GLBs were freshly converted from the retrieved HEAD sources.
- All pass Khronos validation with zero errors and warnings.
- Independent comparison through libGDX's G3DJ loader and the runtime GLB
  importer passed for 1,176 mesh levels, 254,856 triangles and 764,568 corners.
- The same comparison passed again against the applied `mm-data` files.
- All 28 focused loader, equipment-assembly and formation tests passed. Both
  native GPU reviews passed, covering GLB assembly, fallback families, animation,
  camouflage, damage, equipment and formations. Gradle completed successfully.

The user requested leaving generators enabled if the complete comparison
matched. No blocker was installed. The modular and legacy generators, the
PowerShell launcher, and the named-unit/fallback-family build modules retain
their pre-task hashes. Temporary blocker drafts were discarded.
