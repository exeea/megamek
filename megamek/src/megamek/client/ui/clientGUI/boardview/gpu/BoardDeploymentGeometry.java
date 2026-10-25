/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
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
    /** A band's side and whether its right (clockwise) and left neighbouring sides are exposed too. */
    private record Edge(int direction, boolean right, boolean left, double padding, double width) { }

    private BoardDeploymentGeometry() { }

    /** The authoring view shares deployment's animated perimeter, without tinting its editable surfaces. */
    static BoardTactical editorOutlines(BoardScene scene) {
        var tactical = scene.tactical();
        var perimeter = perimeter(scene, zoneFills(scene));
        return new BoardTactical(tactical.fills().stream().filter(fill -> !isZone(fill)).toList(),
              tactical.labels(), walls(scene, perimeter), perimeter, tactical.ruler());
    }

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
                  argb, fill.playback(), fill.border(), fill.planeAnchor()));
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
                boolean right = (edges & (1 << ((direction + 1) % 6))) != 0, left = (edges & (1 << ((direction + 5) % 6))) != 0;
                BoardTactical.Contour segment = segments.computeIfAbsent(
                      new Edge(direction, right, left, border.padding(), FLAT_BAND_WIDTH), BoardDeploymentGeometry::segment);
                List<BoardTactical.Point> points = new ArrayList<>(4);
                for (BoardTactical.Point point : segment.points()) {
                    points.add(new BoardTactical.Point(border.anchor().x() + point.x() * border.scale(),
                          border.anchor().y() + point.y() * border.scale()));
                }
                contours.add(new BoardTactical.Contour(points));
            }
            var flat = new BoardTactical.HexBorder(border.anchor(), border.padding(), FLAT_BAND_WIDTH, border.scale());
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

    /**
     * The band along an exposed side, from the hex's centre: its outer line {@code padding} in from the side, its inner
     * line {@code width} further. Each end meets the next exposed side's band in a mitre, or runs out to the outline
     * where the perimeter continues through the same-colour neighbour.
     */
    private static BoardTactical.Contour segment(Edge edge) {
        int side = Math.floorMod(1 - edge.direction(), 6);
        double outer = edge.padding(), inner = edge.padding() + edge.width();
        return new BoardTactical.Contour(List.of(
              BoardTacticalGeometry.insetCorner(side, outer, side + 1, edge.left() ? outer : 0),
              BoardTacticalGeometry.insetCorner(side, outer, side - 1, edge.right() ? outer : 0),
              BoardTacticalGeometry.insetCorner(side, inner, side - 1, edge.right() ? inner : 0),
              BoardTacticalGeometry.insetCorner(side, inner, side + 1, edge.left() ? inner : 0)));
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
