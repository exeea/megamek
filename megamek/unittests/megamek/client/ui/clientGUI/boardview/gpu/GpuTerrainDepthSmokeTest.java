/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Borrowed depth ranges must preserve cuts, state and index gaps while reducing actual native draw calls. */
@Tag("on-demand")
class GpuTerrainDepthSmokeTest {
    @Test
    void contiguousOpaqueRangesShareDepthDrawsWithoutChangingPixelsOrMeshes() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(900, 640);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkDepthRanges(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Borrowed terrain depth ranges", failure.get()); }
    }

    private static void checkDepthRanges() {
        Texture cutout = cutout();
        Model first = model(cutout, true), second = model(cutout, false);
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        try {
            List<ModelInstance> instances = new ArrayList<>(List.of(new ModelInstance(first), new ModelInstance(second)));
            instances.get(1).transform.setToTranslation(-5, 0, 1);
            RenderableProvider original = (out, pool) -> instances.forEach(instance -> instance.getRenderables(out, pool));
            Array<Renderable> before = collect(original);
            assertEquals(22, before.size);
            String managed = Mesh.getManagedStatus();
            List<MeshData> data = List.of(data(first.meshes.first()), data(second.meshes.first()));
            GpuTerrainDepth merged = new GpuTerrainDepth(instances);
            Array<Renderable> ranges = collect(merged);
            assertEquals(16, ranges.size, "Only six redundant adjacent draws are removed; cutouts and gaps stay separate");
            assertEquals(managed, Mesh.getManagedStatus(), "Depth ranges must allocate no GPU meshes");
            for (Renderable range : ranges) {
                assertTrue(range.meshPart.mesh == first.meshes.first() || range.meshPart.mesh == second.meshes.first());
                boolean borrowed = false;
                for (Renderable source : before) { borrowed |= source.material == range.material; }
                assertTrue(borrowed, "Materials are borrowed too");
            }
            assertSame(before.first().material, ranges.first().material);
            assertEquals(12, ranges.first().meshPart.size);
            assertTrue(ranges.first().meshPart.halfExtents.x > before.first().meshPart.halfExtents.x,
                  "A combined range keeps the union of its original bounds");

            Camera ortho = new OrthographicCamera(18, 12);
            Camera perspective = new PerspectiveCamera(50, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            for (Camera camera : List.of(ortho, perspective)) {
                camera.position.set(2, 2, 20);
                camera.lookAt(2, 2, 0);
                camera.near = 1; camera.far = 40;
                camera.update();
                for (boolean shadows : new boolean[] { false, true }) {
                    String fragment = shadows ? Gdx.files.classpath(
                          "megamek/client/ui/clientGUI/boardview/gpu/shadow-depth.frag").readString() : null;
                    ModelBatch pass = new ModelBatch(GpuTreeInstances.depthProvider(new DepthShader.Config(null, fragment)),
                          new GpuOpaqueSorter());
                    try {
                        frame(pass, camera, original); frame(pass, camera, merged);
                        profiler.enable();
                        profiler.reset();
                        byte[] expected = frame(pass, camera, original);
                        int oldDraws = profiler.getDrawCalls(), oldShaders = profiler.getShaderSwitches();
                        profiler.reset();
                        byte[] actual = frame(pass, camera, merged);
                        assertEquals(oldDraws - 6, profiler.getDrawCalls(), "Contiguous depth ranges reduce real draws");
                        assertTrue(profiler.getShaderSwitches() <= oldShaders, "Borrowing materials adds no shader switches");
                        profiler.disable();
                        assertArrayEquals(expected, actual, "Depth pixels, cutout holes and culling remain exact");
                        assertTrue(varied(actual) > 500, "Depth comparison must contain visible geometry");
                        System.out.printf("Terrain depth %s/%s: draws %d -> %d; identical pixels%n",
                              camera.getClass().getSimpleName(), shadows ? "shadow" : "camera", oldDraws, oldDraws - 6);
                    } finally {
                        if (profiler.isEnabled()) { profiler.disable(); }
                        pass.dispose();
                    }
                }
            }
            Array<Renderable> untouched = collect(original);
            for (int i = 0; i < before.size; i++) {
                assertEquals(before.get(i).meshPart.offset, untouched.get(i).meshPart.offset);
                assertEquals(before.get(i).meshPart.size, untouched.get(i).meshPart.size, "Source mesh parts stay unchanged");
            }
            for (int i = 0; i < 2; i++) {
                Mesh mesh = i == 0 ? first.meshes.first() : second.meshes.first();
                MeshData after = data(mesh);
                assertArrayEquals(data.get(i).vertices(), after.vertices());
                assertArrayEquals(data.get(i).indices(), after.indices());
            }
            Renderable cached = ranges.first();
            cached.environment = new Environment();
            // A completed ModelBatch leaves its shader on cached renderables; a new pass must choose its own.
            Array<Renderable> again = collect(merged);
            assertSame(cached, again.first());
            assertNull(cached.environment); assertNull(cached.shader);
            instances.set(1, new ModelInstance(second));
            instances.get(1).transform.setToTranslation(-4, 1, 2);
            Array<Renderable> replacement = collect(new GpuTerrainDepth(instances));
            assertEquals(-4, replacement.peek().worldTransform.val[com.badlogic.gdx.math.Matrix4.M03]);
            assertEquals(-5, again.peek().worldTransform.val[com.badlogic.gdx.math.Matrix4.M03],
                  "Replacing a chunk snapshot cannot mutate another chunk's cached transforms");
            assertEquals(managed, Mesh.getManagedStatus());
            assertTrue(Gdx.gl.glIsTexture(cutout.getTextureObjectHandle()));
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            if (profiler.isEnabled()) { profiler.disable(); }
            first.dispose(); second.dispose(); cutout.dispose();
        }
    }

    private static Array<Renderable> collect(RenderableProvider provider) {
        Array<Renderable> result = new Array<>();
        provider.getRenderables(result, new Pool<>() {
            @Override
            protected Renderable newObject() { return new Renderable(); }
        });
        return result;
    }

    private static byte[] frame(ModelBatch pass, Camera camera, RenderableProvider provider) {
        ScreenUtils.clear(1, 1, 1, 1, true);
        pass.begin(camera); pass.render(provider); pass.end();
        return ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
    }

    private static int varied(byte[] pixels) {
        int result = 0;
        for (int i = 0; i < pixels.length; i += 4) {
            if (pixels[i] != (byte) 255 || pixels[i + 1] != (byte) 255 || pixels[i + 2] != (byte) 255) { result++; }
        }
        return result;
    }

    private record MeshData(float[] vertices, short[] indices) { }

    private static MeshData data(Mesh mesh) {
        float[] vertices = new float[mesh.getNumVertices() * mesh.getVertexSize() / Float.BYTES];
        short[] indices = new short[mesh.getNumIndices()];
        mesh.getVertices(vertices); mesh.getIndices(indices);
        return new MeshData(vertices, indices);
    }

    private static Texture cutout() {
        Pixmap pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        try {
            pixels.setColor(Color.WHITE); pixels.fill();
            pixels.drawPixel(0, 0, 0); pixels.drawPixel(1, 1, 0);
            return new Texture(pixels);
        } finally { pixels.dispose(); }
    }

    private static Model model(Texture texture, boolean mixed) {
        int count = mixed ? 20 : 3;
        float[] vertices = new float[count * 4 * 8];
        short[] indices = new short[count * 6];
        int[] winding = { 0, 1, 2, 2, 3, 0 };
        for (int part = 0; part < count; part++) {
            for (int corner = 0; corner < 4; corner++) {
                int offset = (part * 4 + corner) * 8;
                vertices[offset] = part % 6 * 1.5f + (corner == 1 || corner == 2 ? 1 : 0);
                vertices[offset + 1] = part / 6 * 1.5f + (corner >= 2 ? 1 : 0);
                vertices[offset + 2] = part * .01f;
                vertices[offset + 5] = 1;
                vertices[offset + 6] = corner == 1 || corner == 2 ? 1 : 0;
                vertices[offset + 7] = corner >= 2 ? 1 : 0;
            }
            for (int index = 0; index < 6; index++) { indices[part * 6 + index] = (short) (part * 4 + winding[index]); }
        }
        Mesh mesh = new Mesh(true, count * 4, count * 6,
              VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0));
        mesh.setVertices(vertices); mesh.setIndices(indices);
        Model model = new Model();
        model.meshes.add(mesh); model.manageDisposable(mesh);
        for (int part = 0; part < count; part++) {
            Material material = new Material(ColorAttribute.createDiffuse(part % 2 == 0 ? Color.RED : Color.BLUE));
            if (mixed) {
                if (part == 1) { material.set(TextureAttribute.createDiffuse(texture)); }
                if (part == 2 || part == 3) { material.set(IntAttribute.createCullFace(GL20.GL_NONE)); }
                if (part == 4 || part == 5) { material.set(IntAttribute.createCullFace(GL20.GL_FRONT)); }
                if (part == 6 || part == 7) { material.set(new DepthTestAttribute(GL20.GL_LEQUAL, .1f, .9f, true)); }
                if (part >= 13 && part <= 15) {
                    material.set(TextureAttribute.createDiffuse(texture), FloatAttribute.createAlphaTest(.5f));
                    if (part >= 14) { material.set(new BlendingAttribute(part == 14 ? 1f : .1f)); }
                }
                if (part == 16 || part == 17) { material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false)); }
                if (part >= 18) { material.set(new DepthTestAttribute(GL20.GL_ALWAYS)); }
            }
            MeshPart meshPart = new MeshPart("part-" + part, mesh, part * 6, 6, GL20.GL_TRIANGLES);
            meshPart.update();
            Node node = new Node();
            node.id = "node-" + part;
            if (mixed && part == 11) { node.translation.z = .4f; }
            NodePart nodePart = new NodePart(meshPart, material);
            nodePart.enabled = !mixed || part != 9;
            node.parts.add(nodePart);
            model.nodes.add(node); model.meshParts.add(meshPart); model.materials.add(material);
        }
        model.calculateTransforms();
        return model;
    }
}
