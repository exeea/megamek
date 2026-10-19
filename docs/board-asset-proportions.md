# Building and bridge proportions

These are the runtime dimensions of legacy structures and manufactured bridges.
[Modular buildings](gpu-modular-buildings.md) describes the separate floor-kit
contract. Board units are defined by `BoardGeometry`.

## Owners and coordinate contract

| Owner | Responsibility |
| --- | --- |
| `BoardArtwork` | Resolve tileset artwork and its associated model reference. |
| `GpuAssets` | Load/cache authored board models and fit legacy structures to their runtime dimensions. |
| `BoardGeometry` | Convert game levels and model units to displayed board dimensions. |
| `BoardBridgeMesh`, `BoardBridgeTruss` | Generate manufactured spans from the live road and on-deck rail footprint. |
| `BoardBridgeSlope`, `BoardBridgeFooting` | Grade the span and fit its bank landings and optional piers. |

Legacy building GLBs use an 84-by-72 hex footprint, with ground at local Z=0 and
roof at Z=18 in the renderer's Z-up convention. Files use glTF Y-up. This is one
default terrain level and also applies to fuel tanks and industrial structures.

Placement fits actual model bounds to the game's level count, including changes
to board scale or level height. Interior floors and columns use the shell's local
height and placement transform. Facade UVs retain per-level repetition.
Custom modular kits instead stack actual one-level modules without compressing
their floor height to accommodate the roof cap.

## Manufactured bridges

`BoardFeatures.capture` preserves a bridge's exit mask, elevation and appearance.
During terrain chunk preparation, `BoardBridgeMesh` builds its slab and kerbs from
`BoardRoad` and the actual on-deck `RailRoute` courses; `BoardBridgeTruss` adds the
steel members for a truss design. All 64 exit masks use these same builders, and
explicit rail junctions and stubs extend the supporting footprint where needed.

The base carriageway is 15 model units wide. Deck Z=0 has a 1.5-unit slab below it;
standard outside rails reach Z=2.5. A truss has a low steel fascia and members
reaching Z=12.5. Board scale applies uniformly; terrain-level height controls
placement elevation. Shapes meet the actual hex edges, including closed cuts
where a truss member reaches an edge.

`BoardBridgeSlope` grades the generated facets. `GpuTerrain` retains the complete
shape for drawing, picking, decals and rail placement, including flat spans.
`GpuRoads` shades the connected road surfaces, while `BoardSurface` grades the
approach without raising the ground below the span.

Decks use the road system's material, world-space coordinates, wetness and surface
clearance. Changing exit patterns must not rotate/restart the material at a join.
Concrete sides and undersides use repeating face UVs; truss steel uses its span colour.
Deck-family selection across a connected span is described in [roads](gpu-roads.md).

Continuous decks have rails only on exposed outer boundaries, leaving exits open.
Road-only five/six-exit shapes keep their roundabout's raised concrete island;
an on-deck rail opens the centre so tracks remain supported and unobstructed.
Zero/single-exit shapes have closed terminal edges. Rendering supports all masks
even though construction/repair rules during play allow exactly two exits.

Span geometry is derived from the live footprint; there are no baked per-mask
span assets or separate saved authoring outlines to update. Terminal and pier
GLBs remain reusable details. See [bridge geometry](gpu-natural-bridges.md) for
bank connections, truss colours and pier ownership.

## Editing building assets

A legacy building is packaged with its original artwork:

```text
building_hard_00.glb   # one-level mesh; processed roof texture embedded
building_hard_00.png   # original building image, including alpha and shadow
```

Each GLB embeds its processed roof; shared facade textures remain external. The adjacent PNG is the original image,
not a separate processed roof texture.
