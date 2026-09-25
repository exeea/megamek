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
    private final Map<Coords, List<Channel>> channels = new HashMap<>();
    private final Map<Coords, Integer> masks = new HashMap<>();

    private record Channel(List<Span> spans) {
        float field(float x, float y, BoardRelief.Tuning tuning, float wander) {
            float result = Float.NEGATIVE_INFINITY;
            for (Span span : spans) { result = Math.max(result, span.field(x, y, tuning, wander)); }
            return result;
        }
    }

    private record Span(float ax, float ay, float bx, float by, float depthA, float depthB, float start, float end,
          boolean broad, boolean detailedA, boolean detailedB) {
        float field(float x, float y, BoardRelief.Tuning tuning, float wander) {
            float dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
            float t = length == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / length, 0, 1);
            float s = start + (end - start) * t;
            float deep = BoardRelief.lerp(Math.clamp(depthA, 0, 1), Math.clamp(depthB, 0, 1), BoardRelief.smooth(s));
            // Width belongs to the whole channel, not its crossings of hex edges. Carry the room needed by a
            // deep-water unit along the stream instead of making a fixed pool at every centre. Depth-zero reaches
            // have no unit-sized minimum; blend gently where a shallow reach meets deeper water.
            float minimum = BoardRelief.lerp(2, 12, deep);
            float full = Math.max(minimum, tuning.shorePool() * (.55f + .45f * deep) + tuning.shoreSpread());
            float radius = BoardRelief.lerp(minimum, full, (tuning.riverWidth() - .05f) / .95f);
            radius = Math.max(minimum, radius + wander * tuning.riverWidth());
            if (broad) { radius = Math.max(radius, 12); }
            // Special artwork (including bridges) keeps its original water footprint. The approach widens smoothly,
            // and both kinds of hex still ask the same world field at their shared opening.
            float natural = (detailedA ? 1 - s : 0) + (detailedB ? s : 0);
            radius = Math.max(radius, 72 * (1 - natural));
            return radius * BoardGeometry.HEX_SCALE - (float) Math.hypot(x - ax - t * dx, y - ay - t * dy);
        }
    }

    BoardRiver(BoardScene scene, BoardRelief.Tuning tuning) {
        this.scene = scene;
        this.tuning = tuning;
    }

    /** A coordinate-based query: adjoining meshes see the same paths, tangent directions and widths. */
    float field(float x, float y) {
        float result = Float.NEGATIVE_INFINITY;
        float cell = tuning.wanderCell() * BoardGeometry.HEX_SCALE;
        float wander = .3f * tuning.shoreWander() * BoardRelief.gradient(x / cell + 3.1f, y / cell - 1.7f);
        int column = Math.round((x - BoardGeometry.WIDTH / 2) / (.75f * BoardGeometry.WIDTH));
        // Round the union of whole branches near a confluence. Blending individual spline samples would inflate
        // every channel, and blending away from junctions would add a regular bulge at every hex centre.
        float round = 0;
        for (int cx = column - 2; cx <= column + 2; cx++) {
            int row = Math.round((-y - BoardGeometry.HEIGHT / 2 - (cx & 1) * BoardGeometry.HEIGHT / 2)
                  / BoardGeometry.HEIGHT);
            for (int cy = row - 2; cy <= row + 2; cy++) {
                Coords coords = new Coords(cx, cy);
                int mask = masks.computeIfAbsent(coords, this::mask);
                if (mask < 0 || Integer.bitCount(mask) < 3) { continue; }
                // A solid arc of water neighbours is a lake edge, not several river branches meeting.
                int starts = mask & ~((mask << 1 | mask >> 5) & 63);
                if (Integer.bitCount(starts) < 2) { continue; }
                float distance = (float) Math.hypot(x - BoardGeometry.centerX(coords), y - BoardGeometry.centerY(coords));
                round = Math.max(round, BoardRelief.smooth(1 - distance / (.8f * BoardGeometry.WIDTH)));
            }
        }
        round *= 2 * tuning.shoreBlend() * BoardGeometry.HEX_SCALE * Math.min(1, .3f + tuning.riverWidth());
        for (int cx = column - 2; cx <= column + 2; cx++) {
            int row = Math.round((-y - BoardGeometry.HEIGHT / 2 - (cx & 1) * BoardGeometry.HEIGHT / 2)
                  / BoardGeometry.HEIGHT);
            for (int cy = row - 2; cy <= row + 2; cy++) {
                Coords coords = new Coords(cx, cy);
                int mask = masks.computeIfAbsent(coords, this::mask);
                if (mask < 0) { continue; }
                for (Channel channel : channels.computeIfAbsent(coords, this::channels)) {
                    float value = channel.field(x, y, tuning, wander);
                    result = round > 0 ? -BoardRelief.smoothMin(-result, -value, round) : Math.max(result, value);
                }
                // Three mutually adjacent water hexes contain open water, not a mesh of separate thin streams.
                for (int d = 0; d < 6; d++) {
                    if ((mask & 1 << d) == 0 || (mask & 1 << (d + 1) % 6) == 0) { continue; }
                    Coords b = coords.translated(d), c = coords.translated((d + 1) % 6);
                    float ax = BoardGeometry.centerX(coords), ay = BoardGeometry.centerY(coords);
                    float bx = BoardGeometry.centerX(b), by = BoardGeometry.centerY(b);
                    float cxp = BoardGeometry.centerX(c), cyp = BoardGeometry.centerY(c);
                    float ab = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
                    float bc = (cxp - bx) * (y - by) - (cyp - by) * (x - bx);
                    float ca = (ax - cxp) * (y - cyp) - (ay - cyp) * (x - cxp);
                    if (ab >= 0 && bc >= 0 && ca >= 0 || ab <= 0 && bc <= 0 && ca <= 0) {
                        return Float.POSITIVE_INFINITY;
                    }
                }
            }
        }
        return result;
    }

    private List<Channel> channels(Coords coords) {
        List<Channel> result = new ArrayList<>();
        BoardScene.Tile tile = tile(coords);
        float ax = BoardGeometry.centerX(coords), ay = BoardGeometry.centerY(coords);
        result.add(new Channel(List.of(new Span(ax, ay, ax, ay, tile.waterDepth(), tile.waterDepth(), 0, 0, false,
              tile.detailedGround(), tile.detailedGround()))));
        for (int d = 0; d < 6; d++) {
            Coords other = coords.translated(d);
            BoardScene.Tile next = tile(other);
            if (!water(next)) { continue; }
            // Each link has one owner and one ordering, including when queried by the other hex.
            if (other.getX() < coords.getX() || other.getX() == coords.getX() && other.getY() < coords.getY()) { continue; }
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
                spans.add(new Span(px, py, qx, qy, tile.waterDepth(), next.waterDepth(), (i - 1f) / STEPS, t, broad,
                      tile.detailedGround(), next.detailedGround()));
                px = qx;
                py = qy;
            }
            result.add(new Channel(List.copyOf(spans)));
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

}
