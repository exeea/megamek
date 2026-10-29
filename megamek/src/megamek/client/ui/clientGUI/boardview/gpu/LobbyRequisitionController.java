/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbySession;
import megamek.client.lobby.RequisitionSkills;
import megamek.common.Player;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The Requisition view's actions, on the Swing thread: adding a unit from the catalogue, as many times as asked, for
 * the local player or one of their bots, with the skills chosen, and taking an added unit back out. Units are read from
 * their files as the unit selector dialog reads them and added through {@link LobbyCommands#addUnits}, the dialog's own
 * path, so the server's checks, C3 and the rest apply as they do there. It needs neither OpenGL nor Swing components, so
 * it can be tested on its own.
 */
final class LobbyRequisitionController {
    private static final MMLogger LOGGER = MMLogger.create(LobbyRequisitionController.class);

    /** The most copies of a unit one Add sends. */
    static final int MAX_QUANTITY = 12;

    /** Reads a fresh unit from the file a catalogue entry names. */
    interface Units {
        /** @return the unit, or null when its file cannot be read */
        @Nullable Entity read(MekSummary unit);
    }

    /**
     * What an Add did.
     *
     * @param ownerId the player the units went to, or {@link Player#PLAYER_NONE} when none went
     * @param count   how many units went to the server
     */
    record Outcome(int ownerId, int count) {
        static final Outcome NONE = new Outcome(Player.PLAYER_NONE, 0);
    }

    /** Reads units with {@link MekFileParser}, as the unit selector dialog does. */
    static final Units FILES = unit -> {
        try {
            return new MekFileParser(unit.getSourceFile(), unit.getEntryName()).getEntity();
        } catch (Exception failure) {
            LOGGER.error(failure, "Unable to load unit: {}: {}", unit.getSourceFile(), unit.getEntryName());
            return null;
        }
    };

    private final LobbySession session;
    private final LobbyCommands commands;
    private final Units units;

    LobbyRequisitionController(LobbySession session, LobbyCommands commands, Units units) {
        this.session = session;
        this.commands = commands;
        this.units = units;
    }

    /** {@code quantity} brought within 1 and {@link #MAX_QUANTITY}. */
    static int clampQuantity(int quantity) {
        return Math.max(1, Math.min(MAX_QUANTITY, quantity));
    }

    /**
     * Adds {@code quantity} copies of {@code unit} for the player {@code ownerId}, or for the local player when the
     * local player may not add units for them, with {@code skills}.
     */
    Outcome add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
        Player local = session.localPlayer();
        if (local == null) {
            return Outcome.NONE;
        }
        List<Entity> entities = new ArrayList<>();
        for (int copy = 0; copy < clampQuantity(quantity); copy++) {
            Entity entity = units.read(unit);
            if (entity == null) {
                break;
            }
            entities.add(entity);
        }
        if (entities.isEmpty()) {
            return Outcome.NONE;
        }
        Player owner = commands.addUnits(entities, session.game().getPlayer(ownerId), skills);
        return (owner == null) ? Outcome.NONE : new Outcome(owner.getId(), entities.size());
    }

    /** Takes the unit {@code id} back out of the game, without asking, when the local player may delete it. */
    void remove(int id) {
        Entity entity = session.game().getEntity(id);
        if (entity != null) {
            commands.delete(new ArrayList<>(), new ArrayList<>(List.of(entity)), false);
        }
    }
}
