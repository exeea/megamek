/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Maglev's legacy artwork becomes the same two terrain layers the editor and rules use. */
class BoardLegacyMaglevTest {
    private static final Coords AT = new Coords(1, 1);

    private static Board board(String terrain) {
        Board board = Board.createEmptyBoard(3, 3);
        board.setHex(AT, new Hex(0, terrain, ""));
        return board;
    }

    @Test
    void groundWaterAndRaisedMaglevBecomeTerrainAndRoundTripWithoutMarkers() throws Exception {
        for (String base : List.of("", "water:2;", "heavy_industrial:2;")) {
            Board board = board(base + "fluff:9:7");
            assertTrue(BoardSceneryLayouts.convert(board).isEmpty());
            Hex hex = board.getHex(AT);
            boolean deck = !base.isEmpty();
            assertEquals(2, hex.terrainLevel(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL));
            assertEquals(deck, hex.containsTerrain(Terrains.BRIDGE));
            assertTrue(hex.getDecorations().stream().noneMatch(RailRoute::isRoute));
            var marker = RailRoute.find(hex, RailRoute.Kind.MAGLEV);
            assertEquals(9, marker.connections());
            assertEquals(deck, RailRoute.onDeck(marker));
            if (deck) {
                assertTrue(HexAppearance.implicit(hex.getAppearance()));
                assertEquals(4, hex.terrainLevel(Terrains.BRIDGE));
                assertEquals(650, hex.terrainLevel(Terrains.BRIDGE_CF));
                assertEquals(base.startsWith("water") ? 0 : 2, hex.terrainLevel(Terrains.BRIDGE_ELEV));
                assertFalse(hex.containsTerrain(Terrains.BRIDGE_ROAD), "The guideway is not an asphalt road");
            }
            assertTrue(hex.getDecorations().stream().filter(object -> RailRoute.rides(object.asset()) == RailRoute.Kind.MAGLEV)
                  .allMatch(object -> object.placement().equals(marker.placement())), "The train still rides its track");
            var bytes = new ByteArrayOutputStream();
            BoardFile.write(board, bytes);
            Hex saved = BoardFile.readNative(bytes.toString(StandardCharsets.UTF_8)).getHex(AT);
            assertEquals(RailRoute.markers(hex, RailRoute.Kind.MAGLEV), RailRoute.markers(saved, RailRoute.Kind.MAGLEV));
            assertEquals(hex.getDecorations(), saved.getDecorations());
        }
    }

    @Test
    void existingDeckKeepsItsStructureAndCollapseRemovesOnlyItsMaglev() {
        Board board = board("bridge:1:9;bridge_elev:2;bridge_cf:40;rail:1:18;fluff:9:0");
        BoardSceneryLayouts.convert(board);
        Hex hex = board.getHex(AT);
        assertEquals(2, hex.terrainLevel(Terrains.BRIDGE_RAIL));
        assertEquals(1, hex.terrainLevel(Terrains.BRIDGE));
        assertEquals(40, hex.terrainLevel(Terrains.BRIDGE_CF));
        assertFalse(HexAppearance.implicit(hex.getAppearance()));
        assertFalse(hex.containsTerrain(Terrains.BRIDGE_ROAD));
        hex.removeBridge();
        assertTrue(RailRoute.markers(hex, RailRoute.Kind.MAGLEV).isEmpty());
        assertEquals(1, hex.terrainLevel(Terrains.RAIL), "The ordinary rail beneath survives");
    }

    @Test
    void perpendicularLegacyMaglevWidensTheDeckWithoutWideningItsRoad() {
        Board board = board("pavement:1:9;bridge:1:9;road:1:9;bridge_cf:120;bridge_elev:3;fluff:9:2");
        // The shipped crossing has road approaches at deck height; a road beneath the bridge is not a deck road.
        board.setHex(AT.translated(0), new Hex(3, "road:1:9", ""));
        board.setHex(AT.translated(3), new Hex(3, "road:1:9", ""));
        var issues = BoardSceneryLayouts.convert(board);
        Hex hex = board.getHex(AT);
        assertEquals(45, hex.getTerrain(Terrains.BRIDGE).getExits());
        assertEquals(36, hex.getTerrain(Terrains.BRIDGE_RAIL).getExits());
        assertEquals(9, hex.getTerrain(Terrains.BRIDGE_ROAD).getExits());
        assertEquals(120, hex.terrainLevel(Terrains.BRIDGE_CF));
        assertEquals(3, hex.terrainLevel(Terrains.BRIDGE_ELEV));
        assertFalse(HexAppearance.implicit(hex.getAppearance()));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).text().startsWith("Bridge exits were widened"));
    }

    @Test
    void maglevOverBuildingIsOmittedWithItsRidersAndAnIssue() {
        Board board = board("building:2;bldg_elev:2;bldg_cf:50;fluff:9:7");
        var issues = BoardSceneryLayouts.convert(board);
        assertEquals(1, issues.size());
        Hex hex = board.getHex(AT);
        assertFalse(hex.containsAnyTerrainOf(Terrains.RAIL, Terrains.BRIDGE_RAIL, Terrains.BRIDGE));
        assertTrue(hex.getDecorations().stream().noneMatch(object -> RailRoute.kind(object) == RailRoute.Kind.MAGLEV
              || RailRoute.rides(object.asset()) == RailRoute.Kind.MAGLEV));
        assertEquals(2, hex.terrainLevel(Terrains.BUILDING));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MEGAMEK_LEGACY_CORPUS", matches = "1")
    void shippedMaglevCensusKeepsTheAcceptedLayersAndReportsEveryOmission() throws Exception {
        int ground = 0, implicit = 0, existing = 0, omitted = 0, zeroElevation = 0, pillars = 0;
        try (var files = Files.walk(Configuration.boardsDir().toPath())) {
            for (var file : files.filter(path -> path.toString().endsWith(".board")).sorted().toList()) {
                if (!Files.readString(file, StandardCharsets.ISO_8859_1).contains("fluff:9")) { continue; }
                Board board = BoardFile.read(file);
                var issues = BoardSceneryLayouts.convert(board);
                omitted += (int) issues.stream().filter(issue -> issue.text().contains("was omitted")).count();
                for (int x = 0; x < board.getWidth(); x++) {
                    for (int y = 0; y < board.getHeight(); y++) {
                        Hex hex = board.getHex(x, y);
                        assertTrue(hex.getDecorations().stream().noneMatch(RailRoute::isRoute), file + " " + x + "," + y);
                        if (hex.terrainLevel(Terrains.RAIL) == 2) { ground++; }
                        if (hex.terrainLevel(Terrains.BRIDGE_RAIL) != 2) { continue; }
                        assertEquals(0, hex.getTerrain(Terrains.BRIDGE_RAIL).getExits() & ~RailRoute.bridgeExits(hex));
                        if (HexAppearance.implicit(hex.getAppearance())) {
                            implicit++;
                            if (hex.terrainLevel(Terrains.BRIDGE_ELEV) == 0) { zeroElevation++; }
                            if (HexAppearance.pillars(hex.getAppearance())) { pillars++; }
                        } else { existing++; }
                    }
                }
            }
        }
        System.out.printf("MAGLEV ground=%d implicit=%d existing=%d omitted=%d zeroElevation=%d pillars=%d%n",
              ground, implicit, existing, omitted, zeroElevation, pillars);
        assertEquals(1903, ground);
        assertEquals(257, implicit);
        assertEquals(23, existing);
        assertEquals(28, omitted);
        assertEquals(72, zeroElevation);
        assertEquals(200, pillars);
    }
}
