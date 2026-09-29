# Terrain geometry and materials

[Docs index](README.md) · [Class responsibilities](gpu-code-map.md)

The native board separates game terrain, finished geometry and shading. The game
provides levels/types/exits; CPU builders derive a shared surface; shaders add
material detail. Geometry changes affect picking and support, while texture relief
changes only appearance.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardGeometry` | Board/world conversion, common surface queries, bounds and picking integration. |
| `BoardSurface` | Finished topography: plateau, road approaches, water bank/bed and surface-role data. |
| `BoardRelief` | Canonical corners, stepped profiles, cliff rows, transition bands, shore shapes and rock placement. |
| `BoardConcrete` | Constructed slab outlines, retaining walls and joins. |
| `BoardRocks / BoardShape` | Reusable rock CPU meshes; placement stays with the feature/relief builders. |
| `BoardRough` | Authored rough variants whose drawing, picking and support use the same placement. |
| `GpuTerrain` | Worker preparation, installed chunks, resource ownership and rendering. |
| `TerrainLod` | Projected-size render sampling; board dimensions do not select quality. |
| `GpuAssets / terrain-sculpt.frag` | Material resources and the main geological surface program. |

## Canonical geometry

Anything adjacent meshes share must be derived from the same board data and world
position. Corners use an integer lattice; all three adjoining hexes compute the same
corner. The higher hex owns a cliff and its foot coincides with the lower boundary.
Rows at fixed absolute heights let cliffs of different spans meet without cracks.

`BoardRelief` combines an inset face, broad buttresses, bedding/joints, a caprock rim
and a talus foot. Its corner fillets and transition bands must preserve a usable top
and shared edge samples. Decorative displacement remains bounded; picking bounds must
include the permitted horizontal overhang and vertical headroom.

Grass/dirt and snow use soil/cover mantles on small steps, exposing rock on taller
faces. Sand and rock retain steeper geological profiles. Concrete stays constructed:
up to two levels it is a cast wall; taller differences expose bedrock beneath the top
slab. `BoardConcrete` also owns the fitted corners used by adjacent material contacts.

Natural hex transitions reserve room on each side of a step for the slope or cliff
and its foot. Roads, buildings and special artwork have additional boundary constraints.
Hex padding uses the same step-room machinery; it does not enlarge the lattice.
When altering these controls, follow `BoardRelief.stepRoom`, corner/edge sampling,
surface construction and bounds together.

## Shores, ramps and support

Water geometry also participates in canonical corners and cliffs. `BoardSurface`
owns the bank/bed and water surface; `BoardRelief.bank` connects the shoreline to the
shared terrain boundary. Water-to-water drops keep the seam needed by the falling
surface. A land-to-bed difference includes the water depth when deciding whether the
bank behaves like a small slope or a taller cliff.

Road approaches change the finished surface, not just its texture. The rest of a
roadside cliff keeps the native relief. `BoardRampMesh` can simplify interior samples,
but must preserve shared mouth/edge vertices and the actual grade. See
[roads](gpu-roads.md) and [water](gpu-water.md) for their connection rules.

Trees, rocks, vegetation and units must sample the installed finished surface.
Dressing such as a tree pit can be drawn/picked with the ground without raising the
support height. Keep support roles distinct when adding a decorative layer.

## Material roles and textures

Each natural family combines ground cover, soil/mantle, exposed rock and deposits.
`GpuTerrain.SCULPT_MATERIALS` selects each family's maps; `GpuAssets` loads and
owns the shared textures. Active sculpt maps are under
`mm-data/data/models/board/textures/sculpt`.

Sculpt albedo textures store height in alpha; their tangent-normal partners store
ambient occlusion in alpha. Repeat scales come from the asset metadata.
World-space projections keep coordinates continuous across hexes. Two differently
oriented ground samples reduce repetition; walls use compatible vertical projections.
Slope and height-aware blending determine how cover gives way to mantle, rock and scree.

`terrain-materials.glsl` owns that common evaluation.
`terrain-projection.glsl` owns projection helpers, and `terrain-concrete.glsl` handles
constructed slab appearance. The same role weights must affect color, normal, cavity
and roughness together. Cross-family contacts are described in
[terrain contacts](gpu-terrain-contacts.md).

For a change in silhouette or support, edit CPU geometry. For grain, wet response,
material breakup or per-level color grade, edit the relevant material helper and its
maps. Editing a normal map will not correct a geometric seam.

## Lighting, grading and inspection

`BoardAtmosphere` supplies linear light. Lit shaders decode albedo, apply the common
sky/direct/ground-bounce model and encode display color once.
`light-model.glsl` and `surface-lighting.glsl` are shared by terrain and other lit
surfaces; introducing a terrain-only light multiplier would make those surfaces disagree.

Per-level grading belongs to the terrain material path and changes continuously down
cliffs. Final exposure and highlight treatment belong to `GpuAtmosphere`'s composite.
Rain response uses the shared surface helpers; snow retains its separate handling.

The clay view in `GpuTerrain.setClay` helps isolate geometry/light from materials.
The normal/relief toggle controls material detail. `GpuBoardTuning` and
`TerrainSettings` carry geometry settings through capture/build/install so displayed
frames do not mix results from different settings.

## Authoring and related features

The [board asset format](../../mm-data/data/models/board/README.md) owns dimensions,
mesh roles and editable source locations. Runtime loads the generated models/maps;
offline tools in mm-data author them.

Use the dedicated guides for [rough](gpu-rough.md), [fields/marsh](gpu-fields-marsh.md),
[grass](gpu-grass-rendering.md), [buildings](gpu-modular-buildings.md) and
[terrain fire/smoke](gpu-terrain-fire-smoke.md). Their builders share the surface and
resource boundaries described here.
