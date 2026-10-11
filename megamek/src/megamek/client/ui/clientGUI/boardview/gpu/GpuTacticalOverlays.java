/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.BoardSource.UiPreferences.Overlays;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The Tactical View's Overlays panel (b11 overlay-panel design, placed as the user chose on 2026-10-10): a column of
 * twelve checkboxes in four groups for the overlays that can be visually busy, which GpuHud places at the top right,
 * left of the right column's top panel. Each row is one setting, the same wherever its overlay is drawn: a View-menu
 * item, which the row runs and shows as selected (as the conditions card does), or a client setting, which the row
 * toggles on the Swing thread and shows as the published preferences have it. The panel keeps no state of its own;
 * whether it is expanded is a client setting too, which a click on its header and the TACTICAL_OVERLAYS key switch.
 * Hidden outside the Tactical View.
 */
final class GpuTacticalOverlays implements GpuHud.Component {
    private static final String KEYS = "GpuBoard.hud.overlays.";
    /** The checkbox of a row whose View-menu item is missing or disabled. */
    private static final float UNAVAILABLE = .45f;
    /** A row's height in the column. */
    private static final float ROW = 22;

    /** A row: its group caption's key, its label's key, and its View-menu item, or its client setting. */
    private record Row(String group, String key, String menuItem, Predicate<Overlays> setting,
          Consumer<GUIPreferences> toggle) { }

    private static final List<Row> ROWS = List.of(
          setting("units", "statusBadges", Overlays::statusBadges, GUIPreferences::toggleTacticalStatusBadges),
          view("units", "links", ClientGUI.VIEW_TOGGLE_UNIT_LINKS),
          view("combat", "envelope", ClientGUI.VIEW_MOVE_ENV),
          setting("combat", "rearArcs", Overlays::rearArcs, GUIPreferences::toggleTacticalRearArcs),
          view("combat", "losShading", ClientGUI.VIEW_TOGGLE_FOV_DARKEN),
          view("combat", "sensorRanges", ClientGUI.VIEW_TOGGLE_SENSOR_RANGE),
          setting("ecm", "fields", Overlays::ecmFields, GUIPreferences::toggleEcmTacticalFields),
          setting("ecm", "jamming", Overlays::ecmHatch, GUIPreferences::toggleEcmTacticalHatch),
          view("map", "coordinates", ClientGUI.VIEW_TOGGLE_HEX_COORDS),
          view("map", "types", ClientGUI.VIEW_TOGGLE_HEX_TYPES),
          setting("map", "levelShading", Overlays::levelShading, GUIPreferences::toggleTacticalLevelShading),
          view("map", "distanceRings", ClientGUI.VIEW_TOGGLE_FOV_HIGHLIGHT));

    private final UiKit ui;
    private final Table root;
    private final Table header;
    private final UiKit.Icon chevron;
    /** The rule and the rows, which a collapsed panel leaves out. */
    private final Table content = new Table();
    private final Cell<Table> contentCell;
    private final List<UiKit.Checkbox> boxes = new ArrayList<>();
    private final List<Label> tips = new ArrayList<>();
    /** Each View row's item of the current frame, or null when it is missing; in the order of {@link #ROWS}. */
    private final BoardScene.Command[] items = new BoardScene.Command[ROWS.size()];
    /** Each View row's key as its tooltip names it, so the tooltip changes only with the key. */
    private final String[] shortcuts = new String[ROWS.size()];
    private boolean open = true;
    private boolean expanded = true;

    GpuTacticalOverlays(GpuHudKit kit, GpuBoardSource source) {
        ui = kit.ui;
        root = ui.panel();
        root.setName("tactical-overlays");
        chevron = ui.icon("chevron-up", 18, UiTheme.MUTED);
        header = ui.header(text(KEYS + "title"), null, chevron);
        header.setName("overlays-header");
        header.setTouchable(Touchable.enabled);
        header.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                source.command(() -> GUIPreferences.getInstance().toggleTacticalOverlaysOpen());
            }
        });
        root.add(header).growX().row();
        content.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        // One column: each group's caption, then its rows.
        Table rows = new Table();
        rows.pad(2, 14, 6, 14);
        rows.defaults().left().growX();
        for (int index = 0; index < ROWS.size(); index++) {
            Row row = ROWS.get(index);
            if (index == 0 || !ROWS.get(index - 1).group().equals(row.group())) {
                rows.add(ui.label(UiTheme.upper(text(KEYS + row.group())), "hud-caption", 10.5f, UiTheme.MUTED))
                      .height(17).bottom().row();
            }
            UiKit.Checkbox box = ui.checkbox(text(KEYS + row.key()), false);
            box.setName("overlay-" + row.key());
            box.left();
            int at = index;
            onChange(box, () -> {
                if (row.menuItem() == null) {
                    source.command(() -> row.toggle().accept(GUIPreferences.getInstance()));
                } else if (items[at] != null && items[at].enabled()) {
                    items[at].action().run();
                }
            });
            tips.add(ui.tip(box).getActor());
            tips.get(index).setText(text(KEYS + row.key() + "Tip"));
            boxes.add(box);
            rows.add(box).height(ROW).row();
        }
        content.add(rows).growX();
        contentCell = root.add(content).growX();
    }

    private static Row setting(String group, String key, Predicate<Overlays> setting,
          Consumer<GUIPreferences> toggle) {
        return new Row(group, key, null, setting, toggle);
    }

    private static Row view(String group, String key, String menuItem) {
        return new Row(group, key, menuItem, null, null);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        root.setVisible(inputs.view().tactical());
        if (!inputs.view().tactical()) {
            // Hidden: no menu lookups outside the Tactical View; the frame that shows it updates it first.
            return;
        }
        Overlays overlays = inputs.preferences().overlays();
        open = overlays.panelOpen();
        for (int index = 0; index < ROWS.size(); index++) {
            Row row = ROWS.get(index);
            UiKit.Checkbox box = boxes.get(index);
            boolean value;
            if (row.menuItem() == null) {
                value = row.setting().test(overlays);
            } else {
                BoardScene.Command item = GpuBoardActions.menuItem(inputs.frame().globalCommands(), row.menuItem());
                items[index] = item;
                boolean available = item != null && item.enabled();
                value = item != null && Boolean.TRUE.equals(item.selected());
                box.setTouchable(available ? Touchable.enabled : Touchable.disabled);
                box.getColor().a = available ? 1 : UNAVAILABLE;
                String shortcut = item == null || item.shortcut() == null ? "" : item.shortcut();
                if (!shortcut.equals(shortcuts[index])) {
                    // A View row's tooltip names the menu and the item's key.
                    shortcuts[index] = shortcut;
                    tips.get(index).setText(text(KEYS + row.key() + "Tip") + " "
                          + (shortcut.isEmpty() ? text(KEYS + "viewMenu") : text(KEYS + "viewMenuKey", shortcut)));
                }
            }
            if (box.isTicked() != value) {
                box.ticked(value);
            }
        }
    }

    /** Whether the client setting has the panel expanded; GpuHud's layout shows it so, or collapsed for a frame. */
    boolean open() {
        return open;
    }

    /** The panel's height expanded or collapsed (its header only), in stage units. */
    float height(boolean withRows) {
        return root.getPadTop() + root.getPadBottom() + header.getPrefHeight()
              + (withRows ? content.getPrefHeight() : 0);
    }

    /** The panel's width, its header's or its rows', the same expanded and collapsed, in stage units. */
    float width() {
        return root.getPadLeft() + root.getPadRight() + Math.max(header.getPrefWidth(), content.getPrefWidth());
    }

    /**
     * Shows the rows or only the header. GpuHud collapses the panel for one frame when the window is too short for
     * its rows or a card beside the right column needs the room; the client setting stays as it is.
     */
    void show(boolean withRows) {
        if (withRows != expanded) {
            expanded = withRows;
            contentCell.setActor(withRows ? content : null);
            chevron.setDrawable(ui.skin.getDrawable(withRows ? "icon-chevron-up" : "icon-chevron-down"));
        }
    }
}
