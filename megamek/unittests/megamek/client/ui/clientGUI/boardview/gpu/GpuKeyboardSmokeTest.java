/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.util.KeyCommandBind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The battle view's keyboard path as GLFW reports keys: a modifier key reports itself while it goes down, a release
 * keeps the modifiers of its press, and the HUD and the camera follow the key bindings the client captured on the
 * Swing thread, so a rebound key works natively without the GL thread reading the bind fields.
 */
@Tag("on-demand")
class GpuKeyboardSmokeTest {
    @Test
    void nativeKeysFollowTheCapturedBindsAndTheModifiersOfTheirPress() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardSource source = mock(GpuBoardSource.class);
            source.uiPreferences = GpuHudInputTest.preferences();
            when(source.takeFrame()).thenAnswer(invocation -> fixture.source.takeFrame());
            new Lwjgl3Application(new GpuBattleView(source) {
                private int tick;

                @Override
                public void render() {
                    try {
                        super.render();
                        if (++tick != 3) {
                            return;
                        }
                        GpuHud hud = (GpuHud) field("ui");
                        Input realInput = Gdx.input;
                        InputProcessor processor = realInput.getInputProcessor();
                        Input keyboard = mock(Input.class);
                        when(keyboard.getInputProcessor()).thenReturn(processor);
                        Gdx.input = keyboard;
                        try {
                            // Alt goes down reporting Alt itself: the nameplate key, which never reaches Swing.
                            press(keyboard, InputEvent.ALT_DOWN_MASK);
                            assertTrue(processor.keyDown(Input.Keys.ALT_LEFT));
                            assertTrue(hud.state.altHeld, "Alt shows every nameplate while held");
                            press(keyboard, 0);
                            processor.keyUp(Input.Keys.ALT_LEFT);
                            assertFalse(hud.state.altHeld);
                            verify(source, never()).key(anyInt(), anyBoolean(), anyInt());

                            // A bare Shift has no bind: Swing gets it with the mask it reports.
                            press(keyboard, InputEvent.SHIFT_DOWN_MASK);
                            processor.keyDown(Input.Keys.SHIFT_LEFT);
                            processor.keyUp(Input.Keys.SHIFT_LEFT);
                            verify(source).key(KeyEvent.VK_SHIFT, true, InputEvent.SHIFT_DOWN_MASK);
                            verify(source).key(KeyEvent.VK_SHIFT, false, InputEvent.SHIFT_DOWN_MASK);

                            // Shift released before W: the release keeps the modifiers of the press
                            // (MegaMek's MOVE_STEP_FORWARD), and an auto-repeated press does not reach Swing twice.
                            clearInvocations(source);
                            press(keyboard, InputEvent.SHIFT_DOWN_MASK);
                            processor.keyDown(Input.Keys.W);
                            processor.keyDown(Input.Keys.W);
                            press(keyboard, 0);
                            processor.keyUp(Input.Keys.W);
                            verify(source, times(1)).key(KeyEvent.VK_W, true, InputEvent.SHIFT_DOWN_MASK);
                            verify(source, times(1)).key(KeyEvent.VK_W, false, InputEvent.SHIFT_DOWN_MASK);

                            // Rebound on the Swing thread: Help on F9 and north scrolling on I. The view follows the
                            // captured binds, while the bind fields keep MegaMek's defaults.
                            source.uiPreferences = rebound(Map.of(KeyCommandBind.KEY_BINDS, KeyEvent.VK_F9,
                                  KeyCommandBind.SCROLL_NORTH, KeyEvent.VK_I));
                            super.render();
                            assertEquals(KeyEvent.VK_K, KeyCommandBind.KEY_BINDS.key);
                            clearInvocations(source);
                            processor.keyDown(Input.Keys.F9);
                            processor.keyUp(Input.Keys.F9);
                            assertEquals(GpuHudState.Dialog.HELP, hud.state.dialog, "F9 opens Help now");
                            press(keyboard, InputEvent.CTRL_DOWN_MASK);
                            processor.keyDown(Input.Keys.K);
                            processor.keyUp(Input.Keys.K);
                            assertEquals(GpuHudState.Dialog.HELP, hud.state.dialog, "Ctrl+K no longer toggles Help");
                            verify(source).key(KeyEvent.VK_K, true, InputEvent.CTRL_DOWN_MASK);
                            press(keyboard, 0);
                            processor.keyDown(Input.Keys.I);
                            assertTrue(cameraKeys().containsValue(KeyCommandBind.SCROLL_NORTH), "I scrolls north");
                            processor.keyUp(Input.Keys.I);
                            processor.keyDown(Input.Keys.W);
                            assertTrue(cameraKeys().isEmpty(), "W no longer scrolls");
                            processor.keyUp(Input.Keys.W);
                            verify(source).key(KeyEvent.VK_W, true, 0);
                            verify(source, never()).key(KeyEvent.VK_I, true, 0);
                            verify(source, never()).key(KeyEvent.VK_F9, true, 0);
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        } finally {
                            Gdx.input = realInput;
                        }
                        Gdx.app.exit();
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @SuppressWarnings("unchecked")
                private Map<Integer, KeyCommandBind> cameraKeys() throws Exception {
                    return (Map<Integer, KeyCommandBind>) field("cameraKeys");
                }

                private Object field(String name) throws Exception {
                    var field = GpuBattleView.class.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(this);
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) {
            throw new AssertionError("Native keyboard routing failed", failure.get());
        }
    }

    /** MegaMek's default binds with {@code keys} moved to new keys without modifiers, as Swing captures them. */
    private static GpuBoardSource.UiPreferences rebound(Map<KeyCommandBind, Integer> keys) {
        GpuBoardSource.UiPreferences defaults = GpuHudInputTest.preferences();
        List<GpuBoardSource.Bind> binds = defaults.binds().stream().map(bind -> keys.containsKey(bind.command())
              ? new GpuBoardSource.Bind(bind.command(), keys.get(bind.command()), 0, bind.text()) : bind).toList();
        return new GpuBoardSource.UiPreferences(defaults.scale(), "", "", true, false, false, false, binds, 0, 0, 0);
    }

    /** The modifier keys the mocked input reports as held, as GLFW does while they are down. */
    private static void press(Input keyboard, int modifiers) {
        when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.SYM)).thenReturn((modifiers & InputEvent.META_DOWN_MASK) != 0);
    }
}
