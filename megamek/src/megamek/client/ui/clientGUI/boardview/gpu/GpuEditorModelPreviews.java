/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
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
    private record Request(String asset, Image target) { }
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "board-editor-previews"); thread.setDaemon(true); return thread;
    });
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Map<Image, FrameBuffer> images = new HashMap<>();
    private Future<ModelData> pending;
    private Request current;
    private ModelBatch batch;

    void add(String asset, Image target) { queue.add(new Request(asset, target)); }

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
            try { render(pending.get(), current.target()); }
            catch (Exception failure) {
                current.target().setUserObject("No preview");
            } finally { pending = null; current = null; }
        }
        if (pending == null && !queue.isEmpty()) {
            current = queue.removeFirst(); String asset = current.asset();
            pending = worker.submit(() -> {
                File root = new File(Configuration.dataDir(), "models/board");
                File file = new File(root, asset + ".glb");
                if (asset.startsWith("buildings/")) {
                    File custom = megamek.client.ui.clientGUI.boardview.BoardArtwork.customBuildingFile(asset);
                    if (custom.isFile()) { file = custom; root = new File(Configuration.dataDir(), "models/buildings"); }
                }
                return RigidGlb.loadLods(new FileHandle(file), root.toPath()).getFirst();
            });
        }
    }

    private void render(ModelData data, Image target) {
        Map<String, Texture> textures = new HashMap<>();
        Model model = null;
        FrameBuffer image = null;
        try {
            model = ModelTextures.create(data, textures,
                  key -> textures.computeIfAbsent(key, file -> new Texture(new FileHandle(file), true)));
            ModelInstance instance = new ModelInstance(model);
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox());
            Vector3 center = bounds.getCenter(new Vector3());
            float radius = Math.max(1, bounds.getDimensions(new Vector3()).len() / 2);
            PerspectiveCamera camera = new PerspectiveCamera(35, 128, 96);
            camera.up.set(Vector3.Z);
            camera.position.set(center).add(new Vector3(1, -1.3f, 1).nor().scl(radius * 3.7f));
            camera.lookAt(center); camera.near = radius / 100; camera.far = radius * 10; camera.update();
            Environment light = new Environment();
            light.set(new ColorAttribute(ColorAttribute.AmbientLight, .55f, .55f, .55f, 1));
            light.add(new DirectionalLight().set(.85f, .85f, .8f, -1, -1, -2));
            if (batch == null) { batch = new ModelBatch(); }
            image = new FrameBuffer(Pixmap.Format.RGBA8888, 128, 96, true);
            image.begin();
            try {
                ScreenUtils.clear(.045f, .06f, .065f, 1, true);
                batch.begin(camera);
                try { batch.render(instance, light); } finally { batch.end(); }
            } finally { image.end(); }
            TextureRegion region = new TextureRegion(image.getColorBufferTexture()); region.flip(false, true);
            target.setDrawable(new TextureRegionDrawable(region)); target.setUserObject("Ready"); images.put(target, image); image = null;
        } finally {
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
    @Override public void dispose() { clear(); worker.shutdownNow(); if (batch != null) { batch.dispose(); } }
}
