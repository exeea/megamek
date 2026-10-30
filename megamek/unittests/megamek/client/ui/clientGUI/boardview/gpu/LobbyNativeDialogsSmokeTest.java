/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyPrompts;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.containers.MunitionTree;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.loaders.MapSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the lobby's libGDX dialogs through the stage, as a pointer and keyboard would: the kit's dialog blocks the
 * lobby behind it and answers Enter, Escape and Tab; the camo chooser hands back the clicked camo; the player settings
 * refuse a bad number and send a chosen start position.
 */
@Tag("on-demand")
class LobbyNativeDialogsSmokeTest {

    @Test
    void theDialogBlocksWhatIsBehindItAndAnswersTheKeys() {
        UiTestStage.run(ui -> {
            int[] behind = { 0 };
            Actor lobby = new Actor();
            lobby.setBounds(0, 0, ui.stage.getWidth(), ui.stage.getHeight());
            lobby.setTouchable(Touchable.enabled);
            lobby.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    behind[0]++;
                }
            });
            ui.window.addActor(lobby);
            List<String> said = new ArrayList<>();
            UiDialog dialog = new UiDialog(ui.kit, "Test", () -> said.add("cancel")).width("dialog.sm");
            dialog.body().add(new Actor()).size(200, 40);
            dialog.primary("OK", () -> said.add("ok"));
            Button cancel = dialog.cancelButton("Cancel");
            dialog.show(ui.stage, false);
            ui.draw();
            assertTrue(dialog.isOpen());

            clickAt(ui, 4, 4);
            assertEquals(0, behind[0], "the lobby behind takes no click");
            assertTrue(dialog.isOpen(), "a click on the scrim does not dismiss a dialog that wants an answer");

            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals(List.of("ok"), said, "Enter presses OK");

            for (int i = 0; i < 6; i++) {
                ui.stage.keyDown(Input.Keys.TAB);
                Actor focus = ui.stage.getKeyboardFocus();
                assertNotNull(focus);
                assertTrue(focus.isDescendantOf(dialog.card()), "Tab stays in the dialog");
            }
            ui.stage.setKeyboardFocus(cancel);
            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals("cancel", said.getLast(), "Enter presses the focused button");
            ui.stage.keyDown(Input.Keys.ESCAPE);
            assertEquals(List.of("ok", "cancel", "cancel"), said, "Escape cancels");

            dialog.close();
            ui.draw();
            assertFalse(dialog.isOpen());
            click(ui, lobby);
            assertEquals(1, behind[0], "the lobby takes clicks again once it closes");
        });
    }

    @Test
    void theCamoChooserHandsBackTheClickedCamo() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            CamoCatalog catalog = new CamoCatalog(null, List.of("BLUE", "CYAN", "RED"));
            BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            LobbyCamoView.Source source = new LobbyCamoView.Source() {
                @Override
                public CamoCatalog catalog(boolean refresh) {
                    return catalog;
                }

                @Override
                public BufferedImage thumbnail(Camouflage camo, int size) {
                    return picture;
                }

                @Override
                public List<BufferedImage> preview(Camouflage camo) {
                    return List.of(picture);
                }
            };
            List<Camouflage> chosen = new ArrayList<>();
            CamoChoice choice = new CamoChoice(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "CYAN"), false);
            LobbyCamoView view = dialogs.camo(source, catalog, choice, false, chosen::add);
            await(ui, () -> view.previewShown() && view.item(0) != null);
            assertTrue(dialogs.isOpen());
            assertEquals(3, view.items().size(), "the colour camos show, as the initial camo's category");

            click(ui, view.item(0));
            assertEquals(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "BLUE"), view.choice().selected());
            click(ui, ui.stage.getRoot().findActor("camo-ok"));
            assertFalse(dialogs.isOpen(), "OK closes the chooser");
            assertEquals(1, chosen.size());
            assertEquals("BLUE", chosen.getFirst().getFilename());
            dialogs.dispose();
        });
    }

    @Test
    void thePlayerSettingsRefuseABadNumberAndSendTheStartPosition() {
        UiTestStage.run(ui -> {
            Game game = new Game();
            Player local = new Player(1, "Local");
            game.addPlayer(1, local);
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(local);
            MapSettings settings = MapSettings.getInstance();
            settings.setBoardSize(16, 17);
            when(client.getMapSettings()).thenReturn(settings);
            when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
            PlayerSettingsForm form = new PlayerSettingsForm(client,
                  new LobbyCommands(() -> client, mock(LobbyPrompts.class)), board(),
                  List.of(new PlayerSettingsForm.Faction("IS", "Inner Sphere")));
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            dialogs.playerSettings(form, form.positions(), null, new LobbyPlayerSettingsView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public void saveLoadout(MunitionTree tree) {
                }

                @Override
                public MunitionTree loadLoadout() {
                    return null;
                }

                @Override
                public void botSettings() {
                }
            });
            ui.draw();
            Actor start = ui.stage.getRoot().findActor("settings-start-5");
            reveal(ui, start);
            click(ui, start);
            assertEquals(5, form.startPos());

            Actor offset = ui.stage.getRoot().findActor("settings-offset");
            reveal(ui, offset);
            click(ui, offset);
            for (int i = 0; i < 4; i++) {
                ui.stage.keyTyped('\b');
            }
            ui.draw();
            Button ok = ui.stage.getRoot().findActor("settings-ok");
            assertTrue(ok.isDisabled(), "an empty number disables OK");
            click(ui, ok);
            verify(client, never()).sendPlayerInfo(local);

            ui.stage.setKeyboardFocus(offset);
            ui.stage.keyTyped('2');
            ui.draw();
            assertFalse(ok.isDisabled());
            click(ui, ok);
            verify(client).sendPlayerInfo(local);
            assertEquals(5, local.getStartingPos());
            assertEquals(2, local.getStartOffset());
            assertFalse(dialogs.isOpen(), "OK closes the settings");
            dialogs.dispose();
        });
    }

    /** Draws frames until {@code done}, as the camo pictures arrive from their thread, three seconds at most. */
    private static void await(UiTestStage ui, BooleanSupplier done) throws InterruptedException {
        for (int frame = 0; frame < 300 && !done.getAsBoolean(); frame++) {
            ui.draw();
            Thread.sleep(10);
        }
        ui.draw();
        assertTrue(done.getAsBoolean(), "the dialog's pictures arrived");
    }

    /** Scrolls the dialog's body to {@code actor}, as the probe does. */
    private static void reveal(UiTestStage ui, Actor actor) {
        UiDialog dialog = ui.stage.getRoot().findActor("dialog") == null ? null : null;
        Actor scroll = actor;
        while (scroll != null && !(scroll instanceof ScrollPane)) {
            scroll = scroll.getParent();
        }
        if (scroll instanceof ScrollPane pane) {
            Vector2 spot = actor.localToAscendantCoordinates(pane.getActor(), new Vector2());
            pane.scrollTo(spot.x, spot.y, actor.getWidth(), actor.getHeight(), false, true);
            pane.updateVisualScroll();
        }
        ui.draw();
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        clickAt(ui, Math.round(point.x), Math.round(point.y));
    }

    private static void clickAt(UiTestStage ui, int x, int y) {
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static Board board() {
        Hex[] hexes = new Hex[16 * 17];
        for (int i = 0; i < hexes.length; i++) {
            hexes[i] = new Hex();
        }
        return new Board(16, 17, hexes);
    }
}
