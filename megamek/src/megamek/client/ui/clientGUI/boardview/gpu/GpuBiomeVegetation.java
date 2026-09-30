/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.Configuration;
import megamek.common.board.Coords;

/**
 * World-aligned crop rows and clustered reeds in persistent per-chunk instanced batches. Terrain workers plant both
 * with each chunk's finished support ({@link BoardPlants}); this class only shapes, batches and draws them.
 */
final class GpuBiomeVegetation implements Disposable {
    static final class Kind extends FloatAttribute {
        static final long TYPE = register("boardBiomeVegetation");
        static final long LOD = register("boardBiomeVegetationLod");
        Kind(boolean crop) { super(TYPE, crop ? 1 : 2); }
        @Override
        public Kind copy() { return new Kind(value == 1); }
    }

    private static final float[] DENSITY = { 1, .35f, .08f };
    // Cumulative coverage: fine plants replace medium plants, which replace the distant stems.
    // The same intervals drive conservative CPU submission and the per-root GPU cross-fade.
    private static final float[] START_PIXELS = { 240, 64, 12 }, FULL_PIXELS = { 480, 128, 36 };
    private static final int STRIDE = 4;
    // Seven registered plants per strip; lengths stay in world metres, independent of per-row height variation.
    static final int PLANTS_PER_ROW = 7;
    // Appearance controls: uniform size, extra width without added height, and clear metres along a row.
    static final float CROP_SCALE = 2f;
    static final float CROP_WIDTH_SCALE = 2f;
    static final float PLANT_GAP = 1f;
    // Maximum resting lean per horizontal axis, in radians; roots retain their planted positions.
    static final float CROP_LEAN = .12f;
    private static final float PLANT_WIDTH = .30f * CROP_SCALE * CROP_WIDTH_SCALE;
    static final float PLANT_SPACING = PLANT_WIDTH + PLANT_GAP;
    static final float ROW_LENGTH = PLANTS_PER_ROW * PLANT_SPACING;
    static final float ROW_HALF_WIDTH = .36f * CROP_SCALE * CROP_WIDTH_SCALE;
    static final float CANOPY_HEIGHT = .88f;
    private static final float PLANT_FILL = .70f;
    private static final float ALONG_FILL = PLANT_WIDTH / PLANT_SPACING;
    private static final int ROW_PIXELS = 1024;

    /**
     * One field hex's crops: near strips and distant canopy runs, each as roots with parallel supported length, rise
     * and U interval.
     */
    record Crops(FloatArray roots, FloatArray spans, FloatArray canopy, FloatArray canopySpans) { }

    /**
     * Plant a field hex on the finished support of its chunk. Terrain workers call this while preparing the chunk,
     * so crops are installed together with the ground they stand on and replaced with it at every detail change.
     */
    static Crops plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
        var patch = new Patch(scene, tile, surface);
        patch.prepare(scene, tile);
        // Installed and cached chunks keep these for their lifetime; drop the arrays' growth slack.
        patch.roots.shrink();
        patch.rowSpans.shrink();
        patch.canopy.shrink();
        patch.canopySpans.shrink();
        return new Crops(patch.roots, patch.rowSpans, patch.canopy, patch.canopySpans);
    }

    /** Plant a marsh hex's reeds up to the share of the lattice its chunk's detail draws, as (x, y, z, seed). */
    static FloatArray plantReeds(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface, float density) {
        var patch = new Patch(scene, tile, surface);
        patch.prepare(scene, tile, density);
        patch.roots.shrink();
        return patch.roots;
    }

    /** CPU-only roots are also useful for verifying ground contact, shared rows and pool/road exclusion. */
    static final class Patch {
        // Crops need this much of the shared coverage field; a furrow piece is (start, height, end, height, triangle).
        private static final float CULTIVATED = .60f;
        private static final int PIECE = 5;
        final BoardVegetation.Key key;
        final List<BoardSurface.Face> ground;
        final Support support, water;
        final BoardScene.Biome kind;
        final boolean uniform;
        final float levelZ;
        final FloatArray roots = new FloatArray();
        // Crop-only, parallel to roots: supported length, end-to-end rise, and the original strip's U interval.
        final FloatArray rowSpans = new FloatArray();
        // Crop-only distant canopy: whole runs of adjoining strips along a furrow, in the same layout. U repeats per strip.
        final FloatArray canopy = new FloatArray(), canopySpans = new FloatArray();
        // Crop-only: the current furrow's strips as (start, end, start height, end height) in furrow metres.
        private final FloatArray furrow = new FloatArray();
        final BoardRoad road;
        // Crop-only: per edge its origin, direction, and length where it borders other ground or another level.
        private final float[] edges = new float[30];
        private final List<BoardSurface.Face> crossed = new ArrayList<>();
        final float across, along;
        final int minX, minY, maxX, maxY;
        int x, y;
        int tier = 2;
        float prepared;

        Patch(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
            key = BoardVegetation.key(scene, tile);
            ground = support(tile, surface);
            kind = BoardBiome.plantKind(scene, tile);
            // Crop rows intersect the triangles directly; only scattered reeds need the point index.
            support = kind == BoardScene.Biome.FIELD ? null : new Support(ground);
            water = tile.liquid().present() ? new Support(surface.water()) : null;
            levelZ = BoardGeometry.groundZ(tile);
            uniform = key.sites().stream().allMatch(site -> site != null && site.biome() == kind
                  && site.elevation() == tile.elevation());
            road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
            across = BoardBiome.ROW_METRES;
            along = kind == BoardScene.Biome.FIELD ? ROW_LENGTH : 1.15f;
            float cx = BoardGeometry.centerX(tile.coords()) / BoardRelief.metres(1);
            float cy = BoardGeometry.centerY(tile.coords()) / BoardRelief.metres(1);
            float u = cx * BoardBiome.ROW_X + cy * BoardBiome.ROW_Y;
            float v = -cx * BoardBiome.ROW_Y + cy * BoardBiome.ROW_X;
            minX = (int) Math.floor((u - 21) / across);
            maxX = (int) Math.ceil((u + 21) / across);
            x = minX;
            minY = (int) Math.floor((v - 21) / along);
            y = minY;
            maxY = (int) Math.ceil((v + 21) / along);
        }

        void prepare(BoardScene scene, BoardScene.Tile tile) {
            prepare(scene, tile, 1);
        }

        /** Roots up to this share of the reed lattice, extending an earlier call's distant subset without moving it. */
        void prepare(BoardScene scene, BoardScene.Tile tile, float density) {
            // A strip contains a whole run of plants. Keep every crop row at every distance;
            // cheaper templates, rather than missing strips, provide its distance reduction.
            if (kind == BoardScene.Biome.FIELD) {
                if (prepared < 1) { plant(scene, tile); prepared = 1; }
                return;
            }
            float metre = BoardRelief.metres(1);
            // The distant subset first: a chunk at coarser terrain detail plants only the tiers it can draw, and a
            // finer replacement extends the same lattice.
            while (prepared < density) {
                if (y > maxY) {
                    prepared = DENSITY[tier--];
                    x = minX; y = minY;
                    continue;
                }
                int ix = x++, iy = y;
                if (x > maxX) { x = minX; y++; }
                // Thin the same world-space lattice at every LOD, independently of wetland coverage and plant height.
                // Reject before sampling support geometry, so omitted roots also avoid preparation and buffer cost.
                if (BoardRelief.hash(ix + 379, iy - 827) >= .46f) { continue; }
                float seed = BoardRelief.hash(ix, iy);
                if (seed >= DENSITY[tier] || (tier < 2 && seed < DENSITY[tier + 1])) { continue; }
                float u = (ix + (BoardRelief.hash(ix + 37, iy) - .5f) * .7f) * across;
                float v = (iy + (BoardRelief.hash(ix, iy + 17) - .5f) * .55f) * along;
                float px = (u * BoardBiome.ROW_X - v * BoardBiome.ROW_Y) * metre;
                float py = (u * BoardBiome.ROW_Y + v * BoardBiome.ROW_X) * metre;
                if (BoardGeometry.tile(scene, px, py) != tile) { continue; }
                // Water's tactical top includes the water plane. Roots instead follow the actual bank/bar mesh.
                float z = support.height(px, py);
                if (!Float.isFinite(z)) { continue; }
                // An interior plateau has unit coverage. Boundary and sloping samples still use the shared field.
                float cover = uniform && Math.abs(z - levelZ) <= .15f * metre ? 1
                      : BoardBiome.coverage(scene, kind, px, py, z);
                if (seed > .88f * BoardRelief.smooth(cover)) { continue; }
                if (water != null) {
                    if (z <= water.height(px, py) + .015f * metre) { continue; }
                }
                if (road != null && road.distance((px - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                      (py - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale()) < BoardRoad.SHOULDER + 1) { continue; }
                float wet = BoardBiome.wetness(px / metre, py / metre);
                if (wet < .48f || wet > .80f) { continue; }
                roots.addAll(px, py, z - .018f * metre, seed);
            }
        }

        /**
         * Plant alternate furrows: larger plants keep 2.3 m row spacing without shifting the terrain's ridges. Each
         * supporting triangle is intersected once with the furrows crossing it, giving the exact published ground
         * along every furrow as (start, height, end, height, triangle) pieces in furrow metres.
         */
        private void plant(BoardScene scene, BoardScene.Tile tile) {
            float metre = BoardRelief.metres(1);
            int first = minX + (minX & 1);
            var furrows = new FloatArray[(maxX - first) / 2 + 1];
            float[] u = new float[3], v = new float[3], z = new float[3];
            for (int f = 0; f < ground.size(); f++) {
                var face = ground.get(f);
                if (face.finish() == BoardSurface.Finish.ICE || face.finish() == BoardSurface.Finish.DRESSING) { continue; }
                project(face.a(), 0, metre, u, v, z);
                project(face.b(), 1, metre, u, v, z);
                project(face.c(), 2, metre, u, v, z);
                int ix = (int) Math.ceil(Math.min(u[0], Math.min(u[1], u[2])) / across);
                for (ix += ix & 1; ix * across <= Math.max(u[0], Math.max(u[1], u[2])); ix += 2) {
                    int index = (ix - first) / 2;
                    if (index < 0 || index >= furrows.length) { continue; }
                    float line = ix * across, low = Float.POSITIVE_INFINITY, lowZ = 0, high = Float.NEGATIVE_INFINITY, highZ = 0;
                    for (int i = 0; i < 3; i++) {
                        int j = i == 2 ? 0 : i + 1;
                        float from = u[i] - line, to = u[j] - line;
                        if (from * to > 0) { continue; }
                        float t = from == to ? 0 : from / (from - to);
                        float at = v[i] + (v[j] - v[i]) * t, height = z[i] + (z[j] - z[i]) * t;
                        if (at < low) { low = at; lowZ = height; }
                        if (at > high) { high = at; highZ = height; }
                    }
                    if (high - low > .0001f) {
                        if (furrows[index] == null) { furrows[index] = new FloatArray(); }
                        furrows[index].addAll(low, lowZ, high, highZ, f);
                    }
                }
            }
            edges(scene, tile);
            for (int index = 0; index < furrows.length; index++) {
                var pieces = furrows[index];
                if (pieces == null) { continue; }
                sort(pieces);
                int ix = first + 2 * index;
                float px = ix * across * BoardBiome.ROW_X * metre, py = ix * across * BoardBiome.ROW_Y * metre;
                // Clip the furrow to this hex; the neighbour plants the rest of a shared strip.
                float low = Float.NEGATIVE_INFINITY, high = Float.POSITIVE_INFINITY;
                for (int edge = 0; edge < 30; edge += 5) {
                    float distance = edges[edge + 2] * (py - edges[edge + 1]) - edges[edge + 3] * (px - edges[edge]);
                    float rate = (edges[edge + 2] * BoardBiome.ROW_X + edges[edge + 3] * BoardBiome.ROW_Y) * metre;
                    if (Math.abs(rate) < .00001f) { if (distance < 0) { high = low; } }
                    else if (rate > 0) { low = Math.max(low, -distance / rate); }
                    else { high = Math.min(high, -distance / rate); }
                }
                if (!(high - low >= .03f)) { continue; }
                furrow.clear();
                // Alternate planted rows offset their stems by half a plant interval, in world space across hexes.
                float offset = ((ix / 2) & 1) * PLANT_SPACING / 2;
                for (int iy = (int) Math.floor((low - offset) / ROW_LENGTH); iy * ROW_LENGTH + offset < high; iy++) {
                    float start = Math.max(low, iy * ROW_LENGTH + offset), end = Math.min(high, (iy + 1) * ROW_LENGTH + offset);
                    if (end - start >= .03f) {
                        strip(scene, tile, pieces, px, py, (iy + .5f) * ROW_LENGTH + offset, start, end, BoardRelief.hash(ix, iy));
                    }
                }
                canopy(px, py, metre, BoardRelief.hash(ix, 0), offset);
            }
        }

        /**
         * Distant canopy runs: adjoining strips of one furrow merge while every strip end stays within 5 cm of the
         * run's line. At the distant tier a metre covers at most a few pixels, and one run replaces several strips.
         */
        private void canopy(float px, float py, float metre, float seed, float offset) {
            float[] s = furrow.items;
            float gap = .001f * BoardGeometry.hexScale() / metre;
            var line = new FloatArray();
            for (int k = 0; k <= furrow.size; k += 4) {
                // Each gap closes the polyline of strip ends so far.
                if (k == furrow.size || line.size > 0 && s[k] > line.items[line.size - 2] + gap) {
                    float[] p = line.items;
                    for (int i = 0, count = line.size / 2; i < count - 1; ) {
                        int j = straight(p, i, count, .05f * metre);
                        // The run follows its chord, which every strip end lies close to.
                        float from = p[2 * i], to = p[2 * j], middle = (from + to) / 2;
                        float x = px - middle * BoardBiome.ROW_Y * metre, y = py + middle * BoardBiome.ROW_X * metre;
                        float u = (from - offset) / ROW_LENGTH - (float) Math.floor((from - offset) / ROW_LENGTH);
                        canopy.addAll(x, y, (p[2 * i + 1] + p[2 * j + 1]) / 2 - .018f * metre, seed);
                        canopySpans.addAll((to - from) * metre, p[2 * j + 1] - p[2 * i + 1], u, u + (to - from) / ROW_LENGTH);
                        i = j;
                    }
                    line.clear();
                }
                if (k == furrow.size) { break; }
                if (line.size == 0) { line.addAll(s[k], s[k + 2]); }
                line.addAll(s[k + 1], s[k + 3]);
            }
        }

        /** The furthest (distance, height) point after {@code i} that keeps every point between within the tolerance. */
        private static int straight(float[] p, int i, int count, float tolerance) {
            int j = i + 1;
            for (boolean fits = true; fits && j + 1 < count; ) {
                int k = j + 1;
                for (int m = i + 1; fits && m < k; m++) {
                    float expected = p[2 * i + 1] + (p[2 * k + 1] - p[2 * i + 1]) * (p[2 * m] - p[2 * i]) / (p[2 * k] - p[2 * i]);
                    fits = Math.abs(p[2 * m + 1] - expected) < tolerance;
                }
                if (fits) { j = k; }
            }
            return j;
        }

        /** One lattice strip, split where its ground bends or stops being cultivated. */
        private void strip(BoardScene scene, BoardScene.Tile tile, FloatArray pieces, float px, float py, float centre,
              float start, float end, float seed) {
            float metre = BoardRelief.metres(1);
            FloatArray points = new FloatArray();
            if (road == null && inland(px, py, start, metre) && inland(px, py, end, metre) && level(pieces, start, end)) {
                // Cultivated everywhere on this strip: follow the exact triangle pieces.
                float gap = .001f * BoardGeometry.hexScale() / metre, last = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < pieces.size; i += PIECE) {
                    float from = Math.max(pieces.items[i], start), to = Math.min(pieces.items[i + 2], end);
                    if (to <= Math.max(from, last)) { continue; }
                    if (from > last + gap) {
                        spans(points, pieces, px, py, centre, seed, metre);
                        points.clear();
                        points.addAll(from, along(pieces, i, from));
                    }
                    points.addAll(to, along(pieces, i, to));
                    last = to;
                }
                spans(points, pieces, px, py, centre, seed, metre);
                return;
            }
            // Field edges, slopes and roads: sample more finely than a stalk interval, then merge the same way.
            int steps = Math.max(1, (int) Math.ceil((end - start) / .15f));
            for (int i = 0; i <= 2 * steps; i++) {
                float at = Math.clamp(start + (end - start) * i / (2 * steps), start + .0001f, end - .0001f);
                float height = cultivated(scene, tile, pieces, px, py, at, metre);
                if (Float.isFinite(height)) {
                    points.addAll(at, height);
                } else {
                    spans(points, pieces, px, py, centre, seed, metre);
                    points.clear();
                }
            }
            spans(points, pieces, px, py, centre, seed, metre);
        }

        /** Greedy runs of (distance, height) points that stay within 8 mm of one straight strip. */
        private void spans(FloatArray points, FloatArray pieces, float px, float py, float centre, float seed, float metre) {
            float[] p = points.items;
            int count = points.size / 2;
            for (int i = 0; i < count - 1; ) {
                int j = straight(p, i, count, .008f * metre);
                float from = p[2 * i], to = p[2 * j], middle = (from + to) / 2;
                if (to - from >= .03f) {
                    float x = px - middle * BoardBiome.ROW_Y * metre, y = py + middle * BoardBiome.ROW_X * metre;
                    float root = ground(pieces, middle, x, y, metre);
                    roots.addAll(x, y, (Float.isFinite(root) ? root : (p[2 * i + 1] + p[2 * j + 1]) / 2) - .018f * metre, seed);
                    rowSpans.addAll((to - from) * metre, p[2 * j + 1] - p[2 * i + 1],
                          Math.max(0, .5f + (from - centre) / ROW_LENGTH), Math.min(1, .5f + (to - centre) / ROW_LENGTH));
                    furrow.addAll(from, to, p[2 * i + 1], p[2 * j + 1]);
                }
                i = j;
            }
        }

        /** Supported ground at this point of the furrow if it is cultivated and clear of the road, otherwise NaN. */
        private float cultivated(BoardScene scene, BoardScene.Tile tile, FloatArray pieces, float px, float py, float at,
              float metre) {
            float x = px - at * BoardBiome.ROW_Y * metre, y = py + at * BoardBiome.ROW_X * metre;
            float z = ground(pieces, at, x, y, metre);
            if (!Float.isFinite(z)) { return Float.NaN; }
            if ((inland(px, py, at, metre) ? BoardBiome.sameLevel(z, levelZ) : BoardBiome.coverage(scene, kind, x, y, z))
                  < CULTIVATED) { return Float.NaN; }
            // Include the horizontal canopy's reach in the road clearance.
            if (road != null && road.distance((x - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale(),
                  (y - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale())
                  < BoardRoad.SHOULDER + 1 + ROW_HALF_WIDTH * metre / BoardGeometry.hexScale()) { return Float.NaN; }
            return z;
        }

        /** {@link BoardSurface#sampleHeight} over only the triangles this furrow crosses near the point. */
        private float ground(FloatArray pieces, float at, float x, float y, float metre) {
            float reach = .001f * BoardGeometry.hexScale() / metre;
            crossed.clear();
            for (int i = 0; i < pieces.size; i += PIECE) {
                if (at >= pieces.items[i] - reach && at <= pieces.items[i + 2] + reach) {
                    crossed.add(ground.get((int) pieces.items[i + 4]));
                }
            }
            return BoardSurface.sampleHeight(crossed, x, y, Float.NaN);
        }

        /** Hex edges on the origin plane, and which of them border ground other than this field's level. */
        private void edges(BoardScene scene, BoardScene.Tile tile) {
            for (int edge = 0; edge < 6; edge++) {
                var a = BoardGeometry.corner(tile.coords(), 0, edge);
                var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                var next = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                boolean foreign = next == null || BoardBiome.kind(next) != kind || BoardGeometry.groundZ(next) != levelZ;
                edges[5 * edge] = a.x;
                edges[5 * edge + 1] = a.y;
                edges[5 * edge + 2] = b.x - a.x;
                edges[5 * edge + 3] = b.y - a.y;
                edges[5 * edge + 4] = foreign ? (float) Math.hypot(b.x - a.x, b.y - a.y) : 0;
            }
        }

        /**
         * Farther than the coverage fade from every edge bordering other ground or another level. Every hex weighted
         * by {@link BoardBiome#coverage} there matches this one, so coverage reduces to this hex's own level test.
         */
        private boolean inland(float px, float py, float at, float metre) {
            float x = px - at * BoardBiome.ROW_Y * metre, y = py + at * BoardBiome.ROW_X * metre;
            float reach = BoardRelief.metres(BoardBiome.EDGE_METRES);
            for (int edge = 0; edge < 30; edge += 5) {
                if (edges[edge + 4] > 0 && edges[edge + 2] * (y - edges[edge + 1]) - edges[edge + 3] * (x - edges[edge])
                      < reach * edges[edge + 4]) { return false; }
            }
            return true;
        }

        /** The height difference is largest at a piece's end, so testing the ends covers the whole strip. */
        private boolean level(FloatArray pieces, float start, float end) {
            for (int i = 0; i < pieces.size; i += PIECE) {
                float from = Math.max(pieces.items[i], start), to = Math.min(pieces.items[i + 2], end);
                if (to > from && (BoardBiome.sameLevel(along(pieces, i, from), levelZ) < CULTIVATED
                      || BoardBiome.sameLevel(along(pieces, i, to), levelZ) < CULTIVATED)) { return false; }
            }
            return true;
        }

        private static float along(FloatArray pieces, int i, float at) {
            float[] p = pieces.items;
            return p[i + 1] + (p[i + 3] - p[i + 1]) * (at - p[i]) / (p[i + 2] - p[i]);
        }

        private static void project(Vector3 point, int i, float metre, float[] u, float[] v, float[] z) {
            u[i] = (point.x * BoardBiome.ROW_X + point.y * BoardBiome.ROW_Y) / metre;
            v[i] = (point.y * BoardBiome.ROW_X - point.x * BoardBiome.ROW_Y) / metre;
            z[i] = point.z;
        }

        /** Few pieces per furrow: an insertion sort by start keeps them in furrow order. */
        private static void sort(FloatArray pieces) {
            float[] p = pieces.items, piece = new float[PIECE];
            for (int i = PIECE; i < pieces.size; i += PIECE) {
                System.arraycopy(p, i, piece, 0, PIECE);
                int j = i - PIECE;
                for (; j >= 0 && p[j] > piece[0]; j -= PIECE) { System.arraycopy(p, j, p, j + PIECE, PIECE); }
                System.arraycopy(piece, 0, p, j + PIECE, PIECE);
            }
        }
    }

    /** Small CPU index of the published triangles, not a second height field. No per-root whole-mesh scan. */
    static final class Support {
        private static final int SIDE = 8;
        final List<List<BoardSurface.Face>> cells = new ArrayList<>(SIDE * SIDE);
        final float minX, minY, scaleX, scaleY;

        Support(List<BoardSurface.Face> faces) {
            float left = Float.POSITIVE_INFINITY, bottom = left, right = Float.NEGATIVE_INFINITY, top = right;
            for (var face : faces) {
                left = Math.min(left, Math.min(face.a().x, Math.min(face.b().x, face.c().x)));
                bottom = Math.min(bottom, Math.min(face.a().y, Math.min(face.b().y, face.c().y)));
                right = Math.max(right, Math.max(face.a().x, Math.max(face.b().x, face.c().x)));
                top = Math.max(top, Math.max(face.a().y, Math.max(face.b().y, face.c().y)));
            }
            minX = left; minY = bottom;
            scaleX = SIDE / Math.max(right - left, .001f); scaleY = SIDE / Math.max(top - bottom, .001f);
            for (int i = 0; i < SIDE * SIDE; i++) { cells.add(new ArrayList<>()); }
            float tolerance = .001f * BoardGeometry.hexScale();
            for (var face : faces) {
                int x0 = column(Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - tolerance);
                int x1 = column(Math.max(face.a().x, Math.max(face.b().x, face.c().x)) + tolerance);
                int y0 = row(Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - tolerance);
                int y1 = row(Math.max(face.a().y, Math.max(face.b().y, face.c().y)) + tolerance);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) { cells.get(y * SIDE + x).add(face); }
                }
            }
        }

        private int column(float x) { return Math.clamp((int) ((x - minX) * scaleX), 0, SIDE - 1); }
        private int row(float y) { return Math.clamp((int) ((y - minY) * scaleY), 0, SIDE - 1); }
        float height(float x, float y) {
            return BoardSurface.sampleHeight(cells.get(row(y) * SIDE + column(x)), x, y, Float.NaN);
        }

        /** The highest indexed face holding (x, y), or null where none does. */
        BoardSurface.Face face(float x, float y) {
            BoardSurface.Face highest = null;
            float top = Float.NEGATIVE_INFINITY;
            for (var face : cells.get(row(y) * SIDE + column(x))) {
                float height = face.height(x, y);
                if (height > top) { top = height; highest = face; }
            }
            return highest;
        }
    }

    private static List<BoardSurface.Face> support(BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
        return tile.liquid().present() ? surface.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP
              || face.finish() == BoardSurface.Finish.SHORE || face.finish() == BoardSurface.Finish.BED).toList() : surface.top();
    }

    private static final class Batch implements Disposable {
        final boolean crop;
        final int lod;
        final int stride;
        // Roots are replaced or extended, never edited in place: identity and size identify unchanged contents.
        // A terrain chunk prepared again can replant identical crops; equal contents need no upload either.
        final List<FloatArray> current = new ArrayList<>(), spans = new ArrayList<>();
        final List<FloatArray> previous = new ArrayList<>(), previousSpans = new ArrayList<>();
        final IntArray sizes = new IntArray(), previousSizes = new IntArray();
        final FloatArray data = new FloatArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(boolean crop, int lod) { this.crop = crop; this.lod = lod; stride = crop ? 8 : STRIDE; }
        void begin() { current.clear(); spans.clear(); sizes.clear(); }
        void add(FloatArray roots, FloatArray rowSpans) {
            if (roots.size > 0) { current.add(roots); spans.add(rowSpans); sizes.add(roots.size); }
        }

        /** Each root still cross-fades on the GPU; a chunk's projected range only selects contributing templates. */
        boolean contributes(float nearPixels, float farPixels) {
            if (crop) { return lod == 0 ? nearPixels > START_PIXELS[1] : farPixels < FULL_PIXELS[1]; }
            return nearPixels > START_PIXELS[lod] && (lod == 0 || farPixels < FULL_PIXELS[lod - 1]);
        }

        private boolean unchanged() {
            if (current.size() != previous.size() || !sizes.equals(previousSizes)) { return false; }
            boolean same = true;
            for (int i = 0; i < current.size() && same; i++) {
                if (current.get(i) != previous.get(i)) {
                    same = current.get(i).equals(previous.get(i)) && (!crop || spans.get(i).equals(previousSpans.get(i)));
                }
            }
            if (same) { remember(); }
            return same;
        }

        private void remember() {
            previous.clear(); previous.addAll(current);
            previousSpans.clear(); previousSpans.addAll(spans);
            previousSizes.clear(); previousSizes.addAll(sizes);
        }

        ModelInstance upload(Texture texture) {
            if (current.isEmpty()) { previous.clear(); previousSpans.clear(); previousSizes.clear(); return null; }
            if (unchanged()) { return data.size == 0 ? null : instance; }
            data.clear();
            for (int p = 0; p < current.size(); p++) {
                FloatArray roots = current.get(p);
                for (int i = 0; i < roots.size; i += STRIDE) {
                    if (crop || roots.items[i + 3] < DENSITY[lod]) {
                        data.addAll(roots.items, i, STRIDE);
                        if (crop) { data.addAll(spans.get(p).items, i, STRIDE); }
                    }
                }
            }
            remember();
            if (data.size == 0) { return null; }
            if (mesh == null) {
                Mesh template = template(crop, lod);
                try { mesh = new GpuInstancedMesh(template); } finally { template.dispose(); }
                var builder = new ModelBuilder();
                builder.begin();
                var material = new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE),
                      new Kind(crop), new FloatAttribute(Kind.LOD, lod));
                if (texture != null) { material.set(TextureAttribute.createDiffuse(texture)); }
                builder.part(crop ? "crop" : "reeds", mesh, GL20.GL_TRIANGLES, material);
                builder.manage(mesh);
                instance = new ModelInstance(builder.end());
            }
            int count = data.size / stride;
            if (count > capacity) {
                if (capacity > 0) { mesh.disableInstancedRendering(); }
                capacity = Math.max(count, Math.max(256, capacity * 2));
                var root = new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverRoot");
                // libGDX indexes instance attributes by usage/unit, separately from the mesh's vertex attributes.
                mesh.enableInstancedRendering(false, capacity, crop ? new VertexAttribute[] { root,
                      new VertexAttribute(VertexAttributes.Usage.Position, 4, "a_coverRow") } : new VertexAttribute[] { root });
            }
            mesh.setInstanceData(data.items, 0, data.size);
            uploads++;
            return instance;
        }

        @Override
        public void dispose() {
            if (instance != null) { instance.model.dispose(); }
            mesh = null; instance = null; capacity = 0;
            begin(); previous.clear(); previousSpans.clear(); previousSizes.clear(); data.clear();
        }
    }

    /**
     * One terrain chunk's near plants: full crop rows and the two nearer reed tiers. Its buffers persist, so panning
     * selects chunks instead of rebuilding instance data. The coarsest tiers share board-wide batches instead.
     */
    private static final class Chunk implements Disposable {
        final Batch[] batches = { new Batch(true, 0), new Batch(false, 0), new Batch(false, 1) };
        final List<Crops> crops = new ArrayList<>();
        final List<FloatArray> reeds = new ArrayList<>();
        // Projected hex widths of the chunk's plant hexes in view. No hex in view leaves the chunk undrawn.
        float nearPixels, farPixels;
        long frame;
        // Plants place themselves from their roots. The batches' transforms only let the renderable sorter draw nearer
        // chunks first, so their rows can reject the rows behind them before shading.
        final Vector3 centre;

        Chunk(Coords chunk) {
            centre = BoardGeometry.center(new Coords(chunk.getX() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2,
                  chunk.getY() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2), 0);
        }

        void begin(long next) {
            frame = next;
            crops.clear(); reeds.clear();
            nearPixels = 0; farPixels = Float.POSITIVE_INFINITY;
        }

        @Override
        public void dispose() { for (Batch batch : batches) { batch.dispose(); } }
    }

    private final Map<Coords, Chunk> chunks = new HashMap<>();
    // The coarsest tier of each kind in one board-wide draw: few enough to leave the view and detail band to the shader.
    private final Batch distantCrops = new Batch(true, 2), distantReeds = new Batch(false, 2);
    private long frame, uploads;
    private List<BoardScene.Tile> tiles;
    // Derived once per immutable terrain snapshot, including marsh fringe hexes, in board order.
    private final Map<Coords, BoardScene.Biome> kinds = new LinkedHashMap<>();
    private int revision = -1, boardId = -1;
    // Near crop rows, the distant canopy and reeds each share one mipmapped cutout across their batches.
    private Texture crop, canopy, sedge;
    /** Render-owned submission snapshot; installed plants still invalidate a stationary view. */
    private record View(float[] projectionView, float viewportPixels, List<BoardScene.Tile> candidates,
          Map<Coords, BoardPlants> sources, List<ModelInstance> instances) { }
    private View view;

    static String vertex(String source) {
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        String plant = GpuShaderSource.read("terrain-biome-vegetation.glsl");
        for (int lod = 0; lod < DENSITY.length; lod++) {
            plant = plant.replace("@START" + lod + "@", Float.toString(START_PIXELS[lod]))
                  .replace("@FULL" + lod + "@", Float.toString(FULL_PIXELS[lod]));
        }
        plant = plant.replace("@ROW_X@", Float.toString(BoardBiome.ROW_X))
              .replace("@ROW_Y@", Float.toString(BoardBiome.ROW_Y))
              .replace("@ROW_METRES@", Float.toString(BoardBiome.ROW_METRES))
              .replace("@ROW_HALF_WIDTH@", Float.toString(ROW_HALF_WIDTH))
              .replace("@PLANTS_PER_ROW@", Float.toString(PLANTS_PER_ROW))
              .replace("@PLANT_SPACING@", Float.toString(PLANT_SPACING))
              .replace("@CROP_LEAN@", Float.toString(CROP_LEAN))
              .replace("@CROP_SCALE@", Float.toString(CROP_SCALE));
        // A plant outside this template's band or the view leaves every vertex of its triangles outside the clip volume.
        return source.replace("void main() {", wind + plant + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "if (!biomePlant(a_position, a_normal, a_color, coverPosition, coverNormal, coverColor)) {\n"
                    + "gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }\n")
              .replace("v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;",
                    "v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;\n"
                          + "if (u_biomeKind < 1.5 && a_coverRow.x > 0.0 && abs(a_normal.x) < .5) {\n"
                          + "v_diffuseUV.x = u_biomeLod > 1.5 ? cropU : cropU * .5;\n}")
              .replace("vec4 pos = u_worldTrans * vec4(a_position, 1.0);", "vec4 pos = vec4(coverPosition, 1.0);")
              .replace("vec3 normal = normalize(u_normalMatrix * a_normal);", "vec3 normal = coverNormal;")
              .replace("v_color = a_color;", "v_color = coverColor;");
    }

    /**
     * The candidate tiles' installed crops and reeds, as persistent per-chunk batches plus one board-wide batch per
     * kind for the coarsest tier. Plants appear and change together with their terrain chunk.
     */
    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardPlants> plants) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose(); revision = BoardGeometry.revision(); boardId = scene.boardId();
        }
        if (tiles != scene.tiles()) {
            tiles = scene.tiles(); view = null;
            kinds.clear();
            for (var tile : tiles) {
                var kind = BoardBiome.plantKind(scene, tile);
                if (kind == BoardScene.Biome.FIELD || kind == BoardScene.Biome.MARSH) { kinds.put(tile.coords(), kind); }
            }
        }
        float viewportPixels = Gdx.graphics == null ? camera.viewportHeight
              : camera.viewportHeight * Gdx.graphics.getBackBufferHeight() / Math.max(1f, Gdx.graphics.getHeight());
        if (view != null && view.viewportPixels() == viewportPixels
              && Arrays.equals(view.projectionView(), camera.combined.val) && view.candidates().equals(candidates)) {
            boolean current = true;
            for (var entry : view.sources().entrySet()) {
                if (plants.apply(entry.getKey()) != entry.getValue()) { current = false; break; }
            }
            if (current) { return view.instances(); }
        }
        frame++;
        // The installed plants each drawn hex used; a stationary view reuses them while unchanged.
        Map<Coords, BoardPlants> sources = new HashMap<>();
        boolean distantFields = false, distantMarsh = false;
        for (var tile : candidates) {
            var kind = kinds.get(tile.coords());
            if (kind == null) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .8f;
            float nearPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera,
                  new Vector3(center).mulAdd(camera.direction, -radius));
            if (nearPixels <= START_PIXELS[2]) { continue; }
            Chunk chunk = chunks.computeIfAbsent(new Coords(tile.coords().getX() / TerrainLod.CHUNK_SIZE,
                  tile.coords().getY() / TerrainLod.CHUNK_SIZE), Chunk::new);
            if (chunk.frame != frame) { chunk.begin(frame); }
            // Every candidate stays in its chunk's buffers, including hexes without installed plants: their chunk's
            // installation must refresh a stationary view. Hexes in view choose whether and how the chunk draws.
            BoardPlants planted = plants.apply(tile.coords());
            if (planted != null && planted.crops() != null) { chunk.crops.add(planted.crops()); }
            if (planted != null && planted.reeds() != null) { chunk.reeds.add(planted.reeds()); }
            if (!camera.frustum.sphereInFrustum(center, radius)) { continue; }
            sources.put(tile.coords(), planted);
            float farPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera,
                  new Vector3(center).mulAdd(camera.direction, radius));
            chunk.nearPixels = Math.max(chunk.nearPixels, nearPixels);
            chunk.farPixels = Math.min(chunk.farPixels, farPixels);
            if (kind == BoardScene.Biome.FIELD) { distantFields |= farPixels < FULL_PIXELS[1]; }
            else { distantMarsh |= farPixels < FULL_PIXELS[1]; }
        }
        List<ModelInstance> result = new ArrayList<>();
        for (var iterator = chunks.values().iterator(); iterator.hasNext(); ) {
            Chunk chunk = iterator.next();
            if (chunk.frame != frame) {
                chunk.dispose();
                iterator.remove();
                continue;
            }
            for (Batch batch : chunk.batches) {
                // An idle template keeps its uploaded roots for a return to its scale or into view.
                batch.begin();
                if (chunk.nearPixels == 0 || !batch.contributes(chunk.nearPixels, chunk.farPixels)) { continue; }
                if (batch.crop) { for (Crops planted : chunk.crops) { batch.add(planted.roots(), planted.spans()); } }
                else { for (FloatArray reeds : chunk.reeds) { batch.add(reeds, null); } }
                submit(batch, result, chunk.centre);
            }
        }
        // Chunk replacements, not panning, change these; board order keeps an unchanged membership recognizable.
        distantCrops.begin();
        distantReeds.begin();
        if (distantFields || distantMarsh) {
            for (var entry : kinds.entrySet()) {
                BoardPlants planted = plants.apply(entry.getKey());
                if (planted == null) { continue; }
                if (distantFields && planted.crops() != null) {
                    distantCrops.add(planted.crops().canopy(), planted.crops().canopySpans());
                }
                if (distantMarsh && planted.reeds() != null) { distantReeds.add(planted.reeds(), null); }
            }
            Vector3 centre = BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0);
            submit(distantCrops, result, centre);
            submit(distantReeds, result, centre);
        }
        view = new View(camera.combined.val.clone(), viewportPixels, List.copyOf(candidates), sources, result);
        return result;
    }

    private void submit(Batch batch, List<ModelInstance> result, Vector3 centre) {
        if (!batch.current.isEmpty() && (batch.crop ? crop == null : sedge == null)) {
            if (batch.crop) {
                var textures = cropTextures();
                crop = textures[0];
                canopy = textures[1];
            } else { sedge = plantTexture("marsh-sedge"); }
        }
        Texture texture = batch.crop ? batch == distantCrops ? canopy : crop : sedge;
        long before = batch.uploads;
        ModelInstance instance = batch.upload(texture);
        uploads += batch.uploads - before;
        if (instance != null) {
            instance.transform.setToTranslation(centre);
            result.add(instance);
        }
    }

    long uploads() { return uploads; }

    /**
     * Register seven variants around their authored stems/crowns. Side and front occupy the upper atlas, with the
     * overhead row below the side. All three views retain matching plant centres; cropping cannot shift them.
     */
    private static Texture[] cropTextures() {
        var registration = new JsonReader().parse(new FileHandle(new File(Configuration.dataDir(),
              "models/board/textures/foliage/crop-row.json")));
        var artwork = new ArrayList<Artwork>();
        var rows = new Pixmap(2 * ROW_PIXELS, 640, Pixmap.Format.RGBA8888);
        var canopy = new Pixmap(512, 256, Pixmap.Format.RGBA8888);
        try {
            for (String view : List.of("side", "front", "top")) {
                var source = Artwork.load("crop-row-" + view, registration.get(view));
                artwork.add(source);
                boolean top = view.equals("top");
                boolean front = view.equals("front");
                source.draw(rows, front ? ROW_PIXELS : 0, top ? 512 : 0, top ? 128 : 512, top,
                      front ? PLANT_FILL : ALONG_FILL);
            }
            // The heads fill roughly the top quarter of the stalks.
            canopy.setBlending(Pixmap.Blending.None);
            canopy.setFilter(Pixmap.Filter.BiLinear);
            canopy.drawPixmap(rows, 0, 10, ROW_PIXELS, 123, 0, 0, 512, 128);
            canopy.drawPixmap(rows, 0, 512, ROW_PIXELS, 128, 0, 128, 512, 128);
            fill(rows, 0, 512);
            fill(rows, 512, 640);
            fill(canopy, 0, 128);
            fill(canopy, 128, 256);
            var near = filteredTexture(rows);
            try { return new Texture[] { near, filteredTexture(canopy) }; }
            catch (RuntimeException error) { near.dispose(); throw error; }
        } finally {
            rows.dispose(); canopy.dispose();
            for (var source : artwork) { source.pixels().dispose(); }
        }
    }

    /** Pixel-space registration belongs to the artwork; world spacing belongs to the shared row template. */
    private record CropSprite(int left, int top, int right, int bottom, int x, int y) { }

    private record Artwork(Pixmap pixels, List<CropSprite> plants) {
        static Artwork load(String name, JsonValue anchors) {
            if (anchors == null || anchors.size != PLANTS_PER_ROW) {
                throw new IllegalArgumentException(name + " requires seven registered variants");
            }
            var source = new Pixmap(new FileHandle(new File(Configuration.dataDir(),
                  "models/board/textures/foliage/" + name + ".png")));
            try {
                var plants = new ArrayList<CropSprite>();
                for (int i = 0; i < PLANTS_PER_ROW; i++) {
                    int start = Math.round(i * source.getWidth() / (float) PLANTS_PER_ROW);
                    int end = Math.round((i + 1) * source.getWidth() / (float) PLANTS_PER_ROW);
                    int left = end, right = start, top = source.getHeight(), bottom = 0;
                    for (int y = 0; y < source.getHeight(); y++) {
                        for (int x = start; x < end; x++) {
                            if ((source.getPixel(x, y) & 255) < 82) { continue; }
                            left = Math.min(left, x); right = Math.max(right, x);
                            top = Math.min(top, y); bottom = Math.max(bottom, y);
                        }
                    }
                    int x = anchors.get(i).getInt(0), y = anchors.get(i).getInt(1);
                    if (left > right || top > bottom || x < start || x >= end || y < top || y > bottom) {
                        throw new IllegalArgumentException(name + " has invalid registration for variant " + i);
                    }
                    plants.add(new CropSprite(left, top, right, bottom, x, y));
                }
                return new Artwork(source, plants);
            } catch (RuntimeException error) {
                source.dispose();
                throw error;
            }
        }

        void draw(Pixmap target, int x, int y, int height, boolean overhead, float fill) {
            target.setBlending(Pixmap.Blending.None);
            target.setFilter(Pixmap.Filter.BiLinear);
            for (int i = 0; i < plants.size(); i++) {
                var plant = plants.get(i);
                float centre = x + (i + .5f) * ROW_PIXELS / PLANTS_PER_ROW;
                float sx = (ROW_PIXELS / (float) PLANTS_PER_ROW) * fill
                      / (2 * Math.max(plant.x - plant.left, plant.right - plant.x) + 1);
                float sy = overhead ? height * PLANT_FILL
                      / (2 * Math.max(plant.y - plant.top, plant.bottom - plant.y) + 1)
                      : (height - 1f) / (plant.y - plant.top);
                int left = Math.round(centre + (plant.left - plant.x) * sx);
                int top = overhead ? Math.round(y + height / 2f + (plant.top - plant.y) * sy) : y;
                int width = Math.max(1, Math.round((plant.right - plant.left + 1) * sx));
                int drawnHeight = overhead ? Math.max(1, Math.round((plant.bottom - plant.top + 1) * sy)) : height;
                target.drawPixmap(pixels, plant.left, plant.top, plant.right - plant.left + 1,
                      overhead ? plant.bottom - plant.top + 1 : plant.y - plant.top + 1,
                      left, top, width, drawnHeight);
            }
        }
    }

    /**
     * Give empty texels a region's average plant colour. The cutouts' empty texels are nearly black, and mipmaps average
     * colour and coverage separately: without this the distant canopy would turn dark olive and lose its golden heads.
     * Near rows also need clean empty texels now that the views have registered transparent gutters.
     */
    private static void fill(Pixmap pixels, int top, int bottom) {
        long red = 0, green = 0, blue = 0, count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                int pixel = pixels.getPixel(x, y);
                if ((pixel & 255) < 128) { continue; }
                red += pixel >>> 24; green += pixel >>> 16 & 255; blue += pixel >>> 8 & 255; count++;
            }
        }
        if (count == 0) { return; }
        int colour = (int) (red / count) << 24 | (int) (green / count) << 16 | (int) (blue / count) << 8;
        for (int y = top; y < bottom; y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                int alpha = pixels.getPixel(x, y) & 255;
                if (alpha < 32) { pixels.drawPixel(x, y, colour | alpha); }
            }
        }
    }

    private static Texture filteredTexture(Pixmap pixels) {
        var texture = new Texture(pixels, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return texture;
    }

    /** One shared 512px reed cutout; keep the full-resolution source as the editable asset. */
    private static Texture plantTexture(String name) {
        var source = new Pixmap(new FileHandle(new File(Configuration.dataDir(),
              "models/board/textures/foliage/" + name + ".png")));
        try {
            var pixels = new Pixmap(512, 512, Pixmap.Format.RGBA8888);
            try {
                pixels.setBlending(Pixmap.Blending.None);
                pixels.setFilter(Pixmap.Filter.BiLinear);
                pixels.drawPixmap(source, 0, 0, source.getWidth(), source.getHeight(), 0, 0, 512, 512);
                return filteredTexture(pixels);
            } finally { pixels.dispose(); }
        } finally { source.dispose(); }
    }

    /** Three registered, tightly bounded planes per plant; the distant tier keeps one repeated canopy per run. */
    private static Mesh template(boolean crop, int lod) {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
              | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
        if (crop) {
            if (lod < 2) {
                float half = ALONG_FILL / (2 * PLANTS_PER_ROW), width = ROW_HALF_WIDTH * PLANT_FILL;
                for (int i = 0; i < PLANTS_PER_ROW; i++) {
                    float centre = (i + .5f) / PLANTS_PER_ROW, left = centre - half, right = centre + half;
                    Vector3 normal = new Vector3(0, -1, 0);
                    mesh.rect(vertex(new Vector3(left - .5f, 0, 0), normal, Color.WHITE).setUV(left, 511.5f / 640),
                          vertex(new Vector3(right - .5f, 0, 0), normal, Color.WHITE).setUV(right, 511.5f / 640),
                          vertex(new Vector3(right - .5f, 0, 1.04f), normal, Color.WHITE).setUV(right, .5f / 640),
                          vertex(new Vector3(left - .5f, 0, 1.04f), normal, Color.WHITE).setUV(left, .5f / 640));
                    normal.set(1, 0, 0);
                    float frontLeft = .5f + (centre - PLANT_FILL / (2 * PLANTS_PER_ROW)) / 2;
                    float frontRight = .5f + (centre + PLANT_FILL / (2 * PLANTS_PER_ROW)) / 2;
                    mesh.rect(vertex(new Vector3(centre - .5f, -width, 0), normal, Color.WHITE).setUV(frontLeft, 511.5f / 640),
                          vertex(new Vector3(centre - .5f, width, 0), normal, Color.WHITE).setUV(frontRight, 511.5f / 640),
                          vertex(new Vector3(centre - .5f, width, 1.04f), normal, Color.WHITE).setUV(frontRight, .5f / 640),
                          vertex(new Vector3(centre - .5f, -width, 1.04f), normal, Color.WHITE).setUV(frontLeft, .5f / 640));
                    normal.set(0, 0, 1);
                    float top = (512 + 128 * (1 - PLANT_FILL) / 2) / 640;
                    float bottom = (512 + 128 * (1 + PLANT_FILL) / 2) / 640;
                    mesh.rect(vertex(new Vector3(left - .5f, -width, CANOPY_HEIGHT), normal, Color.WHITE).setUV(left, top),
                          vertex(new Vector3(right - .5f, -width, CANOPY_HEIGHT), normal, Color.WHITE).setUV(right, top),
                          vertex(new Vector3(right - .5f, width, CANOPY_HEIGHT), normal, Color.WHITE).setUV(right, bottom),
                          vertex(new Vector3(left - .5f, width, CANOPY_HEIGHT), normal, Color.WHITE).setUV(left, bottom));
                }
            } else {
                Vector3 normal = new Vector3(0, 0, 1);
                mesh.rect(vertex(new Vector3(-.5f, -ROW_HALF_WIDTH, CANOPY_HEIGHT), normal, Color.WHITE).setUV(0, 512.5f / 640),
                      vertex(new Vector3(.5f, -ROW_HALF_WIDTH, CANOPY_HEIGHT), normal, Color.WHITE).setUV(1, 512.5f / 640),
                      vertex(new Vector3(.5f, ROW_HALF_WIDTH, CANOPY_HEIGHT), normal, Color.WHITE).setUV(1, 639.5f / 640),
                      vertex(new Vector3(-.5f, ROW_HALF_WIDTH, CANOPY_HEIGHT), normal, Color.WHITE).setUV(0, 639.5f / 640));
            }
            return mesh.end();
        }
        int cards = lod == 2 ? 1 : 2;
        int segments = lod == 0 ? 2 : 1;
        float height = 1.1f, width = .58f;
        for (int card = 0; card < cards; card++) {
            float angle = card * (float) Math.PI / cards;
            Vector3 side = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
            Vector3 outward = new Vector3(-side.y, side.x, 0);
            for (int row = 0; row < segments; row++) {
                float low = row / (float) segments, high = (row + 1) / (float) segments;
                Vector3 a = new Vector3(outward).scl((float) Math.sin(low * Math.PI) * .28f).add(0, 0, low * height);
                Vector3 b = new Vector3(outward).scl((float) Math.sin(high * Math.PI) * .28f).add(0, 0, high * height);
                Vector3 normal = new Vector3(side).crs(new Vector3(b).sub(a)).nor();
                float bottom = .98f - low * .98f, top = .98f - high * .98f;
                mesh.rect(vertex(new Vector3(a).mulAdd(side, -width), normal, Color.WHITE).setUV(0, bottom),
                      vertex(new Vector3(a).mulAdd(side, width), normal, Color.WHITE).setUV(1, bottom),
                      vertex(new Vector3(b).mulAdd(side, width), normal, Color.WHITE).setUV(1, top),
                      vertex(new Vector3(b).mulAdd(side, -width), normal, Color.WHITE).setUV(0, top));
            }
        }
        return mesh.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(color);
    }

    @Override
    public void dispose() {
        for (Chunk chunk : chunks.values()) { chunk.dispose(); }
        chunks.clear();
        distantCrops.dispose();
        distantReeds.dispose();
        if (crop != null) { crop.dispose(); canopy.dispose(); crop = null; canopy = null; }
        if (sedge != null) { sedge.dispose(); sedge = null; }
        kinds.clear(); tiles = null; view = null;
    }
}
