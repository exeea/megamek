/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GpuHud's key dispatch, Esc chain, focus rule, presented units and board-click routing, without a GL context: the
 * source and its services are mocks, so every command the HUD posts is recorded and nothing reaches Swing.
 */
class GpuHudInputTest {
    private static final int FIRST = 1;
    private static final int SECOND = 2;
    private static final int THIRD = 3;
    private static final int FOE = 7;
    private static final int BLIP = 8;
    private static final Coords HEX = new Coords(4, 6);
    private static final GpuMovePlan.Step STEP = new GpuMovePlan.Step(HEX, 0, 0, 0, GpuMovePlan.Band.WALK, false);

    /** A key as the board view passes it: the libGDX code and the AWT code and modifiers. */
    private record Key(int gdx, int awt, int modifiers) { }

    private Application application;
    private Files files;
    private Graphics graphics;
    private GL20 gl;
    private GL20 gl20;
    private Input input;
    private final GpuBoardSource source = mock(GpuBoardSource.class);
    private final GpuMovePlan moves = mock(GpuMovePlan.class);
    private final GpuFireOrders fire = mock(GpuFireOrders.class);
    private final GpuPhysicalOptions physical = mock(GpuPhysicalOptions.class);
    private final GpuUnitRecord record = mock(GpuUnitRecord.class);
    private GpuBoardSkin theme;
    private GpuHud hud;

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @BeforeEach
    void createHud() {
        application = Gdx.app;
        files = Gdx.files;
        graphics = Gdx.graphics;
        gl = Gdx.gl;
        gl20 = Gdx.gl20;
        input = Gdx.input;
        // Scene2D reads these statics (a Table cell needs Gdx.files); the HUD never draws in this test. The components
        // build their widgets from the HUD skin, which bakes its fonts and icons against the mocked GL (libGDX's
        // desktop mipmap path reads Gdx.gl20).
        Gdx.app = mock(Application.class);
        Gdx.files = mock(Files.class);
        // The HUD skin reads its enemy icon from the classpath.
        when(Gdx.files.classpath(anyString())).thenAnswer(call -> new Lwjgl3Files().classpath(call.getArgument(0)));
        Gdx.graphics = mock(Graphics.class);
        Gdx.gl = mock(GL20.class);
        Gdx.gl20 = Gdx.gl;
        Gdx.input = mock(Input.class);
        when(source.moves()).thenReturn(moves);
        when(source.fire()).thenReturn(fire);
        when(source.physical()).thenReturn(physical);
        when(source.record()).thenReturn(record);
        theme = new GpuBoardSkin();
        hud = new GpuHud(source, theme.skin, mock(Batch.class), new BoardCamera(), mock(GpuBoardTuning.class),
              new GpuPlaybackHistory(new UnitPlayback()));
        hud.resize(1920, 1080, 1);
    }

    @AfterEach
    void restoreGdx() {
        try {
            if (hud != null) {
                hud.dispose();
            }
            if (theme != null) {
                theme.dispose();
            }
        } finally {
            Gdx.app = application;
            Gdx.files = files;
            Gdx.graphics = graphics;
            Gdx.gl = gl;
            Gdx.gl20 = gl20;
            Gdx.input = input;
        }
    }

    @Test
    void escapeWithTheOverviewOpenClosesOnlyTheOverviewThenWalksTheChain() {
        GpuBattleStatus.Snapshot moving = status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true),
              unit(FOE, ENEMY, false, false));
        update(moving, panels(move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        hud.state.overview = true;
        hud.state.chatOpen = true;
        hud.state.inspected = FOE;

        assertTrue(escape());
        assertFalse(hud.state.overview);
        assertTrue(hud.state.chatOpen);
        assertEquals(FOE, hud.state.inspected);
        verify(moves, never()).clearRoute();

        assertTrue(escape());
        assertFalse(hud.state.chatOpen);
        assertTrue(escape());
        verify(moves, times(1)).clearRoute();
        assertEquals(FOE, hud.state.inspected);
        // The EDT publishes the cleared route.
        update(moving, panels(move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertTrue(escape());
        assertEquals(Entity.NONE, hud.state.inspected);
        assertFalse(escape(), "the last Esc of the movement phase goes to the phase display as CANCEL");

        // OFFBOARD runs the targeting display, whose CANCEL clears the declared attacks as well.
        for (GamePhase phase : List.of(GamePhase.FIRING, GamePhase.TARGETING, GamePhase.OFFBOARD,
              GamePhase.PHYSICAL)) {
            update(status(3, phase, true, FIRST, 0, unit(FIRST, OWN, true, true)), GpuHudData.EMPTY, null);
            assertTrue(escape(), "CANCEL would clear declared attacks, so " + phase + " never forwards it");
        }
        verify(moves, times(1)).clearRoute();
    }

    @Test
    void theUnitDisplayKeysOpenTheUnitSheetOnTheirTabs() {
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, unit(FIRST, OWN, false, true)));
        int[] keys = { KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5, KeyEvent.VK_F6 };
        for (int index = 0; index < keys.length; index++) {
            assertTrue(press(Input.Keys.F1 + index, keys[index], 0), "F" + (index + 1) + " is the HUD's");
            assertEquals(GpuHudState.SheetTab.values()[index], hud.state.sheetTab);
            assertTrue(hud.state.recordOpen);
        }
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, unit(FIRST, OWN, false, true)));
        verify(record).setSheetOpen(true);
        assertTrue(press(Input.Keys.D, KeyEvent.VK_D, CTRL_DOWN_MASK));
        assertFalse(hud.state.recordOpen, "Ctrl+D toggles the sheet");
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, unit(FIRST, OWN, false, true)));
        verify(record).setSheetOpen(false);
        hud.state.overview = true;
        assertTrue(press(Input.Keys.D, KeyEvent.VK_D, CTRL_DOWN_MASK));
        assertTrue(hud.state.recordOpen && !hud.state.overview, "over the overview it closes it and shows the sheet");
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, unit(FIRST, OWN, false, true)));
        assertTrue(press(Input.Keys.G, KeyEvent.VK_G, 0));
        assertTrue(hud.state.forcesGrid && !hud.state.recordOpen, "one wide left panel at a time: G shows the grid");
        assertEquals(GpuHudState.SheetTab.EXTRAS, hud.state.sheetTab, "the tab stays for the next opening");
    }

    @Test
    void nonPlannerMovementKeepsMegaMeksOwnKeys() {
        // An aerospace or vector route is MegaMek's own (plan A.7 G20): its keys and CANCEL reach the phase display.
        update(status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true)),
              panels(move(false, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        for (Key key : List.of(new Key(Input.Keys.A, KeyEvent.VK_A, SHIFT_DOWN_MASK),
              new Key(Input.Keys.D, KeyEvent.VK_D, SHIFT_DOWN_MASK),
              new Key(Input.Keys.BACKSPACE, KeyEvent.VK_BACK_SPACE, 0),
              new Key(Input.Keys.FORWARD_DEL, KeyEvent.VK_DELETE, 0), new Key(Input.Keys.NUM_1, KeyEvent.VK_1, 0),
              new Key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0))) {
            assertFalse(press(key.gdx(), key.awt(), key.modifiers()), "key " + key + " was consumed");
        }
        verifyNoInteractions(moves);
    }

    @Test
    void aFocusedGripReordersOnAltUpAndLeavesOtherKeysToTheHotkeys() {
        update(status(3, GamePhase.FIRING, true, FIRST, 0, unit(FIRST, OWN, true, true)), GpuHudData.EMPTY, null);
        Table grip = new Table();
        grip.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.UP && UIUtils.alt()) {
                    source.fire().move(5, -1);
                    return true;
                }
                return false;
            }
        });
        hud.stage.addActor(grip);
        hud.stage.setKeyboardFocus(grip);
        when(Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn(true);

        assertTrue(press(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
        assertFalse(hud.state.altHeld, "Alt with a focused grip belongs to the reordering, not to the nameplates");
        assertTrue(press(Input.Keys.UP, KeyEvent.VK_UP, ALT_DOWN_MASK), "never forwarded as CALLED_SHOT_HIGH");
        verify(fire, times(1)).move(5, -1);

        assertTrue(press(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK));
        assertTrue(hud.state.logOpen(), "a key the grip does not use still reaches the hotkeys");
        assertTrue(escape());
        assertNull(hud.stage.getKeyboardFocus(), "the first Esc only ends the grip's focus");
        assertTrue(hud.state.logOpen());

        hud.stage.setKeyboardFocus(grip);
        hud.boardPress();
        assertNull(hud.stage.getKeyboardFocus(), "a press on the board ends the grip's focus as well");
    }

    @Test
    void aFocusedTextFieldTakesTheDigitThatWouldOtherwiseSetTheMovementMode() {
        update(status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true)),
              panels(move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        TextField field = textField();
        hud.stage.addActor(field);
        hud.stage.setKeyboardFocus(field);

        assertTrue(hud.isTextEditing());
        assertTrue(press(Input.Keys.NUM_1, KeyEvent.VK_1, 0));
        assertTrue(hud.keyTyped('1'));
        assertEquals("1", field.getText());
        verify(moves, never()).setMode(any());
        // A shortcut the field itself ignores is kept from the hotkeys as well.
        when(Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(true);
        assertTrue(press(Input.Keys.K, KeyEvent.VK_K, CTRL_DOWN_MASK));
        when(Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(false);
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);

        assertTrue(escape());
        assertFalse(hud.isTextEditing());
        assertTrue(press(Input.Keys.NUM_1, KeyEvent.VK_1, 0));
        verify(moves, times(1)).setMode(GpuMovePlan.Mode.WALK);
        assertEquals("1", field.getText());
    }

    @Test
    void theEnterThatOpensTheChatIsNotTypedIntoIt() {
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0));
        assertTrue(press(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0));
        assertTrue(hud.state.chatOpen);
        // GLFW follows Enter with a typed newline, which reaches the chat's input focused by then (G15).
        List<Character> typed = new ArrayList<>();
        TextField chatInput = textField();
        chatInput.setTextFieldListener((field, character) -> typed.add(character));
        hud.stage.addActor(chatInput);
        hud.stage.setKeyboardFocus(chatInput);

        assertTrue(hud.keyTyped('\n'));
        assertTrue(hud.keyTyped('1'));
        assertEquals(List.of('1'), typed, "only the message's own characters reach the input");
    }

    @Test
    void releasingTheNameplateKeyEndsTheNameplates() {
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0));
        assertTrue(press(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
        assertTrue(hud.state.altHeld);
        // The release carries the key only, so a Shift or Ctrl still held does not keep the plates.
        assertTrue(hud.keyUp(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT), "the consumed press's release is consumed too");
        assertFalse(hud.state.altHeld);
    }

    @Test
    void aPendingDialogTakesEveryKeyAndClickAndPostsNoCommand() {
        GpuBattleStatus.Snapshot moving = status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true),
              unit(FOE, ENEMY, false, false));
        GpuHudData planned = panels(move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY);
        update(moving, planned, null);
        hud.state.overview = true;
        hud.state.inspected = FOE;
        update(moving, planned, message());
        Actor modal = hud.stage.getRoot().findActor("modal-dialog");
        assertSame(modal, hud.stage.getKeyboardFocus(), "the pending dialog holds the keyboard focus");
        List<Integer> modalKeys = new ArrayList<>();
        modal.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                modalKeys.add(keycode);
                return false;
            }
        });
        clearInvocations(source);

        for (Key key : List.of(new Key(Input.Keys.NUM_1, KeyEvent.VK_1, 0), new Key(Input.Keys.T, KeyEvent.VK_T, 0),
              new Key(Input.Keys.G, KeyEvent.VK_G, 0), new Key(Input.Keys.SPACE, KeyEvent.VK_SPACE, 0),
              new Key(Input.Keys.FORWARD_DEL, KeyEvent.VK_DELETE, 0),
              new Key(Input.Keys.BACKSPACE, KeyEvent.VK_BACK_SPACE, 0),
              new Key(Input.Keys.A, KeyEvent.VK_A, SHIFT_DOWN_MASK),
              new Key(Input.Keys.M, KeyEvent.VK_M, CTRL_DOWN_MASK),
              new Key(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK),
              new Key(Input.Keys.U, KeyEvent.VK_U, CTRL_DOWN_MASK),
              new Key(Input.Keys.K, KeyEvent.VK_K, CTRL_DOWN_MASK),
              new Key(Input.Keys.ENTER, KeyEvent.VK_ENTER, CTRL_DOWN_MASK),
              new Key(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK),
              new Key(Input.Keys.ENTER, KeyEvent.VK_ENTER, 0), new Key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0),
              new Key(Input.Keys.UP, KeyEvent.VK_UP, 0), new Key(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0),
              new Key(Input.Keys.LEFT, KeyEvent.VK_LEFT, 0), new Key(Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 0))) {
            assertTrue(press(key.gdx(), key.awt(), key.modifiers()), "key " + key + " was not consumed");
        }
        assertTrue(hud.hit(10, 10), "the scrim takes every press");
        hud.boardPress();
        hud.boardClick(HEX, Entity.NONE, Input.Buttons.LEFT, 0, 10, 10, Float.NaN);
        assertSame(modal, hud.stage.getKeyboardFocus());

        assertTrue(modalKeys.containsAll(List.of(Input.Keys.ENTER, Input.Keys.UP, Input.Keys.DOWN, Input.Keys.LEFT,
              Input.Keys.RIGHT)), "the dialog's own controls receive Enter and the arrows: " + modalKeys);
        assertFalse(modalKeys.contains(Input.Keys.ESCAPE), "Esc presses the dialog's cancel button instead");
        verifyNoInteractions(moves, fire, physical);
        verify(source, never()).selectUnit(anyInt());
        verify(source, never()).locateUnit(anyInt());
        verify(source, never()).click(any(), anyBoolean(), anyInt());
        verify(source, never()).hover(any(), anyInt());
        verify(source, never()).key(anyInt(), anyBoolean(), anyInt());
        verify(source, never()).command(any());
        assertTrue(hud.state.overview, "the Esc chain behind the dialog did not run");
        assertEquals(FOE, hud.state.inspected);
        assertFalse(hud.state.logOpen());
        assertFalse(hud.state.chatOpen);
        assertEquals(GpuHudState.Dialog.NONE, hud.state.dialog);

        update(moving, planned, null);
        assertNull(hud.stage.getKeyboardFocus(), "an answered dialog keeps no focus");
    }

    @Test
    void theFocusFollowsPhaseStartCommitAndTheBeginningOfTheLocalTurn() {
        // Phase start, the opponent's turn: the first pending own unit by id.
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0, unit(THIRD, OWN, false, true),
              unit(FIRST, OWN, false, true), unit(SECOND, OWN, false, true), unit(FOE, ENEMY, false, true)));
        assertEquals(FIRST, hud.state.focus());
        verify(source).setFocusUnit(FIRST);

        // The local turn begins with a dialog pending and the phase display on the third unit: the HUD selects the
        // focus once, as soon as no dialog would drop the command.
        GpuBattleStatus.Snapshot begun = status(3, GamePhase.MOVEMENT, true, THIRD, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true), unit(THIRD, OWN, true, true));
        update(begun, GpuHudData.EMPTY, message());
        verify(source, never()).selectUnit(anyInt());
        update(begun);
        update(status(3, GamePhase.MOVEMENT, true, THIRD, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true), unit(THIRD, OWN, true, true)));
        verify(source, times(1)).selectUnit(FIRST);
        assertEquals(FIRST, hud.state.focus());
        update(status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true), unit(THIRD, OWN, true, true)));
        assertEquals(FIRST, hud.state.focus());

        // A selection during the local turn moves the focus.
        update(status(3, GamePhase.MOVEMENT, true, THIRD, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true), unit(THIRD, OWN, true, true)));
        assertEquals(THIRD, hud.state.focus());

        // The actor commits: its update arrives before the turn ends, and the focus moves once, to the next pending
        // unit after it, wrapping around to the first.
        update(status(3, GamePhase.MOVEMENT, true, Entity.NONE, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true), unit(THIRD, OWN, false, false)));
        update(status(3, GamePhase.MOVEMENT, false, THIRD, 2, unit(FIRST, OWN, false, true),
              unit(SECOND, OWN, false, true), unit(THIRD, OWN, false, false)));
        assertEquals(FIRST, hud.state.focus());

        // A turn for one unit only: the focus cannot act, so the HUD adopts the display's actor and selects nothing.
        update(status(3, GamePhase.MOVEMENT, true, SECOND, 4, unit(FIRST, OWN, false, true),
              unit(SECOND, OWN, true, true), unit(THIRD, OWN, false, false)));
        assertEquals(SECOND, hud.state.focus());

        // Its commit leaves the first unit as the only candidate.
        update(status(3, GamePhase.MOVEMENT, false, SECOND, 5, unit(FIRST, OWN, false, true),
              unit(SECOND, OWN, false, false), unit(THIRD, OWN, false, false)));
        assertEquals(FIRST, hud.state.focus());

        // The next phase starts at the first pending unit again.
        update(status(3, GamePhase.FIRING, false, Entity.NONE, 0, unit(FIRST, OWN, false, true),
              unit(SECOND, OWN, false, true), unit(THIRD, OWN, false, true)));
        assertEquals(FIRST, hud.state.focus());
        verify(source, times(1)).selectUnit(anyInt());
    }

    @Test
    void theLogOpensForTheReportPhasesAndKeepsALogThePlayerOpened() {
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0));
        update(status(3, GamePhase.MOVEMENT_REPORT, false, Entity.NONE, 0));
        assertTrue(hud.state.logOpen(), "the report phase opens the log");
        update(status(3, GamePhase.FIRING, false, Entity.NONE, 0));
        assertFalse(hud.state.logOpen(), "and the next phase closes it again");

        assertTrue(press(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK));
        update(status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0));
        update(status(3, GamePhase.PHYSICAL, false, Entity.NONE, 0));
        assertTrue(hud.state.logOpen(), "a log the player opened stays open after the report phase");

        assertTrue(press(Input.Keys.R, KeyEvent.VK_R, CTRL_DOWN_MASK));
        update(status(4, GamePhase.INITIATIVE_REPORT, false, Entity.NONE, 0));
        assertFalse(hud.state.logOpen(), "the initiative report does not open it");
    }

    @Test
    void unitsStayPresentedUntilThePlaybackHasShownTheirEvents() {
        GpuBattleStatus.UnitStatus before = unit(FIRST, OWN, false, false);
        GpuBattleStatus.UnitStatus after = unit(FIRST, OWN, false, false, .4);
        GpuUnitRecord.Snapshot record = record(FIRST);
        GpuUnitRecord.Snapshot damaged = record(FIRST);
        hud.update(frame(status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0, before),
              panels(record)), GpuHud.HudView.EMPTY, null, preferences());
        GpuHud.HudView playing = new GpuHud.HudView(false, true, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);

        GpuBattleStatus.Snapshot newer = status(3, GamePhase.FIRING_REPORT, false, Entity.NONE, 0, after);
        hud.update(frame(newer, panels(damaged)), playing, null, preferences());
        assertEquals(List.of(before), hud.state.presentedUnits());
        assertSame(record, hud.state.presentedRecord());
        GpuUnitRecord.Snapshot other = record(SECOND);
        hud.update(frame(newer, panels(other)), playing, null, preferences());
        assertSame(other, hud.state.presentedRecord(), "another unit's record has nothing older to wait for");

        hud.update(frame(newer, panels(damaged)), GpuHud.HudView.EMPTY, null, preferences());
        assertEquals(List.of(after), hud.state.presentedUnits());
        assertSame(damaged, hud.state.presentedRecord());
    }

    @Test
    void boardClicksFollowThePhaseAndTheLocalTurn() {
        GpuBattleStatus.UnitStatus actor = unit(FIRST, OWN, true, true);
        GpuBattleStatus.UnitStatus ready = unit(SECOND, OWN, true, true);
        GpuBattleStatus.UnitStatus foe = unit(FOE, ENEMY, false, false);
        GpuBattleStatus.UnitStatus blip = unit(BLIP, ENEMY, false, false, 1, true);
        GpuBattleStatus.Snapshot moving = status(3, GamePhase.MOVEMENT, true, FIRST, 1, actor, ready, foe);
        update(moving, panels(move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(HEX, Entity.NONE, CTRL_DOWN_MASK);
        verify(moves).planTo(HEX, 0, true);
        click(HEX, SECOND, 0);
        verify(source).selectUnit(SECOND);
        click(HEX, FOE, 0);
        assertEquals(FOE, hud.state.inspected);
        // Selecting the acting unit again would reset its path (MovementDisplay.selectEntity clears it).
        click(HEX, FIRST, 0);
        assertEquals(Entity.NONE, hud.state.inspected, "selecting ends the inspection");
        click(HEX, Entity.NONE, CTRL_DOWN_MASK);
        verify(source, never()).measure(HEX, CTRL_DOWN_MASK, Float.NaN);
        // At the height the pointer shows (the user's decision of 2026-10-03), the terrain hit's world height.
        hud.boardClick(HEX, Entity.NONE, Input.Buttons.LEFT, ALT_DOWN_MASK, 100, 100, 3.5f);
        verify(source).measure(HEX, ALT_DOWN_MASK, 3.5f);
        // Ctrl on an own unit pins a waypoint in its hex instead of selecting it.
        update(moving, panels(move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(HEX, SECOND, CTRL_DOWN_MASK);
        verify(moves, times(3)).planTo(HEX, 0, true);
        verify(source, times(1)).selectUnit(anyInt());

        update(status(3, GamePhase.FIRING, true, FIRST, 1, actor, ready, foe, blip), GpuHudData.EMPTY, null);
        click(HEX, FOE, 0);
        verify(fire).focusTarget(TargetKey.unit(FOE));
        click(HEX, BLIP, 0);
        // The fire orders refuse a sensor contact with their toast (H19).
        verify(fire).focusTarget(TargetKey.unit(BLIP));
        click(HEX, SECOND, 0);
        click(HEX, FIRST, 0);
        verify(fire, times(1)).selectUnit(anyInt());
        verify(fire).selectUnit(SECOND);
        verify(fire, times(2)).focusTarget(any(TargetKey.class));

        GpuPhysicalOptions.Snapshot adjacent = new GpuPhysicalOptions.Snapshot(true, FIRST, Entity.NONE, List.of(FOE),
              List.of());
        update(status(3, GamePhase.PHYSICAL, true, FIRST, 1, actor, foe), panels(move(false, List.of()), adjacent),
              null);
        click(HEX, FOE, 0);
        verify(physical).target(FOE);

        update(status(3, GamePhase.DEPLOYMENT, true, FIRST, 1, actor), GpuHudData.EMPTY, null);
        click(HEX, Entity.NONE, 0);
        verify(source).hover(HEX, 0);
        verify(source).click(HEX, false, 0);

        // Outside the local turn an own unit becomes the shown unit, the focus (the user's decision of 2026-10-02):
        // MegaMek selects nothing then, and the orders stay as they are.
        update(status(3, GamePhase.MOVEMENT, false, Entity.NONE, 2, unit(FIRST, OWN, false, true),
              unit(SECOND, OWN, false, true)), panels(move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertEquals(FIRST, hud.state.focus());
        clearInvocations(moves, source);
        click(HEX, SECOND, 0);
        assertEquals(Entity.NONE, hud.state.inspected);
        assertEquals(SECOND, hud.state.focus());
        verify(source, never()).selectUnit(anyInt());
        hud.boardClick(HEX, SECOND, Input.Buttons.RIGHT, 0, 30, 40, Float.NaN);
        verifyNoInteractions(moves);
    }

    @Test
    void shiftClickOrientsThePlannedEndpointWithoutSelectingUnitsOrAddingWaypoints() {
        update(status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true), unit(FOE, ENEMY, false, false)),
              panels(move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        for (int target : new int[] { Entity.NONE, SECOND, FOE }) {
            click(HEX, target, SHIFT_DOWN_MASK);
        }
        verify(moves, times(3)).faceToward(HEX, 0);
        verify(moves, never()).planTo(any(), anyInt(), anyBoolean());
        verify(source, never()).selectUnit(anyInt());
        verify(source, never()).measure(any(), anyInt(), anyFloat());
    }

    /**
     * Movement the HUD does not plan (G20), as while the movement display picks a hex (E2d), keeps MegaMek's board
     * tool: a left click is its press and click, never a plan command.
     */
    @Test
    void aMovementClickTheHudDoesNotPlanGoesToMegaMeksBoardTool() {
        update(status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true)),
              panels(move(false, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(HEX, Entity.NONE, 0);
        verify(source).hover(HEX, 0);
        verify(source).click(HEX, false, 0);
        verifyNoInteractions(moves);
    }

    /**
     * While a bot order picks hexes (N7c), a left board click is MegaMek's board click, which the order's picker
     * takes, on a unit and in the planner's turn too; the Done key ends the pick with the order instead of the turn,
     * and Esc ends it without the order before any other step; the hint line shows the pick.
     */
    @Test
    void aBotOrdersHexPickTakesTheBoardClicksTheDoneKeyAndEsc() {
        GpuPlayers players = mock(GpuPlayers.class);
        when(source.players()).thenReturn(players);
        GpuPlayers.Pick pick = new GpuPlayers.Pick("Click hexes on the board: Strategic Target",
              "1 hex(es) selected: 0507");
        GpuHudData picking = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, move(true, List.of(STEP)),
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null, pick));
        GpuBattleStatus.Snapshot moving = status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true),
              unit(SECOND, OWN, true, true));
        update(moving, picking, null);
        click(HEX, Entity.NONE, 0);
        click(HEX, SECOND, 0);
        verify(source, times(2)).click(HEX, false, 0);
        verify(source, never()).hover(any(), anyInt());
        verify(source, never()).selectUnit(anyInt());

        assertTrue(press(Input.Keys.ENTER, KeyEvent.VK_ENTER, CTRL_DOWN_MASK), "the Done key");
        verify(players).endPick(true);
        assertTrue(escape());
        verify(players).endPick(false);
        verifyNoInteractions(moves);
        assertEquals(List.of(UiKit.text("GpuBoard.hud.mouse.leftClick"), pick.instructions() + " · " + pick.status(),
              "", UiKit.text("BotCommandPanel.HexPicker.done"), "", UiKit.text("BotCommandPanel.HexPicker.cancel")),
              GpuHintLine.items(new GpuHud.Inputs(frame(moving, picking), GpuHud.HudView.EMPTY, null, preferences(),
                    GpuHud.Metrics.of(1920, 1080), List.of())), "the pick's line; this test's keys have no text");
    }

    /**
     * A native LOS measurement waiting for its second point (Alt-click starts it)
     * takes a plain left click with its modifier, ahead of the phase's own gestures, and the hint line names it; with
     * none waiting, a plain click plans again.
     */
    @Test
    void aPlainClickEndsAMeasurementWaitingForItsSecondPoint() {
        GpuBattleStatus.Snapshot moving = status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true));
        for (int pending : new int[] { ALT_DOWN_MASK }) {
            GpuHudData waiting = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, move(true, List.of()),
                  GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
                  GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
                  new RulerModel.Snapshot(true, pending, null, null, 0, "", "", "", "", true, false, null, List.of()), GpuPlayers.Snapshot.EMPTY);
            update(moving, waiting, null);
            click(HEX, Entity.NONE, 0);
            verify(source).measure(HEX, pending, Float.NaN);
            assertEquals(UiKit.text("GpuBoard.hud.hint.completeLos"),
                  GpuHintLine.items(new GpuHud.Inputs(frame(moving, waiting), GpuHud.HudView.EMPTY, null,
                        preferences(), GpuHud.Metrics.of(1920, 1080), List.of())).get(1));
        }
        verifyNoInteractions(moves);
        update(moving, panels(move(true, List.of()), GpuPhysicalOptions.Snapshot.EMPTY), null);
        click(HEX, Entity.NONE, 0);
        verify(moves).planTo(HEX, 0, false);
    }

    /**
     * Where the window has no room for the hint line (W <= 1350), a pick shows in its chip on the dock: its
     * instructions, the picked hexes once there are some, and its Done and Cancel buttons, which end the pick with and
     * without the order. A panel of the middle area (the LOS card) ends above the chip, as above the dock. In a wider
     * window the hint line shows the pick and the chip is hidden, and without a pick there is no chip.
     */
    @Test
    void aPickShowsItsChipWithDoneAndCancelWhereTheHintLineIsHidden() {
        GpuPlayers players = mock(GpuPlayers.class);
        when(source.players()).thenReturn(players);
        GpuPlayers.Pick pick = new GpuPlayers.Pick("Click hexes on the board: Strategic Target",
              "1 hex(es) selected: 0507");
        GpuHudData picking = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, move(true, List.of(STEP)),
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null, pick));
        GpuBattleStatus.Snapshot moving = status(3, GamePhase.MOVEMENT, true, FIRST, 1, unit(FIRST, OWN, true, true));
        Actor chip = hud.stage.getRoot().findActor("pick-chip");
        Actor hint = hud.stage.getRoot().findActor("hint-line");

        hud.resize(1280, 720, 1);
        update(moving, picking, null);
        assertTrue(shown(chip), "the pick's chip in a window without the hint line");
        assertFalse(shown(hint));
        assertEquals(List.of(pick.instructions(), pick.status()), Stream.of("pick-instructions", "pick-status")
              .map(name -> hud.stage.getRoot().<Label>findActor(name).getText().toString()).toList());
        hud.stage.getRoot().findActor("pick-done").fire(new ChangeListener.ChangeEvent());
        verify(players).endPick(true);
        hud.stage.getRoot().findActor("pick-cancel").fire(new ChangeListener.ChangeEvent());
        verify(players).endPick(false);
        GpuPlayers.Pick started = new GpuPlayers.Pick(pick.instructions(), "");
        update(moving, new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, move(true, List.of(STEP)),
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null, started)), null);
        assertNull(hud.stage.getRoot().findActor("pick-status"), "no picked hexes, no second line");

        hud.resize(1920, 1080, 1);
        update(moving, picking, null);
        assertFalse(shown(chip), "the hint line shows the pick");
        assertTrue(shown(hint));
        hud.resize(1280, 720, 1);
        update(moving, panels(move(true, List.of(STEP)), GpuPhysicalOptions.Snapshot.EMPTY), null);
        assertFalse(shown(chip), "no pick, no chip");
    }

    /** Whether the actor and every group above it are visible. */
    private static boolean shown(Actor actor) {
        for (Actor current = actor; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void anAssignedOrClearedWeaponIsDisarmed() {
        update(status(3, GamePhase.FIRING, true, FIRST, 1, unit(FIRST, OWN, true, true), unit(FOE, ENEMY, false,
              false)), GpuHudData.EMPTY, null);
        hud.state.armedWeapon = 4;
        click(HEX, FOE, 0);
        verify(fire).assign(4, TargetKey.unit(FOE));
        assertEquals(-1, hud.state.armedWeapon);
        click(HEX, FOE, 0);
        verify(fire, times(2)).focusTarget(TargetKey.unit(FOE));
        verify(fire, times(1)).assign(anyInt(), any(TargetKey.class));
        // A hex: MegaMek's click chooses the target there, a wooded hex or a building too, and the armed weapon fires
        // at it (the user's report of 2026-10-03: terrain kept "Hold fire").
        hud.state.armedWeapon = 6;
        click(HEX, Entity.NONE, 0);
        verify(fire).clickHex(HEX, 0, 6);
        assertEquals(-1, hud.state.armedWeapon);
        verify(source, never()).click(any(), anyBoolean(), anyInt());

        hud.state.armedWeapon = 5;
        assertTrue(press(Input.Keys.FORWARD_DEL, KeyEvent.VK_DELETE, 0));
        verify(fire).clearAll();
        assertEquals(-1, hud.state.armedWeapon);
    }

    private boolean press(int key, int awt, int modifiers) {
        return hud.keyDown(key, awt, modifiers);
    }

    private boolean escape() {
        return press(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
    }

    private void click(Coords coords, int unitId, int modifiers) {
        hud.boardClick(coords, unitId, Input.Buttons.LEFT, modifiers, 100, 100, Float.NaN);
    }

    private void update(GpuBattleStatus.Snapshot status) {
        update(status, GpuHudData.EMPTY, null);
    }

    private void update(GpuBattleStatus.Snapshot status, GpuHudData panels, GpuBoardWindow.DialogRequest dialog) {
        hud.update(frame(status, panels), GpuHud.HudView.EMPTY, dialog, preferences());
    }

    /** A frame of the given status and panels; the scene is a mock, so board clicks are routed. */
    static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuHudData panels) {
        return new GpuBoardSource.Frame(mock(BoardScene.class), List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, panels);
    }

    /** MegaMek's default key of every bind, so the user's own key bindings do not change the test. */
    static GpuBoardSource.UiPreferences preferences() {
        return preferences(1);
    }

    /** {@link #preferences()} with the GUI scale {@code scale}. */
    static GpuBoardSource.UiPreferences preferences(float scale) {
        List<GpuBoardSource.Bind> binds = Stream.of(KeyCommandBind.values())
              .map(bind -> new GpuBoardSource.Bind(bind, bind.keyDefault, bind.modifiersDefault, "")).toList();
        return new GpuBoardSource.UiPreferences(scale, "", "", true, true, false, false, binds, 0);
    }

    static GpuBattleStatus.Snapshot status(int round, GamePhase phase, boolean myTurn, int actor, int turnIndex,
          GpuBattleStatus.UnitStatus... units) {
        return new GpuBattleStatus.Snapshot(round, phase, myTurn, 1, actor, List.of(), turnIndex, List.of(units),
              List.of(), false);
    }

    /** The panel bundle of the given snapshots, with the others empty. */
    static GpuHudData panels(GpuMovePlan.Snapshot move, GpuFireOrders.Snapshot fire,
          GpuPhysicalOptions.Snapshot physical, GpuUnitRecord.Snapshot record) {
        return new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, move, fire, physical, record,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
    }

    private static GpuHudData panels(GpuMovePlan.Snapshot move, GpuPhysicalOptions.Snapshot physical) {
        return panels(move, GpuFireOrders.Snapshot.EMPTY, physical, GpuUnitRecord.Snapshot.EMPTY);
    }

    private static GpuHudData panels(GpuUnitRecord.Snapshot record) {
        return panels(GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY,
              record);
    }

    static GpuBattleStatus.UnitStatus unit(int id, GpuBattleStatus.Side side, boolean canActNow,
          boolean pending) {
        return unit(id, side, canActNow, pending, 1);
    }

    private static GpuBattleStatus.UnitStatus unit(int id, GpuBattleStatus.Side side, boolean canActNow,
          boolean pending, double armor) {
        return unit(id, side, canActNow, pending, armor, false);
    }

    static GpuBattleStatus.UnitStatus unit(int id, GpuBattleStatus.Side side, boolean canActNow,
          boolean pending, double armor, boolean contact) {
        return new GpuBattleStatus.UnitStatus(id, side, contact, "Unit " + id, "", "", 0, "", "", "", 0, 0, armor, 1,
              0, 0, "", 0, "", 0, "", 0, 0, 0, 0, canActNow, pending, !pending, false, Entity.DMG_NONE, List.of(), "",
              new Coords(id, id), 0, null, List.of(), 0, 0, Player.PLAYER_NONE, List.of());
    }

    static GpuMovePlan.Snapshot move(boolean planner, List<GpuMovePlan.Step> route) {
        return new GpuMovePlan.Snapshot(true, planner, false, FIRST, GpuMovePlan.Mode.AUTO, false, "", route,
              List.of(), List.of(), route.isEmpty() ? null : HEX, 0, route.size(), 4, EntityMovementType.MOVE_WALK, "",
              true, 0, 0, true, List.of(), !route.isEmpty(), !route.isEmpty(), Map.of(), 0);
    }

    private static GpuUnitRecord.Snapshot record(int unitId) {
        return new GpuUnitRecord.Snapshot(unitId, true, false, "", "", List.of(), List.of(), List.of(), List.of(),
              List.of(), List.of(), 0, new WeaponDisplayData.HeatBuildup(0, 0, ""), List.of(), List.of(), List.of(),
              List.of(), List.of(), List.of(), List.of(), List.of(), "", "", List.of(), List.of(), "");
    }

    private static GpuBoardWindow.DialogRequest message() {
        return new GpuBoardWindow.DialogRequest(1, GpuBoardWindow.DialogKind.MESSAGE, "Title", "Message", false,
              List.of("OK", "Cancel"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of());
    }

    /** A real TextField whose font has glyphs for the digits and the space only, enough without a GL context. */
    private static TextField textField() {
        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData();
        for (char character : " 0123456789".toCharArray()) {
            BitmapFont.Glyph glyph = new BitmapFont.Glyph();
            glyph.id = character;
            glyph.width = 6;
            glyph.height = 10;
            glyph.xadvance = 7;
            data.setGlyph(character, glyph);
        }
        Texture page = mock(Texture.class);
        when(page.getWidth()).thenReturn(64);
        when(page.getHeight()).thenReturn(64);
        BitmapFont font = new BitmapFont(data, new TextureRegion(page), false);
        return new TextField("", new TextField.TextFieldStyle(font, Color.WHITE, null, null, null));
    }
}
