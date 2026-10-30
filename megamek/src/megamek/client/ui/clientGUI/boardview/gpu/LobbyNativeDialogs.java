/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Stage;
import jakarta.annotation.Nullable;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.common.icons.Camouflage;

/**
 * The single entry point of the lobby's libGDX dialogs, as {@link LobbyDialogs} is of its Swing ones: the camo chooser
 * and the player settings so far, the others moving here one at a time. One dialog shows at a time, over the whole
 * lobby on a scrim that takes every click and key ({@link UiDialog}); opening another replaces it. Every
 * {@code new UiDialog} of the lobby is here, which {@code LobbyDialogEntryPointsTest} enforces.
 * <p>
 * GPU thread. The camo pictures are read on a daemon thread of their own.
 */
final class LobbyNativeDialogs {
    private final UiKit kit;
    private final Stage stage;
    private final boolean motion;
    private ExecutorService pictures;
    private UiDialog open;
    private Runnable closing;
    private LobbyCamoView camoView;
    private LobbyPlayerSettingsView settingsView;

    LobbyNativeDialogs(UiKit kit, Stage stage, boolean motion) {
        this.kit = kit;
        this.stage = stage;
        this.motion = motion;
    }

    /** Whether a dialog shows; the lobby behind it takes no keys meanwhile. */
    boolean isOpen() {
        return open != null && open.isOpen();
    }

    /** Closes the dialog that shows, as Cancel would. */
    void cancel() {
        if (open != null) {
            close();
        }
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
        UiDialog dialog = begin(Messages.getString("CamoChoiceDialog.select_camo_pattern"), "dialog.lg");
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
        UiDialog dialog = begin(Messages.getString("PlayerSettingsDialog.title"),
              "dialog.md");
        settingsView = new LobbyPlayerSettingsView(kit, dialog, form, positions, camo, host, this::close);
        show(dialog);
        return settingsView;
    }

    private UiDialog begin(String title, String width) {
        if (open != null) {
            close();
        }
        UiDialog dialog = new UiDialog(kit, title, this::close).width(width);
        open = dialog;
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

    private void close() {
        UiDialog dialog = open;
        open = null;
        if (dialog != null) {
            dialog.close();
        }
        if (camoView != null) {
            camoView.dispose();
            camoView = null;
        }
        if (settingsView != null) {
            settingsView.dispose();
            settingsView = null;
        }
    }

    /** As the screen goes: closes the dialog and stops the picture thread. */
    void dispose() {
        close();
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

    @Nullable UiDialog dialog() {
        return open;
    }
}
