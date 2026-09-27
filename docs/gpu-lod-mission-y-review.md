# Terrain LoD review and Mission Y profiling — September 27, 2026

Baseline: `72e56e4dc7` (`seams between hexes + lod`). Production classes/resources were frozen before editing;
each comparison uses the same benchmark harness. This review was completed in four passes without subagents.

## Four review/change/validation passes

1. **Frame allocation and cache correctness.** Terrain selection allocated threshold arrays and enum clones per
   chunk; tree collection constructed asset names and batch keys repeatedly; terrain batching recreated its range
   wrappers every frame. Reuse the fixed tier table, species/pass/tier slots and batch scratch storage. A ready
   cached chunk was unnecessarily rebuilt after another chunk had been installed that frame; defer the cache swap
   instead. The existing one-install-per-frame limit, eight-chunk replacement cache and canonical seams remain.
2. **Actual draws and static submission.** The prop page batch accepted only plain diffuse colors, leaving hundreds
   of compatible textured props separate. Permit opaque diffuse textures too, retaining complete material-value
   comparisons, index limits and existing spatial pages/culling. Snapshot static terrain renderables once per
   chunk instead of traversing their model instances each frame. Both color and depth snapshots borrow meshes;
   chunks still own and dispose GPU resources. Terrain material groups still issue individual hardware draws.
3. **Refinement/build CPU cost.** Rough boulders repeatedly sampled duplicated corners against the entire ground
   mesh. Test unique corners against triangles overlapping the rock footprint, using the existing height-query
   tolerance. Background detail work now has at most two dedicated ForkJoin workers instead of expanding onto the
   common pool. The render thread still owns material collection, uploads and disposal; obsolete work still goes
   through the existing generation/revision checks.
4. **Feature geometry and shader review.** Mission Y has 1,938 Rough hexes. Their rocks previously retained full
   geometry at every terrain tier. COARSE and DISTANT use fewer cuts of the same seeded convex rock library;
   FULL/MEDIUM and submerged rocks keep the original geometry. Shared shader textures are still touched on every
   material draw so the LRU binder cannot silently evict them; unchanged sampler units no longer need another
   uniform upload. Debris normal sampling is skipped when normal maps are disabled.

   Live-capture validation then found a larger panning hotspot: every viewport change repainted tactical images
   across the entire visible rectangle. In a medium Mission Y pan this accounted for about 18 GiB of sampled
   allocation. Camera-only changes now capture the rectangle difference and retain overlapping tile snapshots.
   Existing terrain, painter-revision and phase invalidation still refresh the complete visible area; images
   leaving the viewport are released. This reuses Swing's rectangle-difference helper and the existing painters.

Review rejected an intermediate rock tier whose clipped solid failed the closed-edge regression. Medium detail
therefore keeps the full rock mesh. It also rejected slope-dependent early returns around implicit-derivative
normal-map reads: their small potential saving was not worth introducing another filtering risk.

## Measurement boundaries

Mission Y is `mm-data/data/boards/unofficial/EVS/80x150 Mission Y.board`, 80×150 hexes. Tests use an RTX 4070 Laptop
GPU, Java 21, a 4 GiB Java heap, 2560×1440, VSync disabled, 240 warmup frames and 1,800 measured frames per view.
The scripted views are fit-board overview, center at zoom 2 or 0.5, and a fixed-distance pan. Nearby geometry is
allowed to settle before stationary measurements; panning includes new LoD work. The cursor stays at viewport
center without moving the desktop pointer. Both stopped and live Swing capture have been exercised.

The old benchmark used the requested square test size to center even a loaded rectangular map. It now uses the
loaded scene dimensions. GPU query objects are exercised during warmup and reused between views; draw counting
runs before a further warmup, and its GL interceptors are removed before timed frames. Trailing queries cannot
leak into the next scenario. Screenshots and explicit garbage collection are outside measured intervals.

Uncapped average FPS was unstable on this host: some runs paused roughly 0.5 seconds in `glfwSwapBuffers`, outside
the timed renderer stages. This occurred with both hidden and visible windows; making the window visible alone
did not eliminate it. Do not treat a favorable average from another run as a proven fix for that stutter. Stage
timings, geometry/draw counts, and frame-time percentiles are reported separately. GPU timestamps include gaps
while the CPU feeds commands, and are not pure shader execution time. A one-Atlas fixture does not reproduce the
user's full battle/client configuration or establish that the reported 50–60 FPS case is fixed.

## Mission Y results

Draw counters below are actual GL calls for the opaque stage, including terrain, props and trees. They exclude
the separate shadow, transparent-water, tactical-overlay and UI passes. Counts are stable across the repeated runs.

| View at 2560×1440 | Baseline draws | Reviewed draws | Baseline indices | Reviewed indices |
| --- | ---: | ---: | ---: | ---: |
| Overview, COARSE terrain | 947 | 749 | 15,009,270 | 12,086,910 |
| Medium zoom 2, visible MEDIUM terrain | 655 | 522 | 12,102,264 | 12,102,264 |
| Close zoom 0.5, visible FULL terrain | 271 | 229 | 1,652,451 | 1,652,451 |
| Medium pan, sampled draw frame | 652 | 519 | 12,081,576 | 12,081,576 |

Overview: **20.9% fewer opaque draws and 19.5% fewer indices**. Medium/close retain the original geometry while
reducing draws by 20.3%/15.5%. The overview previously contained 503 terrain draws, 364 textured-prop draws,
27 plain-prop draws, 17 instanced-tree draws and 36 opaque-overlay draws. Textured props fall to 176 draws;
coarser boulders remove ten more terrain mesh splits. This is actual geometry merging, not reporting a material
group as one draw. Props still use 101 different texture sets and 110 material values in that view.

Before the final viewport-capture correction, the live-capture pair (`baseline/live-mid`, `reviewed/live-mid`) was:

| View | Median frame interval, before → after | p95 interval, before → after | Opaque CPU median, before → after |
| --- | ---: | ---: | ---: |
| Overview | 3.454 → 3.039 ms | 4.230 → 4.220 ms | 1.814 → 1.172 ms |
| Medium stationary | 4.317 → 3.952 ms | 4.627 → 4.358 ms | 1.406 → 1.174 ms |
| Medium pan | 6.264 → 6.126 ms | 7.856 → 8.127 ms | 1.621 → 1.440 ms |

The pan's p95 did **not** improve in this pair. Full-run average FPS was 264/236/143 before and 168/179/159 after;
the inconsistent stationary averages include the presentation stalls described above. Median frame intervals
and lower draw counts are encouraging, but the data does not support claiming a universal FPS or stutter fix.
Close zoom in the separate stopped-capture pair measured 1.871 → 1.887 ms median and 2.812 → 2.403 ms p95:
no meaningful median FPS change there despite fewer draws.

With the viewport correction included (`completed/live-mid`), the live pan improves from the baseline's
**6.264/7.856 ms median/p95 to 5.602/6.337 ms**, with p99 **12.663 → 7.841 ms**. Average throughput is
**143.17 → 178.53 FPS** in this pair, although the baseline includes a presentation stall. More directly,
sampled allocation over the same 1,800 panning frames falls **20,955.7 → 1,217.8 MiB** (about **94% less**),
and measured GC pauses fall **258.1 → 9.3 ms**. Compared with the reviewed renderer immediately before the capture
correction, allocation falls 18,495.6 → 1,217.8 MiB. `BoardView.drawHex` drops from about 18.26 GB to 0.29 GB;
AWT no longer dominates sampled running stacks. The remaining allocation is largely terrain refinement.

The completed run's overview/medium stationary median intervals are **3.081/3.980 ms**, with p95
**3.785/4.539 ms**; average FPS is **322.71/173.02**, the latter still affected by a 591 ms presentation stall.
This distinction is why the headline result is reduced panning work and improved frame-time percentiles, not a
claim that all window-presentation stalls disappeared. First terrain frame is 14.90 seconds; medium refinement
settles in 9.74 seconds with a 47.95 ms maximum render submission.

First terrain frame with live capture fell **22.62 → 14.12 seconds**; medium-zoom visible refinement settled in
**12.84 → 9.12 seconds**. Its largest render submission was 64.16 → 45.54 ms, so cold refinement still has hitches.
Stopped-capture overview heap after collection fell about **561 → 494 MiB**; this excludes native/GPU memory.
The 1,800-frame overview allocation sample fell **220.3 → 71.0 MiB** between `baseline/mid-warm` and `final/mid`;
these JFR estimates are allocation churn, not retained heap. That intermediate `final` snapshot still had the
subsequently removed normal-map early return; its geometry, allocation cleanup and batching match the reviewed version.

### The original 3,020-draw fixture

The saved 200×200 medium-settings board was rechecked at its original 1280×900 resolution, with 1,200 sampled
frames. The last commit now submits **3,066 opaque draws**, versus **3,063 after this review**. Of the original
3,066, 3,001 are terrain, 48 are already batched plain props and 17 are instanced trees. The separate water pass
adds 511 draws, each with a distinct chunk water-field input. Mission Y's prop batching gain does not apply here.

The terrain breakdown is 637 grass draws, 544 sand, 121 dirt, 568 grass transition draws, 524 sand transitions,
and 607 special-art draws. Each sculpted family repeats one material across many chunks; those calls are not all
intrinsically necessary. Shared geometry pages could merge many compatible ranges, while preserving culling and
editor updates. Sixteen-bit vertex limits would still split large pages. Material-state reuse currently saves GL
setup but does not merge those meshes. This remains the main draw-reduction opportunity on that fixture.

Its submitted indices fall **31.40 → 29.39 million**, opaque GPU span **7.63 → 6.49 ms**, and collected overview
heap **968 → 922 MiB**. Overview frame median/p95 are **12.64/15.71 → 12.85/17.67 ms**: no demonstrated CPU/frame-time
improvement in this pair. Average FPS (60.03 → 70.09) is misleading because of uneven presentation stalls. Initial
terrain build is **74.95 → 73.67 seconds**, still far too slow. Its shoreline/material construction needs separate
work; this review does not claim to have solved that load time or the 3,000 terrain draws.

## Validation

- 126 focused CPU/integration cases passed across the review: terrain surfaces, rock solids at every tier,
  LoD selection, trees, shader composition, scene capture and invalidation. The water-field seam test now also
  includes submerged rough boulders across FULL/DISTANT neighbors.
- Seven native rendering cases passed: textured/plain prop page pixel equality across camera modes and edits;
  asynchronous terrain LoD installation, disable/re-enable and edits; tree LoD; water texture eviction beyond
  available sampler units; all terrain material families and rain; and Mission Y terrain grouping on/off pixel
  equality at overview/close views across repeated toggles.
- The viewport regression uses a 40×40 fixture, exceeding the legacy painter cache. It compares incremental
  pixels with fresh captures for horizontal/diagonal pans, smaller/larger viewports, disjoint moves and revisits;
  checks retention of overlapping snapshots, release of offscreen images and complete repaint on invalidation.
- Two source-suite assertions failed identically against the frozen last commit: rough ground was expected to
  contain painted stones and trees were expected below foliage height. They now reflect the existing native
  boulder rendering and trees reaching the foliage height, without changing production behavior to satisfy them.
- Scoped Spotless/Checkstyle and `git diff --check` passed. Screenshots were inspected; no claim of exact pixel
  equality is made between different rock LoD tiers or separately animated benchmark runs.

## Reproduction

Local artifacts are under `build/gpu-performance/mission-profile`: the `baseline` snapshot and successive
`pass1`, `pass2`, `pass3`, `pass4`, `final`, `reviewed`, and `completed` snapshots; each run contains JFR,
screenshots and JUnit results. `pass4` is an intermediate experiment; `completed` includes the final viewport
capture correction on top of the reviewed renderer.
The ignored `mission-profile.gradle` runner refreshes benchmark helpers independently of frozen production code.
Use `-DmissionFrozen=true` when comparing an existing snapshot; omitting it compiles/copies the working tree.

Select `*GpuBoardPerformanceSmokeTest.measuresTheCompleteBoardViewWithLiveCapture`, set
`megamek.gpu.performanceFullView=true`, `performanceBoard` to the saved board, `performanceWidth=2560`,
`performanceHeight=1440`, `performanceZoom=2` (or `0.5`), `performanceNoFinish=true`,
`performanceDrawAudit=true`, `performanceWarmupFrames=240`, and `performanceSampleFrames=1800`.
`performanceFrozenCapture=true` isolates render work; omit it to keep live scene capture. All abbreviated
property names above have the `megamek.gpu.` prefix. `performanceVisible=true` displays the benchmark window.

## Remaining limits at the end of the four-pass LoD review

- Initial board construction was still synchronous at this point. The subsequent async implementation is documented below.
- One chunk installation does not bound the complete frame cost: dependent grass, picking, overlays and shadow
  updates also matter. Cold zooms can still hitch even though CPU preparation runs on a worker.
- Terrain mesh splits, distinct materials/texture pages and visible chunks still require many real draws.
  Fewer than ten total scene draws is not a useful invariant for this renderer. A major further reduction needs
  shared indexed buffers and texture-compatible terrain pages (or multi-draw), with explicit handling of local
  edits, culling, seams, shader compatibility and resource lifetime. No new general batching framework was added.
- Canonical shoreline/cliff joins deliberately retain samples across mixed tiers. Distant whole-hex simplification
  that discards those samples would reopen cracks or alter water fields. Do not remove them merely to improve a
  draw/triangle counter.

This review leaves board dimensions out of terrain quality selection and keeps LoD enabled by default.

## Async opportunity evaluation

This section records the initial code/profile evaluation. The implementation and new measurements follow it below.
Async work primarily targets opening, editing, tuning and cold-zoom responsiveness. It does not itself reduce
the 3,063 opaque draws on the 200×200 fixture, steady-state shader cost, or the recorded window-presentation stalls.

### What already runs asynchronously

`GpuTerrain.refine` uses one outstanding chunk job and at most two dedicated ForkJoin workers. It prepares
surfaces, water-field pixels and tactical topography on the worker, returns to the render thread for material
collection, prepares vertex/index data on the worker, then uploads and installs on the render thread. Generation,
terrain revision, source chunk and desired tier are checked before accepting either handoff.

First construction, geometry edits and tuning changes bypass this scheduling: `GpuTerrain.update` synchronously
prepares and builds every affected chunk. Its surface loops use parallel streams, but the render thread still
waits for them; on this path they also use the common pool rather than the bounded detail pool. The measured
first terrain frame still takes 14.90 seconds on Mission Y and 73.67 seconds on the 200×200 fixture, excluding
the preceding source capture. These are whole-stage times, not predicted async savings.

### Recommended order

| Priority | Opportunity | Smallest useful approach | Expected benefit and boundary |
| --- | --- | --- | --- |
| 1 | Opening, editor and tuning rebuilds | Extend the existing chunk preparation path to these callers; keep one bounded worker pool, prioritize visible dirty chunks and replace superseded pending requests with the latest revision. | Removes CPU construction from the render loop and lets loading/input continue. Total completion time still needs measurement; adding threads alone does not remove the work. |
| 1 | Work before chunk preparation | Move owned CPU preparation of rim artwork, coast/current data and image staging into that same pipeline. First restrict repeated rim construction to affected geometry/artwork. | `BoardRim.material` constructs a full surface before its composition-cache lookup, and `update` visits the entire board for a terrain change. JFR identifies rim composition and terrain/shore construction as startup hotspots. Moving only the final mesh builder would leave this work blocking. |
| 2 | LoD handoff and close-view decoration | Bound material collection, uploads and dependent refreshes by work/time, splitting large pieces where necessary; prepare grass geometry from completed surface snapshots. | One chunk per frame is not a time budget: the completed Mission Y cold zoom still reaches 47.95 ms render submission. Visible grass builds synchronously today; only offscreen prefetch has a 2 ms limit. Preserve the previous valid decoration until its replacement is ready. |
| 3 | Asset file reading and decoding | Prepare image pixels and model data on a worker before first use; keep shared asset-cache mutation, GL creation and disposal on the render thread. | Helps cold asset encounters. `GpuAssets` currently combines parsing/decoding with texture/model creation. Measure its contribution separately before adding more scheduling. |
| 3 | Swing scene capture | Capture bounded regions over successive EDT turns, then publish a complete revision. Offload only processing of owned snapshots. | Keeps the classic UI responsive during large captures. `BoardView.capturePlanarHexes` reads and temporarily changes live painter state and explicitly requires the EDT; wrapping it in a future is unsafe. The large repeated panning capture has already been removed, so this is mainly a cold-load/large-edit opportunity. |

Terrain sliders already debounce drag/repeat input. The missing behavior is coalescing and invalidating expensive
build requests after they are scheduled, not another independent slider timer. An old job is currently rejected
at handoff, but otherwise finishes its CPU work first; cooperative checks between tiles/stages would allow the
latest edit to start sooner. Do not enqueue every intermediate brush or slider value.

### Correctness constraints before extending the worker

- Capture immutable geometry settings with each job. `BoardGeometry` exposes mutable scale fields and
  `BoardRelief` exposes replaceable tuning/geology values, which builders read during execution. End-of-job
  revision checks reject obsolete output but do not give a running builder a consistent set of inputs.
- Keep scene capture and game commands on their existing owner thread. Workers derive rendering data from
  snapshots; they must not read live `BoardView`, entities, camera state or UI controls.
- Retain old chunk resources until replacement publication. Atlas repacking currently disposes/replaces pages:
  old meshes cannot remain displayed against disposed textures or new UV layouts. Atlas pages and all affected
  geometry must have a coherent lifetime and publication boundary.
- Publish surfaces, terrain, water and dependent picking/overlay state consistently. The current picker uses
  a whole-board "finished" test because updates are synchronous; progressive edits need readiness per affected
  region and the existing authoritative-snapshot fallback. Neighboring chunks whose shared boundary changed
  must switch together; independent LoD swaps are safe because their canonical boundaries do not change.
- Bound prepared data as well as worker count. Close/board replacement must invalidate pending jobs, release
  CPU buffers and dispose any staged GL resources on the render thread. Do not prepare all 40,000 hexes into
  a second complete set of heavy surfaces merely to keep a loading animation moving.

The recommended design is one explicit preparation/install path reused by initial load, edits, tuning and LoD,
with a small pending set and visible-work priority. No separate executor per subsystem, shared GL context,
general task framework or worker rendering is needed. Preserve the existing mesh/shader algorithms first;
async scheduling need not reduce final visual quality. World-scale or other global geometry changes need a
coherent scene transition rather than mixing coordinate systems while new chunks arrive.

Validate this work with time to first usable terrain, edit-to-visible latency, p95/p99 and maximum frame interval
during brush strokes/slider changes/cold zoom, peak retained CPU/native/GPU memory, and rapid-edit/close/reopen
regressions. Verify water seams, atlas repacks, overlays and picking while work is pending. Report full-load time
separately from responsiveness. Terrain buffer/page batching remains a separate priority for steady-state FPS.

## Async implementation and validation

Opening, geometry edits, terrain tuning and LoD now share `GpuTerrain`'s existing bounded worker pipeline.
There is one latest request, one chunk in CPU preparation and at most two worker threads. Superseded work checks
its generation between hexes/stages instead of queuing every brush stroke or slider value. Rim artwork,
coast/current metadata, road clipping, surface construction and vertex emission run on the worker. Independent
per-hex preparation uses the same two-thread pool. Unchanged rim artwork is reused during local edits.

The render thread owns all materials, GL uploads and disposal. Material collection proceeds a hex at a time;
uploads proceed a mesh buffer at a time under a shared **2 ms soft budget**. One large asset read, texture-page
upload or first shader compile can still exceed that budget. There is no second GL context or worker-side GL.

Each build captures immutable terrain settings. Rendering and picking use the settings of the displayed
revision, while tuning controls edit the live settings. Terrain replacements publish together, preserving joined
edges, water fields and picking. The old board stays displayed during edits/tuning; initial opening displays
progress. The terrain tuning tab also reports pending build progress. Unit-scale changes do not rebuild terrain
unless fallen limb props require it.

Atlas publication needed two correctness fixes: old pages stay alive while old meshes borrow them, and pending
pixel changes receive new slots rather than repainting a displayed slot. Canceled unpublished pages are disposed;
the repeated-cancellation test forces 32 repacks and checks actual GL texture handles. Finished old chunks retire
under the same frame budget. Shutdown cancels the worker and waits for it before disposing its resources.

### Mission Y measurements

`GpuTerrainAsyncSmokeTest`, `80x150 Mission Y.board`, RTX 4070 Laptop GPU, Java 21, 4 GiB heap, 1280×900,
LoD enabled, VSync disabled. This is a **renderer benchmark with frozen source capture**, drawing old terrain
through the replacement, not an end-to-end Swing editor timing. "Local edit" raises the central hex one level;
"tuning" changes level height from 18 to 19. Completion includes terrain publication and first rendering.

| Operation | Request returns | Replacement complete | Frame p95 / p99 | Largest frame | Peak used Java heap |
| --- | ---: | ---: | ---: | ---: | ---: |
| First construction | 3.234 ms | 36.640 s | 0.957 / 1.992 ms | 490.708 ms | 788.4 MiB |
| Local elevation edit | 1.495 ms | 3.913 s | 5.597 / 59.773 ms | 660.151 ms | 825.5 MiB |
| Level-height tuning | 0.023 ms | 25.267 s | 4.158 / 5.453 ms | 586.150 ms | 1,208.8 MiB |

Opening renders only the loading screen until the initial board is complete, so its short frame times are **not
terrain FPS**. Used-heap peaks include garbage awaiting collection and exclude native/GPU allocations; they are
not retained-memory measurements. An atomic global replacement temporarily keeps the old and new completed
chunk sets; one active CPU chunk does not imply only one extra chunk of total memory.

During the local edit, render submission p95/p99 was 4.726/7.273 ms, with a 616.562 ms outlier. JFR samples in
that interval show first-use `ShaderProgram` linking and prop-mesh assembly after publication. Tuning submission
p95/p99 was 3.462/4.676 ms, maximum 108.456 ms. Presentation also still shows the approximately half-second
`glfwSwapBuffers` stalls observed before this async work. The new pipeline keeps normal frames running during
CPU preparation; **it does not eliminate every hitch or make replacement completion instant**.

An earlier full-view run at 2560×1440, with live Swing capture and 1,800 sampled frames per view, reported:

| View | Frame median | p95 | p99 | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Overview | 6.714 ms | 9.420 ms | 11.220 ms | 529.988 ms |
| Medium zoom (`zoom=2`) | 4.849 ms | 5.416 ms | 6.138 ms | 9.592 ms |
| Panning | 6.413 ms | 7.098 ms | 8.158 ms | 509.703 ms |

That full-view run preceded parallelizing the final per-hex preparation stage. It had 635 opaque submissions at
medium zoom. Concurrent road work changed geometry during this session, so these numbers and the older LoD
baseline are **not a controlled before/after comparison of async scheduling**. No steady-state FPS or draw-count
improvement is attributed to asynchronous preparation.

The full-view benchmark now waits for three idle frames rather than mistaking the gap between completed chunk
jobs for settled LoD. Frame timing, total replacement time and request latency are reported separately.

Artifacts: `build/gpu-performance/mission-async-final-edits.log`,
`build/gpu-performance/mission-profile/async-final/edits/profile.jfr`,
`build/gpu-performance/mission-async-stage2-live-mid.log`, and
`build/gpu-performance/mission-profile/async-stage2/live-mid/review/`.

### Review findings and next limits

- Road footprint clipping was still on the render thread, causing 35–110 ms update stages. It now runs in the
  shared CPU preparation stage. This preserves the road algorithm and output.
- Texture lifetime, canceled-page accumulation, repainting displayed atlas slots, pending water-current ownership,
  live-control versus displayed-settings scopes, board replacement, close/reopen and unnecessary rebuilds for
  unit scale were reviewed and corrected. Redundant installed tuning/revision fields were removed.
- Cold file decoding, complete atlas packing/uploads, first shader linking, prop/grass refresh and first shadow
  rendering still need separate work. The soft budget cannot preempt those calls.
- Whole-board tuning still prepares every affected chunk before publication to keep one coordinate system and
  connected terrain seams. It can take tens of seconds. Further geometry reuse and narrower invalidation would
  reduce this latency; adding unbounded workers or publishing mismatched neighboring chunks would not solve it.
- The current shared checkout began an independent GLB rock/scatter migration during final validation. Its new
  loader requires `data/models/board/rocks.glb`, which was missing in both staged data and mm-data at that point.
  This caused 16 cascading CPU test failures, not assertion failures in the async path. Final isolated validation
  uses the complete frozen `async-final` production snapshot plus the final async cleanup, preserving that work.

Final isolated results: **102 CPU cases and six native GPU cases passed**, with none skipped. Coverage includes
serial/parallel rim pixel equivalence, immutable settings during worker preparation, seams/picking, LoD/cache
reuse, rapid edits and tuning, cancellation, close/reopen, water sampler bindings, published atlas pixels and
canceled page lifetime. The full board UI test exercised both projections, inputs, deployment/range overlays
and asynchronous replacement with a larger board. Its frame-driven actions now run once per completed terrain
frame so a loading interval cannot repeatedly trigger the same map replacement. Logs:
`build/gpu-performance/async-overlay-validation.log` (CPU) and
`build/gpu-performance/async-overlay-native-final.log` (native). Scoped formatting and checkstyle also passed.

The subsequent opaque submission investigation, bounded page batching, paired frame-time measurements and
memory tradeoffs are recorded in [Opaque terrain batching review](gpu-opaque-batching-review.md).
