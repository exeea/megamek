/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;

/** Authored structure footprints used by cosmetic placement; no GPU resources or game occupancy state. */
final class BoardObstacles {
    private static final Map<File, BoardKit<Geometry>> MODELS = new ConcurrentHashMap<>();
    private final List<Footprint> footprints = new ArrayList<>();

    private record Geometry(Map<String, BoardShape> shapes, Map<String, String> modules) { }

    /** Reload assets publishes fresh models on the next terrain build; workers retain their immutable snapshot. */
    static void reload() { MODELS.clear(); }

    /** Projected triangles retain empty courtyards and gaps between tanks. */
    record Footprint(List<Vector3> triangles, float low, float high, BoundingBox bounds) {
        boolean obstructs(Vector3 base, float radius, float height) {
            if (base.z + height < low || base.z > high || base.x + radius < bounds.min.x
                  || base.x - radius > bounds.max.x || base.y + radius < bounds.min.y
                  || base.y - radius > bounds.max.y) { return false; }
            for (int i = 0; i < triangles.size(); i += 3) {
                Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
                float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
                if (Math.abs(area) > .00001f
                      && Intersector.isPointInTriangle(base.x, base.y, a.x, a.y, b.x, b.y, c.x, c.y)
                      || BoardRelief.distance(base.x, base.y, a.x, a.y, b.x, b.y) <= radius
                      || BoardRelief.distance(base.x, base.y, b.x, b.y, c.x, c.y) <= radius
                      || BoardRelief.distance(base.x, base.y, c.x, c.y, a.x, a.y) <= radius) { return true; }
            }
            return false;
        }
    }

    BoardObstacles(BoardScene scene, BoardScene.Tile tile) {
        add(scene, tile);
        // Rim rocks can overhang their hex; a neighbour's structure can do the same.
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.tile(tile.coords().translated(direction));
            if (neighbor != null) { add(scene, neighbor); }
        }
    }

    boolean isEmpty() { return footprints.isEmpty(); }

    boolean obstructs(Vector3 base, float radius, float height) {
        return footprints.stream().anyMatch(footprint -> footprint.obstructs(base, radius, height));
    }

    private void add(BoardScene scene, BoardScene.Tile tile) {
        for (var feature : tile.features()) {
            var object = feature.decoration();
            if (feature.asset().equals("bridge") && object == null) {
                // A pier stands from below the floor up to the deck: its whole column is solid wherever cover could grow.
                for (int d = 0; d < 6; d++) {
                    if (!BoardBridgeFooting.pier(scene, tile, d)) { continue; }
                    var outline = BoardBridgeFooting.pierFootprint(tile, d);
                    var bounds = new BoundingBox().inf();
                    outline.forEach(bounds::ext);
                    footprints.add(new Footprint(outline, -Float.MAX_VALUE, Float.MAX_VALUE, bounds));
                }
                deck(scene, tile, feature);
                continue;
            }
            // Paint has no model footprint. Structures already provide clearance for objects on their roofs/decks.
            if (object != null && (object.kind().equals("decal") || object.placement().receiver() != null
                  && !object.placement().receiver().terrain().equals("ground"))) { continue; }
            // Bridges already have height-aware passage/approach clearance. Fields are ground cover, not solids.
            if (feature.kind() != BoardScene.FeatureKind.BUILDING && feature.kind() != BoardScene.FeatureKind.INDUSTRIAL
                  && feature.kind() != BoardScene.FeatureKind.PROP
                  && feature.kind() != BoardScene.FeatureKind.SCENERY
                  || feature.asset().equals("bridge") || feature.asset().equals("field")) { continue; }
            boolean procedural = BoardIndustrial.supports(feature);
            boolean custom = object == null && !procedural && feature.asset().startsWith("buildings/")
                  && BoardArtwork.customBuildingFile(feature.asset()).isFile();
            File root = new File(Configuration.dataDir(), custom ? "models/buildings" : "models/board");
            // A tree's winter form has the shape of its bare file's geometry.
            File file = custom ? BoardArtwork.customBuildingFile(feature.asset())
                  : RigidGlb.source(root, feature.asset()).file();
            // Unresolved native references draw a renderer-owned placeholder and must remain editable.
            if (object != null && !file.isFile()) { continue; }
            var geometry = procedural ? null : MODELS.computeIfAbsent(file.getAbsoluteFile(), key -> new BoardKit<>(() -> {
                var data = RigidGlb.loadLods(new FileHandle(key), root.toPath()).getFirst();
                return new Geometry(BoardShape.shapes(data, custom), custom ? GpuBuilding.parts(data) : Map.of());
            })).get();
            List<Vector3> points = new ArrayList<>();
            if (procedural) {
                points.addAll(BoardIndustrial.triangles(BoardIndustrial.layout(scene, tile, feature)));
            } else if (custom) {
                var modules = GpuBuilding.select(geometry.modules(), Math.max(1, Math.round(feature.height())),
                      GpuBuilding.seed(tile, feature));
                for (String module : modules.stream().distinct().toList()) {
                    var shape = geometry.shapes().get(geometry.modules().get(module));
                    // Use the same selected roof underside that defines the rendered building's occupied interior.
                    var triangles = triangles(shape);
                    if (module.startsWith("roof")) { triangles = GpuBuildingInterior.plane(triangles, 0, false); }
                    points.addAll(triangles);
                }
            } else {
                geometry.shapes().values().forEach(shape -> points.addAll(triangles(shape)));
            }
            float turn = (float) Math.toRadians(feature.rotation()), c = (float) Math.cos(turn), s = (float) Math.sin(turn);
            float scale = feature.scale() * BoardGeometry.hexScale();
            float x = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
            float y = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
            BoundingBox bounds = new BoundingBox().inf();
            var authored = object == null ? null : BoardFeatures.decorationTransform(tile.coords(), feature, 0);
            for (Vector3 p : points) {
                if (authored != null) { p.mul(authored); }
                else {
                    // A layout row's stretch acts on the model's own axes before its turn, as in the drawn instance.
                    float px = p.x * (float) feature.stretch().x(), py = p.y * (float) feature.stretch().y();
                    p.set(x + scale * (c * px - s * py), y + scale * (s * px + c * py), p.z);
                }
                bounds.ext(p);
            }
            float ground = BoardGeometry.groundZ(tile), upperGround = ground;
            // Captured dry buildings stand on the exact flat anchor. An offset or wet prop's finished support is
            // not available while dressing is being emitted: bound it conservatively from nearby terrain instead
            // of building another surface and feeding decorative rocks back into their own clearance query.
            if (feature.x() != 0 || feature.y() != 0 || tile.liquid().present()) {
                upperGround = tile.elevation() * BoardGeometry.level() + BoardRelief.decoration(tile);
                for (int direction = 0; direction < 6; direction++) {
                    var neighbor = scene.tile(tile.coords().translated(direction));
                    if (neighbor == null) { continue; }
                    ground = Math.min(ground, BoardGeometry.groundZ(neighbor));
                    upperGround = Math.max(upperGround,
                          neighbor.elevation() * BoardGeometry.level() + BoardRelief.decoration(neighbor));
                }
            }
            if (object != null) {
                var placement = object.placement();
                if (placement.mode().equals("absolute")) {
                    ground = upperGround = placement.level().floatValue() * BoardGeometry.level();
                } else {
                    ground += placement.offset().floatValue() * BoardGeometry.level();
                    upperGround += placement.offset().floatValue() * BoardGeometry.level();
                }
            }
            boolean fitHeight = custom || feature.kind() == BoardScene.FeatureKind.BUILDING
                  || feature.asset().startsWith("buildings/");
            float verticalScale = object != null ? 1 : feature.kind() == BoardScene.FeatureKind.SCENERY
                  ? scale * (float) feature.stretch().z()
                  : custom ? BoardGeometry.level() / GpuBuilding.LEVEL_HEIGHT
                  : feature.height() * BoardGeometry.level() / (fitHeight ? bounds.getDepth() : 1);
            // Scenery grounds its lowest authored vertex, including models with an offset origin.
            float low = ground + feature.elevation() * BoardGeometry.level()
                  + (feature.kind() == BoardScene.FeatureKind.SCENERY ? 0 : bounds.min.z * verticalScale);
            float height = custom ? feature.height() * BoardGeometry.level() : bounds.getDepth() * verticalScale;
            footprints.add(new Footprint(List.copyOf(points), low, low + height + upperGround - ground, bounds));
        }
    }

    /**
     * The deck's passage from the hex centre to each of its exit edges ({@link BoardBridge#PASSAGE_HALF_WIDTH} each
     * side), from the slab's underside to the deck: cover tall enough to reach it, under a deck at or near the ground,
     * does not grow through it. A raised deck leaves the ground beneath it as it is.
     */
    private void deck(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature bridge) {
        float s = BoardGeometry.hexScale(), half = BoardBridge.PASSAGE_HALF_WIDTH * s, centreZ = BoardBridge.deckZ(tile);
        var centre = BoardGeometry.center(tile.coords(), 0);
        for (int d = 0; d < 6; d++) {
            if ((bridge.bridgeExits() & (1 << d)) == 0) { continue; }
            var edge = BoardGeometry.center(tile.coords().translated(d), 0).add(centre).scl(.5f);
            var across = new Vector3(edge).sub(centre).nor();
            across.set(-across.y, across.x, 0).scl(half);
            var a = new Vector3(centre).add(across);
            var b = new Vector3(centre).sub(across);
            var c = new Vector3(edge).sub(across);
            var e = new Vector3(edge).add(across);
            var bounds = new BoundingBox().inf().ext(a).ext(b).ext(c).ext(e);
            float edgeZ = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, scene.tile(tile.coords().translated(d)), d));
            footprints.add(new Footprint(List.of(a, b, c, new Vector3(a), new Vector3(c), e),
                  Math.min(centreZ, edgeZ) - BoardBridge.SLAB * s, Math.max(centreZ, edgeZ), bounds));
        }
    }

    private static List<Vector3> triangles(BoardShape shape) {
        List<Vector3> result = new ArrayList<>();
        for (var polygon : shape.polygons()) {
            for (int i = 1; i + 1 < polygon.points().length; i++) {
                result.add(new Vector3(polygon.points()[0]));
                result.add(new Vector3(polygon.points()[i]));
                result.add(new Vector3(polygon.points()[i + 1]));
            }
        }
        return result;
    }
}
