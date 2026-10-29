/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The lobby's right column: the battlefield. Its body moves into the centre as a view while the column is folded into
 * its rail, and back when it unfolds. Until the map card, rules and victory come, it holds the map's place and its
 * summary.
 */
final class LobbyBattlefieldPanel {
    /** The map card's height to its width, a wide screen's. */
    static final float MAP_ASPECT = 9 / 16f;

    private final UiKit kit;
    private final Table panel;
    private final Table body = new Table();
    private final Label mapSummary;

    LobbyBattlefieldPanel(UiKit kit, Skin skin) {
        this.kit = kit;
        panel = kit.panel(true);
        body.top();
        Table map = new Table();
        map.setBackground(skin.getDrawable("token-card"));
        map.add(kit.icon("map", DesignTokens.shipped().size().get("icon.lg"), UiTheme.tokenColor("text.muted")));
        body.add(map).growX().height(Value.percentWidth(MAP_ASPECT, body)).row();
        mapSummary = kit.typeLabel("", "body", "text.secondary");
        mapSummary.setWrap(true);
        body.add(mapSummary).growX().left().padTop(space("md")).row();
        dock();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    Table panel() {
        return panel;
    }

    /** The battlefield without its panel, which a centre view takes while the column is folded. */
    Table body() {
        return body;
    }

    /** Puts the body back into the column's panel. */
    void dock() {
        panel.clearChildren();
        panel.add(kit.header(Messages.getString("Lobby.battlefield"), null, true)).growX().row();
        panel.add(body).grow();
    }

    /** Shows {@code snapshot}'s map. */
    void show(LobbySnapshot snapshot) {
        mapSummary.setText(snapshot.mapSummary().isBlank() ? Messages.getString("Lobby.noMap")
              : snapshot.mapSummary());
    }
}
