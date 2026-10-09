/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardEditorBlueprint;
import org.junit.jupiter.api.Test;

/**
 * The decode rows (generated layouts.json) and the hand-edited palette (blueprint.json) agree. Keys that import never
 * decodes are exempt: terrain-owned rubble, geysers and fortifications, which have no prop entry. Decal rows name
 * painted decals, found by path convention, and seated cars name a slot of one. Legacy decal ids decode to decals. Rows
 * and palette entries recolour only the colour slots their mesh has.
 */
class BoardSceneryDecodeDataTest {
    private static boolean undecoded(String key) {
        return key.matches("scenery/saxarba/(misc/|rubble_).*");
    }

    @Test
    void decodeRowsAndThePaletteAgree() throws Exception {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        JsonNode layouts = new ObjectMapper().readTree(root.resolve("scenery/layouts.json").toFile());
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        for (var keys = layouts.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (undecoded(key)) { continue; }
            for (JsonNode row : layouts.get(key)) {
                String asset = row.get("asset").asText();
                var entry = blueprint.asset(asset);
                assertTrue(entry != null, "Imported objects keep a label: " + asset);
                assertTrue(entry.kind().equals("decal") ? BoardDecalArt.image(asset).isFile()
                      : Files.isRegularFile(root.resolve(asset + ".glb")), asset);
                if (row.has("seat")) {
                    var plates = new java.util.ArrayList<String>();
                    layouts.get(key).forEach(other -> { if (other.get("asset").asText().startsWith("decal/")) { plates.add(other.get("asset").asText()); } });
                    assertTrue(row.get("seat").get(1).asInt() < BoardDecalArt.footprint(plates.get(row.get("seat").get(0).asInt())).slots().size(),
                          "A seat names a slot of one of the key's decal rows: " + key);
                }
                assertEquals(0, row.get("position").get(2).asDouble(), "Rows stand on their receiver: " + key);
                if (row.has("stretch")) {
                    var stretch = row.get("stretch");
                    assertTrue(stretch.size() == 3 && stretch.get(0).asDouble() > 0 && stretch.get(1).asDouble() > 0
                          && stretch.get(2).asDouble() > 0, "Stretch is three positive factors: " + key);
                }
                if (row.has("colours")) {
                    assertTrue(row.get("colours").size() <= RigidGlb.colourSlots(root.resolve(asset + ".glb").toFile()).size(),
                          "A row recolours only its mesh's slots: " + key);
                }
            }
        }
        // A legacy decal id decodes to a decal with an image; a one-hex variant shrinks it, a set piece shows it whole.
        JsonNode decals = new ObjectMapper().readTree(root.resolve("decals/decals.json").toFile());
        for (var ids = decals.get("legacy").fieldNames(); ids.hasNext(); ) {
            var legacy = BoardDecalArt.legacy(ids.next());
            assertTrue(BoardDecalArt.image(legacy.decal()).isFile() && decals.get("decals").has(legacy.decal()), legacy.decal());
            assertTrue(legacy.set() ? legacy.scale() == 1 && legacy.rotation() == 0 : legacy.direction() == -1 && legacy.scale() <= 1,
                  legacy.toString());
        }
        Map<String, Integer> visible = new TreeMap<>(), visibleDecals = new TreeMap<>();
        for (var asset : blueprint.assets()) {
            assertFalse(layouts.has(asset.id()), "A palette id is never a decode key: " + asset.id());
            if (asset.layout() != null) {
                assertEquals("prop", asset.kind());
                for (int exits = 0; exits < (asset.layout().contains("{exits}") ? 64 : 1); exits++) {
                    String key = asset.layout().replace("{exits}", String.format(Locale.ROOT, "%02d", exits));
                    assertTrue(layouts.has(key), asset.id() + " stamps " + key);
                }
            }
            if (asset.layout() == null && asset.kind().equals("prop")) {
                // A palette entry may place another model in other colours, as a pond is a pool in pond colours.
                var model = root.resolve(asset.model() + ".glb").toFile();
                assertTrue(model.isFile(), asset.id() + " places " + asset.model());
                assertTrue(asset.colours().slots().size() <= RigidGlb.colourSlots(model).size(), asset.id());
            }
            if (asset.palette() && asset.kind().equals("prop")) { visible.merge(asset.group(), 1, Integer::sum); }
            if (asset.kind().equals("decal")) {
                assertTrue(BoardDecalArt.image(asset.id()).isFile() && decals.get("decals").has(asset.id()), asset.id());
                if (asset.palette()) { visibleDecals.merge(asset.group(), 1, Integer::sum); }
            }
        }
        // One real object or one stamp per entry; colour, rotation and decode-only variants stay out of the palette, and so
        // do the -snow trees (a tree takes its winter form on snow). Turned crane tips and the narrow glass roof are rows.
        assertEquals(new TreeMap<>(Map.ofEntries(Map.entry("Alien plants", 27), Map.entry("Animals", 5),
              Map.entry("Construction and details", 16), Map.entry("Maglev and wagons", 5), Map.entry("Military", 3),
              Map.entry("Parks and furniture", 16), Map.entry("Rocks and debris", 4), Map.entry("Seaport", 78),
              Map.entry("Sport and pools", 5), Map.entry("Transport", 1), Map.entry("Trees and plants", 38),
              Map.entry("Vehicles", 1))), visible);
        // Layers type icons name components by id and objects by palette group label: a renamed group loses its icon.
        var icons = GpuBoardEditor.class.getDeclaredField("LAYER_ICONS");
        icons.setAccessible(true);
        for (Object key : ((Map<?, ?>) icons.get(null)).keySet()) {
            assertTrue(visible.containsKey(key) || blueprint.components().stream().anyMatch(c -> c.id().equals(key)),
                  "Layers icon key " + key + " is a component id or a palette group");
        }
        // One full image per emblem, the 8 standard car parks and the rubble path; emblem pieces are decode-only.
        assertEquals(Map.of("Emblems", 13, "Ground details", 8, "Damage and debris", 1), visibleDecals);
    }
}
