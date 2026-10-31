/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.bot.BotClient;
import megamek.client.generator.ReconfigurationParameters;
import megamek.client.generator.TeamLoadOutGenerator;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.generator.enums.SkillGeneratorType;
import megamek.client.generator.skillGenerators.AbstractSkillGenerator;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.RATGenerator;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.Team;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.containers.MunitionTree;
import megamek.common.enums.SkillLevel;
import megamek.common.equipment.BombLoadout;
import megamek.common.equipment.Briefcase;
import megamek.common.equipment.ICarryable;
import megamek.common.equipment.Minefield;
import megamek.common.game.Game;
import megamek.common.interfaces.IStartingPositions;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.server.ServerBoardHelper;

/**
 * The player settings the lobby edits, without a toolkit, as the Swing {@code PlayerSettingsDialog} edits them for the
 * client's local player (a bot's own player for a bot client): the ammunition auto-configuration, the deployment area
 * (start position, offset, width and, for "Any", a custom box), the initiative modifier, minefields when the rules
 * allow them, fortified hexes, carryable ground objects, the skill generation method and the round report email (not
 * for bots). The view sets texts and choices here; {@link #apply} sends them through the same client calls the Swing
 * dialog uses.
 * <p>
 * Unlike the Swing dialog, which reads a field it cannot parse as 0, a field that is not a whole number (or is below
 * 0, except the initiative modifier) has an error, and {@link #valid} is false until it is fixed. Ground objects are
 * kept here and given to the player on {@link #apply}, so cancelling leaves the player's list as it was.
 */
public final class PlayerSettingsForm {
    /** The ground object name's longest length. */
    public static final int MAX_OBJECT_NAME = 20;
    /** The faction when the team's own is not among the choices. */
    public static final String DEFAULT_FACTION = "IS";

    /** A field the player types a whole number into. */
    public enum Field {
        INIT(true),
        CONVENTIONAL(false),
        VIBRABOMB(false),
        ACTIVE(false),
        INFERNO(false),
        EMP(false),
        TRIPWIRE(false),
        PITFALL(false),
        FORTIFIED(false),
        OFFSET(false),
        WIDTH(false),
        ANY_NW_X(false),
        ANY_NW_Y(false),
        ANY_SE_X(false),
        ANY_SE_Y(false);

        private final boolean signed;

        Field(boolean signed) {
            this.signed = signed;
        }

        /** Whether the field takes numbers below 0. */
        public boolean signed() {
            return signed;
        }
    }

    /** The minefield fields and the minefield types they count, in the Swing dialog's order. */
    public static final Map<Field, Integer> MINEFIELDS = mineTypes();

    private static Map<Field, Integer> mineTypes() {
        Map<Field, Integer> map = new EnumMap<>(Field.class);
        map.put(Field.CONVENTIONAL, Minefield.TYPE_CONVENTIONAL);
        map.put(Field.VIBRABOMB, Minefield.TYPE_VIBRABOMB);
        map.put(Field.ACTIVE, Minefield.TYPE_ACTIVE);
        map.put(Field.INFERNO, Minefield.TYPE_INFERNO);
        map.put(Field.EMP, Minefield.TYPE_EMP);
        map.put(Field.TRIPWIRE, Minefield.TYPE_TRIPWIRE);
        map.put(Field.PITFALL, Minefield.TYPE_PITFALL);
        return Map.copyOf(map);
    }

    /**
     * A faction the auto-configuration may use.
     *
     * @param code its key, such as "IS"
     * @param name its name in the game's year
     */
    public record Faction(String code, String name) {}

    /**
     * Another player who deploys at a start position.
     *
     * @param name     their name
     * @param relation how they stand to this player: {@link LobbySnapshot.Relation#ALLY} or
     *                 {@link LobbySnapshot.Relation#ENEMY}
     */
    public record Deployer(String name, LobbySnapshot.Relation relation) {}

    /**
     * A start position the player can choose, as the Swing dialog's grid shows it.
     *
     * @param id     the position, {@link Board#START_ANY} and the others of {@link IStartingPositions}, or an encoded
     *               custom zone
     * @param label  its name, or "Zone n" for a custom zone
     * @param tip    the custom zone's hexes, or empty
     * @param valid  false when the position is not allowed, such as a zone another player has under Double Blind
     * @param others the other players deploying there
     */
    public record Position(int id, String label, String tip, boolean valid, List<Deployer> others) {
        public Position {
            others = List.copyOf(others);
        }
    }

    /**
     * A carryable object to place before deployment.
     *
     * @param name         its name, at most {@link #MAX_OBJECT_NAME} characters
     * @param tonnage      its weight, at least 0
     * @param invulnerable whether it cannot be destroyed
     * @param source       the player's object it shows, or null for one added here
     */
    public record GroundObject(String name, double tonnage, boolean invulnerable, @Nullable ICarryable source) {}

    /** The grid of fixed positions, three to a row, as the Swing dialog lays it out; -1 is an empty cell. */
    public static final int[][] GRID = { { 1, 2, 3 }, { 8, 10, 4 }, { 7, 6, 5 }, { 0, 9, -1 } };

    private final Client client;
    private final LobbyCommands commands;
    private final Player player;
    private final Game game;
    private final Board board;
    private final int boardWidth;
    private final int boardHeight;
    private final List<Faction> factions;
    private final Map<Field, String> texts = new EnumMap<>(Field.class);
    private final List<GroundObject> groundObjects = new ArrayList<>();
    private int startPos;
    private SkillGeneratorMethod method;
    private SkillGeneratorType type;
    private SkillLevel level;
    private boolean forceClose;
    private String email;
    private String faction;

    private TeamLoadOutGenerator generator;
    private ReconfigurationParameters parameters;
    private MunitionTree tree;
    private MunitionTree original;
    private boolean autoconfigEnabled = true;
    private boolean randomizeEnabled = true;
    private boolean restoreEnabled;
    private boolean trulyRandom;
    private boolean banNukes = true;

    /**
     * @param client   the client whose local player is edited: the human's, or a bot's
     * @param commands the lobby's commands, which send the reconfigured units
     * @param board    the board the custom zones come from
     * @param factions the factions to offer for the auto-configuration, in order
     */
    public PlayerSettingsForm(Client client, LobbyCommands commands, Board board, List<Faction> factions) {
        this.client = client;
        this.commands = commands;
        this.board = board;
        this.factions = List.copyOf(factions);
        game = client.getGame();
        player = client.getLocalPlayer();
        MapSettings settings = client.getMapSettings();
        boardWidth = settings.getBoardWidth() * settings.getMapWidth();
        boardHeight = settings.getBoardHeight() * settings.getMapHeight();
        startPos = player.getStartingPos();
        texts.put(Field.INIT, Integer.toString(player.getConstantInitBonus()));
        for (Map.Entry<Field, Integer> mine : MINEFIELDS.entrySet()) {
            texts.put(mine.getKey(), Integer.toString(player.getMinefieldCount(mine.getValue())));
        }
        texts.put(Field.FORTIFIED, Integer.toString(player.getNbrFortifiedHexes()));
        texts.put(Field.OFFSET, Integer.toString(player.getStartOffset()));
        texts.put(Field.WIDTH, Integer.toString(player.getStartWidth()));
        texts.put(Field.ANY_NW_X, Integer.toString(Math.min(player.getStartingAnyNWx() + 1, boardWidth)));
        texts.put(Field.ANY_NW_Y, Integer.toString(Math.min(player.getStartingAnyNWy() + 1, boardHeight)));
        texts.put(Field.ANY_SE_X, Integer.toString(Math.min(player.getStartingAnySEx() + 1, boardWidth)));
        texts.put(Field.ANY_SE_Y, Integer.toString(Math.min(player.getStartingAnySEy() + 1, boardHeight)));
        AbstractSkillGenerator skills = client.getSkillGenerator();
        method = skills.getMethod();
        type = skills.getType();
        level = skills.getLevel();
        forceClose = skills.isForceClose();
        email = player.getEmail() == null ? "" : player.getEmail();
        for (ICarryable object : player.getGroundObjectsToPlace()) {
            groundObjects.add(new GroundObject(object.generalName(), object.getTonnage(), object.isInvulnerable(),
                  object));
        }
        Team team = game.getTeamForPlayer(player);
        String teamFaction = team == null ? null : team.getFaction();
        faction = factions.stream().anyMatch(f -> f.code().equals(teamFaction)) ? teamFaction : DEFAULT_FACTION;
    }

    /**
     * The form for {@code client}'s local player, with the board as the Swing dialog finds it (the one the map
     * settings would make while in the lobby) and the major factions active in the game's year.
     */
    public static PlayerSettingsForm of(Client client, LobbyCommands commands) {
        Game game = client.getGame();
        Board board = game.getPhase().isLounge()
              ? ServerBoardHelper.getPossibleGameBoard(client.getMapSettings(), true)
              : game.getBoard();
        return new PlayerSettingsForm(client, commands, board,
              factions(game.getOptions().intOption(OptionsConstants.ALLOWED_YEAR)));
    }

    /** The major factions active in {@code year}, by name, as the Swing dialog offers them. */
    public static List<Faction> factions(int year) {
        List<Faction> found = new ArrayList<>();
        for (FactionRecord record : RATGenerator.getInstance().getFactionList()) {
            if (!record.isMinor() && !record.getKey().contains(".") && record.isActiveInYear(year)) {
                found.add(new Faction(record.getKey(), record.getName(year)));
            }
        }
        found.sort(Comparator.comparing(Faction::name));
        return found;
    }

    // region What the form shows

    /** The edited player. */
    public Player player() {
        return player;
    }

    /** Whether the edited player is a bot, which has bot settings and no email. */
    public boolean bot() {
        return client instanceof BotClient;
    }

    /** Whether the rules allow minefields, which shows their counts. */
    public boolean minefields() {
        return Game.rulesManager.getRulesGame()
              .allowMinefields(game.getOptions().booleanOption(OptionsConstants.ADVANCED_MINEFIELDS));
    }

    /** Whether the rules have units walk on rather than deploy. */
    public boolean walkOn() {
        return Game.rulesManager.getRulesGame().isWalkOnDeployment();
    }

    /** Whether the rules keep the deployment width at 1 for the chosen position, which disables the width. */
    public boolean widthRestricted() {
        return Game.rulesManager.getRulesGame().restrictDeploymentWidth(player, startPos);
    }

    /** Whether the custom deployment box applies: only for the "Any" position. */
    public boolean anyBox() {
        return startPos == Board.START_ANY;
    }

    /** The board's width and height in hexes, the custom box's largest corner. */
    public int boardWidth() {
        return boardWidth;
    }

    public int boardHeight() {
        return boardHeight;
    }

    /** The fixed positions in grid order (see {@link #GRID}), then the board's custom zones. */
    public List<Position> positions() {
        List<Position> found = new ArrayList<>();
        for (int[] row : GRID) {
            for (int id : row) {
                if (id >= 0) {
                    found.add(position(id, IStartingPositions.START_LOCATION_NAMES[id], ""));
                }
            }
        }
        for (int zone : customZones()) {
            StringBuilder tip = new StringBuilder("Zone ").append(zone).append(": ");
            List<String> hexes = new ArrayList<>();
            for (Coords coords : board.getCustomDeploymentZone(zone)) {
                hexes.add(coords.toFriendlyString());
            }
            tip.append(String.join(", ", hexes));
            found.add(position(Board.encodeCustomDeploymentZoneID(zone), "Zone " + zone, tip.toString()));
        }
        return found;
    }

    /** The board's custom zone numbers, in order. */
    public List<Integer> customZones() {
        return board.getCustomDeploymentZones().stream().sorted().toList();
    }

    private Position position(int id, String label, String tip) {
        List<Deployer> others = new ArrayList<>();
        for (Player other : game.getPlayersList()) {
            if (!other.equals(player) && other.getStartingPos() == id && id != Board.START_ANY) {
                others.add(new Deployer(other.getName(), other.isEnemyOf(player)
                      ? LobbySnapshot.Relation.ENEMY : LobbySnapshot.Relation.ALLY));
            }
        }
        return new Position(id, label, tip, LobbyRules.isValidStartPos(game, player, id), others);
    }

    // endregion
    // region Edits

    public int startPos() {
        return startPos;
    }

    /** Chooses the start position (one of {@link #positions}). */
    public void startPos(int id) {
        startPos = id;
    }

    /** The field's text. */
    public String text(Field field) {
        return texts.get(field);
    }

    public void text(Field field, String text) {
        texts.put(field, text == null ? "" : text);
    }

    /**
     * The message key of the field's error, or empty: a whole number is needed, at least 0 unless the field is
     * signed, and the custom box's corners lie on the board. Fields that do not apply have no error.
     */
    public Optional<String> error(Field field) {
        if (!applies(field)) {
            return Optional.empty();
        }
        Integer value = parse(texts.get(field));
        if (value == null) {
            return Optional.of("Lobby.settings.wholeNumber");
        }
        if (!field.signed() && value < 0) {
            return Optional.of("Lobby.settings.notNegative");
        }
        int most = switch (field) {
            case ANY_NW_X, ANY_SE_X -> boardWidth;
            case ANY_NW_Y, ANY_SE_Y -> boardHeight;
            default -> Integer.MAX_VALUE;
        };
        return value > most ? Optional.of("Lobby.settings.offBoard") : Optional.empty();
    }

    /** Whether the field is shown and used: minefields only when allowed, the width and box only when they apply. */
    public boolean applies(Field field) {
        return switch (field) {
            case CONVENTIONAL, VIBRABOMB, ACTIVE, INFERNO, EMP, TRIPWIRE, PITFALL -> minefields();
            case WIDTH -> !widthRestricted();
            case ANY_NW_X, ANY_NW_Y, ANY_SE_X, ANY_SE_Y -> anyBox();
            default -> true;
        };
    }

    /** Whether every field that applies holds an acceptable number. */
    public boolean valid() {
        for (Field field : Field.values()) {
            if (error(field).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable Integer parse(@Nullable String text) {
        try {
            return text == null ? null : Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int value(Field field) {
        Integer value = parse(texts.get(field));
        return value == null ? 0 : value;
    }

    public SkillGeneratorMethod method() {
        return method;
    }

    public void method(SkillGeneratorMethod value) {
        method = value;
    }

    public SkillGeneratorType type() {
        return type;
    }

    public void type(SkillGeneratorType value) {
        type = value;
    }

    public SkillLevel level() {
        return level;
    }

    public void level(SkillLevel value) {
        level = value;
    }

    /** The skill levels a generator can roll, as the Swing dialog offers them. */
    public static List<SkillLevel> levels() {
        return List.copyOf(SkillLevel.getGeneratableValues());
    }

    public boolean forceClose() {
        return forceClose;
    }

    public void forceClose(boolean value) {
        forceClose = value;
    }

    public String email() {
        return email;
    }

    public void email(String value) {
        email = value == null ? "" : value;
    }

    public List<GroundObject> groundObjects() {
        return List.copyOf(groundObjects);
    }

    /**
     * Adds a ground object as the Swing dialog does: the name cut to {@link #MAX_OBJECT_NAME} characters, and a
     * tonnage that is not a number or is below 0 read as 0.
     */
    public void addGroundObject(String name, String tonnage, boolean invulnerable) {
        String cut = name == null ? "" : name.length() > MAX_OBJECT_NAME ? name.substring(0, MAX_OBJECT_NAME) : name;
        double tons;
        try {
            tons = Math.max(0, Double.parseDouble(tonnage.trim()));
        } catch (NumberFormatException | NullPointerException e) {
            tons = 0;
        }
        groundObjects.add(new GroundObject(cut, Double.isNaN(tons) ? 0 : tons, invulnerable, null));
    }

    public void removeGroundObject(int index) {
        groundObjects.remove(index);
    }

    // endregion
    // region Ammunition auto-configuration

    public List<Faction> factions() {
        return factions;
    }

    /** The faction code the auto-configuration uses. */
    public String faction() {
        return faction;
    }

    /** Chooses the faction, which makes Autoconfig available again. */
    public void faction(String code) {
        if (!code.equals(faction)) {
            faction = code;
            autoconfigEnabled = true;
        }
    }

    public boolean autoconfigEnabled() {
        return autoconfigEnabled;
    }

    public boolean randomizeEnabled() {
        return randomizeEnabled;
    }

    public boolean restoreEnabled() {
        return restoreEnabled;
    }

    public boolean trulyRandom() {
        return trulyRandom;
    }

    public void trulyRandom(boolean value) {
        trulyRandom = value;
    }

    public boolean banNukes() {
        return banNukes;
    }

    public void banNukes(boolean value) {
        banNukes = value;
    }

    private TeamLoadOutGenerator generator() {
        if (generator == null) {
            generator = new TeamLoadOutGenerator(game);
        }
        return generator;
    }

    private MunitionTree original() {
        if (original == null) {
            original = new MunitionTree();
            original.loadEntityList(game.getPlayerEntities(player, false));
        }
        return original;
    }

    /** Works out a loadout for the player's units from the faction and the game; applied on {@link #apply}. */
    public void autoconfigure() {
        restoreEnabled = true;
        autoconfigEnabled = false;
        randomizeEnabled = true;
        original();
        Team team = game.getTeamForPlayer(player);
        parameters = generator().generateParameters(team);
        parameters.isPirate = faction.equalsIgnoreCase("PIR");
        parameters.nukesBannedForMe = banNukes;
        tree = TeamLoadOutGenerator.generateMunitionTree(parameters, game.getPlayerEntities(player, false), "");
    }

    /** Makes a random loadout, truly random or only damaging ammunition; applied on {@link #apply}. */
    public void randomize() {
        restoreEnabled = true;
        autoconfigEnabled = true;
        randomizeEnabled = false;
        original();
        generator().setTrueRandom(trulyRandom);
        tree = TeamLoadOutGenerator.generateRandomizedMT();
    }

    /** Goes back to the units' loadout as the form found it. */
    public void restore() {
        tree = original();
    }

    /** The loadout to save: the one worked out, or the units' own. */
    public MunitionTree loadoutToSave() {
        return tree != null ? tree : original();
    }

    /** Uses a loadout read from a file; applied on {@link #apply}. */
    public void loaded(MunitionTree loaded) {
        tree = loaded;
        restoreEnabled = true;
    }

    // endregion

    /**
     * Gives the player the settings and sends them, as the Swing dialog's OK and Apply do: the initiative modifier,
     * minefields, fortified hexes, skill generation and email; offboard artillery moved behind a new home edge when
     * the game asks for it; the faction and any worked-out loadout (bombs cleared, units reconfigured and sent); then
     * the deployment area, and the player info to the server. Does nothing while a field has an error.
     *
     * @return whether the settings were applied
     */
    public boolean apply() {
        if (!valid()) {
            return false;
        }
        player.setConstantInitBonus(value(Field.INIT));
        if (minefields()) {
            for (Map.Entry<Field, Integer> mine : MINEFIELDS.entrySet()) {
                player.setMinefieldCount(mine.getValue(), value(mine.getKey()));
            }
        }
        player.setNbrFortifiedHexes(value(Field.FORTIFIED));
        AbstractSkillGenerator skills = client.getSkillGenerator();
        if (method != skills.getMethod()) {
            client.setSkillGenerator(method.getGenerator());
            skills = client.getSkillGenerator();
        }
        skills.setType(type);
        skills.setLevel(level);
        skills.setForceClose(forceClose);
        if (!bot()) {
            player.setEmail(email.trim());
        }
        List<ICarryable> objects = new ArrayList<>();
        for (GroundObject object : groundObjects) {
            if (object.source() != null) {
                objects.add(object.source());
                continue;
            }
            Briefcase briefcase = new Briefcase();
            briefcase.setName(object.name());
            briefcase.setTonnage(object.tonnage());
            briefcase.setInvulnerable(object.invulnerable());
            objects.add(briefcase);
        }
        player.getGroundObjectsToPlace().clear();
        player.getGroundObjectsToPlace().addAll(objects);

        OffBoardDirection direction = OffBoardDirection.translateStartPosition(startPos);
        if (direction != OffBoardDirection.NONE
              && game.getOptions().booleanOption(OptionsConstants.BASE_SET_ARTY_PLAYER_HOME_EDGE)) {
            for (Entity entity : game.getPlayerEntities(player, false)) {
                if (entity.getOffBoardDirection() != OffBoardDirection.NONE) {
                    entity.setOffBoard(entity.getOffBoardDistance(), direction);
                }
            }
        }

        Team team = game.getTeamForPlayer(player);
        if (team != null) {
            team.setFaction(faction);
        }
        if (tree != null && parameters != null) {
            ArrayList<Entity> units = game.getPlayerEntities(player, false);
            parameters.friendlyFaction = faction;
            parameters.binFillPercent = parameters.isPirate ? TeamLoadOutGenerator.UNSET_FILL_RATIO : 1.0f;
            resetBombs(units);
            generator().reconfigureEntities(units, faction, tree, parameters, null);
            commands.sendProxyUpdates(units, player);
        }

        player.setStartingPos(startPos);
        player.setStartOffset(value(Field.OFFSET));
        player.setStartWidth(value(Field.WIDTH));
        int nwX = value(Field.ANY_NW_X);
        int nwY = value(Field.ANY_NW_Y);
        int seX = value(Field.ANY_SE_X);
        int seY = value(Field.ANY_SE_Y);
        player.setStartingAnyNWx(Math.min(nwX, seX) - 1);
        player.setStartingAnyNWy(Math.min(nwY, seY) - 1);
        player.setStartingAnySEx(Math.max(nwX, seX) - 1);
        player.setStartingAnySEy(Math.max(nwY, seY) - 1);
        client.sendPlayerInfo(player);
        return true;
    }

    private void resetBombs(List<Entity> units) {
        List<Entity> bombers = new ArrayList<>();
        for (Entity entity : units) {
            if (entity.isBomber() && !entity.isVehicle()) {
                IBomber bomber = (IBomber) entity;
                bomber.setIntBombChoices(new BombLoadout());
                bomber.setExtBombChoices(new BombLoadout());
                bombers.add(entity);
            }
        }
        if (!bombers.isEmpty()) {
            commands.sendProxyUpdates(bombers, game.getPlayer(units.getFirst().getOwnerId()));
        }
    }
}
