/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuAssetReloadTest {
    @TempDir
    Path directory;

    @Test
    void gameplayAndPreviewRereadImagesNormalsAndTilesetMappingsWithoutChangingTheBoard() throws Exception {
        File data = Configuration.dataDir();
        File images = Configuration.imagesDir();
        Path tileset = Files.createDirectories(directory.resolve("models/board/tileset"));
        Path normals = Files.createDirectories(directory.resolve("models/board/normals"));
        Files.writeString(tileset.resolve("saxarba.tileset"), "base 0 \"\" \"\" \"ground.png\"\n");
        png(tileset.resolve("ground.png"), 0xffff0000);
        png(normals.resolve("ground.png.png"), 0xff8080ff);
        try (var fixture = GpuBoardFixture.create(Board.createEmptyBoard(6, 6))) {
            Configuration.setImagesDir(images);
            Configuration.setDataDir(directory.toFile());
            FutureTask<Void> task = new FutureTask<>(() -> {
                try (var preview = new GpuMapSource(fixture.game, null, null)) {
                    fixture.source.reloadAssets();
                    Board board = fixture.game.getBoard();
                    long generation = fixture.source.takeFrame().boardGeneration();
                    assertArtwork(fixture.source, 0xff0000ff, 0x8080ffff);
                    assertArtwork(preview, 0xff0000ff, 0x8080ffff);
                    png(tileset.resolve("ground.png"), 0xff0000ff);
                    png(normals.resolve("ground.png.png"), 0xff40c0ff);
                    fixture.source.refresh();
                    preview.refresh();
                    assertArtwork(preview, 0xff0000ff, 0x8080ffff);
                    fixture.source.reloadAssets();
                    preview.reloadAssets();
                    assertArtwork(fixture.source, 0x0000ffff, 0x40c0ffff);
                    assertArtwork(preview, 0x0000ffff, 0x40c0ffff);
                    png(tileset.resolve("replacement.png"), 0xff00ff00);
                    Files.writeString(tileset.resolve("saxarba.tileset"), "base 0 \"\" \"\" \"replacement.png\"\n");
                    fixture.source.reloadAssets();
                    preview.reloadAssets();
                    assertArtwork(fixture.source, 0x00ff00ff, 0x8080ffff);
                    assertArtwork(preview, 0x00ff00ff, 0x8080ffff);
                    assertEquals(generation, fixture.source.takeFrame().boardGeneration());
                    assertSame(board, fixture.game.getBoard());
                    assertEquals(1, fixture.source.takeFrame().scene().units().size());
                }
                return null;
            });
            SwingUtilities.invokeAndWait(task);
            task.get();
            assertThrows(IllegalStateException.class, fixture.source::reloadAssets);
        } finally {
            Configuration.setDataDir(data);
            Configuration.setImagesDir(images);
        }
    }

    private static void assertArtwork(BoardSource source, int color, int normal) {
        var tile = source.takeFrame().scene().tile(new Coords(2, 2));
        assertEquals(color, tile.ground().rgba(36 * 84 + 42));
        assertEquals(normal, tile.normals().rgba(36 * 84 + 42));
    }

    static void png(Path file, int color) throws Exception {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, color); }
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
