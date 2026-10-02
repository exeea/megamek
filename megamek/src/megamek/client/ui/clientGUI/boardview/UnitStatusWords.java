/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.actions.LayExplosivesAttackAction;
import megamek.common.annotations.Nullable;
import megamek.common.equipment.HandheldWeapon;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Aero;
import megamek.common.units.Building;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.FighterSquadron;
import megamek.common.units.IAero;
import megamek.common.units.Infantry;
import megamek.common.units.Tank;

/**
 * The status words and small tiles of a unit's board label as data: the board label ({@link UnitAnnotations})
 * and the native HUD both read them, so each status rule exists once.
 */
public final class UnitStatusWords {
    private UnitStatusWords() { }

    /** How urgent a unit status word is: the warning, caution and precaution colours of the labels, or plain. */
    public enum Severity { WARNING, CAUTION, PRECAUTION, INFO }

    /**
     * One unit status word as data for the native HUD: {@code key} is its {@code BoardView1} message key (for example
     * "PRONE"), or the word itself for the unlocalized infantry words "Working", "Deck" and "Rigging", and
     * {@code label} the text the board label shows. A small label tile ({@link #statusTiles}) is keyed by its kind.
     */
    public record StatusWord(String key, String label, Severity severity) { }

    /**
     * The small tiles the board label of this unit shows beside its words, in label order. Each is keyed by its kind
     * and labelled with the tile's text: "ALT" (airborne, not on a space map; the label puts an "A" tile before it)
     * or "ELEV" (elevation other than zero) with the number, then the letters "T" (transporting units), "U" (never
     * seen by the enemy) or "H" (hidden from it), both only on units whose visibility the local player tracks under
     * double blind, "N" (narc pods attached), "D" (dug in or fortifying) and "E" (laying explosives). The label's
     * damage-level tile is left out.
     *
     * @param entity      the unit
     * @param spaceMap    whether the unit is on a space map, where the label shows no altitude
     * @param localPlayer the player the board is shown to, or null
     *
     * @return the tiles, never null
     */
    public static List<StatusWord> statusTiles(Entity entity, boolean spaceMap, @Nullable Player localPlayer) {
        List<StatusWord> tiles = new ArrayList<>();
        if (entity.isAirborne()) {
            if (!spaceMap) {
                tiles.add(new StatusWord("ALT", Integer.toString(entity.getAltitude()), Severity.INFO));
            }
        } else if (entity.getElevation() != 0) {
            tiles.add(new StatusWord("ELEV", Integer.toString(entity.getElevation()), Severity.INFO));
        }

        // Transporting (but not Squadrons that are obviously composed of subunits)
        if (!entity.getLoadedUnits().isEmpty() && !(entity instanceof FighterSquadron)) {
            tiles.add(tile("T", Severity.CAUTION));
        }

        // Unseen or hidden: only on own units, or teammates' under team vision
        if (EntityVisibilityUtils.trackThisEntitiesVisibilityInfo(localPlayer, entity)) {
            if (!entity.isEverSeenByEnemy()) {
                tiles.add(tile("U", Severity.INFO));
            } else if (!entity.isVisibleToEnemy()) {
                tiles.add(tile("H", Severity.INFO));
            }
        }

        if (entity.hasAnyTypeNarcPodsAttached()) {
            tiles.add(tile("N", Severity.WARNING));
        }

        // Infantry dug in, digging in or fortifying, and laying explosives
        if (entity instanceof Infantry inf) {
            if ((inf.getDugIn() != Infantry.DUG_IN_NONE) || inf.isFortifying()) {
                tiles.add(tile("D", Severity.PRECAUTION));
            }
            if (inf.turnsLayingExplosives >= 0) {
                tiles.add(tile("E", Severity.PRECAUTION));
            }
        }

        // Tank fortifying
        if ((entity instanceof Tank tank) && tank.isFortifying()) {
            tiles.add(tile("D", Severity.PRECAUTION));
        }
        return List.copyOf(tiles);
    }

    private static StatusWord tile(String letter, Severity severity) {
        return new StatusWord(letter, letter, severity);
    }

    /**
     * The status words the board label of this unit shows, in label order, such as "PRONE" (caution) or "SD" for a
     * shutdown (warning, or caution when manual). The label's small tiles are {@link #statusTiles} (its damage-level
     * tile is in neither list).
     *
     * @param entity        the unit
     * @param affectedByEcm whether hostile ECM affects the unit in its hex, as the board view computes it for its
     *                      sprites
     *
     * @return the words, never null
     */
    public static List<StatusWord> statusWords(Entity entity, boolean affectedByEcm) {
        List<StatusWord> words = new ArrayList<>();

        // Determine if the entity has a locked turret and if it is a gun emplacement
        boolean turretLocked = false;
        int crewStunned = 0;
        if (entity instanceof Tank tankEntity) {
            turretLocked = !tankEntity.hasNoTurret() && !tankEntity.canChangeSecondaryFacing();
            crewStunned = tankEntity.getStunnedTurns();
        } else if (entity instanceof AbstractBuildingEntity buildingEntity) {
            // Advanced Building critical hits (TO:AR p. 118) stun the gunners or lock a turret
            turretLocked = buildingEntity.hasLockedTurret();
            crewStunned = buildingEntity.getStunnedTurns();
        }

        // Shutdown
        if (entity.isManualShutdown()) {
            words.add(word(Severity.CAUTION, "SHUTDOWN"));
        } else if (entity.isShutDown()) {
            words.add(word(Severity.WARNING, "SHUTDOWN"));
        }

        // Prone, Hull down, Stuck, Immobile, Jammed
        if (entity.isProne()) {
            words.add(word(Severity.CAUTION, "PRONE"));
        }

        if (!entity.getHiddenActivationPhase().isUnknown()) {
            words.add(word(Severity.PRECAUTION, "ACTIVATING"));
        }

        if (entity.isHidden()) {
            words.add(word(Severity.PRECAUTION, "HIDDEN"));
        }

        if (entity.isGyroDestroyed()) {
            words.add(word(Severity.WARNING, "NO_GYRO"));
        }

        if (entity.isHullDown()) {
            words.add(word(Severity.PRECAUTION, "HULLDOWN"));
        }

        if (entity.isStuck()) {
            words.add(word(Severity.CAUTION, "STUCK"));
        }

        if (!isStaticEntity(entity) && entity.isImmobile()) {
            words.add(word(Severity.WARNING, "IMMOBILE"));
        }

        if ((entity instanceof ConvInfantry infantry) && infantry.isExhaustedFromFastMove()) {
            words.add(word(Severity.WARNING, "EXHAUSTED"));
        }

        if (entity.isBracing()) {
            words.add(word(Severity.PRECAUTION, "BRACING"));
        }

        if (affectedByEcm) {
            words.add(word(Severity.CAUTION, "Jammed"));
        }

        // Turret Lock
        if (turretLocked) {
            words.add(word(Severity.CAUTION, "LOCKED"));
        }

        // Grappling & Swarming
        if (entity.getGrappled() != Entity.NONE) {
            if (entity.isGrappleAttacker()) {
                words.add(word(Severity.CAUTION, "GRAPPLER"));
            } else {
                words.add(word(Severity.WARNING, "GRAPPLED"));
            }
        }
        if (entity.getSwarmAttackerId() != Entity.NONE) {
            words.add(word(Severity.WARNING, "SWARMED"));
        }

        if (!entity.getAllTowedUnits().isEmpty()) {
            words.add(word(Severity.CAUTION, "TOWING"));
        }

        // Large Craft Ejecting
        if ((entity instanceof Aero aero) && aero.isEjecting()) {
            words.add(word(Severity.CAUTION, "EJECTING"));
        }

        // Crew
        if (entity.getCrew().isDead()) {
            words.add(word(Severity.WARNING, "CrewDead"));
        }

        if (crewStunned > 0) {
            words.add(new StatusWord("STUNNED", Messages.getString("BoardView1.STUNNED", crewStunned),
                  Severity.CAUTION));
        }

        // Infantry
        if (entity instanceof Infantry inf) {
            int dig = inf.getDugIn();
            if (dig != Infantry.DUG_IN_COMPLETE) {
                if (inf.isFortifying()) {
                    // Multi-turn fortification: show how far along the build is (stage of total).
                    words.add(fortifying(inf.getFortifyStage(), inf.getFortifyTotalStages()));
                } else if (dig != Infantry.DUG_IN_NONE) {
                    words.add(new StatusWord("Working", "Working", Severity.PRECAUTION));
                } else if (inf.isHitTheDeck()) {
                    words.add(new StatusWord("Deck", "Deck", Severity.PRECAUTION));
                } else if (inf.isTakingCover()) {
                    words.add(word(Severity.PRECAUTION, "TakingCover"));
                }
            }

            if (inf.turnsLayingExplosives >= 0) {
                int turnsSpent = Math.min(inf.turnsLayingExplosives,
                      LayExplosivesAttackAction.MAX_TURNS_LAYING_EXPLOSIVES);
                // Keep this label short: non-small statuses draw centered in the hex-sized sprite buffer
                // and longer text gets clipped at its edges
                words.add(new StatusWord("Rigging",
                      "Rigging " + turnsSpent + "/" + LayExplosivesAttackAction.MAX_TURNS_LAYING_EXPLOSIVES,
                      Severity.PRECAUTION));
            }
        }

        // Tank
        if ((entity instanceof Tank tank) && tank.isFortifying()) {
            // Multi-turn fortification: show how far along the build is (stage of total).
            words.add(fortifying(tank.getFortifyStage(), tank.getFortifyTotalStages()));
        }

        // Aero
        if (entity.isAero()) {
            IAero a = (IAero) entity;
            if (a.isRolled()) {
                words.add(word(Severity.CAUTION, "ROLLED"));
            }

            if ((a.getCurrentFuel() <= 0) && a.requiresFuel()) {
                words.add(word(Severity.WARNING, "FUEL"));
            }

            if (entity.isEvading()) {
                words.add(word(Severity.INFO, "EVADE"));
            }

            if (a.isOutControlTotal() && a.isRandomMove()) {
                words.add(word(Severity.WARNING, "RANDOM"));
            } else if (a.isOutControlTotal()) {
                words.add(word(Severity.WARNING, "CONTROL"));
            }
        }
        return List.copyOf(words);
    }

    private static StatusWord word(Severity severity, String key) {
        return new StatusWord(key, Messages.getString("BoardView1." + key), severity);
    }

    private static StatusWord fortifying(int stage, int stages) {
        return new StatusWord("fortifyProgress", Messages.getString("BoardView1.fortifyProgress", stage, stages),
              Severity.PRECAUTION);
    }

    static boolean isStaticEntity(Entity entity) {
        return entity.isBuildingEntityOrGunEmplacement() || entity instanceof HandheldWeapon
              || entity instanceof AbstractBuildingEntity;
    }
}
