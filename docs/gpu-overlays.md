# Tactical overlays, markers and occlusion

Gameplay visibility is decided before rendering. Native overlays present the
client's captured classifications, paths and annotations; depth effects only
change how already-visible information is displayed.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardClientState`, `BoardTacticalGraphics` | Capture the shared painters' tactical output and playback category on Swing. |
| `BoardFieldOfView`, `GpuFieldOfView` | Share the client LOS/sensor classification and upload its per-hex rendering mask. |
| `BoardTacticalGeometry`, `BoardFiringGeometry`, `BoardDeploymentGeometry` | Convert captured paths, boundaries and footprints into common board geometry. |
| `GpuTactical`, `GpuFireControl` | Draw tactical primitives, range walls and attack lines. |
| `BoardMarker`, `GpuMarkers`, `GpuCutout` | Describe visible point symbols and build/place their raised artwork. |
| `UnitAnnotations`, `GpuHexText` | Present unit annotations and board text with the relevant placement/visibility rules. |
| `GpuHexGrid` | Draw the shared hex border pattern through the completed scene in top view. |
| `GpuUnitVisibility` | Draw the screen-space outline/fill for eligible units occluded by the scene. |
| `GpuTerrain` | Fade occupied non-tree props and maintain their cutaway/depth state. |

The shared descriptor/painter classes live beside the GPU package in
`client/ui/clientGUI/boardview`. Native draw and placement classes live in
`boardview/gpu`.

## Tactical geometry and playback

Movement paths, flight arrows, ranges, deployment zones and target data come from
the existing client handlers. Native adapters must preserve each command/target
identity instead of deriving their own legality from rendered geometry.

Flat hex annotations use `BoardTacticalGeometry.floatingZ`, at the owning
hex's nominal ground/water level plus a small clearance. Road ramps and sculpted
relief do not raise this plane. Raised point symbols use the separate
`GpuMarkers.locationSupport` helper to clear the hex ceiling and overlapping units.
Flat annotations still test scene depth, so rising terrain can hide parts of them.
Hover and editor-brush outlines, unit selection bands and target bands draw hidden
sections in an additional depth pass at `GpuBattleView.SELECTION_OCCLUDED_ALPHA` (50%
opacity), keeping unobstructed sections fully bright. Both passes use the same
geometry and preserve the scene depth.
`GpuBattleView.HOVER_HEX_INSET` controls the native hover and editor-brush outline's
inset as a fraction of the hex radius; `.1` preserves the original 10% inset.
When the pointer hits a raised surface, its hover outline snaps the ray-hit height to the supporting floor level (the bottom of the storey hit by the ray). A second
outline at the hex base and six connecting edges use 25% opacity to show the selected column.
Terrain tints follow the terrain, unit bands follow the
animated unit, and cross-hex lines retain their continuous layout. Upright range
walls and raised point symbols have their own geometry; changing one must not
move every overlay onto the same plane.

`GpuBoardSource` captures scene checkpoints in packet order for
`UnitPlayback`. Unit-dependent overlays are hidden during movement playback,
and later scene changes wait for the appropriate visual checkpoint. Manual rulers,
LOS cursors and commands can remain live. A delayed visual scene must not become
an alternate source of game state or visibility.

Field-of-view classification is shared with the classic painter. The GPU uses
one texel per hex and applies the chosen grayscale, dimmed or fog-of-war style.
Change classification in the client capture; change appearance in
`GpuFieldOfView` and the atmosphere's FOV helper.

## Point markers and labels

`GpuHexText` uses only the labels captured by `BoardHexText`, preserving display preferences and the
omission of LEVEL0. HEIGHT labels draw through content in their own hex, including units, while content
in other hexes still occludes them. In top view, enabled ground labels ignore terrain, roofs, bridges
and units. Angled ground labels retain scene depth, with the existing
exception for their own decorative relief. Camera changes reuse the uploaded glyph meshes.

`GpuHexGrid` repeats the existing terrain border pattern in top view after scene geometry and before
labels. It shares the terrain's grid shading control, clips to the board footprint and does not write
depth. One shared hex mesh uses captured coordinates and elevations, so overhead perspective also stays
aligned; camera changes reuse the instance buffer. Angled views retain their existing terrain grid.

`BoardClientState.getBoardMarkers()` captures existing marker descriptors,
including minefields, artillery, objectives, notes, cargo and engineering status.
Existing knowledge, owner, phase and preference filters still apply.
`GpuMarkers` owns their artwork, meshes and cosmetic animation.

Symbols clear the finished hex, structures and overlapping posed units.
Angled views group them into a compact grid; near-overhead views arrange them
around the unit or in a ring for an empty hex. They stay inside their source hex
and return that hex when picked. Sensor contacts retain their own centered,
anonymous question mark and never acquire a hidden model identity.

In oblique views symbols rotate; near overhead they align with the camera.
`FLAT_TILT_DEGREES`, `GROUP_*`, `TOP_GROUP_*` and `TOP_UNIT_MARKER_*`
in `GpuMarkers` control those transitions and spacing. Unit labels and marker
detail labels avoid one another's bounds. Unit annotations follow the displayed
pose and can clamp to the viewport edge; selected/hovered labels have priority
in crowded views.

To add a point-symbol kind, extend `BoardMarker.Kind`, capture it through the
existing painter/handler, provide artwork and an outline choice in `GpuMarkers`,
and suppress the equivalent flat symbol during native capture. Keep area
footprints and route lines in the tactical layer.

The optional top-view unit icons use `GpuUnitIcons` and the existing animated
poses. `GpuBoardTuning` controls their projected-size threshold; labels and
picking follow the displayed icon. This is separate from the anonymous sensor
symbol and from the flat sprite fallback for a missing 3D asset.

## Building cutaways and see-through outlines

A visible unit overlapping a non-tree prop's bounds can fade that prop.
Hovering a building wall opens only the storey immediately above the highlighted floor plane.
Its walls use the same opacity as unit-occupied buildings, while its supporting floor and struts remain opaque; other storeys and the roof
keep their normal appearance. Both cameras use the same snapped height and original picking mesh,
so opening a wall cannot move the hit to a different floor. Roof hover does not open a cutaway.
The hover opening also works during unit cutaways, with its supporting floor kept opaque.
Moving away restores the building unless a unit still requires it to remain faded.
`GpuTerrain` uses animated bounds, so movement and flight height affect occupancy.
Walls use the chosen building opacity; interior struts and floors at or below the lowest visible
occupant remain opaque. Upper floors fade with an opacity one quarter of the way from the wall
opacity to fully opaque. Moving between floors updates the existing render caches without rebuilding geometry.
Faded surfaces stop writing camera depth, while buildings retain their full
solid shadows. Leaving the prop restores its ordinary opacity/depth state.
This is a bounds-based presentation effect, not physical collision.

Trees retain opaque geometry. `GpuUnitVisibility` instead compares the nearest
posed unit surface with scene depth and draws a team-colored outline and faint
fill where an eligible unit is obscured. It also uses this frame's terrain-smoke
opacity. Units omitted by gameplay visibility never enter the capture.

Ground decoration in the unit's current support hex is exempt from ordinary
occlusion highlighting; taller trees/buildings can still obscure it. The
comparison uses the rendered position and a depth tolerance, avoiding highlights
from the unit's own rear faces or depth quantization. Sensor contacts retain
anonymous geometry and their fixed highlight color.

The outline runs after atmosphere/weather and before tactical annotations.
Its capture targets resize with the viewport; zero strength skips the work.
`GpuBattleView` shares current posed bounds across culling, cutaways, shadows
and outlines, refreshing them after animation. Marker outline switches use the
same see-through strength without changing gameplay visibility.
