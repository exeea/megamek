/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTextField;

/** The name, password and port inputs the Host, Resume, Connect and briefing pages share. */
final class StartServerInputs {
    static final float PORT_WIDTH = 110;

    private final GpuStartScreen screen;
    /**
     * The Host or Connect page's inputs, kept while it shows so opening its online options or switching to a bot keeps
     * what was typed. The name and port belong to whichever of the two shows.
     */
    UiTextField nameInput;
    UiFormField nameField;
    UiTextField passwordInput;
    UiKit.TokenPassword passwordBox;
    UiTextField portInput;
    UiFormField portField;
    Table passwordRow;

    StartServerInputs(GpuStartScreen screen) {
        this.screen = screen;
    }

    /** The game's password and port, side by side in {@link #passwordRow}. */
    void buildServerInputs(String lastPassword, int lastPort) {
        UiKit.TokenPassword password = screen.kit.tokenPassword(false);
        passwordBox = password;
        passwordInput = password.field;
        passwordInput.setText(lastPassword == null ? "" : lastPassword);
        passwordInput.setFocusTraversal(false);
        UiFormField passwordField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.password"),
              password).helper(Messages.getString("MegaMek.GameConnection.passwordHelper"));
        portInput = field(String.valueOf(lastPort));
        portField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.port"), portInput);
        passwordRow = new Table();
        passwordRow.top().left();
        passwordRow.add(passwordField).growX().minWidth(0).top();
        passwordRow.add(portField).width(PORT_WIDTH).padLeft(12).top();
    }

    UiTextField field(String text) {
        UiTextField field = screen.kit.input(text == null ? "" : text, false);
        // The screen moves the keyboard between all its controls, not only between text fields
        field.setFocusTraversal(false);
        return field;
    }

    /** The port field's error for {@code port}, as {@link StartHostForm#port} reads it, or null when it is fine. */
    static String portError(int port) {
        return port < 0 ? Messages.getString("MegaMek.GameConnection.portError",
              String.valueOf(MMConstants.MIN_PORT), String.valueOf(MMConstants.MAX_PORT)) : null;
    }
}
