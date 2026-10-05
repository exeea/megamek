# Terrain material contacts

[Docs index](README.md) · [Terrain geometry and materials](gpu-terrain-materials.md)

A contact has two layers: geometry supplies a coherent surface and its role, then the
material field decides which neighboring covers contribute at each world position.
A color blend cannot fix contradictory rim/foot heights or mismatched support triangles.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardSurfaceBlend` | Query world-space family coverage using the owning footprint, neighboring terrain and sample height. |
| `BoardLiquid` | Identify volcanic crust/lava-bank appearances without inventing game terrain types. |
| `BoardRelief / BoardConcrete` | Supply actual boundaries, corner shifts and rim/foot inputs shared by both sides. |
| `GpuSurfaceBlend` | Subdivide shading carriers, pack up to four family contributions and select material palettes. |
| `GpuAssets` | Own the shared material texture arrays and neutral fallback layers. |
| `terrain-materials.glsl` | Combine cover/soil/rock/deposit roles and their color, normal, height, roughness, occlusion and emission. |

## Where cover may spread

Grass, dirt, sand, rock, lunar, snow, magma crust and cooled lava banks share a deterministic
world-space field. The footprint containing a sample owns the neighbor query, even
when another hex's mesh emitted that sample. This is what makes both sides of a seam
evaluate the same cover.

The horizontal field starts with `WIDTH_METRES` (4.5 m) and varies in world space.
Height limits keep the receiving soil/snow near the actual cliff foot: its influence
reaches only 0.9 m up the face. A tall cliff can contribute its own material to the
ground below. At stacked junctions the GPU palette can retain four families.
If authored mixtures contribute more than four at one point, small shading triangles
retain the four strongest contributions; weaker covers at those crowded junctions
are omitted to keep geometry and shader costs bounded.

Concrete slopes, cliffs and underwater retaining walls keep their constructed
material for their full height. They do not blend into neighbouring natural ground.
Fitted boundaries use the same corner shifts as `BoardConcrete`, including the
shoreline and its submerged wall.

Authored special ground, buildings and frozen surfaces retain protected handling.
Roads use their own overlays and joins. Changing a family's eligibility belongs in
the CPU query as well as its shader representation.

## Authored transitions

Legacy `ground_fluff` transitions remain defined in the tileset. GPU artwork capture
intercepts the five recognized transition kinds (desert, grass, tropical grass, Mars
and Moon) and omits their painted overlay from the GPU decal pass. `BoardScene.Tile`
instead captures their target material proportions: strengths 1–5 use 1/6, 2/6, 3/6,
4/6 and 5/6, leaving some base material at both ends. The board and classic artwork
are unchanged. Unknown ground artwork keeps its existing fallback handling.

These weights feed the same world-space contact field as neighboring terrain, so
the native shader blends color, normal, height, roughness and occlusion together;
grass placement reads the same coverage. Authored gradients interpolate the complete
materials, including their colors; ordinary terrain boundaries keep their existing
height-based texture breakup. Lighting and each material's own texture still affect
the displayed pixel color.
An edited strength invalidates neighboring material meshes and grass coverage.

## Contacts inside one family

The ground, soil mantle, exposed face and deposit are separate shading roles even
when both neighboring hexes have the same family.

| Family | Roles that must meet coherently |
| --- | --- |
| Grass | Turf, mineral soil, granite and scree |
| Dirt | Bare dirt, mineral soil, exposed rock and gravel |
| Sand | Sand, sandstone and broken sandstone |
| Rock | Weathered top, exposed granite and scree |
| Lunar | Independent maps initially identical to rock, granite and scree |
| Snow | Snow cover, exposed rock and scree |
| Concrete | Pavement and cast concrete faces |
| Volcanic | Crust/cooled banks and rock, with heat blended by the same coverage |

Role weights vary with slope, rim/foot distance, deposition and material height.
Color, normal, roughness and occlusion must follow those same weights. Use the shared
projection helpers so a top becoming a cliff does not reset texture phase or stretch
the material down the face. Avoid adding a separate transition image for every pair.

## Water and volcanic boundaries

Water banks query eligible adjoining land rather than spreading one water hex's family
over its entire shore. Recessed banks and exposed bars participate; opposite shores
must not paint each other. Beneath water, coverage becomes sediment/stone and living
ground cover fades out. Clip dry/submerged portions before refining their materials so
absorption and caustics apply only where water actually covers the surface.

Molten banks do not receive water absorption or caustics. Volcanic interiors and boundary
palettes call the same solid-material helper. Heat is added after reflected lighting
and fades with volcanic coverage; moving lava remains in its own liquid surface path.

## Changes that must stay coupled

`GpuSurfaceBlend` refines attributes by interpolating existing faces. Preserve their
planes, area and support height; coincident top/cliff vertices may deliberately carry
different roles and UV meanings. Camera detail can change sampling density, but the
coverage field and projection stay world-anchored.

Neighbor-aware scene keys invalidate material coverage and related grass placement
after edits. Uniform interiors keep the ordinary material path; only affected boundaries
need the extra palette attributes. Texture arrays remain owned and disposed by
`GpuAssets`.
