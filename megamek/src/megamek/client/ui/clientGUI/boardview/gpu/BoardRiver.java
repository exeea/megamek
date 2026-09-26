/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;

/** Shared stream paths through connected water hexes. Only visual geometry; the board still supplies water depth. */
final class BoardRiver {
    private static final int STEPS = 12;
    private final BoardScene scene;
    private final BoardRelief.Tuning tuning;
    private final Map<Coords, Sample> samples = new HashMap<>();
    private final Map<Coords, Integer> masks = new HashMap<>();
    private int column = Integer.MIN_VALUE, evenRow, oddRow;
    private final List<Sample> window = new ArrayList<>(25);

    private record Sample(float x, float y, boolean junction, List<Channel> channels, List<float[]> lakes) { }

    /** Endpoint properties belong to a whole channel, not each of its twelve line segments. */
    private final class Channel {
        private final List<Span> spans;
        private final float depthA, depthB;
        private final boolean broadA, broadB, detailedA, detailedB, uniform;

        Channel(List<Span> spans, BoardScene.Tile a, BoardScene.Tile b, boolean broadA, boolean broadB) {
            this.spans = spans;
            depthA = Math.clamp(a.waterDepth(), 0, 1);
            depthB = Math.clamp(b.waterDepth(), 0, 1);
            this.broadA = broadA;
            this.broadB = broadB;
            detailedA = a.detailedGround();
            detailedB = b.detailedGround();
            uniform = depthA == depthB && broadA == broadB && detailedA == detailedB;
        }

        float field(float x, float y, float wander) {
            float result = Float.NEGATIVE_INFINITY;
            float commonRadius = uniform ? radius(0, wander) : 0;
            double nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                float t = span.length() == 0 ? 0 : Math.clamp(((x - span.ax()) * span.dx()
                      + (y - span.ay()) * span.dy()) / span.length(), 0, 1);
                double distance = lengthSquared(x - span.ax() - t * span.dx(), y - span.ay() - t * span.dy());
                if (uniform) {
                    nearest = Math.min(nearest, distance);
                } else {
                    float radius = radius(span.start() + span.range() * t, wander);
                    result = Math.max(result, radius - (float) Math.sqrt(distance));
                }
            }
            // Equal radii need only the nearest segment; taking its square root once gives the same float union.
            return uniform ? commonRadius - (float) Math.sqrt(nearest) : result;
        }

        private float radius(float s, float wander) {
            float smooth = BoardRelief.smooth(s);
            float deep = BoardRelief.lerp(depthA, depthB, smooth);
            // Width belongs to the whole channel, not its crossings of hex edges. Carry the room needed by a
            // deep-water unit along the stream instead of making a fixed pool at every centre. Depth-zero reaches
            // have no unit-sized minimum; blend gently where a shallow reach meets deeper water.
            float minimum = BoardRelief.lerp(2, 12, deep);
            float full = Math.max(minimum, tuning.shorePool() * (.55f + .45f * deep) + tuning.shoreSpread());
            float radius = BoardRelief.lerp(minimum, full, (tuning.riverWidth() - .05f) / .95f);
            radius = Math.max(minimum, radius + wander * tuning.riverWidth());
            // Open water leaves room for the shared shore field to shape the lake's bank. Widen the approaching
            // stream gradually too: a large round cap at the lake's first centre would make a sharp inlet corner.
            float open = BoardRelief.lerp(broadA ? 1 : 0, broadB ? 1 : 0, smooth);
            radius = BoardRelief.lerp(radius, Math.max(radius, BoardGeometry.WIDTH / (2 * BoardGeometry.HEX_SCALE)), open);
            // Special artwork (including bridges) keeps its original water footprint. The approach widens smoothly,
            // and both kinds of hex still ask the same world field at their shared opening.
            float natural = (detailedA ? 1 - s : 0) + (detailedB ? s : 0);
            radius = Math.max(radius, 72 * (1 - natural));
            return radius * BoardGeometry.HEX_SCALE;
        }
    }

    private record Span(float ax, float ay, float dx, float dy, float length, float start, float range) {
        Span(float ax, float ay, float bx, float by, float start, float end) {
            this(ax, ay, bx - ax, by - ay, (bx - ax) * (bx - ax) + (by - ay) * (by - ay), start, end - start);
        }
    }

    BoardRiver(BoardScene scene, BoardRelief.Tuning tuning) {
        this.scene = scene;
        this.tuning = tuning;
    }

    /** A coordinate-based query: adjoining meshes see the same paths, tangent directions and widths. */
    float field(float x, float y) {
        return field(x, y, Float.POSITIVE_INFINITY);
    }

    /** The caller intersects this union with its shore field, so larger values cannot change the shore. */
    float field(float x, float y, float limit) {
        float result = Float.NEGATIVE_INFINITY;
        float cell = tuning.wanderCell() * BoardGeometry.HEX_SCALE;
        float wander = .3f * tuning.shoreWander() * BoardRelief.gradient(x / cell + 3.1f, y / cell - 1.7f);
        window(x, y);
        // Round the union of whole branches near a confluence. Blending individual spline samples would inflate
        // every channel, and blending away from junctions would add a regular bulge at every hex centre.
        float round = 0;
        for (Sample sample : window) {
            if (!sample.junction()) { continue; }
            float distance = length(x - sample.x(), y - sample.y());
            round = Math.max(round, BoardRelief.smooth(1 - distance / (.8f * BoardGeometry.WIDTH)));
        }
        round *= 2 * tuning.shoreBlend() * BoardGeometry.HEX_SCALE * Math.min(1, .3f + tuning.riverWidth());
        for (Sample sample : window) {
            List<Channel> channels = sample.channels();
            for (int i = 0; i < channels.size(); i++) {
                Channel channel = channels.get(i);
                float value = channel.field(x, y, wander);
                result = round > 0 ? -BoardRelief.smoothMin(-result, -value, round) : Math.max(result, value);
                if (result >= limit) { return result; }
            }
            // Three mutually adjacent water hexes contain open water, not a mesh of separate thin streams.
            for (float[] lake : sample.lakes()) {
                float ax = sample.x(), ay = sample.y(), bx = lake[0], by = lake[1], cx = lake[2], cy = lake[3];
                float ab = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
                float bc = (cx - bx) * (y - by) - (cy - by) * (x - bx);
                float ca = (ax - cx) * (y - cy) - (ay - cy) * (x - cx);
                if (ab >= 0 && bc >= 0 && ca >= 0 || ab <= 0 && bc <= 0 && ca <= 0) {
                    return Float.POSITIVE_INFINITY;
                }
            }
        }
        return result;
    }

    /** Successive shore samples normally share a search window; resolve its coordinates only when it changes. */
    private void window(float x, float y) {
        int nextColumn = Math.round((x - BoardGeometry.WIDTH / 2) / (.75f * BoardGeometry.WIDTH));
        int nextEven = Math.round((-y - BoardGeometry.HEIGHT / 2) / BoardGeometry.HEIGHT);
        int nextOdd = Math.round((-y - BoardGeometry.HEIGHT / 2 - BoardGeometry.HEIGHT / 2) / BoardGeometry.HEIGHT);
        if (column == nextColumn && evenRow == nextEven && oddRow == nextOdd) { return; }
        column = nextColumn;
        evenRow = nextEven;
        oddRow = nextOdd;
        window.clear();
        for (int cx = column - 2; cx <= column + 2; cx++) {
            int row = (cx & 1) == 0 ? evenRow : oddRow;
            for (int cy = row - 2; cy <= row + 2; cy++) {
                Coords coords = new Coords(cx, cy);
                int mask = masks.computeIfAbsent(coords, this::mask);
                if (mask >= 0) { window.add(samples.computeIfAbsent(coords, key -> sample(key, mask))); }
            }
        }
    }

    private Sample sample(Coords coords, int mask) {
        int starts = mask & ~((mask << 1 | mask >> 5) & 63);
        List<float[]> lakes = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            if ((mask & 1 << d) == 0 || (mask & 1 << (d + 1) % 6) == 0) { continue; }
            Coords b = coords.translated(d), c = coords.translated((d + 1) % 6);
            lakes.add(new float[] { BoardGeometry.centerX(b), BoardGeometry.centerY(b),
                  BoardGeometry.centerX(c), BoardGeometry.centerY(c) });
        }
        return new Sample(BoardGeometry.centerX(coords), BoardGeometry.centerY(coords),
              Integer.bitCount(mask) >= 3 && Integer.bitCount(starts) >= 2, channels(coords), lakes);
    }

    /** Board coordinates are finite floats; double products cannot overflow or underflow like float products. */
    private static float length(float x, float y) {
        return (float) Math.sqrt(lengthSquared(x, y));
    }

    private static double lengthSquared(float x, float y) { return (double) x * x + (double) y * y; }

    private List<Channel> channels(Coords coords) {
        List<Channel> result = new ArrayList<>();
        BoardScene.Tile tile = tile(coords);
        float ax = BoardGeometry.centerX(coords), ay = BoardGeometry.centerY(coords);
        boolean broadA = broad(coords);
        result.add(new Channel(List.of(new Span(ax, ay, ax, ay, 0, 0)), tile, tile, broadA, broadA));
        for (int d = 0; d < 6; d++) {
            Coords other = coords.translated(d);
            BoardScene.Tile next = tile(other);
            if (!water(next)) { continue; }
            // Each link has one owner and one ordering, including when queried by the other hex.
            if (other.getX() < coords.getX() || other.getX() == coords.getX() && other.getY() < coords.getY()) { continue; }
            boolean broadB = broad(other);
            Coords before = continuation(coords, other), after = continuation(other, coords);
            float bx = BoardGeometry.centerX(other), by = BoardGeometry.centerY(other);
            float tx = before == null ? bx - ax : (bx - BoardGeometry.centerX(before)) / 2;
            float ty = before == null ? by - ay : (by - BoardGeometry.centerY(before)) / 2;
            float ux = after == null ? bx - ax : (BoardGeometry.centerX(after) - ax) / 2;
            float uy = after == null ? by - ay : (BoardGeometry.centerY(after) - ay) / 2;
            // Only very thin, depth-zero channels need their bend limited to remain visible from the centre used
            // by the bed's radial mesh. Deep reaches have room to follow the neighbouring stream's full curve.
            float bend = Math.max(Math.min(tile.waterDepth(), next.waterDepth()) > 0 ? 1 : 0,
                  BoardRelief.smooth((tuning.riverWidth() - .05f) / .45f));
            tx = BoardRelief.lerp(bx - ax, tx, bend);
            ty = BoardRelief.lerp(by - ay, ty, bend);
            ux = BoardRelief.lerp(bx - ax, ux, bend);
            uy = BoardRelief.lerp(by - ay, uy, bend);
            boolean broad = water(tile(coords.translated((d + 1) % 6))) || water(tile(coords.translated((d + 5) % 6)));
            // Rotate the shared direction at each centre instead of moving the centre itself. The stream then
            // meanders through its hexes, and adjoining links use the same turn at their shared centre.
            float angle = broad ? 0 : meander(ax, ay);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle), vx = tx;
            tx = vx * cos - ty * sin;
            ty = vx * sin + ty * cos;
            angle = broad ? 0 : meander(bx, by);
            cos = (float) Math.cos(angle);
            sin = (float) Math.sin(angle);
            vx = ux;
            ux = vx * cos - uy * sin;
            uy = vx * sin + uy * cos;
            List<Span> spans = new ArrayList<>(STEPS);
            float px = ax, py = ay;
            for (int i = 1; i <= STEPS; i++) {
                float t = i / (float) STEPS, t2 = t * t, t3 = t2 * t;
                float qx = (2 * t3 - 3 * t2 + 1) * ax + (t3 - 2 * t2 + t) * tx
                      + (-2 * t3 + 3 * t2) * bx + (t3 - t2) * ux;
                float qy = (2 * t3 - 3 * t2 + 1) * ay + (t3 - 2 * t2 + t) * ty
                      + (-2 * t3 + 3 * t2) * by + (t3 - t2) * uy;
                spans.add(new Span(px, py, qx, qy, (i - 1f) / STEPS, t));
                px = qx;
                py = qy;
            }
            result.add(new Channel(List.copyOf(spans), tile, next, broadA, broadB));
        }
        return List.copyOf(result);
    }

    /** Slow changes in direction, more visible in narrow streams; the world coordinates give both ends of a
     * shared centre the same turn. Bound the turn so a thin channel stays visible from its centre. */
    private float meander(float x, float y) {
        float cell = .5f * tuning.wanderCell() * BoardGeometry.HEX_SCALE;
        float angle = 1.25f * (1 - .5f * tuning.riverWidth()) * tuning.shoreWander() / 8
              * BoardRelief.gradient(x / cell + 2.3f, y / cell - 2.3f);
        float limit = .3f + .4f * tuning.riverWidth();
        return Math.clamp(angle, -limit, limit);
    }

    /** The preceding/following hex of a stream; junctions keep their separate branches. */
    private Coords continuation(Coords here, Coords from) {
        Coords result = null;
        for (int d = 0; d < 6; d++) {
            Coords next = here.translated(d);
            if (next.equals(from) || !water(tile(next))) { continue; }
            if (result != null) { return null; }
            result = next;
        }
        return result;
    }

    private static boolean water(BoardScene.Tile tile) {
        return tile != null && tile.liquid().present() && !tile.liquid().molten();
    }

    private BoardScene.Tile tile(Coords coords) {
        return scene.tile(new Coords(Math.clamp(coords.getX(), 0, scene.width() - 1),
              Math.clamp(coords.getY(), 0, scene.height() - 1)));
    }

    private int mask(Coords coords) {
        if (!water(tile(coords))) { return -1; }
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if (water(tile(coords.translated(d)))) { result |= 1 << d; }
        }
        return result;
    }

    /** A triangle of mutually adjacent water centres contains open water, rather than separate stream branches. */
    private boolean broad(Coords coords) {
        int mask = masks.computeIfAbsent(coords, this::mask);
        return mask >= 0 && (mask & (mask << 1 | mask >> 5) & 63) != 0;
    }

}
