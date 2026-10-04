# Natural bridges and bank connections

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardBridge` | Traverse connected spans; choose manufactured road material or a natural span from their approaches. |
| `BoardNaturalBridge` | Construct the natural arch, top, underside and bank contact facets. |
| `BoardBridgeFooting` | Extend manufactured slab/rails onto actual supporting banks. |
| `BoardBridgeSlope` | Grade authored manufactured slabs and rails between connected deck elevations. |
| `BoardRelief` | Reserve entrances and move obstructing decorative rocks onto supported bank positions. |
| `GpuRoads / GpuTerrain` | Apply deck/apron materials and own the installed bridge geometry used for drawing and picking. |

A connected bridge span without an attached road is a natural rock formation. `BoardBridge` follows
reciprocal bridge exits at the same absolute deck elevation or one level apart and uses the existing
road-to-bridge height checks for approaches. A road beneath the deck remains a ground road. If an approach exists anywhere along the span,
the manufactured bridge inherits its best surface: marked asphalt, unmarked asphalt, gravel, then dirt.

Decks one level apart meet halfway between their elevations at the shared edge, in either direction.
The centre retains its authored elevation. Manufactured spans keep a level central hub, with broad
diagonal approaches across the whole carriageway and both rails. The existing GLB triangles are split
at the grade changes and displaced together, preserving the slab thickness, rail height and footprint.
Road materials and markings use those same installed top facets; picking and bounds use the complete
sloped shape. Natural spans grade their rock shell to matching top and underside contacts at every LOD.
Missing reciprocal exits and differences greater than one level remain disconnected.

Natural spans choose the most frequent material family among their distinct connected dry banks: grass,
dirt, sand, rock or snow. Ties follow the existing family order. With no eligible bank, the bridge hex
supplies the family; concrete falls back to rock. All sections of a connected span use the same bank
vote. An edit that changes the chosen surface or switches between natural and manufactured geometry
rebuilds the affected span and its adjoining banks, including across chunk boundaries.

## Geometry and existing terrain

`BoardNaturalBridge` builds a separate hollow shell with curved, uneven outlines, broad bank shoulders,
an eroded cap and a thick, asymmetric arch underneath. Rounded side strata bulge between the chipped
top and recessed underside. Shared terrain normal smoothing joins the broad rock facets; the surface
engine supplies photographic albedo, normal maps, geology, weather response and material batches.
It adds no texture asset, per-bridge GPU resource or
pillars through the lower hex. Fine cracks come from the existing material maps.

The top's centre remains at the authoritative deck elevation. The underside reserves the last whole level
beneath that deck and respects lower feature tops. Ground, roads, water and unit landing supports below
remain independently constructed. This is presentation geometry; it does not change movement rules,
terrain elevations, bridge CF or exits.

Natural mouths retain five contact points at every LOD. Their full rock body extends beyond the deepest
front-facing cliff triangles across its width and height, including adjoining faces around scalloped
corners. Only the bank's near-side edges participate, so a deeply recessed far wall cannot pull the end
through the whole bank. Walkable banks also fit the finished top, ignoring decorative boulders. This seats both the cap
and underside inside the cliff rather than fitting only its upper rim. Connected sections share mouth
corners, underside clearance and arch support influence, and omit internal end walls across mixed LODs.

A cliff above deck height also anchors the natural arch and contributes its rock material. The span's
thicker underside extends into that wall at deck height instead of rising to the cliff top. This keeps
the rock formation beneath spans such as Lava Tubes 1, with the lower passage still open. Such a cliff
is not a walkable bank approach and does not receive a manufactured bridge footing or road tunnel.

Manufactured bridges use `BoardBridgeFooting` to extend each terminal at least one metre into its bank
hex. If the rim has receded, the extension grows until the full slab width has ground underneath, with an
inset from the rim. The extension keeps the authored slab thickness and rail width. Its short grade
reaches the bank's ground height; asphalt, gravel, dirt, dashes and wheel wear use the same road helpers
as the original deck. Lower-grade road approaches keep their existing fade. Bridge-to-bridge joints
retain their original position in the hex plane, with stepped decks sharing the joint's height.
No foundation is grown down through a lower crossing.

A manufactured terminal without an attached road keeps full-height rails over the void, then slopes them
down over 1.5 metres of supported bank. Lane markings stop before that taper. Beyond the solid slab, a
seven-metre material apron follows the existing bank triangles. Asphalt breaks into smaller fragments
over a wider fan of gravel and compacted soil; gravel remains visible beyond the asphalt before thinning
into the bank's own ground. Gravel and dirt decks blend their own material into that ground. The shader
uses the existing height/grain maps to break the material into fragments, with derivative filtering and
the existing texture mipmaps. These masks never fade the physical span or add fill below it. Connected
road ends retain their full coverage and existing material transitions.

`BoardRelief` moves obstructing cliff-rim and field rocks and shrubs beside bridge passages: through each
bank towards the bridge, and along the bridge hex's own deck. It keeps each rock's mesh, scale and
original tint, checks its whole footprint, then seats it on the bank at its new position. Both bridge
styles reserve the same conservative passage width, so changing a distant road material does not change
the bank's ground mesh. Rocks below the deck elevation remain below it. Existing tunnel clearance also
applies to candidate positions. Where the bounded search finds no valid side position, as on a narrow
promontory whose rims close in on the deck, the rock is left out: nothing stands on a deck or its approach.

The immutable bridge facets belong to the installed terrain chunk. Rendering and picking share those
facets and their bounds; an extended section is picked in the hex its visible contact occupies. Chunk
reuse carries this geometry with the material batches. Manufactured assets retain their original mesh and
are supplemented at their bank ends; level sections keep the original model, while sloped sections
reuse its authored geometry in the terrain batches.

## Limits

The natural silhouette is a procedural interpretation of the existing bridge exits, not a geology
simulation or a new designer-authored bridge class. The current board format has no unambiguous explicit
deck-surface override; a co-located road can belong underneath. Adding an override would require its own
persisted field and editor control. Existing unsupported or malformed terrain combinations are not
normalized by this renderer.
