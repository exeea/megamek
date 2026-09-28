/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Loading-to-board resource transfer in an actual native context, without a classic view. */
@Tag("on-demand")
class GpuStartupSmokeTest {
    @Test
    void loadingScreenTransfersItsSkinAndMapOnlyFramesNeedNoGameplayMarkers() throws Exception {
        AtomicReference<GpuMapSource> source = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Hex[] tiles = new Hex[12];
            Arrays.setAll(tiles, ignored -> new Hex(0));
            Game game = new Game();
            game.setBoard(new Board(4, 3, tiles));
            game.setPhase(GamePhase.LOUNGE);
            source.set(new GpuMapSource(game, null, null));
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new GpuBattleView(null) {
                private GpuBoardSkin theme;
                private int ticks;

                @Override
                public void create() {
                    try {
                        super.create();
                        theme = (GpuBoardSkin) field(GpuBattleView.class, this, "loadingTheme");
                        attachSource(source.get());
                        var ui = field(GpuBattleView.class, this, "ui");
                        assertSame(theme, field(GpuBoardUi.class, ui, "theme"));
                        assertNull(field(GpuBattleView.class, this, "loadingTheme"), "Only the board UI owns the transferred skin");
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }

                @Override
                public void render() {
                    if (failure.get() != null) { return; }
                    try {
                        super.render();
                        assertTrue(++ticks < 10000, "The loading-to-board handoff must finish");
                        if (frames() < 3) { return; }
                        var markers = (GpuMarkers) field(GpuBattleView.class, this, "markers");
                        assertTrue(((Map<?, ?>) field(GpuMarkers.class, markers, "models")).isEmpty(),
                              "A map-only frame must not build gameplay symbol geometry");
                        var first = markers.model(BoardMarker.Kind.SENSOR_CONTACT);
                        assertSame(first, markers.model(BoardMarker.Kind.SENSOR_CONTACT));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        Gdx.app.exit();
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
        } finally {
            SwingUtilities.invokeAndWait(source.get()::close);
        }
        if (failure.get() != null) { throw new AssertionError("Native startup ownership", failure.get()); }
    }

    @Test
    void gameplayPreparesTacticalArtworkBeforeInteractiveFrames() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(Board.createEmptyBoard(8, 8))) {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                @Override
                public void create() {
                    try {
                        super.create();
                        var markers = field(GpuBattleView.class, this, "markers");
                        assertFalse(((Map<?, ?>) field(GpuMarkers.class, markers, "models")).isEmpty(),
                              "Revealing the first sensor contact must not trigger tactical artwork construction");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Gameplay artwork preparation", failure.get()); }
    }

    private static Object field(Class<?> owner, Object value, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
}
