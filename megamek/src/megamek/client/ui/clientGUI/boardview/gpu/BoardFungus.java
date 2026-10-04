/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/** Authored fungal cover and cosmetic colonies mounted on the shared, finished cliff mesh. */
final class BoardFungus {
    static final List<String> COVER = List.of("fungus/cup-chalice", "fungus/cup-trumpet", "fungus/cup-frilled",
          "fungus/cup-forked", "fungus/spore-round", "fungus/spore-pear", "fungus/spore-cluster");
    static final List<String> CUPS = COVER.subList(0, 4);
    static final List<String> SCATTER = List.of("fungus/scatter-cups", "fungus/scatter-buds",
          "fungus/scatter-spores", "fungus/scatter-mixed");
    static final List<String> CLIFF = List.of("fungus/cliff-shelf", "fungus/cliff-tiered",
          "fungus/cliff-cups", "fungus/cliff-mycelium");

    private BoardFungus() { }

    static boolean asset(String name) { return name.startsWith("fungus/"); }

    record Placement(String asset, Matrix4 transform) { }

    /** No new terrain or cover is inferred: only existing exposed walls receive these decorative props. */
    static List<Placement> cliffs(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces) {
        if (tile.surface() != BoardScene.Surface.FUNGUS || tile.liquid().present() || !tile.detailedGround()) {
            return List.of();
        }
        List<Placement> result = new ArrayList<>();
        float unit = BoardGeometry.hexScale();
        for (int edge = 0; edge < 6; edge++) {
            int direction = BoardGeometry.edgeDirection(edge);
            var lower = scene.tile(tile.coords().translated(direction));
            // The board's cutaway perimeter is not an ecological cliff. Keep road and water mouths clear.
            if (lower == null || lower.elevation() >= tile.elevation() || lower.liquid().present()
                  || (tile.roadExits() & (1 << direction)) != 0
                  || (lower.roadExits() & (1 << ((direction + 3) % 6))) != 0) {
                continue;
            }
            int drop = tile.elevation() - lower.elevation();
            Random random = new Random(tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L
                  ^ edge * 83_492_791L ^ 0xf091L);
            // Use the exposed height, not the hex's absolute elevation. Small steps are almost always bare.
            float occupancy = drop == 1 ? .06f : drop == 2 ? .25f : .8f;
            if (random.nextFloat() >= occupancy) { continue; }
            List<BoardSurface.Face> wall = new ArrayList<>();
            List<Float> areas = new ArrayList<>();
            float area = 0;
            for (var face : faces) {
                if (face.landEdge() != edge || face.finish() != BoardSurface.Finish.WALL) { continue; }
                Vector3 normal = face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a()));
                float size = normal.len();
                if (size == 0 || Math.abs(normal.z / size) > .6f) { continue; }
                area += size;
                wall.add(face);
                areas.add(area);
            }
            if (wall.isEmpty()) { continue; }
            int count = drop >= 3 ? 2 : 1;
            List<Vector3> placed = new ArrayList<>();
            for (int attempt = 0; attempt < count * 12 && placed.size() < count; attempt++) {
                float target = random.nextFloat() * area;
                int index = 0;
                while (index < areas.size() - 1 && target > areas.get(index)) { index++; }
                var face = wall.get(index);
                float u = (float) Math.sqrt(random.nextFloat()), v = random.nextFloat();
                Vector3 point = face.a().cpy().scl(1 - u).mulAdd(face.b(), u * (1 - v)).mulAdd(face.c(), u * v);
                if (point.z < lower.elevation() * BoardGeometry.level() + 5 * unit
                      || point.z > tile.elevation() * BoardGeometry.level() - 4 * unit
                      || placed.stream().anyMatch(previous -> previous.dst2(point) < 144 * unit * unit)) { continue; }
                Vector3 normal = face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a()));
                normal.z = 0;
                normal.nor();
                Vector3 outward = new Vector3(BoardGeometry.centerX(lower.coords()) - BoardGeometry.centerX(tile.coords()),
                      BoardGeometry.centerY(lower.coords()) - BoardGeometry.centerY(tile.coords()), 0);
                if (normal.dot(outward) < 0) { normal.scl(-1); }
                float scale = (.7f + .35f * random.nextFloat()) * unit;
                // The GLBs mount at local Y=0, facing -Y with Z up. Their roots overlap slightly into the wall.
                float turn = (float) Math.toDegrees(Math.atan2(normal.x, -normal.y));
                placed.add(point);
                Matrix4 transform = new Matrix4().setToTranslation(point).rotate(Vector3.Z, turn).scale(scale, scale, scale);
                result.add(new Placement(CLIFF.get(random.nextInt(CLIFF.size())), transform));
            }
        }
        return List.copyOf(result);
    }
}
