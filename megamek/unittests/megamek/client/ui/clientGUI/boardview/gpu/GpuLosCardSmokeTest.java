/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuLosResultTest.blocked;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuLosResultTest.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.PlayerColour;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The LOS card (C.1 G18, plan A.15 O7) in the real HUD over a mocked source whose LOS service records every command:
 * a fixture card of MegaMek's ruler result beside the right column at 1920 x 1080, 1280 x 720 and 900 x 600, beside
 * the card the mock puts at the same anchor (shot 15's solution card); the height steps post the ruler's remeasure
 * and stop at its bounds; the close button and Esc close the card through the service; the LOS settings key opens it.
 */
@Tag("on-demand")
class GpuLosCardSmokeTest {
    private static final Coords OWN = new Coords(5, 5);
    private static final Coords ENEMY = new Coords(5, 3);
    /**
     * The ruler's result from an own Atlas at 0606 to a prone enemy Atlas at 0604 across one light-woods hex, as
     * GpuLosResultTest measures it on a real board.
     */
    private static final GpuLosResult.Card CARD = new GpuLosResult.Card(1, OWN, 1, 2, false, ENEMY, 2, 1, false, 2,
          seen(2, "1 (1 intervening light woods) + 1 (target prone (range))"),
          seen(1, "1 (1 intervening light woods)"));
    private static final String DOT = " \u00B7 ";

    /** The real HUD over a mocked source, one stage unit per back-buffer pixel at the harness window's size. */
    private static final class Hud implements AutoCloseable {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuLosResult los = mock(GpuLosResult.class);
        final SpriteBatch batch = new SpriteBatch();
        final GpuHudTestStage harness;
        final GpuHud hud;

        Hud(GpuHudTestStage harness) {
            this.harness = harness;
            when(source.moves()).thenReturn(mock(GpuMovePlan.class));
            when(source.fire()).thenReturn(mock(GpuFireOrders.class));
            when(source.physical()).thenReturn(mock(GpuPhysicalOptions.class));
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            when(source.chat()).thenReturn(mock(GpuChat.class));
            when(source.los()).thenReturn(los);
            hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(), mock(GpuBoardTuning.class),
                  new GpuPlaybackHistory(new UnitPlayback()));
            resize();
        }

        /** Follows the harness's emulated window. */
        void resize() {
            float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
            hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density), 1 / density);
        }

        /**
         * Two frames of the status and the LOS card (null: closed), each drawn: a wrapped row knows its height once
         * the previous layout gave it its width, as from one frame to the next in the game.
         */
        void show(GpuBattleStatus.Snapshot status, GpuLosResult.Card card) {
            GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
                  GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
                  GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
                  new GpuLosResult.Snapshot(card), GpuPlayers.Snapshot.EMPTY);
            GpuBoardSource.Frame frame = GpuHudInputTest.frame(status, panels);
            for (int pass = 0; pass < 2; pass++) {
                hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudInputTest.preferences());
                ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                hud.draw();
            }
        }

        /** A key press and release as the board view passes them on; true when the HUD consumed the press. */
        boolean key(int key, int awt, int modifiers) {
            boolean consumed = hud.keyDown(key, awt, modifiers);
            hud.keyUp(key, awt);
            return consumed;
        }

        /** A left click in the middle of the named actor, through the HUD's stage as the window delivers it. */
        void click(String name) {
            Actor actor = find(name);
            Vector2 point = hud.stage.stageToScreenCoordinates(actor.localToStageCoordinates(
                  new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
            hud.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
            hud.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        }

        <T extends Actor> T find(String name) {
            return hud.stage.getRoot().findActor(name);
        }

        @Override
        public void close() {
            hud.dispose();
            batch.dispose();
        }
    }

    /**
     * The card's anchor (plan C.1 G18): its right edge 12 left of the right column, top 90, 300 wide; each height
     * row shows its hex and height, then the range and the ruler's two views with MegaMek's modifier text. Rendered
     * at the three window sizes of E rule 6, where it lies inside the window, clear of every other shown panel.
     */
    @Test
    void theFixtureCardShowsTheRulerResultBesideTheRightColumn() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show(GpuHudFixtures.status(), null);
                assertFalse(shown(hud.find("los-card")), "no card while the service has none open");
                // Right edges: 1920 - 20 - 310 - 12, then the narrow metrics' W - 16 - 270 - 12.
                int[][] sizes = { { 1920, 1080, 1578 }, { 1280, 720, 982 }, { 900, 600, 602 } };
                for (int[] size : sizes) {
                    harness.size(size[0], size[1]);
                    hud.resize();
                    hud.show(GpuHudFixtures.status(), CARD);
                    Actor card = hud.find("los-card");
                    String name = "los-card-" + size[0] + "x" + size[1];
                    assertTrue(shown(card), name);
                    UiTestStage.assertTexts(card);
                    Rectangle area = GpuHudTestStage.bounds(card);
                    System.out.println(name + " at " + area);
                    assertEquals(size[2], area.x + area.width, .01f, name + ": right gap + R + 12");
                    assertEquals(300, area.width, .01f, name);
                    assertEquals(90, size[1] - area.y - area.height, .01f, name + ": top 90");
                    harness.assertLayout(card, others(hud, area));
                    ScrollPane body = hud.find("los-card-body");
                    assertFalse(body.isScrollY(), name + ": the rows fit without scrolling");
                    Pixmap shot = UiTestStage.capture(name, harness.width(), harness.height());
                    try {
                        if (size[0] == 1280) {
                            // Shot 15 shows the solution card beside the right column at this anchor.
                            UiTestStage.compare(name, shot, card, "15-compact-1280x720.jpg", 682, 90);
                        } else if (size[0] == 1920) {
                            UiTestStage.compare(name + "-05", shot, card, "05-weapon-declaration.jpg", 1590, 880);
                        }
                    } finally {
                        shot.dispose();
                    }
                }
                assertEquals(List.of("LINE OF SIGHT", "From 0606" + DOT + "height 2", "\u2212", "+",
                      "To 0604" + DOT + "height 1", "\u2212", "+", "Range 2 hexes",
                      "Attacker's view: 2 = 1 (1 intervening light woods) + 1 (target prone (range))",
                      "Target's view: 1 = 1 (1 intervening light woods)", "ELEVATION DIAGRAM"),
                      texts(hud.find("los-card")));
            }
        });
    }

    /**
     * Each step posts the ruler's remeasure of the shown card with one end's height one lower or higher (the Swing
     * ruler's spinners); at the spinners' bounds the outward step is off. The close button posts the service's close,
     * the Elevation diagram button its diagram.
     */
    @Test
    void theHeightStepsRemeasureOneEndAndStopAtTheRulerBounds() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show(GpuHudFixtures.status(), CARD);
                hud.click("los-from-lower");
                hud.click("los-from-raise");
                hud.click("los-to-lower");
                hud.click("los-to-raise");
                InOrder order = inOrder(hud.los);
                order.verify(hud.los).measure(OWN, ENEMY, 1, 1);
                order.verify(hud.los).measure(OWN, ENEMY, 3, 1);
                order.verify(hud.los).measure(OWN, ENEMY, 2, 0);
                order.verify(hud.los).measure(OWN, ENEMY, 2, 2);
                verifyNoMoreInteractions(hud.los);

                // The EDT publishes the remeasured card; at the bounds the outward steps post nothing.
                GpuLosResult.Card bounds = new GpuLosResult.Card(2, OWN, 1, RulerDialog.MAX_HEIGHT, false, ENEMY, 2,
                      RulerDialog.MIN_HEIGHT, false, 2, seen(0, ""), blocked("LOS blocked by terrain."));
                hud.show(GpuHudFixtures.status(), bounds);
                // A view without a line of sight reads in coral; a view with one keeps the rows' colour.
                assertEquals(UiTheme.ACCENT, hud.<Label>find("los-attacker-view").getColor());
                assertEquals(UiTheme.CORAL, hud.<Label>find("los-target-view").getColor());
                assertTrue(hud.<UiButton>find("los-from-raise").isDisabled());
                assertTrue(hud.<UiButton>find("los-to-lower").isDisabled());
                assertFalse(hud.<UiButton>find("los-from-lower").isDisabled());
                assertFalse(hud.<UiButton>find("los-to-raise").isDisabled());
                clearInvocations(hud.los);
                hud.click("los-from-raise");
                hud.click("los-to-lower");
                verifyNoInteractions(hud.los);
                hud.click("los-from-lower");
                hud.click("los-to-raise");
                order = inOrder(hud.los);
                order.verify(hud.los).measure(OWN, ENEMY, RulerDialog.MAX_HEIGHT - 1, RulerDialog.MIN_HEIGHT);
                order.verify(hud.los).measure(OWN, ENEMY, RulerDialog.MAX_HEIGHT, RulerDialog.MIN_HEIGHT + 1);
                assertEquals("From 0606" + DOT + "height 200", rowText(hud, "los-from"));
                assertEquals("To 0604" + DOT + "height -100", rowText(hud, "los-to"));

                hud.click("los-close");
                verify(hud.los).closeCard();
                // User item 6: the ruler's elevation diagram, through the service.
                hud.click("los-diagram");
                verify(hud.los).showDiagram();
                verifyNoMoreInteractions(hud.los);

                // An aerospace unit's end reads as its altitude.
                hud.show(GpuHudFixtures.status(), new GpuLosResult.Card(3, OWN, 1, 6, true, ENEMY, 2, 1, false, 2,
                      seen(0, ""), seen(0, "")));
                assertEquals("From 0606" + DOT + "altitude 6", rowText(hud, "los-from"));
                assertEquals("To 0604" + DOT + "height 1", rowText(hud, "los-to"));
                UiTestStage.assertTexts(hud.find("los-card"));
            }
        });
    }

    /**
     * Esc closes the open card through the service, as one step of the Esc chain, and is not taken by a closed card;
     * the LOS settings key (L) opens the card of the ruler's measurement, whether a card is open or not.
     */
    @Test
    void escapeClosesTheOpenCardAndTheLosSettingsKeyOpensIt() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                hud.show(moving, CARD);
                assertTrue(hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0), "the open card takes the Esc");
                verify(hud.los).closeCard();
                // The EDT publishes the closed card; nothing else is open in the movement phase, so the next Esc is
                // the phase display's CANCEL.
                hud.show(moving, null);
                assertFalse(shown(hud.find("los-card")));
                assertFalse(hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0));
                verify(hud.los).closeCard();

                assertTrue(hud.key(Input.Keys.L, KeyEvent.VK_L, 0), "L is View > LOS settings");
                verify(hud.los).open();
                hud.show(moving, CARD);
                assertTrue(hud.key(Input.Keys.L, KeyEvent.VK_L, 0));
                verify(hud.los, times(2)).open();
                // Ctrl+L is another bind (LOCAL_LOAD), which the HUD leaves to Swing.
                assertFalse(hud.key(Input.Keys.L, KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK));
                verify(hud.los, times(2)).open();
            }
        });
    }

    /**
     * Where a panel above it leaves the card less height than its rows need, the rows scroll under the header and
     * the card stays between that panel and the dock: the initiative report, whose initiative card (the client's
     * form, a roll line per side) it stacks below, at 1280 x 720 and at the production minimum of 960 x 640. At
     * 900 x 600 GpuHud leaves it no height at all; that capture is printed, not asserted (stage-G18.md, limits).
     */
    @Test
    void aSqueezedCardScrollsItsRowsAboveTheDock() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                List<GpuBattleStatus.InitiativeSide> sides = List.of(
                      new GpuBattleStatus.InitiativeSide("Player", PlayerColour.BLUE.getColour().getRGB(),
                            GpuBattleStatus.Side.OWN, 3, 3, 0, List.of(), List.of(),
                            List.of(GpuHudFixtures.LOCAL_PLAYER)),
                      new GpuBattleStatus.InitiativeSide("Princess", PlayerColour.RED.getColour().getRGB(),
                            GpuBattleStatus.Side.ENEMY, 10, 10, 0, List.of(), List.of(),
                            List.of(GpuHudFixtures.ENEMY_PLAYER)));
                GpuBattleStatus.Snapshot initiative = new GpuBattleStatus.Snapshot(moving.round(),
                      GamePhase.INITIATIVE_REPORT, false, moving.localPlayerId(), moving.actorId(), moving.turns(), 0,
                      moving.units(), sides, false);
                for (int[] size : new int[][] { { 1280, 720 }, { 960, 640 }, { 900, 600 } }) {
                    harness.size(size[0], size[1]);
                    hud.resize();
                    hud.show(initiative, CARD);
                    Actor card = hud.find("los-card");
                    Rectangle area = GpuHudTestStage.bounds(card);
                    Rectangle above = GpuHudTestStage.bounds(hud.find("initiative-card"));
                    String name = "los-card-initiative-" + size[0] + "x" + size[1];
                    System.out.println(name + " at " + area + ", slot height " + card.getParent().getHeight()
                          + ", initiative card at " + above + ", dock at "
                          + GpuHudTestStage.bounds(hud.find("command-dock")));
                    UiTestStage.capture(name, harness.width(), harness.height()).dispose();
                    if (size[0] > 900) {
                        assertTrue(shown(card), name);
                        assertEquals(above.y - 12, area.y + area.height, .01f, name + ": 12 below the initiative card");
                        assertTrue(((ScrollPane) hud.find("los-card-body")).isScrollY(), name + ": the rows scroll");
                        harness.assertLayout(card, others(hud, area));
                    }
                }
            }
        });
    }

    /** The shown panels' bounds other than {@code area}. */
    private static Rectangle[] others(Hud hud, Rectangle area) {
        return hud.hud.panelBounds().stream().filter(other -> !other.equals(area)).toArray(Rectangle[]::new);
    }

    /** The text of a height row's label. */
    private static String rowText(Hud hud, String row) {
        return texts(hud.find(row)).getFirst();
    }

    private static boolean shown(Actor actor) {
        for (Actor node = actor; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return actor.getStage() != null;
    }

    /** The non-empty label texts at or below {@code actor}, depth first. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        collect(actor, texts);
        return texts;
    }

    private static void collect(Actor actor, List<String> texts) {
        if (actor instanceof Label label && label.getText().length() > 0) {
            texts.add(label.getText().toString());
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, texts));
        }
    }
}
