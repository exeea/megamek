/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Temporary review: MegaMek's envelope strips on their hex planes beside steps, from two sides and from above. */
@Tag("on-demand")
class TmpPlaneSeeThroughSmokeTest {
    @Test
    void envelopeStripsBesideSteps() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Board board = new Board();
        board.load(new File("data/boards/Map Set 2/16x17 Desert Hills.board"));
        // A hex in the middle of the board with a higher neighbour, so that the walk band's border crosses steps.
        Coords center = null;
        for (int x = 4; x < board.getWidth() - 4 && center == null; x++) {
            for (int y = 4; y < board.getHeight() - 4 && center == null; y++) {
                for (int direction = 0; direction < 6; direction++) {
                    Coords next = new Coords(x, y).translated(direction);
                    if (board.getHex(next).getLevel() > board.getHex(x, y).getLevel()) {
                        center = new Coords(x, y);
                        break;
                    }
                }
            }
        }
        Coords middle = center;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            List<MovementEnvelopeSprite> strips = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                for (int x = 0; x < board.getWidth(); x++) {
                    for (int y = 0; y < board.getHeight(); y++) {
                        Coords coords = new Coords(x, y);
                        int band = band(middle, coords);
                        if (band < 0) {
                            continue;
                        }
                        int edges = 0;
                        for (int direction = 0; direction < 6; direction++) {
                            if (band(middle, coords.translated(direction)) != band) {
                                edges |= 1 << direction;
                            }
                        }
                        strips.add(new MovementEnvelopeSprite(fixture.view, band == 0 ? Color.CYAN : Color.YELLOW,
                              coords, edges));
                    }
                }
                fixture.view.addSprites(strips);
                fixture.source.refresh();
            });
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;

                @Override
                public void render() {
                    try {
                        super.render();
                        if (frames() == 0) { return; }
                        tick++;
                        if (tick == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                            boardCamera.center(BoardGeometry.center(middle, board.getHex(middle).getLevel()));
                            boardCamera.zoom(.35f);
                        } else if (tick == 8) {
                            GpuBoardTestUi.capture(new File(output, "plane-see-through-isometric.png"));
                            boardCamera.orbit(150, 0);
                        } else if (tick == 14) {
                            GpuBoardTestUi.capture(new File(output, "plane-see-through-rotated.png"));
                            boardCamera.setIsometric(false);
                            boardCamera.center(BoardGeometry.center(middle, board.getHex(middle).getLevel()));
                        } else if (tick == 20) {
                            GpuBoardTestUi.capture(new File(output, "plane-see-through-top.png"));
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    /** The walk band within two hexes, the run band out to four; -1 beyond. */
    private static int band(Coords middle, Coords coords) {
        int distance = middle.distance(coords);
        return distance <= 2 ? 0 : distance <= 4 ? 1 : -1;
    }
}
