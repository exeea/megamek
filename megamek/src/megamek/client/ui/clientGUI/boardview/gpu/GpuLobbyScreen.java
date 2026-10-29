/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Set;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiKit;
import megamek.logging.MMLogger;

/**
 * The native lobby's screen ({@link GpuLobbyWindow}), the briefing room in the game presentation on the plain
 * surface.base: the top bar ({@link LobbyTopBar}) over three columns ({@link LobbyColumns}),
 * the players with the chat docked under them on the left, the forces in the centre and the battlefield on the right.
 * In a narrow window the battlefield folds into a rail that opens it in the centre, in the forces' place, as a view with
 * Back ({@link LobbyCentre}). It draws only the {@link LobbySnapshot}s its window sends and hands every action back to
 * the window; it never reads the game.
 */
final class GpuLobbyScreen extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyScreen.class);
    private static final int FRAMES_PER_SECOND = 30;
    /** The centre view that shows the folded battlefield. */
    static final String BATTLEFIELD = "battlefield";

    private final GpuLobbyWindow window;
    private final GUIPreferences preferences;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final java.awt.Color base = awtColor("surface.base");

    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private float scale = 1;
    private int bw;
    private int bh;

    private LobbyTopBar topBar;
    private LobbyPlayersPanel players;
    private LobbyForcesPanel forces;
    private LobbyBattlefieldPanel battlefield;
    private LobbyCentre centre;
    private LobbyColumns columns;
    private Button rail;
    private boolean folded;
    private boolean railPending;
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
        build();
        setInputBlocked(window.modalOpen());
        Gdx.graphics.setForegroundFPS(FRAMES_PER_SECOND);
        show(window.snapshot());
        start = System.nanoTime();
        // Sizing the window draws a frame at once on some platforms
        sizeForProbe();
    }

    /** Gives the window the developer switch's size, keeping the saved size as it was. */
    private void sizeForProbe() {
        GpuLobbyProbe probe = window.probe();
        if (probe == null) {
            return;
        }
        railPending = probe.options().contains("rail");
        if (!probe.sized()) {
            return;
        }
        GpuHost.keepSavedBounds();
        if (Gdx.graphics instanceof Lwjgl3Graphics graphics && graphics.getWindow() != null) {
            graphics.getWindow().restoreWindow();
        }
        Gdx.graphics.setWindowedMode(probe.width(), probe.height());
    }

    // ---- Layout ----

    private void build() {
        boolean motion = !preferences.getReduceMotion();
        topBar = new LobbyTopBar(kit, window::leave, window::ready);
        players = new LobbyPlayersPanel(kit, boardSkin.skin, window::chat);
        forces = new LobbyForcesPanel(kit);
        battlefield = new LobbyBattlefieldPanel(kit, boardSkin.skin);
        centre = new LobbyCentre(kit, forces.panel(), motion);
        rail = kit.rail(Messages.getString("Lobby.battlefield"), "map");
        rail.setProgrammaticChangeEvents(false);
        kit.tip(rail, true).getActor().setText(Messages.getString("Lobby.railTip"));
        UiKit.onChange(rail, () -> {
            if (rail.isChecked()) {
                centre.show(BATTLEFIELD, Messages.getString("Lobby.battlefield"), battlefield.body());
            } else {
                centre.back();
            }
        });
        centre.onChange(this::centreChanged);
        columns = new LobbyColumns(players.panel(), centre, battlefield.panel(), rail, space("lg"));

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        stage.addActor(root);
        root.add(topBar).growX().pad(space("md"), space("lg"), space("md"), space("lg")).row();
        root.add(columns).grow().pad(0, space("lg"), space("lg"), space("lg"));
        stage.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Keys.ESCAPE && centre.showing() != null) {
                    centre.back();
                    return true;
                }
                return false;
            }
        });
    }

    /** The battlefield's body goes back to its column whenever another view, or the forces, take the centre. */
    private void centreChanged(String showing) {
        boolean battlefieldShown = BATTLEFIELD.equals(showing);
        rail.setChecked(battlefieldShown);
        if (!battlefieldShown && battlefield.body().getParent() != battlefield.panel()) {
            battlefield.dock();
        }
    }

    /** Folds the battlefield into its rail below the fold width, and unfolds it, closing its centre view, above. */
    private void applyFold() {
        boolean fold = LobbyLayout.folds(stage.getWidth());
        if (fold == folded) {
            return;
        }
        folded = fold;
        if (!fold && BATTLEFIELD.equals(centre.showing())) {
            centre.back();
        }
        columns.fold(fold);
    }

    /** GPU thread: shows {@code snapshot}, the lobby as its window last read it. */
    void show(LobbySnapshot snapshot) {
        if (stage == null || snapshot.equals(shown)) {
            return;
        }
        LobbySnapshot before = shown;
        shown = snapshot;
        topBar.show(snapshot);
        players.show(snapshot, before);
        if (before == null || !before.teams().equals(snapshot.teams())) {
            forces.show(snapshot);
        }
        battlefield.show(snapshot);
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
        applyFold();
    }

    // ---- Each frame ----

    @Override
    public void render() {
        Gdx.gl.glClearColor(base.getRed() / 255f, base.getGreen() / 255f, base.getBlue() / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (railPending && folded) {
            railPending = false;
            rail.setChecked(true);
            centre.show(BATTLEFIELD, Messages.getString("Lobby.battlefield"), battlefield.body());
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
        for (Object disposable : new Object[] { stage }) {
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
