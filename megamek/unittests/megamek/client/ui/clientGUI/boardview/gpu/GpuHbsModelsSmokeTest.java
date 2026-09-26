/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.units.BipedMek;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Requires the user's local cache; proprietary artwork is never a test fixture or a build dependency. */
@Tag("on-demand")
class GpuHbsModelsSmokeTest {
    @TempDir
    Path brokenCache;

    private static BoardScene.UnitModel selection(String chassis) {
        var mek = new BipedMek();
        mek.setChassis(chassis);
        mek.setWeight(75);
        mek.setMovementMode(EntityMovementMode.BIPED);
        return new BoardScene.UnitModel("units/modular/meks/atlas.json",
              "units/modular/meks/fallback-biped-heavy.json", chassis, 1, 0, BoardScene.LocationDamage.NONE,
              UnitModelState.capture(mek), chassis);
    }

    @Test
    void rendersImportedChassisAlongsideGaeaAndKeepsSharedBuffersAlive() throws Exception {
        Path cache = Path.of(System.getProperty(HbsUnitCatalog.CACHE_PROPERTY, "../.work/hbs-cache"));
        assumeTrue(Files.isRegularFile(cache.resolve("catalog.json")), "Import local HBS assets first");
        var catalog = new HbsUnitCatalog(cache);
        assumeTrue(List.of("Bushwacker", "Uziel", "Mad Cat", "Dire Wolf").stream()
              .allMatch(chassis -> catalog.descriptor(chassis) != null));
        Files.writeString(brokenCache.resolve("catalog.json"), """
              {"schema":1,"models":{"bushwacker":{"descriptor":"missing.json"},
                "uziel":{"descriptor":"broken.json"}}}
              """);
        Files.writeString(brokenCache.resolve("broken.json"), "{\"schema\":99}");
        var brokenCatalog = new HbsUnitCatalog(brokenCache);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 760);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels(Configuration.dataDir().toPath().resolve("models"), catalog);
                var batch = new ModelBatch();
                try {
                    var fallbackLibrary = new GpuUnitModels(Configuration.dataDir().toPath().resolve("models"), brokenCatalog);
                    try {
                        for (String chassis : List.of("Bushwacker", "Uziel")) {
                            var fallback = fallbackLibrary.get(selection(chassis), 50);
                            assertNotNull(fallback);
                            assertNotNull(fallback.instance.getMaterial("paint"), "Broken HBS assets must use Gaea");
                        }
                    } finally {
                        fallbackLibrary.dispose();
                    }
                    var selections = List.of(selection("Bushwacker"), selection("Uziel"),
                          selection("Mad Cat (Timber Wolf)"), selection("Dire Wolf"), selection("Unknown HBS Chassis"));
                    var instances = new ArrayList<ModelInstance>();
                    for (int i = 0; i < selections.size(); i++) {
                        var visual = library.get(selections.get(i), i);
                        assertNotNull(visual);
                        assertSame(visual, library.loaded(selections.get(i), i));
                        assertTrue(visual.instance.calculateBoundingBox(new com.badlogic.gdx.math.collision.BoundingBox()).isValid());
                        if (i < 4) {
                            assertTrue(visual.instance.model.materials.first().id.matches("-?\\d+"), "HBS material expected");
                        }
                        var instance = new ModelInstance(visual.instance.model);
                        instance.transform.setToTranslation((i - 2) * 65, 0, 0);
                        if (i == 1) {
                            visual.turnUpperBody(instance, 30);
                        }
                        instances.add(instance);
                    }
                    var shared = library.get(selections.getFirst(), 99);
                    assertSame(shared, library.get(selections.getFirst(), 0));
                    library.retainAssemblies(Set.of(0, 1, 2, 3, 4));
                    // Removing one unit must not dispose another unit's shared HBS mesh or textures.
                    assertSame(shared, library.get(selections.getFirst(), 100));
                    var environment = new Environment();
                    environment.set(ColorAttribute.createAmbientLight(.6f, .6f, .6f, 1));
                    environment.add(new DirectionalLight().set(.9f, .85f, .8f, -.3f, -.7f, -1));
                    var camera = new OrthographicCamera(345, 218.5f);
                    camera.near = 1;
                    camera.far = 1000;
                    Path output = Path.of(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    Files.createDirectories(output);
                    for (boolean top : new boolean[] { false, true }) {
                        camera.position.set(top ? new Vector3(0, 0, 400) : new Vector3(90, 290, 155));
                        camera.up.set(top ? Vector3.Y : Vector3.Z);
                        camera.lookAt(0, 0, 20);
                        camera.update();
                        Gdx.gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        Gdx.gl.glClearColor(.15f, .19f, .23f, 1);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                        batch.begin(camera);
                        for (var instance : instances) {
                            batch.render(instance, environment);
                        }
                        batch.end();
                        var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        try {
                            int changed = 0, background = pixels.getPixel(0, 0);
                            for (int y = 0; y < pixels.getHeight(); y += 3) {
                                for (int x = 0; x < pixels.getWidth(); x += 3) {
                                    if (pixels.getPixel(x, y) != background) { changed++; }
                                }
                            }
                            assertTrue(changed > 500, "Models must render visible geometry");
                            PixmapIO.writePNG(new FileHandle(output.resolve("hbs-" + (top ? "top" : "isometric") + ".png").toFile()),
                                  pixels, -1, true);
                        } finally {
                            pixels.dispose();
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    library.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }
}
