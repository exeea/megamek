/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.HexDrawUtilities;
import megamek.common.board.Coords;

/** Presentation of the selected deployer's captured legal hexes; no deployment rules are repeated here. */
final class BoardDeploymentGeometry {
    /** Same half-level curtain and scrolling dash presentation as the visual/sensor range. */
    private static final float WALL_HEIGHT = .5f;
    private static final float WALL_OPACITY = .65f;
    /** A broad inward band in top view, with the animated outline retained on its outside edge. */
    private static final float FLAT_BAND_WIDTH = 10;
    private static final BoardTactical.Outline OUTLINE = new BoardTactical.Outline(Color.WHITE.getRGB(),
          new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] { 5, 3 }, 0));
    private record Edge(int direction, int cut, double padding, double width) { }

    private BoardDeploymentGeometry() { }

    static boolean isZone(BoardTactical.Fill fill) {
        return fill.border() != null && fill.border().zone();
    }

    /** Keep the painter's final visible color, including translucent warnings over yellow or cyan. */
    static Map<Coords, BoardTactical.Fill> zoneFills(BoardScene scene) {
        Map<Coords, BoardTactical.Fill> result = new LinkedHashMap<>();
        for (BoardTactical.Fill fill : scene.tactical().fills()) {
            if (!isZone(fill)) { continue; }
            Coords coords = BoardTacticalGeometry.borderCoords(scene, fill.border());
            if (coords == null) { continue; }
            BoardTactical.Fill previous = result.get(coords);
            int argb = previous == null ? fill.argb() : over(fill.argb(), previous.argb());
            result.put(coords, argb == fill.argb() ? fill : new BoardTactical.Fill(fill.contours(), fill.winding(),
                  argb, fill.playback(), fill.border()));
        }
        return result;
    }

    /**
     * Only exposed sides become geometry. Returned commands use the shared range wall's flat presentation;
     * removing their zone tag prevents a second perimeter/tint conversion. Different displayed colors stay separate.
     */
    static List<BoardTactical.Fill> perimeter(BoardScene scene, Map<Coords, BoardTactical.Fill> zones) {
        List<BoardTactical.Fill> result = new ArrayList<>();
        Map<Edge, BoardTactical.Contour> segments = new HashMap<>();
        for (var entry : zones.entrySet()) {
            Coords coords = entry.getKey();
            if (scene.tile(coords) == null) { continue; }
            BoardTactical.Fill fill = entry.getValue();
            BoardTactical.HexBorder border = fill.border();
            int edges = 0;
            for (int direction = 0; direction < 6; direction++) {
                BoardTactical.Fill neighbor = zones.get(coords.translated(direction));
                if (neighbor == null || neighbor.argb() != fill.argb()) { edges |= 1 << direction; }
            }
            if (edges == 0) { continue; }
            List<BoardTactical.Contour> contours = new ArrayList<>();
            for (int direction = 0; direction < 6; direction++) {
                if ((edges & (1 << direction)) == 0) { continue; }
                // The movement-envelope painter uses the same cuts: meet the next exposed edge inside the hex,
                // or extend to the shared side when the perimeter continues through its same-color neighbor.
                int cut = (edges & (1 << ((direction + 1) % 6))) == 0
                      ? HexDrawUtilities.CUT_RIGHT_BORDER : HexDrawUtilities.CUT_RIGHT_INSIDE;
                cut |= (edges & (1 << ((direction + 5) % 6))) == 0
                      ? HexDrawUtilities.CUT_LEFT_BORDER : HexDrawUtilities.CUT_LEFT_INSIDE;
                BoardTactical.Contour segment = segments.computeIfAbsent(
                      new Edge(direction, cut, border.padding(), FLAT_BAND_WIDTH), BoardDeploymentGeometry::segment);
                List<BoardTactical.Point> points = new ArrayList<>(4);
                for (BoardTactical.Point point : segment.points()) {
                    points.add(new BoardTactical.Point(border.anchor().x()
                          + (point.x() - BoardGeometry.TILE_WIDTH / 2) * border.scale(), border.anchor().y()
                          + (point.y() - BoardGeometry.TILE_HEIGHT / 2) * border.scale()));
                }
                contours.add(new BoardTactical.Contour(points));
            }
            var flat = new BoardTactical.HexBorder(border.anchor(), border.padding(), FLAT_BAND_WIDTH, border.scale(), false);
            result.add(new BoardTactical.Fill(contours, Path2D.WIND_NON_ZERO, fill.argb(), fill.playback(), flat));
        }
        return result;
    }

    /** Reuse the range-wall renderer and its animated outline; only the exposed perimeter becomes a curtain. */
    static List<BoardTactical.Wall> walls(BoardScene scene, List<BoardTactical.Fill> perimeter) {
        List<BoardTactical.Wall> result = new ArrayList<>();
        for (BoardTactical.Fill fill : perimeter) {
            Coords coords = BoardTacticalGeometry.borderCoords(scene, fill.border());
            var border = new BoardRangeBorder(WALL_HEIGHT, fill.argb(), WALL_OPACITY, OUTLINE);
            Path2D path = new Path2D.Float();
            for (BoardTactical.Contour edge : fill.contours()) {
                path.moveTo(edge.points().get(0).x(), edge.points().get(0).y());
                path.lineTo(edge.points().get(1).x(), edge.points().get(1).y());
            }
            border.append(coords, path, null, fill.playback(), result::add);
        }
        return result;
    }

    private static BoardTactical.Contour segment(Edge edge) {
        List<BoardTactical.Point> outer = points(HexDrawUtilities.getHexBorderLine(edge.direction(), edge.cut(), edge.padding()));
        List<BoardTactical.Point> inner = points(HexDrawUtilities.getHexBorderLine(edge.direction(), edge.cut(),
              edge.padding() + edge.width()));
        return new BoardTactical.Contour(List.of(outer.get(0), outer.get(1), inner.get(1), inner.get(0)));
    }

    private static List<BoardTactical.Point> points(Shape line) {
        List<BoardTactical.Point> result = new ArrayList<>(2);
        PathIterator path = line.getPathIterator(null);
        float[] point = new float[6];
        while (!path.isDone()) {
            if (path.currentSegment(point) != PathIterator.SEG_CLOSE) {
                result.add(new BoardTactical.Point(point[0], point[1]));
            }
            path.next();
        }
        return result;
    }

    /** Straight-alpha SRC_OVER in captured 8-bit colors, without allocating an image for each hex. */
    private static int over(int source, int destination) {
        int sourceAlpha = source >>> 24, destinationAlpha = destination >>> 24;
        if (sourceAlpha == 255 || destinationAlpha == 0) { return source; }
        if (sourceAlpha == 0) { return destination; }
        int alpha = sourceAlpha * 255 + destinationAlpha * (255 - sourceAlpha);
        int result = ((alpha + 127) / 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            int channel = ((source >>> shift) & 255) * sourceAlpha * 255
                  + ((destination >>> shift) & 255) * destinationAlpha * (255 - sourceAlpha);
            result |= ((channel + alpha / 2) / alpha) << shift;
        }
        return result;
    }
}
