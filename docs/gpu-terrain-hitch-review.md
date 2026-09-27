# Terrain update and refinement hitches

The next performance pass targets opening, edits and LoD transitions on 32×17 and 65×65 boards. The renderer's existing asynchronous preparation stays in place; no new worker pools, task queues, quality tiers or persistent geometry caches are introduced.

## Corrected benchmark

The previous async test's simulated single-hex edit also constructed a scene with `light == null`. This disabled directional lighting and caused new shader variants to compile. Its 735 ms maximum therefore was not an isolated edit measurement. A flight recording reproduced shader compilation in this accidental lighting change.

`GpuTerrainAsyncSmokeTest` now preserves all scene metadata during the edit. It measures opening, a centre-hex height edit, terrain-height tuning, medium zoom and close zoom. Slow frames include separate refinement, shadow, opaque and transparent submission times and a matching flight-recording event. Image captures warm the page caches after timing has finished.

## Changes

- **Height queries:** take the scale-dependent edge tolerance once per query, rather than fetching thread-local settings for every triangle rejected during a rock-foundation search. Both paths use the same triangle intersection code, edge tolerance and interpolation. In the initial 65×65 recording, this repeated lookup accounted for 3,504 of 9,754 sampled worker stacks.
- **Rock foundations:** sample each distinct rock vertex once. Flat-shaded polygons repeat their shared corners; repeating the same transformed height query cannot change the minimum support height. The short-lived set borrows the immutable source points and does not alter the rock library.
- **Mesh batching:** read the vertex span used by each indexed part instead of reading its entire source mesh repeatedly. Temporary vertex/index arrays are reused within the page build. Unsigned indices, sparse ranges and normals retain their original values. This adds no persistent buffer cache.
- **GPU uploads:** bind each newly built static terrain mesh during its existing incremental upload step. `MeshBuilder.end()` previously filled CPU buffers and deferred the actual GPU transfer until rendering. Consequently, publication could make the first shadow pass upload a whole rebuilt board at once. Explicit unused attribute locations permit the initial transfer before a rendering shader is selected.

Old terrain remains installed until the replacement revision is complete. Uploads, publication and disposal still belong to the GL thread. Cancelled builds retain the existing retirement path.

## Measurements

These are native OpenGL renderer measurements on an RTX 4070 Laptop GPU, at 1280×900 with LoD enabled. The five phases run in sequence in each process; opening includes first-use assets, while later phases reuse them. Vsync and the foreground frame limiter are off. Flight recording is enabled. There is no `glFinish` in the measured frame loop. These results measure revision completion and frame intervals, not steady full-game FPS; units, the full UI and source capture are excluded.

The baseline and final renderer use the same frozen classes, resources and benchmark, differing only in the four optimizations above. Concurrent road/bridge work is excluded from this comparison and preserved in the working tree. Multiple other builds were active on the host, so these are diagnostic observations rather than controlled speedup estimates.

| Map and operation | Completion before → after | Longest frame before → after |
| --- | ---: | ---: |
| 32×17 opening | 5.41 → 6.88 s | 416 → 378 ms |
| 32×17 one-hex edit | 0.711 → 0.656 s | 85 → 46 ms |
| 32×17 height tuning | 1.89 → 2.81 s | 49 → 144 ms |
| 32×17 medium zoom, no refinement needed | 0.003 → 0.008 s | 1.3 → 2.5 ms |
| 32×17 close zoom | 1.01 → 1.24 s | 33 → 23 ms |
| 65×65 opening | 51.73 → 34.02 s | 211 → 238 ms |
| 65×65 one-hex edit | 2.17 → 1.62 s | 27 → 72 ms |
| 65×65 height tuning | 32.75 → 31.91 s | 77 → 77 ms |
| 65×65 medium zoom | 35.41 → 38.83 s | 33 → 35 ms |
| 65×65 close zoom | 6.49 → 8.03 s | 51 → 128 ms |

The 65×65 opening result is promising, but the mixed timings do **not** establish a general frame-time improvement. Earlier intermediate runs also varied. The changes remove verified redundant CPU work and move static mesh transfers into the existing upload budget; they do not make every remaining operation fit that budget. A single mesh upload, asset load, page build or disposal can still overrun it.

All ten final screenshots (five phases on each board) match their respective baseline pixel for pixel. An intermediate implementation's wider-view captures differed by up to 642 of 1,152,000 pixels; the final comparisons did not reproduce those differences. This validates the captured scenes, not every possible board or graphics driver.

## Validation and remaining work

Native coverage checks batching pixels, unsigned-index boundaries and sparse ranges, culling, source ownership, texture binding, LoD restoration, superseding edits, and close/reopen during preparation. CPU coverage checks height tolerance at different scales, ignored decorative/ice faces, and settings isolation.

The eight native batching, paging, LoD and texture-binding cases passed, including exact isolated batching pixels and no reported GL errors. Scoped formatting/checkstyle and compilation of the current main/test sources passed. The 81-case CPU suite (`BoardSurfaceTest`, `BoardReliefTest`, `BoardRocksTest`, `TerrainSettingsTest`) has 74 passes and seven road/bridge failures on both the frozen baseline and final implementation. The failing case names and messages match exactly; these failures are neither introduced by this pass nor hidden as passes.

The final recording identifies the next work:

1. **Close-range grass preparation:** this recording's first close-zoom submission took 126.5 ms, of which 123.1 ms was opaque rendering; samples include grass generation and array growth. The subsequent [instanced grass pass](gpu-grass-rendering.md) replaces per-hex blade meshes with shared GPU blades, budgets root preparation, and fixes stationary cache churn. Dense grass fills in over later frames. Page builds still allocate and copy inside rendering.
2. **Opening assets:** texture atlas construction, image decoding, model loading and texture transfers still block the render thread. One atlas step took 237 ms. CPU preparation and bounded GL publication need separate treatment.
3. **Shoreline CPU work:** `BoardRiver.Channel.field` became the largest sampled worker leaf after the foundation changes. It accounted for 2,348 samples in the final recording. This is a revision-latency target, not evidence of a steady GPU bottleneck.
4. **Moving shadows:** remeasure their GPU cost once these CPU stalls are addressed. Reducing shadow draws alone will not remove the grass/asset pauses.

GC pauses reached 30 ms in the final 65×65 recording. Mesh upload budgeting is therefore only part of the smoothness problem. No terrain density, shadow quality or rendering feature was reduced in this pass.

## Reproduction artifacts

The opt-in test is `GpuTerrainAsyncSmokeTest`; set `megamek.gpu.performanceBoard` and optionally `megamek.gpu.screenshots`. It preserves lighting and other scene metadata during its simulated edit.

Local diagnostic files are under ignored `build/gpu-performance/`:

- `mission-profile/terrain-stalls-final/`: frozen sources/classes, `baseline-32`, `final-32`, `baseline-65`, `final-65`, their flight recordings and captured images; `native-checks` contains the eight native test results.
- `stalls-final-baseline-32.log`, `stalls-final-patched-32.log`, `stalls-final-baseline-65.log`, `stalls-rocks-final-65.log`: the table's runs.
- `stalls-final-65-analysis.txt`: sampled slow-frame stacks and worker hotspots.
- `opaque-isolation.gradle`, `mission-profile.gradle`, `stalls-profile.gradle`: isolated build and source-variant configuration; `stalls-style.gradle` limits formatting/checkstyle to the touched files.
