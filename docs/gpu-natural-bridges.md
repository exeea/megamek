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

Every connected span is built (manufactured), natural (a rock formation), or implicit (made to carry an elevated way). `BoardBridge` follows reciprocal
bridge exits at the same absolute deck elevation or one level apart (`BridgeSpan`, the span the editor edits) and
draws the type its hexes store in the appearance owner `bridge` (`HexAppearance.bridgeBuilt`; where they differ,
built wins). The renderer never decodes a type: the legacy conversion (`BoardSceneryLayouts.convert`, run wherever a
board is loaded, batch 9) stores one on every bridge with the one decode, `BridgeSpan.decode`; see
[3D board editor](3d-board-editor.md), Bridge types. A bridge still without one (a transient editor state, a
hand-made scene) draws built, the editor stroke's default. A built span uses the
existing road-to-bridge height checks for approaches; a road beneath the deck remains a ground road.
Deck paint comes from that hex's `bridge_road` kind and sides. A clean manufactured
deck is bare concrete; a natural deck retains its rock. Legacy conversion writes the
old span's road-material vote into `bridge_road` once, so rendering never repeats it.
Paint may end halfway along a span without changing its structure or the ground beneath.

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

In Enhanced graphics a span over grass is bare rock (`BoardNaturalBridge.shell`): a meadow does not root on a
thin arch. Across each level passage the bank's cover gives way to the shell's rock over a broken band about six
metres deep, entirely by the bank's edge where the span's top begins (`BoardSurfaceBlend`). The ground's material
and its grass read the same cover, so both fade together. Classic keeps the grass cap.

## Geometry and existing terrain

In Classic graphics, `BoardNaturalBridge` uses a normal six-corner hex cap whose centre
stays at deck height and whose exit sectors slope to the adjoining banks, including
banks without roads. Connected caps derive the same shared corner heights. The cap
uses ordinary dry tileset artwork matching the span's surface. The arch remains hollow,
so an angled view reveals the passage beneath. The installed shell supplies drawing,
picking and road support in both normal and Tactical View. Road and rail courses are
shared with Enhanced; the natural terrain envelope is the deliberate visual difference.

In Enhanced graphics, `BoardNaturalBridge` builds a separate hollow shell with curved, uneven outlines, broad bank shoulders,
an eroded cap and a thick, asymmetric arch underneath. Down each exposed face, broken strata form a rounded lip
over an undercut band and a lower ledge above the recessed underside; medium detail keeps only the lip. Shared terrain normal smoothing joins the broad rock facets; the surface
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

In Enhanced graphics, where both exits around a hex corner rest on ground (an abutment) or on a joined natural span, and so do
those of every span joined there, the rock closes that corner instead of curving away from it
(`BoardNaturalBridge.closes`). Every span at the corner then decides alike, so their outlines meet: the lattice
corner replaces a joint mouth's corner that would overshoot it, and all of them build it at one height (the
bank's top beside a bank both rest on, else the joint's; three joined spans take their mean). Beside ground,
the joint's edge runs on along its line into that ground's rounded-off corner, whose cliff recedes up to about
eight metres there: a bank mouth reaching across that line is cut where it crosses it, so the spans never
overlap, and a shallower mouth gets a small tongue out to the line, built apart from the centre's fan. Between
two grounds, a lone span ends both wide mouths on the corner's ray and seats the corner there as deep as they
reach. Shared edges have no internal walls. An open corner keeps its curved, eroded rim.

A side without an exit narrows to the arch's waist. Where the ground beyond it lies below the supports on both
sides, it keeps near the hex edge instead, as the lip of the hollow the banks continue around. Where a bank's
cliff turns a corner onto such a lower side, the rim follows it to just inside that corner rather than cutting
across (`BoardNaturalBridge.shoulder`), unless the bank's wide mouth already reaches past the corner.

A lone span (no joined natural neighbour) with a free end is an overhang: a thick slab of the cliffs' beds rather
than an arch (Kozice 2609). A free end is an exit with no bank or joined span beyond it and no ground within a level of
the deck beside it, so its rock runs out over open space (`BoardNaturalBridge.free`, `overhang`). Its free ends reach
the hex edge, as the deck does; its other sides stop just inside the hex, except where it roots in a bank or enters a
higher cliff. The outline breaks into blocks along the rock's two vertical joint sets, as large as the family's joints
(`BoardRelief.jointScale`). The caprock and the bed under it stand flush in the front face with a bed line between
them; the beds below step back towards the banks, so the underside climbs in treads. The beds follow the cliffs'
bedding planes (`BoardRelief.bedPlane`), and in Enhanced graphics the side faces take the cliffs' bed colours
(`BoardRelief.bed`). Full detail draws four beds, medium two, coarse and distant one. A span left beside a collapsed
one, its rubble beyond an exit (`Board.collapseBuilding`), is never an overhang, whatever its other exits: it keeps its
arch.

Only the exposed rim erodes. Mouths, joints and closed corners keep the plane they share, and the erosion
fades in over the first few metres of exposed rim. The top's spokes carry the rim heights inwards blended
around the centre (exact at the rim), so a span stepping to a joint one level away, or climbing to a bank
above it, curves smoothly instead of folding into thin wedges; towards a bank the slope reaches the bank's
height at the hex edge, where its cliff stands, rather than only inside the bank.

A deck ramps up to a bank one level above it unless the board marks the bank's side towards the span a cliff top
(`CLIFF_TOP`). There the rock enters the bank's wall at deck height, its corners beside the bank too, and so does a
corner joined spans share beside a bank marked towards either of them. Ground two or more levels above the deck is
always such a wall to the span (`BoardBridge.bank`), although the ground itself draws a step of up to two levels as an
incline unless it is marked (`BoardRim`).

A ramp is one plane rising towards its bank: the mouth's spokes and the free rim beside it take that plane all the way
in, fading out within 60 degrees of a mouth or corner lying below it, which keeps its own height
(`BoardNaturalBridge.reach`); at the rim the mouth keeps the bank's own top. Its spokes' inner rings keep the radii of a
spoke ending at the hex edge, so the fan never folds between a long mouth spoke and a short free one.

At a level bank, the walking surface keeps the bank's height through the entrance rather than eroding
below its lip. The bank owns the top inside its actual scalloped outline wherever the span's top lies on the bank's
level; those top facets are clipped there, avoiding coplanar overlap. Where the span's top climbs above that level
over the bank, towards a higher bank beside it (Kozice 2609 between 2610 and 2510, 1013 between 0914 and 1014), it
stays whole over the bank, so the top never ends in an open cut above the bank's top. Each top edge is divided once
where it crosses the bank's level or the outline of its top, and every face sharing that edge takes the same point:
the walls below the rim, and a joined span across the joint, which also divides the shared edge at its own banks'
level. The clipping runs the bank's outline through those points, so a shallow crossing cannot drift along the edge.
So the shell keeps no T-junction, and the rise meets the bank's top exactly on its level. A bank's island inside a
clipped piece joins the piece's outline along a bridge, as earcut joins holes, or at the point where it touches the
outline; should none be found (a failed assertion in tests), the piece's triangles overlapping the island are dropped,
leaving a gap rather than rock over the bank. `BoardBankClip` holds this clipping.
The supported passage uses upward bank normals, as does the rest of
the bank's edge on a side whose corner the span closes (`BoardNaturalBridge.BankJoin`), while the adjacent
exposed cliff retains its rounding. The buried rock shell remains separate from the bank. Removing the
bridge rebuilds the neighbouring terrain and restores its exposed edge; the surface cache key also
includes the derived natural-bridge type so retyping a span updates this join.

Muddy natural spans keep full coverage on their walking surface and fade deposits down the adjacent
rock shell. Depth is measured below the actual local top edge during mesh preparation, so the fade
also follows descending decks. It uses the existing vertex byte and material samples; the underside
remains bare, and the bridge remains independently removable.

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
The local intersection of deck road and traditional rail exits forms a road/rail corridor,
independently of bridge type or distant banks. Its Middle or Beside footprint widens the
slab and bank support, using the same dimensions and train course as
[ground corridors](gpu-roads.md#road-and-rail-corridors).

**Maglev guideways.** An implicit deck with `bridge_rail:2` and no deck road draws the
existing narrow maglev beam, without the ordinary slab, parapets, landings or apron.
`BoardRail.guideway` derives the bridge's solid and walkable facets from the exact mesh
used by the cached visible beam model. The game, editor, Classic and Enhanced views
therefore pick and support the same beam; its top is four model pixels above its
authoritative deck plane. It remains a real bridge with the normal CF, height, collapse
and one-deck-per-hex rules. An explicit deck or a deck also carrying a road keeps its
ordinary bridge geometry beneath the maglev.

**Truss.** A built span whose design is the truss (`BoardBridge.Deck.truss`, from `BridgeSpan.truss`) adds
`BoardBridgeTruss` steel along the exposed carriageway boundary. Continuous chords follow the curve; fitted
support panels leave the carriageway clear. `Deck.joints` selects the joining chord at a connected deck or an
end post at a bank, road landing or open end. Members are clipped and capped at the local hex boundary.
The steel fascia replaces the concrete side faces it covers, avoiding coplanar surfaces.

The span's Body colour, oxide red by default, is converted to linear colour on its `Part.STEEL` facets.
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
so changing it rebuilds both joint hexes. Implicit maglev guideways use this same bridge
Pillars flag and machinery, with the slimmer `bridges/maglev-pier.glb` kit. These pillars
remain optional: `bridge/implicit` draws the beam alone and `bridge/implicit-pillars`
adds supports without adding a slab. The editor's Pillars control switches these independently of deck height.
The caps follow the actual shared beam underside and grade (`BoardRail.joint`), including a
joint with a maglev carried on an ordinary deck. The room check, footing sampling,
liquid sink, stretched shaft and LoD remain shared. A maglev joint beside a building,
industrial plant or fuel tank gets no pier. Piers belong to the bridge's terrain shape
and select its hex, rather than a retired route object. Limits: chunks of different LOD can meet on one pier with slightly different
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

## Validation

The focused bridge and rail CPU tests passed on 2026-10-18. `BoardRailTest` checks
that an implicit maglev is the narrow beam, including its walkable height and picking
footprint, with no invisible slab or bank landings. `BoardBridgeFootingTest` checks
optional maglev piers, their shared joint/grade, clearance beneath them and structure
exclusions. `WayLayerEditorTest` checks the Pillars action and undo without changing
the implicit guideway into an ordinary bridge.

The NVIDIA `GpuMaglevDeckSmokeTest` passed and its top/angled captures were reviewed
for Karkand's route and station, Koryo's structures and Rail Crossing. These are under
`build/shader-audit/handoff-20261018/verified/maglev-decks/`. Natural and sloped bridge
native checks also passed, with captures in the neighbouring `natural-bridges/` and
`bridge-slopes/` folders. The subsequent authored deck/road and mud captures are in
`build/shader-audit/handoff-20261018/multihex/editor-ways/`; their grouped native/CPU
run and Checkstyle passed in `build/handoff-multihex-verify.log`. These checks establish
the covered geometry and authoring behaviour, not a performance result.

The final bank join and downward mud fade are captured in
`build/shader-audit/handoff-20261018/mud-slide-final/mud-join/editor-ways/`, including
level and descending spans. The native check, all-LOD descending-edge depth regression,
compilation and main Checkstyle passed on 2026-10-19; the angled captures were reviewed.

## Limits

The natural silhouette is a procedural interpretation of the existing bridge exits, not a geology
simulation. The stored type selects its structure; `bridge_road` selects deck road paint,
and ground roads remain independent. Existing unsupported or malformed terrain combinations are not
normalized by this renderer. The guideway has no separate performance claim: its visual
model is reused, while CPU bridge facets supply the ordinary support/picking path.
