/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.ColorToken;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiListPopup;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/**
 * The developer gallery of the native (libGDX) side of the design system, opened from Help in the lobby: the dark
 * Token v0 colors beside the hud-v3 colors the toolkit uses today, every hud font, every button style at rest, pressed
 * and disabled, and the toolkit's other controls. It is the subset of the Swing library that in-game views need, and it
 * shows the toolkit as it is until its styles move onto the tokens. Only one native window may run at a time (GLFW is
 * process-wide), so it frees a map preview first, refuses beside a board or editor, and closes for a board that opens.
 */
public final class GpuUiLibraryWindow extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuUiLibraryWindow.class);
    private static final int SWATCHES_PER_ROW = 6;
    private static final String SAMPLE_PASSWORD = "atlas-as7d";

    /** The open gallery; read and written on the EDT. */
    private static GpuUiLibraryWindow open;

    private final float preference;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final GpuDisplayScale displayScale = new GpuDisplayScale();
    private volatile Lwjgl3Application application;
    private volatile boolean closeRequested;
    /** What runs on the EDT once the native window is gone: a board window waiting for it. */
    private Runnable afterClose;
    private UiTheme theme;
    private UiKit kit;
    private Stage stage;

    private GpuUiLibraryWindow(float preference) {
        this.preference = preference;
    }

    /** EDT: opens the gallery, or brings the open one to the front. */
    public static void showLibrary(Window owner) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showLibrary(owner));
            return;
        }
        if (open != null) {
            open.focus();
            return;
        }
        if (!GpuBoardWindow.yieldNativeWindow(owner, () -> showLibrary(owner))) {
            return;
        }
        GpuUiLibraryWindow window = new GpuUiLibraryWindow(GUIPreferences.getInstance().getGUIScale());
        open = window;
        Thread thread = new Thread(window::run, "MegaMek-UI-library");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * EDT: closes the open gallery and runs {@code then} on the EDT once its native window is gone, so a board window
     * can take GLFW. Returns false, and runs nothing, when no gallery is open.
     */
    static boolean closeThen(Runnable then) {
        if (open == null) {
            return false;
        }
        open.afterClose = then;
        open.requestClose();
        return true;
    }

    private void run() {
        try {
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(true);
            configuration.setTitle(Messages.getString("UiLibrary.gdx.title"));
            configuration.setWindowedMode(1280, 860);
            new Lwjgl3Application(this, configuration);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error(failure, "The native UI library window failed");
        } finally {
            application = null;
            SwingUtilities.invokeLater(this::finished);
        }
    }

    private void finished() {
        if (open == this) {
            open = null;
        }
        Runnable then = afterClose;
        afterClose = null;
        if (then != null) {
            then.run();
        }
    }

    private void requestClose() {
        closeRequested = true;
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(app::exit);
        }
    }

    private void focus() {
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(() -> {
                var window = ((Lwjgl3Graphics) app.getGraphics()).getWindow();
                window.restoreWindow();
                window.focusWindow();
            });
        }
    }

    @Override
    public void create() {
        application = (Lwjgl3Application) Gdx.app;
        theme = new UiTheme(Configuration.fontsDir());
        kit = new UiKit(theme.skin);
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);
        ScrollPane scroll = kit.scrollList(content());
        scroll.setFillParent(true);
        stage.addActor(scroll);
        stage.setScrollFocus(scroll);
        if (closeRequested) {
            Gdx.app.exit();
        }
    }

    @Override
    public void resize(int width, int height) {
        float scale = DisplayScale.read(preference, displayScale.contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
    }

    @Override
    public void render() {
        ScreenUtils.clear(color(tokens.color("surface.base", Mode.DARK)));
        stage.act(Gdx.graphics.getDeltaTime());
        stage.draw();
    }

    @Override
    public void dispose() {
        if (stage != null) {
            stage.dispose();
        }
        if (theme != null) {
            theme.dispose();
        }
    }

    // ---- Content

    private Table content() {
        Table content = new Table();
        content.top().left().pad(20, 24, 32, 24);
        content.defaults().left();
        content.add(kit.label(text("UiLibrary.gdx.title"), "hud-heading", 20, UiTheme.TEXT)).row();
        Label note = kit.label(text("UiLibrary.gdx.note"), "hud-small", 11.5f, UiTheme.MUTED);
        note.setWrap(true);
        content.add(note).width(1100).padTop(4).row();

        section(content, "UiLibrary.gdx.tokenColors");
        content.add(tokenSwatches()).row();
        section(content, "UiLibrary.gdx.currentColors");
        content.add(currentSwatches()).row();
        section(content, "UiLibrary.gdx.fonts");
        content.add(fonts()).row();
        section(content, "UiLibrary.controls.buttons");
        Label buttonNote = kit.label(text("UiLibrary.buttons.note"), "hud-small", 11.5f, UiTheme.MUTED);
        buttonNote.setWrap(true);
        content.add(buttonNote).width(1100).padBottom(8).row();
        for (boolean compact : new boolean[] { false, true }) {
            content.add(kit.label(text(compact ? "UiLibrary.buttons.compact" : "UiLibrary.buttons.regular"),
                  "hud-medium", 12.5f, UiTheme.ACCENT)).padTop(6).padBottom(6).row();
            content.add(buttonMatrix(compact)).row();
        }
        section(content, "UiLibrary.controls.inputs");
        Label inputNote = kit.label(text("UiLibrary.gdx.inputs.note"), "hud-small", 11.5f, UiTheme.MUTED);
        inputNote.setWrap(true);
        content.add(inputNote).width(1100).padBottom(8).row();
        for (boolean compact : new boolean[] { false, true }) {
            content.add(kit.label(text(compact ? "UiLibrary.buttons.compact" : "UiLibrary.buttons.regular"),
                  "hud-medium", 12.5f, UiTheme.ACCENT)).padTop(6).padBottom(6).row();
            content.add(inputMatrix(compact)).row();
        }
        content.add(kit.label(text("UiLibrary.inputs.forms"), "hud-medium", 12.5f, UiTheme.ACCENT)).padTop(6)
              .padBottom(6).row();
        content.add(formSamples()).row();
        section(content, "UiLibrary.controls.choices");
        Label choiceNote = kit.label(text("UiLibrary.choices.note"), "hud-small", 11.5f, UiTheme.MUTED);
        choiceNote.setWrap(true);
        content.add(choiceNote).width(1100).padBottom(8).row();
        content.add(choiceMatrix()).row();
        content.add(choiceSamples()).padTop(6).row();
        section(content, "UiLibrary.controls.tabs");
        Label tabNote = kit.label(text("UiLibrary.tabs.note"), "hud-small", 11.5f, UiTheme.MUTED);
        tabNote.setWrap(true);
        content.add(tabNote).width(1100).padBottom(8).row();
        for (boolean compact : new boolean[] { false, true }) {
            content.add(kit.label(text(compact ? "UiLibrary.buttons.compact" : "UiLibrary.buttons.regular"),
                  "hud-medium", 12.5f, UiTheme.ACCENT)).padTop(6).padBottom(6).row();
            content.add(tabSamples(compact)).row();
        }
        section(content, "UiLibrary.controls.segmented");
        Label segmentedNote = kit.label(text("UiLibrary.segmented.note"), "hud-small", 11.5f, UiTheme.MUTED);
        segmentedNote.setWrap(true);
        content.add(segmentedNote).width(1100).padBottom(8).row();
        content.add(segmentedMatrix()).row();
        section(content, "UiLibrary.controls.select");
        Label selectNote = kit.label(text("UiLibrary.select.note"), "hud-small", 11.5f, UiTheme.MUTED);
        selectNote.setWrap(true);
        content.add(selectNote).width(1100).padBottom(8).row();
        content.add(selectMatrix()).row();
        content.add(selectSamples()).padTop(6).row();
        section(content, "UiLibrary.gdx.buttons");
        content.add(buttons()).row();
        section(content, "UiLibrary.gdx.controls");
        content.add(controls()).row();
        return content;
    }

    private void section(Table content, String key) {
        content.add(kit.label(text(key), "hud-title", 16, UiTheme.TEXT)).padTop(24).padBottom(10).row();
    }

    private Table tokenSwatches() {
        List<Table> cells = new ArrayList<>();
        int panel = tokens.color("surface.panel", Mode.DARK);
        for (ColorToken token : tokens.colors().values()) {
            int value = token.overlay() != null ? token.overlay() : token.dark();
            // Translucent roles show as they sit on a panel.
            int shown = ColorMath.alpha(value) == 0xFF ? value : ColorMath.over(value, panel);
            String hex = ColorMath.hex(value) + (token.overlay() != null ? "  " + text("UiLibrary.gdx.overlay") : "");
            cells.add(swatch(token.name(), hex, color(shown)));
        }
        return grid(cells);
    }

    private Table currentSwatches() {
        Map<String, Color> current = new LinkedHashMap<>();
        current.put("TEXT", UiTheme.TEXT);
        current.put("ACCENT", UiTheme.ACCENT);
        current.put("MUTED", UiTheme.MUTED);
        current.put("DISABLED", UiTheme.DISABLED);
        current.put("PANEL", UiTheme.PANEL);
        current.put("PANEL2", UiTheme.PANEL2);
        current.put("POP", UiTheme.POP);
        current.put("TICK", UiTheme.TICK);
        current.put("CORNER", UiTheme.CORNER);
        current.put("MINT", UiTheme.MINT);
        current.put("CORAL", UiTheme.CORAL);
        current.put("AMBER", UiTheme.AMBER);
        current.put("VIOLET", UiTheme.VIOLET);
        current.put("BLIP", UiTheme.BLIP);
        current.put("FILL", UiTheme.FILL);
        current.put("MAIN", UiTheme.MAIN);
        List<Table> cells = new ArrayList<>();
        current.forEach((name, value) -> cells.add(swatch(name, "#" + value.toString().toUpperCase(Locale.ROOT),
              value)));
        return grid(cells);
    }

    private Table swatch(String name, String detail, Color fill) {
        Table cell = new Table();
        cell.defaults().left();
        Image chip = new Image(kit.skin.newDrawable("white", fill));
        // A subtle outline keeps colors that sit close to the background visible.
        Table outline = new Table();
        outline.setBackground(kit.skin.newDrawable("white", color(tokens.color("border.subtle", Mode.DARK))));
        outline.add(chip).size(148, 32);
        outline.pad(1);
        cell.add(outline).row();
        cell.add(kit.label(name, "hud-medium", 12, UiTheme.TEXT)).padTop(4).row();
        cell.add(kit.label(detail, "hud-small", 10.5f, UiTheme.MUTED)).row();
        return cell;
    }

    private static Table grid(List<Table> cells) {
        Table grid = new Table();
        grid.top().left();
        grid.defaults().left().top().padRight(14).padBottom(12);
        for (int index = 0; index < cells.size(); index++) {
            grid.add(cells.get(index));
            if ((index + 1) % SWATCHES_PER_ROW == 0) {
                grid.row();
            }
        }
        return grid;
    }

    private Table fonts() {
        Table fonts = new Table();
        fonts.defaults().left().padBottom(6);
        for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
            fonts.add(kit.label(String.format(Locale.ROOT, "%s  %s / %s", font.name(), trim(font.size()),
                  weight(font.instance())), "hud-small", 11, UiTheme.MUTED)).width(260);
            fonts.add(new Label(text("UiLibrary.type.sample"), theme.skin, font.name())).row();
        }
        return fonts;
    }

    /** Every token button kind at one size in every state; transient states are frozen through UiButton. */
    private Table buttonMatrix(boolean compact) {
        List<String> states = List.of("rest", "hover", "pressed", "focus", "selected", "disabled");
        Table matrix = new Table();
        matrix.defaults().left().padRight(16).padBottom(8);
        matrix.add(kit.label(text("UiLibrary.buttons.kind"), "hud-small", 11, UiTheme.MUTED)).width(110);
        for (String state : states) {
            matrix.add(kit.label(text("UiLibrary.buttons.state." + state), "hud-small", 11, UiTheme.MUTED));
        }
        matrix.row();
        for (String kind : UiTheme.BUTTON_KINDS) {
            String upper = kind.toUpperCase(Locale.ROOT);
            matrix.add(kit.label(text("UiLibrary.buttons.kind." + upper), "hud-medium", 12.5f, UiTheme.TEXT));
            boolean selectable = kind.equals("toggle") || kind.equals("icon") || kind.equals("special");
            String style = "button-" + kind + (compact ? UiTheme.COMPACT : "");
            for (String state : states) {
                if (state.equals("selected") && !selectable) {
                    matrix.add();
                    continue;
                }
                UiButton button = kind.equals("icon")
                      ? kit.button(style, "search", null, null)
                      : kit.button(style, null, text("UiLibrary.buttons.label." + upper), null);
                switch (state) {
                    case "hover" -> button.hovered(true);
                    case "pressed" -> button.held(true);
                    case "focus" -> button.focused(true);
                    case "selected" -> button.pressed(true);
                    case "disabled" -> button.setDisabled(true);
                    default -> { }
                }
                if (!state.equals("rest")) {
                    button.setTouchable(Touchable.disabled);
                }
                matrix.add(button);
            }
            matrix.row();
        }
        return matrix;
    }

    /**
     * Every token input kind at one size in every state, in two blocks of four states to fit the window; hover and
     * focus are frozen through UiTextField.
     */
    private Table inputMatrix(boolean compact) {
        Table matrix = new Table();
        matrix.defaults().left().padRight(12).padBottom(8);
        List<List<String>> blocks = List.of(List.of("rest", "hover", "focus", "placeholder"),
              List.of("error", "warning", "readOnly", "disabled"));
        for (List<String> states : blocks) {
            matrix.add(kit.label(text("UiLibrary.buttons.kind"), "hud-small", 11, UiTheme.MUTED)).width(80)
                  .padTop(states == blocks.get(0) ? 0 : 6);
            for (String state : states) {
                matrix.add(kit.label(text("UiLibrary.inputs.state." + state), "hud-small", 11, UiTheme.MUTED))
                      .padTop(states == blocks.get(0) ? 0 : 6);
            }
            matrix.row();
            for (String kind : List.of("TEXT", "PASSWORD", "SEARCH")) {
                matrix.add(kit.label(text("UiLibrary.inputs.kind." + kind), "hud-medium", 12.5f, UiTheme.TEXT));
                for (String state : states) {
                    matrix.add(sampleInput(kind, state, compact)).width(160);
                }
                matrix.row();
            }
        }
        return matrix;
    }

    private Actor sampleInput(String kind, String state, boolean compact) {
        boolean empty = state.equals("placeholder");
        UiTextField field;
        Actor cell;
        if (kind.equals("SEARCH")) {
            UiKit.TokenSearch search = kit.tokenSearch(text("UiLibrary.inputs.placeholder.SEARCH"), compact);
            field = search.field;
            cell = search;
        } else {
            field = kind.equals("PASSWORD") ? kit.password(compact) : kit.input("", compact);
            field.setMessageText(text("UiLibrary.inputs.placeholder." + kind));
            cell = field;
        }
        if (!empty) {
            field.setText(kind.equals("PASSWORD") ? SAMPLE_PASSWORD : text("UiLibrary.inputs.value." + kind));
        }
        switch (state) {
            case "hover" -> field.hovered(true);
            case "focus" -> field.focused(true);
            case "error" -> field.status(UiTextField.Status.ERROR);
            case "warning" -> field.status(UiTextField.Status.WARNING);
            case "readOnly" -> field.readOnly(true);
            case "disabled" -> field.setDisabled(true);
            default -> { }
        }
        if (!state.equals("rest") && !empty) {
            cell.setTouchable(Touchable.disabled);
        }
        return cell;
    }

    /** Form fields: label above, helper or status below; a text area with a helper, read-only and disabled. */
    private Table formSamples() {
        Table forms = new Table();
        forms.top().left();
        forms.defaults().left().top().padRight(16).padBottom(12);
        forms.add(kit.formField(text("UiLibrary.inputs.form.name"), kit.input(text("UiLibrary.inputs.value.TEXT"),
              false)).helper(text("UiLibrary.inputs.form.nameHelp"))).width(220);
        forms.add(kit.formField(text("UiLibrary.inputs.form.gunnery"), kit.input("9", false))
              .error(text("UiLibrary.inputs.form.gunneryError"))).width(220);
        forms.add(kit.formField(text("UiLibrary.inputs.form.piloting"), kit.input("7", false))
              .warning(text("UiLibrary.inputs.form.pilotingWarning"))).width(220).row();
        String notes = text("UiLibrary.inputs.form.notesText");
        forms.add(kit.formField(text("UiLibrary.inputs.form.notes"), kit.area(notes, 3))
              .helper(text("UiLibrary.inputs.form.notesHelp"))).width(220);
        forms.add(kit.formField(text("UiLibrary.inputs.form.briefing"), kit.area(notes, 3).readOnly(true)))
              .width(220);
        UiTextField.Area disabled = kit.area(notes, 3);
        disabled.setDisabled(true);
        forms.add(kit.formField(text("UiLibrary.inputs.form.disabled"), disabled)).width(220);
        return forms;
    }

    /** Every choice kind in every state; transient states are frozen through UiChoice. */
    private Table choiceMatrix() {
        List<String> states = List.of("off", "on", "mixed", "hover", "hoverOn", "focus", "disabled", "disabledOn");
        Table matrix = new Table();
        matrix.defaults().left().padRight(16).padBottom(8);
        matrix.add(kit.label(text("UiLibrary.buttons.kind"), "hud-small", 11, UiTheme.MUTED)).width(110);
        for (String state : states) {
            matrix.add(kit.label(text("UiLibrary.choices.state." + state), "hud-small", 11, UiTheme.MUTED));
        }
        matrix.row();
        for (UiChoice.Kind kind : UiChoice.Kind.values()) {
            matrix.add(kit.label(text("UiLibrary.choices.kind." + kind.name()), "hud-medium", 12.5f, UiTheme.TEXT));
            for (String state : states) {
                if (state.equals("mixed") && kind != UiChoice.Kind.CHECK) {
                    matrix.add();
                    continue;
                }
                String sample = text("UiLibrary.choices.sample");
                boolean on = state.equals("on") || state.endsWith("On");
                UiChoice choice = switch (kind) {
                    case CHECK -> kit.check(sample, on);
                    case RADIO -> kit.radio(sample, on);
                    case SWITCH -> kit.toggleSwitch(sample, on);
                };
                switch (state) {
                    case "mixed" -> choice.mixed(true);
                    case "hover", "hoverOn" -> choice.hovered(true);
                    case "focus" -> choice.focused(true);
                    case "disabled", "disabledOn" -> choice.setDisabled(true);
                    default -> { }
                }
                choice.setTouchable(Touchable.disabled);
                matrix.add(choice);
            }
            matrix.row();
        }
        return matrix;
    }

    /** Live choices to click: a check box, a mixed one, two grouped radio buttons and a switch. */
    private Table choiceSamples() {
        Table live = new Table();
        live.defaults().left().padRight(16);
        live.add(kit.label(text("UiLibrary.choices.live"), "hud-medium", 12.5f, UiTheme.MUTED));
        live.add(kit.check(text("UiLibrary.controls.checked"), true));
        live.add(kit.check(text("UiLibrary.choices.selectAll"), false).mixed(true));
        UiChoice standard = kit.radio(text("UiLibrary.controls.optionA"), true);
        UiChoice total = kit.radio(text("UiLibrary.controls.optionB"), false);
        kit.radioGroup(standard, total);
        live.add(standard);
        live.add(total);
        live.add(kit.toggleSwitch(text("UiLibrary.choices.autosave"), true));
        return live;
    }

    /** One tab strip per state at one size: at rest (live), focused (frozen) and disabled. */
    private Table tabSamples(boolean compact) {
        Table samples = new Table();
        samples.defaults().left().top().padRight(24);
        for (String state : List.of("rest", "focus", "disabled")) {
            Table sample = new Table();
            sample.defaults().left();
            sample.add(kit.label(text("UiLibrary.buttons.state." + state), "hud-small", 11, UiTheme.MUTED))
                  .padBottom(4).row();
            UiTabs tabs = kit.tokenTabs(compact, text("UiLibrary.controls.tabA"), text("UiLibrary.controls.tabB"),
                  text("UiLibrary.controls.tabC"));
            switch (state) {
                case "focus" -> tabs.option(0).focused(true);
                case "disabled" -> tabs.setDisabled(true);
                default -> { }
            }
            if (!state.equals("rest")) {
                tabs.setTouchable(Touchable.disabled);
            }
            sample.add(tabs).width(280);
            samples.add(sample);
        }
        return samples;
    }

    /** Segmented controls in every state, one column per size; the second section carries the frozen state. */
    private Table segmentedMatrix() {
        List<String> states = List.of("rest", "hover", "pressed", "focus", "disabled");
        Table matrix = new Table();
        matrix.defaults().left().padRight(24).padBottom(8);
        matrix.add().width(110);
        matrix.add(kit.label(text("UiLibrary.buttons.regular"), "hud-small", 11, UiTheme.MUTED));
        matrix.add(kit.label(text("UiLibrary.buttons.compact"), "hud-small", 11, UiTheme.MUTED));
        matrix.row();
        for (String state : states) {
            matrix.add(kit.label(text("UiLibrary.buttons.state." + state), "hud-medium", 12.5f, UiTheme.TEXT));
            for (boolean compact : new boolean[] { false, true }) {
                UiSegmented segmented = kit.tokenSegmented(compact, text("UiLibrary.segmented.optionA"),
                      text("UiLibrary.segmented.optionB"), text("UiLibrary.segmented.optionC"));
                switch (state) {
                    case "hover" -> segmented.option(1).hovered(true);
                    case "pressed" -> segmented.option(1).held(true);
                    case "focus" -> segmented.option(1).focused(true);
                    case "disabled" -> segmented.setDisabled(true);
                    default -> { }
                }
                if (!state.equals("rest")) {
                    segmented.setTouchable(Touchable.disabled);
                }
                matrix.add(segmented);
            }
            matrix.row();
        }
        return matrix;
    }

    /** Every select kind in every state; transient states are frozen through UiSelect. */
    private Table selectMatrix() {
        List<String> states = List.of("rest", "hover", "focus", "placeholder", "disabled");
        Table matrix = new Table();
        matrix.defaults().left().padRight(16).padBottom(8);
        matrix.add(kit.label(text("UiLibrary.buttons.kind"), "hud-small", 11, UiTheme.MUTED)).width(110);
        for (String state : states) {
            matrix.add(kit.label(text("UiLibrary.inputs.state." + state), "hud-small", 11, UiTheme.MUTED));
        }
        matrix.row();
        for (UiSelect.Kind kind : UiSelect.Kind.values()) {
            matrix.add(kit.label(text("UiLibrary.select.kind." + kind.name()), "hud-medium", 12.5f, UiTheme.TEXT));
            for (String state : states) {
                UiSelect select = sampleSelect(kind);
                switch (state) {
                    case "hover" -> select.hovered(true);
                    case "focus" -> select.focused(true);
                    case "placeholder" -> select.placeholder(text("UiLibrary.select.placeholder"));
                    case "disabled" -> select.setDisabled(true);
                    default -> { }
                }
                if (!state.equals("rest") && !state.equals("placeholder")) {
                    select.setTouchable(Touchable.disabled);
                }
                matrix.add(select);
            }
            matrix.row();
        }
        return matrix;
    }

    private UiSelect sampleSelect(UiSelect.Kind kind) {
        String[] items = selectItems();
        return kind == UiSelect.Kind.INLINE ? kit.tokenSelectInline(items)
              : kit.tokenSelect(kind == UiSelect.Kind.COMPACT, items);
    }

    private String[] selectItems() {
        return new String[] { text("UiLibrary.controls.optionA"), text("UiLibrary.controls.optionB"),
                              text("UiLibrary.select.optionC"), text("UiLibrary.select.optionD") };
    }

    /**
     * The popup list drawn in place under a face frozen open, the current value checked and the second row under the
     * pointer; live selects to click beside it.
     */
    private Table selectSamples() {
        Table samples = new Table();
        samples.top().left();
        samples.defaults().left().top().padRight(32);
        Table open = new Table();
        open.defaults().left();
        open.add(kit.label(text("UiLibrary.select.popup"), "hud-small", 11, UiTheme.MUTED)).padBottom(4).row();
        UiSelect owner = sampleSelect(UiSelect.Kind.REGULAR).opened(true);
        owner.setTouchable(Touchable.disabled);
        open.add(owner).row();
        UiListPopup list = kit.tokenListPopup(selectItems()).current(0).active(1);
        list.setTouchable(Touchable.disabled);
        open.add(list).minWidth(owner.getPrefWidth()).padTop(tokens.space().get("xs")).row();
        samples.add(open);

        Table live = new Table();
        live.defaults().left().padRight(16);
        live.add(kit.label(text("UiLibrary.select.live"), "hud-small", 11, UiTheme.MUTED)).colspan(3).padBottom(4)
              .row();
        live.add(sampleSelect(UiSelect.Kind.REGULAR));
        live.add(sampleSelect(UiSelect.Kind.COMPACT));
        live.add(sampleSelect(UiSelect.Kind.INLINE));
        samples.add(live);
        return samples;
    }

    private Table buttons() {
        Table buttons = new Table();
        buttons.defaults().left().padBottom(8);
        buttons.add(kit.label(text("UiLibrary.gdx.style"), "hud-small", 11, UiTheme.MUTED)).width(200);
        buttons.add(kit.label(text("UiLibrary.gdx.rest"), "hud-small", 11, UiTheme.MUTED)).width(200);
        buttons.add(kit.label(text("UiLibrary.gdx.pressed"), "hud-small", 11, UiTheme.MUTED)).width(200);
        buttons.add(kit.label(text("UiLibrary.gdx.disabled"), "hud-small", 11, UiTheme.MUTED)).width(200).row();
        List<String> styles = new ArrayList<>();
        theme.skin.getAll(TextButton.TextButtonStyle.class).keys().forEach(style -> {
            if (style.startsWith("hud")) {
                styles.add(style);
            }
        });
        styles.sort(String::compareTo);
        for (String style : styles) {
            buttons.add(kit.label(style, "hud-medium", 12, UiTheme.TEXT));
            for (int state = 0; state < 3; state++) {
                UiButton button = sampleButton(style);
                if (state == 1) {
                    button.pressed(true);
                } else if (state == 2) {
                    button.setDisabled(true);
                }
                buttons.add(button).minHeight(30);
            }
            buttons.row();
        }
        Label count = kit.label(text("UiLibrary.gdx.styleCount", styles.size()), "hud-small", 11.5f,
              UiTheme.AMBER);
        buttons.add(count).colspan(4).padTop(4);
        return buttons;
    }

    private UiButton sampleButton(String style) {
        return switch (style) {
            case "hud-icon" -> kit.button(style, "search", null, null);
            case "hud-utility", "hud-utility-row" -> kit.button(style, "map", text("UiLibrary.gdx.sampleMap"), null);
            default -> kit.button(style, null, text("UiLibrary.gdx.sampleAction"), null);
        };
    }

    private Table controls() {
        Table controls = new Table();
        controls.top().left();
        controls.defaults().left().top().padBottom(12);

        Table row = new Table();
        row.defaults().padRight(12);
        row.add(kit.segmented("hud-seg", false, text("UiLibrary.gdx.segAll"), text("UiLibrary.gdx.segMine"),
              text("UiLibrary.gdx.segCritical")).select(0));
        row.add(kit.segmented("hud-tab", false, text("UiLibrary.gdx.tabFriendly"), text("UiLibrary.gdx.tabContacts"))
              .select(0));
        row.add(kit.select(text("UiLibrary.gdx.selectValue"), false)).width(180);
        row.add(kit.select(text("UiLibrary.gdx.selectValue"), true));
        controls.add(row).row();

        Table chips = new Table();
        chips.defaults().padRight(8);
        chips.add(kit.chip(text("UiLibrary.status.info"), Tone.NORMAL));
        chips.add(kit.chip(text("UiLibrary.status.warning"), Tone.WARN));
        chips.add(kit.chip(text("UiLibrary.status.danger"), Tone.BAD));
        chips.add(kit.checkbox(text("UiLibrary.gdx.ticked"), true)).padLeft(12);
        chips.add(kit.checkbox(text("UiLibrary.gdx.clear"), false));
        controls.add(chips).row();

        controls.add(kit.search(text("UiLibrary.gdx.search"))).width(360).row();

        Table meters = kit.panel();
        meters.pad(14, 16, 14, 16);
        meters.defaults().growX().uniformX().padRight(12);
        meters.add(kit.meter(text("UiLibrary.gdx.armor"), false).set("91%", .91f, UiTheme.MINT));
        meters.add(kit.meter(text("UiLibrary.gdx.structure"), false).set("40%", .4f, UiTheme.AMBER));
        meters.add(kit.meter(text("UiLibrary.gdx.heat"), false).set("12", .4f, UiTheme.CORAL));
        controls.add(meters).width(460).row();

        Table panels = new Table();
        panels.top().left();
        panels.defaults().top().padRight(16);
        panels.add(listPanel()).width(300);
        panels.add(menuPanel()).width(260);
        controls.add(panels).row();
        return controls;
    }

    private Table listPanel() {
        Table panel = kit.panel();
        panel.add(kit.header(text("UiLibrary.gdx.panel"), "3", kit.closeButton(() -> { }))).growX().row();
        Table list = new Table();
        list.top();
        for (int index = 0; index < 3; index++) {
            UiButton row = kit.button("hud-row", null,
                  text(index == 1 ? "UiLibrary.sample.selectedRow" : "UiLibrary.sample.row"), null);
            row.getLabel().setAlignment(Align.left);
            row.getCell(row.getLabel()).growX();
            row.pressed(index == 1);
            list.add(row).growX().pad(0, 10, 6, 10).row();
        }
        panel.add(list).growX().row();
        Table foot = kit.footer(panel);
        foot.add(kit.label(text("UiLibrary.sample.detail"), "hud-small", 11.5f, UiTheme.MUTED)).growX().left();
        foot.add(kit.button("hud-mini", null, text("UiLibrary.gdx.sampleAction"), null));
        return panel;
    }

    private Table menuPanel() {
        Table popover = new Table();
        popover.setBackground(kit.skin.getDrawable("panel-pop"));
        popover.pad(8, 2, 8, 2);
        UiMenuList list = new UiMenuList(kit);
        list.item(text("UiLibrary.gdx.menuShortcut"), "Ctrl+K", false, false, true, () -> { });
        list.item(text("UiLibrary.gdx.menuChecked"), null, true, false, true, () -> { });
        list.separator();
        list.item(text("UiLibrary.gdx.menuGroup"), null, false, true, true, () -> { });
        list.item(text("UiLibrary.gdx.menuDisabled"), null, false, false, false, () -> { });
        popover.add(list).growX();
        return popover;
    }

    // ---- Helpers

    private static String text(String key, Object... arguments) {
        return arguments.length == 0 ? Messages.getString(key) : Messages.getString(key, arguments);
    }

    /** A libGDX color from a design token's ARGB value. */
    private static Color color(int argb) {
        return new Color((argb << 8) | (argb >>> 24));
    }

    private static String weight(int instance) {
        return switch (instance) {
            case UiTheme.REGULAR -> "400";
            case UiTheme.MEDIUM -> "500";
            case UiTheme.CONDENSED_MEDIUM -> "condensed 500";
            case UiTheme.CONDENSED_SEMIBOLD -> "condensed 600";
            case UiTheme.CONDENSED_BOLD -> "condensed 700";
            default -> "instance " + instance;
        };
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }
}
