/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.board.Coords;

/** Captured board labels stay legible over their own relief, with the scene's real depth everywhere else. */
final class GpuHexText implements Disposable {
    private record Plane(int elevation, float headroom) { }
    private record Chunk(BitmapFontCache glyphs, BoundingBox bounds) { }
    private final Map<Plane, List<Chunk>> groups = new TreeMap<>(Comparator.comparingInt(Plane::elevation)
          .thenComparingDouble(Plane::headroom));
    private final ShaderProgram shader;
    private final Matrix4 transform = new Matrix4();
    private List<BoardScene.Tile> tiles;
    private int tuning = -1;

    GpuHexText() {
        String path = "megamek/client/ui/clientGUI/boardview/gpu/";
        String vertex = Gdx.files.classpath(path + "hex-text.vert").readString("UTF-8");
        String fragment = Gdx.files.classpath(path + "hex-text.frag").readString("UTF-8")
              .replace("// GROUND_LAYER", Gdx.files.classpath(path + "ground-layer.glsl").readString("UTF-8"))
              .replace("// CAMERA_DEPTH", Gdx.files.classpath(path + "camera-depth.glsl").readString("UTF-8"));
        shader = new ShaderProgram(vertex, fragment);
        if (!shader.isCompiled()) {
            String log = shader.getLog();
            shader.dispose();
            throw new IllegalStateException("GPU hex text shader: " + log);
        }
    }

    /** The font and roof bounds are borrowed; labels are solely the existing board view's captured text. */
    void update(BoardScene scene, BitmapFont font, Function<Coords, BoundingBox> roofBounds) {
        if (tiles == scene.tiles() && tuning == BoardGeometry.revision()) { return; }
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        Color color = new Color(font.getColor());
        Map<Plane, Map<Coords, Chunk>> next = new HashMap<>();
        try {
            for (BoardScene.Tile tile : scene.tiles()) {
                for (BoardView.HexText label : tile.text()) {
                    Plane plane = new Plane(tile.elevation() + label.elevation(), headroom(tile, label));
                    Coords cell = new Coords(tile.coords().getX() / GpuTerrain.CHUNK_SIZE,
                          tile.coords().getY() / GpuTerrain.CHUNK_SIZE);
                    Chunk chunk = next.computeIfAbsent(plane, key -> new HashMap<>())
                          .computeIfAbsent(cell, key -> new Chunk(new BitmapFontCache(font, false), new BoundingBox().inf()));
                    float centerX = BoardGeometry.centerX(tile.coords()), centerY = BoardGeometry.centerY(tile.coords());
                    float z = plane.elevation() * BoardGeometry.LEVEL;
                    chunk.bounds().ext(centerX - BoardGeometry.WIDTH, centerY - BoardGeometry.WIDTH, z - 1)
                          .ext(centerX + BoardGeometry.WIDTH, centerY + BoardGeometry.WIDTH, z + 1);
                    font.getData().setScale(label.font().getSize2D() / GpuBoardUi.FONT_RESOLUTION);
                    int argb = label.argb();
                    font.setColor(((argb >>> 16) & 255) / 255f, ((argb >>> 8) & 255) / 255f,
                          (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
                    GlyphLayout layout = new GlyphLayout(font, label.text());
                    float x = centerX;
                    float baseline = centerY + BoardGeometry.HEIGHT / 2 - label.baseline() * BoardGeometry.HEX_SCALE;
                    BoundingBox roof = label.elevation() > 0 ? roofBounds.apply(tile.coords()) : null;
                    if (roof != null) {
                        float fit = Math.min(1, Math.max(8, roof.getWidth() - 4 * BoardGeometry.HEX_SCALE) / layout.width);
                        font.getData().setScale(font.getData().scaleX * fit);
                        layout.setText(font, label.text());
                        x = roof.getCenterX();
                        baseline = roof.getCenterY() - layout.height / 2;
                    }
                    chunk.glyphs().addText(layout, x - layout.width / 2,
                          label.fromTop() ? baseline : baseline + layout.height);
                }
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
            font.setColor(color);
        }
        groups.clear();
        next.forEach((plane, chunks) -> groups.put(plane, List.copyOf(chunks.values())));
        tiles = scene.tiles();
        tuning = BoardGeometry.revision();
    }

    private static float headroom(BoardScene.Tile tile, BoardView.HexText label) {
        if (label.elevation() > 0 || tile.liquid().present()) { return 0; }
        // Whole world units keep differently sized scatter in a small number of font batches.
        return (float) Math.ceil(BoardRelief.decoration(tile) / BoardGeometry.HEX_SCALE) * BoardGeometry.HEX_SCALE;
    }

    void render(SpriteBatch batch, Camera camera, Texture depth, int bottom) {
        render(batch, camera, depth, null, bottom);
    }

    void render(SpriteBatch batch, Camera camera, Texture depth, Texture unitDepth, int bottom) {
        if (groups.isEmpty()) { return; }
        ShaderProgram previous = batch.getShader();
        batch.setShader(shader);
        batch.setProjectionMatrix(camera.combined);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        batch.begin();
        try {
            depth.bind(1);
            if (unitDepth != null) { unitDepth.bind(2); }
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            shader.setUniformi("u_depth", 1);
            shader.setUniformi("u_units", unitDepth == null ? 1 : 2);
            shader.setUniformf("u_unitOptions", unitDepth == null ? 0 : 1,
                  .05f * BoardGeometry.HEX_SCALE);
            shader.setUniformf("u_projectionDepth", camera.projection.val[Matrix4.M22], camera.projection.val[Matrix4.M23],
                  camera.projection.val[Matrix4.M32], camera.projection.val[Matrix4.M33]);
            shader.setUniformMatrix("u_inverseView", camera.invProjectionView);
            shader.setUniformf("u_viewport", 0, HdpiUtils.toBackBufferY(bottom), depth.getWidth(), depth.getHeight());
            shader.setUniformf("u_groundBoard", 0, 0, BoardGeometry.WIDTH, BoardGeometry.HEIGHT);
            for (var group : groups.entrySet()) {
                batch.flush();
                float z = group.getKey().elevation() * BoardGeometry.LEVEL;
                batch.setTransformMatrix(transform.setToTranslation(0, 0, z + .6f * BoardGeometry.HEX_SCALE));
                shader.setUniformf("u_surface", z, group.getKey().headroom());
                for (Chunk chunk : group.getValue()) {
                    if (camera.frustum.boundsInFrustum(chunk.bounds())) { chunk.glyphs().draw(batch); }
                }
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
        groups.clear();
        tiles = null;
        shader.dispose();
    }
}
