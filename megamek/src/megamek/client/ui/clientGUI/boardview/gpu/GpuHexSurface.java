/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectIntMap;
import megamek.common.board.Coords;

/**
 * Terrain carriers shared by every hex-mask style. Each triangle carries its overlay owner's hex coordinates,
 * even when its terrain belongs to a neighbor; the shader supplies that owner's mask and exact painter lift.
 * Finished surfaces are borrowed, while this carrier owns its indexed meshes and no retained vertex arrays.
 */
final class GpuHexSurface implements Disposable {
    private static final int PAGE_VERTICES = 60000;
    private record Page(Mesh mesh, BoundingBox bounds) { }
    private final List<Page> pages = new ArrayList<>();
    private final Map<Coords, BoardTacticalGeometry.Surface> dependencies = new HashMap<>();
    private final BoundingBox bounds = new BoundingBox().inf();

    /** Chunk coordinates, not tile coordinates: (1, 0) begins at column {@link GpuTerrain#CHUNK_SIZE}. */
    GpuHexSurface(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        this(scene, chunk, surfaces, false);
    }

    GpuHexSurface(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          boolean wholeHex) {
        MeshBuilder builder = null;
        ObjectIntMap<Vector3> indices = new ObjectIntMap<>();
        float[] vertex = new float[5];
        int startX = chunk.getX() * GpuTerrain.CHUNK_SIZE, startY = chunk.getY() * GpuTerrain.CHUNK_SIZE;
        try {
            for (int x = startX; x < Math.min(scene.width(), startX + GpuTerrain.CHUNK_SIZE); x++) {
                for (int y = startY; y < Math.min(scene.height(), startY + GpuTerrain.CHUNK_SIZE); y++) {
                    indices.clear();
                    vertex[3] = x;
                    vertex[4] = y;
                    float left = x * BoardGeometry.TILE_WIDTH * .75f;
                    float top = (y + (x & 1) * .5f) * BoardGeometry.TILE_HEIGHT;
                    float right = left + BoardGeometry.TILE_WIDTH, bottom = top + BoardGeometry.TILE_HEIGHT;
                    // The same conservative candidate window as drape(), for a complete unscaled hex.
                    // A whole-hex tint uses its terrain once, with no footprint clipping or neighbor duplication.
                    int firstX = wholeHex ? x : Math.max(0, (int) Math.floor(left / (BoardGeometry.TILE_WIDTH * .75f)) - 1);
                    int lastX = wholeHex ? x : Math.min(scene.width() - 1, (int) Math.floor(right / (BoardGeometry.TILE_WIDTH * .75f)));
                    int firstY = wholeHex ? y : Math.max(0, (int) Math.floor(top / BoardGeometry.TILE_HEIGHT) - 1);
                    int lastY = wholeHex ? y : Math.min(scene.height() - 1, (int) Math.floor(bottom / BoardGeometry.TILE_HEIGHT));
                    float minX = left * BoardGeometry.HEX_SCALE, maxX = right * BoardGeometry.HEX_SCALE;
                    float minY = -bottom * BoardGeometry.HEX_SCALE, maxY = -top * BoardGeometry.HEX_SCALE;
                    for (int cx = firstX; cx <= lastX; cx++) {
                        for (int cy = firstY; cy <= lastY; cy++) {
                            var surface = dependencies.computeIfAbsent(new Coords(cx, cy), surfaces);
                            for (var faces : wholeHex ? List.of(surface.faces(), surface.water(), surface.walls())
                                  : List.of(surface.top(), surface.slopes())) {
                                for (BoardSurface.Face face : faces) {
                                    if (!wholeHex && !intersects(face, minX, minY, maxX, maxY)) { continue; }
                                    if (builder == null || builder.getNumVertices() + 3 > PAGE_VERTICES) {
                                        if (builder != null) { finish(builder); }
                                        builder = new MeshBuilder();
                                        builder.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.TextureCoordinates,
                                              GL20.GL_TRIANGLES);
                                        indices.clear();
                                    }
                                    builder.triangle(vertex(builder, indices, face.a(), vertex),
                                          vertex(builder, indices, face.b(), vertex), vertex(builder, indices, face.c(), vertex));
                                }
                            }
                        }
                    }
                }
            }
            if (builder != null) { finish(builder); }
        } catch (RuntimeException | Error failure) {
            dispose();
            throw failure;
        }
    }

    private static short vertex(MeshBuilder builder, ObjectIntMap<Vector3> indices, Vector3 point, float[] vertex) {
        int index = indices.get(point, -1);
        if (index < 0) {
            vertex[0] = point.x;
            vertex[1] = point.y;
            vertex[2] = point.z;
            index = Short.toUnsignedInt(builder.vertex(vertex)); // MeshBuilder copies the scratch array immediately.
            indices.put(point, index);
        }
        return (short) index;
    }

    private static boolean intersects(BoardSurface.Face face, float minX, float minY, float maxX, float maxY) {
        float area = (face.b().x - face.a().x) * (face.c().y - face.a().y)
              - (face.b().y - face.a().y) * (face.c().x - face.a().x);
        if (Math.abs(area) < .00001f) { return false; }
        return Math.max(face.a().x, Math.max(face.b().x, face.c().x)) >= minX
              && Math.min(face.a().x, Math.min(face.b().x, face.c().x)) <= maxX
              && Math.max(face.a().y, Math.max(face.b().y, face.c().y)) >= minY
              && Math.min(face.a().y, Math.min(face.b().y, face.c().y)) <= maxY;
    }

    /** No-upload visibility bound. Callers may cache this until the board, tuning or terrain changes. */
    static BoundingBox bounds(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        return bounds(scene, chunk, surfaces, false);
    }

    static BoundingBox bounds(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          boolean wholeHex) {
        int startX = chunk.getX() * GpuTerrain.CHUNK_SIZE, startY = chunk.getY() * GpuTerrain.CHUNK_SIZE;
        int endX = Math.min(scene.width(), startX + GpuTerrain.CHUNK_SIZE);
        int endY = Math.min(scene.height(), startY + GpuTerrain.CHUNK_SIZE);
        float minX = startX * BoardGeometry.TILE_WIDTH * .75f * BoardGeometry.HEX_SCALE;
        float maxX = ((endX - 1) * BoardGeometry.TILE_WIDTH * .75f + BoardGeometry.TILE_WIDTH) * BoardGeometry.HEX_SCALE;
        float minY = -(endY + .5f) * BoardGeometry.TILE_HEIGHT * BoardGeometry.HEX_SCALE;
        float maxY = -startY * BoardGeometry.TILE_HEIGHT * BoardGeometry.HEX_SCALE;
        BoundingBox result = new BoundingBox().inf();
        if (wholeHex) {
            for (int x = startX; x < endX; x++) {
                for (int y = startY; y < endY; y++) {
                    var surface = surfaces.apply(new Coords(x, y));
                    for (var faces : List.of(surface.faces(), surface.water(), surface.walls())) {
                        for (var face : faces) { result.ext(face.a()).ext(face.b()).ext(face.c()); }
                    }
                }
            }
            if (result.isValid()) { liftedBounds(result); }
            return result;
        }
        for (int x = Math.max(0, startX - 1); x <= Math.min(scene.width() - 1, endX); x++) {
            for (int y = Math.max(0, startY - 1); y <= Math.min(scene.height() - 1, endY); y++) {
                var surface = surfaces.apply(new Coords(x, y));
                extend(result, surface.top(), minX, minY, maxX, maxY);
                extend(result, surface.slopes(), minX, minY, maxX, maxY);
            }
        }
        if (result.isValid()) {
            // Whole carrier triangles can reach farther, but the shader discards outside their owner's hex.
            result.min.x = Math.max(result.min.x, minX);
            result.min.y = Math.max(result.min.y, minY);
            result.max.x = Math.min(result.max.x, maxX);
            result.max.y = Math.min(result.max.y, maxY);
            liftedBounds(result);
        }
        return result;
    }

    private static void extend(BoundingBox bounds, List<BoardSurface.Face> faces,
          float minX, float minY, float maxX, float maxY) {
        for (BoardSurface.Face face : faces) {
            if (intersects(face, minX, minY, maxX, maxY)) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
    }

    private static void liftedBounds(BoundingBox bounds) {
        // Current painter lift is capped at 1.35 unscaled pixels; keep culling conservative after vertex lift.
        bounds.max.z += 2 * BoardGeometry.HEX_SCALE;
        bounds.update();
    }

    private void finish(MeshBuilder builder) {
        Mesh mesh = builder.end();
        BoundingBox pageBounds = new BoundingBox();
        pages.add(new Page(mesh, pageBounds));
        mesh.calculateBoundingBox(pageBounds);
        liftedBounds(pageBounds);
        bounds.ext(pageBounds);
    }

    boolean current(Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        for (var entry : dependencies.entrySet()) {
            if (surfaces.apply(entry.getKey()) != entry.getValue()) { return false; }
        }
        return true;
    }

    /** Borrowed read-only bounds, including the shader's capped painter lift. */
    BoundingBox bounds() { return bounds; }

    /** Uploaded vertex and index payload; excludes the driver's storage and borrowed surface objects. */
    long bytes() {
        return pages.stream().mapToLong(page -> (long) page.mesh().getNumVertices() * page.mesh().getVertexSize()
              + (long) page.mesh().getNumIndices() * Short.BYTES).sum();
    }

    /** The caller owns shader uniforms and GL state; meshes bind only for this call. */
    void render(Camera camera, ShaderProgram shader) {
        for (Page page : pages) {
            if (camera.frustum.boundsInFrustum(page.bounds())) { page.mesh().render(shader, GL20.GL_TRIANGLES); }
        }
    }

    /** Pooled submissions share the batch's ordering with icons; the caller keeps each draw state alive until flush. */
    void submit(ModelBatch batch, Camera camera, Shader shader, Material material, Object state) {
        for (Page page : pages) {
            if (!camera.frustum.boundsInFrustum(page.bounds())) { continue; }
            batch.render((renderables, pool) -> {
                Renderable renderable = pool.obtain();
                renderable.worldTransform.idt();
                renderable.meshPart.set("hex-mask", page.mesh(), 0, page.mesh().getNumIndices(), GL20.GL_TRIANGLES);
                page.bounds().getCenter(renderable.meshPart.center);
                page.bounds().getDimensions(renderable.meshPart.halfExtents).scl(.5f);
                renderable.meshPart.radius = renderable.meshPart.halfExtents.len();
                renderable.material = material;
                renderable.shader = shader;
                renderable.environment = null;
                renderable.bones = null;
                renderable.userData = state;
                renderables.add(renderable);
            });
        }
    }

    @Override
    public void dispose() {
        pages.forEach(page -> page.mesh().dispose());
        pages.clear();
        dependencies.clear();
        bounds.inf();
    }
}
