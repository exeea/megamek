/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Point;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import megamek.client.ui.tileset.HexTileset;
import megamek.common.board.Coords;
import org.apache.batik.ext.awt.g2d.AbstractGraphics2D;
import org.apache.batik.ext.awt.g2d.GraphicContext;

/**
 * Records the existing tactical painters as vectors instead of allocating their bitmap buffers.
 * Batik supplies the ordinary Graphics2D state/primitive operations; no SVG or board image is produced.
 */
public final class BoardTacticalGraphics extends AbstractGraphics2D {
    private record BorderStyle(double padding, double width) { }

    private final List<BoardTactical.Fill> fills;
    private final List<BoardTactical.Label> labels;
    private final List<BoardTactical.Wall> walls;
    private final List<BoardTactical.Fill> flatWalls;
    private final Graphics2D metrics;
    /** Shared only within this capture, including graphics copies; styles contain no game state. */
    private final Map<BorderStyle, Shape> borderShapes;
    private BoardTactical.Point anchor;
    private BoardTactical.Playback playback = BoardTactical.Playback.LIVE;
    private boolean deploymentZone;

    public BoardTacticalGraphics() {
        super(false);
        gc = new GraphicContext();
        fills = new ArrayList<>();
        labels = new ArrayList<>();
        walls = new ArrayList<>();
        flatWalls = new ArrayList<>();
        borderShapes = new HashMap<>();
        metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        setFont(metrics.getFont());
    }

    private BoardTacticalGraphics(BoardTacticalGraphics parent) {
        super(parent);
        fills = parent.fills;
        labels = parent.labels;
        walls = parent.walls;
        flatWalls = parent.flatWalls;
        metrics = (Graphics2D) parent.metrics.create();
        borderShapes = parent.borderShapes;
        anchor = parent.anchor;
        playback = parent.playback;
        deploymentZone = parent.deploymentZone;
    }

    public BoardTactical snapshot() {
        return new BoardTactical(fills, labels, walls, flatWalls);
    }

    /** Reuse a scratch recorder after publishing immutable commands for one hex. */
    BoardTactical takeSnapshot() {
        BoardTactical captured = snapshot();
        fills.clear();
        labels.clear();
        walls.clear();
        flatWalls.clear();
        return captured;
    }

    /** Append already captured commands in the same unscaled board coordinates and painter order. */
    void append(BoardTactical captured) {
        fills.addAll(captured.fills());
        labels.addAll(captured.labels());
        walls.addAll(captured.walls());
        flatWalls.addAll(captured.flatWalls());
    }

    /** Repeated deployment and highlight borders share the expensive, unscaled Area subtraction. */
    public void fillHexBorder(Point point, float scale, double padding, double lineWidth) {
        fillHexBorder(point, scale, padding, lineWidth, false);
    }

    public void fillHexBorder(Point point, float scale, double padding, double lineWidth, boolean floating) {
        Shape border = borderShapes.computeIfAbsent(new BorderStyle(padding, lineWidth),
              style -> HexDrawUtilities.getHexFullBorderArea(style.width(), style.padding()));
        Shape shape = AffineTransform.getTranslateInstance(point.x, point.y)
              .createTransformedShape(AffineTransform.getScaleInstance(scale, scale).createTransformedShape(border));
        AffineTransform transform = getTransform();
        BoardTactical.HexBorder metadata = null;
        double combinedScale = transform.getScaleX() * scale;
        if (transform.getShearX() == 0 && transform.getShearY() == 0
              && transform.getScaleX() == transform.getScaleY() && transform.getScaleX() > 0
              && combinedScale > 0 && Float.isFinite((float) combinedScale)
              && (floating || padding >= 0 && lineWidth > 0 && padding + lineWidth < HexDrawUtilities.HEX_HGT / 2)) {
            Point2D center = transform.transform(new Point2D.Double(point.x + HexTileset.HEX_W * scale / 2,
                  point.y + HexTileset.HEX_H * scale / 2), null);
            if (Float.isFinite((float) center.getX()) && Float.isFinite((float) center.getY())) {
                metadata = new BoardTactical.HexBorder(new BoardTactical.Point((float) center.getX(), (float) center.getY()),
                      padding, lineWidth, (float) combinedScale, floating, deploymentZone);
            }
        }
        fill(shape, fills, metadata);
    }

    /** Tags only the native capture; classic painters keep their existing graphics state and behavior. */
    public static void draw(Graphics2D graphics, BoardTactical.Playback playback, Consumer<Graphics2D> painter) {
        if (!(graphics instanceof BoardTacticalGraphics)) {
            painter.accept(graphics);
            return;
        }
        BoardTacticalGraphics local = (BoardTacticalGraphics) graphics.create();
        try {
            local.playback = playback;
            painter.accept(local);
        } finally {
            local.dispose();
        }
    }

    /** Only the selected deployer's existing painter supplies zone membership; classic drawing is unchanged. */
    public static void drawDeployment(Graphics2D graphics, Consumer<Graphics2D> painter) {
        draw(graphics, BoardTactical.Playback.HIDE_DURING_MOVEMENT, local -> {
            if (local instanceof BoardTacticalGraphics tactical) { tactical.deploymentZone = true; }
            painter.accept(local);
        });
    }

    /** Local hex coordinates, with a common anchor for camera-facing text. */
    public static Graphics2D at(Graphics2D graph, java.awt.Point location) {
        Graphics2D copy = (Graphics2D) graph.create();
        copy.translate(location.x, location.y);
        if (copy instanceof BoardTacticalGraphics tactical) {
            tactical.anchor = new BoardTactical.Point(location.x + HexTileset.HEX_W / 2f,
                  location.y + HexTileset.HEX_H / 2f);
        }
        return copy;
    }

    @Override
    public Graphics create() {
        return new BoardTacticalGraphics(this);
    }

    @Override
    public void dispose() {
        metrics.dispose();
    }

    private int argb() {
        if (!(getPaint() instanceof Color color) || !(getComposite() instanceof AlphaComposite composite)
              || composite.getRule() != AlphaComposite.SRC_OVER) {
            throw new IllegalStateException("Native tactical shapes require solid SRC_OVER paint");
        }
        return (Math.round(color.getAlpha() * composite.getAlpha()) << 24) | (color.getRGB() & 0xFFFFFF);
    }

    @Override
    public void draw(Shape shape) {
        fill(getStroke().createStrokedShape(shape));
    }

    /** Preserve the painter's path as upright segments, carrying its outline to the top edge. */
    public void wall(Shape shape, Shape footprint, Coords coords, float height, Color outlineColor) {
        BoardTactical.Outline outline = outlineColor == null ? null
              : new BoardTactical.Outline(outlineColor.getRGB(), (BasicStroke) getStroke());
        wall(shape, footprint, coords, new BoardRangeBorder(height, argb(), outline));
    }

    public void wall(Shape shape, Shape footprint, Coords coords, BoardRangeBorder border) {
        fill(footprint, flatWalls, null, border.argb());
        border.append(coords, shape, getTransform(), playback, walls::add);
    }

    @Override
    public void fill(Shape shape) {
        fill(shape, fills);
    }

    private void fill(Shape shape, List<BoardTactical.Fill> destination) {
        fill(shape, destination, null);
    }

    private void fill(Shape shape, List<BoardTactical.Fill> destination, BoardTactical.HexBorder border) {
        fill(shape, destination, border, argb());
    }

    private void fill(Shape shape, List<BoardTactical.Fill> destination, BoardTactical.HexBorder border, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        Shape clip = getClip();
        if (clip != null && !clip.contains(shape.getBounds2D())) {
            // Floating borders still draw the captured, clipped contours on their owner's plane. Only the
            // terrain mask needs a complete regular ring; dropping the floating tag would drape map-edge hexes.
            if (border != null && !border.floating()) { border = null; }
            Area clipped = new Area(shape);
            clipped.intersect(new Area(clip));
            shape = clipped;
        }
        PathIterator path = shape.getPathIterator(getTransform(), 0.25);
        int winding = path.getWindingRule();
        List<BoardTactical.Contour> contours = new ArrayList<>();
        List<BoardTactical.Point> points = new ArrayList<>();
        float[] xy = new float[6];
        while (!path.isDone()) {
            int segment = path.currentSegment(xy);
            if (segment == PathIterator.SEG_MOVETO && !points.isEmpty()) {
                contours.add(new BoardTactical.Contour(points));
                points = new ArrayList<>();
            }
            if (segment != PathIterator.SEG_CLOSE) {
                points.add(new BoardTactical.Point(xy[0], xy[1]));
            }
            path.next();
        }
        if (!points.isEmpty()) {
            contours.add(new BoardTactical.Contour(points));
        }
        if (!contours.isEmpty()) {
            destination.add(new BoardTactical.Fill(contours, winding, color, playback, border));
        }
    }

    @Override
    public void drawString(String text, float x, float y) {
        if (text.isEmpty()) {
            return;
        }
        Point2D point = getTransform().transform(new Point2D.Float(x, y), null);
        BoardTactical.Point origin = anchor == null
              ? new BoardTactical.Point((float) point.getX(), (float) point.getY()) : anchor;
        float scale = (float) Math.hypot(getTransform().getScaleY(), getTransform().getShearX());
        labels.add(new BoardTactical.Label(origin, new BoardTactical.Text(text,
              getFont().deriveFont(getFont().getSize2D() * scale),
              (float) point.getX() - origin.x(), (float) point.getY() - origin.y(), argb()), playback));
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        new TextLayout(iterator, getFontRenderContext()).draw(this, x, y);
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        return metrics.getFontMetrics(font);
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return metrics.getDeviceConfiguration();
    }

    @Override
    public void setXORMode(Color color) {
        throw new UnsupportedOperationException("Tactical vectors do not use XOR");
    }

    @Override
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {
        throw new UnsupportedOperationException("Tactical vectors do not copy raster areas");
    }

    @Override
    public boolean drawImage(Image image, int x, int y, ImageObserver observer) {
        throw new UnsupportedOperationException("Raster artwork belongs in the compatibility layer");
    }

    @Override
    public boolean drawImage(Image image, int x, int y, int width, int height, ImageObserver observer) {
        throw new UnsupportedOperationException("Raster artwork belongs in the compatibility layer");
    }

    @Override
    public void drawRenderedImage(RenderedImage image, AffineTransform transform) {
        throw new UnsupportedOperationException("Raster artwork belongs in the compatibility layer");
    }

    @Override
    public void drawRenderableImage(RenderableImage image, AffineTransform transform) {
        throw new UnsupportedOperationException("Raster artwork belongs in the compatibility layer");
    }
}
