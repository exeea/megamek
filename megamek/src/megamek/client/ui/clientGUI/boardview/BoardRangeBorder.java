/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import megamek.common.board.Coords;

/**
 * Shared presentation for upright range boundaries. Callers supply their existing contour and style; the native
 * renderer joins its panels across elevations and animates the supplied outline. No range or deployment rules live here.
 */
public record BoardRangeBorder(float height, int argb, BoardTactical.Outline outline) {
    private record Joint(int x, int y, BoardRangeBorder border, BoardTactical.Playback playback) {
        Joint(BoardTactical.Wall wall, BoardTactical.Point point) {
            // Path rotations can leave tiny rounding differences at the same unscaled board-pixel endpoint.
            this(Math.round(point.x() * 100), Math.round(point.y() * 100), wall.border(), wall.playback());
        }
    }

    /** Height is in elevation levels; opacity multiplies the supplied color's alpha. */
    public BoardRangeBorder(float height, int argb, float opacity, BoardTactical.Outline outline) {
        this(height, (argb & 0xFFFFFF) | (Math.round((argb >>> 24) * opacity) << 24), outline);
    }

    /**
     * Join only panels that actually meet on this boundary. The owner is the interior hex: an adjacent exterior
     * cliff must never raise the border. Retaining these tiny neighbor lists in each command also invalidates cached
     * geometry when zone membership changes, even when a surviving panel's path and terrain stay the same.
     */
    public static List<BoardTactical.Wall> join(List<BoardTactical.Wall> walls) {
        Map<Joint, Set<Coords>> joints = new HashMap<>();
        for (var wall : walls) {
            joints.computeIfAbsent(new Joint(wall, wall.a()), ignored -> new HashSet<>()).add(wall.coords());
            joints.computeIfAbsent(new Joint(wall, wall.b()), ignored -> new HashSet<>()).add(wall.coords());
        }
        List<BoardTactical.Wall> result = new ArrayList<>(walls.size());
        for (var wall : walls) {
            result.add(new BoardTactical.Wall(wall.coords(), wall.a(), wall.b(), wall.border(), wall.outlineDistance(),
                  wall.playback(), neighbors(joints.get(new Joint(wall, wall.a())), wall.coords()),
                  neighbors(joints.get(new Joint(wall, wall.b())), wall.coords())));
        }
        return result;
    }

    private static List<Coords> neighbors(Set<Coords> owners, Coords owner) {
        return owners.stream().filter(coords -> !coords.equals(owner))
              .sorted(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY)).toList();
    }

    /** Preserve path order and dash distance, including closed paths and separate subpaths. */
    public void append(Coords coords, Shape shape, AffineTransform transform, BoardTactical.Playback playback,
          Consumer<BoardTactical.Wall> destination) {
        PathIterator path = shape.getPathIterator(transform, .25);
        BoardTactical.Point start = null, previous = null;
        float distance = 0;
        float[] xy = new float[6];
        while (!path.isDone()) {
            int segment = path.currentSegment(xy);
            BoardTactical.Point point = segment == PathIterator.SEG_CLOSE ? start : new BoardTactical.Point(xy[0], xy[1]);
            if (segment == PathIterator.SEG_MOVETO) {
                start = point;
                distance = 0;
            } else if (!point.equals(previous)) {
                destination.accept(new BoardTactical.Wall(coords, previous, point, this, distance, playback));
                distance += (float) Math.hypot(point.x() - previous.x(), point.y() - previous.y());
            }
            previous = point;
            path.next();
        }
    }
}
