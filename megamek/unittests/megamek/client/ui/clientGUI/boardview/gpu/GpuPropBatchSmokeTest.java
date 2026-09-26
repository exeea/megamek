/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelCache;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Prototype gate: existing chunk caches pass through a second cache without losing their pixels or ownership. */
@Tag("on-demand")
class GpuPropBatchSmokeTest {
    @Test
    void plainPropsBatchWithoutChangingPixelsAndRebuildOnlyForNewVisibleSources() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Native prop batch prototype", failure.get()); }
    }

    private record Source(int page, ModelCache owner, Array<Renderable> parts) implements RenderableProvider {
        @Override
        public void getRenderables(Array<Renderable> destination, Pool<Renderable> pool) {
            for (Renderable part : parts) { part.shader = null; part.environment = null; }
            destination.addAll(parts);
        }
    }

    private static void check() throws Exception {
        Model small = model(20, new Material(ColorAttribute.createDiffuse(Color.WHITE)));
        Model large = model(25, new Material(ColorAttribute.createDiffuse(Color.WHITE)));
        Model fallback = model(20, new Material(ColorAttribute.createDiffuse(Color.WHITE),
              IntAttribute.createCullFace(GL20.GL_NONE)));
        List<Source> owned = new ArrayList<>(), all = new ArrayList<>();
        GpuPropBatch aggregate = new GpuPropBatch();
        Array<Renderable> submitted = new Array<>();
        GpuOpaqueSorter sorter = new GpuOpaqueSorter();
        ModelBatch batch = new ModelBatch(new DefaultShaderProvider(), (camera, values) -> {
            submitted.clear();
            submitted.addAll(values);
            sorter.sort(camera, values);
        });
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        boolean aggregateDisposed = false;
        try {
            // 375 chunk-owned meshes, 283,500 vertices: close to the audited 375 draws / 282,960 vertices.
            for (int i = 0; i < 375; i++) {
                Source source = source(i % 5 == 0 ? large : small, i, 0);
                all.add(source);
                owned.add(source);
            }
            Source replacement = source(small, 137, .35f), unbatched = source(fallback, 375, 0);
            owned.add(replacement);
            owned.add(unbatched);
            Source transformed = source(small, 376, 0);
            for (Renderable part : transformed.parts()) { part.worldTransform.translate(0, 0, 2); }
            owned.add(transformed);
            Set<Mesh> borrowed = managedMeshes();
            Environment environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(.3f, .3f, .3f, 1));
            environment.add(new DirectionalLight().set(.8f, .7f, .6f, -.6f, -.4f, -1));
            BoardCamera view = new BoardCamera();
            view.resize(1200, 900);
            view.camera.zoom = .12f;
            view.center(new Vector3(48, -48, 0));
            int maximumChanged = 0, maximumDelta = 0;
            for (boolean perspective : new boolean[] { false, true }) {
                view.setPerspective(perspective);
                for (float tilt : new float[] { 0, 55, 75 }) {
                    view.setIsometric(false);
                    view.orbit(20, tilt);
                    assertEquals(tilt, view.tilt());
                    for (int warm = 0; warm < 8; warm++) {
                        draw(batch, view.camera, environment, all, null);
                        draw(batch, view.camera, environment, all, aggregate);
                    }
                    byte[] reference = pixels(batch, view.camera, environment, all, null);
                    assertEquals(new Difference(0, 0), difference(reference,
                          pixels(batch, view.camera, environment, all, null)), "Repeated reference must be deterministic");
                    byte[] combined = pixels(batch, view.camera, environment, all, aggregate);
                    Difference difference = difference(reference, combined);
                    System.out.printf("Prop batch pixels perspective=%s tilt=%.0f: %s%n", perspective, tilt, difference);
                    maximumChanged = Math.max(maximumChanged, difference.changedPixels());
                    maximumDelta = Math.max(maximumDelta, difference.maximumChannelDelta());
                }
            }
            assertEquals(49, aggregate.rebuilds(), "Camera motion must not rebuild the unchanged 7 by 7 spatial pages");
            List<Source> onePage = all.stream().filter(source -> source.page() == 0).toList();
            draw(batch, view.camera, environment, onePage, null);
            Map<Vertex, Integer> originalVertices = vertices(submitted);
            Material originalMaterial = submitted.first().material;
            draw(batch, view.camera, environment, onePage, aggregate);
            assertEquals(originalVertices, vertices(submitted), "Every indexed position, normal and color must stay bit-exact");
            assertEquals(1, submitted.size, "One compatible page has one draw");
            assertTrue(originalMaterial.same(submitted.first().material, true), "Copied material values must stay exact");

            profiler.enable();
            profiler.reset();
            draw(batch, view.camera, environment, all, null);
            int originalDraws = profiler.getDrawCalls();
            profiler.reset();
            draw(batch, view.camera, environment, all, aggregate);
            int combinedDraws = profiler.getDrawCalls();
            profiler.disable();
            System.out.printf("Prop batch draws: original=%d combined=%d%n", originalDraws, combinedDraws);
            assertEquals(375, originalDraws);
            assertEquals(49, combinedDraws);
            measure(batch, view.camera, environment, all, aggregate);
            GpuPropBatch crowded = new GpuPropBatch();
            try {
                // Dense authored geometry can exceed the unsigned-short index limit even inside one spatial page.
                List<Source> samePage = all.stream().map(source -> new Source(0, source.owner(), source.parts())).toList();
                byte[] reference = pixels(batch, view.camera, environment, samePage, null);
                assertEquals(new Difference(0, 0), difference(reference,
                      pixels(batch, view.camera, environment, samePage, crowded)), "Page splitting must preserve exact pixels");
                assertEquals(5, submitted.size, "283,500 vertices must split across five indexable meshes");
                int indices = 0;
                for (Renderable value : submitted) {
                    assertTrue(value.meshPart.mesh.getNumVertices() <= 65536, "Every mesh must retain 16-bit index safety");
                    indices += value.meshPart.size;
                }
                assertEquals(283500, indices, "Page splitting must preserve every triangle");
            } finally {
                crowded.dispose();
            }

            List<Source> subset = all.subList(90, 260);
            // Rows 6..17 cross two partial page rows; complete and invisible pages stay cached.
            measureChange("pan subset", batch, view.camera, environment, subset, aggregate, 63);
            List<Source> edited = new ArrayList<>(subset);
            edited.set(137 - 90, replacement);
            measureChange("edited chunk", batch, view.camera, environment, edited, aggregate, 64);
            List<Source> mixed = new ArrayList<>(edited);
            mixed.add(unbatched);
            mixed.add(transformed);
            measureChange("unchanged fallback", batch, view.camera, environment, mixed, aggregate, 64);
            measureChange("no visible props", batch, view.camera, environment, List.of(), aggregate, 64);
            measureChange("return to overview", batch, view.camera, environment, all, aggregate, 79);

            byte[] beforeDispose = pixels(batch, view.camera, environment, all, null);
            aggregate.dispose();
            aggregateDisposed = true;
            assertEquals(borrowed, managedMeshes(), "All aggregate buffers must be released; borrowed meshes must survive");
            assertEquals(new Difference(0, 0), difference(beforeDispose,
                  pixels(batch, view.camera, environment, all, null)), "Source caches still render after aggregate disposal");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertEquals(new Difference(0, 0), new Difference(maximumChanged, maximumDelta), "Aggregate pixels must remain exact");
        } finally {
            if (profiler.isEnabled()) { profiler.disable(); }
            if (!aggregateDisposed) { aggregate.dispose(); }
            batch.dispose();
            owned.forEach(source -> source.owner().dispose());
            fallback.dispose();
            large.dispose();
            small.dispose();
        }
    }

    private static void measureChange(String name, ModelBatch batch, Camera camera, Environment environment,
          List<Source> sources, GpuPropBatch aggregate, long rebuilds) {
        byte[] reference = pixels(batch, camera, environment, sources, null);
        assertEquals(new Difference(0, 0), difference(reference,
              pixels(batch, camera, environment, sources, null)), "Repeated reference must be deterministic");
        Gdx.gl.glFinish();
        long start = System.nanoTime();
        draw(batch, camera, environment, sources, aggregate);
        double elapsed = (System.nanoTime() - start) / 1e6;
        Gdx.gl.glFinish();
        Difference difference = difference(reference, capture(!sources.isEmpty()));
        System.out.printf("Prop batch %s: rebuild submission %.3f ms; %s%n", name, elapsed, difference);
        assertEquals(rebuilds, aggregate.rebuilds(), name);
        assertEquals(new Difference(0, 0), difference, name);
        draw(batch, camera, environment, sources, aggregate);
        assertEquals(rebuilds, aggregate.rebuilds(), "Settled frame must reuse " + name);
    }

    private static void measure(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          GpuPropBatch aggregate) {
        double[] original = new double[120], combined = new double[120];
        for (int sample = -24; sample < original.length; sample++) {
            for (int turn = 0; turn < 2; turn++) {
                boolean cached = ((sample + turn) & 1) == 0;
                Gdx.gl.glFinish();
                long start = System.nanoTime();
                draw(batch, camera, environment, sources, cached ? aggregate : null);
                double elapsed = (System.nanoTime() - start) / 1e6;
                if (sample >= 0) { (cached ? combined : original)[sample] = elapsed; }
            }
        }
        Arrays.sort(original);
        Arrays.sort(combined);
        System.out.printf("Prop batch warm/interleaved CPU submission median/p95: original %.3f/%.3f ms; combined %.3f/%.3f ms%n",
              original[60], original[114], combined[60], combined[114]);
    }

    private static void draw(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          GpuPropBatch aggregate) {
        ScreenUtils.clear(.04f, .06f, .08f, 1, true);
        batch.begin(camera);
        if (aggregate == null) {
            for (Source source : sources) { batch.render(source, environment); }
        } else {
            aggregate.begin();
            for (Source source : sources) { aggregate.add(source, source.page()); }
            aggregate.render(batch, environment);
        }
        batch.end();
    }

    private static byte[] pixels(ModelBatch batch, Camera camera, Environment environment, List<Source> sources,
          GpuPropBatch aggregate) {
        draw(batch, camera, environment, sources, aggregate);
        return capture(!sources.isEmpty());
    }

    private static byte[] capture(boolean visibleGeometry) {
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
        if (visibleGeometry) {
            int visible = 0;
            for (int i = 0; i < pixels.length; i += 4) {
                // Allow the implementation's rounding of the .04/.06/.08 clear color.
                if (Math.abs(Byte.toUnsignedInt(pixels[i]) - 10) > 2
                      || Math.abs(Byte.toUnsignedInt(pixels[i + 1]) - 15) > 2
                      || Math.abs(Byte.toUnsignedInt(pixels[i + 2]) - 20) > 2) { visible++; }
            }
            assertTrue(visible > 500, "Pixel comparison must contain substantial visible geometry: " + visible);
        }
        return pixels;
    }

    private record Difference(int changedPixels, int maximumChannelDelta) { }

    private record Vertex(int x, int y, int z, int nx, int ny, int nz, int color) { }

    /** Compare every referenced vertex, including multiplicity, independently of mesh/part packing and ordering. */
    private static Map<Vertex, Integer> vertices(Array<Renderable> renderables) {
        Map<Vertex, Integer> result = new HashMap<>();
        for (Renderable value : renderables) {
            Mesh mesh = value.meshPart.mesh;
            VertexAttributes attributes = mesh.getVertexAttributes();
            int stride = attributes.vertexSize / Float.BYTES;
            int position = attributes.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
            int normal = attributes.findByUsage(VertexAttributes.Usage.Normal).offset / Float.BYTES;
            int color = attributes.findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
            float[] data = new float[mesh.getNumVertices() * stride];
            short[] indices = new short[value.meshPart.size];
            mesh.getVertices(data);
            mesh.getIndices(value.meshPart.offset, value.meshPart.size, indices, 0);
            for (short index : indices) {
                int base = Short.toUnsignedInt(index) * stride;
                Vertex vertex = new Vertex(Float.floatToRawIntBits(data[base + position]),
                      Float.floatToRawIntBits(data[base + position + 1]), Float.floatToRawIntBits(data[base + position + 2]),
                      Float.floatToRawIntBits(data[base + normal]), Float.floatToRawIntBits(data[base + normal + 1]),
                      Float.floatToRawIntBits(data[base + normal + 2]), Float.floatToRawIntBits(data[base + color]));
                result.merge(vertex, 1, Integer::sum);
            }
        }
        return result;
    }

    private static Difference difference(byte[] a, byte[] b) {
        assertEquals(a.length, b.length);
        int changed = 0, maximum = 0;
        for (int i = 0; i < a.length; i += 4) {
            int delta = 0;
            for (int channel = 0; channel < 4; channel++) {
                delta = Math.max(delta, Math.abs(Byte.toUnsignedInt(a[i + channel]) - Byte.toUnsignedInt(b[i + channel])));
            }
            if (delta != 0) { changed++; maximum = Math.max(maximum, delta); }
        }
        return new Difference(changed, maximum);
    }

    private static Source source(Model model, int index, float edit) {
        // Spread 375 occupied chunks across the 25 by 25 chunk grid of a 200 by 200 board.
        int row = index / 15, column = (index % 15 * 5 / 3 + row % 5) % 25;
        ModelInstance instance = new ModelInstance(model);
        instance.transform.setToTranslation(column * 4, -row * 4, edit)
              .rotate(Vector3.Z, index * 17).rotate(Vector3.X, index % 11 * 3)
              .scale(1 + index % 3 * .2f, .7f + index % 5 * .1f, 1.2f + edit);
        ModelCache cache = new ModelCache(new ModelCache.Sorter(), new ModelCache.TightMeshPool());
        cache.begin();
        cache.add(instance);
        cache.end();
        Array<Renderable> parts = new Array<>();
        cache.getRenderables(parts, null);
        int page = row / GpuPropBatch.CHUNKS_PER_PAGE * 7 + column / GpuPropBatch.CHUNKS_PER_PAGE;
        return new Source(page, cache, parts);
    }

    private static Model model(int sectors, Material material) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder mesh = builder.part("prop", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
              | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked, material);
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < sectors; column++) {
                var a = vertex(row, column, sectors);
                var b = vertex(row, column + 1, sectors);
                var c = vertex(row + 1, column + 1, sectors);
                var d = vertex(row + 1, column, sectors);
                mesh.triangle(a, d, c);
                mesh.triangle(c, b, a);
            }
        }
        return builder.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(int row, int column, int sectors) {
        double latitude = Math.PI * row / 6, longitude = 2 * Math.PI * column / sectors;
        Vector3 normal = new Vector3((float) (Math.sin(latitude) * Math.cos(longitude)),
              (float) (Math.sin(latitude) * Math.sin(longitude)), (float) Math.cos(latitude)).nor();
        return new MeshPartBuilder.VertexInfo().setPos(normal).setNor(normal)
              .setCol(.35f + row * .07f, .45f + column % 3 * .1f, .3f, 1);
    }

    @SuppressWarnings("unchecked")
    private static Set<Mesh> managedMeshes() throws Exception {
        Field field = Mesh.class.getDeclaredField("meshes");
        field.setAccessible(true);
        Array<Mesh> meshes = ((Map<com.badlogic.gdx.Application, Array<Mesh>>) field.get(null)).get(Gdx.app);
        Set<Mesh> result = new HashSet<>();
        if (meshes != null) { for (Mesh mesh : meshes) { result.add(mesh); } }
        assertTrue(!result.isEmpty(), "The native fixture must own real mesh resources");
        return result;
    }
}
