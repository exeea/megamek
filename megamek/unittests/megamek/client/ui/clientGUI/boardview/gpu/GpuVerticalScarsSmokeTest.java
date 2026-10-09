/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native shaded scars on sculpted cliffs, including tile seams, oblique contact and accumulated-hit cost. */
@Tag("on-demand")
class GpuVerticalScarsSmokeTest {
    @Test
    void scarsFollowDeformedWallsAndRepeatedHitsReuseTheirGeometry() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { review(); reviewBuilding(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Vertical scar review", failure.get()); }
    }

    private static void review() throws Exception {
        var scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.GRASS);
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        var profiler = new GLProfiler(Gdx.graphics);
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "vertical-scars");
        Files.createDirectories(output.toPath());
        try {
            terrain.update(scene);
            var camera = new BoardCamera();
            camera.resize(1200, 900);
            camera.setIsometric(true);
            camera.camera.zoom = .14f;
            Coords at = new Coords(2, 1);
            var desired = BoardGeometry.center(at, 2).add(-BoardGeometry.width() * .45f, 0, 0);
            camera.center(desired);
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            var walls = terrain.tacticalSurface(at).walls();
            var face = walls.stream().filter(f -> normal(f).x < -.6f && Math.abs(normal(f).z) < .5f)
                  .min(java.util.Comparator.comparingDouble(f -> center(f).dst2(desired))).orElseThrow();
            Vector3 point = center(face), normal = normal(face);
            assertTrue(walls.stream().map(GpuVerticalScarsSmokeTest::normal).map(n -> Math.round(n.x * 100) + ":" + Math.round(n.z * 100))
                  .distinct().count() > 8, "The review must exercise actual deformed geometry rather than a flat quad");
            camera.center(point);
            camera.camera.position.set(point).mulAdd(normal, BoardRelief.metres(60)).add(0, 0, BoardRelief.metres(15));
            camera.camera.up.set(Vector3.Z);
            camera.camera.lookAt(point);
            camera.camera.update();
            frame.render(terrain, camera, scene);
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            assertTrue(terrain.ready(scene));
            frame.render(terrain, camera, scene);
            int[] clean = pixels();
            GpuReviewFrame.save(new File(output, "cliff-clean.png"));
            var report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; 1200x900, deformed cliff\n");
            GpuGroundDamage.Impact last = null;
            for (String[] sample : new String[][] {
                  { "ppc-blast", "ISPPC", "normal" }, { "ppc-gouge", "ISPPC", "grazing" },
                  { "ac20-blast", "ISAC20", "normal" }, { "long-tom", "ISLongTom", "normal" },
                  { "plasma", "ISPlasmaRifle", "normal" }, { "flame", "Flamer", "normal" }
            }) {
                terrain.groundDamage().dispose();
                frame.render(terrain, camera, scene);
                var incoming = normal.cpy().scl(-1);
                if (sample[2].equals("grazing")) { incoming.mulAdd(new Vector3(0, 0, 1).crs(normal).nor(), 3).nor(); }
                var origin = point.cpy().mulAdd(incoming, -BoardRelief.metres(20));
                var aim = point.cpy().mulAdd(incoming, BoardRelief.metres(.1f));
                var mark = GroundDamagePlaybackTest.impact(sample[1], null);
                mark.attack().landscape = ray -> terrain.hit(scene, ray);
                mark.attack().landscapeSegment = (ray, length) -> terrain.hit(scene, ray, length);
                var contact = mark.attack().landscapeContact(t -> origin.cpy().lerp(aim, (float) t), 1);
                assertNotNull(contact, sample[0]);
                last = new GpuGroundDamage.Impact(mark.attack(), sample[0], origin, contact.point(), mark.effect(), mark.shot());
                long start = System.nanoTime();
                terrain.impact(scene, last);
                frame.render(terrain, camera, scene);
                Gdx.gl.glFinish();
                double creation = (System.nanoTime() - start) / 1e6;
                assertTrue(terrain.surfaceScars().tileCount() > 0, sample[0] + " must allocate a wall receiver");
                assertTrue(terrain.surfaceScars().tileCount() <= 16, sample[0] + " must only allocate nearby wall tiles");
                assertTrue(terrain.surfaceScars().triangleCount() > 2, "The patch must follow the deformed receiver");
                for (int warmup = 0; warmup < 3; warmup++) { frame.render(terrain, camera, scene); }
                int changed = differences(clean, pixels());
                assertTrue(changed > 100, sample[0] + " must visibly change the cliff material");
                GpuReviewFrame.save(new File(output, sample[0] + ".png"));
                report.append(sample[0]).append(": ").append(terrain.surfaceScars().tileCount()).append(" tiles, ")
                      .append(terrain.surfaceScars().triangleCount()).append(" triangles, ").append(changed)
                      .append(" changed pixels, first frame including projection/shader/upload ms=").append(creation).append('\n');
            }
            int tiles = terrain.surfaceScars().tileCount(), triangles = terrain.surfaceScars().triangleCount();
            profiler.enable();
            frame.render(terrain, camera, scene);
            profiler.reset();
            frame.render(terrain, camera, scene);
            int originalDraws = profiler.getDrawCalls();
            for (int i = 0; i < 40; i++) {
                terrain.impact(scene, new GpuGroundDamage.Impact(last.attack(), "repeat-" + i, last.origin(), last.point(), last.effect(), last.shot()));
                frame.render(terrain, camera, scene);
            }
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
            assertEquals(tiles, terrain.surfaceScars().tileCount(), "Repeated hits must reuse their mask tiles");
            assertEquals(triangles, terrain.surfaceScars().triangleCount(), "Repeated hits must not add overlay triangles");
            profiler.reset(); frame.render(terrain, camera, scene);
            assertEquals(originalDraws, profiler.getDrawCalls(), "Forty hits must not add draws to the same painted surface");
            report.append("40 repeated hits: unchanged ").append(triangles).append(" triangles, ")
                  .append(originalDraws).append(" total frame draw calls\n");
            profiler.disable();
            for (boolean enabled : new boolean[] { false, true }) {
                terrain.setCombatScars(enabled);
                for (int i = 0; i < 12; i++) { frame.render(terrain, camera, scene); }
                double[] samples = new double[25];
                for (int i = 0; i < samples.length; i++) {
                    Gdx.gl.glFinish(); long start = System.nanoTime();
                    frame.render(terrain, camera, scene); Gdx.gl.glFinish();
                    samples[i] = (System.nanoTime() - start) / 1e6;
                }
                Arrays.sort(samples);
                report.append("scars=").append(enabled).append(" synchronized median frame ms=").append(samples[samples.length / 2]).append('\n');
            }
            var before = terrain.tacticalSurface(at);
            int marks = terrain.groundDamage().marks();
            var replacement = new java.util.ArrayList<>(scene.tiles());
            var editedTile = scene.tile(at);
            replacement.set(replacement.indexOf(editedTile), new BoardScene.Tile(editedTile.coords(), editedTile.elevation() + 1,
                  -1, false, 0, editedTile.surface(), editedTile.ground(), null, null, null, null,
                  List.of(), List.of(), BoardLiquid.NONE, null, true));
            var editedScene = scene.withTiles(replacement);
            terrain.update(editedScene, camera.camera);
            GpuTerrainLodSmokeTest.settle(terrain, null, editedScene, camera);
            assertTrue(before != terrain.tacticalSurface(at), "The edit must replace the receiving terrain mesh");
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, editedScene); }
            assertEquals(tiles, terrain.surfaceScars().tileCount(), "Replacing terrain must retain the masks");
            assertEquals(marks, terrain.groundDamage().marks(), "Replacing terrain must not repaint scars");
            int[] rebuilt = pixels();
            terrain.setCombatScars(false); frame.render(terrain, camera, editedScene);
            assertTrue(differences(rebuilt, pixels()) > 100, "The rebuilt cliff must still carry its accumulated scar");
            report.append("Terrain mesh replacement: masks retained and scar still visible\n");
            Files.writeString(new File(output, "review.txt").toPath(), report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { profiler.disable(); terrain.dispose(); frame.dispose(); }
    }

    private static void reviewBuilding() throws Exception {
        var board = new Board(7, 7);
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) { board.setHex(new Coords(x, y), new Hex(0)); }
        var at = new Coords(3, 3);
        board.setHex(at, new Hex(0, "building:2;bldg_elev:5;bldg_cf:40", ""));
        var scene = BoardAridSurfaceTest.capture(board);
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "vertical-scars");
        try {
            terrain.update(scene);
            var camera = new BoardCamera(); camera.resize(1200, 900); camera.setIsometric(true);
            camera.camera.zoom = .15f;
            var aim = BoardGeometry.center(at, 2.5f);
            camera.center(aim);
            camera.camera.position.set(aim).add(-BoardRelief.metres(50), -BoardRelief.metres(10), BoardRelief.metres(5));
            camera.camera.up.set(Vector3.Z); camera.camera.lookAt(aim); camera.camera.update();
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            frame.render(terrain, camera, scene);
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            frame.render(terrain, camera, scene);
            int[] clean = pixels();
            GpuReviewFrame.save(new File(output, "building-clean.png"));
            var origin = aim.cpy().add(-BoardRelief.metres(40), 0, 0);
            var mark = GroundDamagePlaybackTest.impact("ISAC20", null);
            mark.attack().landscape = ray -> terrain.hit(scene, ray);
            mark.attack().landscapeSegment = (ray, length) -> terrain.hit(scene, ray, length);
            var contact = mark.attack().landscapeContact(t -> origin.cpy().lerp(aim, (float) t), 1);
            assertNotNull(contact);
            assertEquals("building", contact.surface().receiver());
            terrain.impact(scene, new GpuGroundDamage.Impact(mark.attack(), "building", origin, contact.point(), mark.effect(), mark.shot()));
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
            assertTrue(terrain.surfaceScars().tileCount() > 0, "A building wall must receive a scar");
            assertTrue(differences(clean, pixels()) > 100, "The native model material must display the wall scar");
            GpuReviewFrame.save(new File(output, "building-ac20.png"));
            int masks = terrain.surfaceScars().tileCount();
            terrain.animate(0, List.of(), .2f, at, BoardGeometry.level() * 2);
            frame.render(terrain, camera, scene);
            int[] cutaway = pixels();
            terrain.setCombatScars(false); frame.render(terrain, camera, scene);
            assertEquals(0, differences(cutaway, pixels()), "An opened storey must not retain an opaque floating scar");
            terrain.animate(0, List.of(), .2f, null, Float.NaN);
            terrain.setCombatScars(true);
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
            assertEquals(masks, terrain.surfaceScars().tileCount(), "Cutaways retain the stored scar images");
            assertTrue(differences(clean, pixels()) > 100, "Closing the cutaway restores its scar");
            camera.setPerspective(true);
            camera.center(aim);
            camera.camera.position.set(aim).add(-BoardRelief.metres(50), -BoardRelief.metres(10), BoardRelief.metres(5));
            camera.camera.up.set(Vector3.Z); camera.camera.lookAt(aim); camera.camera.update();
            frame.render(terrain, camera, scene);
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
            int[] perspective = pixels();
            GpuReviewFrame.save(new File(output, "building-ac20-perspective.png"));
            terrain.setCombatScars(false); frame.render(terrain, camera, scene);
            assertTrue(differences(perspective, pixels()) > 10, "Perspective uses the same scar patches");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); frame.dispose(); }
    }

    private static Vector3 center(BoardSurface.Face face) { return face.a().cpy().add(face.b()).add(face.c()).scl(1f / 3); }
    private static Vector3 normal(BoardSurface.Face face) { return face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a())).nor(); }
    private static int[] pixels() {
        var image = Pixmap.createFromFrameBuffer(0, 0, 1200, 900);
        try { int[] pixels = new int[1200 * 900]; image.getPixels().asIntBuffer().get(pixels); return pixels; }
        finally { image.dispose(); }
    }
    private static int differences(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift = 0; shift < 24; shift += 8) {
                if (Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)) > 4) { count++; break; }
            }
        }
        return count;
    }
}
