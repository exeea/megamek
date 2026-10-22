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
import com.badlogic.gdx.scenes.scene2d.Stage;
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
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiMenuList;
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
                matrix.add(button);
            }
            matrix.row();
        }
        return matrix;
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
