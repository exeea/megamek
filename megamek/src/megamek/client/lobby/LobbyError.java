/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.text.MessageFormat;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;

/**
 * The messages the lobby shows when it refuses a command. Each one carries either its literal text or the key of its
 * message in the client's resource bundle, and optionally the key of a title; a view shows {@link #message(Object...)}
 * under {@link #title()}, or as a plain notice when there is no title.
 */
public enum LobbyError {
    ONLY_OWN_BOT("Can only remove bots that were added in this lobby."),
    SINGLE_UNIT_OR_FORCE("Please select a single unit or a single force."),
    SINGLE_OWNER("For this action, the selected units must have a single owner."),
    FORCE_ATTACH_TO_SUB_FORCE("Cannot attach a force to its own sub force."),
    NO_DETACH_OFF_BOARD("An off board train cannot be uncoupled. "
          + "There is no board position to leave a trailer at."),
    ONLY_TEAM("Combinations like loading, C3 connections and shared forces are only valid within a team."),
    ONLY_C3M("Only units with a C3M can be set to be Company Masters."),
    NO_DUAL_LOAD("It is not possible to re-load two units to a new transport where one unit currently carries the "
          + "other. Please unload the units first."),
    NO_DUAL_TOW("Both units must have an open appropriate tow hitch."),
    ONLY_TRAIN_MEMBERS("Select one tractor and the trailers to hitch behind it. "
          + "Units that are already part of a train must be detached first."),
    SINGLE_TRACTOR_REQUIRED("Select exactly one tractor to head the train. "
          + "Every other selected unit must be a trailer."),
    NO_SUCH_BAY("The unit does not have that bay."),
    SQUADRON_TOO_MANY(null, "FighterSquadron.toomany", null),
    ONLY_FIGHTERS("Only aerospace and conventional fighters can join squadrons."),
    LOAD_ONLY_ALLIED("Can only load units that are allied with each other."),
    EXCEED_C3_CAPACITY("Connecting the selected units exceed this C3 system's capacity."),
    SAME_C3("The C3 systems of the selected units don't match. Select only the same type of C3 units."),
    CANNOT_CONFIG_ENEMIES("Cannot configure units of other players except units of your bots."),
    CANNOT_VIEW_HIDDEN("Cannot view or set details on hidden units."),
    CANNOT_EDIT_DAMAGE("The Game Master edits the damage of every unit. "
          + "When there is no Game Master, you may edit the damage of your own units."),
    /** Takes the action that cannot be done, such as "assign damage". */
    SINGLE_UNIT("Cannot {0} for more than one unit at a time."),
    TEN_UNITS("Please select fewer than 10 units."),
    HEAT_TRACKING("Cannot apply a heat setting to units that do not track heat."),
    ONLY_MEKS("This setting can only be applied to Meks."),
    ONLY_TEAMMATE("Can only reassign a force to a teammate when reassigning without units."),
    ENTITY_OR_FORCE("Please select either only forces or only units."),
    FORCE_EMPTY("Please select only empty forces."),
    SBF_CONVERSION("At least some of the forces you selected cannot be "
          + "converted to SBF Formations. Please select only the topmost forces to be converted, no subForces. "
          + "A converted force must conform to the rules given in Interstellar Operations. Conversion "
          + "will typically work with companies created in the Force Generator."),
    PLAYER_DONE("Cannot edit units while your status is Done."),
    CANNOT_DISCONNECT_MASTER(null, "LobbyErrors.cannotDisconnectMaster", null),
    /** Takes the message of the failed read. */
    ADF_READ(null, null, null),
    /** Takes the message explaining why the units do not fit. */
    LOADING_BAY(null, null, "LoadingBay.error"),
    SQUADRON_BOMBS(null, "FighterSquadron.bomberror", "FighterSquadron.bomberror");

    private final @Nullable String text;
    private final @Nullable String messageKey;
    private final @Nullable String titleKey;

    LobbyError(String text) {
        this(text, null, null);
    }

    LobbyError(@Nullable String text, @Nullable String messageKey, @Nullable String titleKey) {
        this.text = text;
        this.messageKey = messageKey;
        this.titleKey = titleKey;
    }

    /**
     * @param args the arguments of a message that takes them, such as the action of {@link #SINGLE_UNIT}
     *
     * @return the message to show
     */
    public String message(Object... args) {
        if (messageKey != null) {
            return Messages.getString(messageKey);
        }
        if (text == null) {
            return (args.length > 0) ? String.valueOf(args[0]) : "";
        }
        // only formatted when there are arguments, as formatting would drop the apostrophes of the other texts
        return (args.length > 0) ? MessageFormat.format(text, args) : text;
    }

    /**
     * @return the title to show the message under, or {@code null} for a plain notice
     */
    public @Nullable String title() {
        return (titleKey == null) ? null : Messages.getString(titleKey);
    }
}
