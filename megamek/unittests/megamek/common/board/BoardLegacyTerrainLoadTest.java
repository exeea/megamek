/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardLegacyTerrainLoadTest {
    @TempDir
    Path directory;

    @Test
    void emptyMarkersDoNotChangeTheBuildingOrItsGround() throws IOException {
        Board board = read("none:2:4;bldg_elev:3;building:4:0;bldg_cf:105;none:1");
        var hex = board.getHex(0, 0);
        assertFalse(hex.containsTerrain(0));
        assertEquals(1, hex.getLevel());
        assertEquals("grass", hex.getTheme());
        assertEquals(4, hex.terrainLevel(Terrains.BUILDING));
        assertEquals(3, hex.terrainLevel(Terrains.BLDG_ELEV));
        assertEquals(105, hex.terrainLevel(Terrains.BLDG_CF));
        assertEquals(0, hex.getTerrain(Terrains.BUILDING).getExits());
    }

    @ParameterizedTest
    @ValueSource(strings = { "none", "none:1", "none:2:4" })
    void anEmptyMarkerAloneLoadsAsClearGround(String terrain) throws IOException {
        var hex = read(terrain).getHex(0, 0);
        assertEquals(0, hex.terrainsPresent());
        assertEquals(1, hex.getLevel());
    }

    @Test
    void theLegacyElevatorNameStillLoads() throws IOException {
        var hex = read("none:1;elevator:2:4").getHex(0, 0);
        assertEquals(2, hex.terrainLevel(Terrains.SOLARIS_ELEVATOR));
        assertEquals(4, hex.getTerrain(Terrains.SOLARIS_ELEVATOR).getExits());
        assertFalse(hex.containsTerrain(0));
    }

    @Test
    void unknownTerrainIsReportedInsteadOfBecomingAnEmptyMarker() throws IOException {
        Path file = directory.resolve("misspelled.board");
        Files.writeString(file, source("none:1;wooods:1"));
        for (boolean continueOnError : new boolean[] { false, true }) {
            IOException failure = assertThrows(IOException.class, () -> BoardFile.read(file, continueOnError));
            assertTrue(failure.getMessage().contains("Hex 0101"));
            assertTrue(failure.getMessage().contains("Unknown terrain name: wooods"));
        }
    }

    @Test
    void directStreamLoadingAlsoRejectsUnknownTerrain() {
        var errors = new ArrayList<String>();
        var input = new ByteArrayInputStream(source("road:1;misspelled:1").getBytes(StandardCharsets.UTF_8));
        var failure = assertThrows(IllegalArgumentException.class, () -> new Board().load(input, errors, true));
        assertTrue(failure.getMessage().contains("misspelled:1"));
    }

    @Test
    void malformedKnownTerrainReportsTheHexAndToken() throws IOException {
        IOException failure = assertThrows(IOException.class, () -> read("woods:invalid"));
        assertTrue(failure.getMessage().contains("Hex 0101"));
        assertTrue(failure.getMessage().contains("woods:invalid"));
    }

    private Board read(String terrain) throws IOException {
        Path file = directory.resolve("legacy.board");
        Files.writeString(file, source(terrain));
        return BoardFile.read(file);
    }

    private static String source(String terrain) {
        return "size 1 1\nhex 0101 1 \"" + terrain + "\" \"grass\"\nend\n";
    }
}
