/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;

/**
 * What the lobby's ready bar shows besides the ready count: each side's Battle Value, and the warnings to heed before
 * the local player declares themselves ready. The blocking warnings are exactly the {@link ReadyCheck} blockers, so
 * Ready refuses what the Swing lobby refuses; the others only point at what the Swing lobby marks on its cards and map.
 *
 * @param sides    the sides that field forces: the local player's first, then the other teams in team order, then
 *                 each other player on no team
 * @param warnings the warnings, the blocking ones first
 */
public record LobbyReadiness(List<Side> sides, List<Warning> warnings) {

    /** Nothing to show, as before the client has its game. */
    public static final LobbyReadiness EMPTY = new LobbyReadiness(List.of(), List.of());

    /** The most names a warning's details list before folding the rest into a count. */
    static final int MAX_NAMES = 6;

    public LobbyReadiness {
        sides = List.copyOf(sides);
        warnings = List.copyOf(warnings);
    }

    /**
     * One side of the battle: a team, or a player on no team.
     *
     * @param name     the team's or the player's name
     * @param relation {@link LobbySnapshot.Relation#OWN} for the local player's side, {@link LobbySnapshot.Relation#ENEMY}
     *                 for another team, {@link LobbySnapshot.Relation#NONE} for another player on no team
     * @param hidden   whether a real blind drop hides the side's Battle Value from the local player
     * @param bv       the side's Battle Value, 0 when hidden
     */
    public record Side(String name, LobbySnapshot.Relation relation, boolean hidden, int bv) {
        public Side {
            Objects.requireNonNull(name);
            Objects.requireNonNull(relation);
        }

        /** @return whether this is the local player's side */
        public boolean friendly() {
            return relation == LobbySnapshot.Relation.OWN;
        }
    }

    /** Where a warning's cause is shown. */
    public enum Target {
        /** A player's card, in the players column. */
        PLAYER,
        /** A unit's card, in the forces column. */
        UNIT,
        /** The map's setup, in the battlefield column. */
        MAP
    }

    /** What a warning is about, whether it keeps the local player from being ready, and where its cause is shown. */
    public enum Kind {
        /** Double blind with exclusive deployment, and deployment areas overlap; {@link ReadyCheck} refuses. */
        OVERLAPPING_DEPLOYMENT(true, Target.PLAYER),
        /** Commander killed victory, and local forces have no commander; {@link ReadyCheck} refuses. */
        NO_COMMANDER(true, Target.PLAYER),
        /** The local player or one of their bots fields no units. */
        NO_UNITS(false, Target.PLAYER),
        /** Local units with a problem the cards mark red: an invalid design, doomed by the map, or shut down. */
        UNIT_PROBLEMS(false, Target.UNIT),
        /** Local units the cards mark yellow, such as an unlinked C3 unit or an empty squadron. */
        UNITS_NEED_SETUP(false, Target.UNIT),
        /** Some of the map's board slots have no board. */
        MAP_INCOMPLETE(false, Target.MAP),
        /** Boards of odd width are tiled side by side, which the Swing lobby warns of. */
        MAP_NOT_TILEABLE(false, Target.MAP);

        private final boolean blocking;
        private final Target target;

        Kind(boolean blocking, Target target) {
            this.blocking = blocking;
            this.target = target;
        }

        /** @return whether this keeps the local player from being ready */
        public boolean blocking() {
            return blocking;
        }

        /** @return where the cause is shown */
        public Target target() {
            return target;
        }
    }

    /**
     * One warning for the ready bar.
     *
     * @param kind    what it is about
     * @param text    the chip's short text
     * @param detail  the longer explanation, naming who or what it concerns
     * @param players the players it concerns, the one to show first
     * @param units   the units it concerns, the one to show first
     */
    public record Warning(Kind kind, String text, String detail, List<Integer> players, List<Integer> units) {
        public Warning {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(text);
            Objects.requireNonNull(detail);
            players = List.copyOf(players);
            units = List.copyOf(units);
        }

        /** @return whether this keeps the local player from being ready */
        public boolean blocking() {
            return kind.blocking();
        }
    }

    /** @return whether some warning keeps the local player from being ready */
    public boolean blocked() {
        return warnings.stream().anyMatch(Warning::blocking);
    }

    /**
     * Reads the sides and the warnings from the game. Call it on the thread that changes the game.
     *
     * @param local     the local player, or null before the server assigned one
     * @param localBots the players of the local bots
     * @param forces    the forces as the lobby shows them, for the local units' problems
     * @param map       the map's settings
     */
    public static LobbyReadiness of(Game game, Player local, Collection<Player> localBots, LobbyForces forces,
          MapSettings map) {
        if (local == null) {
            return EMPTY;
        }
        return new LobbyReadiness(sides(game, local), warnings(game, local, localBots, forces, map));
    }

    /**
     * The sides and their Battle Value as the Swing lobby's team overview shows them: only a real blind drop hides
     * another side's Battle Value, and never from the Game Master; observers field nothing.
     */
    static List<Side> sides(Game game, Player local) {
        int localTeam = Math.max(Player.TEAM_NONE, local.getTeam());
        boolean blind = game.getOptions().booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP)
              && !local.isGameMaster();
        Map<Integer, int[]> teamBv = new TreeMap<>();
        List<Side> loners = new ArrayList<>();
        Side ownLoner = null;
        for (Player player : game.getPlayersList()) {
            if (player.isObserver()) {
                continue;
            }
            int team = Math.max(Player.TEAM_NONE, player.getTeam());
            if (team == Player.TEAM_NONE) {
                boolean self = player.getId() == local.getId();
                boolean hidden = blind && !self;
                Side side = new Side(player.getName(), self ? LobbySnapshot.Relation.OWN : LobbySnapshot.Relation.NONE,
                      hidden, hidden ? 0 : player.getBV());
                if (self) {
                    ownLoner = side;
                } else {
                    loners.add(side);
                }
            } else {
                teamBv.computeIfAbsent(team, key -> new int[1])[0] += player.getBV();
            }
        }
        List<Side> sides = new ArrayList<>();
        if (ownLoner != null) {
            sides.add(ownLoner);
        }
        List<Side> others = new ArrayList<>();
        teamBv.forEach((team, bv) -> {
            boolean own = team == localTeam;
            boolean hidden = blind && !own;
            Side side = new Side(LobbySnapshot.teamName(team),
                  own ? LobbySnapshot.Relation.OWN : LobbySnapshot.Relation.ENEMY, hidden, hidden ? 0 : bv[0]);
            (own ? sides : others).add(side);
        });
        sides.addAll(others);
        sides.addAll(loners);
        return sides;
    }

    /** The warnings for the local player and their bots, the blocking ones first. */
    static List<Warning> warnings(Game game, Player local, Collection<Player> localBots, LobbyForces forces,
          MapSettings map) {
        List<Warning> warnings = new ArrayList<>();
        List<Player> overlapping = ReadyCheck.overlapping(game);
        if (!overlapping.isEmpty()) {
            warnings.add(playerWarning(Kind.OVERLAPPING_DEPLOYMENT, "zones", overlapping));
        }
        List<Player> leaderless = ReadyCheck.withoutCommander(game, local, localBots);
        if (!leaderless.isEmpty()) {
            warnings.add(playerWarning(Kind.NO_COMMANDER, "commander", leaderless));
        }

        List<Player> localPlayers = new ArrayList<>();
        localPlayers.add(local);
        localPlayers.addAll(localBots);
        List<Player> empty = localPlayers.stream().filter(player -> !player.isObserver() && !player.isGameMaster()
              && game.getPlayerEntities(player, false).isEmpty()).toList();
        if (!empty.isEmpty()) {
            warnings.add(playerWarning(Kind.NO_UNITS, "noUnits", empty));
        }

        Set<Integer> localIds = new HashSet<>();
        localPlayers.forEach(player -> localIds.add(player.getId()));
        Map<LobbyUnit, String> problems = new LinkedHashMap<>();
        Map<LobbyUnit, String> setup = new LinkedHashMap<>();
        for (LobbyUnit unit : forces.units()) {
            if (!localIds.contains(unit.ownerId()) || unit.hidden()) {
                continue;
            }
            List<String> danger = unit.tags().stream().filter(tag -> tag.tone() == LobbyUnit.Tone.DANGER)
                  .map(LobbyUnit.Tag::text).toList();
            if (!danger.isEmpty()) {
                problems.put(unit, String.join(", ", danger));
            } else if (unit.nameTone() == LobbyUnit.Tone.WARNING) {
                List<String> caution = unit.tags().stream().filter(tag -> tag.tone() == LobbyUnit.Tone.WARNING)
                      .map(LobbyUnit.Tag::text).toList();
                setup.put(unit, String.join(", ", caution));
            }
        }
        if (!problems.isEmpty()) {
            warnings.add(unitWarning(Kind.UNIT_PROBLEMS, "unitProblems", problems));
        }
        if (!setup.isEmpty()) {
            warnings.add(unitWarning(Kind.UNITS_NEED_SETUP, "unitSetup", setup));
        }

        if (map != null) {
            if (map.getBoardsSelectedVector().stream().anyMatch(Objects::isNull)) {
                warnings.add(new Warning(Kind.MAP_INCOMPLETE, Messages.getString("Lobby.warning.mapIncomplete"),
                      Messages.getString("Lobby.warning.mapIncomplete.tooltip"), List.of(), List.of()));
            }
            if (map.getBoardWidth() % 2 != 0 && map.getMapWidth() > 1) {
                warnings.add(new Warning(Kind.MAP_NOT_TILEABLE, Messages.getString("Lobby.warning.oddBoards"),
                      Messages.getString("Lobby.warning.oddBoards.tooltip"), List.of(), List.of()));
            }
        }
        return warnings;
    }

    private static Warning playerWarning(Kind kind, String key, List<Player> players) {
        List<String> names = players.stream().map(Player::getName).toList();
        return new Warning(kind, shortText(key, names), Messages.getString("Lobby.warning." + key + ".tooltip",
              names(names)), players.stream().map(Player::getId).toList(), List.of());
    }

    private static Warning unitWarning(Kind kind, String key, Map<LobbyUnit, String> units) {
        List<String> names = units.keySet().stream().map(LobbyUnit::name).toList();
        List<String> lines = new ArrayList<>();
        units.forEach((unit, why) -> lines.add(why.isEmpty() ? unit.name() : unit.name() + " (" + why + ")"));
        return new Warning(kind, shortText(key, names), Messages.getString("Lobby.warning." + key + ".tooltip",
              names(lines)), List.of(), units.keySet().stream().map(LobbyUnit::id).toList());
    }

    /** The chip's text: the one name, or how many there are. */
    private static String shortText(String key, List<String> names) {
        return names.size() == 1 ? Messages.getString("Lobby.warning." + key, names.get(0))
              : Messages.getString("Lobby.warning." + key + ".many", names.size());
    }

    /** {@code names} joined, the ones past {@link #MAX_NAMES} folded into a count. */
    public static String names(List<String> names) {
        if (names.size() <= MAX_NAMES) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_NAMES)) + " "
              + Messages.getString("Lobby.warning.more", names.size() - MAX_NAMES);
    }
}
