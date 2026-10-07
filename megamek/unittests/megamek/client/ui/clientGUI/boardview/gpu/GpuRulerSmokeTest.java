/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuRulerSmokeTest {
    @Test
    void sharedRulerIsStraightAndRemainsVisibleThroughTheBlockingHillInBothViews() throws Exception {
        Board map = Board.createEmptyBoard(10, 10);
        Coords from = new Coords(5, 2), to = new Coords(5, 8), hill = new Coords(5, 5);
        map.getHex(hill).setLevel(3);
        AtomicReference<BoardScene> blocked = new AtomicReference<>(), clear = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(map)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(from);
                var ruler = fixture.source.los().model();
                ruler.measure(from, to, fixture.entity.getId(), 2);
                fixture.source.refresh();
                blocked.set(fixture.source.takeFrame().scene());
                assertEquals(hill, blocked.get().tactical().ruler().blockedAt());
                ruler.height(true, 5); ruler.height(false, 5);
                fixture.source.refresh();
                clear.set(fixture.source.takeFrame().scene());
            });
        }
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(blocked.get());
            GpuTactical ruler = new GpuTactical();
            try {
                for (boolean tactical : new boolean[] { false, true }) {
                    board.view(tactical);
                    board.camera.setIsometric(!tactical);
                    for (boolean obstructed : new boolean[] { true, false }) {
                        ruler.update(obstructed ? blocked.get() : clear.get());
                        board.draw(ruler::renderLabels);
                        Pixmap shot = hud.captureBackBuffer("ruler-" + (obstructed ? "blocked-" : "clear-")
                              + (tactical ? "tactical" : "3d"));
                        try {
                            assertTrue(count(shot, GpuTactical.RULER_CLEAR, 0, 0, shot.getWidth(), shot.getHeight()) > 100);
                            int red = count(shot, GpuTactical.RULER_BLOCKED, 0, 0, shot.getWidth(), shot.getHeight());
                            assertTrue(obstructed ? red > 100 : red < 10, "Red pixels: " + red);
                            if (obstructed) {
                                var covered = board.screen(BoardGeometry.center(hill, 2));
                                assertTrue(count(shot, GpuTactical.RULER_BLOCKED,
                                      Math.round(covered.x) - 12, Math.round(covered.y) - 12, 25, 25) > 0,
                                      "The red ray remains visible inside the blocking hill");
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        } finally {
                            shot.dispose();
                        }
                    }
                }
            } finally {
                ruler.dispose();
                board.dispose();
            }
        });
    }

    private static int count(Pixmap image, int rgba, int x, int y, int width, int height) {
        int count = 0;
        for (int row = Math.max(0, y); row < Math.min(image.getHeight(), y + height); row++) {
            for (int column = Math.max(0, x); column < Math.min(image.getWidth(), x + width); column++) {
                int actual = image.getPixel(column, row);
                if (Math.abs((actual >>> 24) - (rgba >>> 24)) <= 3
                      && Math.abs(((actual >>> 16) & 255) - ((rgba >>> 16) & 255)) <= 3
                      && Math.abs(((actual >>> 8) & 255) - ((rgba >>> 8) & 255)) <= 3) { count++; }
            }
        }
        return count;
    }
}
