/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Raster markings retain distant meshes, including when shared atlas artwork splits, merges or compacts. */
@Tag("on-demand")
class GpuMarkingsSmokeTest {
    private static final Coords FIRST = new Coords(4, 4);
    private static final Coords SECOND = new Coords(12, 4);
    private static final Coords THIRD = new Coords(20, 4);

    @Test
    void markingUpdatesRebuildOnlyChunksWhoseAtlasReferencesChanged() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1000, 700);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Native marking invalidation", failure.get()); }
    }

    private static void check() throws Exception {
        GpuTerrain terrain = new GpuTerrain();
        BoardScene.Pixels red = art(64, 64, 0xffff3030), blue = art(64, 64, 0xff3040ff);
        Map<Coords, BoardScene.Pixels> markings = new HashMap<>();
        markings.put(FIRST, red);
        markings.put(SECOND, red);
        markings.put(THIRD, art(64, 64, 0xff30ff40));
        try {
            terrain.update(scene(markings, false));
            GpuTextures<Coords> atlas = atlas(terrain);
            assertSame(atlas.region(FIRST), atlas.region(SECOND), "Identical artwork must share one slot");
            Texture stablePage = atlas.region(SECOND).getTexture();

            markings.put(THIRD, blue);
            checkChange("in-place pixels", terrain, scene(markings, false), Set.of());
            assertSame(stablePage, atlas.region(SECOND).getTexture());

            markings.put(FIRST, art(64, 64, 0xffffe030));
            checkChange("split shared slot", terrain, scene(markings, false), Set.of(0));
            assertNotSame(atlas.region(FIRST), atlas.region(SECOND));
            assertSame(stablePage, atlas.region(SECOND).getTexture());

            markings.put(FIRST, red);
            checkChange("merge shared slot", terrain, scene(markings, false), Set.of(0));
            assertSame(atlas.region(FIRST), atlas.region(SECOND));

            // Exercise the terrain-update path as well as the overlay-only fast path.
            markings.put(FIRST, art(64, 64, 0xffc030ef));
            checkChange("terrain plus marking edit", terrain, scene(markings, true), Set.of(0, 2));
            markings.put(FIRST, red);
            checkChange("restore shared slot", terrain, scene(markings, true), Set.of(0));
            markings.remove(FIRST);
            checkChange("remove one marking", terrain, scene(markings, true), Set.of(0));
            markings.put(FIRST, red);
            checkChange("add shared marking", terrain, scene(markings, true), Set.of(0));

            boolean compacted = false;
            for (int edit = 0; edit < 8 && !compacted; edit++) {
                // Alternately split and merge one alias, leaving discarded artwork behind until the atlas compacts.
                for (boolean split : new boolean[] { true, false }) {
                    Texture before = atlas.region(SECOND).getTexture();
                    List<List<Model>> previous = models(terrain);
                    markings.put(FIRST, split ? art(64, 64, 0xff408080 + edit * 0x080801) : red);
                    BoardScene next = scene(markings, true);
                    terrain.update(next);
                    compacted = before != atlas.region(SECOND).getTexture();
                    assertChanged("atlas compaction", previous, models(terrain), compacted ? Set.of(0, 1, 2) : Set.of(0));
                    if (compacted) {
                        assertPixels(terrain, next);
                        break;
                    }
                }
            }
            assertTrue(compacted, "Discarded atlas slots must eventually compact and relocate unchanged artwork");

            Texture beforeResize = atlas.region(SECOND).getTexture();
            markings.put(FIRST, art(300, 32, 0xff40e0e0));
            checkChange("larger atlas page", terrain, scene(markings, true), Set.of(0, 1, 2));
            assertNotSame(beforeResize, atlas.region(SECOND).getTexture());
            markings.clear();
            checkChange("remove all markings", terrain, scene(markings, true), Set.of(0, 1, 2));
            checkChange("unchanged empty layer", terrain, scene(markings, true), Set.of());
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
        }
    }

    private static void checkChange(String name, GpuTerrain terrain, BoardScene scene, Set<Integer> changed) throws Exception {
        List<List<Model>> before = models(terrain);
        terrain.update(scene);
        assertChanged(name, before, models(terrain), changed);
        assertPixels(terrain, scene);
    }

    private static void assertChanged(String name, List<List<Model>> before, List<List<Model>> after, Set<Integer> expected) {
        assertEquals(3, before.size());
        assertEquals(before.size(), after.size());
        Set<Integer> changed = new HashSet<>();
        for (int index = 0; index < before.size(); index++) {
            if (!before.get(index).equals(after.get(index))) { changed.add(index); }
        }
        System.out.printf("Raster markings %s: rebuilt chunks=%s of %d%n", name, changed, before.size());
        assertEquals(expected, changed, name);
    }

    private static void assertPixels(GpuTerrain terrain, BoardScene scene) {
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.fit(scene);
        GpuTerrain reference = new GpuTerrain();
        try {
            reference.update(scene);
            byte[] expected = pixels(reference, camera);
            assertArrayEquals(expected, pixels(terrain, camera), "Incremental markings must match a fresh terrain build");
            if (scene.tiles().stream().anyMatch(tile -> tile.tactical() != null)) {
                int visible = 0;
                for (int index = 0; index < expected.length; index += 4) {
                    if (Byte.toUnsignedInt(expected[index]) > 30 || Byte.toUnsignedInt(expected[index + 1]) > 30
                          || Byte.toUnsignedInt(expected[index + 2]) > 30) { visible++; }
                }
                assertTrue(visible > 100, "The comparison must include visible markings");
            }
        } finally {
            reference.dispose();
        }
    }

    private static byte[] pixels(GpuTerrain terrain, BoardCamera camera) {
        ScreenUtils.clear(.02f, .03f, .04f, 1, true);
        terrain.render(camera.camera, true);
        return ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Model>> models(GpuTerrain terrain) throws Exception {
        Field chunks = GpuTerrain.class.getDeclaredField("chunks");
        chunks.setAccessible(true);
        List<List<Model>> result = new ArrayList<>();
        for (Object chunk : (List<?>) chunks.get(terrain)) {
            Field markings = chunk.getClass().getDeclaredField("tactical");
            markings.setAccessible(true);
            result.add(((List<ModelInstance>) markings.get(chunk)).stream().map(instance -> instance.model).toList());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static GpuTextures<Coords> atlas(GpuTerrain terrain) throws Exception {
        Field atlas = GpuTerrain.class.getDeclaredField("tactical");
        atlas.setAccessible(true);
        return (GpuTextures<Coords>) atlas.get(terrain);
    }

    private static BoardScene scene(Map<Coords, BoardScene.Pixels> markings, boolean withDecal) {
        BoardScene.Pixels ground = art(84, 72, 0xff707070), decal = art(84, 72, 0xff909090);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 8; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS,
                      ground, withDecal && coords.equals(THIRD) ? decal : null, markings.get(coords), List.of(), List.of()));
            }
        }
        return new BoardScene(0, 24, 8, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels art(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(argb, true));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return new BoardScene.Pixels(image);
    }
}
