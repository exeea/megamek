/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;

/**
 * The lobby's top bar, over the art without a panel: the BRIEFING title with where the game runs and its map under it
 * at the start; the ready count and a Battle Value chip per side in the middle, centred on the whole bar; Leave and
 * Ready at the end, Ready last so it holds the edge.
 */
final class LobbyTopBar extends WidgetGroup {
    /** More sides than this fold into one chip. */
    static final int MAX_SIDE_CHIPS = 4;

    private final UiKit kit;
    private final Table heading = new Table();
    private final Table chips = new Table();
    private final Table trailing = new Table();
    private final Label subtitle;
    private final UiButton ready;
    private final float gap = space("lg");

    LobbyTopBar(UiKit kit, Runnable leaveAction, Runnable readyAction) {
        this.kit = kit;
        heading.left();
        heading.add(kit.roleTypeLabel("headline", Messages.getString("Lobby.title"), "text.primary")).left().row();
        subtitle = kit.typeLabel("", "caption", "text.muted");
        heading.add(subtitle).left().padTop(space("xxs"));

        UiButton leave = kit.button("button-secondary", null, Messages.getString("Lobby.leave"), null);
        UiKit.onChange(leave, leaveAction);
        trailing.add(leave).padRight(space("sm"));
        ready = kit.button("button-primary", null, Messages.getString("Lobby.ready"), null);
        UiKit.onChange(ready, readyAction);
        // Sized to its wider face, so it never changes width
        float width = 0;
        for (String key : new String[] { "Lobby.cancelReady", "Lobby.ready" }) {
            ready.setText(kit.cased("button", Messages.getString(key)));
            width = Math.max(width, ready.getPrefWidth());
        }
        trailing.add(ready).minWidth(width);

        addActor(heading);
        addActor(chips);
        addActor(trailing);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** Shows the server and map, the ready count, each side's Battle Value and Ready's face for {@code snapshot}. */
    void show(LobbySnapshot snapshot) {
        String line = snapshot.server();
        if (!snapshot.mapSummary().isBlank()) {
            line += "  \u00b7  " + snapshot.mapSummary();
        }
        subtitle.setText(line);
        ready.setText(kit.cased("button",
              Messages.getString(snapshot.localReady() ? "Lobby.cancelReady" : "Lobby.ready")));

        chips.clearChildren();
        int readyPlayers = snapshot.readyCount();
        boolean allReady = readyPlayers > 0 && readyPlayers == snapshot.playerCount();
        chips.add(kit.chip(Messages.getString("Lobby.readyCount", readyPlayers, snapshot.playerCount()),
              allReady ? Tone.OK : Tone.NORMAL, true));
        List<LobbySnapshot.Side> sides = snapshot.sides();
        if (sides.size() > MAX_SIDE_CHIPS) {
            chips.add(kit.edgeChip(Messages.getString("Lobby.sides", sides.size()), "neutral")).padLeft(space("sm"));
        } else {
            for (LobbySnapshot.Side side : sides) {
                chips.add(sideChip(side)).padLeft(space("sm"));
            }
        }
        invalidateHierarchy();
    }

    private Actor sideChip(LobbySnapshot.Side side) {
        String text = side.hidden() ? Messages.getString("Lobby.sideHidden", side.name())
              : Messages.getString("Lobby.side", side.name(), side.bv());
        return kit.edgeChip(text, side.friendly() ? "accent" : "danger");
    }

    @Override
    public float getPrefWidth() {
        return heading.getPrefWidth() + gap + chips.getPrefWidth() + gap + trailing.getPrefWidth();
    }

    @Override
    public float getPrefHeight() {
        return Math.max(heading.getPrefHeight(), Math.max(chips.getPrefHeight(), trailing.getPrefHeight()));
    }

    @Override
    public void layout() {
        float height = getHeight();
        float headingWidth = heading.getPrefWidth();
        float trailingWidth = trailing.getPrefWidth();
        heading.setBounds(0, Math.round((height - heading.getPrefHeight()) / 2), headingWidth,
              heading.getPrefHeight());
        trailing.setBounds(getWidth() - trailingWidth, Math.round((height - trailing.getPrefHeight()) / 2),
              trailingWidth, trailing.getPrefHeight());
        float chipsWidth = Math.min(chips.getPrefWidth(), Math.max(0, getWidth() - headingWidth - trailingWidth
              - 2 * gap));
        float x = LobbyLayout.chipsX(getWidth(), headingWidth, chips.getPrefWidth(), trailingWidth, gap);
        chips.setBounds(Math.round(x), Math.round((height - chips.getPrefHeight()) / 2), chipsWidth,
              chips.getPrefHeight());
    }
}
