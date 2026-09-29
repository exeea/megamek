# Orchard trees

`WOODS` with `FLUFF:12` selects the orchard models in the shared 3D board scene.
The marker takes precedence over ordinary biome trees and level-one shrubs;
jungle retains its own vegetation. Removing woods removes the trees even if the
cosmetic fluff remains. The hex's `FOLIAGE_ELEV` still determines their height.

Light orchards contain six trees in two planted rows that align across adjacent
hexes, heavy orchards nine, and ultra-heavy orchards sixteen. Coordinates
deterministically select and rotate the variants. Road clearance uses the same
trunk relocation as other woods.
Orchard fluff allows native ground materials; the existing flat-foliage artwork
remains available for the board's flat-tree presentation.

The six forms are round, spreading, upright, vase, leaning, and young. Each has
its own mesh and image-generated atlas, with a snow-covered counterpart that
reuses the existing snow texture. Near and middle meshes have hanging fruit;
the far mesh retains the fruit colour in its canopy texture. Fruit uses solid
lighting, while leaves retain the existing foliage lighting.

| Forms (including snow counterparts) | Near triangles | Middle triangles | Far triangles |
| --- | ---: | ---: | ---: |
| Round, spreading, upright, leaning, young | 408 | 216 | 72 |
| Vase | 388 | 196 | 64 |

All levels share a ground origin and a 30-unit authored height. The existing
`GpuTreeInstances` and `TreeLod` select the meshes at 80/24 framebuffer pixels
with 10% hysteresis, including the colour, depth and shadow passes. The atlas is
shared across parts and LODs through the existing texture cache.

Assets live in the sibling `mm-data` repository:

- `data/models/board/orchard-*.glb`: twelve models, three LOD nodes per file.
- `data/models/board/textures/foliage/orchard/orchard-*.png`: six unmodified
  1254×1254 opaque atlases generated with the built-in ImageGen tool.
- `tools/orchard-texture-prompts.json`: the exact six generation prompts and
  atlas layout (canopy, leaf, bark, apple skin).
- `tools/build_orchard_assets.py`: reproducible geometry builder, reusing the
  existing foliage primitives and GLB exporter.
- `tools/board-orchards.blend`: editable geometry, with relative texture paths.

Rebuild through background Blender:

```text
blender --background --python tools/build_orchard_assets.py
```

The focused checks are `BoardOrchardTest`, `BoardFeaturesTest`,
`BoardFoliageTest`, `TreeLodTest`, and the opt-in `GpuOrchardSmokeTest`.
The native test measures submitted geometry across all three passes, verifies
shared textures and stable picking, and captures top, isometric and perspective
views under `megamek/build/gpu-board-review/orchards`.

Targeted asset validation confirms all twelve GLBs, their triangle budgets,
root/height agreement and texture dependencies. The full board catalog validator
currently stops on the unrelated `bridges/bridge-exits-31.glb` (708 triangles
against its generic 500-triangle limit).

Verification on 2026-09-28:

- `BoardOrchardTest`: 10 passed, including planted-row alignment across staggered
  columns, cover removal, road clearance, snow selection and all shipped LODs.
- `BoardFoliageTest`: 20 passed; `TreeLodTest`: 2 passed.
- `GpuOrchardSmokeTest`: passed for all twelve models at 23:15 local time,
  including native colour/depth/shadow counts, texture sharing, picking and
  top/isometric/perspective captures. This was before the final row-spacing
  adjustment, which is covered by the CPU row-alignment test.
- The final native rerun at 23:27 was blocked during terrain shader creation by
  concurrent shared shader changes (`GPU terrain fragment`: four implicit
  `vec4` to `vec3` casts). No orchard geometry was drawn in that rerun.
- The latest broader `BoardFeaturesTest` run has six failures for terrain 43
  (`BLDG_BASE_COLLAPSED`) following concurrent native-ground changes; its other
  fifteen cases pass. This change leaves those unrelated edits intact.

The model gallery below is a Blender render of the shipped authoring geometry;
the board captures are in the test output directory above.

![Six orchard tree forms](images/orchard-models.png)
