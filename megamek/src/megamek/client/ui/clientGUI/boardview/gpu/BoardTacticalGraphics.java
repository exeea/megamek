/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
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

import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import org.apache.batik.ext.awt.g2d.AbstractGraphics2D;
import org.apache.batik.ext.awt.g2d.GraphicContext;

/**
 * Records Graphics2D drawing in tactical units (board units at hex scale 1, y down) as the 3D board's tactical
 * commands. Batik supplies the ordinary Graphics2D state/primitive operations; no SVG or image is produced.
 */
final class BoardTacticalGraphics extends AbstractGraphics2D {
    private record BorderStyle(double padding, double width) { }

    private final List<BoardTactical.Fill> fills;
    private final List<BoardTactical.Label> labels;
    private final List<BoardTactical.Wall> walls;
    private final List<BoardTactical.Fill> flatWalls;
    private final Graphics2D metrics;
    /** Shared only within this capture, including graphics copies; styles contain no game state. */
    private final Map<BorderStyle, Shape> borderShapes;
    private BoardTactical.Point anchor;
    private BoardTactical.Point planeAnchor;
    private BoardTactical.Playback playback = BoardTactical.Playback.LIVE;
    private boolean deploymentZone;

    BoardTacticalGraphics() {
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
        planeAnchor = parent.planeAnchor;
        playback = parent.playback;
        deploymentZone = parent.deploymentZone;
    }

    BoardTactical snapshot() {
        return new BoardTactical(fills, labels, walls, flatWalls);
    }

    /** Append already captured commands in the same tactical units and drawing order. */
    void append(BoardTactical captured) {
        fills.addAll(captured.fills());
        labels.addAll(captured.labels());
        walls.addAll(captured.walls());
        flatWalls.addAll(captured.flatWalls());
    }

    /**
     * The band between the hex outline moved {@code padding} and {@code padding + lineWidth} towards the centre, about
     * this graphics' origin. A band only moved and scaled carries its hex for the analytic mask; repeated styles share
     * one Area.
     */
    void fillHexBorder(double padding, double lineWidth) {
        Shape border = borderShapes.computeIfAbsent(new BorderStyle(padding, lineWidth),
              style -> BoardTacticalGeometry.ring(style.padding(), style.width()));
        AffineTransform transform = getTransform();
        BoardTactical.HexBorder metadata = null;
        if (transform.getShearX() == 0 && transform.getShearY() == 0
              && transform.getScaleX() == transform.getScaleY() && transform.getScaleX() > 0
              && padding >= 0 && lineWidth > 0 && padding + lineWidth < BoardGeometry.TILE_HEIGHT / 2) {
            var centre = new BoardTactical.Point((float) transform.getTranslateX(), (float) transform.getTranslateY());
            metadata = new BoardTactical.HexBorder(centre, padding, lineWidth, (float) transform.getScaleX(),
                  deploymentZone);
        }
        fill(border, fills, metadata, argb(), planeAnchor);
    }

    /** Tag what {@code painter} records with {@code playback}. */
    static void draw(BoardTacticalGraphics graphics, BoardTactical.Playback playback,
          Consumer<BoardTacticalGraphics> painter) {
        BoardTacticalGraphics local = (BoardTacticalGraphics) graphics.create();
        try {
            local.playback = playback;
            painter.accept(local);
        } finally {
            local.dispose();
        }
    }

    /** The selected deployer's legal hexes: their borders form the deployment zone. */
    static void drawDeployment(BoardTacticalGraphics graphics, Consumer<BoardTacticalGraphics> painter) {
        draw(graphics, BoardTactical.Playback.HIDE_DURING_MOVEMENT, local -> {
            local.deploymentZone = true;
            painter.accept(local);
        });
    }

    /** Units about {@code coords}' centre, which also anchors camera-facing text. */
    static BoardTacticalGraphics at(BoardTacticalGraphics graph, Coords coords) {
        BoardTactical.Point centre = BoardTacticalGeometry.centre(coords);
        BoardTacticalGraphics copy = (BoardTacticalGraphics) graph.create();
        copy.translate(centre.x(), centre.y());
        AffineTransform transform = copy.getTransform();
        copy.anchor = new BoardTactical.Point((float) transform.getTranslateX(), (float) transform.getTranslateY());
        return copy;
    }

    /** {@link #at}, with every shape on the hex's flat annotation plane in 3D. */
    static BoardTacticalGraphics onHexPlane(BoardTacticalGraphics graph, Coords coords) {
        BoardTacticalGraphics copy = at(graph, coords);
        copy.planeAnchor = copy.anchor;
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

    /** {@code shape} as upright segments of {@code border} on {@code coords}, over the flat {@code footprint}. */
    void wall(Shape shape, Shape footprint, Coords coords, BoardRangeBorder border) {
        fill(footprint, flatWalls, null, border.argb(), null);
        border.append(coords, shape, getTransform(), playback, walls::add);
    }

    @Override
    public void fill(Shape shape) {
        fill(shape, fills, null, argb(), planeAnchor);
    }

    private void fill(Shape shape, List<BoardTactical.Fill> destination, BoardTactical.HexBorder border, int color,
          BoardTactical.Point owner) {
        if ((color >>> 24) == 0) {
            return;
        }
        Shape clip = getClip();
        if (clip != null && !clip.contains(shape.getBounds2D())) {
            // A clipped ring cannot use the analytic mask. Its owner plane survives independently of that hint.
            if (owner == null) { border = null; }
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
            destination.add(new BoardTactical.Fill(contours, winding, color, playback, border, owner));
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
