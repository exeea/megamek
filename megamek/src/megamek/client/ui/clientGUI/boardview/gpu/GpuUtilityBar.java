/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Top-right utilities (Tactical view, Map, Log, Help, Menu), the Tactical View chip and the Tactical View's north mark
 * (C.1 G4). They show the camera, the minimap preference, the log's presented events and the HUD's open panels, and
 * run the camera's Tactical View, the View menu's minimap item and the HUD's own toggles.
 */
final class GpuUtilityBar implements GpuHud.Component {
    /** The row's gap (#sys). */
    static final float GAP = 8;
    /**
     * The top of the Tactical View's north mark in the window, centred: its baseline then lies at the prototype's
     * y 96 (flat.js), whether or not the chip above it has room.
     */
    static final float NORTH_TOP = 84;
    /** The chip's gap between its icon, caption and button (#mapchip). */
    private static final float CHIP_GAP = 10;
    private static final Color NORTH = new Color(226 / 255f, 235 / 255f, 215 / 255f, .7f);

    private final GpuHudState state;
    private final BoardCamera camera;
    private final Table root = new Table();
    private final Label north;
    private final Table chip = new Table();
    private final UiButton tactical;
    private final UiButton map;
    private final UiButton log;
    private final UiButton help;
    private final UiButton menu;
    private final UiButton back;
    private final List<UiButton> utilities;
    private final TextButton.TextButtonStyle wide;
    private final TextButton.TextButtonStyle narrow;
    private final TextTooltip tacticalTip;
    private final TextTooltip mapTip;
    private final TextTooltip logTip;
    private final TextTooltip helpTip;
    private final TextTooltip backTip;
    private GpuHud.Inputs inputs;
    private GpuBoardSource.UiPreferences tipPreferences;
    private boolean narrowShown;

    GpuUtilityBar(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera) {
        this.state = state;
        this.camera = camera;
        root.setName("utility-bar");
        chip.setName("tactical-chip");
        UiKit ui = kit.ui;
        tactical = utility(ui, "tactical", "GpuBoard.hud.util.tactical", "utility-tactical");
        map = utility(ui, "map", "GpuBoard.hud.util.map", "utility-map");
        log = utility(ui, "report", "GpuBoard.hud.util.log", "utility-log");
        help = utility(ui, "help", "GpuBoard.hud.util.help", "utility-help");
        menu = utility(ui, "menu", "GpuBoard.hud.menu.title", "utility-menu");
        utilities = List.of(tactical, map, log, help, menu);
        for (UiButton utility : utilities) {
            root.add(utility).padLeft(utility == tactical ? 0 : GAP);
        }
        wide = ui.skin.get("hud-utility", TextButton.TextButtonStyle.class);
        // The style at W <= 1350, which the developer Tuning utility at the row's end (G17) also takes.
        narrow = ui.skin.get("hud-utility-narrow", TextButton.TextButtonStyle.class);
        tacticalTip = ui.tip(tactical);
        mapTip = ui.tip(map);
        logTip = ui.tip(log);
        helpTip = ui.tip(help);
        ui.tip(menu).getActor().setText(Messages.getString("GpuBoard.hud.menu.title"));
        onChange(tactical, () -> setTactical(!camera.tactical()));
        onChange(map, () -> runMinimap(inputs));
        onChange(log, state::toggleLog);
        onChange(help, () -> state.toggle(GpuHudState.Dialog.HELP));
        onChange(menu, () -> state.toggle(GpuHudState.Dialog.MENU));

        chip.setBackground(ui.skin.getDrawable("panel-rails"));
        chip.add(ui.icon("map", 16, UiTheme.MINT));
        chip.add(ui.label(UiTheme.upper(Messages.getString("GpuBoard.hud.util.tactical")), "hud-caption", 12,
              UiTheme.MINT)).padLeft(CHIP_GAP);
        back = ui.button("hud-utility-small", null, Messages.getString("GpuBoard.hud.util.backTo3d"), null);
        back.setName("tactical-back");
        chip.add(back).padLeft(CHIP_GAP);
        backTip = ui.tip(back);
        onChange(back, () -> setTactical(false));
        north = ui.label(Messages.getString("GpuBoard.hud.minimap.north"), "hud-medium", 12, NORTH);
        north.setName("tactical-north");
        // Part of the board drawing in the prototype: presses pass through it to the board.
        north.setTouchable(Touchable.disabled);
    }

    private static UiButton utility(UiKit ui, String icon, String label, String name) {
        UiButton button = ui.button("hud-utility", icon, Messages.getString(label), null);
        button.setName(name);
        return button;
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The chip centred at the top while the Tactical View is on. */
    Actor chip() {
        return chip;
    }

    /**
     * The Tactical View's north mark, shown while that view is on. It is placed apart from the chip, centred in the
     * window with its top at {@link #NORTH_TOP}, so it stays when the chip has no room.
     */
    Actor north() {
        return north;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        boolean tacticalView = inputs.view().tactical();
        BoardScene.Command minimap = minimapCommand(inputs);
        // K12: the round's reviewable events the board has presented, which the log lists (C.6; game.js S.events)
        int reviewable = state.history.played().size();
        tactical.pressed(tacticalView);
        map.pressed(inputs.preferences().minimapEnabled()).setDisabled(minimap == null || !minimap.enabled());
        log.pressed(state.logOpen()).badge(reviewable > 0 ? String.valueOf(reviewable) : null);
        help.pressed(state.dialog == GpuHudState.Dialog.HELP);
        menu.pressed(state.dialog == GpuHudState.Dialog.MENU);
        chip.setVisible(tacticalView);
        north.setVisible(tacticalView);
        if (narrowShown != inputs.metrics().narrow()) {
            narrowShown = inputs.metrics().narrow();
            utilities.forEach(utility -> utility.setStyle(narrowShown ? narrow : wide));
        }
        GpuBoardSource.UiPreferences preferences = inputs.preferences();
        if (preferences != tipPreferences) {
            // The key texts change only with the preferences, which the source replaces when they change.
            tipPreferences = preferences;
            tip(tacticalTip, "GpuBoard.hud.util.tacticalTip", preferences, KeyCommandBind.TOGGLE_ISO);
            tip(mapTip, "GpuBoard.hud.util.mapTip", preferences, KeyCommandBind.MINIMAP);
            tip(logTip, "GpuBoard.hud.util.logTip", preferences, KeyCommandBind.ROUND_REPORT);
            tip(helpTip, "GpuBoard.hud.util.helpTip", preferences, KeyCommandBind.KEY_BINDS);
            tip(backTip, "GpuBoard.hud.util.backTo3dTip", preferences, KeyCommandBind.TOGGLE_ISO);
        }
    }

    /** A tooltip naming the bind's current key (plan A.19). */
    static void tip(TextTooltip tip, String key, GpuBoardSource.UiPreferences preferences, KeyCommandBind bind) {
        tip.getActor().setText(Messages.getString(key, GpuHintLine.key(preferences, bind)));
    }

    /** As the T key: the camera enters the Tactical View or returns to the 3D pose it replaced. */
    private void setTactical(boolean enabled) {
        camera.setTactical(enabled, inputs.frame().scene());
    }

    /**
     * The View menu's minimap item, or null: the one switch of the minimap preference, shared with the minimap's close
     * button and the MINIMAP key (plan A.5 E2).
     */
    static BoardScene.Command minimapCommand(GpuHud.Inputs inputs) {
        return GpuBoardActions.menuItem(inputs.frame().globalCommands(), ClientGUI.VIEW_MINI_MAP);
    }

    /** Runs the View menu's minimap item, which toggles the preference on the Swing thread. */
    static void runMinimap(GpuHud.Inputs inputs) {
        BoardScene.Command command = minimapCommand(inputs);
        if (command != null && command.enabled()) {
            command.action().run();
        }
    }
}
