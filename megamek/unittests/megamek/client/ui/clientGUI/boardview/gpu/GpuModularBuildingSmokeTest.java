/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GLB import, native lifetime, terrain placement, cutaway and LOD-independent picking. */
@Tag("on-demand")
class GpuModularBuildingSmokeTest {
    @Test
    void assembledBuildingsSurviveOriginOffsetsEditsAndCameraChanges() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkKit();
                    checkLightBuilding();
                    GpuResourcesSmokeTest.checkInteriorCourtyard(1);
                    GpuResourcesSmokeTest.checkInteriorCourtyard(BoardGeometry.MODEL_LEVEL_HEIGHT);
                    checkTerrain();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Modular building review", failure.get()); }
    }

    private static void checkKit() {
        var textures = new HashMap<String, Texture>();
        var file = GpuBuildingTest.file();
        GpuBuilding kit = new GpuBuilding(file, file.parent().file().toPath(), data -> {
            // Also move vertices, so deriving placement cannot accidentally depend on object origins alone.
            for (var mesh : data.meshes) {
                for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                    mesh.vertices[i] += 71;
                    mesh.vertices[i + 1] -= 23;
                    mesh.vertices[i + 2] += 43;
                }
            }
            for (var node : data.nodes) { node.translation.add(900, -400, 257); }
            return ModelTextures.create(data, textures, path -> { throw new AssertionError("Expected embedded textures"); });
        });
        try {
            var five = kit.assemble(5, 7);
            assertSame(five, kit.assemble(5, 7), "Repeated loads/edits reuse compact recipes");
            var another = kit.assemble(5, 19);
            assertSame(five.interior(), another.interior(), "Variants share the same volume's interior");
            for (int count : new int[] { 1, 5, 10 }) {
                var assembly = kit.assemble(count, 7);
                assertEquals(count + 1, assembly.modules().length(), "Only module indices are cached, with implicit offsets");
                for (int lod = 0; lod < assembly.lodCount(); lod++) {
                    ModelInstance instance = assembly.instance(lod);
                    assertSame(assembly.model(lod), instance.model, "No expanded model per combination");
                    assertEquals(count + 1, instance.nodes.size);
                    for (int level = 0; level <= count; level++) {
                        var bounds = instance.nodes.get(level).calculateBoundingBox(new BoundingBox());
                        assertEquals(level * 18, bounds.min.z, .001f, "Saved display offsets must be discarded");
                        assertEquals(0, bounds.getCenter(new Vector3()).x, .001f);
                        assertEquals(0, bounds.getCenter(new Vector3()).y, .001f);
                        if (level < count) { assertEquals(18, bounds.getDepth(), .001f); }
                    }
                    for (var node : instance.nodes) {
                        for (var part : node.parts) {
                            assertTrue(instance.model.meshes.contains(part.meshPart.mesh, true), "Native buffers are shared");
                        }
                    }
                }
                var floors = new ModelInstance(assembly.interior(), "floors").calculateBoundingBox(new BoundingBox());
                assertEquals(count == 1 ? .036f : (count - 1) * 18, floors.max.z, .001f);
                var struts = new ModelInstance(assembly.interior(), "struts").calculateBoundingBox(new BoundingBox());
                assertEquals(count * 18, struts.getDepth(), .001f);
                var triangles = GpuTerrain.triangles(assembly.interior());
                assertFalse(Intersector.intersectRayTriangles(new Ray(new Vector3(1, -33, 250), new Vector3(0, 0, -1)),
                      triangles, new Vector3()), "The front notch remains empty at every floor");
                assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(-12, 0, 250), new Vector3(0, 0, -1)),
                      triangles, new Vector3()));
                Vector3 hit = new Vector3();
                assertTrue(assembly.hit(new Ray(new Vector3(0, 0, 250), new Vector3(0, 0, -1)), hit));
                assertEquals(count * 18 + .65f, hit.z, .002f, "LOD0 roof picking follows the translated stack");
            }
            var mesh = five.model(0).meshes.first();
            kit.retain(Set.of(five));
            assertEquals(1, kit.assemblyCount());
            assertEquals(1, kit.interiorCount());
            assertSame(mesh, kit.assemble(10, 3).model(0).meshes.first(), "Every height shares module buffers");
            kit.retain(Set.of());
            assertEquals(0, kit.assemblyCount());
            assertEquals(0, kit.interiorCount());
            assertSame(mesh, kit.assemble(1, 9).model(0).meshes.first(), "An edit reuses the loaded kit");
        } finally {
            kit.dispose();
            textures.values().forEach(Texture::dispose);
        }
    }

    private static void checkLightBuilding() {
        var assets = new GpuAssets();
        try {
            var building = assets.building("buildings/saxarba/building_light/building_light_00", 5, 0);
            assertNotNull(building);
            var floors = new ModelInstance(building.interior(), "floors").calculateBoundingBox(new BoundingBox());
            assertEquals(26, floors.getWidth(), .01f, "Floors stop at the external walls, before the roof border");
            assertEquals(50, floors.getHeight(), .01f);
            var struts = new ModelInstance(building.interior(), "struts").calculateBoundingBox(new BoundingBox());
            assertTrue(floors.min.x <= struts.min.x && floors.max.x >= struts.max.x);
            assertTrue(floors.min.y <= struts.min.y && floors.max.y >= struts.max.y);
        } finally { assets.dispose(); }
    }

    private static BoardScene scene(int middleHeight, String asset) {
        return BoardSurfaceBlendTest.scene(coords -> {
            int levels = coords.equals(new Coords(2, 4)) ? 1 : coords.equals(new Coords(4, 4)) ? middleHeight
                  : coords.equals(new Coords(6, 4)) ? 10 : 0;
            var tile = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            int type = coords.getX() == 2 ? Terrains.INDUSTRIAL
                  : coords.getX() == 4 ? Terrains.BUILDING : Terrains.FUEL_TANK;
            var hex = new Hex(0);
            hex.addTerrain(new Terrain(type, type == Terrains.INDUSTRIAL ? levels : 1));
            if (type != Terrains.INDUSTRIAL) {
                hex.addTerrain(new Terrain(type == Terrains.BUILDING ? Terrains.BLDG_ELEV : Terrains.FUEL_TANK_ELEV, levels));
            }
            return new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), tile.ground(), null, null, null, null,
                  levels == 0 ? List.of() : BoardFeatures.capture(hex, coords, Map.of(type, asset)),
                  List.of(), BoardLiquid.NONE, null, true);
        });
    }

    private static void checkTerrain() throws Exception {
        String fallback = "buildings/saxarba/building_light/building_light_42";
        GpuAssets assets = new GpuAssets();
        try {
            assertNull(assets.building(fallback, 5, 4));
            assertNotNull(assets.model(fallback), "Legacy fallback still loads");
            assertNotNull(assets.model(GpuBuildingTest.ASSET), "The custom kit must win even when a board model exists");
        } finally { assets.dispose(); }
        var terrain = new GpuTerrain();
        var settings = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
              0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
        var frame = new GpuReviewFrame(settings);
        var camera = new BoardCamera();
        camera.resize(1280, 960);
        BoardScene scene = scene(5, GpuBuildingTest.ASSET);
        var tuning = BoardGeometry.tuning();
        try {
            BoardScene legacy = scene(5, fallback);
            terrain.update(legacy);
            for (int x : new int[] { 2, 4, 6 }) {
                var coords = new Coords(x, 4);
                assertEquals(legacy.tile(coords).features().getFirst().height() * BoardGeometry.level(),
                      terrain.roofBounds(coords).getDepth(), .02f, "All structure types keep their board-model fallback");
            }
            checkFloorCutaways(terrain, legacy, frame, camera);
            terrain.update(scene);
            checkFloorCutaways(terrain, scene, frame, camera);
            camera.setIsometric(true);
            camera.fit(scene);
            camera.zoom(.55f);
            camera.center(BoardGeometry.center(new Coords(4, 4), 2));
            terrain.animate(0, List.of());
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
            long uploads = terrain.treeInstanceUploads();
            assertTrue(uploads > 0, "Building modules use the existing GPU instancing path");
            long bytes = terrain.treeGeometryBytes();
            frame.render(terrain, camera, scene);
            assertEquals(uploads, terrain.treeInstanceUploads(), "An unchanged frame uploads no module instances");
            assertEquals(bytes, terrain.treeGeometryBytes(), "An unchanged frame allocates no module buffers");
            File output = new File(System.getProperty("megamek.gpu.screenshots"), "modular-buildings");
            assertTrue(output.isDirectory() || output.mkdirs());
            GpuReviewFrame.save(new File(output, "one-five-ten-levels.png"));
            checkPicking(terrain, scene);
            camera.zoom(8);
            for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
            GpuReviewFrame.save(new File(output, "distant-shells.png"));
            checkPicking(terrain, scene);
            Vector3 occupant = BoardGeometry.center(new Coords(4, 4), 1);
            Model marker = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createBox(12, 12, 12,
                  new com.badlogic.gdx.graphics.g3d.Material(), com.badlogic.gdx.graphics.VertexAttributes.Usage.Position
                        | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
            try {
                terrain.animate(0, List.of(new ModelInstance(marker, occupant)), .15f);
                for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                GpuReviewFrame.save(new File(output, "distant-cutaway.png"));
                camera.zoom(.125f);
                for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                GpuReviewFrame.save(new File(output, "interior-cutaway.png"));
            } finally { marker.dispose(); }
            scene = scene(3, GpuBuildingTest.ASSET);
            terrain.update(scene);
            checkPicking(terrain, scene);
            BoardGeometry.tune(new BoardGeometry.Tuning(tuning.hexScale(), tuning.unitScale(), tuning.unitHeightScale(),
                  12, tuning.gridShade()));
            terrain.update(scene);
            checkPicking(terrain, scene);
        } finally {
            BoardGeometry.tune(tuning);
            frame.dispose();
            terrain.dispose();
        }
    }

    static List<ModelInstance> floors(GpuTerrain terrain, Coords coords) throws Exception {
        List<ModelInstance> floors = new java.util.ArrayList<>();
        for (Object chunk : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(chunk, "cutaways")) {
                float z = (float) GpuMixedUnitBenchmarkSmokeTest.field(prop, "floorZ");
                if (!Float.isNaN(z) && coords.equals(GpuMixedUnitBenchmarkSmokeTest.field(prop, "coords"))) {
                    floors.add((ModelInstance) GpuMixedUnitBenchmarkSmokeTest.field(prop, "instance"));
                }
            }
        }
        floors.sort(java.util.Comparator.comparingDouble(floor -> UnitBounds.world(floor).min.z));
        return floors;
    }

    private static void checkFloorCutaways(GpuTerrain terrain, BoardScene scene, GpuReviewFrame frame,
          BoardCamera camera) throws Exception {
        Coords coords = new Coords(4, 4);
        List<ModelInstance> floors = floors(terrain, coords);
        assertEquals(5, floors.size(), "Each floor must have independent cutaway state and shared mesh geometry");
        Model model = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createBox(8, 8, 10,
              new com.badlogic.gdx.graphics.g3d.Material(), com.badlogic.gdx.graphics.VertexAttributes.Usage.Position
                    | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
        camera.setIsometric(false);
        camera.camera.zoom = .25f;
        camera.center(BoardGeometry.center(coords, 2));
        try {
            checkInteriorCache(terrain, false);
            // A second, higher occupant cannot raise the opaque floor above the lowest occupant.
            for (int lowest : new int[] { 0, 1, 3, 1 }) {
                ModelInstance low = new ModelInstance(model, BoardGeometry.center(coords, lowest).add(0, 0, 5.5f));
                ModelInstance high = new ModelInstance(model, BoardGeometry.center(coords, 4).add(0, 0, 5.5f));
                for (float wallAlpha : new float[] { 0, .5f }) {
                    terrain.animate(0, List.of(low, high), wallAlpha);
                    checkInteriorCache(terrain, true);
                    for (int level = 0; level < floors.size(); level++) {
                        var material = floors.get(level).materials.first();
                        var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
                        if (level <= lowest) {
                            assertNull(blend, "Occupied floor and all lower floors remain opaque");
                            assertFalse(material.has(DepthTestAttribute.Type), "Opaque floors retain normal depth writes");
                        } else {
                            assertNotNull(blend);
                            assertTrue(blend.opacity > wallAlpha && blend.opacity < 1,
                                  "Upper floors remain more visible than the walls");
                            assertFalse(material.get(DepthTestAttribute.class, DepthTestAttribute.Type).depthMask);
                        }
                    }
                    frame.render(terrain, camera, scene, false);
                    float floorZ = UnitBounds.world(floors.get(lowest)).min.z;
                    int samples = 0;
                    for (int x = -20; x <= 20; x += 5) {
                        for (int y = -20; y <= 20; y += 5) {
                            Vector3 pixel = camera.camera.project(BoardGeometry.center(coords, 0).add(x, y, 0));
                            var depth = BufferUtils.newFloatBuffer(1);
                            Gdx.gl.glReadPixels((int) pixel.x, (int) pixel.y, 1, 1, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
                            Vector3 point = new Vector3(pixel.x / camera.camera.viewportWidth * 2 - 1,
                                  pixel.y / camera.camera.viewportHeight * 2 - 1, depth.get(0) * 2 - 1)
                                  .prj(camera.camera.invProjectionView);
                            if (Math.abs(point.z - floorZ) < .1f) { samples++; }
                        }
                    }
                    assertTrue(samples > 5, "The occupied floor must actually reach the opaque framebuffer, not disappear from its cache");
                }
            }
            Ray roofRay = new Ray(BoardGeometry.center(coords, 0).add(0, 0, 400), new Vector3(0, 0, -1));
            var shellHit = terrain.selectionHit(scene, roofRay);
            for (int hoverLevel : new int[] { 0, 2, 4, 5, 0 }) {
                terrain.animate(0, List.of(), .5f, coords, hoverLevel * BoardGeometry.level());
                boolean interior = hoverLevel < 5;
                checkInteriorCache(terrain, interior);
                for (int level = 0; level < floors.size(); level++) {
                    assertFalse(floors.get(level).materials.first().has(BlendingAttribute.Type),
                          "Hover reveals its supporting floor and struts, but a roof hover restores the shell");
                }
                frame.render(terrain, camera, scene);
                assertEquals(shellHit, terrain.selectionHit(scene, roofRay), "Cutaways must keep the shell's picking geometry");
            }
            // A roof hover must not cancel the existing unit-driven cutaway.
            terrain.animate(0, List.of(new ModelInstance(model, BoardGeometry.center(coords, 1).add(0, 0, 5.5f))),
                  .5f, coords, 5 * BoardGeometry.level());
            checkInteriorCache(terrain, true);
            assertFalse(floors.get(1).materials.first().has(BlendingAttribute.Type));
            assertTrue(floors.get(2).materials.first().has(BlendingAttribute.Type));
            terrain.animate(0, List.of());
            checkInteriorCache(terrain, false);
            terrain.animate(0, List.of(), 1, coords, BoardGeometry.level());
            checkInteriorCache(terrain, false);
            terrain.animate(0, List.of(new ModelInstance(model, BoardGeometry.center(coords, 1))), 1);
            checkInteriorCache(terrain, false);
            for (ModelInstance floor : floors) {
                assertFalse(floor.materials.first().has(BlendingAttribute.Type), "Leaving restores every floor");
            }
        } finally { model.dispose(); }
    }

    static void checkInteriorCache(GpuTerrain terrain, boolean visible) throws Exception {
        int interiors = 0;
        for (Object chunk : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(terrain, "chunks")) {
            var renderables = new com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g3d.Renderable>();
            ((com.badlogic.gdx.graphics.g3d.RenderableProvider) GpuMixedUnitBenchmarkSmokeTest.field(chunk, "solidProps"))
                  .getRenderables(renderables, null);
            for (Object value : (com.badlogic.gdx.utils.Array<?>) GpuMixedUnitBenchmarkSmokeTest.field(chunk, "sharedProps")) {
                renderables.add((com.badlogic.gdx.graphics.g3d.Renderable) value);
            }
            for (var part : renderables) {
                if (part.material.id.equals("struts") || part.material.id.equals("floors")) { interiors++; }
            }
            for (String field : List.of("sharedShadows", "shadowPropRenderables")) {
                for (Object value : (com.badlogic.gdx.utils.Array<?>) GpuMixedUnitBenchmarkSmokeTest.field(chunk, field)) {
                    var part = (com.badlogic.gdx.graphics.g3d.Renderable) value;
                    assertFalse(part.material.id.equals("struts") || part.material.id.equals("floors"),
                          "Generated cutaway aids must not cast exterior shadows");
                }
            }
        }
        assertEquals(visible, interiors > 0, "Interior geometry is submitted only while an occupied shell is faded");
    }

    private static void checkPicking(GpuTerrain terrain, BoardScene scene) {
        for (int x : new int[] { 2, 4, 6 }) {
            Coords coords = new Coords(x, 4);
            float levels = scene.tile(coords).features().getFirst().height();
            var center = BoardGeometry.center(coords, 0);
            var ray = new Ray(new Vector3(center.x, center.y, 400), new Vector3(0, 0, -1));
            var hit = terrain.hit(scene, ray);
            assertNotNull(hit);
            assertEquals(coords, hit.coords());
            assertEquals(400 - (levels + .65f / 18) * BoardGeometry.level(),
                  Math.sqrt(hit.distance()), .02f);
            var bounds = terrain.roofBounds(coords);
            assertEquals((levels + 4.15f / 18) * BoardGeometry.level(), bounds.max.z, .02f);
        }
    }
}
