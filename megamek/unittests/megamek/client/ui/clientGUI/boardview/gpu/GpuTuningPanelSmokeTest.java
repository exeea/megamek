/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.CancelAction;
import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The developer tuning utility (C.1 G17) in the real HUD over a real {@link GpuBoardTuning} model: the utility ends the
 * utility row and opens the panel at its place, one dialog at a time; every control of the model's two pages has its
 * hud-v3 control on the panel, which edits the model, group by group, and shows the model's own changes; the Camera
 * page sets the fixed sun and the board camera's framing; Defaults restores both pages; "Planetary conditions…" opens
 * the existing Swing editor.
 */
@Tag("on-demand")
class GpuTuningPanelSmokeTest {
    private static final int[][] SIZES = { { 1920, 1080 }, { 1280, 720 }, { 900, 600 } };
    private static final float NEAR = .0001f;

    /** The real HUD at the harness's size, with a real tuning model over a mocked source and the HUD's camera. */
    private static final class Hud implements AutoCloseable {
        final GpuHudTestStage harness;
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final BoardCamera camera = new BoardCamera();
        final SpriteBatch batch = new SpriteBatch();
        final GpuBoardSource.Frame frame = GpuHudInputTest.frame(
              GpuHudInputTest.status(1, GamePhase.MOVEMENT, false, Entity.NONE, 0),
              GpuHudInputTest.panels(GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY,
                    GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
        final GpuBoardTuning tuning;
        final GpuHud hud;

        Hud(GpuHudTestStage harness) {
            this.harness = harness;
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            // A preset previews its own conditions, as GpuBoardSource.preview does with its time sample.
            when(source.preview(any(AtmospherePreset.class)))
                  .thenAnswer(call -> call.<AtmospherePreset>getArgument(0).settings(.5));
            tuning = new GpuBoardTuning(harness.theme.skin, source);
            hud = new GpuHud(source, harness.theme.skin, batch, camera, tuning,
                  new GpuPlaybackHistory(new UnitPlayback()));
            resize();
        }

        /** One stage unit per back-buffer pixel at the harness's window size. */
        void resize() {
            float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
            hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density), 1 / density);
        }

        /** One frame: the snapshots, then a draw, which lays the HUD out. */
        void show() {
            hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudInputTest.preferences());
            ScreenUtils.clear(.1f, .13f, .13f, 1, true);
            hud.draw();
        }

        <T extends Actor> T find(String name) {
            T actor = hud.stage.getRoot().findActor(name);
            assertNotNull(actor, name);
            return actor;
        }

        /**
         * Writes {name}.png once the last click's visual press (Scene2D's 0.1 s) is over, with the panel's texts
         * checked for missing message keys.
         */
        void capture(String name) throws InterruptedException {
            Thread.sleep(150);
            show();
            UiTestStage.assertTexts(find("tuning-panel"));
            harness.capture(name).dispose();
        }

        /** The model's control of that name, on either of its pages. */
        <T extends Actor> T model(String name) {
            T actor = tuning.boardRows().findActor(name);
            if (actor == null) {
                actor = tuning.atmosphereRows().findActor(name);
            }
            assertNotNull(actor, name);
            return actor;
        }

        /** A left click at the actor's centre through the HUD's stage, once its page shows it; then a frame. */
        void click(Actor actor) {
            reveal(actor);
            Vector2 point = hud.stage.stageToScreenCoordinates(
                  actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
            hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            show();
        }

        void click(String name) {
            click(find(name));
        }

        /** A press near the right end of the slider, as a drag ends there; then a frame. */
        void pressEnd(Slider slider) {
            reveal(slider);
            Vector2 point = hud.stage.stageToScreenCoordinates(
                  slider.localToStageCoordinates(new Vector2(slider.getWidth() - 1, slider.getHeight() / 2)));
            hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            show();
        }

        /** Sets the panel's slider as its drag does (Slider.calculatePositionAndValue), then a frame. */
        void drag(String name, float value) {
            Slider slider = find(name);
            assertFalse(slider.isDisabled(), name + " is enabled");
            slider.setValue(value);
            show();
        }

        /** Opens the panel's choice and clicks the item of that text in its list. */
        void choose(String name, Object item) {
            click(name);
            UiButton row = button(find("tuning-choices"), String.valueOf(item));
            assertNotNull(row, item + " in the list of " + name);
            click(row);
            assertFalse(find("tuning-choices").isVisible(), "a choice closes the list");
        }

        /** Whether the HUD takes a press at the stage point (y up), as GpuBattleView asks before the board. */
        boolean hits(float x, float y) {
            Vector2 screen = hud.stage.stageToScreenCoordinates(new Vector2(x, y));
            return hud.hit(Math.round(screen.x), Math.round(screen.y));
        }

        void key(KeyCommandBind bind) {
            hud.keyDown(Input.Keys.UNKNOWN, bind.keyDefault, bind.modifiersDefault);
            hud.keyUp(Input.Keys.UNKNOWN, bind.keyDefault);
            show();
        }

        /** Scrolls the page that holds the actor so that the actor shows. */
        private void reveal(Actor actor) {
            for (Group parent = actor.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof ScrollPane page) {
                    Vector2 corner = actor.localToAscendantCoordinates(page.getActor(), new Vector2());
                    page.scrollTo(corner.x, corner.y, actor.getWidth(), actor.getHeight());
                    page.updateVisualScroll();
                    show();
                    return;
                }
            }
        }

        @Override
        public void close() {
            // Geometry and family sizes are global: Defaults puts them back for the next test.
            fire(tuning.defaults());
            hud.dispose();
            batch.dispose();
        }
    }

    @Test
    void theUtilityEndsTheRowAndOpensThePanelAtItsPlaceOneDialogAtATime() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                for (int[] size : SIZES) {
                    harness.size(size[0], size[1]);
                    hud.resize();
                    hud.show();
                    GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                    String at = size[0] + " x " + size[1];
                    UiButton utility = hud.find("tuning-button");
                    Rectangle button = GpuHudTestStage.bounds(utility);
                    Rectangle menu = GpuHudTestStage.bounds(hud.find("utility-menu"));
                    assertEquals(size[0] - metrics.gap(), button.x + button.width, .5f, "last, at the right gap " + at);
                    assertEquals(menu.x + menu.width + GpuUtilityBar.GAP, button.x, .5f, "one gap after Menu " + at);
                    assertEquals(menu.y, button.y, .5f, "on the row's line " + at);
                    assertEquals(metrics.narrow() ? 54 : 62, button.width, .5f, "the utility's width " + at);
                    assertFalse(hud.find("tuning-panel").isVisible(), "closed at first");

                    hud.click(utility);
                    assertSame(GpuHudState.Dialog.TUNING, hud.hud.state.dialog);
                    assertTrue(utility.isChecked(), "the utility is pressed while the panel is open");
                    Rectangle panel = GpuHudTestStage.bounds(hud.find("tuning-frame"));
                    assertEquals(size[0] - metrics.gap(), panel.x + panel.width, .5f, "at the right gap " + at);
                    assertEquals(360, panel.width, .5f, at);
                    assertEquals(90, size[1] - panel.y - panel.height, .5f, "90 below the top " + at);
                    assertEquals(size[1] - 160, panel.height, .5f, "the long Board page fills H - 160 " + at);
                    assertTrue(hud.<ScrollPane>find("tuning-board").isScrollY(), "and scrolls " + at);
                    assertTrue(hud.hits(panel.x + 3, panel.y + panel.height - 3), "the panel takes its presses");
                    assertFalse(hud.hits(size[0] / 2f, size[1] / 2f), "presses beside it reach the board");
                    for (String page : List.of("tuning-board", "tuning-atmosphere", "tuning-camera")) {
                        hud.click(page + "-tab");
                        GpuBoardTestUi.assertHorizontalBounds(hud.find("tuning-frame"), hud.find("tuning-frame"));
                    }
                    hud.click("tuning-board-tab");
                    hud.capture("tuning-hud-" + size[0] + "x" + size[1]);

                    hud.key(KeyCommandBind.KEY_BINDS);
                    assertSame(GpuHudState.Dialog.HELP, hud.hud.state.dialog, "Help replaces the panel " + at);
                    assertFalse(hud.find("tuning-panel").isVisible());
                    assertFalse(utility.isChecked());
                    hud.click(utility);
                    assertSame(GpuHudState.Dialog.TUNING, hud.hud.state.dialog, "and the utility replaces Help");
                    hud.key(KeyCommandBind.CANCEL);
                    assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog, "Esc closes the panel " + at);
                    assertFalse(hud.find("tuning-panel").isVisible());
                    hud.click(utility);
                    hud.click("tuning-close");
                    assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog, "the close button closes it " + at);
                }
            }
        });
    }

    @Test
    void everyModelControlHasItsControlAndTheBoardPageEditsTheModel() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show();
                hud.click("tuning-button");
                // Every named control of both model pages, on the page's tab, and Defaults, by name and kind.
                List<Actor> board = controls(hud.tuning.boardRows());
                List<Actor> atmosphere = controls(hud.tuning.atmosphereRows());
                hud.click("tuning-atmosphere-tab");
                assertMirrored(hud, atmosphere);
                hud.click("tuning-board-tab");
                assertMirrored(hud, board);
                assertMirrored(hud, List.of(hud.tuning.defaults()));
                List<Actor> every = new ArrayList<>(board);
                every.addAll(atmosphere);
                assertEquals(every.size(), every.stream().map(Actor::getName).distinct().count(),
                      "the model's control names are unique");
                assertTrue(every.size() > 60, "the model's " + every.size() + " controls");

                // Geometry: a press at the slider's end and drags.
                hud.pressEnd(hud.find("Hex scale"));
                assertEquals(3, BoardGeometry.tuning().hexScale(), NEAR, "Hex scale's maximum");
                assertEquals("3.00", reading(hud, "Hex scale"), "the reading is the model's");
                hud.drag("Unit scale", 1.5f);
                hud.drag("Unit height scale", 1.2f);
                hud.drag("Base level height", 24);
                hud.drag("Hex frame shade", .5f);
                hud.drag("Multi-hex unit scale", .75f);
                BoardGeometry.Tuning geometry = BoardGeometry.tuning();
                assertEquals(1.5f, geometry.unitScale(), NEAR);
                assertEquals(1.2f, geometry.unitHeightScale(), NEAR);
                assertEquals(24, geometry.levelHeight());
                assertEquals(.5f, geometry.gridShade(), NEAR);
                assertEquals(.75f, geometry.multiHexUnitScale(), NEAR);

                // Normal maps and VSync.
                assertTrue(hud.tuning.normalMaps());
                hud.click("tuning-normal-maps");
                assertFalse(hud.tuning.normalMaps(), "Normal maps off");
                assertFalse(hud.<UiKit.Checkbox>find("tuning-normal-maps").isTicked());
                boolean vsync = hud.<CheckBox>model("tuning-vsync").isChecked();
                hud.click("tuning-vsync");
                assertEquals(!vsync, hud.<CheckBox>model("tuning-vsync").isChecked(), "VSync switched");

                // Unit family sizes.
                for (UnitFamilyScale family : UnitFamilyScale.values()) {
                    hud.drag("tuning-size-" + family.name(), 1.5f);
                    assertEquals(1.5f, family.UNIT_SCALE, NEAR, family.label);
                }

                // Zoom-out unit scaling (user decision 26): the panel's controls set the growth the 3D view takes on
                // its next frame, from the constants.
                UiKit.Checkbox scaling = hud.find("tuning-zoom-scaling");
                assertEquals(UnitScreenScale.ENABLED, scaling.isTicked(), "the switch starts at its constant");
                assertEquals(String.valueOf(Math.round(UnitScreenScale.THRESHOLD)), reading(hud, "Threshold hex px"));
                if (!scaling.isTicked()) {
                    hud.click(scaling);
                }
                hud.drag("Threshold hex px", 120);
                hud.drag("Max zoom-out scale", 1.5f);
                assertEquals(1, UnitScreenScale.factor(120), NEAR, "from a hex 120 HUD pixels wide");
                assertEquals(1.2f, UnitScreenScale.factor(100), NEAR, "units grow as the hex shrinks");
                assertEquals(1.5f, UnitScreenScale.factor(60), NEAR, "up to the maximum");
                assertEquals("120", reading(hud, "Threshold hex px"), "the reading is the model's");
                hud.click(scaling);
                assertFalse(UnitScreenScale.enabled);
                assertEquals(1, UnitScreenScale.factor(60), NEAR, "switched off, units keep their size");
                assertTrue(hud.<Slider>find("Threshold hex px").isDisabled(), "its values rest while it is off");
                hud.click(scaling);
                assertEquals(1.5f, UnitScreenScale.factor(60), NEAR, "and come back on");
                hud.capture("tuning-zoom-scaling");

                // The route pulse (user item 55): the panel's switch and sliders set the values the overlay's pulse
                // takes on its next frame, from the constants.
                UiKit.Checkbox pulse = hud.find("tuning-route-pulse");
                assertEquals(GpuRoutePulse.ENABLED, pulse.isTicked(), "the pulse's switch starts at its constant");
                if (!pulse.isTicked()) {
                    hud.click(pulse);
                }
                hud.drag("Pulse speed", 2);
                hud.drag("Pulse intensity", .5f);
                assertEquals(2, GpuRoutePulse.speed, NEAR);
                assertEquals(.5f, GpuRoutePulse.intensity, NEAR);
                assertEquals("2.00", reading(hud, "Pulse speed"), "the reading is the model's");
                hud.click(pulse);
                assertFalse(GpuRoutePulse.enabled, "switched off");
                assertTrue(hud.<Slider>find("Pulse intensity").isDisabled(), "its values rest while it is off");
                hud.click(pulse);
                assertTrue(GpuRoutePulse.enabled, "and back on");
                hud.capture("tuning-route-pulse");

                // Unit visibility.
                hud.drag("Building opacity", 40);
                hud.drag("See-through", 25);
                assertEquals(.4f, hud.tuning.buildingOpacity(), NEAR);
                assertEquals(.25f, hud.tuning.seeThrough(), NEAR);

                // Outside field of view and sensor range: the modes are segments of one choice.
                for (GpuFieldOfView.Style style : GpuFieldOfView.Style.values()) {
                    hud.click("fov-style-" + style.name());
                    hud.click("sensor-style-" + style.name());
                    assertSame(style, hud.tuning.fovStyle());
                    assertSame(style, hud.tuning.sensorStyle());
                    for (GpuFieldOfView.Style other : GpuFieldOfView.Style.values()) {
                        assertEquals(other == style, hud.<UiButton>find("fov-style-" + other.name()).isChecked(),
                              "only the chosen mode is pressed");
                    }
                }
                hud.drag("FoV darkness", 30);
                hud.drag("Sensor darkness", 70);
                assertEquals(.3f, hud.tuning.fovDarkness(), NEAR);
                assertEquals(.7f, hud.tuning.sensorDarkness(), NEAR);

                // Unit damage: the preview's slider and location follow the override.
                assertTrue(hud.<Slider>find("Display damage").isDisabled(), "no damage preview without override");
                assertTrue(hud.<UiButton>find("tuning-damage-location").isDisabled());
                hud.click("tuning-override-damage");
                hud.drag("Display damage", .6f);
                assertEquals(.6f, hud.tuning.damageOverride(), NEAR);
                hud.choose("tuning-damage-location", UnitDamageDisplay.Location.LEFT_ARM);
                assertSame(UnitDamageDisplay.Location.LEFT_ARM, hud.tuning.damageLocation());
                assertEquals("Left arm", hud.<UiButton>find("tuning-damage-location").getText().toString());
                hud.click("tuning-override-damage");
                assertEquals(-1, hud.tuning.damageOverride(), "override off");
                hud.capture("tuning-board-edited");

                // Defaults restores the Board page.
                hud.click("tuning-defaults");
                assertEquals(1, BoardGeometry.tuning().hexScale(), NEAR);
                assertTrue(hud.tuning.normalMaps());
                for (UnitFamilyScale family : UnitFamilyScale.values()) {
                    assertEquals(family.defaultUnitScale, family.UNIT_SCALE, NEAR, family.label);
                }
                assertEquals(UnitScreenScale.ENABLED, UnitScreenScale.enabled, "the zoom-out scaling's constants");
                assertEquals(UnitScreenScale.THRESHOLD, UnitScreenScale.threshold, NEAR);
                assertEquals(UnitScreenScale.MAX, UnitScreenScale.max, NEAR);
                assertEquals(UnitScreenScale.ENABLED, hud.<UiKit.Checkbox>find("tuning-zoom-scaling").isTicked());
                assertEquals(GpuRoutePulse.ENABLED, GpuRoutePulse.enabled, "the route pulse's constants");
                assertEquals(GpuRoutePulse.SPEED, GpuRoutePulse.speed, NEAR);
                assertEquals(GpuRoutePulse.INTENSITY, GpuRoutePulse.intensity, NEAR);
                assertEquals("1.00", reading(hud, "Hex scale"), "the panel shows the restored value");
            }
        });
    }

    @Test
    void theAtmospherePageEditsTheModelAndShowsItsOwnChanges() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuAtmosphere.Options initialOptions = hud.tuning.atmosphereOptions();
                hud.show();
                hud.click("tuning-button");
                hud.click("tuning-atmosphere-tab");
                hud.capture("tuning-atmosphere");

                // Presets: the model takes the preset's conditions, and the panel shows the model's new values.
                for (AtmospherePreset preset : AtmospherePreset.values()) {
                    hud.click("atmosphere-" + preset.name());
                    BoardAtmosphere.Settings settings = preset.settings(.5);
                    assertEquals(settings, hud.tuning.atmosphere(), preset.label);
                    assertEquals(settings.clouds(), hud.<Slider>find("Cloud cover").getValue(), .01f, preset.label);
                    assertEquals(settings.moonlight(), hud.<UiKit.Checkbox>find("tuning-moonlight").isTicked());
                }
                hud.click("atmosphere-CLEAR");

                // Lighting.
                hud.drag("Time of day", 12);
                hud.drag("Exposure (EV)", .5f);
                assertEquals(12, hud.tuning.atmosphere().hour(), NEAR);
                assertEquals(.5f, hud.tuning.atmosphere().exposure(), NEAR);
                assertEquals("12:00", reading(hud, "Time of day"));
                boolean moonlight = hud.tuning.atmosphere().moonlight();
                hud.click("tuning-moonlight");
                assertEquals(!moonlight, hud.tuning.atmosphere().moonlight(), "Moonlight switched");
                hud.click("tuning-fixed-sun");
                assertTrue(hud.tuning.fixedSun(), "Fixed sun/moon on the Atmosphere page");
                hud.click("tuning-fixed-sun");
                assertFalse(hud.tuning.fixedSun());

                // Planet properties: pressure and taint through their lists; vacuum disables the weather sliders.
                hud.drag("Gravity (g)", 2);
                assertEquals(2, hud.tuning.atmosphere().gravity(), NEAR);
                assertEquals(2, hud.tuning.gravityOverride(), NEAR);
                hud.drag("Temperature (C)", 0);
                assertEquals(0, hud.tuning.atmosphere().temperature());
                hud.choose("tuning-atmosphere-pressure", Atmosphere.VACUUM);
                assertSame(Atmosphere.VACUUM, hud.tuning.atmosphere().pressure());
                assertTrue(hud.<Slider>find("Rain").isDisabled(), "no rain in vacuum");
                assertTrue(hud.<UiButton>find("weather-toggle-Rain").isDisabled());
                hud.choose("tuning-atmosphere-pressure", Atmosphere.STANDARD);
                assertFalse(hud.<Slider>find("Rain").isDisabled());
                // The taint's list, open over the page: the current choice checked.
                float readings = right(readingOf(hud, "Time of day"));
                hud.click("tuning-atmospheric-taint");
                hud.capture("tuning-choice-open");
                hud.click(button(hud.find("tuning-choices"), String.valueOf(AtmosphericTaint.TOXIC_POISON)));
                assertSame(AtmosphericTaint.TOXIC_POISON, hud.tuning.atmosphere().taint());
                assertEquals(String.valueOf(AtmosphericTaint.TOXIC_POISON),
                      hud.<UiButton>find("tuning-atmospheric-taint").getText().toString(), "the face shows it");
                // A long choice ends in an ellipsis on its face: the page keeps its columns and its width.
                assertEquals(readings, right(readingOf(hud, "Time of day")), .5f, "the readings stay in place");
                GpuBoardTestUi.assertHorizontalBounds(hud.find("tuning-frame"), hud.find("tuning-frame"));
                // An open list closes with the panel and leaves no keyboard focus to swallow the HUD's keys. Keys
                // pass no press outside the list, which would close it by itself: the first Esc ends the list's
                // focus (GpuHud's chain), the second closes the panel.
                hud.click("tuning-atmosphere-pressure");
                assertTrue(hud.find("tuning-choices").isVisible());
                hud.key(KeyCommandBind.CANCEL);
                hud.key(KeyCommandBind.CANCEL);
                assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog);
                assertFalse(hud.find("tuning-choices").isVisible(), "the list closes with the panel");
                assertNull(hud.hud.stage.getKeyboardFocus());
                hud.click("tuning-button");
                assertFalse(hud.find("tuning-choices").isVisible(), "and stays closed when the panel reopens");
                hud.capture("tuning-atmosphere-planet");

                // Clouds and ground air.
                hud.drag("Cloud cover", .6f);
                hud.drag("Ground fog", .4f);
                hud.drag("Ground layer height", 3);
                hud.drag("Haze", .3f);
                BoardAtmosphere.Settings air = hud.tuning.atmosphere();
                assertEquals(.6f, air.clouds(), NEAR);
                assertEquals(.4f, air.fog(), NEAR);
                assertEquals(3, air.groundLayerHeight(), NEAR);
                assertEquals(.3f, air.haze(), NEAR);

                // Weather effects: the sliders and the weather switches.
                hud.drag("Temperature (C)", 15);
                String[] weather = { "Rain", "Snow", "Hail", "Blowing sand", "Lightning", "Wind strength" };
                for (int index = 0; index < weather.length; index++) {
                    hud.drag(weather[index], .1f * (index + 2));
                }
                hud.drag("Wind direction", 90);
                BoardAtmosphere.Effects effects = hud.tuning.atmosphere().effects();
                assertEquals(.2f, effects.rain(), NEAR);
                assertEquals(.3f, effects.snow(), NEAR);
                assertEquals(.4f, effects.hail(), NEAR);
                assertEquals(.5f, effects.sand(), NEAR);
                assertEquals(.6f, effects.lightning(), NEAR);
                assertEquals(.7f, effects.wind(), NEAR);
                assertEquals(90, effects.windDirection(), NEAR);
                UiButton rain = hud.find("weather-toggle-Rain");
                assertEquals("RAIN ON", rain.getText().toString());
                hud.click(rain);
                assertEquals(0, hud.tuning.atmosphere().effects().rain(), NEAR, "the switch turns rain off");
                assertEquals("RAIN OFF", rain.getText().toString());
                assertFalse(rain.isChecked());
                hud.click(rain);
                assertEquals(.5f, hud.tuning.atmosphere().effects().rain(), NEAR, "and on again at half strength");

                // Light and fog effects.
                String[] names = { "God rays", "Cloud shadow min", "Cloud shadow max", "Sun glare",
                      "Fog height variation", "Fog density variation", "Moon shadow contrast", "Taint strength",
                      "Fog calm drift" };
                float[] values = { .8f, .3f, .9f, .2f, .5f, .4f, .45f, 1.5f, .12f };
                if (!hud.tuning.atmosphere().moonlight()) {
                    hud.click("tuning-moonlight");
                }
                for (int index = 0; index < names.length; index++) {
                    hud.drag(names[index], values[index]);
                }
                GpuAtmosphere.Options options = hud.tuning.atmosphereOptions();
                float[] model = { options.rays(), options.minCloudShadow(), options.maxCloudShadow(),
                      options.sunGlare(), options.fogHeightVariation(), options.fogDensityVariation(),
                      options.moonShadowContrast(), options.taintStrength(), options.fogCalmDrift() };
                for (int index = 0; index < names.length; index++) {
                    assertEquals(values[index], model[index], NEAR, names[index]);
                }
                hud.capture("tuning-atmosphere-effects");

                // Planetary conditions: the model's button asks the source for the editor, one at a time.
                UiButton conditions = hud.find("tuning-planetary-conditions");
                assertEquals("PLANETARY CONDITIONS…", conditions.getText().toString());
                hud.click(conditions);
                verify(hud.source).editPlanetaryConditions(any());
                assertTrue(conditions.isDisabled(), "while its editor is open");

                // Defaults restores the page and the extra effect controls.
                hud.click("tuning-defaults");
                assertEquals(BoardAtmosphere.DEFAULTS, hud.tuning.atmosphere());
                assertEquals(initialOptions, hud.tuning.atmosphereOptions());
                assertEquals(BoardAtmosphere.DEFAULTS.clouds(), hud.<Slider>find("Cloud cover").getValue(), .01f);
            }
        });
    }

    @Test
    void theCameraPageSetsTheFixedSunAndTheCamerasFraming() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show();
                hud.click("tuning-button");
                hud.click("tuning-camera-tab");
                UiKit.Checkbox selection = hud.find("camera-animate-selection");
                UiKit.Checkbox movement = hud.find("camera-animate-movement");
                assertEquals(hud.camera.animateOnSelectionChange, selection.isTicked());
                assertEquals(hud.camera.animateOnMove, movement.isTicked());

                hud.click("camera-fixed-sun");
                assertTrue(hud.tuning.fixedSun(), "the fixed sun is the model's");
                hud.click("tuning-atmosphere-tab");
                assertTrue(hud.<UiKit.Checkbox>find("tuning-fixed-sun").isTicked(), "the Atmosphere page shows it");
                hud.click("tuning-camera-tab");
                boolean animateSelection = hud.camera.animateOnSelectionChange;
                hud.click(selection);
                assertEquals(!animateSelection, hud.camera.animateOnSelectionChange);
                boolean animateMovement = hud.camera.animateOnMove;
                hud.click(movement);
                assertEquals(!animateMovement, hud.camera.animateOnMove);
                hud.capture("tuning-camera");

                // Changes made elsewhere (the model, the camera) show on the next frame.
                hud.tuning.setFixedSun(false);
                hud.camera.animateOnMove = animateMovement;
                hud.show();
                assertFalse(hud.<UiKit.Checkbox>find("camera-fixed-sun").isTicked());
                assertEquals(animateMovement, movement.isTicked());
            }
        });
    }

    @Test
    void planetaryConditionsOpensTheExistingDialog() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AWTEventListener answer = event -> {
            if (event instanceof WindowEvent window && window.getWindow() instanceof PlanetaryConditionsDialog dialog) {
                if (window.getID() == WindowEvent.WINDOW_OPENED) {
                    opened.countDown();
                    SwingUtilities.invokeLater(() -> cancel(dialog));
                } else if (window.getID() == WindowEvent.WINDOW_CLOSED) {
                    closed.countDown();
                }
            }
        };
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Toolkit.getDefaultToolkit().addAWTEventListener(answer, AWTEvent.WINDOW_EVENT_MASK);
            try {
                GpuHudTestStage.run(harness -> {
                    GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
                    GpuTuningPanel panel = new GpuTuningPanel(harness.kit, fixture.source, state, new BoardCamera(),
                          new GpuBoardTuning(harness.theme.skin, fixture.source));
                    harness.window.addActor(panel.actor());
                    panel.actor().setSize(harness.width(), harness.height());
                    state.dialog = GpuHudState.Dialog.TUNING;
                    GpuHud.Inputs inputs = new GpuHud.Inputs(null, GpuHud.HudView.EMPTY, null, null,
                          GpuHud.Metrics.of(harness.width(), harness.height()), List.of());
                    panel.update(inputs);
                    harness.draw();
                    UiButton tab = harness.stage.getRoot().findActor("tuning-atmosphere-tab");
                    clickOn(harness, tab);
                    panel.update(inputs);
                    harness.draw();
                    UiButton conditions = harness.stage.getRoot().findActor("tuning-planetary-conditions");
                    clickOn(harness, conditions);
                    assertTrue(opened.await(30, TimeUnit.SECONDS), "the Swing planetary conditions editor opens");
                    panel.update(inputs);
                    assertTrue(conditions.isDisabled(), "one editor at a time");
                    assertTrue(closed.await(30, TimeUnit.SECONDS), "the test closes it");
                });
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(answer);
            }
        }
    }

    /** Each model control has a shown control of its name and kind: a slider, a checkbox or a hud button. */
    private static void assertMirrored(Hud hud, List<Actor> controls) {
        for (Actor model : controls) {
            Class<?> kind = model instanceof Slider ? Slider.class
                  : model instanceof CheckBox ? UiKit.Checkbox.class : UiButton.class;
            assertInstanceOf(kind, hud.find(model.getName()), model.getName());
        }
    }

    /** The named controls of a model page: sliders, checkboxes, choices and buttons, nested ones included. */
    private static List<Actor> controls(Group page) {
        List<Actor> controls = new ArrayList<>();
        for (Actor actor : page.getChildren()) {
            if (actor instanceof Slider || actor instanceof SelectBox<?> || actor instanceof TextButton) {
                assertNotNull(actor.getName(), "every model control is named: " + actor);
                controls.add(actor);
            } else if (actor instanceof Group group) {
                controls.addAll(controls(group));
            }
        }
        return controls;
    }

    /** The reading the panel shows right of the slider of that name. */
    private static String reading(Hud hud, String name) {
        return readingOf(hud, name).getText().toString();
    }

    private static Label readingOf(Hud hud, String name) {
        Slider slider = hud.find(name);
        Group row = slider.getParent();
        return (Label) row.getChildren().get(row.getChildren().indexOf(slider, true) + 1);
    }

    /** The actor's right edge in stage units. */
    private static float right(Actor actor) {
        Rectangle area = GpuHudTestStage.bounds(actor);
        return area.x + area.width;
    }

    /** The visible button of that text under {@code group}, or null. */
    private static UiButton button(Group group, String text) {
        for (Actor actor : group.getChildren()) {
            if (actor.isVisible() && actor instanceof UiButton button && button.getText().toString().equals(text)) {
                return button;
            }
            if (actor.isVisible() && actor instanceof Group nested) {
                UiButton found = button(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A ChangeEvent on the model's button, as a click on it fires. */
    private static void fire(TextButton model) {
        ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
        model.fire(event);
        Pools.free(event);
    }

    /** A left click at the actor's centre through the harness's stage. */
    private static void clickOn(GpuHudTestStage harness, Actor actor) {
        assertNotNull(actor);
        Vector2 point = harness.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        harness.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        harness.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    /** Presses the dialog's own Cancel button, or closes it without one. */
    private static void cancel(PlanetaryConditionsDialog dialog) {
        for (JButton button : buttons(dialog)) {
            if (button.getAction() instanceof CancelAction) {
                button.doClick();
                return;
            }
        }
        dialog.dispose();
    }

    private static List<JButton> buttons(Container parent) {
        List<JButton> buttons = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button) {
                buttons.add(button);
            } else if (child instanceof Container container) {
                buttons.addAll(buttons(container));
            }
        }
        return buttons;
    }
}
