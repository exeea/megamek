/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.units.Entity;

/**
 * Everything the lobby's commands need from the person using the lobby: telling them why a command was refused,
 * asking them to confirm or name something, and the editors for units. A view implements it with its own dialogs.
 * Unless noted, each method returns once the player has answered.
 */
public interface LobbyPrompts {

    /**
     * The outcome of a unit configuration editor.
     *
     * @param confirmed {@code true} when the player accepted the changes
     * @param next      the unit the player asked to configure next, or {@code null} when they are done
     */
    record CustomizeResult(boolean confirmed, @Nullable Entity next) {}

    /**
     * Tells the player why a command was refused.
     *
     * @param error the reason
     * @param args  the arguments of an error that takes them
     */
    void error(LobbyError error, Object... args);

    /**
     * @param title    the question's title
     * @param question the question
     *
     * @return {@code true} when the player said yes
     */
    boolean confirm(String title, String question);

    /**
     * Tells the player why they cannot be ready yet.
     *
     * @param blocker what keeps them from being ready
     */
    void readyBlocked(ReadyCheck.Blocker blocker);

    /**
     * @param request the names to offer and the force's current name
     *
     * @return the chosen name, trimmed, or empty when cancelled or left blank
     */
    Optional<String> chooseForceName(ForceNameRequest request);

    /** @return the designation for a new fighter squadron, or empty when cancelled */
    Optional<String> chooseSquadronDesignation();

    /**
     * Shows a camouflage chooser for units.
     *
     * @param initial           the camouflage to start from
     * @param hasIndividualCamo {@code true} when any of the units has a camouflage other than its owner's
     * @param units             the units to show the camouflage on
     *
     * @return the chosen camouflage, or empty when cancelled
     */
    Optional<Camouflage> chooseCamo(Camouflage initial, boolean hasIndividualCamo, List<Entity> units);

    /**
     * Shows the unit configuration editor for several units at once. The editor changes the units directly.
     *
     * @param client   the client the units are configured for: the local player's or a local bot's
     * @param units    the units
     * @param editable {@code false} to only show the configuration
     *
     * @return whether the changes were accepted and which unit to configure next
     */
    CustomizeResult customizeUnits(Client client, List<Entity> units, boolean editable);

    /**
     * Shows the unit configuration editor for a single unit. The editor changes the unit directly.
     *
     * @param client   the client the unit is configured for: the local player's or a local bot's
     * @param unit     the unit
     * @param editable {@code false} to only show the configuration
     *
     * @return whether the changes were accepted and which unit to configure next
     */
    CustomizeResult customizeUnit(Client client, Entity unit, boolean editable);

    /**
     * Shows the crew editor of a Battlefield Support Asset and, when accepted and editable, applies the choices.
     *
     * @param asset    the asset
     * @param editable {@code false} to only show the crew
     *
     * @return {@code true} when the choices were applied
     */
    boolean configureAsset(BattlefieldSupportAsset asset, boolean editable);

    /**
     * Shows the damage editor for a unit, which changes the unit directly.
     *
     * @param unit the unit, not a Battlefield Support Asset
     */
    void editDamage(Entity unit);

    /**
     * Shows the damage editor of a Battlefield Support Asset and, when accepted, applies the choices.
     *
     * @param asset the asset
     *
     * @return {@code true} when the choices were applied
     */
    boolean editAssetDamage(BattlefieldSupportAsset asset);

    /**
     * Asks for the order of the trailers behind a tractor.
     *
     * @param head     the tractor heading the train
     * @param trailers the trailers to hitch behind it
     *
     * @return the trailers in the chosen order, or empty when cancelled
     */
    Optional<List<Entity>> orderTrain(Entity head, List<Entity> trailers);

    /**
     * Shows the Strategic BattleForce stats of forces. May return before the view is closed.
     *
     * @param forces the forces, each convertible to an SBF formation
     * @param game   the game
     */
    void showSbfStats(Collection<Force> forces, Game game);

    /**
     * Shows the Alpha Strike stats of units. May return before the view is closed.
     *
     * @param units the units
     */
    void showAlphaStrikeStats(Collection<Entity> units);
}
