/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * Work under way shows as ghosts of what it builds: the bridge's pieces, the sandbags, the cleared path, the trench. A
 * dug-in platoon's trench is part of the scene.
 */
class GpuWorkGhostsTest {
    private static final Coords BRIDGE = new Coords(8, 3), FORTIFY = new Coords(2, 3), RUBBLE = new Coords(5, 3),
          DIGGING = new Coords(2, 6), DUG = new Coords(5, 6);

    private static Board board() {
        int width = 12, height = 8;
        Hex[] hexes = new Hex[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Hex hex = new Hex(0);
                if (x == BRIDGE.getX()) { hex.addTerrain(new Terrain(Terrains.WATER, 1)); }
                if (x == RUBBLE.getX() && y == RUBBLE.getY()) { hex.addTerrain(new Terrain(Terrains.RUBBLE, 2)); }
                hexes[y * width + x] = hex;
            }
        }
        return new Board(width, height, hexes);
    }

    private static boolean trench(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> feature.asset().equals(BoardFeatures.TRENCH));
    }

    @Test
    void workUnderWayShowsWhatItBuildsUntilItIsDone() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            AtomicReference<GpuEditorPreview.Data> ghosts = new AtomicReference<>();
            var work = List.of(new TacticalMark.BridgeBuild(BRIDGE, 2, 6, (1 << 1) | (1 << 4), 2, false),
                  new TacticalMark.Fortify(FORTIFY, 1, 2), new TacticalMark.RubbleClear(RUBBLE, 1, 3),
                  new TacticalMark.DugIn(DIGGING, false, "Digging in"), new TacticalMark.DugIn(DUG, true, "Dug in"));
            SwingUtilities.invokeAndWait(() -> {
                assertSame(GpuEditorPreview.Data.NONE, fixture.source.workGhosts());
                fixture.view.addMarks(work);
                fixture.source.refresh();
                ghosts.set(fixture.source.workGhosts());
                fixture.source.refresh();
                assertSame(ghosts.get(), fixture.source.workGhosts(), "Unchanged work keeps its ghosts");
            });
            var scene = fixture.source.takeFrame().scene();
            var data = ghosts.get();
            assertTrue(data.routes().containsKey(BRIDGE), "The bridge is built by the route builders");
            assertTrue(data.routes().get(BRIDGE).features().stream().anyMatch(feature -> feature.asset().equals("bridge")));
            for (Coords coords : List.of(FORTIFY, RUBBLE, DIGGING)) {
                assertTrue(data.objects().get(coords).stream().anyMatch(feature -> feature.authoredPlacement()
                            && !scene.tile(coords).features().contains(feature)),
                      "New terrain-owned scenery at " + coords);
            }
            assertTrue(data.hexes().isEmpty() && data.standIns().isEmpty() && data.label().isEmpty(),
                  "No editor stand-ins, outlines or label in a game");
            assertTrue(!data.objects().containsKey(DUG) && trench(scene.tile(DUG)), "A dug-in platoon's trench stands");
            assertTrue(!trench(scene.tile(DIGGING)), "A trench being dug is only a ghost");
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.removeMarks(work);
                fixture.source.refresh();
                assertSame(GpuEditorPreview.Data.NONE, fixture.source.workGhosts());
            });
            assertTrue(!trench(fixture.source.takeFrame().scene().tile(DUG)), "The trench goes with its platoon");
        }
    }
}
