/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import megamek.client.ui.boardeditor.BoardEditorSnapping;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.MaglevRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Imports every shipped legacy board that has scenery tokens, one at a time, and checks the import invariants. Opt-in
 * (minutes): set the environment variable {@code MEGAMEK_LEGACY_CORPUS=1}. Prints objects and groups per board and per
 * decoded artwork, the tokens kept, the maglev routes and the import times.
 */
@EnabledIfEnvironmentVariable(named = "MEGAMEK_LEGACY_CORPUS", matches = "1")
class LegacySceneryCorpusTest {
    private record Result(String board, int objects, int groups, long millis) { }
    /** A car park's lane road by its exits. */
    private static final Map<Integer, String> LANES = Map.of(18, "NE-SW", 9, "N-S", 36, "SE-NW");

    @Test
    void everyLegacyBoardImportsWithoutLeavingDecodableScenery() throws Exception {
        Path models = Configuration.dataDir().toPath().resolve("models/board");
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Configuration.boardsDir().toPath())) {
            files = walk.filter(path -> path.toString().endsWith(".board")).sorted().toList();
        }
        List<Result> results = new ArrayList<>();
        Map<String, Integer> perAsset = new TreeMap<>(), kept = new TreeMap<>(), receivers = new TreeMap<>();
        int noTrees = 0, stretched = 0, emblemSets = 0, emblemSingles = 0, pillaredBridges = 0;
        // Maglev: markers, their joined sides (counted from both ends), adjacent markers left apart, absolute markers,
        // station and train hexes, and those whose route climbs to a neighbour while the pieces stay level.
        int markers = 0, joinedSides = 0, apart = 0, absolute = 0, stations = 0, sloped = 0;
        // Car-park roads: lane roads by axis and their boards, roof (or other off-ground) car parks left without a road,
        // car parks keeping an authored road, and neighbour roads whose automatic exits were written out.
        Map<String, Integer> laneRoads = new TreeMap<>();
        Set<String> laneBoards = new HashSet<>();
        int offGroundParks = 0, authoredParks = 0, keptNeighbours = 0;
        // Car-park decal rows beside their road, and folded back to back (mirrored) where no road is drawn.
        int besideRows = 0, foldedRows = 0;
        Set<String> checkedAssets = new HashSet<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            if (!text.contains("fluff:") && !text.contains("road:2")) { continue; }
            Board board;
            try { board = BoardFile.read(file); }
            catch (java.io.IOException | IllegalArgumentException unreadable) {
                System.out.printf("CORPUS unreadable %s: %s%n", file.getFileName(), unreadable.getMessage());
                continue;
            }
            List<String> before = new ArrayList<>(), after = new ArrayList<>();
            board.isValid(before);
            Map<Coords, Terrain> roads = new HashMap<>();
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    var road = board.getHex(x, y).getTerrain(Terrains.ROAD);
                    if (road != null) { roads.put(new Coords(x, y), new Terrain(road)); }
                }
            }
            long start = System.nanoTime();
            var issues = BoardSceneryLayouts.importBoard(board);
            assertEquals(List.of(), issues, "Every emblem set in the corpus is complete: " + file);
            long millis = (System.nanoTime() - start) / 1_000_000;
            board.isValid(after);
            assertEquals(before, after, "Import adds no rule errors: " + file);
            // The only road change: a ground car park without a road gains its lane; kept neighbours only write theirs out.
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    var hex = board.getHex(x, y);
                    var road = hex.getTerrain(Terrains.ROAD);
                    var old = roads.get(new Coords(x, y));
                    boolean carPark = hex.getDecorations().stream().anyMatch(o -> o.asset().startsWith("decal/car-park/"));
                    String where = file.getFileName() + " " + x + "," + y;
                    if (old == null && road != null) {
                        assertTrue(road.getLevel() == 1 && road.hasExitsSpecified() && LANES.containsKey(road.getExits() & 63),
                              where + ": a lane road " + road);
                        assertTrue(hex.getDecorations().stream().anyMatch(o -> o.asset().startsWith("decal/car-park/")
                              && o.placement().receiver().terrain().equals("ground")), where + ": a lane road on a ground car park");
                        laneRoads.merge(LANES.get(road.getExits() & 63), 1, Integer::sum);
                        laneBoards.add(file.toString());
                    } else if (old != null) {
                        assertTrue(road != null && road.getLevel() == old.getLevel()
                              && (road.getExits() & 63) == (old.getExits() & 63)
                              && (road.hasExitsSpecified() || !old.hasExitsSpecified()), where + ": road " + old + " kept");
                        if (road.hasExitsSpecified() && !old.hasExitsSpecified()) { keptNeighbours++; }
                        if (carPark) { authoredParks++; }
                    } else if (carPark && !BoardSceneryLayouts.plainGround(hex)) {
                        offGroundParks++;
                    }
                }
            }
            int objects = 0;
            Set<String> groups = new HashSet<>();
            try (var artwork = new BoardArtwork()) {
                for (int y = 0; y < board.getHeight(); y++) {
                    for (int x = 0; x < board.getWidth(); x++) {
                        var hex = board.getHex(x, y);
                        var scenery = artwork.scenery(board, new Coords(x, y));
                        for (var model : scenery.sources()) {
                            assertFalse(BoardSceneryLayouts.imported(model.terrains(), hex),
                                  file.getFileName() + " still selects " + model.key() + " at " + x + "," + y);
                        }
                        for (var decal : scenery.decals()) {
                            assertFalse(BoardSceneryLayouts.imported(decal.terrains(), hex),
                                  file.getFileName() + " still paints " + decal.key() + " at " + x + "," + y);
                        }
                        // Pillar art on a bridge is the bridge's Pillars toggle: no pillar objects there.
                        if (HexAppearance.pillars(hex.getAppearance())) { pillaredBridges++; }
                        if (hex.containsTerrain(Terrains.BRIDGE)) {
                            assertTrue(hex.getDecorations().stream().noneMatch(o -> o.asset().equals(BoardSceneryLayouts.PILLAR)),
                                  file.getFileName() + " " + x + "," + y + ": no pillar objects on a bridge");
                        }
                        var vegetation = hex.getAppearance().get("vegetation");
                        if (vegetation != null && BoardFeatures.NO_TREES.equals(vegetation.variant())) { noTrees++; }
                        if (hex.containsTerrain(Terrains.FLUFF) && hex.terrainLevel(Terrains.FLUFF) < 2000) {
                            kept.merge("fluff:" + hex.terrainLevel(Terrains.FLUFF), 1, Integer::sum);
                        }
                        var route = MaglevRoute.find(hex);
                        if (route != null) {
                            markers++;
                            assertNull(route.group(), "A route marker is never a group member");
                            if (route.placement().mode().equals("absolute")) { absolute++; }
                            // Other artwork of the hex (road trees, say) keeps its own support.
                            var pieces = hex.getDecorations().stream().filter(object -> !MaglevRoute.isRoute(object)
                                  && object.asset().startsWith("scenery/maglev/")).toList();
                            assertTrue(pieces.stream().allMatch(object -> object.placement().equals(route.placement())),
                                  file.getFileName() + " " + x + "," + y + ": the pieces ride the route");
                            double level = BoardEditorSnapping.level(hex, route.placement());
                            boolean climbs = false;
                            for (int direction = 0; direction < 6; direction++) {
                                Hex next = board.getHex(new Coords(x, y).translated(direction));
                                var other = MaglevRoute.find(next);
                                if (other == null) { continue; }
                                if (!MaglevRoute.joined(route, direction, other)) { apart++; continue; }
                                joinedSides++;
                                climbs |= BoardEditorSnapping.level(next, other.placement()) != level;
                            }
                            if (!pieces.isEmpty()) { stations++; if (climbs) { sloped++; } }
                        }
                        for (BoardDecoration object : hex.getDecorations()) {
                            objects++;
                            if (object.group() != null) { groups.add(object.group()); }
                            perAsset.merge(object.asset(), 1, Integer::sum);
                            if (!object.stretch().equals(BoardDecoration.Stretch.NONE)) { stretched++; }
                            if (object.asset().startsWith("decal/emblems/")) {
                                if (object.scale() == 1) { emblemSets++; } else { emblemSingles++; }
                            }
                            if (object.asset().startsWith("decal/car-park/")) {
                                if (object.mirror()) { foldedRows++; } else { besideRows++; }
                            }
                            receivers.merge(object.placement().receiver() == null ? "absolute"
                                  : object.placement().receiver().terrain(), 1, Integer::sum);
                            // The trees of a road passing under a bridge stand beneath its deck.
                            assertFalse(BoardFeatures.parkSpecies().contains(object.asset()) && object.placement().receiver() != null
                                  && object.placement().receiver().terrain().equals("bridge"), file.getFileName() + ": a tree on a deck");
                            if (checkedAssets.add(object.asset())) {
                                // Props have a mesh; decals (emblems, car parks) their image by path convention.
                                assertTrue(object.kind().equals("decal") ? BoardDecalArt.image(object.asset()).isFile()
                                      : Files.isRegularFile(models.resolve(object.asset() + ".glb")), object.asset());
                                assertTrue(blueprint.asset(object.asset()) != null, "Unlabelled import " + object.asset());
                            }
                        }
                    }
                }
            }
            var written = new ByteArrayOutputStream();
            BoardFile.write(board, written);
            Board reread = BoardFile.readNative(written.toString(StandardCharsets.UTF_8));
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    assertEquals(BoardFile.hexNode(board.getHex(x, y)), BoardFile.hexNode(reread.getHex(x, y)), file + " " + x + "," + y);
                }
            }
            results.add(new Result(Configuration.boardsDir().toPath().relativize(file).toString(), objects, groups.size(), millis));
        }
        System.out.printf("CORPUS boards=%d objects=%d groups=%d%n", results.size(),
              results.stream().mapToInt(Result::objects).sum(), results.stream().mapToInt(Result::groups).sum());
        results.stream().sorted((a, b) -> Integer.compare(b.objects(), a.objects())).limit(10)
              .forEach(r -> System.out.printf("CORPUS board %6d objects %5d groups %6d ms  %s%n", r.objects(), r.groups(), r.millis(), r.board()));
        perAsset.forEach((asset, count) -> System.out.printf("CORPUS asset %7d  %s%n", count, asset));
        kept.forEach((token, count) -> System.out.printf("CORPUS kept %6d hexes  %s%n", count, token));
        receivers.forEach((receiver, count) -> System.out.printf("CORPUS receiver %7d objects  %s%n", count, receiver));
        System.out.printf("CORPUS woods drawn without trees under imported art: %d hexes%n", noTrees);
        // The shipped boards hold 23 complete 7-hex emblem sets and 226 one-hex variants (none incomplete).
        System.out.printf("CORPUS emblems: %d sets as one decal each, %d one-hex variants%n", emblemSets, emblemSingles);
        assertEquals(23, emblemSets);
        assertEquals(226, emblemSingles);
        System.out.printf("CORPUS car-park roads: %d lane roads on %d boards %s, %d car parks off the ground without a road, "
              + "%d car parks keep an authored road, %d neighbour roads written out%n", laneRoads.values().stream()
              .mapToInt(Integer::intValue).sum(), laneBoards.size(), laneRoads, offGroundParks, authoredParks, keptNeighbours);
        System.out.printf("CORPUS car-park rows: %d beside their road, %d back to back (mirrored)%n", besideRows, foldedRows);
        System.out.printf("CORPUS stretched objects (narrow glass roofs): %d%n", stretched);
        // The shipped boards have pillar art on 491 bridge hexes, on 15 boards.
        System.out.printf("CORPUS bridge hexes with their Pillars toggle on: %d%n", pillaredBridges);
        assertEquals(491, pillaredBridges);
        System.out.printf("CORPUS maglev markers=%d (absolute %d), joined sides=%d, adjacent markers apart=%d sides, "
              + "station/train hexes=%d, of which on a climbing route=%d%n", markers, absolute, joinedSides, apart, stations, sloped);
        results.stream().filter(r -> r.board().contains("Valencia_City"))
              .forEach(r -> System.out.printf("CORPUS valencia import %d ms, %d objects, %d groups%n", r.millis(), r.objects(), r.groups()));
    }
}
