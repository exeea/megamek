# Cliff materials

Exposed hex sides use dedicated 1024 by 1024 rock, sandstone, compacted soil,
concrete and snow materials. Grass exposes the soil family. These are independent
of the top tiles, upper rim masks and hanging cornice art. Both board camera views
use the same wall meshes and material shader.

Each family has three aligned repeating textures under
`mm-data/data/models/board/textures/cliffs/`:

| Map | Channels |
| --- | --- |
| `NAME.png` | RGB base color |
| `NAME-normal.png` | RGB normalized tangent normal, U/right and V/down, neutral 128/128/255 |
| `NAME-surface.png` | R height; G perceptual roughness; B ambient occlusion; A relief range / 0.1 |

Height is zero in recesses and one on protruding planes. The alpha channel gives
the physical depth as a fraction of the existing 96-world-unit texture repeat;
normal slopes are baked using that exact same, quantized range. Rock and sand have
the deepest relief; concrete has shallow pores. Normals retain the renderer's
existing 128-centered encoding.

`GpuAssets` lazily owns and shares the maps across hexes and disposes them with the
other board assets. A family missing any of the three maps uses its original
`textures/terrain/` color material. The dedicated cliff directory bypasses the
legacy 128-texel base-mip restriction. Trilinear mipmaps and up to 8x supported
anisotropic filtering handle distant and oblique surfaces.

`terrain-cliff.frag` transforms detail normals with the actual directed wall edge
and downward V axis. Close-up parallax occlusion mapping traverses 12–24 height
layers and interpolates the intersection. A six-sample light ray shades recesses.
Relief fades out at small projected sizes, grazing views, and quad boundaries;
this keeps the top seam and hex corners attached to the authoritative surface.
On desktop drivers without `GL_ARB_shader_texture_lod`, a single height offset
replaces traversal and height self-shadowing is omitted. That compatibility path
does not require texture gradients inside divergent control flow.

Roughness controls a dielectric GGX highlight. Ambient cavity shading, directional
light, geometry shadows and cloud transmission share the existing board lighting.
Rain darkens the exposed material and reduces roughness according to its existing
water-film response; snow does not receive liquid rain film. The existing Normal
maps control also disables cliff parallax and cavity shading, without rebuilding
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
therefore introduce approximate relief. Parallax changes shading and sampling,
not silhouettes, collision, picking, terrain rules or cast-shadow geometry. The
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
