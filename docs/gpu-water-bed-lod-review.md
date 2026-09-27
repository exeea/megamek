# Water-bed render LoD review — 2026-09-27

The previous terrain cost review found that 15.52 million submitted sculpt-ground indices on the scattered-water
200×200 board carried water attributes. This category includes banks and submerged faces as well as the bed.
Each water hex still constructed and drew a 648-triangle bed at every terrain LoD.

## Implementation

`BoardSurface` retains the original bed for water-depth fields, picking, support, rock placement and neighbouring
walls. Its optional render mesh skips intermediate radial rings only when the resulting height error is small.
The shoreline, all shared mouth samples, material-sector boundaries and centre anchor remain fixed.

Every coarse radial quad covers the same footprint as its original strips. Both new triangle planes are checked
against all original vertices in those strips, including vertices outside the individual triangle. This is
deliberately conservative: it also bounds error inside the old triangles and where old/new diagonals cross.
Checking only the retained corners would incorrectly accept a saddle. Whole rings remain shared between sectors,
so there are no radial T-junctions or mismatched chunk edges.

The height tolerances are hex width / 512, / 128 and / 32 for medium, coarse and distant detail. At the default
refinement thresholds, these are approximately a fifth of a pixel or less. Board size never selects quality.
Full detail is unchanged. Exposed shallow bars, descending streams, frozen water and molten terrain retain their
complete bed. Descending streams also carry a varying water-height vertex attribute, which this patch does not
simplify. The existing Terrain LoD checkbox controls this path; there is no additional setting or cache.

`GpuTerrain` computes bed normals once during worker preparation from the complete canonical bed, before material
splits. The render thread reuses them. Previously it recalculated normals after removing the sections assigned to
mixed bank materials, doing duplicate work and giving the remaining bed different normals at those joins.

An initial interior-fan-only version removed about 5% of the scattered-water board's submitted indices and did
not demonstrate a frame-time improvement. Retaining the profile's necessary rings while dropping intermediate
ones is both more effective and simpler than adding a general mesh decimator.

## Measurement scope

The native diagnostic measures the opaque terrain/props pass, including overlays, with GPU timestamp queries.
It warms 90 frames and measures 180 per mode, with `glFinish` between samples. These measurements are not complete
battle-view FPS. Captures additionally draw the transparent water pass so the visual check includes the actual
water above the bed. The earlier opaque-only captures cannot establish water appearance.

Builds and assets are held fixed for comparison. Concurrent road development changed other renderer classes
during this session; the final reference therefore uses the same frozen final renderer and assets, replacing
only `BoardSurface.renderBed` with its canonical-bed return. This diagnostic override lives under ignored build
outputs; production has no benchmark flag or runtime reference path.

Overview results on the RTX 4070 Laptop GPU:

| Map | Submitted indices before → after | Opaque draws before → after |
| --- | ---: | ---: |
| Scattered water, 200×200, 1280×900 | 25,315,202 → 23,458,330 (**7.3% fewer**) | 1,254 → 1,237 |
| Mission Y, 80×150, 2560×1440 | 14,746,170 → 14,743,146 | 904 → 904 |
| Brandywine, 200×200, 2560×1440 | 15,233,256 → 15,233,256 | 638 → 638 |

The scattered-water map's wet-ground indices fell from 15,521,829 to 13,651,254 (**12.1% fewer**).
At medium zoom, total indices fell from 2,087,481 to 1,968,564 and draws from 255 to 252.
At close zoom, total indices fell from 939,663 to 914,949; draw count stayed at 215.

The final matched scattered-water runs measured these opaque-pass GPU medians (last full-shading round):

| View | Canonical bed | Simplified bed |
| --- | ---: | ---: |
| Overview | 6.7492 ms | 6.4973 ms |
| Medium | 0.9595 ms | 0.9114 ms |
| Close | 0.6420 ms | 0.7107 ms |

These are useful geometry savings, but **no reliable overall FPS improvement was demonstrated**. Overview and
medium improved by roughly 4–5% in the final pair; close was slower by 0.069 ms. Earlier runs varied substantially,
and even Brandywine's unchanged geometry and identical captures produced different timings between processes.
The close-view result is not an improvement and needs a controlled complete-frame comparison before attributing
it to the mesh change or to timing variability. No application FPS claim follows from these measurements.
Opening the stress board still took 146.9 seconds for the reference and 144.7 seconds for the simplified version.

Mission Y's benefit from this particular optimization is negligible. Its overview submits 4.47 million sculpt
cliff indices and 2.10 million rock indices, versus only 0.79 million wet-ground indices. Brandywine retains its
bed profile under the quality limit. An experiment checking both geometry and interpolated water height for
gentle streams also left Brandywine unchanged, so that additional implementation was removed.

Full-water captures for Brandywine were pixel-identical to the matched reference at all three zooms. Mission Y
changed 0.0152% of pixels at overview and 0.0277% at medium zoom; its close capture was identical. Geometry tests,
rather than image similarity alone, check shoreline continuity and the height-error limit.

## Remaining limits

- This is a conservative reduction in bed geometry, not a solution for every water or terrain cost. Curved beds
  can retain all their rings when the error limit requires them.
- Canonical bed and shore construction still runs at full resolution. Slow initial loading and repeated shoreline
  preparation remain separate work.
- Draw calls remain in the hundreds or thousands, depending on the map and view. Removing triangles does not
  eliminate material, chunk and visibility boundaries.
- Refinement's two-millisecond budget is still soft: collecting one tile, uploading one mesh buffer and finishing
  a chunk are indivisible operations that can exceed it. This patch removes the render-thread bed-normal pass,
  but does not bound those remaining operations. They need separate profiling for camera/editor stutters.
- Complete battle-view frame pacing and editor-update latency need separate measurements. The geometry savings
  alone do not establish an application FPS improvement.

## Validation

- **80 CPU cases passed:** bed footprints and seam vertices; positive winding; height error at original vertices,
  triangle centres and edge midpoints; unchanged canonical beds and game-depth anchors; shallow/frozen/descending
  water; optical-depth seams across mixed-detail chunks; existing water surface behavior.
- **7 native GPU cases passed:** all six terrain material families and animated water; LoD checkbox and camera
  refinement; edits superseding pending preparation; close/reopen ownership; water texture binding across chunks;
  opaque page equivalence. Shaders used the existing GLSL 3.30 path on the tested NVIDIA driver.
- Scoped formatting and Checkstyle checks passed. The final source and test logs are
  `water-bed-verified-build.log`, `water-bed-cpu-final.log` and `water-bed-native-validation.log`.

## Reproduction

Use `GpuTerrainScalingSmokeTest` with `megamek.gpu.performanceCost=true` and
`megamek.gpu.performanceBoard=<absolute .board path>`. Optional `performanceWidth` and `performanceHeight` properties
set the viewport. The local isolated runner and reference compiler are
`build/gpu-performance/mission-profile.gradle` and `water-bed-reference.gradle`.

The measured Mission Y/Brandywine renderer and reference classes are under
`build/gpu-performance/mission-profile/water-bed-final/`; final validation and the stress-map comparison are under
`water-bed-verified/`. Logs use the `water-bed-` prefix in
`build/gpu-performance/`. The stress board is `build/gpu-performance/medium-200.board`.

The final matched stress-map logs are `water-bed-verified-medium-200-reference.log` and
`water-bed-verified-medium-200.log`. Mission Y and Brandywine use the `water-bed-final-` logs. Scattered-water
full-water captures changed 0.5234%, 1.1741% and 0.4053% of pixels at overview, medium and close respectively;
the shore outlines and material appearance were inspected alongside the geometry checks.
