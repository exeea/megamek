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

import static megamek.common.alphaStrike.BattleForceSUA.ARTAC;
import static megamek.common.alphaStrike.BattleForceSUA.ARTAIS;
import static megamek.common.alphaStrike.BattleForceSUA.CAP;
import static megamek.common.alphaStrike.BattleForceSUA.FLK;
import static megamek.common.alphaStrike.BattleForceSUA.MSL;
import static megamek.common.alphaStrike.BattleForceSUA.SCAP;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import megamek.client.ui.clientGUI.calculationReport.CalculationReport;
import megamek.client.ui.clientGUI.calculationReport.DummyCalculationReport;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASSpecialAbilityCollection;
import megamek.common.alphaStrike.ASSpecialAbilityCollector;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.enums.GamePhase;
import megamek.common.force.Force;
import megamek.common.interfaces.ForceAssignable;
import megamek.common.jacksonAdapters.SBFFormationDeserializer;
import megamek.common.jacksonAdapters.SBFFormationSerializer;
import megamek.common.units.Terrains;
import megamek.common.units.Deployable;
import megamek.common.units.Entity;

/**
 * Represents a Strategic Battle Force Formation composed of one or more SBF Units.
 */
@JsonRootName(value = "SBFFormation")
@JsonSerialize(using = SBFFormationSerializer.class)
@JsonDeserialize(using = SBFFormationDeserializer.class)
public class SBFFormation implements ASSpecialAbilityCollector, BattleForceSUAFormatter, ForceAssignable,
                                     Deployable, Serializable {

    private List<SBFUnit> units = new ArrayList<>();
    private String name;
    protected SBFElementType type;
    private int size;
    private int tmm;
    private int movement;
    private Integer startingMovement;
    private SBFMovementMode movementMode;
    private int jumpMove;
    private SBFMovementMode trspMovementMode;
    private int trspMovement;
    private int tactics;
    private int morale;
    private int skill;
    private int pointValue;
    private transient CalculationReport conversionReport = new DummyCalculationReport();
    private final ASSpecialAbilityCollection specialAbilities = new ASSpecialAbilityCollection();

    private String forceString = "";
    private int forceId = Force.NO_FORCE;
    private int id = Entity.NONE;
    private int ownerId = Player.PLAYER_NONE;

    /** Hidden deployment (not unseen) */
    private boolean isHidden;
    // The gamemaster's front arc for Sensor Ghost reports (IO:BF p. 196). Ground movement has no facing.
    private int reconFacing;

    public int getReconFacing() {
        return reconFacing;
    }

    public void setReconFacing(int direction) {
        if (direction < 0 || direction >= 6) {
            throw new IllegalArgumentException("Recon facing must be a hex direction from 0 to 5.");
        }
        reconFacing = direction;
    }

    private Integer lastAmbushRound;

    public void markAmbush(int round) {
        lastAmbushRound = round;
    }

    public boolean ambushedIn(int round) {
        return lastAmbushRound != null && lastAmbushRound == round;
    }
    // ID of the Formation from which these Units split. Detachments share their parent's movement turn.
    private Integer originalFormation;
    private boolean movesWithParent;
    private Integer detachmentMovementGroup;
    private Integer reformPenaltyRound;
    private Integer reunionRound;
    private BoardLocation reunionLocation;

    public void planReunion(BoardLocation location, int round) {
        reunionLocation = location;
        reunionRound = round;
    }

    public BoardLocation reunionIn(int round) {
        return reunionRound != null && reunionRound == round ? reunionLocation : null;
    }
    private Integer adHocInactiveRound;

    public int originalFormationId() {
        return originalFormation == null ? getId() : originalFormation;
    }

    public int movementGroup() {
        return detachmentMovementGroup != null ? detachmentMovementGroup : (movesWithParent ? originalFormationId() : getId());
    }

    public void setMovementGroup(int group) {
        movesWithParent = group != getId() && group == originalFormationId();
        detachmentMovementGroup = (group == getId() || movesWithParent) ? null : group;
    }

    /** An ad hoc transfer replaces the participating sheets, including those held by off-site siblings (IO:BF p. 198). */
    public void retireSheet() {
        int group = movementGroup();
        originalFormation = null;
        setMovementGroup(group);
        clearReunion();
    }

    public void assignOrigin(int formation, boolean detached) {
        originalFormation = formation;
        setMovementGroup(detached ? formation : getId());
    }

    public void markReformed(int nextRound) {
        retireSheet();
        adHocInactiveRound = null;
        reformPenaltyRound = nextRound;
    }

    public void clearReunion() {
        reunionLocation = null;
        reunionRound = null;
    }

    public void markAdHoc(int nextRound) {
        clearReunion();
        originalFormation = null;
        setMovementGroup(getId());
        reformPenaltyRound = null;
        adHocInactiveRound = nextRound;
    }

    public boolean inactiveAfterAssembly(int round) {
        return adHocInactiveRound != null && adHocInactiveRound == round;
    }

    public int reformMovementPenalty(int round) {
        return reformPenaltyRound != null && reformPenaltyRound == round ? 1 : 0;
    }

    /** A damaged Unit may detach and withdraw while its healthy parent continues (IO:BF p. 172). */
    public void updateForcedWithdrawalAfterAdjustment() {
        forcedWithdrawal = isCrippled();
    }

    private boolean isDeployed = false;
    private int deployRound = 0;
    private BoardLocation position;
    private boolean isDone = false;
    private int jumpUsedThisTurn = 0;
    private boolean sprintedThisTurn = false;
    private boolean searchlightsOn;

    public boolean hasSearchlight() {
        return !isAerospace() && (isType(SBFElementType.BM) || hasSUA(BattleForceSUA.SRCH));
    }

    public boolean searchlightsOn() {
        return searchlightsOn && hasSearchlight();
    }

    public void setSearchlightsOn(boolean on) {
        searchlightsOn = on;
    }

    private Integer empRound;
    private Integer infernoMoveEnd;

    public void hitByEmp(int round) {
        empRound = round;
    }

    public boolean disruptedByEmp(int round) {
        return empRound != null && empRound == round;
    }

    public void hitByInferno(int round) {
        infernoMoveEnd = round + 1;
    }

    public boolean slowedByInferno(int round) {
        return infernoMoveEnd != null && round <= infernoMoveEnd;
    }

    private Integer transportId;
    private double packedCargoTons;
    private Integer cargoTransferRound;
    private Integer cargoReadyRound;

    public double packedCargoTons() {
        return packedCargoTons;
    }

    public boolean handledCargo(int round) {
        return cargoTransferRound != null && cargoTransferRound == round;
    }

    public void recordCargoTransfer(int round) {
        cargoTransferRound = round;
    }

    public boolean unpackingCargo(int round) {
        return cargoReadyRound != null && round < cargoReadyRound;
    }

    public void packAsCargo(int carrier, double cargoTons) {
        embark(carrier, false);
        packedCargoTons = cargoTons;
    }

    private boolean externallyMounted;
    private boolean flightLanded;
    private Integer highDropRound;
    private BoardLocation plannedLowDrop;

    public BoardLocation plannedLowDrop() {
        return plannedLowDrop;
    }

    public void planLowDrop(BoardLocation site) {
        plannedLowDrop = site;
    }

    private BoardLocation dropDestination;
    private boolean landingFromHighDrop;
    private int airDropMovementCost;

    public boolean isAirVehicle() {
        return (movementMode == SBFMovementMode.VTOL) || (movementMode == SBFMovementMode.WIGE)
              || (movementMode == SBFMovementMode.AIRSHIP);
    }

    public boolean isAirVehicleLanded() {
        return flightLanded;
    }

    public void setAirVehicleLanded(boolean landed) {
        flightLanded = landed;
    }

    public void beginHighDrop(int round) {
        transportId = null;
        externallyMounted = false;
        highDropRound = round;
        dropDestination = null;
        setRadarPosition(SBFRadarZone.CENTRAL, List.of());
        setDone(true);
    }

    public boolean inHighDrop() {
        return highDropRound != null;
    }

    public boolean canLandHighDrop(int round) {
        return inHighDrop() && (round > highDropRound) && (dropDestination == null);
    }

    public void aimDrop(BoardLocation destination, boolean high) {
        transportId = null;
        externallyMounted = false;
        position = null;
        dropDestination = destination;
        landingFromHighDrop = high;
        setDone(true);
    }

    public BoardLocation getDropDestination() {
        return dropDestination;
    }

    public boolean landingFromHighDrop() {
        return landingFromHighDrop;
    }

    public void finishDrop(BoardLocation destination, int round) {
        setRadarPosition(null, List.of());
        highDropRound = null;
        dropDestination = null;
        airDropMovementCost = landingFromHighDrop ? 3 : 2;
        dismount(destination, round);
        setDone(false);
    }

    public int airDropMovementCost() {
        return airDropMovementCost;
    }

    private Integer dismountRound;
    private boolean dismountedFromBay;
    private Integer bayUseRound;
    private int bayDoorsUsed;

    public int bayDoorsUsed(int round) {
        return (bayUseRound != null) && (bayUseRound == round) ? bayDoorsUsed : 0;
    }

    public void useBayDoors(int round, int doors) {
        bayDoorsUsed = bayDoorsUsed(round) + doors;
        bayUseRound = round;
    }

    public int dismountCost(int round) {
        if (dismountedIn(round) && (airDropMovementCost > 0)) {
            return airDropMovementCost;
        }
        return dismountedIn(round) ? (dismountedFromBay || !isAnyTypeOf(SBFElementType.BA, SBFElementType.CI) ? 4 : 1) : 0;
    }

    private boolean forcedWithdrawal;
    private boolean withdrawn;
    private int reconMpSpent;
    private boolean attackedThisTurn;
    private boolean attackedByMekThisTurn;
    private boolean hostileEnteredHex;
    private int quietTurns;
    private Integer enragedRound;

    public enum MoraleStatus {
        NORMAL, SHAKEN, UNSTEADY, BROKEN, ROUTED
    }

    protected MoraleStatus moraleStatus = MoraleStatus.NORMAL;

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

    public int getJumpMove() {
        return jumpMove;
    }

    public void setJumpMove(int jumpMove) {
        this.jumpMove = jumpMove;
    }

    public int getTrspMovement() {
        return trspMovement;
    }

    public void setTrspMovement(int trspMovement) {
        this.trspMovement = trspMovement;
    }

    public int getMovement() {
        return movement;
    }

    public void setMovement(int movement) {
        this.movement = movement;
        startingMovement = null;
    }

    public int getStartingMovement() {
        return startingMovement == null ? movement : startingMovement;
    }

    /** Preserves the printed Move when critical hits or casualties change the Formation's operational Move. */
    public void setCurrentMovement(int movement) {
        startingMovement = getStartingMovement();
        this.movement = movement;
    }

    public int getTactics() {
        return tactics;
    }

    public void setTactics(int tactics) {
        this.tactics = tactics;
    }

    public int getMorale() {
        return morale;
    }

    public void setMorale(int morale) {
        this.morale = morale;
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

    public ASSpecialAbilityCollection getSpecialAbilities() {
        return specialAbilities;
    }

    public List<SBFUnit> getUnits() {
        return Collections.unmodifiableList(units);
    }

    public void addUnit(SBFUnit newUnit) {
        units.add(newUnit);
    }

    public void setUnits(List<SBFUnit> units) {
        this.units = units;
    }

    public void removeUnit(SBFUnit unit) {
        units.remove(unit);
    }

    public CalculationReport getConversionReport() {
        return conversionReport;
    }

    public void setConversionReport(CalculationReport report) {
        conversionReport = report;
    }

    public SBFMovementMode getMovementMode() {
        return movementMode;
    }

    public String getMovementCode() {
        return movementMode.code;
    }

    public void setMovementMode(SBFMovementMode mode) {
        movementMode = mode;
    }

    public SBFMovementMode getTrspMovementMode() {
        return trspMovementMode;
    }

    public void setTrspMovementMode(SBFMovementMode mode) {
        trspMovementMode = mode;
    }

    public String getTrspMovementCode() {
        return trspMovementMode.code;
    }

    /**
     * Returns the Artillery Special's SBF damage (standard, not homing missile damage). Returns 0 when the given SPA is
     * not an Artillery SPA.
     */
    public static int getSbfArtilleryDamage(BattleForceSUA spa) {
        return switch (spa) {
            case ARTTC -> 1;
            case ARTT, ARTBA, ARTSC -> 2;
            case ARTAIS, ARTAC, ARTS, ARTLTC -> 3;
            case ARTLT -> 6;
            case ARTCM5 -> 8;
            case ARTCM7 -> 13;
            case ARTCM9 -> 22;
            case ARTCM12 -> 36;
            default -> 0;
        };
    }

    /**
     * Returns the Artillery Special's SBF damage for homing missiles. Returns 0 when the given SPA is not ARTAIS or
     * ARTAC.
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    public static int getSbfArtilleryHomingDamage(BattleForceSUA spa) {
        return spa.isAnyOf(ARTAIS, ARTAC) ? 2 : 0;
    }

    @Override
    public boolean isUnitGroup() {
        return true;
    }

    @Override
    public String generalName() {
        return name;
    }

    @Override
    public String specificName() {
        return "";
    }

    /**
     * Returns true if this SBF Formation represents an aerospace Team.
     */
    @Override
    public boolean isAerospace() {
        return isAnyTypeOf(SBFElementType.AS, SBFElementType.LA);
    }

    /**
     * Returns true if this SBF Formation is of the given type.
     */
    public boolean isType(SBFElementType tp) {
        return type == tp;
    }

    /**
     * Returns true if this SBF Formation is any of the given types.
     */
    public boolean isAnyTypeOf(SBFElementType type, SBFElementType... types) {
        return isType(type) || Arrays.stream(types).anyMatch(this::isType);
    }

    /** IO:BF p. 221. Only ratings for abilities granted to the Formation can contribute. */
    public int woodlandClearingRating() {
        boolean saw = hasSUA(BattleForceSUA.SAW);
        boolean engineer = hasSUA(BattleForceSUA.ENG);
        return getUnits().stream().filter(unit -> unit.getCurrentArmor() > 0)
              .mapToInt(unit -> Math.max(saw ? unit.clearingRating(BattleForceSUA.SAW) : 0,
                    engineer ? unit.clearingRating(BattleForceSUA.ENG) : 0)).sum();
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
    public String formatSUA(BattleForceSUA sua, String delimiter, ASSpecialAbilityCollector collection) {
        return formatAbility(sua);
    }

    /**
     * Creates the formatted SPA string for the given spa. For turrets this includes everything in that turret. The
     * given collection can be the specials of the AlphaStrikeElement itself, a turret or an arc of a large aerospace
     * unit.
     *
     * @param sua The Special Unit Ability to process
     *
     * @return The complete formatted Special Unit Ability string such as "LRM1/1/-" or "CK15D2".
     */
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
    public int getId() {
        return id;
    }

    @Override
    public void setId(int newId) {
        id = newId;
    }

    @Override
    public int getOwnerId() {
        return ownerId;
    }

    @Override
    public void setOwnerId(int newOwnerId) {
        ownerId = newOwnerId;
    }

    @Override
    public int getStrength() {
        return getPointValue();
    }

    @Override
    public String getForceString() {
        return forceString;
    }

    @Override
    public void setForceString(String newForceString) {
        forceString = newForceString;
    }

    @Override
    public int getForceId() {
        return forceId;
    }

    @Override
    public void setForceId(int newId) {
        forceId = newId;
    }

    @Override
    public String toString() {
        return "[SBFFormation] "
              + name
              + ": "
              + type
              + "; SZ"
              + size
              + "; TMM"
              + tmm
              + "; MV"
              + movement
              + movementMode.code
              + (jumpMove > 0 ? "/" + jumpMove + "j" : "")
              + (trspMovement != movement || trspMovementMode != movementMode ?
              "; TRSP" + trspMovement + trspMovementMode.code :
              "")
              + "; T"
              + tactics
              + "; M"
              + morale
              + "; "
              + pointValue
              + "@"
              + skill
              + "; "
              + units.size()
              + " units"
              + "; "
              + specialAbilities.getSpecialsDisplayString(this);
    }

    @Override
    public boolean isDeployed() {
        return isDeployed;
    }

    @Override
    public int getDeployRound() {
        return deployRound;
    }

    /**
     * Returns the game round that this formation is to be deployed in. Note that deployment technically counts as
     * happening at the end of that round.
     *
     * @param deployRound The round this formation deploys in
     */
    public void setDeployRound(int deployRound) {
        this.deployRound = deployRound;
    }

    /**
     * Sets this element's deployment status to the given status
     */
    public void setDeployed(boolean deployed) {
        isDeployed = deployed;
    }

    /**
     * Two formations are equal if their ids are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        final SBFFormation other = (SBFFormation) obj;
        return (id == other.id);
    }

    @Override
    public int hashCode() {
        return id;
    }

    public BoardLocation getPosition() {
        return position;
    }

    private Integer passageEntrance;

    public int passageEntrance() {
        return passageEntrance == null ? -1 : passageEntrance;
    }

    public void setPassageEntrance(int side) {
        passageEntrance = side < 0 ? null : side;
    }

    public void setPosition(BoardLocation boardLocation) {
        if (!java.util.Objects.equals(position, boardLocation)) {
            passageEntrance = null;
        }
        position = boardLocation;
    }

    public void setHidden(boolean hidden) {
        isHidden = hidden;
    }

    public boolean isHidden() {
        return isHidden;
    }

    private List<Integer> engagedOpponents = new ArrayList<>();
    private int mpSpentThisTurn;
    private Integer forcedOpponent;
    private boolean overranThisTurn;
    private boolean wasOverrunThisTurn;
    private boolean evadedThisTurn;
    private boolean failedEvadeThisTurn;
    private boolean movingEvasively;
    private boolean boggedDown;
    private Integer boggedRound;

    public boolean isBoggedDown() {
        return boggedDown;
    }

    public boolean boggedIn(int round) {
        return boggedDown && boggedRound != null && boggedRound == round;
    }

    public void resolveBogCheck(boolean stuck, int round) {
        boggedRound = stuck ? round : null;
        boggedDown = stuck;
    }

    private BoardLocation hullDownPosition;
    private BoardLocation constructionSite;
    private SBFUnit constructionUnit;
    private int constructionLevel;

    public SBFUnit constructionUnit() {
        return constructionUnit;
    }

    public BoardLocation constructionSite() {
        return constructionSite;
    }

    public int constructionLevel() {
        return constructionLevel;
    }

    /** IO:BF p. 220. Engineering Units work while other members of the Formation may still fire. */
    public void beginFieldConstruction(SBFUnit builder, int level) {
        constructionSite = position;
        constructionLevel = level;
        constructionUnit = builder;
        getUnits().stream().filter(u -> u.hasAnySUAOf(BattleForceSUA.ENG, BattleForceSUA.TRN))
              .forEach(u -> u.setConstructing(true));
        builder.setConstructing(true);
    }

    public void finishFieldConstruction() {
        getUnits().forEach(u -> u.setConstructing(false));
        constructionSite = null;
        constructionLevel = 0;
        constructionUnit = null;
    }


    /** Optional evasive movement, distinct from evading an engagement (IO:BF pp. 198–199). */
    public int evasiveModifier() {
        return !movingEvasively ? 0 : skill <= 2 ? 4 : skill <= 4 ? 3 : skill == 5 ? 2 : 1;
    }

    public void moveEvasively() {
        movingEvasively = true;
    }

    /** Urban Hexes, IO:BF p. 217: inside the buildings of the urban hex it occupies rather than in its streets. */
    private BoardLocation insideBuildingsPosition;

    /** Sharp Shooters special command ability, IO:BF p. 225; at most one-third of a Force's Formations have it. */
    private boolean sharpShooters;

    public boolean isSharpShooters() {
        return sharpShooters;
    }

    public void setSharpShooters(boolean sharpShooters) {
        this.sharpShooters = sharpShooters;
    }

    /** Brawlers special command ability, IO:BF p. 225; at most one-third of a Force's Formations have it. */
    private boolean brawlers;

    public boolean isBrawlers() {
        return brawlers;
    }

    public void setBrawlers(boolean brawlers) {
        this.brawlers = brawlers;
    }

    /** Overrun Combat, IO:BF p. 224: this Formation moved out of sequence and attacks first in that round. */
    private Integer overrunRound;

    public boolean overrunsIn(int round) {
        return (overrunRound != null) && (overrunRound == round);
    }

    public void markOverrun(int round) {
        overrunRound = round;
    }

    /** Communications Disruption, IO:BF p. 226: an enemy disrupted this Formation's communications for this turn. */
    private boolean communicationsDisrupted;

    public boolean isCommunicationsDisrupted() {
        return communicationsDisrupted;
    }

    public void setCommunicationsDisrupted(boolean disrupted) {
        communicationsDisrupted = disrupted;
    }

    /** Made up exclusively of wheeled Elements, which pay 1 MP less in urban hexes (IO:BF p. 216, urban table note *). */
    public boolean isExclusivelyWheeled() {
        return SBFTerrainMovement.Bonuses.of(SBFTerrainMovement.of(this).toList()).wheeled();
    }

    public boolean isInsideBuildings() {
        return insideBuildingsPosition != null && insideBuildingsPosition.equals(position);
    }

    public void setInsideBuildings(boolean inside) {
        insideBuildingsPosition = inside ? position : null;
        if (inside) {
            onTopOfBuildingsPosition = null;
        }
    }

    /** Urban Hexes, IO:BF p. 217: standing on the roofs of the urban hex it occupies. */
    private BoardLocation onTopOfBuildingsPosition;

    public boolean isOnTopOfBuildings() {
        return onTopOfBuildingsPosition != null && onTopOfBuildingsPosition.equals(position);
    }

    public void setOnTopOfBuildings(boolean onTop) {
        onTopOfBuildingsPosition = onTop ? position : null;
        if (onTop) {
            insideBuildingsPosition = null;
        }
    }

    public boolean isHullDown() {
        return hullDownPosition != null && hullDownPosition.equals(position);
    }

    public void setHullDown(boolean hullDown) {
        hullDownPosition = hullDown ? position : null;
    }


    public int getMpSpentThisTurn() {
        return mpSpentThisTurn;
    }

    public void setMpSpentThisTurn(int points) {
        mpSpentThisTurn = points;
    }

    public void resetEngagementMovement() {
        jumpUsedThisTurn = 0;
        airDropMovementCost = 0;
        movingEvasively = false;
        airAttackOpponent = null;
        airPasses = new ArrayList<>();
        reservedAirReturns = new ArrayList<>();
        mpSpentThisTurn = isDeployed && !isEmbarked() && (movement > 0) && usesWiGE() ? 1 : 0;
        overranThisTurn = false;
        wasOverrunThisTurn = false;
        evadedThisTurn = false;
        failedEvadeThisTurn = false;
    }

    public void setForcedOpponent(Integer opponentId) {
        forcedOpponent = opponentId;
    }

    public void recordOverrun(boolean target) {
        if (target) {
            wasOverrunThisTurn = true;
        } else {
            overranThisTurn = true;
        }
    }

    public boolean wasOverrunThisTurn() {
        return wasOverrunThisTurn;
    }

    public boolean hasOverrunThisTurn() {
        return overranThisTurn;
    }

    public void recordEvade(boolean successful) {
        evadedThisTurn = true;
        failedEvadeThisTurn = !successful;
    }

    public boolean canAttackAfterEngagement() {
        return !movingEvasively && !inHighDrop() && ((airDropMovementCost != 3) || (mpSpentThisTurn == 0))
              && !overranThisTurn && (!evadedThisTurn || failedEvadeThisTurn);
    }

    public int getEngagementAttackModifier() {
        return failedEvadeThisTurn ? 2 : 0;
    }

    public int getEngagementDefenseModifier() {
        return evadedThisTurn ? 1 : 0;
    }

    public boolean canFireArtilleryAfterEngagement() {
        return canAttackAfterEngagement() && (forcedOpponent == null) && !failedEvadeThisTurn;
    }

    /** Forced Engagement and Evade damage fractions, rounded down (IO:BF pp. 167–168). */
    public int adjustEngagementDamageAgainst(SBFFormation target, int damage) {
        if ((forcedOpponent != null) || failedEvadeThisTurn) {
            damage /= 2;
        }
        if ((target.forcedOpponent != null) && (target.forcedOpponent == getId())) {
            damage /= 2;
        }
        if (target.evadedThisTurn) {
            damage = (3 * damage) / 4;
        }
        return damage;
    }

    public List<Integer> getEngagedOpponents() {
        if (engagedOpponents == null) {
            engagedOpponents = new ArrayList<>();
        }
        return List.copyOf(engagedOpponents);
    }

    public boolean isEngagedWith(int opponentId) {
        return (engagedOpponents != null) && engagedOpponents.contains(opponentId);
    }

    public void setEngagedWith(int opponentId, boolean engage) {
        if (engagedOpponents == null) {
            engagedOpponents = new ArrayList<>();
        }
        if (engage && !isEngagedWith(opponentId)) {
            engagedOpponents.add(opponentId);
        } else if (!engage) {
            engagedOpponents.remove(Integer.valueOf(opponentId));
        }
    }

    private Integer airAttackOpponent;
    private List<Integer> airPasses = new ArrayList<>();
    private List<Integer> reservedAirReturns = new ArrayList<>();

    public boolean mayAttackAirOpponent(int id) {
        return !inHighDrop() && !reservedAirReturn(id) && ((airAttackOpponent == null) || (airAttackOpponent == id));
    }

    public void commitAirAttack(int id) {
        airAttackOpponent = id;
    }

    public void declineAirReturn(int attacker, boolean pass) {
        if (reservedAirReturns == null) {
            reservedAirReturns = new ArrayList<>();
        }
        if (!reservedAirReturns.contains(attacker)) {
            reservedAirReturns.add(attacker);
        }
        if (airPasses == null) {
            airPasses = new ArrayList<>();
        }
        if (pass && !airPasses.contains(attacker)) {
            airPasses.add(attacker);
        }
    }

    public boolean passesAirOpponent(int attacker) {
        return (airPasses != null) && airPasses.contains(attacker);
    }

    public boolean reservedAirReturn(int attacker) {
        return (reservedAirReturns != null) && reservedAirReturns.contains(attacker);
    }

    private SBFCapitalSector capitalSector;
    private SBFRadarZone engagementZone;
    private Integer engagementEntryRound;
    private String phaseCombatArea;
    private Integer spaceFuel;
    private Integer spaceManeuverRound;
    private Integer spaceFuelResolvedRound;
    private Integer spaceRefuelRound;
    private Integer spaceRecoveryRound;
    private Integer gravityFallRound;
    private SBFRadarZone gravityBearing;
    private BoardLocation crashAim;

    public SBFCapitalSector capitalSector() {
        return capitalSector;
    }

    public SBFRadarZone engagementZone() {
        return engagementZone;
    }

    public void enterEngagementMap(SBFRadarZone zone, int round) {
        engagementZone = zone;
        engagementEntryRound = round;
    }

    /** Snapshot at turn-order creation so dismantling a sector map cannot invalidate an unspent turn (p. 186). */
    public String assignPhaseCombatArea(int round) {
        return assignPhaseCombatArea(round, this);
    }

    public String assignPhaseCombatArea(int round, SBFFormation position) {
        phaseCombatArea = position.engagementZone != null && !position.waitingForSpaceCombat(round) ? "0-" + position.capitalSector
              : position.capitalSector != null ? "1-capital" : "2-atmosphere-ground";
        return phaseCombatArea;
    }

    public boolean assignedToCombatArea(String area) {
        return area == null || area.equals(phaseCombatArea);
    }

    public boolean waitingForSpaceCombat(int round) {
        return engagementZone != null && Integer.valueOf(round).equals(engagementEntryRound);
    }

    public void setEngagementZone(SBFRadarZone zone) {
        engagementZone = zone;
    }

    public SBFRadarZone combatZone() {
        return engagementZone == null ? radarZone : engagementZone;
    }

    public boolean sharesAerospaceMap(SBFFormation other) {
        if (engagementZone != null || other.engagementZone != null) {
            return engagementZone != null && other.engagementZone != null && capitalSector.equals(other.capitalSector);
        }
        return isOnRadarMap() && other.isOnRadarMap();
    }

    public void setCapitalSector(SBFCapitalSector sector) {
        setRadarPosition(null, List.of());
        capitalSector = sector;
    }

    /** Central on a ground-centered capital map and the atmospheric Outer Ring occupy the same space (p. 184). */
    public void enterCapitalAtmosphere(SBFRadarZone outer) {
        if (!outer.isOuter()) {
            throw new IllegalArgumentException("Capital Central meets the atmospheric Outer Ring.");
        }
        setRadarPosition(outer, List.of());
        capitalSector = new SBFCapitalSector(SBFCapitalSector.Ring.CENTRAL, 1);
        gravityFallRound = null;
    }

    public int spaceFuel() {
        return spaceFuel == null ? fighterFuelCapacity() : spaceFuel;
    }

    public int fighterFuelCapacity() {
        return getUnits().stream().filter(this::hasFuelLimitedFighters).mapToInt(SBFUnit::getFUEL).min().orElse(0);
    }

    /** Only fighter Flights track fuel. An abstract AS roster can explicitly provide FUEL# instead of Elements. */
    public boolean usesFighterFuel() {
        return getUnits().stream().anyMatch(this::hasFuelLimitedFighters);
    }

    private boolean hasFuelLimitedFighters(SBFUnit unit) {
        return unit.getElements().isEmpty() ? unit.isType(SBFElementType.AS) && unit.getFUEL() > 0
              : unit.getElements().stream().anyMatch(e -> e.getASUnitType() != null && e.isAerospaceFighter());
    }

    public boolean canDescendIntoAtmosphere() {
        return getUnits().stream().noneMatch(unit -> unit.getMovementMode() == SBFMovementMode.WARSHIP
              || unit.getMovementMode() == SBFMovementMode.STATION_KEEPING);
    }

    public int fighterSlots() {
        return getUnits().stream().mapToInt(u -> u.getElements().isEmpty() ? 1 : u.getElements().size()).sum();
    }

    public void recoverForRefueling(SBFFormation carrier, int round) {
        setCapitalSector(null);
        embark(carrier.getId(), false);
        spaceRefuelRound = round;
        carrier.spaceRecoveryRound = round;
    }

    public boolean recoveringFighters(int round) {
        return Integer.valueOf(round).equals(spaceRecoveryRound);
    }

    public boolean readyToLaunch(int round) {
        return isEmbarked() && spaceRefuelRound != null && round >= spaceRefuelRound + 3;
    }

    public void launchRefueled(SBFFormation carrier, int round) {
        dismount(null, round);
        spaceFuel = null;
        spaceRefuelRound = null;
        setCapitalSector(carrier.capitalSector());
        if (carrier.engagementZone() != null) {
            enterEngagementMap(carrier.engagementZone(), round);
        } else if (carrier.isOnRadarMap()) {
            enterCapitalAtmosphere(carrier.getRadarZone());
        }
        setDone(true); // Launching movement must end in the carrier's zone (IO:BF p. 183).
    }

    public void setGravityBearing(SBFRadarZone outer) {
        if (!outer.isOuter()) {
            throw new IllegalArgumentException("An Outer Ring zone is required.");
        }
        gravityBearing = outer;
    }

    public void fallIntoAtmosphere(int round) {
        SBFRadarZone destination = gravityBearing != null ? gravityBearing
              : isOnRadarMap() ? getRadarZone() : SBFRadarZone.OUTER_12;
        setRadarPosition(destination, List.of());
        gravityFallRound = round;
    }

    public boolean fallingFromOrbit() {
        return gravityFallRound != null && isOnRadarMap() && getRadarZone().isOuter() && capitalSector == null;
    }

    public BoardLocation crashAim() {
        return crashAim;
    }

    public void setCrashAim(BoardLocation aim) {
        crashAim = aim;
    }

    public boolean fellThisRound(int round) {
        return Integer.valueOf(round).equals(gravityFallRound);
    }

    public boolean isDrifting() {
        return capitalSector != null && usesFighterFuel() && spaceFuel() <= 0;
    }

    public int capitalThrust() {
        return isDrifting() || getMovementMode() == SBFMovementMode.STATION_KEEPING ? 0
              : Math.max(0, getMovement() - highExplosiveBombs - clusterBombs - (communicationsDisrupted ? 1 : 0));
    }

    public void markSpaceManeuver(int round) {
        spaceManeuverRound = round;
    }

    /** Charge the destination's cost once in End Phase, including a maneuver returning to the same sector. */
    public void expendSpaceFuel(int round) {
        if (capitalSector != null && Integer.valueOf(round).equals(spaceManeuverRound)
              && !Integer.valueOf(round).equals(spaceFuelResolvedRound) && usesFighterFuel()) {
            spaceFuel = Math.max(0, spaceFuel() - capitalSector.ring().fuelCost);
            spaceFuelResolvedRound = round;
        }
    }

    private SBFRadarZone radarZone;
    private SBFRadarZone radarHome;

    public SBFRadarZone getRadarHome() {
        return radarHome;
    }

    public void setRadarHome(SBFRadarZone home) {
        radarHome = home;
    }
    private List<BoardLocation> groundFlightPath = List.of();
    private int highExplosiveBombs;
    private int clusterBombs;

    /** Ground occupation excludes embarked passengers and the ground footprint of an airborne flight path. */
    public boolean occupiesGroundHex() {
        return isDeployed() && !isEmbarked() && !isOnRadarMap() && getPosition() != null;
    }

    /** A scenario battery may fire indirectly from outside the board (IO:BF pp. 204–205). */
    private BoardLocation offMapArtilleryPosition;

    public BoardLocation getArtilleryPosition() {
        return isOffMapArtillery() ? offMapArtilleryPosition : getPosition();
    }

    public boolean isOffMapArtillery() {
        return offMapArtilleryPosition != null;
    }

    public void deployOffMapArtillery(BoardLocation position) {
        offMapArtilleryPosition = position;
        setPosition(null);
        setDeployed(true);
    }

    public boolean concealsRoster() {
        return isHidden() || isOffMapArtillery();
    }

    private boolean submarineSurfaced;

    public boolean isSubmarineSurfaced() {
        return submarineSurfaced;
    }

    public void setSubmarineSurfaced(boolean surfaced) {
        submarineSurfaced = surfaced;
    }

    public boolean usesWiGE() {
        return SBFTerrainMovement.of(this).anyMatch(mover -> mover.mode() == SBFMovementMode.WIGE);
    }

    public boolean canChangeSubmarineDepth(Hex hex, boolean surface) {
        return (hex != null) && (hex.terrainLevel(Terrains.WATER) > 0)
              && SBFTerrainMovement.of(this).anyMatch(mover -> mover.mode() == SBFMovementMode.SUBMARINE)
              && (surface || SBFTerrainMovement.isFullWater(hex));
    }

    public boolean isOnRadarMap() {
        return radarZone != null;
    }

    public SBFRadarZone getRadarZone() {
        return radarZone;
    }

    public List<BoardLocation> getGroundFlightPath() {
        return groundFlightPath == null ? List.of() : groundFlightPath;
    }

    public void setRadarPosition(SBFRadarZone zone, List<BoardLocation> flightPath) {
        capitalSector = null;
        engagementZone = null;
        radarZone = zone;
        groundFlightPath = List.copyOf(flightPath);
        setPosition(groundFlightPath.isEmpty() ? null : groundFlightPath.getLast());
    }

    public int getHighExplosiveBombs() {
        return highExplosiveBombs;
    }

    public int getClusterBombs() {
        return clusterBombs;
    }

    public void setBombLoad(int highExplosive, int cluster) {
        highExplosiveBombs = highExplosive;
        clusterBombs = cluster;
    }

    /** Loaded bombs reduce atmospheric Thrust, to a minimum of one for a mobile craft (IO:BF p. 176). */
    public int getCurrentThrust() {
        if (fallingFromOrbit() && ((spaceFuel != null && spaceFuel == 0)
              || getMovementMode() == SBFMovementMode.STATION_KEEPING)) {
            return 0;
        }
        // Communications Disruption, IO:BF p. 226: "reduce its base Thrust by 1"
        return getMovement() <= 0 ? 0
              : Math.max(1, getMovement() - highExplosiveBombs - clusterBombs - (communicationsDisrupted ? 1 : 0));
    }

    public boolean isEligibleForPhase(GamePhase phase) {
        // A sprinting Formation may not make attacks (IO:BF p.199)
        return !isDone && !isEmbarked() && !(phase.isFiring() && (sprintedThisTurn || !canAttackAfterEngagement())) && switch (phase) {
            case FIRING -> isDeployed && !isHidden && !inHighDrop() && (dropDestination == null)
                  && ((airDropMovementCost != 3) || (mpSpentThisTurn == 0));
            case MOVEMENT -> isDeployed && !isHidden && !isOffMapArtillery() && (inHighDrop() || getEngagedOpponents().isEmpty()
                  || (combatZone() != null && getEngagedOpponents().stream().allMatch(this::passesAirOpponent)));
            case PREMOVEMENT, PRE_FIRING -> isHidden;
            default -> isDeployed;
        };
    }

    public void setDone(boolean done) {
        isDone = done;
    }

    public boolean isDone() {
        return isDone;
    }

    public int getJumpUsedThisTurn() {
        return jumpUsedThisTurn;
    }

    public void setJumpUsedThisTurn(int jumpUsedThisTurn) {
        this.jumpUsedThisTurn = jumpUsedThisTurn;
    }

    /** @return True when this Formation used more than its Move this turn, i.e. it sprinted (IO:BF p.199). */
    public boolean hasSprintedThisTurn() {
        return sprintedThisTurn;
    }

    public void setSprintedThisTurn(boolean sprintedThisTurn) {
        this.sprintedThisTurn = sprintedThisTurn;
    }

    /** IO:BF, Forced Withdrawal, p. 172. Each half-Formation test applies to its own damage category. */
    public boolean isCrippled() {
        if (units.isEmpty()) {
            return false;
        }
        int half = (units.size() + 1) / 2;
        return isDrifting() || ((getStartingMovement() > 2) && (movement <= 1))
              || (units.stream().filter(u -> u.getDamage().hasDamage() && !u.getCurrentDamage().hasDamage())
                    .count() >= half)
              || (units.stream().filter(u -> (u.getArmor() > 0) && (u.getCurrentArmor() <= u.getArmor() / 5))
                    .count() >= half)
              || (units.stream().filter(u -> u.getTargetingCrits() >= 2).count() >= half);
    }

    /** IO:BF, Transporting Infantry, p. 167. A carried Formation has no independent board position. */
    public boolean isEmbarked() {
        return transportId != null;
    }

    public Integer carrierId() {
        return transportId;
    }

    public boolean isCarriedBy(int carrierId) {
        return (transportId != null) && (transportId == carrierId);
    }

    public boolean isExternallyMounted() {
        return externallyMounted;
    }

    public void embark(int carrierId, boolean external) {
        setHullDown(false);
        setInsideBuildings(false);
        setOnTopOfBuildings(false);
        transportId = carrierId;
        externallyMounted = external;
        position = null;
    }

    public void dismount(BoardLocation location, int round) {
        transportId = null;
        externallyMounted = false;
        if (packedCargoTons > 0) {
            cargoReadyRound = round + 3;
            packedCargoTons = 0;
        }
        dismountedFromBay = false;
        dismountRound = round;
        position = location;
    }

    public void dismountFromBay(BoardLocation location, int round) {
        dismount(location, round);
        dismountedFromBay = true;
    }

    public boolean dismountedIn(int round) {
        return (dismountRound != null) && (dismountRound == round);
    }

    public int getReconMpSpent() {
        return reconMpSpent;
    }

    public void spendReconMp() {
        reconMpSpent++;
    }

    public void resetReconMp() {
        reconMpSpent = 0;
    }

    public boolean mustWithdraw() {
        return forcedWithdrawal || isBroken() || isRouted();
    }

    /** In Forced Withdrawal for its damage, not for a failed Morale Check (IO:BF p. 172). */
    public boolean isInForcedWithdrawal() {
        return forcedWithdrawal && !isBroken() && !isRouted();
    }

    public void startForcedWithdrawal() {
        forcedWithdrawal = true;
    }

    public boolean hasWithdrawn() {
        return withdrawn;
    }

    public void markWithdrawn() {
        withdrawn = true;
    }

    public boolean isMoraleRestricted() {
        return isShaken() || isBroken() || isRouted();
    }

    private boolean underArtilleryFire;
    private Integer lastCruiseMissileAttack;

    /** Attack, including a miss, triggers infantry morale. Cruise missiles also affect the following turn (p. 173). */
    public void recordArtilleryAttack(SBFFormation attacker, int round, boolean cruise) {
        recordAttack(attacker);
        underArtilleryFire = true;
        if (cruise) {
            lastCruiseMissileAttack = round;
        }
    }

    public boolean mustCheckArtilleryMorale(int round) {
        return (underArtilleryFire && isAnyTypeOf(SBFElementType.BA, SBFElementType.CI))
              || (lastCruiseMissileAttack != null && lastCruiseMissileAttack == round);
    }

    public boolean recentCruiseMissileAttack(int round) {
        return (lastCruiseMissileAttack != null) && (round >= lastCruiseMissileAttack) && (round - lastCruiseMissileAttack < 2);
    }

    public void recordAttack(SBFFormation attacker) {
        attackedThisTurn = true;
        attackedByMekThisTurn |= attacker.isType(SBFElementType.BM);
    }

    public boolean wasAttackedByMek() {
        return attackedByMekThisTurn;
    }

    public void recordHostileEntry() {
        hostileEnteredHex = true;
    }

    public int updateQuietTurns() {
        quietTurns = (attackedThisTurn || hostileEnteredHex || !isMoraleRestricted()) ? 0 : quietTurns + 1;
        return quietTurns;
    }

    public void clearMoraleEvents() {
        underArtilleryFire = false;
        attackedThisTurn = false;
        attackedByMekThisTurn = false;
        hostileEnteredHex = false;
    }

    public void becomeEnraged(int round) {
        enragedRound = round;
    }

    public boolean isEnraged() {
        return enragedRound != null;
    }

    public boolean hasEnragedDamageBonus(int round) {
        return (enragedRound != null) && (round > enragedRound) && (round <= enragedRound + 2);
    }

    public boolean isShaken() {
        return moraleStatus == MoraleStatus.SHAKEN;
    }

    public boolean isUnsteady() {
        return moraleStatus == MoraleStatus.UNSTEADY;
    }

    public boolean isBroken() {
        return moraleStatus == MoraleStatus.BROKEN;
    }

    public boolean isRouted() {
        return moraleStatus == MoraleStatus.ROUTED;
    }

    public MoraleStatus moraleStatus() {
        return moraleStatus;
    }

    public void setMoraleStatus(MoraleStatus status) {
        moraleStatus = status;
    }
}
