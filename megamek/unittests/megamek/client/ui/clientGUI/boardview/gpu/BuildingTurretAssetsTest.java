/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import org.junit.jupiter.api.Test;

class BuildingTurretAssetsTest {
    @Test
    void allExportedLevelsValidateAndKeepTheSameJointHierarchy() throws Exception {
        var root = Configuration.dataDir().toPath().resolve("models/" + BuildingTurretVisual.ROOT);
        var json = new ObjectMapper();
        int checked = 0;
        try (var files = Files.list(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                if (!json.readTree(file.toFile()).has("mesh")) { continue; }
                var descriptor = UnitModelDescriptor.read(file);
                var levels = RigidGlb.loadLods(new FileHandle(file.getParent().resolve(descriptor.mesh()).toFile()));
                assertNotSame(levels.get(0), levels.get(1), file.toString());
                Set<String> first = new HashSet<>(), second = new HashSet<>();
                levels.get(0).nodes.forEach(node -> ids(node, first));
                levels.get(1).nodes.forEach(node -> ids(node, second));
                assertEquals(first, second, file.toString());
                int detailed = descriptor.validate(levels.get(0), 0);
                int simplified = descriptor.validate(levels.get(1), 1);
                assertTrue(simplified < detailed, file.toString());
                if (descriptor.kind().equals("equipment")) {
                    assertTrue(descriptor.bounds().min().get(1) >= -.001f, file.toString());
                    assertTrue(descriptor.joints().containsKey("aim"));
                    assertEquals(descriptor.family().equals("ballistic") || descriptor.family().equals("machine-gun"),
                          descriptor.joints().containsKey("recoil"));
                    for (var material : levels.getFirst().materials) {
                        assertTrue(material.emissive == null || material.emissive.r + material.emissive.g + material.emissive.b == 0,
                              "Weapon muzzle must remain unlit: " + file);
                    }
                }
                checked++;
            }
        }
        assertEquals(26, checked);
    }

    private static void ids(ModelNode node, Set<String> ids) {
        assertTrue(ids.add(node.id), "Duplicate node " + node.id);
        if (node.children != null) { for (var child : node.children) { ids(child, ids); } }
    }
}
