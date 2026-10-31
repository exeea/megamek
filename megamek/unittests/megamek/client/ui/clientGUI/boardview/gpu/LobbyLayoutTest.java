/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.client.ui.clientGUI.boardview.gpu.LobbyLayout.Columns;
import org.junit.jupiter.api.Test;

class LobbyLayoutTest {
    private static final float GUTTER = 12;

    @Test
    void foldsBelowTheReferenceWidth() {
        assertTrue(LobbyLayout.folds(1100));
        assertTrue(LobbyLayout.folds(1279));
        assertFalse(LobbyLayout.folds(1280));
        assertFalse(LobbyLayout.folds(1600));
    }

    @Test
    void theCentreTakesWhatTheSideColumnsLeave() {
        Columns columns = LobbyLayout.columns(1576, 800, GUTTER, false);

        assertEquals(LobbyLayout.PLAYERS_WIDTH, columns.players().width());
        assertEquals(LobbyLayout.BATTLEFIELD_WIDTH, columns.battlefield().width());
        assertEquals(1576 - LobbyLayout.BATTLEFIELD_WIDTH, columns.battlefield().x());
        assertEquals(LobbyLayout.PLAYERS_WIDTH + GUTTER, columns.centre().x());
        assertEquals(1576 - LobbyLayout.PLAYERS_WIDTH - LobbyLayout.BATTLEFIELD_WIDTH - 2 * GUTTER,
              columns.centre().width());
        assertEquals(800, columns.centre().height());
        assertNull(columns.rail());
    }

    @Test
    void foldedTheRailStandsInTheBattlefieldsPlace() {
        Columns columns = LobbyLayout.columns(1076, 700, GUTTER, true);

        assertNull(columns.battlefield());
        assertEquals(LobbyLayout.RAIL_WIDTH, columns.rail().width());
        assertEquals(1076 - LobbyLayout.RAIL_WIDTH, columns.rail().x());
        assertEquals(1076 - LobbyLayout.PLAYERS_WIDTH - LobbyLayout.RAIL_WIDTH - 2 * GUTTER,
              columns.centre().width());
    }

    @Test
    void aSmallWindowCapsTheSideColumns() {
        Columns columns = LobbyLayout.columns(600, 400, GUTTER, false);

        assertEquals(150, columns.players().width());
        assertEquals(200, columns.battlefield().width());
        assertEquals(600 - 150 - 200 - 2 * GUTTER, columns.centre().width());
        assertTrue(LobbyLayout.columns(10, 10, GUTTER, true).centre().width() >= 0);
    }

    @Test
    void chipsCentreOnTheBarAndStepAsideOnlyWhenCrowded() {
        assertEquals(700, LobbyLayout.chipsX(1600, 300, 200, 300, 12));
        assertEquals(412, LobbyLayout.chipsX(1000, 400, 300, 100, 12));
        assertEquals(1000 - 500 - 12 - 300, LobbyLayout.chipsX(1000, 100, 300, 500, 12));
        assertEquals(312, LobbyLayout.chipsX(800, 300, 400, 300, 12));
    }
}
