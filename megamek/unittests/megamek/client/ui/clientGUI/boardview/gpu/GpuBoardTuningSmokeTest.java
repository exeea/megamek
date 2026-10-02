/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
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
            var tuning = new GpuBoardTuning(skin.skin, source);
            tuning.useScenario(initial, true);
            for (var family : UnitFamilyScale.values()) {
                Slider slider = GpuBoardTestUi.tuning(tuning, "tuning-size-" + family.name());
                assertEquals(1, slider.getValue(), "Family sizes start neutral");
                slider.setValue(1.5f);
                assertEquals(1.5f, family.UNIT_SCALE);
                assertEquals(family.heightScale(), family.HEIGHT_SCALE, "Uniform size does not change height proportions");
            }
            press(tuning, "tuning-defaults");
            for (var family : UnitFamilyScale.values()) { assertEquals(1, family.UNIT_SCALE); }
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
            skin.dispose();
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
