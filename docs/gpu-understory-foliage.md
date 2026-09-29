# Level-one foliage

Level-one woods and jungle use eight purpose-built understory GLBs. `BoardFeatures`
chooses a family from the existing terrain and theme. `GpuTerrain` scales these
models uniformly to the foliage height, preserving their authored proportions.
They use the existing tree placement, asset cache, instancing and colour/depth/shadow
LOD selection. Taller woods continue to use the tree catalog.

The meshes and their textures are in the sibling `mm-data` repository:

- `data/models/board/foliage-{family}.glb`: three meshes under the existing
  `foliage-{family}-lod0`, `-lod1`, `-lod2` node convention.
- `data/models/board/textures/foliage/shrubs/{family}.png`: original, opaque
  1254 × 1254 botanical atlases generated with built-in ImageGen.
- `tools/foliage-texture-prompts.json`: the full prompts and atlas panel layout.
- `tools/build_foliage_assets.py`: Blender authoring and export through the existing
  `glb_geometry.write_glb` helper. This was executed through Blender MCP.
- `tools/board-foliage.blend`: one editable scene containing all 24 meshes, with
  relative texture paths. The scene layout does not affect exported root coordinates.

## Families and triangle counts

| Family | Selection | LOD0 | LOD1 | LOD2 |
| --- | --- | ---: | ---: | ---: |
| Temperate | Grass and paved parks | 460 | 236 | 92 |
| Highland | Elevated grass or tundra | 460 | 236 | 92 |
| Rocky | Rock and volcanic themes | 460 | 236 | 92 |
| Wetland | Swamp, mud, water or dirt | 460 | 236 | 92 |
| Desert | Sand or desert theme | 478 | 238 | 94 |
| Jungle | Jungle terrain | 472 | 228 | 96 |
| Barren | Mars and lunar themes | 456 | 232 | 96 |
| Snow | Snow terrain or theme | 460 | 236 | 92 |

Snow takes precedence; jungle then selects its own understory, followed by desert,
barren and wetland overrides. Existing light/heavy/ultra-heavy counts and deterministic
placement remain shared with the tree path. Foliage is only added for actual woods or
jungle terrain.

All roots reach local Z=0. LOD0 is 18 units tall; lower LODs share its coordinate system
and uniform normalization. The smallest cactus LOD is 17.77 units tall. Atlases have
separate canopy, leaf/skin, bark and accent panels with inset UVs. Each family borrows
one texture across all parts and LODs. Folded leaf geometry has explicit back faces.

## Desert correction

The prickly pear has eleven thick pads on branches extending in both horizontal
directions. Each pad has its own orientation. Child bases overlap their parent's rim;
Blender Boolean unions produce one welded, closed body, including the basal stem.
This topology is checked after every union and after simplifying each LOD. A localized
correction restores the basal stem's ground contact after simplification without
moving the crown. The exported GLBs are also tested after welding position-equivalent
UV/normal seams: every edge has two faces and every pad belongs to one connected body.

## Verification on 2026-09-28

Passed `BoardFoliageTest` (20 cases), `BoardFeaturesTest` (21), `TreeLodTest` (2), and
the native `GpuShrubSmokeTest` (1). The native check loads all eight textures, verifies
uniform placement, and measures submitted geometry while selecting LOD0/1/2/0 in the
colour, depth and shadow passes. OpenGL reported no error. Isometric, overhead and
perspective captures are in `megamek/build/gpu-board-review/shrubs-{0,1,2}.png`.
The corrected cactus angle review is `build/foliage-review/cactus-spatial-final.png`.
Checkstyle and Spotless passed for the two new foliage tests. Reopening the saved
Blender source confirmed one scene, 24 meshes, eight valid relative texture paths,
and closed, grounded cactus meshes at all three detail levels.

Re-run from the client repository:

```powershell
.\gradlew.bat :megamek:test --tests '*BoardFoliageTest' --tests '*BoardFeaturesTest' --tests '*TreeLodTest' :megamek:gpuBoardSmoke --tests '*GpuShrubSmokeTest'
```

`mm-data/tools/validate_board_assets.py` now recognizes the shrub LODs and atlas paths.
The full catalog validation remains blocked by the existing
`bridges/bridge-exits-31.glb`: it contains 708 triangles against that validator's
500-triangle limit. No bridge asset was changed for this work. These checks do not
claim a full gameplay test or a performance improvement.
