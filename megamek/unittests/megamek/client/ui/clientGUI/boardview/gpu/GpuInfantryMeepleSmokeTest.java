/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.equipment.EquipmentType;
import megamek.common.units.ConvInfantry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GLB loading, formation replacement/cache invalidation, and a scale-preserving visual review. */
@Tag("on-demand")
class GpuInfantryMeepleSmokeTest {
    private static final String ROOT = "units/modular/meeples/";
    // CHECKSTYLE IGNORE ForbiddenWords FOR 3 LINES - literal asset filenames
    private static final List<String> DESIGNS = List.of("branth-rider", "camel-horse-rider", "odessan-raxx-rider",
          "elephant-rider", "field-gun", "orca-rider", "vtol-trooper", "mechwarrior", "motorcycle-rider",
          "infantry-trooper", "battle-armor-trooper");

    @Test
    void loadsAllSilhouettesAndRebuildsOnlyChangedFormations() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    var reference = library.modular(ROOT + "infantry-trooper.json");
                    assertNotNull(reference);
                    var instances = new ArrayList<ModelInstance>();
                    for (int index = 0; index < DESIGNS.size(); index++) {
                        String name = DESIGNS.get(index);
                        var asset = library.modular(ROOT + name + ".json");
                        assertNotNull(asset, name);
                        assertEquals("meeple-v1", asset.descriptor().rig());
                        assertNotNull(asset.model().getNode("root"));
                        assertNotNull(asset.model().getMaterial("paint"));
                        assertTrue(asset.triangles() > 0 && asset.triangles() * 6 <= UnitModelDescriptor.MAX_TRIANGLES);
                        if (index < 9) {
                            assertEquals(reference.descriptor().bounds().min().get(1),
                                  asset.descriptor().bounds().min().get(1), .0001f);
                            assertEquals(reference.descriptor().bounds().max().get(1),
                                  asset.descriptor().bounds().max().get(1), .0001f);
                        }
                        // CHECKSTYLE IGNORE ForbiddenWords FOR 1 LINES - literal asset filename
                        if (name.equals("mechwarrior")) {
                            assertEquals(reference.descriptor().bounds().max().get(2),
                                  asset.descriptor().bounds().max().get(2), .0001f);
                        }
                        var instance = new ModelInstance(asset.model());
                        // Three source-sheet columns; the last row holds the two original size references.
                        instance.transform.setToTranslation((1 - index % 3) * 28, 0, (3 - index / 3) * 18);
                        instances.add(instance);
                    }
                    renderReview(batch, instances);
                    library.setDisplayMode(UnitDisplayMode.ALL_MEEPLES, false);
                    var tileset = new MekTileset(new File(Configuration.dataDir(), "images/units"));
                    tileset.loadFromFile("mekset.txt");
                    int id = 100;
                    for (String chassis : List.of("Beast Infantry (Branth)", "Beast Infantry (Camel)",
                          "Beast Infantry (Horse)", "Beast Infantry (Odessan Raxx)", "Beast Infantry (Elephant)",
                          // CHECKSTYLE IGNORE ForbiddenWords FOR 1 LINES - existing stock tileset chassis key
                          "Beast Infantry (Orca)", "MechWarrior", "Pilot",
                          "VTOL Infantry Hachiman Taro Enterprise Extraction Force")) {
                        var infantry = new ConvInfantry();
                        infantry.setChassis(chassis);
                        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                        var selection = UnitModelSelection.capture(infantry, -1, false, tileset);
                        var formation = library.get(selection, id++);
                        assertNotNull(formation, chassis);
                        assertEquals(6, formation.instance.nodes.size);
                        assertTrue(formation.rigs().stream().allMatch(UnitRig::meeple));
                        assertTrue(triangles(formation.instance.nodes) > reference.triangles() * 6,
                              "Stock icon must resolve a new silhouette: " + chassis);
                        assertTrue(triangles(formation.instance.nodes) <= UnitModelDescriptor.MAX_TRIANGLES);
                    }
                    var infantry = new ConvInfantry();
                    infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                    var customTileset = mock(MekTileset.class);
                    when(customTileset.modelFor(infantry, -1)).thenReturn("units/modular/infantry.json");
                    when(customTileset.entryFor(infantry, -1)).thenReturn(customTileset.new MekEntry("Infantry/Miniguncycle.png"));
                    var cycleSelection = UnitModelSelection.capture(infantry, -1, false, customTileset);
                    var cycles = library.get(cycleSelection, id);
                    assertNotNull(cycles);
                    assertEquals(6 * library.modular(ROOT + "motorcycle-rider.json").triangles(), triangles(cycles.instance.nodes));
                    when(customTileset.entryFor(infantry, -1)).thenReturn(customTileset.new MekEntry("Infantry/fieldgun.png"));
                    var gun = infantry.addEquipment(EquipmentType.get("ISAC5"), ConvInfantry.LOC_FIELD_GUNS);
                    infantry.addEquipment(EquipmentType.get("ISAC5"), ConvInfantry.LOC_FIELD_GUNS);
                    var armedSelection = UnitModelSelection.capture(infantry, -1, false, customTileset);
                    var armed = library.get(armedSelection, id);
                    assertNotNull(armed);
                    assertNotSame(cycles, armed);
                    assertSame(armed, library.get(armedSelection, id));
                    int gunTriangles = library.modular(ROOT + "field-gun.json").triangles();
                    assertEquals(2 * gunTriangles + 4 * reference.triangles(), triangles(armed.instance.nodes));
                    gun.setDestroyed(true);
                    var damaged = library.get(UnitModelSelection.capture(infantry, -1, false, customTileset), id);
                    assertNotNull(damaged);
                    assertNotSame(armed, damaged);
                    assertEquals(gunTriangles + 5 * reference.triangles(), triangles(damaged.instance.nodes));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    library.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Infantry meeple review failed", failure.get()); }
    }

    private static int triangles(Iterable<Node> nodes) {
        int result = 0;
        for (var node : nodes) {
            for (var part : node.parts) { if (part.enabled) { result += part.meshPart.size / 3; } }
            result += triangles(node.getChildren());
        }
        return result;
    }

    private static void renderReview(ModelBatch batch, List<ModelInstance> instances) {
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(.6f, .6f, .6f, 1));
        environment.add(new DirectionalLight().set(.8f, .8f, .8f, -.4f, -.7f, -1));
        var camera = new OrthographicCamera(120, 120f * Gdx.graphics.getHeight() / Gdx.graphics.getWidth());
        camera.near = 1;
        camera.far = 1000;
        camera.position.set(12, 200, 43);
        camera.up.set(Vector3.Z);
        camera.lookAt(0, 0, 34);
        camera.update();
        try (var surface = new GpuSceneTestOutput(Gdx.graphics.getWidth(), Gdx.graphics.getHeight())) {
            surface.begin(.12f, .14f, .17f, 1);
            batch.begin(camera);
            for (var instance : instances) { batch.render(instance, environment); }
            batch.end();
            surface.display();
            GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots",
                  "build/gpu-board-review"), "infantry-meeples-scale-review.png"));
        }
    }
}
