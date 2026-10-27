/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.MMConstants;
import megamek.SuiteConstants;
import megamek.Version;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.SplashArt;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Action;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.ConnectRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.HostRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.LoadRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Role;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.ScenarioRequest;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.design.swing.TokenFonts;
import megamek.client.ui.dialogs.buttonDialogs.GameOptionsDialog;
import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.client.ui.dialogs.iconChooser.CamoChooserDialog;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiListPopup;
import megamek.client.ui.gdx.UiOptionBar;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.game.IGame;
import megamek.common.icons.Camouflage;
import megamek.common.interfaces.PlanetaryConditionsUsing;
import megamek.common.net.NetworkAddresses;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.scenario.Scenario;
import megamek.common.scenario.ScenarioLoader;
import megamek.common.util.TipOfTheDay;
import megamek.logging.MMLogger;

/**
 * The start screen: a greeting typed over a dark field, the art revealed over it by hexes popping open, then the main
 * menu sliding in at the right with the version at the top left and a tip at the bottom left. Pages such as Host and
 * Connect replace the menu in the same column; Esc goes back. The art and its overlays are drawn once with Java2D and
 * uploaded; the GPU only animates on top of them. Once the intro has played, only slow embers move, at 30 frames a
 * second.
 */
final class GpuStartScreen extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuStartScreen.class);
    private static final String TIPS = "megamek.client.TipOfTheDay";
    private static final double TIP_SECONDS = 10;
    private static final int EMBERS = 140;
    private static final float TIP_WIDTH = 360;
    private static final float NOTES_WIDTH = 320;
    private static final float PORT_WIDTH = 110;
    /** The Connect page's segment that has a bot join instead of the player. */
    private static final int AS_BOT = 1;
    /** The narrowest the column gets on the pages that list or describe something. */
    private static final float WIDE_COLUMN = 340;
    /** The least height a list or description keeps when the window is short. */
    private static final float MIN_FILLER = 96;
    private static final float ROLE_WIDTH = 130;
    /** A camouflage swatch on the briefing, about a hex's proportions. */
    private static final int SWATCH_WIDTH = 28;
    private static final int SWATCH_HEIGHT = 24;
    private static final DateTimeFormatter SAVED_AT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,
          FormatStyle.SHORT);
    private static boolean probed;

    private record Hex(double x, double y, double at) { }

    /** A rendered line of greeting text, the pixel width of each prefix, its ascent and an M's width. */
    private record TextLine(Texture texture, int[] widths, int ascent, int charWidth) {
        int width() {
            return widths[widths.length - 1];
        }

        void dispose() {
            texture.dispose();
        }
    }

    private enum Page { MAIN, HELP, HOST, CONNECT, LOAD, RESUME, SCENARIOS, BRIEFING }

    /** A scenario read and its game made for the briefing, with each player's camouflage swatch, or why that failed. */
    private record Briefing(Scenario scenario, IGame game, List<BufferedImage> swatches, String error) { }

    private final GpuStartWindow window;
    private final GUIPreferences preferences;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final java.awt.Color base = awtColor("surface.base");
    private final java.awt.Color mint = awtColor("accent.primary");
    private final java.awt.Color text = awtColor("text.primary");
    private final java.awt.Color muted = awtColor("text.muted");
    private final java.awt.Color strong = awtColor("border.strong");
    private final Random random = new Random(7);
    private final List<float[]> particles = new ArrayList<>();
    private final Matrix4 pixels = new Matrix4();
    private final List<Actor> column = new ArrayList<>();
    private final List<Actor> focusable = new ArrayList<>();
    private final List<String> tips;
    private final boolean motion;
    /** The developer switch's page and shot, or null; only the first start window of a run reads it. */
    private final StartScreenProbe probe;
    private boolean shotSaved;

    private BufferedImage art;
    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private boolean inputBlocked;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private float scale = 1;
    private int bw;
    private int bh;
    private float w;
    private float h;
    private Texture backdrop;
    private TextLine line1;
    private TextLine line2;
    private List<Hex> hexes = new ArrayList<>();
    private double hexR;
    private Image logo;
    private Texture logoTexture;
    private Table bottomRow;
    private Table corner;
    private Table tipCard;
    private Label tipText;
    private Label tipAuthor;
    private int tipIndex;
    private double tipShownAt;
    private Page page = Page.MAIN;
    /** When the page last changed, in seconds on the clock; the first page slides in with the intro. */
    private double pageShownAt = -1;
    private long start;
    private boolean skipped;
    private boolean settledRate;
    /**
     * The Host or Connect page's inputs, kept while it shows so opening its online options or switching to a bot keeps
     * what was typed. The name and port belong to whichever of the two shows.
     */
    private UiTextField nameInput;
    private UiFormField nameField;
    private UiButton onlineToggle;
    private boolean onlineOpen;
    private UiTextField passwordInput;
    private UiKit.TokenPassword passwordBox;
    private UiTextField portInput;
    private UiFormField portField;
    private Table passwordRow;
    private UiChoice registerBox;
    private UiTextField metaserverInput;
    private UiFormField metaserverField;
    private Table addresses;
    private Label publicAddress;
    private UiButton hostButton;
    private UiButton backButton;
    private UiSegmented roleBar;
    private UiTextField addressInput;
    private UiFormField addressField;
    private Table addressRow;
    private UiChoice sbfBox;
    private UiButton connectButton;
    /** Counts pages shown and reads asked for; a background read whose count is no longer current is dropped. */
    private int generation;
    /** Set once the screen is disposed, so results that arrive after it are dropped. GPU thread only. */
    private boolean disposed;
    /** The Load game page's list, kept while it shows so its scroll stays; and its note, or null. */
    private Actor saveList;
    private Label loadNote;
    private UiButton browseSaves;
    /** The save the Resume page loads, what it says about itself, and the player the host takes. */
    private StartSaves.Summary resume;
    private Table resumeHeader;
    private UiSelect playAs;
    private UiFormField playAsField;
    /** The scenarios, read once per start screen; null until they are. */
    private List<StartScenarios.Entry> catalog;
    private int groupIndex;
    private UiSelect groupSelect;
    /** What the browser's search field holds; kept while the start screen is open, as the group is. */
    private String scenarioQuery = "";
    private UiTextField searchInput;
    private Table scenarioFilters;
    private Table scenarioRows;
    private ScrollPane scenarioList;
    private UiButton openScenario;
    /** The briefing's scenario file, the page Back returns to, and the briefing once it's made. */
    private File briefingFile;
    private Page briefingBack = Page.SCENARIOS;
    private Briefing briefing;
    private Table briefingHeader;
    private ScrollPane briefingText;
    private Table playerRows;
    private final List<UiSelect> roleSelects = new ArrayList<>();
    /** Each player's camouflage swatch on the briefing, and the textures they show. */
    private final List<Image> swatches = new ArrayList<>();
    private final List<Texture> swatchTextures = new ArrayList<>();
    /** The scenario's own value of each rule, so the briefing can count the ones the player changed. */
    private final Map<String, Object> ruleBaseline = new HashMap<>();
    private Table rulesLine;
    private Label rulesValue;
    private Table conditionsLine;
    private Label conditionsValue;
    private UiButton startButton;

    GpuStartScreen(GpuStartWindow window, boolean greet, GUIPreferences preferences) {
        this.window = window;
        this.preferences = preferences;
        motion = !preferences.getReduceMotion();
        skipped = !greet || !motion;
        tips = TipOfTheDay.plainTips(TIPS);
        probe = takeProbe();
        if (probe != null && probe.page() != null) {
            skipped = true;
        }
    }

    private static synchronized StartScreenProbe takeProbe() {
        if (probed) {
            return null;
        }
        probed = true;
        return StartScreenProbe.fromEnvironment();
    }

    private java.awt.Color awtColor(String name) {
        return new java.awt.Color(tokens.color(name, Mode.DARK), true);
    }

    /** Seconds since the first frame. */
    private double clock() {
        return (System.nanoTime() - start) / 1e9;
    }

    /** The intro's time: past its end once skipped. */
    private double introTime() {
        return skipped ? StartTimeline.INTRO_END + 1 : clock();
    }

    /** GPU thread: ignores the mouse and keyboard while a modal Swing form is open, as a disabled window would. */
    void setInputBlocked(boolean blocked) {
        inputBlocked = blocked;
        if (stage == null) {
            return;
        }
        if (blocked) {
            stage.cancelTouchFocus();
        }
        Gdx.input.setInputProcessor(blocked ? null : stage);
    }

    @Override
    public void create() {
        window.started();
        boardSkin = new GpuBoardSkin();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(inputBlocked ? null : stage);
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        art = readImage(new File(Configuration.miscImagesDir(), "mm-background.jpg"));
        BufferedImage logoImage = readImage(new File(Configuration.miscImagesDir(), "mm-logo.png"));
        if (logoImage != null) {
            logoTexture = texture(SplashArt.logoWithTag(logoImage), true);
            logoTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            logo = new Image(logoTexture);
            stage.addActor(logo);
        }
        for (int i = 0; i < EMBERS; i++) {
            particles.add(newParticle(true));
        }
        bottomRow = bottomRow();
        stage.addActor(bottomRow);
        corner = corner();
        stage.addActor(corner);
        tipCard = tipCard();
        stage.addActor(tipCard);
        showPage(Page.MAIN);
        keys();
        start = System.nanoTime();
        if (skipped && motion) {
            // Reopened after a game: no greeting, but the menu still slides in
            pageShownAt = 0;
        }
        applyProbe();
    }

    /** Opens the page the developer switch asks for, in the state its options ask for. */
    private void applyProbe() {
        if (probe == null || probe.page() == null) {
            return;
        }
        Page asked;
        try {
            asked = Page.valueOf(probe.page());
        } catch (IllegalArgumentException unknown) {
            LOGGER.warn("MM_START_PAGE names no start page: {}", probe.page());
            return;
        }
        if (asked == Page.BRIEFING) {
            // The scenario MM_START_SCENARIO names, or the last one played
            String file = System.getenv("MM_START_SCENARIO");
            openBriefing(new File(file == null || file.isBlank()
                  ? PreferenceManager.getClientPreferences().getLastScenario() : file), Page.SCENARIOS);
        } else if (asked == Page.RESUME) {
            List<File> saves = StartSaves.list(new File(MMConstants.SAVEGAME_DIR));
            showPage(Page.LOAD);
            if (!saves.isEmpty()) {
                readSave(saves.get(0), false);
            }
        } else if (asked != Page.MAIN) {
            showPage(asked);
        }
        if (asked != Page.MAIN) {
            pageShownAt = 0;
        }
        if (asked == Page.HOST) {
            if (probe.has("online") && !onlineOpen) {
                onlineOpen = true;
                chevron(onlineToggle, true);
                fillHostColumn();
            }
            if (probe.has("reveal")) {
                passwordBox.shown(true);
            }
        }
        if (asked == Page.CONNECT) {
            if (probe.has("bot")) {
                roleBar.select(AS_BOT);
                fillConnectColumn();
            }
            if (probe.has("sbf")) {
                sbfBox.setChecked(true);
            }
        }
    }

    /** Saves the frame just drawn as the developer switch's PNG, then quits when it asks to. */
    private void saveShot() {
        shotSaved = true;
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, bw, bh);
        try {
            BufferedImage image = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
            ByteBuffer rgba = pixels.getPixels();
            for (int y = 0; y < bh; y++) {
                // The frame buffer's rows run upward
                int from = (bh - y - 1) * bw * 4;
                for (int x = 0; x < bw; x++, from += 4) {
                    image.setRGB(x, y, (rgba.get(from) & 0xFF) << 16 | (rgba.get(from + 1) & 0xFF) << 8
                          | rgba.get(from + 2) & 0xFF);
                }
            }
            File file = probe.shot().getAbsoluteFile();
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            ImageIO.write(image, "png", file);
            LOGGER.info("Start screen shot saved to {}", file);
        } catch (IOException failure) {
            LOGGER.error(failure, "The start screen could not save its shot to {}", probe.shot());
        } finally {
            pixels.dispose();
        }
        if (probe.quit()) {
            window.dispatch(Action.QUIT);
        }
    }

    private static BufferedImage readImage(File file) {
        try {
            return ImageIO.read(file);
        } catch (IOException failure) {
            LOGGER.error(failure, "The start screen could not read {}", file);
            return null;
        }
    }

    // ---- The menu ----

    private void showPage(Page shown) {
        page = shown;
        generation++;
        column.forEach(Actor::remove);
        column.clear();
        if (shown == Page.HOST) {
            buildHostPage(this::submitHost, Page.MAIN);
            fillHostColumn();
        } else if (shown == Page.RESUME) {
            buildHostPage(this::submitResume, Page.LOAD);
            buildResume();
            fillHostColumn();
        } else if (shown == Page.CONNECT) {
            buildConnectPage();
            fillConnectColumn();
        } else if (shown == Page.LOAD) {
            buildLoadPage();
            fillLoadColumn();
        } else if (shown == Page.SCENARIOS) {
            showScenarios();
        } else if (shown == Page.BRIEFING) {
            showBriefing();
        } else {
            List<Actor> items = new ArrayList<>();
            if (shown == Page.MAIN) {
                // The latest save, when there is one, is the way back in and takes the accent from New game
                List<File> saves = StartSaves.list(new File(MMConstants.SAVEGAME_DIR));
                if (!saves.isEmpty()) {
                    items.add(continueItem(saves.get(0)));
                }
                items.add(pageButton(saves.isEmpty() ? "button-primary" : "button-secondary", "StartScreen.newGame",
                      Page.HOST));
                items.add(pageItem("button-secondary", "StartScreen.loadGame", () -> showPage(Page.LOAD),
                      () -> window.dispatch(Action.QUICK_LOAD)));
                // As a dev convenience, Ctrl opens the last scenario's briefing
                items.add(pageItem("button-secondary", "StartScreen.scenario", () -> showPage(Page.SCENARIOS),
                      this::lastScenario));
                items.add(pageButton("button-secondary", "StartScreen.connect", Page.CONNECT));
                items.add(overline("StartScreen.tools"));
                items.add(item("button-quiet", "StartScreen.mapEditor", Action.MAP_EDITOR, null));
                items.add(item("button-quiet", "StartScreen.openMap", Action.OPEN_MAP, null));
                items.add(item("button-quiet", "StartScreen.unitBrowser", Action.UNIT_BROWSER, null));
                items.add(item("button-quiet", "StartScreen.randomArmy", Action.RANDOM_ARMY, null));
                items.add(item("button-quiet", "StartScreen.skinEditor", Action.SKIN_EDITOR, null));
            } else {
                items.add(overline("StartScreen.help"));
                items.add(item("button-quiet", "StartScreen.helpContents", Action.HELP_CONTENTS, null));
                items.add(item("button-quiet", "StartScreen.helpSkinning", Action.HELP_SKINNING, null));
                items.add(item("button-quiet", "StartScreen.reportBug", Action.REPORT_BUG, null));
                items.add(item("button-quiet", "StartScreen.copySystemData", Action.COPY_SYSTEM_DATA, null));
                items.add(item("button-quiet", "StartScreen.resetPrompts", Action.RESET_PROMPTS, null));
                items.add(item("button-quiet", "StartScreen.about", Action.ABOUT, null));
                items.add(backButton());
            }
            setColumn(items);
        }
        if (start != 0) {
            pageShownAt = clock();
        }
        Actor first = switch (shown) {
            case HOST, CONNECT -> nameInput;
            case RESUME -> playAs != null ? playAs : nameInput;
            default -> null;
        };
        stage.setKeyboardFocus(first);
        if (first instanceof TextField field) {
            field.selectAll();
        }
    }

    /**
     * Shows {@code items} in the column, in order, and makes them, then the bottom row, the keyboard's stops. Items
     * already showing stay on the stage, so they keep their keyboard focus and text.
     */
    private void setColumn(List<Actor> items) {
        for (Actor actor : column) {
            if (!items.contains(actor)) {
                actor.remove();
            }
        }
        column.clear();
        column.addAll(items);
        focusable.clear();
        for (Actor item : items) {
            if (item.getStage() == null) {
                stage.addActor(item);
            }
            stops(item);
        }
        for (Actor actor : bottomRow.getChildren()) {
            stops(actor);
        }
    }

    /** Adds the keyboard stops in {@code actor}: itself when it is a control, or the controls inside it. */
    private void stops(Actor actor) {
        if (actor instanceof UiButton || actor instanceof UiChoice || actor instanceof TextField
              || actor instanceof UiOptionBar || actor instanceof UiSelect) {
            focusable.add(actor);
        } else if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                stops(child);
            }
        }
    }

    /** A menu button; with Ctrl held, it asks for {@code withControl} instead, where there is one. */
    private UiButton item(String style, String key, Action action, Action withControl) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> window.dispatch(withControl != null && controlHeld() ? withControl : action));
        return button;
    }

    /** A menu button that opens a page; with Ctrl held, it runs {@code withControl} instead. */
    private UiButton pageItem(String style, String key, Runnable action, Runnable withControl) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> (controlHeld() ? withControl : action).run());
        return button;
    }

    /** Continue: the latest save, named with how long ago it was made, loaded as a click on it in the list loads it. */
    private UiButton continueItem(File save) {
        UiButton button = kit.button("button-primary", null, Messages.getString("StartScreen.continue"),
              StartSaves.title(save) + "  \u00b7  " + StartSaves.ago(save.lastModified(), System.currentTimeMillis()));
        leftAligned(button);
        UiKit.onChange(button, () -> readSave(save, true));
        return button;
    }

    /** A menu button that opens {@code shown} in the column. */
    private UiButton pageButton(String style, String key, Page shown) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> showPage(shown));
        return button;
    }

    private UiButton backButton() {
        return backButton(Page.MAIN);
    }

    /** Back, to {@code target}. */
    private UiButton backButton(Page target) {
        UiButton back = kit.button("button-secondary", "chevron-left", Messages.getString("StartScreen.back"), null);
        leftAligned(back);
        UiKit.onChange(back, () -> showPage(target));
        return back;
    }

    /** The page Back and Esc return to from {@code from}. */
    private Page parent(Page from) {
        return switch (from) {
            case RESUME -> Page.LOAD;
            case BRIEFING -> briefingBack;
            default -> Page.MAIN;
        };
    }

    /** Whether {@code shown} lists or describes something and so takes a wider column. */
    private static boolean wide(Page shown) {
        return shown == Page.LOAD || shown == Page.RESUME || shown == Page.SCENARIOS || shown == Page.BRIEFING;
    }

    /** A line of wrapped text in the column, such as a page's status or an error. */
    private Label note(String text, String role) {
        Label note = kit.typeLabel(text, "body", role);
        note.setWrap(true);
        note.setAlignment(Align.left);
        return note;
    }

    /** One line of text that shortens with an ellipsis rather than wrap. */
    private Label line(String text, String type, String role) {
        Label line = kit.typeLabel(text, type, role);
        line.setEllipsis(true);
        return line;
    }

    /**
     * Runs {@code work} off the GPU thread, then hands its result to {@code done} on it, unless another page or read
     * has been asked for since.
     */
    private <T> void background(String name, Supplier<T> work, Consumer<T> done) {
        int asked = generation;
        // Gdx.app moves to the next native window once this one closes; results stay with this one
        Application app = Gdx.app;
        Thread thread = new Thread(() -> {
            T result = work.get();
            app.postRunnable(() -> {
                if (!disposed && asked == generation) {
                    done.accept(result);
                }
            });
        }, "MegaMek-start-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean controlHeld() {
        return Gdx.input.isKeyPressed(Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Keys.CONTROL_RIGHT);
    }

    private static void leftAligned(UiButton button) {
        button.left();
        button.getCells().forEach(cell -> cell.left());
        button.getLabelCell().expandX();
    }

    private Label overline(String key) {
        return kit.roleTypeLabel("overline", Messages.getString(key), "text.muted");
    }

    // ---- The Host page ----

    /** The Host page's inputs, filled from the last game's choices; the main button runs {@code submit}. */
    private void buildHostPage(Runnable submit, Page back) {
        ClientPreferences client = PreferenceManager.getClientPreferences();
        nameInput = field(client.getLastPlayerName());
        nameField = kit.formField(Messages.getString("MegaMek.GameConnection.name"), nameInput)
              .helper(Messages.getString("MegaMek.GameConnection.nameHelper"));

        String lastPassword = client.getLastServerPass();
        onlineOpen = StartHostForm.onlineOpen(lastPassword, client.getLastServerPort());
        onlineToggle = kit.button("button-quiet", onlineOpen ? "chevron-down" : "chevron-right",
              Messages.getString("MegaMek.GameConnection.online"), null);
        leftAligned(onlineToggle);
        // A section heading: its chevron lines up with the fields under it
        onlineToggle.padLeft(0);
        UiKit.onChange(onlineToggle, () -> {
            onlineOpen = !onlineOpen;
            chevron(onlineToggle, onlineOpen);
            fillHostColumn();
        });

        buildServerInputs(lastPassword, client.getLastServerPort());

        registerBox = kit.check(Messages.getString("MegaMek.GameConnection.register"), false);
        registerBox.left();
        UiKit.onChange(registerBox, this::fillHostColumn);
        metaserverInput = field(client.getMetaServerName());
        metaserverField = kit.formField(Messages.getString("MegaMek.GameConnection.metaserver"), metaserverInput);

        addresses = addresses();
        hostButton = kit.button("button-primary", null, Messages.getString("MegaMek.GameConnection.host"), null);
        leftAligned(hostButton);
        UiKit.onChange(hostButton, submit);
        backButton = backButton(back);
    }

    /** The game's password and port, side by side in {@link #passwordRow}. */
    private void buildServerInputs(String lastPassword, int lastPort) {
        UiKit.TokenPassword password = kit.tokenPassword(false);
        passwordBox = password;
        passwordInput = password.field;
        passwordInput.setText(lastPassword == null ? "" : lastPassword);
        passwordInput.setFocusTraversal(false);
        UiFormField passwordField = kit.formField(Messages.getString("MegaMek.GameConnection.password"),
              password).helper(Messages.getString("MegaMek.GameConnection.passwordHelper"));
        portInput = field(String.valueOf(lastPort));
        portField = kit.formField(Messages.getString("MegaMek.GameConnection.port"), portInput);
        passwordRow = new Table();
        passwordRow.top().left();
        passwordRow.add(passwordField).growX().minWidth(0).top();
        passwordRow.add(portField).width(PORT_WIDTH).padLeft(12).top();
    }

    private UiTextField field(String text) {
        UiTextField field = kit.input(text == null ? "" : text, false);
        // The screen moves the keyboard between all its controls, not only between text fields
        field.setFocusTraversal(false);
        return field;
    }

    private void chevron(UiButton button, boolean open) {
        if (!button.icons.isEmpty() && button.icons.get(0) instanceof Image icon) {
            icon.setDrawable(boardSkin.skin.getDrawable("icon-" + (open ? "chevron-down" : "chevron-right")));
        }
    }

    /** Lays the Host page's column out for the online options' and server browser's current state. */
    private void fillHostColumn() {
        boolean resuming = page == Page.RESUME;
        List<Actor> items = new ArrayList<>();
        items.add(overline(resuming ? "StartScreen.loadGame" : "StartScreen.newGame"));
        if (resuming) {
            items.add(resumeHeader);
        }
        items.add(resuming && playAsField != null ? playAsField : nameField);
        if (resuming && watching()) {
            items.add(nameField);
        }
        items.add(onlineToggle);
        if (onlineOpen) {
            items.add(passwordRow);
            items.add(registerBox);
            if (registerBox.isChecked()) {
                items.add(metaserverField);
            }
            items.add(addresses);
        }
        items.add(hostButton);
        items.add(backButton);
        // Keep the title label: a new one would slide in again
        if (!column.isEmpty() && column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        setColumn(items);
    }

    /**
     * The addresses friends type to join: the local one, found at once, and the public one, which is only asked of an
     * outside service when the player chooses to show it, since it would also show on a stream or a screenshot.
     */
    private Table addresses() {
        Table table = new Table();
        table.left().top();
        UiButton show = kit.button("button-quiet-compact", null, Messages.getString("StartScreen.showAddress"),
              null);
        // Rows keep the button's height so the page doesn't shift when the address replaces it
        table.defaults().left().minHeight(show.getPrefHeight()).padBottom(4);
        Label local = kit.typeLabel(Messages.getString("StartScreen.findingAddress"), "small", "text.primary");
        table.add(kit.typeLabel(Messages.getString("StartScreen.localAddress"), "small", "text.muted"))
              .padRight(12);
        table.add(local).expandX().row();
        table.add(kit.typeLabel(Messages.getString("StartScreen.publicAddress"), "small", "text.muted"))
              .padRight(12);
        publicAddress = kit.typeLabel("", "small", "text.primary");
        Cell<Actor> publicCell = table.add((Actor) show);
        UiKit.onChange(show, () -> {
            publicAddress.setText(Messages.getString("StartScreen.findingAddress"));
            publicCell.setActor(publicAddress);
            int at = focusable.indexOf(show);
            if (at >= 0) {
                focusable.remove(at);
            }
            if (stage.getKeyboardFocus() == show) {
                stage.setKeyboardFocus(hostButton);
            }
            lookUp(NetworkAddresses::publicAddress, publicAddress);
        });
        lookUp(NetworkAddresses::localAddress, local);
        return table;
    }

    /** Finds an address off the GPU thread, then shows it, or that it wasn't found, in {@code label}. */
    private void lookUp(Supplier<String> address, Label label) {
        Application app = Gdx.app;
        Thread thread = new Thread(() -> {
            String found = address.get();
            app.postRunnable(() -> {
                if (!disposed) {
                    label.setText(found == null ? Messages.getString("StartScreen.noAddress") : found);
                }
            });
        }, "MegaMek-start-address");
        thread.setDaemon(true);
        thread.start();
    }

    /** Checks the Host page; shows what is wrong under the inputs, or asks MegaMek to host the game. */
    private void submitHost() {
        String name = nameInput.getText().trim();
        int port = StartHostForm.port(portInput.getText());
        nameField.error(name.isEmpty() ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        portField.error(portError(port));
        if (name.isEmpty()) {
            stage.setKeyboardFocus(nameInput);
            return;
        }
        if (port < 0) {
            if (!onlineOpen) {
                onlineOpen = true;
                chevron(onlineToggle, true);
                fillHostColumn();
            }
            stage.setKeyboardFocus(portInput);
            return;
        }
        boolean register = registerBox.isChecked();
        window.host(new HostRequest(name, passwordInput.getText(), port, register,
              register ? metaserverInput.getText().trim() : ""));
    }

    /** The port field's error for {@code port}, as {@link StartHostForm#port} reads it, or null when it is fine. */
    private static String portError(int port) {
        return port < 0 ? Messages.getString("MegaMek.GameConnection.portError",
              String.valueOf(MMConstants.MIN_PORT), String.valueOf(MMConstants.MAX_PORT)) : null;
    }

    // ---- The Connect page ----

    /** The Connect page's inputs, filled from the last game joined; the player joins unless Bot is chosen. */
    private void buildConnectPage() {
        ClientPreferences client = PreferenceManager.getClientPreferences();
        roleBar = kit.tokenSegmented(false, Messages.getString("MegaMek.GameConnection.player"),
              Messages.getString("MegaMek.GameConnection.bot"));
        // The segments share the column's width
        for (Cell<?> cell : roleBar.getCells()) {
            cell.expandX();
        }
        UiKit.onChange(roleBar, this::fillConnectColumn);

        nameInput = field(client.getLastPlayerName());
        nameField = kit.formField(Messages.getString("MegaMek.GameConnection.name"), nameInput);
        addressInput = field(client.getLastConnectAddr());
        addressField = kit.formField(Messages.getString("MegaMek.GameConnection.address"), addressInput);
        portInput = field(String.valueOf(client.getLastConnectPort()));
        portField = kit.formField(Messages.getString("MegaMek.GameConnection.port"), portInput);
        addressRow = new Table();
        addressRow.top().left();
        addressRow.add(addressField).growX().minWidth(0).top();
        addressRow.add(portField).width(PORT_WIDTH).padLeft(12).top();

        sbfBox = kit.check(Messages.getString("MegaMek.GameConnection.sbf"), false);
        sbfBox.left();
        connectButton = kit.button("button-primary", null, Messages.getString("MegaMek.GameConnection.connect"),
              null);
        leftAligned(connectButton);
        UiKit.onChange(connectButton, this::submitConnect);
        backButton = backButton();
    }

    private boolean asBot() {
        return roleBar.getSelectedIndex() == AS_BOT;
    }

    /** Lays the Connect page's column out for the player or a bot; there is no Strategic BattleForce bot. */
    private void fillConnectColumn() {
        boolean bot = asBot();
        nameField.helper(Messages.getString(bot ? "MegaMek.GameConnection.botNameHelper"
              : "MegaMek.GameConnection.nameHelper"));
        connectButton.setText(kit.cased("button", Messages.getString(bot ? "MegaMek.GameConnection.connectBot"
              : "MegaMek.GameConnection.connect")));
        List<Actor> items = new ArrayList<>();
        items.add(overline("StartScreen.connect"));
        items.add(roleBar);
        items.add(nameField);
        items.add(addressRow);
        if (!bot) {
            items.add(sbfBox);
        }
        items.add(connectButton);
        items.add(backButton);
        if (!column.isEmpty() && column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        setColumn(items);
    }

    /** Checks the Connect page; shows what is wrong under the inputs, or asks MegaMek to join the game. */
    private void submitConnect() {
        String name = nameInput.getText().trim();
        String address = addressInput.getText().trim();
        int port = StartHostForm.port(portInput.getText());
        nameField.error(name.isEmpty() ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        addressField.error(address.isEmpty() ? Messages.getString("MegaMek.GameConnection.addressError") : null);
        portField.error(portError(port));
        Actor wrong = name.isEmpty() ? nameInput : address.isEmpty() ? addressInput : port < 0 ? portInput : null;
        if (wrong != null) {
            stage.setKeyboardFocus(wrong);
            return;
        }
        boolean bot = asBot();
        window.connect(new ConnectRequest(name, address, port, bot, !bot && sbfBox.isChecked()));
    }

    // ---- The Load game page ----

    /** The saves in MegaMek's save folder, newest first, each a button that reads it. */
    private void buildLoadPage() {
        loadNote = null;
        browseSaves = kit.button("button-quiet", null, Messages.getString("StartScreen.browseSaves"), null);
        leftAligned(browseSaves);
        UiKit.onChange(browseSaves, () -> window.chooseFile(new File(MMConstants.SAVEGAME_DIR),
              Messages.getString("MegaMek.SaveGameDialog.title"), new FileNameExtensionFilter(
                    Messages.getString("StartScreen.saveFiles"), "sav", "gz"), file -> {
                  if (page == Page.LOAD) {
                      readSave(file, false);
                  }
              }));
        List<File> saves = StartSaves.list(new File(MMConstants.SAVEGAME_DIR));
        if (saves.isEmpty()) {
            saveList = null;
            loadNote = note(Messages.getString("StartScreen.noSaves"), "text.muted");
            return;
        }
        Table rows = new Table();
        rows.top().left();
        rows.defaults().growX().left();
        for (File save : saves) {
            UiButton row = kit.button("button-quiet", null, StartSaves.title(save), savedAt(save));
            leftAligned(row);
            UiKit.onChange(row, () -> readSave(save, true));
            // The way to the Resume page's choices for a save that would otherwise load at once
            UiButton options = kit.button("button-quiet-compact", null,
                  Messages.getString("StartScreen.saveOptions"), null);
            UiKit.onChange(options, () -> readSave(save, false));
            Table line = new Table();
            line.add(row).growX().minWidth(0);
            line.add(options).padLeft(4);
            rows.add(line).row();
        }
        saveList = kit.scrollList(rows, true);
    }

    private void fillLoadColumn() {
        List<Actor> items = new ArrayList<>();
        items.add(overline("StartScreen.loadGame"));
        if (saveList != null) {
            items.add(saveList);
        }
        if (loadNote != null) {
            items.add(loadNote);
        }
        items.add(browseSaves);
        items.add(backButton());
        if (!column.isEmpty() && column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        setColumn(items);
    }

    /** When {@code file} was last written, in the player's locale. */
    private static String savedAt(File file) {
        return SAVED_AT.format(Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()));
    }

    /**
     * Reads {@code save} off the GPU thread, then, when {@code quick} and it is the last player's own game against
     * bots, loads it at once; otherwise opens it on the Resume page, or says on the Load game page why it can't load.
     */
    private void readSave(File save, boolean quick) {
        generation++;
        if (page == Page.LOAD) {
            showLoadNote(Messages.getString("StartScreen.readingSave", StartSaves.title(save)), "text.muted");
        }
        background("save", () -> StartSaves.read(save), summary -> {
            ClientPreferences client = PreferenceManager.getClientPreferences();
            String player = quick ? summary.quickPlayer(client.getLastPlayerName()) : null;
            if (player != null) {
                String password = client.getLastServerPass();
                window.load(new LoadRequest(summary.file(), new HostRequest(player,
                      password == null ? "" : password, client.getLastServerPort(), false, ""), false,
                      summary.bots()));
            } else if (summary.loadable()) {
                resume = summary;
                showPage(Page.RESUME);
            } else {
                if (page != Page.LOAD) {
                    showPage(Page.LOAD);
                }
                showLoadNote(saveProblem(summary), "status.danger");
            }
        });
    }

    private void showLoadNote(String text, String role) {
        loadNote = note(text, role);
        fillLoadColumn();
    }

    /** Why {@code summary}'s save can't be loaded, for the page to say. */
    static String saveProblem(StartSaves.Summary summary) {
        String name = StartSaves.title(summary.file());
        return switch (summary.problem()) {
            case NO_VERSION -> Messages.getString("StartScreen.saveNoVersion", name, SuiteConstants.VERSION);
            case WRONG_VERSION -> Messages.getString("StartScreen.saveWrongVersion", name, summary.version(),
                  SuiteConstants.VERSION);
            default -> Messages.getString("StartScreen.saveUnreadable", name);
        };
    }

    /**
     * The Resume page: the Host page for the chosen save, whose name field becomes a choice of the save's players
     * (the last name played, where the save has it), and whose main button loads it.
     */
    private void buildResume() {
        hostButton.setText(kit.cased("button", Messages.getString("StartScreen.loadGame")));
        resumeHeader = new Table();
        resumeHeader.left().top();
        resumeHeader.defaults().left().growX().minWidth(0);
        resumeHeader.add(line(StartSaves.title(resume.file()), "body", "text.primary")).row();
        resumeHeader.add(line(savedAt(resume.file()), "small", "text.muted"));
        playAs = null;
        playAsField = null;
        List<String> players = resume.players();
        if (!players.isEmpty()) {
            List<String> choices = new ArrayList<>(players);
            choices.add(Messages.getString("StartScreen.watch"));
            playAs = kit.tokenSelect(false, choices.toArray(String[]::new));
            int last = players.indexOf(PreferenceManager.getClientPreferences().getLastPlayerName());
            playAs.select(Math.max(last, 0));
            UiKit.onChange(playAs, this::fillHostColumn);
            playAsField = kit.formField(Messages.getString("StartScreen.playAs"), playAs)
                  .helper(Messages.getString("StartScreen.playAsHelper"));
            // Watching asks for a name, as the briefing does when the host takes no side
            nameField = kit.formField(Messages.getString("StartScreen.observerName"), nameInput)
                  .helper(Messages.getString("StartScreen.loadObserverHelper"));
        }
    }

    /** Whether the Resume page's host watches the save rather than take one of its players. */
    private boolean watching() {
        return playAs != null && playAs.getSelectedIndex() == resume.players().size();
    }

    /** Checks the Resume page as the Host page; asks MegaMek to host the save as the chosen player, or to watch it. */
    private void submitResume() {
        boolean watching = watching();
        String name = playAs != null && !watching ? playAs.getSelected() : nameInput.getText().trim();
        int port = StartHostForm.port(portInput.getText());
        String nameError = null;
        if (playAs == null || watching) {
            if (name.isEmpty()) {
                nameError = Messages.getString("MegaMek.GameConnection.nameError");
            } else if (watching && resume.players().contains(name)) {
                nameError = Messages.getString("StartScreen.observerNameTaken");
            }
            nameField.error(nameError);
        }
        portField.error(portError(port));
        if (nameError != null) {
            stage.setKeyboardFocus(nameInput);
            return;
        }
        if (port < 0) {
            if (!onlineOpen) {
                onlineOpen = true;
                chevron(onlineToggle, true);
                fillHostColumn();
            }
            stage.setKeyboardFocus(portInput);
            return;
        }
        boolean register = registerBox.isChecked();
        List<String> bots = resume.bots().stream().filter(bot -> watching || !bot.equals(name)).toList();
        window.load(new LoadRequest(resume.file(), new HostRequest(name, passwordInput.getText(), port, register,
              register ? metaserverInput.getText().trim() : ""), watching, bots));
    }

    // ---- The scenario browser ----

    /** Shows the scenarios, reading them first the first time. */
    private void showScenarios() {
        openScenario = kit.button("button-quiet", null, Messages.getString("StartScreen.openScenario"), null);
        leftAligned(openScenario);
        UiKit.onChange(openScenario, () -> window.chooseFile(Configuration.scenariosDir(),
              Messages.getString("MegaMek.SelectScenarioDialog.title"), new FileNameExtensionFilter(
                    Messages.getString("StartScreen.scenarioFiles"), SuiteConstants.SCENARIO_EXT.substring(1)),
              file -> {
                  if (page == Page.SCENARIOS) {
                      openBriefing(file, Page.SCENARIOS);
                  }
              }));
        if (catalog != null) {
            buildScenarioPage();
            fillScenarioColumn();
            return;
        }
        setColumn(List.of(overline("StartScreen.scenario"),
              note(Messages.getString("StartScreen.readingScenarios"), "text.muted"), openScenario, backButton()));
        background("scenarios", StartScenarios::read, entries -> {
            catalog = entries;
            buildScenarioPage();
            fillScenarioColumn();
        });
    }

    private void buildScenarioPage() {
        List<String> groups = StartScenarios.groups(catalog);
        groupSelect = null;
        if (groups.size() > 1) {
            List<String> names = new ArrayList<>();
            names.add(Messages.getString("StartScreen.allScenarios"));
            groups.forEach(group -> names.add(groupName(group)));
            groupSelect = kit.tokenSelect(false, names.toArray(String[]::new));
            groupIndex = Math.min(groupIndex, names.size() - 1);
            groupSelect.select(groupIndex);
            UiKit.onChange(groupSelect, () -> {
                groupIndex = groupSelect.getSelectedIndex();
                fillScenarioRows();
                scenarioList.setScrollY(0);
                fillScenarioColumn();
            });
        } else {
            groupIndex = 0;
        }
        UiKit.TokenSearch search = kit.tokenSearch(Messages.getString("StartScreen.searchScenarios"), false);
        searchInput = search.field;
        searchInput.setFocusTraversal(false);
        searchInput.setText(scenarioQuery);
        // Only the rows change while typing, so the field keeps the keyboard
        UiKit.onChange(searchInput, () -> {
            scenarioQuery = searchInput.getText();
            fillScenarioRows();
            scenarioList.setScrollY(0);
        });
        scenarioFilters = new Table();
        scenarioFilters.left().top();
        scenarioFilters.add(search).growX().minWidth(0);
        if (groupSelect != null) {
            scenarioFilters.add(groupSelect).width(ROLE_WIDTH + 30).padLeft(8);
        }
        scenarioRows = new Table();
        scenarioRows.top().left();
        scenarioRows.defaults().growX().left();
        scenarioList = kit.scrollList(scenarioRows, true);
        fillScenarioRows();
    }

    /** The scenarios of the chosen group, or all of them, that answer the search. */
    private void fillScenarioRows() {
        scenarioRows.clearChildren();
        List<String> groups = StartScenarios.groups(catalog);
        String group = groupIndex == 0 ? null : groups.get(groupIndex - 1);
        for (StartScenarios.Entry entry : catalog) {
            if (group != null && !group.equals(entry.group()) || !StartScenarios.matches(entry, scenarioQuery)) {
                continue;
            }
            List<String> about = new ArrayList<>();
            if (group == null && groups.size() > 1) {
                about.add(groupName(entry.group()));
            }
            String planet = entry.scenario().getPlanet();
            if (planet != null && !planet.isBlank()) {
                about.add(planet.trim());
            }
            UiButton row = kit.button("button-quiet", null, entry.name(),
                  about.isEmpty() ? null : String.join("  \u00b7  ", about));
            leftAligned(row);
            UiKit.onChange(row, () -> openBriefing(new File(entry.scenario().getFileName()), Page.SCENARIOS));
            scenarioRows.add(row).row();
        }
        if (!scenarioRows.hasChildren()) {
            scenarioRows.add(note(Messages.getString("StartScreen.noMatches"), "text.muted")).pad(8);
        }
    }

    private void fillScenarioColumn() {
        List<Actor> items = new ArrayList<>();
        items.add(overline("StartScreen.scenario"));
        if (catalog.isEmpty()) {
            items.add(note(Messages.getString("StartScreen.noScenarios"), "text.muted"));
        } else {
            items.add(scenarioFilters);
            items.add(scenarioList);
        }
        items.add(openScenario);
        items.add(backButton());
        if (!column.isEmpty() && column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        setColumn(items);
    }

    /** A group's name as the browser shows it: the scenarios folder's own are the basic ones. */
    static String groupName(String group) {
        if (group.isBlank()) {
            return Messages.getString("StartScreen.basicScenarios");
        }
        return group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
    }

    /** Ctrl+Scenario: the last scenario's briefing, or the browser when there is none. */
    private void lastScenario() {
        String last = PreferenceManager.getClientPreferences().getLastScenario();
        if (last == null || last.isBlank() || !new File(last).isFile()) {
            showPage(Page.SCENARIOS);
        } else {
            openBriefing(new File(last), Page.MAIN);
        }
    }

    // ---- The briefing ----

    /** Opens the briefing for the scenario in {@code file}; Back returns to {@code back}. */
    private void openBriefing(File file, Page back) {
        briefingFile = file;
        briefingBack = back;
        briefing = null;
        showPage(Page.BRIEFING);
    }

    /** Reads the scenario and makes its game off the GPU thread, then shows the briefing. */
    private void showBriefing() {
        File file = briefingFile;
        setColumn(List.of(overline("StartScreen.briefing"),
              note(Messages.getString("StartScreen.preparingBriefing"), "text.muted"), backButton(briefingBack)));
        background("briefing", () -> prepare(file), made -> {
            briefing = made;
            if (made.error() == null) {
                buildBriefing();
            }
            fillBriefingColumn();
            stage.setKeyboardFocus(startButton);
        });
    }

    /** Blocks: reads {@code file}'s scenario and makes its game, or says why it couldn't. */
    private static Briefing prepare(File file) {
        try {
            Scenario scenario = new ScenarioLoader(file).load();
            IGame game = scenario.createGame();
            List<Player> players = game.getPlayersList();
            if (!scenario.isSinglePlayer()) {
                // As the old players dialog did, a multiplayer scenario's sides take the colours in order; a side
                // still in the default blue camouflage wears its colour, so the briefing's swatches tell them apart
                PlayerColour[] colours = PlayerColour.values();
                Camouflage untouched = Camouflage.of(PlayerColour.BLUE);
                for (int i = 0; i < players.size(); i++) {
                    Player player = players.get(i);
                    PlayerColour colour = colours[i % colours.length];
                    player.setColour(colour);
                    if (untouched.equals(player.getCamouflage())) {
                        player.setCamouflage(Camouflage.of(colour));
                    }
                }
            }
            return new Briefing(scenario, game, players.stream().map(GpuStartScreen::swatch).toList(), null);
        } catch (Exception ex) {
            LOGGER.error(ex, "The start screen could not prepare the scenario {}", file);
            return new Briefing(null, null, List.of(), Messages.getString("StartScreen.briefingFailed",
                  file.getName(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /** Blocks: a side's camouflage as the briefing shows it, a small rounded patch of it, or of its colour. */
    private static BufferedImage swatch(Player player) {
        java.awt.Image camo = null;
        try {
            camo = player.getCamouflage().getBaseImage();
        } catch (RuntimeException ex) {
            LOGGER.warn("No camouflage image for {}: {}", player.getName(), ex.getMessage());
        }
        if (camo != null) {
            // Waits for an image the toolkit loads in the background
            camo = new ImageIcon(camo).getImage();
        }
        int width = SWATCH_WIDTH * 2;
        int height = SWATCH_HEIGHT * 2;
        BufferedImage swatch = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = swatch.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setClip(new RoundRectangle2D.Float(0, 0, width, height, 10, 10));
        if (camo != null && camo.getWidth(null) > 0) {
            g.drawImage(camo, 0, 0, width, height, null);
        } else {
            g.setColor(player.getDisplayColour().getColour());
            g.fillRect(0, 0, width, height);
        }
        g.dispose();
        return swatch;
    }

    /**
     * The briefing's parts: its name, where and how it's played, its description, and who plays each side. In a
     * single-player scenario the host takes the first side and bots the rest; in a multiplayer one each side is the
     * host's, another person's or a bot's, and the host names the game's password and port.
     */
    private void buildBriefing() {
        Scenario scenario = briefing.scenario();
        StartScenarios.Entry entry = new StartScenarios.Entry("", scenario);
        briefingHeader = new Table();
        briefingHeader.left().top();
        briefingHeader.defaults().left().growX().minWidth(0);
        Label title = kit.roleTypeLabel("title", entry.name(), "text.primary");
        title.setEllipsis(true);
        briefingHeader.add(title).row();
        List<String> about = new ArrayList<>();
        String planet = scenario.getPlanet();
        if (planet != null && !planet.isBlank()) {
            about.add(planet.trim());
        }
        about.add(Messages.getString(scenario.isSinglePlayer() ? "StartScreen.singlePlayer"
              : "StartScreen.multiplayer"));
        briefingHeader.add(line(String.join("  \u00b7  ", about), "small", "text.muted")).padTop(2);

        String description = scenario.getDescription();
        briefingText = null;
        if (description != null && !description.isBlank()) {
            Label text = kit.typeLabel(description.trim(), "body", "text.secondary");
            text.setWrap(true);
            text.setAlignment(Align.topLeft);
            briefingText = kit.scrollList(text);
        }

        roleSelects.clear();
        disposeSwatches();
        playerRows = new Table();
        playerRows.left().top();
        playerRows.defaults().left().padBottom(4);
        List<Player> players = briefing.game().getPlayersList();
        String[] roles = { Messages.getString("StartScreen.you"), Messages.getString("StartScreen.human"),
                           Messages.getString("StartScreen.bot") };
        for (int i = 0; i < players.size(); i++) {
            playerRows.add(swatchButton(i)).padRight(8);
            playerRows.add(line(players.get(i).getName(), "body", "text.primary")).growX().minWidth(0);
            if (scenario.isSinglePlayer()) {
                playerRows.add(kit.typeLabel(roles[i == 0 ? 0 : 2], "body", "text.muted")).width(ROLE_WIDTH)
                      .padLeft(12);
            } else {
                // Most multiplayer games are played against bots: the host takes the first side, bots the rest
                UiSelect role = kit.tokenSelect(true, roles);
                role.select(i == 0 ? Role.YOU.ordinal() : Role.BOT.ordinal());
                UiKit.onChange(role, () -> roleChosen(role));
                roleSelects.add(role);
                playerRows.add(role).width(ROLE_WIDTH).padLeft(12);
            }
            playerRows.row();
        }

        ruleBaseline.clear();
        if (briefing.game() instanceof Game twGame) {
            twGame.getOptions().getOptionsList().forEach(option -> ruleBaseline.put(option.getName(),
                  option.getValue()));
        }
        rulesValue = kit.typeLabel("", "body", "text.primary");
        rulesLine = settingLine("StartScreen.rules", rulesValue,
              scenario.hasFixedGameOptions() || !(briefing.game() instanceof Game) ? null : this::changeRules);
        conditionsValue = kit.typeLabel("", "body", "text.primary");
        conditionsLine = briefing.game() instanceof PlanetaryConditionsUsing
              ? settingLine("StartScreen.conditions", conditionsValue,
                    scenario.hasFixedPlanetaryConditions() ? null : this::changeConditions)
              : null;
        describeSettings();

        ClientPreferences client = PreferenceManager.getClientPreferences();
        nameInput = field(client.getLastPlayerName());
        nameField = kit.formField(Messages.getString("StartScreen.observerName"), nameInput)
              .helper(Messages.getString("StartScreen.observerHelper"));
        buildServerInputs(client.getLastServerPass(), client.getLastServerPort());
        startButton = kit.button("button-primary", null, Messages.getString("StartScreen.startScenario"), null);
        leftAligned(startButton);
        UiKit.onChange(startButton, this::submitBriefing);
    }

    /** A side's camouflage swatch; a click chooses another camouflage for it. */
    private UiButton swatchButton(int index) {
        Texture texture = texture(briefing.swatches().get(index), false);
        swatchTextures.add(texture);
        Image image = new Image(texture);
        swatches.add(image);
        UiButton button = kit.button("button-quiet-compact", null, null, null);
        button.add(image).size(SWATCH_WIDTH, SWATCH_HEIGHT);
        UiKit.onChange(button, () -> chooseCamo(index));
        return button;
    }

    /** Opens the camouflage chooser for side {@code index}, then shows what was chosen. */
    private void chooseCamo(int index) {
        Player player = briefing.game().getPlayersList().get(index);
        Briefing asked = briefing;
        window.onSwing(frame -> {
            CamoChooserDialog dialog = new CamoChooserDialog(frame, player.getCamouflage());
            try {
                if (!dialog.showDialog().isConfirmed()) {
                    return null;
                }
                player.setCamouflage(dialog.getSelectedItem());
                return swatch(player);
            } finally {
                dialog.dispose();
            }
        }, chosen -> {
            if (chosen == null || briefing != asked || page != Page.BRIEFING) {
                return;
            }
            Texture texture = texture(chosen, false);
            swatchTextures.set(index, texture).dispose();
            swatches.get(index).setDrawable(new TextureRegionDrawable(texture));
        });
    }

    private void disposeSwatches() {
        swatchTextures.forEach(Texture::dispose);
        swatchTextures.clear();
        swatches.clear();
    }

    /**
     * A line of the briefing's settings: its name, what it holds, and a Change button when the scenario leaves it to
     * the player, or that the scenario sets it when it doesn't.
     */
    private Table settingLine(String key, Label value, Runnable change) {
        Table line = new Table();
        line.left();
        line.add(kit.typeLabel(Messages.getString(key), "body", "text.muted")).width(ROLE_WIDTH - 30).left();
        value.setEllipsis(true);
        line.add(value).growX().minWidth(0).left();
        if (change != null) {
            UiButton button = kit.button("button-quiet-compact", null, Messages.getString("StartScreen.change"),
                  null);
            UiKit.onChange(button, change);
            line.add(button).padLeft(8);
        }
        return line;
    }

    /** Fills the Rules and Conditions lines from the briefing's game. */
    private void describeSettings() {
        Scenario scenario = briefing.scenario();
        if (scenario.hasFixedGameOptions() || !(briefing.game() instanceof Game twGame)) {
            rulesValue.setText(Messages.getString("StartScreen.setByScenario"));
        } else {
            long changed = twGame.getOptions().getOptionsList().stream()
                  .filter(option -> !Objects.equals(option.getValue(), ruleBaseline.get(option.getName())))
                  .count();
            rulesValue.setText(changed == 0 ? Messages.getString("StartScreen.rulesDefault")
                  : changed == 1 ? Messages.getString("StartScreen.rulesChangedOne")
                  : Messages.getString("StartScreen.rulesChanged", changed));
        }
        if (conditionsLine != null) {
            conditionsValue.setText(scenario.hasFixedPlanetaryConditions()
                  ? Messages.getString("StartScreen.setByScenario")
                  : conditions(((PlanetaryConditionsUsing) briefing.game()).getPlanetaryConditions()));
        }
    }

    /** The conditions in a few words: the light, and whatever else isn't the usual. */
    static String conditions(PlanetaryConditions conditions) {
        List<String> parts = new ArrayList<>();
        parts.add(conditions.getLight().toString());
        if (!conditions.getWeather().isClear()) {
            parts.add(conditions.getWeather().toString());
        }
        if (!conditions.getWind().isCalm()) {
            parts.add(conditions.getWind().toString());
        }
        if (!conditions.getFog().isFogNone()) {
            parts.add(conditions.getFog().toString());
        }
        if (conditions.getTemperature() != 25) {
            parts.add(Messages.getString("StartScreen.temperature", conditions.getTemperature()));
        }
        if (conditions.getGravity() != 1) {
            parts.add(Messages.getString("StartScreen.gravity", conditions.getGravity()));
        }
        return String.join(", ", parts);
    }

    /** Opens the game options the scenario leaves open; confirming them sets them on the briefing's game. */
    private void changeRules() {
        Briefing asked = briefing;
        Game game = (Game) briefing.game();
        Set<String> locked = briefing.scenario().lockedGameOptions();
        window.onSwing(frame -> {
            GameOptionsDialog dialog = new GameOptionsDialog(frame, game.getOptions(), false);
            try {
                dialog.update(game.getOptions());
                dialog.setEditable(true);
                // The options the scenario calls its mission stay as written; the rest are the player's
                dialog.lockOptions(locked);
                return dialog.showDialog().isConfirmed() ? List.copyOf(dialog.getOptions()) : null;
            } finally {
                dialog.dispose();
            }
        }, chosen -> {
            if (chosen == null || briefing != asked) {
                return;
            }
            for (IBasicOption option : chosen) {
                IOption rule = game.getOptions().getOption(option.getName());
                if (rule != null) {
                    rule.setValue(option.getValue());
                }
            }
            describeSettings();
        });
    }

    /** Opens the planetary conditions; confirming them sets them on the briefing's game. */
    private void changeConditions() {
        Briefing asked = briefing;
        PlanetaryConditionsUsing game = (PlanetaryConditionsUsing) briefing.game();
        PlanetaryConditions current = game.getPlanetaryConditions();
        window.onSwing(frame -> {
            PlanetaryConditionsDialog dialog = new PlanetaryConditionsDialog(frame, current);
            try {
                return dialog.showDialog() ? dialog.getConditions() : null;
            } finally {
                dialog.dispose();
            }
        }, chosen -> {
            if (chosen == null || briefing != asked) {
                return;
            }
            game.setPlanetaryConditions(chosen);
            describeSettings();
        });
    }

    /** A side became {@code chosen}'s choice; the host takes one side at most, so You moves rather than copies. */
    private void roleChosen(UiSelect chosen) {
        if (chosen.getSelectedIndex() == Role.YOU.ordinal()) {
            for (UiSelect other : roleSelects) {
                if (other != chosen && other.getSelectedIndex() == Role.YOU.ordinal()) {
                    other.select(Role.HUMAN.ordinal());
                }
            }
        }
        fillBriefingColumn();
    }

    private void fillBriefingColumn() {
        List<Actor> items = new ArrayList<>();
        items.add(overline("StartScreen.briefing"));
        if (briefing.error() != null) {
            items.add(note(briefing.error(), "status.danger"));
        } else {
            items.add(briefingHeader);
            if (briefingText != null) {
                items.add(briefingText);
            }
            items.add(overline("StartScreen.players"));
            items.add(playerRows);
            if (!briefing.scenario().isSinglePlayer()) {
                List<Role> roles = roles();
                if (!roles.contains(Role.YOU)) {
                    items.add(nameField);
                }
                // Only another person needs the password and port to join
                if (roles.contains(Role.HUMAN)) {
                    items.add(passwordRow);
                }
            }
            items.add(rulesLine);
            if (conditionsLine != null) {
                items.add(conditionsLine);
            }
            items.add(startButton);
        }
        items.add(backButton(briefingBack));
        if (!column.isEmpty() && column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        setColumn(items);
    }

    /** Who plays each of the scenario's sides, as the briefing shows it. */
    private List<Role> roles() {
        int count = briefing.game().getPlayersList().size();
        List<Role> roles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (briefing.scenario().isSinglePlayer()) {
                roles.add(i == 0 ? Role.YOU : Role.BOT);
            } else {
                roles.add(Role.values()[roleSelects.get(i).getSelectedIndex()]);
            }
        }
        return roles;
    }

    /** Checks the briefing; asks MegaMek to host the scenario with its sides as chosen. */
    private void submitBriefing() {
        if (startButton.isDisabled()) {
            return;
        }
        List<Role> roles = roles();
        boolean single = briefing.scenario().isSinglePlayer();
        // Without another person to join, the game takes the default port and no password, as single player does
        boolean online = !single && roles.contains(Role.HUMAN);
        int you = roles.indexOf(Role.YOU);
        String name = you >= 0 ? briefing.game().getPlayersList().get(you).getName() : nameInput.getText().trim();
        int port = online ? StartHostForm.port(portInput.getText()) : MMConstants.DEFAULT_PORT;
        if (you < 0) {
            nameField.error(name.isEmpty() ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        }
        if (online) {
            portField.error(portError(port));
        }
        if (name.isEmpty()) {
            stage.setKeyboardFocus(nameInput);
            return;
        }
        if (port < 0) {
            stage.setKeyboardFocus(portInput);
            return;
        }
        startButton.setDisabled(true);
        startButton.setText(kit.cased("button", Messages.getString("StartScreen.starting")));
        window.scenario(new ScenarioRequest(briefing.scenario(), briefing.game(), roles, name,
              online ? passwordInput.getText() : "", port));
    }

    /**
     * The scenario didn't start; MegaMek has said why. Start works again, once the briefing has made its game again when
     * {@code gameUsed}, keeping the sides' camouflage, the rules and the conditions the player chose.
     */
    void startFailed(boolean gameUsed) {
        if (page != Page.BRIEFING || briefing == null || briefing.error() != null) {
            return;
        }
        if (!gameUsed) {
            enableStart();
            return;
        }
        Briefing used = briefing;
        background("briefing", () -> prepare(briefingFile), made -> {
            if (made.error() != null) {
                briefing = made;
                fillBriefingColumn();
                return;
            }
            List<Player> before = used.game().getPlayersList();
            List<Player> after = made.game().getPlayersList();
            for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
                after.get(i).setCamouflage(before.get(i).getCamouflage());
            }
            if (used.game() instanceof Game oldGame && made.game() instanceof Game newGame) {
                for (IOption option : oldGame.getOptions().getOptionsList()) {
                    IOption rule = newGame.getOptions().getOption(option.getName());
                    if (rule != null) {
                        rule.setValue(option.getValue());
                    }
                }
            }
            if (used.game() instanceof PlanetaryConditionsUsing oldConditions
                  && made.game() instanceof PlanetaryConditionsUsing newConditions) {
                newConditions.setPlanetaryConditions(
                      (PlanetaryConditions) oldConditions.getPlanetaryConditions().clone());
            }
            briefing = new Briefing(made.scenario(), made.game(), used.swatches(), null);
            enableStart();
        });
    }

    private void enableStart() {
        startButton.setDisabled(false);
        startButton.setText(kit.cased("button", Messages.getString("StartScreen.startScenario")));
    }

    private Table bottomRow() {
        Table row = new Table();
        row.left();
        row.defaults().padRight(6);
        UiButton settings = kit.button("button-quiet-compact", "settings",
              Messages.getString("StartScreen.settings"), null);
        UiKit.onChange(settings, () -> window.dispatch(Action.SETTINGS));
        UiButton help = kit.button("button-quiet-compact", "help", Messages.getString("StartScreen.help"), null);
        UiKit.onChange(help, () -> showPage(page == Page.HELP ? Page.MAIN : Page.HELP));
        UiButton quit = kit.button("button-quiet-compact", "close", Messages.getString("StartScreen.quit"), null);
        UiKit.onChange(quit, () -> window.dispatch(Action.QUIT));
        row.add(settings);
        row.add(help);
        row.add(quit);
        row.pack();
        return row;
    }

    /** The version, and this build's note where it has one. */
    private Table corner() {
        Table table = new Table();
        table.left().top();
        table.defaults().left();
        table.add(kit.typeLabel(Messages.getString("MegaMek.Version", MMConstants.VERSION), "small",
              "text.muted")).row();
        if (Version.hasBuildNotes()) {
            Label notes = kit.typeLabel(Version.getBuildNotes(), "small", "text.primary");
            Label.LabelStyle style = new Label.LabelStyle(notes.getStyle());
            java.awt.Color color = SplashArt.buildNotesColor();
            style.fontColor = new com.badlogic.gdx.graphics.Color(color.getRed() / 255f, color.getGreen() / 255f,
                  color.getBlue() / 255f, 1);
            notes.setStyle(style);
            notes.setWrap(true);
            notes.setAlignment(Align.left);
            table.add(notes).width(NOTES_WIDTH).padTop(4).row();
        }
        table.pack();
        return table;
    }

    private Table tipCard() {
        // The screen's corners frame it already, and only the outermost frame has ticks
        Table card = kit.panel(true);
        card.setBackground(boardSkin.skin.getDrawable(UiTheme.TOKEN_PANEL_PLAIN));
        card.defaults().left();
        card.add(kit.roleTypeLabel("overline", Messages.getString("StartScreen.tip"), "text.muted"))
              .growX().row();
        tipText = kit.typeLabel("", "body", "text.primary");
        tipText.setWrap(true);
        card.add(tipText).width(TIP_WIDTH).padTop(6).row();
        tipAuthor = kit.typeLabel("", "small", "text.muted");
        card.add(tipAuthor).right().padTop(4).row();
        card.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                showTip(tipIndex + 1);
            }
        });
        tipIndex = tips.isEmpty() ? 0 : random.nextInt(tips.size());
        showTip(tipIndex);
        card.setVisible(!tips.isEmpty());
        card.pack();
        return card;
    }

    private void showTip(int index) {
        if (tips.isEmpty()) {
            return;
        }
        tipIndex = Math.floorMod(index, tips.size());
        tipShownAt = start == 0 ? 0 : clock();
        String[] lines = tips.get(tipIndex).split("\n");
        int last = lines.length - 1;
        boolean attributed = last > 0 && lines[last].startsWith("-");
        tipText.setText(String.join("\n", List.of(lines).subList(0, attributed ? last : lines.length)));
        tipAuthor.setText(attributed ? lines[last] : "");
        if (tipCard != null) {
            tipCard.pack();
        }
    }

    private void keys() {
        stage.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (introTime() < StartTimeline.INTRO_END) {
                    skip();
                    event.stop();
                    return true;
                }
                return false;
            }

            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (introTime() < StartTimeline.INTRO_END) {
                    skip();
                    return true;
                }
                // An open list, and a select's own opening keys, are the select's; the event reaches here after
                Actor target = event.getTarget();
                if (target instanceof UiListPopup || target instanceof UiSelect && (keycode == Keys.DOWN
                      || keycode == Keys.ENTER || keycode == Keys.NUMPAD_ENTER || keycode == Keys.SPACE)) {
                    return false;
                }
                boolean shift = Gdx.input.isKeyPressed(Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Keys.SHIFT_RIGHT);
                switch (keycode) {
                    case Keys.DOWN -> move(1);
                    case Keys.UP -> move(-1);
                    case Keys.TAB -> move(shift ? -1 : 1);
                    case Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE -> {
                        Actor focus = stage.getKeyboardFocus();
                        if (focus instanceof UiButton button) {
                            button.fire(new ChangeListener.ChangeEvent());
                        } else if (focus instanceof TextField && keycode != Keys.SPACE && submitsOnEnter()) {
                            submit();
                        } else {
                            return false;
                        }
                    }
                    case Keys.ESCAPE -> {
                        if (page != Page.MAIN) {
                            showPage(parent(page));
                        }
                    }
                    default -> {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    /** Whether Enter in a text field sends the page's form. */
    private boolean submitsOnEnter() {
        return page == Page.HOST || page == Page.RESUME || page == Page.CONNECT
              || page == Page.BRIEFING && briefing != null && briefing.error() == null;
    }

    private void submit() {
        switch (page) {
            case HOST -> submitHost();
            case RESUME -> submitResume();
            case CONNECT -> submitConnect();
            case BRIEFING -> submitBriefing();
            default -> {
            }
        }
    }

    private void skip() {
        skipped = true;
        pageShownAt = -1;
    }

    private void move(int step) {
        int index = focusable.indexOf(stage.getKeyboardFocus());
        int next = index < 0 ? (step > 0 ? 0 : focusable.size() - 1) : Math.floorMod(index + step, focusable.size());
        Actor focus = focusable.get(next);
        stage.setKeyboardFocus(focus);
        // A list's row the keyboard reaches scrolls into view
        for (Group parent = focus.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getActor() != null) {
                Vector2 at = focus.localToAscendantCoordinates(scroll.getActor(), new Vector2());
                scroll.scrollTo(at.x, at.y, focus.getWidth(), focus.getHeight());
                break;
            }
        }
    }

    /** How far column item {@code index} has slid in, from 0 to 1. */
    private double slide(int index) {
        if (!motion) {
            return 1;
        }
        if (pageShownAt < 0) {
            return StartTimeline.itemProgress(introTime(), index);
        }
        return StartTimeline.ease((clock() - pageShownAt - StartTimeline.ITEM_STAGGER * index)
              / StartTimeline.ITEM_SLIDE);
    }

    /**
     * The menu sits in a column at the right, clear of the art's main subject on the left, and slides in from the edge.
     * A page's list or description takes the height the other items leave it above the bottom row, and scrolls.
     */
    private void layoutMenu() {
        float margin = Math.max(40, w * 0.05f);
        float menuWidth = Math.max(260, w * 0.2f);
        float columnWidth = wide(page) ? Math.max(WIDE_COLUMN, w * 0.28f) : menuWidth;
        float left = w - margin - columnWidth;
        float top = Math.max(32, h * 0.07f);
        double t = introTime();
        double logoIn = motion ? StartTimeline.ease((t - StartTimeline.MENU_START) / 0.5) : 1;
        float y = top;
        if (logo != null) {
            float logoWidth = menuWidth * 0.8f;
            float logoHeight = logoWidth * logoTexture.getHeight() / logoTexture.getWidth();
            logo.setBounds(w - margin - logoWidth + (float) ((1 - logoIn) * 60), h - top - logoHeight, logoWidth,
                  logoHeight);
            logo.setColor(1, 1, 1, (float) logoIn);
            logo.setVisible(logoIn > 0);
            y += logoHeight + 28;
        }
        float[] heights = new float[column.size()];
        float taken = 0;
        int filler = -1;
        for (int i = 0; i < column.size(); i++) {
            Actor item = column.get(i);
            // A wrapped label's height follows its width
            item.setWidth(columnWidth);
            if (item instanceof ScrollPane scroll && filler < 0) {
                filler = i;
                fitScrolled(scroll, columnWidth);
            }
            heights[i] = item instanceof Layout layout ? layout.getPrefHeight() : item.getHeight();
            taken += gapAbove(item) + gapBelow(item, i) + (i == filler ? 0 : heights[i]);
        }
        if (filler >= 0) {
            float room = h - y - taken - (32 + bottomRow.getHeight() + 16);
            heights[filler] = Math.min(heights[filler], Math.max(room, MIN_FILLER));
        }
        float offscreen = columnWidth + margin + 40;
        for (int i = 0; i < column.size(); i++) {
            Actor item = column.get(i);
            float itemHeight = heights[i];
            double p = slide(i);
            float dx = (float) ((1 - p) * offscreen);
            y += gapAbove(item);
            item.setBounds(left + dx, h - y - itemHeight, columnWidth, itemHeight);
            y += itemHeight + gapBelow(item, i);
            item.setVisible(p > 0);
        }
        // Like the logo, the bottom row only slides in with the greeting and stays put between pages
        double rowIn = pageShownAt < 0 ? slide(column.size()) : 1;
        bottomRow.setPosition(w - margin - bottomRow.getWidth() + (float) ((1 - rowIn) * offscreen), 32);
        bottomRow.setVisible(rowIn > 0);
        boolean settled = t >= StartTimeline.MENU_START;
        corner.setPosition(32, h - corner.getHeight() - 28);
        corner.setVisible(settled);
        tipCard.setPosition(32, 32);
        tipCard.setVisible(settled && !tips.isEmpty());
        if (settled && clock() - tipShownAt > TIP_SECONDS) {
            showTip(tipIndex + 1);
        }
    }

    /** Labels in the column are its section headings and notes, with room above them. */
    private static float gapAbove(Actor item) {
        return item instanceof Label ? 14 : 0;
    }

    private float gapBelow(Actor item, int index) {
        if (item instanceof Label) {
            return 6;
        }
        return index == 0 && page == Page.MAIN ? 14 : 8;
    }

    /** Gives a scrolled wrapped text its width, so the pane's preferred height is the text's at that width. */
    private static void fitScrolled(ScrollPane scroll, float width) {
        Actor inner = scroll.getActor();
        if (!(inner instanceof Label label) || !label.getWrap()) {
            return;
        }
        Drawable frame = scroll.getStyle().background;
        float innerWidth = width - (frame == null ? 0 : frame.getLeftWidth() + frame.getRightWidth());
        if (label.getWidth() != innerWidth) {
            label.setWidth(innerWidth);
            scroll.invalidate();
        }
    }

    // ---- Size and scale ----

    @Override
    public void resize(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        scale = DisplayScale.read(preferences.getGUIScale(), new GpuDisplayScale().contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        bw = Gdx.graphics.getBackBufferWidth();
        bh = Gdx.graphics.getBackBufferHeight();
        w = bw / scale;
        h = bh / scale;
        pixels.setToOrtho2D(0, 0, bw, bh);
        layoutHexes();
        rebuildBackdrop();
        rebuildGreeting();
    }

    /** GPU thread: after the GUI scale changes. */
    void rescale() {
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private void layoutHexes() {
        hexR = Math.max(28, h / 16.0);
        hexes = new ArrayList<>();
        Random scatter = new Random(11);
        double rowHeight = Math.sqrt(3) * hexR;
        int col = 0;
        for (double cx = 0; cx < w + hexR * 2; cx += hexR * 1.5, col++) {
            double offset = (col % 2) * rowHeight / 2;
            for (double cy = -rowHeight / 2 + offset; cy < h + rowHeight; cy += rowHeight) {
                double at = (cx / (w + hexR)) * 0.78 + scatter.nextDouble() * 0.22;
                hexes.add(new Hex(cx, cy, at));
            }
        }
    }

    /** The art, its shading, the faint hex grid, scanlines and corner ticks, drawn once at the window's size. */
    private void rebuildBackdrop() {
        BufferedImage image = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.scale(scale, scale);
        g.setColor(base);
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
        if (art != null) {
            double cover = Math.max(w / art.getWidth(), h / art.getHeight()) * 1.04;
            double drawnWidth = art.getWidth() * cover;
            double drawnHeight = art.getHeight() * cover;
            g.drawImage(art, (int) ((w - drawnWidth) / 2), (int) ((h - drawnHeight) / 2), (int) drawnWidth,
                  (int) drawnHeight, null);
        }
        g.setPaint(new LinearGradientPaint(w, 0, w * 0.45f, 0, new float[] { 0f, 0.45f, 1f },
              new java.awt.Color[] { alpha(base, 0.94f), alpha(base, 0.72f), alpha(base, 0f) }));
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(w * 0.4, h * 0.45), Math.max(w, h) * 0.75f,
              new float[] { 0.55f, 1f }, new java.awt.Color[] { alpha(base, 0f), alpha(base, 0.75f) }));
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
        g.setStroke(new BasicStroke(1f));
        g.setColor(alpha(text, 0.045f));
        for (Hex hex : hexes) {
            g.draw(hexPath(hex.x, hex.y, hexR));
        }
        g.setColor(alpha(java.awt.Color.BLACK, 0.10f));
        for (int y = 0; y < h; y += 3) {
            g.fillRect(0, y, (int) w + 1, 1);
        }
        drawCornerTicks(g);
        g.dispose();
        if (backdrop != null) {
            backdrop.dispose();
        }
        backdrop = texture(image, false);
    }

    private void drawCornerTicks(Graphics2D g) {
        int inset = 16;
        int length = 26;
        g.setColor(alpha(strong, 0.7f));
        g.setStroke(new BasicStroke(2));
        int x0 = inset;
        int y0 = inset;
        int x1 = (int) w - inset;
        int y1 = (int) h - inset;
        g.drawLine(x0, y0, x0 + length, y0);
        g.drawLine(x0, y0, x0, y0 + length);
        g.drawLine(x1, y0, x1 - length, y0);
        g.drawLine(x1, y0, x1, y0 + length);
        g.drawLine(x0, y1, x0 + length, y1);
        g.drawLine(x0, y1, x0, y1 - length);
        g.drawLine(x1, y1, x1 - length, y1);
        g.drawLine(x1, y1, x1, y1 - length);
    }

    private void rebuildGreeting() {
        Font overline = TokenFonts.font("overline", true, true)
              .deriveFont(TokenFonts.font("overline").getSize2D() * 1.4f * scale);
        Font display = TokenFonts.font("display", true, true).deriveFont((float) Math.max(34, h * 0.055) * scale);
        String name = StartTimeline.greetingName(PreferenceManager.getClientPreferences().getLastPlayerName());
        String first = Messages.getString("StartScreen.link", MMConstants.VERSION);
        String second = name == null ? Messages.getString("StartScreen.welcome")
              : Messages.getString("StartScreen.welcomeBack", name);
        if (line1 != null) {
            line1.dispose();
            line2.dispose();
        }
        line1 = textLine(first.toUpperCase(Locale.ROOT), overline, mint);
        line2 = textLine(second.toUpperCase(Locale.ROOT), display, text);
    }

    private static TextLine textLine(String line, Font font, java.awt.Color color) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = probe.createGraphics();
        textHints(probeGraphics);
        FontMetrics metrics = probeGraphics.getFontMetrics(font);
        int[] widths = new int[line.length() + 1];
        for (int i = 0; i <= line.length(); i++) {
            widths[i] = (int) Math.ceil(metrics.getStringBounds(line.substring(0, i), probeGraphics).getWidth());
        }
        int ascent = metrics.getAscent();
        int height = metrics.getAscent() + metrics.getDescent();
        int charWidth = metrics.charWidth('M');
        probeGraphics.dispose();
        BufferedImage image = new BufferedImage(widths[line.length()] + 4, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        textHints(g);
        g.setFont(font);
        g.setColor(color);
        g.drawString(line, 0, ascent);
        g.dispose();
        return new TextLine(texture(image, false), widths, ascent, charWidth);
    }

    private static void textHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static Texture texture(BufferedImage image, boolean mipMaps) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        ByteBuffer buffer = pixmap.getPixels();
        for (int value : argb) {
            buffer.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) (value >>> 24));
        }
        buffer.flip();
        Texture texture = new Texture(pixmap, mipMaps);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    private static Path2D hexPath(double cx, double cy, double r) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 3 * i;
            if (i == 0) {
                path.moveTo(cx + r * Math.cos(angle), cy + r * Math.sin(angle));
            } else {
                path.lineTo(cx + r * Math.cos(angle), cy + r * Math.sin(angle));
            }
        }
        path.closePath();
        return path;
    }

    private static java.awt.Color alpha(java.awt.Color color, float alpha) {
        return new java.awt.Color(color.getRed(), color.getGreen(), color.getBlue(),
              Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    // ---- Each frame ----

    @Override
    public void render() {
        double t = introTime();
        float dt = Math.min(0.1f, Gdx.graphics.getDeltaTime());
        Gdx.gl.glClearColor(base.getRed() / 255f, base.getGreen() / 255f, base.getBlue() / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);
        if (motion) {
            stepParticles(dt);
        }
        drawScene(t);
        layoutMenu();
        stage.act(dt);
        hud.update(Set.of());
        stage.draw();
        window.frame();
        if (probe != null && probe.shot() != null && !shotSaved && clock() >= probe.shotAt()) {
            saveShot();
        }
        if (!settledRate && t >= StartTimeline.INTRO_END) {
            // The settled screen has only slow embers: 30 frames a second is plenty
            settledRate = true;
            Gdx.graphics.setForegroundFPS(30);
            window.settled();
        }
    }

    private void drawScene(double t) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setProjectionMatrix(pixels);
        shapes.setProjectionMatrix(pixels);
        drawGreeting(t);
        drawHexReveal(t);
    }

    private void drawArt() {
        batch.begin();
        batch.setColor(1, 1, 1, 1);
        batch.draw(backdrop, 0, 0, bw, bh);
        batch.end();
        drawEmbers();
    }

    private void drawHexReveal(double t) {
        double rs = StartTimeline.REVEAL_START;
        double re = StartTimeline.REVEAL_END;
        if (t >= rs) {
            boolean masked = t < re;
            if (masked) {
                double rp = StartTimeline.revealProgress(t);
                Gdx.gl.glEnable(GL20.GL_STENCIL_TEST);
                Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
                Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
                Gdx.gl.glColorMask(false, false, false, false);
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                for (Hex hex : hexes) {
                    double pop = StartTimeline.clamp((rp - hex.at) / StartTimeline.HEX_SPAN);
                    if (pop > 0) {
                        fillHex(hex.x, hex.y, hexR * (0.35 + 0.67 * StartTimeline.ease(pop)));
                    }
                }
                shapes.end();
                Gdx.gl.glColorMask(true, true, true, true);
                Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);
                Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
            }
            drawArt();
            if (masked) {
                Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);
            }
        }
        if (t >= rs - 0.3 && t < re + 0.5) {
            double rpRaw = (t - rs) / (re - rs);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Hex hex : hexes) {
                double since = rpRaw - hex.at;
                float a = since < 0 ? (float) (0.22 * StartTimeline.clamp(1 + since / 0.12))
                      : (float) (0.85 * (1 - StartTimeline.clamp((since - StartTimeline.HEX_SPAN) / 0.25)));
                if (a > 0.01f) {
                    shapes.setColor(rgba(mint, a));
                    outlineHex(hex.x, hex.y, hexR - 1, 1.5f * scale);
                }
            }
            shapes.end();
        }
    }

    /** The two typed lines, a blinking cursor and a rule under them. */
    private void drawGreeting(double t) {
        if (skipped || t > StartTimeline.REVEAL_END) {
            return;
        }
        double ts = StartTimeline.TEXT_START;
        int n1 = StartTimeline.lineOneTyped(t, line1.widths().length - 1);
        int n2 = StartTimeline.lineTwoTyped(t, line2.widths().length - 1);
        int lineWidth = line2.width();
        int x = (bw - lineWidth) / 2;
        int baseline2 = (int) (bh / 2.0 + line2.ascent() / 2.0);
        int baseline1 = baseline2 - line2.ascent() - (int) (10 * scale);
        batch.begin();
        batch.setColor(1, 1, 1, 1);
        if (n1 > 0) {
            float y = bh - (baseline1 - line1.ascent()) - line1.texture().getHeight();
            batch.draw(new TextureRegion(line1.texture(), 0, 0, line1.widths()[n1], line1.texture().getHeight()), x, y,
                  line1.widths()[n1], line1.texture().getHeight());
        }
        if (n2 > 0) {
            float y = bh - (baseline2 - line2.ascent()) - line2.texture().getHeight();
            batch.draw(new TextureRegion(line2.texture(), 0, 0, line2.widths()[n2], line2.texture().getHeight()), x, y,
                  line2.widths()[n2], line2.texture().getHeight());
        }
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (t < StartTimeline.REVEAL_START) {
            boolean typing = n2 < line2.widths().length - 1;
            if ((typing || ((int) (t * 3)) % 2 == 0) && t > ts + 0.5) {
                float cursorX = x + line2.widths()[n2] + 6 * scale;
                float cursorWidth = Math.max(10 * scale, line2.charWidth() / 2f);
                float cursorHeight = line2.ascent() - 6 * scale;
                shapes.setColor(rgba(mint, 1));
                shapes.rect(cursorX, bh - baseline2, cursorWidth, cursorHeight);
            }
        }
        double rule = StartTimeline.ease((t - ts - 0.4) / 1.2);
        shapes.setColor(rgba(muted, 0.6f));
        shapes.rect(x, bh - baseline2 - 18 * scale - scale, (float) (lineWidth * rule), scale);
        shapes.end();
    }

    private void drawEmbers() {
        if (!motion) {
            return;
        }
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (float[] p : particles) {
            float life = p[4] / p[5];
            float a = Math.min(1, Math.min(life * 3, (1 - life) * 4)) * 0.8f;
            shapes.setColor(1f, (150 + life * 80) / 255f, 60 / 255f, Math.max(0, a));
            float size = p[6] * scale;
            shapes.circle(p[0] * bw + size / 2, bh - (p[1] * bh + size / 2), size / 2, 8);
        }
        shapes.end();
    }

    private void stepParticles(float dt) {
        for (int i = 0; i < particles.size(); i++) {
            float[] p = particles.get(i);
            p[0] += p[2] * dt;
            p[1] += p[3] * dt;
            p[4] -= dt;
            if (p[4] <= 0 || p[1] < -0.05) {
                particles.set(i, newParticle(false));
            }
        }
    }

    /** An ember: x, y (fractions of the window), their speeds, life left, its whole life and its size. */
    private float[] newParticle(boolean anywhere) {
        float life = 3 + random.nextFloat() * 5;
        return new float[] {
              0.2f + random.nextFloat() * 0.8f, anywhere ? random.nextFloat() : 1.02f,
              0.005f + random.nextFloat() * 0.02f, -(0.02f + random.nextFloat() * 0.05f),
              anywhere ? random.nextFloat() * life : life, life, 1.2f + random.nextFloat() * 2.2f };
    }

    private void fillHex(double cx, double cy, double r) {
        float x = (float) (cx * scale);
        float y = (float) (bh - cy * scale);
        float rr = (float) (r * scale);
        for (int i = 0; i < 6; i++) {
            double a0 = Math.PI / 3 * i;
            double a1 = Math.PI / 3 * (i + 1);
            shapes.triangle(x, y, x + rr * (float) Math.cos(a0), y - rr * (float) Math.sin(a0),
                  x + rr * (float) Math.cos(a1), y - rr * (float) Math.sin(a1));
        }
    }

    private void outlineHex(double cx, double cy, double r, float width) {
        float x = (float) (cx * scale);
        float y = (float) (bh - cy * scale);
        float rr = (float) (r * scale);
        for (int i = 0; i < 6; i++) {
            double a0 = Math.PI / 3 * i;
            double a1 = Math.PI / 3 * (i + 1);
            shapes.rectLine(x + rr * (float) Math.cos(a0), y - rr * (float) Math.sin(a0),
                  x + rr * (float) Math.cos(a1), y - rr * (float) Math.sin(a1), width);
        }
    }

    private static com.badlogic.gdx.graphics.Color rgba(java.awt.Color color, float alpha) {
        return new com.badlogic.gdx.graphics.Color(color.getRed() / 255f, color.getGreen() / 255f,
              color.getBlue() / 255f, alpha);
    }

    @Override
    public void dispose() {
        disposed = true;
        generation++;
        window.disposing();
        for (Object disposable : new Object[] { stage, batch, shapes, backdrop, logoTexture }) {
            if (disposable instanceof com.badlogic.gdx.utils.Disposable resource) {
                resource.dispose();
            }
        }
        if (line1 != null) {
            line1.dispose();
            line2.dispose();
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.dispose();
        }
        disposeSwatches();
    }
}
