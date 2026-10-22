/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Roads cut into cliffs: ledges between a cliff above and a cliff below, a coast road climbing a sea cliff, bridges
 * between mesas (flush and ramped) and roads spiralling up mesas, each with and without a railway.
 */
final class CliffRoadFixture {
    static final int WIDTH = 42, HEIGHT = 32;
    static final String NAME = "Cliff Roads";
    static final Coords LEDGE = new Coords(4, 1), LEDGE_RAIL = new Coords(10, 1), COAST = new Coords(20, 1),
          BRIDGES = new Coords(31, 2), SPIRAL = new Coords(7, 23), SPIRAL_RAIL = new Coords(21, 23),
          HAIRPIN = new Coords(35, 23);

    private CliffRoadFixture() { }

    static Board create() {
        Board board = RoadGradeFixture.blank(false, WIDTH, HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) { board.getHex(x, y).setLevel(0); }
        }
        board.setDocumentName(NAME);
        board.addTag("Example");
        board.setDescription("Roads cut into cliffs. North-west: two ledges between a level 8 cliff and level 0 ground, "
              + "a road and a road with a railway, level at 4, then descending along the cliff's foot. North: a coast "
              + "road climbing a sea cliff between a level 8 cliff and the sea. North-east: bridges between level 7 "
              + "mesas over a level 1 canyon: flush, with a railway, and ramped up from lower ground. South: roads "
              + "spiralling up mesas between their cliffs and the ground, a road and a road with a railway, and a "
              + "tight hairpin turning onto a climbing leg. Inspect in Classic and Enhanced.");
        ledge(board, LEDGE, false);
        ledge(board, LEDGE_RAIL, true);
        coast(board);
        bridges(board);
        spiral(board, SPIRAL, false);
        spiral(board, SPIRAL_RAIL, true);
        hairpin(board);
        board.setAnnotations(LEDGE, List.of("Ledge road"));
        board.setAnnotations(LEDGE_RAIL, List.of("Ledge road and rail"));
        board.setAnnotations(COAST, List.of("Coast road"));
        board.setAnnotations(BRIDGES, List.of("Mesa bridges"));
        board.setAnnotations(SPIRAL, List.of("Spiral road"));
        board.setAnnotations(SPIRAL_RAIL, List.of("Spiral road and rail"));
        board.setAnnotations(HAIRPIN, List.of("Hairpin turn"));
        return board;
    }

    private static List<Coords> line(Coords from, int direction, int length) {
        List<Coords> result = new ArrayList<>();
        for (int i = 0; i <= length; i++) { result.add(from.translated(direction, i)); }
        return result;
    }

    private static void level(Board board, int x, int y, int level) {
        Hex hex = board.getHex(x, y);
        if (hex != null) { hex.setLevel(level); }
    }

    /**
     * A ledge running south between a level 8 cliff to the west and level 0 ground to the east: level at 4, then
     * descending one level a hex along the cliff's foot.
     */
    private static void ledge(Board board, Coords top, boolean rail) {
        var road = line(top, 3, 15);
        for (Coords at : road) {
            int y = at.getY() - top.getY();
            for (int x = top.getX() - 3; x <= top.getX() + 2; x++) {
                level(board, x, at.getY(), x < top.getX() ? 8 : x > top.getX() ? 0 : Math.max(0, 4 - Math.max(0, y - 10)));
            }
        }
        RoadGradeFixture.ways(board, road, Set.of(), rail);
    }

    /** A coast road climbing north along a sea cliff: a level 8 cliff to the west, the sea below to the east. */
    private static void coast(Board board) {
        var road = line(COAST, 3, 14);
        for (Coords at : road) {
            int y = at.getY() - COAST.getY();
            for (int x = COAST.getX() - 4; x <= COAST.getX() + 5; x++) {
                Hex hex = board.getHex(x, at.getY());
                if (x < COAST.getX()) {
                    hex.setLevel(8);
                } else if (x == COAST.getX()) {
                    hex.setLevel(Math.max(1, 6 - y / 2));
                } else if (x == COAST.getX() + 1) {
                    hex.setLevel(0);
                } else {
                    hex.setLevel(0);
                    hex.addTerrain(new Terrain(Terrains.WATER, 2));
                }
            }
        }
        RoadGradeFixture.ways(board, road, Set.of(), false);
    }

    /**
     * Level 7 mesas either side of a level 1 canyon, bridged three times: a road whose deck is flush with both mesas, a
     * road with a railway, and a road ramping up to its deck from a lower terrace.
     */
    private static void bridges(Board board) {
        for (int y = 0; y < 17; y++) {
            for (int x = BRIDGES.getX() - 2; x < WIDTH; x++) {
                int dx = x - BRIDGES.getX();
                // The third approach starts on a lower terrace and ramps up onto its deck.
                level(board, x, y, dx >= 3 && dx <= 5 ? 1 : dx <= 2 && y >= 10 ? 5 : 7);
            }
        }
        List<List<Coords>> spans = List.of(line(BRIDGES, 2, 8), line(BRIDGES.translated(3, 4), 2, 8),
              line(BRIDGES.translated(3, 9), 2, 8));
        for (int i = 0; i < spans.size(); i++) {
            var span = spans.get(i);
            Set<Coords> decks = new HashSet<>();
            for (Coords at : span) {
                int dx = at.getX() - BRIDGES.getX();
                if (dx >= 3 && dx <= 5) {
                    decks.add(at);
                    Hex hex = board.getHex(at);
                    hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 0));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 7 - hex.getLevel()));
                    hex.setAppearance(Map.of("bridge", HexAppearance.BUILT_BRIDGE));
                }
            }
            RoadGradeFixture.ways(board, span, decks, i == 1);
        }
    }

    /** The hexes of a ring round a centre, clockwise from its south-west. */
    private static List<Coords> ring(Coords center, int radius) {
        List<Coords> result = new ArrayList<>();
        Coords at = center.translated(4, radius);
        for (int side = 0; side < 6; side++) {
            for (int step = 0; step < radius; step++) {
                result.add(at);
                at = at.translated(side);
            }
        }
        return result;
    }

    /**
     * A road spiralling up a level 7 mesa over level 0 ground: round its second ring, climbing a level every second hex
     * between the mesa's cliff and the drop to the ground, then onto its top.
     */
    private static void spiral(Board board, Coords center, boolean rail) {
        for (int radius = 0; radius <= 4; radius++) {
            for (Coords at : radius == 0 ? List.of(center) : ring(center, radius)) {
                level(board, at.getX(), at.getY(), radius <= 1 ? 7 : radius == 2 ? 7 : 0);
            }
        }
        var climb = ring(center, 2);
        // An approach from the ground to the south-west, then round the ring and onto the top.
        List<Coords> road = new ArrayList<>(line(climb.getFirst().translated(4, 2), 1, 1));
        for (int i = 0; i < 10; i++) {
            Coords at = climb.get(i);
            board.getHex(at).setLevel(1 + i / 2);
            road.add(at);
        }
        // Onto the top with the gentlest turn a railway takes.
        int entry = RoadGradeFixture.direction(climb.get(9), climb.get(8)), best = -1;
        for (int d = 0; d < 6; d++) {
            int turn = Math.min(Math.floorMod(d - entry, 6), Math.floorMod(entry - d, 6));
            if (climb.get(9).translated(d).distance(center) == 1 && (best < 0
                  || turn > Math.min(Math.floorMod(best - entry, 6), Math.floorMod(entry - best, 6)))) { best = d; }
        }
        road.add(climb.get(9).translated(best));
        RoadGradeFixture.ways(board, road, Set.of(), rail);
    }

    /**
     * A tight hairpin: from its apex one leg runs on level, the other climbs a level beside it, the two separated only by
     * the ground inside the turn, which steps up a level between them.
     */
    private static void hairpin(Board board) {
        for (int y = HAIRPIN.getY() - 6; y <= HAIRPIN.getY() + 6; y++) {
            for (int x = HAIRPIN.getX() - 5; x <= HAIRPIN.getX() + 6; x++) { level(board, x, y, 2); }
        }
        var upper = line(HAIRPIN, 1, 4);
        var lower = line(HAIRPIN.translated(2), 1, 4);
        board.getHex(HAIRPIN.translated(3)).setLevel(3);
        for (Coords at : lower) {
            board.getHex(at).setLevel(3);
            board.getHex(at.translated(3)).setLevel(4);
        }
        List<Coords> road = new ArrayList<>(upper.reversed());
        road.addAll(lower);
        RoadGradeFixture.ways(board, road, Set.of(), false);
    }

    /** Writes the native board, validated after reloading, with an optional runtime mirror directory. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path file = output.resolve(NAME + ".board2");
        BoardFile.save(create(), file);
        RoadGradeFixture.validate(BoardFile.read(file));
        System.out.println(file + " : " + WIDTH + "x" + HEIGHT);
        if (args.length > 1) {
            Files.createDirectories(Path.of(args[1]));
            Files.copy(file, Path.of(args[1]).resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
