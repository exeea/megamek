/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.clientGUI.boardview.RulerModel;
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
            // Ctrl is phase input. Alt starts native LOS, and the HUD completes it with a plain measurement click.
            fixture.source.click(start, false, InputEvent.CTRL_DOWN_MASK);
            SwingUtilities.invokeAndWait(() -> {
                assertNull(fixture.view.getFirstLOS());
                assertEquals(0, fixture.source.takeFrame().panels().los().pending());
            });
            fixture.source.measure(start, InputEvent.ALT_DOWN_MASK, Float.NaN);
            SwingUtilities.invokeAndWait(() -> {
                var los = fixture.source.takeFrame().panels().los();
                assertEquals(start, los.start().coords());
                assertEquals(InputEvent.ALT_DOWN_MASK, los.pending());
            });
            fixture.source.measure(end, 0, Float.NaN);
            SwingUtilities.invokeAndWait(() -> {
                var los = fixture.source.takeFrame().panels().los();
                assertEquals(start, los.start().coords());
                assertEquals(end, los.end().coords());
                assertEquals(0, los.pending());
                assertNull(fixture.view.getFirstLOS(), "Native LOS leaves the Legacy 2D tool untouched");
                assertEquals(List.of(start), events.stream().map(BoardViewEvent::getCoords).toList(),
                      "Measurement clicks must not reach the phase display");
                assertTrue(events.stream().allMatch(event -> event.getType() == BoardViewEvent.BOARD_HEX_CLICKED
                      && event.getModifiers() == 0));
            });
        }
    }

    @Test
    void rulerAndLosUpdateAndClearWithoutChangingRasterTiles() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardScene before = fixture.source.takeFrame().scene();
            Coords start = new Coords(4, 4), end = new Coords(8, 6);
            fixture.source.measure(start, InputEvent.ALT_DOWN_MASK, Float.NaN);
            SwingUtilities.invokeAndWait(() -> {
                var first = fixture.source.takeFrame();
                assertEquals(start, first.panels().los().start().coords());
                assertEquals(start, first.scene().tactical().ruler().start());
                assertNull(first.scene().tactical().ruler().end(), "The ray waits for its second endpoint");
            });
            fixture.source.measure(end, 0, Float.NaN);
            SwingUtilities.invokeAndWait(() -> {
                BoardScene measured = fixture.source.takeFrame().scene();
                assertEquals(start, measured.tactical().ruler().start());
                assertEquals(end, measured.tactical().ruler().end());
                assertEquals(before.tactical().fills(), measured.tactical().fills(),
                      "The native ruler is a ray, never a draped fill");
                assertEquals(before.tiles().stream().map(BoardScene.Tile::tactical).toList(),
                      measured.tiles().stream().map(BoardScene.Tile::tactical).toList());
            });
            BoardScene measured = fixture.source.takeFrame().scene();
            fixture.source.changeRuler(fixture.source.takeFrame().boardGeneration(), RulerModel::clear);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(before.tactical(), fixture.source.takeFrame().scene().tactical());
                assertEquals(start, measured.tactical().ruler().start(), "Clearing must not mutate a published frame");
                assertEquals(end, measured.tactical().ruler().end());
                assertFalse(fixture.source.takeFrame().panels().los().open());
            });
        }
    }
}
