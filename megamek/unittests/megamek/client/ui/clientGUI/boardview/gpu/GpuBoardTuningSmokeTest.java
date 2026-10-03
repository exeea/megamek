/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The tuning model's rules on a real source: presets, derived controls and Defaults stay visual. The HUD's tuning panel
 * that shows the model is GpuTuningPanelSmokeTest's.
 */
@Tag("on-demand")
class GpuBoardTuningSmokeTest {
    @Test
    void presetsAndDerivedControlsStayVisualAndResetEffectTuning() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            var initial = fixture.source.takeFrame().scenarioAtmosphere();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try {
                        checkControls(fixture.source, initial);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            SwingUtilities.invokeAndWait(() -> assertEquals(initial,
                  fixture.source.atmosphereFor(fixture.game.getPlanetaryConditions(), false)));
            assertEquals(0, fixture.clicks.get(), "Tuning must never issue gameplay orders");
        }
    }

    private void checkControls(GpuBoardSource source, BoardAtmosphere.Settings initial) {
        var skin = new GpuBoardSkin();
        try {
            var camera = new BoardCamera();
            camera.resize(1280, 800);
            var tuning = new GpuBoardTuning(skin.skin, source, camera);
            tuning.useScenario(initial, true);
            Slider cameraFov = GpuBoardTestUi.tuning(tuning, "tuning-camera-fov");
            assertTrue(cameraFov.isDisabled());
            press(tuning, "tuning-free-flight");
            assertTrue(camera.firstPerson());
            assertTrue(camera.perspective());
            assertFalse(cameraFov.isDisabled());
            assertEquals(0, camera.camera.projection.val[Matrix4.M33], .0001f,
                  "Perspective must use a projective camera matrix");
            float projectionScale = camera.camera.projection.val[Matrix4.M00];
            cameraFov.setValue(70);
            assertEquals(70, camera.fieldOfView());
            assertTrue(camera.camera.projection.val[Matrix4.M00] < projectionScale,
                  "Increasing FOV widens the live camera projection");
            for (var family : UnitFamilyScale.values()) {
                Slider slider = GpuBoardTestUi.tuning(tuning, "tuning-size-" + family.name());
                assertEquals(family.defaultUnitScale, slider.getValue(), .0001f,
                      "Family sizes start at their defaults");
                slider.setValue(1.5f);
                assertEquals(1.5f, family.UNIT_SCALE);
                assertEquals(family.heightScale(), family.HEIGHT_SCALE, "Uniform size does not change height proportions");
            }
            SelectBox<GpuGraphicsCard> card = tuning.boardRows().findActor("tuning-graphics-card");
            assertEquals(GpuGraphicsCard.cards().isEmpty(), card == null, "A card choice needs two cards to pick");
            press(tuning, "tuning-defaults");
            assertFalse(camera.perspective());
            assertTrue(cameraFov.isDisabled());
            assertEquals(BoardCamera.DEFAULT_FIELD_OF_VIEW, camera.fieldOfView());
            assertEquals(BoardCamera.DEFAULT_FIELD_OF_VIEW, cameraFov.getValue());
            for (var family : UnitFamilyScale.values()) {
                assertEquals(family.defaultUnitScale, family.UNIT_SCALE, .0001f);
            }
            checkTerrainControls(tuning);
            checkTerrainRendering(tuning, source.takeFrame().scene());
            var defaultEffects = tuning.atmosphereOptions();
            assertNull(tuning.boardRows().findActor("Speed gain / hex"));
            assertNull(tuning.atmosphereRows().findActor("Speed gain / hex"));
            set(tuning, "God rays", 0.8f);
            set(tuning, "Cloud shadow min", 0.3f);
            set(tuning, "Cloud shadow max", 0.9f);
            set(tuning, "Moon shadow contrast", 0.45f);
            set(tuning, "Sun glare", 0.2f);
            set(tuning, "Fog height variation", 0.5f);
            set(tuning, "Fog density variation", 0.4f);
            set(tuning, "Taint strength", 1.5f);
            for (var preset : AtmospherePreset.values()) {
                press(tuning, "atmosphere-" + preset.name());
                assertEquals(source.atmosphereFor(preset), tuning.atmosphere(), preset.label);
                assertEquals(defaultEffects, tuning.atmosphereOptions(), "Presets reset the extra controls to their constants");
                assertEquals(tuning.atmosphere().gravity(), tuning.gravityOverride());
                set(tuning, "God rays", 0.8f);
                set(tuning, "Moon shadow contrast", 0.45f);
            }
            var rainyConditions = new PlanetaryConditions();
            rainyConditions.setWeather(Weather.HEAVY_RAIN);
            var rainy = source.atmosphereFor(rainyConditions, false);
            tuning.useScenario(rainy, true);
            set(tuning, "Temperature (C)", 10);
            assertEquals(rainy.clouds(), tuning.atmosphere().clouds(), 0.000001f,
                  "Editing temperature must not round scenario-derived cloud cover to a different value");
            tuning.useScenario(initial, true);
            assertTrue(Float.isNaN(tuning.gravityOverride()), "Without an override, jumps retain their captured gravity");
            set(tuning, "Gravity (g)", 0.5f);
            assertEquals(0.5f, tuning.gravityOverride());
            press(tuning, "atmosphere-FULL_MOON");
            press(tuning, "tuning-moonlight");
            assertFalse(tuning.atmosphere().moonlight());
            assertFalse(BoardAtmosphere.lighting(tuning.atmosphere()).hasDirectLight());
            press(tuning, "atmosphere-PITCH_BLACK");
            assertEquals(-1, tuning.atmosphere().exposure());
            assertFalse(GpuBoardTestUi.<CheckBox>tuning(tuning, "tuning-moonlight").isChecked());
            press(tuning, "atmosphere-MOONLESS");
            assertEquals(-0.6f, tuning.atmosphere().exposure(), 0.00001f);
            assertFalse(tuning.atmosphere().moonlight());
            set(tuning, "Time of day", 12);
            assertTrue(BoardAtmosphere.lighting(tuning.atmosphere()).hasDirectLight());
            set(tuning, "Time of day", 0);
            assertFalse(BoardAtmosphere.lighting(tuning.atmosphere()).hasDirectLight());

            press(tuning, "atmosphere-RAIN_STORM");
            assertTrue(BoardAtmosphere.wetness(tuning.atmosphere()) > 0);
            set(tuning, "Temperature (C)", 0);
            assertEquals(0, BoardAtmosphere.wetness(tuning.atmosphere()));
            set(tuning, "Temperature (C)", 10);
            assertTrue(BoardAtmosphere.wetness(tuning.atmosphere()) > 0);
            SelectBox<Atmosphere> pressure = GpuBoardTestUi.tuning(tuning, "tuning-atmosphere-pressure");
            pressure.setSelected(Atmosphere.VACUUM);
            assertEquals(BoardAtmosphere.Effects.NONE, tuning.atmosphere().effects());
            assertEquals(0, tuning.atmosphere().clouds());
            assertTrue(GpuBoardTestUi.<Slider>tuning(tuning, "Rain").isDisabled());
            pressure.setSelected(Atmosphere.STANDARD);
            assertFalse(GpuBoardTestUi.<Slider>tuning(tuning, "Rain").isDisabled());
            SelectBox<AtmosphericTaint> taint = GpuBoardTestUi.tuning(tuning, "tuning-atmospheric-taint");
            taint.setSelected(AtmosphericTaint.TOXIC_POISON);
            assertEquals(taint.getSelected(), tuning.atmosphere().taint());
            assertFalse(GpuBoardTestUi.<Slider>tuning(tuning, "Taint strength").isDisabled());
            set(tuning, "Ground fog", 0.6f);
            assertEquals(AtmosphericTaint.TOXIC_POISON, tuning.atmosphere().taint());

            press(tuning, "tuning-defaults");
            assertEquals(initial, tuning.atmosphere());
            assertEquals(defaultEffects, tuning.atmosphereOptions());
        } finally {
            BoardConcrete.tune(BoardConcrete.DEFAULT_MODE);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            TerrainLod.tune(TerrainLod.DEFAULTS);
            TerrainLod.setEnabled(TerrainLod.DEFAULT_ENABLED);
            BoardSurface.tune(BoardSurface.DEFAULTS);
            BoardRelief.tuneGeology(BoardRelief.defaultGeology());
            skin.dispose();
        }
    }

    /** The Terrain page's rules on the model (GpuTuningPanelSmokeTest shows the page). */
    private static void checkTerrainControls(GpuBoardTuning tuning) {
        CheckBox terrainLod = GpuBoardTestUi.tuning(tuning, "tuning-terrain-lod");
        assertTrue(terrainLod.isChecked(), "Terrain LoD is on by default");
        Slider fullDetail = GpuBoardTestUi.tuning(tuning, "Full detail at (px)");
        Slider mediumDetail = GpuBoardTestUi.tuning(tuning, "Medium detail at (px)");
        assertFalse(fullDetail.isDisabled());
        assertFalse(mediumDetail.isDisabled());
        int lodRevision = BoardGeometry.terrainRevision();
        press(tuning, "tuning-terrain-lod");
        assertFalse(TerrainLod.enabled());
        assertTrue(fullDetail.isDisabled(), "LoD thresholds have no effect while full detail is forced");
        assertTrue(mediumDetail.isDisabled());
        assertEquals(TerrainLod.FULL, TerrainLod.select(1, TerrainLod.DISTANT));
        assertEquals(lodRevision, BoardGeometry.terrainRevision(), "The checkbox must not trigger a whole-board rebuild");
        int revision = BoardGeometry.revision();
        SelectBox<String> concrete = GpuBoardTestUi.tuning(tuning, "tuning-concrete-shapes");
        assertEquals(3, concrete.getItems().size);
        concrete.setSelected("Everywhere");
        assertEquals(BoardConcrete.Mode.EVERYWHERE, BoardConcrete.mode());
        concrete.setSelected("None");
        assertEquals(BoardConcrete.Mode.OFF, BoardConcrete.mode());
        concrete.setSelected("Water only");
        assertEquals(BoardConcrete.Mode.WATER_ONLY, BoardConcrete.mode());
        assertTerrainHelp(tuning, tuning.terrainRows());
        CheckBox wetCliffs = GpuBoardTestUi.tuning(tuning, "tuning-cliffs-into-water");
        assertEquals(BoardRelief.DEFAULT_CLIFFS_INTO_WATER, wetCliffs.isChecked());
        int cliffRevision = BoardGeometry.revision();
        press(tuning, "tuning-cliffs-into-water");
        assertEquals(!BoardRelief.DEFAULT_CLIFFS_INTO_WATER, BoardRelief.tuning().cliffsIntoWater());
        assertTrue(BoardGeometry.revision() > cliffRevision, "Cliff edits invalidate terrain and unit support");
        set(tuning, "River width (%)", 5);
        assertEquals(.05f, BoardRelief.tuning().riverWidth(), .0001f);
        set(tuning, "Shore spread", -12);
        set(tuning, "Land retained", .85f);
        assertEquals(-12, BoardRelief.tuning().shoreSpread());
        assertEquals(.85f, BoardRelief.tuning().landKeep(), .0001f);
        assertTrue(BoardGeometry.revision() > revision, "Terrain edits invalidate cached support and picking");
        set(tuning, "Wet margin", 0);
        assertEquals(.1f, BoardSurface.tuning().hug(), .0001f, "Banks require a positive wet margin");
        assertEquals(.1f, GpuBoardTestUi.<Slider>tuning(tuning, "Wet margin").getValue(), .0001f);
        set(tuning, "Beach width", 2);
        set(tuning, "Mouth opening", 8);
        assertEquals(2, BoardSurface.tuning().mouthOpening(), "The opening cannot remove more than its beach");
        assertEquals(2, GpuBoardTestUi.<Slider>tuning(tuning, "Mouth opening").getValue());
        press(tuning, "tuning-falls-off-board");
        assertEquals(!BoardSurface.DEFAULTS.fallsOffBoard(), BoardSurface.tuning().fallsOffBoard());
        SelectBox<String> family = GpuBoardTestUi.tuning(tuning, "tuning-geology-family");
        family.setSelectedIndex(BoardScene.Surface.SAND.ordinal());
        set(tuning, "Ground relief (m)", .2f);
        set(tuning, "Loose stones / hex", 0);
        set(tuning, "Low shrubs / hex", 2);
        assertEquals(.2f, BoardRelief.geology().get(BoardScene.Surface.SAND.ordinal()).relief(), .0001f);
        assertEquals(0, BoardRelief.geology().get(BoardScene.Surface.SAND.ordinal()).stones());
        assertEquals(2, BoardRelief.geology().get(BoardScene.Surface.SAND.ordinal()).shrubs());
        assertEquals(BoardRelief.defaultGeology().get(0), BoardRelief.geology().get(0),
              "Editing sand does not change grass");
        revision = BoardGeometry.revision();
        family.setSelectedIndex(0);
        family.setSelectedIndex(BoardScene.Surface.SAND.ordinal());
        assertEquals(revision, BoardGeometry.revision(), "Browsing materials does not change their geometry");
        assertEquals(.2f, GpuBoardTestUi.<Slider>tuning(tuning, "Ground relief (m)").getValue(), .0001f);
        List<String> bedrockUnused = List.of("Ground relief (m)", "Corner rounding", "Loose stones / hex",
              "Low shrubs / hex");
        family.setSelectedIndex(BoardScene.Surface.values().length);
        for (String name : bedrockUnused) {
            assertTrue(GpuBoardTestUi.<Slider>tuning(tuning, name).isDisabled(), name + " does not apply to bedrock");
        }
        family.setSelectedIndex(BoardScene.Surface.SAND.ordinal());
        for (String name : bedrockUnused) {
            assertFalse(GpuBoardTestUi.<Slider>tuning(tuning, name).isDisabled(), name + " applies to surface materials");
        }
        press(tuning, "tuning-defaults");
        assertEquals(BoardRelief.DEFAULTS, BoardRelief.tuning());
        assertTrue(TerrainLod.enabled());
        assertTrue(terrainLod.isChecked(), "Defaults restore the terrain LoD checkbox");
        assertFalse(fullDetail.isDisabled());
        assertFalse(mediumDetail.isDisabled());
        assertEquals(TerrainLod.DEFAULTS, TerrainLod.tuning());
        assertEquals(BoardRelief.DEFAULT_CLIFFS_INTO_WATER, wetCliffs.isChecked());
        assertEquals(BoardSurface.DEFAULTS, BoardSurface.tuning());
        assertEquals(BoardConcrete.DEFAULT_MODE, BoardConcrete.mode());
        assertEquals(BoardConcrete.DEFAULT_MODE.ordinal(), concrete.getSelectedIndex());
        assertEquals(BoardRelief.defaultGeology(), BoardRelief.geology());
    }

    private static void checkTerrainRendering(GpuBoardTuning tuning, BoardScene scene) {
        var terrain = new GpuTerrain();
        var camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.fit(scene);
        try {
            int original = terrainPixels(terrain, camera, scene);
            set(tuning, "Transition room (m)", 0);
            assertNotEquals(original, terrainPixels(terrain, camera, scene),
                  "Terrain sliders rebuild the rendered board even when its snapshot is unchanged");
            press(tuning, "tuning-defaults");
            assertEquals(original, terrainPixels(terrain, camera, scene), "Defaults restores the original terrain mesh");
        } finally {
            terrain.dispose();
        }
    }

    private static void assertTerrainHelp(GpuBoardTuning tuning, Group group) {
        for (var actor : group.getChildren()) {
            if (actor instanceof Slider slider) {
                Label help = tuning.terrainRows().findActor("tuning-help-" + slider.getName());
                assertNotNull(help, "Visible explanation for " + slider.getName());
                assertFalse(help.getText().isEmpty());
            }
            if (actor instanceof Group nested) { assertTerrainHelp(tuning, nested); }
        }
    }

    private static int terrainPixels(GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        terrain.update(scene);
        terrain.renderShadows(List.of());
        ScreenUtils.clear(.12f, .16f, .2f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            return pixels.getPixels().hashCode();
        } finally {
            pixels.dispose();
        }
    }

    private static void set(GpuBoardTuning tuning, String name, float value) {
        GpuBoardTestUi.<Slider>tuning(tuning, name).setValue(value);
    }

    /** A press on the model's button, as the HUD's tuning panel gives it. */
    private static void press(GpuBoardTuning tuning, String name) {
        GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(tuning, name));
    }
}
