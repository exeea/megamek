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

import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFIdentifiedUnitPlaceHolder;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Ground reconnaissance orders, IO:BF pp. 195–197. */
final class SBFReconCommand extends ServerCommand {
    static final String HELP = "Reconnaissance: /sbfrecon lists contacts; /sbfrecon <formation ID> <contact ID>"
          + " spends 1 MP to scan; /sbfrecon done ends your reconnaissance. During firing use /sbfrecon air <squadron ID> <contact ID>.";
    private final SBFGameManager manager;

    SBFReconCommand(Server server, SBFGameManager manager) {
        super(server, "sbfrecon", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int owner, String[] args) {
        SBFGame game = manager.getGame();
        if (game.getPhase().isFiring() && args.length == 4 && args[1].equalsIgnoreCase("air")) {
            try {
                var squadron = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                var target = game.getFormation(Integer.parseInt(args[3])).orElse(null);
                if (manager.detectionHelper.orderAerialScan(owner, squadron, target)) {
                    server.sendServerChat(owner, "Aerial scan ordered. It resolves if the Squadron finishes the turn without combat.");
                    return;
                }
            } catch (NumberFormatException ignored) {
                // An unknown or ineligible target receives the same response.
            }
            server.sendServerChat(owner, "Unavailable aerial scan. " + HELP);
            return;
        }
        if (game.getPhase() != GamePhase.SBF_DETECTION_REPORT || game.getPlayer(owner) == null) {
            server.sendServerChat(owner, "Ground reconnaissance is available during the detection report.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("done")) {
            manager.handlePacket(owner, new Packet(PacketCommand.PLAYER_READY, true));
        } else if (args.length == 3) {
            try {
                var observer = game.getFormation(Integer.parseInt(args[1])).orElse(null);
                var target = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                if (manager.detectionHelper.scan(owner, observer, target, () -> Compute.d6(2))) {
                    server.sendServerChat(owner, "Reconnaissance result: " + game.getVisibility(game.getPlayer(owner), target.getId()));
                    manager.sendPendingPackets();
                    return;
                }
            } catch (NumberFormatException ignored) {
                // A malformed order receives the same help as an ineligible one.
            }
            server.sendServerChat(owner, "Unavailable scan. " + HELP);
        } else {
            server.sendServerChat(owner, HELP);
            game.getActiveFormations().stream().filter(f -> game.areHostile(f, game.getPlayer(owner)))
                  .filter(f -> game.getVisibility(game.getPlayer(owner), f.getId()).isLocated())
                  .filter(f -> f.getPosition() != null)
                  .forEach(f -> {
                      String description = "Contact " + f.getId() + " at " + f.getPosition().coords().getBoardNum();
                      if (manager.unitUpdateHelper.representationFor(game.getPlayer(owner), f)
                            instanceof SBFIdentifiedUnitPlaceHolder contact && !contact.getChassis().isEmpty()) {
                          description += " (" + String.join(", ", contact.getChassis()) + ")";
                      }
                      server.sendServerChat(owner, description);
                  });
        }
    }
}
