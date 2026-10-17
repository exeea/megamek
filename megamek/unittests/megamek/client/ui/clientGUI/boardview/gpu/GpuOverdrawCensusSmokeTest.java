/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.IntArray;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDepthPrepassBaselineSmokeTest.View;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.ARBPipelineStatisticsQuery;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;

/**
 * Which draw categories shade far more fragments than they show, with the depth pre-pass off: the candidates for depth
 * twins beyond the trees. Each draw of the terrain's batch (both flushes: the opaque world, then scars, ground cover,
 * overlays, decals and paint) is counted by category with GL_SAMPLES_PASSED and the fragment-shader invocation query.
 * A second, "oracle" frame first lays every opaque draw's depth with colour writes off, then draws the same flush
 * testing GL_EQUAL without writing depth: what each category shades where it is finally seen, an upper bound on what a
 * twin saves. Overdraw is the first frame's shaded fragments over the oracle's. Measurement only: a test sorter wraps
 * the terrain batch's own; nothing in the renderer changes. Report: build/gpu-board-review/overdraw-census.txt.
 */
@Tag("on-demand")
class GpuOverdrawCensusSmokeTest {
    private static final List<String> CATEGORIES = List.of("relief", "trees", "impostors", "grass blades",
          "biome plants", "scatter", "buildings", "props", "other");
    private static final int RELIEF = 0, TREES = 1, IMPOSTORS = 2, GRASS = 3, BIOME = 4, SCATTER = 5, BUILDINGS = 6,
          PROPS = 7, OTHER = 8;
    private static final File BOARDS = new File("data/boards");
    private static final Set<String> BUILDING_MATERIALS = Set.of("roof", "wall", "shell");

    @Test
    void countsShadedFragmentsPerCategory() throws Exception {
        BoardScene grassland = GpuDepthPrepassBaselineSmokeTest.scene(new File(BOARDS,
              "AGoAC Maps/16x17 Grassland 3.board"));
        BoardScene city = GpuDepthPrepassBaselineSmokeTest.scene(new File(BOARDS,
              "unofficial/VictorMorson/48x48.board"));
        BoardScene valencia = GpuDepthPrepassBaselineSmokeTest.scene(new File(BOARDS,
              "unofficial/VictorMorson/128x192 Texlos - Livius - Valencia_City.board"));
        BoardScene forest = GpuForestBenchmarkSmokeTest.forest();
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1920, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                Set<GpuTerrain.Prepass> previous = GpuTerrain.depthPrepass;
                try {
                    var capabilities = GL.getCapabilities();
                    boolean statistics = capabilities.OpenGL46 || capabilities.GL_ARB_pipeline_statistics_query;
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; window ")
                          .append(Gdx.graphics.getBackBufferWidth()).append(" x ")
                          .append(Gdx.graphics.getBackBufferHeight())
                          .append(statistics ? "; fragments shaded from pipeline statistics"
                                : "; NO pipeline statistics: samples written only").append("; depth pre-pass off\n");
                    GpuTerrain.depthPrepass = EnumSet.noneOf(GpuTerrain.Prepass.class);
                    census(grassland, List.of(View.fitted("grassland 3 fitted"),
                          View.at("grassland 3 middle", .5f, centre(grassland))), statistics, report);
                    census(city, List.of(View.fitted("48x48 city fitted"),
                          View.at("48x48 city middle", .5f, centre(city))), statistics, report);
                    census(valencia, List.of(View.at("valencia centre", 1, centre(valencia)),
                          View.at("valencia centre close", .5f, centre(valencia))), statistics, report);
                    census(forest, List.of(GpuDepthPrepassBaselineSmokeTest.forestViews(forest).get(1)), statistics,
                          report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuTerrain.depthPrepass = previous;
                    BoardCamera.renderScale = 1;
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.createDirectories(GpuDepthPrepassBaselineSmokeTest.OUTPUT.toPath());
        Files.writeString(new File(GpuDepthPrepassBaselineSmokeTest.OUTPUT, "overdraw-census.txt").toPath(), report);
        if (failure.get() != null) { throw new AssertionError("Overdraw census", failure.get()); }
    }

    private static Coords centre(BoardScene scene) { return new Coords(scene.width() / 2, scene.height() / 2); }

    /** One board: each view settled, then a counted frame and an oracle frame. */
    private static void census(BoardScene scene, List<View> views, boolean statistics, StringBuilder report)
          throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(GpuDepthPrepassBaselineSmokeTest.NOON);
        ModelBatch batch = (ModelBatch) GpuDepthPrepassBenchmarkSmokeTest.field(terrain, "batch");
        Field sorterField = ModelBatch.class.getDeclaredField("sorter");
        sorterField.setAccessible(true);
        var sorter = (RenderableSorter) sorterField.get(batch);
        var census = new Census(sorter, terrain, batch, statistics);
        sorterField.set(batch, census);
        try {
            var camera = new BoardCamera();
            GpuDepthPrepassBaselineSmokeTest.prepare(terrain, frame, camera, scene);
            for (View view : views) {
                GpuDepthPrepassBaselineSmokeTest.show(view, scene, camera);
                GpuDepthPrepassBaselineSmokeTest.settle(terrain, frame, camera, scene);
                long[][] counted = census.frame(false, () -> frame.render(terrain, camera, scene));
                long covered = covered();
                long[][] oracle = census.frame(true, () -> frame.render(terrain, camera, scene));
                report(view.name(), counted, oracle, covered, census.materials, report);
            }
        } finally {
            sorterField.set(batch, sorter);
            census.dispose();
            frame.dispose();
            terrain.dispose();
            BoardCamera.renderScale = 1;
        }
    }

    /** Window pixels the scene covers: depth below the far plane after the composite. */
    private static long covered() {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        FloatBuffer depth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        long covered = 0;
        while (depth.hasRemaining()) { if (depth.get() < 1) { covered++; } }
        return covered;
    }

    private static void report(String view, long[][] counted, long[][] oracle, long covered,
          Map<String, Map<String, Integer>> materials, StringBuilder report) {
        report.append(String.format(Locale.ROOT, "%n%s: %d covered pixels%n", view, covered));
        report.append(String.format(Locale.ROOT, "  %-13s %7s %12s %10s %12s %10s %9s %8s%n", "category", "draws",
              "shaded", "per pixel", "shown", "overdraw", "written", "share"));
        long total = 0, totalShown = 0;
        for (int c = 0; c < CATEGORIES.size(); c++) { total += counted[1][c]; totalShown += oracle[1][c]; }
        for (int c = 0; c < CATEGORIES.size(); c++) {
            if (counted[0][c] == 0) { continue; }
            report.append(String.format(Locale.ROOT, "  %-13s %7d %12d %10.3f %12d %10s %9.3f %7.1f%%%n",
                  CATEGORIES.get(c), counted[0][c], counted[1][c], counted[1][c] / (double) covered, oracle[1][c],
                  oracle[1][c] == 0 ? "-" : String.format(Locale.ROOT, "%.2fx", counted[1][c] / (double) oracle[1][c]),
                  counted[2][c] / (double) covered, total == 0 ? 0 : 100.0 * counted[1][c] / total));
        }
        report.append(String.format(Locale.ROOT, "  %-13s %7s %12d %10.3f %12d %10s%n", "all", "", total,
              total / (double) covered, totalShown, totalShown == 0 ? "-"
                    : String.format(Locale.ROOT, "%.2fx", total / (double) totalShown)));
        // What each category held, so its classification can be checked.
        materials.forEach((category, ids) -> report.append("  ").append(category).append(": ").append(ids)
              .append('\n'));
        materials.clear();
    }

    /**
     * The terrain batch's sorter, wrapped: after its own sort, each draw's shader is a counting wrapper for this
     * frame. Shaders are unwrapped before every sort, so the terrain's own sorting and grouping see their own shaders.
     */
    private static final class Census implements RenderableSorter {
        private static final DepthTestAttribute EQUAL = new DepthTestAttribute(GL20.GL_EQUAL, false);
        private final RenderableSorter sorter;
        private final GpuTerrain terrain;
        private final ModelBatch batch;
        private final boolean statistics;
        private final Map<Shader, Counted> wrappers = new IdentityHashMap<>();
        /** Query objects for reuse, one pool per target: a query object keeps the target it was first used with. */
        private final IntArray samplesPool = new IntArray(), shadedPool = new IntArray();
        private final List<int[]> pending = new ArrayList<>();
        /** Per category, the distinct material ids drawn and how often, for the report. */
        final Map<String, Map<String, Integer>> materials = new LinkedHashMap<>();
        private final Texture scatter;
        private Set<Mesh> props;
        private boolean active;
        private boolean oracle;

        Census(RenderableSorter sorter, GpuTerrain terrain, ModelBatch batch, boolean statistics)
              throws ReflectiveOperationException {
            this.sorter = sorter;
            this.terrain = terrain;
            this.batch = batch;
            this.statistics = statistics;
            scatter = ((GpuAssets) GpuDepthPrepassBenchmarkSmokeTest.field(terrain, "assets")).scatter();
        }

        /** One counted frame: per category, draws, fragments shaded and samples written. */
        long[][] frame(boolean oracle, Runnable render) {
            this.oracle = oracle;
            active = true;
            try { render.run(); } finally { active = false; }
            Gdx.gl.glFinish();
            long[][] result = new long[3][CATEGORIES.size()];
            for (int[] draw : pending) {
                result[0][draw[0]]++;
                result[2][draw[0]] += ARBTimerQuery.glGetQueryObjectui64(draw[1], GL15.GL_QUERY_RESULT);
                samplesPool.add(draw[1]);
                if (draw[2] != 0) {
                    result[1][draw[0]] += ARBTimerQuery.glGetQueryObjectui64(draw[2], GL15.GL_QUERY_RESULT);
                    shadedPool.add(draw[2]);
                }
            }
            pending.clear();
            if (!statistics) { result[1] = result[2].clone(); }
            return result;
        }

        @Override
        public void sort(Camera camera, Array<Renderable> renderables) {
            for (Renderable renderable : renderables) {
                if (renderable.shader instanceof Counted counted) { renderable.shader = counted.inner; }
            }
            sorter.sort(camera, renderables);
            if (!active) { return; }
            props = null;
            if (oracle) { prime(camera, renderables); }
            for (Renderable renderable : renderables) {
                renderable.shader = wrappers.computeIfAbsent(renderable.shader, inner -> new Counted(inner, this));
            }
        }

        /** The oracle's depth: every opaque draw of this flush, in its order, with colour writes off. */
        private void prime(Camera camera, Array<Renderable> renderables) {
            RenderContext context = batch.getRenderContext();
            Gdx.gl.glColorMask(false, false, false, false);
            Shader current = null;
            try {
                for (Renderable renderable : renderables) {
                    if (blended(renderable.material)) { continue; }
                    if (current != renderable.shader) {
                        if (current != null) { current.end(); }
                        current = renderable.shader;
                        current.begin(camera, context);
                    }
                    current.render(renderable);
                }
            } finally {
                if (current != null) { current.end(); }
                Gdx.gl.glColorMask(true, true, true, true);
            }
        }

        void render(Shader inner, Renderable renderable) {
            if (!active) {
                inner.render(renderable);
                return;
            }
            int category = classify(renderable);
            materials.computeIfAbsent(CATEGORIES.get(category), key -> new LinkedHashMap<>())
                  .merge(String.valueOf(renderable.material.id), 1, Integer::sum);
            boolean equal = oracle && !blended(renderable.material)
                  && !renderable.material.has(DepthTestAttribute.Type);
            int samples = query(samplesPool), shaded = statistics ? query(shadedPool) : 0;
            if (equal) { renderable.material.set(EQUAL); }
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, samples);
            if (shaded != 0) {
                GL15.glBeginQuery(ARBPipelineStatisticsQuery.GL_FRAGMENT_SHADER_INVOCATIONS_ARB, shaded);
            }
            try {
                inner.render(renderable);
            } finally {
                if (shaded != 0) { GL15.glEndQuery(ARBPipelineStatisticsQuery.GL_FRAGMENT_SHADER_INVOCATIONS_ARB); }
                GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                if (equal) { renderable.material.remove(DepthTestAttribute.Type); }
            }
            pending.add(new int[] { category, samples, shaded });
        }

        /**
         * A draw's category from its material's markers. The relief and the scatter plants share the sculpt material:
         * a sculpt draw whose mesh belongs to the prop pages (props, scatter, routes) is scatter.
         */
        private int classify(Renderable renderable) {
            Attributes material = renderable.material;
            if (has(material, "boardImpostor")) { return IMPOSTORS; }
            if (has(material, "boardFoliage")) { return TREES; }
            if (has(material, "boardVegetationWind")) { return GRASS; }
            if (has(material, "boardBiomeVegetation")) { return BIOME; }
            if (has(material, "boardBuildingCutaway")) { return BUILDINGS; }
            TextureAttribute diffuse = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
            if (diffuse != null && diffuse.textureDescription.texture == scatter) { return SCATTER; }
            if (has(material, "boardSculpt") || has(material, "boardGround") || has(material, "boardPit")
                  || has(material, "boardMagmaSurface") || has(material, "boardSurfaceBlend")
                  || has(material, "boardBankTurf")) {
                return props().contains(renderable.meshPart.mesh) ? SCATTER : RELIEF;
            }
            if (has(material, "boardModelSurface") || has(material, "boardInstanced")
                  || props().contains(renderable.meshPart.mesh)) {
                // The building models' own materials (models/board/buildings); industrial structures and objects are
                // props.
                return BUILDING_MATERIALS.contains(renderable.material.id) ? BUILDINGS : PROPS;
            }
            return OTHER;
        }

        /** The meshes of the prop pages this flush draws from (sources and their combined copies). */
        @SuppressWarnings("unchecked")
        private Set<Mesh> props() {
            if (props == null) {
                props = Collections.newSetFromMap(new IdentityHashMap<>());
                try {
                    Object propBatch = GpuDepthPrepassBenchmarkSmokeTest.field(terrain, "propBatch");
                    for (GpuMeshPage page : ((Map<Integer, GpuMeshPage>) GpuDepthPrepassBenchmarkSmokeTest
                          .field(propBatch, "pages")).values()) {
                        for (Renderable part : page.current) { props.add(part.meshPart.mesh); }
                        for (Renderable part : page.cached) { props.add(part.meshPart.mesh); }
                    }
                } catch (ReflectiveOperationException inaccessible) {
                    throw new IllegalStateException(inaccessible);
                }
            }
            return props;
        }

        private static boolean has(Attributes material, String alias) {
            long type = Attribute.getAttributeType(alias);
            return type != 0 && material.has(type);
        }

        private static boolean blended(Attributes material) {
            var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
            return blend != null && blend.blended;
        }

        private static int query(IntArray pool) { return pool.isEmpty() ? GL15.glGenQueries() : pool.pop(); }

        void dispose() {
            for (IntArray pool : List.of(samplesPool, shadedPool)) {
                for (int i = 0; i < pool.size; i++) { GL15.glDeleteQueries(pool.get(i)); }
                pool.clear();
            }
        }
    }

    /** A shader that counts its draws for the census, and only then. */
    private static final class Counted implements Shader {
        private final Shader inner;
        private final Census census;

        Counted(Shader inner, Census census) {
            this.inner = inner;
            this.census = census;
        }

        @Override public void init() { inner.init(); }

        @Override
        public int compareTo(Shader other) { return inner.compareTo(other instanceof Counted c ? c.inner : other); }

        @Override public boolean canRender(Renderable instance) { return inner.canRender(instance); }

        @Override public void begin(Camera camera, RenderContext context) { inner.begin(camera, context); }

        @Override public void render(Renderable renderable) { census.render(inner, renderable); }

        @Override public void end() { inner.end(); }

        /** The terrain's shader provider owns the shader. */
        @Override public void dispose() { }
    }
}
