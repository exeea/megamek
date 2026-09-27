# Persistent terrain and prop pages — 2026-09-27

This pass prioritizes normal 32×17 boards and boards up to 65×65. The 200×200 fixtures remain stress tests;
minimizing their draw counter is not the acceptance criterion for everyday play.

## Change

Previously, panning rebuilt a prop page whenever its visible chunk list changed. Terrain pages avoided that
copy but fell back to individual chunk draws whenever a page was partially visible.

`GpuMeshPage` now implements the shared behavior. Both collectors supply all installed source ranges, including
hidden chunks, with a separate visibility flag. A copied page records each source's index range. A partial page
submits consecutive visible ranges together, without including hidden triangles or changing vertex/index buffers.
Replacing a source after an edit, cutaway or LoD change invalidates only its page. Original ranges draw while
replacement pages await preparation. Pages own only their copied meshes; source meshes and materials remain borrowed.

Both collectors prepare at most one page per frame. This removes prop batches' previous all-at-once construction,
but it is not a hard millisecond limit on an individual copy/upload. Pages cover at most 4×4 terrain chunks.
Terrain retains its existing 8,192-index source limit and 65,536-index page budget. A partially visible page can
now be prepared; its hidden geometry is cached but is never submitted for drawing.

Small terrain decorations use the prop cache too. Their existing visibility threshold selects cached ranges,
without changing their geometry, normals, textures or distance threshold. Terrain retains its original rendering
path for perspective and grass views, where its per-part detail uniforms and depth ordering matter.

## Measured draws

RTX 4070 Laptop GPU, Java 21, terrain LoD enabled. Reference and changed runs use the same saved boards and frozen
renderer, with only the page implementation and its terrain integration replaced. The benchmark measures the
complete board view with live source capture, no forced GPU completion, and separate counter frames.
The 65×65 fixture is a saved random map with medium water, mountains, cliffs, woods and rough terrain.

| Board and view | Opaque draws before → after | Complete counter frame before → after |
| --- | ---: | ---: |
| 32×17 Deployment Zone, overview, 1280×900 | 59 → 48 | 182 → 171 |
| 32×17 Deployment Zone, close, 1280×900 | 51 → 48 | 118 → 115 |
| 65×65 medium fixture, overview, 1920×1080 | 411 → 380 | 654 → 623 |
| 65×65 medium fixture, medium zoom, 1920×1080 | 465 → 406 | 790 → 731 |
| 65×65 medium fixture, moving, 1920×1080 | 449 → 392 | 1,303 → 1,246 |

Opaque counts include terrain, props and opaque terrain overlays. Complete counts additionally include the
fixture's units, water, tactical overlays, UI and any shadow refresh in that particular frame. They are not an
average across frames. The 65×65 moving counter frame includes 530 shadow draws; settled frames reuse the shadow map.
The harness calls the second view `close`, but the 65×65 runs explicitly set `performanceZoom=2` (medium zoom).

Submitted opaque index counts are unchanged: 6,702,972 at overview, 9,926,685 at medium zoom and 9,638,781 in the
moving counter frame. This patch reduces submissions and visibility-induced copying; it does not simplify geometry.

## Timing and remaining limits

The first 65×65 pair measured complete-frame medians of 2.982 → 3.051 ms at overview, 3.144 → 3.150 ms at medium
zoom and 4.493 → 4.668 ms while moving. Moving p99 was 7.980 → 6.985 ms, while its maximum increased from
15.840 → 19.352 ms. These mixed results do **not** demonstrate an overall FPS or frame-pacing improvement.
The isolated prop comparison likewise shows fewer draws, but inconsistent timing benefits across views/rounds.

A repeated pair also does not establish a speedup: medians were 3.873 → 4.513 ms at overview, 3.618 → 3.744 ms
at medium zoom and 5.067 → 5.833 ms while moving. Moving p95/p99 improved from 9.406/11.644 to 8.685/10.486 ms.
CPU timings also increased in unchanged stages, so these process-to-process results cannot isolate the reason
for the slower medians. They are retained as a limitation, not attributed to measurement noise or claimed as a gain.
During the same pan, prop page builds increased by 15 in the reference and 4 after the change. The four remaining
builds coincide with terrain LoD replacements; visibility-only changes are covered independently by the native tests.

Cache memory has a cost. At overview, combined prop/terrain page geometry grows from 14.282 to 17.967 MiB.
At medium zoom it grows from 4.320 to 17.705 MiB because partially visible pages retain their complete buffers.
These are vertex/index capacities **per copy**: CPU native buffers and GPU buffers each retain a copy. The medium
view therefore uses about 26.8 MiB more combined native/GPU storage. Java heap counters do not include that cost.
There is only one cache per spatial page; neither visibility history nor the number of pans adds cached versions.

The moving shadow pass costs about 1.9 ms in this fixture. Initial board preparation still takes tens of seconds,
and replacing geometry after zooming still costs substantially more than a steady frame. Visibility-only pans
no longer rebuild prop pages, but actual LoD/source replacements still do. Shadow refresh, terrain preparation
and individual upload/build stalls remain separate performance work.

The final async check is a correctness test, not an editing-latency pass: its single-tile edit completed after
1.31 seconds and included a 735 ms worst frame. Initial opening and whole-board tuning each took about
12 seconds on that 32×17 fixture. These unpaired measurements include first-use work and cannot establish a
regression or improvement, but they confirm that editing/tuning stalls are still unresolved by this cache change.

## Correctness checks

- Native prop tests cover orthographic/perspective cameras, three tilts, textured/plain materials, hidden chunks,
  replacement sources, and 16-bit mesh splits. They compare every indexed position, normal and colour as well as
  pixels. Panning a stable source set performs zero page rebuilds. Disposal leaves borrowed source meshes usable.
- Native terrain page tests cover cold partial pages, contiguous and separated visible ranges, fully hidden pages,
  edits while hidden, unindexed fallback and disposal. Only consecutive visible ranges share a hardware draw.
- The real 65×65 prop/decorations on/off comparison preserves every pixel and the exact integer index count at
  overview, medium and close views, in two rounds per view.
- The strict terrain-pages on/off diagnostic finds 31 differing overview pixels on this map. The original renderer
  fails the same check with the same 31 pixels. Comparing original-renderer vs changed-renderer captures gives
  **zero differing pixels**, both with terrain pages enabled and disabled. This existing draw-order-sensitive
  difference is not fixed here; the diagnostic remains strict and its failure is retained.
- The final combined checkout passed **nine native cases, with no skips or failures**: two prop cases, one page
  case, two LoD/replacement cases, three water texture-binding cases and the async opening/edit/tuning benchmark
  on the 32×17 Deployment Zone board. The first async invocation omitted its required board argument and was
  skipped; the final run supplies it. Scoped Spotless and Checkstyle checks and compilation also passed.

## Reproduction

Sources use the normal build. Local isolated profiling helpers live under ignored `build/gpu-performance/`:
`mission-profile.gradle`, `page-iteration.gradle`, `page-style.gradle` and the saved `medium-65.board` fixture.
Benchmark classes/resources are in `mission-profile/terrain-pages-next/`. Logs use `terrain-pages-` prefixes;
the initial small-board reference is `draws-32x17-deployment.log`.

Concurrent bridge work introduced a new `BoardGeometry.MODEL_LEVEL_HEIGHT` reference during the repeated profile.
Compiling that newer integration source against the earlier frozen classes correctly failed. The repeated changed
run instead uses the already compiled and validated page implementation in `frozen-patched/`; no bridge work was
reverted or included in the matched benchmark.

## Follow-up measurement correction

The later [terrain hitch review](gpu-terrain-hitch-review.md) found that the async benchmark's 735 ms edit frame also disabled lighting and compiled new shader variants. Use the corrected benchmark there for isolated edit and LoD-transition measurements; this report's page-cache and draw-count comparisons are separate.
