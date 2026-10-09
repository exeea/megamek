# Building and bridge asset proportions

These are the runtime dimensions of legacy structures and manufactured bridge
assets. [Modular buildings](gpu-modular-buildings.md) describes the separate
floor-kit contract. Board units are defined by `BoardGeometry`.

## Owners and coordinate contract

| Owner | Responsibility |
| --- | --- |
| `BoardArtwork` | Resolve tileset artwork and its associated model reference. |
| `GpuAssets` | Load/cache authored board models and fit legacy structures to their runtime dimensions. |
| `BoardGeometry` | Convert game levels and model units to displayed board dimensions. |
| `mm-data/tools/build_board_assets.py` | Build crops offline and delegate manufactured bridge generation. |
| `BoardBridge`, `BoardBridgeFooting` | Select a span asset/material and fit terminal geometry to its banks. |

Legacy building GLBs use an 84-by-72 hex footprint, with ground at local Z=0 and
roof at Z=18 in the renderer's Z-up convention. Files use glTF Y-up. This is one
default terrain level and also applies to fuel tanks and industrial structures.

Placement fits actual model bounds to the game's level count, including changes
to board scale or level height. Interior floors and columns use the shell's local
height and placement transform. Facade UVs retain per-level repetition.
Custom modular kits instead stack actual one-level modules without compressing
their floor height to accommodate the roof cap.

## Manufactured bridges

`BoardFeatures.capture` preserves a bridge's exit mask and elevation.
`GpuAssets.model` loads its complete GLB; `GpuTerrain` places the model and
includes its triangles in picking. `GpuRoads` shades the connected road surfaces,
while `BoardSurface` grades the approach without raising the ground below the span.

There are 64 shapes, one for every six-bit exit mask. `bridge.glb` is the
north/south straight span (mask 09); `bridges/bridge-exits-NN.glb` covers the
other masks. Each has one LOD0 group. An exit pattern is not a detail level.

The carriageway is 15 model units wide. Deck Z=0 has a 1.5-unit slab below it
and outside rails reaching Z=2.5. Board scale applies uniformly; terrain-level
height controls placement elevation. Shapes meet the actual hex edges.

Decks use the road system's material, world-space coordinates, wetness and surface
clearance. Changing exit patterns must not rotate/restart the material at a join.
Sides and undersides use shared concrete with repeating face UVs.
Deck-family selection across a connected span is described in [roads](gpu-roads.md).

Continuous decks have rails only on exposed outer boundaries, leaving exits open.
Five/six-exit shapes use roundabouts with a full-width circulating carriageway and
a smaller concrete island. Zero/single-exit shapes have closed terminal edges.
The runtime can display authored masks even though construction/repair rules
during play allow exactly two exits.

## Editing the assets

Authoring polygons live in `mm-data/tools/board-models/bridge-shapes.json`.
From `mm-data`, run
`blender --background --python tools/build_bridge_assets.py` to bake the GLBs;
the main board builder delegates to this same script. Runtime loads those assets
rather than regenerating their topology. If road width changes, update both the
saved outlines and the approach/footing dimensions.

A legacy building is packaged with its original artwork:

```text
building_hard_00.glb   # one-level mesh; processed roof texture embedded
building_hard_00.png   # original building image, including alpha and shadow
```

The footprint tool prepares roof inputs under `tools/board-models/roofs` and
copies the original image beside the GLB. The exporter embeds the processed roof;
shared facade textures remain external. The adjacent PNG is the original image,
not a separate processed roof texture.
