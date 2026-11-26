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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.actions.sbf.SBFAttackAction;
import megamek.common.game.IGame;
import megamek.common.enums.GamePhase;
import megamek.common.game.InGameObject;
import megamek.common.interfaces.ReportEntry;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.InvalidPacketDataException;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFPlayerTurn;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;
import megamek.common.strategicBattleSystems.SBFRuleOptionsUser;
import megamek.common.strategicBattleSystems.SBFTurn;
import megamek.common.strategicBattleSystems.SBFWaterWidths;
import megamek.logging.MMLogger;
import megamek.server.AbstractGameManager;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/**
 * This class manages an SBF game on the server side. As of 2024, this is under construction.
 */
public final class SBFGameManager extends AbstractGameManager implements SBFRuleOptionsUser {
    private static final MMLogger logger = MMLogger.create(SBFGameManager.class);

    private SBFGame game;

    private final List<SBFReportEntry> pendingReports = new ArrayList<>();

    record PendingPacket(int recipient, Packet packet) {
    }

    private final List<PendingPacket> pendingPackets = new ArrayList<>();

    final SBFPhaseEndManager phaseEndManager = new SBFPhaseEndManager(this);
    final SBFPhasePreparationManager phasePreparationManager = new SBFPhasePreparationManager(this);
    final SBFMovementProcessor movementProcessor = new SBFMovementProcessor(this);
    final SBFAttackProcessor attackProcessor = new SBFAttackProcessor(this);
    final SBFActionsProcessor actionsProcessor = new SBFActionsProcessor(this);
    final SBFInitiativeHelper initiativeHelper = new SBFInitiativeHelper(this);
    final SBFUnitUpdateHelper unitUpdateHelper = new SBFUnitUpdateHelper(this);
    final SBFDetectionHelper detectionHelper = new SBFDetectionHelper(this);

    @Override
    public void handlePacket(int connId, Packet packet) {
        try {
            if (game.getPhase() == GamePhase.SBF_DETECTION_REPORT && packet.command() == PacketCommand.PLAYER_READY
                  && !initiativeHelper.hasReconPriority(connId)) {
                sendServerChat(connId, "Reconnaissance follows side initiative. Wait for your side's turn.");
                return;
            }
            super.handlePacket(connId, packet);
            try {
                switch (packet.command()) {
                    case ENTITY_MOVE:
                        receiveMovement(packet, connId);
                        break;
                    case ENTITY_ATTACK:
                        receiveAttack(packet, connId);
                        break;
                    default:
                        break;
                }
            } catch (InvalidPacketDataException e) {
                logger.error("Invalid packet data:", e);
                if (packet.command() == PacketCommand.ENTITY_MOVE) {
                    correctRejectedTurn(connId, null, true, false);
                } else if (packet.command() == PacketCommand.ENTITY_ATTACK) {
                    correctRejectedTurn(connId, null, true, true);
                }
            }
        } finally {
            logger.debug("Leaving handle packet: {}", packet.command());
            sendPendingPackets();
        }
    }

    /**
     * Sends all pending packets to eligible players and clears out the pending packets.
     */
    void sendPendingPackets() {
        // packets must be sorted/filtered according to recipient for double-blind games
        // each player must receive the packets directed at them as well as any
        // undirected packets
        // in the order they were stored in pendingPackets
        reducePendingPackets();
        List<PendingPacket> packetsToSend = new ArrayList<>(pendingPackets);
        pendingPackets.clear();
        // Send each player what they should receive ...
        for (int playerId : game.getPlayersList().stream().map(Player::getId).toList()) {
            List<Packet> packets = packetsToSend.stream()
                  // ... including packets to PLAYER_NONE; these go to every player
                  .filter(p -> (p.recipient == Player.PLAYER_NONE) ||
                        (p.recipient == playerId))
                  .map(PendingPacket::packet)
                  .toList();
            if (!packets.isEmpty()) {
                // the redundant new ArrayList is necessary to prevent a xstream error
                super.send(playerId, new Packet(PacketCommand.MULTI_PACKET, new ArrayList<>(packets)));
            }
        }
    }

    private void reducePendingPackets() {
        // TODO remove redundant packets, maybe consolidate packets
    }

    void addPendingPacket(int recipient, Packet packet) {
        pendingPackets.add(new PendingPacket(recipient, packet));
    }

    void addPendingPacket(Packet packet) {
        pendingPackets.add(new PendingPacket(Player.PLAYER_NONE, packet));
    }

    @Override
    public SBFGame getGame() {
        return game;
    }

    @Override
    public void setGame(IGame g) {
        if (!(g instanceof SBFGame)) {
            logger.fatal("Attempted to set game to incorrect class.");
            return;
        }
        game = (SBFGame) g;
    }

    @Override
    public void resetGame() {
    }

    @Override
    public void disconnect(Player player) {
    }

    @Override
    public void removeAllEntitiesOwnedBy(Player player) {
    }

    @Override
    public void handleCfrPacket(Server.ReceivedPacket rp) {
    }

    @Override
    public void requestGameMaster(Player player) {
    }

    @Override
    public List<ServerCommand> getCommandList(Server server) {
        return List.of(new SBFDeployCommand(server, this), new SBFEngageCommand(server, this),
              new SBFFormationCommand(server, this), new SBFSpaceCommand(server, this), new SBFInitiativeCommand(server, this), new SBFAllocateCommand(server, this), new SBFCombatCommand(server, this), new SBFCommandDesignation(server, this),
              new SBFMovementCommand(server, this), new SBFTransportCommand(server, this), new SBFWithdrawCommand(server, this), new SBFAeroCommand(server, this), new SBFReconCommand(server, this), new SBFHiddenCommand(server, this));
    }

    @Override
    public void addReport(ReportEntry r) {
        SBFReportEntry entry = (SBFReportEntry) r;
        if (entry.getSubjectId() != null && (game.usesDoubleBlind() || SBFScenarioResolution.roster(game).stream()
              .anyMatch(f -> f.getId() == entry.getSubjectId() && f.concealsRoster()))) {
            var recipients = game.getPlayersList().stream().filter(p -> game.isVisible(p.getId(), entry.getSubjectId()))
                  .map(Player::getId).collect(Collectors.toSet());
            game.getGameReport().restrictTo(entry, recipients);
        }
        pendingReports.add(entry);
    }

    /** Record recipients while the formations and their contacts still exist. All named subjects must be visible. */
    void addReportFor(SBFReportEntry entry, SBFFormation... subjects) {
        if (game.usesDoubleBlind() || Arrays.stream(subjects).anyMatch(SBFFormation::concealsRoster)) {
            var recipients = game.getPlayersList().stream()
                  .filter(player -> Arrays.stream(subjects).allMatch(f -> (f.getOwnerId() == player.getId())
                        || game.isVisible(player.getId(), f.getId())))
                  .map(Player::getId).collect(Collectors.toSet());
            game.getGameReport().restrictTo(entry, recipients);
        }
        pendingReports.add(entry);
    }

    @Override
    public void calculatePlayerInitialCounts() {
    }

    @Override
    public void requestTeamChangeForPlayer(int teamID, Player player) {

    }

    /**
     * Creates a packet containing all entities, including wrecks, visible to the player in a blind game
     */
    Packet createGameStartUnitPacket(Player recipient) {
        var forces = game.getForces();
        if (game.usesDoubleBlind() || game.getActiveFormations().stream().anyMatch(SBFFormation::concealsRoster)) {
            var viewers = game.visibilityHelper().playersSharingVision(recipient.getId());
            forces = forces.visibleTo(owner -> viewers.contains(game.getPlayer(owner)), id -> game.isVisible(recipient.getId(), id));
        }
        return new Packet(PacketCommand.SENDING_ENTITIES,
              unitUpdateHelper.snapshotFor(recipient),
              getGame().getGraveyard().stream().filter(unit -> getGame().isVisible(recipient.getId(), unit.getId()))
                    .toList(),
              forces);
    }

    @Override
    public void sendCurrentInfo(int connId) {
        if (game.getPhase() == GamePhase.SBF_DETECTION_REPORT && game.getPlayer(connId) != null) {
            sendServerChat(connId, SBFReconCommand.HELP);
        }
        if (game.getPhase().isEnd() && game.takingAdjustmentOrders()) {
            sendServerChat(connId, SBFFormationCommand.HELP);
        }
        if (game.getPhase().isMovement() || game.getPhase().isEnd()) {
            new SBFEngagementControl(this).remind(connId);
        }
        if (game.getPhase().isFiring() && (game.getPlayer(connId) != null)) {
            var combat = new SBFCombatControl(this);
            combat.remind(game.getPlayer(connId));
            SBFArtilleryAirDefense.remind(this, connId);
        }
        if (game.getPhase().isFiringReport() && game.getDamageAllocations().stream()
              .anyMatch(hit -> (hit.chooserId() == connId) && (hit.unitIndex() < 0))) {
            sendServerChat(connId, "The firing report is waiting for your damage choices. Use /sbfallocate.");
        }
        send(connId, packetHelper.createGameSettingsPacket());

        Player player = getGame().getPlayer(connId);
        if (player != null) {
            if (getGame().getPhase().isLounge()) {
                // send(connId, createMapSettingsPacket());
                // send(createMapSizesPacket());

                // LOUNGE triggers a Game.reset() on the client!
                // Send Entities *after* the Lounge Phase Change
                send(connId, packetHelper.createPhaseChangePacket());
                send(connId, createGameStartUnitPacket(player));
            } else {
                send(connId, packetHelper.createCurrentRoundNumberPacket());
                send(connId, packetHelper.createBoardsPacket());
                send(connId, createAllReportsPacket(player));

                // Send entities *before* other phase changes.
                send(connId, createGameStartUnitPacket(player));
                if (getGame().getPhase().isFiring()) {
                    new SBFCombatControl(this).sendOrders(player);
                }
                //
                // setPlayerDone(player, getGame().getEntitiesOwnedBy(player) <= 0);
                send(connId, packetHelper.createPhaseChangePacket());
            }

            send(connId, SBFScenarioResolution.markerPacket(game));
            send(connId, packetHelper.createPlanetaryConditionsPacket());
            if (game.getPhase().isFiring()) {
                send(connId, createActionsPacket(player));
            }
            if (getGame().getPhase().usesTurns() && getGame().getTurn() != null) {
                send(connId, packetHelper.createTurnListPacket());
                send(connId, packetHelper.createTurnIndexPacket(Player.PLAYER_NONE));
            }

            // This method is not called through normal packet handling, so it must send
            // packets actively
            sendPendingPackets();
        }
    }

    @Override
    protected void endCurrentPhase() {
        if (game.getPhase().isEnd() && !game.engagementOrders().remainingEnds().isEmpty()) {
            return;
        }
        logger.info("Ending phase {}", game.getPhase());
        phaseEndManager.managePhase();
    }

    @Override
    protected void prepareForCurrentPhase() {
        logger.info("Preparing phase {}", game.getPhase());
        phasePreparationManager.managePhase();
    }

    @Override
    protected void executeCurrentPhase() {
        logger.info("Executing phase {}", game.getPhase());
        switch (game.getPhase()) {
            case EXCHANGE:
                resetPlayersDone();
                calculatePlayerInitialCounts();
                game.setupTeams();
                game.getPlanetaryConditions().determineWind();
                send(packetHelper.createPlanetaryConditionsPacket());
                SBFWaterWidths.roll(game);
                send(packetHelper.createBoardsPacket());
                game.setupDeployment();
                break;
            case MOVEMENT:
                addReport(new SBFReportHeader(2000));
                // intentional fall through
            case PREMOVEMENT:
            case SET_ARTILLERY_AUTO_HIT_HEXES:
            case DEPLOY_MINEFIELDS:
            case DEPLOYMENT:
            case PRE_FIRING:
            case FIRING:
            case PHYSICAL:
            case TARGETING:
            case OFFBOARD:
                changeToNextTurn(-1); // TODO what is the prev player good for??
                if (game.getOptions().booleanOption(OptionsConstants.BASE_PARANOID_AUTOSAVE)) {
                    autoSave();
                }
                break;
            default:
                break;
        }
    }

    /**
     * Called at the beginning of certain phases to make every player not ready.
     */
    private void resetPlayersDone() {
        // FIXME This is highly unclear why not in report but in victory
        if ((getGame().getPhase().isReport()) && (!getGame().getPhase().isVictory())) {
            return;
        }

        for (Player player : game.getPlayersList()) {
            setPlayerDone(player, false);
        }

        transmitAllPlayerDones();
    }

    private void setPlayerDone(Player player, boolean normalDone) {
        // FIXME This is highly specialized and very arcane!!
        if (getGame().getPhase().isReport() &&
              getGame().getOptions().booleanOption(OptionsConstants.BASE_GM_CONTROLS_DONE_REPORT_PHASE) &&
              getGame().getPlayersList().stream().anyMatch(Player::isGameMaster)) {
            player.setDone(!player.isGameMaster());
        } else {
            player.setDone(normalDone);
        }
    }

    /**
     * Called at the beginning of certain phases to make every active player not ready.
     */
    void resetActivePlayersDone() {
        for (Player player : game.getPlayersList()) {
            // FIXME This is highly specialized and very arcane!!
            setPlayerDone(player, getGame().getEntitiesOwnedBy(player) <= 0);
        }
        transmitAllPlayerDones();
    }

    /**
     * Rolls initiative for all teams.
     */
    void rollInitiative() {
        initiativeHelper.rollInitiative();
        transmitAllPlayerUpdates();
    }

    private Packet createAllReportsPacket(Player recipient) {
        return new Packet(PacketCommand.SENDING_REPORTS_ALL, game.getGameReport().createFilteredReport(recipient));
    }

    Packet createReportPacket(Player recipient) {
        return new Packet(PacketCommand.SENDING_REPORTS, game.getGameReport().visibleTo(recipient, pendingReports));
    }

    public void clearPendingReports() {
        pendingReports.clear();
    }

    List<SBFReportEntry> getPendingReports() {
        return pendingReports;
    }

    /** Chat commands run outside the packet handler. Publish only new damage rolls, then honor previous ready votes. */
    void finishDamageAllocation(int previousReportCount) {
        List<SBFReportEntry> newReports = List.copyOf(pendingReports.subList(previousReportCount, pendingReports.size()));
        if (!newReports.isEmpty()) {
            game.addReports(newReports);
            for (Player player : game.getPlayersList()) {
                send(player.getId(), new Packet(PacketCommand.SENDING_REPORTS,
                      game.getGameReport().visibleTo(player, newReports)));
            }
        }
        if (!SBFCombatDamage.hasPendingChoices(game)) {
            checkReady();
        }
        sendPendingPackets();
    }

    void checkSetupReady() {
        checkReady();
    }

    void addPendingReportsToGame() {
        if (game.getPhase().isEnd()) {
            game.getGameReport().prependSectionHeader(pendingReports, 3399);
        }
        game.addReports(pendingReports);
    }

    /**
     * Tries to change to the next turn. If there are no more turns, ends the current phase. If the player whose turn it
     * is next is not connected, we allow the other players to skip that player.
     */
    boolean resolveDropLandings() {
        List<Integer> owners = new SBFAirDrop(this).landAll();
        if (owners.isEmpty()) {
            return false;
        }
        var turns = new ArrayList<>(game.getTurnsList());
        owners.forEach(owner -> turns.add(new SBFFormationTurn(owner)));
        game.setTurns(turns);
        sendCurrentTurns();
        changeToNextTurn(Player.PLAYER_NONE);
        return true;
    }

    private void changeToNextTurn(int prevPlayerId) {
        game.engagementOrders().finishMovement();
        if (!game.hasMoreTurns()) {
            endCurrentPhase();
            return;
        }

        SBFTurn nextTurn = game.changeToNextTurn();
        boolean isValidTurn = nextTurn.isValid(game);
        while (game.hasMoreTurns() && !isValidTurn) {
            nextTurn = game.changeToNextTurn();
            isValidTurn = nextTurn.isValid(game);
        }

        if (!isValidTurn) {
            endCurrentPhase();
        } else {
            Optional<Player> player = game.getPlayerFor(nextTurn);
            if (game.getPhase() == GamePhase.PREMOVEMENT || game.getPhase() == GamePhase.PRE_FIRING) {
                sendServerChat(nextTurn.playerId(), SBFHiddenCommand.REVEAL);
            }
            if (game.getPhase().isDeployment()) {
                sendServerChat(nextTurn.playerId(), nextTurn instanceof SBFPlayerTurn ? SBFDeployCommand.ORDER
                      : SBFDeployCommand.PLACE);
            }
            if (prevPlayerId != Player.PLAYER_NONE) {
                // send(packetHelper.createTurnIndexPacket(prevPlayerId));
                addPendingPacket(packetHelper.createTurnIndexPacket(prevPlayerId));
            } else {
                addPendingPacket(packetHelper.createTurnIndexPacket(player.map(Player::getId)
                      .orElse(Player.PLAYER_NONE)));
                // send(packetHelper.createTurnIndexPacket(player.map(Player::getId).orElse(Player.PLAYER_NONE)));
            }

            if (player.isPresent() && player.get().isGhost()) {
                sendGhostSkipMessage(player.get());
                // } else if ((game.getFirstEntity() == null) && (player != null) &&
                // !minefieldPhase && !artyPhase) {
                // sendTurnErrorSkipMessage(player);
            }
        }
    }

    /**
     * Send the round report to all connected clients.
     */
    public void sendReport() {
        game.getPlayersList().forEach(player -> send(player.getId(), createReportPacket(player)));
    }

    @Override
    public SBFRuleOptions getOptions() {
        return game.getOptions();
    }

    /**
     * Receives an entity movement packet, and if valid, executes it and ends the current turn.
     */
    private void receiveMovement(Packet packet, int connId) throws InvalidPacketDataException {
        SBFMovePath movePath = packet.getSBFMovePath(0);
        boolean wrongPhase = !getGame().getPhase().isMovement();

        if (movePath == null) {
            correctRejectedTurn(connId, null, wrongPhase, false);
            return;
        }
        Optional<SBFFormation> formationInfo = game.getFormation(movePath.getEntityId());
        if (formationInfo.isEmpty()) {
            logger.error("Malformed packet {}", packet);
            correctRejectedTurn(connId, null, wrongPhase, false);
            return;
        }
        SBFFormation formation = formationInfo.get();
        SBFTurn turn = game.getTurn();
        if ((turn == null) || !turn.isValid(connId, formation, game)) {
            logger.error("It is not player {}'s turn! ", connId);
            correctRejectedTurn(connId, formation, wrongPhase, false);
            return;
        }
        if (!movementProcessor.processMovement(movePath, formation)) {
            correctRejectedTurn(connId, formation, wrongPhase, false);
        }
    }

    /**
     * Called when the current player has done his current turn and the turn counter needs to be advanced.
     */
    void endCurrentTurn(SBFFormation entityUsed) {
        final int playerId = (entityUsed == null) ? Player.PLAYER_NONE : entityUsed.getOwnerId();
        if (entityUsed != null && game.getPhase().isMovement() && game.adjustsFormations()
              && game.continueDetachmentMovement(entityUsed)) {
            game.engagementOrders().finishMovement();
            sendServerChat(playerId, "Move the remaining counters of this detachment before the next player acts.");
            addPendingPacket(packetHelper.createTurnIndexPacket(playerId));
            return;
        }
        if (entityUsed != null && game.getPhase().isMovement() && game.usesDoubleBlind()
              && game.visibilityHelper().isUncountedMovementGroup(entityUsed) && game.getTurn() != null
              && game.getTurn().playerId() == playerId && game.getTurn().isValid(game)) {
            game.engagementOrders().finishMovement();
            addPendingPacket(packetHelper.createTurnIndexPacket(playerId));
            return;
        }
        changeToNextTurn(playerId);
    }

    @Override
    protected void transmitAllPlayerDones() {
        getGame().getPlayersList()
              .forEach(player -> addPendingPacket(player.getId(), packetHelper.createPlayerDonePacket(player.getId())));
    }

    @Override
    public void send(Packet packet) {
        addPendingPacket(packet);
    }

    /**
     * Sends the given packet to the given connection (= player ID).
     *
     * @see Server#send(int, Packet)
     */
    @Override
    public void send(int connId, Packet p) {
        addPendingPacket(connId, p);
    }

    /**
     * Sends out the player object to all players. Private info of the given player is redacted before being sent to
     * other players.
     *
     * @param player The player whose information is to be shared
     *
     * @see #transmitAllPlayerUpdates() //TODO: wonder if pending packets can be extended to TW //TODO: might work
     *       easily by overriding send; must send CFR packets immediately
     */
    @Override
    protected void transmitPlayerUpdate(Player player) {
        int playerId = player.getId();
        for (Player player1 : game.getPlayersList()) {

            var destPlayer = player;

            if (playerId != player1.getId()) {
                // Sending the player's data to another player's
                // connection, need to redact any private data
                destPlayer = player.copy();
                destPlayer.redactPrivateData();
            }
            send(player1.getId(), new Packet(PacketCommand.PLAYER_UPDATE, playerId, destPlayer));
        }
    }

    /**
     * Info at {@link SBFUnitUpdateHelper#sendUnitUpdate(InGameObject)}
     */
    void sendUnitUpdate(InGameObject unit) {
        unitUpdateHelper.sendUnitUpdate(unit);
    }

    /**
     * Updates all units to all players, taking into account double-blind filtering.
     */
    void entityAllUpdate() {
        for (Player player : game.getPlayersList()) {
            send(player.getId(), createGameStartUnitPacket(player));
        }
    }

    /**
     * Corrects a Client after the server rejected its movement or attacks: its own formation as the server has it, the
     * actions it may see, and the phase and turn, so that the Client can act again. A formation of another player is
     * never sent, so a rejected request cannot reveal a hidden unit.
     */
    private void correctRejectedTurn(int connId, SBFFormation formation, boolean includePhase,
          boolean includeActions) {
        Player player = game.getPlayer(connId);
        if ((formation != null) && (player != null) && (formation.getOwnerId() == connId)) {
            unitUpdateHelper.sendUnitUpdate(player, formation);
        }
        if (includeActions && (player != null)) {
            send(connId, createActionsPacket(player));
        }
        if (includePhase) {
            sendPhaseAndTurnCorrection(connId);
        } else {
            sendTurnCorrection(connId);
        }
    }

    void receiveAttack(Packet packet, int connId) throws InvalidPacketDataException {
        var attacks = packet.getEntityActionList(1);
        int formationId = packet.getIntValue(0);
        Optional<SBFFormation> formationInfo = game.getFormation(formationId);
        boolean wrongPhase = !getGame().getPhase().isFiring();

        if (formationInfo.isEmpty() ||
              !attacks.stream().map(EntityAction::getEntityId).allMatch(id -> id == formationId)) {
            logger.error("Invalid formation ID or diverging attacker IDs");
            correctRejectedTurn(connId, formationInfo.orElse(null), wrongPhase, true);
            return;
        }

        for (EntityAction action : attacks) {
            if (!validateEntityAction(action, connId)) {
                correctRejectedTurn(connId, formationInfo.get(), wrongPhase, true);
                return;
            }
        }

        // is this the right phase?
        if (wrongPhase) {
            logger.error("Server got attack packet in wrong phase");
            correctRejectedTurn(connId, formationInfo.get(), true, true);
            return;
        }

        // looks like mostly everything's okay
        if (!attackProcessor.processAttacks(attacks, formationInfo.get())) {
            correctRejectedTurn(connId, formationInfo.get(), false, true);
        }
    }

    private boolean validateEntityAction(EntityAction action, int connId) {
        // TODO unify firing/movement validity
        Optional<SBFFormation> formationInfo = game.getFormation(action.getEntityId());
        if (formationInfo.isEmpty()) {
            logger.error("Incorrect formation ID {}", action.getEntityId());
            return false;
        }
        SBFTurn turn = game.getTurn();
        if ((turn == null) || !turn.isValid(connId, formationInfo.get(), game)) {
            logger.error("It is not player {}'s turn! ", connId);
            return false;
        }

        return true;
    }

    /**
     * Sends the game's pending actions to all Clients for them to replace any previous actions
     */
    void sendPendingActions() {
        game.getPlayersList().forEach(player -> send(player.getId(), createActionsPacket(player)));
    }

    /** One recipient's declarations for a live update or reconnect. Every contact named in the packet must be known. */
    Packet createActionsPacket(Player recipient) {
        List<EntityAction> visibleActions = game.getActionsVector().stream()
              .filter(action -> game.isVisible(recipient.getId(), action.getEntityId()))
              .filter(action -> {
                  if (!(action instanceof SBFAttackAction attack)) {
                      return true;
                  }
                  boolean ownOrder = game.getFormation(action.getEntityId())
                        .map(f -> f.getOwnerId() == recipient.getId()).orElse(false);
                  return attack.referencedIds().stream().allMatch(id -> game.getFormation(id).isEmpty()
                        || (ownOrder ? game.getVisibility(recipient, id).isLocated() : game.isVisible(recipient.getId(), id)));
              })
              .toList();
        return new Packet(PacketCommand.ACTIONS, new ArrayList<>(visibleActions));
    }
}
