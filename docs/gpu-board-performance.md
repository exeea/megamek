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

An edited hex rebuilds the hexes within its reach (`GpuTerrain.editReach`): two
rings for dry relief, features, ice, biome or ground cover; three for a level,
ground or road edit within six hexes of liquid, and for a pool that only grows
deeper or shallower while staying at least a level deep, since beyond a level the
shore field and river channels read no depth; `BoardSurface.SHORE_RINGS` (six)
where liquid appears or disappears or a neighbour's shore class changes. Changed
river currents and bridge spans add their own hexes. The on-demand
`GpuTerrainEditReachSmokeTest` applies and reverts edits incrementally on shipped
boards (`megamek.gpu.reach.boards`, `.samples`) and fails when a hex differs from
a full build or changed without being rebuilt. It leaves road masks' atlas slots
out of that comparison, since each build packs a section's masks afresh.

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

Building hover and unit cutaways retain the chunk's complete opaque source in
the static page. While a chunk is opened, its original ranges are hidden and its
existing cutaway cache is drawn separately. Moving the pointer between storeys
must not recopy the entire surrounding page. Colour, depth and shadow passes
still use their existing cutaway rules; `GpuBuildingHoverSmokeTest` checks both
the visible storey and the unchanged page-build count.

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

## Classic and Enhanced graphics

The 3D Graphics setting is a build mode of the same chunk pipeline ([GPU board](gpu-board.md#graphics-classic-and-enhanced)).
A Classic build prepares each hex's tileset column on the terrain workers (`GpuTerrain.prepareColumns`) and composes its
art in the update plan's texture pass; it builds no relief, sculpted material, water or lava field, wave simulation,
plant, road, biome map or ground cover, and its chunks have one level of detail, so the camera never refines them.
Enhanced builds no Classic data (no tileset decals, sprites or column art). A switch either way is a full rebuild
behind the loading stage; it gets the opening's 8 ms upload slice per frame instead of camera refinement's 2 ms.
The Tactical View hides fluff at draw time (`GpuTerrain.setFluffHidden`): the 3D prop caches and their pages stay,
and each chunk's few kept props are packed once on the view's first draw (`Chunk#keptProps`). Recaching every chunk's
props with the keep rule instead (the design's first plan) cost about 1 s more on entering the view on Valencia
(longest frame 1.9-2.3 s against 0.7-1.3 s before, RTX 4070) and rebuilt the prop pages both ways.

Measured 2026-10-12 on the user's laptop (RTX 4070 Laptop with `-Dmegamek.gpu.card=DISCRETE`, Intel Iris Xe with
`INTEGRATED`), 1920x1080 hidden window, `glFinish` per frame, 15 warm-up and 30 sample frames, before = 79273ff075,
after = this change, in `GpuBoardPerformanceSmokeTest.measuresTheCompleteBoardViewWithLiveCapture` with
`-Dmegamek.gpu.performanceGraphics` (one JVM loads one graphics, then switches to the other) and
`-Dmegamek.gpu.performanceTactical=true` (overview, close and moving in the 3D view, then in the Tactical View). Other
sessions' builds and, for long stretches, an interactive MegaMek client on the Iris Xe shared the machine: a scenario
during which a foreign Gradle test JVM ran, or other processes put 10 % or more 3D load on the card, is left out.
Ranges over 1-6 runs (NVIDIA) and 1-4 (Iris Xe). "Load" is the first frame to three frames without a terrain job;
memory is the used heap after `System.gc()` and the Windows GPU process memory (dedicated on the NVIDIA card, shared
on the Iris Xe) after load.

| Board | Loaded as | Load s, before / after (NVIDIA; Iris Xe) | Heap MiB | GPU MiB (NVIDIA; Iris Xe) |
|---|---|---|---|---|
| Valencia 128x192 | Classic | 64.1 / 28.4; 43.2 / 13.2 | 490 / 391-392 | 5512 / 1193; 6677 / 1278 |
| Valencia 128x192 | Enhanced | 53.9-78.3 / 63.7; 47.0 / 42.8 | 444 / 444 | 5616-5628 / 5556; 7214 / 6882 |
| MesaCity1 96x102 | Classic | 17.1-34.3 / 10.5; 16.7 / 5.0 | 231-232 / 211 | 1400-1402 / 440; 1926 / 402 |
| MesaCity1 96x102 | Enhanced | 23.0-29.9 / 20.8; 16.6 / 15.2 | 206-207 / 206-207 | 980-1669 / 1243; 2187 / 2144 |
| 48x48 lake | Classic | 19.5-19.9 / 7.7; 13.9 / 5.4 | 254-255 / 227 | 1247-1262 / 228; 1515 / 461 |
| 48x48 lake | Enhanced | 13.8-19.9 / 20.0; 21.4 / 15.8-20.3 | 251-252 / 251-252 | 981-1276 / 875; 1650 / 1354-1733 |

- Runtime switch on Valencia (settle / longest frame, NVIDIA; Iris Xe): Enhanced to Classic 4.5-7.9 / 4.2-7.3 s;
  4.1 / 3.6 s before (the 3D terrain stayed and the columns were built on the render thread) against 15.2 / 1.0 s;
  11.6 / 1.8 s after. Classic to Enhanced 3.8 / 3.2 s; 3.0 / 2.4 s before (the 3D terrain was already built) against
  85 / 3.6 s; 42.6 / 2.5 s after, a full build behind the loading stage. After a switch the other build's chunks are
  retired over the next frames: the Valencia heap after a switch to Classic reads 549-552 MiB in the first scenario
  and settles at 420-421 MiB by its Tactical View scenarios (a Classic load: 391-393), after a switch to Enhanced
  447-450 MiB (an Enhanced load: 444). A switch does not free `GpuAssets`: it keeps the Enhanced texture arrays (sculpt,
  road, magma) and the loaded models until the renderer closes, since the print and the editor previews borrow it.
  GPU memory after a switch was not sampled once settled (the last samples fall in the build or the shutdown).
- Entering the Tactical View on Valencia (settle / longest frame): NVIDIA Enhanced 0.9-1.6 / 0.7-1.3 s before,
  1.0-1.4 / 0.9-1.1 s after; Iris Xe Enhanced 1.3-1.4 / 0.7-0.9 s before, 1.4-1.6 / 0.9-1.5 s after; Iris Xe Classic
  0.9-1.4 / 0.7-1.2 s before, 0.5-0.7 / 0.4-0.5 s after.
- Frame time: the Tactical View's Valencia overview dropped in both graphics on both cards (median ms, before / after:
  NVIDIA Enhanced 24.8-42.0 / 15.8-24.7, Classic 20.4-41.0 / 16.8-18.3; Iris Xe Enhanced 71.4-72.3 / 42.3-47.1,
  Classic 45.8-46.3 / 23.5-24.9), as did the Iris Xe's 48x48 Classic Tactical View (overview 21.6-24.7 / 9.7-19.9).
  The other cells whose ranges do not overlap go both ways, by up to about 30 %, among them 3D cells whose drawing
  this change does not touch (Iris Xe Valencia Enhanced overview 81.0-81.1 / 82.1-105.0, close 17.6-19.2 / 20.0-22.1),
  and within a run the stages it does not touch (HUD, overlays, atmosphere) move by the same factor: that spread is the
  machine's state. Classic 3D close views read slower after (Valencia NVIDIA 7.6-12.0 / 12.3, Iris Xe 7.8-9.2 /
  10.6-11.6; MesaCity1 Iris Xe 6.5-8.0 / 8.8-9.0) with the same draws and vertices; in the Valencia Iris Xe pair the
  terrain's own GPU time rose 14 % against 23-24 % for the untouched stages, so no cost of the change is shown there.
  Entering the Tactical View packs each chunk's kept props once; later entries reuse them (the harness times the
  first).
- CPU preparation (`gpuTerrainLoadBenchmark`, Valencia region 64,16,32,32, 8 workers): the Enhanced chunk checksums
  are unchanged (DISTANT `f1c708af`, FULL `35b92066` before and after); a Classic build prepares the columns only,
  18-25 ms for the 1024 hexes with the column checksum unchanged (`d374c543`), where before it prepared the 3D chunks
  as Enhanced does (1.2-2.3 s per level) and built the columns on the render thread at the first Classic frame.

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
