/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.sprite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardTacticalGraphics;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.units.Entity;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The target movement modifier a plotted path shows under its last step (a Dervish: walk 5, run 8, jump 5). */
class StepSpriteTmmTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void walkingThreeHexes() throws Exception {
        assertEquals(Set.of("+1"), tmmLabels(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS));
    }

    @Test
    void walkingFiveHexes() throws Exception {
        assertEquals(Set.of("+2"), tmmLabels(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS, MoveStepType.FORWARDS));
    }

    @Test
    void runningSevenHexes() throws Exception {
        assertEquals(Set.of("+3"), tmmLabels(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS));
    }

    /** Three hexes give +1 and the jump another +1. */
    @Test
    void jumpingThreeHexes() throws Exception {
        assertEquals(Set.of("+2"), tmmLabels(MoveStepType.START_JUMP, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS));
    }

    /** Draws the last step of the path and returns the signed labels it writes (the modifier and its shadow). */
    private static Set<String> tmmLabels(MoveStepType... steps) throws Exception {
        FutureTask<Set<String>> task = new FutureTask<>(() -> {
            Game game = new Game();
            game.setBoard(ClearBoard.of(16, 17));
            game.setPhase(GamePhase.MOVEMENT);
            Player player = new Player(0, "Player");
            game.addPlayer(0, player);
            Entity dervish = new MekFileParser(new File("testresources/megamek/common/units/Dervish DV-11DK.mtf"))
                  .getEntity();
            dervish.setId(1);
            dervish.setOwner(player);
            dervish.setPosition(new Coords(7, 12));
            dervish.setFacing(0);
            dervish.setDeployed(true);
            game.addEntity(dervish, false);
            MovePath path = new MovePath(game, dervish);
            for (MoveStepType step : steps) {
                path.addStep(step);
            }
            BoardView view = new BoardView(game, null, null, 0);
            try {
                BoardTacticalGraphics graphics = new BoardTacticalGraphics();
                new StepSprite(view, path.getLastStep(), true).drawTactical(graphics);
                return graphics.snapshot().labels().stream().map(label -> label.text().value())
                      .filter(text -> text.startsWith("+") || text.startsWith("-")).collect(Collectors.toSet());
            } finally {
                view.dispose();
            }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
