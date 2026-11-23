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
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import com.squareup.gifencoder.HashMultiset;
import com.squareup.gifencoder.MedianCutQuantizer;
import com.squareup.gifencoder.Multiset;
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
 * units were seen on. Its terrain is the board as the game ended.
 * <p>
 * Making it is quick: the terrain is painted once; one palette of at most 256 colours, cut once from the terrain and a
 * few drawn frames, serves every frame, so a frame's pixels only look up their index; frames are drawn and indexed on
 * all cores; and each frame after the first stores only the rectangle in which it differs from the one before, which
 * the frame keeps showing around it.
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
    private static final String GIF_FORMAT = "javax_imageio_gif_image_1.0";
    private static final String GIF_STREAM_FORMAT = "javax_imageio_gif_stream_1.0";

    private GameSummaryGif() { }

    /**
     * The board {@code summary}'s GIF shows, of {@code game}'s boards as they are now: the one the most units were
     * seen on; null when none was seen on a board of the game.
     */
    public static Board board(GameSummary summary, Game game) {
        return board(summary.frames(), game);
    }

    /** Writes {@code summary}'s GIF on {@code board} ({@link #board}) to {@code file}. Any thread. */
    public static void write(GameSummary summary, Board board, File file) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            write(summary.frames(), board, out);
        }
    }

    /** Encodes {@code frames} on {@code board} as an endlessly looping GIF. */
    static void write(List<GameSummary.Frame> frames, Board board, OutputStream out) throws IOException {
        if (frames.isEmpty()) {
            return;
        }
        Painter terrain = new Painter(board);
        int width = terrain.base.getWidth();
        int height = terrain.base.getHeight();
        Palette palette = Palette.of(terrain, frames);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        int workers = Math.max(1, Math.min(frames.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "Game summary GIF");
            thread.setDaemon(true);
            return thread;
        });
        ThreadLocal<Worker> local = ThreadLocal.withInitial(() -> new Worker(terrain, palette));
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(streamMetadata(writer, palette, width, height));
            Deque<Future<byte[]>> pending = new ArrayDeque<>();
            int submitted = 0;
            byte[] previous = null;
            for (int index = 0; index < frames.size(); index++) {
                // A bounded window of frames in flight: every core busy, memory kept to a few frames
                while (submitted < frames.size() && submitted < index + 2 * workers) {
                    int frame = submitted++;
                    pending.add(pool.submit(() -> local.get().indices(frames.get(frame),
                          frame == 0 ? null : frames.get(frame - 1))));
                }
                byte[] indices = pending.removeFirst().get();
                writeFrame(writer, palette, indices, previous, width, height,
                      index == frames.size() - 1 ? LAST_MILLIS : FRAME_MILLIS, index == 0);
                previous = indices;
            }
            writer.endWriteSequence();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while making the game summary GIF", interrupted);
        } catch (ExecutionException failure) {
            throw new IOException("Could not draw a game summary GIF frame", failure.getCause());
        } finally {
            pool.shutdownNow();
            writer.dispose();
        }
    }

    /** The GIF's screen, {@code width} by {@code height}, and its one colour table, {@code palette}'s. */
    private static IIOMetadata streamMetadata(ImageWriter writer, Palette palette, int width, int height)
          throws IOException {
        IIOMetadata metadata = writer.getDefaultStreamMetadata(null);
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_STREAM_FORMAT);
        IIOMetadataNode screen = child(root, "LogicalScreenDescriptor");
        screen.setAttribute("logicalScreenWidth", Integer.toString(width));
        screen.setAttribute("logicalScreenHeight", Integer.toString(height));
        screen.setAttribute("colorResolution", "8");
        screen.setAttribute("pixelAspectRatio", "0");
        IIOMetadataNode table = child(root, "GlobalColorTable");
        while (table.hasChildNodes()) {
            table.removeChild(table.getFirstChild());
        }
        table.setAttribute("sizeOfGlobalColorTable", "256");
        table.setAttribute("backgroundColorIndex", "0");
        table.setAttribute("sortFlag", "FALSE");
        for (int index = 0; index < 256; index++) {
            int rgb = palette.model.getRGB(index);
            IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
            entry.setAttribute("index", Integer.toString(index));
            entry.setAttribute("red", Integer.toString((rgb >> 16) & 0xFF));
            entry.setAttribute("green", Integer.toString((rgb >> 8) & 0xFF));
            entry.setAttribute("blue", Integer.toString(rgb & 0xFF));
            table.appendChild(entry);
        }
        metadata.setFromTree(GIF_STREAM_FORMAT, root);
        return metadata;
    }

    /**
     * Writes one frame: the whole image for the first, else the rectangle in which it differs from {@code previous},
     * over which the image before stays (a frame that changes nothing still holds its time with one pixel).
     */
    private static void writeFrame(ImageWriter writer, Palette palette, byte[] indices, byte[] previous, int width,
          int height, int millis, boolean first) throws IOException {
        int left = 0, top = 0, right = width - 1, bottom = height - 1;
        if (previous != null) {
            left = width;
            top = height;
            right = -1;
            bottom = -1;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                if (Arrays.equals(indices, row, row + width, previous, row, row + width)) {
                    continue;
                }
                top = Math.min(top, y);
                bottom = y;
                for (int x = 0; x < width; x++) {
                    if (indices[row + x] != previous[row + x]) {
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                    }
                }
            }
            if (right < 0) {
                left = top = right = bottom = 0;
            }
        }
        int regionWidth = right - left + 1;
        int regionHeight = bottom - top + 1;
        byte[] region = new byte[regionWidth * regionHeight];
        for (int y = 0; y < regionHeight; y++) {
            System.arraycopy(indices, (top + y) * width + left, region, y * regionWidth, regionWidth);
        }
        BufferedImage image = new BufferedImage(regionWidth, regionHeight, BufferedImage.TYPE_BYTE_INDEXED,
              palette.model);
        System.arraycopy(region, 0, ((DataBufferByte) image.getRaster().getDataBuffer()).getData(), 0,
              region.length);
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_FORMAT);
        // Every frame uses the screen's colour table
        for (int index = root.getLength() - 1; index >= 0; index--) {
            if (root.item(index).getNodeName().equals("LocalColorTable")) {
                root.removeChild(root.item(index));
            }
        }
        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "doNotDispose");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(millis / 10));
        control.setAttribute("transparentColorIndex", "0");
        IIOMetadataNode descriptor = child(root, "ImageDescriptor");
        descriptor.setAttribute("imageLeftPosition", Integer.toString(left));
        descriptor.setAttribute("imageTopPosition", Integer.toString(top));
        descriptor.setAttribute("imageWidth", Integer.toString(regionWidth));
        descriptor.setAttribute("imageHeight", Integer.toString(regionHeight));
        descriptor.setAttribute("interlaceFlag", "FALSE");
        if (first) {
            // NETSCAPE2.0: loop for ever
            IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[] { 1, 0, 0 });
            child(root, "ApplicationExtensions").appendChild(loop);
        }
        metadata.setFromTree(GIF_FORMAT, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), null);
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (int index = 0; index < parent.getLength(); index++) {
            if (parent.item(index).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(index);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    /** The board most of the frames' units stood on, or null when none was seen on a board of {@code game}. */
    static Board board(List<GameSummary.Frame> frames, Game game) {
        return frames.stream().flatMap(frame -> frame.units().stream())
              .collect(Collectors.groupingBy(GameSummary.Unit::board, Collectors.counting())).entrySet().stream()
              .filter(entry -> game.getBoard(entry.getKey()) != null)
              .max(Map.Entry.comparingByValue()).map(entry -> game.getBoard(entry.getKey())).orElse(null);
    }

    /**
     * The GIF's one palette of at most 256 colours, from the terrain and the first, middle and last frames: the
     * colours that cover a hex or more (terrain fills, symbols, lines) exactly, the antialiased blends by median cut;
     * any pixel takes its nearest colour, looked up once per colour.
     */
    static final class Palette {
        /** A colour covering one in so many pixels of the sampled images, or more, keeps its exact value. */
        private static final int FLAT_SHARE = 5000;
        /** At most this many exact colours; the rest of the 256 entries go to the median cut. */
        private static final int FLAT_COLOURS = 64;
        private final int[] rgb;
        final IndexColorModel model;

        private Palette(int[] rgb) {
            this.rgb = rgb;
            // The GIF's colour table has 256 entries: the palette, padded with black; frames use the same table
            byte[] red = new byte[256], green = new byte[256], blue = new byte[256];
            for (int index = 0; index < rgb.length; index++) {
                red[index] = (byte) (rgb[index] >> 16);
                green[index] = (byte) (rgb[index] >> 8);
                blue[index] = (byte) rgb[index];
            }
            model = new IndexColorModel(8, 256, red, green, blue);
        }

        static Palette of(Painter terrain, List<GameSummary.Frame> frames) {
            Map<Integer, Integer> counts = new HashMap<>();
            count(terrain.base, counts);
            BufferedImage sample = terrain.newFrame();
            for (int index : new int[] { 0, frames.size() / 2, frames.size() - 1 }) {
                terrain.paint(frames.get(index), index == 0 ? null : frames.get(index - 1), sample);
                count(sample, counts);
            }
            // The flat colours (whole hexes, symbols, lines) keep their exact colour; median cut shares the rest,
            // the edges antialiasing blends, among the entries left
            int flat = counts.values().stream().mapToInt(Integer::intValue).sum() / FLAT_SHARE;
            int[] exact = counts.entrySet().stream().filter(entry -> entry.getValue() >= flat)
                  .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()).limit(FLAT_COLOURS)
                  .mapToInt(Map.Entry::getKey).toArray();
            Set<Integer> kept = Arrays.stream(exact).boxed().collect(Collectors.toSet());
            Multiset<com.squareup.gifencoder.Color> blends = new HashMultiset<>();
            counts.forEach((colour, count) -> {
                if (!kept.contains(colour)) {
                    blends.add(com.squareup.gifencoder.Color.fromRgbInt(colour), count);
                }
            });
            int[] cut = blends.isEmpty() ? new int[0]
                  : MedianCutQuantizer.INSTANCE.quantize(blends, 256 - exact.length).stream()
                        .mapToInt(com.squareup.gifencoder.Color::getRgbInt).toArray();
            int[] rgb = Arrays.copyOf(exact, exact.length + cut.length);
            System.arraycopy(cut, 0, rgb, exact.length, cut.length);
            return new Palette(rgb);
        }

        private static void count(BufferedImage image, Map<Integer, Integer> counts) {
            for (int pixel : image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth())) {
                counts.merge(pixel & 0xFFFFFF, 1, Integer::sum);
            }
        }

        /** The index of the colour nearest {@code colour} (RGB). */
        int nearest(int colour) {
            int red = (colour >> 16) & 0xFF, green = (colour >> 8) & 0xFF, blue = colour & 0xFF;
            int best = 0;
            int bestDistance = Integer.MAX_VALUE;
            for (int index = 0; index < rgb.length; index++) {
                int dr = red - ((rgb[index] >> 16) & 0xFF);
                int dg = green - ((rgb[index] >> 8) & 0xFF);
                int db = blue - (rgb[index] & 0xFF);
                int distance = dr * dr + dg * dg + db * db;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = index;
                }
            }
            return best;
        }
    }

    /** A drawing thread's own frame image, pixel buffer and colour-to-index cache. */
    private static final class Worker {
        /** Open addressing over RGB values; -1 marks a free slot. */
        private static final int CACHE_SIZE = 1 << 16;
        private final Painter terrain;
        private final Palette palette;
        private final BufferedImage frame;
        private final int[] pixels;
        private final int[] keys = new int[CACHE_SIZE];
        private final byte[] values = new byte[CACHE_SIZE];
        private int filled;

        Worker(Painter terrain, Palette palette) {
            this.terrain = terrain;
            this.palette = palette;
            frame = terrain.newFrame();
            pixels = new int[frame.getWidth() * frame.getHeight()];
            Arrays.fill(keys, -1);
        }

        /** {@code shown}'s pixels as palette indices. */
        byte[] indices(GameSummary.Frame shown, GameSummary.Frame before) {
            terrain.paint(shown, before, frame);
            frame.getRGB(0, 0, frame.getWidth(), frame.getHeight(), pixels, 0, frame.getWidth());
            byte[] indices = new byte[pixels.length];
            int last = -1;
            byte lastIndex = 0;
            for (int pixel = 0; pixel < pixels.length; pixel++) {
                int colour = pixels[pixel] & 0xFFFFFF;
                if (colour != last) {
                    last = colour;
                    lastIndex = index(colour);
                }
                indices[pixel] = lastIndex;
            }
            return indices;
        }

        private byte index(int colour) {
            int slot = (colour * 0x9E3779B1) >>> 16;
            while (keys[slot] != -1) {
                if (keys[slot] == colour) {
                    return values[slot];
                }
                slot = (slot + 1) & (CACHE_SIZE - 1);
            }
            byte index = (byte) palette.nearest(colour);
            // A full table keeps answering, uncached, rather than growing
            if (filled < CACHE_SIZE / 2) {
                keys[slot] = colour;
                values[slot] = index;
                filled++;
            }
            return index;
        }
    }

    /** Draws frames over the board's terrain, painted once; each caller brings the image a frame is drawn into. */
    static final class Painter {
        private final Board board;
        private final double unit;
        final BufferedImage base;
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
            font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, base.getHeight() / 32));
        }

        /** An image a frame can be drawn into, the terrain's size. */
        BufferedImage newFrame() {
            return new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        }

        /** {@code frame} over the terrain, its units' ways from where {@code before} saw them (null for none). */
        BufferedImage paint(GameSummary.Frame frame, GameSummary.Frame before, BufferedImage image) {
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
