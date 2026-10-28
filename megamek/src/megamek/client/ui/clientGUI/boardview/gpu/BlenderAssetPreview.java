/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** File-based authoring bridge. Uses the runtime loaders and assembly rules without starting a game or OpenGL. */
public final class BlenderAssetPreview {
    private BlenderAssetPreview() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected request.json result.json");
        }
        var mapper = new ObjectMapper();
        Map<String, Object> result;
        try {
            GdxNativesLoader.load();
            result = preview(mapper.readTree(Path.of(args[0]).toFile()));
        } catch (Exception error) {
            result = Map.of("ok", false, "error", error.getMessage() == null ? error.toString() : error.getMessage());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), result);
    }

    static Map<String, Object> preview(JsonNode request) {
        Path file = Path.of(request.required("model").asText());
        Path textures = Path.of(request.required("modelsRoot").asText());
        int lod = request.path("lod").asInt();
        if (lod < 0 || lod >= RigidGlb.LEVELS) { throw new IllegalArgumentException("LOD must be 0 to 3"); }
        var lods = RigidGlb.loadLods(new FileHandle(file.toFile()), textures);
        var data = (RigidGlb.Data) lods.get(lod);
        var ground = RigidGlb.groundwork(file.toFile());
        List<Map<String, Object>> instances = new ArrayList<>();
        String description = "Rigid GLB loaded";
        if (request.path("kind").asText().equals("KIT")) {
            int height = request.path("height").asInt(1);
            int steps = request.path("rotation").asInt();
            JsonNode neighbours = request.required("reach");
            if (height < 1 || height > 100 || steps < 0 || steps > 5 || neighbours.size() != 6) {
                throw new IllegalArgumentException("Expected height 1 to 100, rotation 0 to 5, six connection heights");
            }
            int[] reach = new int[6];
            for (int i = 0; i < 6; i++) {
                reach[i] = neighbours.get(i).asInt(-1);
                if (reach[i] < 0 || reach[i] > 100) { throw new IllegalArgumentException("Connection height must be 0 to 100"); }
            }
            var kit = ConnectedKit.load(lods);
            for (var instance : ConnectedKit.placements(kit, lod, height, reach, steps, request.path("seed").asLong())) {
                instances.add(Map.of("node", instance.node(), "matrix", instance.matrix().val));
            }
            description = (kit.symmetric() ? "Symmetric" : "Explicit-direction") + " kit: " + instances.size() + " pieces";
        } else if (!request.path("kind").asText().equals("RIGID")) {
            throw new IllegalArgumentException("Expected KIT or RIGID preview");
        }
        return Map.of("ok", true, "instances", instances, "lod", MeshLod.level(data.id),
              "summary", description + "; LOD " + MeshLod.level(data.id) + "; " + data.materials.size + " materials; "
                    + ground.cut().size() + " cut triangles; " + ground.stands().size() + " stand/vehicle/hide markers");
    }
}
