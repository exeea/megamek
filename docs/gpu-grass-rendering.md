# Instanced grass: cost and rendering review

Grass uses **zero draws when too small to resolve, one at a uniform orthographic scale, and at most two in perspective**. These counts cover the grass colour pass, not the whole board. Grass receives the existing lighting and shadows but does not add individual blade shadow draws.

**GPU Tuning → Terrain → Terrain detail → Grass blades** shows or removes the animated grass immediately. It is enabled by default, and **Defaults** turns it back on. Disabling it skips grass root preparation and drawing; cached blades remain available for re-enabling. The ground material, crops, marsh plants and trees are unaffected.

## What changed

`GpuGroundCover` previously built a separate expanded mesh for each nearby hex. A 65×65 test exposed a second problem: offscreen prefetch exceeded the 384-patch cache and repeatedly evicted and rebuilt patches. With a stationary camera at zoom 0.8, 178 patches were reconstructed in 30 frames.

The replacement stores deterministic surface roots and draws two shared blade templates through OpenGL instancing. Each root uses four floats: position and stable sample rank. The vertex shader supplies curved shape, orientation, colour variation and coherent wind. The fragment shader supplies root darkening, wetness, lighting and shadow reception. Blade templates contain three or seven triangles; no transparent texture cards or new renderer backend are involved.

Grass wind strength interpolates the blade tip's bend angle between its resting shape and the full-wind pose, including the coherent gust. Equal slider steps give equal angular changes throughout 0–1. The tip retains its length, roots stay fixed, and both endpoint poses are preserved. This avoids exhausting most of the visible bend below 0.2 by normalizing an increasingly large displacement; full strength retains the fiftyfold bend and gust inputs.

Visual review exposed abrupt bare clearings from the old fragment shader's coverage cutoff. The shared meadow field now varies blade height and thickness continuously in the vertex shader. Grassy ground retains blades, including shorter growth in those patches. This also removes coverage texture samples and `discard` from the grass fragment shader. Ground colour and grass shape reuse `terrain-meadow.glsl` rather than maintaining two procedural patterns.

Placement continues to use the finished terrain triangles and existing road/surface rules. Root preparation has a soft 2 ms budget per frame. Zooming extends or truncates an ordered root prefix; existing roots never move. A stationary, fully prepared view neither reconstructs roots nor uploads instance buffers. Only visible tiles are prepared; hidden roots may remain cached for a return visit.

Density depends on projected size, never board dimensions. It increases smoothly between 120 and 500 pixels per hex, with up to 4,096 candidate roots per hex before terrain/road filtering. Perspective submits enough candidates for the nearest edge of each tile; the shader evaluates density at each actual root. Fractional width introduces the next blade continuously. At distance, the existing grass material provides coverage without blade geometry.

Grass is submitted after solid terrain, allowing terrain material and page batching to stay enabled. The obsolete cover-fade uniform was removed from terrain state grouping. Trees and grass share the existing core-profile instanced-mesh unbind fix through `GpuInstancedMesh`; buffer ownership stays with their respective renderers.

The curved-blade and density approach follows established GPU vegetation principles described in [AMD's procedural grass article](https://gpuopen.com/learn/mesh_shaders/mesh_shaders-procedural_grass_rendering/). This implementation uses the engine's existing GL 3.3 instancing path; it does not require the mesh-shader backend discussed in that article.

## Measurements

Native RTX 4070 Laptop GPU, 1280×900, seeded 65×65 board with medium terrain settings, terrain LoD on. Shaders were also capped to GLSL 330. Other builds were active on the host. This establishes local behaviour, not performance or driver compatibility on every supported platform.

The paired diagnostic alternates grass off/on/on/off, twice, with 120 measured frames per block. Each state has about 476–480 collected GPU samples. Terrain, trees, water, camera and lighting stay fixed within a view. GPU completion is requested **after** the timed stage to drain timestamp queries. These are isolated renderer timings, excluding units and the full UI, not full-game FPS. The new grass is denser than the old grass, so this is also a quality change.

| View / zoom | Old grass draws | New grass draws | New whole-render GPU median, off → on | Added CPU submission median |
| --- | ---: | ---: | ---: | ---: |
| Medium / 1.5 | 0 | 0 | 1.912 → 1.868 ms | within noise |
| Former onset / 0.8 | 271 | 0 | 1.517 → 1.515 ms | within noise |
| Close / 0.5 | 113 | 1 | 1.192 → 1.247 ms | 0.176 ms |
| Detail / 0.2 | 31 | 1 | 0.777 → 0.956 ms | 0.131 ms |
| Perspective / 0.2 | not measured | 2 | 1.628 → 1.740 ms | 0.254 ms |

The final whole-render draw counts at close/detail are 124/84 with grass, including all other terrain passes; they were 239/115 in the old run. Restoring terrain batching also removes three/one non-grass draws at those views. The new onset threshold avoids spending 271 draws on almost flattened grass. Close-up grass now costs more GPU time than the much sparser old blades (about 0.179 ms added versus 0.064 ms), while its CPU submission cost is lower. Draw-count reduction alone does not imply less GPU work.

All final stationary checks found zero newly constructed patches over 30 frames. The native regression also checks that unchanged views do not upload instance data again.

## Cold preparation and limits

Seven alternating cold-cache trials per view keep shaders warm, finish preceding GPU work, then dispose only grass resources. The reported time includes one renderer submission and its GPU completion.

| View | Old first complete frame | New first complete frame | New frames until all requested roots exist |
| --- | ---: | ---: | ---: |
| Former onset | 114.95 ms | 2.24 ms | 1; blades no longer needed at this scale |
| Close | 50.43 ms | 3.91 ms | 8 |
| Detail | 15.82 ms | 3.56 ms | 53 |
| Perspective | not measured | 5.51 ms | 79 |

These first-frame numbers are **not equivalent completion points**: the new renderer deliberately fills in grass over later frames. The unpaced diagnostic needed about 23/165/356 ms to fill close/detail/perspective views. At a 60 Hz frame cap, 53–79 preparation frames mean roughly 0.9–1.3 seconds. Existing cached roots remain available when increasing density. The budget is soft: one root batch, surface setup or buffer upload may exceed it. No strict worst-case latency guarantee is claimed.

Two blade topology levels still switch at 280 projected pixels. Density transitions are continuous, but the small silhouette change between those templates is not cross-faded. Repacked instance buffers are uploaded when the visible population changes; stationary views reuse them. Roots remain CPU-prepared against canonical terrain rather than introducing a second GPU height/road representation.

## Validation and artifacts

Native checks cover wind animation, terrain edits, root-cache reuse, tree instancing, close-range terrain page reuse and texture ownership, with GLSL 330. Image comparisons wait for progressive grass preparation to finish, so adding roots cannot falsely pass a wind-animation check.

All six final native cases passed: `GpuTerrainReliefSmokeTest`, `GpuTreeLodSmokeTest`, `GpuTerrainPagesSmokeTest`, and the three `GpuTerrainTextureBindingSmokeTest` cases. Scoped formatting, checkstyle and compilation passed. No GL errors were reported. The 65×65 paired profile also completed successfully after the meadow correction.

Local diagnostic artifacts live under ignored `build/gpu-performance/grass-ab/`:

- `paired-65-complete/review/`: old grass paired timings and captures.
- `shader-final-65/review/`: batching and perspective correction, before smoothing meadow clearings.
- `shader-meadow-65/review/`: final paired timings and orthographic/perspective captures.
- `native-meadow.log`, `native-meadow/`: current-source regression log and relief captures.
- `GrassProfile.java`, `GpuTerrain.java`, `../grass-ab.gradle`: isolated performance harness using the frozen terrain-stall baseline, excluding concurrent unrelated rendering changes.

`GLProfiler`'s vertex counter does not multiply instanced geometry by instance count. Its raw `submittedVertices` field must not be treated as the new grass's total geometry workload.
