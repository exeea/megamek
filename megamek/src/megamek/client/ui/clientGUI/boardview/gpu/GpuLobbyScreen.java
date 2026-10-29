/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.PlayerRow;
import megamek.client.lobby.LobbySnapshot.TeamGroup;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/**
 * The native lobby's screen ({@link GpuLobbyWindow}), in the game presentation over the start screen's battle art,
 * dimmed: a top bar with the title, where the game runs, the map, how many players are ready, Ready and Leave; the
 * players by team on the left and the chat on the right. It draws only the {@link LobbySnapshot}s its window sends and
 * hands every action back to the window; it never reads the game.
 */
final class GpuLobbyScreen extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyScreen.class);
    /** How much of the window's width the players' panel takes. */
    private static final float PLAYERS_SHARE = 0.42f;
    /** How dark the art is under the lobby's panels. */
    private static final float ART_DIM = 0.78f;
    private static final int FRAMES_PER_SECOND = 30;

    private final GpuLobbyWindow window;
    private final GUIPreferences preferences;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final java.awt.Color base = awtColor("surface.base");
    private final Matrix4 pixels = new Matrix4();

    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private SpriteBatch batch;
    private BufferedImage art;
    private Texture backdrop;
    private float scale = 1;
    private int bw;
    private int bh;
    private boolean inputBlocked;

    private Label serverLine;
    private Label readyCount;
    private UiButton readyButton;
    private Table playerList;
    private Label playerCount;
    private Label chatLog;
    private ScrollPane chatScroll;
    private UiTextField chatInput;
    private LobbySnapshot shown;
    private long start;
    private boolean shotSaved;

    GpuLobbyScreen(GpuLobbyWindow window, GUIPreferences preferences) {
        this.window = window;
        this.preferences = preferences;
    }

    private java.awt.Color awtColor(String name) {
        return new java.awt.Color(tokens.color(name, Mode.DARK), true);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** GPU thread: ignores the mouse and keyboard while a modal Swing dialog is open, as a disabled window would. */
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
        boardSkin = new GpuBoardSkin();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport());
        batch = new SpriteBatch();
        art = readArt();
        build();
        setInputBlocked(window.modalOpen());
        Gdx.graphics.setForegroundFPS(FRAMES_PER_SECOND);
        show(window.snapshot());
        start = System.nanoTime();
    }

    private static BufferedImage readArt() {
        File file = new File(Configuration.miscImagesDir(), "mm-background.jpg");
        try {
            return ImageIO.read(file);
        } catch (IOException failure) {
            LOGGER.error(failure, "The lobby could not read {}", file);
            return null;
        }
    }

    // ---- Layout ----

    private void build() {
        Table root = new Table();
        root.setFillParent(true);
        root.top().pad(space("xl"));
        stage.addActor(root);
        root.add(topBar()).growX().row();
        Table body = new Table();
        body.add(playersPanel()).width(Value.percentWidth(PLAYERS_SHARE, body)).growY().padRight(space("lg"));
        body.add(chatPanel()).grow();
        root.add(body).grow().padTop(space("lg"));
    }

    private Table topBar() {
        Table bar = kit.panel(true);
        Table titles = new Table();
        titles.left();
        titles.add(kit.roleTypeLabel("display", Messages.getString("Lobby.title"), "text.primary")).left().row();
        serverLine = kit.typeLabel("", "body", "text.secondary");
        titles.add(serverLine).left().padTop(space("xs"));
        bar.add(titles).left().growX().minWidth(0);
        readyCount = kit.typeLabel("", "title", "text.primary");
        bar.add(readyCount).padRight(space("lg"));
        readyButton = kit.button("button-primary", null, Messages.getString("Lobby.ready"), null);
        UiKit.onChange(readyButton, window::ready);
        bar.add(readyButton).padRight(space("sm"));
        UiButton leave = kit.button("button-secondary", null, Messages.getString("Lobby.leave"), null);
        UiKit.onChange(leave, window::leave);
        bar.add(leave);
        return bar;
    }

    private Table playersPanel() {
        Table panel = kit.panel(true);
        Table header = kit.header(Messages.getString("Lobby.players"), "", true);
        playerCount = header.findActor(UiKit.HEADER_COUNT);
        panel.add(header).growX().row();
        playerList = new Table();
        playerList.top();
        panel.add(kit.scrollList(playerList, true)).grow();
        return panel;
    }

    private Table chatPanel() {
        Table panel = kit.panel(true);
        panel.add(kit.header(Messages.getString("Lobby.chat"), null, true)).growX().row();
        chatLog = kit.typeLabel("", "body", "text.secondary");
        chatLog.setWrap(true);
        chatLog.setAlignment(Align.topLeft);
        Table holder = new Table();
        holder.top().left();
        holder.add(chatLog).growX().top().left();
        chatScroll = kit.scrollList(holder, true);
        panel.add(chatScroll).grow().row();
        chatInput = kit.input("", false);
        chatInput.setMessageText(Messages.getString("Lobby.chatHint"));
        chatInput.setTextFieldListener((field, typed) -> {
            if (typed == '\r' || typed == '\n') {
                String text = field.getText();
                field.setText("");
                window.chat(text);
            }
        });
        panel.add(chatInput).growX().padTop(space("md"));
        return panel;
    }

    /** GPU thread: shows {@code snapshot}, the lobby as its window last read it. */
    void show(LobbySnapshot snapshot) {
        if (stage == null || snapshot.equals(shown)) {
            return;
        }
        LobbySnapshot before = shown;
        shown = snapshot;
        String server = snapshot.server();
        if (!snapshot.mapSummary().isBlank()) {
            server += "  \u00b7  " + snapshot.mapSummary();
        }
        serverLine.setText(server);
        readyCount.setText(Messages.getString("Lobby.readyCount", snapshot.readyCount(), snapshot.playerCount()));
        readyButton.setText(kit.cased("button",
              Messages.getString(snapshot.localReady() ? "Lobby.cancelReady" : "Lobby.ready")));
        if (playerCount != null) {
            playerCount.setText(String.valueOf(snapshot.playerCount()));
        }
        if (before == null || !before.teams().equals(snapshot.teams())) {
            fillPlayers(snapshot);
        }
        if (before == null || !before.chat().equals(snapshot.chat())) {
            chatLog.setText(String.join("\n", snapshot.chat()));
            chatScroll.layout();
            chatScroll.setScrollPercentY(1);
            chatScroll.updateVisualScroll();
        }
    }

    private void fillPlayers(LobbySnapshot snapshot) {
        playerList.clearChildren();
        boolean first = true;
        for (TeamGroup team : snapshot.teams()) {
            playerList.add(kit.caption(team.name(), true)).left().growX()
                  .padTop(first ? 0 : space("lg")).padBottom(space("xs")).row();
            first = false;
            for (PlayerRow player : team.players()) {
                playerList.add(playerRow(player)).growX().padTop(space("xs")).row();
            }
        }
    }

    private Table playerRow(PlayerRow player) {
        Table row = new Table();
        Table name = new Table();
        name.left();
        Label label = kit.roleTypeLabel("name", player.name(), player.self() ? "accent.primary" : "text.primary");
        label.setEllipsis(true);
        name.add(label).left().minWidth(0);
        if (player.self()) {
            name.add(mark("Lobby.you")).padLeft(space("xs"));
        }
        if (player.bot()) {
            name.add(mark("Lobby.bot")).padLeft(space("xs"));
        }
        if (player.gameMaster()) {
            name.add(mark("Lobby.gameMaster")).padLeft(space("xs"));
        }
        if (player.observer()) {
            name.add(mark("Lobby.observer")).padLeft(space("xs"));
        }
        row.add(name).left().growX().minWidth(0).row();
        Table details = new Table();
        String forces = player.hidden() ? Messages.getString("Lobby.hidden")
              : Messages.getString("Lobby.forces", player.units(), player.bv());
        details.add(kit.typeLabel(forces, "small", "text.secondary")).left().growX();
        details.add(kit.chip(Messages.getString(player.ready() ? "Lobby.isReady" : "Lobby.notReady"),
              player.ready() ? Tone.OK : Tone.NORMAL, true));
        row.add(details).growX().padTop(space("xs"));
        return row;
    }

    private Actor mark(String key) {
        return kit.chip(Messages.getString(key), Tone.NORMAL, true);
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
        pixels.setToOrtho2D(0, 0, bw, bh);
        rebuildBackdrop();
    }

    /** The start screen's art, cover-fitted and dimmed under the panels, drawn once at the window's size. */
    private void rebuildBackdrop() {
        BufferedImage image = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(base);
        g.fillRect(0, 0, bw, bh);
        if (art != null) {
            double cover = Math.max((double) bw / art.getWidth(), (double) bh / art.getHeight()) * 1.04;
            int drawnWidth = (int) (art.getWidth() * cover);
            int drawnHeight = (int) (art.getHeight() * cover);
            g.drawImage(art, (bw - drawnWidth) / 2, (bh - drawnHeight) / 2, drawnWidth, drawnHeight, null);
        }
        g.setColor(alpha(base, ART_DIM));
        g.fillRect(0, 0, bw, bh);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(bw * 0.5, bh * 0.45), Math.max(bw, bh) * 0.75f,
              new float[] { 0.5f, 1f }, new java.awt.Color[] { alpha(base, 0f), alpha(base, 0.6f) }));
        g.fillRect(0, 0, bw, bh);
        g.dispose();
        if (backdrop != null) {
            backdrop.dispose();
        }
        backdrop = GpuStartScreen.texture(image, false);
    }

    private static java.awt.Color alpha(java.awt.Color color, float alpha) {
        return new java.awt.Color(color.getRed(), color.getGreen(), color.getBlue(),
              Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    // ---- Each frame ----

    @Override
    public void render() {
        Gdx.gl.glClearColor(base.getRed() / 255f, base.getGreen() / 255f, base.getBlue() / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (backdrop != null) {
            batch.setProjectionMatrix(pixels);
            batch.begin();
            batch.setColor(1, 1, 1, 1);
            batch.draw(backdrop, 0, 0, bw, bh);
            batch.end();
        }
        stage.act(Math.min(0.1f, Gdx.graphics.getDeltaTime()));
        hud.update(Set.of());
        stage.draw();
        takeShot();
    }

    /** Saves the developer switch's PNG once its time has come, then quits when it asks to. */
    private void takeShot() {
        GpuLobbyProbe probe = window.probe();
        if (shotSaved || probe == null || probe.shot() == null || (System.nanoTime() - start) / 1e9 < probe.shotAt()) {
            return;
        }
        shotSaved = true;
        GpuLobbyProbe.reportWindows();
        if (GpuStartScreen.saveFrame(probe.shot(), bw, bh)) {
            LOGGER.info("Lobby shot saved to {}", probe.shot().getAbsoluteFile());
        }
        if (probe.quit()) {
            // After the window report, which waits its turn on the same thread
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> System.exit(0)));
        }
    }

    @Override
    public void dispose() {
        window.disposing();
        for (Object disposable : new Object[] { stage, batch, backdrop }) {
            if (disposable instanceof com.badlogic.gdx.utils.Disposable resource) {
                resource.dispose();
            }
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.dispose();
        }
    }
}
