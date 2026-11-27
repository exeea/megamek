/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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

package megamek.server.totalWarfare;

import java.util.EnumSet;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.units.Entity;
import megamek.server.GameManagerPacketHelper;

/**
 * Puts a Client back in step with the server after the server refused one of its requests. A Client changes its own
 * copy of a unit before it sends a setting change (a mode, the ammo, a sensor), and gives up its turn when it submits
 * one; when the server refuses, the Client would otherwise keep showing the refused setting or wait for a turn that
 * the server still expects from it. Only units the requesting connection owns are ever sent, so a refused request
 * cannot reveal another player's unit.
 */
record TWRequestResync(TWGameManager gameManager) {

    /** Setting changes the Client applies to its own copy of the unit before the server has accepted them. */
    private static final Set<PacketCommand> SETTING_CHANGES = EnumSet.of(PacketCommand.ENTITY_MODE_CHANGE,
          PacketCommand.ENTITY_SENSOR_CHANGE, PacketCommand.ENTITY_SINKS_CHANGE, PacketCommand.ENTITY_ACTIVATE_HIDDEN,
          PacketCommand.ENTITY_NOVA_NETWORK_CHANGE, PacketCommand.ENTITY_MOUNTED_FACING_CHANGE,
          PacketCommand.ENTITY_CALLED_SHOT_CHANGE, PacketCommand.ENTITY_SYSTEM_MODE_CHANGE,
          PacketCommand.ENTITY_AMMO_CHANGE);

    /** Turn submissions; a refused one is corrected where it is refused (see {@link #turnRefused}). */
    private static final Set<PacketCommand> TURN_SUBMISSIONS = EnumSet.of(PacketCommand.ENTITY_MOVE,
          PacketCommand.ENTITY_ATTACK, PacketCommand.ENTITY_PREPHASE, PacketCommand.ENTITY_DEPLOY,
          PacketCommand.ENTITY_DEPLOY_UNLOAD);

    /**
     * Called once a request has been handled. After a setting change, accepted or refused, the Client gets the unit as
     * the server now has it: an accepted change shows, a refused one is undone. A turn submission the server could not
     * even read gets its turn back.
     *
     * @param connId    the requesting connection
     * @param packet    the request
     * @param malformed true when the request's data could not be read
     */
    void requestHandled(int connId, Packet packet, boolean malformed) {
        if (SETTING_CHANGES.contains(packet.command())) {
            sendOwnUnit(connId, requestedUnit(packet));
        } else if (malformed && TURN_SUBMISSIONS.contains(packet.command())) {
            turnRefused(connId, requestedUnit(packet), false);
        }
    }

    /**
     * Corrects a Client whose turn submission was refused: its own unit as the server has it, the phase when the
     * submission was made in another phase, and the turn, with the Client as the previous player so that the displays
     * of simultaneous phases start its turn again.
     *
     * @param connId     the requesting connection
     * @param entity     the unit of the submission, if known; sent only when the connection owns it
     * @param wrongPhase true when the submission belongs to another phase than the game's
     */
    void turnRefused(int connId, @Nullable Entity entity, boolean wrongPhase) {
        sendOwnUnit(connId, entity);
        GameManagerPacketHelper packets = gameManager.getPacketHelper();
        if (wrongPhase) {
            gameManager.send(connId, packets.createPhaseChangePacket());
        }
        gameManager.send(connId, packets.createTurnListPacket());
        gameManager.send(connId, packets.createTurnIndexPacket(connId));
    }

    private void sendOwnUnit(int connId, @Nullable Entity entity) {
        if ((entity != null) && (entity.getOwnerId() == connId)) {
            gameManager.send(connId, gameManager.createEntityPacket(entity.getId(), null));
        }
    }

    private @Nullable Entity requestedUnit(Packet packet) {
        Object[] data = packet.data();
        return ((data.length > 0) && (data[0] instanceof Integer entityId)) ? gameManager.getGame().getEntity(entityId)
              : null;
    }
}
