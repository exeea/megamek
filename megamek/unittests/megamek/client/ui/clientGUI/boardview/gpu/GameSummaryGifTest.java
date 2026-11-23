/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
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
import org.w3c.dom.Node;

/**
 * A summary's GIF is made from its registry alone: one frame per recorded phase end on a screen the size of the
 * board's thumbnail, each shown for its time over the frames before, the units drawn where the registry puts them.
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
            IIOMetadataNode screen = node(reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"),
                  "LogicalScreenDescriptor");
            assertEquals(String.valueOf(thumbnail.getWidth()), screen.getAttribute("logicalScreenWidth"));
            assertEquals(String.valueOf(thumbnail.getHeight()), screen.getAttribute("logicalScreenHeight"));
            // As a viewer shows it: each frame drawn at its place over the frames before
            BufferedImage shown = new BufferedImage(thumbnail.getWidth(), thumbnail.getHeight(),
                  BufferedImage.TYPE_INT_RGB);
            for (int index = 0; index < frames.size(); index++) {
                Node metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode control = node(metadata, "GraphicControlExtension");
                int delay = (index == frames.size() - 1 ? GameSummaryGif.LAST_MILLIS : GameSummaryGif.FRAME_MILLIS)
                      / 10;
                assertEquals(String.valueOf(delay), control.getAttribute("delayTime"), "frame " + index + "'s time");
                assertEquals("doNotDispose", control.getAttribute("disposalMethod"),
                      "the frame before stays around it");
                IIOMetadataNode place = node(metadata, "ImageDescriptor");
                Graphics2D g = shown.createGraphics();
                g.drawImage(reader.read(index), Integer.parseInt(place.getAttribute("imageLeftPosition")),
                      Integer.parseInt(place.getAttribute("imageTopPosition")), null);
                g.dispose();
                if (index == 1) {
                    // The own Atlas has walked from 0512 to 0510: its new hex shows its symbol, its old hex none
                    assertTrue(near(shown, 5, 10, OWN), "the symbol on its new hex");
                    assertFalse(near(shown, 5, 12, OWN), "no symbol on its old hex");
                    File shot = new File("build/gpu-board-review/game-summary-frame.png");
                    shot.getParentFile().mkdirs();
                    ImageIO.write(shown, "png", shot);
                }
            }
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

    /** Whether a pixel within a fifth of a hex of hex ({@code x}, {@code y})'s centre is near {@code wanted}. */
    private static boolean near(BufferedImage image, int x, int y, int wanted) {
        double unit = LobbyMapThumbnail.unit(16, 17, GameSummaryGif.LONG_SIDE);
        Coords coords = new Coords(x, y);
        int centreX = (int) LobbyMapThumbnail.centreX(coords, unit);
        int centreY = (int) LobbyMapThumbnail.centreY(coords, unit);
        int reach = (int) (unit / 5);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                int rgb = image.getRGB(centreX + dx, centreY + dy);
                int far = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    far = Math.max(far, Math.abs(((rgb >> shift) & 0xFF) - ((wanted >> shift) & 0xFF)));
                }
                if (far <= 20) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IIOMetadataNode node(Node root, String name) {
        return (IIOMetadataNode) ((IIOMetadataNode) root).getElementsByTagName(name).item(0);
    }
}
