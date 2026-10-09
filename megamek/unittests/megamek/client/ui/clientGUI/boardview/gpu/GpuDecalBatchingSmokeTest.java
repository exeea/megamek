/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Pixmap;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Decals of one texture share a draw, and nearby chunks share paged draws, without changing the painter order: a
 * decal of another texture between two of the same texture keeps the later one on top, in the paged (isometric) view.
 */
@Tag("on-demand")
class GpuDecalBatchingSmokeTest {
    @Test void sharedDrawsKeepThePainterOrder() throws Exception {
        Coords owner = new Coords(7, 7);
        var setup = new FutureTask<GpuMapSource>(() -> {
            Board board = Board.createEmptyBoard(16, 16);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "lunar")); }
            }
            board.getHex(owner).setDecorations(List.of(decal("lower-red", "red-cross", 2), decal("green", "capellan-confederation", 3),
                  decal("upper-red", "red-cross", 4)));
            // The same texture far away, in other chunks of the same page.
            board.getHex(new Coords(12, 12)).setDecorations(List.of(decal("far-red", "red-cross", 2)));
            board.getHex(new Coords(14, 1)).setDecorations(List.of(decal("far-green", "capellan-confederation", 2)));
            Game game = new Game();
            game.setBoard(board);
            return new GpuMapSource(game, null, null);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review");
        assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 90_000_000_000L;

                @Override public void create() {
                    super.create();
                    boardCamera.setIsometric(true);
                    boardCamera.center(BoardGeometry.center(owner, 0));
                }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "The board must load");
                        if (GpuBoardTestUi.loading(this) || frames() < 90) { return; }
                        var point = boardCamera.camera.project(BoardGeometry.center(owner, 0));
                        Pixmap pixels = Pixmap.createFromFrameBuffer((int) point.x - 2, (int) point.y - 2, 5, 5);
                        long red = 0, green = 0;
                        for (int x = 0; x < 5; x++) {
                            for (int y = 0; y < 5; y++) {
                                int value = pixels.getPixel(x, y);
                                red += value >>> 24;
                                green += value >>> 16 & 255;
                            }
                        }
                        pixels.dispose();
                        GpuBoardTestUi.capture(new File(output, "decal-batching-isometric.png"));
                        assertTrue(red > green * 1.5, "The last red decal stays above the green one: R=" + red + " G=" + green);
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                    if (failure.get() != null || frames() >= 90) { Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Batched decal order", failure.get()); }
    }

    private static BoardDecoration decal(String id, String emblem, int order) {
        return new BoardDecoration(id, "decal", "decal/emblems/" + emblem, null, 0, 0, 0, false, 3 * .4,
              BoardDecoration.Placement.ground(), order, false);
    }
}
