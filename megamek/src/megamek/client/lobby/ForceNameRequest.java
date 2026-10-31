/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;
import megamek.common.Player;
import megamek.common.force.ForceNames;
import megamek.common.game.Game;

/**
 * A request for a force's name: the names to offer, which suit its side and read well on the radio ("Charlie Lance",
 * "Gamma Star"), and the name it has now. Any name typed in is taken as long as it is not blank.
 *
 * @param owner       the player the force belongs to, or {@code null} when unknown
 * @param currentName the force's name now, or {@code null} for a new force
 * @param suggestions the names to offer
 */
public record ForceNameRequest(@Nullable Player owner, @Nullable String currentName, List<String> suggestions) {

    /**
     * @param game        the game, for the owner's units
     * @param owner       the player the force belongs to
     * @param currentName the force's name now, or {@code null} for a new force
     *
     * @return a request offering the names that suit the owner's side
     */
    public static ForceNameRequest of(Game game, @Nullable Player owner, @Nullable String currentName) {
        ForceNames.Style style = ForceNames.styleFor((owner == null) ? null : owner.getName(),
              (owner == null) ? List.of() : game.getPlayerEntities(owner, false));
        return new ForceNameRequest(owner, currentName, ForceNames.suggestions(style));
    }

    /**
     * @param chosen what the player entered or picked, or {@code null}
     *
     * @return the name, trimmed, or empty when nothing or only blanks were entered
     */
    public static Optional<String> accept(@Nullable Object chosen) {
        if ((chosen == null) || chosen.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(chosen.toString().trim());
    }
}
