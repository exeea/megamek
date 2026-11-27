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

import megamek.common.Player;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFPublicReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.server.victory.VictoryResult;

public record SBFPhaseEndManager(SBFGameManager gameManager) implements SBFGameManagerHelper {

    void managePhase() {
        switch (gameManager.getGame().getPhase()) {
            case LOUNGE:
                gameManager.addPendingReportsToGame();
                gameManager.changePhase(GamePhase.EXCHANGE);
                break;
            case EXCHANGE:
            case STARTING_SCENARIO:
                if (game().getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)) {
                    var survey = SBFInitiativeModifiers.initialSurvey(game());
                    game().scenarioProgress().saveInitialIntelligence(survey);
                    survey.forEach((side, info) -> gameManager.addReport(new SBFPublicReportEntry(1067)
                          .add(game().getPlayer(side).getName()).add(info.rating())));
                }
                SBFHiddenCommand.validateSetup(gameManager);
                SBFCommandAbilities.validateSetup(gameManager);
                gameManager.send(SBFScenarioResolution.markerPacket(game()));
                gameManager.addPendingReportsToGame();
                if (game().getCurrentRound() < 0) {
                    gameManager.incrementAndSendGameRound();
                }
                game().setupDeployment();
                if (game().shouldDeployThisRound()) {
                    gameManager.rollInitiative();
                    gameManager.changePhase(GamePhase.DEPLOYMENT);
                } else {
                    gameManager.changePhase(GamePhase.INITIATIVE);
                }
                break;
            case SET_ARTILLERY_AUTO_HIT_HEXES:
                gameManager.addPendingReportsToGame();
                boolean hasMinesToDeploy = gameManager.getGame().getPlayersList().stream()
                      .anyMatch(Player::hasMinefields);
                if (hasMinesToDeploy) {
                    gameManager.changePhase(GamePhase.DEPLOY_MINEFIELDS);
                } else {
                    gameManager.changePhase(GamePhase.INITIATIVE);
                }
                break;
            case END:
                if (game().engagementOrders().remainingEnds().isEmpty() && !game().takingAdjustmentOrders()) {
                    finishRound();
                }
                break;
            case DEPLOY_MINEFIELDS, END_REPORT:
                gameManager.changePhase(GamePhase.INITIATIVE);
                break;
            case DEPLOYMENT:
                gameManager.getGame().clearDeploymentThisRound();
                if (gameManager.getGame().getCurrentRound() == 0) {
                    gameManager.changePhase(GamePhase.INITIATIVE);
                } else {
                    startMovementSequence();
                }
                break;
            case INITIATIVE:
                gameManager.addPendingReportsToGame();
                gameManager.changePhase(GamePhase.INITIATIVE_REPORT);
                break;
            case INITIATIVE_REPORT:
                if (game().getCurrentRound() == 1 && !SBFCommandDesignation.ready(gameManager)) {
                    break;
                }
                gameManager.getGame().setupDeployment();
                if (gameManager.getGame().shouldDeployThisRound()) {
                    // TODO: could be handled in SBFGame.isCurrentPhasePlayable
                    gameManager.changePhase(GamePhase.DEPLOYMENT);
                } else {
                    startMovementSequence();
                }
                break;
            case SBF_DETECTION:
                gameManager.changePhase(GamePhase.SBF_DETECTION_REPORT);
                break;
            case SBF_DETECTION_REPORT:
                gameManager.changePhase(GamePhase.TARGETING);
                break;
            case PREMOVEMENT:
                gameManager.changePhase(GamePhase.MOVEMENT);
                break;
            case MOVEMENT:
                if (gameManager.resolveDropLandings()) {
                    break;
                }
                SBFMovementHazards.extremeDepthAtMovementEnd(gameManager, () -> megamek.common.compute.Compute.d6(2));
                if (gameManager.usesDoubleBlind()) {
                    gameManager.detectionHelper.endMovement();
                }
                goToDependingOnReport(GamePhase.MOVEMENT_REPORT, GamePhase.OFFBOARD);
                break;
            case MOVEMENT_REPORT:
                gameManager.changePhase(GamePhase.OFFBOARD);
                break;
            case PRE_FIRING:
                gameManager.changePhase(GamePhase.FIRING);
                break;
            case FIRING:
                if (game().getActionHandlers().isEmpty()) {
                    addReport(new SBFReportHeader(3000));
                }
                if (!gameManager.actionsProcessor.handleActions()) {
                    break;
                }
                if (game().usesDoubleBlind()) {
                    gameManager.detectionHelper.finishCombatRecon(() -> Compute.d6(2));
                }
                goToDependingOnReport(GamePhase.FIRING_REPORT, GamePhase.END);
                break;
            case FIRING_REPORT:
                if (!SBFCombatDamage.hasPendingChoices(game())) {
                    gameManager.changePhase(GamePhase.END);
                }
                break;
            case TARGETING:
                goToDependingOnReport(GamePhase.TARGETING_REPORT, GamePhase.PREMOVEMENT);
                break;
            case OFFBOARD:
                goToDependingOnReport(GamePhase.OFFBOARD_REPORT, GamePhase.PRE_FIRING);
                break;
            case OFFBOARD_REPORT:
                // sendSpecialHexDisplayPackets();
                gameManager.changePhase(GamePhase.PRE_FIRING);
                break;
            case TARGETING_REPORT:
                gameManager.changePhase(GamePhase.PREMOVEMENT);
                break;
            default:
                break;
        }
    }

    private void finishRound() {
        if (SBFFormationCommand.finishReunions(game())) {
            gameManager.entityAllUpdate();
        }
        // IO:BF, Capturing Objectives, p. 174: observe the hex after all End Phase regrouping orders.
        SBFScenarioResolution.updateObjectives(gameManager);
        VictoryResult result = SBFVictory.atRoundEnd(game());
        if (result.isVictory()) {
            SBFScenarioResolution.reportFinal(gameManager, result, () -> Compute.d6(2));
            if (result.isDraw()) {
                addReport(new SBFPublicReportEntry(3500));
            } else {
                String name = result.getWinningTeam() == Player.TEAM_NONE
                      ? game().getPlayer(result.getWinningPlayer()).getName() : Player.TEAM_NAMES[result.getWinningTeam()];
                addReport(new SBFPublicReportEntry(3501).add(name));
            }
            gameManager.addPendingReportsToGame();
            gameManager.changePhase(GamePhase.VICTORY);
        } else if (!gameManager.getPendingReports().isEmpty()) {
            gameManager.addPendingReportsToGame();
            gameManager.changePhase(GamePhase.END_REPORT);
        } else {
            gameManager.changePhase(GamePhase.INITIATIVE);
        }
    }

    /** IO:BF, “Detection and Reconnaissance Phase”, p. 195: resolve once after initiative and before movement. */
    private void startMovementSequence() {
        gameManager.changePhase(gameManager.usesDoubleBlind() ? GamePhase.SBF_DETECTION : GamePhase.TARGETING);
    }

    private void goToDependingOnReport(GamePhase reportPhase, GamePhase afterReportPhase) {
        if (gameManager.getPendingReports().size() > 1) {
            gameManager.getGame().addReports(gameManager.getPendingReports());
            gameManager.changePhase(reportPhase);
        } else {
            // just the header, so we'll add the <nothing> label
            gameManager.addReport(new SBFReportEntry(1205));
            gameManager.getGame().addReports(gameManager.getPendingReports());
            gameManager.sendReport();
            gameManager.changePhase(afterReportPhase);
        }
    }
}
