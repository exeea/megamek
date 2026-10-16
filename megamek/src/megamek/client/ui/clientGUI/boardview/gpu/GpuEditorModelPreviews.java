/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Configuration;

/** A page-sized, disposable preview queue: CPU model decoding off-thread; one upload/render per UI frame. */
final class GpuEditorModelPreviews implements Disposable {
    /** A model in these colours; for a route asset its layout of {@code sides} and {@code tracks}, from above when {@code top}. */
    private record Request(String asset, megamek.common.board.BoardDecoration.Colours colours, int sides, int tracks, boolean top,
          Image target) { }
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "board-editor-previews"); thread.setDaemon(true); return thread;
    });
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Map<Image, FrameBuffer> images = new HashMap<>();
    private Future<ModelData> pending;
    private Request current;
    private ModelBatch batch;
    private GpuSceneDisplay display;
    private final GpuEnvironmentLighting environmentLighting = new GpuEnvironmentLighting();

    void add(String asset, Image target) { add(asset, megamek.common.board.BoardDecoration.Colours.NONE, target); }

    /** A preview of the model in these colours, as a palette entry places it; a route's shows a N-S straight. */
    void add(String asset, megamek.common.board.BoardDecoration.Colours colours, Image target) {
        queue.add(new Request(asset, colours, megamek.common.board.RailRoute.side(0) | megamek.common.board.RailRoute.side(3), 0,
              false, target));
    }

    /** A route of {@code kind} on its flat carrier with these sides and track pieces, seen from above (the junction card). */
    void add(megamek.common.board.RailRoute.Kind kind, int sides, int tracks, Image target) {
        queue.add(new Request(kind.asset, megamek.common.board.BoardDecoration.Colours.NONE, sides, tracks, true, target));
    }

    void update() {
        queue.removeIf(request -> request.target().getStage() == null);
        images.entrySet().removeIf(entry -> {
            if (entry.getKey().getStage() != null) { return false; }
            entry.getValue().dispose(); return true;
        });
        if (pending != null && current.target().getStage() == null) {
            pending.cancel(true); pending = null; current = null;
        }
        if (pending != null && pending.isDone()) {
            try { render(pending.get(), current.top(), current.target()); }
            catch (Exception failure) {
                current.target().setUserObject(megamek.client.ui.gdx.UiKit.text("BoardEditor.noPreview"));
            } finally { pending = null; current = null; }
        }
        if (pending == null && !queue.isEmpty()) {
            current = queue.removeFirst(); String asset = current.asset(); var colours = current.colours();
            int sides = current.sides(), tracks = current.tracks();
            pending = worker.submit(() -> {
                File root = new File(Configuration.dataDir(), "models/board");
                // A route is generated, not a file: the builder lays its pieces on a flat carrier.
                var route = megamek.common.board.RailRoute.Kind.of(asset);
                if (route != null) { return BoardRail.model(BoardRail.flat(route, sides, tracks)); }
                if (asset.startsWith("buildings/")) {
                    File custom = megamek.common.board.TilesetScenery.customBuildingFile(asset);
                    if (custom.isFile()) {
                        return RigidGlb.loadLods(new FileHandle(custom), new File(Configuration.dataDir(), "models/buildings").toPath()).getFirst();
                    }
                }
                var data = RigidGlb.loadLods(RigidGlb.source(root, asset), root.toPath()).getFirst();
                RigidGlb.recolour(data, colours);
                return data;
            });
        }
    }

    private void render(ModelData data, boolean top, Image target) {
        Map<String, Texture> textures = new HashMap<>();
        Model model = null;
        FrameBuffer image = null;
        FrameBuffer scene = null;
        try {
            model = ModelTextures.create(data, textures,
                  (key, color) -> textures.computeIfAbsent((color ? "color:" : "data:") + key,
                        ignored -> color ? GpuColorTexture.create(new FileHandle(key), true) : GpuColorTexture.createData(new FileHandle(key), true)));
            ModelInstance instance = new ModelInstance(model);
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox());
            Vector3 center = bounds.getCenter(new Vector3());
            float radius = Math.max(1, bounds.getDimensions(new Vector3()).len() / 2);
            PerspectiveCamera camera = new PerspectiveCamera(35, 128, 96);
            // A junction is read from above, north up; a model from the palette's three-quarter view.
            camera.up.set(top ? Vector3.Y : Vector3.Z);
            camera.position.set(center).add((top ? new Vector3(0, -.25f, 1) : new Vector3(1, -1.3f, 1)).nor().scl(radius * (top ? 3 : 3.7f)));
            camera.lookAt(center); camera.near = radius / 100; camera.far = radius * 10; camera.update();
            Environment light = new Environment();
            var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
            float exposure = lighting.adaptation();
            float backdropScale = GpuSceneRadiance.SCALE / exposure;
            environmentLighting.set(lighting);
            light.set(environmentLighting);
            GpuEnvironmentLighting.applyAntialiasing(light);
            light.set(ColorAttribute.createAmbientLight(lighting.ambient()));
            light.add(new DirectionalLight().set(lighting.direct(), lighting.direction()));
            if (batch == null) { batch = new ModelBatch(GpuShaderManager.provider("Editor model previews", GpuUnitShader::provider)); }
            if (display == null) { display = new GpuSceneDisplay(); }
            scene = GpuHeatGlow.buffer(128, 96, true);
            image = GpuAtmosphere.buffer(128, 96, true);
            scene.begin();
            try {
                Gdx.gl.glDepthMask(true);
                ScreenUtils.clear(GpuColor.linear(.045f) * backdropScale,
                      GpuColor.linear(.06f) * backdropScale,
                      GpuColor.linear(.065f) * backdropScale, 1, true);
                batch.begin(camera);
                try { batch.render(instance, light); } finally { batch.end(); }
            } finally { scene.end(); }
            try { display.render(scene, image, exposure); } finally { image.end(); }
            TextureRegion region = new TextureRegion(image.getColorBufferTexture()); region.flip(false, true);
            target.setDrawable(new TextureRegionDrawable(region)); target.setUserObject("Ready"); images.put(target, image); image = null;
        } finally {
            if (scene != null) { scene.dispose(); }
            if (image != null) { image.dispose(); }
            if (model != null) { model.dispose(); }
            textures.values().forEach(Texture::dispose);
        }
    }

    void clear() {
        queue.clear();
        if (pending != null) { pending.cancel(true); pending = null; current = null; }
        images.values().forEach(FrameBuffer::dispose); images.clear();
    }
    @Override public void dispose() {
        clear(); worker.shutdownNow();
        if (batch != null) { batch.dispose(); }
        if (display != null) { display.dispose(); }
        environmentLighting.dispose();
    }
}
