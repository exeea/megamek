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

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirDefenseArtilleryAttack;
import megamek.common.actions.sbf.SBFArtilleryAttack;
import megamek.common.actions.sbf.SBFCounterBatteryAttack;
import megamek.common.actions.sbf.SBFDirectArtilleryAttack;
import megamek.common.actions.sbf.SBFIndirectArtilleryAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack.WeaponMode;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.strategicBattleSystems.SBFArtilleryAmmo;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Combat orders that may be given by the controlling player even during another player's declaration turn. */
final class SBFCombatCommand extends ServerCommand {
    private final SBFGameManager manager;

    SBFCombatCommand(Server server, SBFGameManager manager) {
        super(server, "sbfcombat", "/sbfcombat lists your pending decisions. /sbfcombat range <own ID> <opponent ID> "
              + "short|medium|long (also extreme in air); /sbfcombat sight <own ID> <opponent ID> open|block; "
              + "/sbfcombat fire <own ID> <Unit number> <contact ID>; /sbfcombat spot <spotter ID> <target ID>; /sbfcombat indirect <own ID> <Unit number> <target ID> "
              + "<spotter ID>; /sbfcombat torpedo <own ID> <Unit number> <target ID>. /sbfcombat artillery <own ID> <ART type> "
              + "direct|indirect|homing <board ID> <column> <row> <target ID|terrain> [spotter ID]. Use /sbfcombat paint <own ID> <Unit number> <target ID> for TAG. /sbfcombat counter <own ID> <ART type> <contact number>. /sbfcombat art-load <own ID> <Unit#> <ART type> [alternate ...] in setup; /sbfcombat flak|air-defense <own ID> <ART type> <target ID>; /sbfcombat aa-hit <Flight#>. Press Done or use /sbfcombat done <own ID> after declaring fire.");
        this.manager = manager;
    }

    @Override
    public void run(int connId, String[] args) {
        SBFCombatControl control = new SBFCombatControl(manager);
        if (args.length < 2) {
            server.sendServerChat(connId, getHelp());
            if (manager.getGame().getPhase().isFiring() && (manager.getGame().getPlayer(connId) != null)) {
                SBFCounterBattery.list(manager, connId);
                SBFArtilleryAirDefense.remind(manager, connId);
                control.listContacts(manager.getGame().getPlayer(connId));
                control.remind(manager.getGame().getPlayer(connId));
            }
            return;
        }
        try {
            if ((args.length == 3) && args[1].equalsIgnoreCase("aa-hit")
                  && SBFArtilleryAirDefense.choose(manager, connId, Integer.parseInt(args[2]) - 1)) {
                return;
            }
            if ((args.length >= 5) && args[1].equalsIgnoreCase("art-load")) {
                var game = manager.getGame();
                var formation = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                int unit = Integer.parseInt(args[3]) - 1;
                var rounds = EnumSet.noneOf(SBFArtilleryAmmo.class);
                for (int i = 5; i < args.length; i++) {
                    rounds.add(SBFArtilleryAmmo.valueOf(args[i].toUpperCase(Locale.ROOT).replace('-', '_')));
                }
                if ((game.getPhase().isLounge() || (game.getPhase().isDeployment() && game.getCurrentRound() <= 1))
                      && (formation != null) && formation.getOwnerId() == connId && unit >= 0 && unit < formation.getUnits().size()
                      && formation.getUnits().get(unit).loadArtillery(artilleryType(args[4]), rounds)) {
                    manager.sendUnitUpdate(formation);
                    manager.sendPendingPackets();
                    server.sendServerChat(connId, "Artillery loadout accepted.");
                    return;
                }
            }
            if ((args.length == 5) && (args[1].equalsIgnoreCase("flak") || args[1].equalsIgnoreCase("air-defense"))) {
                var ammo = args[1].equalsIgnoreCase("flak") ? SBFArtilleryAmmo.CLUSTER : SBFArtilleryAmmo.AIR_DEFENSE;
                var shot = new SBFAirDefenseArtilleryAttack(Integer.parseInt(args[2]), Integer.parseInt(args[4]),
                      artilleryType(args[3]), ammo);
                if (SBFArtillery.declare(manager, connId, shot)) {
                    server.sendServerChat(connId, "Anti-air artillery declared.");
                    return;
                }
            }
            if ((args.length == 3) && args[1].equalsIgnoreCase("done")) {
                var game = manager.getGame();
                var formation = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                if ((formation != null) && (game.getTurn() != null) && game.getTurn().isValid(connId, formation, game)
                      && new SBFAttackProcessor(manager).processAttacks(List.of(), formation)) {
                    return;
                }
            }
            if ((args.length == 5) && args[1].equalsIgnoreCase("counter")) {
                var shot = new SBFCounterBatteryAttack(Integer.parseInt(args[2]), artilleryType(args[3]), Integer.parseInt(args[4]));
                if (SBFArtillery.declare(manager, connId, shot)) {
                    server.sendServerChat(connId, "Counter-battery fire declared.");
                    return;
                }
            }
            if ((args.length == 4) && args[1].equalsIgnoreCase("spot")
                  && control.spot(connId, Integer.parseInt(args[2]), Integer.parseInt(args[3]))) {
                server.sendServerChat(connId, "Spotter designation accepted.");
                return;
            }
            if (args[1].equalsIgnoreCase("artillery") && (args.length == 9 || args.length == 10)) {
                int attacker = Integer.parseInt(args[2]);
                BattleForceSUA weapon = artilleryType(args[3]);
                var aim = BoardLocation.of(new Coords(Integer.parseInt(args[6]) - 1, Integer.parseInt(args[7]) - 1),
                      Integer.parseInt(args[5]));
                int target = args[8].equalsIgnoreCase("terrain") ? Player.PLAYER_NONE : Integer.parseInt(args[8]);
                Integer spotter = args.length == 10 ? Integer.valueOf(args[9]) : null;
                SBFArtilleryAttack shot = switch (args[4].toLowerCase(Locale.ROOT)) {
                    case "direct" -> new SBFDirectArtilleryAttack(attacker, target, weapon, aim, spotter);
                    case "indirect" -> new SBFIndirectArtilleryAttack(attacker, target, weapon, aim, spotter);
                    case "homing" -> spotter == null ? new SBFIndirectArtilleryAttack(attacker, target, weapon, aim, true) : null;
                    default -> null;
                };
                if (shot != null && SBFArtillery.declare(manager, connId, shot)) {
                    server.sendServerChat(connId, "Artillery declared. Complete the Formation's orders with Done.");
                    return;
                }
            }
            String order = args[1].toLowerCase(Locale.ROOT);
            WeaponMode weapon = switch (order) {
                case "fire" -> WeaponMode.STANDARD;
                case "paint" -> WeaponMode.TAG;
                case "torpedo" -> WeaponMode.TORPEDO;
                case "indirect" -> WeaponMode.INDIRECT;
                default -> null;
            };
            boolean indirect = weapon == WeaponMode.INDIRECT;
            if ((weapon != null) && (args.length == (indirect ? 6 : 5))) {
                var attack = new SBFStandardUnitAttack(Integer.parseInt(args[2]), Integer.parseInt(args[3]) - 1,
                      Integer.parseInt(args[4]), ASRange.LONG, weapon,
                      indirect ? Integer.parseInt(args[5]) : -1);
                if (new SBFAttackProcessor(manager).declareWeapon(connId, attack)) {
                    server.sendServerChat(connId, "Weapon declared. Complete the Formation's orders with Done.");
                    return;
                }
            } else if ((args.length == 5) && control.choose(connId, Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                  args[1].toLowerCase(Locale.ROOT), args[4].toLowerCase(Locale.ROOT))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Respond with the command syntax.
        }
        server.sendServerChat(connId, "Combat order refused. " + getHelp());
    }
    private static BattleForceSUA artilleryType(String input) {
        String name = input.toUpperCase(Locale.ROOT).replace("-", "");
        return BattleForceSUA.valueOf(name.startsWith("ART") ? name : "ART" + name);
    }
}
