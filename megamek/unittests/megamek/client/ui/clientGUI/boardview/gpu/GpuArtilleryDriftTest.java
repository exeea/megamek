/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.SpecialHexDisplay;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/**
 * A missed artillery round on the 3D board: the hex it was aimed at carries a drift line to where it came down, which
 * the board overlay draws, and no standing symbol.
 */
class GpuArtilleryDriftTest {
    @Test
    void aMissThatDriftedBecomesADriftLineFromItsAimedHex() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords aimed = new Coords(8, 8);
            Coords landing = new Coords(8, 11);
            SwingUtilities.invokeAndWait(() -> {
                var miss = new SpecialHexDisplay(SpecialHexDisplay.Type.ARTILLERY_MISS, fixture.game.getRoundCount(),
                      fixture.player, "Artillery missed here");
                miss.setDriftHex(landing);
                fixture.game.getBoard().addSpecialHexDisplay(aimed, miss);
                fixture.source.refresh();
            });
            List<BoardMarker> drifts = fixture.source.takeFrame().scene().markers().stream()
                  .filter(marker -> marker.kind() == BoardMarker.Kind.ARTILLERY_DRIFT)
                  .toList();

            assertEquals(1, drifts.size());
            assertEquals(aimed, drifts.getFirst().coords());
            assertEquals(landing, drifts.getFirst().driftTo());
        }
    }

    @Test
    void aMissThatCameDownOnItsTargetDrawsNoLine() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getBoard().addSpecialHexDisplay(new Coords(8, 8),
                      new SpecialHexDisplay(SpecialHexDisplay.Type.ARTILLERY_MISS, fixture.game.getRoundCount(),
                            fixture.player, "Artillery missed here, landed on target"));
                fixture.source.refresh();
            });
            boolean hasDrift = fixture.source.takeFrame().scene().markers().stream()
                  .anyMatch(marker -> marker.kind() == BoardMarker.Kind.ARTILLERY_DRIFT);

            assertFalse(hasDrift);
        }
    }
}
