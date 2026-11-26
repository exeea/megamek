/*
 * Copyright (C) 2021-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.common.strategicBattleSystems;

import static megamek.common.alphaStrike.BattleForceSUA.CAP;
import static megamek.common.alphaStrike.BattleForceSUA.FLK;
import static megamek.common.alphaStrike.BattleForceSUA.MSL;
import static megamek.common.alphaStrike.BattleForceSUA.SCAP;
import static megamek.common.alphaStrike.BattleForceSUA.SOA;
import static megamek.common.alphaStrike.BattleForceSUA.SRCH;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import megamek.common.Hex;
import megamek.common.board.BoardLocation;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASSpecialAbilityCollection;
import megamek.common.alphaStrike.ASSpecialAbilityCollector;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.jacksonAdapters.SBFUnitDeserializer;
import megamek.common.jacksonAdapters.SBFUnitSerializer;
import megamek.common.units.Terrains;

/**
 * Represents an SBF Unit (Ground SBF Unit or Aerospace Flight) which contains between 1 and 6 AlphaStrike elements and
 * is the building block of SBF Formations.
 */
@JsonRootName(value = "SBFUnit")
@JsonSerialize(using = SBFUnitSerializer.class)
@JsonDeserialize(using = SBFUnitDeserializer.class)
public class SBFUnit implements ASSpecialAbilityCollector, BattleForceSUAFormatter,
                                Serializable {

    private String name = "Unknown";
    private SBFElementType type = SBFElementType.UNKNOWN;
    private int size = 0;
    private int tmm = 0;
    private int movement = 0;
    private SBFMovementMode movementMode = SBFMovementMode.UNKNOWN;
    //TODO make JUMP a special?
    private int jumpMove = 0;
    private int trspMovement = 0;
    private SBFMovementMode trspMovementMode = SBFMovementMode.UNKNOWN;
    private int armor = 0;
    private int skill = 4;
    private ASDamageVector damage = ASDamageVector.ZERO;
    private int pointValue = 0;

    @JsonIgnore
    private final ASSpecialAbilityCollection specialAbilities = new ASSpecialAbilityCollection();
    private List<AlphaStrikeElement> elements = new ArrayList<>();

    // in game values
    private int currentArmor;
    private int damageCrits = 0;
    private int targetingCrits = 0;
    private int mpCrits = 0;
    /** Authored tactical specialization benefits; Heroic/Legendary Forces can repeat a selection (IO:BF p. 225). */
    public record TacticalSpecialization(int attack, int defense, String scenario, int scenarioLevels) implements Serializable {
        public TacticalSpecialization {
            if (attack < 0 || defense < 0 || scenarioLevels < 0 || Math.max(attack, Math.max(defense, scenarioLevels)) > 2
                  || attack + defense + scenarioLevels > 3 || (scenarioLevels > 0 && (scenario == null || scenario.isBlank()))) {
                throw new IllegalArgumentException("Invalid tactical specialization levels or scenario.");
            }
        }
    }

    /** One preferred environment. A zero initiative benefit still incurs the absent-environment penalty. */
    public record EnvironmentSpecialization(String terrain, Integer terrainLevel, String condition, int levels)
          implements Serializable {
        public EnvironmentSpecialization {
            boolean hasTerrain = terrain != null && !terrain.isBlank();
            boolean hasCondition = condition != null && !condition.isBlank();
            if (hasTerrain == hasCondition || levels < 0 || levels > 2 || (!hasTerrain && terrainLevel != null)) {
                throw new IllegalArgumentException("Choose one terrain or environmental condition and 0-2 initiative levels.");
            }
            if (hasTerrain && Terrains.getType(terrain.toLowerCase(Locale.ROOT)) == 0
                  && !terrain.equalsIgnoreCase("clear")) {
                throw new IllegalArgumentException("Unknown specialization terrain: " + terrain);
            }
        }
    }

    /** Enemy faction or command names, with one or two preferred enemies or doubled initiative (IO:BF pp. 225-226). */
    public record EnemySpecialization(List<String> enemies, int levels) implements Serializable {
        public EnemySpecialization {
            if (enemies == null || enemies.isEmpty() || enemies.size() > 2 || enemies.stream().anyMatch(String::isBlank)
                  || levels < 0 || levels > 2 || (enemies.size() == 2 && levels != 1)) {
                throw new IllegalArgumentException("Choose one preferred enemy at 0-2 levels, or two at one level.");
            }
            enemies = List.copyOf(enemies);
        }
    }

    private EnvironmentSpecialization environmentSpecialization;
    private EnemySpecialization enemySpecialization;
    private TacticalSpecialization tacticalSpecialization;

    public EnvironmentSpecialization getEnvironmentSpecialization() {
        return environmentSpecialization;
    }

    public void setEnvironmentSpecialization(EnvironmentSpecialization specialization) {
        environmentSpecialization = specialization;
    }

    public EnemySpecialization getEnemySpecialization() {
        return enemySpecialization;
    }

    public void setEnemySpecialization(EnemySpecialization specialization) {
        enemySpecialization = specialization;
    }

    public TacticalSpecialization getTacticalSpecialization() {
        return tacticalSpecialization;
    }

    public void setTacticalSpecialization(TacticalSpecialization specialization) {
        tacticalSpecialization = specialization;
    }

    /**
     * The Special Command Abilities a commander Unit carries (IO:BF p. 224: "tied to the Force Commander (COM) Unit or a
     * specific sub-commander ... If this Unit is destroyed, the ability is lost"), authored as "initiativeabilities".
     */
    public enum InitiativeAbility { BANKING, FORCING, ZONE_OF_CONTROL, COMMUNICATIONS_DISRUPTION, OVERRUN_COMBAT }

    private Map<BattleForceSUA, Set<SBFArtilleryAmmo>> artilleryLoadouts;

    /** Two alternate tube rounds, or one Arrow alternate alongside its normal ammunition, IO:BF p. 205. */
    public boolean loadArtillery(BattleForceSUA weapon, Set<SBFArtilleryAmmo> types) {
        if (!hasSUA(weapon) || types.size() > 2 || types.stream().anyMatch(a -> a == SBFArtilleryAmmo.STANDARD
              || a == SBFArtilleryAmmo.HOMING || !a.fits(weapon))) {
            return false;
        }
        if (artilleryLoadouts == null) {
            artilleryLoadouts = new EnumMap<>(BattleForceSUA.class);
        }
        artilleryLoadouts.put(weapon, types.isEmpty() ? EnumSet.noneOf(SBFArtilleryAmmo.class) : EnumSet.copyOf(types));
        return true;
    }

    public boolean carriesArtillery(BattleForceSUA weapon, SBFArtilleryAmmo ammo) {
        if (!hasSUA(weapon) || !ammo.fits(weapon)) {
            return false;
        }
        var alternates = artilleryLoadouts == null ? Set.of() : artilleryLoadouts.getOrDefault(weapon, Set.of());
        return (ammo == SBFArtilleryAmmo.STANDARD || ammo == SBFArtilleryAmmo.HOMING)
              ? !SBFArtilleryAmmo.isArrow(weapon) || alternates.size() < 2 : alternates.contains(ammo);
    }

    private Set<InitiativeAbility> initiativeAbilities;
    private int commandTier;
    private int spentArmorProtection;
    private Integer lastHarJelRound;

    /** Command abilities are attached to the granting COM/SCOM Unit (IO:BF p. 223). */
    public Set<InitiativeAbility> getInitiativeAbilities() {
        if (initiativeAbilities == null) {
            initiativeAbilities = EnumSet.noneOf(InitiativeAbility.class);
        }
        return initiativeAbilities;
    }

    /** Explicit scenario command tier (IO:BF pp. 98 and 195), zero when the Unit has no tier. */
    public int getCommandTier() {
        return commandTier;
    }

    public void setCommandTier(int tier) {
        if (tier < 0 || tier > 4) {
            throw new IllegalArgumentException("A field command Unit has tier 1 through 4.");
        }
        commandTier = tier;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SBFElementType getType() {
        return type;
    }

    public void setType(SBFElementType type) {
        this.type = type;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getTmm() {
        return tmm;
    }

    public void setTmm(int tmm) {
        this.tmm = tmm;
    }

    public int getMovement() {
        return movement;
    }

    public int getCurrentMovement() {
        return Math.max(0, movement - mpCrits);
    }

    public void setMovement(int movement) {
        this.movement = movement;
    }

    public int getTrspMovement() {
        return trspMovement;
    }

    public void setTrspMovement(int trspMovement) {
        this.trspMovement = trspMovement;
    }

    public String getTrspMovementCode() {
        return trspMovementMode.code;
    }

    public SBFMovementMode getTrspMovementMode() {
        return trspMovementMode;
    }

    public void setTrspMovementMode(SBFMovementMode mode) {
        trspMovementMode = mode;
    }

    public String getMovementCode() {
        return movementMode.code;
    }

    public SBFMovementMode getMovementMode() {
        return movementMode;
    }

    public void setMovementMode(SBFMovementMode mode) {
        movementMode = mode;
    }

    public int getJumpMove() {
        return jumpMove;
    }

    public void setJumpMove(int jumpMove) {
        this.jumpMove = jumpMove;
    }

    public int getSkill() {
        return skill;
    }

    public void setSkill(int skill) {
        this.skill = skill;
    }

    public int getPointValue() {
        return pointValue;
    }

    public void setPointValue(int pointValue) {
        this.pointValue = pointValue;
    }

    public int getArmor() {
        return armor;
    }

    /**
     * Sets the full (undamaged) armor of this SBF Unit. Also sets the current armor to the same value.
     *
     * @param armor The full undamaged armor of this Unit
     */
    public void setArmor(int armor) {
        this.armor = armor;
        currentArmor = armor;
    }

    /**
     * @return The current armor of this Unit, which is any value between the full (undamaged) armor and 0.
     */
    public int getCurrentArmor() {
        return currentArmor;
    }

    public ASDamageVector getDamage() {
        return damage;
    }

    public ASDamageVector getCurrentDamage() {
        return damage.reducedBy(damageCrits);
    }

    public void setDamage(ASDamageVector damage) {
        this.damage = damage;
    }

    public void setElements(List<AlphaStrikeElement> elements) {
        this.elements = elements;
    }

    public List<AlphaStrikeElement> getElements() {
        return new ArrayList<>(elements);
    }

    public ASSpecialAbilityCollection getSpecialAbilities() {
        return specialAbilities;
    }

    /**
     * Returns true if this SBF Unit is of the given type.
     */
    public boolean isType(SBFElementType tp) {
        return type == tp;
    }

    /**
     * Returns true if this SBF Unit is any of the given types.
     */
    public boolean isAnyTypeOf(SBFElementType type, SBFElementType... types) {
        return isType(type) || Arrays.stream(types).anyMatch(this::isType);
    }

    /**
     * Returns true if this SBF Unit represents an aerospace Unit.
     */
    public boolean isAerospace() {
        return type.isAerospace();
    }

    /**
     * Returns true if this SBF Unit represents a ground Unit.
     */
    public boolean isGround() {
        return !type.isAerospace();
    }

    @Override
    public boolean hasSUA(BattleForceSUA sua) {
        return specialAbilities.hasSUA(sua);
    }

    @Override
    public Object getSUA(BattleForceSUA sua) {
        return specialAbilities.getSUA(sua);
    }

    @Override
    public String getSpecialsDisplayString(String delimiter, BattleForceSUAFormatter element) {
        return specialAbilities.getSpecialsDisplayString(delimiter, element);
    }

    @Override
    public boolean showSUA(BattleForceSUA sua) {
        if ((type == SBFElementType.BM) && (sua == SOA || sua == SRCH)) {
            return false;
        }
        return (type != SBFElementType.V) || (sua != SRCH);
    }

    @Override
    public String formatSUA(BattleForceSUA sua, String delimiter, ASSpecialAbilityCollector collection) {
        return formatAbility(sua);
    }

    private String formatAbility(BattleForceSUA sua) {
        if (!specialAbilities.hasSUA(sua)) {
            return "";
        }
        Object suaObject = specialAbilities.getSUA(sua);
        if (sua.isAnyOf(BattleForceSUA.BHJ2, BattleForceSUA.BHJ3) && (suaObject instanceof Number rating)) {
            return sua + "-" + rating.intValue();
        } else if (sua.isAnyOf(BattleForceSUA.SAW, BattleForceSUA.ENG) && suaObject instanceof Number rating) {
            return sua.toString() + rating.intValue();
        } else if (!sua.isValidAbilityObject(suaObject)) {
            return "ERROR - wrong ability object (" + sua + ")";
        } else if (sua.isAnyOf(CAP, SCAP, MSL)) {
            return sua.toString();
        } else if (sua == FLK) {
            ASDamageVector flkDamage = specialAbilities.getFLK();
            return sua.toString() + flkDamage.M().damage + "/" + flkDamage.L().damage;
        } else if (sua.isTransport()) {
            String result = sua + suaObject.toString();
            BattleForceSUA door = sua.getDoor();
            if (isType(SBFElementType.LA)
                  && specialAbilities.hasSUA(door) && ((int) specialAbilities.getSUA(door) > 0)) {
                result += door.toString() + specialAbilities.getSUA(door);
            }
            return result;
        } else {
            return sua.toString() + (suaObject != null ? suaObject : "");
        }
    }

    @Override
    public String toString() {
        return "[SBFUnit] " + name + ": " + type + "; SZ" + size + "; TMM" + tmm + "; MV" + movement + movementMode.code
              + (jumpMove > 0 ? "/" + jumpMove + "j" : "")
              + (trspMovement != movement || trspMovementMode != movementMode ?
              "; TRSP" + trspMovement + trspMovementMode.code :
              "")
              + "; A" + armor + "; " + damage + "; " + pointValue + "@" + skill + "; " + elements.size() + " elements"
              + "; " + specialAbilities.getSpecialsDisplayString(this);
    }

    public void addTargetingCrit() {
        targetingCrits++;
    }

    public void addDamageCrit() {
        damageCrits++;
    }

    public void addMovementCrit() {
        mpCrits++;
    }

    public int getDamageCrits() {
        return damageCrits;
    }

    private boolean constructing;
    private boolean enteredExtremeDepth;

    public void markExtremeDepth() {
        enteredExtremeDepth = true;
    }

    public boolean consumeExtremeDepth() {
        boolean exposed = enteredExtremeDepth;
        enteredExtremeDepth = false;
        return exposed;
    }


    public void setConstructing(boolean value) {
        constructing = value;
    }

    public boolean isConstructing() {
        return constructing;
    }

    /** IO:BF pp. 171, 220: targeting damage and construction can prevent a Unit from firing. */
    public boolean canFire() {
        return !constructing && targetingCrits < 3;
    }

    public int getTargetingCrits() {
        return targetingCrits;
    }

    public int getMpCrits() {
        return mpCrits;
    }

    /**
     * @return The base roll value for firing on targets. Equals the skill, modified by targeting crits.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    public int getBaseGunnery() {
        return skill + targetingCrits;
    }

    public void setCurrentArmor(int currentArmor) {
        this.currentArmor = currentArmor;
    }

    /** ARM and ARS each cancel the first available critical chance while unspent (IO:BF p. 261). */
    public boolean absorbCriticalChance() {
        int protection = (hasSUA(BattleForceSUA.ARM) ? 1 : 0) + (hasSUA(BattleForceSUA.ARS) ? 1 : 0);
        if (spentArmorProtection >= protection) {
            return false;
        }
        spentArmorProtection++;
        return true;
    }

    /** IO:BF pp. 261, 264: surviving HarJel II/III Units restore armor each End Phase. */
    public int recoverHarJelArmor(int round) {
        if (((lastHarJelRound != null) && (lastHarJelRound == round))
              || (currentArmor <= 0) || (currentArmor * 4 < armor) || (currentArmor >= armor)) {
            return 0;
        }
        int recovered = harJelRating(BattleForceSUA.BHJ2) / 2 + harJelRating(BattleForceSUA.BHJ3);
        int previous = currentArmor;
        if (recovered > 0) {
            lastHarJelRound = round;
            currentArmor = Math.min(armor, currentArmor + recovered);
        }
        return currentArmor - previous;
    }

    /** IO:BF p. 221: participating SAW or ENG ratings; an Element cannot work twice in one turn. */
    int clearingRating(BattleForceSUA ability) {
        if (!hasSUA(ability)) {
            return 0;
        }
        if (getSUA(ability) instanceof Number rating) {
            return Math.max(0, rating.intValue());
        }
        return Math.max(1, (int) getElements().stream().filter(e -> e.hasSUA(ability)).count());
    }

    private int harJelRating(BattleForceSUA ability) {
        return getSUA(ability) instanceof Number rating ? rating.intValue() : (hasSUA(ability) ? 1 : 0);
    }

    /** IO:BF pp. 165, 171: distinguish bottom/submarine movement from units crossing on the surface. */
    public boolean isUnderwater(SBFGame game, SBFFormation formation) {
        return isUnderwater(game, formation, formation.getPosition());
    }

    public boolean isUnderwater(SBFGame game, SBFFormation formation, BoardLocation location) {
        Hex hex = location == null ? null : game.getHex(location);
        if ((hex == null) || (hex.terrainLevel(Terrains.WATER) <= 0)
              || (game.usesAdvancedTerrain() && hex.containsTerrain(Terrains.ICE))) {
            return false;
        }
        SBFMovementMode mode = getMovementMode() == SBFMovementMode.UNKNOWN ? formation.getMovementMode()
              : getMovementMode();
        return switch (mode) {
            case SUBMARINE -> !formation.isSubmarineSurfaced();
            case HOVER, WIGE, NAVAL, VTOL, AIRSHIP, AERODYNE, SPHEROID -> false;
            default -> !hex.hasPavementOrRoad() && !hasSUA(BattleForceSUA.AMP);
        };
    }

    /** Older converters marked every stealth-equipped Unit STL, MAS and LMAS. Its Elements retain the original armor. */
    boolean hasMimeticArmor() {
        if (!hasAnySUAOf(BattleForceSUA.MAS, BattleForceSUA.LMAS)) {
            return false;
        }
        return !hasSUA(BattleForceSUA.STL) || getElements().isEmpty()
              || getElements().stream().anyMatch(element -> element.hasAnySUAOf(BattleForceSUA.MAS, BattleForceSUA.LMAS));
    }

    public boolean hasArmorDamage() {
        return currentArmor != armor;
    }
}
