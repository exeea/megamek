/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Four connected compositions of roads, rails and bridges over terraced ground, authored with the grade fixture's
 * helpers: a hill crossroads, a river crossing, a concrete city and an interchange.
 */
final class RoadNetworkFixture {
    static final int SIZE = 30;
    static final String NAME = "Road Network";
    static final Coords HILL = new Coords(7, 7), RIVER = new Coords(22, 7), CITY = new Coords(7, 22),
          INTERCHANGE = new Coords(22, 22);

    private RoadNetworkFixture() { }

    static Board create() {
        Board board = RoadGradeFixture.blank(false, SIZE, SIZE);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) { board.getHex(x, y).setLevel(1); }
        }
        board.setDocumentName(NAME);
        board.addTag("Example");
        board.setDescription("Complex road, rail and bridge compositions over terraced ground. North-west: a road and "
              + "centred railway climbing over a terraced hill, crossing a road at its summit, with a T junction on "
              + "its slope. North-east: the road and railway crossing a river valley on a bridge between banks at "
              + "different levels, over a valley road, with a T junction at the bridge mouth. South-west: a concrete "
              + "city on terraced blocks, with a beside railway, junctions across level changes and a sloped apron. "
              + "South-east: an elevated road bridge over a road and railway corridor, with a climbing link road "
              + "and junctions beside both bridge mouths. Inspect in Classic and Enhanced.");
        hill(board);
        river(board);
        city(board);
        interchange(board);
        board.setAnnotations(HILL, List.of("Hill crossroads"));
        board.setAnnotations(RIVER, List.of("River crossing"));
        board.setAnnotations(CITY, List.of("Concrete city"));
        board.setAnnotations(INTERCHANGE, List.of("Interchange"));
        return board;
    }

    /** Terraces in a square region around a centre: the level of each hex from its distance. */
    private static void terrain(Board board, Coords center, int radius, ToIntFunction<Coords> level) {
        for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
            for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
                if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) { continue; }
                Coords at = new Coords(x, y);
                board.getHex(at).setLevel(level.applyAsInt(at));
            }
        }
    }

    private static List<Coords> line(Coords from, int direction, int length) {
        List<Coords> result = new ArrayList<>();
        for (int i = 0; i <= length; i++) { result.add(from.translated(direction, i)); }
        return result;
    }

    /** Leaves {@code from} in {@code direction}, then steps to whichever neighbour is nearest to {@code to}. */
    private static List<Coords> route(Coords from, int direction, Coords to) {
        List<Coords> result = new ArrayList<>(List.of(from, from.translated(direction)));
        while (!result.getLast().equals(to)) {
            Coords at = result.getLast(), best = null;
            for (int d = 0; d < 6; d++) {
                Coords next = at.translated(d);
                if (best == null || next.distance(to) < best.distance(to)) { best = next; }
            }
            result.add(best);
        }
        return result;
    }

    private static List<Coords> join(List<Coords> first, List<Coords> second) {
        List<Coords> result = new ArrayList<>(first);
        result.addAll(second.subList(1, second.size()));
        return result;
    }

    /** A terraced hill: a road and centred railway over its summit, a crossing road, a T junction on its slope. */
    private static void hill(Board board) {
        terrain(board, HILL, 7, at -> Math.max(1, 4 - (HILL.distance(at) + 1) / 2));
        var main = join(line(HILL.translated(0, 6), 3, 6), line(HILL, 3, 6));
        RoadGradeFixture.ways(board, main, Set.of(), true);
        var cross = join(line(HILL.translated(1, 5), 4, 5), line(HILL, 4, 5));
        RoadGradeFixture.ways(board, cross, Set.of(), false);
        // A branch leaving the crossing road on the slope descends the hill to the east.
        var branch = join(line(HILL.translated(1, 2), 2, 3), line(HILL.translated(1, 2).translated(2, 3), 3, 2));
        RoadGradeFixture.ways(board, branch, Set.of(), false);
    }

    /** A river valley: a bridge carrying road and railway between banks at different levels, over a valley road. */
    private static void river(Board board) {
        terrain(board, RIVER, 7, at -> {
            int dx = at.getX() - RIVER.getX();
            return dx < -1 ? 3 : dx > 1 ? 2 : dx == 0 ? 0 : 1;
        });
        for (int y = RIVER.getY() - 7; y <= RIVER.getY() + 7; y++) {
            board.getHex(RIVER.getX(), y).addTerrain(new Terrain(Terrains.WATER, 1));
        }
        var crossing = line(RIVER.translated(5, 5), 2, 10);
        Set<Coords> decks = new HashSet<>();
        for (Coords at : crossing) {
            int dx = at.getX() - RIVER.getX();
            if (Math.abs(dx) <= 1) { decks.add(at); }
        }
        // The valley road keeps its own ground under the western deck hex.
        Coords under = null;
        for (Coords at : decks) {
            if (at.getX() == RIVER.getX() - 1) { under = at; }
        }
        for (Coords at : decks) {
            if (at.equals(under)) { continue; }
            RoadGradeFixture.bridge(board, at, 3, true);
        }
        Hex overpass = board.getHex(under);
        overpass.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 0));
        overpass.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
        overpass.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 3 - overpass.getLevel()));
        overpass.setAppearance(java.util.Map.of("bridge", megamek.common.board.HexAppearance.BUILT_BRIDGE));
        RoadGradeFixture.ways(board, crossing, decks, true);
        var valley = join(line(under.translated(0, 5), 3, 5), line(under, 3, 5));
        RoadGradeFixture.ways(board, valley, Set.of(), false);
        // A T junction at the eastern bridge mouth; its branch follows the bank south and descends.
        Coords mouth = null;
        for (Coords at : crossing) {
            if (at.getX() == RIVER.getX() + 2) { mouth = at; }
        }
        var bank = line(mouth, 3, 5);
        RoadGradeFixture.ways(board, bank, Set.of(), false);
        board.getHex(bank.getLast()).setLevel(1);
        board.getHex(bank.get(bank.size() - 2)).setLevel(1);
    }

    /** A concrete city on terraced blocks, with a beside railway and a sloped apron. */
    private static void city(Board board) {
        terrain(board, CITY, 7, at -> at.getY() < CITY.getY() - 1 ? 2 : at.getX() < CITY.getX() - 2 ? 3 : 1);
        for (int y = CITY.getY() - 7; y <= CITY.getY() + 7; y++) {
            for (int x = CITY.getX() - 7; x <= CITY.getX() + 7; x++) {
                board.getHex(x, y).addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            }
        }
        // A street with a railway beside it runs north to south across the terraces.
        var avenue = line(CITY.translated(0, 7).translated(2), 3, 13);
        RoadGradeFixture.ways(board, avenue, Set.of(), true);
        // Two diagonal streets cross it, one at a terrace step.
        var first = join(line(CITY.translated(5, 5), 2, 5), line(CITY, 2, 5));
        RoadGradeFixture.ways(board, first, Set.of(), false);
        var second = join(line(CITY.translated(0, 4).translated(5, 4), 2, 4), line(CITY.translated(0, 4), 2, 5));
        RoadGradeFixture.ways(board, second, Set.of(), false);
        // A street ending at a raised, roadless apron.
        var lane = line(CITY.translated(3, 4), 4, 3);
        RoadGradeFixture.ways(board, lane, Set.of(), false);
        for (Coords at : List.of(lane.getLast().translated(4), lane.getLast().translated(4).translated(3),
              lane.getLast().translated(4).translated(5))) {
            board.getHex(at).setLevel(2);
        }
        beside(board, CITY, 7);
    }

    /** An elevated road bridge over a road and railway corridor, with a link road climbing to its eastern bank. */
    private static void interchange(Board board) {
        terrain(board, INTERCHANGE, 7, at -> {
            int west = new Coords(INTERCHANGE.getX() - 5, INTERCHANGE.getY()).distance(at);
            int east = new Coords(INTERCHANGE.getX() + 5, INTERCHANGE.getY()).distance(at);
            return Math.max(2, Math.max(5 - west, 5 - east));
        });
        var corridor = line(INTERCHANGE.translated(0, 7), 3, 14);
        RoadGradeFixture.ways(board, corridor, Set.of(), true);
        var span = line(INTERCHANGE.translated(4, 5), 1, 10);
        Set<Coords> decks = new HashSet<>();
        for (Coords at : span) {
            if (Math.abs(at.getX() - INTERCHANGE.getX()) <= 1) { decks.add(at); }
        }
        for (Coords at : decks) {
            Hex hex = board.getHex(at);
            boolean ground = at.getX() == INTERCHANGE.getX();
            if (!ground) { hex.setLevel(2); }
            hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 0));
            hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
            hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 5 - hex.getLevel()));
            hex.setAppearance(java.util.Map.of("bridge", megamek.common.board.HexAppearance.BUILT_BRIDGE));
        }
        for (Coords at : span) {
            if (!decks.contains(at)) { board.getHex(at).setLevel(Math.max(4, board.getHex(at).getLevel())); }
        }
        RoadGradeFixture.ways(board, span, decks, false);
        // The link road leaves the corridor at a T junction and climbs to a T junction beside the eastern mouth.
        Coords mouth = span.stream().filter(at -> at.getX() == INTERCHANGE.getX() + 2).findFirst().orElseThrow();
        var link = route(INTERCHANGE.translated(3, 4), 2, mouth);
        RoadGradeFixture.ways(board, link, Set.of(), false);
        beside(board, INTERCHANGE, 7);
    }

    private static void beside(Board board, Coords center, int radius) {
        Set<RailRoute.Member> done = new HashSet<>();
        for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
            for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
                Coords at = new Coords(x, y);
                if (board.getHex(at) == null) { continue; }
                for (var marker : RailRoute.markers(board.getHex(at), RailRoute.Kind.RAIL)) {
                    var start = new RailRoute.Member(at, marker);
                    if (done.contains(start)) { continue; }
                    var run = RailRoute.corridorRun(board::getHex, at, marker);
                    done.addAll(run);
                    RailRoute.besideBits(board::getHex, run, start).forEach((member, bits) ->
                          RailRoute.beside(board.getHex(member.at()), RailRoute.onDeck(member.marker()), bits));
                }
            }
        }
    }

    /** Writes the native board, validated after reloading, with an optional runtime mirror directory. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path file = output.resolve(NAME + ".board2");
        BoardFile.save(create(), file);
        RoadGradeFixture.validate(BoardFile.read(file));
        System.out.println(file + " : " + SIZE + "x" + SIZE);
        if (args.length > 1) {
            Files.createDirectories(Path.of(args[1]));
            Files.copy(file, Path.of(args[1]).resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
