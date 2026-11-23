/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.squareup.gifencoder.GifEncoder;
import com.squareup.gifencoder.ImageOptions;
import com.squareup.gifencoder.MedianCutQuantizer;
import com.squareup.gifencoder.NearestColorDitherer;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.dialogs.minimap.MinimapUnitSymbols;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;

/**
 * The animated GIF of a {@link GameSummary}, made only when the player asks for it at the end of a game. A frame is
 * the board as every board thumbnail paints it ({@link LobbyMapThumbnail#paintTerrain}, the in-game minimap's terrain
 * colours) on the minimap's background, with the frame's units in the minimap symbols the summary always showed
 * ({@link MinimapUnitSymbols}, in their owners' colours, with facing arrows when the minimap shows them), the way each
 * unit came from where the frame before saw it, the phase's attacks as lines from their units to the hexes they
 * attacked (physical attacks thicker), and the round and phase in the top left corner. One board: the one the most
 * units were seen on. Its terrain is the board as the game ended. Frames are drawn and encoded one at a time.
 */
public final class GameSummaryGif {
    /** A frame's longer side in pixels, as the lobby's board thumbnails. */
    static final int LONG_SIDE = LobbyMapThumbnail.LONG_SIDE;
    static final int FRAME_MILLIS = 400;
    /** The last frame stays this long before the GIF starts over. */
    static final int LAST_MILLIS = 2000;
    /**
     * Symbol units per pixel of hex width: the old summary's minimap frames (zoom 4) drew symbols at 12 % on hexes 16
     * pixels wide.
     */
    private static final double SYMBOL_SCALE = .12 / 16;
    private static final Color BACKGROUND = new Color(GpuMinimap.BACKGROUND.r, GpuMinimap.BACKGROUND.g,
          GpuMinimap.BACKGROUND.b);
    private static final Color OUTLINE = new Color(0, 0, 0, 190);
    /** A unit's way from the frame before and an attack's line, as shares of a hex's width. */
    private static final float TRAIL_WIDTH = .06f;
    private static final float WEAPON_WIDTH = .06f;
    private static final float PHYSICAL_WIDTH = .14f;

    private GameSummaryGif() { }

    /**
     * Writes {@code summary}'s GIF to {@code file} with the boards of {@code game}. False, writing nothing, when it has
     * no frame on a board the game knows.
     */
    public static boolean write(GameSummary summary, Game game, File file) throws IOException {
        List<GameSummary.Frame> frames = summary.frames();
        Board board = board(frames, game);
        if (board == null) {
            return false;
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            write(frames, board, out);
        }
        return true;
    }

    /** Encodes {@code frames} on {@code board} as an endlessly looping GIF. */
    static void write(List<GameSummary.Frame> frames, Board board, OutputStream out) throws IOException {
        Painter painter = new Painter(board);
        int width = painter.base.getWidth();
        int height = painter.base.getHeight();
        GifEncoder encoder = new GifEncoder(out, width, height, 0);
        int[] pixels = null;
        for (int index = 0; index < frames.size(); index++) {
            BufferedImage image = painter.paint(frames.get(index), index == 0 ? null : frames.get(index - 1));
            pixels = image.getRGB(0, 0, width, height, pixels, 0, width);
            // The terrain's flat colours stay flat: no dithering
            encoder.addImage(pixels, width, new ImageOptions().setColorQuantizer(MedianCutQuantizer.INSTANCE)
                  .setDitherer(NearestColorDitherer.INSTANCE)
                  .setDelay(index == frames.size() - 1 ? LAST_MILLIS : FRAME_MILLIS, TimeUnit.MILLISECONDS));
        }
        encoder.finishEncoding();
    }

    /** The board most of the frames' units stood on, or null when none was seen on a board of {@code game}. */
    static Board board(List<GameSummary.Frame> frames, Game game) {
        return frames.stream().flatMap(frame -> frame.units().stream())
              .collect(Collectors.groupingBy(GameSummary.Unit::board, Collectors.counting())).entrySet().stream()
              .filter(entry -> game.getBoard(entry.getKey()) != null)
              .max(Map.Entry.comparingByValue()).map(entry -> game.getBoard(entry.getKey())).orElse(null);
    }

    /** Draws frames over the board's terrain, baked once; every frame reuses one image. */
    static final class Painter {
        private final Board board;
        private final double unit;
        final BufferedImage base;
        private final BufferedImage image;
        private final Font font;
        private final boolean facings = GUIPreferences.getInstance().getDrawFacingArrowsOnMiniMap();

        Painter(Board board) {
            this.board = board;
            unit = LobbyMapThumbnail.unit(board.getWidth(), board.getHeight(), LONG_SIDE);
            base = LobbyMapThumbnail.image(board.getWidth(), board.getHeight(), unit);
            Graphics2D g = base.createGraphics();
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, base.getWidth(), base.getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            LobbyMapThumbnail.paintTerrain(g, board, unit);
            g.dispose();
            image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, base.getHeight() / 32));
        }

        /** {@code frame} over the terrain, its units' ways from where {@code before} saw them (null for none). */
        BufferedImage paint(GameSummary.Frame frame, GameSummary.Frame before) {
            Graphics2D g = image.createGraphics();
            try {
                g.drawImage(base, 0, 0, null);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Map<Integer, GameSummary.Unit> units = onBoard(frame);
                Map<Integer, GameSummary.Unit> earlier = before == null ? Map.of() : onBoard(before);
                for (GameSummary.Unit shown : units.values()) {
                    GameSummary.Unit was = earlier.get(shown.id());
                    if (was != null && (was.x() != shown.x() || was.y() != shown.y())) {
                        line(g, was.x(), was.y(), shown.x(), shown.y(), new Color(shown.colour()), TRAIL_WIDTH, 150);
                    }
                }
                for (GameSummary.Attack attack : frame.attacks()) {
                    GameSummary.Unit from = units.get(attack.attacker());
                    if (from != null && board.contains(attack.x(), attack.y())) {
                        line(g, from.x(), from.y(), attack.x(), attack.y(), new Color(from.colour()),
                              attack.physical() ? PHYSICAL_WIDTH : WEAPON_WIDTH, 255);
                    }
                }
                // Wrecks first, so a unit on a wreck's hex stays in sight; several units in a hex stack up
                Map<Coords, Integer> stacked = new HashMap<>();
                units.values().stream().filter(GameSummary.Unit::destroyed).forEach(shown -> unit(g, shown, stacked));
                units.values().stream().filter(shown -> !shown.destroyed()).forEach(shown -> unit(g, shown, stacked));
                caption(g, Messages.getString("GameSummaryGif.caption", frame.round(), frame.phase().localizedName()));
            } finally {
                g.dispose();
            }
            return image;
        }

        private Map<Integer, GameSummary.Unit> onBoard(GameSummary.Frame frame) {
            int boardId = board.getBoardId();
            return frame.units().stream().filter(shown -> shown.board() == boardId)
                  .collect(Collectors.toMap(GameSummary.Unit::id, Function.identity(), (first, second) -> first));
        }

        /** A line between two hexes' centres in {@code colour}, {@code share} of a hex wide, edged in black. */
        private void line(Graphics2D g, int fromX, int fromY, int toX, int toY, Color colour, float share, int alpha) {
            Line2D line = new Line2D.Double(x(fromX, fromY), y(fromX, fromY), x(toX, toY), y(toX, toY));
            float width = (float) Math.max(1.5, unit * share);
            g.setStroke(new BasicStroke(width + 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0, 0, 0, alpha * OUTLINE.getAlpha() / 255));
            g.draw(line);
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha));
            g.draw(line);
        }

        /** The unit's minimap symbol on its hex, as the minimap draws it. */
        private void unit(Graphics2D g, GameSummary.Unit shown, Map<Coords, Integer> stacked) {
            AffineTransform transform = g.getTransform();
            Stroke stroke = g.getStroke();
            g.translate(x(shown.x(), shown.y()), y(shown.x(), shown.y()));
            g.scale(unit * SYMBOL_SCALE, unit * SYMBOL_SCALE);
            MinimapUnitSymbols.stack(g, stacked.merge(new Coords(shown.x(), shown.y()), 1, Integer::sum));
            Color colour = new Color(shown.colour());
            MinimapUnitSymbols.paint(g, shown.symbol(), colour, shown.moved(), shown.destroyed());
            if (facings && !shown.destroyed()) {
                MinimapUnitSymbols.paintFacing(g, shown.symbol(), colour, shown.facing());
            }
            g.setTransform(transform);
            g.setStroke(stroke);
        }

        private void caption(Graphics2D g, String text) {
            g.setFont(font);
            int x = font.getSize() / 2;
            int y = font.getSize() * 3 / 2;
            g.setColor(OUTLINE);
            g.drawString(text, x + 1, y + 1);
            g.setColor(Color.WHITE);
            g.drawString(text, x, y);
        }

        private double x(int column, int row) {
            return LobbyMapThumbnail.centreX(new Coords(column, row), unit);
        }

        private double y(int column, int row) {
            return LobbyMapThumbnail.centreY(new Coords(column, row), unit);
        }
    }
}
