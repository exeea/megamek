/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;

import megamek.client.ui.clientGUI.boardview.gpu.BoardSceneryLayouts;
import megamek.common.Configuration;
import megamek.common.board.BoardEditorBlueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Times the editor's full issue validation on the largest shipped board, Valencia City (128 × 192), imported as the
 * editor opens it. Opt-in: set {@code MEGAMEK_VALENCIA_TIMING=1}. The result decides whether validation may run on the
 * EDT (docs/3d-board-editor.md, Issues).
 */
@EnabledIfEnvironmentVariable(named = "MEGAMEK_VALENCIA_TIMING", matches = "1")
class BoardEditorValidationTimingTest {
    @Test
    void timesFullValidationOfValenciaCity() throws Exception {
        Path file = Configuration.boardsDir().toPath()
              .resolve("unofficial/VictorMorson/128x192 Texlos - Livius - Valencia_City.board");
        var session = new BoardEditorSession(BoardEditorBlueprint.get(), BoardSceneryLayouts.DECODER);
        long opened = System.nanoTime();
        session.open(file);
        System.out.printf("VALENCIA open+import+validate %.0f ms%n", (System.nanoTime() - opened) / 1e6);
        assertEquals(128 * 192, session.board().getWidth() * session.board().getHeight());
        int issues = session.snapshot().issues().size();
        long objects = 0;
        for (int y = 0; y < session.board().getHeight(); y++) {
            for (int x = 0; x < session.board().getWidth(); x++) { objects += session.board().getHex(x, y).getDecorations().size(); }
        }
        // A fresh session has not yet looked up any model file: the first validation after opening a document.
        var fresh = new BoardEditorSession(BoardEditorBlueprint.get(), BoardSceneryLayouts.DECODER);
        fresh.game().setBoard(session.board());
        long cold = System.nanoTime();
        fresh.validateAll();
        System.out.printf("VALENCIA first validation %.1f ms%n", (System.nanoTime() - cold) / 1e6);
        for (int warm = 0; warm < 5; warm++) { session.validateAll(); }
        double[] millis = new double[20];
        for (int run = 0; run < millis.length; run++) {
            long start = System.nanoTime();
            session.validateAll();
            millis[run] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(millis);
        System.out.printf("VALENCIA full validation: %d hexes, %d objects, %d issues; min %.1f ms, median %.1f ms, max %.1f ms%n",
              128 * 192, objects, issues, millis[0], millis[millis.length / 2], millis[millis.length - 1]);
        assertEquals(issues, session.snapshot().issues().size(), "Validation is repeatable");
    }
}
