/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** A saved, editable catalogue of road/rail joins; also the shared input for focused render crops. */
final class RoadJunctionFixture {
    static final int WIDTH = 64, HEIGHT = 132;

    enum Panel {
        ROAD("Road only", true, false), RAIL("Rail only", false, true),
        CENTERED("Road + centered rail", true, true), BESIDE("Road + beside rail", true, true);

        final String label;
        final boolean road, rail;

        Panel(String label, boolean road, boolean rail) { this.label = label; this.road = road; this.rail = rail; }
    }

    record Case(String id, String label, Coords at) { }

    static Coords center(Panel panel, int mask) {
        return new Coords(2 + 32 * (panel.ordinal() % 2) + 4 * (mask % 8),
              2 + 32 * (panel.ordinal() / 2) + 4 * (mask / 8));
    }

    static List<Case> mixedCases() {
        return List.of(new Case("crossing", "Independent crossing: road 9 / rail 18", new Coords(5, 69)),
              new Case("side-street-centered", "Side street: road 11 / centered rail 9", new Coords(15, 69)),
              new Case("side-street-beside", "Side street: road 11 / beside rail 9", new Coords(25, 69)),
              new Case("bend-centered", "Partial shared bend: road 27 / centered rail 17", new Coords(35, 69)),
              new Case("bend-beside", "Partial shared bend: road 27 / beside rail 17", new Coords(45, 69)),
              new Case("crossing-deck", "Ground road 9 under elevated rail 18", new Coords(55, 69)));
    }

    static List<Case> triangleCases() {
        return List.of(new Case("triangle-road", "Three-hex road triangle", new Coords(5, 82)),
              new Case("triangle-crossing", "Road triangle crossed by straight rail", new Coords(15, 82)),
              new Case("triangle-bend", "Road triangle with bending rail", new Coords(25, 82)),
              new Case("triangle-centered", "Road triangle with centered shared rail", new Coords(35, 82)),
              new Case("triangle-beside", "Road triangle with beside shared rail", new Coords(45, 82)),
              new Case("triangle-deck", "Road triangle under elevated rail", new Coords(55, 82)));
    }

    static List<Coords> triangle(Case sample) {
        return List.of(sample.at(), sample.at().translated(2), sample.at().translated(3));
    }

    static List<Case> triangleVariants() {
        return List.of(new Case("seaport-left", "Seaport left: road masks 13 / 50 / 19", new Coords(5, 96)),
              new Case("seaport-right", "Seaport right: road masks 44 / 52 / 19", new Coords(15, 96)),
              new Case("triangle-rotated-120", "Seaport left rotated 120 degrees", new Coords(25, 96)),
              new Case("triangle-rotated-240", "Seaport left rotated 240 degrees", new Coords(35, 96)),
              new Case("triangle-reflected", "Seaport left reflected", new Coords(45, 96)),
              new Case("triangle-reflected-120", "Seaport left reflected and rotated 120 degrees", new Coords(55, 96)));
    }

    static List<Case> widthCases() {
        return List.of(new Case("parking", "Widened road beside converted parking bays and scale-1 cars", new Coords(5, 110)),
              new Case("trees", "Widened tree-lined road", new Coords(15, 110)),
              new Case("bridge-road", "Widened built road bridge", new Coords(25, 110)),
              new Case("bridge-centered", "Widened road bridge with centered rail", new Coords(35, 110)),
              new Case("bridge-beside", "Widened road bridge with beside rail and border seams", new Coords(45, 110)),
              new Case("tunnel", "Widened road entering a level-4 cliff", new Coords(55, 110)),
              new Case("tunnel-centered", "Cliff tunnel with centered rail", new Coords(5, 124)),
              new Case("tunnel-beside", "Cliff tunnel with beside rail", new Coords(15, 124)));
    }

    static List<Case> controlCases() {
        return List.of(new Case("priority-t", "Automatic T: priority through road, STOP on the side road", new Coords(5, 118)),
              new Case("signal-t", "Explicit signal T: all three incoming approaches controlled", new Coords(15, 118)),
              new Case("default-x", "Automatic X: overhead signals and yellow keep-clear box", new Coords(25, 118)),
              new Case("stop-x", "STOP-only X: stop bars without a yellow box", new Coords(35, 118)),
              new Case("signal-x", "Signal X: roadside lights and yellow keep-clear box", new Coords(45, 118)),
              new Case("gantry-rail-x", "Overhead signal X with centered railway: keep the track clear", new Coords(55, 118)));
    }

    static Board create() {
        Board board = Board.createEmptyBoard(WIDTH, HEIGHT);
        board.setNativeFormat(true);
        board.setRoadsAutoExit(false);
        board.setDocumentName("Road and Rail Junctions");
        board.addTag("Example");
        board.setDescription("Road/rail geometry fixture. The four 8 x 8 panels are road only (upper left), rail only "
              + "(upper right), road + centered rail (lower left), and road + beside rail (lower right). "
              + "Each panel contains exit masks 0 through 63, left to right then top to bottom. Bits 1,2,4,8,16,32 "
              + "mean N,NE,SE,S,SW,NW. Every active arm has a connected terminal hex. Mask 0 is an isolated segment. "
              + "Rails keep the standard legal straight and gentle bend pieces; adjacent-only arms remain stranded "
              + "stubs, and Y tracks keep their standard stem. Beside choices apply only where the existing shared "
              + "corridor permits them; they never invent a tight rail turn. The strip at row 70 compares independent "
              + "crossings, side streets, partial shared bends and elevated rail. The strip at row 83 repeats a "
              + "three-hex road triangle with straight, bending, centered, beside and elevated rail. Row 97 contains "
              + "the exact Seaport left/right topologies and rotated/reflected copies of the left triangle. Row 111 "
              + "compares the wider road with parking, roadside trees, a road bridge, centered and beside rail bridges, "
              + "and a cliff tunnel. The north/south bridge seams expose the full road and ballast width. Row 119 "
              + "compares an automatic priority T, an explicitly signal-controlled T, an automatic overhead-signal X, "
              + "a STOP-only X, a roadside-signal X, and an overhead-signal X with centered railway. Row 125 adds "
              + "centered and beside railway tunnels. Inspect the "
              + "annotated centre hex of each case. All roads and rails are ordinary editable board terrain.");
        for (Panel panel : Panel.values()) {
            for (int mask = 0; mask < 64; mask++) {
                Coords at = center(panel, mask);
                spokes(board, at, panel.road ? mask : -1, panel.rail ? mask : -1);
                if (panel == Panel.BESIDE) { beside(board, at); }
                board.setAnnotations(at, List.of(panel.label + "; exits " + mask + " ("
                      + String.format("%6s", Integer.toBinaryString(mask)).replace(' ', '0') + ")"));
            }
        }
        int[][] mixed = { { 9, 18 }, { 11, 9 }, { 11, 9 }, { 27, 17 }, { 27, 17 } };
        var samples = mixedCases();
        for (int i = 0; i < mixed.length; i++) {
            spokes(board, samples.get(i).at(), mixed[i][0], mixed[i][1]);
            if (i == 2 || i == 4) { beside(board, samples.get(i).at()); }
        }
        spokes(board, samples.get(5).at(), 9, -1);
        railPath(board, through(samples.get(5).at(), 1, 4), true);
        for (Case sample : samples) { board.setAnnotations(sample.at(), List.of(sample.label())); }

        samples = triangleCases();
        for (int i = 0; i < samples.size(); i++) {
            Case sample = samples.get(i);
            var vertices = triangleRoads(board, sample.at(), 0, false, false);
            Coords a = vertices.get(0), b = vertices.get(1), c = vertices.get(2);
            if (i == 1 || i == 5) { railPath(board, through(a, 1, 4), i == 5); }
            if (i == 2) {
                railPath(board, List.of(a.translated(0, 2), a.translated(0), a, b, b.translated(2), b.translated(2, 2)), false);
            }
            if (i == 3 || i == 4) {
                railPath(board, List.of(a.translated(0, 2), a.translated(0), a, c, c.translated(4), c.translated(4, 2)), false);
                if (i == 4) { beside(board, a); }
            }
            board.setAnnotations(a, List.of(sample.label()));
        }
        samples = triangleVariants();
        for (int i = 0; i < samples.size(); i++) {
            Case sample = samples.get(i);
            int rotation = i == 2 || i == 5 ? 2 : i == 3 ? 4 : 0;
            triangleRoads(board, sample.at(), rotation, i >= 4, i == 1);
            board.setAnnotations(sample.at(), List.of(sample.label()));
        }
        samples = widthCases();
        for (int i = 0; i < 2; i++) {
            Coords at = samples.get(i).at();
            roadPath(board, through(at, 0, 3));
            board.getHex(at).addTerrain(i == 0 ? new Terrain(Terrains.FLUFF, 5, true, 2)
                  : new Terrain(Terrains.ROAD_FLUFF, 3));
            BoardSceneryLayouts.convert(board, List.of(at));
        }
        for (int i = 2; i < 5; i++) {
            Coords at = samples.get(i).at();
            var path = through(at, 0, 3);
            for (Coords member : path) {
                Hex hex = board.getHex(member);
                bridge(hex, 9);
                hex.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 1, true, 9));
                if (i > 2) { RailRoute.lay(hex, true, 9, 0); }
            }
            for (int d : new int[] { 0, 3 }) {
                Coords approach = at.translated(d, 3);
                Hex hex = board.getHex(approach);
                hex.setLevel(2);
                int back = 1 << ((d + 3) % 6);
                road(board, approach, back);
                if (i > 2) { RailRoute.lay(hex, false, back, 0); }
            }
            if (i == 4) { beside(board, at, true); }
        }
        for (int i = 5; i < samples.size(); i++) {
            Coords tunnel = samples.get(i).at();
            var path = List.of(tunnel.translated(3, 2), tunnel.translated(3), tunnel);
            roadPath(board, path);
            road(board, tunnel, 1);
            if (i > 5) {
                railPath(board, path, false);
                RailRoute.lay(board.getHex(tunnel), false, 9, 0);
                if (i == 7) { beside(board, tunnel); }
            }
            board.getHex(tunnel.translated(0)).setLevel(4);
        }
        for (Case sample : samples) { board.setAnnotations(sample.at(), List.of(sample.label())); }
        samples = controlCases();
        HexAppearance.TrafficControl[] controls = { null, HexAppearance.TrafficControl.SIGNALS, null,
              HexAppearance.TrafficControl.STOPS, HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY };
        for (int i = 0; i < samples.size(); i++) {
            Case sample = samples.get(i);
            spokes(board, sample.at(), i < 2 ? 11 : 27, i == 5 ? 9 : -1);
            if (controls[i] != null) {
                board.getHex(sample.at()).setAppearance(Map.of("road", HexAppearance.withTrafficControl(null, controls[i])));
            }
            board.setAnnotations(sample.at(), List.of(sample.label()));
        }
        return board;
    }

    private static List<Coords> triangleRoads(Board board, Coords at, int rotation, boolean reflected, boolean right) {
        java.util.function.IntUnaryOperator turn = d -> Math.floorMod((reflected ? -d : d) + rotation, 6);
        var vertices = List.of(at, at.translated(turn.applyAsInt(2)), at.translated(turn.applyAsInt(3)));
        roadPath(board, List.of(vertices.get(0), vertices.get(1), vertices.get(2), vertices.get(0)));
        int[] arms = right ? new int[] { 5, 2, 4 } : new int[] { 0, 1, 4 };
        for (int i = 0; i < vertices.size(); i++) {
            Coords vertex = vertices.get(i);
            int d = turn.applyAsInt(arms[i]);
            roadPath(board, List.of(vertex, vertex.translated(d), vertex.translated(d, 2)));
        }
        return vertices;
    }

    private static List<Coords> through(Coords at, int first, int second) {
        return List.of(at.translated(first, 2), at.translated(first), at, at.translated(second), at.translated(second, 2));
    }

    /** -1 means no way; zero is an authored isolated segment. */
    private static void spokes(Board board, Coords at, int road, int rail) {
        if (road >= 0) { road(board, at, road); }
        if (rail >= 0) { RailRoute.lay(board.getHex(at), false, rail, 0); }
        for (int d = 0; d < 6; d++) {
            Coords next = at.translated(d);
            int back = 1 << ((d + 3) % 6);
            if (road >= 0 && (road & 1 << d) != 0) { road(board, next, back); }
            if (rail >= 0 && (rail & 1 << d) != 0) { RailRoute.lay(board.getHex(next), false, back, 0); }
        }
    }

    private static void road(Board board, Coords at, int sides) {
        Hex hex = board.getHex(at);
        int previous = hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() : 0;
        hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, previous | sides));
    }

    private static void roadPath(Board board, List<Coords> path) {
        for (int i = 1; i < path.size(); i++) {
            Coords a = path.get(i - 1), b = path.get(i);
            int d = direction(a, b);
            road(board, a, 1 << d); road(board, b, 1 << ((d + 3) % 6));
        }
    }

    private static void railPath(Board board, List<Coords> path, boolean deck) {
        for (int i = 0; i < path.size(); i++) {
            Coords at = path.get(i);
            int sides = (i == 0 ? 0 : 1 << direction(at, path.get(i - 1)))
                  | (i == path.size() - 1 ? 0 : 1 << direction(at, path.get(i + 1)));
            Hex hex = board.getHex(at);
            if (deck) { bridge(hex, sides); }
            RailRoute.lay(hex, deck, sides, 0);
        }
    }

    private static void bridge(Hex hex, int sides) {
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, sides));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        hex.setAppearance(Map.of("bridge", HexAppearance.BUILT_BRIDGE));
    }

    private static int direction(Coords from, Coords to) {
        for (int d = 0; d < 6; d++) { if (from.translated(d).equals(to)) { return d; } }
        throw new IllegalArgumentException("Fixture paths must use adjacent hexes");
    }

    private static void beside(Board board, Coords at) {
        beside(board, at, false);
    }

    private static void beside(Board board, Coords at, boolean deck) {
        var marker = RailRoute.markers(board.getHex(at), RailRoute.Kind.RAIL).stream()
              .filter(m -> RailRoute.onDeck(m) == deck).findFirst().orElseThrow();
        var run = RailRoute.corridorRun(board::getHex, at, marker);
        if (run.isEmpty()) {
            RailRoute.beside(board.getHex(at), deck, 64);
            return;
        }
        var choices = RailRoute.besideBits(board::getHex, run, new RailRoute.Member(at, marker));
        choices.forEach((member, bits) -> RailRoute.beside(board.getHex(member.at()), RailRoute.onDeck(member.marker()), bits));
    }

    /** Export with the compiled test runtime classpath; normal tests only write their temporary directory. */
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        BoardFile.save(create(), target);
    }
}
