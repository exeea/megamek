/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;
import static megamek.client.ui.util.KeyCommandBind.BOT_COMMANDS;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_RESET;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_ROTATE_LEFT;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_ROTATE_RIGHT;
import static megamek.client.ui.util.KeyCommandBind.CANCEL;
import static megamek.client.ui.util.KeyCommandBind.CENTER_ON_SELECTED;
import static megamek.client.ui.util.KeyCommandBind.CLEAR_ORDERS;
import static megamek.client.ui.util.KeyCommandBind.DONE;
import static megamek.client.ui.util.KeyCommandBind.FORCES_GRID;
import static megamek.client.ui.util.KeyCommandBind.KEY_BINDS;
import static megamek.client.ui.util.KeyCommandBind.LOS_SETTING;
import static megamek.client.ui.util.KeyCommandBind.MINIMAP;
import static megamek.client.ui.util.KeyCommandBind.MOVE_MODE_JUMP;
import static megamek.client.ui.util.KeyCommandBind.MOVE_MODE_RUN;
import static megamek.client.ui.util.KeyCommandBind.MOVE_MODE_WALK;
import static megamek.client.ui.util.KeyCommandBind.NEXT_TARGET;
import static megamek.client.ui.util.KeyCommandBind.NEXT_UNIT;
import static megamek.client.ui.util.KeyCommandBind.NEXT_WEAPON;
import static megamek.client.ui.util.KeyCommandBind.PLAYBACK_NEXT;
import static megamek.client.ui.util.KeyCommandBind.PLAYBACK_PREV;
import static megamek.client.ui.util.KeyCommandBind.PLAYBACK_TOGGLE;
import static megamek.client.ui.util.KeyCommandBind.PREV_TARGET;
import static megamek.client.ui.util.KeyCommandBind.PREV_UNIT;
import static megamek.client.ui.util.KeyCommandBind.PREV_WEAPON;
import static megamek.client.ui.util.KeyCommandBind.ROUND_REPORT;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_EAST;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_NORTH;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_SOUTH;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_WEST;
import static megamek.client.ui.util.KeyCommandBind.SHOW_NAMEPLATES;
import static megamek.client.ui.util.KeyCommandBind.TOGGLE_CHAT;
import static megamek.client.ui.util.KeyCommandBind.TOGGLE_ISO;
import static megamek.client.ui.util.KeyCommandBind.TURN_LEFT;
import static megamek.client.ui.util.KeyCommandBind.TURN_RIGHT;
import static megamek.client.ui.util.KeyCommandBind.TWIST_LEFT;
import static megamek.client.ui.util.KeyCommandBind.TWIST_RIGHT;
import static megamek.client.ui.util.KeyCommandBind.UNDO_LAST_STEP;
import static megamek.client.ui.util.KeyCommandBind.UNIT_DISPLAY;
import static megamek.client.ui.util.KeyCommandBind.UNIT_OVERVIEW;
import static megamek.client.ui.util.KeyCommandBind.ZOOM_IN;
import static megamek.client.ui.util.KeyCommandBind.ZOOM_OUT;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Help dialog "Controls" (C.1 G14, r1 3.19, plan A.15 O1): the HUD's key binds with their current keys and MegaMek's
 * names, by group, then the mouse gestures, as two-column key pairs; the native counterpart of MegaMek's key bindings
 * overlay (swing-inventory P4).
 */
final class GpuHelpDialog implements GpuHud.Component {
    /** The binds of each group, in the order listed: the camera binds and the HUD's hotkeys (C.4). */
    private static final List<Group> GROUPS = List.of(
          new Group("GpuBoard.hud.help.camera", List.of(SCROLL_NORTH, SCROLL_WEST, SCROLL_SOUTH, SCROLL_EAST,
                CAMERA_ROTATE_LEFT, CAMERA_ROTATE_RIGHT, ZOOM_IN, ZOOM_OUT, CAMERA_RESET, CENTER_ON_SELECTED,
                TOGGLE_ISO)),
          new Group("GpuBoard.hud.help.selection", List.of(NEXT_UNIT, PREV_UNIT, DONE, CANCEL, SHOW_NAMEPLATES)),
          new Group("GpuBoard.hud.help.movement", List.of(MOVE_MODE_WALK, MOVE_MODE_RUN, MOVE_MODE_JUMP, TURN_LEFT,
                TURN_RIGHT, UNDO_LAST_STEP, CLEAR_ORDERS)),
          new Group("GpuBoard.hud.help.weapons", List.of(NEXT_WEAPON, PREV_WEAPON, NEXT_TARGET, PREV_TARGET,
                TWIST_LEFT, TWIST_RIGHT)),
          new Group("GpuBoard.hud.help.playback", List.of(PLAYBACK_TOGGLE, PLAYBACK_PREV, PLAYBACK_NEXT)),
          new Group("GpuBoard.hud.help.panels", List.of(ROUND_REPORT, UNIT_OVERVIEW, UNIT_DISPLAY, FORCES_GRID,
                MINIMAP, LOS_SETTING, KEY_BINDS, BOT_COMMANDS, TOGGLE_CHAT)));
    /** The mouse gestures, which no bind names: the message keys of each gesture and of what it does. */
    private static final List<Pair> MOUSE = List.of(
          new Pair("GpuBoard.hud.mouse.leftClick", "GpuBoard.hud.help.leftClick"),
          new Pair("GpuBoard.hud.mouse.ctrlClick", "GpuBoard.hud.help.ctrlClick"),
          new Pair("GpuBoard.hud.mouse.shiftLeftClick", "GpuBoard.hud.help.shiftLeftClick"),
          new Pair("GpuBoard.hud.mouse.altClick", "GpuBoard.hud.help.altClick"),
          new Pair("GpuBoard.hud.mouse.rightDrag", "GpuBoard.hud.hint.pan"),
          new Pair("GpuBoard.hud.mouse.orbitDrag", "GpuBoard.hud.help.orbit"),
          new Pair("GpuBoard.hud.mouse.shortRightClick", "GpuBoard.hud.help.shortRightClick"),
          new Pair("GpuBoard.hud.mouse.wheel", "GpuBoard.hud.help.wheel"));
    /** The pair grid's gaps (.keys: gap 6 18) and a pair's padding (.keys div: 5 0). */
    private static final float ROW_GAP = 6;
    private static final float COLUMN_GAP = 18;
    private static final float PAIR_PADDING = 5;
    /** A key and a description are 12 units (.keys b, .keys span). */
    private static final float SIZE = 12;
    /** The key's line: the font shorthand's "normal" line of Roboto Condensed, (1900 + 500) / 2048 em (.keys b). */
    private static final float KEY_LINE = SIZE * 1.172f;
    /** The description's line: the body's 1.35 (.keys span). */
    private static final float LINE = SIZE * 1.35f;

    /** A caption and the binds listed under it. */
    private record Group(String caption, List<KeyCommandBind> binds) { }

    /** A key or gesture and what it does. */
    private record Pair(String key, String description) { }

    private final UiKit ui;
    private final GpuHudState state;
    private final Table root;
    private final Table body = new Table();
    private GpuBoardSource.UiPreferences shown;

    GpuHelpDialog(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.state = state;
        root = ui.dialog(text("GpuBoard.hud.help.title"), () -> state.dialog = GpuHudState.Dialog.NONE);
        root.setName("help-dialog");
        body.top().left();
        root.add(ui.scrollList(body)).grow().minHeight(0);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Lists the binds when the dialog shows, again whenever the preferences, and with them the keys, change. */
    @Override
    public void update(GpuHud.Inputs inputs) {
        if (state.dialog == GpuHudState.Dialog.HELP && inputs.preferences() != shown) {
            shown = inputs.preferences();
            body.clearChildren();
            for (Group group : GROUPS) {
                List<Pair> pairs = new ArrayList<>();
                for (KeyCommandBind command : group.binds()) {
                    // An unbound command has no key to show.
                    shown.binds().stream().filter(bind -> bind.command() == command && bind.keyCode() != 0)
                          .findFirst().ifPresent(bind -> pairs.add(new Pair(bind.text(),
                                text("KeyBinds.cmdNames." + command.cmd))));
                }
                section(group.caption(), pairs);
            }
            section("GpuBoard.hud.help.mouse", MOUSE.stream()
                  .map(gesture -> new Pair(text(gesture.key()), text(gesture.description()))).toList());
        }
    }

    /** A caption (.dlg .cap: 12 above, 7 below) over its pairs in two columns. */
    private void section(String caption, List<Pair> pairs) {
        body.add(ui.caption(text(caption))).left().padTop(12).padBottom(7).row();
        Table keys = new Table();
        // Fractional line boxes add up as in the browser.
        keys.setRound(false);
        for (int index = 0; index < pairs.size(); index++) {
            keys.add(pair(pairs.get(index))).growX().uniformX().fillY()
                  .padLeft(index % 2 == 0 ? 0 : COLUMN_GAP).padTop(index < 2 ? 0 : ROW_GAP);
            if (index % 2 == 1) {
                keys.row();
            }
        }
        if (pairs.size() % 2 == 1) {
            keys.add().growX().uniformX().padLeft(COLUMN_GAP);
        }
        body.add(keys).growX().row();
    }

    /** A key over its description (.keys div), its hairline at the bottom of its grid row. */
    private Table pair(Pair keyAndDescription) {
        Table pair = new Table();
        pair.setRound(false);
        pair.top().left();
        pair.add(ui.label(keyAndDescription.key(), "hud-name", SIZE, UiTheme.TEXT)).left().height(KEY_LINE)
              .padTop(PAIR_PADDING).row();
        Label text = ui.label(keyAndDescription.description(), "hud-body", SIZE, UiTheme.ACCENT);
        text.setWrap(true);
        pair.add(text).growX().minHeight(LINE).padBottom(PAIR_PADDING).row();
        pair.add().expandY().row();
        pair.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1);
        return pair;
    }
}
