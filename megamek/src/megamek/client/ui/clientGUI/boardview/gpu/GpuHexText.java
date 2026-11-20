/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.common.board.Coords;

/**
 * Captured labels, each on its outline unless that setting is off: a roof label (HEIGHT, or with Hex Types the structure line) shows through its
 * own hex; top view also reveals the enabled ground labels. The Tactical View adds its own: water depths and
 * structures' construction factors ({@link BoardHexText#tactical}). A label whose text is too small on screen to
 * read is neither laid out nor drawn ({@link #readable}); the others are laid out as the view shows their terrain
 * chunk ({@link #build}).
 */
final class GpuHexText implements Disposable {
    /**
     * Bounded 2 MiB instance buffers, so that a mesh's upload is one step of a frame's budget ({@link Merge#step});
     * all glyphs share the same four vertices and six indices.
     */
    private static final int PAGE_GLYPHS = 32_768;
    private static final int GLYPH_FLOATS = 16;
    /** Eight borrowed font pages, plus depth, unit depth and contrast, fit the GL 3.3 minimum of sixteen units. */
    private static final int TEXTURES = 8;
    /**
     * The smallest text a player reads: a label's font size on screen, in pixels (the legacy board's smallest font,
     * about 5 px of capital height). Smaller, a label is neither laid out nor drawn ({@link #readable}), at every
     * preset.
     */
    static final float MIN_TEXT_PIXELS = 7;
    /** The time a frame of a live view spends laying out the labels of chunks that came into view ({@link #build}). */
    private static final long BUILD_NANOS = 2_000_000;

    /**
     * Whether text of this font size (world units) can be read at this many screen pixels a world unit: the one rule
     * of what is laid out and of what is drawn.
     */
    static boolean readable(float size, float pixelsPerUnit) { return size * pixelsPerUnit >= MIN_TEXT_PIXELS; }

    /** {@code size}: its labels' font size in world units, to a quarter: what {@link #readable} is asked. */
    private record Plane(int elevation, boolean overlay, float size) { }
    /** One label of a hex, laid out: its plane, and its outline's and its text's glyph instances by font page. */
    private record Laid(Plane plane, List<FloatArray> outlines, List<FloatArray> pages) {
        /** Adds a label's glyphs by page to a chunk's. */
        static void append(List<FloatArray> chunk, List<FloatArray> label) {
            for (int page = 0; page < label.size(); page++) {
                if (chunk.size() == page) { chunk.add(new FloatArray()); }
                chunk.get(page).addAll(label.get(page));
            }
        }
    }
    /** A chunk's glyph instances, one array per page of the outline font and of the text font, in drawing order. */
    private record Chunk(List<FloatArray> outlines, List<FloatArray> pages, BoundingBox bounds) {
        static void add(List<FloatArray> pages, BitmapFontCache glyphs, float contrast, Coords tile, float headroom, Plane plane) {
            for (int page = 0; page < glyphs.getPageCount(); page++) {
                if (pages.size() == page) { pages.add(new FloatArray()); }
                float[] vertices = glyphs.getVertices(page);
                FloatArray instances = pages.get(page);
                for (int at = 0; at < glyphs.getVertexCount(page); at += 20) {
                    instances.add(vertices[at], vertices[at + 1], vertices[at + 10], vertices[at + 11]);
                    instances.add(vertices[at + 2]);
                    instances.add(vertices[at + 3], vertices[at + 4], vertices[at + 13], vertices[at + 14]);
                    instances.add(contrast);
                    instances.add(tile.getX(), tile.getY(), headroom);
                    instances.add(plane.elevation() * BoardGeometry.level(), plane.overlay() ? 1 : 0, 0);
                }
            }
        }
    }
    private record Range(BoundingBox bounds, float size, int offset, int count, Coords cell) { }
    /** Owns the quad/instance buffer, borrowing the fonts' live textures, including their incremental glyph updates. */
    private static final class Page {
        private final List<Texture> textures;
        private final Mesh mesh;
        private final float[] instances;
        private final List<Range> ranges;
        private final BitSet visible = new BitSet();
        private final FloatArray compacted = new FloatArray();
        private boolean uploaded;

        /** Draws from {@code spare}, a retired mesh, or from a new one that holds {@code capacity} glyphs. */
        Page(List<Texture> textures, float[] instances, List<Range> ranges, Mesh spare, int capacity) {
            this.textures = List.copyOf(textures);
            this.instances = instances;
            this.ranges = List.copyOf(ranges);
            if (spare != null) {
                mesh = spare;
                return;
            }
            mesh = new GpuInstancedMesh(4, 6,
                  new VertexAttributes(new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position")));
            try {
                mesh.setVertices(new float[] { 0, 0, 0, 1, 1, 1, 1, 0 });
                mesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
                mesh.enableInstancedRendering(false, capacity,
                      new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_bounds"),
                      new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, "a_color"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_uv"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_contrast"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_hex"),
                      new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_surface"));
            } catch (RuntimeException | Error failure) {
                mesh.dispose();
                throw failure;
            }
        }

        /** Uploads every range now: its first draw then uploads nothing. */
        void prepare(ShaderProgram shader) {
            visible.set(0, ranges.size());
            compacted.clear();
            compacted.addAll(instances);
            mesh.setInstanceData(compacted.items, 0, compacted.size);
            mesh.bind(shader);
            mesh.unbind(shader);
            uploaded = true;
        }

        /** Draws its ranges that can be read and are in view, but those of the chunks in {@code dead}. */
        void render(Camera camera, ShaderProgram shader, float pixelsPerUnit, Set<Coords> dead) {
            boolean changed = !uploaded;
            for (int index = 0; index < ranges.size(); index++) {
                boolean shown = !dead.contains(ranges.get(index).cell())
                      && readable(ranges.get(index).size(), pixelsPerUnit)
                      && camera.frustum.boundsInFrustum(ranges.get(index).bounds());
                if (visible.get(index) != shown) { visible.set(index, shown); changed = true; }
            }
            if (changed) {
                // Keep source order, compacting only when a chunk enters/leaves the view. Stationary frames upload nothing.
                compacted.clear();
                for (int index = visible.nextSetBit(0); index >= 0; index = visible.nextSetBit(index + 1)) {
                    Range range = ranges.get(index);
                    compacted.addAll(instances, range.offset(), range.count());
                }
                if (compacted.size > 0) { mesh.setInstanceData(compacted.items, 0, compacted.size); }
                uploaded = true;
            }
            if (compacted.size == 0) { return; }
            for (int index = 0; index < textures.size(); index++) { textures.get(index).bind(index); }
            for (int index = 0; index < TEXTURES; index++) {
                shader.setUniformi(index == 0 ? "u_texture" : "u_fonts[" + (index - 1) + "]",
                      index < textures.size() ? index : 0);
            }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            mesh.render(shader, GL20.GL_TRIANGLES);
        }
    }
    /**
     * The merged meshes, in the order drawn (a plane's outlines, its text, then the next plane), of the chunks in
     * {@link #mergedCells}: those among them let go or laid out again since ({@link #dead}) no longer draw from here.
     */
    private final List<Page> pages = new ArrayList<>();
    private final Set<Coords> mergedCells = new HashSet<>(), dead = new HashSet<>();
    /** The meshes of each chunk laid out since the last merge, drawn after {@link #pages}. */
    private final Map<Coords, List<Page>> fresh = new HashMap<>();
    /** The merge under way ({@link #merge}), null without one. */
    private Merge merging;
    /** Meshes no longer drawn, freed a few a frame within its budget ({@link #build}). */
    private final List<Page> trash = new ArrayList<>();
    /** The buffers of merged meshes no longer drawn: the next merge fills them instead of making new ones. */
    private final java.util.Deque<Mesh> spares = new java.util.ArrayDeque<>();
    /** The chunk whose layout a frame's budget cut short ({@link #lay}): its next tile, and its sizes so far. */
    private Coords laying;
    private int layingNext;
    private float layingSkipped, layingLaid;
    private ShaderProgram shader;
    private final Matrix4 transform = new Matrix4();
    private final IntBuffer viewport = BufferUtils.newIntBuffer(4);
    private List<BoardScene.Tile> tiles;
    /** The tiles the played events lead to, as last given, and the tile each hex's labels were laid out from. */
    private List<BoardScene.Tile> coming;
    private List<BoardScene.Tile> shown = List.of();
    private BitmapFont font;
    private BitmapFont outline;
    private int tuning = -1;
    private boolean tactical;
    private boolean types;
    private boolean outlined;
    /** In the Tactical View, the hexes units stand on, whose own labels give way to them. */
    private Set<Coords> covered = Set.of();
    /** The roof each roof label was laid on at the last rebuild, by hex; null where the hex had none. */
    private final Map<Coords, BoundingBox> roofs = new HashMap<>();
    /** The count of fall changes at the last update, and the hexes whose roof labels are left out (#update). */
    private int falls;
    private final Set<Coords> down = new HashSet<>();
    /** A plane's chunks draw by column, then row, whatever order the view laid them out in. */
    private static final Comparator<Coords> CELLS = Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY);
    /** The glyphs laid out, by plane and chunk: only the chunks whose labels changed are gathered again. */
    private final Map<Plane, Map<Coords, Chunk>> built = new TreeMap<>(Comparator.comparing(Plane::overlay)
          .thenComparingInt(Plane::elevation).thenComparing(Plane::size));
    /** Each hex's labels as laid out, in their order: in a chunk gathered again, only changed hexes are laid out. */
    private final Map<Coords, List<Laid>> laid = new HashMap<>();
    /** Each terrain chunk's source tiles and the box its labels lie in, for {@link #shown}. */
    private final Map<Coords, List<BoardScene.Tile>> cellTiles = new HashMap<>();
    private final Map<Coords, BoundingBox> cellBounds = new HashMap<>();
    /** A laid-out chunk's largest font size left out as unreadable and largest laid out (0: none). */
    private record Extent(float skipped, float laid) { }
    /** The chunks whose labels are laid out: only chunks the view shows ({@link #build}). */
    private final Map<Coords, Extent> laidCells = new HashMap<>();
    /** The last update's borrowed lookups and the last draw's screen pixels a world unit, which {@link #lay} reads. */
    private Function<Coords, BoundingBox> roofBounds;
    private Predicate<Coords> fallen;
    private float pixelsPerUnit;

    GpuHexText() {
        shader = GpuShaderManager.program(GpuHexText::program, next -> shader = next);
    }

    private static ShaderProgram program() {
        String vertex = GpuShaderSource.read("hex-text.vert");
        String fragment = GpuShaderSource.read("hex-text.frag");
        return GpuGlsl.compile("GPU hex text", vertex, fragment);
    }

    void update(BoardScene scene, BitmapFont font, BitmapFont outline, Function<Coords, BoundingBox> roofBounds) {
        update(scene, font, outline, roofBounds, false, false, true);
    }

    /**
     * The fonts and roof bounds are borrowed; {@code outline} is {@code font} with a border of
     * {@link GpuBoardSkin#OUTLINE} texels ({@link GpuBoardSkin#OUTLINE_FONT}), drawn under every label when
     * {@code outlined} (Client Settings > Hex label outline). Labels are the existing board view's captured text and,
     * in the Tactical View ({@code tactical}), its labels of the captured water depths and structures; with View > Hex
     * Types on ({@code types}) the captured type block tells the depths instead.
     */
    void update(BoardScene scene, BitmapFont font, BitmapFont outline, Function<Coords, BoundingBox> roofBounds,
          boolean tactical, boolean types, boolean outlined) {
        update(scene, font, outline, roofBounds, tactical, types, outlined, at -> false, 0, scene.tiles());
    }

    /**
     * {@code fallen}: whether a hex's structure lies as pieces (GpuTerrain#fallShown), {@code falls} counting the
     * changes of that. Such a hex takes its labels from {@code coming}, the tiles the played events lead to (the
     * scene's own, listed alike, once they have played): its labels change once, as it gives way, to what they will be,
     * not again at each scene the playback shows on the way (user, 2026-11-10). While it still has a roof label there,
     * that label is left out until its collapse settles.
     */
    void update(BoardScene scene, BitmapFont font, BitmapFont outline, Function<Coords, BoundingBox> roofBounds,
          boolean tactical, boolean types, boolean outlined, Predicate<Coords> fallen, int falls,
          List<BoardScene.Tile> coming) {
        boolean fell = this.falls != falls;
        this.falls = falls;
        Set<Coords> units = new HashSet<>();
        if (tactical) { scene.units().forEach(unit -> units.addAll(unit.footprint())); }
        boolean same = this.font == font && this.outline == outline && tuning == BoardGeometry.revision()
              && this.tactical == tactical && this.types == types && this.outlined == outlined;
        boolean moved = tiles != scene.tiles() || this.coming != coming;
        this.roofBounds = roofBounds;
        this.fallen = fallen;
        if (same && !fell && !moved && covered.equals(units)) { return; }
        List<BoardScene.Tile> source = sources(scene.tiles(), coming, fallen);
        // The hexes whose labels this update changes; null for all of them. A strike's collapses and the scenes they
        // leave change a few dozen of a board's thousands of labels.
        Set<Coords> hexes = same && shown.size() == source.size() ? staleHexes(source, roofBounds, fallen, units, moved)
              : null;
        tiles = scene.tiles();
        this.coming = coming;
        if (laying != null) { forget(Set.of(laying)); }
        if (shown != source || hexes == null) { index(source); }
        shown = source;
        covered = units;
        this.font = font;
        this.outline = outline;
        tuning = BoardGeometry.revision();
        this.tactical = tactical;
        this.types = types;
        this.outlined = outlined;
        if (hexes == null) {
            // Other fonts, sizes or kinds of label: nothing laid out stays; the view lays out what it shows (#build).
            forget(new HashSet<>(laidCells.keySet()));
            clear();
            return;
        }
        // Changed labels in chunks that are laid out are laid out again at once; other chunks' wait for the view.
        Set<Coords> stale = new HashSet<>();
        for (Coords hex : hexes) {
            if (laidCells.containsKey(cell(hex))) { stale.add(cell(hex)); }
        }
        for (Coords cell : stale) { lay(cell, hexes, Long.MAX_VALUE); }
    }

    /** Each terrain chunk's tiles among {@code source}, and the box their labels lie in. */
    private void index(List<BoardScene.Tile> source) {
        cellTiles.clear();
        cellBounds.clear();
        for (BoardScene.Tile tile : source) {
            Coords cell = cell(tile.coords());
            cellTiles.computeIfAbsent(cell, key -> new ArrayList<>()).add(tile);
            float x = BoardGeometry.centerX(tile.coords()), y = BoardGeometry.centerY(tile.coords());
            float z = tile.elevation() * BoardGeometry.level();
            cellBounds.computeIfAbsent(cell, key -> new BoundingBox().inf())
                  .ext(x - BoardGeometry.width(), y - BoardGeometry.width(), z - 1)
                  .ext(x + BoardGeometry.width(), y + BoardGeometry.width(), z + BoardRelief.headroom(tile) + 1);
        }
    }

    /** Lets go of these chunks' labels: their glyphs, and what was kept of their hexes. */
    private void forget(Set<Coords> cells) {
        if (cells.isEmpty()) { return; }
        built.values().forEach(chunks -> chunks.keySet().removeAll(cells));
        built.values().removeIf(Map::isEmpty);
        laid.keySet().removeIf(hex -> cells.contains(cell(hex)));
        roofs.keySet().removeIf(hex -> cells.contains(cell(hex)));
        down.removeIf(hex -> cells.contains(cell(hex)));
        laidCells.keySet().removeAll(cells);
        cells.forEach(this::retire);
        if (laying != null && cells.contains(laying)) { laying = null; }
    }

    /** A chunk's meshes of its own go, its glyphs in the merged meshes no longer draw, and a merge under way is dropped. */
    private void retire(Coords cell) {
        List<Page> own = fresh.remove(cell);
        if (own != null) { trash.addAll(own); }
        if (mergedCells.contains(cell)) { dead.add(cell); }
        if (merging != null) {
            merging.pages.forEach(page -> spares.add(page.mesh));
            merging = null;
        }
        // Merged meshes none of whose chunks still draws go too.
        if (!pages.isEmpty() && dead.containsAll(mergedCells)) {
            pages.forEach(page -> spares.add(page.mesh));
            pages.clear();
            mergedCells.clear();
            dead.clear();
        }
    }

    /**
     * Lays out a chunk's labels that can be read at the last draw's scale ({@link #readable}), tile by tile until
     * {@code deadline} (System.nanoTime), and then makes its meshes ({@link #freshen}); false where the deadline cut
     * it short: the next call goes on from there ({@link #laying}), what the chunk drew before still drawn. With
     * {@code hexes} only those hexes anew, the chunk's others as they were laid out. The chunk notes the largest size
     * it left out.
     */
    private boolean lay(Coords cell, Set<Coords> hexes, long deadline) {
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        float outlineX = outline.getData().scaleX, outlineY = outline.getData().scaleY;
        Color color = new Color(font.getColor()), ringColor = new Color();
        // One label at a time: a font's cache grows its arrays to the exact size at every text it adds, so a chunk's
        // labels in one cache would copy them over and over (quadratic in the chunk's glyphs).
        BitmapFontCache glyphs = new BitmapFontCache(font, false), rings = new BitmapFontCache(outline, false);
        List<BoardScene.Tile> tiles = cellTiles.getOrDefault(cell, List.of());
        int from = 0;
        float skipped = 0, largest = 0;
        if (cell.equals(laying) && hexes == null) {
            from = layingNext;
            skipped = layingSkipped;
            largest = layingLaid;
        } else {
            built.values().forEach(chunks -> chunks.remove(cell));
            Extent before = hexes == null ? null : laidCells.get(cell);
            if (before != null) {
                skipped = before.skipped();
                largest = before.laid();
            }
        }
        if (cell.equals(laying)) { laying = null; }
        try {
            for (int index = from; index < tiles.size(); index++) {
                if (index > from && System.nanoTime() >= deadline) {
                    laying = cell;
                    layingNext = index;
                    layingSkipped = skipped;
                    layingLaid = largest;
                    return false;
                }
                BoardScene.Tile tile = tiles.get(index);
                Coords at = tile.coords();
                float centerX = BoardGeometry.centerX(at), centerY = BoardGeometry.centerY(at);
                // Unchanged in a chunk gathered again: its glyphs as they were laid out, and its roof.
                List<Laid> kept = hexes != null && !hexes.contains(at) ? laid.get(at) : null;
                List<Laid> parts = kept != null ? kept : new ArrayList<>();
                if (kept == null) {
                    roofs.remove(at);
                    down.remove(at);
                }
                laid.put(at, parts);
                for (BoardHexText label : kept != null ? List.<BoardHexText>of()
                      : labels(tile, tactical, types, covered.contains(at))) {
                    BoundingBox roof = label.elevation() > 0 ? roofBounds.apply(tile.coords()) : null;
                    if (label.elevation() > 0) {
                        roofs.put(tile.coords(), roof == null ? null : new BoundingBox(roof));
                        if (fallen.test(tile.coords())) {
                            down.add(tile.coords());
                            continue;
                        }
                    }
                    // A label too small to read is not laid out; a roof's fit only makes it smaller.
                    if (!readable(label.font().getSize2D(), pixelsPerUnit)) {
                        skipped = Math.max(skipped, label.font().getSize2D());
                        continue;
                    }
                    float headroom = headroom(tile, label);
                    font.getData().setScale(label.font().getSize2D() / GpuBoardSkin.FONT_RESOLUTION);
                    int argb = label.argb();
                    // Space always uses white ink and its dark outline; other neutral ink adapts without
                    // rebuilding, and deliberately colored labels retain their color.
                    float contrast = tile.backdrop() == BoardBackdrop.SPACE ? 2
                          : (argb >>> 16 & 255) == (argb >>> 8 & 255) && (argb >>> 8 & 255) == (argb & 255) ? 1 : 0;
                    font.setColor(((argb >>> 16) & 255) / 255f, ((argb >>> 8) & 255) / 255f,
                          (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
                    GlyphLayout layout = new GlyphLayout(font, label.text());
                    float x = centerX;
                    float baseline = centerY + BoardGeometry.height() / 2
                          - label.baseline() * BoardGeometry.hexScale();
                    if (roof != null) {
                        float fit = Math.min(1, Math.max(8, roof.getWidth() - 4 * BoardGeometry.hexScale())
                              / layout.width);
                        font.getData().setScale(font.getData().scaleX * fit);
                        layout.setText(font, label.text());
                        x = roof.getCenterX();
                        baseline = roof.getCenterY() - layout.height / 2;
                    }
                    float size = Math.round(font.getData().scaleX * GpuBoardSkin.FONT_RESOLUTION * 4) / 4f;
                    if (!readable(size, pixelsPerUnit)) {
                        skipped = Math.max(skipped, size);
                        continue;
                    }
                    largest = Math.max(largest, size);
                    Plane plane = new Plane(tile.elevation() + label.elevation(), label.elevation() > 0, size);
                    Laid part = new Laid(plane, new ArrayList<>(), new ArrayList<>());
                    parts.add(part);
                    float left = x - layout.width / 2, top = label.fromTop() ? baseline : baseline + layout.height;
                    if (outlined) {
                        // The outline: the bordered glyphs in the outline's colour. A line starts at its first
                        // glyph's border, so they go OUTLINE texels left to put each glyph's ink on the text's.
                        outline.getData().setScale(font.getData().scaleX, font.getData().scaleY);
                        Color.argb8888ToColor(ringColor, BoardHexText.outline(argb));
                        rings.clear();
                        rings.addText(new GlyphLayout(outline, label.text(), ringColor, 0, Align.left, false),
                              left - GpuBoardSkin.OUTLINE * outline.getData().scaleX, top);
                        Chunk.add(part.outlines(), rings, -contrast, tile.coords(), headroom, plane);
                    }
                    glyphs.clear();
                    glyphs.addText(layout, left, top);
                    Chunk.add(part.pages(), glyphs, contrast, tile.coords(), headroom, plane);
                }
                for (Laid part : parts) {
                    Chunk chunk = built.computeIfAbsent(part.plane(), key -> new TreeMap<>(CELLS))
                          .computeIfAbsent(cell, key -> new Chunk(new ArrayList<>(), new ArrayList<>(),
                                new BoundingBox().inf()));
                    float z = part.plane().elevation() * BoardGeometry.level();
                    chunk.bounds().ext(centerX - BoardGeometry.width(), centerY - BoardGeometry.width(), z - 1)
                          .ext(centerX + BoardGeometry.width(), centerY + BoardGeometry.width(), z + 1);
                    Laid.append(chunk.outlines(), part.outlines());
                    Laid.append(chunk.pages(), part.pages());
                }
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
            outline.getData().setScale(outlineX, outlineY);
            font.setColor(color);
        }
        built.values().removeIf(Map::isEmpty);
        laidCells.put(cell, new Extent(skipped, largest));
        freshen(cell);
        return true;
    }

    /**
     * The glyphs laid out, in the order drawn: a plane's outlines, its text, then the next plane; one chunk's, or with
     * null every chunk's.
     */
    private List<Glyphs> ordered(Coords only) {
        List<Glyphs> ordered = new ArrayList<>();
        for (var plane : built.entrySet()) {
            float size = plane.getKey().size();
            for (var chunk : plane.getValue().entrySet()) {
                if (only != null && !only.equals(chunk.getKey())) { continue; }
                ordered.add(new Glyphs(chunk.getValue().outlines(), chunk.getValue().bounds(), size, outline, chunk.getKey()));
            }
            for (var chunk : plane.getValue().entrySet()) {
                if (only != null && !only.equals(chunk.getKey())) { continue; }
                ordered.add(new Glyphs(chunk.getValue().pages(), chunk.getValue().bounds(), size, font, chunk.getKey()));
            }
        }
        return ordered;
    }

    /** A chunk just laid out draws from meshes of its own until the next merge takes it in ({@link #merge}). */
    private void freshen(Coords cell) {
        retire(cell);
        Merge own = new Merge(ordered(cell), null);
        own.step(Long.MAX_VALUE);
        if (!own.pages.isEmpty()) { fresh.put(cell, own.pages); }
    }

    /**
     * Once the view's layout is at rest: everything laid out is gathered into one merged set of meshes, chunk by
     * chunk within the frames' budget ({@code deadline}, System.nanoTime); when it is whole, the chunks' own meshes
     * and the merged ones before it give way to it, and those merged ones' buffers serve the next merge ({@link #spares}:
     * a fresh buffer's first upload can hold a frame for tens of milliseconds in the driver). Anything laid out or
     * let go meanwhile drops it ({@link #retire}).
     */
    private void merge(long deadline) {
        if (fresh.isEmpty() && dead.isEmpty()) { return; }
        if (merging == null) { merging = new Merge(ordered(null), spares); }
        if (!merging.step(deadline)) { return; }
        // Its meshes upload in the labels' draw, one a frame (a print's at once); it takes over when all are in.
        while (deadline == Long.MAX_VALUE && !merging.upload(shader)) { }
        if (!merging.ready) { return; }
        List<Page> merged = merging.pages;
        merging = null;
        pages.forEach(page -> spares.add(page.mesh));
        pages.clear();
        fresh.values().forEach(trash::addAll);
        fresh.clear();
        mergedCells.clear();
        dead.clear();
        pages.addAll(merged);
        built.values().forEach(chunks -> mergedCells.addAll(chunks.keySet()));
    }

    /**
     * The labels the view shows, and no others: a terrain chunk in view that is not laid out, or left out labels that
     * can be read now, is laid out, nearest the view's middle first, until the frame's {@code budget} (ns) is spent
     * (a chunk's layout goes on in the next frame where the budget cut it: {@link #lay}); a chunk out of view, or
     * whose laid-out labels are all too small to read now, is let go. A view too far out to read any label so lays
     * out and keeps none. With the layout at rest, what is left of the budget goes to the merge ({@link #merge}).
     */
    private void build(Camera camera, long budget) {
        if (font == null) { return; }
        List<Coords> wanted = new ArrayList<>();
        Set<Coords> gone = new HashSet<>();
        for (var cell : cellBounds.entrySet()) {
            Extent extent = laidCells.get(cell.getKey());
            boolean seen = camera.frustum.boundsInFrustum(cell.getValue());
            if (extent != null && (!seen || extent.laid() > 0 && !readable(extent.laid(), pixelsPerUnit))) {
                gone.add(cell.getKey());
            } else if (seen && (extent == null || extent.skipped() > 0 && readable(extent.skipped(), pixelsPerUnit))) {
                wanted.add(cell.getKey());
            }
        }
        // A chunk cut short that the view no longer wants gives up what it has.
        if (laying != null && !wanted.contains(laying)) { gone.add(laying); }
        if (wanted.isEmpty() && gone.isEmpty() && fresh.isEmpty() && dead.isEmpty() && trash.isEmpty()) { return; }
        forget(gone);
        long deadline = budget == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + budget;
        // The view's middle on the ground plane.
        float reach = camera.direction.z < 0 ? -camera.position.z / camera.direction.z : 0;
        float middleX = camera.position.x + camera.direction.x * reach;
        float middleY = camera.position.y + camera.direction.y * reach;
        Vector3 centre = new Vector3();
        wanted.sort(Comparator.comparingDouble(cell -> {
            cellBounds.get(cell).getCenter(centre);
            return (centre.x - middleX) * (centre.x - middleX) + (centre.y - middleY) * (centre.y - middleY);
        }));
        // The chunk the last frame's budget cut short goes on first.
        if (laying != null && wanted.remove(laying)) { wanted.add(0, laying); }
        for (Coords cell : wanted) {
            if (!lay(cell, null, deadline) || System.nanoTime() >= deadline) { return; }
        }
        // A frame that laid chunks out leaves the merge to the next one: a mesh's upload is a frame's step by itself.
        if (wanted.isEmpty() || budget == Long.MAX_VALUE) { merge(deadline); }
        // Meshes no longer drawn are freed with what is left of the budget, one at least.
        while (!trash.isEmpty()) {
            trash.removeLast().mesh.dispose();
            if (System.nanoTime() >= deadline) { break; }
        }
    }

    /**
     * The tile each hex's labels come from: the scene's, or {@code coming}'s for a hex whose structure lies as pieces.
     */
    private static List<BoardScene.Tile> sources(List<BoardScene.Tile> tiles, List<BoardScene.Tile> coming,
          Predicate<Coords> fallen) {
        if (coming == tiles || coming.size() != tiles.size()) { return tiles; }
        List<BoardScene.Tile> result = null;
        for (int index = 0; index < tiles.size(); index++) {
            BoardScene.Tile tile = tiles.get(index), next = coming.get(index);
            if (next != tile && next.coords().equals(tile.coords()) && fallen.test(tile.coords())) {
                if (result == null) { result = new ArrayList<>(tiles); }
                result.set(index, next);
            }
        }
        return result == null ? tiles : result;
    }

    /** Whether two tiles of a hex give it the same labels in the same place: a rebuild would upload the same glyphs. */
    private boolean sameLabels(BoardScene.Tile held, BoardScene.Tile tile, boolean covered) {
        return held.elevation() == tile.elevation() && held.backdrop() == tile.backdrop()
              && held.liquid().present() == tile.liquid().present()
              && BoardRelief.decoration(held) == BoardRelief.decoration(tile)
              && labels(held, tactical, types, covered).equals(labels(tile, tactical, types, covered));
    }

    private static boolean sameRoof(BoundingBox now, BoundingBox then) {
        return now == null ? then == null : then != null && now.min.equals(then.min) && now.max.equals(then.max);
    }

    /** The terrain chunk of a hex: its labels' glyphs are kept, and culled, by it. */
    private static Coords cell(Coords hex) {
        return new Coords(hex.getX() / GpuTerrain.CHUNK_SIZE, hex.getY() / GpuTerrain.CHUNK_SIZE);
    }

    /**
     * The hexes whose labels differ from those the meshes hold: those whose tile gives other labels, whose labelled
     * roof came down or stands again, or that a unit covers or uncovers; with other tiles given ({@code moved}) also
     * those whose labelled roof is elsewhere. {@code next} lists the hexes as {@link #shown} does.
     */
    private Set<Coords> staleHexes(List<BoardScene.Tile> next, Function<Coords, BoundingBox> roofBounds,
          Predicate<Coords> fallen, Set<Coords> units, boolean moved) {
        Set<Coords> stale = new HashSet<>();
        for (int index = 0; index < next.size(); index++) {
            BoardScene.Tile tile = next.get(index), held = shown.get(index);
            boolean hidden = units.contains(tile.coords());
            if (covered.contains(tile.coords()) != hidden
                  || held != tile && !held.equals(tile) && !sameLabels(held, tile, hidden)) {
                stale.add(tile.coords());
                stale.add(held.coords());
            }
        }
        for (Map.Entry<Coords, BoundingBox> roof : roofs.entrySet()) {
            if (fallen.test(roof.getKey()) != down.contains(roof.getKey())
                  || moved && !sameRoof(roofBounds.apply(roof.getKey()), roof.getValue())) {
                stale.add(roof.getKey());
            }
        }
        return stale;
    }

    /**
     * A tile's labels: its captured text, {@link BoardHexText#settled settled} so that the ground lines still end on
     * the bottom edge while a roof label lies on its roof, and in the Tactical View its water depth (not with Hex
     * Types, whose type block tells it) and its structure's CF chip, whose height the captured text decides.
     */
    static List<BoardHexText> labels(BoardScene.Tile tile, boolean tactical, boolean types, boolean covered) {
        if (!tactical) { return BoardHexText.settled(tile.text(), tile.text()); }
        int depth = !types && tile.liquid().present() && !tile.frozen() ? Math.max(0, tile.waterDepth()) : 0;
        int structure = tile.cover().structure();
        int cf = structure > 0 && structure != BoardScene.Cover.INDUSTRIAL ? tile.cover().cf() : 0;
        return BoardHexText.tactical(tile.text(), depth, cf, tile.cover().height(), covered);
    }

    private record Glyphs(List<FloatArray> regions, BoundingBox bounds, float size, BitmapFont font, Coords cell) { }

    /**
     * Ordered glyphs into meshes that share a bounded instance buffer and up to eight font textures, without repacking
     * their atlas; a list of glyphs (one chunk's outlines or text on a plane) at a time.
     */
    private static final class Merge {
        final List<Page> pages = new ArrayList<>();
        /** Whether every mesh is uploaded ({@link #upload}). */
        boolean ready;
        private final List<Glyphs> glyphs;
        private final java.util.Deque<Mesh> spares;
        private final FloatArray vertices = new FloatArray();
        private final List<Range> ranges = new ArrayList<>();
        private final List<Texture> textures = new ArrayList<>();
        private int next;

        /** {@code spares}: retired meshes of a whole mesh's size to draw from, or null for meshes of the glyphs' own size. */
        Merge(List<Glyphs> glyphs, java.util.Deque<Mesh> spares) {
            this.glyphs = glyphs;
            this.spares = spares;
        }

        /**
         * Uploads one of its meshes that is not uploaded yet; true where none was left. The labels' draw calls it, a
         * mesh a frame: an upload from outside the draw held a frame for 40 to 75 ms now and then.
         */
        boolean upload(ShaderProgram shader) {
            for (Page page : pages) {
                if (!page.uploaded) {
                    page.prepare(shader);
                    return false;
                }
            }
            return ready = true;
        }

        /**
         * Takes in at least one list, and more until the deadline (System.nanoTime) or until a mesh is full: making it
         * ends a budgeted step. True once all are in meshes, from a call that made none.
         */
        boolean step(long deadline) {
            try {
                boolean budgeted = deadline != Long.MAX_VALUE;
                while (next < glyphs.size()) {
                    int made = pages.size();
                    add(glyphs.get(next++));
                    if (budgeted && (pages.size() > made || System.nanoTime() >= deadline)) { return false; }
                }
                if (vertices.size == 0) { return true; }
                flush();
                return !budgeted;
            } catch (RuntimeException | Error failure) {
                dispose();
                throw failure;
            }
        }

        private void add(Glyphs chunk) {
            // Incremental fonts can gain atlas pages after an earlier chunk was completed.
            List<FloatArray> regions = chunk.regions();
            for (int region = 0; region < regions.size(); region++) {
                FloatArray quads = regions.get(region);
                int remaining = quads.size;
                Texture font = chunk.font().getRegions().get(region).getTexture();
                int offset = 0;
                while (remaining > 0) {
                    int texture = textures.indexOf(font);
                    if (vertices.size == PAGE_GLYPHS * GLYPH_FLOATS || texture < 0 && textures.size() == TEXTURES) {
                        flush();
                        texture = -1;
                    }
                    if (texture < 0) { texture = textures.size(); textures.add(font); }
                    int count = Math.min(remaining, PAGE_GLYPHS * GLYPH_FLOATS - vertices.size);
                    ranges.add(new Range(chunk.bounds(), chunk.size(), vertices.size, count, chunk.cell()));
                    vertices.addAll(quads.items, offset, count);
                    for (int at = vertices.size - count + 15; at < vertices.size; at += GLYPH_FLOATS) {
                        vertices.items[at] = texture;
                    }
                    offset += count;
                    remaining -= count;
                }
            }
        }

        private void flush() {
            if (vertices.size > 0) {
                pages.add(new Page(textures, vertices.toArray(), ranges, spares == null ? null : spares.poll(),
                      spares == null ? vertices.size / GLYPH_FLOATS : PAGE_GLYPHS));
            }
            vertices.clear();
            ranges.clear();
            textures.clear();
        }

        /** Whether every list is in a mesh. */
        boolean whole() { return next == glyphs.size() && vertices.size == 0; }

        void dispose() {
            pages.forEach(page -> page.mesh.dispose());
            pages.clear();
        }
    }

    /** Frees every mesh: the merged ones, the chunks' own and those of a merge under way. */
    private void clear() {
        pages.forEach(page -> page.mesh.dispose());
        pages.clear();
        fresh.values().forEach(own -> own.forEach(page -> page.mesh.dispose()));
        fresh.clear();
        mergedCells.clear();
        dead.clear();
        if (merging != null) {
            merging.dispose();
            merging = null;
        }
        trash.forEach(page -> page.mesh.dispose());
        trash.clear();
        spares.forEach(Mesh::dispose);
        spares.clear();
    }

    private static float headroom(BoardScene.Tile tile, BoardHexText label) {
        if (label.elevation() > 0 || tile.liquid().present()) { return 0; }
        // Keep the existing relief envelope; each glyph carries it without splitting the plane into font batches.
        return (float) Math.ceil(BoardRelief.decoration(tile) / BoardGeometry.hexScale()) * BoardGeometry.hexScale();
    }

    /** A print: what it shows is laid out at once. */
    void render(SpriteBatch batch, BoardCamera camera, Texture depth, int bottom) {
        render(batch, camera, depth, null, bottom, null, false, Long.MAX_VALUE);
    }

    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom) {
        render(batch, boardCamera, depth, unitDepth, bottom, null);
    }

    /** Prepared per-hex ink is borrowed for this draw; glyph geometry and depth/occlusion policy are unchanged. */
    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom,
          GpuAtmosphere atmosphere) {
        render(batch, boardCamera, depth, unitDepth, bottom, atmosphere, false);
    }

    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom,
          GpuAtmosphere atmosphere, boolean wireframe) {
        render(batch, boardCamera, depth, unitDepth, bottom, atmosphere, wireframe, BUILD_NANOS);
    }

    private void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom,
          GpuAtmosphere atmosphere, boolean wireframe, long budget) {
        Camera camera = boardCamera.camera;
        // Labels draw into a display-sized target, whatever the scene's render resolution.
        pixelsPerUnit = BoardCamera.pixelsPerUnit(camera) / BoardCamera.renderScale;
        build(camera, budget);
        if (pages.isEmpty() && fresh.isEmpty()) { return; }
        ShaderProgram previous = batch.getShader();
        batch.setShader(shader);
        batch.setProjectionMatrix(camera.combined);
        batch.setTransformMatrix(transform.setToTranslation(0, 0, .6f * BoardGeometry.hexScale()));
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        batch.begin();
        try {
            // SpriteBatch normally applies blending when it flushes; the static meshes bypass that upload path.
            if (batch.isBlendingEnabled()) {
                Gdx.gl.glEnable(GL20.GL_BLEND);
                if (batch.getBlendSrcFunc() != -1) {
                    Gdx.gl.glBlendFuncSeparate(batch.getBlendSrcFunc(), batch.getBlendDstFunc(),
                          batch.getBlendSrcFuncAlpha(), batch.getBlendDstFuncAlpha());
                }
            } else {
                Gdx.gl.glDisable(GL20.GL_BLEND);
            }
            shader.setUniformf("u_contrastEnabled", !tactical && wireframe ? -1 : 0);
            if (!tactical && !wireframe && atmosphere != null) { atmosphere.bindContrast(shader, TEXTURES + 2); }
            depth.bind(TEXTURES);
            if (unitDepth != null) { unitDepth.bind(TEXTURES + 1); }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            shader.setUniformi("u_depth", TEXTURES);
            shader.setUniformi("u_units", unitDepth == null ? TEXTURES : TEXTURES + 1);
            shader.setUniformf("u_unitOptions", unitDepth == null ? 0 : 1,
                  .05f * BoardGeometry.hexScale());
            shader.setUniformf("u_projectionDepth", camera.projection.val[Matrix4.M22], camera.projection.val[Matrix4.M23],
                  camera.projection.val[Matrix4.M32], camera.projection.val[Matrix4.M33]);
            shader.setUniformMatrix("u_inverseView", camera.invProjectionView);
            // The destination's viewport: the scene's depth captures can have another size (the render resolution).
            viewport.clear();
            Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
            shader.setUniformf("u_viewport", viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            shader.setUniformf("u_groundBoard", 0, 0, BoardGeometry.width(), BoardGeometry.height());
            shader.setUniformi("u_topView", boardCamera.isTopDown() ? 1 : 0);
            for (Page page : pages) { page.render(camera, shader, pixelsPerUnit, dead); }
            for (List<Page> own : fresh.values()) {
                for (Page page : own) { page.render(camera, shader, pixelsPerUnit, Set.of()); }
            }
            if (merging != null && merging.whole()) { merging.upload(shader); }
        } finally {
            batch.end();
            batch.setTransformMatrix(transform.idt());
            batch.setShader(previous);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        }
    }

    @Override
    public void dispose() {
        clear();
        roofs.clear();
        tiles = null;
        coming = null;
        shown = List.of();
        built.clear();
        laid.clear();
        cellTiles.clear();
        cellBounds.clear();
        laidCells.clear();
        font = null;
        outline = null;
        GpuShaderManager.dispose(shader);
    }
}
