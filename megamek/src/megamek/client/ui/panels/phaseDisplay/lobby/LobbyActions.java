/*
 * Copyright (C) 2021-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.panels.phaseDisplay.lobby;

import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyMekPopup.LMP_HULL_DOWN;
import static megamek.client.ui.panels.phaseDisplay.lobby.LobbyMekPopup.LMP_PRONE;

import java.util.Collection;
import java.util.StringTokenizer;

import megamek.client.Client;
import megamek.client.lobby.LobbyCommands;
import megamek.common.Player;
import megamek.common.force.Force;
import megamek.common.net.packets.InvalidPacketDataException;
import megamek.common.units.Entity;

/**
 * The Swing lobby's entry point to the entity and force changes made from the pop-up menu and elsewhere. The work is
 * done by {@link LobbyCommands}; this class translates the menus' action strings.
 */
public class LobbyActions {

    private final LobbyCommands commands;

    LobbyActions(LobbyCommands commands) {
        this.commands = commands;
    }

    void applyDeployment(Collection<Entity> entities, int newRound) {
        commands.applyDeployment(entities, newRound);
    }

    void applyHeat(Collection<Entity> entities, int heat) {
        commands.applyHeat(entities, heat);
    }

    void applyHidden(Collection<Entity> entities, boolean newHidden) {
        commands.applyHidden(entities, newHidden);
    }

    void applyScanTarget(Collection<Entity> entities, boolean wanted) {
        commands.applyScanTarget(entities, wanted);
    }

    /** Sets deploy prone, hull down or standing, as given by the menu's info string. */
    void applyProne(Collection<Entity> entities, String info) {
        LobbyCommands.Stance stance;
        if (info.equals(LMP_PRONE)) {
            stance = LobbyCommands.Stance.PRONE;
        } else if (info.equals(LMP_HULL_DOWN)) {
            stance = LobbyCommands.Stance.HULL_DOWN;
        } else {
            stance = LobbyCommands.Stance.STANDING;
        }
        commands.applyProne(entities, stance);
    }

    void forceAttach(int forceId, int newParentId) {
        commands.forceAttach(forceId, newParentId);
    }

    void forcePromote(Collection<Integer> forceIds) {
        commands.forcePromote(forceIds);
    }

    void configureDamage(Collection<Entity> entities) {
        commands.configureDamage(entities);
    }

    /** @see LobbyCommands#canEditDamage(Client, Entity) */
    static boolean canEditDamage(Client client, Entity entity) {
        return LobbyCommands.canEditDamage(client, entity);
    }

    void forceMove(Collection<Force> forceList, Collection<Entity> entityList, boolean up) {
        commands.forceMove(forceList, entityList, up);
    }

    public void individualCamo(Collection<Entity> entities) {
        commands.individualCamo(entities);
    }

    public void customizeMeks(Collection<Entity> entities) {
        commands.customizeMeks(entities);
    }

    public void customizeMek(Entity entity) {
        commands.customizeMek(entity);
    }

    void setRandomSkills(Collection<Entity> entities) {
        commands.setRandomSkills(entities);
    }

    void setRandomNames(Collection<Entity> entities) {
        commands.setRandomNames(entities);
    }

    void setRandomCallSigns(Collection<Entity> entities) {
        commands.setRandomCallSigns(entities);
    }

    void forceCreateEmpty() {
        commands.forceCreateEmpty();
    }

    void forceDeleteEmpty(int forceId) {
        commands.forceDeleteEmpty(forceId);
    }

    void forceCreateFrom(Collection<Entity> entities) {
        commands.forceCreateFrom(entities);
    }

    void forceCreateSub(int parentId) {
        commands.forceCreateSub(parentId);
    }

    void toggleBurstMg(Collection<Entity> entities, boolean burstOn) {
        commands.toggleBurstMg(entities, burstOn);
    }

    void setPriorityTarget(String botName, Collection<Entity> entities) {
        commands.setPriorityTarget(botName, entities);
    }

    void toggleHotLoad(Collection<Entity> entities, boolean hotLoadOn) {
        commands.toggleHotLoad(entities, hotLoadOn);
    }

    void setVRTMode(Collection<Entity> entities, boolean longMode) {
        commands.setVRTMode(entities, longMode);
    }

    void toggleEI(Collection<Entity> entities, boolean enableEI) {
        commands.toggleEI(entities, enableEI);
    }

    /**
     * @param info the loader id, the bay number and optionally whether to load onto the rear clamp, separated by
     *             colons
     */
    public void load(Collection<Entity> selEntities, String info) {
        StringTokenizer stLoad = new StringTokenizer(info, ":");
        int loaderId = Integer.parseInt(stLoad.nextToken());
        int bayNumber = Integer.parseInt(stLoad.nextToken());
        boolean loadRear = false;
        if (stLoad.hasMoreTokens()) {
            loadRear = Boolean.parseBoolean(stLoad.nextToken());
        }
        commands.load(selEntities, loaderId, bayNumber, loadRear);
    }

    /**
     * @param trailer Entity to be towed
     * @param info    the tractor id
     */
    public void tow(Entity trailer, String info) {
        StringTokenizer sTow = new StringTokenizer(info, ":");
        commands.tow(trailer, Integer.parseInt(sTow.nextToken()));
    }

    public void connectTrain(Collection<Entity> entities) {
        commands.connectTrain(entities);
    }

    void forceRename(int forceId) {
        commands.forceRename(forceId);
    }

    void delete(Collection<Force> foDelete, Collection<Entity> enDelete, boolean confirm) {
        commands.delete(foDelete, enDelete, confirm);
    }

    void forceRemoveEntity(Collection<Entity> entities) {
        commands.forceRemoveEntity(entities);
    }

    void swapPilots(Collection<Entity> entities, int targetId) {
        commands.swapPilots(entities, targetId);
    }

    void c3DisconnectFromNetwork(Collection<Entity> entities) {
        commands.c3DisconnectFromNetwork(entities);
    }

    void c3SetCompanyMaster(Collection<Entity> entities) {
        commands.c3SetCompanyMaster(entities);
    }

    void c3SetLanceMaster(Collection<Entity> entities) {
        commands.c3SetLanceMaster(entities);
    }

    void c3JoinNh(Collection<Entity> entities, int masterID, boolean disconnectFirst) {
        commands.c3JoinNh(entities, masterID, disconnectFirst);
    }

    void c3Connect(Collection<Entity> entities, int masterID, boolean disconnectFirst) {
        commands.c3Connect(entities, masterID, disconnectFirst);
    }

    void changeOwner(Collection<Entity> entities, Collection<Force> forceList, int newOwnerId)
          throws InvalidPacketDataException {
        commands.changeOwner(entities, forceList, newOwnerId);
    }

    void changeTeam(Collection<Player> players, int team) {
        commands.changeTeam(players, team);
    }

    void forceAddEntity(Collection<Entity> entities, int forceId) {
        commands.forceAddEntity(entities, forceId);
    }

    void forceAssignOnly(Collection<Force> forceList, int newOwnerId) {
        commands.forceAssignOnly(forceList, newOwnerId);
    }

    void forceAssignFull(Collection<Force> forceList, int newOwnerId) {
        commands.forceAssignFull(forceList, newOwnerId);
    }

    void unloadFromBay(Collection<Entity> entities, int bayId) {
        commands.unloadFromBay(entities, bayId);
    }

    void createSquadron(Collection<Entity> entities) {
        commands.createSquadron(entities);
    }

    void showSbfView(Collection<Force> fo) {
        commands.showSbfView(fo);
    }

    void showAlphaStrikeView(Collection<Entity> en) {
        commands.showAlphaStrikeView(en);
    }

    void sendUpdates(Collection<Entity> entities) {
        commands.sendUpdates(entities);
    }

    boolean isEditable(Entity entity) {
        return commands.isEditable(entity);
    }

    boolean isNotEditable(Entity entity) {
        return commands.isNotEditable(entity);
    }

    boolean isEditable(Collection<Entity> entities) {
        return commands.isEditable(entities);
    }

    boolean canSeeAll(Collection<Entity> entities) {
        return commands.canSeeAll(entities);
    }

    boolean isSelfOrLocalBot(Player player) {
        return commands.isSelfOrLocalBot(player);
    }

    boolean isLocalEnemy(Entity entity) {
        return commands.isLocalEnemy(entity);
    }

    boolean isEditable(Force force) {
        return commands.isEditable(force);
    }

    boolean areForcesEditable(Collection<Force> forces) {
        return commands.areForcesEditable(forces);
    }
}
