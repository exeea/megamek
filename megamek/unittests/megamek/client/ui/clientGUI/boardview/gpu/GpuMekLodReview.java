/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
 * A Mek with two levels of detail, built from the shipped Atlas. Weapons show at both levels, focused units retain
 * LOD0, and an absent optional LOD1 keeps the same unit's LOD0 body.
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
        nearBody.put("detail", "lod0");
        json.writeValue(modular.resolve("bodies/atlas-near.json").toFile(), nearBody);
        ObjectNode twoLevels = (ObjectNode) json.readTree(modular.resolve("meks/atlas.json").toFile());
        twoLevels.put("body", "units/modular/bodies/atlas-near.json");
        twoLevels.put("bodyLod1", "units/modular/bodies/atlas.json");
        json.writeValue(modular.resolve("meks/atlas-lod.json").toFile(), twoLevels);
        twoLevels.remove("bodyLod1");
        json.writeValue(modular.resolve("meks/atlas-near-only.json").toFile(), twoLevels);
        twoLevels.put("bodyLod1", "units/modular/bodies/missing-lod1.json");
        json.writeValue(modular.resolve("meks/atlas-missing-lod1.json").toFile(), twoLevels);

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
            verifyPackedBody(library, tileset, "meks/3039u/Phoenix Hawk PXH-1.mtf", "phoenix-hawk", 910);
            verifyPackedBody(library, tileset, "meks/3085u/Phoenix/Phoenix Hawk IIC 3.mtf", "phoenix-hawk-iic", 911);
            int bodyTriangles = library.modular("units/modular/bodies/atlas.json").triangles();
            GpuUnitModel mek = library.get(selection(captured, MEKS + "atlas-lod.json", atlas), atlas.getId());
            assertNotNull(mek, "The Atlas with a near and a far body must assemble");
            assertFalse(mek.detailLevels().lod1().isEmpty());
            assertFalse(mek.equipment().isEmpty(), "The Atlas AS7-D carries weapons");
            var instance = new GpuUnitInstance(mek);
            int weapons = drawnTriangles(instance.nodes) - bodyTriangles;
            assertTrue(weapons > 0);
            // Small on screen: the far body replaces the near one, and the weapons stay.
            float small = 64 / mek.figureHeight();
            instance.bodyDetail(small, false);
            assertEquals(1, instance.detailLevel());
            assertEquals(bodyTriangles + weapons, drawnTriangles(instance.nodes));
            assertNoNearPartDrawn(instance.nodes, mek.detailLevels());
            // Zoomed in again, the near body returns.
            instance.bodyDetail(10, false);
            assertEquals(0, instance.detailLevel());
            assertNoFarPartDrawn(instance.nodes, mek.detailLevels());
            // A Mek in focus keeps its near body however small it is.
            instance.bodyDetail(small, true);
            assertEquals(0, instance.detailLevel());

            for (String asset : new String[] { "atlas-near-only.json", "atlas-missing-lod1.json" }) {
                GpuUnitModel fallback = library.get(selection(captured, MEKS + asset, atlas), atlas.getId() + 1);
                assertNotNull(fallback, "Missing optional LOD1 retains the same unit's LOD0 body");
                assertEquals(fallback.detailLevels().lod0(), fallback.detailLevels().lod1());
                var fallbackInstance = new GpuUnitInstance(fallback);
                fallbackInstance.bodyDetail(small, false);
                assertEquals(0, fallbackInstance.detailLevel());
                assertEquals(bodyTriangles + weapons, drawnTriangles(fallbackInstance.nodes));
            }
        } finally {
            library.dispose();
        }
    }

    /** Each chassis selects two levels from its own GLB while retaining its equipment. */
    private static void verifyPackedBody(GpuUnitModels library, MekTileset tileset, String unitFile, String name, int id)
          throws Exception {
        Mek unit = (Mek) new MekFileParser(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"), unitFile).getEntity();
        unit.setId(id);
        var captured = UnitModelSelection.capture(unit, -1, false, tileset);
        var body = library.modular("units/modular/bodies/" + name + ".json");
        assertNotNull(body);
        assertTrue(body.triangles(1) < body.triangles(), "The same chassis has an authored simpler level");
        var model = library.get(selection(captured, MEKS + name + ".json", unit), id);
        assertNotNull(model);
        var instance = new GpuUnitInstance(model);
        // Anatomy filtering removes unused hand/arm alternatives before either level is drawn.
        int lod0 = meshTriangles(instance.nodes, model.detailLevels().lod0());
        int lod1 = meshTriangles(instance.nodes, model.detailLevels().lod1());
        assertTrue(lod0 > lod1 && lod1 > 0);
        int equipment = drawnTriangles(instance.nodes) - lod0;
        instance.bodyDetail(64 / model.figureHeight(), false);
        assertEquals(1, instance.detailLevel());
        assertEquals(lod1 + equipment, drawnTriangles(instance.nodes));
        assertNoNearPartDrawn(instance.nodes, model.detailLevels());
        instance.bodyDetail(20 / model.figureHeight(), true);
        assertEquals(0, instance.detailLevel());
        assertEquals(lod0 + equipment, drawnTriangles(instance.nodes));
        int lod2 = meshTriangles(instance.nodes, model.detailLevels().lod2());
        instance.bodyDetail(20 / model.figureHeight(), false);
        assertEquals(model.detailLevels().resolvedLevel(2), instance.detailLevel());
        assertEquals(lod2 + equipment, drawnTriangles(instance.nodes));

        // A severed arm, including its equipment and every LOD, stays absent across zoom and focus changes.
        UnitDamageDisplay.show(instance, new BoardScene.LocationDamage(Set.of("RA"), Set.of()));
        for (boolean focused : new boolean[] { true, false }) {
            instance.bodyDetail(20 / model.figureHeight(), focused);
            for (var part : UnitDamageDisplay.locationParts(instance, "RA")) { assertFalse(part.enabled); }
        }
    }

    private static int meshTriangles(Iterable<Node> nodes, java.util.Set<com.badlogic.gdx.graphics.Mesh> meshes) {
        int result = 0;
        for (var node : nodes) {
            for (var part : node.parts) {
                if (meshes.contains(part.meshPart.mesh)) { result += part.meshPart.size / 3; }
            }
            result += meshTriangles(node.getChildren(), meshes);
        }
        return result;
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
                assertFalse(part.enabled && levels.lod0().contains(part.meshPart.mesh)
                      && !levels.lod1().contains(part.meshPart.mesh), node.id);
            }
            assertNoNearPartDrawn(node.getChildren(), levels);
        }
    }

    private static void assertNoFarPartDrawn(Iterable<Node> nodes, GpuUnitModel.DetailLevels levels) {
        for (Node node : nodes) {
            for (var part : node.parts) {
                assertFalse(part.enabled && levels.lod1().contains(part.meshPart.mesh)
                      && !levels.lod0().contains(part.meshPart.mesh), node.id);
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
