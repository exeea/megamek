/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardEditorFuelTankTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({ "1, 2", "1, 3", "1, 4", "2, 3", "2, 4", "3, 4" })
    void differentTankWeightsConnectWithDefaultAndTankFarmDesignsAndSurviveSaving(int firstWeight, int secondWeight)
          throws Exception {
        var blueprint = BoardEditorBlueprint.get();
        Coords first = new Coords(1, 1), second = first.translated(2);
        for (String design : List.of("", "building/tank-farm")) {
            var session = new BoardEditorSession();
            var entry = blueprint.entry("fuelTank/default");
            String terrain = blueprint.component(entry.owner()).fields().getFirst().terrain();
            send(session, Action.CHOOSE_BRUSH, entry.owner(), entry.preset().id());
            if (!design.isEmpty()) { send(session, Action.BRUSH_VALUE, "variant:fuelTank", design); }
            send(session, Action.BRUSH_VALUE, "terrain:" + terrain, Integer.toString(firstWeight));
            session.pointer(first, 0, 0, false); session.finishStroke();
            send(session, Action.BRUSH_VALUE, "terrain:" + terrain, Integer.toString(secondWeight));
            session.pointer(second, 0, 0, false); session.finishStroke();
            send(session, Action.TOOL, "", "SELECT");
            session.pointer(first, 0, 0, false); session.finishStroke();
            send(session, Action.EDGE, terrain, "2");
            session.pointer(second, 0, 0, false); session.finishStroke();
            send(session, Action.EDGE, terrain, "5");
            assertTrue(session.snapshot().issues().isEmpty(), design + ": " + session.snapshot().issues());
            assertTanks(session.board(), first, second, firstWeight, secondWeight);

            send(session, Action.UNDO, "", "");
            assertFalse(session.board().getHex(second).containsTerrainExit(Terrains.FUEL_TANK, 5));
            send(session, Action.REDO, "", "");
            assertTrue(session.snapshot().issues().isEmpty());
            assertTanks(session.board(), first, second, firstWeight, secondWeight);

            Path file = directory.resolve(entry.owner() + ".board2");
            session.save(file);
            assertTanks(BoardFile.read(file), first, second, firstWeight, secondWeight);
        }
    }

    private static void assertTanks(Board board, Coords first, Coords second, int firstWeight, int secondWeight) {
        assertTrue(board.isValid());
        assertEquals(firstWeight, board.getHex(first).terrainLevel(Terrains.FUEL_TANK));
        assertEquals(secondWeight, board.getHex(second).terrainLevel(Terrains.FUEL_TANK));
        assertTrue(board.getHex(first).containsTerrainExit(Terrains.FUEL_TANK, 2));
        assertTrue(board.getHex(second).containsTerrainExit(Terrains.FUEL_TANK, 5));
        for (Coords at : List.of(first, second)) {
            assertFalse(board.getHex(at).containsTerrain(Terrains.BUILDING));
            assertEquals(100, board.getHex(at).terrainLevel(Terrains.FUEL_TANK_MAGN));
        }
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
