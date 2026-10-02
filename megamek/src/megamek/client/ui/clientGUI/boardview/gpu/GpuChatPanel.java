/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * Chat panel above its bottom-right button (C.1 G15, r1 3.20, r2 8.5, plan A.15 O6; shot 16): the game's chat lines
 * (GpuChat), each sender over its text, mint for a player and muted for MegaMek's own lines, then the message field
 * and Send. Enter or Send posts the draft through the board chat's send, which keeps it in the chat history, and the
 * field keeps the focus. The draft stays in the field while the panel closes and opens again. The list shows the
 * newest line; while the panel is closed, a newer line than the last one seen puts the unread dot on the button.
 */
final class GpuChatPanel implements GpuHud.Component {
    /** The send row's field and button (#chat .send .b: min-height 34). */
    private static final float SEND_HEIGHT = 34;

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Table root;
    private final UiButton button;
    private final Table lines = new Table();
    private final ScrollPane scroll;
    private final TextField field;
    private GpuChat.Snapshot shown;
    /** The id of the newest line shown while the panel was open; newer lines are unread. */
    private long seen;
    private boolean open;
    /** The list scrolls to its newest line once it is laid out. */
    private boolean scrollDown;

    GpuChatPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        root = ui.panel();
        root.setName("chat-panel");
        root.add(ui.header(text("GpuBoard.hud.chat.title"), text("GpuBoard.hud.chat.allPlayers"),
              ui.closeButton(() -> state.chatOpen = false))).growX().row();
        // #chat .msgs: the list takes the free height, padded 4 14.
        lines.top().left().pad(4, 14, 4, 14);
        lines.defaults().growX();
        scroll = ui.scrollList(lines);
        root.add(scroll).grow().minHeight(0).row();
        field = new TextField("", ui.skin, "hud");
        field.setName("chat-input");
        field.setMessageText(text("GpuBoard.hud.chat.placeholder"));
        // Tab (MegaMek's next unit) neither moves the focus to another panel's field nor types into the message.
        field.setFocusTraversal(false);
        field.setTextFieldFilter((input, character) -> character != '\t');
        field.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    send();
                    return true;
                }
                return false;
            }
        });
        UiButton send = ui.button("hud", null, text("GpuBoard.hud.chat.send"), null);
        send.setName("chat-send");
        UiKit.onChange(send, this::send);
        // #chat .send: padding 8 12 12, the field takes the width, 6 before the button.
        Table sendRow = new Table();
        sendRow.pad(8, 12, 12, 12);
        sendRow.add(field).growX().height(SEND_HEIGHT);
        sendRow.add(send).height(SEND_HEIGHT).padLeft(6);
        root.add(sendRow).growX();
        // The row utility (.b.util.row): pressed while the panel is open.
        button = ui.button("hud-utility-row", "chat", text("GpuBoard.hud.chat.title"), null);
        button.setName("chat-button");
        UiKit.onChange(button, () -> {
            state.chatOpen = !state.chatOpen;
            if (state.chatOpen) {
                focus();
            }
        });
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The Chat button at the bottom right. */
    Actor button() {
        return button;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuChat.Snapshot chat = inputs.frame().panels().chat();
        if (chat != shown) {
            shown = chat;
            lines.clearChildren();
            chat.lines().forEach(this::line);
            scrollDown = true;
        }
        if (state.chatOpen != open) {
            open = state.chatOpen;
            scrollDown |= open;
        }
        long newest = chat.lines().isEmpty() ? seen : chat.lines().getLast().id();
        if (open) {
            seen = Math.max(seen, newest);
        } else {
            blur();
        }
        button.pressed(open).dot(newest > seen);
        if (scrollDown && open && root.getHeight() > 0) {
            // The prototype keeps the newest line in view (#chatlog scrollTop = scrollHeight).
            scrollDown = false;
            root.validate();
            scroll.setScrollPercentY(1);
            scroll.updateVisualScroll();
        }
    }

    /** One message (.msg: padding 5 0): the sender (700 12 condensed) over the text (12.5). */
    private void line(GpuChat.ChatLine line) {
        Table message = new Table();
        message.pad(5, 0, 5, 0);
        message.defaults().left().growX();
        message.add(ui.label(line.sender(), "hud-name", 12, line.system() ? UiTheme.MUTED : UiTheme.MINT)).row();
        Label text = ui.label(line.text(), "hud-body", 12.5f, UiTheme.TEXT);
        text.setWrap(true);
        message.add(text);
        lines.add(message).row();
    }

    /** Opens with the input focused (TOGGLE_CHAT, TOGGLE_CHAT_CMD); the typed "/" follows as a character. */
    void focus() {
        Stage stage = root.getStage();
        if (stage != null) {
            stage.setKeyboardFocus(field);
        }
    }

    /** A closed panel keeps no keyboard focus; its draft stays in the field. */
    private void blur() {
        Stage stage = root.getStage();
        if (stage != null && stage.getKeyboardFocus() != null && stage.getKeyboardFocus().isDescendantOf(root)) {
            stage.setKeyboardFocus(null);
        }
    }

    /** Posts the draft unless it is blank and empties the field, which keeps the focus (Enter or Send). */
    private void send() {
        String draft = field.getText();
        if (!draft.isBlank()) {
            source.chat().send(draft);
            field.setText("");
        }
        focus();
    }
}
