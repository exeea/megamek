/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import megamek.common.Configuration;
import megamek.common.board.Coords;

/** Plain authored compositions of shared meshes; terrain rules and artwork selection remain with their owners. */
public final class BoardSceneryLayouts {
    private static final Map<String, Layout> LAYOUTS = read();

    private BoardSceneryLayouts() { }

    record Component(String asset, BoardScene.FeatureKind kind, float x, float y, float z,
          float rotation, float scale) { }

    record Layout(List<Component> components) {
        List<BoardScene.Feature> features(Coords coords, List<String> species, boolean natural) {
            var result = new ArrayList<BoardScene.Feature>();
            var random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L);
            for (Component component : components) {
                boolean tree = component.kind() == BoardScene.FeatureKind.TREE;
                // The original broad park tree slot opts into the existing species stands; other explicit assets stay explicit.
                boolean varied = tree && natural && component.asset().equals("tree-broad");
                String asset = varied ? BoardTreeDistribution.species(species, coords,
                      component.x(), component.y(), random.nextLong()) : component.asset();
                float height = tree ? component.scale() * 30 / BoardGeometry.MODEL_LEVEL_HEIGHT : 1;
                result.add(new BoardScene.Feature(asset, component.x(), component.y(),
                      varied ? random.nextFloat() * 360 : component.rotation(), component.scale(), height,
                      component.z() / BoardGeometry.MODEL_LEVEL_HEIGHT, component.kind(), 0, true));
            }
            return List.copyOf(result);
        }
    }

    static Layout layout(String asset) { return LAYOUTS.get(asset); }

    /** A composed artwork selection needs no baked per-layout mesh. */
    public static boolean hasLayout(String asset) { return LAYOUTS.containsKey(asset); }

    private static Map<String, Layout> read() {
        var file = new FileHandle(new File(Configuration.dataDir(), "models/board/scenery/layouts.json"));
        // Older/custom data packs keep their complete scenery meshes.
        if (!file.exists()) { return Map.of(); }
        var result = new HashMap<String, Layout>();
        for (var entry : new JsonReader().parse(file)) {
            var components = new ArrayList<Component>();
            for (var component : entry) {
                var position = component.get("position");
                var kind = BoardScene.FeatureKind.valueOf(component.getString("kind"));
                if (kind != BoardScene.FeatureKind.TREE && kind != BoardScene.FeatureKind.SCENERY) {
                    throw new IllegalArgumentException("Unsupported scenery component kind: " + kind);
                }
                components.add(new Component(component.getString("asset"), kind,
                      position.getFloat(0), position.getFloat(1), position.getFloat(2),
                      component.getFloat("rotation"), component.getFloat("scale")));
            }
            result.put(entry.name, new Layout(List.copyOf(components)));
        }
        return Map.copyOf(result);
    }
}
