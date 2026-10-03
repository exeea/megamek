/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.percent;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.shown;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Modifier;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Solution;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Target;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFlow;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.rolls.TargetRoll;

/**
 * Firing solution of the selected weapon (C.1 G10; r1 3.14, r2 5, plan A.8 H21): the weapon and its target with the
 * roll, the range brackets in the band colors with the mount arc and the minimum range, the rules' modifiers and the
 * result, or why there is no shot. It shows while a weapon of the local declaration is selected; its close button and
 * the weapons panel's Esc step deselect the weapon.
 */
final class GpuSolutionCard implements GpuHud.Component {
    private static final String SEPARATOR = "·";
    /** Stands for the target's name in a title's message, which the card splits there (no unit name holds it). */
    private static final String NAME = "\uE000";
    /** #solution .m: 12-unit text on the body's 1.35 line height, 3 apart. */
    private static final float TEXT_SIZE = 12;
    private static final float LINE_GAP = 3;
    /** #solution .rng: 600 11 condensed, 10 apart, 6 above the modifiers. */
    private static final float RANGE_SIZE = 11;

    /** What the card shows: the solution, its weapon's name, the letters, the focus's name. */
    private record View(Solution solution, String weapon, List<Target> targets, String focusName) { }

    private final UiKit ui;
    private final GpuHudState state;
    private final Table root;
    private final Label title;
    /** The title's part after the target's name, the roll or "unavailable", which never ends in an ellipsis. */
    private final Label roll;
    private final HorizontalGroup ranges = new HorizontalGroup();
    private final UiFlow modifiers;
    private final Cell<Actor> modifierCell;
    private final UiFlow result;
    private final Cell<UiFlow> resultCell;
    private View shown;

    GpuSolutionCard(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.state = state;
        root = ui.panel();
        root.setName("solution-card");
        root.setVisible(false);
        // #solution: padding 0 14 12 inside the 2-unit rails and side borders
        root.pad(2, 16, 14, 16);
        UiButton close = ui.closeButton(() -> GpuWeaponsPanel.deselect(source, state));
        close.setName("solution-close");
        Table header = ui.header("", null, close);
        header.setName("solution-header");
        // #solution .phd: padding 9 0 4, the smaller title (.t.sm: 13.5 units)
        header.pad(9, 0, 4, 0);
        title = (Label) header.getChildren().first();
        title.setName("solution-title");
        UiKit.size(title, "hud-title", 13.5f);
        // A long target name ends in the ellipsis, not the roll after it (P1 H1): the title's cell holds both parts,
        // and only the first may shrink.
        roll = new Label("", title.getStyle());
        roll.setName("solution-roll");
        UiKit.size(roll, "hud-title", 13.5f);
        Table line = new Table();
        header.getCell(title).setActor(line);
        line.add(title).minWidth(0);
        line.add(roll);
        root.add(header).growX().row();
        ranges.setName("solution-ranges");
        ranges.wrap().rowLeft().space(10).wrapSpace(2);
        // .rng's margin of 6 below collapses with the 3 above each .m line.
        root.add(ranges).growX().padBottom(6).row();
        modifiers = new UiFlow(ui, TEXT_SIZE, TEXT_SIZE * 1.35f);
        modifiers.setName("solution-modifiers");
        modifierCell = root.add((Actor) null).growX();
        root.row();
        result = new UiFlow(ui, TEXT_SIZE, TEXT_SIZE * 1.35f);
        result.setName("solution-result");
        resultCell = root.add(result).growX();
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        Solution solution = fire.editable() ? fire.solution() : null;
        String weapon = solution == null ? "" : fire.weapons().stream().filter(row -> row.eqNum() == solution.eqNum())
              .map(GpuFireOrders.WeaponRow::name).findFirst().orElse("");
        View view = new View(solution, weapon, fire.targets(), fire.focus().name());
        if (!view.equals(shown)) {
            shown = view;
            rebuild();
        }
        // Laid out at the column's width now, so its wrapped lines give the HUD its height this frame.
        root.setWidth(inputs.metrics().right());
        root.validate();
    }

    private void rebuild() {
        Solution solution = shown.solution();
        root.setVisible(solution != null);
        ranges.clearChildren();
        modifiers.clearChildren();
        result.clearChildren();
        if (solution == null) {
            return;
        }
        boolean target = !solution.target().equals(TargetKey.NONE);
        boolean shot = possible(solution.value());
        showTitle(solution, target, shot);
        List<Integer> brackets = solution.ranges();
        range(text("GpuBoard.hud.solution.short", brackets.get(1)), GpuBoardSkin.BAND_SHORT);
        range(text("GpuBoard.hud.solution.medium", brackets.get(2)), GpuBoardSkin.BAND_MEDIUM);
        range(text("GpuBoard.hud.solution.long", brackets.get(3)), GpuBoardSkin.BAND_LONG);
        if (brackets.size() > 4) {
            range(text("GpuBoard.hud.solution.extreme", brackets.get(4)), UiTheme.MUTED);
        }
        if (!solution.arc().isEmpty()) {
            range(solution.arc(), UiTheme.MUTED);
        }
        if (brackets.getFirst() > 0) {
            range(text("GpuBoard.hud.solution.minimum", brackets.getFirst()), UiTheme.MUTED);
        }
        boolean listed = target && shot && !solution.modifiers().isEmpty();
        modifierCell.setActor(listed ? modifiers : null);
        // Without a target the card ends with the weapon's ranges.
        resultCell.setActor(target ? result : null).padTop(listed ? LINE_GAP : 0);
        if (target && !shot) {
            result.run(solution.reason(), "hud-body", UiTheme.CORAL);
        } else if (target) {
            showModifiers(solution.modifiers());
            result.run(text("GpuBoard.hud.solution.roll", solution.distance(), shown(solution.value())) + " "
                  + SEPARATOR, "hud-body", UiTheme.ACCENT).run(percent(solution.odds()), "hud-medium", Color.WHITE);
        }
    }

    /**
     * The title (.t.sm): the weapon, then its target with its letter, if it has one, and the roll or "unavailable";
     * the weapon alone without a target. It is split after the target's name: the part up to the name ends in an
     * ellipsis where the card is too narrow, the rest stays whole.
     */
    private void showTitle(Solution solution, boolean target, boolean shot) {
        String text = shown.weapon();
        String name = "";
        if (target) {
            Target lettered = shown.targets().stream().filter(candidate -> candidate.key().equals(solution.target()))
                  .findFirst().orElse(null);
            if (lettered != null) {
                name = lettered.name();
                text = shot ? text("GpuBoard.hud.solution.title", shown.weapon(), lettered.letter(), NAME,
                      shown(solution.value()))
                      : text("GpuBoard.hud.solution.titleUnavailable", shown.weapon(), lettered.letter(), NAME);
            } else {
                name = shown.focusName();
                text = shot ? text("GpuBoard.hud.solution.titleFocus", shown.weapon(), NAME, shown(solution.value()))
                      : text("GpuBoard.hud.solution.titleFocusUnavailable", shown.weapon(), NAME);
            }
        }
        int at = text.indexOf(NAME);
        title.setText(UiTheme.upper(at < 0 ? text : text.substring(0, at) + name));
        roll.setText(UiTheme.upper(at < 0 ? "" : text.substring(at + NAME.length())));
    }

    /**
     * The rules' modifiers (.m with bold values): each description, then its value, the first (the base, the gunnery
     * skill) bare and the others signed.
     */
    private void showModifiers(List<Modifier> list) {
        for (int index = 0; index < list.size(); index++) {
            Modifier modifier = list.get(index);
            String description = modifier.description();
            modifiers.run(description.isEmpty() ? "" : description.substring(0, 1).toUpperCase(Locale.ROOT)
                  + description.substring(1), "hud-body", UiTheme.ACCENT);
            modifiers.run(index == 0 ? Integer.toString(modifier.value()) : GpuHudKit.signed(modifier.value()),
                  "hud-medium", Color.WHITE);
            if (index < list.size() - 1) {
                modifiers.run(SEPARATOR, "hud-body", UiTheme.ACCENT);
            }
        }
    }

    /** A range-row item (.rng span) in its band's color. */
    private void range(String text, Color color) {
        ranges.addActor(ui.label(text, "hud-button", RANGE_SIZE, color));
    }

    /** A roll that dice can make or fail, which the card and the weapon rows show: no impossible or automatic fail. */
    static boolean possible(int value) {
        return value != TargetRoll.IMPOSSIBLE && value != TargetRoll.AUTOMATIC_FAIL;
    }
}
