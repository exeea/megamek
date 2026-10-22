/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.BasementType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardEditorTerrainTest {
    @Test
    void settingBasementToNoneRemovesThePublishedEditorBadge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new BoardEditorSession();
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            board.setHex(coords, new Hex(0, "building:2;bldg_elev:2;bldg_cf:40;bldg_basement_type:"
                  + BasementType.ONE_DEEP_NORMAL.ordinal(), "", coords));
            editor.game().setBoard(board);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                var initial = source.takeFrame().scene();
                var badges = source.editorBadges();
                var original = Map.of(coords, List.of(new BoardEditorTerrain.Badge(BoardEditorTerrain.Kind.BASEMENT, "B1")));
                assertEquals(original, badges);
                assertTrue(initial.tactical().fills().isEmpty(), "A basement badge replaces the generic terrain card");
                assertTrue(initial.tactical().labels().isEmpty(), "Basements no longer use generic terrain labels");
                var changed = board.getHex(coords).duplicate();
                changed.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, BasementType.NONE.ordinal()));
                changed.addTerrain(new Terrain(Terrains.BLDG_BASE_COLLAPSED, 0));
                board.setHex(coords, changed);
                source.refresh();
                assertEquals(badges, source.editorBadges(),
                      "The render thread keeps badges paired with its displayed frame until takeFrame");
                var scene = source.takeFrame().scene();
                assertTrue(source.editorBadges().isEmpty(), "Changing to NONE removes the per-hex badge");
                assertEquals(original, badges, "Publishing an edit must not mutate an earlier frame's badge snapshot");
                assertTrue(scene.tactical().fills().isEmpty(), "NONE has no generic basement card");
                assertTrue(scene.tactical().labels().isEmpty(), "NONE has no basement label either");
                assertTrue(scene.tile(coords).building(), "The building remains in the captured scene");
                assertEquals(BasementType.NONE.ordinal(), board.getHex(coords).terrainLevel(Terrains.BLDG_BASEMENT_TYPE));
                assertEquals(2, board.getHex(coords).terrainLevel(Terrains.BLDG_ELEV));
                assertEquals(40, board.getHex(coords).terrainLevel(Terrains.BLDG_CF));

                changed = board.getHex(coords).duplicate();
                changed.addTerrain(new Terrain(Terrains.BLACK_ICE, 1));
                board.setHex(coords, changed);
                source.refresh();
                assertTrue(source.takeFrame().scene().tactical().fills().isEmpty(), "Badges never add ground cards");
                assertEquals(List.of(new BoardEditorTerrain.Badge(BoardEditorTerrain.Kind.BLACK_ICE,
                      Terrains.getEditorName(Terrains.BLACK_ICE))), source.editorBadges().get(coords),
                      "An absent basement must not suppress a different badge on the same hex");
            }
        });
    }

    @Test
    void connectedBuildingKeepsPerHexBasementsInAnImmutableEditorOnlySnapshot() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new BoardEditorSession();
            var single = new Coords(0, 0);
            var doubleDepth = new Coords(0, 1);
            var unknown = new Coords(0, 2);
            var none = new Coords(0, 3);
            var unspecified = new Coords(0, 4);
            BasementType[] types = {BasementType.ONE_DEEP_NORMAL, BasementType.TWO_DEEP_HEAD,
                                   BasementType.UNKNOWN, BasementType.NONE};
            Hex[] hexes = new Hex[6];
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(0, y);
                Hex hex = new Hex(0, "bldg_elev:3;bldg_cf:40", "", at);
                int exits = 0;
                for (int direction = 0; direction < 6; direction++) {
                    Coords neighbor = at.translated(direction);
                    if (neighbor.getX() == 0 && neighbor.getY() >= 0 && neighbor.getY() < 5) {
                        exits |= 1 << direction;
                    }
                }
                hex.addTerrain(new Terrain(Terrains.BUILDING, 2, true, exits));
                if (y < types.length) {
                    hex.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, types[y].ordinal()));
                }
                hexes[y] = hex;
            }
            hexes[5] = new Hex(0, "", "", new Coords(0, 5));
            var board = new Board();
            board.newData(1, 6, hexes, null);
            var building = board.getBuildingAt(single);
            assertNotNull(building);
            for (int y = 0; y < 5; y++) {
                assertSame(building, board.getBuildingAt(new Coords(0, y)),
                      "These different basement states belong to one connected building");
            }
            assertFalse(board.getHex(unspecified).containsTerrain(Terrains.BLDG_BASEMENT_TYPE));
            editor.game().setBoard(board);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                var scene = source.takeFrame().scene();
                var badges = source.editorBadges();
                var unknownBadge = List.of(new BoardEditorTerrain.Badge(BoardEditorTerrain.Kind.UNKNOWN_BASEMENT, "B?"));
                assertEquals(Map.of(single, List.of(new BoardEditorTerrain.Badge(BoardEditorTerrain.Kind.BASEMENT, "B1")),
                      doubleDepth, List.of(new BoardEditorTerrain.Badge(BoardEditorTerrain.Kind.BASEMENT, "B2")),
                      unknown, unknownBadge, unspecified, unknownBadge), badges,
                      "Each building hex retains its own depth; an unspecified basement remains unknown");
                assertFalse(badges.containsKey(none));
                assertThrows(UnsupportedOperationException.class, () -> badges.put(none, unknownBadge));
                assertThrows(UnsupportedOperationException.class, () -> badges.get(single).clear());
                assertTrue(scene.tactical().fills().isEmpty());
                assertTrue(scene.tactical().labels().isEmpty());
            }
            try (var preview = new GpuMapSource(editor.game(), null, null)) {
                preview.takeFrame();
                assertTrue(preview.editorBadges().isEmpty(), "Map previews do not expose editor badges");
            }
        });
    }

    @Test
    void stackedFlagsUpdateIndependentlyWithoutAddingGroundCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new BoardEditorSession();
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            board.setHex(coords, new Hex(0, "building:2;bldg_elev:2;bldg_cf:40;bldg_basement_type:"
                  + BasementType.TWO_DEEP_HEAD.ordinal() + ";bldg_base_collapsed:1;black_ice:1;metal_deposit:0", "", coords));
            editor.game().setBoard(board);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                var initial = source.takeFrame().scene();
                var original = source.editorBadges().get(coords);
                assertEquals(List.of(BoardEditorTerrain.Kind.BASEMENT, BoardEditorTerrain.Kind.COLLAPSED_BASEMENT,
                      BoardEditorTerrain.Kind.BLACK_ICE, BoardEditorTerrain.Kind.METAL_DEPOSITS), kinds(original),
                      "All authored flags remain individually visible in a stable top-to-bottom order");
                assertEquals("B2", original.getFirst().text());
                assertEquals(Messages.getString("BoardEditor.badge.collapsed"), original.get(1).text());
                assertEquals(Terrains.getEditorName(Terrains.BLACK_ICE), original.get(2).text());
                assertEquals(Messages.getString("BoardEditor.badge.metal", 0), original.getLast().text(),
                      "Authored metal level zero is still an explicit flag");
                assertTrue(initial.tactical().fills().isEmpty());
                assertTrue(initial.tactical().labels().isEmpty());

                var changed = board.getHex(coords).duplicate();
                changed.addTerrain(new Terrain(Terrains.BLDG_BASE_COLLAPSED, 0));
                board.setHex(coords, changed);
                source.refresh();
                source.takeFrame();
                assertEquals(List.of(original.get(0), original.get(2), original.get(3)), source.editorBadges().get(coords),
                      "Collapsed zero removes only the collapse badge");

                changed = board.getHex(coords).duplicate();
                changed.addTerrain(new Terrain(Terrains.METAL_CONTENT, 7));
                board.setHex(coords, changed);
                source.refresh();
                source.takeFrame();
                var updated = source.editorBadges().get(coords);
                assertEquals(original.get(0), updated.get(0));
                assertEquals(original.get(2), updated.get(1));
                assertEquals(Messages.getString("BoardEditor.badge.metal", 7), updated.getLast().text(),
                      "The stack reflects an edited metal level");

                changed = board.getHex(coords).duplicate();
                changed.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, BasementType.NONE.ordinal()));
                board.setHex(coords, changed);
                source.refresh();
                source.takeFrame();
                assertEquals(List.of(updated.get(1), updated.get(2)), source.editorBadges().get(coords));

                changed = board.getHex(coords).duplicate();
                changed.removeTerrain(Terrains.BLACK_ICE);
                board.setHex(coords, changed);
                source.refresh();
                source.takeFrame();
                assertEquals(List.of(updated.get(2)), source.editorBadges().get(coords));

                changed = board.getHex(coords).duplicate();
                changed.removeTerrain(Terrains.METAL_CONTENT);
                board.setHex(coords, changed);
                source.refresh();
                var cleared = source.takeFrame().scene();
                assertTrue(source.editorBadges().isEmpty(), "Removing the final flag removes the stack");
                assertTrue(cleared.tactical().fills().isEmpty());
                assertTrue(cleared.tactical().labels().isEmpty());
                assertEquals(4, original.size(), "Earlier frame snapshots remain untouched through all edits");
                assertThrows(UnsupportedOperationException.class, original::clear);
            }
        });
    }

    private static List<BoardEditorTerrain.Kind> kinds(List<BoardEditorTerrain.Badge> badges) {
        return badges.stream().map(BoardEditorTerrain.Badge::kind).toList();
    }
}
