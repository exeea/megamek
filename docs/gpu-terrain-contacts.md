# Terrain contacts

Grass, dirt, sand, rock, snow, magma crust and cooled lava banks share a world-space
material field across level ground, slopes and cliff feet. A tall cliff contributes
its material to the ground immediately below it. The receiving material reaches
only 0.9 metres up the cliff, keeping soil and snow close to the actual contact.
Fine height-based patches break up the interface instead of painting large areas
of the receiving terrain over the wall.

Concrete keeps its existing complete cast slabs for differences of zero, one or
two levels. At differences of three or more levels, the rock foundation can meet
adjacent ground through the shared field. The slab panels retain their original
material. Concrete's contribution to other ground uses loose aggregate. Fitted
concrete boundaries use the same corner shifts as `BoardConcrete` and the support
mesh; material bands follow the constructed outline rather than the old hex edges.

`BoardSurfaceBlend` derives the two volcanic cover IDs from the immutable
`BoardLiquid` snapshot. It does not add game terrain types. Its horizontal field
starts with a 4.5 metre width and broadens modestly below a plateau. World-space
variation and the height limit are independent of camera and mesh detail.
`GpuSurfaceBlend` retains up to four material families at stacked cliff junctions.
Boundary subdivision preserves the existing triangles' area and support heights.

`terrain-materials.glsl` combines colour, normal, cavity, height, roughness and
emission with the same material weights. The shared volcanic helpers supply basalt
evaluation to both volcanic interiors and blended contacts, including the
normal/parallax toggle and cold crust's height-aware texture variation. Heat is
added after lighting and fades with the material's coverage. Moving lava continues
to use the existing separate FFT surface, current field and shared animation clock.

Actual water retains its bank and submerged-bed treatment. Molten banks do not
receive water absorption or caustics. Authored terrain, buildings and frozen tiles
retain their existing protected surfaces.

## Visual references

The built-in `image_gen` tool generated the terrain references in
[terrain-transition-reference](terrain-transition-reference/README.md). The exact
[prompt set](terrain-transition-reference/prompts.json) is saved with the images.
These guide contact shapes and material roles; their photographic rock geometry
is not imported into the game.

## Verification

`BoardSurfaceBlendTest` checks agreement between a cliff and receiving ground in
all six directions, bounded height reach, volcanic level contacts, protected low
concrete slabs and existing water-bank continuity. `GpuSurfaceBlendTest` checks
that palette subdivision retains every contributing material, face area, heights,
vertex roles and detail-level consistency.

`GpuTerrainContactSmokeTest` renders each of the eight families on level ground,
one-level slopes and four-level cliffs, plus night perspective views. Concrete
also has two- and three-level captures. Its existing water/cliff checks retain
coverage of detail-level changes and texture-array disposal. Captures are written
to `megamek/build/gpu-board-review/terrain-contacts/`.

```text
gradlew :megamek:test --tests *BoardSurfaceBlendTest --tests *GpuSurfaceBlendTest
gradlew :megamek:gpuBoardSmoke --tests *GpuTerrainContactSmokeTest --tests *GpuMagmaSmokeTest --tests *GpuTerrainBlendSmokeTest --tests *GpuLiquidBlendSmokeTest
```

The change adds material transitions to the existing geometry; it does not add
simulated sediment transport, thermal cooling or extra displacement geometry.
Volcanic emission retains the limitations documented in [gpu-magma.md](gpu-magma.md).
