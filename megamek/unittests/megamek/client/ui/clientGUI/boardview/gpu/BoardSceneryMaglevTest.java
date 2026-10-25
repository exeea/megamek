/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardModelBounds;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Legacy maglev decodes to route markers and separate pieces measured from its sprites; the palette keeps five entries. */
class BoardSceneryMaglevTest {
    private static final String WAGON = "scenery/maglev/wagon", CAB = "scenery/maglev/cab", PLATFORM = "scenery/maglev/platform";

    @Test
    void conventionalStockUsesLargerAuthoredMeshesAndMatchingPlacementBoundsWhileMaglevAndCarsStayUnchanged() {
        Coords at = new Coords(3, 4);
        for (String asset : List.of("scenery/rail/locomotive", "scenery/rail/box-car", RailRoute.BUFFER_STOP,
              WAGON, CAB, "scenery/vehicles/car")) {
            var object = new BoardDecoration("stock", "prop", asset, null, .12, -.18, 0, false, 1.25,
                  BoardDecoration.Placement.ground(), 0, false, 0, 0);
            Hex hex = new Hex(); hex.setDecorations(List.of(object));
            var feature = BoardFeatures.capture(hex, at, Map.of()).stream()
                  .filter(f -> object.equals(f.decoration())).findFirst().orElseThrow();
            float[] raw = BoardModelBounds.bounds(asset);
            assertNotNull(raw, asset);
            float expectedScale = 1.25f * (asset.startsWith("scenery/rail/") ? 1.3f : 1);
            var drawn = new BoundingBox(new Vector3(raw[0], raw[1], raw[2]), new Vector3(raw[3], raw[4], raw[5]))
                  .mul(BoardFeatures.decorationTransform(at, feature, 0));
            assertEquals((raw[3] - raw[0]) * expectedScale * BoardGeometry.hexScale(), drawn.getWidth(), .001f, asset);
            assertEquals((raw[4] - raw[1]) * expectedScale * BoardGeometry.hexScale(), drawn.getHeight(), .001f, asset);
            assertEquals((raw[5] - raw[2]) * expectedScale * BoardGeometry.hexScale(), drawn.getDepth(), .001f, asset);
            var footprint = BoardModelBounds.footprint(at, object);
            assertNotNull(footprint, asset);
            assertEquals(drawn.min.x / BoardGeometry.hexScale(), footprint.stream().mapToDouble(p -> p.x()).min().orElseThrow(), .001, asset);
            assertEquals(drawn.max.y / BoardGeometry.hexScale(), footprint.stream().mapToDouble(p -> p.y()).max().orElseThrow(), .001, asset);
            assertEquals(1.25, object.scale(), "Runtime scale never changes saved author scale");
        }
    }

    @Test
    void newConventionalTrainStampsGrowCarSpacingWhileMaglevStampCoordinatesArePreserved() {
        for (String stamp : List.of("stamp/rail-train", "stamp/express-train", "stamp/colony-train", "stamp/maglev-train")) {
            String layout = BoardEditorBlueprint.get().asset(stamp).layout();
            var rows = BoardSceneryLayouts.layout(layout).components();
            var objects = BoardSceneryLayouts.stamp(layout, new Hex(), new Coords(2, 2));
            assertEquals(rows.size(), objects.size(), stamp);
            double spacing = stamp.equals("stamp/maglev-train") ? 1 : 1.3;
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i); var object = objects.get(i);
                assertEquals(row.x() * spacing, object.x() * HexTile.MODEL_WIDTH, .0001, stamp);
                assertEquals(row.y() * spacing, object.y() * 72, .0001, stamp);
                assertEquals(row.scale(), object.scale(), .0001, stamp);
            }
        }
    }

    /** What each legacy sprite shows: the route's sides, a platform, cab and wagon count, and parked cars. */
    private record Sprite(int sides, int platforms, int cabs, int wagons, int cars) { }

    private static final Map<String, Sprite> SPRITES = Map.ofEntries(
          Map.entry("track1", new Sprite(9, 0, 0, 0, 0)), Map.entry("track2", new Sprite(18, 0, 0, 0, 0)),
          Map.entry("track3", new Sprite(36, 0, 0, 0, 0)), Map.entry("station1", new Sprite(9, 1, 0, 0, 4)),
          Map.entry("station2", new Sprite(18, 1, 0, 0, 2)), Map.entry("station3", new Sprite(36, 1, 0, 0, 0)),
          Map.entry("train1", new Sprite(9, 0, 1, 1, 2)), Map.entry("train2", new Sprite(9, 1, 0, 2, 4)),
          Map.entry("train3", new Sprite(9, 0, 0, 2, 1)), Map.entry("train4", new Sprite(18, 0, 1, 1, 0)),
          Map.entry("train5", new Sprite(18, 1, 0, 2, 3)), Map.entry("train6", new Sprite(18, 0, 0, 2, 1)));

    @Test
    void legacyMaglevDecodesToARouteMarkerAndPiecesMeasuredFromTheSprites() {
        SPRITES.forEach((name, sprite) -> {
            var rows = BoardSceneryLayouts.layout("scenery/fluff/maglev" + name).components();
            var routes = rows.stream().filter(BoardSceneryLayouts.Component::route).toList();
            assertEquals(1, routes.size(), name);
            assertEquals(sprite.sides(), routes.getFirst().connections(), name);
            assertEquals(0, routes.getFirst().x(), 1e-4, name);
            assertEquals(0, routes.getFirst().y(), 1e-4, name);
            assertTrue(rows.stream().allMatch(row -> row.z() == 0), name + ": the ride height is in the meshes");
            assertEquals(sprite.platforms(), count(rows, PLATFORM), name);
            assertEquals(sprite.cabs(), count(rows, CAB), name);
            assertEquals(sprite.wagons(), count(rows, WAGON), name);
            assertEquals(sprite.cars(), rows.stream().filter(row -> row.asset().startsWith("scenery/vehicles/car")).count(), name);
        });
        // Platforms: west of N/S, north-west of NE/SW and north-east of NW/SE routes.
        assertTrue(piece("station1", PLATFORM).x() < 0);
        assertTrue(piece("station2", PLATFORM).x() < 0 && piece("station2", PLATFORM).y() > 0);
        assertTrue(piece("station3", PLATFORM).x() > 0 && piece("station3", PLATFORM).y() > 0);
        // A three-hex train's front hex: maglevtrain1's nose points north, maglevtrain4's south-west.
        var north = piece("train1", CAB);
        assertTrue(north.y() > 0 && Math.abs(north.x()) < 1e-3 && Math.abs(north.rotation()) < 1e-3);
        var southWest = piece("train4", CAB);
        assertTrue(southWest.x() < 0 && southWest.y() < 0);
        assertEquals(120, southWest.rotation(), 1e-3);
    }

    @Test
    void thePaletteOffersBothRoutesInTransportAndTheRollingStockAndStampsWithoutDuplicateMeshes() {
        var blueprint = BoardEditorBlueprint.get();
        // Maglev and Rail are two cards in Transportation's Rail and maglev section, beside the crossing signal, the level
        // crossing and the station platform; the rolling stock has two sections and the train stamps are blueprints.
        var rail = section(blueprint, "transportation/rail-maglev");
        assertTrue(rail.containsAll(Set.of(RailRoute.Kind.MAGLEV.asset, RailRoute.Kind.RAIL.asset,
              "scenery/rail/crossing-signal", "decal/rail/level-crossing", PLATFORM)), rail.toString());
        var stock = new java.util.HashSet<>(section(blueprint, "transportation/maglev-stock"));
        stock.addAll(section(blueprint, "transportation/rail-stock"));
        assertTrue(stock.containsAll(Set.of(WAGON, CAB, "scenery/rail/locomotive")), stock.toString());
        assertTrue(section(blueprint, "transportation/blueprints").containsAll(Set.of("stamp/maglev-train", "stamp/maglev-colony-train",
              "stamp/rail-train", "stamp/express-train", "stamp/colony-train")));
        assertTrue(stock.stream().noneMatch(id -> RailRoute.Kind.of(id) != null), "A route is a Rail and maglev card");
        assertTrue(stock.stream().allMatch(id -> blueprint.asset(id).snap() == null));
        assertTrue(blueprint.asset("scenery/maglev/coupler").sections().isEmpty(), "The coupler comes with the train stamp");
        var stamp = blueprint.asset("stamp/maglev-train");
        assertEquals("scenery/maglev/train", stamp.layout());
        assertTrue(Files.isRegularFile(Configuration.dataDir().toPath().resolve(stamp.thumbnail())), "Stamps use static thumbnails");
        var train = BoardSceneryLayouts.layout(stamp.layout()).components();
        assertEquals(List.of(WAGON, CAB, "scenery/maglev/coupler"), train.stream().map(BoardSceneryLayouts.Component::asset).toList());
        assertTrue(train.stream().allMatch(row -> row.z() == 0));

        // The cars' suspension shoes wrap the route's 4 px beam down to 1.3 px (the coupler to 2.3); the platform and
        // route stand on the ground. The route has no model file: its beam is generated.
        var beam = bounds(BoardRail.model(BoardRail.flat(RailRoute.Kind.MAGLEV, RailRoute.side(0) | RailRoute.side(3), 0)), 7);
        assertEquals(0, beam[0], 1e-4);
        assertEquals(4, beam[1], 1e-4);
        assertEquals(0, low(PLATFORM), 1e-4);
        assertEquals(1.3, low(WAGON), 1e-4);
        assertEquals(1.3, low(CAB), 1e-4);
        assertEquals(2.3, low("scenery/maglev/coupler"), 1e-4);

        var root = Configuration.dataDir().toPath().resolve("models/board/scenery");
        assertFalse(Files.exists(root.resolve("maglev/track.glb")), "The generated route is the only beam");
        assertFalse(Files.exists(root.resolve("maglev/reference")), "No baked legacy maglev composite remains");
        for (String name : SPRITES.keySet()) { assertFalse(Files.exists(root.resolve("fluff/maglev" + name + ".glb")), name); }
    }

    @Test
    void importWritesRailLayersAndThePiecesRideTheRoute() {
        Board board = Board.createEmptyBoard(6, 1);
        String[] hexes = { "fluff:9:7", "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:9:7", "water:1;fluff:9:7",
              "heavy_industrial:2;fluff:9:7", "building:2;bldg_elev:2;bldg_cf:50;fluff:9:3", "fluff:9:5" };
        for (int x = 0; x < hexes.length; x++) { board.setHex(new Coords(x, 0), new Hex(1, hexes[x], "")); }
        BoardSceneryLayouts.convert(board);
        List<BoardDecoration.Placement> expected = List.of(BoardDecoration.Placement.ground(), BoardDecoration.Placement.on("bridge", 0),
              BoardDecoration.Placement.on("bridge", 0), BoardDecoration.Placement.on("bridge", 0), BoardDecoration.Placement.ground(),
              BoardDecoration.Placement.ground());
        for (int x = 0; x < hexes.length; x++) {
            Hex hex = board.getHex(x, 0);
            assertTrue(hex.getDecorations().stream().noneMatch(RailRoute::isRoute), "Route markers are derived");
            if (x == 4) {
                assertNull(RailRoute.find(hex, RailRoute.Kind.MAGLEV), "A maglev over a building is omitted");
                continue;
            }
            var route = RailRoute.find(hex, RailRoute.Kind.MAGLEV);
            assertNotNull(route, hexes[x]);
            assertNull(route.group(), "A route marker is never a group member");
            var pieces = hex.getDecorations().stream().filter(object -> !RailRoute.isRoute(object)).toList();
            // The maglev pieces ride the marker; parked cars stand on the hex's support, and are left out over the
            // water and the industrial plant, which give them none.
            for (var piece : pieces) {
                var placement = piece.asset().startsWith("scenery/maglev/") ? route.placement()
                      : BoardSceneryLayouts.receiver(hex, true);
                assertEquals(placement, piece.placement(), hexes[x] + " " + piece.asset());
            }
            boolean cars = pieces.stream().anyMatch(object -> object.asset().startsWith("scenery/vehicles/car"));
            assertEquals(x != 2 && x != 3 && x != 5, cars, hexes[x]);
            assertEquals(expected.get(x), route.placement(), hexes[x]);
            // A station or train with two or more pieces is one group; the lone platform of station 3 is not grouped.
            var groups = pieces.stream().map(BoardDecoration::group).collect(Collectors.toSet());
            if (pieces.size() > 1) { assertEquals(1, groups.size()); assertNotNull(groups.iterator().next()); }
            else { assertEquals(java.util.Collections.singleton(null), groups); }
        }
    }

    /**
     * Batch-10 answer 7: a converted maglev run a level or more up (here over a bridge deck) stands on Pillars, all of
     * its run; a run on the ground does not; and legacy pillar art in the hexes beside a line (Karkand's row of posts) is
     * that line's Pillars: no pillar objects, the run's Pillars on and the line a level above the ground with the
     * pieces that ride it, while parked cars stay on the ground.
     */
    @Test
    void convertedMaglevsStandOnPillarsWhereTheLegacyArtShowsThemRaised() {
        Board board = Board.createEmptyBoard(7, 6);
        for (int y = 1; y <= 3; y++) {
            board.setHex(new Coords(0, y), new Hex(0, y == 2 ? "water:1;bridge:1:9;bridge_cf:40;bridge_elev:2;fluff:9:0"
                  : "fluff:9:0", ""));
            board.setHex(new Coords(3, y), new Hex(0, "fluff:4:6", ""));
            board.setHex(new Coords(4, y), new Hex(0, y == 2 ? "fluff:9:6" : "fluff:9:0", ""));
            board.setHex(new Coords(6, y), new Hex(0, "fluff:9:0", ""));
        }
        BoardSceneryLayouts.convert(board);
        var raised = BoardDecoration.Placement.on("bridge", 0);
        for (int y = 1; y <= 3; y++) {
            var deck = RailRoute.find(board.getHex(0, y), RailRoute.Kind.MAGLEV);
            assertEquals(y == 2, RailRoute.onDeck(deck), "Only the raised span is on a deck");
            assertEquals(y == 2 ? BoardDecoration.Placement.on("bridge", 0) : BoardDecoration.Placement.ground(), deck.placement());
            assertFalse(board.getHex(6, y).containsTerrain(Terrains.BRIDGE), "a run on the ground, row " + y);
            assertTrue(board.getHex(3, y).getDecorations().isEmpty(), "the pillar art places no pillars, row " + y);
            assertTrue(board.getHex(3, y).containsTerrain(Terrains.FLUFF), "its token stays for the 2D board");
            var line = RailRoute.find(board.getHex(4, y), RailRoute.Kind.MAGLEV);
            assertTrue(HexAppearance.pillars(board.getHex(4, y).getAppearance()), "Bridge pillars carry the line, row " + y);
            assertEquals(1, board.getHex(4, y).terrainLevel(Terrains.BRIDGE_ELEV));
            assertEquals(raised, line.placement(), "a level above the ground, row " + y);
        }
        for (var piece : board.getHex(4, 2).getDecorations()) {
            if (RailRoute.isRoute(piece)) { continue; }
            assertEquals(piece.asset().startsWith("scenery/maglev/") ? raised : BoardDecoration.Placement.ground(),
                  piece.placement(), piece.asset());
        }
        // Pillar art where two lines end (Ishiyama 5407-5607) is no row along a line: it stays plain pillars.
        Board ends = Board.createEmptyBoard(5, 5);
        for (int y = 0; y < 3; y++) {
            ends.setHex(new Coords(1, y), new Hex(0, "fluff:9:0", ""));
            ends.setHex(new Coords(3, y), new Hex(0, "fluff:9:0", ""));
        }
        for (int x = 1; x <= 3; x++) { ends.setHex(new Coords(x, 3), new Hex(0, "fluff:4:6", "")); }
        BoardSceneryLayouts.convert(ends);
        for (int x = 1; x <= 3; x++) {
            assertEquals(6, ends.getHex(x, 3).getDecorations().size(), "the pillars stay, column " + x);
        }
        for (int x : new int[] { 1, 3 }) {
            var line = RailRoute.find(ends.getHex(x, 1), RailRoute.Kind.MAGLEV);
            assertFalse(ends.getHex(x, 1).containsTerrain(Terrains.BRIDGE));
            assertEquals(BoardDecoration.Placement.ground(), line.placement());
        }
    }

    @Test
    void aTrainStampedOnARouteRidesItsMarkerAndElsewhereStandsOnTheHex() {
        var session = new megamek.client.ui.boardeditor.BoardEditorSession(BoardEditorBlueprint.get());
        send(session, megamek.client.ui.boardeditor.BoardEditorSession.Action.ASSET, RailRoute.Kind.MAGLEV.asset);
        var route = new Coords(2, 2);
        // A route long enough for the train: a stamp places only the vehicles that fit on its track.
        session.pointer(route.translated(0), 0, 0, false); session.pointer(route, 0, 0, true);
        session.pointer(route.translated(3), 0, 0, true); session.finishStroke();
        var marker = RailRoute.find(session.board().getHex(route), RailRoute.Kind.MAGLEV);
        assertEquals(BoardDecoration.Placement.on("bridge", 0), marker.placement(), "A painted route rides its new bridge");
        assertEquals(1, session.board().getHex(route).terrainLevel(Terrains.BRIDGE_ELEV));
        send(session, megamek.client.ui.boardeditor.BoardEditorSession.Action.ASSET, "stamp/maglev-train");
        session.pointer(route, 0, 0, false); session.finishStroke();
        var train = session.board().getHex(route).getDecorations().stream().filter(object -> !RailRoute.isRoute(object)).toList();
        assertEquals(List.of(WAGON, CAB, "scenery/maglev/coupler"), train.stream().map(BoardDecoration::asset).toList());
        assertTrue(train.stream().allMatch(object -> object.placement().equals(marker.placement())));
        var plain = new Coords(5, 5);
        session.pointer(plain, 0, 0, false); session.finishStroke();
        assertTrue(session.board().getHex(plain).getDecorations().stream()
              .allMatch(object -> object.placement().equals(BoardDecoration.Placement.ground())));
    }

    @Test
    void aRouteOnASupportWithoutItsHeightFallsBackToTheGround() {
        // Loadable but invalid legacy hexes: a bridge without bridge_elev must not abort the import.
        var deck = new Hex(0, "bridge:1:9;bridge_cf:40", "");
        assertEquals(BoardDecoration.Placement.ground(), BoardSceneryLayouts.routePlacement(deck));
        assertEquals(BoardDecoration.Placement.absolute(2),
              BoardSceneryLayouts.routePlacement(new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2", "")));
    }

    private static void send(megamek.client.ui.boardeditor.BoardEditorSession session,
          megamek.client.ui.boardeditor.BoardEditorSession.Action action, String value) {
        session.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(action, "", value), null);
    }

    @Test
    void realBoardsImportParallelRoutesApartAndThreeHexTrainsInOrder() throws Exception {
        // 86x51 Rail Crossing: parallel NW/SE lines and stations, side by side.
        Board crossing = corpus("unofficial/EVS/86x51 Rail Crossing.board");
        BoardSceneryLayouts.convert(crossing);
        int joined = 0, apart = 0;
        for (int x = 0; x < crossing.getWidth(); x++) {
            for (int y = 0; y < crossing.getHeight(); y++) {
                var route = RailRoute.find(crossing.getHex(x, y), RailRoute.Kind.MAGLEV);
                if (route == null) { continue; }
                assertEquals(36, route.connections());
                for (int direction = 0; direction < 6; direction++) {
                    var neighbour = RailRoute.find(crossing.getHex(new Coords(x, y).translated(direction)), RailRoute.Kind.MAGLEV);
                    if (neighbour == null) { continue; }
                    boolean axis = direction == 2 || direction == 5;
                    assertEquals(axis, RailRoute.joined(route, direction, neighbour), x + "," + y + " towards " + direction);
                    if (axis) { joined++; } else { apart++; }
                }
            }
        }
        assertTrue(joined > 100 && apart > 10, "Both joined lines and adjacent parallel lines: " + joined + " / " + apart);

        // 90x41 Whats Done Behind: a N/S train (maglevtrain1, 2, 2, 3) and a NE/SW one (maglevtrain4, 5, 5, 5, 6).
        Board legacy = corpus("unofficial/Derv_Maps/90x41 Whats Done Behind.board");
        Board board = corpus("unofficial/Derv_Maps/90x41 Whats Done Behind.board");
        BoardSceneryLayouts.convert(board);
        int trains = 0;
        for (int x = 0; x < legacy.getWidth(); x++) {
            for (int y = 0; y < legacy.getHeight(); y++) {
                Coords at = new Coords(x, y);
                int code = legacy.getHex(at).terrainLevel(Terrains.FLUFF);
                if (code != 9 || !legacy.getHex(at).containsTerrain(Terrains.FLUFF)) { continue; }
                int art = legacy.getHex(at).getTerrain(Terrains.FLUFF).getExits();
                // Each train's end continues into a middle hex along its route: front, middle and tail join.
                int inward = switch (art) { case 6 -> 3; case 8 -> 0; case 9 -> 1; case 11 -> 4; default -> -1; };
                if (inward < 0) { continue; }
                trains++;
                Coords next = at.translated(inward);
                assertEquals(art <= 8 ? 7 : 10, legacy.getHex(next).getTerrain(Terrains.FLUFF).getExits(), at + " continues into a middle hex");
                assertTrue(RailRoute.joined(RailRoute.find(board.getHex(at), RailRoute.Kind.MAGLEV), inward, RailRoute.find(board.getHex(next), RailRoute.Kind.MAGLEV)));
                var cab = board.getHex(at).getDecorations().stream().filter(object -> object.asset().equals(CAB)).findFirst();
                assertEquals(art == 6 || art == 9, cab.isPresent(), at + ": only the front hex has the cab");
                // The nose points away from the train.
                cab.ifPresent(object -> assertTrue(object.x() * dx(inward) + object.y() * dy(inward) < 0, at + " nose"));
            }
        }
        assertEquals(4, trains);
    }

    private static Board corpus(String name) throws Exception {
        return BoardFile.read(Configuration.boardsDir().toPath().resolve(name));
    }

    /** Hex-width and hex-height units towards a direction, as object positions use. */
    private static double dx(int direction) { return new double[] { 0, .75, .75, 0, -.75, -.75 }[direction]; }

    private static double dy(int direction) { return new double[] { 1, .5, -.5, -1, -.5, .5 }[direction]; }

    /** The ids of the palette assets in the section at {@code path}. */
    private static Set<String> section(BoardEditorBlueprint blueprint, String path) {
        return blueprint.assets().stream().filter(asset -> asset.sections().contains(path))
              .map(BoardEditorBlueprint.Asset::id).collect(Collectors.toSet());
    }

    private static long count(List<BoardSceneryLayouts.Component> rows, String asset) {
        return rows.stream().filter(row -> row.asset().equals(asset)).count();
    }

    private static BoardSceneryLayouts.Component piece(String sprite, String asset) {
        return BoardSceneryLayouts.layout("scenery/fluff/maglev" + sprite).components().stream()
              .filter(row -> row.asset().equals(asset)).findFirst().orElseThrow();
    }

    private static float low(String asset) { return bounds(asset)[0]; }

    private static float[] bounds(String asset) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        return bounds(RigidGlb.loadLods(new FileHandle(root.resolve(asset + ".glb").toFile()), root).getFirst(), RigidGlb.STRIDE);
    }

    /** The lowest and highest Z of a mesh with {@code stride} floats per vertex, in model pixels. */
    private static float[] bounds(ModelData model, int stride) {
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (int at = 2; at < mesh.vertices.length; at += stride) {
                low = Math.min(low, mesh.vertices[at]);
                high = Math.max(high, mesh.vertices[at]);
            }
        }
        return new float[] { low, high };
    }
}
