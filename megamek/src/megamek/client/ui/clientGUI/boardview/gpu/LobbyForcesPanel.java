/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.PlayerRow;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiKit;

/**
 * The lobby's centre column at rest: the forces. Until the unit cards come, it sums up each owner's units and Battle
 * Value, or says there are none yet.
 */
final class LobbyForcesPanel {
    private final UiKit kit;
    private final Table panel;
    private final Label unitCount;
    private final Table owners = new Table();

    LobbyForcesPanel(UiKit kit) {
        this.kit = kit;
        panel = kit.panel(true);
        Table header = kit.header(Messages.getString("Lobby.forcesTitle"), "", true);
        unitCount = header.findActor(UiKit.HEADER_COUNT);
        panel.add(header).growX().row();
        owners.top();
        panel.add(kit.scrollList(owners, true, false)).grow();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    Table panel() {
        return panel;
    }

    /** Shows the owners of units in {@code snapshot}. */
    void show(LobbySnapshot snapshot) {
        owners.clearChildren();
        int units = 0;
        for (PlayerRow player : snapshot.players()) {
            if (player.hidden()) {
                continue;
            }
            units += player.units();
            if (player.units() > 0) {
                owners.add(owner(player)).growX().padBottom(space("xs")).row();
            }
        }
        if (unitCount != null) {
            unitCount.setText(String.valueOf(units));
        }
        if (units == 0) {
            Table empty = new Table();
            empty.add(kit.roleTypeLabel("title", Messages.getString("Lobby.noUnits"), "text.secondary")).row();
            Label hint = kit.typeLabel(Messages.getString("Lobby.noUnitsHint"), "body", "text.muted");
            hint.setWrap(true);
            hint.setAlignment(Align.center);
            empty.add(hint).growX().padTop(space("sm"));
            owners.add(empty).growX().padTop(space("xxxl")).padLeft(space("xl")).padRight(space("xl"));
        }
    }

    private Table owner(PlayerRow player) {
        Table row = new Table();
        row.pad(space("sm"), space("md"), space("sm"), space("md"));
        Label name = kit.roleTypeLabel("name", player.name(), player.self() ? "accent.primary" : "text.primary");
        name.setEllipsis(true);
        row.add(name).left().growX().minWidth(0);
        row.add(kit.typeLabel(Messages.getString("Lobby.forces", player.units(), player.bv()), "body",
              "text.secondary")).padLeft(space("md"));
        return row;
    }
}
