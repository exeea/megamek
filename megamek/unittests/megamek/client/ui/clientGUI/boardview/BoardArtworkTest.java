/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardArtworkTest {
    private static final Map<Integer, String> STRUCTURES = Map.of(
          Terrains.BUILDING, "buildings/saxarba/test/building",
          Terrains.FUEL_TANK, "buildings/saxarba/test/tank",
          Terrains.INDUSTRIAL, "buildings/test/industrial");
    /** Each structure's art marks its own pixel, so the tileset image shows whose art it holds. */
    private static final Map<String, Integer> MARKS = Map.of("models/board/tileset/saxarba/test/building.png", 10,
          "models/board/tileset/saxarba/test/tank.png", 20, "models/board/tileset/test/industrial.png", 30);

    @TempDir
    Path directory;

    @BeforeEach
    void tileset() throws Exception {
        Path tileset = Files.createDirectories(directory.resolve("models/board/tileset"));
        Files.writeString(tileset.resolve("saxarba.tileset"), """
              super * "building:1" "" "saxarba/test/building.png"
              super * "fuel_tank:1" "" "saxarba/test/tank.png"
              super * "heavy_industrial:1" "" "test/industrial.png"
              base * "" "" "ground.png"
              """);
        for (String name : List.of("models/board/tileset/ground.png",
              "models/board/tileset/saxarba/test/building.png", "models/board/tileset/saxarba/test/tank.png",
              "models/board/tileset/test/industrial.png", "images/hexes/transparent/HexMask.png")) {
            var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xffffffff);
            if (MARKS.containsKey(name)) { image.setRGB(MARKS.get(name), MARKS.get(name), 0xffff0000); }
            Path file = directory.resolve(name);
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        }
    }

    @Test
    void customKitsWithoutBoardModelsAreSelectedForEveryStructureType() throws Exception {
        for (String name : List.of("building", "tank", "industrial")) {
            model("models/buildings/test/" + name + ".glb");
        }
        assertEquals(STRUCTURES, capture().structureModels());
    }

    @Test
    void boardGlbModelsKeepEachStructuresOwnTilesetProvenance() throws Exception {
        for (String asset : STRUCTURES.values()) { model("models/board/" + asset + ".glb"); }
        assertEquals(STRUCTURES, capture().structureModels());
    }

    @Test
    void customKitsAndBoardFallbacksCanCoexistInOneHex() throws Exception {
        model("models/buildings/test/building.glb");
        model("models/buildings/test/tank.glb");
        model("models/board/" + STRUCTURES.get(Terrains.INDUSTRIAL) + ".glb");
        assertEquals(STRUCTURES, capture().structureModels());
    }

    @Test
    void obsoleteG3djFilesDoNotAdvertiseModelsThatTheRendererCannotLoad() throws Exception {
        for (String asset : STRUCTURES.values()) { model("models/board/" + asset + ".g3dj"); }
        assertEquals(Map.of(), capture().structureModels());
    }

    @Test
    void tacticalArtLeavesOutTheStructuresStandingAsModels() throws Exception {
        model("models/board/" + STRUCTURES.get(Terrains.BUILDING) + ".glb");
        model("models/buildings/test/tank.glb");
        BufferedImage art = capture().tileset();
        assertEquals(0, art.getRGB(10, 10) >>> 24, "The building's model replaces its art");
        assertEquals(0, art.getRGB(20, 20) >>> 24, "So does the tank's modular kit");
        assertNotEquals(0, art.getRGB(30, 30) >>> 24, "An unmodelled structure keeps its art");
    }

    private void model(String name) throws Exception {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        // Artwork discovery checks existence; decoding belongs to the renderer's asset tests.
        Files.createFile(file);
    }

    private BoardArtwork.HexImage capture() throws Exception {
        var task = new FutureTask<BoardArtwork.HexImage>(() -> {
            File data = Configuration.dataDir();
            Configuration.setDataDir(directory.toFile());
            try (var artwork = new BoardArtwork()) {
                Board board = Board.createEmptyBoard(1, 1);
                var coords = new Coords(0, 0);
                board.setHex(coords, new Hex(0, "building:1;bldg_elev:2;bldg_cf:15;fuel_tank:1;"
                      + "fuel_tank_elev:1;fuel_tank_cf:15;fuel_tank_magn:10;heavy_industrial:1", ""));
                return artwork.capture(board, coords, true);
            } finally {
                Configuration.setDataDir(data);
            }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
