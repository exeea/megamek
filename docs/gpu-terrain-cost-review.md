# Terrain geometry and material cost review — 2026-09-27

This follows the opaque batching work. The next two costs are different: material evaluation at normal zoom,
and excessive submitted terrain geometry at large-map overview. Reducing draw calls alone does not remove either.

## Changes

- Natural terrain samples colour/height first, then samples normals/cavity only for layers with a nonzero final
  blend weight. A conservative bound also rejects layers whose maximum possible height score cannot contribute.
  Coverage and the height-blending formula remain unchanged.
- All material layers share their projection and explicit texture gradients. Derivatives are calculated before
  branching on layer weights; conditional texture reads therefore do not depend on undefined implicit mip
  derivatives. This requires no newer API than the existing GLSL 3.30 minimum.
- Distant dry tops use a center fan when every triangle faces upward. They retain every boundary vertex, including
  the full samples shared with a higher-detail chunk. Notched outlines retain the existing rim band. Full,
  medium and coarse terrain, water beds and shorelines keep their existing geometry.
- Opaque shader groups use encounter order instead of `System.identityHashCode`. Recreating programs no longer
  randomly reorders groups. Grass still uses the original depth order, and transparent parts remain depth sorted.

The fragment-shader diagnostic is injected by the opt-in benchmark; there is no profiling branch or uniform in
the production shader. The new mesh path adds no cache, worker, tuning control or alternate source of game state.

## Measurements

RTX 4070 Laptop GPU, native desktop OpenGL. The diagnostic renders the opaque terrain/props pass, including its
overlays, rather than the complete battle-view frame. Shader comparisons alternate original/optimized twice on
the same installed meshes, textures, animation time and camera. Each round warms 90 frames and measures 180;
GPU timestamps completed without dropped samples. `glFinish` separates samples, so these are GPU cost
measurements, **not application FPS or frame-pacing results**.

Final Mission Y comparison, 2560×1440, current renderer and matching assets:

| View | Original GPU median, second round | Optimized GPU median | Reduction |
| --- | ---: | ---: | ---: |
| Overview | 3.3751 ms | 2.7546 ms | 18.4% |
| Medium, zoom 2 | 3.5461 ms | 2.9819 ms | 15.9% |
| Close, zoom 0.5 | 1.2227 ms | 1.1325 ms | 7.4% |

The first round also improved at all three views. Both variants use the final deterministic sorter; the table
isolates the material change, rather than crediting changes in draw ordering to shader optimization.

Earlier controlled cost probes held geometry and camera fixed while either reducing viewport pixels to a
quarter or replacing the sculpt fragment body with a flat colour:

| Scene/view | Normal GPU median | Quarter pixels | Flat sculpt fragment |
| --- | ---: | ---: | ---: |
| Mission Y overview | 3.2635 ms | 2.2200 ms | 2.1012 ms |
| Mission Y medium | 3.4765 ms | 2.0675 ms | 1.6876 ms |
| Mission Y close | 1.2165 ms | 0.7404 ms | 0.6492 ms |
| Scattered-water 200×200 overview | 7.9790 ms | 8.0865 ms | 7.7373 ms |

These earlier probes identify the type of bottleneck. They must not be subtracted from the final shader table:
the surrounding asset work and draw ordering changed between builds. In particular, the large-map overview
has substantial submission/geometry cost; its GPU timestamps can include CPU submission bubbles.

The distant-top change reduced the 200×200 overview's sculpted-ground index count from **24,378,423 to
20,435,583**, a reduction of **3,942,840 (16.2%)**. Cliff, rock and wet-rock counts were unchanged. Of the remaining
ground indices, **15,521,829** carry water data: beds, banks and related submerged surfaces. This is the next large
geometry target. These are submitted indices, not unique vertices or bytes of memory.

An early before/after run also reported total draws changing from 1,359 to 1,254 and total indices from about
29.39M to 25.32M. Do not attribute those entire changes or its frame times to this patch: updated tree assets
were incompatible with the frozen old renderer and visibly stretched its trees. That cross-build FPS
comparison was discarded. The final Mission Y paired run uses the current compatible renderer.

## Visual and regression checks

The layer-skipping optimization was compared against the same explicit-gradient shader with pruning disabled:
all three Mission Y views were pixel-identical. This checks that the optimization does not discard a contributing
material layer. Comparing against the original implicit-gradient shader is intentionally a different check:
filtering changes localized pixels. In the final Mission Y captures, mean absolute RGBA channel differences on
a 0–255 scale were 0.146 overview, 0.161 medium and 0.017 close. Difference crops and full views were inspected;
no new terrain holes or loss of the material treatment was observed. This is not a claim of identical output.

Brandywine River also compiled and rendered the paired shader variants at GLSL 3.30, at three zooms. Its close
view differed by at most one channel level. Overview/medium had localized filtering differences (mean absolute
channel error 0.010/0.013); those captures were inspected too. These opaque-pass captures are not a substitute
for checking animated water, which the material integration test renders separately.

Validation completed:

- 92 distinct CPU cases across `BoardReliefTest`, `BoardSculptTest`, `BoardSurfaceTest`, `TerrainLodTest` and
  `GpuOpaqueSorterTest`. New cases cover dry-top footprint and mixed-detail seams, upward cliff-top triangles,
  and stable ordering after program recreation.
- Nine native cases under GLSL 3.30: terrain-material families, two LoD lifecycle cases, three atlas/water binding
  cases, terrain pages and two prop-batching cases. They cover normal-map toggles, animated water/wind,
  cached-detail reuse, edits superseding asynchronous work, page pixel parity, resource ownership and culling.
- Scoped Java formatting/checkstyle and whitespace checks passed.

The material integration test initially stopped at an outdated grass-cache assertion that prohibited even
looking up the displayed surface. The cache correctly uses that surface's identity to notice a new LoD mesh.
The test now retains the same published surface for an overlay-only update and separately checks replacement
when new terrain geometry is published. The complete material test then passed, including all six families.

## Remaining work

1. Decouple rendered water-bed detail from the canonical bed sampled by water optics. Preserve shoreline,
   waterfall and cross-chunk contacts. Simply lowering the existing basin sample count would change the water
   depth field and risk reintroducing water seams. The subsequent [water-bed LoD review](gpu-water-bed-lod-review.md)
   implements and measures this separation.
2. Reduce repeated river/shore preparation during initial loading and refinement. This patch does not solve
   the roughly 150-second construction time observed for the scattered-water stress map.
3. Repeat complete battle-view frame-time and p95/p99 measurements with a matching, fixed asset snapshot.
   Shader cost improvements alone do not prove smoother camera motion or faster editor updates.

## Reproduction and artifacts

`GpuTerrainScalingSmokeTest` accepts `megamek.gpu.performanceBoard` and optionally
`megamek.gpu.performanceWidth` / `megamek.gpu.performanceHeight`. Set `megamek.gpu.performanceCost=true` for
the three-view diagnostic; add `megamek.gpu.performanceShaderReference=<absolute terrain-materials.glsl path>`
for interleaved shader comparisons. These properties must be forwarded to the test JVM. Existing submission
and page comparisons still run when `performanceCost` is absent.

This checkout's isolated runner is `build/gpu-performance/mission-profile.gradle`, with
`opaque-isolation.gradle` and project cache `build/gpu-performance/opaque-gradle-state`. It forwards these
properties and supports frozen compiler outputs so concurrent source changes do not contaminate a pair.
Assets must also match those outputs; freezing classes alone is insufficient during an asset migration.

Key logs under `build/gpu-performance/`:

- `terrain-cost-missiony-current.log`: final paired numbers in the table.
- `terrain-cost-missiony-fixed-order.log`: pixel-identical pruning control.
- `terrain-cost-missiony-baseline-verified.log`, `terrain-cost-medium200-baseline.log`: cost-isolation probes.
- `terrain-cost-medium200-optimized.log`: ground/water geometry attribution; not an FPS comparison.
- `terrain-cost-final-validation.log`: final CPU and scoped style checks.
- `terrain-cost-native-330.log`, `terrain-cost-materials-final-330.log`: eight passing native cases plus the corrected
  material integration rerun.

Final material images are in `mission-profile/terrain-cost-final/materials-final-330/review/`; final Mission Y shader
captures are in `mission-profile/terrain-cost-final/missiony-paired-current/review/`.
