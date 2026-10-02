/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;

/**
 * Line-of-sight card beside the right column (C.1 G18, plan A.15 O7), the native form of MegaMek's ruler result
 * (swing-inventory W7): the two measured hexes with their heights and the ruler's height steps, the range, the
 * attacker's and target's views with MegaMek's modifier text, a view without a line of sight in coral, and a button
 * that opens the ruler's elevation diagram. It shows the open card of {@link GpuLosResult}; its buttons and its Esc
 * step post that service's commands, and the card changes when the EDT publishes the next one.
 */
final class GpuLosCard implements GpuHud.Component {
    /** The rows of a card beside the right column (#solution .m: 12 ink2, 3 apart). */
    private static final float ROW_SIZE = 12;
    private static final float ROW_GAP = 3;

    /** One end's row: its hex and height, and the steps of the Swing ruler's height spinner (.b.mini). */
    private record End(Label label, UiButton lower, UiButton raise) { }

    private final UiKit ui;
    private final GpuBoardSource source;
    private final Table root;
    private final Table body = new Table();
    private final TextTooltip closeTip;
    private final End from;
    private final End to;
    private final Label range;
    private final Label attackerView;
    private final Label targetView;
    private GpuBoardSource.UiPreferences preferences;
    /** The card on show; null while the service has none open. */
    private GpuLosResult.Card shown;

    GpuLosCard(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.source = source;
        root = ui.panel();
        root.setName("los-card");
        root.setVisible(false);
        UiButton close = ui.closeButton(() -> source.los().closeCard());
        close.setName("los-close");
        closeTip = ui.tip(close);
        root.add(ui.header(text("GpuBoard.hud.los.title"), null, close)).growX().row();
        body.top().left().pad(0, 14, 12, 14);
        body.defaults().left().growX().minWidth(0);
        from = end("los-from", true);
        to = end("los-to", false);
        range = row("los-range", false);
        attackerView = row("los-attacker-view", true);
        targetView = row("los-target-view", true);
        // MegaMek's ruler with its elevation diagram, kept in Swing (user item 6, U2), for the card's measurement.
        UiButton diagram = ui.button("hud-mini", null, text("GpuBoard.hud.los.diagram"), null);
        diagram.setName("los-diagram");
        onChange(diagram, () -> source.los().showDiagram());
        body.add(diagram).fill(false, false).padTop(8);
        // A short window can leave the card less height than its rows need; they scroll under the header then.
        ScrollPane scroll = ui.scrollList(body);
        scroll.setName("los-card-body");
        root.add(scroll).grow().minHeight(0);
    }

    /** A From or To row: the label, then its two height steps at the right. */
    private End end(String name, boolean fromEnd) {
        Label label = ui.label("", "hud-body", ROW_SIZE, UiTheme.ACCENT);
        label.setEllipsis(true);
        UiButton lower = ui.button("hud-mini", null, "\u2212", null);
        UiButton raise = ui.button("hud-mini", null, "+", null);
        lower.setName(name + "-lower");
        raise.setName(name + "-raise");
        onChange(lower, () -> step(fromEnd, -1));
        onChange(raise, () -> step(fromEnd, 1));
        Table row = new Table();
        row.setName(name);
        row.add(label).growX().minWidth(0);
        // .b.mini is 24 wide around one character, as the initiative card's pager.
        row.add(lower).minWidth(24).padLeft(8);
        row.add(raise).minWidth(24).padLeft(4);
        body.add(row).padTop(fromEnd ? 0 : ROW_GAP).row();
        return new End(label, lower, raise);
    }

    /** A text row below the height rows, tinted (ink2 until a view's row says otherwise); the views wrap. */
    private Label row(String name, boolean wrap) {
        Label label = ui.label("", "hud-body", ROW_SIZE, Color.WHITE);
        label.setColor(UiTheme.ACCENT);
        label.setName(name);
        label.setWrap(wrap);
        body.add(label).padTop(ROW_GAP).row();
        return label;
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        if (inputs.preferences() != preferences) {
            preferences = inputs.preferences();
            closeTip.getActor().setText(text("GpuBoard.hud.common.closeTip",
                  GpuHintLine.key(preferences, KeyCommandBind.CANCEL)));
        }
        GpuLosResult.Card card = inputs.frame().panels().los().card();
        if (card == shown) {
            return;
        }
        shown = card;
        root.setVisible(card != null);
        if (card != null) {
            show(from, card.fromAltitude() ? "GpuBoard.hud.los.fromAltitude" : "GpuBoard.hud.los.from", card.from(),
                  card.fromHeight());
            show(to, card.toAltitude() ? "GpuBoard.hud.los.toAltitude" : "GpuBoard.hud.los.to", card.to(),
                  card.toHeight());
            range.setText(text("GpuBoard.hud.los.range", card.range()));
            show(attackerView, "GpuBoard.hud.los.attackerView", card.attackerView());
            show(targetView, "GpuBoard.hud.los.targetView", card.targetView());
        }
    }

    /** A view's row: MegaMek's modifiers, or in coral the reason it has no line of sight. */
    private static void show(Label row, String key, RulerDialog.LosView view) {
        row.setText(text(key, view.row()));
        row.setColor(view.clear() ? UiTheme.ACCENT : UiTheme.CORAL);
    }

    /**
     * An end's hex and height, or an aerospace unit's altitude; its steps stop at the bounds of the Swing ruler's
     * spinners, within which the service keeps the heights.
     */
    private static void show(End end, String key, Coords hex, int height) {
        end.label().setText(text(key, hex.getBoardNum(), height));
        end.lower().setDisabled(height <= RulerDialog.MIN_HEIGHT);
        end.raise().setDisabled(height >= RulerDialog.MAX_HEIGHT);
    }

    /** Remeasures the shown card with one end a height step lower or higher, as the ruler's spinners do. */
    private void step(boolean fromEnd, int delta) {
        GpuLosResult.Card card = shown;
        if (card != null) {
            source.los().measure(card.from(), card.to(), card.fromHeight() + (fromEnd ? delta : 0),
                  card.toHeight() + (fromEnd ? 0 : delta));
        }
    }

    /** One Esc step (C.4): closes the card; true when it was open. */
    boolean cancel() {
        if (shown == null) {
            return false;
        }
        source.los().closeCard();
        return true;
    }
}
