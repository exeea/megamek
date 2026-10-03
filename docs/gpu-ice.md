# Ice and black ice

Ice shades the existing lake, road or ground surface. `GpuIceShader` owns the
optical material; `BoardLiquid` and the captured terrain flags decide where it
is present. Rules for discovery, movement and breaking ice stay in the game code.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `GpuIceShader`, `terrain-ice.glsl` | Floes, snow, joints, transmission, reflection and the jagged land-ice edge. |
| `BoardSurface.freeze`, `GpuTerrain` | Lake slab, its edges, ragged margin and floes; iced banks; mesh batching. |
| `GpuBiomeSurface` | Per-hex ice flags (texture B channel) for the land-ice edge. |
| `Terrain`, `ServerHelper.checkEnteringBlackIce`, `MovePathHandler` | Store discovery, reveal black ice during movement and publish the changed hex. |
| `BoardEditorTerrain` | Authoring markers for hidden flags, independently of gameplay appearance. |
| `data/models/board/textures/ice` | Runtime base-color, normal and packed surface maps. |

Source textures, channel definitions and the asset rebuild command are in the
[ice asset guide](../../mm-data/tools/board-models/ice-source/README.md).

## Lake and ground surfaces

A frozen lake is a slab whose top is the level units stand on, one pixel above
open water. It runs on into frozen neighbours and up banks below frozen land. It
breaks raggedly out over open water, shedding floes, and breaks off short of
ice-free shores, leaving a lead with rubble. All of that is drawn only; support
and picking keep the hex's waterline. Ice changes no bed: frozen and open water
at one level share the depths of their mouths and corners
(`BoardSurface.cornerDepth`), so no slit opens between their beds.

All ice shares world-anchored floes: snow drifts, bare grey ice and refrozen
joints, so lake and land ice continue across each other. Land ice meets dry
ground at its level along a shattered border straddling their hex edge: the dry
tile draws its side (`GpuIceShader` modes 4 and 5). Toward water or another
level it breaks off short. Ice never coats ground under water.

Costs: lake ice stays one transparent draw per chunk, blended after open water;
land ice is a shader variant of existing ground draws, and so is its border on
dry neighbours. Measured on Intel Iris Xe at 1400×900, a full-screen iced view
costs about 0.1 ms over the same view without ice. Detail, cracks and joints fade
with projected size; there is no geometric LOD or reflection capture.

## Discovery and editor visibility

Black ice remains `BLACK_ICE` in the rules model after discovery. A discovery bit
on `Terrain` survives snapshots and game serialization; text board files start
hidden for a new game. The server reveals it through its movement-entry check
and sends the changed hex to clients.

Undiscovered black ice has no gameplay surface graphic or terrain-tooltip entry.
The classic tile matcher also avoids letting invisible black-ice or metal metadata
select an unintended base pavement image. Revealing black ice must not replace its
rules type with ordinary lake ice.

The 3D editor receives explicit annotations for hidden terrain flags through
`BoardEditorTerrain`. Gameplay views do not receive those authoring markers.
See [map editing](map-editor-3d.md) for marker selection and edit routing.
