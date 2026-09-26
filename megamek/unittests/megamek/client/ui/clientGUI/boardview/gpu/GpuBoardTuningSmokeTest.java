/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuBoardTuningSmokeTest {
    @Test
    void slidersDebounceAndSupportKeyboardNavigation() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var skin = new GpuBoardSkin();
                var stage = new Stage(new ScreenViewport());
                try {
                    var tuning = new GpuBoardTuning(skin.skin);
                    stage.addActor(tuning.panel());
                    Gdx.input.setInputProcessor(new InputMultiplexer(stage));
                    var dock = new GpuPanelDock(skin.skin, () -> { }, null, tuning.panel());
                    dock.resize(1280, 800, 0, 0, 0, 0);
                    dock.show(tuning.panel());
                    stage.act(0);
                    stage.draw();
                    checkSliderKeyboard(tuning, stage);
                    checkTerrainDrag(tuning, stage, tuning.panel().findActor("tuning-terrain-scroll"));
                    GpuBoardTestUi.click("tuning-defaults");
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    stage.dispose();
                    skin.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

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
        var stage = new Stage(new ScreenViewport());
        try {
            var camera = new BoardCamera();
            camera.resize(1280, 800);
            var tuning = new GpuBoardTuning(skin.skin, source, camera);
            tuning.useScenario(initial, true);
            stage.addActor(tuning.panel());
            Gdx.input.setInputProcessor(new InputMultiplexer(stage));
            var dock = new GpuPanelDock(skin.skin, () -> { }, null, tuning.panel());
            dock.resize(1280, 800, 0, 0, 0, 0);
            dock.show(tuning.panel());
            stage.act(0);
            stage.draw();
            assertTrue(tuning.panel().findActor("tuning-general-scroll").isVisible());
            assertFalse(tuning.panel().findActor("tuning-scroll").isVisible());
            assertFalse(tuning.panel().findActor("tuning-terrain-scroll").isVisible());
            Slider cameraFov = tuning.panel().findActor("tuning-camera-fov");
            assertTrue(cameraFov.isDisabled());
            GpuBoardTestUi.click("tuning-perspective");
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
                Slider slider = tuning.panel().findActor("tuning-size-" + family.name());
                assertEquals(family.defaultUnitScale, slider.getValue(), .0001f,
                      "Family sizes start at their defaults");
                slider.setValue(1.5f);
                assertEquals(1.5f, family.UNIT_SCALE);
                assertEquals(family.heightScale(), family.HEIGHT_SCALE, "Uniform size does not change height proportions");
            }
            capture(stage, "tuning-general.png");
            SelectBox<GpuGraphicsCard> card = tuning.panel().findActor("tuning-graphics-card");
            assertEquals(GpuGraphicsCard.cards().isEmpty(), card == null, "A card choice needs two cards to pick");
            if (card != null) {
                card.showList();
                stage.act(0.3f);
                capture(stage, "tuning-graphics-card.png");
                card.hideList();
                stage.act(0.3f);
            }
            GpuBoardTestUi.click("tuning-defaults");
            assertFalse(camera.perspective());
            assertTrue(cameraFov.isDisabled());
            assertEquals(BoardCamera.DEFAULT_FIELD_OF_VIEW, camera.fieldOfView());
            assertEquals(BoardCamera.DEFAULT_FIELD_OF_VIEW, cameraFov.getValue());
            for (var family : UnitFamilyScale.values()) {
                assertEquals(family.defaultUnitScale, family.UNIT_SCALE, .0001f);
            }
            ScrollPane generalScroll = tuning.panel().findActor("tuning-general-scroll");
            generalScroll.setScrollPercentY(0.6f);
            generalScroll.updateVisualScroll();
            float generalPosition = generalScroll.getScrollY();
            GpuBoardTestUi.click("tuning-tab-atmosphere");
            assertFalse(generalScroll.isVisible());
            assertTrue(tuning.panel().findActor("tuning-scroll").isVisible());
            GpuBoardTestUi.click("tuning-tab-general");
            assertEquals(generalPosition, generalScroll.getScrollY(), "Each tab preserves its scroll position");
            checkTerrainControls(tuning, stage);
            checkTerrainRendering(tuning, source.takeFrame().scene());
            var defaultEffects = tuning.atmosphereOptions();
            assertNull(tuning.panel().findActor("Speed gain / hex"));
            set(tuning, "God rays", 0.8f);
            set(tuning, "Cloud shadow min", 0.3f);
            set(tuning, "Cloud shadow max", 0.9f);
            set(tuning, "Moon shadow contrast", 0.45f);
            set(tuning, "Sun glare", 0.2f);
            set(tuning, "Fog height variation", 0.5f);
            set(tuning, "Fog density variation", 0.4f);
            set(tuning, "Taint strength", 1.5f);
            for (var preset : AtmospherePreset.values()) {
                GpuBoardTestUi.click("atmosphere-" + preset.name());
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
            GpuBoardTestUi.click("atmosphere-FULL_MOON");
            GpuBoardTestUi.click("tuning-moonlight");
            assertFalse(tuning.atmosphere().moonlight());
            assertFalse(BoardAtmosphere.lighting(tuning.atmosphere()).hasDirectLight());
            GpuBoardTestUi.click("atmosphere-PITCH_BLACK");
            assertEquals(-1, tuning.atmosphere().exposure());
            assertFalse(tuning.panel().<CheckBox>findActor("tuning-moonlight").isChecked());
            GpuBoardTestUi.click("atmosphere-MOONLESS");
            assertEquals(-0.6f, tuning.atmosphere().exposure(), 0.00001f);
            assertFalse(tuning.atmosphere().moonlight());
            set(tuning, "Time of day", 12);
            assertTrue(BoardAtmosphere.lighting(tuning.atmosphere()).hasDirectLight());
            set(tuning, "Time of day", 0);
            assertFalse(BoardAtmosphere.lighting(tuning.atmosphere()).hasDirectLight());

            GpuBoardTestUi.click("atmosphere-RAIN_STORM");
            assertTrue(BoardAtmosphere.wetness(tuning.atmosphere()) > 0);
            set(tuning, "Temperature (C)", 0);
            assertEquals(0, BoardAtmosphere.wetness(tuning.atmosphere()));
            set(tuning, "Temperature (C)", 10);
            assertTrue(BoardAtmosphere.wetness(tuning.atmosphere()) > 0);
            SelectBox<Atmosphere> pressure = tuning.panel().findActor("tuning-atmosphere-pressure");
            pressure.setSelected(Atmosphere.VACUUM);
            assertEquals(BoardAtmosphere.Effects.NONE, tuning.atmosphere().effects());
            assertEquals(0, tuning.atmosphere().clouds());
            assertTrue(tuning.panel().<Slider>findActor("Rain").isDisabled());
            pressure.setSelected(Atmosphere.STANDARD);
            assertFalse(tuning.panel().<Slider>findActor("Rain").isDisabled());
            SelectBox<AtmosphericTaint> taint = tuning.panel().findActor("tuning-atmospheric-taint");
            taint.setSelected(AtmosphericTaint.TOXIC_POISON);
            assertEquals(taint.getSelected(), tuning.atmosphere().taint());
            assertFalse(tuning.panel().<Slider>findActor("Taint strength").isDisabled());
            set(tuning, "Ground fog", 0.6f);
            assertEquals(AtmosphericTaint.TOXIC_POISON, tuning.atmosphere().taint());
            capture(stage, "tuning-atmosphere.png");
            GpuBoardTestUi.click("tuning-atmospheric-taint");
            assertTrue(taint.getScrollPane().hasParent(), "The dropdown must open through actual pointer input");
            stage.act(0.3f);
            capture(stage, "tuning-taint-choices.png");
            taint.hideList();
            stage.act(0.3f);

            GpuBoardTestUi.click("tuning-defaults");
            assertEquals(initial, tuning.atmosphere());
            assertEquals(defaultEffects, tuning.atmosphereOptions());
            for (int[] size : new int[][] { { 1280, 800 }, { 900, 600 } }) {
                dock.resize(size[0], size[1], 30, 45, 0, 0);
                stage.act(0);
                stage.draw();
                GpuBoardTestUi.assertHorizontalBounds(tuning.panel(), tuning.panel());
                assertTrue(tuning.panel().getTop() <= size[1] - 30);
                assertTrue(tuning.panel().getY() >= 45);
                GpuBoardTestUi.click("tuning-tab-general");
                GpuBoardTestUi.assertHorizontalBounds(tuning.panel(), tuning.panel());
                GpuBoardTestUi.click("tuning-tab-terrain");
                GpuBoardTestUi.assertHorizontalBounds(tuning.panel(), tuning.panel());
                capture(stage, "tuning-terrain-" + size[0] + ".png");
                GpuBoardTestUi.click("tuning-tab-atmosphere");
            }
            GpuBoardTestUi.click("atmosphere-DAWN");
            capture(stage, "tuning-presets-small.png");
            ScrollPane scroll = tuning.panel().findActor("tuning-scroll");
            scroll.setScrollPercentY(1);
            scroll.updateVisualScroll();
            capture(stage, "tuning-effects-small.png");
        } finally {
            BoardConcrete.tune(BoardConcrete.DEFAULT_MODE);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            BoardSurface.tune(BoardSurface.DEFAULTS);
            BoardRelief.tuneGeology(BoardRelief.defaultGeology());
            stage.dispose();
            skin.dispose();
        }
    }

    private static void checkTerrainControls(GpuBoardTuning tuning, Stage stage) {
        GpuBoardTestUi.click("tuning-tab-terrain");
        ScrollPane scroll = tuning.panel().findActor("tuning-terrain-scroll");
        assertTrue(scroll.isVisible());
        assertFalse(tuning.panel().findActor("tuning-general-scroll").isVisible());
        assertFalse(tuning.panel().findActor("tuning-scroll").isVisible());
        int revision = BoardGeometry.revision();
        SelectBox<String> concrete = tuning.panel().findActor("tuning-concrete-shapes");
        assertEquals(3, concrete.getItems().size);
        concrete.setSelected("Everywhere");
        assertEquals(BoardConcrete.Mode.EVERYWHERE, BoardConcrete.mode());
        concrete.setSelected("None");
        assertEquals(BoardConcrete.Mode.OFF, BoardConcrete.mode());
        concrete.setSelected("Water only");
        assertEquals(BoardConcrete.Mode.WATER_ONLY, BoardConcrete.mode());
        checkTerrainDrag(tuning, stage, scroll);
        assertTerrainHelp(tuning, scroll);
        CheckBox wetCliffs = tuning.panel().findActor("tuning-cliffs-into-water");
        assertEquals(BoardRelief.DEFAULT_CLIFFS_INTO_WATER, wetCliffs.isChecked());
        int cliffRevision = BoardGeometry.revision();
        GpuBoardTestUi.click("tuning-cliffs-into-water");
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
        assertEquals(.1f, tuning.panel().<Slider>findActor("Wet margin").getValue(), .0001f);
        set(tuning, "Beach width", 2);
        set(tuning, "Mouth opening", 8);
        assertEquals(2, BoardSurface.tuning().mouthOpening(), "The opening cannot remove more than its beach");
        assertEquals(2, tuning.panel().<Slider>findActor("Mouth opening").getValue());
        GpuBoardTestUi.click("tuning-falls-off-board");
        assertEquals(!BoardSurface.DEFAULTS.fallsOffBoard(), BoardSurface.tuning().fallsOffBoard());
        SelectBox<String> family = tuning.panel().findActor("tuning-geology-family");
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
        assertEquals(.2f, tuning.panel().<Slider>findActor("Ground relief (m)").getValue(), .0001f);
        List<String> bedrockUnused = List.of("Ground relief (m)", "Corner rounding", "Loose stones / hex",
              "Low shrubs / hex");
        family.setSelectedIndex(BoardScene.Surface.values().length);
        for (String name : bedrockUnused) {
            assertTrue(tuning.panel().<Slider>findActor(name).isDisabled(), name + " does not apply to bedrock");
        }
        family.setSelectedIndex(BoardScene.Surface.SAND.ordinal());
        for (String name : bedrockUnused) {
            assertFalse(tuning.panel().<Slider>findActor(name).isDisabled(), name + " applies to surface materials");
        }
        scroll.setScrollPercentY(1);
        scroll.updateVisualScroll();
        capture(stage, "tuning-terrain-geology.png");
        float position = scroll.getScrollY();
        GpuBoardTestUi.click("tuning-tab-general");
        GpuBoardTestUi.click("tuning-tab-terrain");
        assertEquals(position, scroll.getScrollY());
        GpuBoardTestUi.click("tuning-defaults");
        assertEquals(BoardRelief.DEFAULTS, BoardRelief.tuning());
        assertEquals(BoardRelief.DEFAULT_CLIFFS_INTO_WATER, wetCliffs.isChecked());
        assertEquals(BoardSurface.DEFAULTS, BoardSurface.tuning());
        assertEquals(BoardConcrete.DEFAULT_MODE, BoardConcrete.mode());
        assertEquals(BoardConcrete.DEFAULT_MODE.ordinal(), concrete.getSelectedIndex());
        assertEquals(BoardRelief.defaultGeology(), BoardRelief.geology());
        scroll.setScrollPercentY(0);
        scroll.updateVisualScroll();
        capture(stage, "tuning-terrain.png");
    }

    private static void checkTerrainDrag(GpuBoardTuning tuning, Stage stage, ScrollPane scroll) {
        Slider slider = tuning.panel().findActor("River width (%)");
        Vector2 position = slider.localToAscendantCoordinates(scroll.getWidget(), new Vector2());
        scroll.scrollTo(position.x, position.y, slider.getWidth(), slider.getHeight(), false, true);
        scroll.updateVisualScroll();
        stage.draw();
        Vector2 start = stage.stageToScreenCoordinates(slider.localToStageCoordinates(
              new Vector2(slider.getWidth() * .25f, slider.getHeight() / 2)));
        Vector2 end = stage.stageToScreenCoordinates(slider.localToStageCoordinates(
              new Vector2(slider.getWidth() * .75f, slider.getHeight() / 2)));
        int revision = BoardGeometry.revision();
        float original = BoardRelief.tuning().riverWidth();
        var input = Gdx.input.getInputProcessor();
        input.touchDown((int) start.x, (int) start.y, 0, Input.Buttons.LEFT);
        assertTrue(slider.isDragging(), "The real pointer must capture the terrain slider");
        assertSame(slider, stage.getKeyboardFocus(), "Clicking a slider gives it keyboard focus");
        stage.act(.06f);
        assertEquals(revision, BoardGeometry.revision(), "Terrain edits wait for the debounce");
        input.touchDragged((int) end.x, (int) end.y, 0);
        stage.act(.06f);
        assertNotEquals(original, slider.getValue() / 100, "The control previews its dragged value");
        assertEquals(revision, BoardGeometry.revision(), "Moving the slider restarts the debounce");
        assertEquals(original, BoardRelief.tuning().riverWidth(), "The board keeps its terrain while the value is moving");
        input.touchDown((int) end.x, (int) end.y, 1, Input.Buttons.LEFT);
        input.touchUp((int) end.x, (int) end.y, 1, Input.Buttons.LEFT);
        assertTrue(slider.isDragging());
        assertEquals(revision, BoardGeometry.revision(), "A rejected second pointer must not commit the active drag");
        stage.act(.05f);
        assertTrue(slider.isDragging());
        assertEquals(revision + 1, BoardGeometry.revision(), "Pausing for 100 ms applies without releasing the pointer");
        assertEquals(slider.getValue() / 100, BoardRelief.tuning().riverWidth(), .0001f);
        stage.act(.2f);
        assertEquals(revision + 1, BoardGeometry.revision(), "A stationary slider applies only once");
        input.touchDragged((int) start.x, (int) start.y, 0);
        stage.act(.05f);
        assertEquals(revision + 1, BoardGeometry.revision());
        input.touchUp((int) start.x, (int) start.y, 0, Input.Buttons.LEFT);
        assertFalse(slider.isDragging());
        assertEquals(revision + 2, BoardGeometry.revision(), "Releasing applies the latest value immediately");
        stage.act(.2f);
        assertEquals(revision + 2, BoardGeometry.revision(), "Release cancels the pending debounce");
        assertEquals(slider.getValue() / 100, BoardRelief.tuning().riverWidth(), .0001f);
        input.touchDown((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
        stage.act(.11f);
        input.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
        assertEquals(revision + 3, BoardGeometry.revision(), "Release after a pause does not rebuild unchanged terrain");
        assertEquals(slider.getValue() / 100, BoardRelief.tuning().riverWidth(), .0001f);
    }

    private static void checkSliderKeyboard(GpuBoardTuning tuning, Stage stage) {
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.click("tuning-tab-general");
        Slider slider = tuning.panel().findActor("Unit scale");
        var normalStyle = slider.getStyle();
        GpuBoardTestUi.click("Unit scale");
        assertSame(slider, stage.getKeyboardFocus());
        assertSame(normalStyle.knobOver, slider.getStyle().knob, "Keyboard focus remains visible after clicking");
        float value = slider.getValue();
        assertTrue(input.keyDown(Input.Keys.RIGHT));
        input.keyUp(Input.Keys.RIGHT);
        assertEquals(value + slider.getStepSize(), slider.getValue(), .0001f);
        assertEquals(slider.getValue(), BoardGeometry.tuning().unitScale(), .0001f);
        assertTrue(input.keyDown(Input.Keys.LEFT));
        input.keyUp(Input.Keys.LEFT);
        assertEquals(value, slider.getValue(), .0001f);
        slider.setValue(slider.getMaxValue());
        input.keyDown(Input.Keys.RIGHT);
        assertEquals(slider.getMaxValue(), slider.getValue(), "Keyboard edits respect the slider bounds");
        slider.setValue(slider.getMinValue());
        input.keyDown(Input.Keys.LEFT);
        assertEquals(slider.getMinValue(), slider.getValue());
        input.keyDown(Input.Keys.DOWN);
        assertSame(tuning.panel().findActor("Unit height scale"), stage.getKeyboardFocus());
        assertSame(normalStyle, slider.getStyle(), "Losing focus restores the ordinary slider appearance");
        input.keyDown(Input.Keys.UP);
        assertSame(slider, stage.getKeyboardFocus());
        input.keyDown(Input.Keys.UP);
        assertSame(tuning.panel().findActor("Hex scale"), stage.getKeyboardFocus());
        input.keyDown(Input.Keys.UP);
        assertSame(tuning.panel().findActor("tuning-perspective"), stage.getKeyboardFocus(),
              "Navigation skips disabled inputs and includes checkboxes");
        input.keyDown(Input.Keys.SPACE);
        assertFalse(tuning.panel().<Slider>findActor("tuning-camera-fov").isDisabled());
        input.keyDown(Input.Keys.DOWN);
        assertSame(tuning.panel().findActor("tuning-camera-fov"), stage.getKeyboardFocus());

        GpuBoardTestUi.click("tuning-tab-terrain");
        assertNull(stage.getKeyboardFocus(), "Switching tabs releases focus from the hidden tab");
        Slider river = tuning.panel().findActor("River width (%)");
        GpuBoardTestUi.click("River width (%)");
        value = river.getValue();
        input.keyDown(Input.Keys.RIGHT);
        assertEquals((value + river.getStepSize()) / 100, BoardRelief.tuning().riverWidth(), .0001f);
        input.keyDown(Input.Keys.UP);
        assertSame(tuning.panel().findActor("tuning-concrete-shapes"), stage.getKeyboardFocus(),
              "Navigation includes dropdowns");
        input.keyDown(Input.Keys.DOWN);
        assertSame(river, stage.getKeyboardFocus());
        ScrollPane scroll = tuning.panel().findActor("tuning-terrain-scroll");
        float before = scroll.getScrollY();
        for (int i = 0; i < 10; i++) { input.keyDown(Input.Keys.DOWN); }
        assertSame(tuning.panel().findActor("Shore blend"), stage.getKeyboardFocus());
        assertTrue(scroll.getScrollY() > before, "Keyboard navigation scrolls the next input into view");
        SelectBox<String> family = tuning.panel().findActor("tuning-geology-family");
        family.setSelectedIndex(BoardScene.Surface.values().length);
        GpuBoardTestUi.click("Recess (m)");
        input.keyDown(Input.Keys.DOWN);
        assertSame(tuning.panel().findActor("Caprock scale"), stage.getKeyboardFocus(),
              "Navigation skips terrain inputs disabled for this material");
        GpuBoardTestUi.click("tuning-defaults");
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
            GpuBoardTestUi.click("tuning-defaults");
            assertEquals(original, terrainPixels(terrain, camera, scene), "Defaults restores the original terrain mesh");
        } finally {
            terrain.dispose();
        }
    }

    private static void assertTerrainHelp(GpuBoardTuning tuning, Group group) {
        for (var actor : group.getChildren()) {
            if (actor instanceof Slider slider) {
                Label help = tuning.panel().findActor("tuning-help-" + slider.getName());
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
        tuning.panel().<Slider>findActor(name).setValue(value);
    }

    private static void capture(Stage stage, String name) {
        ScreenUtils.clear(0.12f, 0.16f, 0.2f, 1);
        stage.act(0);
        stage.draw();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            var directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(directory.isDirectory() || directory.mkdirs());
            PixmapIO.writePNG(new FileHandle(new File(directory, name)), pixels, -1, true);
        } finally {
            pixels.dispose();
        }
    }
}
