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
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.MaglevRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Legacy maglev decodes to route markers and separate pieces measured from its sprites; the palette keeps five entries. */
class BoardSceneryMaglevTest {
    private static final String GROUP = "Maglev and wagons";
    private static final String WAGON = "scenery/maglev/wagon", CAB = "scenery/maglev/cab", PLATFORM = "scenery/maglev/platform";

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
    void thePaletteKeepsTheRoutePlatformWagonCabAndTrainStampWithoutDuplicateMeshes() {
        var blueprint = BoardEditorBlueprint.get();
        assertEquals(Set.of(MaglevRoute.ASSET, PLATFORM, WAGON, CAB, "stamp/maglev-train"), blueprint.assets().stream()
              .filter(asset -> asset.group().equals(GROUP) && asset.palette()).map(BoardEditorBlueprint.Asset::id)
              .collect(Collectors.toSet()));
        assertTrue(blueprint.assets().stream().filter(asset -> asset.group().equals(GROUP)).allMatch(asset -> asset.snap() == null));
        assertFalse(blueprint.asset("scenery/maglev/coupler").palette(), "The coupler comes with the train stamp");
        var stamp = blueprint.asset("stamp/maglev-train");
        assertEquals("scenery/maglev/train", stamp.layout());
        assertTrue(Files.isRegularFile(Configuration.dataDir().toPath().resolve(stamp.thumbnail())), "Stamps use static thumbnails");
        var train = BoardSceneryLayouts.layout(stamp.layout()).components();
        assertEquals(List.of(WAGON, CAB, "scenery/maglev/coupler"), train.stream().map(BoardSceneryLayouts.Component::asset).toList());
        assertTrue(train.stream().allMatch(row -> row.z() == 0));

        // The vehicles ride half a pixel into the route's 4 px rail; the platform and route stand on the ground.
        assertEquals(0, low(MaglevRoute.ASSET), 1e-4);
        assertEquals(4, high(MaglevRoute.ASSET), 1e-4);
        assertEquals(0, low(PLATFORM), 1e-4);
        assertEquals(3.5, low(WAGON), 1e-4);
        assertEquals(3.5, low(CAB), 1e-4);
        assertEquals(4, low("scenery/maglev/coupler"), 1e-4);

        var root = Configuration.dataDir().toPath().resolve("models/board/scenery");
        assertFalse(Files.exists(root.resolve("maglev/track.glb")), "The route marker is the only rail mesh");
        assertFalse(Files.exists(root.resolve("maglev/reference")), "No baked legacy maglev composite remains");
        for (String name : SPRITES.keySet()) { assertFalse(Files.exists(root.resolve("fluff/maglev" + name + ".glb")), name); }
    }

    @Test
    void importPlacesMarkersOutsideTheGroupsAndThePiecesRideTheRoute() {
        Board board = Board.createEmptyBoard(6, 1);
        String[] hexes = { "fluff:9:7", "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:9:7", "water:1;fluff:9:7",
              "heavy_industrial:2;fluff:9:7", "building:2;bldg_elev:2;bldg_cf:50;fluff:9:3", "fluff:9:5" };
        for (int x = 0; x < hexes.length; x++) { board.setHex(new Coords(x, 0), new Hex(1, hexes[x], "")); }
        BoardSceneryLayouts.importBoard(board);
        List<BoardDecoration.Placement> expected = List.of(BoardDecoration.Placement.ground(), BoardDecoration.Placement.absolute(2),
              BoardDecoration.Placement.absolute(1), BoardDecoration.Placement.absolute(3), BoardDecoration.Placement.absolute(3),
              BoardDecoration.Placement.ground());
        for (int x = 0; x < hexes.length; x++) {
            Hex hex = board.getHex(x, 0);
            assertFalse(hex.containsTerrain(Terrains.FLUFF), hexes[x]);
            var route = MaglevRoute.find(hex);
            assertNotNull(route, hexes[x]);
            assertNull(route.group(), "A route marker is never a group member");
            var pieces = hex.getDecorations().stream().filter(object -> !MaglevRoute.isRoute(object)).toList();
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

    @Test
    void aTrainStampedOnARouteRidesItsMarkerAndElsewhereStandsOnTheHex() {
        var session = new megamek.client.ui.boardeditor.BoardEditorSession(BoardEditorBlueprint.get(), BoardSceneryLayouts.DECODER);
        send(session, megamek.client.ui.boardeditor.BoardEditorSession.Action.ASSET, MaglevRoute.ASSET);
        var route = new Coords(2, 2);
        session.pointer(route, 0, 0, false); session.finishStroke();
        var marker = MaglevRoute.find(session.board().getHex(route));
        assertEquals(BoardDecoration.Placement.surface("ground", "top", 1), marker.placement(), "A painted route rides one level up");
        send(session, megamek.client.ui.boardeditor.BoardEditorSession.Action.ASSET, "stamp/maglev-train");
        session.pointer(route, 0, 0, false); session.finishStroke();
        var train = session.board().getHex(route).getDecorations().stream().filter(object -> !MaglevRoute.isRoute(object)).toList();
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
        BoardSceneryLayouts.importBoard(crossing);
        int joined = 0, apart = 0;
        for (int x = 0; x < crossing.getWidth(); x++) {
            for (int y = 0; y < crossing.getHeight(); y++) {
                var route = MaglevRoute.find(crossing.getHex(x, y));
                if (route == null) { continue; }
                assertEquals(36, route.connections());
                for (int direction = 0; direction < 6; direction++) {
                    var neighbour = MaglevRoute.find(crossing.getHex(new Coords(x, y).translated(direction)));
                    if (neighbour == null) { continue; }
                    boolean axis = direction == 2 || direction == 5;
                    assertEquals(axis, MaglevRoute.joined(route, direction, neighbour), x + "," + y + " towards " + direction);
                    if (axis) { joined++; } else { apart++; }
                }
            }
        }
        assertTrue(joined > 100 && apart > 10, "Both joined lines and adjacent parallel lines: " + joined + " / " + apart);

        // 90x41 Whats Done Behind: a N/S train (maglevtrain1, 2, 2, 3) and a NE/SW one (maglevtrain4, 5, 5, 5, 6).
        Board legacy = corpus("unofficial/Derv_Maps/90x41 Whats Done Behind.board");
        Board board = corpus("unofficial/Derv_Maps/90x41 Whats Done Behind.board");
        BoardSceneryLayouts.importBoard(board);
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
                assertTrue(MaglevRoute.joined(MaglevRoute.find(board.getHex(at)), inward, MaglevRoute.find(board.getHex(next))));
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

    private static long count(List<BoardSceneryLayouts.Component> rows, String asset) {
        return rows.stream().filter(row -> row.asset().equals(asset)).count();
    }

    private static BoardSceneryLayouts.Component piece(String sprite, String asset) {
        return BoardSceneryLayouts.layout("scenery/fluff/maglev" + sprite).components().stream()
              .filter(row -> row.asset().equals(asset)).findFirst().orElseThrow();
    }

    private static float low(String asset) { return bounds(asset)[0]; }

    private static float high(String asset) { return bounds(asset)[1]; }

    /** The lowest and highest Z of a mesh, in model pixels. */
    private static float[] bounds(String asset) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        var model = RigidGlb.loadLods(new FileHandle(root.resolve(asset + ".glb").toFile()), root).getFirst();
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (int at = 2; at < mesh.vertices.length; at += RigidGlb.STRIDE) {
                low = Math.min(low, mesh.vertices[at]);
                high = Math.max(high, mesh.vertices[at]);
            }
        }
        return new float[] { low, high };
    }
}
