/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnitModelDescriptorTest {
    private final Path assets = Configuration.dataDir().toPath().resolve("models/units/modular");
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path scratch;

    @Test
    void allModularAssetsValidateAgainstTheirActualMesh() throws Exception {
        var manifest = json.readTree(assets.resolve("manifest.json").toFile()).get("assets");
        for (var names = manifest.fieldNames(); names.hasNext();) {
            String key = names.next();
            Path file = assets.resolve(key + ".json");
            var descriptor = UnitModelDescriptor.read(file);
            Path mesh = UnitModelDescriptor.contained(assets, file.getParent().resolve(descriptor.mesh()));
            var levels = RigidGlb.loadLods(new FileHandle(mesh.toFile()));
            var data = levels.getFirst();
            descriptor.validate(data);
            for (int level = 1; level < levels.size(); level++) {
                if (levels.get(level) != levels.get(level - 1)) { descriptor.validate(levels.get(level), level); }
            }
            int triangles = 0;
            for (var part : data.meshes.first().parts) {
                triangles += part.indices.length / 3;
            }
            assertEquals(manifest.get(key).get("triangles").asInt(), triangles, key);
        }
    }

    @Test
    void anAssetMayReachTheSanityCeilingButNotPassIt() throws Exception {
        var descriptor = UnitModelDescriptor.read(assets.resolve("bodies/warhammer.json"));
        var data = meshData(assets.resolve("bodies/warhammer.glb"));
        resizeTo(data, UnitModelDescriptor.MAX_TRIANGLES);
        assertEquals(UnitModelDescriptor.MAX_TRIANGLES, descriptor.validate(data));
        resizeTo(data, UnitModelDescriptor.MAX_TRIANGLES + 1);
        assertThrows(IllegalArgumentException.class, () -> descriptor.validate(data));
    }

    @Test
    void bodiesSuitsAndEquipmentHaveNoArtBudgetInTheEngine() throws Exception {
        // The former caps (1,500 per body, 3,000 per LOD0 body, 330 per suit, 149 per equipment module) are gone:
        // the mm-data exporter enforces the art budgets, the engine only a sanity ceiling.
        assertValidatesAt("bodies/warhammer", 4000);
        assertValidatesAt("troops/battle-armor-standing", 5000);
        assertValidatesAt("equipment/ppc", 1000);
    }

    @Test
    void aMeshOtherThanGlbIsRejected() throws Exception {
        ObjectNode document = (ObjectNode) json.readTree(assets.resolve("equipment/ppc.json").toFile());
        document.put("mesh", "ppc.g3dj");
        Path broken = scratch.resolve("broken-mesh.json");
        json.writeValue(broken.toFile(), document);
        assertThrows(JsonMappingException.class, () -> UnitModelDescriptor.read(broken));
    }

    @Test
    void onlyABodyMayBeMarkedAsLod0() throws Exception {
        ObjectNode equipment = (ObjectNode) json.readTree(assets.resolve("equipment/ppc.json").toFile());
        equipment.put("detail", "lod0");
        Path broken = scratch.resolve("broken.json");
        json.writeValue(broken.toFile(), equipment);
        assertThrows(JsonMappingException.class, () -> UnitModelDescriptor.read(broken));
        ObjectNode body = (ObjectNode) json.readTree(assets.resolve("bodies/warhammer.json").toFile());
        body.put("detail", "medium");
        json.writeValue(broken.toFile(), body);
        assertThrows(JsonMappingException.class, () -> UnitModelDescriptor.read(broken));
    }

    @Test
    void brokenSocketNodesAndVerticesAreRejectedBeforeGpuAllocation() throws Exception {
        Path file = assets.resolve("bodies/warhammer.json");
        ObjectNode document = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) document.get("hardpoints").get(0)).put("node", "missing-limb");
        Path broken = scratch.resolve("broken.json");
        json.writeValue(broken.toFile(), document);
        var descriptor = UnitModelDescriptor.read(broken);
        var data = meshData(assets.resolve("bodies/warhammer.glb"));
        assertThrows(IllegalArgumentException.class, () -> descriptor.validate(data));
        var valid = UnitModelDescriptor.read(file);
        data.meshes.first().vertices[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> valid.validate(data));
    }

    @Test
    void invalidEmitterDirectionAndUnknownSchemaCannotEnterTheLibrary() throws Exception {
        ObjectNode document = (ObjectNode) json.readTree(assets.resolve("equipment/ppc.json").toFile());
        ((ObjectNode) document.get("emitters").get(0)).putArray("direction").add(0).add(0).add(0);
        Path broken = scratch.resolve("broken.json");
        json.writeValue(broken.toFile(), document);
        assertThrows(JsonMappingException.class, () -> UnitModelDescriptor.read(broken));
        document = (ObjectNode) json.readTree(assets.resolve("equipment/ppc.json").toFile());
        document.put("schema", 3);
        json.writeValue(broken.toFile(), document);
        assertThrows(JsonMappingException.class, () -> UnitModelDescriptor.read(broken));
    }

    @Test
    void landingSupportsRejectMissingControlsAndFootUnderTheScaledShaft() throws Exception {
        Path file = assets.resolve("bodies/family-spheroid.json");
        var descriptor = UnitModelDescriptor.read(file);
        assertEquals(4, descriptor.landingSupports().size());
        var data = meshData(file.resolveSibling(descriptor.mesh()));
        descriptor.validate(data);
        ObjectNode document = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) document.get("landingSupports").get(0)).put("foot", "missing-pad");
        Path broken = scratch.resolve("broken-support.json");
        json.writeValue(broken.toFile(), document);
        assertThrows(IllegalArgumentException.class, () -> UnitModelDescriptor.read(broken).validate(data));
        ((ObjectNode) document.get("landingSupports").get(0)).putArray("stowedOffset").add(0).add(0).add(-1);
        json.writeValue(broken.toFile(), document);
        assertThrows(JsonMappingException.class, () -> UnitModelDescriptor.read(broken), "A support must withdraw into the hull");
        var support = descriptor.landingSupports().getFirst();
        var parent = java.util.Arrays.stream(data.nodes.first().children[0].children)
              .filter(node -> node.id.equals(support.node())).findFirst().orElseThrow();
        var shaft = java.util.Arrays.stream(parent.children).filter(node -> node.id.equals(support.shaft())).findFirst().orElseThrow();
        var foot = java.util.Arrays.stream(parent.children).filter(node -> node.id.equals(support.foot())).findFirst().orElseThrow();
        shaft.children = new com.badlogic.gdx.graphics.g3d.model.data.ModelNode[] { foot };
        parent.children = new com.badlogic.gdx.graphics.g3d.model.data.ModelNode[] { shaft };
        assertThrows(IllegalArgumentException.class, () -> descriptor.validate(data), "Foot cannot inherit length scaling");
    }

    @Test
    void assetReferencesCannotEscapeTheModelDirectory() throws Exception {
        Path root = Files.createDirectory(scratch.resolve("models"));
        Path outside = Files.writeString(scratch.resolve("outside.glb"), "{}");
        assertThrows(IllegalArgumentException.class, () -> UnitModelDescriptor.contained(root, outside));
        assertThrows(IllegalArgumentException.class,
              () -> UnitModelDescriptor.contained(root, root.resolve("../outside.glb")));
    }

    /** The LOD0 geometry of a GLB asset, read without any GPU allocation. */
    private static ModelData meshData(Path glb) {
        return RigidGlb.loadLods(new FileHandle(glb.toFile())).getFirst();
    }

    /** Pads the first mesh part with copies of its first triangle, so the asset holds exactly {@code total}. */
    private static void resizeTo(ModelData data, int total) {
        var part = data.meshes.first().parts[0];
        int otherTriangles = UnitModelDescriptor.triangleCount(data) - part.indices.length / 3;
        short[] original = part.indices;
        part.indices = new short[(total - otherTriangles) * 3];
        for (int index = 0; index < part.indices.length; index++) {
            part.indices[index] = original[index % 3];
        }
    }

    private void assertValidatesAt(String asset, int triangles) throws Exception {
        var descriptor = UnitModelDescriptor.read(assets.resolve(asset + ".json"));
        var data = meshData(assets.resolve(asset + ".glb"));
        resizeTo(data, triangles);
        assertEquals(triangles, descriptor.validate(data), asset);
    }
}
