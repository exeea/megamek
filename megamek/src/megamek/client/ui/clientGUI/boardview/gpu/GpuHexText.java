/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

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
 * structures' construction factors ({@link BoardHexText#tactical}).
 */
final class GpuHexText implements Disposable {
    /** Four vertices per glyph, with unsigned 16-bit indices. */
    private static final int PAGE_GLYPHS = 16_383;
    private static final int GLYPH_FLOATS = 36;
    private record Plane(int elevation, float headroom, boolean overlay) { }
    /** A chunk's glyph vertices, one array per page of the outline font and of the text font, in drawing order. */
    private record Chunk(List<FloatArray> outlines, List<FloatArray> pages, BoundingBox bounds) {
        static void add(List<FloatArray> pages, BitmapFontCache glyphs, float contrast, BoardScene.Tile tile) {
            for (int page = 0; page < glyphs.getPageCount(); page++) {
                if (pages.size() == page) { pages.add(new FloatArray()); }
                float[] vertices = glyphs.getVertices(page);
                for (int at = 0; at < glyphs.getVertexCount(page); at += 5) {
                    pages.get(page).addAll(vertices, at, 5);
                    pages.get(page).add(contrast);
                    pages.get(page).add(tile.coords().getX(), tile.coords().getY(), tile.elevation());
                }
            }
        }
    }
    private record Range(BoundingBox bounds, int offset, int count) { }
    /** Owns the static mesh; the font owns the texture. Ranges keep the original chunk/page drawing order. */
    private record Page(Texture texture, Mesh mesh, List<Range> ranges) {
        void render(Camera camera, ShaderProgram shader) {
            boolean bound = false;
            int start = 0, count = 0;
            try {
                for (Range range : ranges) {
                    if (camera.frustum.boundsInFrustum(range.bounds())) {
                        if (!bound) {
                            texture.bind(0);
                            mesh.bind(shader);
                            bound = true;
                        }
                        if (count == 0) { start = range.offset(); }
                        count += range.count();
                    } else if (count > 0) {
                        mesh.render(shader, GL20.GL_TRIANGLES, start, count, false);
                        count = 0;
                    }
                }
                if (count > 0) { mesh.render(shader, GL20.GL_TRIANGLES, start, count, false); }
            } finally {
                if (bound) { mesh.unbind(shader); }
            }
        }
    }
    private final Map<Plane, List<Page>> groups = new TreeMap<>(Comparator.comparing(Plane::overlay)
          .thenComparingInt(Plane::elevation)
          .thenComparingDouble(Plane::headroom));
    private ShaderProgram shader;
    private final Matrix4 transform = new Matrix4();
    private final IntBuffer viewport = BufferUtils.newIntBuffer(4);
    private List<BoardScene.Tile> tiles;
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
        Set<Coords> units = new HashSet<>();
        if (tactical) { scene.units().forEach(unit -> units.addAll(unit.footprint())); }
        boolean same = this.font == font && this.outline == outline && tuning == BoardGeometry.revision()
              && this.tactical == tactical && this.types == types && this.outlined == outlined
              && covered.equals(units);
        if (same && tiles != scene.tiles() && sameLabels(scene.tiles(), roofBounds)) { tiles = scene.tiles(); }
        if (same && tiles == scene.tiles()) { return; }
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        float outlineX = outline.getData().scaleX, outlineY = outline.getData().scaleY;
        Color color = new Color(font.getColor()), ringColor = new Color();
        // One label at a time: a font's cache grows its arrays to the exact size at every text it adds, so a chunk's
        // labels in one cache would copy them over and over (quadratic in the chunk's glyphs).
        BitmapFontCache glyphs = new BitmapFontCache(font, false), rings = new BitmapFontCache(outline, false);
        Map<Plane, Map<Coords, Chunk>> next = new HashMap<>();
        Map<Coords, BoundingBox> nextRoofs = new HashMap<>();
        try {
            for (BoardScene.Tile tile : scene.tiles()) {
                for (BoardHexText label : labels(tile, tactical, types, units.contains(tile.coords()))) {
                    Plane plane = new Plane(tile.elevation() + label.elevation(), headroom(tile, label), label.elevation() > 0);
                    Coords cell = new Coords(tile.coords().getX() / GpuTerrain.CHUNK_SIZE,
                          tile.coords().getY() / GpuTerrain.CHUNK_SIZE);
                    Chunk chunk = next.computeIfAbsent(plane, key -> new HashMap<>())
                          .computeIfAbsent(cell, key -> new Chunk(new ArrayList<>(), new ArrayList<>(),
                                new BoundingBox().inf()));
                    float centerX = BoardGeometry.centerX(tile.coords()), centerY = BoardGeometry.centerY(tile.coords());
                    float z = plane.elevation() * BoardGeometry.level();
                    chunk.bounds().ext(centerX - BoardGeometry.width(), centerY - BoardGeometry.width(), z - 1)
                          .ext(centerX + BoardGeometry.width(), centerY + BoardGeometry.width(), z + 1);
                    font.getData().setScale(label.font().getSize2D() / GpuBoardSkin.FONT_RESOLUTION);
                    boolean space = tile.backdrop() == BoardBackdrop.SPACE;
                    int argb = space ? label.argb() | 0x00ffffff : label.argb();
                    // Space always keeps white text and its dark outline, regardless of sampled brightness.
                    // Other neutral ink adapts without rebuilding; deliberately colored labels retain their color.
                    boolean neutral = !space && (argb >>> 16 & 255) == (argb >>> 8 & 255)
                          && (argb >>> 8 & 255) == (argb & 255);
                    font.setColor(((argb >>> 16) & 255) / 255f, ((argb >>> 8) & 255) / 255f,
                          (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
                    GlyphLayout layout = new GlyphLayout(font, label.text());
                    float x = centerX;
                    float baseline = centerY + BoardGeometry.height() / 2 - label.baseline() * BoardGeometry.hexScale();
                    BoundingBox roof = label.elevation() > 0 ? roofBounds.apply(tile.coords()) : null;
                    if (label.elevation() > 0) {
                        nextRoofs.put(tile.coords(), roof == null ? null : new BoundingBox(roof));
                    }
                    if (roof != null) {
                        float fit = Math.min(1, Math.max(8, roof.getWidth() - 4 * BoardGeometry.hexScale()) / layout.width);
                        font.getData().setScale(font.getData().scaleX * fit);
                        layout.setText(font, label.text());
                        x = roof.getCenterX();
                        baseline = roof.getCenterY() - layout.height / 2;
                    }
                    float left = x - layout.width / 2, top = label.fromTop() ? baseline : baseline + layout.height;
                    if (outlined) {
                        // The outline: the bordered glyphs in the outline's colour. A line starts at its first glyph's
                        // border, so they go OUTLINE texels left to put each glyph's ink on the text's.
                        outline.getData().setScale(font.getData().scaleX, font.getData().scaleY);
                        Color.argb8888ToColor(ringColor, BoardHexText.outline(argb));
                        rings.clear();
                        rings.addText(new GlyphLayout(outline, label.text(), ringColor, 0, Align.left, false),
                              left - GpuBoardSkin.OUTLINE * outline.getData().scaleX, top);
                        Chunk.add(chunk.outlines(), rings, neutral ? -1 : 0, tile);
                    }
                    glyphs.clear();
                    glyphs.addText(layout, left, top);
                    Chunk.add(chunk.pages(), glyphs, neutral ? 1 : 0, tile);
                }
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
            outline.getData().setScale(outlineX, outlineY);
            font.setColor(color);
        }
        Map<Plane, List<Page>> uploaded = new HashMap<>();
        try {
            next.forEach((plane, chunks) -> {
                List<Chunk> planeChunks = List.copyOf(chunks.values());
                // All of a plane's outlines under all of its text, so that no outline covers a neighbour's letters.
                List<Page> pages = new ArrayList<>(upload(planeChunks, outline, Chunk::outlines));
                uploaded.put(plane, pages);
                pages.addAll(upload(planeChunks, font, Chunk::pages));
            });
        } catch (RuntimeException | Error failure) {
            uploaded.values().forEach(pages -> pages.forEach(page -> page.mesh().dispose()));
            throw failure;
        }
        clear();
        groups.putAll(uploaded);
        roofs.clear();
        roofs.putAll(nextRoofs);
        tiles = scene.tiles();
        this.font = font;
        this.outline = outline;
        tuning = BoardGeometry.revision();
        this.tactical = tactical;
        this.types = types;
        this.outlined = outlined;
        covered = units;
    }

    /**
     * Whether {@code next}, the scene's new tiles, give the labels the meshes hold, so that a rebuild would upload the
     * same glyphs: only their markings changed (a path preview repaints the tactical images of the hexes it crosses,
     * which recaptures equal labels) and every roof label's roof is where it was.
     */
    private boolean sameLabels(List<BoardScene.Tile> next, Function<Coords, BoundingBox> roofBounds) {
        if (tiles == null || tiles.size() != next.size()) { return false; }
        for (int index = 0; index < next.size(); index++) {
            BoardScene.Tile old = tiles.get(index), tile = next.get(index);
            if (old != tile && !(old.text().equals(tile.text())
                  && old.withMarkings(tile.tactical(), tile.text()).equals(tile))) { return false; }
        }
        for (Map.Entry<Coords, BoundingBox> roof : roofs.entrySet()) {
            BoundingBox now = roofBounds.apply(roof.getKey()), then = roof.getValue();
            if (now == null ? then != null : then == null || !now.min.equals(then.min) || !now.max.equals(then.max)) {
                return false;
            }
        }
        return true;
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

    /**
     * Upload the exact SpriteBatch vertices of the chunks' {@code glyphs} in {@code font}, joining only consecutive
     * draws with the same font texture.
     */
    private static List<Page> upload(List<Chunk> chunks, BitmapFont font, Function<Chunk, List<FloatArray>> glyphs) {
        List<Page> pages = new ArrayList<>();
        FloatArray vertices = new FloatArray();
        List<Range> ranges = new ArrayList<>();
        Texture texture = null;
        try {
            for (Chunk chunk : chunks) {
                // Incremental fonts can gain atlas pages after an earlier chunk was completed.
                List<FloatArray> regions = glyphs.apply(chunk);
                for (int region = 0; region < regions.size(); region++) {
                    FloatArray quads = regions.get(region);
                    int remaining = quads.size;
                    Texture next = font.getRegions().get(region).getTexture();
                    int offset = 0;
                    while (remaining > 0) {
                        if (vertices.size > 0 && (texture != next || vertices.size == PAGE_GLYPHS * GLYPH_FLOATS)) {
                            pages.add(page(texture, vertices, ranges));
                            vertices.clear();
                            ranges.clear();
                        }
                        texture = next;
                        int count = Math.min(remaining, PAGE_GLYPHS * GLYPH_FLOATS - vertices.size);
                        ranges.add(new Range(chunk.bounds(), vertices.size / GLYPH_FLOATS * 6,
                              count / GLYPH_FLOATS * 6));
                        vertices.addAll(quads.items, offset, count);
                        offset += count;
                        remaining -= count;
                    }
                }
            }
            if (vertices.size > 0) { pages.add(page(texture, vertices, ranges)); }
            return List.copyOf(pages);
        } catch (RuntimeException | Error failure) {
            pages.forEach(page -> page.mesh().dispose());
            throw failure;
        }
    }

    private static Page page(Texture texture, FloatArray vertices, List<Range> ranges) {
        int glyphs = vertices.size / GLYPH_FLOATS;
        short[] indices = new short[glyphs * 6];
        for (int glyph = 0, at = 0; glyph < glyphs; glyph++) {
            int vertex = glyph * 4;
            indices[at++] = (short) vertex;
            indices[at++] = (short) (vertex + 1);
            indices[at++] = (short) (vertex + 2);
            indices[at++] = (short) (vertex + 2);
            indices[at++] = (short) (vertex + 3);
            indices[at++] = (short) vertex;
        }
        Mesh mesh = new Mesh(true, glyphs * 4, indices.length,
              new VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
              new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
              new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_contrast"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_hex"));
        try {
            mesh.setVertices(vertices.items, 0, vertices.size);
            mesh.setIndices(indices);
            return new Page(texture, mesh, List.copyOf(ranges));
        } catch (RuntimeException | Error failure) {
            mesh.dispose();
            throw failure;
        }
    }

    private void clear() {
        groups.values().forEach(pages -> pages.forEach(page -> page.mesh().dispose()));
        groups.clear();
    }

    private static float headroom(BoardScene.Tile tile, BoardHexText label) {
        if (label.elevation() > 0 || tile.liquid().present()) { return 0; }
        // Whole world units keep differently sized scatter in a small number of font batches.
        return (float) Math.ceil(BoardRelief.decoration(tile) / BoardGeometry.hexScale()) * BoardGeometry.hexScale();
    }

    void render(SpriteBatch batch, BoardCamera camera, Texture depth, int bottom) {
        render(batch, camera, depth, null, bottom);
    }

    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom) {
        render(batch, boardCamera, depth, unitDepth, bottom, null);
    }

    /** Prepared per-hex ink is borrowed for this draw; glyph geometry and depth/occlusion policy are unchanged. */
    void render(SpriteBatch batch, BoardCamera boardCamera, Texture depth, Texture unitDepth, int bottom,
          GpuAtmosphere atmosphere) {
        if (groups.isEmpty()) { return; }
        Camera camera = boardCamera.camera;
        ShaderProgram previous = batch.getShader();
        batch.setShader(shader);
        batch.setProjectionMatrix(camera.combined);
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
            shader.setUniformf("u_contrastEnabled", 0);
            if (!tactical && atmosphere != null) { atmosphere.bindContrast(shader, 3); }
            depth.bind(1);
            if (unitDepth != null) { unitDepth.bind(2); }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            shader.setUniformi("u_depth", 1);
            shader.setUniformi("u_units", unitDepth == null ? 1 : 2);
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
            for (var group : groups.entrySet()) {
                float z = group.getKey().elevation() * BoardGeometry.level();
                batch.setTransformMatrix(transform.setToTranslation(0, 0, z + .6f * BoardGeometry.hexScale()));
                shader.setUniformf("u_surface", z, group.getKey().headroom());
                shader.setUniformi("u_overlay", group.getKey().overlay() ? 1 : boardCamera.isTopDown() ? 2 : 0);
                for (Page page : group.getValue()) { page.render(camera, shader); }
            }
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
        font = null;
        outline = null;
        GpuShaderManager.dispose(shader);
    }
}
