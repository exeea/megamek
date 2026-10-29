/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static megamek.common.bays.Bay.UNSET_BAY;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmorHandlesTank;
import megamek.common.bays.Bay;
import megamek.common.equipment.TankTrailerHitch;
import megamek.common.equipment.Transporter;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;

/** The lobby's rules for starting positions, blind drop and loading units, independent of any view. */
public final class LobbyRules {

    private LobbyRules() {}

    /**
     * Returns true when the starting position of the given player is valid in the given game. This is not the case only
     * when the options "Double Blind" and "Exclusive Starting Positions" are on and the starting position overlaps with
     * that of other players, if "Teams Share Vision" is off, or enemy players, if "Teams Share Vision" is on.
     * <p>
     * See also {@link #startPosOverlap(int, int)}
     */
    public static boolean isValidStartPos(Game game, Player player) {
        return isValidStartPos(game, player, player.getStartingPos());
    }

    /**
     * Returns true when the given starting position pos is valid for the given player in the given game. This is not
     * the case only when the options "Double Blind" and "Exclusive Starting Positions" are on and the starting position
     * overlaps with that of other players, if "Teams Share Vision" is off, or enemy players, if "Teams Share Vision" is
     * on.
     * <p>
     * See also {@link #startPosOverlap(int, int)}
     */
    public static boolean isValidStartPos(Game game, Player player, int pos) {
        if (!isExclusiveDeployment(game)) {
            return true;
        } else {
            final var gOpts = game.getOptions();
            List<Player> players = game.getPlayersList();

            if (gOpts.booleanOption(OptionsConstants.BASE_SET_PLAYER_DEPLOYMENT_TO_PLAYER_0) &&
                  !player.isBot() &&
                  player.getId() != 0) {
                return true;
            }

            if (gOpts.booleanOption(OptionsConstants.BASE_SET_PLAYER_DEPLOYMENT_TO_PLAYER_0)) {
                players = players.stream().filter(p -> p.isBot() || p.getId() == 0).toList();
            }

            if (isTeamsShareVision(game)) {
                return players.stream()
                      .filter(p -> p.isEnemyOf(player))
                      .noneMatch(p -> startPosOverlap(pos, p.getStartingPos()));
            } else {
                return players.stream()
                      .filter(p -> !p.equals(player))
                      .noneMatch(p -> startPosOverlap(pos, p.getStartingPos()));
            }
        }
    }

    /**
     * Returns true when double-blind and exclusive deployment are on, meaning that player's deployment zones may not
     * overlap.
     */
    public static boolean isExclusiveDeployment(Game game) {
        final var gOpts = game.getOptions();
        return gOpts.booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND) &&
              gOpts.booleanOption(OptionsConstants.BASE_EXCLUSIVE_DB_DEPLOYMENT);
    }

    /**
     * Returns true when blind drop is on.
     */
    public static boolean isBlindDrop(Game game) {
        final var gOpts = game.getOptions();
        return gOpts.booleanOption(OptionsConstants.BASE_BLIND_DROP);
    }

    /**
     * Returns true when real blind drop is on.
     */
    public static boolean isRealBlindDrop(Game game) {
        final var gOpts = game.getOptions();
        return gOpts.booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP);
    }

    /**
     * Returns true when teams share vision is on, regardless of whether double-blind is on.
     */
    public static boolean isTeamsShareVision(Game game) {
        final var gOpts = game.getOptions();
        return gOpts.booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);
    }

    /** Returns true if the given entities all belong to the same player. */
    public static boolean haveSingleOwner(Collection<Entity> entities) {
        return entities.stream().mapToInt(e -> e.getOwner().getId()).distinct().count() == 1;
    }

    /**
     * Returns true when the entities can embark onto loader given the other constraints. If false, the passed errorMsg
     * contains a suitable error message for display.
     */
    public static boolean validateLobbyLoad(Collection<Entity> entities, Entity loader, int bayNumber, boolean loadRear,
          StringBuilder errorMsg) {
        // ProtoMek loading uses only 1 entity, get that (doesn't matter if it's something else)
        Optional<Entity> optionalProtoMek = entities.stream().findFirst();
        double capacity;
        boolean hasEnoughCargoCapacity = false;
        String errorMessage = "";

        if (optionalProtoMek.isPresent()) {
            Entity soleProtoMek = entities.stream().findAny().get();

            if (bayNumber != UNSET_BAY) {
                Bay bay = loader.getBayById(bayNumber);
                if (bay != null) {
                    double loadSize = entities.stream().mapToDouble(bay::spaceForUnit).sum();
                    capacity = bay.getUnused();
                    hasEnoughCargoCapacity = loadSize <= capacity;
                    errorMessage = Messages.getString("LoadingBay.baytoomany",
                          (int) bay.getUnusedSlots(),
                          bay.getDefaultSlotDescription());
                } else if (loader.hasETypeFlag(Entity.ETYPE_MEK) && soleProtoMek.hasETypeFlag(Entity.ETYPE_PROTOMEK)) {
                    // We're also using bay number to distinguish between front and rear locations
                    // for ProtoMek mag clamp systems
                    hasEnoughCargoCapacity = entities.size() == 1;
                    errorMessage = Messages.getString("LoadingBay.protostoomany");
                } else if (loader.getTransports().get(Integer.MAX_VALUE - bayNumber).canLoad(soleProtoMek)) {
                    // FIXME #7640: Update once we can properly specify any transporter an entity has, and properly load
                    //  into that transporter.
                    hasEnoughCargoCapacity = entities.size() == 1;
                } else {
                    errorMessage = Messages.getString("LoadingBay.bayNumberNotFound", bayNumber);
                }
            } else {
                HashMap<Long, Double> capacities = new HashMap<>();
                HashMap<Long, Double> counts = new HashMap<>();
                HashMap<Transporter, Double> potentialLoad = new HashMap<>();
                // Get the counts and capacities for all present types
                for (Entity e : entities) {
                    long entityType = e.getEntityType();
                    long loaderType = loader.getEntityType();
                    double unitSize;
                    if ((entityType & Entity.ETYPE_MEK) != 0) {
                        entityType = Entity.ETYPE_MEK;
                        unitSize = 1;
                    } else if ((entityType & Entity.ETYPE_INFANTRY) != 0) {
                        entityType = Entity.ETYPE_INFANTRY;
                        boolean useCount = true;
                        if ((loaderType & Entity.ETYPE_TANK) != 0) {
                            // This is a super hack... When getting
                            // capacities, troopspace gives unused space in
                            // terms of tons, and BattleArmorHandles gives
                            // it in terms of unit count. If I call
                            // getUnused, it sums these together, and is
                            // meaningless, so we'll go through all
                            // transporters....
                            boolean hasTroopSpace = false;
                            for (Transporter t : loader.getTransports()) {
                                if (t instanceof TankTrailerHitch) {
                                    continue;
                                }
                                double loadWeight = e.getWeight();
                                if (potentialLoad.containsKey(t)) {
                                    loadWeight += potentialLoad.get(t);
                                }
                                if (!(t instanceof BattleArmorHandlesTank) &&
                                      t.canLoad(e) &&
                                      (loadWeight <= t.getUnused())) {
                                    hasTroopSpace = true;
                                    potentialLoad.put(t, loadWeight);
                                    break;
                                }
                            }
                            if (hasTroopSpace) {
                                useCount = false;
                            }
                        }
                        // TroopSpace uses tonnage
                        // bays and BA handlebars use a count
                        if (useCount) {
                            unitSize = 1;
                        } else {
                            unitSize = e.getWeight();
                        }
                    } else if ((entityType & Entity.ETYPE_PROTOMEK) != 0) {
                        entityType = Entity.ETYPE_PROTOMEK;
                        unitSize = 1;
                        // Loading using mag clamps; user can specify front or rear.
                        // Make use of bayNumber field
                        if ((loaderType & Entity.ETYPE_MEK) != 0) {
                            bayNumber = loadRear ? 1 : 0;
                        }
                    } else if ((entityType & Entity.ETYPE_DROPSHIP) != 0) {
                        entityType = Entity.ETYPE_DROPSHIP;
                        unitSize = 1;
                    } else if ((entityType & Entity.ETYPE_JUMPSHIP) != 0) {
                        entityType = Entity.ETYPE_JUMPSHIP;
                        unitSize = 1;
                    } else if ((entityType & Entity.ETYPE_AERO) != 0) {
                        entityType = Entity.ETYPE_AERO;
                        unitSize = 1;
                    } else if ((entityType & Entity.ETYPE_TANK) != 0) {
                        entityType = Entity.ETYPE_TANK;
                        unitSize = 1;
                    } else {
                        unitSize = 1;
                    }

                    Double count = counts.get(entityType);
                    if (count == null) {
                        count = 0.0;
                    }
                    count = count + unitSize;
                    counts.put(entityType, count);

                    Double cap = capacities.get(entityType);
                    if (cap == null) {
                        cap = loader.getUnused(e);
                        capacities.put(entityType, cap);
                    }
                }
                hasEnoughCargoCapacity = true;
                for (Long typeId : counts.keySet()) {
                    double currCount = counts.get(typeId);
                    double currCapacity = capacities.get(typeId);
                    if (currCount > currCapacity) {
                        hasEnoughCargoCapacity = false;
                        capacity = currCapacity;
                        String messageName;
                        if (typeId == Entity.ETYPE_INFANTRY) {
                            messageName = "LoadingBay.nonbaytoomanyInf";
                        } else {
                            messageName = "LoadingBay.nonbaytoomany";
                        }
                        errorMessage = Messages.getString(messageName,
                              currCount,
                              Entity.getEntityTypeName(typeId),
                              currCapacity);
                    }
                }
            }
            if (loader instanceof FighterSquadron &&
                  entities.stream().anyMatch(e -> !e.isFighter() || e instanceof FighterSquadron)) {
                errorMessage = "Only aerospace and conventional fighters can join squadrons.";
                hasEnoughCargoCapacity = false;
            }
        }

        errorMsg.append(errorMessage);
        return hasEnoughCargoCapacity;
    }

    /**
     * Returns true when the two starting positions overlap, i.e. if they are equal or adjacent (e.g. E and NE, SW and
     * S). ANY overlaps all others.
     */
    private static boolean startPosOverlap(int pos1, int pos2) {
        if (pos1 > 10) {
            pos1 -= 10;
        }
        if (pos2 > 10) {
            pos2 -= 10;
        }
        if (pos1 == pos2) {
            return true;
        }
        int a = Math.max(pos1, pos2);
        int b = Math.min(pos1, pos2);
        // Out of bounds values:
        if (b < 0 || a > 10) {
            throw new IllegalArgumentException("The given starting position is invalid!");
        }
        // ANY (0) overlaps all others, EDG (9) overlaps all others but CTR (10)
        if (b == 0 || a == 9) {
            return true;
        }
        // EDG and CTR don't overlap
        if (a == 10 && b == 9) {
            return false;
        }
        // the rest of the positions overlap if they're 1 apart
        // NW = 1 and W = 8 also overlap
        return ((a - b == 1) || (a == 8 && b == 1));
    }
}
