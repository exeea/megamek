/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** What the board shows of work the game tracks without terrain of its own: saws clearing woods, Solaris elevators. */
class BoardSiteWorksTest {
    private static final Coords CENTER = new Coords(1, 1);

    private static BoardScene scene(Hex middle) {
        Hex[] hexes = new Hex[9];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        hexes[CENTER.getY() * 3 + CENTER.getX()] = middle;
        Board board = new Board(3, 3, hexes);
        return new BoardScene(0, 3, 3, BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of(),
              new BoardScene.Light(1, -1));
    }

    private static long count(BoardScene.Tile tile, String asset) {
        return tile.features().stream().filter(feature -> feature.asset().equals(asset)).count();
    }

    @Test
    void sawsFellAShareOfTheTreesForEachTurnOfWorkLeavingAStumpAndALogForEach() {
        Hex woods = new Hex(0);
        woods.addTerrain(new Terrain(Terrains.WOODS, 2));
        woods.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        BoardScene.Tile tile = scene(woods).tile(CENTER);
        long trees = tile.features().stream().filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).count();
        assertTrue(trees > 3, "Heavy woods stand on the hex");
        BoardScene.Tile started = BoardFeatures.withSawClearing(tile, 2), nearlyDone = BoardFeatures.withSawClearing(tile, 1);
        long firstTurn = count(started, BoardFeatures.SAWN_STUMP), secondTurn = count(nearlyDone, BoardFeatures.SAWN_STUMP);
        assertTrue(firstTurn > 0 && secondTurn > firstTurn && secondTurn < trees, firstTurn + " then " + secondTurn);
        assertEquals(secondTurn, count(nearlyDone, BoardFeatures.SAWN_LOG));
        assertEquals(trees - secondTurn,
              nearlyDone.features().stream().filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).count(),
              "Each stump stands where a tree was felled");
        for (var log : nearlyDone.features().stream().filter(f -> f.asset().equals(BoardFeatures.SAWN_LOG)).toList()) {
            assertTrue(Math.hypot(log.x(), log.y()) < BoardGeometry.TILE_HEIGHT / 2f, "The logs stay in their hex");
        }
    }

    @Test
    void aRaisedElevatorStandsOnAnOpenLiftWithStraightWallsForPickingAndNoScatter() {
        Hex lift = new Hex(3);
        lift.addTerrain(new Terrain(Terrains.SOLARIS_ELEVATOR, 0, true, 1));
        BoardScene scene = scene(lift);
        BoardScene.Tile tile = scene.tile(CENTER);
        assertTrue(BoardFeatures.shaft(tile));
        assertFalse(BoardScatter.allowed(tile), "Nothing lies loose on a lift platform");
        var walls = new BoardSurface(scene, tile).walls(scene, BoardGeometry.floor(scene));
        assertFalse(walls.isEmpty());
        for (var face : walls) {
            var normal = face.a().cpy().sub(face.b()).crs(face.c().cpy().sub(face.b())).nor();
            assertEquals(0, normal.z, 1e-4, "The shaft's walls are straight, without a cliff's relief");
        }
        float top = BoardGeometry.surfaceZ(tile);
        var machine = BoardElevator.build(scene, CENTER, top, walls);
        var parts = machine.parts();
        assertTrue(parts.values().stream().allMatch(faces -> !faces.isEmpty()),
              "Deck, ram and scissors stand in a pit under the raised platform");
        float ground = BoardGeometry.surfaceZ(scene.tile(new Coords(0, 1)));
        assertTrue(parts.get(BoardElevator.Part.PIT).stream().anyMatch(face -> face.a().z < ground - BoardRelief.metres(1)),
              "The pit is sunk below the ground beside it");
        assertTrue(machine.wear(machine.joints().getFirst())
              > machine.wear(new com.badlogic.gdx.math.Vector3(BoardGeometry.center(CENTER, 0)).add(0, 0, top)),
              "Wear gathers at the lift's joints, not on its clean paint");
        var unraised = BoardElevator.build(scene, CENTER, top, List.of()).parts();
        assertTrue(unraised.entrySet().stream().allMatch(part -> part.getValue().isEmpty()
                    == (part.getKey() != BoardElevator.Part.PLATE && part.getKey() != BoardElevator.Part.HAZARD)),
              "A platform with no walls below it shows its top alone: diamond plate inside a striped band");
        // A moving platform's lift is built from its raised walls at each height it passes (GpuTerrain#present).
        float rising = ground + BoardRelief.metres(.3f);
        var low = BoardElevator.build(scene, CENTER, rising, walls).parts();
        assertFalse(low.get(BoardElevator.Part.STEEL).isEmpty(), "Barely risen, it already stands on its deck");
        assertTrue(low.values().stream().flatMap(List::stream).allMatch(face -> Math.max(face.a().z, Math.max(face.b().z, face.c().z))
              <= rising + 1e-4f), "Nothing of its lift rises past the platform");
    }
}
