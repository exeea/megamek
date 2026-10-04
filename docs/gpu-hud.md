# Native battle HUD

The 3D battle view ([GPU battle view](gpu-board.md)) draws a native HUD over the board: the hud-v3 design, built in
libGDX Scene2D. The Swing client underneath keeps the game, every rule, the selection and the command availability.
The HUD shows immutable snapshots of them and calls MegaMek's existing commands; it decides no rule.

| Code | Where |
| --- | --- |
| Battle HUD (package-private) | `megamek/src/megamek/client/ui/clientGUI/boardview/gpu`: `GpuHud`, `GpuHudState`, the components and services below |
| UI toolkit (public) | `megamek/src/megamek/client/ui/gdx`: `UiTheme`, `UiKit`, `UiButton`, `UiPopover`, `UiMenuList`, `UiList`, `UiFlow`, `DisplayScale` |
| Paperdoll data | mm-data `data/images/paperdolls` (JSON), sources in mm-data `tools/paperdolls` |
| Tests | `megamek/unittests/megamek/client/ui/clientGUI/boardview/gpu` and `megamek/unittests/megamek/client/ui/gdx` |

Sections: [1 Architecture](#1-architecture) · [2 Input](#2-input-routing) · [3 Components](#3-components) ·
[4 Keys](#4-keys-and-binds) · [5 Tactical View](#5-the-tactical-view) ·
[6 Zoom-out unit scaling](#6-zoom-out-unit-scaling) · [7 Unit panel](#7-unit-panel-and-paperdolls) ·
[8 Toolkit](#8-the-ui-toolkit) · [9 Swing](#9-swing-in-gpu-mode) · [10 Tuning](#10-the-developer-tuning-utility) ·
[11 Tests](#11-tests) · [12 Limits](#12-known-limits)

## 1. Architecture

### 1.1 Two threads

| | Swing event thread (EDT) | Render (GL) thread |
| --- | --- | --- |
| Classes | `ClientGUI`, the phase displays, `BoardClientState`, `GpuBoardSource` and its HUD services | `GpuBattleView`, `GpuHud`, `GpuHudState`, the HUD components |
| Owns | the game, rules, selection, command availability, the captures | the Scene2D stage, the layout, which panels are open, the playback history |
| Hands over | immutable snapshots in a `GpuBoardSource.Frame` | commands, posted back to the EDT |

- Snapshots are immutable records. Images travel as `BoardScene.Pixels`, never as live game objects.
- Every snapshot type has an `EMPTY` or `NONE` value. The source keeps the previous instance while nothing changed,
  so the GL side compares `GpuHudData` and its parts by identity.
- The GL thread never reads the game. The EDT never waits on the GL thread: a native dialog waits in a nested Swing
  event loop (1.6).

### 1.2 Board state: `BoardClientState`

- Each board has one `BoardClientState` (`megamek.client.ui.clientGUI.boardview`). It holds the sprites, overlays,
  selection, LOS and ruler, field of view, ECM, chat box, tooltips and every GPU capture (`capturePlanarHexes`,
  `captureTacticalGeometry`, `captureFieldOfView`, `getBoardMarkers`).
- `BoardView` is only the classic 2D renderer. `ClientGUI` builds it only while the classic view is enabled; in GPU
  mode no `BoardView` exists.
- `GpuBoardSource.currentView()` is the shown board's state. Every service reads it per call, so a board switch needs
  no rebinding.
- Reach a board through `source.currentView()` or `ClientGUI.getBoardState(..)`, `boardStates()` and
  `onAllBoardStates(..)`. `getBoardView()` and `boardViews()` return the classic view, which GPU mode does not build.
- A command captured for a board that is no longer the selected one, was replaced or closed goes stale and does
  nothing (`isCurrentView`, the board generation).
- `UnitStatusWords` holds a unit's status words and label tiles. The classic label (`UnitAnnotations`) and the HUD
  (`GpuBattleStatus`) both read it, so each status rule exists once.
- Tests: `GpuBoardFixture.view` is a `BoardClientState`; `classicView()` builds the renderer only for a test that
  needs it. A mocked `ClientGUI` that runs captured commands must report its board through `getCurrentBoardState()`.

### 1.3 The capture: `GpuBoardSource`

`GpuBoardSource` captures a frame on the EDT: on a 100 ms Swing timer (which also advances the state's overlay
fades), on game events (reports, resolved attacks, unit moves, new and removed units), on board edits, and after every
command it runs. The render thread takes it with `takeFrame()`, together with the animation events queued since the
last frame.

| `Frame` component | Content |
| --- | --- |
| `scene` | `BoardScene`: tiles, units, markers, tactical geometry, firing lines, range borders and labels, field of view |
| `timeline` | movement, combat and scene-update events for the playback |
| `context`, `globalCommands` | MegaMek's map menu at a hex; the menu bar, the game commands and a Maps group |
| `status` | `GpuBattleStatus.Snapshot`: units with side, state and status words, initiative, turns, phase, round |
| `reports` | `GpuReportLog.Snapshot`: the visible reports as entries; combat, PSR and movement events; critical hits; artillery in flight |
| `panels` | `GpuHudData`: one snapshot per HUD service (1.4) |
| others | tooltip, centring request, board generation, actor name, the scenario's atmosphere |

Two channels sit outside the frame and are read without the source's monitor:
- `uiPreferences` (`UiPreferences`): the GUI scale, report keywords, the minimap, planetary conditions and turn
  details preferences, every key binding (`Bind`: command, key, modifiers, text), and the sprint colour. It is
  captured again at every capture and on preference events.
- `dialog()`: the newest pending native dialog (1.6).

Units the local player cannot see are not captured, and a sensor contact is captured without its identity.

### 1.4 HUD services

Each service owns one domain on the EDT: its snapshot, its capture and its commands. A command called on the GL
thread only posts to the EDT. There it runs only while the input guard (1.5) holds, rechecks the phase, turn and
actor, calls MegaMek's own code and republishes the frame.

| Service | Snapshot | Commands |
| --- | --- | --- |
| `GpuBoardActions` | `panels.phase()` (`PhaseInfo`: phase status, Done/Skip/Clear ids, turn details, conditions lines); the phase, context and global commands | each `BoardScene.Command` clicks its captured button or menu item if its turn is still current |
| `GpuBattleStatus` | `status` | none |
| `GpuMovePlan` | `panels.move()` | `planTo`, `pinDestination`, `turn`, `setMode`, `undo`, `clearRoute`, `holdAll`, `stopHolding` |
| `GpuFireOrders` | `panels.fire()` | `selectUnit`, `selectWeapon`, `focusTarget`, `assign`, `clickHex`, `remove`, `removeLast`, `removeTarget`, `retarget`, `move`, `setPrimary`, `setAmmo`, `cycleMode`, `calledShot`, `aim`, `twist`, `clearAll`, `resolvePhase`, `stopResolve` |
| `GpuPhysicalOptions` | `panels.physical()` | `target`, `declare` |
| `GpuUnitRecord` | `panels.record()`: the card unit's record | `setMode`, `setSystem`, `moveWeapon`, `setDumping`, `setAmmo` |
| `GpuFirePreview` | `panels.preview()`: the movement fire preview | none (time-sliced jobs, 12.4) |
| `GpuChat` | `panels.chat()` | `send` |
| `GpuToasts` | `panels.toasts()` | none; `ClientGUI.addToast` feeds it through `GpuBoardWindow.toast` |
| `GpuLosResult` | `panels.los()`: the measurement waiting for its second point | `lineOfSight` (ruler measures from the unit to the height the pointer showed) |
| `GpuPlayers` | `panels.players()` | `setPanelOpen` |
| `GpuReportLog` | `reports` | none |

`GpuBoardSource` has its own commands too: `selectUnit` (MegaMek's SELECT_UNIT event: the phase display decides),
`locateUnit` (`ClientGUI.centerOnUnit`), `click` and `hover` (MegaMek's board tool through
`BoardClientState.mouseAction`), `measure` (the ruler's point at the pointed height), `key` (1.5), `setCardUnit`,
`setFocusUnit` and `answer` (1.6).

- **Fire targets.** The fire orders know a target as MegaMek's attacks do, by its target type and id (`TargetKey`,
  which `Game.getTarget` resolves): a unit, a hex, a building or a minefield alike. The focus is the firing display's
  target whatever it is (a unit only while the player may identify it), and every weapon attack in the queue gives its
  target a letter, so a target MegaMek's board click or map menu chooses (woods to clear, a building) is focused,
  assigned, lettered, carded and traced as an enemy is, and its attacks count for Fire weapons. A target that is no
  unit has its hex in the snapshot while it lies on the shown board; the view anchors its card and trace on that hex
  (`HudView.targetHeads`), and the overlay rings it.

- **Captured commands.** The phase commands are the phase display's own buttons: those of
  `StatusBarPhaseDisplay.getActionButtons()` on all its pages (without the More paging button), the visible buttons of
  other phase panels, and Done and Skip; the HUD builds no parallel set. The context commands are MegaMek's `MapMenu`
  at the hex; building it is read-only, and a target is applied only when an action is chosen. A command runs only
  while its phase panel, phase, turn index and actor are unchanged and its button is enabled; a menu command rebuilds
  the current menu first, so a choice that became hidden or unavailable cannot run.
- The movement planner plots for ground units in the walk, jump and back-up gears. Aerospace units, the other gears
  (such as charge and death from above) and a hex the display picks itself (an escape pod's landing, a bridge) keep
  MegaMek's own board tool. So does a unit that walks on (walk-on deployment) until MegaMek's click on its entry hex
  places it; the planner then continues from the entry and keeps the placement (the DEPLOY step) at the start of the
  path, as it keeps a jump's start.
- The fire orders read and change `FiringDisplay`'s queue through its own methods; the queue is the one source of
  the orders. While another own unit acts, a unit's orders wait as its draft, and "Resolve phase" declares the
  drafts on the following own turns.

### 1.5 The input guard

`GpuBoardSource.acceptsInput()` is false once the source or its board state is closed, while a native dialog of the
source is waiting, and while a Swing modal dialog is shown (`UIUtil.isModalDialogDisplayed`). Every command checks it
first, so input queued before a dialog appeared is dropped instead of running inside the dialog's nested loop.
Non-modal windows such as Help or the accessibility window do not block it. Keys forwarded to Swing also respect
`ClientGUI.shouldIgnoreHotKeys()`, which is true while a Swing modal or a native dialog is shown. A captured command
also goes stale when its turn (phase, turn index, actor) or its board changed.

### 1.6 The modal bridge

The client's prompts reach the HUD through `ClientGUI`'s chokepoints: `askNative`, `askYesNo`, `askRows`, `askText`
and `askForm`, and the facades `confirm`, `input`, `doAlertDialog` and `doYesNoDialog`. Choice lists
(`AbstractChoiceDialog`), the ECM suite choice and aimed shots check the same predicate.

1. The chokepoint calls `GpuBoardWindow.route(gui, request)`. It asks natively only while
   `GpuBoardWindow.drawsDialogsFor(gui)` holds, that is while this client's native window is presented. Otherwise
   it returns null and the caller shows its Swing dialog.
2. `ask` raises the native window and calls `GpuBoardSource.ask`. That pushes the request on an EDT-only stack,
   publishes the newest unanswered request in a volatile field and pumps Swing events in a nested loop
   (`SecondaryLoop`) until the request is answered. If the source closes first, it returns the cancelled answer,
   the same result as closing the Swing dialog.
3. The HUD reads `source.dialog()` every frame, also while no board has arrived yet. `GpuModalDialog` draws it over a
   scrim on the top layer. The answer goes back through `answer(id, answer)`, which posts to the EDT and never blocks.

| Kind | Body |
| --- | --- |
| MESSAGE | the text, with the request's image on its left when it has one (an image file, base64 encoded) |
| CHOICE | a list; the highlighted row is the choice, a double click confirms |
| MULTI | ticks, at most `max`, with Select all and Clear all |
| INPUT | a text field; with `min` or `max` an integer of that range, shown beside the field |
| FORM | one field per `DialogField`: INTEGER, TEXT, CHOICE or CHECKBOX |

- Enter presses the default button (none for -1). Esc presses the cancel button; with -1 it closes the dialog like a
  JOptionPane close box (button -1, the checkbox as the player left it).
- Only the newest request shows; an outer one shows again after it (last in, first out).
- While a dialog is pending it takes every key, its scrim takes every press, and no other command runs.
- With the native window presented, the physical attack's "To hit" yes/no is skipped, aimed shots are offered as a
  native choice, and the Unit Display window stays closed (the unit card and sheet replace it).
- The bomb payload, mine, called blow, building facing, turret facing and suicide implants dialogs ask natively from
  their own controls: combo boxes as choice lists (`askEntry`), the facings that do not fit or that the mount cannot
  take disabled, and bombs and troopers as live form choices whose every pick goes through the Swing control, so the
  entries narrow and the damage line follows as in Swing.
- The pre-end declarations (infantry action, Nova CEWS networks, Variable Range Targeting, abandonment, demolition
  charges, minesweepers), the AP and B-Pod triggers and the automatic ejection warning ask natively from the rows their
  Swing dialogs show, as tick lists and forms. The infantry action's form is live, so its totals follow each choice;
  the Nova dialog shows its units and pending changes with its five buttons, and Link... and Unlink... ask for the
  units.
- The Victory Setup phase's control point editor asks as a form of the rows its Swing pane shows; changing the
  scheme, the counting or the retention asks again with the rows, labels and description the pane then shows. A
  scenario's story message shows with its image; a story that arrives while another dialog is pending shows after it.

### 1.7 `GpuHud`: stage, layers and frame order

One stage unit is one CSS pixel of the hud-v3 design. `DisplayScale.calculate` sets the scale: the larger of the
monitor's DPI scale and the window's resolution (window / 1600 x 1000), times the GUI scale preference, but never so
large that 960 x 640 stage units do not fit. The native window cannot be smaller than 900 x 600 logical pixels.

| Metric (stage units) | W > 1500 | 1350 < W <= 1500 | W <= 1350 |
| --- | --- | --- | --- |
| gap | 20 | 20 | 16 |
| left column | 300 | 270 | 250 |
| forces list | 250 | 250 | 250 |
| right column | 310 | 290 | 270 |
| dock | 580 | 540 | 500 |
| log | 380 | 340 | 310 |

The forces list is `GpuHud.FORCES_WIDTH` wide, at most the left column: narrower than the prototype's column, which
left its rows much room (the user's request of 2026-10-02). The phase header and the unit card keep the column, the
forces grid its own width, and the dock's band still starts after the column. The forces panel is as tall as its
content: a long list ends 12 units above the unit card and scrolls.

Height <= 800 is the low-height layout. The layers, bottom to top: board labels (the Tactical View's north mark,
guides, traces and leaders, nameplates, other board labels, target cards), panels, chat, forces overview, dialogs
(Help, Menu, Players, Tuning), popovers (the unit card's hover card, the sheet's slot popover, the context menu),
toasts, and the modal dialog.

Each component implements `GpuHud.Component`: `actor()`, `update(Inputs)` and `dispose()`. `Inputs` holds the frame,
the `HudView`, the pending dialog, the preferences, the metrics and the bounds of the shown panels.
`GpuBattleView` builds the `HudView` each frame: the view mode, whether the playback still presents live events,
each drawn unit's screen rectangle, label anchor (head) and animated board position, the hovered hex and unit, and a
hex's width on screen.

`GpuBattleView.render`, per frame:
1. Take the frame and feed its timeline to the playback history.
2. `ui.updateState(...)`: the focus unit, the presented units and the log's own opening (1.8).
3. Frame the camera in the board area between the HUD's columns (`cameraLeft()`, `cameraWidth()`).
4. Pose the units.
5. `updateHud(frame)`: build the `HudView`, run `GpuHud.update` and update the board overlay.
6. Draw the board, then `ui.draw()`.

A frame without a board still updates and draws the HUD, so a dialog asked before the first map is shown and can be
answered.

### 1.8 Shared view state: `GpuHudState`

`GpuHudState` holds only what several components read or change together: the inspected unit, the armed weapon, the
one open dialog (Help, Menu, Players or Tuning), the overview, chat, unit sheet and forces grid flags, the sheet's tab,
the held nameplate key, the log's open state and the playback history. It is presentation state, never game state.

- **Focus unit.** The candidates are the own units still to act, in ascending id order, the order of the phase's
  "next" command. A phase starts at the first candidate, or at the player's pick while it is one. During the local
  turn the focus is the actor; after it, the next candidate. When the local turn begins in MOVEMENT, FIRING or
  PHYSICAL and the focus can act, the HUD selects it once. A report phase has no candidates: the turns of the
  initiative report only list the round's order.
- **Own units outside the local turn.** A click on an own unit (board, row, card or menu) makes it the focus, shown
  and selected in mint. Enemy units are inspected.
- **Cleared selection.** The card's ✕, on the selected unit as on an inspected one, ends the inspection and clears
  the selection (`GpuHudState.clearSelection`; the user's decisions of 2026-10-02): no card and no highlighted unit.
  In the local turn no unit is then selected at all. MegaMek's phase display keeps its current unit (it has no
  selection without one), but the HUD presents the turn without it (`GpuHudState.presented`, used by the HUD and the
  board overlay): no acting unit, no dock, no route or envelope, no fire orders, preview or physical options, no map
  menu. No input reaches that unit: a hex click does nothing, a unit click selects (an own unit that can act) or
  inspects (any other, which keeps the selection cleared), and the HUD keeps every key from MegaMek except the menu
  bar's binds (`KeyCommandBind.isMenuBar`) and `GpuHud.UNITLESS_BINDS` (camera, chat, pause, report keys, turn timer,
  the HUD's panels). A selection (board, row, menu, Next pending, MegaMek's next or previous unit) or the next turn,
  phase or focus unit ends it; MegaMek then selects the unit afresh.
- **No fire preview outside the local turn.** `GpuHudState.presented` also drops the fire preview while another
  player moves or fires: no to-hit badges or guides from the last own unit (the user's decision of 2026-10-03).
- **Presented units.** While the playback still presents live events, every component shows the units and the record
  of the last status captured while the playback was idle, so armor and damage never run ahead of the animation.
  Phase, turns and initiative are always the newest.
- **Log.** A closed log opens for the report phases after the initiative phase and closes again with the next phase
  that is not a report. A log the player opened stays open.
- **Playback history.** `GpuBattleView` owns one `GpuPlaybackHistory` over its `UnitPlayback` and hands it to the
  HUD. The board feeds it; the dock's transport, the phase header's speeds, the log's cards and Replay, the playback keys and the
  board's pop-ups all act on it. A review presents a retained shot again and never applies an event, so damage, heat
  and ammunition stay as the client reports them. Live events that arrive during a review play after it, in order.

## 2. Input routing

### 2.1 Mouse

1. The HUD's stage gets every press first. A panel takes presses on its whole area; the slots that span the window
   (board labels, target cards, popovers, the Tuning panel) take presses on their widgets only. While a dialog is
   pending, the scrim takes every press.
2. A press on the board calls `GpuHud.boardPress()`, which ends a keyboard focus and closes the context menu. Right
   drags pan and middle drags orbit; Shift held at the press swaps the two. A drag starts past 6 pixels times the
   layout scale. A left drag moves nothing and cancels its click. A middle drag on the minimap's map orbits the
   camera as well, by the same `GpuBattleView.ORBIT_DEGREES` per unit.
3. A short release (not the middle button) on the board of its press picks the nearest of the unit models, location
   markers and terrain along the pointer's ray. In the Tactical View a unit icon wins over the ground, and a hex
   holding a unit picks that unit. The release calls `GpuHud.boardClick` with the modifiers held at the press:
   - right: the context menu at the pointer; opening it never changes orders;
   - left with Ctrl or Alt: MegaMek's measurement, which its Swing ruler shows over the native window, each point
     at the height the pointer shows there (the building floor it points at, else the ground); while one waits for
     its second point, a plain left click ends it;
   - left: by phase, below.
4. The wheel zooms at the pointer over the board, and scrolls a panel under the pointer.
5. Hovering picks the hex and the unit. The pointer is a hand over units and a move cursor over the minimap (desktop
   platforms only). No route preview is requested while a move animates.

| Left click | On a unit | On a hex without a unit |
| --- | --- | --- |
| Outside the local turn | own unit: becomes the focus; other: inspected | nothing |
| Local movement turn, planner | select an own unit that can act, else inspect; with Shift (and on an own unit only once a route exists) plan to its hex | plan the route to it; Shift pins a waypoint |
| Local FIRING turn | own: select; identified enemy: focus it as the target (an armed weapon is assigned); sensor contact: refused with a toast | MegaMek's board click chooses the target there (`clickHex`: a building, woods to clear, its dialog among several; Shift twists), focused as an enemy is; an armed weapon is assigned to it |
| Local PHYSICAL turn | own: select; enemy: inspect, and make it the target when adjacent | MegaMek's board tool |
| Any other local turn: deployment, TARGETING and OFFBOARD, movement outside the planner | MegaMek's board tool (`hover` then `click`) | MegaMek's board tool |

While a bot order picks target hexes (Players: Strategic Target, waypoints, fire missions, Scoot-To Hex), every left
click on the board, in any phase and on a unit too, picks that hex for the order, as on the classic board (`click`).
Where the window hides the hint line (W <= 1350), the pick shows in a chip on the dock: its instructions, the picked
hexes, and Done and Cancel buttons.

`GpuHud.select` is the one selection rule for clicks, rows, cards and menus. An own unit that can act now in the
local MOVEMENT, FIRING, TARGETING, OFFBOARD or PHYSICAL turn is selected through that phase's command. Outside the
local turn an own unit becomes the focus. Any other unit is inspected.

### 2.2 Keys

`GpuBattleView.BoardInput` receives every key; the stage never sees one directly. For a press:

1. `GpuHud.keyDown`: a pending dialog takes every key (the CANCEL bind presses its cancel button);
2. the CANCEL bind runs the Esc chain (2.3);
3. a focused text field takes every other key;
4. a focused grip or open list gets the key first (menus navigate, Alt+Up/Down moves an attack on a target card, and
   a weapon row of the sheet's focused list);
5. the HUD hotkeys (section 4);
6. the camera binds;
7. everything else goes to Swing: `GpuBoardSource.key` runs `MegaMekController`'s binds on the EDT unless the client
   ignores hotkeys, then the menu bar's accelerators. With the selection cleared in the local turn only the binds of
   no unit go (section 1.8); the HUD keeps the others.

A key's release goes to the HUD first; the release of a key whose press the HUD consumed ends there. A forwarded
release carries the modifiers of its press. Typed characters reach only a focused text field or a pending dialog. The
held camera keys work with every panel open and pause only while a text field has the focus.

### 2.3 The Esc chain

CANCEL (Esc by default) takes one step per press:

1. a pending dialog: its cancel button;
2. a row dragged in a list (a target card's attack, a weapon of the sheet): it glides home and nothing moves;
3. a keyboard focus outside the context menu, the sheet's popover and an open dialog: dropped;
4. the context menu or a select list: closed;
5. Help, Menu, Players or Tuning: closed, with the focus inside it;
6. the forces overview: closed;
7. chat: closed;
8. a bot order's hex pick: cancelled, no order sent;
9. the dock's confirm strip, then the unit sheet (its popover, then the expanded row, then the sheet);
10. the weapons panel: the armed weapon disarmed, else the weapon deselected (the solution card closes with it);
11. the local planner turn's route: cleared;
12. the inspected unit: no longer inspected;
13. otherwise: in FIRING, TARGETING, OFFBOARD and PHYSICAL the key stops here, because MegaMek's CANCEL would clear the
    declared attacks. In every other phase it goes to the phase display.

## 3. Components

Every component reads the frame's snapshots and `GpuHudState`. Its orders go through the commands of 1.4; only
view changes (open panels, the camera) stay on the GL thread. Names in quotes are the English texts.

| Component | Where | Shows | Runs |
| --- | --- | --- | --- |
| `GpuPhaseHeader` | top left | round, phase ring, phase name, whose turn; in movement the activation ribbon (hidden under double blind); the playback speeds in the frame's top right corner as one pill of sections (0.5x, 1x, 2x, 4x, I for Instant), in every phase and turn; the round shortens to its number ("01") only where the line has no room for "Round 01" | the playback history's speed |
| `GpuForcesPanel` | left column | own and allied units, or the contacts, as grouped rows or a grid with search, grouping and filter | selection rule, unit menu, the phase's next-unit command |
| `GpuUnitCard` | bottom left | the inspected unit, else the focus unit: paperdoll, vitals, chips; the mini card while the sheet is open; the contact card for a sensor contact | "Unit record", Locate |
| `GpuRecordSheet` | left, at the grid's width | the unit sheet (section 7) | the record service's unit actions |
| `GpuConditionsCard` | beside the left column | the planetary conditions overlay's lines while its View preference is on | its close button runs that View item |
| `GpuUtilityBar` | top right | "Tactical view", "Wireframe" (an open grid icon: three lines each way, no border), "Map", "Log" (amber count of the round's reviewable events the board has presented), "Help", "Menu"; the Tactical View chip and north mark | the camera's Tactical View, the tuning model's wireframe view (without the cosmetic scatter), View > minimap, the HUD's toggles |
| `GpuInitiativeCard` | centre, initiative phase | each side's reported roll, "Wins", "Moves first", the turn order in pages of eight (double blind hides the order and "Moves first") | none |
| `GpuMinimap` | under the utilities | the board in its tileset colours, units at their animated positions, the planned route, the camera's ground area; its close button straight on the panel's corner | a left press or drag centres the camera, a middle drag orbits it and the wheel zooms it as on the board, about the view's centre; no order changes |
| `GpuContactsPanel` | right column | enemy units; with the movement fire preview, where the unit fires from, each enemy's best salvo both ways and the guide toggles | selection rule, unit menu |
| `GpuWeaponsPanel` | right column, local weapon declaration | target pills, one row per weapon with its roll and slots (an assigned slot in its target's colour), the heat if the queued weapons fire; no assign line while a weapon is armed, which its row shows selected; on another player's turn the focus unit's draft, read-only. Declaring never ends the turn by default: MegaMek's auto-end firing (`GUIPreferences.AUTO_END_FIRING`, now stored as `AutoEndFiringAfterLastWeapon` so that the old `AutoEndFiring` value is ignored) defaults to off, so FIRE WEAPONS sends the attacks | fire orders |
| `GpuSolutionCard` | under or beside the right column | the selected weapon's shot: roll, range brackets, arc, modifiers, or why there is no shot; a long target name ends in an ellipsis, the roll after it stays whole | close deselects the weapon |
| `GpuLogPanel` | right column, log width | the round's events as cards, Summary and Full log, filters, search, totals, earlier rounds, report keywords, copy, artillery in flight | review a step, locate, replay |
| `GpuCommandDock` | bottom centre | one variant per phase and turn: initiative, movement plan, waiting, weapon fire, physical, no physical attack, playback, generic; in the local TARGETING turn the generic option row leads with the off-board targets ("Off-board West"), the edges whose arrows `OffBoardTargetOverlay` shows; the playback transport has no speeds (the phase header holds them) | phase commands (an off-board target runs the overlay's own click), movement, fire and physical services, the playback, the Follow camera toggle |
| `GpuHintLine` | under the dock (hidden at W <= 1350); there a bot order's hex pick shows in its chip on the dock | what a left click does now, the gestures and their keys; the pick's instructions and picked hexes | the chip's Done and Cancel end the pick |
| `GpuChatPanel` | bottom right, with its button | chat lines, the field and Send, an unread dot on the button | the board chat's send, which keeps its history |
| `GpuToastStack` | centre, above the dock | MegaMek's toasts: at most 5, levels INFO, SUCCESS, WARNING, ERROR, GAMEMASTER | presses pass through |
| `GpuForceOverview` | over the board; the panels it covers are hidden (the prototype blurs them) | a card for every presented unit, grouped, filtered and searchable | locate, selection rule, unit menu |
| `GpuHelpDialog` | centred | "Controls": the binds by group with their current keys, then the mouse gestures | none |
| `GpuMenuPanel` | centred | "Players", then the menu bar's groups as `GpuBoardActions` captures them (File, Game, Board, View, Help, Commands, Maps) | each item's own action, or a redirect (below) |
| `GpuPlayersPanel` | centred | the players as the Swing player list shows them; each bot's commands under it | bot commands |
| `GpuContextMenu` | popover | unit menus, the hex menu with MegaMek's map menu in sections (a group's commands between separators, no menu of menus), weapon-row and queued-attack menus, the dock's More, select lists; a group opens its submenu beside its item as a desktop menu does: the menus before it stay open, another group replaces the chain below its menu, Esc closes the deepest and a press outside closes all | existing commands only |
| `GpuModalDialog` | top layer | the pending dialog (1.6) | answer |
| `GpuNameplates` | board labels | a team pip per unit; a name tag for the focus, hovered and inspected unit, the physical target and sensor contacts; every tag while the nameplate key is held; target cards replace their targets' plates | none |
| `GpuBoardLabels` | board labels | to-hit badges (every shot), fire-preview guides (the best 50 of each direction), traces of the queued attacks, each guide and trace leaving its shooter outside its rectangle at its front, or its back at a target in its rear arc; leaders to the target cards, the destination tip, waypoint numbers, playback pop-ups (a unit's stack: the newest at its place, each older one 4 over the next) | none |
| `GpuTargetCards` | board labels | a card per target in letter order, placed by `GpuCardPlacement` clear of panels and units, its frame neutral; the target's letter square carries its colour, as in the weapons panel and the sheet (`GpuHudKit.targetColour`: MekBay's twelve target colours from A); the focus without attacks has no letter; the attack rows (no fire-order number) are a reorderable `UiList` | fire orders; a row with a grip drags to another place of the card (from any part of it, as the prototype's draggable `.or`), its focused grip moves it on Alt+Up/Down, each a single `move` |
| `GpuTuningPanel` | dialogs layer | developer tuning (section 10); the frame rate beside its title | the tuning model |

Two board-space components are world meshes that `GpuBattleView` draws:
- `GpuBoardOverlay`: the route and its ghost, the front arc or the displayed weapon's arc, the physical-attack
  neighbours and the Tactical View's elevation-drop edges as meshes, rebuilt only when what they draw changed, with
  the route's pulse over the route (below). The unit marks (side rings, glows, the other targets' rings and the
  hovered unit's ring) are drawn every frame where the units stand, so the pointer rebuilds no mesh. 
  Where the terrain or a model hides the acting unit's, the focused target's or the hovered
  unit's ring, it is drawn again at half opacity behind what hides it (occluded outlines).
  `GpuBattleView` rings the hovered hex; over a building floor the ring lies at that floor with a
  faint ring at the ground and faint corner posts between them. The movement envelope is MegaMek's own (below).
- `GpuFireControl`: range walls of the displayed weapon, the firing lines and the flat range labels
  ([attack controls](gpu-attack-controls.md)). During the local weapon declaration the actor's lines are the HUD's
  traces instead; every other line is drawn in its attacker's side colour. Lines hide while an attack plays
  (`GpuBattleView.HIDE_TARGET_ARROWS_DURING_ATTACKS`).

**Region markings** The displayed weapon's range brackets are `GpuFireControl`'s continuous walls along the brackets'
contours (`BoardFiringGeometry`) with the camera-facing S/M/L letters. 
The visual range (`SensorRangeSprite`) and the deployment zones (`BoardDeploymentGeometry`) stand
upright through `BoardRangeBorder` in the tactical capture, which `GpuTactical` lays flat in the Tactical View.
MegaMek's movement envelope, the sensor ranges and the zones lie flat on their hex's plane; `GpuTactical` depth-tests
those markings two levels nearer (`PLANE_SEE_THROUGH_LEVELS`), so that the slopes and rocks inside their hex never
cover them while a hill in front of the hex still does.

**The route's pulse** (`GpuRoutePulse`, user item 55). In the local movement turn a plotted route pulses: a glowing
head leaves the unit, runs the route and settles into the destination; the ghost then surges and a ring ripples out of
the destination ring; after a rest the next pulse leaves. The hover preview, a route MegaMek's board clicks plan (G20)
and a cleared selection have none.
- **3D.** The head is a pool of light on the ground under it and four soft glows facing the camera, from a wide faint
  bloom to a white-hot core, in the band colour of the step it runs (whiter at the core), with a tapering streak
  behind it. The route's dashes, jump dots and step discs light up just ahead of it and fade over the 2.5 hexes
  behind; about every other one lets a small mote of light rise and drift off. A jump's head rides its arc. On
  landing the head flares, the destination hex flashes, a ring with a bright edge and a fading wake runs out to 1.65
  hex radii with a fainter echo, and the ghost's glow rises toward white while it turns more opaque, then eases back.
  The ghost never grows: units keep their size.
- **Tactical View.** The same as flat glows on the dashed line; the destination ring ripples and the ghost icon
  brightens in the route's last colour.
- **Timing.** 0.16 s a hex, at least 0.5 s and at most 1.8 s for a route, divided by the speed in use; the head
  speeds up over the first quarter of that time and settles to rest over the last 35 %. The surge rises in 0.08 s and
  ends 0.9 s after the landing, then the pulse rests 0.6 s. A rebuild for the same plan keeps the rhythm; another plan
  starts it from the unit. A route that only turns in place has no head: its ghost surges and its ring ripples.
- **How.** While it builds its meshes, the overlay hands the pulse the route's line (kept over the ground, or along the
  jump's arc) and its marks. Each frame `update` moves the pulse on by the frame's time (at most 0.1 s), `render`
  draws its quads after the static meshes (one dynamic mesh of up to 256 quads through the overlay's ModelBatch, with a
  64 x 64 glow texture made once per overlay), and `renderGhost` sets the surge on the ghost's own material copies.
  The quads use premultiplied blending: each adds light and covers a share of what lies under it, so the colours stay
  visible on bright ground. They are depth-tested in 3D without writing depth (the head's glows come toward the
  camera so the ground does not cut them) and lie flat on top in the Tactical View, under the icons. The static meshes
  are never rebuilt for the pulse, a frame allocates nothing, and `GpuBattleView` is unchanged.
- **Constants** (`GpuRoutePulse`): `ENABLED`, `SPEED`, `INTENSITY`, `SECONDS_PER_HEX`, `MIN_TRAVEL`, `MAX_TRAVEL`,
  `SURGE`, `REST`, `HEAD_SIZE` and `FLAT_HEAD_SIZE`, `TRAIL`, `GHOST_SURGE`, `GHOST_SURGE_OPACITY`, `ICON_SURGE`,
  `RING_RADIUS`, `RING_SECONDS`. They are the defaults of the values in use (`enabled`, `speed`, `intensity`), which the
  Tuning utility's Board page edits under "Route pulse"; Defaults restores the constants.
- **Limits.** Beyond the destination hex the ring lies at the hex's level: over lower ground it floats, and higher
  ground hides it.

The Menu's items that open a native surface instead of Swing (`GpuMenuPanel`):

| Menu item | In the HUD |
| --- | --- |
| Unit Display | the unit sheet |
| Force Display | the forces overview |
| Keyboard Shortcuts | Help |
| Player List | Players |
| Round Report | Log |
| Rounds in the Air | Log (its Summary lists the artillery in flight) |
| Ruler / LOS Tool | Swing ruler |
| Isometric View (labelled "Tactical view") | the Tactical View |
| Zoom In, Zoom Out, Toggle Overview Zoom | the board camera |
| Reset Window Positions | not shown |

Every other item runs its own action on the EDT. The client's menu shortcuts reach the same items (2.2).

## 4. Keys and binds

Every key comes from `KeyCommandBind`, so a player can rebind it in the client settings; the HUD reads the binds the
client captured (`UiPreferences.binds()`). The defaults:

| Bind | Default | Action in the HUD |
| --- | --- | --- |
| `CANCEL` | Esc | the Esc chain (2.3) |
| `ROUND_REPORT` | Ctrl+R | Log |
| `KEY_BINDS` | Ctrl+K | Help |
| `BOT_COMMANDS` | Ctrl+Shift+G | Players |
| `LOS_SETTING` | L | Swing ruler, as View > Ruler / LOS Tool |
| `UNIT_OVERVIEW`, `FORCE_DISPLAY` | Ctrl+U, Ctrl+F | forces overview |
| `UNIT_DISPLAY` | Ctrl+D | unit sheet (closes the overview) |
| `UD_GENERAL` ... `UD_EXTRAS` | F1-F6 | the sheet's tabs |
| `FORCES_GRID` | G | forces list or grid |
| `TOGGLE_CHAT`, `TOGGLE_CHAT_CMD` | Enter, / | open chat; "/" starts the message |
| `DONE` | Ctrl+Enter | the dock's main button; while a bot order picks hexes, the order with the picked hexes (the hint line shows the pick and both keys; in narrow windows the pick's chip has Done and Cancel buttons) |
| `SHOW_NAMEPLATES` | Alt, held | every nameplate |
| `MOVE_MODE_WALK`, `_RUN`, `_JUMP` | 1, 2, 3 | movement mode (local planner turn) |
| `TURN_LEFT`, `TURN_RIGHT` | Shift+A, Shift+D | turn (local planner turn) |
| `TWIST_LEFT`, `TWIST_RIGHT` | Shift+A, Shift+D | torso twist (local FIRING turn) |
| `UNDO_LAST_STEP` | Backspace | undo a step (planner) or the last attack (FIRING) |
| `CLEAR_ORDERS` | Delete | clear the route (planner) or the orders (FIRING) |
| `PLAYBACK_TOGGLE`, `_PREV`, `_NEXT` | Space, Left, Right | report phases: pause and step the playback |
| `CENTER_ON_SELECTED` | Space | other phases: locate the acting or inspected unit |
| `REPORT_KEY_NEXT`, `_PREV`, `REPORT_KEY_SELECT_NEXT`, `_PREVIOUS`, `REPORT_KEY_FILTER`, `REPORT_FILTER_KEY_SELECT_NEXT` | N, Shift+N, Ctrl+N, Ctrl+Shift+N, Shift+F, Ctrl+Shift+F | while the log is open: find and filter by report keyword |

Camera binds (`GpuBattleView`): `SCROLL_NORTH/WEST/SOUTH/EAST` (W, A, S, D, held), `CAMERA_ROTATE_LEFT/RIGHT` (Q,
E, held: 70 degrees per second, 3D view only), `CAMERA_TILT_UP/DOWN` (Page Up, Page Down, held: 60 degrees per
second), `TOGGLE_ISO` (T: Tactical View), `ZOOM_IN/OUT` (numpad + and -, a step of 1.2, also the Menu's zoom items),
`CAMERA_RESET` (Home), `CAMERA_FIT_BOARD` (End) and `ZOOM_OVERVIEW_TOGGLE` (Z).

Forwarded to MegaMek on purpose, among others: `NEXT_UNIT`/`PREV_UNIT` (Tab, Shift+Tab), the weapon and target
cycling binds, `FIRE`, `PHYS_PUNCH`, `PHYS_KICK` and `PHYS_PUSH` (they declare at once, because the native window
skips the "To hit" question), `MINIMAP` and `PLANETARY_CONDITIONS` (menu accelerators that toggle the preferences
the minimap and the conditions card follow), and in TARGETING and OFFBOARD the targeting display's own binds.
`GpuHudRoutingSmokeTest` presses every default bind in 14 phase and turn scenarios and checks that exactly one
handler runs.

## 5. The Tactical View

The Tactical View is the board seen straight down on its tileset columns. The scene, picking and animation timeline
stay the 3D view's own; only the shaded terrain becomes tileset columns and unit models become icons.

- **Switch it** with `TOGGLE_ISO` (T), the "Tactical view" utility, the Menu's "Tactical view" item, or the chip's
  "Back to 3D". Entering it ends Free Flight; switching Free Flight on leaves it. The chip ("Tactical view" with "Back to 3D") shows centred at the top when the top row has room, and
  the north mark "N ↑" centred below it, under the board labels.
- **Camera.** `BoardCamera.setTactical(enabled, scene)` saves the full 3D pose (focus, zoom, azimuth, tilt, whether
  the view fits the window) and turns the same orthographic camera straight down, north up. Pan, zoom, reset, fit
  and automatic framing work as usual. Orbit, tilt and the turn keys are ignored. Zoom has the 3D view's limits.
  Leaving restores the saved pose exactly; after a board change it fits the new board from the saved angle. The flag
  is render-thread state and is never saved.
- **Unit icons** (`GpuUnitIcons`, the hud-v3 `flat.js` icon):
  - a square 0.7 hex heights (0.6 hex widths) wide in board units at every zoom, like the classic 2D board's units;
    there is no on-screen size clamp, so an icon always fills the same share of its hex;
  - a dark tint of the side colour, the unit's classic sprite with its camouflage, a frame in the side colour and a
    tick on the facing side; the whole square turns with the unit's animated facing;
  - own and allied units mint, enemies coral; a sensor contact blip orange with a dashed frame, the radar image and no
    tick; a unit the battle status does not list, such as a wreck, grey;
  - the selected unit and the marked targets get a bold white frame, a hovered unit a thin one;
  - a destroyed or doomed unit fades to 45 % with the red cross. The shown pose decides, so the cross comes with the
    shot's impact on the animation timeline. An own or allied unit that has moved gets a dark veil.
  - Labels hang 0.62 of the icon's side above its centre. A click on an icon picks its unit, ahead of the ground.
- **Terrain.** `GpuTerrain.setTacticalView` follows the camera's mode every frame: `GpuTilesetTerrain` draws each 
  hex as a column at its level topped by its Saxarba tileset art, with the 3D view's structures, animated
  liquids, the hex grid, picking and overlay draping on the columns ([GPU board](gpu-board.md#tactical-view)). Trees
  and other feature meshes leave the board and picking, so a click returns the hex under the pointer, also where a 3D
  canopy would overhang the next hex. The wireframe view and the Tactical View exclude each other: turning one on
  turns the other off. The minimap shows the same tileset art.
- **Board overlay.** It uses the `flat.js` colours, draws the route's ghost as the unit's icon at half strength, and
  adds elevation-drop edges. The route's pulse is a flat glow on the dashed line, a ripple of the destination ring and
  a brighter ghost icon (section 3).
- **Tests.** `BoardCameraTacticalTest` covers the camera. `GpuUnitIconsTest` checks the icon's side (0.6 of its hex's
  width, within 1 %) and its corners inside the hex at every facing in 15-degree steps. `GpuTacticalViewSmokeTest`
  renders a 12 x 10 board with woods, jungle, buildings, a fuel tank, an industrial hex and a bridged river at
  1920 x 1080 and enters and leaves through the real T bind. It checks the restored pose, the icons' rotation and
  states, the frame pixels (exactly #82E2CE and #EC9189 on an Intel Iris Xe), the icon's share of the hex on screen
  (0.600 of hexes 20, 60, 300 and 800 pixels wide), picking at icons, on the columns' art and where a 3D canopy
  would overhang the next hex, and the field-of-view dimming of unseen tileset art. It was adapted to the tileset
  columns in the 2026-10-04 merge and not run there.
- **Limits.** Unlike the hud-v3 mock, whose squares stay upright, the whole icon turns with the unit. Frame widths
  scale with the icon, so on hexes 20 pixels wide the thin frame shows as broken dots, and a bold frame's corners
  reach 1 % of a hex past the hex.

## 6. Zoom-out unit scaling

Zoomed out, the 3D view's units grow so they stay readable (`UnitScreenScale`).

- While a hex is at least `THRESHOLD` (80) HUD pixels wide on screen, units keep their size. A HUD pixel is a window
  pixel divided by the display scale (`hexPixels(zoom, scale)` = `BoardGeometry.WIDTH / (zoom * scale)`).
- Further out, the factor is `threshold / hexPixels`, capped at `MAX` (2): a unit stays as large on screen as at the
  threshold, until it is twice its size. `ENABLED` (true) switches the rule.
- The growth multiplies the placed transform on every axis about the unit's feet, on top of `UnitFamilyScale`. Meshes
  keep their canonical size and proportions.
- Picking, bounds, outlines, anchors and labels, attack points and the route ghost follow the grown unit. The gait
  and the wheels use the grown scale too (`UnitAnimator`), so feet stay planted.
- Sensor contacts, the parts of a large unit and units across several hexes keep their size. The Tactical View's
  icons never grow.
- The constants are the defaults of the values in use (`UnitScreenScale.enabled`, `threshold`, `max`). The Tuning
  utility's Board page edits them under "Zoom-out unit scaling"; Defaults restores the constants.

Tests:
- `UnitScreenScaleTest`: the rule, the HUD-pixel measure across display scales, the uniform growth and a planted foot
  of a grown walking biped.
- `GpuUnitScreenScaleSmokeTest` zooms the running view out from the threshold. The Atlas stays 46.9 pixels tall on
  screen on hexes from 80 down to 40 pixels wide and 31.2 pixels at 26.7 (past the cap). Its anchor keeps pace, a
  point above the ungrown Atlas's head picks it, and the tuning rows change the growth on the next frame.
- `GpuLivePlaybackSmokeTest` measured the real Atlas's stride: 25.57 world units at its size and 50.64 grown twice.
- All on Windows 11 with an Intel Iris Xe.

## 7. Unit panel and paperdolls

The unit panel replaces Swing's Unit Display window in GPU mode.
- **Card** (`GpuUnitCard`, bottom left): the inspected unit, else the focus unit. It shows the paperdoll with a
  number per location (a Mek's rear armor in a strip under it), four vitals rows by unit family, one line of chips
  for what the doll cannot show, and the "Unit record" and Locate buttons. Hovering the doll shows a hover card. An
  card has a ✕ that clears the selection (the cleared selection, section 1.8).
- **Sheet** (`GpuRecordSheet`, `GpuUnitSheetTabs`): six tabs, opened by `UNIT_DISPLAY` and F1-F6. Its close button
  sits at the right end of the tab row; while it is open the card shrinks to its one-line mini form.

| Tab (key) | Content |
| --- | --- |
| Status (F1) | this round, pending changes, heat, movement, conditions |
| Crew (F2) | the pilot and the crew's advantages |
| Armor (F3) | front armor beside rear armor over structure (Meks), or one drawing |
| Weapons (F4) | the weapons, with ammunition and mode controls; an own unit's rows are a reorderable `UiList` (the grip drags a row, Alt+Up/Down moves the selected one) into its custom weapon order |
| Critical table (F5, Meks) or Systems (F5, other units) | the record-sheet slots (`GpuCriticalTable`) or the systems |
| Extras (F6) | sensors, networks, transport and the unit's readout |

- **Critical table** (`GpuCriticalTable`, Meks only): each location's slots in blocks of six under a bold heading with
  its CASE tag. Slot names are record-sheet names (`RecordSheetSlotNames`), and an empty slot reads "Roll Again". A
  hit or destroyed slot is struck through in coral, a slot of a blown-off or breached location in grey; empty and
  filler slots are dimmed. Ammunition shows its dots and a count badge, and a box shows the engine, gyro, sensor and
  life support hits.
- **Interaction.** A click on a location of the sheet's dolls (or Enter on the Armor tab's dolls) opens the Systems
  tab, a Mek's critical table, and briefly flashes that location's block; nothing is selected or filtered. Hovering a
  weapon row, slot or doll region outlines the location on every visible doll. The sheet's slot popover runs the
  record service's unit actions (modes, ammunition, dumping and the like) for the unit shown.

**Paperdoll pipeline.** The HUD reads platform-neutral JSON at run time: no SVG, Batik or AWT
(`GpuPaperdollsTest.everyFileParsesWithoutDesktopClasses` parses every file in a class loader without desktop
classes).

| Step | Where |
| --- | --- |
| Source art: 41 SVG views, copies of MekBay's location cut set | mm-data `tools/paperdolls/src` |
| Offline converter (Batik, validation, review PNGs) | `megamek.utilities.PaperdollConverter`, Gradle task `convertPaperdolls` |
| Output: one JSON per family and view (format 2: regions, triangles, outline rings, label anchors, value boxes, hull, shield variants) | mm-data `data/images/paperdolls`, with its README (licence, provenance, format, validation) |
| Review output | mm-data `tools/paperdolls/review`: a PNG per family and `validation.txt` |
| Staging | `stageDataImages` copies `paperdolls/` into `megamek/data/images` |
| Runtime | `GpuPaperdolls` (libGDX `JsonReader`, lazy per family, view and shield arms); `GpuPaperdoll` draws one view |

- The family comes from `GpuPaperdolls.family(Entity)`, the record-sheet choice of MegaMekLab. A family without JSON
  falls back to tiles.
- Biped and tripod front armor has shield variants per arm; a mounted shield shows its capacity and absorption
  numbers.
- To regenerate, edit the SVG copies, then run from the MegaMek checkout:

```powershell
.\gradlew.bat :megamek:convertPaperdolls
```

  The task reads `tools/paperdolls/src`, writes the JSON, the review PNGs and `validation.txt`, and writes no JSON for a
  family whose checks fail. Look at every review PNG after a change. The checks (codes, overlaps, the hands inside the
  arms, mirroring, anchors, shields) and the recorded corrections are in the JSON folder's README.

## 8. The UI toolkit

`megamek.client.ui.gdx` holds the hud-v3 design system and its generic widgets, for any native view.

| Class | Provides |
| --- | --- |
| `UiTheme` | colour tokens (`TEXT`, `MUTED`, `MINT`, `CORAL`, `AMBER`, `BLIP`, ...), the fonts, the icon sheet, the `hud-*` styles, the drawables `HudFrame`, `EdgeBox` and `Dashes`, the builders `box`, `flat`, `font`, `scrollStyle`, and the registration guard `add(name, resource, type)` |
| `UiKit` | `label`, `caption`, `icon`, `panel`, `header`, `closeButton`, `dialog`, `footer`, `empty`, `button`, `segmented` (tabs, separate segments, or `hud-pill`: one pill split in sections), `select`, `checkbox`, `menuRow`, `chip`, `search`, `meter`, `scrollList`, `tip` (hides when its actor or panel hides, so no tooltip stays stuck), `fill`; statics `text`, `onChange`, `size`; `DIALOG_WIDTH` (620) and `DIALOG_MARGIN` (140) |
| `UiButton` | the button with icons, detail lines, a pressed state, a badge and a dot; the only toolkit class that may be subclassed |
| `UiPopover`, `UiMenuList` | the popover frame (opens at a point or above an anchor, closes on an outside press) and its keyboard menu list |
| `UiList` | a vertical list of any rows; `reorderable(moved)` turns on drag-to-reorder by each row's handle (`add(row, handle)`): the lifted row follows the pointer above everything with a shadow, bounded to the list (and a scroll pane's view of it), a slot opens where it would drop and the other rows slide (springs), a scroll pane scrolls near its edges; `move(index, delta)` flies a row as the keyboard asks; `cancel()` (Esc) sends a dragged row home; `busy()` while a row moves. The view hears one `moved(from, to)` once the rows rest; the list keeps that order until the view gives it new rows and glides back after a second when none come |
| `UiFlow` | wrapped runs of differently styled text |
| `DisplayScale` | the one scale rule (1.7) |

Rules (checkstyle enforces the first):
- Toolkit code imports only `java` (no AWT), libGDX without its backends, `Messages`, `MMLogger` and its own package
  (`config/checkstyle/import-control.xml`). Only the test harness `UiTestStage` may open a desktop window. The guard
  does not see fully qualified names.
- Toolkit classes are `public final` (except `UiButton`), use no battle vocabulary, hold no static GL object, and take
  every user text from the caller. Tokens are read-only: derive colours with `UiTheme.alpha` or `tint`.
- Battle-only widgets stay in `GpuHudKit`: unit sprites (masks tinted #E4EBE7 for friends, coral for enemies), unit
  rows, letter tiles, heat bars. Components reach the toolkit through `kit.ui`.
- A widget or style with one user stays in its component; the change that adds a second user moves it into the
  toolkit under a generic name.
- No private copies of toolkit code: before finishing a change, run this on the changed files and justify every hit.

```text
grep -nE "extends BaseDrawable|new ScrollPane\(|M00\]|fontScale|private static (String text|Color (alpha|rgba|css|tint))|new ChangeListener|new TextTooltip\(" <files>
```

Fonts: the `hud-*` faces are named instances of the shipped Roboto variable font, generated at 4x and drawn at a
quarter. Characters Roboto lacks come from Noto Sans Symbols 2, then Bitter Bold (the arrows U+2190-U+2193, at 0.8 of
the size), merged into the same font. Icons are Material Symbols Rounded glyphs baked once into one mipmapped sheet.

**How to build a new view with the toolkit:**
1. Depend on `megamek.client.ui.gdx` only. Pass in every text (`UiKit.text(key, args)`), the fonts directory and the
   scale.
2. In `create()`, on the window's GL thread: `UiTheme theme = new UiTheme(Configuration.fontsDir());` (fonts, glyph
   pages, icon sheet, every `hud-*` style; one theme per window) and `UiKit ui = new UiKit(theme.skin);` (it owns
   nothing).
3. `Stage stage = new Stage(new ScreenViewport());` In `resize`: `float scale = DisplayScale.read(preference,
   contentScale);` with the GUI scale preference captured on the EDT and the window's content scale (desktop:
   `GpuDisplayScale.contentScale()`), then `((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
   stage.getViewport().update(width, height, true);`.
4. Panels: `Table panel = ui.panel();` then `panel.add(ui.header(title, count, ui.closeButton(this::close)))`,
   a list in `ui.scrollList(list)` with rows from `ui.button("hud-row", null, text, null)`, `ui.menuRow(...)` or
   `ui.empty(list, text)`, and `ui.footer(panel)`. Centred dialogs: `ui.dialog(title, close)`.
5. Controls: `UiButton button = ui.button(style, icon, text, sub); UiKit.onChange(button, action);` show state with
   `button.pressed(...)`; also `ui.segmented`, `ui.select`, `ui.search`, `ui.chip`, `ui.meter`, `ui.checkbox`;
   tooltips through `ui.tip(actor)`.
6. Custom drawing: `ui.fill(batch, color, alpha, x, y, width, height)`, `UiTheme.pixelScale(batch)` and `snap`, the
   drawables `UiTheme.HudFrame`, `EdgeBox`, `Dashes`.
7. A view's own styles go into the same skin through `theme.add(name, resource, type)`, `theme.box(...)` or
   `theme.font(...)`; a name the toolkit owns throws.
8. `dispose()`: `stage.dispose()`, then `theme.dispose()`, which frees every texture, glyph page, font and generator.
   Never share a theme between two applications.
9. Test with `UiTestStage.run(...)` (a hidden 1920 x 1080 window with a theme from `data/fonts`, which fails when a
   texture outlives the test or a label shows a missing message key), `UiTestStage.place`, `capture` and `compare`,
   as `UiGallerySmokeTest` does.

## 9. Swing in GPU mode

While the native window is active (`GpuBoardWindow.isActiveFor`), the HUD replaces the battle view's Swing surfaces:

| Swing surface | Native replacement |
| --- | --- |
| Menu bar | Menu utility (`GpuMenuPanel`) |
| Minimap window | `GpuMinimap` |
| Player list, bot commands | Players panel |
| Unit Display window | unit card and sheet |
| Force display, unit overview strips | forces panel and forces overview |
| Rounds in the air | the log's artillery in flight |
| Chat box overlay | chat panel |
| Toast overlay | toast stack |
| Keybindings overlay | Help |
| Planetary conditions overlay | Conditions card |
| Turn details overlay | the dock's Steps tooltip |
| Prompts and choice lists | the modal dialog (1.6) |
| Bot orders' hex picker dialog | the hint line's pick: the board's clicks, the Done key and Esc (2.1, 2.3, 4); in narrow windows the pick's chip with Done and Cancel |
| Off-board target arrows (`OffBoardTargetOverlay`) | the dock's off-board targets in the local TARGETING turn (3); several units beyond one edge are the modal's choice |

- `ClientGUI` keeps the minimap, player list, rounds in the air, force display, bot commands and Unit Display windows
  hidden while the native window is active; they show again on the classic board as their preferences say.
  Line of sight stays with the Swing `RulerDialog` with its elevation diagram:
  it shows over the native window for a measurement, as on the classic board, and the allowlist raises it. The
  context menu's "Line of sight from {unit}" measures with it (`RulerDialog.measure`), to the height the pointer
  showed where the menu opened (`RulerDialog.setHeight`).
- The frame carries no Swing overlay layers, so each toast shows once, in the native stack.
- **Allowlist.** `GpuBoardWindow.allowedOverBattle(Dialog)` decides which Swing dialogs may still show over the
  presented window: the classes in `GpuBoardWindow.SWING_DIALOGS` (the Menu's settings and tools, the Game Master
  editors, the reinforcement selector, the accessibility window, the Nova network view, unit cache loading, Readme and
  Help, the ruler with its elevation diagram and the planetary conditions editor), file choosers, a game master's
  choice of the player to reinforce, and the message boxes the user kept, known by their titles: the exit's save
  question, About, the bug report and its package result, and a reinforcement file's version check. A dialog that
  opens inside one of these (owned by it, or while it shows modally) belongs to it. An allowed dialog is raised over
  the native window. Any other is logged at error level and not raised, so it may open behind the window. While the
  window cannot ask the client's prompts itself (before its board source exists, or while it closes), the prompts
  show their Swing dialogs and every client dialog is raised. `GpuSwingDialogGateTest` drives the routed prompt
  chokepoints, the dialogs made native and a list prompt of each routed class with the window presented, and the same
  openers on the classic client.

### The map tools of the board editor and the map preview

The board editor's 3D view and the lobby's map preview run the same `GpuBattleView` over a `GpuMapSource`, which has
no game. `GpuBattleView` holds its HUD as a `GpuBoardHud`: the battle HUD (`GpuHud`) over a `GpuBoardSource`, else
`GpuMapHud`. The map tools show the battle HUD's utilities that apply to a map (Tactical view, Wireframe, Tuning; in
the editor also Menu with its menu bar, Tools and 2D Editor). At the bottom, the hovered or inspected hex's card
(`GpuMapSource.hexCard`: the hex with its level and theme, then the terrains a player sees with their terrain factors,
as the battle's hex tooltip names them; no automated terrains or terrain codes) stands over a hint line with the
editor's title or what the preview's measurement waits for, and the keys of the editor, Free Flight or the preview's
line of sight. A left drag paints in the editor. In the preview a right click inspects the hex, and a Ctrl or Alt
click measures with MegaMek's Swing ruler at the height the pointer shows, which the preview makes on its own board
state at the first measurement (`BoardSource.measure`); a plain click ends a measurement that waits for its second
point, and the board draws the ruler's line. The camera centres the board beside the editor's Swing tools
(`BoardSource.toolsInset`).

## 10. The developer Tuning utility

A developer and tester tool, to be removed before release.
- **Button:** "Tuning", last in the utility row. It opens a panel at the right gap, 90 below the window's top, 360
  wide and at most the window's height less 160, with the pages Board, Atmosphere, Terrain and Camera, and in its
  footer Defaults, Reload assets with its outcome, and Edit shaders (the GLSL editor).
- **Model:** `GpuBoardTuning` keeps every value as a Scene2D control that is never drawn; `GpuBattleView` reads the
  values from it. `GpuTuningPanel` mirrors the model's rows in the hud-v3 look, and an edit goes to the model's own
  control. Its tooltips are the model's texts; the row texts are English literals.

| Page | Sections |
| --- | --- |
| Board | Geometry (with Normal maps and VSync), Unit family sizes, Zoom-out unit scaling, Route pulse (on/off, speed, intensity), Unit visibility, Outside field of view, Outside sensor range, Unit damage |
| Atmosphere | Atmosphere presets with "Planetary conditions…" (the Swing editor), Lighting (time of day, exposure, moonlight, fixed sun), Planet properties, Clouds and ground air, Weather effects, Light and fog effects |
| Terrain | Terrain build progress, concrete shapes, river shape and land, banks and river openings, waterfalls, terrain detail (grass blades, terrain LoD), material geology |
| Camera | Fixed sun/moon, framing on selection, framing on movement (combat framing is the dock's Follow toggle); the model's camera rows: Free Flight, the thermal wireframe view, the Free Flight field of view |

**Removing it before release:**
1. Delete `GpuTuningPanel` and `GpuTuningPanelSmokeTest`.
2. In `GpuHud`, delete the tuning block (its comment says so) and the `tuningModel` constructor parameter, and drop
   the argument in `GpuBattleView.createBoard`.
3. Delete `GpuHudState.Dialog.TUNING` and the `GpuBoard.hud.tuning.*` message keys.
4. Remove the panel steps from the tests that click `tuning-button` or measure it: GpuAtmosphereSmokeTest,
   GpuBoardSmokeTest, GpuHudLayoutSmokeTest, GpuHudRoutingSmokeTest and GpuPlanetaryConditionsSmokeTest.
5. Keep `GpuBoardTuning`: the board reads its values, and the Wireframe utility and Free Flight switch its camera
   rows. Its `cameraRows()`, `boardRows()`, `atmosphereRows()`, `terrainRows()` and footer buttons also serve
   `GpuBoardTestUi.tuning`, which many smoke tests use. Keep `UiKit.checkbox` too: `GpuModalDialog` uses it.
6. Move Reload assets and Edit shaders to another developer surface first, or drop them with the panel.

The tuning model's "Planetary conditions…" button is the only caller of `GpuBoardSource.editPlanetaryConditions`, so
without the panel the visual conditions preview is gone from the native window.

## 11. Tests

Use the project's Gradle wrapper from the repository root (`./gradlew` outside Windows), with mm-data beside the
checkout. `test` excludes `@Tag("on-demand")`, runs `checkstyleMain` first and stages the data.

```powershell
# Unit tests of the GPU package and the toolkit (no OpenGL)
.\gradlew.bat :megamek:test --tests "megamek.client.ui.clientGUI.boardview.gpu.*" --tests "megamek.client.ui.gdx.*"
# One class or method
.\gradlew.bat :megamek:test --tests "*GpuHudInputTest"
# Smoke tests (on-demand; need desktop OpenGL; write PNGs to megamek/build/gpu-board-review)
.\gradlew.bat :megamek:gpuBoardSmoke --tests "*GpuHudRoutingSmokeTest"
.\gradlew.bat :megamek:gpuBoardSmoke
# Checkstyle, as CI runs it
.\gradlew.bat :megamek:checkstyleMain :megamek:checkstyleTest
```

- `gpuBoardSmoke` runs the on-demand tests named `megamek.client.ui.clientGUI.boardview.gpu.*SmokeTest` and
  `megamek.client.ui.gdx.*SmokeTest`, and always reruns. Most open hidden windows; `GpuBoardWindowSmokeTest` opens
  the real, maximised native window.
- Screenshots go to `megamek/build/gpu-board-review` (system property `megamek.gpu.screenshots`). Side-by-side
  comparisons with the hud-v3 screenshots read the folder named by `megamek.gpu.hudMock` (default
  `../../megamek_temp/docs/design/claude-ui-concepts/hud-v3`, relative to `megamek`) and are skipped without it.
- Every capture through `UiTestStage` or `GpuHudTestStage` fails when a label shows a missing message key
  (`!key!`). `GpuUnitCardSmokeTest` runs that check on the whole stage of a real `GpuHud`.
- Lit 3D scenes take a random time of day per board source, so their screenshots differ from run to run.

| Area | Tests |
| --- | --- |
| Routing and keys | `GpuHudInputTest` (dispatch, Esc chain, focus rule, held units, click routing; no GL), `GpuHudRoutingSmokeTest` (real `BoardInput` and `GpuHud`: every default bind in 14 scenarios, clicks and drags, text fields, dialogs before the first board), `GpuKeyboardSmokeTest`, `ClientGUIHotKeyTest` |
| Modal bridge and Swing cut | `GpuModalBridgeTest`, `GpuDialogRoutingTest`, `GpuListPromptBridgeTest`, `GpuChatToastModalSmokeTest`, `GpuSwingCutTest`, `GpuSwingCutSmokeTest` (a scripted round with real Swing windows under the native window), `GpuVictoryHexFormTest`, `GpuStoryDialogTest`, `GpuHexPickTest` (the bot orders' hex pick), `GpuTurretFacingRoutingTest`, `GpuSwingDialogGateTest` (every prompt opener with the window presented and on the classic client; the allowlist) |
| Live view | `GpuLiveBoardSpaceSmokeTest` (board labels, overlay, traces, minimap, 100 v 100), `GpuLivePlaybackSmokeTest` (presented units, review, speeds, stride), `GpuHudParitySmokeTest` (the live view of a real battle beside each of the 16 hud-v3 shots: every anchored panel within 2 units of the mock's frame; `parity-NN-sbs.png`) |
| Layout and coverage | `GpuHudLayoutSmokeTest` (900 x 600, 1280 x 720, 1920 x 1080), `GpuMenuCoverageSmokeTest` (every menu item and every phase command of real phase displays reachable) |
| Board overlay | `GpuBoardOverlaySmokeTest` (beside the mock's shots; envelopes, route, hover preview, ghost, drop edges, rebuilds), `GpuRoutePulseSmokeTest` (the route's pulse frame by frame: the head from the unit to the destination, the surge, the rhythm across rebuilds, a jump's arc, a turn in place, none without a plotted route or switched off, the Tactical View, and the real view's own frames moving it on without a rebuild; `pulse-*.png` and its measured cost in `pulse-cost.txt`) |
| Services | `GpuHudServicesTest`, `GpuMovePlanTest`, `GpuFireOrdersTest`, `GpuFireDraftsTest`, `GpuOffBoardTargetsTest` (the classic off-board arrows pinned; the dock's command sends the same attack; the native choice and its Esc), `GpuPhysicalOptionsTest`, `GpuUnitRecordTest`, `GpuFirePreviewTest`, `GpuLosResultTest`, `GpuHudMessagesTest`, `GpuChatRoundLinesTest`, `GpuBattleStatusTest`, `GpuReportLogTest`, `GpuPlaybackHistoryTest`, `GpuBotCommandsTest` |
| Components | one smoke test per component, e.g. `GpuPhaseHeaderSmokeTest`, `GpuForcesPanelSmokeTest`, `GpuCommandDockSmokeTest`, `GpuWeaponsPanelSmokeTest`, `GpuTargetCardsSmokeTest`, `GpuLogPanelSmokeTest`, `GpuUnitCardSmokeTest`, `GpuTuningPanelSmokeTest`; `GpuCardPlacementTest`, `GpuContextMenuTest` |
| Toolkit and skin | `UiGallerySmokeTest`, `UiListSmokeTest`, `DisplayScaleTest`, `GpuHudKitSmokeTest`, `GpuHudSkinSmokeTest`, `GpuPaperdollTest`, `GpuPaperdollsTest`, `GpuPaperdollSmokeTest` |

## 12. Known limits

### 12.1 Not verified

- **Platforms.** Every recorded test run was on Windows 11, and every GL run reported the Intel Iris Xe as its
  `GL_RENDERER`. The hybrid laptop's RTX 4070 was not measured. Nothing was run on macOS or Linux: the macOS screen menu bar was not observed, and on macOS FlatLaf
  binds only Space in a confirm dialog (read from its input maps, not run), so a native dialog's Enter = Yes would
  differ from Swing there.
- **Running game.** The automated tests use fixtures, real phase displays and mocked or local clients. These checks
  in a game against a server were not performed: a `MovementDisplay.ready()` nag opens no Swing dialog, the native
  window is raised when a dialog is asked, no Unit Display window opens in any phase, and a punch declares without the
  yes/no question. Full games across all phases and usability sessions with new and experienced players are still
  needed before the native view could replace the classic one by default.

### 12.2 Behaviour

- **Off-board artillery.** The native window draws no board overlay, so MegaMek's off-board target arrows
  (`OffBoardTargetOverlay`) become the dock's commands in the local TARGETING turn: one per edge whose arrow shows,
  named after the edge ("Off-board West"), not after the units beyond it. The HUD does not show which weapon the
  targeting display has selected; the commands follow MegaMek's selection, which its Skip command and weapon keys
  cycle.
- **TARGETING and OFFBOARD** keep MegaMek's board tool and keys, with the generic dock.
- **Dialogs.** A native dialog shows no severity icon, does not answer the confirm dialog's Y and N keys, and shows
  monospaced alerts in Roboto. A nested outer dialog loses typed text when it shows again. A Swing dialog missing from
  the allowlist is not raised, so it may open behind the native window; the error log names it. A dialog with no
  owner (`MMLogger.errorDialog`'s) or of another client's window is left as it is: neither raised nor reported. A
  local bot's alert, which has no owner either, is its creating client's native alert while that client's window is
  presented.
- **Initiative** shows a side's total, roll and bonus, not its dice: the client does not receive team dice.
- **Presented units** switch to the client's state only when the whole playback queue has drained, so the record's
  "-n" delta shows a volley's total. A physical attack's review does not move the camera.
- **Zooming during a walk** moves the leg phase and the wheel angle at once, because the gait follows the drawn size.
- **The "reveal all artillery rounds" debug flag** reaches the server only when it changes, because the hidden rounds
  window no longer pushes it in GPU mode.
- **Saved games.** A save written by this build stores the kept initiative dice (`InitiativeRoll`'s `<keptDice>`
  element). Older saves load (tested); an older MegaMek build is expected to reject the new element (not tried).
- **Glyphs.** No shipped face has ↰ ↱, several planetary-condition symbols (⌁ ⌒ ⎚ ⎯ █ ░ ☽ ⚐ ⟡ ⇶) or emoji;
  they draw as the missing glyph or blank. ⟐ and ⋯ draw as their lookalikes ◈ and … (`UiTheme`).

### 12.3 Tests

- `GpuBoardWindowSmokeTest`'s `switchesExclusiveWindows...` failed under load ("A second request must retain the same
  framing"): it read the camera before the source's timer had published the center request. It now waits until the
  render thread has applied the request.
- Smoke tests bounded by a frame count can run out early when frames are very fast; `GpuCombatCameraSmokeTest` uses a
  30-second budget because a hidden, uncapped window rendered 400-600 frames per second.
- Five smoke tests outside the HUD fail in every recorded full run: `GpuAtmosphericTaintSmokeTest`,
  `GpuBuildingMaterialsSmokeTest`, `GpuPlaybackSmokeTest`, `GpuScatterSmokeTest` and `GpuVolleySmokeTest`.

### 12.4 Measured costs

All on Windows 11; GPU numbers on an Intel Iris Xe, often with other builds running on the machine.

| What | Measured |
| --- | --- |
| Board overlay rebuild at 100 v 100 | 2.7-3.2 ms in 3D, 1.7-2.1 ms in the Tactical View; an unchanged recapture at most 0.22 ms and no rebuild |
| The route's pulse, per frame (1920 x 1080, `GpuRoutePulseSmokeTest`) | the overlay's draw takes 0.026-0.039 ms more GPU time with the pulse (median of 100 timestamped frames each: 43 quads while the head travels, 75 while the ring ripples) and under 0.02 ms more submission time; filling and uploading the quads 0.001-0.004 ms median (CPU timings under JaCoCo) |
| The frame's "board-space HUD" stage (HudView, `GpuHud.update`, overlay update) at about 200 units | 0.39-0.66 ms median; while the pointer sweeps, 2.1-3.6 ms median, at most 10.4 ms |
| Forces overview, first open at 100 v 100 | about 0.5 s in a profiling run (200 cards built in 251 ms, laid out in 91 ms, first frame 165 ms), 133 ms in a later run; a status change then 1.5-2.9 ms. Mostly the JVM's and the fonts' first use: a second overview built in the same run took 80-120 ms (cards 40-74 ms, layout 29-38 ms, first frame 6 ms), and 49 ms after other tests had run |
| Movement fire preview (CPU only, i7-13700H) | a plan change 16-43 ms at 12 v 12 and 0.24-0.50 s at 100 v 100; slices of 8 ms budget, at least one enemy each, up to about 31 ms per Swing event at 100 v 100; where the unit stands, a preview starts only after 250 ms without a game change |

They come from the stages' runs on one laptop and are no performance claim beyond those runs.
