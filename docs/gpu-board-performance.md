# GPU board performance investigation

Latest review: [four-pass terrain LoD and Mission Y investigation](gpu-lod-mission-y-review.md), including live
panning capture, updated draw-call breakdowns and the limits of the FPS measurements below.

Measured locally on 26 September 2026, with Java 21 and an NVIDIA GeForce RTX 4070 Laptop GPU. The native benchmarks use a 1280×900 window, VSync disabled and no coverage instrumentation. The earlier latency measurements use `glFinish`; the frame-rate comparisons below measure successive render-frame starts without `glFinish`. These are local measurements, not hardware-independent FPS guarantees.

## Brandywine water and far-zoom regressions — September 27

The saved `unofficial/UlyssesMaps/200x200 Brandywine River.board` reproduced the long white-water section with
terrain LoD disabled. Shared rain-noise, water-detail and wave textures were bound only at shader start. Enough
distinct chunk-field textures evicted them from libGDX's LRU texture units while their sampler uniforms still
referred to those units. The water then sampled another chunk's field as foam/waves. Shared textures are now
touched through the existing binder for every material draw; resident bindings are reused. This also protects
the terrain shaders using the same water/rain inputs. No shader effect or water geometry was removed.

The near-plane bug was independent of LoD: an orthographic camera remained 10,000 world units from its focus even
when a tilted viewport covered more depth than that. Ground inside the lower screen crossed behind its near plane.
Its eye distance now includes the ground-depth span implied by viewport height, zoom and tilt. Screen framing and
world scale remain unchanged; no full-board scan or new culling system is needed.

The two terrain sliders are LoD thresholds, not obsolete board-size quality caps. They are now labelled
`Full detail at (px)` and `Medium detail at (px)`, with clearer screen-size descriptions, and disabled when LoD is
off. Defaults remain 64/24 pixels and LoD remains on. Hysteresis prevents switching repeatedly at a threshold.

Validation:

- The new camera regression fails before the fix with visible ground behind the near plane, then passes at
  overhead, 55-degree and 80-degree tilt across successive pans. All 40 camera and five terrain-LoD CPU checks pass.
- A native 45-water-chunk fixture exceeds the binder's maximum 32 slots. Before the fix its active water shader
  samples texture 40 in place of detail texture 2. Afterwards all three shared samplers retain the correct textures.
- The native tuning interaction/defaults check passes, including disabled/enabled sliders. All 24 existing frozen
  bed/water/waterfall shader fast-path comparisons pass exactly. These compare the fast path with its reference,
  not old corrupt water against corrected water.
- Both LoD-off and LoD-on full Brandywine runs pass without GL errors. Their overview captures show the river blue
  end-to-end, and the far-zoom/panned capture retains the whole map below screen center. These short runs validate
  the reported visuals, not an additional controlled FPS comparison.

Artifacts: `brandywine-lod-off-before.log`, `brandywine-lod-off-after.log`, `brandywine-lod-on-after.log`,
`terrain-textures-before.log`, `terrain-regressions-after.log` and `lod-profile-style.log` under
`build/gpu-performance`. Corrected captures are in `terrain-regressions/brandywine-lod-{off,on}-after/review`;
the old water capture is in `lod-profile/brandywine-lod-off-before/review`. The native regression is
`GpuTerrainTextureBindingSmokeTest`; the camera regression is in `BoardCameraPerspectiveTest`.

## Controlled terrain LoD profiling — September 26–27

This comparison predates the Brandywine rendering corrections above. Its renderer is frozen separately, so later
bug fixes and concurrent work cannot silently change the measured A/B pair.

**Measurement correction:** libGDX 1.14.2's `GLProfiler.disable()` restores GL30 but leaves its interceptor in
LWJGL3's GL20 slot. Draw counting therefore continued doing per-call error checks in earlier timed frames.
`GpuStageTimings.stopCounting` now restores the original GL20 interface explicitly; measured frames assert its
identity. The older FPS/CPU timing comparisons below are historical, instrumented results, not estimates of
uninstrumented application performance. Geometry, draw counts and retained heap are still useful independently.
The final comparison here supersedes those timings for the current LoD implementation.

The final two native runs use identical frozen production classes/resources, the same 200×200 medium-settings
board, 180 warmup frames and 1,200 measured frames per view. The fixture's capture timer is stopped, and simulated
input holds the cursor at screen center without moving the desktop pointer. Camera motion otherwise repicks terrain
under the live mouse, which made earlier moving-view runs incomparable. The LoD choice is applied after UI defaults
are initialized; the disabled run verifies all 625 installed chunks are FULL. Stationary measurements wait for
visible refinement before warmup; the moving view includes refinement. Both runs passed without GL errors.

Configuration: RTX 4070 Laptop GPU, Java 21, 4 GiB maximum Java heap, 1280×900, VSync off, no `glFinish`, no coverage
agent, JFR profile recording and asynchronous GPU timestamps enabled. This is one Atlas and the complete board
renderer/UI, not a large battle. FPS is uncapped rendered throughput, including the window loop. Manual collection
and screenshots occur outside measured frames. Results are one controlled pair on a shared host, not a universal
speedup or a reproduction of the user's 2 FPS case.

| Full view | LoD off FPS | LoD on FPS | Off median / p95 interval | On median / p95 interval |
| --- | ---: | ---: | ---: | ---: |
| Overview | 70.69 | 76.04 | 13.953 / 15.664 ms | 12.795 / 15.283 ms |
| Close | 508.69 | 498.88 | 1.881 / 2.614 ms | 1.946 / 2.505 ms |
| Moving camera | 378.07 | 368.57 | 2.569 / 3.465 ms | 2.543 / 3.767 ms |

Overview throughput improved 7.6% in this pair. Close/moving results do not establish an improvement: nearby
terrain is full detail in both modes, and the moving LoD run also prepares newly visible chunks.

| Overview resource/work measure | LoD off | LoD on |
| --- | ---: | ---: |
| First terrain frame, excluding prior client capture/window initialization | 95.60 s | 63.22 s |
| Java heap after collection | 2,350.9 MiB | 957.1 MiB |
| Installed chunk tiers | 625 FULL | 625 DISTANT |
| Opaque terrain draws | 3,337 | 3,020 |
| Approximate submitted opaque indices | 106.60 million | 24.05 million |
| Transparent-effect draws | 511 | 511 |
| All draws in the sampled overview frame | 4,053 | 3,736 |
| Opaque CPU submission median | 10.344 ms | 9.170 ms |
| Opaque GPU timestamp span median | 12.786 ms | 5.654 ms |

LoD removes 77.4% of the counted opaque indices and 59.3% of retained Java heap, but only 9.5% of opaque draws.
The libGDX index counter has floating-point accumulation and does not multiply instanced geometry by instance
count; it is not an exact scene triangle count. Heap is not total process or GPU memory. GPU timestamp spans
include idle gaps while the CPU feeds commands and must not be called pure shader execution time.

Both close views submit 188 opaque draws and 2,031,240 indices; the LoD view has eight FULL chunks and 617 DISTANT
chunks. Screenshots were inspected for both modes. This is not a pixel-equality claim across tiers or animated
frames. Zoom refinement took 1.81 s and included a **113.99 ms render-submission hitch**. That interval also includes
grass, tactical surfaces and shadow invalidation, so the earlier mesh-upload-only figure understates the whole
frame cost. The disabled run did not separately record its cold-zoom maximum; not all of this hitch is proven
exclusive to LoD. First opening and local editor rebuilding remain synchronous and are not solved by camera LoD.

### Profiled remaining work

- **Opening:** `BoardSurface` shoreline construction dominates sampled build stacks. `BoardRiver.Channel.field`,
  `BoardRiver.field`, surface-height queries and distance calculations are prominent. Water field preparation calls
  `GpuWaterShader.Pools.get`, which constructs full neighboring surfaces when they are absent from that chunk's
  prepared surfaces. Sharing necessary canonical water inputs and pruning irrelevant river spans are the next
  geometry-preserving opportunities. Estimated build allocation churn is about 21 GiB with LoD versus 49 GiB without;
  these sampled allocation totals are not simultaneously retained memory.
- **Overview:** 231 of 322 sampled main-thread stacks include `GpuTerrain.render`. Collection, model submission,
  culling, sorting, material handling and draw/VAO calls remain substantial. The solid reduction in GPU work is
  limited by CPU submission. Fewer than ten draws has not been achieved; metadata groups still issue individual
  mesh draws. Shared buffer ranges/multi-draw need a separately validated implementation that preserves culling,
  materials, local edits and transparency ordering.
- **Avoidable per-frame allocation:** about 959 MiB is allocated across 1,200 overview frames with LoD. Tree asset
  strings/batch keys dominate, followed by terrain batch wrappers; LoD's `values()` clone and threshold array also
  appear. Reusing stable keys and scratch objects is a small, direct next step. Only 6.2 ms of GC pause is recorded
  in that steady overview window, so this is not evidence that GC alone explains FPS.
- **Motion/refinement:** hover ray/triangle picking remains visible in both controlled profiles. Detail installation
  also causes dependent grass/tactical/shadow work. Budgeting only one mesh upload per frame does not bound that
  total work; retain the same picking geometry and inspect the complete update when improving it.

JFR inclusive counts overlap and include worker threads; waiting AWT/socket native samples are not CPU hotspots.
The profiling stage corrects benchmark measurement and adds phase markers; it does not optimize the renderer.

Reproduction artifacts are under `build/gpu-performance`: `lod-on-200-final.log`, `lod-off-200-final.log`, their
`*-profile.txt` summaries, and `lod-profile/<run>/profile.jfr`, screenshots and JUnit reports. The saved
`medium-200.board` has SHA-256 `987a9b4a0d203b7fb5ead86d30d5e61be7cd49ae5c9e80c7aa3e57200f50df44`.
`terrain-lod-profile.gradle` freezes the renderer once and refreshes only benchmark helpers thereafter;
`-DterrainProfileFrozen=true` reuses it. Use `performanceTerrainLod=true/false`, `performanceFrozenCapture=true`,
`performanceNoFinish=true`, `performanceWarmupFrames=180` and `performanceSampleFrames=1200` with that board.

## Problems and changes

- **Tactical overlays reconstructed CPU terrain surfaces.** Clipping for deployment and all other terrain-following tactical overlays now borrows the finished top and slope triangles owned by each terrain chunk. Ground cover borrows those same triangles. Neither retains the terrain builder's shoreline caches or a competing game-state snapshot.
- **Hover picking reconstructed terrain during camera movement.** Picking now borrows the chunk's finished tops, water, beds, cliffs and waterfall geometry. It preserves triangle order and hex ownership. Before a new scene or tuning change has been installed, the existing builder path supplies current geometry without rejecting it through stale chunk bounds.
- **Deployment capture and clipping allocated excessively.** Shapes wholly inside the existing graphics clip skip redundant area intersections. Triangle clipping reuses invocation-local scratch and prepares each marker triangle's bounds once; mesh construction reuses vertex descriptors. Emitted geometry keeps its own vertices.
- **A small editor edit recaptured and rebuilt the board.** Capture records the dirty hex area and its immediate neighbors. Texture atlases append changed artwork without relocating unaffected slots; only chunks using changed UVs rebuild. Riverbanks also invalidate when the neighboring ground artwork they borrow moves. Atlas compaction bounds discarded texture storage.
- **Dragging terrain sliders repeatedly rebuilt geometry.** Terrain sliders preview their values during a drag and apply on release, using libGDX's existing final change event. Programmatic changes and the other controls retain their existing behavior.
- **Shoreline construction repeated expensive work.** River queries cache nearby channel descriptions, share channel invariants, and stop when a larger result cannot affect the shoreline intersection. Profiled hot loops use indexed access to avoid per-query iterator allocation. Completed neighboring surface edges are reused when constructing cliffs.
- **Large maps retained construction caches.** Water fields release their CPU construction data once the chunk's meshes finish. The GPU field texture and its transform retain their normal lifetime.

The optimizations preserve game/client ownership of deployment legality and editor state. They do not reduce terrain tessellation, texture resolution, water effects, grass density, or shader quality.

## Benchmarks

`GpuBoardPerformanceSmokeTest` exercises actual client painters and native rendering. Its default map is a seeded flat board with 22% separated depth-one water. The complete-view mode also uses a saved random board generated with medium water, hills, mountains, cliffs, woods, and rough ground. Saving and reusing that board makes repeat runs comparable.

Measurements without coverage (terrain construction and capture are timed separately):

| Workload | Before | After |
| --- | ---: | ---: |
| 24×24 deployment overlay construction | 11,164 ms | 333 ms |
| 24×24 terrain tuning rebuild | 3,609 ms | 910 ms |
| 24×24 initial terrain construction | 5,488 ms | 1,454 ms |
| 200×200 scattered-water terrain construction | 425,650 ms | 63,000 ms |
| 200×200 single-hex terrain update with atlas invalidation | 184,271 ms | 902 ms |

The editor baseline is the intermediate run that exposed full-board atlas invalidation. The final large-map edit deliberately kept deployment borders active across the entire map; capture took a further 588 ms. Showing all 40,000 deployment hexes still takes 5.43 seconds to build the overlay, plus 1.31 seconds of client capture. Allocation and bounds fixes reduced overlay construction from 9.68 seconds after terrain reuse had already been applied. These worst-case pauses remain real limitations.

With the full-map borders already present, changing the unit to deployed and refreshing its sprites took 643 ms of client capture, 0.02 ms of terrain update and 11.31 ms of tactical update. The equivalent 24×24 update took 66 ms of capture, 0.03 ms of terrain update and 1.41 ms of tactical update. This case retains the same legal deployment borders; changing the unit's legal area can still require rebuilding the overlay.

The original 2 FPS report has not been reproduced with the generated medium-settings board. The complete-view measurements include live Swing capture, units, shadows, atmosphere, overlays, and UI:

| Medium 200×200 full view | Before median / p95 | After median / p95 |
| --- | ---: | ---: |
| Overview | 43.04 / 57.45 ms | 31.54 / 47.31 ms |
| Close | 8.33 / 71.78 ms | 5.66 / 6.86 ms |
| Moving camera | 13.24 / 51.49 ms | 9.75 / 13.84 ms |

The moving-camera comparison starts after ground-cover reuse was already in place. Picking reuse reduced scene-update p95 from 41.69 to 4.04 ms. Full-view opening on the saved medium board fell from 140.16 to 39.29 seconds. Profiling traced the original close-view pauses to ground cover rebuilding shoreline surfaces. Atmosphere and UI were minor costs in this case. Timing covers render submission plus GPU completion, excluding the application's window/event-loop wait; it must not be presented as a guaranteed displayed FPS. A specific saved board and the same camera/tuning state remain necessary to attribute the reported 2 FPS precisely.

Retaining complete picking faces is a deliberate memory tradeoff. Near the final measured frames, JFR reported roughly 1.0–1.1 GB of Java heap after collection, compared with roughly 0.8–0.9 GB before picking reuse. These are sampled heap measurements, not total process or GPU memory. Temporary fallback picking builders are cleared when terrain is updated; finished faces live with their owning chunk.

## Terrain detail and frame submission

Terrain already has three board-size detail tiers. By default, boards up to 2,500 hexes use full relief, boards up to 10,000 use medium relief, and larger boards use coarse relief. A 200×200 board therefore already uses the coarse tier. This is a whole-board setting, not camera-distance terrain tessellation. Trees select among three meshes from projected size, with hysteresis; small scatter and grass also use projected-size visibility. Terrain is culled in 8×8-hex chunks. This investigation does not change those thresholds, meshes, densities, textures, or shader effects.

JFR sampling and per-stage native timings identified repeated shader changes, glyph uploads, and full-board camera-bound scans as frame-time costs:

- The terrain color batch now uses the existing opaque shader sorter, already shared by depth and unit rendering. Overview shader switches fell from 1,548 to 4. Blended surfaces retain their existing depth ordering.
- Hex labels upload the existing font-cache vertices once into owned static meshes. They retain their plane/chunk/atlas-page order, colors, depth shader and culling bounds. Consecutive visible ranges share a draw; hidden ranges remain excluded. Bounded pages preserve unsigned-short indices. Meshes are replaced on tile, font or geometry changes and disposed with the label renderer; font textures remain borrowed. Incremental font growth is handled using each cache's own page count.
- Camera visibility reuses the height limits of the immutable tile snapshot. Panning no longer rescans 40,000 tiles for the floor and maximum elevation. Tile replacement or geometry tuning invalidates those limits; camera rays and board dimensions are still evaluated for the current view.

Two uncapped comparisons used the same saved medium-settings 200×200 board, 90 warmup frames and 600 measured frames per scenario. The second comparison reversed run order. Production classes and resources were frozen; only the three changed render classes and their nested classes were overlaid for the optimized variant, keeping concurrent terrain work out of the comparison. The final variant includes the incremental-font page-count fix. FPS is the reciprocal of the mean of 599 measured frame intervals, including window-loop overhead; it is rendered throughput, not a display refresh-rate claim.

| Full view | Baseline FPS, two runs | Optimized FPS, two runs |
| --- | ---: | ---: |
| Overview | 50.44 / 49.25 | 84.20 / 81.82 |
| Close | 368.87 / 399.29 | 478.85 / 331.68 |
| Moving camera | 190.49 / 187.74 | 293.35 / 284.23 |

The final comparison improves overview throughput by 66% and moving-camera throughput by 51%. Close-view timing varied enough that no consistent improvement is established. The optimized final overview's median/p95 frame interval was 11.11/19.15 ms, compared with 18.58/24.07 ms before; moving-camera intervals were 3.45/4.83 ms, compared with 5.21/6.80 ms. Background work and JVM warmup affect these short runs. Earlier exploratory runs were substantially slower on the same machine, so their absolute timings should not be combined with this table.

In the final comparison, overview opaque-terrain CPU time fell from 9.51 to 7.09 ms, and the tactical/text stage from 5.50 to 0.51 ms. Overview tactical/text draws fell from 422 to 93 with the same submitted glyph index count. Moving-camera scene-update time fell from 1.82 to 0.34 ms. Close views can submit more small glyph ranges than SpriteBatch did (30 versus 18 draws in this fixture), because static pages preserve order and exclude hidden chunks; reducing overview uploads does not guarantee a close-view gain.

The remaining large overview costs are terrain submission (2,267 opaque draws, about 7.1 ms CPU) and transparent effects (511 draws, about 1.8 ms CPU). JFR still attributes substantial CPU work to model submission, culling, sorting and tree instance preparation. GPU timestamp spans also include idle time while the CPU submits work; they are not a measure of pure GPU utilization. libGDX's profiler index counter uses floating-point accumulation and does not multiply instanced draws by their instance count, so it must not be interpreted as an exact scene triangle count.

## Draw-call and shader audit

The saved medium-settings 200×200 board submits 2,983 draws in the overview and 257 in the stationary close view before this second round of batching. The overview breakdown is:

| Submitted work | Draws |
| --- | ---: |
| Sculpted grass / sand / dirt terrain | 625 / 525 / 121 |
| Ground artwork and normals | 604 |
| Opaque props | 375 |
| Instanced trees | 17 |
| Water | 511 |
| Tactical overlays and text | 93 |
| Units, outlines, lighting, atmosphere and UI | 112 |

Every visible chunk/material pair already has one draw. No audited terrain mesh reaches the 48,000-vertex split threshold: the largest has 19,112 vertices. Raising that threshold or merely reusing Material objects therefore cannot remove these draws. The close view is different: grass contributes 118 of its 165 opaque draws, while props contribute only two. These counts come from actual submitted renderables, after culling and shader selection.

Water has 511 distinct chunk field textures, each describing its own shoreline, depth and current. Combining those surfaces requires shared field storage and preservation of transparent depth order. A larger global chunk size would also enlarge local editor rebuilds and weaken culling. Neither is a safe batching toggle. Terrain material families bind different texture sets, and perspective rain/detail uniforms depend on each original part's center, which a future shared-mesh design must preserve.

The shader audit found exact zero-contribution work that can be skipped. Submerged-bed refraction and caustics now skip their noise reads when water effects or projected rain detail are zero. Water without a waterfall landing skips two foam texture reads; the active landing path retains its existing calculations. These uniform conditions preserve derivative and mip-selection behavior. Orthographic cover/ripple/rain uniforms are uploaded once per shader pass because projected size is constant across that view; perspective still computes them per original part. The same overview loses 4,766 GL calls across opaque and transparent submission, with unchanged draw and index counts at this stage. This is a driver-call reduction, not a draw-call reduction.

The focused shader comparison passed all 24 frozen native cases exactly: pools and waterfalls, orthographic and perspective cameras, zero detail, disabled water effects, active wet surfaces, and separate opaque-bed/full-water captures. The final fixture also exercises older cliff materials. Reference repeats are measured separately and were exact too. Complex texture filtering, normal mapping, water motion, material quality and rain loops remain intact; removing those effects would trade quality for speed rather than eliminate redundant work.

Depth passes now reuse contiguous index ranges from each chunk's existing opaque meshes. Compatible ranges share one draw without copying vertices, allocating GPU meshes or changing chunk culling. Different meshes, transforms, cull/depth state, index gaps, cutouts, blending, skinning and instancing remain separate. The native fixture removed six actual depth draws (21 to 15) with identical packed camera-depth and derivative-biased shadow-depth pixels in both camera projections. These savings apply when depth/shadow passes run; the cached stationary overview already submits no geometry-shadow draws.

A first prop-batching prototype merged the full visible set through libGDX's existing ModelCache. Its native fixture reduced 375 draws to five and CPU submission from 4.308 to 0.275 ms median in an interleaved comparison. However, changing the visible set or editing one source rebuilt the entire aggregate in 10–29 ms. That approach was rejected because it could introduce movement/editor pauses. The revised cache groups 4×4 existing terrain chunks per page, keeps existing per-chunk visibility, and rebuilds only pages whose visible source renderables changed. It borrows chunk meshes and owns tightly sized aggregate buffers. Pages copy already-baked vertices through MeshBuilder without applying another transform or normal normalization. Chunk props retain their existing ModelCache path, and both paths share mesh disposal. Temporary builders are discarded, avoiding retained scratch arrays or accumulating exact-size mesh pools.

Review also exposed a pre-existing ordering sensitivity in the shader sorter: fading grass intersects terrain at depth-buffer precision, so shader identity ordering can change a few tied fragments. Passes containing grass now retain the complete default distance ordering. Other passes, including the large-map overview where grass is culled, retain shader grouping. This fixes the visual boundary without a non-transitive exception inside the comparator.

The final full-board audit confirms these reductions:

| Saved 200×200 overview | Before this pass | After |
| --- | ---: | ---: |
| All draw calls | 2,983 | 2,656 |
| Opaque terrain and features | 2,267 | 1,940 |
| Plain opaque props | 375 | 48 |
| Water | 511 | 511 |

Prop geometry still submits the same 282,960 indices and uses tightly sized buffers. Total overview draws fall 11%; prop draws fall 87%. The close view stays at 257 draws, because its two prop chunks belong to different pages. Its opaque shader switches rise from 6 to 26 as the original grass/terrain depth ordering is restored; visual correctness takes priority at that boundary. The audit logs are `draw-audit-before.log` and `draw-audit-final.log`.

The final native prop fixture distributes 375 source caches over 49 occupied pages. It measures 375→49 draws and interleaved CPU submission median/p95 of 4.992/6.372 ms before versus 0.924/1.242 ms after. A one-source edit rebuilds exactly one page and submits in 1.635 ms; a large visibility jump changes fourteen boundary pages and takes 6.667 ms, while returning to the overview takes 9.522 ms. Those are complete fixture submission times, not isolated editor latency or hardware-independent limits. Every compared pixel and indexed position/normal/color value matches exactly. Tests also verify unsigned index limits, a dense page splitting across five buffers, unsupported/transformed-source fallbacks, reuse of unaffected pages, and disposal without deleting borrowed meshes.

The final combined native checks pass all twelve mixed terrain/camera/occupied-building cases with exact pixels, all four packed camera/shadow depth cases with exact pixels, the resource ownership test, and the CPU sorter regression test. The final log is `draw-batching-exact-review.log`. These comparisons replace the earlier prop prototypes' small normal-rounding differences; no relaxed pixel tolerance was retained for prop batching.

A final complete-view comparison, with diagnostics disabled, reuses the saved board and frozen production classes/resources with only this pass's renderer changes overlaid. Each view uses 90 warmup and 600 measured frames. Overview throughput is 70.39→79.91 FPS; median/p95 frame intervals are 13.015/21.596→11.460/18.734 ms. Opaque-terrain CPU median falls 8.448→7.056 ms, and transparent submission 2.055→1.857 ms. Close view measures 242.74→367.99 FPS and moving view 251.24→254.42 FPS, but unrelated close-view stages also become substantially faster: concurrent machine work varies, so this single pair does not establish a reliable close-view gain. The repeatable draw counts and interleaved prop measurements are stronger attribution evidence. Logs: `draw-final-before.log` and `draw-final-after.log`. The earlier FPS table predates these additional batching and grass-ordering changes.

Grass still submits one mesh per visible tile: 118 draws in this close-view fixture. Orthographic batching could pack these into roughly three full-size meshes, but a global visible-set cache has the same rebuild problem as the rejected prop prototype. Perspective grass additionally needs each tile's existing fade value. Fixed spatial mesh storage with visible subranges is the appropriate next experiment; reducing density or changing fade behavior would alter the visuals.

## Incremental overlay updates

Tactical geometry now retains the packed vertices and borrowed surface dependencies of each active drawing command. A local change re-clips only changed commands and commands depending on replaced terrain surfaces. Wall endpoints also check their six neighboring elevations. Unchanged GPU pages survive; the original 10,000-triangle draw boundaries, painter order, capped height offsets, stroke groups and flat/upright presentations are preserved. Keeping those boundaries matters because changing a transparent draw's center can change blending order. Pages outside the camera frustum are omitted using their existing bounds.

Swing capture reuses immutable ECM/ECCM, source, embedded-board and map-sheet drawing output when its actual inputs are unchanged. Coverage still precedes the separate map-sheet border pass. Live sprites continue to be evaluated, including visibility changes that do not request a repaint. Deployment rules are still evaluated on every capture; repeated native borders share the expensive unscaled border-shape construction within that capture. One scratch recorder serves all changed hexes, avoiding one small bitmap/graphics allocation per hex. After every painter runs, Swing compares the complete immutable snapshot with the previous one and reuses it when equal. This keeps the deep comparison of unchanged command lists off the render thread without skipping live state evaluation.

Legacy raster markings use the existing atlas's changed-region keys to rebuild only affected chunks. In-place pixel changes retain their meshes; additions, removals, shared-slot splits/merges and atlas compaction invalidate every affected reference. The compatibility painter still follows its existing visible-area invalidation: this pass localizes its GPU mesh updates, not all raster capture. Regional capture needs additional care because legacy sprites and plugins can paint across hex boundaries.

The saved medium-settings 200×200 board, with legal deployment borders over all 40,000 hexes, gives the following local comparison. Both runs use the same frozen support classes/resources, Java 21, the RTX 4070 Laptop GPU and a 6 GB test heap:

| Work | Before | After |
| --- | ---: | ---: |
| Overlay rebuild after a single terrain edit | 11,980.58 ms | 441.77 ms |
| Client capture for that edit | 1,010.33 ms | 151.41 ms |
| Tactical update after deploying a unit with unchanged legal borders | 11.37 ms | 0.016 ms |
| Close-view tactical overlay draws | 1,048 | 247 |
| Close-view total draws with deployment overlays | 1,266 | 465 |
| Close-view render submission plus GPU completion, median / p95 | 24.57 / 31.68 ms | 16.38 / 18.69 ms |

The unchanged terrain-only close view has 218 draws in both runs. Background work also changed terrain construction and submission timings, so the frame-time pair is indicative; retained-mesh checks and reduced draw counts provide stronger attribution. These timings are not displayed FPS or complete editor latency: the terrain rebuild itself still takes 2.05 seconds in the optimized worst-case edit. Logs are `overlay-before.log` and `overlay-final.log`. The intermediate optimized run, before publication reused unchanged snapshots, measured 513.61 ms for the edit's overlay rebuild and 17.07 ms for the unchanged-border tactical update (`overlay-after.log`).

A separate CPU benchmark captures actual client painters on a flat 200×200 board, with three warmups and nine samples per case:

| Capture workload | Before / after median | Before / after allocated per capture |
| --- | ---: | ---: |
| 40,000 deployment borders | 1,048.18 / 54.36 ms | 821.82 / 110.17 MiB |
| 80,000 unchanged ECM/ECCM fills | 453.13 / 35.79 ms | 635.10 / 7.34 MiB |
| One-hex recolor among those fills | 374.42 / 25.56 ms | 635.10 / 7.35 MiB |
| Dense coverage plus map-sheet borders | 563.34 / 37.28 ms | 799.42 / 11.12 MiB |

`GpuOverlayCapturePerformanceSmokeTest` measures capture on Swing's event thread and reports thread-allocated bytes as well as time. It accepts `megamek.gpu.performanceSize` and `megamek.gpu.performanceMapSheets`. These capture-only numbers exclude terrain construction and GL uploads.

The cache is a memory/speed tradeoff. The saved board's 40,000 deployment commands retain 479.67 MiB of packed vertex arrays and 357,604 borrowed surface references, excluding map/object overhead and mesh/GPU buffers. Arrays are shared by page descriptions rather than copied into another retained page array, and removed overlays release their caches and owned resources. First activation still constructs the complete overlay: it took 5.40 seconds in the final run. Inserting/removing early commands can shift subsequent painter offsets and page contents, requiring additional reconstruction. This change does not promise constant-time updates for those cases. A separate complete saved-board run also passed with the application's normal 4 GB heap limit, including initial overlay construction and the local edit, with the same retained payload and draw counts (`overlay-4g-validation.log`).

Validation passes 16 focused CPU/integration tests and the two native marking/tactical suites. Native comparisons require exact indexed vertices, draw bounds and pixels across camera modes, local edits, reordered commands, dash animation and offscreen culling. Marking tests cover local mesh identity, shared artwork, removals, compaction, resizing and fresh-build pixel parity. Resource checks cover owned meshes and dash textures. Scoped formatting/Checkstyle and full main/test compilation pass. Two broader `GpuBoardSourceTest` artwork assertions (`featuresUseAuthoredModelsAndTheirOwnHeights` and `groundIncludesRoughAndRubbleArtworkForNormalMapping`) fail identically on the frozen baseline and optimized capture; they are not introduced by this pass.

## Deployment zones and GPU border masks

Selected-unit deployment now derives one zone from the existing client painter's legal hexes. Shared edges of the same displayed color disappear; holes, disconnected regions, warning colors and map boundaries remain. A solid 200×200 region has 1,598 perimeter edges instead of 240,000 individual hex edges (99.3% fewer boundary segments). This is a boundary-geometry reduction, not a claim of 99.3% fewer total draws or higher FPS.

Deployment and visual/sensor range boundaries share `BoardRangeBorder`: height, color/opacity, outline stroke, path capture, dash distances and endpoint joining. Only interior owners of matching panels that meet at an endpoint contribute to its elevation span; a higher exterior plateau must not raise a low deployment boundary. Each command retains these small neighbor lists so changing the boundary's membership invalidates cached geometry even without a terrain edit. Endpoint heights account for finished sculpted tops rather than only logical elevation; the highest top is derived once per terrain snapshot. Deployment uses a 65%-opaque yellow curtain half an elevation level high above that support, with scrolling white dashes. Captured cyan/warning colors retain their meaning. A small depth bias prevents coplanar map-edge cliffs from fighting the curtain while real foreground geometry still occludes it.

Top view uses a ten-pixel inward colored band with the moving white dashes on its outside edge. Switching camera presentation and advancing the dash animation reuse cached geometry. The hover hex keeps the selection marker's fixed surface clearance without bobbing. Classic 2D and all-player deployment painting retain their existing presentation.

The interior tint uses 18% opacity on the complete terrain belonging to each selected hex, including slopes and cliff faces. It borrows each hex's geometry once, without slope exclusions, neighboring geometry duplication or footprint clipping. The same shader-mask implementation also handles complete passes of opaque, non-overlapping regular hex borders. Those borders retain their original terrain-following coverage. Translucent or overlapping borders, arbitrary clipped artwork, mixed wall passes and depth-independent unit icons retain their original geometry path: native review found that changing their transparent-page centers could otherwise change painter order. Tests require exact pixels for these fallbacks.

Masks use one board-sized RGBA color texture and one floating-point parameter texture (0.763 MiB total at 200×200), plus indexed position carriers built lazily for visible 8×8 chunks. These carriers borrow finished terrain triangles but own their mesh buffers; they are additional geometry, not zero-cost reuse of existing terrain GPU buffers. They do not retain per-command Java vertex arrays. Recoloring reuses carrier meshes, local terrain edits invalidate their surface dependencies, and inactive overlays release their owned meshes/textures. Temporary primitive index maps and one scratch vertex array avoid per-vertex boxing and varargs allocation during carrier construction.

The connected-border/full-terrain-tint run uses the saved medium-settings 200×200 board, an RTX 4070 Laptop GPU, Java 21 and the normal 4 GiB heap. The final interior-only joining run is `range-zone-interior-200.log`; the preceding run is `range-zone-200.log`. Both use the current engine, including concurrent terrain changes. These are observed timings, not an isolated attribution of all differences to this border change.

| Overlay work / storage | Previous draped hex borders | Connected zone + whole-terrain tint |
| --- | ---: | ---: |
| Initial tactical update | 5.40 s | 268.83 ms |
| Retained Java vertex payload | 479.67 MiB | 6.905 MiB |
| Close-view overlay draws | 247 | 11 |
| Update after unchanged legal deployment | 0.016 ms | 0.020 ms |
| Tactical update after one terrain edit | 441.77 ms | 72.12 ms |

The tint also has a deferred first-render cost: 374.56 ms in the close view, with 2.411 MiB of uploaded carrier payload. First visiting the entire board adds 863.71 ms and grows that payload to 141.864 MiB (the previous top-only prototype used 163.116 MiB). The wider cached overhead band increases the Java vertex payload from that prototype's 0.750 MiB to 6.905 MiB. Mesh/native and GPU copies, textures and object overhead are separate from the Java-vertex row above. The full-board zone adds 627 draws (625 tint chunks plus two perimeter groups), compared with eleven in the close view. This remains a real limit: a full-map tint redraws terrain geometry, even though the boundary itself is small. The preceding run measured 359.05 ms for the tactical update and 1,188.54 ms for the first overview visit; these timings vary and are not an isolated FPS comparison. The final edit also still spends 2.65 seconds rebuilding terrain outside the tactical overlay.

Validation covers captured deployment state, color composition, holes/islands, map boundaries, finished-top clearance and connected joints, unchanged/local-edit mesh reuse, unsigned indices, offscreen culling, animation without geometry rebuilds and resource disposal. The deployment fixture checks twelve orthographic/perspective views of twelve-level plateaus, including angles on both sides of the overhead transition. It requires no untinted cliff holes and bounds double blending where biased terrain triangles intersect to 0.1% of cliff pixels (minimum four); the recorded cases have 0–84 such pixels. A separate coplanar-curtain fixture requires stable map-edge coverage while foreground geometry still occludes the curtain. The shader's whole-section branch has a margin against perspective interpolation rounding. A textured 16×16 board crossing rising ground and twelve-level cliffs renders both presentations under `range-joins-review`.

Full main/test compilation, scoped Spotless and Checkstyle, 29 focused CPU tests and four native GPU suites pass (`range-border-interior-validation.log`), together with the textured 16×16 preview. The checks cover exterior-plateau exclusion, matching boundary ownership and membership-based command invalidation; the GPU suites retain the ordinary-border and incremental-rendering comparisons described below.

Regular opaque masks preserve interior colors; analytic discard and CPU triangle clipping can disagree at pixel-center boundary ties. Their native gate permits at most 0.5% of visible border pixels (minimum four), restricted to existing neighboring colors or isolated coverage samples with eight unchanged neighbors. It does not allow arbitrary channel tolerances. The optimized opaque fixtures differ by 0–25 edge samples; generic fallback comparisons and unchanged reference repeats are exact. This replaces the rejected broader transparent-mask path rather than relaxing its alpha-ordering errors.

A separate run forces the same 40,000 captured deployment outlines through the ordinary opaque-mask path (`hex-mask-200.log`), independently of the new zone presentation. Its tactical update is 61.27 ms, with no retained Java vertex arrays; first close rendering takes 562.75 ms and uploads 2.327 MiB of carriers. First overview adds 1,382.68 ms, reaching 189.703 MiB. It adds seven close-view draws and 625 overview draws; a local edit's tactical update is 36.80 ms. These measurements include the deferred carrier work separately rather than hiding it in frame warmups. Set `megamek.gpu.performanceDeploymentOverview=true` to include the overview visit and `megamek.gpu.performanceHexMasks=true` to exercise ordinary borders instead of the zone presentation. `performanceDeploymentStart` selects an existing `Board.START_*` deployment position for visual review (default `START_ANY`); `performanceDeploymentTopView=true` saves the overhead presentation too.

## Opaque terrain investigation: screen-size LoD and submission cost

The requirement is to **remove board-size-based terrain detail**, not retune its thresholds. A nearby hex on a
200×200 map must have the same visual detail as a nearby hex on a small map. Board dimensions should affect how
many chunks exist, not their visual quality. The current `BoardRelief` constructor still chooses FULL/MEDIUM/COARSE
from the total hex count; zooming in cannot recover the detail omitted during construction.

The September 26 investigation froze the compiled classes and resources before running the saved medium 200×200
board on the RTX 4070 Laptop GPU at 1280×900. These are measurements of that renderer snapshot, not an optimization
comparison; other terrain work continued in the shared checkout. The full-view run used 90 warmup and 600 measured
frames per view with VSync/frame limiting disabled. An isolated opaque-pass experiment used the same frozen build,
90 warmup and 300 measured frames per condition, repeated twice, with an unchanged camera and geometry.

### What is actually expensive

The full overview submits **1,940 opaque draws and 49,453 OpenGL calls**, despite only **four shader switches and
28 texture bindings**. Its opaque stage takes a median **14.15 ms CPU**, versus **1.98 ms** in the close view. CPU
sampling finds renderable collection, attribute handling, sorting, mesh/VAO binding and draw submission in that
path. The count is dominated by the 625 visible 8×8 chunks, each submitting its material parts independently:

| Opaque overview category | Draws | Submitted indices |
| --- | ---: | ---: |
| Sculpted grass-family terrain | 625 | 22,752,690 |
| Sculpted sand-family terrain | 525 | 899,913 |
| Sculpted dirt-family terrain | 121 | 115,941 |
| Ground artwork | 604 | 407,232 |
| Batched opaque props | 48 | 282,960 |
| Instanced trees | 17 | 2,574 before instance multiplication |

An independent one-frame API audit counted **13,338 `glActiveTexture`**, **13,336 `glUniform1i`**, **5,125
`glUniform1f`**, **3,867 matrix-4 uploads**, and **1,923 matrix-3 uploads**. Binding a material once per batch can
avoid much of this repetition. libGDX's `BaseShader.render` currently invokes every local uniform setter for every
part, and its texture setter binds a texture unit and uploads the sampler number even when a texture was reused.
Making `Material` objects shared by identity alone would not remove these calls.

The isolated overview completes in **8.58–9.92 ms per pass**. Quartering the pixel count gives **9.10–10.61 ms**;
quadrupling it gives **9.11–9.77 ms**. Clay shading gives **9.25–9.26 ms**, and disabling rasterization gives
**9.07–9.40 ms**. None establishes a useful overview speedup. Retaining collection/sorting but suppressing actual
submissions gives **1.90–2.03 ms**. This points to CPU submission overhead as the primary current overview limit;
the suppressed-draw result is a diagnostic lower bound, not an achievable FPS forecast.

GPU timestamps between stages include any GPU idle time while the CPU feeds commands. They must not be read as
shader execution time. In the isolated close view, where this problem is much smaller, quadrupling the pixels raises
completed mean time from **0.78–0.92 ms to 2.15–2.26 ms**, so fragment shading does matter at larger screen coverage.
Its overloaded timestamp-query ring drops many samples at that setting; the completed timings include all 300
frames and are the appropriate comparison.

### Geometry is much finer than its screen coverage

The overview has **7,922,848 sculpted triangles**: 5,876,703 ground, 1,084,200 cliff and 961,945 rock. **99.91%**
project to less than one pixel of area; **99.80%** to less than a quarter pixel. These counts measure submitted
triangles, before occlusion/clipping; they do not imply every such triangle can be deleted. A thin silhouette edge
can matter despite small area. In the close view most ground/cliff triangles are larger than a pixel, supporting
camera-dependent detail rather than a global lower-detail setting.

The replacement should choose cached chunk mesh detail from a **screen-space error bound**, with full detail near
the camera and progressively simpler distant geometry in both orthographic and perspective views. Projected area
was useful for diagnosing waste; displacement/silhouette error is the correct quality criterion. Shared chunk
edges, cliff breaks, shorelines, roads and material boundaries must remain consistent. Camera movement should select
cached variants, with hysteresis, rather than synchronously rebuild the board. Preparing/refining those variants
must be budgeted and visible chunks prioritized, so removing the size cap does not first build a full-detail
40,000-hex board and make opening/memory worse.

This requires separating canonical landform shape from render sampling. Today the `Detail` record controls both
tessellation and whether rock/plant dressing exists. Changing it wholesale with camera distance would therefore
change features and shared edge samples, not just triangle count. Keep one canonical shape and feature placement;
derive render detail from it. Installed surface/picking snapshots, overlays and shadows must be invalidated locally
when their geometry changes, and editor edits must invalidate affected variants rather than an entire aggregate.

### Is fewer than ten draw calls the right target?

For **base opaque terrain**, it is a useful direction: the 1,875 base-terrain draws in this scene use only **six
distinct material-value groups** (three sculpt families plus three ground-artwork values). Shared vertex/index
storage with visible chunk ranges can submit one multi-draw batch per compatible group. Keep 8×8 chunks for edits
and culling; storage/submission need not have the same granularity. This machine reports support for base-vertex
and indirect multi-draw. A first prototype can use a CPU-generated list of visible ranges rather than introduce a
GPU culling framework.

Six material groups is an opportunity, not a demonstrated six-draw implementation or a universal count. Current
meshes live in separate buffers with unsigned-short indices; batches need shared storage and range/base-vertex
addressing. Perspective detail uniforms currently depend on each original part's center, and grass has real depth
ordering constraints. Preserve those inputs/order, update only edited storage ranges, and avoid duplicating the
whole terrain just to batch it. A native multi-draw API submission still contains many GPU draws; report API
submission count and actual geometry work separately.

For the **entire frame**, a hard ten-call ceiling would conflate terrain, instanced vegetation, transparent water,
units, text, effects and compositing. Water alone currently has 511 separate chunk-field textures; merging that
submission needs field storage and transparent-order work too. The useful acceptance measures are frame CPU/GPU
time, camera-motion spikes, local-edit latency and unchanged visible quality, with low terrain batch counts as one
means of achieving them.

Recommended implementation order: replace board-size detail with cached screen-space terrain LoD, introduce shared
terrain storage/material batches alongside it, then optimize fragment work supported by close/high-resolution
measurements. Shader candidates include reusing normal RGB/height samples at identical UVs and skipping unused
normal-map work; compiler elimination and frozen pixel comparisons must establish whether these actually help.
Reducing shader quality or globally lowering detail would not solve the observed submission bottleneck.

Logs: `opaque-full.log`, `opaque-isolated.log`, `opaque-geometry.log`, with corresponding JFR recordings under
`build/gpu-performance`. The local `opaque-profile.gradle` runner freezes current classes/resources on its first run;
`-DopaqueFrozen=true` reuses them. `GpuOpaqueProfileSmokeTest` there contains the controlled experiments and projected
triangle/API audit (`-Dmegamek.gpu.opaqueAuditOnly=true`). The geometry-readback frame's GLProfiler total includes its
inspection calls; the clean full-frame/profile and separate API-count frame supply the production call totals above.
The board SHA-256 is `987a9b4a0d203b7fb5ead86d30d5e61be7cd49ae5c9e80c7aa3e57200f50df44`.

## Camera-dependent terrain implementation — September 26

Terrain mesh sampling now depends on projected hex width, not board dimensions. `TerrainLod` selects four levels
with hysteresis; its default thresholds are 64 pixels for full detail, 24 for medium and 6 for coarse. Both camera
types use the same installed chunks, picking surfaces, overlays and shadows. Perspective selection uses the nearest
possible point of a chunk, so its far side cannot cause the near side to receive less detail. These thresholds are
a screen-size heuristic, not a measured bound on every vertex's projected displacement.

`TerrainLod.DEFAULT_ENABLED` is `true`. **GPU Tuning → Terrain → Terrain detail → Terrain LoD** controls the live
setting, and Defaults restores it. Turning it off requests full detail at every distance. Existing visible chunks
refine gradually through the same queue; hidden chunks refine when they become visible. Neither this switch nor
the pixel thresholds invalidates the board's terrain shape. The two pixel sliders remain available while LoD is off.

The implementation retains 8×8 chunks for local edits and culling. Neighboring chunks keep canonical shared edge
samples at every tier; coarse cliff interiors use fewer rows while their endpoints remain stitched to those shared
samples. Shoreline contact and the water-depth field retain consistent sampling across tiers. Material-blend
subdivision also scales with detail, so it does not silently recreate a dense distant mesh. Close views retain the
full mesh and its rock/plant dressing regardless of board size.

One worker prepares surfaces, water-field bytes, material-blend triangles and vertex buffers. The render thread
resolves textures/materials and uploads the result. Only one detail job is in flight and at most one completed chunk
is installed per frame. Eight replaced chunks are retained for quick reversals; the cache does not grow with board
size. Terrain edits or shape tuning invalidate jobs and cached variants. Obsolete jobs cannot replace edited
geometry, and GL resources are created/disposed on the render thread. Ground cover and tactical surfaces follow
the installed chunk rather than retaining an earlier tier's heights.

Opaque terrain also reuses material state between consecutive compatible draws, retaining their original order.
The compatibility check includes material values, projected shading uniforms and the environment. Grass retains
the existing path because its depth ties matter. This reduces redundant uniform/texture API calls, but **does not
merge the underlying mesh draws**. The audit counts those individual ranges, not the enclosing shader group.

### Review findings addressed

- Global material sorting changed a few stable pixels at shared depth ties, so that experiment was removed.
- Beginning/ending a shader per material group increased API traffic. A group now spans the existing contiguous
  shader run and changes material normally when required.
- CPU-only surface preparation still left significant work on the render thread. Material-blend planning and
  vertex emission were moved into the worker stages; GPU ownership remains explicit.
- A prepared water texture's buffer position must be reset before upload. The missing flip caused a native driver
  crash on large boards. The corrected upload passes an exact RGBA readback check as well as the 200×200 run.
- Pixel thresholds and the enable switch are render settings, not landform settings. They no longer trigger a
  terrain-shape revision or a synchronous whole-board rebuild.

### Measured results and remaining work

`GpuTerrainScalingSmokeTest` uses the saved medium 200×200 board above, the RTX 4070 Laptop GPU, a 1280×900 opaque
pass, 90 warmup frames and 300 measured frames per condition, twice. The scene/camera are frozen for each paired
comparison. Concurrent material/water work changed the renderer since the earlier investigation; the older totals
are **not** a controlled before/after baseline for this implementation.

| Final frozen renderer | Overview | Close view |
| --- | ---: | ---: |
| Opaque draws | 3,019 | 219 |
| Submitted indices | 24,051,488 | 2,300,703 |
| GL calls, material reuse off → on | 92,742 → 86,040 | 4,648 → 4,648 |
| Warmed second-round mean, off → on | 10.308 → 10.470 ms | 1.547 → 1.607 ms |

Material reuse removes 7.2% of overview GL calls in this snapshot, but these warmed timings do **not** establish an
FPS improvement from that change alone. The close view includes grass and therefore keeps the original path.
An earlier implementation run submitted 35,500,892 overview indices; correcting distant material subdivision and
water-cliff sampling reduced that to 24,051,488 (32.2% fewer). That comparison measures the refinement of this
implementation, not a 32.2% gain over the original production renderer, and index savings are not an FPS forecast.

Initial terrain construction still takes **66.8 seconds** in the final large-board run. Initial construction and
local editor rebuilds remain synchronous; only camera/toggle detail transitions use the new worker. The largest
render-thread detail handoff measured while refining the close view was **11.23 ms**. Small-board cold native tests
also show a longer collection handoff than the final mesh upload, so one chunk per frame is not a hard time budget.
The eight-entry variant cache bounds additional cached chunks, not the memory of all active full-detail geometry.

The fewer-than-ten terrain submission target remains unimplemented. Shared buffer storage with visible mesh ranges
is the next structural opportunity; grouping metadata alone cannot reduce the actual draw count. Likewise, this
opaque-only benchmark does not establish full-application FPS or solve first-open/editor latency.

The geometry stage passed 93 focused CPU tests covering terrain topology, mixed-detail joins, water contacts,
material boundaries and tactical surfaces. The final switch run passes all five `TerrainLodTest` checks, including
enable/disable behavior without shape invalidation. Native checks pass for UI pointer input/defaults, fixed-camera
toggle/cache reuse, zoom refinement, edits superseding in-flight jobs, texture upload and GL errors. The opaque
submission A/B test passes all 12 orthographic/perspective, tilt and building-opacity combinations: no changed
stable pixels. It samples unchanged reference renders before and after the comparison, excluding only their
5–21 unstable pixels out of 1,152,000 (with an enforced maximum of 64), rather than treating that noise as a batching
regression. This comparison validates material submission, not equality between different LoD tiers.

Logs are `terrain-cpu-final.log`, `terrain-scaling-final.log`, `terrain-toggle-cpu.log` and
`terrain-toggle-native.log` under `build/gpu-performance`. The local `terrain-validation.gradle` runner freezes
compiled classes/resources; `-DterrainFrozen=true` reuses that snapshot. `terrain-isolation.gradle` separates
compiler outputs from other active builds in this shared checkout. The tracked on-demand tests are
`GpuTerrainLodSmokeTest`, `GpuTerrainScalingSmokeTest`, `GpuTerrainSubmissionSmokeTest` and `GpuBoardTuningSmokeTest`.

## Validation and reproduction

The on-demand benchmark accepts JVM properties prefixed with `megamek.gpu.`: `performanceSize`, `performanceFullView`, `performanceMedium`, `performanceBoard`, `performanceDeployment`, `performanceWarmupFrames`, `performanceSampleFrames`, `performanceNoFinish`, and `performanceDrawAudit`. Warmup and sample counts must each be at least two, and `performanceNoFinish=true` measures uncapped frame intervals. `performanceDrawAudit=true` prints one-frame material, mesh occupancy and source-category diagnostics in overview and close view; it is removed before timed frames, but its allocations can still disturb timing, so use separate runs for the audit and FPS measurements. Supply an absolute `performanceBoard` path to reuse a saved board; a missing file is generated and saved when medium mode is enabled. Performance runs must disable the test coverage agent. Ordinary native checks can be run through `gpuBoardSmoke` with a test filter.

The local investigation's isolated runner, logs, JFR recordings, screenshots, and saved board are under `build/gpu-performance`; they are build artifacts, not committed fixtures. CPU checks cover river boundaries, terrain topology, tactical clipping, capture invalidation/undo, and selection. Finished-geometry picking is compared with builder picking across transitions, padding and square columns, including ice, submerged beds, cliffs, slopes, waterfalls, shared edges and oblique rays. Native checks cover atlas alias changes, adjacent bank updates, distant chunk retention, normal-map alignment, picking, grass reuse, slider dragging, and GL resource ownership.

The frame-submission changes passed all 29 camera tests, the native hex-text/depth/ownership checks and the native water/building/resource checks. Static label vertices match the prior font-cache path bit for bit; the native pixel comparison also matches exactly, including overlapping alpha glyphs and multiple font textures. Tests exercise incremental atlas growth, unsigned-short index/page limits, hidden chunk ranges, unchanged-cache reuse, subsequent SpriteBatch drawing and mesh disposal.

The earlier terrain-submission revision was compared on the same frozen scene in 12 combinations: orthographic/perspective, top/isometric/low-angle, and opaque/faded occupied buildings, with water, trees, decals and mixed relief materials. Eleven cases were pixel-identical; one differed at one pixel by one 8-bit channel value, also seen between unchanged reference frames. That comparison allowed at most eight changed pixels out of 1,152,000, each with a maximum channel difference of one; the newer renderer's reference-noise handling is documented above. Both comparisons and their measured differences are logged. The frame-rate logs for that earlier revision are `fps-paired-before-final.log` and `fps-paired-after-final.log`, with corresponding JFR recordings; the earlier pair uses `fps-paired-before.log` and `fps-paired-after.log`.

Isolated numerical comparisons checked 600,000 river queries and 200,000 clipping inputs against their previous implementations. The resulting river values and 152,086 emitted clipping triangles matched bit for bit. The first deployment screenshot comparison was pixel-identical. The final integrated 24×24 screenshot retained the same appearance, with a mean absolute difference of 0.00152 per 8-bit color channel; 9 of 1,152,000 pixels differed by more than one channel value. Concurrent terrain work also changed submitted geometry slightly. No visual-quality reduction was used to obtain the measured speedups.

Remaining constraints: changing terrain-shape settings or the board's minimum rendered floor still requires a whole-board rebuild. The LoD switch and pixel thresholds do not. Atlas compaction or switching its normal-map format can relocate all slots. Those cases must not be confused with ordinary local editor edits.
