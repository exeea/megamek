# Cliff materials

These 1024 by 1024 rock, sandstone, compacted soil, concrete and snow materials now
serve only the exposed sides that are not sculpted: edges of water, road and ramp
hexes. Dry terrain uses the sculpted landform and its own procedural materials; see
[Sculpted terrain](gpu-terrain-materials.md). Both camera views and picking use the
same wall mesh in either case.

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

`GpuAssets` lazily owns and shares the maps across hexes and disposes them with the
other board assets. A family missing any of the three maps uses its original
`textures/terrain/` color material. The dedicated cliff directory bypasses the
legacy 128-texel base-mip restriction. Trilinear mipmaps and up to 8x supported
anisotropic filtering handle distant and oblique surfaces.

`terrain-cliff.frag` blends two continuous world-space wall projections, with
normal perturbations transformed into the same frame. Their aligned color,
normal and cavity samples cross hex edges without switching UV orientation.
Real mesh relief replaces the previous 12–24-step parallax and six-step light
traversal. Normal detail fades at small projected sizes; the larger sculpted
relief remains in the shared terrain geometry. Broad, smooth wall normals avoid
highlighting every tessellation diagonal.

Ambient cavity shading, directional
light, geometry shadows and cloud transmission share the existing board lighting.
Rain darkens the exposed material and adds a sheen according to its existing
water-film response; snow does not receive liquid rain film. The existing Normal
maps control also disables mapped normals and cavity shading, without rebuilding
meshes or texture allocations. No additional wall draw pass is added.

The five material sets occupy approximately 67 MiB of uncompressed RGB8/RGBA8
texture payload including mipmaps if all families are used. This is calculated
storage, not measured driver memory; drivers may expand RGB to RGBA. Texture files
are loaded only for families present on a board. Rendering cost increases for
large visible cliff faces; no FPS or hardware compatibility claim is implied.

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

`GpuCliffMaterialsSmokeTest` opens a hidden native OpenGL context and checks all
six wall orientations for every terrain family, live normal/relief toggling,
rain and snow response, texture sharing and legacy fallback. It captures flat,
relief and wet close-ups under the configured GPU screenshot directory. Existing
terrain normals, cornices, upper rims and field-of-view cliff tests cover the
neighboring render paths. Offline checks cover periodic seams, tangent normal
direction, map alignment, normal lengths and deterministic regeneration.
