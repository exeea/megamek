# Cliff materials

All geological terrain now uses the shared materials described in
[Sculpted terrain](gpu-terrain-materials.md), including roadside boulders,
road/ramp side walls and submerged cliffs. `terrain-sculpt.frag` receives the
canonical surface kind, normal and material family even when the hex's ground
keeps a flat road mesh or authored artwork. Both camera views and picking retain
the same geometry.

The September 2026 shader audit found that these surfaces still selected an older
cliff shader. Its outcrop branch muted texture grain and explicitly skipped normal
maps, making roadside boulders look untextured. That shader, its material tags,
uniform bindings and unused ground/cliff map accessors have been removed.
Submerged walls now share the water-triangle clipping used by the rest of the
terrain, so only covered portions receive water tint.

Road paint/artwork, cornices, foliage, vegetation, water and magma retain their
dedicated shaders: their texture coordinates, transparency, animation or lighting
data differ from geological terrain. The audit checked these material selectors
and their live resource references.

## Legacy texture sources

The older 1024 by 1024 rock, sandstone, compacted soil, concrete and snow sets are
retained as asset sources, but are no longer loaded by the terrain renderer.

Each family has three aligned repeating textures under
`mm-data/data/models/board/textures/cliffs/`:

| Map | Channels |
| --- | --- |
| `NAME.png` | RGB base color |
| `NAME-normal.png` | RGB normalized tangent normal, U/right and V/down, neutral 128/128/255 |
| `NAME-surface.png` | R height; G perceptual roughness; B ambient occlusion; A relief range / 0.1 |

Height is zero in recesses and one on protruding planes. The alpha channel gives
the detail depth as a fraction of an eight-visual-metre texture repeat;
normal slopes are baked using that exact same, quantized range. Rock and sand have
the deepest relief; concrete has shallow pores. Normals retain the renderer's
existing 128-centered encoding.

The active sculpt maps remain lazily owned, shared and disposed by `GpuAssets`.
They use world-space projections with mapped normals, cavity shading, board
lighting and weather. Missing sculpt maps retain their existing neutral fallback.
This consolidation makes no measured performance or memory claim.

## Authoring and rebuilding

Original generated color sources are preserved in
`mm-data/tools/cliff-sources/`; all built-in image_gen prompts are recorded in
`mm-data/tools/cliff-texture-prompts.json`. These sources are outside the runtime
data tree. Rebuild from the mm-data root with Python, NumPy and Pillow:

```text
python tools/prepare_cliff_materials.py
python tools/prepare_cliff_materials.py --check
python tools/test_cliff_materials.py
```

The preparation script removes smooth boundary mismatch with a periodic Poisson
solve, reduces broad baked illumination, and derives a multiscale height field.
Color, height, roughness, occlusion and normal derivatives use wrap boundaries.
The manifest records source hashes and baked parameters; `--check` regenerates
and compares all runtime pixels without writing files.

For authored/scanned geometry, add `NAME-height.png` alongside the color source:
an 8-bit or 16-bit linear grayscale height image replaces luminance estimation.
The supplied maps are artistic estimates from generated color sources, not
photogrammetric measurements. Pigment changes or residual baked lighting can
therefore introduce approximate normal relief. The sculpted mesh changes silhouettes, picking and
cast shadows. Neither changes terrain rules. The
board's existing display-space lighting is retained rather than changing the
whole renderer's color-management pipeline.

## Verification

`GpuCliffMaterialsSmokeTest` checks all six wall orientations for every terrain
family on both sculpted terrain and retained road/artwork geometry. It measures
normal-map and rain/snow response, checks shared sculpt textures and fallback,
and captures close-ups. `GpuRoughSmokeTest` checks that actual uploaded roadside
boulder vertices bind the common rock color/normal maps and carry the rock tag.
`GpuWaterCoverageTest`, `GpuWetCliffSmokeTest` and `GpuWaterShaderSmokeTest` cover
water tint boundaries and water shader behavior; `GpuRoadSlopeSmokeTest` captures
road approaches and retaining faces.

Verified on 2026-09-28 on Windows: 44 tests across `BoardRocksTest`,
`BoardRoadSlopeTest`, `BoardWetCliffTest` and `GpuWaterCoverageTest`, all seven
native checks in the five smoke-test classes above, and `checkstyleMain` passed.
The roadside boulders, road walls and underwater cliff captures were inspected.
No references to the removed shader or its material tags remain in runtime code
or tests, and the processed verification resources exclude the deleted shader.
