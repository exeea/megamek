# Codebase

[Docs index](README.md). Java packages start under [megamek/src/megamek](../megamek/src/megamek/).

## Application structure

`MegaMek` is the executable entry point. `MegaMekGUI` provides the main menu and
launches games and tools. Once connected to a game,
[ClientGUI](../megamek/src/megamek/client/ui/clientGUI/ClientGUI.java) manages the phase
panels, menus, board selection and client windows. The selected board renderer shares
that client session and its pending orders.

The three main areas are `common`, `server` and `client`. Shared rules and model
objects live in `common`; the server resolves submitted actions; the client handles
network updates, interaction and presentation. Most work starts in one of these areas
and follows an existing action or event across the boundary.

## Game state and rules

`common/game/Game` holds the game, players, entities, phase and turn information.
`common/board/Board` contains the hex grid; `common/Hex` holds each hex's elevation
and terrain. `common/units/Entity` is the shared unit base, with unit-specific behavior
in classes such as `Mek`, `Tank` and `Aero`. Battle Armor also has its own
`common/battleArmor` package.

Movement is represented by `common/moves/MovePath` and its steps. The movement code
checks movement modes, costs and restrictions; `common/pathfinder` searches possible
routes using those rules. Combat calculations are spread across `common/compute`,
`common/actions` and `common/weapons`: actions describe declarations, while weapon
handlers resolve weapon-specific behavior. `common/equipment` contains equipment
definitions and mounted-equipment state. Start sight and concealment investigations
with `LosEffects` and `EntityVisibilityUtils`.

Game options in `common/options` affect rules. Client preferences in
`common/preference` and `GUIPreferences` affect local behavior and presentation.
A visual preference should not become a second copy of a game option.

## Follow an action through the system

For movement, `MovementDisplay` lets the player build a `MovePath` against the
client's game state. Submitting the turn sends that order through the client connection.
`Server` receives packets and delegates game processing to
[TWGameManager](../megamek/src/megamek/server/totalWarfare/TWGameManager.java).
`MovePathHandler` processes the movement and its consequences. Results are sent back
to clients, where `AbstractClient` and `Client` apply incoming updates and emit game events.

The UI listens to those events. Phase panels refresh their controls, and board
presentation captures the updated state. Animation can continue while further network
updates arrive; the renderer's displayed position is therefore distinct from the
latest resolved unit position.

Attacks follow the same broad route: a phase display prepares action declarations,
the server resolves them using the relevant handlers, and clients present the results.
When fixing a rule, trace both the client preview and server resolution. When fixing a
button, begin with its phase controller; when fixing its appearance, follow the
[UI adapter](controls-and-interaction.md) or [board renderer](gpu-board.md).

## Bots, data and supporting code

[Princess](../megamek/src/megamek/client/bot/princess/Princess.java) is a bot client.
Its package evaluates movement and attacks and constructs orders through the game
interfaces. Bot decision quality belongs there; shared legality still belongs in the
rules code. `common/alphaStrike`, `common/strategicBattleSystems` and
`common/autoResolve` contain separate game modes and resolution systems.

`common/loaders`, including `MekFileParser`, reads unit definitions.
`common/scenario` reads scenario setup, and `Board` handles board loading.
`utilities` contains exporters, validators and command-line tools, including the
model catalog exporters used by the asset pipeline.

[Resources](../megamek/resources/) contains bundled strings, icons and shaders.
Client text is in `megamek/client/messages.properties`. Maps, unit definitions and
model assets originate in the sibling `mm-data` repository and are staged for runtime use.
[Unit tests](../megamek/unittests/) mirror the source packages; `megamek/testresources`
contains their fixtures.
