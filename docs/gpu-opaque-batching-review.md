# Opaque terrain batching review — 2026-09-27

Follow-up: [persistent terrain and prop pages](gpu-page-cache-review.md) reuses cached ranges during partial
visibility, batches small decorations, and measures the normal 32×17 and 65×65 workloads. The results below
describe the original page-cache implementation.

## Cause

The saved `build/gpu-performance/medium-200.board` fixture has 625 terrain chunks. At overview, its 3,063 opaque
submissions include 2,998 terrain draws, 48 prop draws and 17 instanced tree draws. Terrain is already grouped
by material **within** a chunk. Its remaining draws repeat the same materials across independent chunk meshes:

| Terrain material | Draws |
| --- | ---: |
| Grass sculpt | 636 |
| Sand sculpt | 543 |
| Dirt sculpt | 121 |
| Grass transitions | 568 |
| Sand transitions | 524 |
| Special artwork | 606 |

Only 11 of the grass draws and 18 of the sand draws exceed one draw per participating chunk. The dominant cause
is chunk/material fragmentation, not the unsigned-short vertex limit. The previous `GpuTerrainBatch` saves
compatible material state but still submits those mesh ranges individually. At overview, CPU submission costs
more than the measured GPU span; Mission Y is more often limited by GPU work.

## Implementation

`GpuTerrainPages` reuses the existing prop mesh-copy routine for small opaque terrain ranges. It groups at most
4×4 neighboring chunks, admits ranges of at most 8,192 indices, and caps each page at 65,536 source indices.
Large terrain ranges keep their existing buffers. This bounds the cost and extra storage of each cache build.
The same eligibility rule as material-state batching excludes blending, alpha tests, instances and transforms;
the copy path also requires indexed triangles.

Only one page can be prepared per frame. Original ranges draw until their replacement is available. A partial
page uses the original visible chunks, so batching does not submit hidden chunks. Panning cannot change the
page's source geometry. Edits and LoD changes invalidate only pages whose source renderables change. Whole-board
replacement and shutdown dispose the caches. Texture/material ownership stays with the original chunks.

Perspective and views with grass use the original ranges: those passes have per-part detail uniforms or depth
ordering that cannot safely be combined by this simple cache. No terrain shape, texture, shader, water field,
shadow geometry, picking rule or LoD threshold changes. This is a bounded submission optimization; it does not
shorten the underlying terrain construction.

## Same-scene comparison

RTX 4070 Laptop GPU, Java 21, 1280×900, LoD enabled, frozen scene capture, two off/on rounds in one process.
Each mode warms for 90 draws, then measures 300. The mean includes GPU completion at the sample's end; CPU
submission percentiles can include queue backpressure. These are terrain-rendering measurements, not app FPS.

| Map / overview | Original | Batched |
| --- | ---: | ---: |
| 200×200 opaque draws | 3,063 | 1,359 |
| 200×200 GL calls | 91,316 | 28,498 |
| 200×200 mean, rounds 1 / 2 | 11.677 / 12.170 ms | 6.591 / 6.575 ms |
| Mission Y opaque draws | 918 | 758 |
| Mission Y mean, rounds 1 / 2 | 2.363 / 2.353 ms | 2.359 / 2.367 ms |

Both maps' overview and close images matched **pixel for pixel** when switching batching off/on. Close views
retain their original draws. Mission Y's draw reduction did not demonstrate a GPU/frame-time gain in this test.

The benchmark now separately counts submitted indices as integers. GLProfiler's floating-point index total
loses low bits above 2^24; reordering the same draws can change that reported total by dozens of indices.

## Complete board view

The combined checkout was measured with live Swing capture, 180 warmup frames and 900 sample frames per mode.
There is no forced GPU finish during timed frames. Original and page modes run in the same process with the
same board, camera, shaders and assets; timings include the complete UI and other render passes.

200×200 medium fixture, 1280×900:

| Overview metric | Original | Pages |
| --- | ---: | ---: |
| Opaque draws | 3,063 | 1,359 |
| Frame interval median | 13.036 ms | 8.486 ms |
| Frame interval p95 | 16.462 ms | 12.472 ms |
| Frame interval p99 | 17.952 ms | 18.509 ms |
| Average FPS | 73.96 | 110.54 |
| Opaque CPU median | 9.319 ms | 4.593 ms |
| Opaque GPU median | 8.060 ms | 6.514 ms |

The average and median improve substantially, but the p99 does not improve in this run. Close views retain 190
opaque draws in both modes; their measured median frame intervals were 2.498 / 2.825 ms. The close moving view
also retains its original 183 draws. Sequential moving runs warm LoD caches differently, so their differing
frame times do not demonstrate a batching gain.

Summing every row of the draw audit (zero omitted rows) gives exactly **29,390,670 submitted opaque indices**
in both overview modes and 2,220,738 in both close modes. The differing GLProfiler float totals are an
accumulation-precision artifact, not missing geometry.

The overview page cache contains **105.389 MiB** of vertex/index capacity. CPU native buffers and GPU buffers
each store a copy; this is not captured by the roughly 2 MiB Java heap increase. This additional geometry is
retained when zooming close so returning to overview does not rebuild every page. The first three page-mode
frames included a 41.566 ms maximum submission: one page per frame bounds work, but is not a hard time budget.

Initial terrain construction still took 164.9 seconds, and close-view refinement took 4.9 seconds. These occur
before the corresponding steady-state samples and are **not** fixed by combining draw submissions.

Mission Y, 2560×1440; the second view uses `zoom=2`:

| Metric | Original | Pages |
| --- | ---: | ---: |
| Overview opaque draws | 977 | 788 |
| Overview frame median / p95 / p99 | 5.173 / 7.962 / 9.192 ms | 3.795 / 5.517 / 6.328 ms |
| Overview average FPS | 184.80 | 249.85 |
| Overview opaque CPU median | 2.743 ms | 1.974 ms |
| Overview opaque GPU median | 2.643 ms | 2.663 ms |
| Medium opaque draws | 691 | 634 |
| Medium frame median / p95 | 4.724 / 4.909 ms | 4.746 / 4.959 ms |
| Medium average FPS | 216.54 | 215.93 |

Mission Y's overview gains come from CPU submission; the medium view remains limited by GPU work, with no
measured frame-time improvement. Its cache holds 21.653 MiB at overview and 12.974 MiB after medium-detail
replacement. Initial loading takes 36.9 seconds and medium refinement takes 18.6 seconds in this run.
These draw counts differ from the earlier terrain-only comparison because the combined checkout includes
the concurrent asset migration.

Brandywine River passed four off/on pixel comparisons at overview and close with the final code. Integer
submission counts remain exactly 8,979,702 at overview and 512,826 close. Overview draws fall from 661 to 565,
using 7.723 MiB of page geometry, but terrain-rendering mean stays around 0.94 ms. Close draws remain 183.

## Validation and limits

The combined snapshot passed 102 terrain CPU tests and nine native cases: page pixels/culling/local replacement,
two prop cases, two LoD cases, three atlas/water-binding cases, and the full board UI. Scoped formatting and
checkstyle passed. The page test also checks the unindexed-mesh fallback and that source meshes remain drawable
after cache disposal.

The final build uses an isolated Gradle project cache and output directory. A concurrent rock asset migration
and disappearing compiler outputs interrupted earlier attempts; the complete combined checkout subsequently
passed validation. Paired final profiles use the same frozen `opaque-combined` classes and current assets for
both modes. The earlier measurements above predate that asset migration and are kept separate.

After those successful profiles and Brandywine's pixel comparison, a concurrent tree migration removed
`tree-slender-lod0.g3dj`. A supplementary 200×200 pixel rerun then failed loading that asset before rendering.
It does not supersede the completed measurements or integer audit, but the newer tree loader/assets have not
been validated by this change. No unrelated asset work was restored or reverted to make the rerun pass.

Remaining priorities: reduce the large distant terrain meshes (the 200×200 overview still submits about
24.5 million grass-surface indices), avoid repeated river/shore sampling during geometry preparation, and
reduce first-use shader/upload/cache hitches. Fewer than ten total draws is not the target of this cache:
it preserves local culling and rebuilds, retains existing material boundaries and avoids duplicating every
dense terrain mesh. Actual frame time remains the acceptance measure.

Artifacts are under `build/gpu-performance/`: `opaque-baseline-*.log`, `opaque-pages-parity-*.log`,
`opaque-native-validation.log`, `opaque-isolated-validation.log`, and the frozen classes/JFR files in
`mission-profile/opaque-baseline/`, `mission-profile/opaque-pages/`, and `mission-profile/opaque-combined/`.
Final paired runs are `opaque-combined-medium200-paired.log` and `opaque-combined-missiony-paired.log`;
the successful river comparison is `opaque-combined-parity-brandywine.log`.

Follow-up: [terrain geometry and material cost review](gpu-terrain-cost-review.md) separates fragment cost from
geometry/submission, reduces distant dry-top geometry, and measures material sampling changes on Mission Y.
