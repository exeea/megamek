/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;

/**
 * Hint line under the dock: the mouse gestures of the phase and the view, with the current keys (C.1 G4, L11). In
 * windows too narrow for the line (W <= 1350) a bot order's hex pick shows in its chip instead, with its Done and
 * Cancel buttons.
 */
final class GpuHintLine implements GpuHud.Component {
    /** The line's text (#hint); the gesture names are white (#hint b). */
    private static final Color TEXT = new Color(236 / 255f, 240 / 255f, 238 / 255f, .75f);
    /** The line's text shadow (0 1px 3px rgba(0,0,0,.8)), drawn one unit lower. */
    private static final Color SHADOW = new Color(0, 0, 0, .8f);
    private static final float SIZE = 11;
    private static final float ITEM_GAP = 16;
    private static final float NAME_GAP = 5;

    private final UiKit ui;
    private final Table root = new Table() {
        @Override
        protected void drawChildren(Batch batch, float parentAlpha) {
            for (Actor child : getChildren()) {
                child.moveBy(0, -1);
                child.setColor(SHADOW);
            }
            super.drawChildren(batch, parentAlpha);
            for (Actor child : getChildren()) {
                child.moveBy(0, 1);
                child.setColor(Color.WHITE);
            }
            super.drawChildren(batch, parentAlpha);
        }
    };
    /** Gesture and action texts, alternating, of the line on screen. */
    private List<String> shown = List.of();
    /**
     * The pick's chip: its instructions, the picked hexes once there are some, Done and Cancel, as the classic board's
     * picker dialog shows them.
     */
    private final Table chip;
    private final Label instructions;
    private final Label status;
    private final Cell<Label> statusCell;
    private final BoardCamera camera;

    GpuHintLine(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera) {
        ui = kit.ui;
        this.camera = camera;
        root.setName("hint-line");
        // Presses pass through the line to the board (#hint: pointer-events none).
        root.setTouchable(Touchable.disabled);
        chip = ui.panel();
        chip.setName("pick-chip");
        chip.pad(7, 14, 7, 9);
        instructions = ui.label("", "hud-small", 12, UiTheme.TEXT);
        instructions.setEllipsis(true);
        instructions.setName("pick-instructions");
        status = ui.label("", "hud-small", 11, UiTheme.MUTED);
        status.setEllipsis(true);
        status.setName("pick-status");
        Table texts = new Table();
        texts.add(instructions).growX().minWidth(0).left().row();
        statusCell = texts.add(status).growX().minWidth(0).left();
        UiButton done = ui.button("hud-mini", null, text("BotCommandPanel.HexPicker.done"), null);
        done.setName("pick-done");
        UiButton cancel = ui.button("hud-mini", null, text("BotCommandPanel.HexPicker.cancel"), null);
        cancel.setName("pick-cancel");
        onChange(done, () -> source.players().endPick(true));
        onChange(cancel, () -> source.players().endPick(false));
        chip.add(texts).growX().minWidth(0);
        chip.add(done).padLeft(12);
        chip.add(cancel).padLeft(6);
    }

    /** The pick's chip, which GpuHud shows on the dock while a pick runs in a window without the hint line. */
    Actor chip() {
        return chip;
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        // While the camera flies, Free Flight's keys replace the board's gestures (the old GL UI's help line).
        List<String> items = camera.firstPerson() ? flight() : items(inputs);
        if (!items.equals(shown)) {
            shown = items;
            root.clearChildren();
            for (int index = 0; index < items.size(); index += 2) {
                root.add(ui.label(items.get(index), "hud-medium", SIZE, Color.WHITE))
                      .padLeft(index == 0 ? 0 : ITEM_GAP);
                root.add(ui.label(items.get(index + 1), "hud-small", SIZE, TEXT)).padLeft(NAME_GAP);
            }
        }
        GpuPlayers.Pick pick = inputs.frame().panels().players().pick();
        if (pick != null) {
            instructions.setText(pick.instructions());
            status.setText(pick.status());
            statusCell.setActor(pick.status().isEmpty() ? null : status);
        }
    }

    /**
     * The line's gestures and what each does now, alternating (the prototype's hintText, plan A.12 L11). While a bot
     * order picks hexes, the pick's: its instructions and picked hexes, Done and Cancel, as the classic board's
     * picker dialog shows them.
     */
    static List<String> items(GpuHud.Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        boolean planning = GpuHud.planning(inputs);
        GpuBoardSource.UiPreferences preferences = inputs.preferences();
        GpuPlayers.Pick pick = inputs.frame().panels().players().pick();
        if (pick != null) {
            return List.of(text("GpuBoard.hud.mouse.leftClick"),
                  pick.status().isEmpty() ? pick.instructions() : pick.instructions() + " · " + pick.status(),
                  key(preferences, KeyCommandBind.DONE), text("BotCommandPanel.HexPicker.done"),
                  key(preferences, KeyCommandBind.CANCEL), text("BotCommandPanel.HexPicker.cancel"));
        }
        // A measurement waiting for its second point takes the next left click (GpuHud.boardClick).
        int pending = inputs.frame().panels().los().pending();
        List<String> items = new ArrayList<>(List.of(text("GpuBoard.hud.mouse.leftClick"),
              text(pending == InputEvent.CTRL_DOWN_MASK ? "GpuBoard.hud.hint.completeLos"
                    : pending != 0 ? "GpuBoard.hud.hint.completeDistance" : leftClick(status, planning))));
        if (inputs.view().tactical()) {
            items.addAll(List.of(text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.panMap"),
                  text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.orbit"),
                  text("GpuBoard.hud.mouse.wheel"), text("GpuBoard.hud.hint.zoom"),
                  key(preferences, KeyCommandBind.TOGGLE_ISO), text("GpuBoard.hud.util.backTo3d")));
        } else {
            if (planning) {
                items.addAll(List.of(text("GpuBoard.hud.mouse.shiftClick"), text("GpuBoard.hud.hint.waypoint")));
            }
            String camera = key(preferences, KeyCommandBind.SCROLL_NORTH) + key(preferences, KeyCommandBind.SCROLL_WEST)
                  + key(preferences, KeyCommandBind.SCROLL_SOUTH) + key(preferences, KeyCommandBind.SCROLL_EAST) + " "
                  + key(preferences, KeyCommandBind.CAMERA_ROTATE_LEFT)
                  + key(preferences, KeyCommandBind.CAMERA_ROTATE_RIGHT);
            items.addAll(List.of(text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.pan"),
                  text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.orbit"), camera,
                  text("GpuBoard.hud.hint.camera")));
        }
        return items;
    }

    /** Free Flight's name and keys, from its help text ("Free Flight: WASD move | …"). */
    private static List<String> flight() {
        String help = text("GpuBoard.firstPersonHelp");
        int colon = help.indexOf(": ");
        return colon < 0 ? List.of(text("GpuBoard.firstPerson"), help)
              : List.of(help.substring(0, colon), help.substring(colon + 2));
    }

    /**
     * What a left click on the board does now, as GpuHud routes it: the HUD's own gestures in the local movement,
     * firing and physical turns, MegaMek's board tool in every other local turn; outside the local turn an own unit's
     * click selects it and any other unit's inspects it (user item 29c).
     */
    private static String leftClick(GpuBattleStatus.Snapshot status, boolean planning) {
        GamePhase phase = status.phase();
        if (status.myTurn()) {
            if (planning) {
                return "GpuBoard.hud.hint.selectPlan";
            } else if (phase.isFiring() || phase.isTargeting()) {
                return "GpuBoard.hud.hint.selectTarget";
            }
            return phase.isPhysical() ? "GpuBoard.hud.hint.selectPhysicalTarget" : "GpuBoard.hud.hint.useTool";
        }
        return "GpuBoard.hud.hint.selectInspect";
    }

    /** The current key text of a bind ({@code KeyCommandBind.getDesc}), as the settings dialog shows it. */
    static String key(GpuBoardSource.UiPreferences preferences, KeyCommandBind command) {
        return preferences.binds().stream().filter(bind -> bind.command() == command)
              .map(GpuBoardSource.Bind::text).findFirst().orElse("");
    }
}
