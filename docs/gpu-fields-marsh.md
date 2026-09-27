# Planted fields, wetlands, and liquid contacts

The 3D board now draws supported planted fields, marsh, quicksand, and mud through
the natural terrain renderer. This replaces the legacy field prop and flat tile
artwork for those supported hexes. Unsupported combinations retain their original
artwork. Terrain rules, movement, cover modifiers, picking, and unit support remain
owned by the existing game and board surface.

## Appearance and continuity

Fields have world-aligned furrows and instanced crop stalks with leaves and muted
seed heads. Rows continue across adjacent field hexes. Plants vary in height around
half a displayed terrain level; they follow the actual surface, leave road
corridors clear, and thin out at the field perimeter.

Marsh uses a shared world-space wetness field for peat, low sedge hummocks, shallow
reflective pools, and reed/cattail placement. Adjacent marsh hexes therefore share
pool patterns rather than repeating a separate stamp. Quicksand is bare mineral
silt; mud is darker wet soil. Neither receives the marsh's reeds. The small
hummocks affect shading normals; they do not add physical terrain heights or
invent water terrain where the board has none.

Marsh, mud, and quicksand influence nearby shallow water with suspended peat/silt,
calmer waves, and reduced shore foam. The material query reaches the sculpted
waterline inside the water hex. Wet sediment fades out within 1.1 metres below
water; crops and turf do not extend down the bed. Natural grass/sand/rock/snow and
cliff transitions retain the earlier [material transition implementation](gpu-terrain-transitions.md).

Clear, Mars-tinted, volcano-tinted, and hazardous green waters share a continuous
world-space optical mixture. Both surface and submerged bed use the same weights
for absorption, scattering, and shallow tint. A restrained spatial distortion
breaks up the contact. This is a visual mixing band, not fluid transport; liquid
types and hazards remain those of the board. Ice, magma, and water at different
surface elevations do not enter the same mixture. Hazardous water now qualifies
for sculpted terrain, so its bed participates in these transitions too.

## Lighting, wind, and LOD

Crops and reeds use the existing vegetation lighting shader: sunlight, ambient
light, geometry/cloud shadows, and wetness affect their colour. Wind bends the
vertices progressively above fixed roots and adjusts their normals. It does not
rebuild or upload plant instances. Wet ground catches specular light; marsh pools
use the existing sky reflection and rain response, with small wind ripples.
Individual crop/reed shadow casting is not implemented.

Plant LOD0/LOD1/LOD2 reduce both geometry and deterministic subsets of roots, using
the existing projected-size LOD thresholds and hysteresis. Distant ground retains
the row and wetland shader patterns with derivative-based detail fading. All four
terrain mesh tiers (FULL/MEDIUM/COARSE/DISTANT) use the same material coordinates.

`GpuBiomeSurface` owns one RGBA8 texel per hex: biome, aqueous palette, and elevation.
It updates on the render thread when the captured tile data changes and is disposed
with the terrain. The stencil is bounded to nine candidate hexes. It is disabled
on ordinary boards with no special ground and only one water palette. Plant roots
are prepared within the existing frame-budget approach and uploaded only when
their visible patch data changes. There are at most six plant batches: two plant
types times three LODs. No per-hex material textures are generated.

## Native verification

Checked locally on 28 September 2026 using the actual OpenGL renderer at 1280x960:

- `GpuBiomeSmokeTest`: fields, marsh, quicksand, mud, all three plant LODs, all four
  terrain tiers, day/evening lighting, wind, stable instance uploads, terrain edits,
  texture disposal, shallow/deep shore contacts, and the actual Fire And Ice 2 map.
- `GpuLiquidBlendSmokeTest`: all six pairings of the four aqueous palettes, all six
  hex-edge directions, three-way contacts, equal surface/bed mixture weights,
  uniform interiors, and elevation/ice/magma barriers. It compiles and reads back
  the production GLSL mask rather than reproducing its logic in Java.
- `GpuTerrainContactSmokeTest`: the earlier cliff and submerged-material regression.
- `GpuTerrainBlendSmokeTest`: natural material rendering and local-edit versus
  clean-rebuild comparison. The comparison now waits for bounded grass preparation
  and uses the same animation time on both renderers.
- `GpuTerrainTextureBindingSmokeTest`: shared texture residency and atlas ownership.
- Scoped main/test Checkstyle and whitespace checks.

The final focused CPU run passed 55 tests covering biome capture/root placement,
terrain features, surface blending, liquid types, rivers, terrain LOD, and shader
source validation.

The 3x3 special-ground fixture produced these plant counts (not whole-frame cost):

| Plants | LOD0 triangles | LOD1 triangles | LOD2 triangles |
| --- | ---: | ---: | ---: |
| Crops | 243,072 | 41,928 | 10,200 |
| Marsh reeds/sedge | 91,208 | 22,392 | 3,066 |

Each homogeneous fixture used one plant draw at each LOD. Mud and quicksand added
none. The native shader check observed at most 13 active texture samplers, within
the existing 16-sampler budget. These are bounded-work and geometry measurements,
not an FPS benchmark or a claim about other platforms.

The broader CPU check also reported six failures in
`BoardSurfaceTest.roadsRampUpAndDownToAlignedBridgeDecksOverLandAndWater`, at the
assertion about rounding the road's departure from its hub. Bridge-ramp geometry
was not changed by this work. The Fire And Ice 2 capture also exposes gaps around
the existing bridge/road geometry; it is not a clean bill of health for that code.

Native output is in `megamek/build/gpu-board-review/fields-marsh` and
`megamek/build/gpu-board-review/liquid-blends`. Useful captures:

- [Crop field](../megamek/build/gpu-board-review/fields-marsh/FIELD-close.png)
- [Connected marsh](../megamek/build/gpu-board-review/fields-marsh/MARSH-close.png)
- [Shallow water to marsh](../megamek/build/gpu-board-review/fields-marsh/water-MARSH-shallow.png)
- [Shallow water to mud](../megamek/build/gpu-board-review/fields-marsh/water-MUD-shallow.png)
- [Three liquid colours](../megamek/build/gpu-board-review/liquid-blends/blue-red-green-FULL.png)
- [Their submerged bed](../megamek/build/gpu-board-review/liquid-blends/bed-blue-red-green-FULL.png)
- [Fire And Ice 2](../megamek/build/gpu-board-review/fields-marsh/Fire-And-Ice-2-top.png)

## Generated references

The built-in image generator supplied field, marsh, shoreline, and liquid-contact
concepts. Its API does not expose an Imagen 2.5 selector. These images are design
references, not native screenshots or a claim of matching Gaea's output.
[Saved images and complete prompts](../build/fields-marsh-review/references.md)
are in `build/fields-marsh-review`.

The native suites can be rerun with:

```powershell
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuBiomeSmokeTest' --tests '*GpuLiquidBlendSmokeTest' --tests '*GpuTerrainBlendSmokeTest' --tests '*GpuTerrainContactSmokeTest' --tests '*GpuTerrainTextureBindingSmokeTest' --offline
```
