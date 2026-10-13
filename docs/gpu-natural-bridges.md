# Natural bridges and bank connections

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardBridge` | Traverse connected spans; draw each span's stored type (built or natural; an untyped span draws built) and a built deck's road material. |
| `BoardNaturalBridge` | Construct the natural arch, top, underside and bank contact facets. |
| `BoardBridgeFooting` | Extend manufactured slab/rails onto actual supporting banks; build the piers under deck joints. |
| `BoardBridgeMesh`, `BoardBridgeTruss` | Generate the built slab, kerbs and steel from the live road and on-deck rail footprint. |
| `BoardBridgeSlope` | Grade generated manufactured facets between connected deck elevations. |
| `BoardRelief` | Reserve entrances and move obstructing decorative rocks onto supported bank positions. |
| `GpuRoads / GpuTerrain` | Apply deck/apron materials and own the installed bridge geometry used for drawing and picking. |

Every connected span is built (manufactured) or natural (a rock formation). `BoardBridge` follows reciprocal
bridge exits at the same absolute deck elevation or one level apart (`BridgeSpan`, the span the editor edits) and
draws the type its hexes store in the appearance owner `bridge` (`HexAppearance.bridgeBuilt`; where they differ,
built wins). The renderer never decodes a type: the legacy conversion (`BoardSceneryLayouts.convert`, run wherever a
board is loaded, batch 9) stores one on every bridge with the one decode, `BridgeSpan.decode`; see
[3D board editor](3d-board-editor.md), Bridge types. A bridge still without one (a transient editor state, a
hand-made scene) draws built, the editor stroke's default; a road approach then only chooses its material. A built span uses the
existing road-to-bridge height checks for approaches; a road beneath the deck remains a ground road. If an approach
exists anywhere along the span, the built bridge inherits its best surface: marked asphalt, unmarked asphalt, gravel,
then dirt. Without one it is plain unmarked asphalt between its kerbs.

Decks one level apart meet halfway between their elevations at the shared edge, in either direction.
The centre retains its authored elevation. Manufactured spans keep a level central hub, with broad
diagonal approaches across the whole carriageway and both rails. The generated bridge triangles are split
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
It adds no texture asset, per-bridge GPU resource or piers: a rock arch never has any (see Piers below). Fine
cracks come from the existing material maps.

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
inset from the rim. The extension keeps the shared slab thickness and rail width. Its short grade
reaches the bank's ground height; asphalt, gravel, dirt, dashes and wheel wear use the same road helpers
as the original deck. Lower-grade road approaches keep their existing fade. Bridge-to-bridge joints
retain their original position in the hex plane, with stepped decks sharing the joint's height.
No foundation is grown down through a lower crossing.

**Manufactured spans.** `BoardBridgeMesh` derives the slab and kerbs from the current road footprint and
the actual on-deck `RailRoute` courses, including explicit junctions and unfinished stubs. Standard and truss
designs share that supporting footprint. Road-only five/six-way spans retain a raised roundabout island;
an on-deck rail opens its centre. Rail geometry still follows the common route model.

**Truss.** A built span whose design is the truss (`BoardBridge.Deck.truss`, from `BridgeSpan.truss`) adds
`BoardBridgeTruss` steel along the exposed carriageway boundary. Continuous chords follow the curve; fitted
support panels leave the carriageway clear. `Deck.joints` selects the joining chord at a connected deck or an
end post at a bank, road landing or open end. Members are clipped and capped at the local hex boundary.
The steel fascia replaces the concrete side faces it covers, avoiding coplanar surfaces.

The span's Body colour, oxide red by default, is carried by its `Part.STEEL` facets.
`BoardBridgeSlope` grades the complete generated slab and steel together. The prepared terrain chunk owns
the resulting shape in the game, editor and previews; flat and sloped decks use those same facets for drawing
and picking. Decals project only onto `Part.TOP`, keeping them off kerbs and steel. No per-mask span model,
recoloured bridge-model cache or per-frame bridge generation is involved.

**Piers.** A bridge hex whose Pillars toggle is on (`HexAppearance.PILLARS`, the appearance owner `bridge`, variant
`bridge/pillars`, a built type; legacy pillar art on a bridge hex turns it on) gets one concrete pier under each joint between two
bridge hexes of its deck, on the shared edge, never at a hex centre: every hex centre stays at least 29.5 model px clear.
`BoardBridgeFooting.pier` decides from scene data alone, the same from both hexes: the decks are `connected`, either
hex has the toggle, no drawn ground road crosses that edge, and the deck there is at least one whole level above both
floors. So a bank, ramp or road landing, an off-board or open end, a one-hex bridge and a deck at ground level (Sirius
V) get none. A rock arch gets none either: the toggle is a built type, so its span is drawn built, and switching a
span to natural clears it. Each hex builds the half of each joint's pier on its own side of the edge (`BoardBridgeFooting.withPiers`,
in the terrain's roads stage, appended to the hex's bridge shape as `Part.PIER` facets): the kit
`bridges/bridge-pier.glb` (cap, shaft and footing, two LODs) is cut once at its mirror plane when it loads and each half
closed by its section there, facing the neighbour, so a half alone (the side view draws one hex) reads as solid from
every side; in the whole pier the two sections lie back to back inside it. Both halves come from the same inputs, so on
the deck they meet exactly with no owner hex. The cap follows the drawn deck underside
above each of its points (`BoardBridge.deckZ` less the 1.5 px `SLAB`, on the slab's grade from
`BoardBridgeSlope.profile` where decks one level apart meet), its top 0.75 px inside the slab;
the shaft stretches in Z only and takes world-space UVs, so its texture never stretches; the footing stands on the
lowest drawn floor under it in either hex (a 5 × 5 grid over it; no loose rock; under ice the bed), buried 6 px with
a 1 px lip, lowered if needed to keep a 2 px shaft; where either hex holds a liquid it sinks out of sight, its top
half a px under that floor, since an uneven bed would break the lip into loose slivers. The kit scales with the hex,
never with the level height. Drawing (concrete), shadows, chunk bounds, rebuilds and picking
come from the bridge shape in every view; a pick selects the hex under the pointer, the side view shows the halves on the inspected hex's edges (under
water through the liquid's tint, down to its bed), and walkable hits (the hover outline) look through piers.
`BoardObstacles` keeps ground cover out of each footing, from below the floor up. The toggle is in `BoardBridge.Deck`,
so changing it rebuilds both joint hexes. Since batch 10 the same code builds the piers of a maglev route whose
Pillars toggle is on (docs/3d-board-editor.md): the room rule (`BoardBridgeFooting.room`), the joint frame, footing
sample, liquid sink, stretched shaft and LoD are parameterised by the kit (`bridges/maglev-pier.glb`, slimmer) and by
the underside carried (the beam's underside on the shared edge, `BoardRail.joint`); the bridge piers it builds are
bit-identical to before. A maglev joint beside a building, industrial plant or fuel tank gets no pier, and a maglev's
piers are picked as its route. Limits: chunks of different LOD can meet on one pier with slightly different
halves; the Tactical View shows no piers (nor decks); the 2D views draw no pier art. A legacy `.board` game shows
them since batch 9, as the game converts the board (pillar art becomes the toggle). `GpuBridgeSmokeTest.piersReachTheDrawnDeckOnEveryDeckKind`
checks paved decks over water and at 1 and 6 levels over dry ground, a one-level joint, a natural span (no piers), a built deck without a road (unmarked, no apron on
its pavement bank), a junction, a
one-hex bridge and a ground-level deck at level heights 18 and 30 (captures `bridges/piers-*.png`).
`corpusExamplesDrawTheTypeTheConversionStores` draws a shipped span per decode reason (road, pillar art, pavement,
rail, building, natural) converted, in the type the conversion stores (captures `bridges/corpus-*-imported.png`).

A manufactured terminal without an attached road keeps full-height rails over the void, then slopes them
down over 1.5 metres of supported bank. Lane markings stop before that taper. Beyond the solid slab, a
seven-metre material apron follows the existing bank triangles (not on a pavement bank, which is paved). Asphalt breaks into smaller fragments
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
reuse carries this geometry with the material batches. Manufactured spans are generated during chunk
preparation, graded and supplemented at their bank ends, then retained until their scene inputs change.
Terminal and pier GLBs remain reusable details; the span itself has no external mesh asset.

## Limits

The natural silhouette is a procedural interpretation of the existing bridge exits, not a geology
simulation or a new designer-authored bridge class. The stored type chooses built or natural, not a built deck's
surface: that still follows the attached roads (a co-located road can belong underneath). A surface choice would
need its own appearance variant and editor control. Existing unsupported or malformed terrain combinations are not
normalized by this renderer.
