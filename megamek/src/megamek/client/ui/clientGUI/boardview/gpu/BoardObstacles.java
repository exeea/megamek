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
import megamek.common.Configuration;
import megamek.common.board.Coords;
import megamek.common.board.TilesetScenery;

/** Authored structure footprints used by cosmetic placement; no GPU resources or game occupancy state. */
final class BoardObstacles {
    private static final Map<File, BoardKit<Geometry>> MODELS = new ConcurrentHashMap<>();
    /** How thick an earthwork's spoil lies, in metres, where grass and plants still grow through it. */
    private static final float EARTHWORK_THIN = .2f;
    private final List<Footprint> footprints = new ArrayList<>();

    private record Geometry(Map<String, BoardShape> shapes, Map<String, String> modules, BoundingBox bounds) { }

    /** Reload assets publishes fresh models on the next terrain build; workers retain their immutable snapshot. */
    static void reload() { MODELS.clear(); BoardWall.reload(); }

    /**
     * Projected triangles retain empty courtyards and gaps between tanks. An earthwork's ({@code thin} above zero: scenery
     * that digs its hex, BoardGroundCut) keep their world heights, and its {@code ground} is its hex's.
     */
    record Footprint(List<Vector3> triangles, float low, float high, BoundingBox bounds, boolean localHeight, float thin,
          float ground) {
        Footprint(List<Vector3> triangles, float low, float high, BoundingBox bounds, boolean localHeight) {
            this(triangles, low, high, bounds, localHeight, 0, 0);
        }

        Footprint(List<Vector3> triangles, float low, float high, BoundingBox bounds) {
            this(triangles, low, high, bounds, false);
        }

        boolean obstructs(Vector3 base, float radius, float height) {
            if (thin > 0) { return earthwork(base); }
            if (base.z + height < low || base.z > high || base.x + radius < bounds.min.x
                  || base.x - radius > bounds.max.x || base.y + radius < bounds.min.y
                  || base.y - radius > bounds.max.y) { return false; }
            for (int i = 0; i < triangles.size(); i += 3) {
                Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
                if (localHeight && base.z > Math.max(a.z, Math.max(b.z, c.z))) { continue; }
                float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
                if (Math.abs(area) > .00001f
                      && Intersector.isPointInTriangle(base.x, base.y, a.x, a.y, b.x, b.y, c.x, c.y)
                      || BoardRelief.distance(base.x, base.y, a.x, a.y, b.x, b.y) <= radius
                      || BoardRelief.distance(base.x, base.y, b.x, b.y, c.x, c.y) <= radius
                      || BoardRelief.distance(base.x, base.y, c.x, c.y, a.x, a.y) <= radius) { return true; }
            }
            return false;
        }

        /**
         * An earthwork is ground that what grows round it grows to, and through where its spoil lies no thicker than
         * {@code thin}, so the heap's foot meets the grass without a cleared band. What it digs below the ground and its
         * thicker heap stay clear.
         */
        private boolean earthwork(Vector3 base) {
            if (base.x < bounds.min.x || base.x > bounds.max.x || base.y < bounds.min.y || base.y > bounds.max.y) {
                return false;
            }
            boolean dug = base.z < ground - thin;
            for (int i = 0; i < triangles.size(); i += 3) {
                Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
                float d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
                if (Math.abs(d) < .00001f) { continue; }
                float u = ((b.y - c.y) * (base.x - c.x) + (c.x - b.x) * (base.y - c.y)) / d;
                float v = ((c.y - a.y) * (base.x - c.x) + (a.x - c.x) * (base.y - c.y)) / d;
                if (u < 0 || v < 0 || u + v > 1) { continue; }
                if (dug || u * a.z + v * b.z + (1 - u - v) * c.z > base.z + thin) { return true; }
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
        for (var captured : tile.features()) {
            var feature = BoardGroundCut.fitted(scene, tile, captured);
            var object = feature.decoration();
            if (feature.asset().equals("bridge") && object == null) {
                // A pier stands from below the floor up to the deck: its whole column is solid wherever cover could grow.
                for (int d = 0; d < 6; d++) {
                    if (BoardBridgeFooting.pier(scene, tile, d)) {
                        pier(tile, d, BoardBridge.guideway(tile) || BoardBridge.guideway(scene.neighbor(tile, d)));
                    }
                }
                deck(scene, tile, feature);
                continue;
            }
            // Paint has no model footprint. Structures already provide clearance for objects on their roofs/decks.
            if (object != null && (object.kind().equals("decal") || object.placement().receiver() != null
                  && !object.placement().receiver().terrain().equals("ground"))) { continue; }
            if (megamek.common.board.RailRoute.kind(object) == megamek.common.board.RailRoute.Kind.RAIL) {
                // Nothing grows or lies on a ground rail's track: its ballast band, the full height of the column.
                rail(scene, tile, feature);
                continue;
            }
            // Bridges already have height-aware passage/approach clearance. Fields are ground cover, not solids.
            if (feature.kind() != BoardScene.FeatureKind.BUILDING && feature.kind() != BoardScene.FeatureKind.INDUSTRIAL
                  && feature.kind() != BoardScene.FeatureKind.PROP
                  && feature.kind() != BoardScene.FeatureKind.SCENERY
                  || feature.asset().equals("bridge") || feature.asset().equals("vegetation/field")) { continue; }
            boolean wall = BoardWall.supports(feature.asset());
            boolean assembled = BoardIndustrial.supports(feature) || wall;
            boolean custom = object == null && !assembled && feature.asset().startsWith("buildings/")
                  && TilesetScenery.customBuildingFile(feature.asset()).isFile();
            File root = new File(Configuration.dataDir(), custom ? "models/buildings" : "models/board");
            // A tree's winter form has the shape of its bare file's geometry.
            File file = custom ? TilesetScenery.customBuildingFile(feature.asset())
                  : RigidGlb.source(root, feature.asset()).file();
            // Unresolved native references draw a renderer-owned placeholder and must remain editable.
            if (object != null && !file.isFile()) { continue; }
            var geometry = assembled ? null : geometry(root, file, custom);
            List<Vector3> points = new ArrayList<>();
            if (assembled) {
                points.addAll(wall ? BoardWall.triangles(BoardWall.layout(scene, tile, feature))
                      : BoardIndustrial.triangles(BoardIndustrial.layout(scene, tile, feature)));
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
                // A model's hidden numbered parts, as an empty slot's fighting hole, are no footprint.
                geometry.shapes().forEach((name, shape) -> {
                    if (BoardGroundCut.shown(RigidGlb.part(name), feature.parts())) { points.addAll(triangles(shape)); }
                });
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
                  : custom || wall ? BoardGeometry.level() / GpuBuilding.LEVEL_HEIGHT
                  : feature.height() * BoardGeometry.level() / (fitHeight ? bounds.getDepth() : 1);
            // Scenery grounds its lowest authored vertex, including models with an offset origin.
            float low = ground + feature.elevation() * BoardGeometry.level()
                  + (feature.kind() == BoardScene.FeatureKind.SCENERY ? 0 : bounds.min.z * verticalScale);
            float height = custom ? feature.height() * BoardGeometry.level() : bounds.getDepth() * verticalScale;
            if (wall) {
                // The tall pier must not reserve empty space over its shorter connecting arms.
                for (Vector3 point : points) {
                    point.z = low + (point.z - bounds.min.z) * verticalScale + upperGround - ground;
                }
            }
            if (object == null && BoardGroundCut.digs(feature.asset())) {
                // An earthwork stands on its hex's ground at its model's own zero.
                points.forEach(point -> point.z = low + point.z * verticalScale);
                footprints.add(new Footprint(List.copyOf(points), low + bounds.min.z * verticalScale,
                      low + height + upperGround - ground, bounds, false, BoardRelief.metres(EARTHWORK_THIN), ground));
                continue;
            }
            footprints.add(new Footprint(List.copyOf(points), low, low + height + upperGround - ground, bounds, wall));
        }
    }

    /** A model's finest authored level, read once per file for every hex and worker. */
    private static Geometry geometry(File root, File file, boolean custom) {
        return MODELS.computeIfAbsent(file.getAbsoluteFile(), key -> new BoardKit<>(() -> {
            var data = RigidGlb.loadLods(new FileHandle(key), root.toPath()).getFirst();
            var shapes = BoardShape.shapes(data, custom);
            BoundingBox bounds = new BoundingBox().inf();
            shapes.values().forEach(shape -> shape.polygons().forEach(polygon -> {
                for (Vector3 point : polygon.points()) { bounds.ext(point); }
            }));
            return new Geometry(shapes, custom ? GpuBuilding.parts(data) : Map.of(), bounds);
        })).get();
    }

    /**
     * Points whose convex hull is a placed object's footprint on the board plane, as drawn: a decal's stamp ({@link
     * BoardDecals#paintRectangle}); a route's track ({@link BoardRail#paths}, its half width either side); else the
     * eight corners of its model's bounds placed by {@link BoardFeatures#decorationTransform} (anchor, turn, tilt,
     * scale, stretch and mirror). Null for a missing model, whose placeholder the renderer draws.
     */
    static List<Vector3> outline(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        var object = feature.decoration();
        Coords owner = tile.coords();
        if (object.kind().equals("decal")) { return BoardDecals.paintRectangle(owner, object); }
        if (BoardRail.route(feature)) {
            List<Vector3> result = new ArrayList<>();
            float scale = BoardGeometry.hexScale(), half = BoardRail.halfWidth(feature);
            for (var path : BoardRail.paths(scene, tile, feature)) {
                for (Vector3 p : path) {
                    for (int side = 0; side < 4; side++) {
                        result.add(new Vector3(BoardGeometry.centerX(owner) + (p.x + ((side & 1) * 2 - 1) * half) * scale,
                              BoardGeometry.centerY(owner) + (p.y + ((side & 2) - 1) * half) * scale, 0));
                    }
                }
            }
            return result;
        }
        File root = new File(Configuration.dataDir(), "models/board");
        File file = RigidGlb.source(root, feature.asset()).file();
        if (!file.isFile()) { return null; }
        BoundingBox bounds = geometry(root, file, false).bounds();
        var transform = BoardFeatures.decorationTransform(owner, feature, 0);
        List<Vector3> corners = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            corners.add(new Vector3((i & 1) == 0 ? bounds.min.x : bounds.max.x, (i & 2) == 0 ? bounds.min.y : bounds.max.y,
                  (i & 4) == 0 ? bounds.min.z : bounds.max.z).mul(transform));
        }
        return corners;
    }

    /** The footing of the pier under the tile's joint {@code d} ({@link BoardBridgeFooting#pierFootprint}), solid at every height. */
    private void pier(BoardScene.Tile tile, int d, boolean maglev) {
        var outline = BoardBridgeFooting.pierFootprint(tile, d, maglev);
        var bounds = new BoundingBox().inf();
        outline.forEach(bounds::ext);
        footprints.add(new Footprint(outline, -Float.MAX_VALUE, Float.MAX_VALUE, bounds));
    }

    /** A ground rail's ballast band along each of its centre lines, as triangles, solid at every height. */
    private void rail(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature route) {
        float scale = BoardGeometry.hexScale(), half = BoardRail.halfWidth(route);
        var centre = BoardGeometry.center(tile.coords(), 0);
        for (var path : BoardRail.paths(scene, tile, route)) {
            List<Vector3> triangles = new ArrayList<>();
            var bounds = new BoundingBox().inf();
            for (int i = 1; i < path.size(); i++) {
                Vector3 a = path.get(i - 1), b = path.get(i);
                var across = new Vector3(a.y - b.y, b.x - a.x, 0).nor().scl(half);
                Vector3[] corners = { new Vector3(a).add(across), new Vector3(b).add(across), new Vector3(b).sub(across),
                      new Vector3(a).sub(across) };
                for (Vector3 corner : corners) { corner.set(centre.x + corner.x * scale, centre.y + corner.y * scale, 0); }
                triangles.addAll(List.of(corners[0], corners[1], corners[2], corners[0], corners[2], corners[3]));
                for (Vector3 corner : corners) { bounds.ext(corner); }
            }
            if (!triangles.isEmpty()) { footprints.add(new Footprint(triangles, -Float.MAX_VALUE, Float.MAX_VALUE, bounds)); }
        }
    }

    /**
     * The deck's passage from the hex centre to each of its exit edges ({@link BoardBridge#PASSAGE_HALF_WIDTH} each
     * side), from the slab's underside to the deck: cover tall enough to reach it, under a deck at or near the ground,
     * does not grow through it. A raised deck leaves the ground beneath it as it is.
     */
    private void deck(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature bridge) {
        float s = BoardGeometry.hexScale(), centreZ = BoardBridge.deckZ(tile);
        var centre = BoardGeometry.center(tile.coords(), 0);
        for (int d = 0; d < 6; d++) {
            if ((bridge.bridgeExits() & (1 << d)) == 0) { continue; }
            var next = scene.tile(tile.coords().translated(d));
            var bank = BoardGeometry.center(tile.coords().translated(d), 0);
            var edge = new Vector3(bank).add(centre).scl(.5f);
            var across = new Vector3(edge).sub(centre).nor();
            across.set(-across.y, across.x, 0).scl(BoardBridge.passage(tile, d) * s);
            float edgeZ = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, next, d));
            passage(centre, edge, across, Math.min(centreZ, edgeZ) - BoardBridge.SLAB * s, Math.max(centreZ, edgeZ));
            if (BoardBridge.family(tile) != BoardBridge.Family.NATURAL && !BoardBridge.guideway(tile)
                  && BoardBridge.bank(tile, next, d) && next.elevation() < tile.elevation() + bridge.elevation()) {
                // The solid ramp begins at the bridge inset and ends with a wider authored landing block.
                // Reserve that whole passage, leaving the level centre of the span height-aware above.
                float lane = (tile.deckCorridor().sides() & 1 << d) != 0
                      ? BoardRoad.CORRIDOR_HALF : BoardRoad.Kind.PAVED.halfWidth;
                var outer = new Vector3(across).nor().scl(Math.max(BoardBridge.passage(tile, d),
                      lane + BoardBridgeFooting.terminalWidth()) * s);
                int e = Math.floorMod(1 - d, 6);
                var corner = BoardGeometry.corner(tile.coords(), 0, e);
                var edgeVector = BoardGeometry.corner(tile.coords(), 0, e + 1).sub(corner);
                var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
                var inner = new Vector3(centre).lerp(corner, .5f);
                var along = new Vector3(bank).sub(centre).nor();
                var a = new Vector3(centre).add(outer);
                var b = new Vector3(centre).sub(outer);
                a.mulAdd(along, new Vector3(inner).sub(a).dot(normal) / along.dot(normal));
                b.mulAdd(along, new Vector3(inner).sub(b).dot(normal) / along.dot(normal));
                quadrilateral(a, b, new Vector3(bank).sub(outer), new Vector3(bank).add(outer),
                      Math.min(BoardGeometry.groundZ(tile), BoardGeometry.groundZ(next)) - BoardBridge.SLAB * s, centreZ);
            }
        }
    }

    private void passage(Vector3 start, Vector3 end, Vector3 across, float low, float high) {
        var a = new Vector3(start).add(across);
        var b = new Vector3(start).sub(across);
        var c = new Vector3(end).sub(across);
        var d = new Vector3(end).add(across);
        quadrilateral(a, b, c, d, low, high);
    }

    private void quadrilateral(Vector3 a, Vector3 b, Vector3 c, Vector3 d, float low, float high) {
        var bounds = new BoundingBox().inf().ext(a).ext(b).ext(c).ext(d);
        footprints.add(new Footprint(List.of(a, b, c, new Vector3(a), new Vector3(c), d), low, high, bounds));
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
