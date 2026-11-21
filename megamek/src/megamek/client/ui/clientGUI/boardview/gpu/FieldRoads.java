/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import megamek.common.board.Coords;

/**
 * The height field's road corridor ({@link TerrainField}): ground beside a carriageway eases into it, and lies on it
 * over the ground it reserves, by today's rule (BoardRoadBank#height), which every hex evaluates alike. Owned by its
 * FieldBoard, for that scene revision; a hex's bank is made on the first ask by any chunk worker and only read
 * afterwards.
 */
final class FieldRoads {
    /**
     * The most banks kept, each with its hex's relief (about 25 KB): a block asks for its own hex's and its
     * neighbours', so the workers have a few in use at a time, and one let go is made again on the next ask.
     */
    private static final int KEPT = 256;

    /** A hex's bank and the relief it reads; none where no carriageway reaches its surroundings. */
    private record Hex(BoardRoadBank bank, BoardRelief relief) { }

    private static final Hex NONE = new Hex(null, null);

    private final BoardScene scene;
    private final Map<Coords, Hex> hexes = new ConcurrentHashMap<>();
    /**
     * A hex's containment walls: each panel's place (BoardRoadCut.Panel#place, then the field's height just behind its
     * back's two ends, NaN off the field) and the panels' faces.
     */
    record Walls(List<float[]> places, List<BoardSurface.Face> faces) {
        static final Walls NONE = new Walls(List.of(), List.of());
    }

    /** Every panel today's rule gives a hex (#found), and the hex's own of them (#walls), each on the first ask. */
    private final Map<Coords, List<BoardRoadCut.Panel>> found = new ConcurrentHashMap<>();
    private final Map<Coords, Walls> walls = new ConcurrentHashMap<>();
    /** The hexes whose banks are kept, eldest first, and how many. */
    private final Queue<Coords> kept = new ConcurrentLinkedQueue<>();
    private final AtomicInteger count = new AtomicInteger();

    FieldRoads(BoardScene scene) {
        this.scene = scene;
    }

    private Hex hex(Coords h) {
        Hex hex = hexes.computeIfAbsent(h, at -> {
            BoardScene.Tile tile = scene.tile(at);
            if (tile == null) { return NONE; }
            BoardRelief relief = new BoardRelief(scene, tile, BoardSurface.ramps(scene, tile));
            BoardRoadBank bank = new BoardRoadBank(scene, tile, relief);
            if (!bank.active()) { return NONE; }
            // The relief finds its containment walls on the first ask: here, before another worker reads them.
            relief.walled(0, 0);
            kept.add(at);
            count.incrementAndGet();
            return new Hex(bank, relief);
        });
        while (count.get() > KEPT) {
            Coords eldest = kept.poll();
            if (eldest == null) { break; }
            hexes.remove(eldest);
            count.decrementAndGet();
        }
        return hex;
    }

    private BoardRoadBank bank(Coords h) { return hex(h).bank(); }

    /**
     * How far an edit of the hex at {@code at} reaches, in hexes, through its carriageway's bank: a bank's reach where
     * the hex carries a grade before or after the edit, else none.
     */
    static int reach(BoardScene before, BoardScene after, Coords at) {
        return BoardRoadGrade.of(before, before.tile(at), true) != null || BoardRoadGrade.of(after, after.tile(at), true) != null
              ? BoardRoadBank.REACH : 0;
    }

    /**
     * How far a cliff's rim sample at (x, y), on hex h's edge e at t from its first corner, recedes from the roads
     * beside it (BoardRelief#fieldPush): {x, y}; null where it stays.
     */
    float[] push(Coords h, int e, float t, float x, float y) {
        BoardRelief relief = hex(h).relief();
        return relief == null ? null : relief.fieldPush(e, t, x, y);
    }

    /** Whether a carriageway reserves hex h's ground at (x, y). */
    boolean reserved(Coords h, float x, float y) {
        BoardRoadBank bank = bank(h);
        return bank != null && bank.reserved(x, y);
    }

    /** Hex h's ground at (x, y), which stood at height z, as the roads around leave it. */
    float height(Coords h, float x, float y, float z) {
        BoardRoadBank bank = bank(h);
        return bank == null ? z : bank.height(x, y, z);
    }

    /** The edges hex h's carriageway ramps to, by direction (BoardSurface#ramps). */
    int ramps(Coords h) {
        BoardScene.Tile tile = scene.tile(h);
        return tile == null ? 0 : BoardSurface.ramps(scene, tile);
    }

    /**
     * The surface of a carriageway whose reserved ground holds (x, y): hex h's own, or a neighbour's past its edge; NaN
     * where none does. A plate's road (FieldPlates), which no bank eases.
     */
    float surface(Coords h, float x, float y) {
        for (int d = -1; d < 6; d++) {
            BoardScene.Tile tile = scene.tile(d < 0 ? h : h.translated(d));
            BoardRoadGrade grade = tile == null ? null : BoardRoadGrade.of(scene, tile, true);
            if (grade != null && grade.contains(x, y)) { return grade.height(x, y); }
        }
        return Float.NaN;
    }

    /**
     * The containment walls beside the roads round hex h as today's rule places them (BoardRoadCut#containmentWalls),
     * over a hex's own height ({@code ground}; null for a hex without one). The rule runs a wall a panel on past its
     * end, into the next hex too: where that hex has a panel of its own beside it, the panel is that hex's, so a wall
     * through a hex border is not built twice. The same at every detail.
     */
    Walls walls(Coords h, Function<Coords, BoardRelief.Ground> ground) {
        return walls.computeIfAbsent(h, at -> {
            Walls kept = new Walls(new ArrayList<>(), new ArrayList<>());
            for (BoardRoadCut.Panel panel : found(at, ground)) {
                float[] p = panel.place();
                float x = (p[0] + p[2]) / 2, y = (p[1] + p[3]) / 2;
                if (!BoardGeometry.contains(at, x, y) && claimed(at, x, y, ground)) { continue; }
                float[] place = Arrays.copyOf(p, 11);
                place[9] = behind(at, p[0], p[1], p[4], p[5], ground);
                place[10] = behind(at, p[2], p[3], p[4], p[5], ground);
                kept.places().add(place);
                kept.faces().addAll(panel.faces());
            }
            return kept.places().isEmpty() ? Walls.NONE : kept;
        });
    }

    /**
     * The field's height 10 cm behind a panel's back at (x, y), the panel facing (fx, fy): asked of the hex the point
     * lies in, hex h or a neighbour, so every hex cut along the panel gets the same; NaN where none has ground.
     */
    private static float behind(Coords h, float x, float y, float fx, float fy,
          Function<Coords, BoardRelief.Ground> ground) {
        float back = BoardRelief.metres(.1f), px = x - fx * back, py = y - fy * back;
        for (int d = -1; d < 6; d++) {
            Coords in = d < 0 ? h : h.translated(d);
            BoardRelief.Ground own = BoardGeometry.contains(in, px, py) ? ground.apply(in) : null;
            if (own != null) { return own.height(px, py); }
        }
        return Float.NaN;
    }

    /** Every panel today's rule gives hex h over its own height. */
    private List<BoardRoadCut.Panel> found(Coords h, Function<Coords, BoardRelief.Ground> ground) {
        BoardRelief relief = hex(h).relief();
        if (relief == null) { return List.of(); }
        return found.computeIfAbsent(h, at -> {
            BoardRelief.Ground own = ground.apply(at);
            BoardRoadCut cut = own == null ? null : BoardSurface.containmentCut(scene, scene.tile(at));
            if (cut == null) { return List.of(); }
            List<BoardRoadCut.Panel> panels = new ArrayList<>();
            cut.containmentWalls(own, List.of(), relief, new ArrayList<>(), panels);
            return panels;
        });
    }

    /** Whether the neighbour of hex h that (x, y) lies in has a panel in itself within a panel's width of the point. */
    private boolean claimed(Coords h, float x, float y, Function<Coords, BoardRelief.Ground> ground) {
        float near = BoardRelief.metres(BoardRoadCut.PANEL);
        for (int d = 0; d < 6; d++) {
            Coords next = h.translated(d);
            if (!BoardGeometry.contains(next, x, y)) { continue; }
            for (BoardRoadCut.Panel other : found(next, ground)) {
                float[] q = other.place();
                float qx = (q[0] + q[2]) / 2, qy = (q[1] + q[3]) / 2;
                if (BoardGeometry.contains(next, qx, qy) && Math.hypot(qx - x, qy - y) < near) { return true; }
            }
        }
        return false;
    }

    /** The panels hex h's block was cut along (#walls), to draw: none for a hex whose block is not built. */
    List<BoardSurface.Face> wallFaces(Coords h) {
        return walls.getOrDefault(h, Walls.NONE).faces();
    }

    /** The carriageway's surface at (x, y) as hex h sees it (BoardRoadBank#bed); NaN without a road in reach. */
    float bed(Coords h, float x, float y) {
        BoardRoadBank bank = bank(h);
        return bank == null ? Float.NaN : bank.bed(x, y);
    }
}
