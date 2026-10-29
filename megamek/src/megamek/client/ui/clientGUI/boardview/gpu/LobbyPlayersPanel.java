/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.LobbySnapshot.PlayerRow;
import megamek.client.lobby.LobbySnapshot.TeamGroup;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTextField;

/**
 * The lobby's left column: the players by team, with the chat docked under them, below a hairline, as the Swing
 * briefing room has it.
 */
final class LobbyPlayersPanel {
    /** How many lines of chat show at once. */
    static final int CHAT_LINES = 7;

    private final UiKit kit;
    private final Table panel;
    private final Label playerCount;
    private final Table playerList = new Table();
    private final Label chatLog;
    private final ScrollPane chatScroll;

    LobbyPlayersPanel(UiKit kit, Skin skin, Consumer<String> chat) {
        this.kit = kit;
        panel = kit.panel(true);
        Table header = kit.header(Messages.getString("Lobby.players"), "", true);
        playerCount = header.findActor(UiKit.HEADER_COUNT);
        panel.add(header).growX().row();
        playerList.top();
        panel.add(kit.scrollList(playerList, true, false)).grow().row();

        panel.add(new Image(skin.getDrawable("token-divider"))).growX()
              .height(DesignTokens.shipped().size().get("border")).padTop(space("md")).row();
        panel.add(kit.caption(Messages.getString("Lobby.chat"), true)).left().padTop(space("sm")).row();
        chatLog = kit.typeLabel("", "body", "text.secondary");
        chatLog.setWrap(true);
        chatLog.setAlignment(Align.topLeft);
        Table holder = new Table();
        holder.top().left();
        holder.add(chatLog).growX().top().left();
        chatScroll = kit.scrollList(holder, true);
        Drawable frame = chatScroll.getStyle().background;
        float chatHeight = CHAT_LINES * chatLog.getStyle().font.getLineHeight() * chatLog.getFontScaleY()
              + (frame == null ? 0 : frame.getTopHeight() + frame.getBottomHeight());
        panel.add(chatScroll).growX().height(chatHeight).padTop(space("xs")).row();
        UiTextField input = kit.input("", true);
        input.setMessageText(Messages.getString("Lobby.chatHint"));
        input.setTextFieldListener((field, typed) -> {
            if (typed == '\r' || typed == '\n') {
                String text = field.getText();
                field.setText("");
                chat.accept(text);
            }
        });
        panel.add(input).growX().padTop(space("sm"));
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    Table panel() {
        return panel;
    }

    /** Shows {@code snapshot}'s players and chat; {@code before} is the snapshot shown until now, or null. */
    void show(LobbySnapshot snapshot, LobbySnapshot before) {
        if (playerCount != null) {
            playerCount.setText(String.valueOf(snapshot.playerCount()));
        }
        if (before == null || !before.teams().equals(snapshot.teams())) {
            fillPlayers(snapshot);
        }
        if (before == null || !before.chat().equals(snapshot.chat())) {
            chatLog.setText(String.join("\n", snapshot.chat()));
            chatScroll.layout();
            chatScroll.setScrollPercentY(1);
            chatScroll.updateVisualScroll();
        }
    }

    private void fillPlayers(LobbySnapshot snapshot) {
        playerList.clearChildren();
        boolean first = true;
        for (TeamGroup team : snapshot.teams()) {
            playerList.add(kit.caption(team.name(), true)).left().growX()
                  .padTop(first ? 0 : space("lg")).padBottom(space("xs")).row();
            first = false;
            for (PlayerRow player : team.players()) {
                playerList.add(playerRow(player)).growX().padTop(space("xs")).row();
            }
        }
    }

    private Table playerRow(PlayerRow player) {
        Table row = new Table();
        Table name = new Table();
        name.left();
        Label label = kit.roleTypeLabel("name", player.name(), player.self() ? "accent.primary" : "text.primary");
        label.setEllipsis(true);
        name.add(label).left().minWidth(0);
        if (player.self()) {
            name.add(mark("Lobby.you")).padLeft(space("xs"));
        }
        if (player.bot()) {
            name.add(mark("Lobby.bot")).padLeft(space("xs"));
        }
        if (player.gameMaster()) {
            name.add(mark("Lobby.gameMaster")).padLeft(space("xs"));
        }
        if (player.observer()) {
            name.add(mark("Lobby.observer")).padLeft(space("xs"));
        }
        row.add(name).left().growX().minWidth(0).row();
        Table details = new Table();
        String forces = player.hidden() ? Messages.getString("Lobby.hidden")
              : Messages.getString("Lobby.forces", player.units(), player.bv());
        Label forcesLabel = kit.typeLabel(forces, "small", "text.secondary");
        forcesLabel.setEllipsis(true);
        details.add(forcesLabel).left().growX().minWidth(0);
        details.add(kit.chip(Messages.getString(player.ready() ? "Lobby.isReady" : "Lobby.notReady"),
              player.ready() ? Tone.OK : Tone.NORMAL, true)).padLeft(space("xs"));
        row.add(details).growX().padTop(space("xs"));
        return row;
    }

    private Actor mark(String key) {
        return kit.chip(Messages.getString(key), Tone.NORMAL, true);
    }
}
