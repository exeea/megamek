/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardWallFamilyTest {
    private static final Coords ORIGIN = new Coords(0, 0);

    @ParameterizedTest
    @CsvSource({ "1,CIVILIAN", "10,CIVILIAN", "11,LIGHT", "15,LIGHT", "16,MEDIUM", "40,MEDIUM",
          "41,HEAVY", "90,HEAVY", "91,HARDENED", "150,HARDENED", "151,CASTLE_BRIAN", "500,CASTLE_BRIAN" })
    void capturedWallUsesCfBoundaries(int cf, BoardWall.Family expected) {
        Board board = board(cf);
        var tile = BoardTiles.captureAll(board).getFirst();
        assertEquals(expected, layout(tile).family());
    }

    @Test
    void damageNeverChangesThePristineWallFamilyOrItsGeometry() {
        Board board = board(151);
        Board received = board(151);
        var before = BoardTiles.captureAll(board).getFirst();
        assertEquals(BoardWall.Family.CASTLE_BRIAN, layout(before).family());
        for (int remaining : new int[] { 150, 90, 40, 15, 10, 1 }) {
            received.getBuildingAt(ORIGIN).setCurrentCF(remaining, ORIGIN);
            board.updateBuilding(received.getBuildingAt(ORIGIN));
            var after = BoardTiles.captureAll(board).getFirst();
            assertEquals(remaining, after.cover().cf(), "Gameplay still tracks damage normally");
            assertEquals(151, after.cover().pristineCF());
            assertEquals(layout(before), layout(after), "Damage across every tier retains the original model");
            assertTrue(before.sameGeometry(after));
        }
    }

    @Test
    void editorPreviewUsesPaintedCfThenCommittedEditUsesTheSameFamily() {
        Board board = board(150);
        Hex painted = new Hex(0, "building:5:0;bldg_elev:2;bldg_cf:11;bldg_basement_type:1", "");
        painted.setCoords(ORIGIN);
        try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
            var preview = capture.preview(board, ORIGIN, painted);
            assertEquals(BoardWall.Family.LIGHT, layout(preview).family(), "Preview ignores the old building's CF");
            board.setHex(ORIGIN, painted);
            var committed = BoardTiles.captureAll(board).getFirst();
            assertEquals(layout(preview), layout(committed));
            painted.addTerrain(new Terrain(Terrains.BLDG_CF, 15));
            var sameTier = BoardTiles.captureAll(board).getFirst();
            assertTrue(committed.sameGeometry(sameTier));
            painted.addTerrain(new Terrain(Terrains.BLDG_CF, 16));
            assertFalse(committed.sameGeometry(BoardTiles.captureAll(board).getFirst()));
        }
    }

    @Test
    void explicitWallModelKeepsItsAppearanceWhenCfChanges() {
        Board board = board(151);
        board.getHex(ORIGIN).setAppearance(Map.of("building",
              new HexAppearance(null, "buildings/saxarba/building_hard/building_hard_04", null, null)));
        var before = BoardTiles.captureAll(board).getFirst();
        board.getHex(ORIGIN).addTerrain(new Terrain(Terrains.BLDG_CF, 10));
        var after = BoardTiles.captureAll(board).getFirst();
        assertNull(BoardWall.family(before));
        assertTrue(before.sameGeometry(after));
    }

    @Test
    void eachFamilyAssemblesBothLodsWithEmbeddedMaterialsAndFullHeightLayers() {
        for (int cf : new int[] { 10, 15, 40, 90, 150, 151 }) {
            for (int exits : new int[] { 0, 9, 63 }) {
                int previous = Integer.MAX_VALUE;
                for (int lod = 0; lod < BoardWall.LODS; lod++) {
                    var model = (RigidGlb.Data) BoardWall.model(BoardWall.layout("buildings/wall-" + exits, 3, cf), lod);
                    assertFalse(model.images.isEmpty());
                    assertEquals(model.materials.size, model.surfaces.size());
                    if (cf == 10) { assertFalse(model.alphaTests.isEmpty(), "Civilian wire cutouts retain their masks"); }
                    int indices = 0;
                    for (var mesh : model.meshes) {
                        for (var part : mesh.parts) { indices += part.indices.length; }
                        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                        for (int vertex = 2; vertex < mesh.vertices.length; vertex += RigidGlb.STRIDE) {
                            low = Math.min(low, mesh.vertices[vertex]);
                            high = Math.max(high, mesh.vertices[vertex]);
                        }
                        if (mesh.id.contains("/roof0-")) {
                            assertEquals(54, low, .001f, "The roof caps three complete rules levels");
                            assertTrue(high > low && high <= 62.01f);
                        } else {
                            assertEquals(18, high - low, .001f, "Base and floors retain their authored height");
                        }
                    }
                    assertTrue(indices < previous, "Both authored LODs must be loaded for CF " + cf);
                    previous = indices;
                }
            }
        }
    }

    private static BoardWall.Layout layout(BoardScene.Tile tile) {
        var feature = tile.features().stream().filter(part -> BoardWall.supports(part.asset())).findFirst().orElseThrow();
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
        return BoardWall.layout(scene, tile, feature);
    }

    private static Board board(int cf) {
        Board board = new Board();
        board.newData(1, 1, new Hex[] { new Hex(0,
              "building:5:0;bldg_elev:2;bldg_cf:" + cf + ";bldg_basement_type:1", "") }, null);
        BoardSceneryLayouts.convert(board);
        return board;
    }
}
