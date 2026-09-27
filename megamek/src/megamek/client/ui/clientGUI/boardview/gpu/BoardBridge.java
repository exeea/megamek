/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.board.Coords;

/** Bridge asset selection and offline outlines, derived from the same paths as ground roads. */
public final class BoardBridge {
    private BoardBridge() { }

    static String asset(int exits) {
        return exits == 9 ? "bridge" : "bridges/bridge-exits-" + String.format(java.util.Locale.ROOT, "%02d", exits);
    }

    /** Export with the default tile dimensions; runtime uniformly scales the resulting GLBs. */
    static Area footprint(int exits, float margin) {
        Coords at = new Coords(0, 0);
        Area shape = BoardRoad.clearance(at, exits).footprint(margin);
        Path2D.Float hex = new Path2D.Float();
        var center = BoardGeometry.center(at, 0);
        for (int i = 0; i < 6; i++) {
            var p = BoardGeometry.corner(at, 0, i).sub(center).scl(1 / BoardGeometry.hexScale());
            if (i == 0) { hex.moveTo(p.x, p.y); }
            else { hex.lineTo(p.x, p.y); }
        }
        hex.closePath();
        shape.intersect(new Area(hex));
        // The road union has many nearly collinear vertices. Bake a centimetre-scale approximation before
        // subtracting the deck from the slab, so their shared rail boundaries remain identical.
        Path2D.Float polygon = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        for (var loop : outlines(shape)) {
            int farthest = 1;
            for (int i = 2; i < loop.size(); i++) {
                if (distance(loop.get(0), loop.get(i)) > distance(loop.get(0), loop.get(farthest))) { farthest = i; }
            }
            List<float[]> closed = new ArrayList<>(loop);
            closed.add(loop.getFirst());
            List<float[]> simple = new ArrayList<>();
            simplify(closed, 0, farthest, simple);
            simplify(closed, farthest, closed.size() - 1, simple);
            for (int i = 0; i < simple.size(); i++) {
                var p = simple.get(i);
                if (i == 0) { polygon.moveTo(p[0], p[1]); }
                else { polygon.lineTo(p[0], p[1]); }
            }
            polygon.closePath();
        }
        return new Area(polygon);
    }

    private static double distance(float[] a, float[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }

    private static void simplify(List<float[]> points, int first, int last, List<float[]> result) {
        float[] a = points.get(first), b = points.get(last);
        double maximum = .01; // Squared distance: 0.1 tile units, under four centimetres.
        int split = -1;
        for (int i = first + 1; i < last; i++) {
            var p = points.get(i);
            double error = Line2D.ptSegDistSq(a[0], a[1], b[0], b[1], p[0], p[1]);
            if (error > maximum) { maximum = error; split = i; }
        }
        if (split < 0) { result.add(a); }
        else {
            simplify(points, first, split, result);
            simplify(points, split, last, result);
        }
    }

    private static List<List<float[]>> outlines(Area shape) {
        List<List<float[]>> result = new ArrayList<>();
        List<float[]> loop = null;
        float[] point = new float[6];
        var path = shape.getPathIterator(null, .15);
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) {
                loop = new ArrayList<>();
                result.add(loop);
            }
            if (type != PathIterator.SEG_CLOSE) { loop.add(new float[] { point[0], point[1] }); }
            path.next();
        }
        return result;
    }

    /** Writes the authoring input consumed by mm-data/tools/build_bridge_assets.py; requires no graphics context. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) { throw new IllegalArgumentException("Expected output bridge-shapes.json path"); }
        BoardGeometry.tune(BoardGeometry.DEFAULTS);
        List<Map<String, Object>> shapes = new ArrayList<>();
        for (int exits = 0; exits < 64; exits++) {
            Area deck = footprint(exits, 0), slab = footprint(exits, BoardRoad.SHOULDER);
            if (BoardRoad.roundabout(exits)) {
                // A bridge's island has a solid concrete foundation, rather than a hole through its platform.
                float r = BoardRoad.ROUNDABOUT_RADIUS;
                slab.add(new Area(new Ellipse2D.Float(-r, -r, 2 * r, 2 * r)));
            }
            Area rails = new Area(slab);
            rails.subtract(deck);
            shapes.add(Map.of("asset", asset(exits), "exits", exits, "deck", outlines(deck),
                  "slab", outlines(slab), "rails", outlines(rails)));
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), shapes);
    }
}
