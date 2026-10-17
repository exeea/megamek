/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The session commands the Assets panel's cards use: a card's optional field set to None leaves the brush, Edit's Type
 * swaps an object to another member of its family in place, the DropShip blueprint builds seven hexes at once, and a
 * preset's terrain outside its component (Hazardous liquid's water) is painted too.
 */
class BoardEditorPaletteCommandsTest {
    private static final String POND = "scenery/parks/pond-1", POOL = "scenery/pools/rectangular";

    @Test
    void noneRemovesAnOfferedFieldFromTheBrush() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "water", "shallow");
        send(session, Action.BRUSH_VALUE, "terrain:rapids", "1");
        assertNotNull(session.snapshot().activeBrush().property("rapids"));
        send(session, Action.BRUSH_VALUE, "terrain:rapids", "");
        assertNull(session.snapshot().activeBrush().property("rapids"), "Current: None");
        Coords at = new Coords(3, 3);
        click(session, at);
        Hex hex = session.board().getHex(at);
        assertTrue(hex.containsTerrain(Terrains.WATER) && !hex.containsTerrain(Terrains.RAPIDS));
    }

    @Test
    void hazardousLiquidPaintsItsWaterWhereThereIsNone() {
        var session = new BoardEditorSession();
        Coords dry = new Coords(3, 3), lake = new Coords(6, 6);
        session.board().setHex(lake, new Hex(0, "water:3", ""));
        send(session, Action.CHOOSE_BRUSH, "hazards", "hazard-liquid");
        click(session, dry);
        click(session, lake);
        assertEquals(List.of(1, 1), List.of(session.board().getHex(dry).terrainLevel(Terrains.WATER),
              session.board().getHex(dry).terrainLevel(Terrains.HAZARDOUS_LIQUID)), "The card's pool, as its sample shows it");
        assertEquals(List.of(3, 1), List.of(session.board().getHex(lake).terrainLevel(Terrains.WATER),
              session.board().getHex(lake).terrainLevel(Terrains.HAZARDOUS_LIQUID)), "A lake keeps its depth");
        send(session, Action.TOOL, "", "ERASE");
        click(session, dry);
        assertEquals(1, session.board().getHex(dry).terrainLevel(Terrains.WATER), "Erase takes the hazards only");
        assertFalse(session.board().getHex(dry).containsTerrain(Terrains.HAZARDOUS_LIQUID));
    }

    @Test
    void typeSwapsAPondToAPoolInPlaceInOneUndoStepAndRefusesAnotherFamily() {
        var session = new BoardEditorSession();
        Coords at = new Coords(2, 2);
        send(session, Action.ASSET, "", POND);
        click(session, at);
        send(session, Action.OBJECT_VALUE, "rotation", "30");
        send(session, Action.OBJECT_VALUE, "scale", "1.5");
        BoardDecoration pond = objects(session, at).getFirst();
        assertEquals("Kidney pond", pond.name());
        send(session, Action.OBJECT_VALUE, "asset", POOL);
        BoardDecoration pool = objects(session, at).getFirst();
        assertEquals(List.of(pond.id(), POOL, pond.x(), pond.y(), 30.0, 1.5),
              List.of(pool.id(), pool.asset(), pool.x(), pool.y(), pool.rotation(), pool.scale()));
        assertNull(pool.name(), "The pond's alias name goes with it");
        assertEquals(BoardDecoration.Colours.NONE, pool.colours(), "The pond's colours were its member's");
        send(session, Action.OBJECT_VALUE, "asset", "scenery/vehicles/car");
        assertEquals(POOL, objects(session, at).getFirst().asset(), "A car is no pool");
        send(session, Action.UNDO, "", "");
        assertEquals(pond, objects(session, at).getFirst(), "One undo step brings the pond back");
    }

    @Test
    void theDropShipBuildsSevenHexesOverTheirPavementInOneUndoStep() {
        var session = new BoardEditorSession();
        Coords centre = new Coords(5, 5);
        List<Coords> around = List.of(centre, centre.translated(0), centre.translated(1), centre.translated(2),
              centre.translated(3), centre.translated(4), centre.translated(5));
        around.forEach(at -> session.board().setHex(at, new Hex(0, "pavement:1", "")));
        send(session, Action.CHOOSE_BRUSH, "building", "dropship");
        click(session, centre);
        // The centre is piece 72; by direction N, NE, SE, S, SW, NW the shipped boards' 70, 69, 71, 73, 74, 75.
        assertEquals(List.of(72, 70, 69, 71, 73, 74, 75), around.stream()
              .map(at -> session.board().getHex(at).getTerrain(Terrains.BUILDING).getExits()).toList());
        assertTrue(around.stream().allMatch(at -> session.board().getHex(at).containsTerrain(Terrains.PAVEMENT)), "Pavement stays");
        send(session, Action.UNDO, "", "");
        assertTrue(around.stream().noneMatch(at -> session.board().getHex(at).containsTerrain(Terrains.BUILDING)), "One undo step");
        assertFalse(session.snapshot().canUndo());
        Coords edge = new Coords(0, 0);
        click(session, edge);
        assertEquals(Messages.getString("BoardEditor.blueprintOffBoard"), session.snapshot().message());
        assertFalse(session.board().getHex(edge).containsTerrain(Terrains.BUILDING), "At the edge nothing is built");
    }

    private static List<BoardDecoration> objects(BoardEditorSession session, Coords coords) {
        return session.board().getHex(coords).getDecorations();
    }

    private static void click(BoardEditorSession session, Coords coords) {
        session.pointer(coords, 0, 0, false);
        session.finishStroke();
        session.release();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
