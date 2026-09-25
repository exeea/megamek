/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;

/**
 * Live board controls. Geometry is shared with picking; presentation settings belong to this GPU window.
 */
final class GpuBoardTuning {
    private static final float SLIDER_WIDTH = 120;
    private static final float LABEL_WIDTH = 120;

    private record Knob(String name, float min, float max, float step, String format) { }
    private record Control(Knob knob, Slider slider, Label reading, TextButton toggle) { }

    /** One row of the panel: the board value it drives, its range and how its reading is written. */
    private static final List<Knob> KNOBS = List.of(
          new Knob("Hex scale", 0.5f, 3f, 0.05f, "%.2f"),
          new Knob("Unit scale", 0.25f, 3f, 0.05f, "%.2f"),
          new Knob("Unit height scale", 0.1f, 2f, 0.05f, "%.2f"),
          new Knob("Base level height", 4, 40, 1, "%.0f"),
          // A value of one hides the grid.
          new Knob("Hex frame shade", 0f, 1f, 0.05f, "%.2f"),
          new Knob("Multi-hex unit scale", 0.25f, 1.5f, 0.05f, "%.2f"),
          new Knob("Hex padding (m)", 0, BoardGeometry.MAX_PADDING, 0.5f, "%.1f"));

    private static final List<Knob> LIGHTING_KNOBS = List.of(
          // One-minute steps, the same grid scenario times are chosen on (BoardAtmosphere.hourInWindow).
          new Knob("Time of day", 0, 24, 1f / 60, "clock"),
          new Knob("Exposure (EV)", -2, 2, 0.1f, "%+.1f"));

    private static final List<Knob> ATMOSPHERE_KNOBS = List.of(
          new Knob("Cloud cover", 0, 1, 0.01f, "%.2f"),
          new Knob("Ground fog", 0, 1, 0.01f, "%.2f"),
          new Knob("Ground layer height", BoardAtmosphere.MIN_GROUND_LAYER_HEIGHT, 8, 0.25f, "%.2f"),
          new Knob("Haze", 0, 1, 0.05f, "%.2f"));

    private static final List<Knob> VISIBILITY_KNOBS = List.of(
          new Knob("Building opacity", 0, 100, 5, "%.0f%%"),
          new Knob("See-through", 0, 100, 5, "%.0f%%"));

    private static final List<Knob> FOV_KNOBS = List.of(
          new Knob("FoV darkness", 0, 100, 5, "%.0f%%"));

    private static final List<Knob> SENSOR_KNOBS = List.of(
          new Knob("Sensor darkness", 0, 100, 5, "%.0f%%"));

    private static final List<Knob> EFFECT_KNOBS = List.of(
          new Knob("Rain", 0, 1, 0.05f, "%.2f"),
          new Knob("Snow", 0, 1, 0.05f, "%.2f"),
          new Knob("Hail", 0, 1, 0.05f, "%.2f"),
          new Knob("Blowing sand", 0, 1, 0.05f, "%.2f"),
          new Knob("Lightning", 0, 1, 0.05f, "%.2f"),
          new Knob("Wind strength", 0, 1, 0.05f, "%.2f"),
          new Knob("Wind direction", 0, 360, 15, "%.0f"));

    private final Table panel = new Table();
    /** Construction cursor only; each tab owns its own rows and scroll position. */
    private Table rows = new Table();
    private final BoardCamera camera;
    private final CheckBox perspective;
    private final List<Control> cameraFieldOfView;
    private final CheckBox normalMaps;
    private final CheckBox vsync;
    /** The graphics card rows; null on computers without two cards to choose from. */
    private SelectBox<GpuGraphicsCard> graphicsCard;
    private Label cardInUse;
    private Label cardPending;
    private final List<Control> geometry;
    private final CheckBox transitions;
    private final List<Control> relief;
    private final List<Control> water;
    private final List<Control> terrainDetail;
    private final CheckBox fallsOffBoard;
    private final SelectBox<String> geologyFamily;
    private final List<Control> geology;
    private final List<Control> familySizes;
    private final CheckBox overviewIcons;
    private final List<Control> overview;
    private final List<Control> visibility;
    private final ButtonGroup<TextButton> fovModes;
    private final List<Control> fieldOfView;
    private final ButtonGroup<TextButton> sensorModes;
    private final List<Control> sensors;
    private final List<Control> daylight;
    private final CheckBox moonlight;
    private final SelectBox<Atmosphere> pressure;
    private final SelectBox<AtmosphericTaint> atmosphericTaint;
    private final List<Control> temperature;
    private final List<Control> gravity;
    private final List<Control> weather;
    private final CheckBox fixedSun;
    private final List<Control> effects;
    private final List<Control> rendering;
    private final CheckBox overrideDamage;
    private final SelectBox<UnitDamageDisplay.Location> damageLocation;
    private final List<Control> damage;
    private BoardAtmosphere.Settings atmosphere = BoardAtmosphere.DEFAULTS;
    private BoardAtmosphere.Settings lastScenario;
    private boolean conditionsPreview;
    private boolean syncing;

    GpuBoardTuning(Skin skin) {
        this(skin, null);
    }

    GpuBoardTuning(Skin skin, GpuBoardSource source) {
        this(skin, source, new BoardCamera());
    }

    GpuBoardTuning(Skin skin, GpuBoardSource source, BoardCamera camera) {
        this.camera = camera;
        panel.setBackground(skin.getDrawable("menu-panel"));
        panel.setTouchable(Touchable.enabled);
        panel.setName("board-tuning");
        panel.pad(8).top();
        panel.add(new Label("Board tuning", skin)).left().padBottom(6).row();
        Table general = rows;
        rows.top().defaults().pad(0, 3, 0, 3);
        section(skin, "Camera");
        perspective = checkbox(skin, "Perspective", "tuning-perspective");
        perspective.addListener(new TextTooltip("Enable perspective: nearby objects appear larger than distant ones. "
              + "Turn off to return to the orthographic board view.", skin, "menu"));
        cameraFieldOfView = controls(skin, List.of(new Knob("Camera FOV", BoardCamera.MIN_FIELD_OF_VIEW,
              BoardCamera.MAX_FIELD_OF_VIEW, 1, "%.0f\u00b0")), this::applyCamera, 0);
        cameraFieldOfView.getFirst().slider().setName("tuning-camera-fov");
        cameraFieldOfView.getFirst().slider().addListener(new TextTooltip(
              "Vertical field of view in degrees. Larger angles show more of the board. Requires Perspective.", skin, "menu"));
        perspective.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyCamera(); }
            }
        });
        section(skin, "Geometry");
        geometry = controls(skin, KNOBS, this::applyGeometry, 0);
        geometry.get(6).slider().addListener(new TextTooltip("Gap the board opens between neighbouring hexes, in metres; "
              + "each hex keeps its size. Where levels match the ground runs on through the gap; where they differ the gap "
              + "holds a slope up to two levels and a cliff above its talus from three. Replaces hex transitions while on. "
              + "Visual only; the game's levels and hexes are unchanged.", skin, "menu"));
        transitions = checkbox(skin, "Hex transitions", "tuning-transitions");
        transitions.addListener(new TextTooltip("Steps between hexes take room on both sides of their edge: slopes up to "
              + "two levels, deep cliffs above a talus from three. Visual only; the game's levels and hexes are unchanged.",
              skin, "menu"));
        transitions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyGeometry(); }
            }
        });
        normalMaps = checkbox(skin, "Normal maps", "tuning-normal-maps");
        vsync = checkbox(skin, "VSync", "tuning-vsync");
        // The window's own preference, shown once here and then left to the user; Defaults never touches it.
        vsync.setChecked(GpuBoardWindow.DEFAULT_VSYNC);
        vsync.addListener(new TextTooltip("Synchronize with the monitor's refresh rate. FPS stays capped at 60.",
              skin, "menu"));
        vsync.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Gdx.graphics.setVSync(vsync.isChecked());
                Gdx.graphics.setForegroundFPS(0);
                // Gdx.graphics.setForegroundFPS(vsync.isChecked() ? 0 : 60);
            }
        });
        Label graphics = new Label("Graphics: " + GpuGlsl.description(), skin, "small");
        graphics.setName("tuning-graphics");
        graphics.addListener(new TextTooltip("Detected when the board opens: the board compiles its shaders for the newest "
              + "shading language the graphics driver offers, from GLSL 3.30 up to 4.60.", skin, "menu"));
        rows.add(graphics).colspan(3).left().height(18).row();
        if (!GpuGraphicsCard.cards().isEmpty()) {
            graphicsCard = choice(skin, "Graphics card", "tuning-graphics-card", GpuGraphicsCard.values(),
                  this::applyGraphicsCard);
            syncing = true;
            graphicsCard.setSelected(GpuGraphicsCard.preferred());
            syncing = false;
            graphicsCard.addListener(new TextTooltip("The card the board draws with. Windows keeps a program on the "
                  + "card it started with, so a change applies the next time MegaMek starts.", skin, "menu"));
            cardInUse = new Label("", skin, "small");
            cardInUse.setName("tuning-graphics-card-in-use");
            cardInUse.setEllipsis(true);
            rows.add(cardInUse).colspan(3).left().minWidth(0).growX().height(18).row();
            cardPending = new Label("Applies when MegaMek next starts", skin, "small");
            cardPending.setName("tuning-graphics-card-pending");
            rows.add(cardPending).colspan(3).left().height(18).row();
            updateGraphicsCard();
        }
        section(skin, "Unit family sizes");
        familySizes = controls(skin, Arrays.stream(UnitFamilyScale.values())
              .map(family -> new Knob(family.label, 0.25f, 3, 0.05f, "%.2f")).toList(), this::applyFamilySizes, 0);
        for (int index = 0; index < familySizes.size(); index++) {
            var slider = familySizes.get(index).slider();
            slider.setName("tuning-size-" + UnitFamilyScale.values()[index].name());
            slider.addListener(new TextTooltip("Uniform size multiplier; 1.00 draws the authored size. "
                  + "Infantry and battle armor start at 1.80: their canonical figures, drawn larger to read "
                  + "on the board. Stacks with Unit scale. Mek weight classes also multiply All Meks; "
                  + "ultralight Meks use Light Meks.", skin, "menu"));
        }
        section(skin, "Overview icons");
        overviewIcons = checkbox(skin, "Tactical View (Top-View only)", "tuning-overview-icons");
        overviewIcons.addListener(new TextTooltip("Replace units and trees with flat board artwork when zoomed out "
              + "within 15 degrees of overhead. Also available in the Camera menu.", skin, "menu"));
        overview = controls(skin, List.of(new Knob("Icon switch hex px", 0, 256, 2, "%.0f")),
              this::applyOverview, 0);
        overview.getFirst().slider().addListener(new TextTooltip("Switch to icons when a hex is this many window pixels wide. "
              + "Zoom in 15% further to return to models, avoiding flicker at the boundary.", skin, "menu"));
        section(skin, "Unit visibility");
        visibility = controls(skin, VISIBILITY_KNOBS, this::applyVisibility, 0);
        visibility.get(1).slider().addListener(new TextTooltip(
              "Highlights occluded units with an outline and fill. Set to 0% to turn off.", skin, "menu"));
        section(skin, "Outside field of view");
        fovModes = effectModes(skin, "fov");
        fieldOfView = controls(skin, FOV_KNOBS, this::applyFieldOfView, 0);
        section(skin, "Outside sensor range");
        sensorModes = effectModes(skin, "sensor");
        sensors = controls(skin, SENSOR_KNOBS, this::applyFieldOfView, 0);
        Table atmospheric = new Table();
        rows = atmospheric;
        rows.top().defaults().pad(0, 3, 0, 3);
        TextButton conditions = new TextButton("Planetary conditions...", skin, "menu-control");
        conditions.setName("tuning-planetary-conditions");
        conditions.setDisabled(source == null);
        conditions.setProgrammaticChangeEvents(false);
        conditions.addListener(new TextTooltip("Edit the active visual planetary conditions. Apply always restores their values "
              + "and resets the extra atmosphere effects to their constants, even when conditions are unchanged.", skin, "menu"));
        conditions.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                conditions.setChecked(false);
                conditions.setDisabled(true);
                var application = Gdx.app;
                source.editPlanetaryConditions(settings -> application.postRunnable(() -> {
                    if (source.isClosed()) { return; }
                    conditions.setDisabled(false);
                    if (settings != null) { applyConditions(settings); }
                }));
            }
        });
        section(skin, "Atmosphere presets").add(conditions).height(22).padLeft(8);
        Table presets = new Table();
        for (AtmospherePreset preset : AtmospherePreset.values()) {
            TextButton button = new TextButton(preset.label, skin, "menu-control");
            button.setName("atmosphere-" + preset.name());
            button.setProgrammaticChangeEvents(false);
            button.addListener(new TextTooltip(preset.description(), skin, "menu"));
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    button.setChecked(false);
                    applyConditions(source == null ? preset.settings(0.5) : source.preview(preset));
                }
            });
            presets.add(button).width(94).height(22).padRight(2);
            if (presets.getChildren().size % 3 == 0) { presets.row(); }
        }
        rows.add(presets).colspan(3).left().padBottom(2).row();
        section(skin, "Lighting");
        daylight = controls(skin, LIGHTING_KNOBS, this::applyAtmosphere, 0);
        moonlight = checkbox(skin, "Moonlight at night", "tuning-moonlight");
        moonlight.addListener(new TextTooltip("Enable the directional moon at night. Moonless and Pitch Black disable it; "
              + "Exposure (EV) controls their darkness. This never disables daytime sunlight.", skin, "menu"));
        moonlight.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyAtmosphere(); }
            }
        });
        fixedSun = checkbox(skin, "Fixed sun/moon", "tuning-fixed-sun");
        fixedSun.addListener(new TextTooltip("Keep the light at the same position on screen when rotating or tilting the board. "
              + "Time of day still sets its color and strength; Moonless and Pitch Black have no moonlight.", skin, "menu"));
        daylight.getFirst().slider().addListener(new TextTooltip(
              "Starts at a random minute within the scenario's daylight, dawn/dusk or night window. "
                    + "It stays fixed during combat; adjust here to override. Defaults restores the scenario's choice.", skin, "menu"));
        daylight.get(1).slider().addListener(new TextTooltip(
              "Visual brightness offset. The Moonless preset uses -0.6 EV; Pitch Black uses -1 EV, both without moonlight. "
                    + "Glare/Solar Flare use +0.6/+1.2 EV. Time of day also sets the base exposure.", skin, "menu"));
        section(skin, "Planet properties");
        gravity = controls(skin, List.of(new Knob("Gravity (g)", 0, 10, 0.01f, "%.2f")), this::applyAtmosphere, 0);
        gravity.getFirst().slider().addListener(new TextTooltip(
              "Visual gravity controls the height and timing of newly starting jump animations. "
                    + "Moves and gameplay rules stay unchanged; an airborne jump finishes its existing arc.", skin, "menu"));
        pressure = choice(skin, "Air pressure", "tuning-atmosphere-pressure", Atmosphere.values(), this::applyAtmosphere);
        pressure.addListener(new TextTooltip("Visual atmosphere pressure: controls sky scattering, clouds and permitted weather. "
              + "Vacuum clears atmospheric effects; changing pressure never changes the game's rules.", skin, "menu"));
        temperature = controls(skin, List.of(new Knob("Temperature (C)", -200, 200, 1, "%.0f")), this::applyAtmosphere, 0);
        temperature.getFirst().slider().addListener(new TextTooltip(
              "Scenario temperature controls rain wetness: freezing or colder keeps the ground dry. Visual preview only.", skin, "menu"));
        atmosphericTaint = choice(skin, "Atmospheric taint", "tuning-atmospheric-taint", AtmosphericTaint.values(), this::applyAtmosphere);
        atmosphericTaint.addListener(new TextTooltip("Select the atmospheric palette to preview. Taint strength scales its color. "
              + "This does not change gameplay exposure, fire or damage rules.", skin, "menu"));
        section(skin, "Clouds and ground air");
        weather = controls(skin, ATMOSPHERE_KNOBS, this::applyAtmosphere, 0);
        weather.getFirst().slider().addListener(new TextTooltip(
              "Moving cloud shadows and a soft sky gradient. Thin air gives lighter shade; trace atmosphere and vacuum disable clouds.",
              skin, "menu"));
        rows.add(new Label(String.format(Locale.ROOT, "Fog + haze opacity cap: %.0f%%",
              BoardAtmosphere.MAX_FOG_OPACITY * 100), skin, "small")).colspan(3).left().height(18).row();
        section(skin, "Weather effects");
        effects = controls(skin, EFFECT_KNOBS, this::applyAtmosphere, 5);
        effects.get(5).slider().addListener(new TextTooltip(
              "Clouds and fog keep drifting even when wind strength is zero.", skin, "menu"));
        section(skin, "Light and fog effects");
        rendering = controls(skin, List.of(new Knob("God rays", 0, 2, 0.05f, "%.2f"),
              new Knob("Cloud shadow min", 0, 1, 0.05f, "%.2f"),
              new Knob("Cloud shadow max", 0, 1, 0.05f, "%.2f"),
              new Knob("Sun glare", 0, 1, 0.05f, "%.2f"),
              new Knob("Fog height variation", 0, 4, 0.05f, "%.2f"),
              new Knob("Fog density variation", 0, 1, 0.05f, "%.2f"),
              new Knob("Moon shadow contrast", 0, 1, 0.05f, "%.2f"),
              new Knob("Taint strength", 0, 10, 0.05f, "%.2f"),
              new Knob("Fog calm drift", 0, 2, 0.01f, "%.2f")), this::applyRendering, 0);
        rendering.get(0).slider().addListener(new TextTooltip(
              "Sunlight scattered through cloud openings. 0 disables shafts; haze and viewing angle affect visibility.", skin, "menu"));
        rendering.get(1).slider().addListener(new TextTooltip(
              "Cloud-patch opacity at sparse cover. Strength rises with cover toward the maximum; density stays at 0.25.",
              skin, "menu"));
        rendering.get(2).slider().addListener(new TextTooltip(
              "Cloud-patch opacity at full cover. Cannot be lower than the minimum; ambient light remains in shadow.",
              skin, "menu"));
        rendering.get(3).slider().addListener(new TextTooltip(
              "Golden glare and lens reflections when facing the sun, strongest near dawn/dusk. "
                    + "Clouds, fog and source occlusion reduce it. "
                    + "0 disables it; this lens effect also works without an atmosphere.", skin, "menu"));
        effects.get(0).slider().addListener(new TextTooltip(
              "Rain also wets exposed ground above freezing in sufficiently dense air.", skin, "menu"));
        effects.get(3).slider().addListener(new TextTooltip(String.format(Locale.ROOT,
              "Wind-driven dust above the board, with at most %.0f%% opacity even at full strength. "
                    + "Ground layer height controls its falloff; the solid map base stays clear.",
              GpuAtmosphere.MAX_SAND_OPACITY * 100), skin, "menu"));
        weather.get(2).slider().addListener(new TextTooltip(
              "Shared fog and sand falloff height above the board baseline, in terrain levels.", skin, "menu"));
        rendering.get(4).slider().addListener(new TextTooltip(
              "Maximum height deviation of drifting fog banks, in terrain levels. 0 gives a constant height.", skin, "menu"));
        rendering.get(5).slider().addListener(new TextTooltip(
              "Thins the gaps between wind-driven fog banks. 0 gives uniform density; 1 permits clear gaps. "
                    + "The fog opacity cap still applies.", skin, "menu"));
        rendering.get(6).slider().addListener(new TextTooltip(
              "Strengthens Full Moon shadows while preserving the brightness of lit level ground. "
                    + "0 restores the original softer shadows. Moonless and Pitch Black have no moonlight.", skin, "menu"));
        rendering.get(7).slider().addListener(new TextTooltip(
              "Scale the selected taint's palette across the sky, existing fog and the board itself; "
                    + "0 disables the tint. Keeps brightness and fog density unchanged. Fades at dawn and dusk, "
                    + "weaker in thin air, and disabled in vacuum or breathable air.",
              skin, "menu"));
        rendering.get(8).slider().addListener(new TextTooltip(
              "Intrinsic fog speed in hex widths per second when calm. A gently changing vector blends into the wind. "
                    + "0 disables calm drift; wind still moves the fog.", skin, "menu"));
        rows = general;
        section(skin, "Unit damage");
        overrideDamage = checkbox(skin, "Override visible unit damage", "tuning-override-damage");
        damageLocation = choice(skin, "Damage location", "tuning-damage-location", UnitDamageDisplay.Location.values(), this::applyDamage);
        damageLocation.addListener(new TextTooltip("Applies to matching model locations across the board. Models without locations "
              + "always use All locations. Other locations keep their actual damage.", skin, "menu"));
        damage = controls(skin, List.of(new Knob("Display damage", 0, 1, 0.01f, "%.2f")), this::applyDamage, 0);
        damage.getFirst().slider().addListener(new TextTooltip(
              "Non-Meks: linear damage. Meks: 0-0.50 removes armor; 0.50-1 damages structure. "
                    + "Infantry: proportion of troops fallen (rounded to visible figures). "
                    + "Destroyed parts take priority. Visual preview only.", skin, "menu"));
        overrideDamage.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                applyDamage();
            }
        });
        Table terrain = new Table();
        rows = terrain;
        rows.top().defaults().pad(0, 3, 0, 3);
        section(skin, "River shape and land");
        relief = controls(skin, List.of(
              new Knob("Shore spread", -20, 12, .5f, "%+.1f"),
              new Knob("Land retained", .5f, 1, .01f, "%.2f"),
              new Knob("Corner shift limit", 0, 28, .5f, "%.1f"),
              new Knob("Shore room", 0, 16, .5f, "%.1f"),
              new Knob("Shore reach", 64, 128, 1, "%.0f"),
              new Knob("Narrow channel pull", 0, 2, .05f, "%.2f"),
              new Knob("Hard ground pull", 1, 4, .1f, "%.1f"),
              new Knob("Pool radius", 8, 32, .5f, "%.1f"),
              new Knob("Island radius", 8, 32, .5f, "%.1f"),
              new Knob("Shore blend", 1, 20, .5f, "%.1f"),
              new Knob("Shore wander", 0, 16, .5f, "%.1f"),
              new Knob("Wander length", 70, 280, 5, "%.0f"),
              new Knob("Shore lip", .5f, 10, .5f, "%.1f"),
              new Knob("Transition room (m)", 0, 8, .1f, "%.1f")), this::applyRelief, 0);
        hints(skin, relief,
              "SHORE_SPREAD: moves both banks outward; larger values widen rivers and lakes. Distances here are in hex-scale units.",
              "LAND_KEEP: minimum share of a land hex retained when water moves its corners. Larger values limit river expansion.",
              "SHORE_SHIFT: maximum distance a shoreline can move a hex corner into adjoining land.",
              "SHORE_ROOM: extra room beyond the waterline for banks beside land at the water's level.",
              "SHORE_REACH: reach of nearby hexes when shaping a continuous shoreline. Larger values smooth over more neighbours.",
              "SHORE_NARROW: extra pull along water/land boundaries, preserving narrow rivers and spits of land.",
              "SHORE_HARD: how strongly roads, paving and other fixed ground push water away.",
              "SHORE_POOL: water radius retained around each water hex's centre; also shapes isolated ponds.",
              "SHORE_ISLE: land radius retained around each land hex's centre; also shapes isolated islands.",
              "SHORE_BLEND: distance over which pond and island protection blend into the shoreline.",
              "SHORE_WANDER: how far banks vary sideways along their course; 0 removes this variation.",
              "WANDER_CELL: length of the broad bends along a shoreline.",
              "SHORE_LIP: width of the bank from its level lip toward the water.",
              "TRANSITION: room on each side of a height step, in metres. Requires Hex transitions and no Hex padding.");
        section(skin, "Banks and river openings");
        water = new ArrayList<>(controls(skin, List.of(
              new Knob("Wet margin", .1f, 4, .1f, "%.1f"),
              new Knob("Beach width", 1, 12, .5f, "%.1f"),
              new Knob("Bank width", 1, 10, .5f, "%.1f"),
              new Knob("Bank rounding", 1, 12, .5f, "%.1f"),
              new Knob("Mouth opening", 0, 12, .5f, "%.1f"),
              new Knob("Plunge opening", 0, 12, .5f, "%.1f"),
              new Knob("Bed slope share", .1f, .95f, .05f, "%.2f")), this::applyWater, 0));
        section(skin, "Waterfalls");
        fallsOffBoard = checkbox(skin, "Waterfalls off board", "tuning-falls-off-board");
        fallsOffBoard.addListener(new TextTooltip("FALLS_OFF_THE_BOARD: let rivers pour over the board's edge. "
              + "Also updates the visible current direction.", skin, "menu"));
        water.addAll(controls(skin, List.of(
              new Knob("Off-board drop", 1, 8, .5f, "%.1f"),
              new Knob("Plunge pool swell", 0, 12, .5f, "%.1f"),
              new Knob("Lip variation", 0, 7, .5f, "%.1f"),
              new Knob("Fall lip width", .005f, .1f, .005f, "%.3f"),
              new Knob("Fall lip drop", .1f, 1, .05f, "%.2f"),
              new Knob("Underwater ledge", 0, 4, .25f, "%.2f"),
              new Knob("Valley extension", 0, 14, .5f, "%.1f")), this::applyWater, 0));
        hints(skin, water,
              "HUG: wet clearance beside slopes and corners that extend into the water, in hex-scale units.",
              "BEACH: bank inset beside walls and lower land, in hex-scale units.",
              "SHORE_BANK: bank inset beside land at water level. Moved river-mouth corners keep this width plus one unit.",
              "SHORE_ROUND: distance over which adjoining banks round into each other.",
              "MOUTH_OPENING: beach width given back to the river at fixed hex corners. Larger values widen the opening; "
                    + "limited by Beach width and the shoreline.",
              "PLUNGE_OPENING: extra opening below waterfalls, limited by Beach width.",
              "PLATEAU: share of each radius used by the underwater slope. Larger values leave a smaller flat bed.",
              "BOTTOMLESS_LEVELS: how many terrain levels an off-board waterfall drops before fading.",
              "PLUNGE_POOL: how far the pool swells outward below a waterfall, in hex-scale units.",
              "LIP_JUT: maximum uneven projection of a waterfall lip over its pool.",
              "FALL_LIP_WIDTH: maximum lip curvature radius as a fraction of hex width.",
              "FALL_LIP_DROP: lip curvature radius as a fraction of the fall's height, capped by Fall lip width.",
              "LIP_DEPTH: depth of the underwater ledge where a pool spills over a fall.",
              "VALLEY: distance a joined waterfall corner extends over the pool below.");
        fallsOffBoard.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { applyWater(); }
            }
        });
        section(skin, "Terrain detail");
        terrainDetail = controls(skin, List.of(
              new Knob("Full detail hexes", 100, 10000, 100, "%.0f"),
              new Knob("Medium detail hexes", 100, 40000, 100, "%.0f")), this::applyRelief, 0);
        hints(skin, terrainDetail,
              "FULL_DETAIL_HEXES: maximum board size using full terrain detail. Larger limits increase geometry cost.",
              "MEDIUM_DETAIL_HEXES: maximum board size using medium detail. Larger boards use coarse detail without loose rocks.");
        section(skin, "Material geology");
        geologyFamily = choice(skin, "Material", "tuning-geology-family",
              new String[] { "Grass", "Dirt", "Sand", "Rock", "Concrete", "Snow", "Bedrock under slabs" }, this::syncGeology);
        geology = controls(skin, List.of(
              new Knob("Joint width (m)", .25f, 20, .05f, "%.2f"),
              new Knob("Joint height (m)", .25f, 20, .05f, "%.2f"),
              new Knob("Joint relief (m)", 0, 3, .05f, "%.2f"),
              new Knob("Fractures (m)", 0, 3, .05f, "%.2f"),
              new Knob("Strata relief (m)", 0, 2, .05f, "%.2f"),
              new Knob("Bedding (m)", .25f, 10, .05f, "%.2f"),
              new Knob("Buttresses (m)", 0, 3, .05f, "%.2f"),
              new Knob("Recess (m)", 0, 3, .05f, "%.2f"),
              new Knob("Ground relief (m)", 0, 2, .05f, "%.2f"),
              new Knob("Caprock scale", 0, 2, .05f, "%.2f"),
              new Knob("Talus scale", 0, 2, .05f, "%.2f"),
              new Knob("Corner rounding", 0, .5f, .01f, "%.2f"),
              new Knob("Soil jointing", 0, 1, .05f, "%.2f"),
              new Knob("Bank lean", 0, 1, .01f, "%.2f"),
              new Knob("Cast slab share", 0, 1, .05f, "%.2f"),
              new Knob("Loose stones / hex", 0, 6, .1f, "%.1f"),
              new Knob("Low shrubs / hex", 0, 6, .1f, "%.1f")), this::applyGeology, 0);
        hints(skin, geology,
              "Geology.cellWidth: horizontal size of the jointed rock masses.",
              "Geology.cellHeight: vertical size of the jointed rock masses.",
              "Geology.cells: relief of the jointed rock masses.",
              "Geology.fractures: depth of the fractures between rock masses.",
              "Geology.strata: relief of the geological layers.",
              "Geology.bedding: spacing of the geological layers.",
              "Geology.buttress: strength of broad supports along cliff faces.",
              "Geology.recess: how far cliff faces retreat behind their edges.",
              "Geology.relief: variation in the ground above the cliffs.",
              "Geology.cap: scale of the caprock lip.",
              "Geology.talus: scale of the debris apron at a cliff's foot.",
              "Geology.round: corner fillet as a fraction of the hex edge.",
              "Geology.bank: fraction of rock jointing retained by low soil banks.",
              "Geology.lean: metres a soil bank leans back per metre of depth.",
              "Geology.cast: contribution of a poured slab above bedrock on tall cliffs.",
              "Average loose stones per open hex of this material, before placement clearances. Requires full or medium detail.",
              "Average low shrubs per open hex of this material, before placement clearances. Requires full or medium detail.");
        ScrollPane generalScroll = scroll(skin, general, "tuning-general-scroll");
        ScrollPane atmosphereScroll = scroll(skin, atmospheric, "tuning-scroll");
        ScrollPane terrainScroll = scroll(skin, terrain, "tuning-terrain-scroll");
        Table tabs = new Table();
        ButtonGroup<TextButton> tabGroup = new ButtonGroup<>();
        tab(skin, tabs, tabGroup, "General", generalScroll, atmosphereScroll, terrainScroll);
        tab(skin, tabs, tabGroup, "Atmosphere", atmosphereScroll, generalScroll, terrainScroll);
        tab(skin, tabs, tabGroup, "Terrain", terrainScroll, generalScroll, atmosphereScroll);
        atmosphereScroll.setVisible(false);
        terrainScroll.setVisible(false);
        panel.add(tabs).growX().padBottom(6).row();
        panel.add(new Stack(generalScroll, atmosphereScroll, terrainScroll)).minHeight(0).grow().row();
        panel.add(new Image(skin.getDrawable("rule"))).height(1).growX().padTop(6).row();
        TextButton reset = new TextButton("Defaults", skin, "menu-control");
        reset.setName("tuning-defaults");
        reset.addListener(new TextTooltip("Restore all tabs: camera projection, geometry, terrain, water, geology, "
              + "family sizes, visibility, light/fog effects, "
              + "the game's current planetary conditions, and disable damage preview.",
              skin, "menu"));
        reset.setProgrammaticChangeEvents(false);
        reset.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                reset.setChecked(false);
                if (source != null) { source.resetConditionsPreview(); }
                conditionsPreview = false;
                restoreDefaults();
            }
        });
        Table buttons = new Table();
        buttons.add(reset).width(76).height(22);
        buttons.add(new Label("Visual preview only", skin, "small")).padLeft(10).expandX().left();
        buttons.add(new Label("F9 to close", skin, "small")).right();
        panel.add(buttons).growX().padTop(4).row();
        restoreDefaults();
    }

    private ScrollPane scroll(Skin skin, Table content, String name) {
        ScrollPane scroll = new ScrollPane(content, skin, "menu");
        scroll.setName(name);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        scroll.setFlickScroll(false);
        scroll.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                panel.getStage().setScrollFocus(scroll);
            }
        });
        return scroll;
    }

    private void tab(Skin skin, Table tabs, ButtonGroup<TextButton> group, String label, ScrollPane page, ScrollPane... others) {
        TextButton button = new TextButton(label, skin, "menu-control");
        button.setName("tuning-tab-" + label.toLowerCase(Locale.ROOT));
        group.add(button);
        button.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!button.isChecked()) { return; }
                if (panel.getStage() != null) {
                    for (ScrollPane other : others) { panel.getStage().unfocus(other); }
                    panel.getStage().setScrollFocus(page);
                }
                damageLocation.hideList();
                pressure.hideList();
                atmosphericTaint.hideList();
                geologyFamily.hideList();
                page.setVisible(true);
                for (ScrollPane other : others) { other.setVisible(false); }
            }
        });
        tabs.add(button).growX().height(26).padRight(2);
    }

    private Table section(Skin skin, String title) {
        float spacing = rows.hasChildren() ? 8 : 0;
        Table heading = new Table();
        Label label = new Label(title.toUpperCase(Locale.ROOT), skin, "kicker");
        label.setEllipsis(true);
        heading.add(label).minWidth(0).growX();
        rows.add(heading).colspan(3).growX().padTop(spacing).padBottom(3).row();
        return heading;
    }

    private static void hints(Skin skin, List<Control> controls, String... hints) {
        for (int i = 0; i < controls.size(); i++) {
            controls.get(i).slider().addListener(new TextTooltip(hints[i], skin, "menu"));
        }
    }

    private CheckBox checkbox(Skin skin, String label, String name) {
        CheckBox checkbox = new CheckBox(label, skin, "menu");
        checkbox.setName(name);
        checkbox.getImage().setScaling(Scaling.fit);
        checkbox.getImageCell().size(14).padRight(5);
        rows.add(checkbox).colspan(3).left().height(20).row();
        return checkbox;
    }

    private <T> SelectBox<T> choice(Skin skin, String label, String name, T[] values, Runnable apply) {
        SelectBox<T> choice = new SelectBox<>(skin, "menu");
        choice.setName(name);
        choice.setItems(values);
        choice.setMaxListCount(7);
        choice.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (!syncing) { apply.run(); }
            }
        });
        rows.add(new Label(label, skin, "menu")).left().width(LABEL_WIDTH);
        rows.add(choice).colspan(2).minWidth(0).growX().height(24).row();
        return choice;
    }

    private ButtonGroup<TextButton> effectModes(Skin skin, String name) {
        ButtonGroup<TextButton> group = new ButtonGroup<>();
        Table modes = new Table();
        for (GpuFieldOfView.Style style : GpuFieldOfView.Style.values()) {
            TextButton button = new TextButton(style.label, skin, "menu-control");
            button.setName(name + "-style-" + style.name());
            button.setUserObject(style);
            group.add(button);
            modes.add(button).width(92).height(22).padRight(2);
            if (modes.getChildren().size % 3 == 0) { modes.row(); }
        }
        rows.add(modes).colspan(3).left().padBottom(2).row();
        return group;
    }

    private List<Control> controls(Skin skin, List<Knob> knobs, Runnable apply, int toggleCount) {
        List<Control> result = new ArrayList<>();
        for (Knob knob : knobs) {
            Slider slider = new Slider(knob.min(), knob.max(), knob.step(), false, skin, "menu");
            slider.setName(knob.name());
            Label reading = new Label("", skin, "small");
            reading.setAlignment(Align.right);
            slider.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (!syncing) {
                        apply.run();
                    }
                }
            });
            TextButton toggle = null;
            if (result.size() < toggleCount) {
                toggle = new TextButton(knob.name(), skin, "menu-control");
                toggle.setName("weather-toggle-" + knob.name());
                toggle.getLabel().setAlignment(Align.left);
                toggle.setProgrammaticChangeEvents(false);
                toggle.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        slider.setValue(slider.getValue() > 0 ? 0 : 0.5f);
                    }
                });
            }
            result.add(new Control(knob, slider, reading, toggle));
            rows.add(toggle == null ? new Label(knob.name(), skin, "menu") : toggle).left().width(LABEL_WIDTH);
            rows.add(slider).minWidth(60).prefWidth(SLIDER_WIDTH).growX().height(20);
            rows.add(reading).width(38).right().row();
        }
        return result;
    }

    Table panel() {
        return panel;
    }

    /**
     * Writes the current board values into the sliders, as the initial state and after a reset. VSync and the
     * fixed sun/moon frame are the user's window preferences, and the graphics card the computer's, not board values,
     * so a reset leaves them alone.
     */
    private void restoreDefaults() {
        syncing = true;
        perspective.setChecked(false);
        syncing = false;
        setValues(cameraFieldOfView, new float[] { BoardCamera.DEFAULT_FIELD_OF_VIEW });
        applyCamera();
        normalMaps.setChecked(true);
        boolean fixedSunKept = fixedSun.isChecked();
        BoardGeometry.Tuning defaults = BoardGeometry.DEFAULTS;
        float[] values = { defaults.hexScale(), defaults.unitScale(), defaults.unitHeightScale(),
              defaults.levelHeight(), defaults.gridShade(), defaults.multiHexUnitScale(), defaults.padding() };
        setValues(geometry, values);
        syncing = true;
        transitions.setChecked(defaults.transitions());
        syncing = false;
        applyGeometry();
        BoardRelief.tune(BoardRelief.DEFAULTS);
        BoardSurface.tune(BoardSurface.DEFAULTS);
        BoardRelief.tuneGeology(BoardRelief.defaultGeology());
        syncRelief();
        syncWater();
        syncGeology();
        float[] familyDefaults = new float[familySizes.size()];
        for (int index = 0; index < familyDefaults.length; index++) {
            familyDefaults[index] = UnitFamilyScale.values()[index].defaultUnitScale;
        }
        setValues(familySizes, familyDefaults);
        applyFamilySizes();
        overviewIcons.setChecked(GpuUnitIcons.DEFAULT_ENABLED);
        setValues(overview, new float[] { GpuUnitIcons.DEFAULT_HEX_PIXELS });
        updateReadings(overview);
        setValues(visibility, new float[] { GpuTerrain.DEFAULT_BUILDING_OPACITY * 100,
              GpuUnitVisibility.DEFAULT_OUTLINE_INTENSITY * 100 });
        updateReadings(visibility);
        fovModes.getButtons().get(GpuFieldOfView.FOV_STYLE.ordinal()).setChecked(true);
        setValues(fieldOfView, new float[] { GpuFieldOfView.FOV_DARKNESS * 100 });
        sensorModes.getButtons().get(GpuFieldOfView.SENSOR_STYLE.ordinal()).setChecked(true);
        setValues(sensors, new float[] { GpuFieldOfView.SENSOR_DARKNESS * 100 });
        applyFieldOfView();
        setRenderingOptions(GpuAtmosphere.Options.DEFAULTS);
        // Restored board values must not move the light back out of the frame the user chose for it.
        fixedSun.setChecked(fixedSunKept);
        setAtmosphere(lastScenario == null ? BoardAtmosphere.DEFAULTS : lastScenario);
        overrideDamage.setChecked(false);
        damageLocation.setSelected(UnitDamageDisplay.Location.ALL);
        setValues(damage, new float[] { 0 });
        applyDamage();
    }

    private void setValues(List<Control> controls, float[] values) {
        syncing = true;
        for (int index = 0; index < controls.size(); index++) {
            controls.get(index).slider().setValue(values[index]);
        }
        syncing = false;
    }

    BoardAtmosphere.Settings atmosphere() {
        return atmosphere;
    }

    /** Leave captured game gravity intact until a local conditions selection or gravity edit overrides it. */
    float gravityOverride() {
        return conditionsPreview || lastScenario != null && Math.abs(atmosphere.gravity() - lastScenario.gravity()) > 0.00001f
              ? atmosphere.gravity() : Float.NaN;
    }

    GpuAtmosphere.Options atmosphereOptions() {
        return new GpuAtmosphere.Options(value(rendering, 0), fixedSun.isChecked(), value(rendering, 1), value(rendering, 2),
              value(rendering, 3), value(rendering, 4), value(rendering, 5), value(rendering, 6), value(rendering, 7), value(rendering, 8));
    }

    private void applyRendering() {
        setRenderingOptions(atmosphereOptions());
    }

    private void setRenderingOptions(GpuAtmosphere.Options options) {
        fixedSun.setChecked(options.fixedSun());
        setValues(rendering, new float[] { options.rays(), options.minCloudShadow(), options.maxCloudShadow(), options.sunGlare(),
              options.fogHeightVariation(), options.fogDensityVariation(), options.moonShadowContrast(), options.taintStrength(),
              options.fogCalmDrift() });
        updateReadings(rendering);
    }

    boolean normalMaps() {
        return normalMaps.isChecked();
    }

    boolean fixedSun() {
        return fixedSun.isChecked();
    }

    boolean overviewIcons() { return overviewIcons.isChecked(); }

    void setOverviewIcons(boolean enabled) { overviewIcons.setChecked(enabled); }

    float overviewHexPixels() { return value(overview, 0); }

    void setFixedSun(boolean fixed) {
        fixedSun.setChecked(fixed);
    }

    UnitDamageDisplay.Location damageLocation() {
        return damageLocation.getSelected();
    }

    /** Negative means the preview is disabled; otherwise this is the displayed loss from zero to one. */
    float damageOverride() {
        return overrideDamage.isChecked() ? value(damage, 0) : -1;
    }

    float buildingOpacity() {
        return value(visibility, 0) / 100;
    }

    float seeThrough() {
        return value(visibility, 1) / 100;
    }

    GpuFieldOfView.Style fovStyle() {
        return (GpuFieldOfView.Style) fovModes.getChecked().getUserObject();
    }

    float fovDarkness() {
        return value(fieldOfView, 0) / 100;
    }

    GpuFieldOfView.Style sensorStyle() {
        return (GpuFieldOfView.Style) sensorModes.getChecked().getUserObject();
    }

    float sensorDarkness() {
        return value(sensors, 0) / 100;
    }

    /** Follow effective scenario changes while preserving individual visual overrides. */
    void useScenario(BoardAtmosphere.Settings initial, boolean newBoard) {
        if (lastScenario == null || newBoard) {
            conditionsPreview = false;
            setAtmosphere(initial);
        } else if (!conditionsPreview && lastScenario != null && !lastScenario.equals(initial)) {
            setAtmosphere(BoardAtmosphere.followScenario(atmosphere, lastScenario, initial));
        }
        lastScenario = initial;
    }

    /** An explicit Apply is an action, including when the selected conditions have not changed. */
    private void applyConditions(BoardAtmosphere.Settings settings) {
        conditionsPreview = true;
        setRenderingOptions(GpuAtmosphere.Options.DEFAULTS);
        setAtmosphere(settings);
    }

    private void setAtmosphere(BoardAtmosphere.Settings settings) {
        atmosphere = settings;
        syncing = true;
        moonlight.setChecked(settings.moonlight());
        pressure.setSelected(settings.pressure());
        atmosphericTaint.setSelected(settings.taint());
        // Preserve unusual loaded temperatures when editing an unrelated control.
        temperature.getFirst().slider().setRange(Math.min(-200, settings.temperature()), Math.max(200, settings.temperature()));
        gravity.getFirst().slider().setRange(0, Math.max(10, settings.gravity()));
        syncing = false;
        setValues(daylight, new float[] { settings.hour(), settings.exposure() });
        setValues(temperature, new float[] { settings.temperature() });
        setValues(gravity, new float[] { settings.gravity() });
        setValues(weather, new float[] { settings.clouds(), settings.fog(), settings.groundLayerHeight(), settings.haze() });
        BoardAtmosphere.Effects next = settings.effects();
        setValues(effects, new float[] { next.rain(), next.snow(), next.hail(), next.sand(), next.lightning(),
              next.wind(), next.windDirection() });
        boolean weatherAllowed = atmosphere.pressure().isDenserThan(Atmosphere.THIN);
        weather.getFirst().slider().setDisabled(atmosphere.pressure().isLighterThan(Atmosphere.THIN));
        weather.get(1).slider().setDisabled(!weatherAllowed);
        weather.get(2).slider().setDisabled(atmosphere.pressure().isVacuum());
        weather.get(3).slider().setDisabled(atmosphere.pressure().isVacuum());
        for (int index = 0; index < effects.size(); index++) {
            boolean disabled = atmosphere.pressure().isVacuum() || (index < 3 || index == 4) && !weatherAllowed;
            effects.get(index).slider().setDisabled(disabled);
            if (effects.get(index).toggle() != null) { effects.get(index).toggle().setDisabled(disabled); }
        }
        updateReadings(daylight);
        updateReadings(temperature);
        updateReadings(gravity);
        updateReadings(weather);
        updateReadings(effects);
        for (int index = 0; index < rendering.size(); index++) {
            rendering.get(index).slider().setDisabled(index < 3 && atmosphere.pressure().isLighterThan(Atmosphere.THIN)
                  || (index == 4 || index == 5 || index == 8) && !weatherAllowed || index == 6 && !atmosphere.moonlight()
                  || index == 7 && (atmosphere.pressure().isVacuum() || atmosphere.taint().isBreathable()));
        }
        updateReadings(rendering);
    }

    /** Saves the card for MegaMek's next start; this run keeps the card it opened the board with. */
    private void applyGraphicsCard() {
        String card = graphicsCard.getSelected().name();
        SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardGraphicsCard(card));
        updateGraphicsCard();
    }

    private void updateGraphicsCard() {
        // Drivers append their bus and instruction set: "NVIDIA GeForce RTX 4070 Laptop GPU/PCIe/SSE2".
        String renderer = GpuGlsl.renderer().split("/")[0];
        cardInUse.setText(renderer.isEmpty() ? "" : "In use: " + renderer);
        cardPending.setVisible(graphicsCard.getSelected() != GpuGraphicsCard.applied());
    }

    private void applyCamera() {
        camera.setFieldOfView(value(cameraFieldOfView, 0));
        camera.setPerspective(perspective.isChecked());
        cameraFieldOfView.getFirst().slider().setDisabled(!perspective.isChecked());
        updateReadings(cameraFieldOfView);
    }

    private void applyGeometry() {
        // The sliders apply while the panel is still being built, before the transitions box exists.
        boolean steps = transitions != null ? transitions.isChecked() : BoardGeometry.DEFAULT_TRANSITIONS;
        float padding = value(geometry, 6);
        BoardGeometry.tune(new BoardGeometry.Tuning(value(geometry, 0), value(geometry, 1), value(geometry, 2),
              Math.round(value(geometry, 3)), value(geometry, 4), value(geometry, 5), steps, padding));
        // Padding replaces transitions while it is on.
        if (transitions != null) { transitions.setDisabled(padding > 0); }
        updateReadings(geometry);
    }

    private void applyRelief() {
        int full = Math.round(value(terrainDetail, 0));
        BoardRelief.tune(new BoardRelief.Tuning(value(relief, 2), value(relief, 3), value(relief, 4), value(relief, 5),
              value(relief, 6), value(relief, 7), value(relief, 8), value(relief, 9), value(relief, 10), value(relief, 11),
              value(relief, 0), value(relief, 1), value(relief, 12), value(relief, 13), full,
              Math.max(full, Math.round(value(terrainDetail, 1)))));
        syncRelief();
    }

    private void syncRelief() {
        BoardRelief.Tuning t = BoardRelief.tuning();
        setValues(relief, new float[] { t.shoreSpread(), t.landKeep(), t.shoreShift(), t.shoreRoom(), t.shoreReach(),
              t.shoreNarrow(), t.shoreHard(), t.shorePool(), t.shoreIsle(), t.shoreBlend(), t.shoreWander(),
              t.wanderCell(), t.shoreLip(), t.transition() });
        setValues(terrainDetail, new float[] { t.fullDetailHexes(), t.mediumDetailHexes() });
        updateReadings(relief);
        updateReadings(terrainDetail);
    }

    private void applyWater() {
        BoardSurface.tune(new BoardSurface.Tuning(fallsOffBoard.isChecked(), value(water, 7), value(water, 0),
              value(water, 1), value(water, 8), value(water, 2), value(water, 3), value(water, 4), value(water, 5),
              value(water, 9), value(water, 10), value(water, 11), value(water, 6), value(water, 12), value(water, 13)));
        syncWater();
    }

    private void syncWater() {
        BoardSurface.Tuning t = BoardSurface.tuning();
        syncing = true;
        fallsOffBoard.setChecked(t.fallsOffBoard());
        syncing = false;
        setValues(water, new float[] { t.hug(), t.beach(), t.shoreBank(), t.shoreRound(), t.mouthOpening(),
              t.plungeOpening(), t.plateau(), t.bottomlessLevels(), t.plungePool(), t.lipJut(), t.fallLipWidth(),
              t.fallLipDrop(), t.lipDepth(), t.valley() });
        updateReadings(water);
    }

    private void applyGeology() {
        List<BoardRelief.Geology> next = new ArrayList<>(BoardRelief.geology());
        next.set(geologyFamily.getSelectedIndex(), new BoardRelief.Geology(value(geology, 0), value(geology, 1),
              value(geology, 2), value(geology, 3), value(geology, 4), value(geology, 5), value(geology, 6),
              value(geology, 7), value(geology, 8), value(geology, 9), value(geology, 10), value(geology, 11),
              value(geology, 12), value(geology, 13), value(geology, 14), value(geology, 15), value(geology, 16)));
        BoardRelief.tuneGeology(next);
        updateReadings(geology);
    }

    private void syncGeology() {
        BoardRelief.Geology g = BoardRelief.geology().get(geologyFamily.getSelectedIndex());
        setValues(geology, new float[] { g.cellWidth(), g.cellHeight(), g.cells(), g.fractures(), g.strata(),
              g.bedding(), g.buttress(), g.recess(), g.relief(), g.cap(), g.talus(), g.round(), g.bank(), g.lean(), g.cast(),
              g.stones(), g.shrubs() });
        boolean bedrock = geologyFamily.getSelectedIndex() == BoardScene.Surface.values().length;
        geology.get(8).slider().setDisabled(bedrock);
        geology.get(11).slider().setDisabled(bedrock);
        geology.get(15).slider().setDisabled(bedrock);
        geology.get(16).slider().setDisabled(bedrock);
        updateReadings(geology);
    }

    private void applyFamilySizes() {
        for (int index = 0; index < familySizes.size(); index++) {
            UnitFamilyScale.values()[index].UNIT_SCALE = value(familySizes, index);
        }
        updateReadings(familySizes);
    }

    private void applyVisibility() {
        updateReadings(visibility);
    }

    private void applyOverview() { updateReadings(overview); }

    private void applyFieldOfView() {
        updateReadings(fieldOfView);
        updateReadings(sensors);
    }

    private void applyDamage() {
        damage.getFirst().slider().setDisabled(!overrideDamage.isChecked());
        damageLocation.setDisabled(!overrideDamage.isChecked());
        updateReadings(damage);
    }

    private void applyAtmosphere() {
        setAtmosphere(new BoardAtmosphere.Settings(value(daylight, 0), value(weather, 0), value(weather, 1),
              value(weather, 2), value(weather, 3), value(daylight, 1),
              new BoardAtmosphere.Effects(value(effects, 0), value(effects, 1), value(effects, 2), value(effects, 3),
                    value(effects, 4), value(effects, 5), value(effects, 6)), pressure.getSelected(),
              Math.round(value(temperature, 0)), moonlight.isChecked(), atmosphericTaint.getSelected(), value(gravity, 0)));
    }

    private static void updateReadings(List<Control> controls) {
        for (Control control : controls) {
            float value = control.slider().getValue();
            if (control.toggle() != null) {
                control.toggle().setChecked(value > 0);
                control.toggle().setText(control.knob().name() + (value > 0 ? " On" : " Off"));
            }
            int minutes = Math.round(value * 60);
            control.reading().setText(control.knob().format().equals("clock")
                  ? String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
                  : String.format(Locale.ROOT, control.knob().format(), value));
        }
    }

    private static float value(List<Control> controls, int index) {
        return controls.get(index).slider().getValue();
    }
}
