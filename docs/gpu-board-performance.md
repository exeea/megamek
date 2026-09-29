# Terrain updates, detail and render caches

Most rendering work is coordinated by `GpuTerrain`, but each cache has a different
owner and invalidation boundary. Use this guide when a view rebuilds too often,
stutters during edits, or draws stale geometry.

## Preparation and replacement

`BoardSource` publishes immutable scene/frame data. `GpuTerrain` captures that
scene and `TerrainSettings` in a request, prepares CPU geometry on workers, and
collects/uploads the result on the GL thread. Generation checks reject superseded
requests. A worker must not read changing Swing state or allocate GL resources.

An edit plan identifies changed tiles plus their geometric dependents. Shared
corners, road/bridge spans, shore mouths and fields can reach beyond a single hex
or chunk. Reuse unchanged tile meshes where their inputs still match, and replace
the affected installed resources together. A new scene object alone is not a
reason to rebuild everything.

Camera refinement and local edits use the same bounded preparation pipeline.
Collection/upload is spread over frames, but the frame budget is soft: an individual
tile collection or buffer upload can overrun it. Retired chunks are disposed by
the GL owner once they are no longer installed or cached.

The opening screen retains the overall percentage of completed map sections and
lists the current task for each in-flight section in map order. Task percentages
and counts measure completed work (hexes, shapes, buffers or discrete operations),
not estimated time. The elapsed seconds belong to that task, including any wait
for its worker or the GL thread. Preparation, vegetation, roads, water fields,
models and mesh uploads have separate labels. Each job owns its progress and
publishes immutable snapshots; superseded jobs cannot overwrite a newer build's
status. Display updates occur between frames, so a single synchronous GL operation
can still pause the display until it returns.

## Detail and support are different representations

`TerrainLod` chooses detail from projected size. Board dimensions do not determine
quality. Chunk refinement preserves shared boundaries so neighboring detail
levels meet. The bounded detail cache can retain a previous chunk representation
for a return to its scale.

`BoardSurface` and the finished tactical/support surface remain the basis for
picking, unit contact and vegetation placement. Rendering may simplify parts such
as a water bed within an error bound; it must not replace the canonical support
with an unrelated approximation. `TreeLod`, `MeshLod` and `FormationLod` select
their own tree, rigid-unit and formation representations.

The CPU geometry cache is bounded. Installed tiles keep weak links to their
finished support so a consumer already retaining that surface can reuse it after
cache eviction. Do not add a second strong cache for every tile to fix a repeated
lookup. Ground cover is planted by the same worker on that support and installed
with the tile; see [fields and marsh](gpu-fields-marsh.md#one-pipeline-for-ground-cover).

## Opaque drawing and pages

| Owner | What it combines or reuses |
| --- | --- |
| `GpuTerrainBatch` | Compatible terrain material state during opaque submission. |
| `GpuOpaqueSorter` | Ordering of opaque renderables to keep compatible work together. |
| `GpuPropBatch` | Static opaque prop ranges with compatible state. |
| `GpuTerrainPages`, `GpuMeshPage` | Small ranges from nearby chunks in persistent page buffers. |
| `GpuTreeInstances`, `GpuInstancedMesh` | Repeated models/transforms through instanced draws. Every chunk's trees stay uploaded per species and detail level in board order; a pass draws the visible chunks' ranges, so panning, zooming and detail changes upload nothing. Building parts still gather per pass. |
| `TerrainLod` | Ground sampling per level, chosen by projected hex size (64/24/6 px). Flat natural ground costs 573 faces per hex at the near level (468 top, 105 rock outcrops) and is primitive-bound long before it is fill-bound: a half-size window barely changes its frame time. Two cheaper near levels were measured and rejected: 8 edge samples break the halving that keeps mixed-detail seams closed (12/6/3), and two interior rings at 12 samples (324 faces, about 10% less ground time) fold a top triangle in `BoardSculptTest`'s padded and transition scenes. Doubling the thresholds instead gains little and adds a rebuild hitch when a zoom crosses a level. The remaining lever is a flat-hex fan in `BoardRelief` for hexes whose relief and shading are uniform. |
| `TreeLod` | Four plant levels by projected diameter (80/48/24 px, 10% hysteresis): the near mesh, two decimated meshes and, below 24 px, an impostor of 12 triangles: two crossed vertical cards and a cap through the crown, textured with unlit renders of the near mesh from the front, side and above (`mm-data/tools/prepare_tree_lods.py -- impostors`). The cards are ordinary alpha-tested geometry, so they shadow, write depth and pick like the meshes and read correctly from the top view, where only the cap shows. Far and middle views were primitive-bound (halving the window changed frame time little), so fewer triangles, not cheaper shading, is what pays there. A tree-only vertex shader without the scene shader's per-vertex light loops, shadow projection and fog was tried and measured identical (the driver already discards that unused work), so trees keep the shared vertex shader. |
| `GpuTerrainDepth` | Depth-only ranges borrowing finished chunk meshes and materials. |

Pages receive the source ranges from invisible chunks as well as visible ones.
Panning changes which existing ranges are selected, not the page's geometry.
Only replacement source ranges invalidate a page. One bounded page is built per
frame; original ranges draw while pending pages are prepared.

Page grouping is used for orthographic rendering. Perspective keeps the original
ranges and their projected-detail uniforms. Large meshes and ineligible material
states also remain separate. Do not merge transparent, cutout, instanced or
unusual depth-state work just because its texture matches.

Depth snapshots copy no vertex data. They must be recaptured when the owning
chunk's meshes, transforms or relevant material state change. Their borrowed
resources cannot outlive that chunk.

## Shadows, depth and animated fields

`GpuTerrain` can reuse a static terrain shadow when coverage, light direction and
terrain are unchanged. Moving units are drawn over the retained terrain shadow.
Both color and depth attachments must be restored: retaining only color leaves
incorrect depth for moving casters. Terrain changes, shader changes and unsuitable
shadow-camera coverage invalidate the cache.

`GpuAtmosphere` reuses the captured scene depth for fog, shafts and composition.
`GpuEffectDepth` supplies depth to effects that need it. These are distinct from
the light's shadow map. An animated cloud field changes direct illumination
without requiring a new geometry shadow.

Wind, water waves, cloud motion and most lighting changes update existing uniforms
or textures. Grass and plant buffers depend only on the installed plants of their
chunk; camera movement and time should not upload them again.

## Choosing the right place to investigate

If a stationary view rebuilds terrain, follow request equality, generation and
support-cache misses. If panning rebuilds page buffers, inspect source-range
identity and page membership. If geometry is correct but old flow, road masks or
biome appearance remain, inspect replacement material bindings and chunk-owned
textures. If an edit loses newer work, inspect the generation check at installation.

More triangles, more submissions, CPU preparation and GPU execution are separate
costs. A change to one does not establish a whole-frame improvement. Keep runtime
diagnostics in the existing rendering paths and keep measured claims scoped to
what was actually measured.
