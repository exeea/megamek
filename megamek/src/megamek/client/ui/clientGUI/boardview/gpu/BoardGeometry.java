/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.board.Coords;

/** Z-up, tightly tiled hex columns. Rendering and picking use these same surfaces. */
final class BoardGeometry {
    /** One level in authored board models, using the same units as an 84 by 72 hex. */
    static final int MODEL_LEVEL_HEIGHT = 18;
    /** Independent default for a whole unit occupying more than one game hex. */
    static final float DEFAULT_MULTI_HEX_UNIT_SCALE = 1.0f;
    /** Whether steps between hexes take room on both sides of their edge; see {@link Tuning#transitions()}. */
    static final boolean DEFAULT_TRANSITIONS = true;
    /** Metres of gap between neighbouring hex tiles; 0 keeps them joined. See {@link Tuning#padding()}. */
    static final float DEFAULT_PADDING = 0;
    /** Beyond this the room of a step eats so far into its hexes that their tops fold (measured with FoldProbe). */
    static final float MAX_PADDING = 8;

    /**
     * Board presentation settings. {@code transitions}: each step between hexes of different levels takes room on
     * both sides of the shared edge, a slope up to two levels and a deep cliff from three (see {@link BoardRelief}).
     * {@code padding}: metres of gap the board opens between neighbouring hexes. The lattice spreads apart while every
     * hex keeps its own size; where levels match the ground runs on through the gap, and where they differ the gap holds
     * the step, a slope up to two levels and a cliff above its talus from three. Padding and transitions both lay steps
     * out in the room beside an edge, so padding turns transitions off.
     */
    record Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade,
          float multiHexUnitScale, boolean transitions, float padding) {
        Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade) {
            this(hexScale, unitScale, unitHeightScale, levelHeight, gridShade, DEFAULT_MULTI_HEX_UNIT_SCALE);
        }

        Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade,
              float multiHexUnitScale) {
            this(hexScale, unitScale, unitHeightScale, levelHeight, gridShade, multiHexUnitScale, DEFAULT_TRANSITIONS);
        }

        Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade,
              float multiHexUnitScale, boolean transitions) {
            this(hexScale, unitScale, unitHeightScale, levelHeight, gridShade, multiHexUnitScale, transitions,
                  DEFAULT_PADDING);
        }

        Tuning {
            if (!Float.isFinite(hexScale) || hexScale <= 0 || !Float.isFinite(unitScale) || unitScale <= 0
                  || !Float.isFinite(unitHeightScale) || unitHeightScale <= 0 || levelHeight < 1
                  || !Float.isFinite(gridShade) || gridShade < 0 || gridShade > 1
                  || !Float.isFinite(multiHexUnitScale) || multiHexUnitScale <= 0
                  || !Float.isFinite(padding) || padding < 0 || padding > MAX_PADDING) {
                throw new IllegalArgumentException("Invalid board dimensions");
            }
            transitions &= padding == 0;
        }

        /**
         * Whether a step's face can lie back into the room beside its edge, so the ground at a point may be that face
         * rather than a hex's top: with hex transitions or padding.
         */
        boolean stepsBetweenTops() {
            return transitions || padding > 0;
        }
    }

    static final Tuning DEFAULTS = new Tuning(1, 1.0f, 1.0f, MODEL_LEVEL_HEIGHT, 0.8f);
    /**
     * Native tactical markers keep this fraction of the hex radius clear of the shared hex edges. Exactly on
     * an edge a marker is coplanar with the terrain there and flickers against it while the camera rotates.
     */
    static final float MARKER_INSET = 0.1f;
    static final float TILE_WIDTH = HexTileset.HEX_W;
    static final float TILE_HEIGHT = HexTileset.HEX_H;
    static float HEX_SCALE;
    static float WIDTH;
    static float HEIGHT;
    static float LEVEL;
    static float UNIT_SCALE;
    static float MULTI_HEX_UNIT_SCALE;
    static float UNIT_HEIGHT_SCALE;
    private static Tuning tuning;
    private static int revision;
    private static int terrainRevision;

    static {
        tune(DEFAULTS);
    }

    private BoardGeometry() { }

    static Tuning tuning() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? tuning : settings.geometry();
    }

    static float hexScale() { return tuning().hexScale(); }
    static float width() { return TILE_WIDTH * hexScale(); }
    static float height() { return TILE_HEIGHT * hexScale(); }
    static float level() {
        Tuning settings = tuning();
        return settings.levelHeight() * settings.hexScale();
    }
    static float unitScale() { return tuning().unitScale(); }
    static float multiHexUnitScale() { return tuning().multiHexUnitScale(); }
    static float unitHeightScale() { return tuning().unitHeightScale(); }

    static void tune(Tuning next) {
        if (next.equals(tuning)) {
            return;
        }
        tuning = next;
        HEX_SCALE = next.hexScale();
        WIDTH = TILE_WIDTH * HEX_SCALE;
        HEIGHT = TILE_HEIGHT * HEX_SCALE;
        LEVEL = next.levelHeight() * HEX_SCALE;
        UNIT_SCALE = next.unitScale();
        MULTI_HEX_UNIT_SCALE = next.multiHexUnitScale();
        UNIT_HEIGHT_SCALE = next.unitHeightScale();
        revision++;
    }

    static int revision() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? revision : settings.revision();
    }

    /** Terrain controls are applied on the GL thread between frames, like the board dimensions above. */
    static void terrainChanged() {
        terrainRevision++;
        revision++;
    }

    static int terrainRevision() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? terrainRevision : settings.terrainRevision();
    }

    static float centerX(Coords coords) {
        float width = width();
        return coords.getX() * width * 0.75f + width / 2;
    }

    static float centerY(Coords coords) {
        float height = height();
        return -(coords.getY() * height + (coords.getX() & 1) * height / 2 + height / 2);
    }

    static Vector3 center(Coords coords, float elevation) {
        return new Vector3(centerX(coords), centerY(coords), elevation * level());
    }

    static Vector3 corner(Coords coords, float elevation, int corner) {
        return corner(new Vector3(), coords, elevation, corner);
    }

    private static final int[] CORNER_DX = { 2, 1, -1, -2, -1, 1 };
    private static final int[] CORNER_DY = { 0, 1, 1, 0, -1, -1 };

    /**
     * Corners lie on a lattice of quarter widths and half heights. Computing them from lattice indices gives every
     * hex that shares a corner bit-identical coordinates, independently of the hex scale.
     */
    static Vector3 corner(Vector3 out, Coords coords, float elevation, int corner) {
        int k = Math.floorMod(corner, 6);
        return out.set((3 * coords.getX() + 2 + CORNER_DX[k]) * (width() / 4),
              (-(2 * coords.getY() + (coords.getX() & 1) + 1) + CORNER_DY[k]) * (height() / 2), elevation * level());
    }

    static int edgeDirection(int edge) {
        return Math.floorMod(1 - edge, 6);
    }

    /** Pulls a point toward a hex center inside the hex plane; the point keeps its elevation. */
    static Vector3 inset(Vector3 point, Vector3 center, float fraction) {
        return point.set(point.x + (center.x - point.x) * fraction,
              point.y + (center.y - point.y) * fraction, point.z);
    }

    /** Liquid beds include a two-world-unit visual recess even without positive game water depth. */
    static float groundZ(BoardScene.Tile tile) {
        return tile.elevation() * level() - (tile.liquid().present() ? Math.max(2 * hexScale(), tile.waterDepth() * level()) : 0);
    }

    static float waterZ(BoardScene.Tile tile) {
        return tile.elevation() * level() - hexScale();
    }

    static float surfaceZ(BoardScene.Tile tile) {
        if (tile.frozen()) {
            return tile.elevation() * level();
        }
        return tile.liquid().present() ? waterZ(tile) : groundZ(tile);
    }

    static float floor(BoardScene scene) {
        float lowest = Float.POSITIVE_INFINITY;
        float depth = level();
        for (BoardScene.Tile tile : scene.tiles()) {
            lowest = Math.min(lowest, groundZ(tile));
            // At small elevation-height settings, sculpted hollows can extend below a single game level.
            depth = Math.max(depth, BoardRelief.headroom(tile));
        }
        return lowest - depth;
    }

    /** Shared atmosphere baseline: hex elevation, never a riverbed, water depth, or model height. */
    static float weatherBase(BoardScene scene) {
        return scene.tiles().stream().mapToInt(BoardScene.Tile::elevation).min().orElse(0) * level();
    }

    static boolean contains(Coords coords, float x, float y) {
        float dx = Math.abs(x - centerX(coords));
        float dy = Math.abs(y - centerY(coords));
        return dy <= height() / 2 + 0.001f && (height() / 2) * dx + (width() / 4) * dy <= width() * height() / 4 + 0.001f;
    }

    /** The hex whose footprint holds (x, y), or null off the board. */
    static BoardScene.Tile tile(BoardScene scene, float x, float y) {
        int column = (int) Math.floor(x / (width() * .75f));
        int row = (int) Math.floor(-y / height());
        for (int cx = column - 1; cx <= column + 1; cx++) {
            for (int cy = row - 1; cy <= row + 1; cy++) {
                BoardScene.Tile tile = scene.tile(new Coords(cx, cy));
                if (tile != null && contains(tile.coords(), x, y)) { return tile; }
            }
        }
        return null;
    }

    record Hit(Coords coords, float distance, boolean hardSurface) {
        Hit(Coords coords, float distance) { this(coords, distance, false); }
    }

    static Coords pick(BoardScene scene, Ray ray) {
        Hit hit = hit(scene, ray);
        return hit == null ? null : hit.coords();
    }

    /** Intersect the same triangles used to draw carved roads, banks, beds and exposed sides. */
    static Hit hit(BoardScene scene, Ray ray) {
        return hit(scene, ray, scene.tiles(), floor(scene));
    }

    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor) {
        return hit(scene, ray, candidates, floor, (BoardSurface.Cache) null);
    }

    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor, BoardSurface.Cache cache) {
        return hit(scene, ray, candidates, floor, cache, null);
    }

    /** Borrow the installed terrain's finished geometry without retaining its builders or scene. */
    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        return hit(scene, ray, candidates, floor, null, surfaces);
    }

    private static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor,
          BoardSurface.Cache cache, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        Hit hit = nearest(scene, ray, candidates, floor, cache, surfaces);
        if (hit == null) {
            // A ray exactly along a shared triangle edge can miss both triangles in float arithmetic. Symmetry lines
            // through hex centres make that reproducible for axis-aligned pointer rays, so retry once, nudged.
            Ray nudged = new Ray(new Vector3(ray.origin).add(.0013f * hexScale(), .0007f * hexScale(), 0), ray.direction);
            hit = nearest(scene, nudged, candidates, floor, cache, surfaces);
        }
        return hit;
    }

    /**
     * The lower neighbour for a hit on the talus inside its footprint; otherwise the cliff's owner. With transitions or
     * padding a step's lower half lies in the lower hex's footprint, and a hit there belongs to it.
     */
    private static Coords foot(BoardScene scene, Coords owner, Vector3 hit) {
        if (contains(owner, hit.x, hit.y)) { return owner; }
        BoardScene.Tile high = scene.tile(owner);
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.tile(owner.translated(direction));
            if (neighbor == null || !contains(neighbor.coords(), hit.x, hit.y)) { continue; }
            float talus = level() * .3f;
            if (tuning().stepsBetweenTops() && high != null) {
                talus = Math.max(talus, (high.elevation() * level() - surfaceZ(neighbor)) * .5f);
            }
            if (hit.z < surfaceZ(neighbor) + talus) { return neighbor.coords(); }
        }
        return owner;
    }

    /**
     * The hex whose footprint holds a hit on a top, bank, bed or water: its owner, or the neighbour whose footprint
     * holds it where the owner's faces reach past its own, as a water hex's shore does over a land corner.
     */
    private static Coords footprint(BoardScene scene, Coords owner, Vector3 hit) {
        if (contains(owner, hit.x, hit.y)) { return owner; }
        for (int direction = 0; direction < 6; direction++) {
            Coords other = owner.translated(direction);
            if (scene.tile(other) != null && contains(other, hit.x, hit.y)) { return other; }
        }
        return owner;
    }

    private record PickCandidate(BoardScene.Tile tile, float entry, int order) { }

    private static Hit nearest(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor,
          BoardSurface.Cache cache, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        Coords result = null;
        float nearest = Float.POSITIVE_INFINITY;
        boolean hardSurface = false;
        Vector3 hit = new Vector3();
        var ordered = new ArrayList<PickCandidate>();
        var bounds = new BoundingBox();
        int order = 0, nearestOrder = Integer.MAX_VALUE;
        for (BoardScene.Tile tile : candidates) {
            float high = tile.elevation() * level();
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
                high = Math.max(high, BoardSurface.roadEdgeElevation(tile, neighbor, direction) * level());
            }
            high += BoardRelief.decoration(tile);
            // Sculpted cliffs, talus and rim lips can reach slightly beyond the logical footprint.
            float reach = 2 * BoardRelief.overhang();
            bounds.set(new Vector3(centerX(tile.coords()) - (width() + reach) / 2,
                        centerY(tile.coords()) - (height() + reach) / 2, floor - .005f),
                  new Vector3(centerX(tile.coords()) + (width() + reach) / 2,
                        centerY(tile.coords()) + (height() + reach) / 2, high + .005f));
            if (Intersector.intersectRayBounds(ray, bounds, hit)) {
                ordered.add(new PickCandidate(tile, ray.origin.dst2(hit), order));
            }
            order++;
        }
        ordered.sort(Comparator.comparingDouble(PickCandidate::entry));
        for (PickCandidate candidate : ordered) {
            // A low camera ray may cross the entire board. Do not reconstruct surfaces hidden behind the first hit.
            if (candidate.entry() > nearest) { break; }
            BoardScene.Tile tile = candidate.tile();
            BoardSurface surface = surfaces != null ? null
                  : cache == null ? new BoardSurface(scene, tile) : cache.get(scene, tile);
            BoardTacticalGeometry.Surface finished = surfaces == null ? null : surfaces.apply(tile.coords());
            for (BoardSurface.Face face : finished == null ? surface.faces : finished.faces()) {
                if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)
                      && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                    nearest = ray.origin.dst2(hit);
                    nearestOrder = candidate.order();
                    // Rim rocks and a waterfall's bed lip can overhang a lower hex just like its cliff.
                    // A high bed hit belongs to that upper pool; only the low talus belongs to the receiving hex.
                    result = face.finish() == BoardSurface.Finish.OUTCROP || face.finish() == BoardSurface.Finish.BED
                          ? foot(scene, tile.coords(), hit) : footprint(scene, tile.coords(), hit);
                    hardSurface = hardSurface(tile, face);
                }
            }
            for (BoardSurface.Face face : finished == null ? surface.waterFaces : finished.water()) {
                if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)
                      && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                    nearest = ray.origin.dst2(hit);
                    nearestOrder = candidate.order();
                    result = footprint(scene, tile.coords(), hit);
                    hardSurface = false;
                }
            }
            for (BoardSurface.Face face : finished == null ? surface.walls(scene, floor) : finished.walls()) {
                if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)
                      && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                    nearest = ray.origin.dst2(hit);
                    nearestOrder = candidate.order();
                    // A rock face belongs to the higher hex that owns it; the scree at its foot, which spreads
                    // past the logical edge, lies on the lower hex whose footprint contains it.
                    result = foot(scene, tile.coords(), hit);
                    hardSurface = hardSurface(tile, face);
                }
            }
            for (BoardSurface.Side side : finished == null ? surface.waterfalls : finished.waterfalls()) {
                Vector3 lowerA = new Vector3(side.a().x, side.a().y, side.lowA());
                Vector3 lowerB = new Vector3(side.b().x, side.b().y, side.lowB());
                float distance = Float.POSITIVE_INFINITY;
                if (Intersector.intersectRayTriangle(ray, side.a(), lowerA, lowerB, hit)) {
                    distance = ray.origin.dst2(hit);
                }
                if (Intersector.intersectRayTriangle(ray, side.a(), lowerB, side.b(), hit)) {
                    distance = Math.min(distance, ray.origin.dst2(hit));
                }
                if (Float.isFinite(distance) && nearer(distance, nearest, candidate.order(), nearestOrder)) {
                    nearest = distance;
                    nearestOrder = candidate.order();
                    result = tile.coords();
                    hardSurface = false;
                }
            }
        }
        return result == null ? null : new Hit(result, nearest, hardSurface);
    }

    /** Preserve the original candidate order when shared-edge triangles have exactly the same hit distance. */
    private static boolean nearer(float distance, float nearest, int order, int nearestOrder) {
        return distance < nearest || distance == nearest && order < nearestOrder;
    }

    private static boolean hardSurface(BoardScene.Tile tile, BoardSurface.Face face) {
        if (face.finish() == BoardSurface.Finish.ICE || face.finish() == BoardSurface.Finish.DRESSING) { return false; }
        return face.finish() == BoardSurface.Finish.OUTCROP
              || tile.surface() == BoardScene.Surface.ROCK || tile.surface() == BoardScene.Surface.CONCRETE
              || tile.surface() == BoardScene.Surface.GRASS && face.finish() == BoardSurface.Finish.WALL;
    }
}
