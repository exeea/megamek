/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import megamek.MMConstants;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.game.Game;
import megamek.common.interfaces.IStartingPositions;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;

/**
 * What the lobby screen shows at one moment, read from the live game on the Swing thread and handed to a view that
 * must not read the game itself, such as the libGDX lobby on its own thread.
 *
 * @param localPlayerId       the local player's id, or {@link Player#PLAYER_NONE} before the server assigned one
 * @param localGameMaster     whether the local player is the Game Master
 * @param localReady          whether the local player has declared themselves ready (done)
 * @param gameMasterAvailable whether the local player may take or give up the Game Master role: the game allows one
 *                            and nobody else holds it
 * @param teams               the players by team: the local player's team first, then the others in team order, and
 *                            the players without a team last
 * @param chat                the chat's recent lines, the newest last
 * @param customZones         the map's custom deployment zones, by their zone number
 * @param mapSummary          one line on the map: its boards and their size
 * @param server              where the game runs: a local game's port, or the server's address and port
 * @param forces              the units and formations the local player may see
 * @param battlefield         the map, conditions, rules, victory and auto-resolve as the battlefield column sums them up
 */
public record LobbySnapshot(int localPlayerId, boolean localGameMaster, boolean localReady,
      boolean gameMasterAvailable, List<TeamGroup> teams, List<ChatLine> chat, List<Integer> customZones,
      String mapSummary, String server, LobbyForces forces, LobbyBattlefield battlefield) {

    /** The most chat lines a snapshot carries. */
    public static final int CHAT_LINES = 200;

    /** How many teams a player may choose from, "No team" included; team numbers run from 0 to one less. */
    public static final int TEAM_COUNT = Player.TEAM_NAMES.length;

    /** An empty lobby, as shown before the client has its game. */
    public static final LobbySnapshot EMPTY = new LobbySnapshot(Player.PLAYER_NONE, false, false, false, List.of(),
          List.of(), List.of(), "", "", LobbyForces.EMPTY, LobbyBattlefield.EMPTY);

    public LobbySnapshot {
        teams = List.copyOf(teams);
        chat = List.copyOf(chat);
        customZones = List.copyOf(customZones);
        Objects.requireNonNull(mapSummary);
        Objects.requireNonNull(server);
        Objects.requireNonNull(forces);
        Objects.requireNonNull(battlefield);
    }

    /** How a player or team stands to the local player. */
    public enum Relation {
        /** The local player, or their team. */
        OWN,
        /** A teammate of the local player. */
        ALLY,
        /** An opponent. */
        ENEMY,
        /** A player on no team, or the players on no team as a group. */
        NONE
    }

    /**
     * One team and its players.
     *
     * @param id   the team's number, {@link Player#TEAM_NONE} for the players on no team
     * @param name the team's name as the lobby shows it
     */
    public record TeamGroup(int id, String name, List<PlayerRow> players) {
        public TeamGroup {
            players = List.copyOf(players);
        }

        /** @return whether a blind drop hides any of the team's players' forces from the local player */
        public boolean hidden() {
            return players.stream().anyMatch(PlayerRow::hidden);
        }

        /** @return the Battle Value of the team's players whose forces are not hidden */
        public int bv() {
            return players.stream().mapToInt(PlayerRow::bv).sum();
        }

        /** @return the tonnage of the team's players whose forces are not hidden */
        public int tons() {
            return players.stream().mapToInt(PlayerRow::tons).sum();
        }

        /** @return the number of units of the team's players whose forces are not hidden */
        public int units() {
            return players.stream().mapToInt(PlayerRow::units).sum();
        }

        /** @return whether the local player is on this team */
        public boolean ownTeam() {
            return id > Player.TEAM_NONE && players.stream().anyMatch(PlayerRow::self);
        }

        /** @return how the team stands to the local player: their own, the players on no team, or an enemy team */
        public Relation relation() {
            if (id <= Player.TEAM_NONE) {
                return Relation.NONE;
            }
            return ownTeam() ? Relation.OWN : Relation.ENEMY;
        }
    }

    /**
     * One player as the lobby shows them.
     *
     * @param self       whether this is the local player
     * @param local      whether the player runs on this machine: the local player or one of its bots; only these
     *                   may be configured from here
     * @param ready      whether the player has declared themselves ready (done)
     * @param ghost      whether the player has lost their connection, so that a bot may take their place
     * @param hidden     whether the blind drop rules hide the player's forces from the local player; their units, BV
     *                   and tonnage are then 0
     * @param units      how many units the player has, fighters in a squadron counted once
     * @param bv         the player's total Battle Value
     * @param tons       the player's units' total weight, rounded
     * @param start      where the player deploys, as the Swing lobby words it ("Blind" when hidden), or empty
     * @param startValid false when the player's deployment area overlaps another's where the rules forbid it
     * @param colour     the player's colour as 0xRRGGBB
     */
    public record PlayerRow(int id, String name, int team, boolean bot, boolean self, boolean local,
          boolean gameMaster, boolean observer, boolean ready, boolean ghost, boolean hidden, int units, int bv,
          int tons, String start, boolean startValid, int colour) {

        public PlayerRow {
            Objects.requireNonNull(start);
        }

        /** @return whether this is a bot that runs on this machine, which the local player may set up or remove */
        public boolean localBot() {
            return local && !self;
        }
    }

    /**
     * One side of the battle as the top bar sums it up: a team, or a player on no team.
     *
     * @param name     the team's or the player's name
     * @param friendly whether it is the local player's side
     * @param hidden   whether a blind drop hides any of its players' forces from the local player
     * @param bv       the Battle Value of the side's players whose forces are not hidden
     */
    public record Side(String name, boolean friendly, boolean hidden, int bv) { }

    /**
     * @return the sides that field forces: the local player's first, then the other teams in team order, then each
     *       other player on no team; observers field none
     */
    public List<Side> sides() {
        int localTeam = Player.TEAM_NONE;
        for (PlayerRow player : players()) {
            if (player.self()) {
                localTeam = player.team();
            }
        }
        List<Side> own = new ArrayList<>();
        List<Side> teamSides = new ArrayList<>();
        List<Side> loners = new ArrayList<>();
        for (TeamGroup team : teams) {
            if (team.id() <= Player.TEAM_NONE) {
                for (PlayerRow player : team.players()) {
                    if (!player.observer()) {
                        Side side = new Side(player.name(), player.self(), player.hidden(), player.bv());
                        (player.self() ? own : loners).add(side);
                    }
                }
                continue;
            }
            boolean fields = false;
            boolean hidden = false;
            int bv = 0;
            for (PlayerRow player : team.players()) {
                if (!player.observer()) {
                    fields = true;
                    hidden |= player.hidden();
                    bv += player.bv();
                }
            }
            if (fields) {
                boolean friendly = team.id() == localTeam;
                (friendly ? own : teamSides).add(new Side(team.name(), friendly, hidden, bv));
            }
        }
        own.addAll(teamSides);
        own.addAll(loners);
        return own;
    }

    /** @return how many players are ready */
    public int readyCount() {
        return (int) players().stream().filter(PlayerRow::ready).count();
    }

    /** @return how many players are in the lobby */
    public int playerCount() {
        return players().size();
    }

    /** @return every player, team by team */
    public List<PlayerRow> players() {
        return teams.stream().flatMap(team -> team.players().stream()).toList();
    }

    /** @return the player with the id {@code id}, or null */
    public PlayerRow player(int id) {
        return players().stream().filter(player -> player.id() == id).findFirst().orElse(null);
    }

    /** @return the local player's team, or {@link Player#TEAM_NONE} when they are on none or not yet known */
    public int localTeam() {
        return players().stream().filter(PlayerRow::self).mapToInt(PlayerRow::team).findFirst()
              .orElse(Player.TEAM_NONE);
    }

    /** @return the lowest team number nobody is on, for a new opponent, or {@link Player#TEAM_NONE} when all are taken */
    public int freeTeam() {
        Set<Integer> taken = new HashSet<>();
        players().forEach(player -> taken.add(player.team()));
        for (int team = Player.TEAM_NONE + 1; team < TEAM_COUNT; team++) {
            if (!taken.contains(team)) {
                return team;
            }
        }
        return Player.TEAM_NONE;
    }

    /**
     * Reads the lobby from {@code session}'s game. Call it on the thread that changes the game (the Swing thread).
     *
     * @param chat the chat lines to show; only the last {@link #CHAT_LINES} are kept
     */
    public static LobbySnapshot of(LobbySession session, List<String> chat) {
        return of(session, chat, List.of());
    }

    /**
     * Reads the lobby from {@code session}'s game. Call it on the thread that changes the game (the Swing thread).
     *
     * @param chat        the chat lines to show; only the last {@link #CHAT_LINES} are kept
     * @param customZones the map's custom deployment zones, which take the map's boards to know
     */
    public static LobbySnapshot of(LobbySession session, List<String> chat, Collection<Integer> customZones) {
        return of(session, chat, customZones, LobbyForces.Order.NONE);
    }

    /**
     * Reads the lobby from {@code session}'s game. Call it on the thread that changes the game (the Swing thread).
     *
     * @param chat        the chat lines to show; only the last {@link #CHAT_LINES} are kept
     * @param customZones the map's custom deployment zones, which take the map's boards to know
     * @param order       the order the forces list the units in, and the orders to choose from
     */
    public static LobbySnapshot of(LobbySession session, List<String> chat, Collection<Integer> customZones,
          LobbyForces.Order order) {
        return of(session, chat, customZones, order, LobbyBattlefield.AutoResolve.DEFAULT);
    }

    /**
     * Reads the lobby from {@code session}'s game. Call it on the thread that changes the game (the Swing thread).
     *
     * @param chat        the chat lines to show; only the last {@link #CHAT_LINES} are kept
     * @param customZones the map's custom deployment zones, which take the map's boards to know
     * @param order       the order the forces list the units in, and the orders to choose from
     * @param autoResolve the Swing lobby's auto-resolve settings
     */
    public static LobbySnapshot of(LobbySession session, List<String> chat, Collection<Integer> customZones,
          LobbyForces.Order order, LobbyBattlefield.AutoResolve autoResolve) {
        Client client = session.client();
        Game game = session.game();
        Player local = session.localPlayer();
        Set<Integer> localIds = new HashSet<>();
        if (local != null) {
            localIds.add(local.getId());
        }
        for (AbstractClient bot : session.localBots().values()) {
            Player botPlayer = bot.getLocalPlayer();
            if (botPlayer != null) {
                localIds.add(botPlayer.getId());
            }
        }
        Map<Integer, List<PlayerRow>> byTeam = new TreeMap<>();
        for (Player player : game.getPlayersList()) {
            boolean hidden = LobbyRules.hidesForces(game, local, player);
            int units = 0;
            int bv = 0;
            double tons = 0;
            if (!hidden) {
                for (Entity entity : game.getPlayerEntities(player, false)) {
                    if (!(entity instanceof FighterSquadron)) {
                        units++;
                        tons += entity.getWeight();
                    }
                }
                bv = player.getBV();
            }
            int team = Math.max(Player.TEAM_NONE, player.getTeam());
            boolean self = local != null && player.getId() == local.getId();
            byTeam.computeIfAbsent(team, key -> new ArrayList<>()).add(new PlayerRow(player.getId(),
                  player.getName(), team, player.isBot(), self, localIds.contains(player.getId()),
                  player.isGameMaster(), player.isObserver(), player.isDone(), player.isGhost(), hidden, units, bv,
                  (int) Math.round(tons), startText(game, player, hidden),
                  hidden || LobbyRules.isValidStartPos(game, player), colour(player)));
        }
        int localTeam = local == null ? Player.TEAM_NONE : Math.max(Player.TEAM_NONE, local.getTeam());
        List<TeamGroup> teams = new ArrayList<>();
        byTeam.forEach((team, players) -> {
            players.sort(Comparator.comparing(PlayerRow::self).reversed().thenComparingInt(PlayerRow::id));
            teams.add(new TeamGroup(team, teamName(team), players));
        });
        teams.sort(Comparator.comparingInt((TeamGroup team) -> teamOrder(team.id(), localTeam)));
        List<String> recent = chat.size() > CHAT_LINES ? chat.subList(chat.size() - CHAT_LINES, chat.size()) : chat;
        List<ChatLine> lines = recent.stream().map(line -> ChatLine.parse(line, game.getPlayersList(), local))
              .toList();
        return new LobbySnapshot(local == null ? Player.PLAYER_NONE : local.getId(),
              local != null && local.isGameMaster(), local != null && local.isDone(),
              gameMasterAvailable(game, local), teams, lines, new ArrayList<>(new TreeSet<>(customZones)),
              mapSummary(client.getMapSettings()), server(client.getHost(), client.getPort()),
              LobbyForces.of(session, order), LobbyBattlefield.of(game.getOptions(), game.getPlanetaryConditions(),
              client.getMapSettings(), autoResolve));
    }

    /** Where a team goes in the list: the local player's team first, the players on no team last. */
    private static int teamOrder(int team, int localTeam) {
        if (team <= Player.TEAM_NONE) {
            return Integer.MAX_VALUE;
        }
        return team == localTeam ? Integer.MIN_VALUE : team;
    }

    /**
     * Whether {@code local} may take or give up the Game Master role: the game must allow one, and nobody else may
     * hold it, as the Swing lobby's Game Master button has it.
     */
    static boolean gameMasterAvailable(Game game, Player local) {
        if (local == null || !game.getOptions().booleanOption(OptionsConstants.GAME_MASTER_ALLOW)) {
            return false;
        }
        return game.getPlayersList().stream().noneMatch(player -> player.isGameMaster()
              && player.getId() != local.getId());
    }

    /** The player's colour as 0xRRGGBB. */
    private static int colour(Player player) {
        var colour = player.getColour();
        return colour == null ? 0 : colour.getColour().getRGB() & 0xFFFFFF;
    }

    /**
     * Where {@code player} deploys, as the Swing lobby's player list words it: "Player0" when all human players deploy
     * with player 0, "Blind" when a blind drop hides it, otherwise the deployment area with its corners, offset and
     * width where they differ from the default, or a custom zone; empty when unknown.
     */
    static String startText(Game game, Player player, boolean hidden) {
        if (game.getOptions().booleanOption(OptionsConstants.BASE_SET_PLAYER_DEPLOYMENT_TO_PLAYER_0)
              && !player.isBot() && player.getId() != 0) {
            return Messages.getString("ChatLounge.Player0");
        }
        if (hidden) {
            return Messages.getString("ChatLounge.Blind");
        }
        int pos = player.getStartingPos();
        if (pos >= 0 && pos < IStartingPositions.START_LOCATION_NAMES.length) {
            StringBuilder text = new StringBuilder(IStartingPositions.START_LOCATION_NAMES[pos]);
            if (pos == 0) {
                int nwX = player.getStartingAnyNWx() + 1;
                int nwY = player.getStartingAnyNWy() + 1;
                int seX = player.getStartingAnySEx() + 1;
                int seY = player.getStartingAnySEy() + 1;
                if (nwX + nwY + seX + seY > 0) {
                    text.append(" (").append(nwX).append(", ").append(nwY).append(")-(").append(seX).append(", ")
                          .append(seY).append(')');
                }
            }
            int offset = player.getStartOffset();
            int width = player.getStartWidth();
            if (offset != 0 || !(width == 3 || (width == 1
                  && Game.rulesManager.getRulesGame().isWalkOnDeployment()))) {
                text.append(", ").append(offset).append(", ").append(width);
            }
            return text.toString();
        }
        if (pos > IStartingPositions.START_LOCATION_NAMES.length) {
            return zoneName(Board.decodeCustomDeploymentZoneID(pos));
        }
        return "";
    }

    /** The name of the custom deployment zone {@code zone}. */
    public static String zoneName(int zone) {
        return Messages.getString("Lobby.zone", zone);
    }

    /** The name of the deployment area {@code pos}: a standard area, or an encoded custom zone. */
    public static String deploymentName(int pos) {
        if (pos >= 0 && pos < IStartingPositions.START_LOCATION_NAMES.length) {
            return IStartingPositions.START_LOCATION_NAMES[pos];
        }
        return zoneName(Board.decodeCustomDeploymentZoneID(pos));
    }

    /** The name of team {@code team}: "No team", or "Team n". */
    public static String teamName(int team) {
        return team <= Player.TEAM_NONE ? Messages.getString("Lobby.noTeam")
              : Messages.getString("Lobby.team", team);
    }

    /** One line on the map: the size of a single board, or how many boards of which size. */
    static String mapSummary(MapSettings settings) {
        if (settings == null) {
            return "";
        }
        if (settings.getMapWidth() * settings.getMapHeight() == 1) {
            return Messages.getString("Lobby.mapOneBoard", settings.getBoardWidth(), settings.getBoardHeight());
        }
        return Messages.getString("Lobby.mapBoards", settings.getMapWidth(), settings.getMapHeight(),
              settings.getBoardWidth(), settings.getBoardHeight());
    }

    /** Where the game runs: "Local game, port n" for this machine, otherwise its address and port. */
    static String server(String host, int port) {
        if (host == null || host.isBlank() || MMConstants.LOCALHOST.equalsIgnoreCase(host)
              || MMConstants.LOCALHOST_IP.equals(host)) {
            return Messages.getString("Lobby.serverLocal", String.valueOf(port));
        }
        return Messages.getString("Lobby.serverRemote", host, String.valueOf(port));
    }
}
