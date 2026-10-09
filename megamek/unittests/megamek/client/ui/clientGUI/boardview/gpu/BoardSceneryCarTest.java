/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

import com.badlogic.gdx.files.FileHandle;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** The unchanged 2D sprites are the independent reference for neutral car bodies, not their blue-gray curbs. */
class BoardSceneryCarTest {
    static final List<String> VARIANTS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "2b", "3b");
    static final List<Integer> COUNTS = List.of(10, 6, 10, 11, 7, 6, 5, 6, 5, 6);

    @Test
    void everyParkingSpriteKeepsItsCarsAndNeutralBodiesMatchTheOriginalPixels() throws Exception {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        int neutralCars = 0;
        for (int index = 0; index < VARIANTS.size(); index++) {
            String variant = VARIANTS.get(index);
            String name = "scenery/fluff/cars_" + variant;
            var layout = BoardSceneryLayouts.layout(name);
            var cars = layout.components().stream().filter(c -> c.asset().startsWith("scenery/vehicles/car")).toList();
            assertEquals(COUNTS.get(index).intValue(), cars.size(), variant);
            assertFalse(Files.exists(root.resolve(name + ".glb")), "No duplicate car geometry stays in the layout");
            BufferedImage reference = ImageIO.read(root.resolve("tileset/fluff/cars_" + variant + ".gif").toFile());
            for (var car : cars) {
                assertEquals(BoardScene.FeatureKind.SCENERY, car.kind());
                var data = RigidGlb.loadLods(new FileHandle(root.resolve(car.asset() + ".glb").toFile()), root).getFirst();
                // The row's paint replaces the shared car's paint slot, as the renderer replaces it.
                RigidGlb.recolour(data, car.colours());
                float[] vertices = data.meshes.first().vertices;
                float red = vertices[6], green = vertices[7], blue = vertices[8];
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > .01f) { continue; }
                neutralCars++;
                int bodyPixels = 0;
                double angle = Math.toRadians(car.rotation());
                for (int x = -1; x <= 1; x++) for (int y = -2; y <= 2; y++) {
                    int px = Math.round(42 + car.x() + (float) (x * Math.cos(angle) - y * Math.sin(angle)));
                    int py = Math.round(36 - car.y() - (float) (x * Math.sin(angle) + y * Math.cos(angle)));
                    int pixel = reference.getRGB(px, py);
                    int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                    if ((pixel >>> 24) > 128 && Math.min(r, Math.min(g, b)) > 45
                          && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 5) { bodyPixels++; }
                }
                assertTrue(bodyPixels >= 7, "Neutral car must overlap its body, not blue-gray curb paint: "
                      + variant + " at " + car.x() + "," + car.y() + " / " + bodyPixels);
            }
            var scenery = new BoardArtwork.Scenery(List.of(name), Set.of(Terrains.FLUFF), Set.of(Terrains.FLUFF), 0);
            var hex = new Hex(0, "pavement:1", "");
            var coords = new Coords(2, 2);
            assertEquals(BoardFeatures.capture(hex, coords, Map.of(), Set.of(), c -> null, scenery, false),
                  BoardFeatures.capture(hex, coords, Map.of(), Set.of(), c -> null, scenery, true),
                  "The vegetation switch cannot randomize parked cars");
        }
        assertEquals(16, neutralCars);
        // The road-free 2b/3b sprites are 7/8 without the lane.
        assertEquals(objects("scenery/fluff/cars_7"), objects("scenery/fluff/cars_2b"));
        assertEquals(objects("scenery/fluff/cars_8"), objects("scenery/fluff/cars_3b"));
    }

    private static List<BoardSceneryLayouts.Component> objects(String layout) {
        return BoardSceneryLayouts.layout(layout).components().stream().filter(c -> !c.decal()).toList();
    }
}
