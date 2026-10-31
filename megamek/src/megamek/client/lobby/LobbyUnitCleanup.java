/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Collection;

import megamek.common.enums.NeuralInterfaceMode;
import megamek.common.options.IGameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.options.PilotOptions;
import megamek.common.units.Entity;

/**
 * Takes from the lobby's units what the game's options don't allow, as the lobby is shown: pilot abilities, edge and
 * implants that are switched off, partial repairs, and hidden, prone or hull down deployment of carried units or, without
 * hidden units, hiding at all. The changes are local; they reach the server with the next update of each unit.
 */
public final class LobbyUnitCleanup {

    private LobbyUnitCleanup() {
    }

    /** Takes from {@code units} what {@code options} don't allow. */
    public static void apply(Collection<Entity> units, IGameOptions options) {
        for (Entity entity : units) {
            apply(entity, options);
        }
    }

    /** Takes from {@code entity} what {@code options} don't allow. */
    public static void apply(Entity entity, IGameOptions options) {
        if (!options.booleanOption(OptionsConstants.RPG_PILOT_ADVANTAGES)) {
            entity.getCrew().clearOptions(PilotOptions.LVL3_ADVANTAGES);
        }
        if (!options.booleanOption(OptionsConstants.EDGE)) {
            entity.getCrew().clearOptions(PilotOptions.EDGE_ADVANTAGES);
        }
        if (!NeuralInterfaceMode.from(options).allowsImplants()) {
            entity.getCrew().clearOptions(PilotOptions.MD_ADVANTAGES);
            entity.getCrew().clearOptions(PilotOptions.EI_ADVANTAGES);
        }
        if (!options.booleanOption(OptionsConstants.ADVANCED_STRATOPS_PARTIAL_REPAIRS)) {
            entity.clearPartialRepairs();
        }
        if (entity.getTransportId() != Entity.NONE) {
            entity.setHidden(false);
            entity.setProne(false);
            entity.setHullDown(false);
        }
        if (!options.booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
            entity.setHidden(false);
        }
    }
}
