/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.units.Terrains;

/** Authored rough variants: one placement supplies the visible model, picking and unit support. */
public final class BoardRough {
    private BoardRough() { }

    /** The modeled rough appearance; other uses of fluff retain their tileset artwork. Safe during EDT capture. */
    public static int variant(Hex hex) {
        if (hex.getAppearance().containsKey("rough")) { hex = megamek.common.board.BoardEditorBlueprint.get().artwork(hex, "rough"); }
        int fluff = hex.terrainLevel(Terrains.FLUFF);
        return hex.containsTerrain(Terrains.ROUGH) && (fluff == 1 || fluff == 2) ? fluff : 0;
    }

    // Capture does not load geometry. Terrain workers share complete immutable CPU shapes, without GL resources.
    private static final BoardKit<Map<String, BoardShape>> KIT = new BoardKit<>(BoardRough::load);

    private static Map<String, BoardShape> load() {
        Map<String, BoardShape> result = new HashMap<>();
        for (String name : List.of("dragon-tooth", "felled-trunk", "charred-stump", "fallen-stump")) {
            String asset = "rough/" + name;
            result.put(asset, BoardShape.loadModel(asset));
        }
        return Map.copyOf(result);
    }

    record Placement(String asset, Matrix4 transform) { }

    static void reload() { KIT.reload(); }

    static List<Placement> place(BoardSurface surface) {
        List<BoardScene.Feature> features = surface.tile.features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.ROUGH).toList();
        if (features.isEmpty()) { return List.of(); }
        List<Placement> placements = new ArrayList<>();
        List<BoardSurface.Face> ground = surface.faces.stream()
              .filter(face -> face.finish() != BoardSurface.Finish.OUTCROP
                    && face.finish() != BoardSurface.Finish.DRESSING && face.finish() != BoardSurface.Finish.ICE).toList();
        for (BoardScene.Feature feature : features) {
            BoardShape shape = KIT.get().get(feature.asset());
            float x = BoardGeometry.centerX(surface.tile.coords()) + feature.x() * BoardGeometry.hexScale();
            float y = BoardGeometry.centerY(surface.tile.coords()) + feature.y() * BoardGeometry.hexScale();
            float height = feature.height() * BoardGeometry.level();
            Matrix4 transform = new Matrix4().setToTranslation(x, y, 0).rotate(Vector3.Z, feature.rotation())
                  .scale(feature.scale() * BoardGeometry.hexScale(), feature.scale() * BoardGeometry.hexScale(),
                        height / shape.height());
            Map<Vector3, Vector3> points = new HashMap<>();
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for (BoardShape.Polygon polygon : shape.polygons()) {
                for (Vector3 point : polygon.points()) {
                    if (points.containsKey(point)) { continue; }
                    Vector3 placed = new Vector3(point).mul(transform);
                    points.put(point, placed);
                    float support = BoardSurface.sampleHeight(ground, placed.x, placed.y, Float.NaN);
                    low = Math.min(low, support);
                    high = Math.max(high, support);
                }
            }
            // Retain the captured rows and route clearance. Omit a solid spanning a cliff or a receding edge;
            // elsewhere bury its base in the downhill ground so neither concrete nor timber floats.
            if (!Float.isFinite(low) || high - low > height * .8f) { continue; }
            float base = low - .025f * BoardGeometry.level();
            transform.val[Matrix4.M23] = base;
            points.values().forEach(point -> point.z += base);
            for (BoardShape.Polygon polygon : shape.polygons()) {
                Vector3[] p = polygon.points();
                surface.faces.add(new BoardSurface.Face(points.get(p[0]), points.get(p[1]), points.get(p[2]),
                      BoardSurface.Finish.ROUGH));
            }
            placements.add(new Placement(feature.asset(), transform));
        }
        return List.copyOf(placements);
    }
}
