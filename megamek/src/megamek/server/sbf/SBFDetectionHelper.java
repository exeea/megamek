/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.server.sbf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import megamek.common.Facing;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.actions.sbf.SBFAttackAction;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.rolls.Roll;
import megamek.common.rolls.TargetRoll;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;

/**
 * This class performs detection of formations in the Detection and Recon phase, IO:BF p.195 for the SBFGameManager.
 */
record SBFDetectionHelper(SBFGameManager gameManager) implements SBFGameManagerHelper {
    private static final MMLogger logger = MMLogger.create(SBFDetectionHelper.class);

    /**
     * Performs sensor detection for all formations of all players and updates the visibility status in the game
     * accordingly. Does not send anything.
     */
    void performSensorDetection() {
        if (game().usesDoubleBlind()) {
            for (Player player : game().getPlayersList()) {
                logger.info("Detection for {}", player.getName()); // TODO remove or move to protocol
                performSensorDetection(player);
            }
        }
    }

    private void performSensorDetection(Player viewingPlayer) {
        List<SBFFormation> viewingFormations = game().getActiveFormations().stream()
              .filter(u -> u.getOwnerId() == viewingPlayer.getId())
              .filter(this::canDetect)
              .toList();

        List<SBFFormation> hostileFormations = game().getActiveFormations().stream()
              .filter(u -> game().areHostile(u, viewingPlayer))
              .filter(this::canDetect)
              .toList();

        for (SBFFormation hostileFormation : hostileFormations) {
            SBFFormation ghostObserver = null;
            List<SBFFormation> pingObservers = new ArrayList<>();
            SBFVisibilityStatus visibilityStatus = game().visibilityHelper().getObservedVisibility(viewingPlayer.getId(),
                  hostileFormation.getId());
            for (SBFFormation viewingFormation : viewingFormations) {
                if (!game().onSameBoard(viewingFormation, hostileFormation)) {
                    continue;
                }
                int distance = viewingFormation.getPosition().coords().distance(hostileFormation.getPosition().coords());
                if (distance <= visualRange(game(), viewingFormation, hostileFormation.getPosition())) {
                    visibilityStatus = visibilityStatus.bestOf(identifyVisually(game(), viewingFormation,
                          hostileFormation, () -> Compute.d6(2)));
                }
                var detectionModifiers = new SBFDetectionModifiers(game(), viewingFormation, hostileFormation,
                      hostileFormation.getPosition(),
                      game().minefields().inEmp(hostileFormation.getPosition(), game().getCurrentRound()));
                if (detectionModifiers.getValue() != TargetRoll.CHECK_FALSE) {
                    Roll diceRoll = Compute.rollD6(2);
                    int rollResult = diceRoll.getIntValue() + detectionModifiers.getValue();
                    SBFVisibilityStatus detectionResult = sensorDetectionResult(rollResult, game().getOptions().booleanOption(SBFRuleOptions.SIMPLE_DETECTION));
                    // TODO remove or move to protocol:
                    logger.info("Detected from {} to {} result {}",
                          viewingFormation.getId(),
                          hostileFormation.getId(),
                          detectionResult);
                    if (concealedInHardenedUrban(game(), hostileFormation) && detectionResult.isLocated()) {
                        detectionResult = SBFVisibilityStatus.BLIP;
                    }
                    if (detectionResult == SBFVisibilityStatus.SENSOR_GHOST) {
                        ghostObserver = viewingFormation;
                    } else if (detectionResult == SBFVisibilityStatus.SENSOR_PING) {
                        pingObservers.add(viewingFormation);
                    }
                    visibilityStatus = visibilityStatus.bestOf(detectionResult);
                }
            }
            game().visibilityHelper().setVisibility(viewingPlayer.getId(), hostileFormation.getId(), visibilityStatus);
            if (visibilityStatus == SBFVisibilityStatus.SENSOR_PING) {
                for (SBFFormation observer : pingObservers) {
                    reportTo(viewingPlayer, sensorPing(observer, hostileFormation.getPosition()));
                }
            } else if (visibilityStatus == SBFVisibilityStatus.SENSOR_GHOST && ghostObserver != null) {
                reportTo(viewingPlayer, new SBFReportEntry(inFrontOf(ghostObserver, hostileFormation.getPosition())
                      ? 3600 : 3602).add(ghostObserver.generalName()));
            }
        }
    }

    private void reportTo(Player player, SBFReportEntry report) {
        game().getGameReport().restrictTo(report, Set.of(player.getId()));
        addReport(report);
    }

    /** IO:BF p. 196. Report the nearest hexside or vertex seen from this observer. */
    static SBFReportEntry sensorPing(SBFFormation observer, BoardLocation target) {
        double bearing = observer.getPosition().coords().radian(target.coords());
        int spoke = (int) Math.round(bearing / (Coords.HEX_SIDE / 2));
        int side = spoke / 2;
        String facing = Facing.valueOfInt(side).name();
        if (spoke % 2 == 0) {
            return new SBFReportEntry(3603).add(observer.generalName()).add(facing);
        }
        return new SBFReportEntry(3604).add(observer.generalName()).add(facing).add(Facing.valueOfInt(side + 1).name());
    }

    static boolean inFrontOf(SBFFormation observer, BoardLocation position) {
        int bearing = observer.getPosition().coords().direction(position.coords());
        int relative = Math.floorMod(bearing - observer.getReconFacing(), 6);
        return relative == 0 || relative == 1 || relative == 5;
    }

    /** IO:BF “Losing Contact”, p. 198. Identification persists until the target leaves every observer's range. */
    void endMovement() {
        if (!game().usesDoubleBlind()) {
            return;
        }
        for (Set<Player> side : visionGroups()) {
            List<SBFFormation> observers = observers(side);
            for (SBFFormation target : game().getActiveFormations()) {
                if (!canDetect(target) || !game().areHostile(target, side.iterator().next())) {
                    continue;
                }
                boolean inRange = observers.stream().filter(viewer -> game().onSameBoard(viewer, target))
                      .anyMatch(viewer -> SBFDetectionModifiers.sensorRange(game(), viewer) > 0
                            && viewer.getPosition().coords().distance(target.getPosition().coords())
                            <= SBFDetectionModifiers.sensorRange(game(), viewer));
                if (!inRange) {
                    side.forEach(player -> game().visibilityHelper().setInvisible(player.getId(), target.getId()));
                }
            }
        }
        gameManager.unitUpdateHelper.sendAllUnitUpdate();
    }

    private List<Set<Player>> visionGroups() {
        return game().getPlayersList().stream().map(p -> game().visibilityHelper().playersSharingVision(p.getId()))
              .distinct().toList();
    }

    private List<SBFFormation> observers(Set<Player> side) {
        return game().getActiveFormations().stream().filter(this::canDetect)
              .filter(f -> side.contains(game().getPlayer(f.getOwnerId()))).toList();
    }

    private boolean canDetect(SBFFormation formation) {
        return formation.occupiesGroundHex() && !formation.isHidden();
    }

    /** IO:BF “Visual Identification”, p. 196. Apply automatic identification on entry, including transit. */
    void enterGroundHex(SBFFormation mover, BoardLocation from, BoardLocation entered, IntSupplier dice) {
        game().visibilityHelper().leftHex(mover.getId(), from);
        enterUrban(mover, entered, dice);
        for (SBFFormation other : game().getActiveFormationsAt(entered)) {
            if (other.occupiesGroundHex() && game().areHostile(other, game().getPlayer(mover.getOwnerId()))) {
                identifyInSameHex(other.getOwnerId(), mover, entered);
                if (identifyInSameHex(mover.getOwnerId(), other, entered)) {
                    gameManager.sendUnitUpdate(other);
                }
            }
        }
    }

    private boolean identifyInSameHex(int player, SBFFormation target, BoardLocation location) {
        var previous = game().getVisibility(game().getPlayer(player), target.getId());
        if (!previous.isLocated() || previous.bestOf(SBFVisibilityStatus.EYES_ON_TARGET) == previous
              || concealedInHardenedUrban(game(), target, location)) {
            return false;
        }
        game().visibilityHelper().setVisibility(player, target.getId(), SBFVisibilityStatus.EYES_ON_TARGET);
        return true;
    }

    /** IO:BF p. 197, Hardened Urban footnote. Combat removes concealment for this occupation of this hex. */
    void noteCombat(SBFFormation formation, BoardLocation location) {
        if (game().usesDoubleBlind() && location != null && !formation.isOnRadarMap()) {
            Hex hex = game().getHex(location);
            if (hex != null && hex.terrainLevel(Terrains.BUILDING) == 4) {
                game().visibilityHelper().exposeUrbanPosition(formation.getId(), location);
            }
        }
    }

    void finishCombatRecon(IntSupplier dice) {
        for (var action : game().getActionsVector()) {
            if (action instanceof SBFAttackAction attack) {
                game().getFormation(attack.getEntityId()).ifPresent(f -> noteCombat(f, f.getPosition()));
                var targets = attack instanceof SBFAirGroundAttack run ? run.targets() : List.of(attack.getTargetId());
                targets.forEach(id -> game().getFormation(id).ifPresent(f -> noteCombat(f, f.getPosition())));
            }
        }
        finishAerialRecon(dice);
    }

    /** IO:BF “Losing Contact”, p. 198. Each urban hex actually entered may degrade an existing contact. */
    void enterUrban(SBFFormation target, BoardLocation entered, IntSupplier dice) {
        Hex hex = game().getHex(entered);
        if (!game().usesDoubleBlind() || hex == null || !hex.containsTerrain(Terrains.BUILDING)) {
            return;
        }
        for (Set<Player> side : visionGroups()) {
            Player player = side.iterator().next();
            SBFVisibilityStatus known = game().getVisibility(player, target.getId());
            if (!game().areHostile(target, player) || !known.isLocated()) {
                continue;
            }
            boolean retained = observers(side).stream().filter(f -> entered.isSameBoardAs(f.getPosition()))
                  .anyMatch(f -> retainsUrbanContact(f, target, entered, known, dice));
            if (!retained) {
                SBFVisibilityStatus reduced = known == SBFVisibilityStatus.BLIP
                      ? SBFVisibilityStatus.INVISIBLE : SBFVisibilityStatus.BLIP;
                side.forEach(p -> game().visibilityHelper().setVisibility(p.getId(), target.getId(), reduced));
            }
        }
    }

    private boolean retainsUrbanContact(SBFFormation viewer, SBFFormation target, BoardLocation entered,
          SBFVisibilityStatus known, IntSupplier dice) {
        int distance = entered.coords().distance(viewer.getPosition().coords());
        int sensor = SBFDetectionModifiers.sensorRange(game(), viewer);
        boolean sensorReach = sensor > 0 && distance <= sensor;
        if (known == SBFVisibilityStatus.BLIP) {
            return sensorReach && dice.getAsInt() + new SBFDetectionModifiers(game(), viewer, target, entered,
                  game().minefields().inEmp(entered, game().getCurrentRound())).getValue() > 6;
        }
        if (concealedInHardenedUrban(game(), target, entered)) {
            return false;
        }
        boolean visualReach = distance <= visualRange(game(), viewer, entered);
        boolean visualHeld = !visualReach || distance == 0 || dice.getAsInt() + visualModifier(game(), viewer, entered) > 6;
        boolean scanHeld = !sensorReach || dice.getAsInt() + scanModifier(game(), viewer, target, entered, distance) > 6;
        return (visualReach || sensorReach) && visualHeld && scanHeld;
    }

    /**
     * IO:BF p.197
     *
     * @param rollWithModifiers The 2d6 roll including modifiers
     *
     * @return The visibility status from sensor scan
     */
    static SBFVisibilityStatus sensorDetectionResult(int rollWithModifiers, boolean simple) {
        if (simple) {
            return rollWithModifiers >= 7 ? SBFVisibilityStatus.BLIP : SBFVisibilityStatus.INVISIBLE;
        }
        return switch (Math.clamp(rollWithModifiers, 2, 12)) {
            case 2 -> SBFVisibilityStatus.INVISIBLE;
            case 3, 4 -> SBFVisibilityStatus.SENSOR_GHOST;
            case 5, 6 -> SBFVisibilityStatus.SENSOR_PING;
            case 7, 8, 9 -> SBFVisibilityStatus.BLIP;
            case 10, 11 -> SBFVisibilityStatus.PARTIAL_SCAN;
            default -> SBFVisibilityStatus.VISIBLE;
        };
    }

    /** IO:BF “Visual Identification” and “Recon Scan”, pp. 196–197. */
    static SBFVisibilityStatus identifyVisually(SBFGame game, SBFFormation viewer, SBFFormation target, IntSupplier dice) {
        int distance = viewer.getPosition().coords().distance(target.getPosition().coords());
        int range = visualRange(game, viewer, target.getPosition());
        if (distance > range) {
            return SBFVisibilityStatus.INVISIBLE;
        }
        if (concealedInHardenedUrban(game, target)) {
            return SBFVisibilityStatus.BLIP;
        }
        if (distance == 0) {
            return SBFVisibilityStatus.EYES_ON_TARGET;
        }
        int result = dice.getAsInt() + visualModifier(game, viewer, target.getPosition());
        if (game.getOptions().booleanOption(SBFRuleOptions.SIMPLE_RECON)) {
            return simpleReconResult(result);
        }
        return result <= 3 ? SBFVisibilityStatus.BLIP : result <= 5 ? SBFVisibilityStatus.SOMETHING_OUT_THERE
              : result <= 8 ? SBFVisibilityStatus.I_GOT_SOMETHING : SBFVisibilityStatus.EYES_ON_TARGET;
    }

    private static SBFVisibilityStatus simpleReconResult(int modifiedRoll) {
        return modifiedRoll >= 7 ? SBFVisibilityStatus.VISIBLE : SBFVisibilityStatus.BLIP;
    }

    boolean scan(int player, SBFFormation viewer, SBFFormation target, IntSupplier dice) {
        SBFGame game = game();
        if (game.getPhase() != GamePhase.SBF_DETECTION_REPORT || viewer == null || target == null
              || !gameManager.initiativeHelper.hasReconPriority(player) || viewer.getOwnerId() != player
              || !game.areHostile(target, game.getPlayer(player)) || !canDetect(viewer) || !canDetect(target)
              || !game.onSameBoard(viewer, target) || SBFMovePath.normalMovementPoints(viewer, game) <= 0) {
            return false;
        }
        SBFVisibilityStatus previous = game.getVisibility(game.getPlayer(player), target.getId());
        int range = SBFDetectionModifiers.sensorRange(game, viewer);
        int distance = viewer.getPosition().coords().distance(target.getPosition().coords());
        if (!previous.isLocated() || previous == SBFVisibilityStatus.VISIBLE || range == 0 || distance > range) {
            return false;
        }
        viewer.spendReconMp();
        resolveScan(viewer, target, distance, dice);
        gameManager.sendUnitUpdate(viewer);
        gameManager.sendUnitUpdate(target);
        return true;
    }

    private void resolveScan(SBFFormation viewer, SBFFormation target, int distance, IntSupplier dice) {
        SBFGame game = game();
        int player = viewer.getOwnerId();
        SBFVisibilityStatus previous = game.getVisibility(game.getPlayer(player), target.getId());
        int roll = dice.getAsInt();
        boolean simple = game.getOptions().booleanOption(SBFRuleOptions.SIMPLE_RECON);
        if (!simple && roll == 2) {
            game.visibilityHelper().setVisible(target.getOwnerId(), viewer.getId());
        } else if (!concealedInHardenedUrban(game, target)) {
            int result = roll + scanModifier(game, viewer, target, target.getPosition(), distance);
            SBFVisibilityStatus scanned = simple ? simpleReconResult(result) : result <= 3 ? SBFVisibilityStatus.BLIP
                  : result <= 5 ? SBFVisibilityStatus.I_GOT_SOMETHING : result <= 8 ? SBFVisibilityStatus.PARTIAL_SCAN_RECON
                  : SBFVisibilityStatus.VISIBLE;
            game.visibilityHelper().setVisibility(player, target.getId(), previous.bestOf(scanned));
        }
    }

    /** Orders are private until all attacks have been declared. A combat encounter cancels the mission (p. 196). */
    boolean orderAerialScan(int player, SBFFormation squadron, SBFFormation target) {
        SBFGame game = game();
        if (!game.usesDoubleBlind() || !game.getPhase().isFiring() || squadron == null || target == null
              || game.getTurn() == null || !game.getTurn().isValid(player, squadron, game)
              || !squadron.isOnRadarMap() || !target.occupiesGroundHex()
              || !game.areHostile(target, game.getPlayer(player)) || !undisturbed(squadron)) {
            return false;
        }
        SBFVisibilityStatus known = game.getVisibility(game.getPlayer(player), target.getId());
        int sensor = SBFDetectionModifiers.sensorRange(game, squadron);
        var path = game.visibilityHelper().aerialReconPath(squadron.getId());
        if (!known.isLocated() || known == SBFVisibilityStatus.VISIBLE || sensor <= 0
              || distanceFromFlightPath(path, target) > sensor) {
            return false;
        }
        int limit = squadron.hasAnySUAOf(BattleForceSUA.PRB, BattleForceSUA.RCN) ? Integer.MAX_VALUE
              : path.size() / 8;
        return game.visibilityHelper().orderAerialScan(squadron.getId(), target.getId(), limit);
    }

    private static int distanceFromFlightPath(List<BoardLocation> path, SBFFormation target) {
        return path.stream().filter(hex -> hex.isSameBoardAs(target.getPosition()))
              .mapToInt(hex -> hex.coords().distance(target.getPosition().coords())).min().orElse(Integer.MAX_VALUE);
    }

    private boolean undisturbed(SBFFormation squadron) {
        return squadron.getEngagedOpponents().isEmpty() && game().getActionsVector().stream()
              .filter(SBFAttackAction.class::isInstance).map(SBFAttackAction.class::cast)
              .noneMatch(a -> a.getEntityId() == squadron.getId() || a.getTargetId() == squadron.getId());
    }

    /** A quiet turn in the qualifying radar zone locates exposed ground Formations, IO:BF “Aerial Recon”, p. 196. */
    void finishAerialRecon(IntSupplier dice) {
        SBFGame game = game();
        if (!game.usesDoubleBlind()) {
            return;
        }
        for (SBFFormation squadron : game.getActiveFormations()) {
            if (!squadron.isOnRadarMap() || !squadron.isDeployed()) {
                continue;
            }
            var path = game.visibilityHelper().aerialReconPath(squadron.getId());
            var scans = game.visibilityHelper().takeAerialScans(squadron.getId());
            if (!undisturbed(squadron)) {
                continue;
            }
            int owner = squadron.getOwnerId();
            for (SBFFormation target : game.getActiveFormations()) {
                if (!canDetect(target) || !game.areHostile(target, game.getPlayer(owner))) {
                    continue;
                }
                if (inAerialVisualRange(game, squadron, target)) {
                    var seen = game.visibilityHelper().getObservedVisibility(owner, target.getId());
                    game.visibilityHelper().setVisibility(owner, target.getId(), seen.bestOf(SBFVisibilityStatus.BLIP));
                }
                if (scans.contains(target.getId())) {
                    resolveScan(squadron, target, distanceFromFlightPath(path, target), dice);
                }
            }
        }
        gameManager.unitUpdateHelper.sendAllUnitUpdate();
    }

    private static boolean concealedInHardenedUrban(SBFGame game, SBFFormation target) {
        return concealedInHardenedUrban(game, target, target.getPosition());
    }

    private static boolean concealedInHardenedUrban(SBFGame game, SBFFormation target, BoardLocation location) {
        Hex hex = game.getHex(location);
        return hex != null && hex.terrainLevel(Terrains.BUILDING) == 4 && target.getEngagedOpponents().isEmpty()
              && !game.visibilityHelper().exposedAt(target.getId(), location);
    }

    private static int visualModifier(SBFGame game, SBFFormation viewer, BoardLocation target) {
        return visualRange(game, viewer, target) - viewer.getPosition().coords().distance(target.coords())
              + reconModifier(game, viewer, target) - (visualLevel(game, target) - 1);
    }

    private static int scanModifier(SBFGame game, SBFFormation viewer, SBFFormation target, BoardLocation location, int distance) {
        int probe = viewer.hasAnySUAOf(BattleForceSUA.PRB, BattleForceSUA.BH) ? 1 : 0;
        if (target.hasAnySUAOf(BattleForceSUA.ECM, BattleForceSUA.AECM) || game.minefields().inEmp(location, game.getCurrentRound())) {
            probe--;
        }
        return SBFDetectionModifiers.sensorRange(game, viewer) - distance
              + reconModifier(game, viewer, location) + probe + (viewer.hasSUA(BattleForceSUA.RCN) ? 1 : 0);
    }

    private static int reconModifier(SBFGame game, SBFFormation viewer, BoardLocation target) {
        int modifier = viewer.isRouted() ? -6 : viewer.isBroken() ? -3 : viewer.isShaken() ? -1 : 0;
        Hex hex = game.getHex(target);
        if (hex != null) {
            int foliage = Math.max(hex.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.JUNGLE));
            modifier -= Math.clamp(foliage, 0, 2);
            if (hex.containsTerrain(Terrains.BUILDING)) {
                modifier -= 1 + Math.clamp(hex.terrainLevel(Terrains.BUILDING), 1, 3);
            }
        }
        return modifier;
    }

    /** IO:BF pp. 196-197 aerial detection coverage also determines airborne IF spotting range (p. 169). */
    static boolean inAerialVisualRange(SBFGame game, SBFFormation squadron, SBFFormation groundTarget) {
        if (!squadron.isOnRadarMap() || groundTarget.isHidden() || groundTarget.isOnRadarMap() || groundTarget.isEmbarked()
              || (groundTarget.getPosition() == null)) {
            return false;
        }
        Hex hex = game.getHex(groundTarget.getPosition());
        if ((hex == null) || hex.containsTerrain(Terrains.BUILDING)
              || (Math.max(hex.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.JUNGLE)) >= 3)
              || groundTarget.getUnits().stream().allMatch(unit -> unit.isUnderwater(game, groundTarget))) {
            return false;
        }
        int visual = squadron.hasAnySUAOf(BattleForceSUA.PRB, BattleForceSUA.RCN) ? 1
              : visualLevel(game, groundTarget.getPosition());
        if (squadron.getRadarZone() == SBFRadarZone.INNER) {
            return visual <= 2;
        }
        if (squadron.getRadarZone() != SBFRadarZone.CENTRAL) {
            return false;
        }
        return (visual <= 3) || squadron.getGroundFlightPath().stream()
              .anyMatch(step -> step.isSameBoardAs(groundTarget.getPosition())
                    && (step.coords().distance(groundTarget.getPosition().coords()) <= 4));
    }

    /** Ground visual spotting ranges, IO:BF p. 197. */
    static int visualRange(SBFGame game, SBFFormation formation) {
        return visualRange(game, formation, formation.getPosition());
    }

    static int visualRange(SBFGame game, SBFFormation formation, BoardLocation target) {
        int srchModifier = (formation.hasSUA(BattleForceSUA.SRCH) && !illuminated(game, target) &&
              (game.getPlanetaryConditions().getLight().isDuskDawn()
                    || game.getPlanetaryConditions().getLight().isMoonlessOrPitchBack())) ? 1 : 0;
        // IO:BF p. 197. Mixed and ProtoMek Formations use the BattleMek/Vehicle column. On a roof (p. 217) "infantry
        // units use the visual ranges for BattleMechs and Vehicles" and the building's height extends the range.
        boolean onRoof = game.usesUrbanCombat() && formation.isOnTopOfBuildings();
        boolean mekOrVehicle = onRoof || formation.isAnyTypeOf(SBFElementType.BM, SBFElementType.V, SBFElementType.MX,
              SBFElementType.PM, SBFElementType.AS);
        int roof = onRoof ? SBFUrbanCombat.roofVisualBonus(game.getHex(formation.getPosition())) : 0;
        return roof + switch (visualLevel(game, target)) {
            case 4 -> srchModifier;
            case 3 -> 1 + srchModifier;
            case 2 -> srchModifier + (mekOrVehicle ? 2 : 1);
            default -> srchModifier + (mekOrVehicle ? 4 : 2);
        };
    }

    private static boolean illuminated(SBFGame game, BoardLocation site) {
        return game.isIlluminated(site);
    }

    private static int visualLevel(SBFGame game, BoardLocation site) {
        PlanetaryConditions conditions = game.getPlanetaryConditions();
        Light light = illuminated(game, site) ? Light.DAY : conditions.getLight();
        Weather weather = conditions.getWeather();
        Fog fog = conditions.getFog();
        boolean sand = conditions.isBlowingSand();
        // TODO: this is missing quite a few conditions
        if (light.isMoonlessOrPitchBack()) {
            return 4;
        } else if (light.isDuskDawn() || sand || weather.isAnyOf(Weather.SLEET, Weather.LIGHT_HAIL,
              Weather.HEAVY_HAIL, Weather.LIGHTNING_STORM, Weather.ICE_STORM)) {
            return 3;
        } else if (weather.isAnyRain() || weather.isAnySnowfall() || !fog.isFogNone()) {
            return 2;
        } else {
            return 1;
        }
    }
}
