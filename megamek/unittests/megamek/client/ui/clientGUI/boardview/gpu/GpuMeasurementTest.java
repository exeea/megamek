/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuMeasurementTest {
    @Test
    void mouseMeasurementsRetainTheirEndpointsWithoutAnActingUnit() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<BoardViewEvent> events = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> fixture.view.addBoardViewListener(new BoardViewListenerAdapter() {
                @Override
                public void hexMoused(BoardViewEvent event) {
                    events.add(event);
                }
            }));
            Coords start = new Coords(4, 4), end = new Coords(8, 6);
            // The HUD hands every Ctrl or Alt click to MegaMek's tools, both endpoints as on the classic board.
            fixture.source.click(start, false, InputEvent.CTRL_DOWN_MASK);
            SwingUtilities.invokeAndWait(() -> assertEquals(start, fixture.view.getFirstLOS()));
            fixture.source.click(end, false, InputEvent.CTRL_DOWN_MASK);
            SwingUtilities.invokeAndWait(() -> assertNull(fixture.view.getFirstLOS()));
            fixture.source.click(start, false, InputEvent.ALT_DOWN_MASK);
            fixture.source.click(end, false, InputEvent.ALT_DOWN_MASK);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of(start, end), events.stream().map(BoardViewEvent::getCoords).toList());
                assertTrue(events.stream().allMatch(event -> event.getType() == BoardViewEvent.BOARD_HEX_CLICKED
                      && event.getModifiers() == InputEvent.ALT_DOWN_MASK));
            });
        }
    }

    @Test
    void rulerAndLosUpdateAndClearWithoutChangingRasterTiles() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                BoardScene before = fixture.source.takeFrame().scene();
                Coords start = new Coords(4, 4), end = new Coords(8, 6);
                fixture.view.drawRuler(start, null, Color.CYAN, Color.ORANGE);
                fixture.source.refresh();
                var first = fixture.source.takeFrame().scene().tactical();
                assertEquals(start, first.ruler().start());
                assertNull(first.ruler().end());

                fixture.view.drawRuler(start, end, Color.CYAN, Color.ORANGE);
                fixture.view.checkLOS(start);
                fixture.view.checkLOS(end);
                fixture.source.refresh();
                BoardScene measured = fixture.source.takeFrame().scene();
                var colors = measured.tactical().fills().stream().map(fill -> fill.argb()).toList();
                assertTrue(colors.contains(Color.RED.getRGB()));
                assertEquals(start, measured.tactical().ruler().start());
                assertEquals(end, measured.tactical().ruler().end());
                assertFalse(colors.contains(Color.YELLOW.getRGB()), "The ruler must not become a draped fill");
                assertEquals(before.tiles().stream().map(BoardScene.Tile::tactical).toList(),
                      measured.tiles().stream().map(BoardScene.Tile::tactical).toList());

                fixture.view.drawRuler(null, null, Color.CYAN, Color.ORANGE);
                fixture.view.select(null);
                fixture.source.refresh();
                assertEquals(before.tactical(), fixture.source.takeFrame().scene().tactical());
                assertFalse(measured.tactical().fills().isEmpty(), "Clearing must not mutate a published frame");
            });
        }
    }
}
