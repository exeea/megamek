/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.started;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuChoiceRoutingTest.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.present;
import static megamek.common.equipment.enums.BombType.BombTypeEnum.CLUSTER;
import static megamek.common.equipment.enums.BombType.BombTypeEnum.HE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import javax.swing.JFrame;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.dialogs.phaseDisplay.BombPayloadDialog;
import megamek.client.ui.dialogs.phaseDisplay.BuildingFacingDialog;
import megamek.client.ui.dialogs.phaseDisplay.MineLayingDialog;
import megamek.client.ui.dialogs.phaseDisplay.SuicideImplantsDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiPopover;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The rare in-battle dialogs of swing-inventory table 3 (R1, R4) in the real native modal over the real bridge: the
 * bomb and implant forms come back narrowed as their Swing dialogs when the player picks in their lists, the facing
 * list skips the facings that do not fit, and every answer reaches the Swing dialog's result. Writes n7a-*.png.
 */
@Tag("on-demand")
class GpuRareDialogSmokeTest {
    private static final long WAIT_SECONDS = 20;

    @Test
    void rareDialogsDrawInTheNativeModalAndReachTheirSwingResults() throws Exception {
        ClientGUI gui = GpuImplantBlowFacingRoutingTest.previewingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            Entity platoon = GpuImplantBlowFacingRoutingTest.platoon();
            Entity tank = GpuBombMineDialogRoutingTest.minelayer();
            onSwing(() -> {
                fixture.entity.setFacing(1);
                return null;
            });
            try {
                GpuHudTestStage.run(harness -> {
                    GpuModalDialog modal = new GpuModalDialog(harness.kit, fixture.source,
                          new GpuHudState(new GpuPlaybackHistory(new UnitPlayback())));
                    Actor root = modal.actor();
                    root.setTouchable(Touchable.enabled);
                    harness.window.addActor(root);
                    root.setBounds(0, 0, harness.width(), harness.height());

                    // A fighter may drop two bombs: one HE bomb picked leaves one cluster bomb and no third bomb
                    FutureTask<List<Object>> bombs = started(() -> GpuBombMineDialogRoutingTest.payload(
                          new BombPayloadDialog(frame, "Bomb Payload, internal", GpuBombMineDialogRoutingTest.loadout(),
                                false, false, 2, 0)));
                    DialogRequest form = show(modal, harness, fixture.source, null);
                    harness.capture("n7a-bomb-form").dispose();
                    pick(harness, root, 0, "1");
                    DialogRequest narrowed = show(modal, harness, fixture.source, form);
                    assertEquals(List.of(List.of("0", "1", "2"), List.of("0", "1"), List.of("0", "1")),
                          GpuBombMineDialogRoutingTest.choices(narrowed));
                    pick(harness, root, 1, "1");
                    DialogRequest full = show(modal, harness, fixture.source, narrowed);
                    assertEquals(List.of("1", "1", "0"), faces(root), "The form shows the picks");
                    harness.capture("n7a-bomb-narrowed").dispose();
                    click(harness.stage, button(root, "Okay"));
                    assertEquals(Arrays.asList(true, Map.of(HE, 1, CLUSTER, 1)), bombs.get(WAIT_SECONDS, SECONDS));
                    assertEquals(List.of(List.of("0", "1"), List.of("0", "1"), List.of("0")),
                          GpuBombMineDialogRoutingTest.choices(full));

                    // Twenty-eight troopers: picking ten moves the damage line as the Swing slider does
                    FutureTask<List<Object>> implants = started(() -> GpuImplantBlowFacingRoutingTest.implants(
                          new SuicideImplantsDialog(frame, platoon)));
                    DialogRequest troopers = show(modal, harness, fixture.source, full);
                    harness.capture("n7a-implants-form").dispose();
                    pick(harness, root, 0, "10");
                    DialogRequest ten = show(modal, harness, fixture.source, troopers);
                    assertEquals("WARNING: This action cannot be undone!\nDamage to all units in hex: 6",
                          ten.message());
                    harness.capture("n7a-implants-10").dispose();
                    click(harness.stage, button(root, "Okay"));
                    assertEquals(List.of(true, 10), implants.get(WAIT_SECONDS, SECONDS));

                    // The building faces north-east; Down skips the south, which does not fit
                    FutureTask<List<Object>> facing = started(() -> GpuImplantBlowFacingRoutingTest.facing(
                          new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4))));
                    DialogRequest facings = show(modal, harness, fixture.source, ten);
                    harness.capture("n7a-facing").dispose();
                    harness.stage.keyDown(Input.Keys.DOWN);
                    harness.stage.keyDown(Input.Keys.DOWN);
                    harness.stage.keyDown(Input.Keys.ENTER);
                    assertEquals(List.of(DialogResult.CONFIRMED, 4), facing.get(WAIT_SECONDS, SECONDS));

                    FutureTask<List<Object>> mine = started(() -> GpuBombMineDialogRoutingTest.mine(
                          new MineLayingDialog(frame, tank)));
                    show(modal, harness, fixture.source, facings);
                    harness.capture("n7a-mine").dispose();
                    // Esc, which the HUD hands to the modal's cancel
                    modal.cancel();
                    assertEquals(List.of(false), mine.get(WAIT_SECONDS, SECONDS), "Esc lays no mine");
                });
            } finally {
                dismiss();
                GpuBombMineDialogRoutingTest.dispose(frame);
            }
        }
    }

    /**
     * GL thread: waits for the bridge's request after {@code previous} (any for null), then shows it in the modal and
     * draws two frames, the first of which lays the dialog out.
     */
    private static DialogRequest show(GpuModalDialog modal, GpuHudTestStage harness, GpuBoardSource source,
          DialogRequest previous) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
        DialogRequest shown = source.dialog();
        while ((shown == null) || ((previous != null) && (shown.id() == previous.id()))) {
            if (System.nanoTime() > deadline) {
                return fail("No new dialog after " + previous);
            }
            Thread.sleep(5);
            shown = source.dialog();
        }
        GpuHud.Inputs inputs = new GpuHud.Inputs(null, GpuHud.HudView.EMPTY, shown, GpuHudInputTest.preferences(),
              GpuHud.Metrics.of(harness.width(), harness.height()), List.of());
        modal.update(inputs);
        harness.draw();
        modal.update(inputs);
        harness.draw();
        return shown;
    }

    /** Opens the form's {@code field}-th choice and picks {@code entry} in its list, as the player clicks. */
    private static void pick(GpuHudTestStage harness, Actor root, int field, String entry) {
        click(harness.stage, selects(root).get(field));
        harness.draw();
        UiPopover list = all(root, UiPopover.class).getFirst();
        click(harness.stage, button(list, entry));
    }

    /** The texts the form's choice faces show. */
    private static List<String> faces(Actor root) {
        return selects(root).stream().map(face -> face.getText().toString()).toList();
    }

    /** The choice faces of the form: its buttons outside the button row and the open list. */
    private static List<UiButton> selects(Actor root) {
        return all(root, UiButton.class).stream()
              .filter(button -> !List.of("OKAY", "CANCEL").contains(button.getText().toString().toUpperCase()))
              .filter(button -> all(root, UiPopover.class).stream().noneMatch(button::isDescendantOf))
              .filter(button -> button.getText().length() > 0).toList();
    }

    /** A left click in the middle of the actor, through the stage as the window delivers it. */
    private static void click(Stage stage, Actor actor) {
        Vector2 point = stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    /** The button with this caption; the dialog's buttons show theirs in capitals. */
    private static UiButton button(Actor actor, String text) {
        return all(actor, UiButton.class).stream().filter(button -> button.getText().toString().equalsIgnoreCase(text))
              .findFirst().orElseThrow(() -> new AssertionError("No button " + text));
    }

    /** Every actor of the type at or below {@code actor}, depth first. */
    private static <T extends Actor> List<T> all(Actor actor, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(actor)) {
            found.add(type.cast(actor));
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> found.addAll(all(child, type)));
        }
        return found;
    }
}
