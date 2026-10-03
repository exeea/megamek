# UI and controls

[Docs index](README.md). Human command behavior lives in
[phaseDisplay](../megamek/src/megamek/client/ui/panels/phaseDisplay/).
The classic and native boards expose the same phase operations.

## Phase controllers and commands

`DeploymentDisplay`, `MovementDisplay`, `FiringDisplay` and `PhysicalDisplay`
own their phase's acting unit, pending orders and completion behavior.
[MapMenu](../megamek/src/megamek/client/ui/clientGUI/MapMenu.java) builds contextual
actions for a hex or unit using the current game and phase.

Start a behavior change here. For example, a movement tool must update the existing
movement controller and path, while a firing action must use the existing weapon
selection and attack declaration. The native UI presents those controls even when the
classic board viewport is absent.

[GpuBoardActions](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuBoardActions.java)
captures descriptions of the existing controls for rendering. Execution returns to
the Swing event thread and rechecks the current game/controller state. Availability
can change between opening a menu and clicking an item, so a captured button is a
presentation of an action, not permanent permission to execute it.

## Native input and panels

`GpuBattleView` converts pointer input into a hit against board or unit geometry.
The hit goes through `BoardSource` to the existing phase operation. Picks include
the board generation, allowing the source to discard clicks from a map that has since
been replaced. Camera gestures are handled by `BoardCamera`.

Pointer picking uses `GpuTerrain.selectionHit`, passing through tree and shrub
canopies to the board surface beneath the cursor. Buildings, bridges, units and
point markers retain direct picking. Attack effects use `GpuTerrain.hit`, which
also intersects foliage; physical impacts must not determine the pointer's hex.
Cliff walls and overhanging rim rocks select the raised hex; rubble at the foot
of a cliff selects the lower hex whose footprint contains the hit.

Ordinary left-click behavior depends on the phase: select a unit, place it during
deployment, plot movement or choose an attack target. Right-click opens contextual
choices. Choosing a target, queuing an attack and completing the phase remain separate
operations. Stacked units need their individual target identities to survive this path.

`GpuBoardUi` presents contextual menus, the action palette and common controls.
`GpuMenuCommands` dispatches the existing global menu actions.
`GpuAttackPanel` displays weapon selection, ammunition, target information and pending
attacks from phase-control snapshots. Fire, Clear and Done invoke the original commands;
specialized confirmations and dialogs continue through the existing client UI.
`GpuReportLog` and `GpuReportPanel` provide native report presentation.

The camera gestures are right-drag to pan, middle-drag to orbit and wheel to zoom;
Shift swaps the two drag gestures. `BoardCamera` owns these bindings and both
projection modes. Keep a short right-click distinguishable from a pan so it still
opens contextual actions.

See [attack controls](gpu-attack-controls.md) for target/weapon selection and order
submission, and [map editing](map-editor-3d.md) for brush/elevation gestures.

Free Flight uses the perspective eye managed by `BoardCamera` and swept contact
from `BoardCameraCollision`. Projection/FOV changes belong in
`BoardProjectionCamera`; they must retain the same scene and playback.
Camera framing for selection, movement and attack volleys is controlled by
`BoardCamera.ANIMATE_CAMERA_ON_SELECTION_CHANGE`,
`ANIMATE_CAMERA_ON_MOVE` and `ANIMATE_CAMERA_COMBAT_PLAYBACK`.

## Panel layout, reports and artwork

`GpuPanelDock` owns right-panel placement, visibility and the clearance reserved
for camera framing. Report width can be resized; the attack controls retain their
own layout. `GpuReportLog` captures already-filtered game reports, while
`GpuReportText` retains glyph-based link hit areas for `GpuReportPanel`.
Keep report filtering with the client and native scrolling/link presentation here.

`UnitOverviewOverlay` supplies the shared unit strips and visible enemy cards.
`GpuBoardSkin` applies the configured client skin. `GpuDisplayScale` handles
physical framebuffer/DPI sizing, so font/layout fixes should not be implemented
as changes to board geometry.

## Tactical markings and preferences

The client already computes movement paths, ranges, targets and visibility.
Board presentation captures that information; `GpuTactical`, `GpuFireControl` and
the overlay adapters turn it into native geometry or artwork.

Flat hex markings use `BoardTacticalGeometry.floatingZ`: the owning hex's nominal
ground/water level plus a small clearance. Road ramps cannot lift the whole marker plane.
Spinning point symbols use `GpuMarkers.locationSupport` above the hex ceiling and units.
Terrain tints and lines that
span hexes have their own placement requirements, so changing one overlay should not
redefine the board's physical surface or flatten every annotation.
[Overlays and occlusion](gpu-overlays.md) covers field-of-view masks, raised
symbols, labels, building cutaways and unit outlines.

[KeyCommandBind](../megamek/src/megamek/client/ui/util/KeyCommandBind.java) defines key
bindings; `MegaMekController` dispatches them to the active handlers.
`GUIPreferences` owns UI settings and `megamek/resources/megamek/client/messages.properties`
owns client text. Keep command behavior with its controller and presentation settings
with these existing preference/resource owners.

## Map editor

[BoardEditorPanel](../megamek/src/megamek/client/ui/boardeditor/BoardEditorPanel.java)
owns the board, tools, brush settings, file operations and undo history.
The native view uses `GpuMapSource` to send picks back to those editor operations,
including the existing `paintAt` path. Switching renderers retains the same editable
board and history.

A brush drag is one undo transaction. Wheel elevation changes also use the editor's
stroke machinery, and finishing a gesture, losing focus or switching views must finish
the active transaction. New/Open/Resize can replace the board, so queued native input
must still pass the generation check before it applies.

For window lifecycle, snapshot capture and thread ownership, see [GPU board](gpu-board.md).
