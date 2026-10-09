/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BridgeConstruction;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bridge is built or natural ({@link HexAppearance#bridgeBuilt}): one legacy decode ({@link BoardBridge#built}) types
 * the bridges import writes, the 3D editor opens and the render draws untyped; the render honours a stored type.
 */
class BoardBridgeTypeTest {
    private static final String DECK = "bridge:1:09;bridge_cf:40;bridge_elev:";

    /** A N-S span of two decks at column {@code x} (rows 2-3) between banks at rows 1 and 4, with the case's extras. */
    private record Case(String name, int x, boolean built, Map<Coords, String> terrain) { }

    private static Case span(String name, int x, boolean built, int deck, String north, String south, String extra) {
        Map<Coords, String> terrain = new LinkedHashMap<>();
        terrain.put(new Coords(x, 1), north);
        terrain.put(new Coords(x, 2), extra + DECK + deck);
        terrain.put(new Coords(x, 3), DECK + deck);
        terrain.put(new Coords(x, 4), south);
        return new Case(name, x, built, terrain);
    }

    /** The decode's categories, one span each, three columns apart so no case sees another. */
    private static final List<Case> CASES = new ArrayList<>();
    static {
        CASES.add(span("natural between bare banks", 1, false, 1, "", "", ""));
        CASES.add(span("a road two levels below a deck end", 4, true, 3, "road:1:09", "", ""));
        CASES.add(span("a road crossing beneath the deck", 7, true, 3, "", "", "road:1:18;"));
        CASES.add(span("railway art at a deck end", 10, true, 1, "fluff:10:05", "", ""));
        CASES.add(span("pavement on the deck hex", 13, true, 1, "", "", "pavement:1;"));
        CASES.add(span("snow-covered pavement at a deck end", 16, true, 1, "pavement:1;snow:1", "", ""));
        var beside = span("a building beside the span", 19, true, 1, "", "", "");
        beside.terrain().put(new Coords(19, 3).translated(2), "building:1;bldg_cf:15;bldg_elev:1");
        CASES.add(beside);
    }

    private static Board legacy() {
        Board board = Board.createEmptyBoard(22, 7);
        for (var shown : CASES) {
            shown.terrain().forEach((at, text) -> board.setHex(at, new Hex(text.contains("bridge") ? 0 : 1, text, "")));
        }
        return board;
    }

    private static boolean decoded(Board board, Coords at) {
        return BoardBridge.built(board::getHex, c -> false, BridgeSpan.of(board::getHex, at));
    }

    @Test
    void oneDecodeTypesEachSpanInImportAndInTheLegacyRender() throws Exception {
        Board legacy = legacy(), imported = legacy();
        for (var shown : CASES) {
            for (int y = 2; y <= 3; y++) {
                assertEquals(shown.built(), decoded(legacy, new Coords(shown.x(), y)), shown.name());
            }
        }
        // Pillar art on one hex makes its whole span built; a stored type beats every signal, built beating natural.
        assertTrue(BoardBridge.built(legacy::getHex, c -> c.equals(new Coords(1, 3)), BridgeSpan.of(legacy::getHex, new Coords(1, 2))));
        Board stored = legacy();
        for (int y = 2; y <= 3; y++) { type(stored, new Coords(4, y), HexAppearance.NATURAL_BRIDGE); }
        type(stored, new Coords(1, 3), HexAppearance.BUILT_BRIDGE);
        assertFalse(decoded(stored, new Coords(4, 3)), "A stored natural road bridge stays natural");
        assertTrue(decoded(stored, new Coords(1, 2)), "One stored built hex types its whole span");
        type(stored, new Coords(1, 2), HexAppearance.NATURAL_BRIDGE);
        assertTrue(decoded(stored, new Coords(1, 2)), "Built wins in a mixed span");

        BoardSceneryLayouts.importBoard(imported);
        try (var artwork = new BoardArtwork()) {
            var pool = new BoardScene.PixelPool();
            for (var shown : CASES) {
                for (int y = 2; y <= 3; y++) {
                    Coords at = new Coords(shown.x(), y);
                    var expected = shown.built() ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE;
                    assertEquals(expected, imported.getHex(at).getAppearance().get("bridge"), "Import: " + shown.name());
                    var tile = BoardScene.captureTile(legacy.getHex(at), artwork.capture(legacy, at, true), null, pool, legacy::getHex);
                    assertEquals(expected, tile.appearance().get("bridge"), "Legacy render: " + shown.name());
                    assertFalse(legacy.getHex(at).getAppearance().containsKey("bridge"), "The render never writes the board");
                }
            }
        }
        assertNull(imported.getHex(new Coords(4, 1)).getAppearance().get("bridge"), "Only bridge hexes get a type");
    }

    private static void type(Board board, Coords at, HexAppearance type) {
        Hex hex = board.getHex(at).duplicate();
        hex.setAppearance(Map.of("bridge", type));
        board.setHex(at, hex);
    }

    @Test
    void aGameKeepsTheDecodedTypeWhenTheBuildingBesideTheSpanCollapses() throws Exception {
        Coords north = new Coords(19, 2), south = new Coords(19, 3);
        try (var fixture = GpuBoardFixture.create(legacy())) {
            SwingUtilities.invokeAndWait(() -> {
                Board board = fixture.game.getBoard();
                fixture.source.refresh();
                assertEquals(HexAppearance.BUILT_BRIDGE, fixture.source.takeFrame().scene().tile(south).appearance().get("bridge"));
                board.setHex(south.translated(2), new Hex(1, "rubble:1", ""));
                assertFalse(decoded(board, south), "Decoded again, the span would now be a rock arch");
                fixture.source.refresh();
                for (Coords at : List.of(north, south)) {
                    assertEquals(HexAppearance.BUILT_BRIDGE, fixture.source.takeFrame().scene().tile(at).appearance().get("bridge"),
                          "The recaptured neighbourhood keeps its type: " + at);
                }
                board.initializeAllAutomaticTerrain();
                fixture.source.refresh();
                assertEquals(HexAppearance.BUILT_BRIDGE, fixture.source.takeFrame().scene().tile(south).appearance().get("bridge"),
                      "A full recapture of the same board keeps it too");
            });
        }
    }

    @Test
    void theEditorTypesAnOpenedBoardsUntypedBridges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Untyped.board2");
        Board board = legacy();
        board.setNativeFormat(true);
        BoardFile.save(board, file);
        var session = new BoardEditorSession(BoardEditorBlueprint.get(), BoardSceneryLayouts.DECODER);
        session.open(file);
        for (var shown : CASES) {
            for (int y = 2; y <= 3; y++) {
                assertEquals(shown.built(), HexAppearance.bridgeBuilt(session.board().getHex(shown.x(), y).getAppearance()),
                      shown.name());
            }
        }
        assertFalse(session.dirty(), "The stored types are the ones the board was drawn with");
        var plain = new BoardEditorSession(BoardEditorBlueprint.get());
        plain.open(file);
        assertNull(HexAppearance.bridgeBuilt(plain.board().getHex(1, 2).getAppearance()), "Without the 3D board nothing decodes");
    }

    @Test
    void engineersBuildABuiltBridge() {
        // A fresh section is built; one rebuilt where a built section with piers stood (its look kept) keeps them.
        for (var before : java.util.Arrays.asList(null, HexAppearance.NATURAL_BRIDGE, HexAppearance.PILLARS)) {
            Board board = Board.createEmptyBoard(3, 3);
            board.setHex(1, 0, new Hex(1));
            Hex water = new Hex(0, "water:1", "");
            if (before != null) { water.setAppearance(Map.of("bridge", before)); }
            board.setHex(1, 1, water);
            board.setHex(1, 2, new Hex(1));
            BridgeConstruction.placeBridge(board, new Coords(1, 1), 9, 1, 15);
            assertEquals(before == HexAppearance.PILLARS ? HexAppearance.PILLARS : HexAppearance.BUILT_BRIDGE,
                  board.getHex(1, 1).getAppearance().get("bridge"), "Before: " + before);
        }
    }

    /** A bridge tile of a hand-made scene with {@code type} (null: none) and a deck {@code deck} levels up. */
    private static BoardScene.Tile deck(Coords at, int exits, boolean water, int deck, HexAppearance type) {
        Hex bridge = new Hex(0);
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deck));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        var ground = BoardSurfaceBlendTest.tile(at, BoardScene.Surface.GRASS, 0, water ? 1 : -1, 0).ground();
        var t = new BoardScene.Tile(at, 0, water ? 1 : -1, false, 0, BoardScene.Surface.GRASS, ground, null, null, null, null,
              BoardFeatures.capture(bridge, at, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true,
              BoardRoad.Kind.NONE);
        return type == null ? t : appearance(t, Map.of("bridge", type));
    }

    private static BoardScene.Tile appearance(BoardScene.Tile t, Map<String, HexAppearance> appearance) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(),
              t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.bridge(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), appearance);
    }

    private static final Coords AT = new Coords(4, 4);

    @Test
    void theRenderDrawsTheStoredTypeAndAPlainBuiltDeckWithoutMarkings() {
        // A stored natural span keeps its rock arch between paved roads.
        var paved = BoardBridgeMaterialsTest.straight(AT, 3, 1, BoardRoad.Kind.PAVED, BoardRoad.Kind.PAVED);
        assertEquals(BoardRoad.Kind.PAVED, BoardBridge.kind(paved, paved.tile(AT)));
        var natural = BoardNaturalBridgeTest.replace(paved, appearance(paved.tile(AT), Map.of("bridge", HexAppearance.NATURAL_BRIDGE)));
        assertTrue(BoardBridge.deck(natural, natural.tile(AT)).natural());
        // A built span without a road is plain asphalt, unmarked; markings come with a paved road.
        var bare = BoardBridgeMaterialsTest.straight(AT, 3, 1, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE);
        assertTrue(BoardBridge.deck(bare, bare.tile(AT)).natural(), "An untyped hand-made span without roads stays natural");
        var built = BoardNaturalBridgeTest.replace(bare, appearance(bare.tile(AT), Map.of("bridge", HexAppearance.BUILT_BRIDGE)));
        var deck = BoardBridge.deck(built, built.tile(AT));
        assertEquals(BoardRoad.Kind.ALLEY, deck.kind());
        assertTrue(GpuRoads.deckPatches(built.tile(AT), deck, deck.road(AT)).stream()
              .noneMatch(p -> p.texture().equals("concrete") && !p.shape().isEmpty()), "No centre markings without a road");
        var marked = BoardBridge.deck(paved, paved.tile(AT));
        assertTrue(GpuRoads.deckPatches(paved.tile(AT), marked, marked.road(AT)).stream()
              .anyMatch(p -> p.texture().equals("concrete") && !p.shape().isEmpty()));
    }

    @Test
    void piersStandOnlyUnderBuiltDecksAndTheApronStaysOffPavement() {
        // The Pillars toggle is a built type: its span is a built deck with piers even without a road.
        var pillared = BoardBridgeFootingTest.span(3, false, 2, Set.of(0, 1, 2));
        for (Coords end : List.of(BoardBridgeFootingTest.START.translated(0), BoardBridgeFootingTest.START.translated(3, 3))) {
            pillared = BoardNaturalBridgeTest.replace(pillared, BoardRoadTest.tile(end, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS));
        }
        var middle = BoardBridgeFootingTest.START.translated(3);
        assertFalse(BoardBridge.deck(pillared, pillared.tile(middle)).natural());
        assertFalse(BoardBridgeFootingTest.piers(pillared, middle).isEmpty());
        // Switched to natural, the same span is a rock arch without piers.
        var arch = pillared;
        for (int i = 0; i < 3; i++) {
            Coords at = BoardBridgeFootingTest.START.translated(3, i);
            arch = BoardNaturalBridgeTest.replace(arch, appearance(arch.tile(at), Map.of("bridge", HexAppearance.NATURAL_BRIDGE)));
        }
        assertTrue(BoardBridge.deck(arch, arch.tile(middle)).natural());
        assertTrue(BoardBridgeFootingTest.piers(arch, middle).isEmpty());

        // A built deck between a pavement bank (north) and a grass bank (south): the loose apron only on the grass.
        var scene = BoardSurfaceBlendTest.scene(at -> at.equals(AT) ? deck(at, 9, false, 0, HexAppearance.BUILT_BRIDGE)
              : BoardSurfaceBlendTest.tile(at, at.equals(AT.translated(0)) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS,
                    0, -1, 0));
        var footing = BoardBridgeFooting.build(scene, scene.tile(AT), TerrainLod.FULL, new java.util.HashMap<>());
        assertEquals(1 << 3, footing.bareExits(), "No dirt apron over pavement");
    }

    private static BoardTacticalGeometry.Surface support(BoardScene scene, Coords at) {
        return BoardTacticalGeometry.Surface.of(new BoardSurface(scene, scene.tile(at)), scene, BoardGeometry.floor(scene));
    }

    @Test
    void noBladeGrowsThroughALowDeck() {
        float half = BoardBridge.PASSAGE_HALF_WIDTH * BoardGeometry.hexScale();
        for (int level : new int[] { 0, 2 }) {
            var scene = BoardSurfaceBlendTest.scene(at -> at.equals(AT) ? deck(at, 9, false, level, HexAppearance.BUILT_BRIDGE)
                  : BoardSurfaceBlendTest.tile(at, BoardScene.Surface.GRASS, 0, -1, 0));
            FloatArray roots = GpuGroundCover.plant(scene, scene.tile(AT), support(scene, AT), null);
            int under = 0;
            for (int i = 0; i < roots.size; i += 4) {
                if (Math.abs(roots.get(i) - BoardGeometry.centerX(AT)) < half) { under++; }
            }
            System.out.printf("COVER deck %d levels up: %d of %d blades under the deck%n", level, under, roots.size / 4);
            if (level == 0) { assertEquals(0, under, "No blade through a ground-level deck"); }
            else { assertTrue(under > 50, "Grass grows beneath a raised deck: " + under); }
        }
    }

    @Test
    void reedsAndCropsAvoidPlacedObjects() {
        var fountain = new BoardDecoration("fountain", "prop", "scenery/parks/fountain", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        var feature = new BoardScene.Feature(fountain.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, fountain);
        for (var kind : List.of(BoardScene.Biome.MARSH, BoardScene.Biome.FIELD)) {
            var plain = BoardSurfaceBlendTest.scene(c -> BoardBiomeTest.tile(c, kind, 0));
            var base = plain.tile(AT);
            var placed = BoardNaturalBridgeTest.replace(plain, new BoardScene.Tile(AT, 0, -1, false, 0, base.surface(), base.ground(),
                  null, null, null, null, List.of(feature), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE,
                  BoardFireSmoke.NONE, kind));
            var obstacles = new BoardObstacles(placed, placed.tile(AT));
            FloatArray bare = roots(plain, kind), around = roots(placed, kind);
            Set<List<Float>> kept = new HashSet<>(), all = new HashSet<>();
            for (int i = 0; i < bare.size; i += 4) { all.add(List.of(bare.get(i), bare.get(i + 1))); }
            int inside = 0;
            for (int i = 0; i < around.size; i += 4) {
                kept.add(List.of(around.get(i), around.get(i + 1)));
                if (obstacles.obstructs(new Vector3(around.get(i), around.get(i + 1), around.get(i + 2)), .01f, .01f)) { inside++; }
            }
            System.out.printf("COVER %s: %d plants, %d beside the fountain%n", kind, bare.size / 4, around.size / 4);
            assertEquals(0, inside, kind + ": no plant through the fountain");
            assertTrue(around.size > 0, kind + ": the rest of the hex still grows");
            // Crop rows split at the fountain into more, shorter strips; reeds are points that keep their places.
            if (kind == BoardScene.Biome.MARSH) {
                assertTrue(around.size < bare.size && all.containsAll(kept), "The fountain clears its reeds; the others stay");
            }
        }
    }

    private static FloatArray roots(BoardScene scene, BoardScene.Biome kind) {
        return kind == BoardScene.Biome.MARSH ? GpuBiomeVegetation.plantReeds(scene, scene.tile(AT), support(scene, AT), 1, null)
              : GpuBiomeVegetation.plant(scene, scene.tile(AT), support(scene, AT), null).roots();
    }
}
