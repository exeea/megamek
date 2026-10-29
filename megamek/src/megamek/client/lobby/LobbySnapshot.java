/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import megamek.MMConstants;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;

/**
 * What the lobby screen shows at one moment, read from the live game on the Swing thread and handed to a view that
 * must not read the game itself, such as the libGDX lobby on its own thread.
 *
 * @param localPlayerId   the local player's id, or {@link Player#PLAYER_NONE} before the server assigned one
 * @param localGameMaster whether the local player is the Game Master
 * @param localReady      whether the local player has declared themselves ready (done)
 * @param teams           the players by team, in team order; players without a team come first
 * @param chat            the chat's recent lines, the newest last
 * @param mapSummary      one line on the map: its boards and their size
 * @param server          where the game runs: a local game's port, or the server's address and port
 */
public record LobbySnapshot(int localPlayerId, boolean localGameMaster, boolean localReady, List<TeamGroup> teams,
      List<String> chat, String mapSummary, String server) {

    /** The most chat lines a snapshot carries. */
    public static final int CHAT_LINES = 200;

    /** An empty lobby, as shown before the client has its game. */
    public static final LobbySnapshot EMPTY = new LobbySnapshot(Player.PLAYER_NONE, false, false, List.of(), List.of(),
          "", "");

    public LobbySnapshot {
        teams = List.copyOf(teams);
        chat = List.copyOf(chat);
        Objects.requireNonNull(mapSummary);
        Objects.requireNonNull(server);
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
    }

    /**
     * One player as the lobby shows them.
     *
     * @param self       whether this is the local player
     * @param local      whether the player runs on this machine: the local player or one of its bots
     * @param ready      whether the player has declared themselves ready (done)
     * @param hidden     whether the blind drop rules hide the player's forces from the local player; their units and
     *                   BV are then 0
     * @param units      how many units the player has, fighters in a squadron counted once
     * @param bv         the player's total Battle Value
     */
    public record PlayerRow(int id, String name, int team, boolean bot, boolean self, boolean local,
          boolean gameMaster, boolean observer, boolean ready, boolean hidden, int units, int bv) { }

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

    /**
     * Reads the lobby from {@code session}'s game. Call it on the thread that changes the game (the Swing thread).
     *
     * @param chat the chat lines to show; only the last {@link #CHAT_LINES} are kept
     */
    public static LobbySnapshot of(LobbySession session, List<String> chat) {
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
            if (!hidden) {
                for (Entity entity : game.getPlayerEntities(player, false)) {
                    if (!(entity instanceof FighterSquadron)) {
                        units++;
                    }
                }
                bv = player.getBV();
            }
            int team = Math.max(Player.TEAM_NONE, player.getTeam());
            byTeam.computeIfAbsent(team, key -> new ArrayList<>()).add(new PlayerRow(player.getId(),
                  player.getName(), team, player.isBot(), local != null && player.getId() == local.getId(),
                  localIds.contains(player.getId()), player.isGameMaster(), player.isObserver(), player.isDone(),
                  hidden, units, bv));
        }
        List<TeamGroup> teams = new ArrayList<>();
        byTeam.forEach((team, players) -> teams.add(new TeamGroup(team, teamName(team), players)));
        List<String> recent = chat.size() > CHAT_LINES ? chat.subList(chat.size() - CHAT_LINES, chat.size()) : chat;
        return new LobbySnapshot(local == null ? Player.PLAYER_NONE : local.getId(),
              local != null && local.isGameMaster(), local != null && local.isDone(), teams, recent,
              mapSummary(client.getMapSettings()), server(client.getHost(), client.getPort()));
    }

    /** The name of team {@code team}: "No team", or "Team n". */
    static String teamName(int team) {
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
