/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.g3d.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Mek;

/**
 * A Mek with two levels of detail, built from the shipped Atlas: its body copied as a near body, the original as the
 * far one. Near up close, far once small on screen, near again in focus; the weapons show at both levels. A near body
 * with no far body is refused, so it can never be drawn at its full cost from afar.
 */
final class GpuMekLodReview {
    private static final String MEKS = "units/modular/meks/";

    private GpuMekLodReview() { }

    /** @param scratch an empty folder the review copies the modular assets into */
    static void verify(Path scratch) throws Exception {
        Path root = scratch.resolve("models");
        Path modular = root.resolve("units/modular");
        copy(Configuration.dataDir().toPath().resolve("models/units/modular"), modular);
        var json = new ObjectMapper();
        ObjectNode nearBody = (ObjectNode) json.readTree(modular.resolve("bodies/atlas.json").toFile());
        nearBody.put("detail", "near");
        json.writeValue(modular.resolve("bodies/atlas-near.json").toFile(), nearBody);
        ObjectNode twoLevels = (ObjectNode) json.readTree(modular.resolve("meks/atlas.json").toFile());
        twoLevels.put("body", "units/modular/bodies/atlas-near.json");
        twoLevels.put("farBody", "units/modular/bodies/atlas.json");
        json.writeValue(modular.resolve("meks/atlas-lod.json").toFile(), twoLevels);
        twoLevels.remove("farBody");
        json.writeValue(modular.resolve("meks/atlas-near-only.json").toFile(), twoLevels);

        EquipmentType.initializeTypes();
        var tileset = new MekTileset(new File(Configuration.dataDir(), "images/units"));
        tileset.loadFromFile("mekset.txt");
        Mek atlas = (Mek) new MekFileParser(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"),
              "meks/3039u/Atlas AS7-D.mtf").getEntity();
        assertNotNull(atlas);
        atlas.setId(900);
        var captured = UnitModelSelection.capture(atlas, -1, false, tileset);
        var library = new GpuUnitModels(root);
        try {
            int bodyTriangles = library.modular("units/modular/bodies/atlas.json").triangles();
            GpuUnitModel mek = library.get(selection(captured, MEKS + "atlas-lod.json", atlas), atlas.getId());
            assertNotNull(mek, "The Atlas with a near and a far body must assemble");
            assertFalse(mek.detailLevels().far().isEmpty());
            assertFalse(mek.equipment().isEmpty(), "The Atlas AS7-D carries weapons");
            var instance = new GpuUnitInstance(mek);
            int weapons = drawnTriangles(instance.nodes) - bodyTriangles;
            assertTrue(weapons > 0);
            // Small on screen: the far body replaces the near one, and the weapons stay.
            float small = 20 / mek.figureHeight();
            instance.farDetail(small, false);
            assertEquals(1, instance.detailLevel());
            assertEquals(bodyTriangles + weapons, drawnTriangles(instance.nodes));
            assertNoNearPartDrawn(instance.nodes, mek.detailLevels());
            // Zoomed in again, the near body returns.
            instance.farDetail(10, false);
            assertEquals(0, instance.detailLevel());
            assertNoFarPartDrawn(instance.nodes, mek.detailLevels());
            // A Mek in focus keeps its near body however small it is.
            instance.farDetail(small, true);
            assertEquals(0, instance.detailLevel());

            assertNull(library.get(selection(captured, MEKS + "atlas-near-only.json", atlas), atlas.getId() + 1),
                  "A near body without a far body must fall back rather than draw at its full cost");
        } finally {
            library.dispose();
        }
    }

    private static BoardScene.UnitModel selection(BoardScene.UnitModel captured, String asset, Mek mek) {
        return new BoardScene.UnitModel(asset, null, captured.variant(), captured.figures(), captured.twist(),
              captured.damage(), UnitModelState.capture(mek));
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
    }

    private static void assertNoNearPartDrawn(Iterable<Node> nodes, GpuUnitModel.DetailLevels levels) {
        for (Node node : nodes) {
            for (var part : node.parts) {
                assertFalse(part.enabled && levels.near().contains(part.meshPart.mesh), node.id);
            }
            assertNoNearPartDrawn(node.getChildren(), levels);
        }
    }

    private static void assertNoFarPartDrawn(Iterable<Node> nodes, GpuUnitModel.DetailLevels levels) {
        for (Node node : nodes) {
            for (var part : node.parts) {
                assertFalse(part.enabled && levels.far().contains(part.meshPart.mesh), node.id);
            }
            assertNoFarPartDrawn(node.getChildren(), levels);
        }
    }

    private static int drawnTriangles(Iterable<Node> nodes) {
        int total = 0;
        for (Node node : nodes) {
            for (var part : node.parts) {
                if (part.enabled) {
                    total += part.meshPart.size / 3;
                }
            }
            total += drawnTriangles(node.getChildren());
        }
        return total;
    }
}
