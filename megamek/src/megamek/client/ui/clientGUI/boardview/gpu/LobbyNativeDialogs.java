/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Stage;
import jakarta.annotation.Nullable;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.common.icons.Camouflage;

/**
 * The single entry point of the lobby's libGDX dialogs, as {@link LobbyDialogs} is of its Swing ones: the camo chooser,
 * the player settings, the bot setup and a message so far, the others moving here one at a time. A dialog shows over
 * the whole lobby on a scrim that takes every click and key ({@link UiDialog}). Opening the camo chooser or the player
 * settings replaces whatever shows; the bot setup and a message go over it, as the player settings' Bot settings opens
 * the bot setup, and closing one shows the one below again. Every {@code new UiDialog} of the lobby is here, which
 * {@code LobbyDialogEntryPointsTest} enforces.
 * <p>
 * GPU thread. The camo pictures are read on a daemon thread of their own.
 */
final class LobbyNativeDialogs {
    private final UiKit kit;
    private final Stage stage;
    private final boolean motion;
    private ExecutorService pictures;
    /** The dialogs that show, the top one first, each with what to do once it has closed. */
    private final Deque<Open> open = new ArrayDeque<>();
    private LobbyCamoView camoView;
    private LobbyPlayerSettingsView settingsView;
    private LobbyBotSetupView botView;

    private record Open(UiDialog dialog, Runnable closed) { }

    LobbyNativeDialogs(UiKit kit, Stage stage, boolean motion) {
        this.kit = kit;
        this.stage = stage;
        this.motion = motion;
    }

    /** Whether a dialog shows; the lobby behind it takes no keys meanwhile. */
    boolean isOpen() {
        return !open.isEmpty() && open.peek().dialog().isOpen();
    }

    /** Closes the top dialog, as Cancel would. */
    void cancel() {
        close();
    }

    /**
     * Opens the camo chooser.
     *
     * @param source  where the camos and the preview come from
     * @param catalog the camo directory as first read
     * @param choice  the camo being chosen
     * @param assets  whether the previewed units are Battlefield Support Assets, which have a marker overlay
     * @param chosen  takes the confirmed camo, on this thread
     */
    LobbyCamoView camo(LobbyCamoView.Source source, CamoCatalog catalog, CamoChoice choice, boolean assets,
          Consumer<Camouflage> chosen) {
        UiDialog dialog = begin(Messages.getString("CamoChoiceDialog.select_camo_pattern"), "dialog.lg", false,
              () -> {
                  camoView.dispose();
                  camoView = null;
              });
        if (pictures == null) {
            pictures = Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "Lobby camo pictures");
                thread.setDaemon(true);
                return thread;
            });
        }
        camoView = new LobbyCamoView(kit, dialog, source, catalog, choice, assets, pictures, bodyWidth(dialog),
              chosen, this::close);
        show(dialog);
        return camoView;
    }

    /**
     * Opens the player settings.
     *
     * @param positions the start positions, as the form read them on the Swing thread
     * @param camo      the player's camo picture, or null
     */
    LobbyPlayerSettingsView playerSettings(PlayerSettingsForm form, List<PlayerSettingsForm.Position> positions,
          @Nullable BufferedImage camo, LobbyPlayerSettingsView.Host host) {
        UiDialog dialog = begin(Messages.getString("PlayerSettingsDialog.title"), "dialog.md", false, () -> {
            settingsView.dispose();
            settingsView = null;
        });
        settingsView = new LobbyPlayerSettingsView(kit, dialog, form, positions, camo, host, this::close);
        show(dialog);
        return settingsView;
    }

    /**
     * Opens the bot setup over whatever shows.
     *
     * @param host what the view asks of the window
     */
    LobbyBotSetupView botSetup(BotSetupForm form, LobbyBotSetupView.Host host) {
        UiDialog dialog = begin(Messages.getString("BotConfigDialog.title"), "dialog.lg", true, () -> botView = null);
        botView = new LobbyBotSetupView(kit, dialog, form, bodyWidth(dialog), host, this::close);
        show(dialog);
        return botView;
    }

    /** Shows {@code text} with an OK button over whatever shows, as an alert. */
    UiDialog message(String title, String text) {
        UiDialog dialog = begin(title, "dialog.sm", true, () -> { });
        var label = kit.typeLabel(text, "body", "text.primary");
        label.setWrap(true);
        dialog.body().add(label).growX().left();
        dialog.primary(Messages.getString("Ok.text"), this::close).setName("message-ok");
        show(dialog);
        return dialog;
    }

    /**
     * @param over   whether it goes over the dialogs that show rather than replacing them
     * @param closed what to do once it has closed, such as giving its view's textures back
     */
    private UiDialog begin(String title, String width, boolean over, Runnable closed) {
        if (!over) {
            while (!open.isEmpty()) {
                close();
            }
        }
        UiDialog dialog = new UiDialog(kit, title, this::close).width(width);
        open.push(new Open(dialog, closed));
        return dialog;
    }

    private void show(UiDialog dialog) {
        dialog.show(stage, motion);
    }

    /** The width the body will have in this window: the card's width less its padding and the scroll bar. */
    private float bodyWidth(UiDialog dialog) {
        float space = DesignTokens.shipped().space().get("xxxl");
        float card = Math.min(DesignTokens.shipped().size().get("dialog.lg"), Math.max(0, stage.getWidth() - 2
              * space));
        return Math.max(0, card - dialog.card().getPadLeft() - dialog.card().getPadRight() - DesignTokens.shipped()
              .space().get("md"));
    }

    /** Closes the top dialog, showing the one below it again. */
    private void close() {
        Open top = open.poll();
        if (top != null) {
            top.dialog().close();
            top.closed().run();
            Open below = open.peek();
            if (below != null) {
                stage.setScrollFocus(below.dialog().scroll());
            }
        }
    }

    /** As the screen goes: closes every dialog and stops the picture thread. */
    void dispose() {
        while (!open.isEmpty()) {
            close();
        }
        if (pictures != null) {
            pictures.shutdownNow();
            pictures = null;
        }
    }

    // ---- For the probe and the tests ----

    @Nullable LobbyCamoView camoView() {
        return camoView;
    }

    @Nullable LobbyPlayerSettingsView settingsView() {
        return settingsView;
    }

    @Nullable LobbyBotSetupView botView() {
        return botView;
    }

    /** The top dialog, or null. */
    @Nullable UiDialog dialog() {
        return open.isEmpty() ? null : open.peek().dialog();
    }
}
