/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.ui.clientGUI.UnitOwnerColors;
import megamek.client.ui.dialogs.minimap.MinimapUnitSymbols;
import megamek.common.Player;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.Targetable;

/**
 * A game's summary for the animated GIF the player may ask for when it ends ({@link GameSummaryGif}): at the end of
 * each deployment, movement, firing and physical phase, the bare minimum to draw it, recorded from the client's own
 * game as the local player saw it. Each unit the player sees stands on its hex with its facing and the minimap's
 * symbol in its owner's colour; a wreck is marked destroyed; each attack is a line from its unit to the hex it
 * attacked. Plain data only, about 120 KB for 20 rounds of 24 units; nothing is drawn unless the GIF is asked for.
 */
public final class GameSummary {
    /** One phase end: the units as the local player saw them, and the phase's attacks. */
    record Frame(int round, GamePhase phase, List<Unit> units, List<Attack> attacks) { }

    /**
     * A unit on hex ({@code x}, {@code y}) of board {@code board}, facing 0 (north) to 5 clockwise, with its minimap
     * {@code symbol} in {@code colour} (its owner's or team's, as an RGB value); {@code moved} in the phase.
     */
    record Unit(int id, int board, int x, int y, int facing, MinimapUnitSymbols.Symbol symbol, int colour,
          boolean moved, boolean destroyed) { }

    /** A unit's attack on hex ({@code x}, {@code y}): a weapon attack, or a physical one (punch, kick, charge...). */
    record Attack(int attacker, int x, int y, boolean physical) { }

    private final List<Frame> frames = new ArrayList<>();
    /** Each unit's symbol, taken once: a unit's kind does not change. */
    private final Map<Integer, MinimapUnitSymbols.Symbol> symbols = new HashMap<>();

    /**
     * Records the phase that has just ended, if the summary shows it (deployment, movement, firing, physical; the
     * victory adds the final state). The game's pending actions still hold the phase's attacks when its end is
     * announced. Client thread.
     */
    public synchronized void phaseEnded(Game game, Player local, GamePhase phase) {
        if (local == null || !(phase.isDeployment() || phase.isMovement() || phase.isFiring() || phase.isPhysical()
              || phase.isVictory())) {
            return;
        }
        List<Unit> units = new ArrayList<>();
        for (Entity entity : game.getEntitiesVector()) {
            unit(entity, local, game, false, units);
        }
        game.getWreckedEntities().asIterator().forEachRemaining(wreck -> unit(wreck, local, game, true, units));
        Set<Attack> attacks = new LinkedHashSet<>();
        if (phase.isFiring() || phase.isPhysical()) {
            GpuBattleStatus.declared(game, local, phase.isPhysical()).forEach(attack -> {
                Targetable target = game.getTarget(attack.getTargetType(), attack.getTargetId());
                Coords at = target == null ? null : target.getPosition();
                if (at != null) {
                    // A volley of several weapons at one target is one line
                    attacks.add(new Attack(GpuBattleStatus.attacker(game, attack).getId(), at.getX(), at.getY(),
                          !(attack instanceof WeaponAttackAction)));
                }
            });
        }
        frames.add(new Frame(game.getRoundCount(), phase, List.copyOf(units), List.copyOf(attacks)));
    }

    /** Adds {@code entity} when it stands on a board and the local player sees it (own units always). */
    private void unit(Entity entity, Player local, Game game, boolean destroyed, List<Unit> units) {
        Coords position = entity.getPosition();
        if (position == null || entity.getOwnerId() != local.getId()
              && (!EntityVisibilityUtils.detectedOrHasVisual(local, game, entity)
              || EntityVisibilityUtils.onlyDetectedBySensors(local, entity))) {
            return;
        }
        MinimapUnitSymbols.Symbol symbol = symbols.computeIfAbsent(entity.getId(),
              id -> MinimapUnitSymbols.symbol(entity));
        units.add(new Unit(entity.getId(), entity.getBoardId(), position.getX(), position.getY(), entity.getFacing(),
              symbol, UnitOwnerColors.colorFor(entity.getOwner(), local, game).getRGB(),
              entity.moved != EntityMovementType.MOVE_NONE, destroyed));
    }

    /** The frames recorded so far, oldest first. */
    synchronized List<Frame> frames() {
        return List.copyOf(frames);
    }
}
