/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import megamek.client.ui.dialogs.minimap.MinimapUnitSymbols;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/**
 * A summary's GIF is made from its registry alone: one frame per recorded phase end, the size of the board's
 * thumbnail, each frame shown for its time, the units drawn where the registry puts them.
 */
class GameSummaryGifTest {
    private static final int OWN = 0x2E6BD8;
    private static final int ENEMY = 0xD8402E;

    @Test
    void aRegistryMakesOneFramePerPhaseEnd() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        MinimapUnitSymbols.Symbol mek = MinimapUnitSymbols.symbol(atlas());
        List<GameSummary.Frame> frames = List.of(
              frame(1, GamePhase.MOVEMENT, List.of(unit(1, 5, 12, OWN, mek, false), unit(2, 5, 3, ENEMY, mek, false)),
                    List.of()),
              frame(1, GamePhase.FIRING, List.of(unit(1, 5, 10, OWN, mek, false), unit(2, 5, 5, ENEMY, mek, false)),
                    List.of(new GameSummary.Attack(1, 5, 5, false), new GameSummary.Attack(2, 5, 10, false))),
              frame(1, GamePhase.PHYSICAL, List.of(unit(1, 5, 10, OWN, mek, false), unit(2, 5, 5, ENEMY, mek, true)),
                    List.of()));
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        GameSummaryGif.write(frames, board, gif);

        BufferedImage thumbnail = LobbyMapThumbnail.image(board.getWidth(), board.getHeight(),
              LobbyMapThumbnail.unit(board.getWidth(), board.getHeight(), GameSummaryGif.LONG_SIDE));
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif.toByteArray()))) {
            reader.setInput(in);
            assertEquals(frames.size(), reader.getNumImages(true), "one frame per phase end");
            for (int index = 0; index < frames.size(); index++) {
                BufferedImage frame = reader.read(index);
                assertEquals(thumbnail.getWidth(), frame.getWidth());
                assertEquals(thumbnail.getHeight(), frame.getHeight());
                IIOMetadataNode control = (IIOMetadataNode) ((IIOMetadataNode) reader.getImageMetadata(index)
                      .getAsTree("javax_imageio_gif_image_1.0")).getElementsByTagName("GraphicControlExtension")
                      .item(0);
                int delay = (index == frames.size() - 1 ? GameSummaryGif.LAST_MILLIS : GameSummaryGif.FRAME_MILLIS)
                      / 10;
                assertEquals(String.valueOf(delay), control.getAttribute("delayTime"), "frame " + index + "'s time");
            }
            // The own Atlas has walked from 0512 to 0510: its hex shows its symbol in its colour, its old hex none
            GameSummaryGif.Painter painter = new GameSummaryGif.Painter(board);
            BufferedImage moved = painter.paint(frames.get(1), frames.getFirst());
            assertEquals(OWN, colourNear(moved, 5, 10, OWN), "the symbol on its new hex");
            assertNotEquals(OWN, colourNear(moved, 5, 12, OWN), "no symbol on its old hex");
            File shot = new File("build/gpu-board-review/game-summary-frame.png");
            shot.getParentFile().mkdirs();
            ImageIO.write(moved, "png", shot);
        } finally {
            reader.dispose();
        }
    }

    private static Entity atlas() throws Exception {
        return new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
    }

    private static GameSummary.Frame frame(int round, GamePhase phase, List<GameSummary.Unit> units,
          List<GameSummary.Attack> attacks) {
        return new GameSummary.Frame(round, phase, units, attacks);
    }

    private static GameSummary.Unit unit(int id, int x, int y, int colour, MinimapUnitSymbols.Symbol symbol,
          boolean destroyed) {
        return new GameSummary.Unit(id, 0, x, y, 2, symbol, colour, true, destroyed);
    }

    /** {@code wanted} when a pixel within a fifth of a hex of hex ({@code x}, {@code y})'s centre has it, else -1. */
    private static int colourNear(BufferedImage image, int x, int y, int wanted) {
        double unit = LobbyMapThumbnail.unit(16, 17, GameSummaryGif.LONG_SIDE);
        Coords coords = new Coords(x, y);
        int centreX = (int) LobbyMapThumbnail.centreX(coords, unit);
        int centreY = (int) LobbyMapThumbnail.centreY(coords, unit);
        int reach = (int) (unit / 5);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                if ((image.getRGB(centreX + dx, centreY + dy) & 0xFFFFFF) == wanted) {
                    return wanted;
                }
            }
        }
        return -1;
    }
}
