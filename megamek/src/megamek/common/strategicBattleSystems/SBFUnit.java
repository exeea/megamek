/*
 * Copyright (c) 2022-2024 - The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MegaMek. If not, see <http://www.gnu.org/licenses/>.
 */
package megamek.common.strategicBattleSystems;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import megamek.common.alphaStrike.*;
import megamek.common.jacksonadapters.SBFUnitDeserializer;
import megamek.common.jacksonadapters.SBFUnitSerializer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static megamek.common.alphaStrike.BattleForceSUA.*;

/**
 * Represents an SBF Unit (Ground SBF Unit or Aerospace Flight) which contains between 1 and 6 AlphaStrike
 * elements and is the building block of SBF Formations.
 */
@JsonRootName(value = "SBFUnit")
@JsonSerialize(using = SBFUnitSerializer.class)
@JsonDeserialize(using = SBFUnitDeserializer.class)
public class SBFUnit implements  ASSpecialAbilityCollector, BattleForceSUAFormatter,
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

    // ingame values
    private int currentArmor;
    private int damageCrits = 0;
    private int targetingCrits = 0;
    private int mpCrits = 0;

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
        if ((type == SBFElementType.V) && (sua == SRCH)) {
            return false;
        }
        return true;
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
        if (!sua.isValidAbilityObject(suaObject)) {
            return "ERROR - wrong ability object (" + sua + ")";
        } else if (sua.isAnyOf(CAP, SCAP, MSL)) {
            return sua.toString();
        } else if (sua == FLK) {
            ASDamageVector flkDamage = specialAbilities.getFLK();
            return sua.toString() + flkDamage.M.damage + "/" + flkDamage.L.damage;
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
                + (trspMovement != movement || trspMovementMode != movementMode ? "; TRSP" + trspMovement + trspMovementMode.code : "")
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

    public int getTargetingCrits() {
        return targetingCrits;
    }

    public int getMpCrits() {
        return mpCrits;
    }

    /**
     * @return The base roll value for firing on targets. Equals the skill, modified by targeting crits.
     */
    public int getBaseGunnery() {
        return skill + targetingCrits;
    }

    public void setCurrentArmor(int currentArmor) {
        this.currentArmor = currentArmor;
    }

    public boolean hasArmorDamage() {
        return currentArmor != armor;
    }
}
