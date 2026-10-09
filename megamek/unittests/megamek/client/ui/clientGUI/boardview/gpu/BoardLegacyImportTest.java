/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Editor import of legacy scenery tokens into objects and groups, against hand-checked decode rows. */
class BoardLegacyImportTest {
    private static final String PICNIC = "fluff:93:8";
    private static final Coords TABLES = new Coords(0, 0), ROOF = new Coords(1, 0), BRIDGE = new Coords(2, 0),
          ROAD = new Coords(3, 0), MAGLEV = new Coords(4, 0), SEAPORT = new Coords(5, 0), INVALID = new Coords(6, 0),
          ICE = new Coords(7, 0), TANK = new Coords(8, 0);

    private static Board fixture() {
        Board board = Board.createEmptyBoard(9, 1);
        board.setHex(TABLES, new Hex(0, PICNIC, ""));
        board.setHex(ROOF, new Hex(0, "building:2;bldg_elev:2;bldg_cf:50;fluff:6:14", ""));
        board.setHex(BRIDGE, new Hex(0, "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:4:06", ""));
        board.setHex(ROAD, new Hex(0, "road:2:09", ""));
        board.setHex(MAGLEV, new Hex(0, "fluff:9:00", ""));
        board.setHex(SEAPORT, new Hex(0, "woods:1;foliage_elev:2;fluff:80:01", ""));
        board.setHex(INVALID, new Hex(0, "fluff:9:36", ""));
        board.setHex(ICE, new Hex(0, "water:2;ice:1;fluff:8:00", ""));
        board.setHex(TANK, new Hex(0, "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100;fluff:2:03", ""));
        return board;
    }

    @Test
    void pillarArtOnABridgeIsTheBridgesToggleInImportAndInTheLegacyRender() throws Exception {
        // An Oanhu span over water between two roads (pillars5 on SE-NW decks), a raised dry deck, a deck under ice,
        // a deck at ground level (Sirius V), pillars on plain ground, a road with trees passing under a deck (Koryo)
        // and a car park on a deck.
        Coords west = new Coords(1, 1), east = west.translated(2), raised = new Coords(5, 1), iced = new Coords(6, 1),
              level = new Coords(7, 1), plain = new Coords(8, 1), trees = new Coords(9, 1), parked = new Coords(10, 1);
        Map<Coords, String> terrain = new java.util.LinkedHashMap<>();
        terrain.put(west.translated(5), "road:1:36");
        terrain.put(west, "water:1;bridge:1:36;bridge_cf:40;bridge_elev:0;fluff:4:10");
        terrain.put(east, "water:1;bridge:1:36;bridge_cf:40;bridge_elev:0;fluff:4:10");
        terrain.put(east.translated(2), "road:1:36");
        terrain.put(raised, "bridge:1:09;bridge_cf:40;bridge_elev:2;fluff:4:06");
        terrain.put(iced, "water:2;ice:1;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:4:06");
        terrain.put(level, "bridge:1:09;bridge_cf:40;bridge_elev:0;fluff:4:06");
        terrain.put(plain, "fluff:4:06");
        terrain.put(trees, "bridge:1:40;bridge_cf:40;road:2:18;bridge_elev:4");
        terrain.put(parked, "bridge:1:09;bridge_cf:40;bridge_elev:2;fluff:5:02");
        Board legacy = Board.createEmptyBoard(12, 4), board = Board.createEmptyBoard(12, 4);
        terrain.forEach((at, text) -> { legacy.setHex(at, new Hex(0, text, "")); board.setHex(at, new Hex(0, text, "")); });
        BoardSceneryLayouts.importBoard(board);

        List<Coords> pillared = List.of(west, east, raised, iced, level);
        for (Coords at : pillared) {
            Hex hex = board.getHex(at);
            assertTrue(HexAppearance.pillars(hex.getAppearance()), at + ": the toggle is on");
            assertFalse(hex.containsTerrain(Terrains.FLUFF), at + ": the token is decoded");
            assertTrue(hex.getDecorations().isEmpty(), at + ": no posts");
        }
        var posts = board.getHex(plain).getDecorations();
        assertEquals(6, posts.size(), "Pillars on plain ground stay pillars");
        assertTrue(posts.stream().allMatch(o -> o.asset().equals(BoardSceneryLayouts.PILLAR)
              && o.placement().equals(BoardDecoration.Placement.ground()) && o.stretch().equals(BoardDecoration.Stretch.NONE)));
        assertEquals(1, posts.stream().map(BoardDecoration::group).distinct().count());
        assertFalse(HexAppearance.pillars(board.getHex(plain).getAppearance()));
        // The trees of the road beneath a deck stand on the ground; a car park's cars stand on the deck.
        var roadside = board.getHex(trees).getDecorations();
        assertFalse(roadside.isEmpty());
        assertTrue(roadside.stream().allMatch(o -> o.placement().equals(BoardDecoration.Placement.ground())), roadside.toString());
        var cars = board.getHex(parked).getDecorations().stream().filter(o -> o.asset().equals("scenery/vehicles/car")).toList();
        assertFalse(cars.isEmpty());
        assertTrue(cars.stream().allMatch(o -> o.placement().receiver().terrain().equals("bridge")));

        // The legacy render decodes the same art the same way: the toggle, and posts only on plain ground.
        try (var artwork = new BoardArtwork()) {
            var pool = new BoardScene.PixelPool();
            for (Coords at : terrain.keySet()) {
                var tile = BoardScene.captureTile(legacy.getHex(at), artwork.capture(legacy, at, true), null, pool, legacy::getHex);
                long drawn = tile.features().stream().filter(f -> f.asset().equals(BoardSceneryLayouts.PILLAR)).count();
                assertEquals(pillared.contains(at), HexAppearance.pillars(tile.appearance()), at.toString());
                assertEquals(at.equals(plain) ? 6 : 0, drawn, at + ": legacy posts");
            }
        }
    }

    @Test
    void picnicTablesBecomeIndividualTablesAndTreesInOneGroup() {
        Board legacy = fixture();
        Board board = fixture();
        BoardSceneryLayouts.importBoard(board);
        assertTrue(board.isNativeFormat());

        Hex tables = board.getHex(TABLES);
        assertFalse(tables.containsTerrain(Terrains.FLUFF), "The decoded token is removed");
        List<BoardDecoration> objects = tables.getDecorations();
        assertEquals(17, objects.size());
        assertEquals(15, objects.stream().filter(o -> o.asset().equals("scenery/parks/picnic-table")).count());
        assertEquals(Set.of("L0_0_g0"), objects.stream().map(BoardDecoration::group).collect(java.util.stream.Collectors.toSet()));
        for (int i = 0; i < objects.size(); i++) { assertEquals("L0_0_" + i, objects.get(i).id()); }
        assertEquals(BoardDecoration.Placement.ground(), objects.getFirst().placement());

        // The trees are the species and headings the legacy render shows for the same hex.
        List<BoardScene.Feature> legacyTrees;
        try (var artwork = new BoardArtwork()) {
            var image = artwork.capture(legacy, TABLES, true);
            legacyTrees = BoardFeatures.capture(legacy.getHex(TABLES), TABLES, Map.of(), Set.of(), legacy::getHex,
                  image.scenery()).stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
        }
        var trees = objects.stream().filter(o -> !o.asset().startsWith("scenery/")).toList();
        assertEquals(2, legacyTrees.size());
        for (int i = 0; i < 2; i++) {
            assertEquals(legacyTrees.get(i).asset(), trees.get(i).asset());
            assertEquals(legacyTrees.get(i).rotation(), trees.get(i).rotation(), 1e-4);
            assertEquals(legacyTrees.get(i).x(), trees.get(i).x() * BoardGeometry.TILE_WIDTH, 1e-3);
            assertEquals(legacyTrees.get(i).y(), trees.get(i).y() * BoardGeometry.TILE_HEIGHT, 1e-3);
        }
    }

    @Test
    void rowsAreHandCheckedAndOnlyDecodableTokensLeave() throws Exception {
        Board board = fixture();
        BoardSceneryLayouts.importBoard(board);

        // bevel3 is the roof bevel turned by -120 degrees about the hex centre (generator row, model px).
        Hex roof = board.getHex(ROOF);
        assertFalse(roof.containsTerrain(Terrains.FLUFF));
        BoardDecoration bevel = roof.getDecorations().getFirst();
        assertEquals(1, roof.getDecorations().size());
        assertEquals("scenery/roofs/bevel", bevel.asset());
        assertEquals(-19.486 / 84, bevel.x(), 1e-5);
        assertEquals(11.25 / 72, bevel.y(), 1e-5);
        assertEquals(-120, bevel.rotation(), 1e-4);
        assertNull(bevel.group(), "One object forms no group");
        assertEquals(BoardDecoration.Placement.surface("building", "roof", 0), bevel.placement());

        // pillars1 on the bridge: the bridge's Pillars toggle, no objects (the round trip below keeps it).
        Hex bridge = board.getHex(BRIDGE);
        assertTrue(bridge.getDecorations().isEmpty());
        assertFalse(bridge.containsTerrain(Terrains.FLUFF));
        assertEquals(HexAppearance.PILLARS, bridge.getAppearance().get("bridge"));

        // Road level 2 keeps its rule terrain and becomes the native Alley; its trees become objects.
        Hex road = board.getHex(ROAD);
        assertEquals(2, road.terrainLevel(Terrains.ROAD));
        assertEquals(6, road.getDecorations().size());
        assertTrue(road.getDecorations().stream().allMatch(o -> BoardFeatures.parkSpecies().contains(o.asset())));

        // A seaport yard is one object; its woods stay for the rules, drawn without trees as the legacy art showed.
        Hex seaport = board.getHex(SEAPORT);
        assertFalse(seaport.containsTerrain(Terrains.FLUFF));
        assertEquals(1, seaport.terrainLevel(Terrains.WOODS));
        assertEquals(List.of("scenery/seaport/containers-n-s-01"), seaport.getDecorations().stream().map(BoardDecoration::asset).toList());
        assertNull(seaport.getDecorations().getFirst().group());
        assertEquals(BoardFeatures.NO_TREES, seaport.getAppearance().get("vegetation").variant());
        assertTrue(BoardFeatures.capture(seaport, SEAPORT, Map.of(), Set.of(), board::getHex).stream()
              .noneMatch(f -> f.kind() == BoardScene.FeatureKind.TREE), "No woods trees grow in the container yard");

        // Ice and fuel tanks carry what stands on them.
        assertEquals(BoardDecoration.Placement.surface("ice", "top", 0), board.getHex(ICE).getDecorations().getFirst().placement());
        assertEquals(BoardDecoration.Placement.surface("fuelTank", "top", 0),
              board.getHex(TANK).getDecorations().getFirst().placement());

        // A maglev track becomes one ungrouped N/S route marker; a code without artwork stays as it was.
        assertFalse(board.getHex(MAGLEV).containsTerrain(Terrains.FLUFF));
        var route = board.getHex(MAGLEV).getDecorations();
        assertEquals(1, route.size());
        assertTrue(megamek.common.board.MaglevRoute.isRoute(route.getFirst()));
        assertEquals(9, route.getFirst().connections());
        assertNull(route.getFirst().group());
        assertEquals(9, board.getHex(INVALID).terrainLevel(Terrains.FLUFF));

        // The imported document round-trips through .board2.
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        Board reread = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
        for (int x = 0; x < board.getWidth(); x++) {
            assertEquals(BoardFile.hexNode(board.getHex(x, 0)), BoardFile.hexNode(reread.getHex(x, 0)));
        }
    }

    @Test
    void herdsImportAsTheAnimalsOfTheHerdTheTilesetChoseForEachHex() {
        Board legacy = Board.createEmptyBoard(8, 1), board = Board.createEmptyBoard(8, 1);
        for (int x = 0; x < 8; x++) {
            legacy.setHex(new Coords(x, 0), new Hex(0, "fluff:13:01", ""));
            board.setHex(new Coords(x, 0), new Hex(0, "fluff:13:01", ""));
        }
        BoardSceneryLayouts.importBoard(board);
        Set<String> herds = new java.util.HashSet<>();
        try (var artwork = new BoardArtwork()) {
            for (int x = 0; x < 8; x++) {
                Coords at = new Coords(x, 0);
                List<String> chosen = artwork.scenery(legacy, at).models();
                assertEquals(1, chosen.size(), at.toString());
                herds.add(chosen.getFirst());
                var rows = BoardSceneryLayouts.layout(chosen.getFirst()).components();
                var animals = board.getHex(at).getDecorations();
                assertFalse(board.getHex(at).containsTerrain(Terrains.FLUFF));
                assertEquals(rows.size(), animals.size(), chosen.getFirst());
                assertEquals(Set.of("L" + x + "_0_g0"), animals.stream().map(BoardDecoration::group)
                      .collect(java.util.stream.Collectors.toSet()), "One group per herd");
                for (int i = 0; i < rows.size(); i++) {
                    assertEquals(rows.get(i).asset(), animals.get(i).asset());
                    assertEquals(rows.get(i).x() / BoardGeometry.TILE_WIDTH, animals.get(i).x(), 1e-6);
                    assertEquals(rows.get(i).rotation(), animals.get(i).rotation(), 1e-4);
                }
            }
        }
        assertTrue(herds.size() > 1, "The tileset picks the cattle herd by position: " + herds);
    }

    @Test
    void emblemSetsBecomeOneFullEmblemAndOneHexVariantsKeepTheirTurnAndScale() throws Exception {
        var file = megamek.common.Configuration.boardsDir().toPath().resolve("Templates/LandingPadsFactionSymbols.board");
        Board legacy = BoardFile.read(file), board = BoardFile.read(file);
        assertEquals(List.of(), BoardSceneryLayouts.importBoard(board), "Every set on the template is complete");
        Set<String> emblems = new java.util.HashSet<>();
        int sets = 0, singles = 0;
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                var before = legacy.getHex(x, y).getTerrain(Terrains.FLUFF);
                if (before == null || before.getLevel() < 50 || before.getLevel() > 62) { continue; }
                assertFalse(board.getHex(x, y).containsTerrain(Terrains.FLUFF), "Every piece is decoded: " + x + "," + y);
                for (var object : board.getHex(x, y).getDecorations()) {
                    assertEquals("decal", object.kind());
                    assertTrue(object.asset().startsWith("decal/emblems/"), object.asset());
                    if (object.scale() == 1) {
                        // One emblem, on the centre piece's hex, spanning its six neighbours.
                        sets++;
                        emblems.add(object.asset());
                        assertEquals(10, before.getExits(), "The emblem sits on the centre hex");
                        assertFalse(object.clipToHex());
                        assertEquals(0, object.x()); assertEquals(0, object.y()); assertEquals(0, object.rotation());
                    } else {
                        singles++;
                        int variant = before.getExits();
                        assertTrue(variant <= 6 && object.clipToHex() && object.scale() < .51, object.toString());
                        assertEquals(variant % 3 == 1 ? 0 : variant % 3 == 2 ? -30 : 30, object.rotation(), "Variant " + variant);
                    }
                }
            }
        }
        assertEquals(13, sets);
        assertEquals(13, emblems.size(), "One set of each emblem");
        assertEquals(72, singles);
    }

    @Test
    void anIncompleteSetStillGetsItsFullEmblemAtTheInferredCentreAndAnIssue() {
        // Only the N and S pieces of a Red Cross set: the centre hex has no piece of its own.
        Board board = Board.createEmptyBoard(3, 3);
        board.setHex(new Coords(1, 0), new Hex(0, "fluff:62:7", ""));
        board.setHex(new Coords(1, 2), new Hex(0, "pavement:1;fluff:62:13", ""));
        var issues = BoardSceneryLayouts.importBoard(board);
        var emblem = board.getHex(new Coords(1, 1)).getDecorations();
        assertEquals(1, emblem.size());
        assertEquals("decal/emblems/red-cross", emblem.getFirst().asset());
        assertEquals(1, emblem.getFirst().scale());
        assertEquals(List.of(new BoardEditorSession.Issue(new Coords(1, 1), emblem.getFirst().id(),
              "Emblem set with 2 of 7 pieces: the full emblem is placed")), issues);
        assertTrue(board.getHex(new Coords(1, 0)).getDecorations().isEmpty());
        assertFalse(board.getHex(new Coords(1, 2)).containsTerrain(Terrains.FLUFF));
        assertTrue(board.getHex(new Coords(1, 2)).containsTerrain(Terrains.PAVEMENT), "Rule terrain stays");

        // A set cut by the board edge: its centre lies north of the board, so its piece's hex owns it at that offset.
        Board cut = Board.createEmptyBoard(3, 3);
        cut.setHex(new Coords(1, 0), new Hex(0, "fluff:62:13", ""));
        assertEquals(1, BoardSceneryLayouts.importBoard(cut).size());
        var offset = cut.getHex(new Coords(1, 0)).getDecorations().getFirst();
        assertEquals(0, offset.x(), 1e-6);
        assertEquals(1, offset.y(), 1e-6, "One hex height north");
    }

    @Test
    void carParksBecomeStandardDecalsWithEachCarInItsOwnBayKeepingItsPaint() {
        String[] codes = { "00", "01", "02", "03", "04", "05", "06", "07", "98", "99" };
        Board board = Board.createEmptyBoard(codes.length, 1), legacy = Board.createEmptyBoard(codes.length, 1);
        for (int x = 0; x < codes.length; x++) {
            board.setHex(new Coords(x, 0), new Hex(0, "pavement:1;fluff:5:" + codes[x], ""));
            legacy.setHex(new Coords(x, 0), new Hex(0, "pavement:1;fluff:5:" + codes[x], ""));
        }
        BoardSceneryLayouts.importBoard(board);
        try (var artwork = new BoardArtwork()) {
            for (int x = 0; x < codes.length; x++) {
                var objects = board.getHex(x, 0).getDecorations();
                var plates = objects.stream().filter(o -> o.kind().equals("decal")).toList();
                var cars = objects.stream().filter(o -> o.asset().equals("scenery/vehicles/car")).toList();
                assertTrue(plates.size() == 1 || plates.size() == 2, codes[x]);
                assertEquals(1, objects.stream().map(BoardDecoration::group).distinct().count(), "One car-park group");
                // 00-07 get their lane as a road and stand beside it; 98/99 have no lane and stand back to back.
                assertRows(plates, x < 8, codes[x]);
                // The cars keep their paint: the same colours as the legacy key's car rows.
                var rows = BoardSceneryLayouts.layout(artwork.scenery(legacy, new Coords(x, 0)).models().getFirst()).components();
                assertEquals(rows.stream().filter(r -> r.asset().equals("scenery/vehicles/car")).map(r -> r.colours().slots()).sorted(
                      java.util.Comparator.comparing(Object::toString)).toList(), cars.stream().map(c -> c.colours().slots())
                      .sorted(java.util.Comparator.comparing(Object::toString)).toList());
                for (var car : cars) {
                    // Inside one of the decals' bay rows (model px, in that decal's frame), and no two cars share a bay.
                    double px = car.x() * BoardGeometry.TILE_WIDTH, py = car.y() * BoardGeometry.TILE_HEIGHT;
                    assertTrue(plates.stream().anyMatch(plate -> {
                        var size = megamek.common.board.BoardDecalArt.footprint(plate.asset());
                        double turn = Math.toRadians(-plate.rotation());
                        double dx = px - plate.x() * BoardGeometry.TILE_WIDTH, dy = py - plate.y() * BoardGeometry.TILE_HEIGHT;
                        double u = dx * Math.cos(turn) - dy * Math.sin(turn), v = dx * Math.sin(turn) + dy * Math.cos(turn);
                        return Math.abs(u) < size.width() / 2 && Math.abs(v) < size.height() / 2;
                    }), codes[x] + " " + car);
                    // On a slot where its plate paints it: a mirrored plate's art, flipped along the row, keeps its bays.
                    assertTrue(plates.stream().anyMatch(plate -> {
                        var size = megamek.common.board.BoardDecalArt.footprint(plate.asset());
                        var uv = BoardDecals.paintUv(plate);
                        double dx = (car.x() - plate.x()) * BoardGeometry.width(), dy = (car.y() - plate.y()) * BoardGeometry.height();
                        return size.slots().stream().anyMatch(slot -> Math.abs(uv.u(dx, dy) - (.5 + slot.x() / size.width())) < 1e-3
                              && Math.abs(uv.v(dx, dy) - (.5 - slot.y() / size.height())) < 1e-3);
                    }), codes[x] + " on a painted slot: " + car);
                    for (var other : cars) {
                        if (other != car) {
                            assertTrue(Math.hypot(px - other.x() * BoardGeometry.TILE_WIDTH,
                                  py - other.y() * BoardGeometry.TILE_HEIGHT) > 4.9, codes[x] + ": one car per bay");
                        }
                    }
                }
            }
        }
    }

    /**
     * A car park's two decal rows (model px): beside a road, each row's open side on the road's fading asphalt edge
     * ({@link BoardRoad.Kind#halfWidth} less {@link GpuRoads#FEATHER}), so the gray verge is hidden and the carriageway
     * clear; without one, back to back and mirrored, their closed sides meeting on the line through the hex centre.
     * Either way the rows lie on opposite sides and inside the hex, so clipping cuts nothing.
     */
    private static void assertRows(List<BoardDecoration> plates, boolean road, String name) {
        assertEquals(2, plates.size(), name);
        for (var plate : plates) {
            var size = megamek.common.board.BoardDecalArt.footprint(plate.asset());
            double turn = Math.toRadians(plate.rotation()), half = size.height() * plate.scale() / 2;
            double x = plate.x() * BoardGeometry.TILE_WIDTH, y = plate.y() * BoardGeometry.TILE_HEIGHT;
            // How far the row's centre lies along its back direction (its +y, toward its closed side).
            double toward = -Math.sin(turn) * x + Math.cos(turn) * y;
            assertEquals(!road, plate.mirror(), name);
            if (road) {
                double edge = toward - half;
                assertTrue(edge >= BoardRoad.Kind.PAVED.halfWidth - GpuRoads.FEATHER && edge < BoardRoad.Kind.PAVED.halfWidth,
                      name + ": road-side edge " + edge);
            } else {
                assertEquals(0, toward + half, 1e-3, name + ": the closed side on the centre line");
            }
            for (int corner = 0; corner < 4; corner++) {
                double u = (corner % 3 == 0 ? -1 : 1) * size.width() * plate.scale() / 2, v = (corner < 2 ? -1 : 1) * half;
                double cx = x + u * Math.cos(turn) - v * Math.sin(turn), cy = y + u * Math.sin(turn) + v * Math.cos(turn);
                assertTrue(Math.abs(cy) <= 36 + 1e-6 && Math.abs(cx) <= 42 - 21 * Math.abs(cy) / 36 + 1e-6,
                      name + ": inside the hex at " + cx + ", " + cy);
            }
        }
        // The second row is the first turned half a turn about the hex centre.
        var a = plates.get(0);
        var b = plates.get(1);
        assertEquals(0, a.x() + b.x(), 1e-5, name);
        assertEquals(0, a.y() + b.y(), 1e-5, name);
        assertEquals(180, Math.abs(a.rotation() - b.rotation()) % 360, 1e-4, name);
    }

    /** A hex's ROAD as level, exits and whether its exits are written out, or "none". */
    private static String road(Hex hex) {
        var road = hex.getTerrain(Terrains.ROAD);
        return road == null ? "none"
              : road.getLevel() + ":" + road.getExits() + (road.hasExitsSpecified() ? " specified" : " automatic");
    }

    @Test
    void groundCarParksGetTheirLaneAsARoadAlongTheAisle() throws Exception {
        // cars_1..8, the laneless cars_2b/3b, and cars_3 on bare ground; each car park on its own hex.
        String[] terrain = { "pavement:1;fluff:5:00", "pavement:1;fluff:5:01", "pavement:1;fluff:5:02",
              "pavement:1;fluff:5:03", "pavement:1;fluff:5:04", "pavement:1;fluff:5:05", "pavement:1;fluff:5:06",
              "pavement:1;fluff:5:07", "pavement:1;fluff:5:98", "pavement:1;fluff:5:99", "fluff:5:02" };
        // The legacy render's lane per sprite: NE+SW 18, SE+NW 36, N+S 9; none for 2b/3b.
        int[] lanes = { 18, 36, 9, 18, 36, 9, 36, 9, 0, 0, 9 };
        Board board = Board.createEmptyBoard(2 * terrain.length + 1, 3);
        for (int i = 0; i < terrain.length; i++) { board.setHex(new Coords(1 + 2 * i, 1), new Hex(0, terrain[i], "")); }
        BoardSceneryLayouts.importBoard(board);
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        Board reread = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
        // The board's 84 x 72 hex puts the diagonal borders 0.26 degrees off the decals' 60-degree turns.
        double square = Math.sin(Math.toRadians(.5));
        for (int i = 0; i < terrain.length; i++) {
            Coords at = new Coords(1 + 2 * i, 1);
            Hex hex = board.getHex(at);
            String expected = lanes[i] == 0 ? "none" : "1:" + lanes[i] + " specified";
            assertEquals(expected, road(hex), terrain[i]);
            assertEquals(expected, road(reread.getHex(at)), terrain[i] + " after a .board2 round trip");
            var plates = hex.getDecorations().stream().filter(o -> o.kind().equals("decal")).toList();
            assertEquals(2, plates.size(), terrain[i]);
            // The two decals' centres lie across their lane: square to the road's course through each exit edge.
            double ax = (plates.get(1).x() - plates.get(0).x()) * BoardGeometry.TILE_WIDTH;
            double ay = (plates.get(1).y() - plates.get(0).y()) * BoardGeometry.TILE_HEIGHT;
            for (int direction = 0; direction < 6; direction++) {
                if ((lanes[i] & 1 << direction) == 0) { continue; }
                Coords next = at.translated(direction);
                double ex = (BoardGeometry.centerX(next) - BoardGeometry.centerX(at)) / BoardGeometry.hexScale() / 2;
                double ey = (BoardGeometry.centerY(next) - BoardGeometry.centerY(at)) / BoardGeometry.hexScale() / 2;
                double cos = (ax * ex + ay * ey) / Math.hypot(ax, ay) / Math.hypot(ex, ey);
                assertTrue(Math.abs(cos) < square, terrain[i] + ": the lane runs along the aisle, direction " + direction);
            }
        }
    }

    @Test
    void carParksOffTheGroundStayDecalsWithoutARoad() {
        // Car-park art on a roof, deck, industrial top, fuel tank, ice or water was decoration, and the renderer draws no
        // road on a liquid: none of them gets a road, and their rows stand back to back.
        String[] supports = { "building:2;bldg_elev:2;bldg_cf:50", "bridge:1:09;bridge_cf:40;bridge_elev:2",
              "heavy_industrial:2", "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100", "water:2;ice:1",
              "water:1", "hazardous_liquid:1", "magma:2" };
        Board board = Board.createEmptyBoard(2 * supports.length + 1, 3);
        for (int i = 0; i < supports.length; i++) {
            board.setHex(new Coords(1 + 2 * i, 1), new Hex(0, supports[i] + ";fluff:5:02", ""));
        }
        BoardSceneryLayouts.importBoard(board);
        for (int i = 0; i < supports.length; i++) {
            Hex hex = board.getHex(new Coords(1 + 2 * i, 1));
            assertEquals("none", road(hex), supports[i]);
            assertFalse(hex.containsTerrain(Terrains.FLUFF), supports[i]);
            assertRows(hex.getDecorations().stream().filter(o -> o.kind().equals("decal")).toList(), false, supports[i]);
        }
        assertTrue(board.getHex(new Coords(1, 1)).getDecorations().stream().filter(o -> o.kind().equals("decal"))
              .allMatch(o -> o.placement().receiver().terrain().equals("building")), "The roof carries the decals");
    }

    @Test
    void anAuthoredRoadOnACarParkIsKept() {
        // An automatic road that also exits NE, off the N-S lane, toward a road beside it; and a dead end to the south.
        // A road level the renderer does not draw (5) shows no road.
        Coords automatic = new Coords(1, 1), beside = automatic.translated(1), deadEnd = new Coords(5, 1),
              undrawn = new Coords(7, 1);
        Board board = Board.createEmptyBoard(9, 3);
        board.setHex(automatic, new Hex(0, "pavement:1;road:1;fluff:5:02", ""));
        board.setHex(beside, new Hex(0, "road:1", ""));
        board.setHex(deadEnd, new Hex(0, "road:1:08;fluff:5:02", ""));
        board.setHex(undrawn, new Hex(0, "road:5:09;fluff:5:02", ""));
        assertEquals("1:2 automatic", road(board.getHex(automatic)));
        List<String> before = List.of(road(board.getHex(automatic)), road(board.getHex(beside)), road(board.getHex(deadEnd)),
              road(board.getHex(undrawn)));
        BoardSceneryLayouts.importBoard(board);
        assertEquals(before, List.of(road(board.getHex(automatic)), road(board.getHex(beside)), road(board.getHex(deadEnd)),
              road(board.getHex(undrawn))));
        assertFalse(board.getHex(automatic).containsTerrain(Terrains.FLUFF));
        // The drawn roads run between their rows; the undrawn one leaves them back to back.
        for (Coords at : List.of(automatic, deadEnd, undrawn)) {
            assertRows(board.getHex(at).getDecorations().stream().filter(o -> o.kind().equals("decal")).toList(),
                  !at.equals(undrawn), at.toString());
        }
    }

    private static final Coords PARK = new Coords(2, 2);

    /** A cars_3 car park (lane N-S) without pavement at the centre of a 5 x 5 board, among the given hexes. */
    private static Board lane(Map<Coords, String> around) {
        Board board = Board.createEmptyBoard(5, 5);
        board.setHex(PARK, new Hex(0, "fluff:5:02", ""));
        around.forEach((at, terrain) -> board.setHex(at, new Hex(0, terrain, "")));
        return board;
    }

    @Test
    void theLaneJoinsOnlyRoadsThatPointAlongIt() {
        Coords north = PARK.translated(0), northEast = PARK.translated(1), southEast = PARK.translated(2),
              south = PARK.translated(3);
        // No neighbouring road, pavement all around with roads exiting to pavement: the lane only, no roundabout.
        Map<Coords, String> paved = new HashMap<>();
        for (int direction = 0; direction < 6; direction++) { paved.put(PARK.translated(direction), "pavement:1"); }
        Board board = lane(paved);
        assertTrue(board.getRoadsAutoExit());
        BoardSceneryLayouts.importBoard(board);
        assertEquals("1:9 specified", road(board.getHex(PARK)));

        // A dead end from the north: the two now point at each other, and the neighbour is unchanged.
        board = lane(Map.of(north, "road:1:08"));
        BoardSceneryLayouts.importBoard(board);
        assertEquals("1:9 specified", road(board.getHex(PARK)));
        assertEquals("1:8 specified", road(board.getHex(north)));

        // Roads at both ends join into one through road.
        board = lane(Map.of(north, "road:1:08", south, "road:1:01"));
        BoardSceneryLayouts.importBoard(board);
        assertEquals(List.of("1:9 specified", "1:8 specified", "1:1 specified"),
              List.of(road(board.getHex(PARK)), road(board.getHex(north)), road(board.getHex(south))));

        // A road pointing in off the lane still ends at the car park's edge, as the legacy lane left it.
        board = lane(Map.of(northEast, "road:1:16"));
        BoardSceneryLayouts.importBoard(board);
        assertEquals(List.of("1:9 specified", "1:16 specified"), List.of(road(board.getHex(PARK)), road(board.getHex(northEast))));

        // An automatic side road keeps the exits it loaded with, written out: no arm toward the car park.
        Coords beyond = southEast.translated(2);
        board = lane(Map.of(southEast, "road:1", beyond, "road:1"));
        assertEquals("1:4 automatic", road(board.getHex(southEast)));
        BoardSceneryLayouts.importBoard(board);
        assertEquals(List.of("1:9 specified", "1:4 specified", "1:32 automatic"),
              List.of(road(board.getHex(PARK)), road(board.getHex(southEast)), road(board.getHex(beyond))));

        // An automatic road on the lane gains its exit toward the car park and joins it.
        board = lane(Map.of(south, "road:1", south.translated(3), "road:1"));
        assertEquals("1:8 automatic", road(board.getHex(south)));
        BoardSceneryLayouts.importBoard(board);
        assertEquals(List.of("1:9 specified", "1:9 automatic"), List.of(road(board.getHex(PARK)), road(board.getHex(south))));

        // The same road beside a second car park, off that one's lane: it still joins the lane and grows no arm toward
        // the second car park, whether the scan meets that car park first (NW of the road) or last (NE).
        for (int side : new int[] { 5, 1 }) {
            Coords other = south.translated(side);
            board = lane(Map.of(south, "road:1", south.translated(3), "road:1", other, "fluff:5:02"));
            BoardSceneryLayouts.importBoard(board);
            assertEquals(List.of("1:9 specified", "1:9 specified", "1:9 specified"),
                  List.of(road(board.getHex(PARK)), road(board.getHex(south)), road(board.getHex(other))), "side " + side);
        }
    }

    @Test
    void treesStoreTheirBareSpeciesAndTakeTheirSnowFormOnSnowUnlessKeptBare() throws Exception {
        Board legacy = Board.createEmptyBoard(1, 1), imported = Board.createEmptyBoard(1, 1);
        legacy.setHex(TABLES, new Hex(0, "snow:1;" + PICNIC, ""));
        imported.setHex(TABLES, new Hex(0, "snow:1;" + PICNIC, ""));
        BoardSceneryLayouts.importBoard(imported);
        List<String> legacyTrees;
        try (var artwork = new BoardArtwork()) {
            legacyTrees = BoardFeatures.capture(legacy.getHex(TABLES), TABLES, Map.of(), Set.of(), legacy::getHex,
                        artwork.capture(legacy, TABLES, true).scenery()).stream()
                  .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).map(BoardScene.Feature::asset).toList();
        }
        var trees = imported.getHex(TABLES).getDecorations().stream().filter(o -> BoardFeatures.hasSnowForm(o.asset())).toList();
        assertEquals(2, legacyTrees.size());
        assertEquals(legacyTrees, trees.stream().map(o -> o.asset() + "-snow").toList(), "Trees store their bare species");
        assertEquals(legacyTrees, drawnTrees(imported.getHex(TABLES)), "On snow they draw as the legacy winter trees");

        // The inspector's Snow toggle keeps one tree bare on the snow; the choice is saved in .board2.
        var session = new BoardEditorSession(BoardEditorBlueprint.get(), BoardSceneryLayouts.DECODER);
        Coords at = new Coords(1, 1);
        Hex hex = imported.getHex(TABLES).duplicate();
        hex.setDecorations(hex.getDecorations().stream().map(o -> o.withGroup(null)).toList());
        session.board().setHex(at, hex);
        session.pointer(at, 0, 0, false);
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_OBJECT, "", trees.getFirst().id()), null);
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.OBJECT_VALUE, "bare", "true"), null);
        Hex edited = session.board().getHex(at);
        assertEquals(List.of(trees.getFirst().asset(), legacyTrees.get(1)), drawnTrees(edited));
        var text = new ByteArrayOutputStream();
        BoardFile.write(session.board(), text);
        assertEquals(edited.getDecorations(), BoardFile.readNative(text.toString(StandardCharsets.UTF_8)).getHex(at).getDecorations());
    }

    private static List<String> drawnTrees(Hex hex) {
        return BoardFeatures.capture(hex, TABLES, Map.of(), Set.of(), coords -> null).stream()
              .filter(f -> f.decoration() != null && BoardFeatures.hasSnowForm(f.decoration().asset()))
              .map(BoardScene.Feature::asset).toList();
    }

    @Test
    void roadLevelTwoTreesRenderOnlyOnLegacyBoardsWhileOtherScenerySelectsOnBoth() {
        for (boolean nativeBoard : new boolean[] { false, true }) {
            Board board = fixture();
            board.setNativeFormat(nativeBoard);
            try (var artwork = new BoardArtwork()) {
                assertEquals(!nativeBoard, !artwork.capture(board, ROAD, true).scenery().models().isEmpty(),
                      "Road level 2 is legacy road-with-trees art, natively the Alley finish");
                var tables = artwork.capture(board, TABLES, true);
                assertEquals(List.of("scenery/saxarba/SMV_Fluff/FluffSystem-07-Garden-02-Table-1-03"), tables.scenery().models());
                assertFalse(BoardFeatures.capture(board.getHex(TABLES), TABLES, Map.of(), Set.of(), board::getHex,
                      tables.scenery()).isEmpty(), "A FLUFF token typed on a native board still renders");
            }
        }
    }

    @Test
    void stampsTakeTheHexRoadExitsAndFreshIds() {
        Hex road = new Hex(0, "road:1:09", "");
        var members = BoardSceneryLayouts.stamp("scenery/saxarba/roads/road_trees{exits}", road, new Coords(3, 4));
        assertEquals(6, members.size());
        String group = members.getFirst().group();
        UUID.fromString(group);
        for (BoardDecoration member : members) {
            UUID.fromString(member.id());
            assertEquals(group, member.group());
        }
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
              () -> BoardSceneryLayouts.stamp("scenery/saxarba/roads/road_trees{exits}", new Hex(0), new Coords(3, 4)));
        assertEquals("Paint a road first", error.getMessage());
    }

    @Test
    void paintingAStampIsOneUndoStep() {
        var session = new BoardEditorSession(BoardEditorBlueprint.get(), BoardSceneryLayouts.DECODER);
        Coords at = new Coords(2, 2);
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.ASSET, "stamp/roadside-trees"), null);
        session.pointer(at, 0, 0, false);
        session.finishStroke();
        assertEquals("Paint a road first", session.snapshot().message());
        assertTrue(session.board().getHex(at).getDecorations().isEmpty());

        session.board().setHex(at, new Hex(0, "road:1:09", ""));
        session.pointer(at, 0, 0, false);
        session.pointer(at, .1, .1, true);
        session.finishStroke();
        var objects = session.board().getHex(at).getDecorations();
        assertEquals(6, objects.size(), "One press places the layout once");
        assertEquals(1, objects.stream().map(BoardDecoration::group).distinct().count());
        assertEquals(6, session.snapshot().selection().size(), "The new stamp is selected as its whole group");
        assertEquals(objects.getFirst().group(), session.snapshot().group());
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), null);
        assertTrue(session.board().getHex(at).getDecorations().isEmpty());
    }
}
