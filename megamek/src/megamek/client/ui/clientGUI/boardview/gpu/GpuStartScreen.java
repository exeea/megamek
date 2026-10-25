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
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;

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
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.MMConstants;
import megamek.Version;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.SplashArt;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Action;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.design.swing.TokenFonts;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.common.Configuration;
import megamek.common.preference.PreferenceManager;
import megamek.common.util.TipOfTheDay;
import megamek.logging.MMLogger;

/**
 * The start screen: a greeting typed over a dark field, the art revealed over it (hexes popping open, or a tuning
 * band), then the main menu sliding in at the left with the version at the top right and a tip at the bottom right.
 * The art and its overlays are drawn once with Java2D and uploaded; the GPU only animates on top of them. Once the
 * intro has played, only slow embers move, at 30 frames a second.
 */
final class GpuStartScreen extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuStartScreen.class);
    private static final String TIPS = "megamek.client.TipOfTheDay";
    private static final double TIP_SECONDS = 10;
    private static final int EMBERS = 140;
    private static final float TIP_WIDTH = 360;
    private static final float NOTES_WIDTH = 320;

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

    private enum Page { MAIN, HELP }

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
    private final List<UiButton> focusable = new ArrayList<>();
    private final List<String> tips;
    private final boolean motion;

    private BufferedImage art;
    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
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

    GpuStartScreen(GpuStartWindow window, boolean greet, GUIPreferences preferences) {
        this.window = window;
        this.preferences = preferences;
        motion = !preferences.getReduceMotion();
        skipped = !greet || !motion;
        tips = TipOfTheDay.plainTips(TIPS);
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

    @Override
    public void create() {
        window.started();
        boardSkin = new GpuBoardSkin();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);
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
        column.forEach(Actor::remove);
        column.clear();
        focusable.clear();
        if (shown == Page.MAIN) {
            item("button-primary", "StartScreen.newGame", Action.HOST, null);
            item("button-secondary", "StartScreen.loadGame", Action.LOAD, Action.QUICK_LOAD);
            item("button-secondary", "StartScreen.scenario", Action.SCENARIO, Action.LAST_SCENARIO);
            item("button-secondary", "StartScreen.connect", Action.CONNECT, Action.CONNECT_BOT);
            overline("StartScreen.tools");
            item("button-quiet", "StartScreen.mapEditor", Action.MAP_EDITOR, null);
            item("button-quiet", "StartScreen.openMap", Action.OPEN_MAP, null);
            item("button-quiet", "StartScreen.unitBrowser", Action.UNIT_BROWSER, null);
            item("button-quiet", "StartScreen.randomArmy", Action.RANDOM_ARMY, null);
            item("button-quiet", "StartScreen.skinEditor", Action.SKIN_EDITOR, null);
        } else {
            overline("StartScreen.help");
            item("button-quiet", "StartScreen.helpContents", Action.HELP_CONTENTS, null);
            item("button-quiet", "StartScreen.helpSkinning", Action.HELP_SKINNING, null);
            item("button-quiet", "StartScreen.reportBug", Action.REPORT_BUG, null);
            item("button-quiet", "StartScreen.copySystemData", Action.COPY_SYSTEM_DATA, null);
            item("button-quiet", "StartScreen.resetPrompts", Action.RESET_PROMPTS, null);
            item("button-quiet", "StartScreen.about", Action.ABOUT, null);
            UiButton back = kit.button("button-secondary", "chevron-left", Messages.getString("StartScreen.back"), null);
            leftAligned(back);
            UiKit.onChange(back, () -> showPage(Page.MAIN));
            add(back);
        }
        for (Actor actor : bottomRow.getChildren()) {
            if (actor instanceof UiButton button) {
                focusable.add(button);
            }
        }
        if (start != 0) {
            pageShownAt = clock();
        }
        stage.setKeyboardFocus(null);
    }

    /** A menu button; with Ctrl held, it asks for {@code withControl} instead, where there is one. */
    private void item(String style, String key, Action action, Action withControl) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> window.dispatch(withControl != null && controlHeld() ? withControl : action));
        add(button);
    }

    private static boolean controlHeld() {
        return Gdx.input.isKeyPressed(Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Keys.CONTROL_RIGHT);
    }

    private static void leftAligned(UiButton button) {
        button.left();
        button.getLabelCell().left().expandX();
    }

    private void overline(String key) {
        Label label = kit.roleTypeLabel("overline", Messages.getString(key), "text.muted");
        column.add(label);
        stage.addActor(label);
    }

    private void add(UiButton button) {
        column.add(button);
        focusable.add(button);
        stage.addActor(button);
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
        Table card = kit.panel(true);
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
                boolean shift = Gdx.input.isKeyPressed(Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Keys.SHIFT_RIGHT);
                switch (keycode) {
                    case Keys.DOWN -> move(1);
                    case Keys.UP -> move(-1);
                    case Keys.TAB -> move(shift ? -1 : 1);
                    case Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE -> {
                        if (stage.getKeyboardFocus() instanceof UiButton button) {
                            button.fire(new ChangeListener.ChangeEvent());
                        }
                    }
                    case Keys.ESCAPE -> {
                        if (page == Page.HELP) {
                            showPage(Page.MAIN);
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

    private void skip() {
        skipped = true;
        pageShownAt = -1;
    }

    private void move(int step) {
        int index = focusable.indexOf(stage.getKeyboardFocus());
        int next = index < 0 ? (step > 0 ? 0 : focusable.size() - 1) : Math.floorMod(index + step, focusable.size());
        stage.setKeyboardFocus(focusable.get(next));
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

    /** The menu sits in a column at the right, clear of the art's main subject on the left, and slides in from the edge. */
    private void layoutMenu() {
        float margin = Math.max(40, w * 0.05f);
        float columnWidth = Math.max(260, w * 0.2f);
        float left = w - margin - columnWidth;
        float top = Math.max(32, h * 0.07f);
        double t = introTime();
        double logoIn = motion ? StartTimeline.ease((t - StartTimeline.MENU_START) / 0.5) : 1;
        float y = top;
        if (logo != null) {
            float logoWidth = columnWidth * 0.8f;
            float logoHeight = logoWidth * logoTexture.getHeight() / logoTexture.getWidth();
            logo.setBounds(w - margin - logoWidth + (float) ((1 - logoIn) * 60), h - top - logoHeight, logoWidth,
                  logoHeight);
            logo.setColor(1, 1, 1, (float) logoIn);
            logo.setVisible(logoIn > 0);
            y += logoHeight + 28;
        }
        float offscreen = columnWidth + margin + 40;
        for (int i = 0; i < column.size(); i++) {
            Actor item = column.get(i);
            float itemHeight = item instanceof com.badlogic.gdx.scenes.scene2d.utils.Layout layout
                  ? layout.getPrefHeight() : item.getHeight();
            double p = slide(i);
            float dx = (float) ((1 - p) * offscreen);
            if (item instanceof Label) {
                y += 14;
                item.setBounds(left + dx + 4, h - y - itemHeight, columnWidth, itemHeight);
                y += itemHeight + 6;
            } else {
                item.setBounds(left + dx, h - y - itemHeight, columnWidth, itemHeight);
                y += itemHeight + (i == 0 && page == Page.MAIN ? 14 : 8);
            }
            item.setVisible(p > 0);
        }
        double rowIn = slide(column.size());
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
    }
}
