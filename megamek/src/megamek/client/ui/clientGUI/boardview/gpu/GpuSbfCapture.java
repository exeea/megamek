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
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import megamek.client.ui.Messages;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.util.StringDrawer;
import megamek.client.ui.util.UIUtil;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.IArmorState;
import megamek.common.game.InGameObject;
import megamek.common.icons.Camouflage;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;
import megamek.common.strategicBattleSystems.SBFSomethingOutThereUnitPlaceHolder;
import megamek.common.strategicBattleSystems.SBFTurn;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFUnitPlaceHolder;
import megamek.common.units.Entity;

/**
 * EDT: what an SBF game puts into the 3D board's frame where a Total Warfare game has its entities: the formations as
 * unit tokens (a counter's picture: camouflage, the owner's colour, the formation type), the placeholders of
 * units only detected as sensor contacts, the formations' status for the HUD and the SBF reports for the log. The
 * {@link SBFGame} is the one source of truth; this keeps only the counter pictures, one per look, and the last
 * snapshots, returned again while nothing changed so the render thread can compare them by identity.
 */
final class GpuSbfCapture {
    private static final int INSET = 6;
    private static final int RIM = 4;

    private final SBFGame game;
    private final IntSupplier localPlayerId;
    private final Map<Counter, BoardScene.Pixels> counters = new HashMap<>();
    private GpuBattleStatus.Snapshot status = GpuBattleStatus.Snapshot.EMPTY;
    private GpuReportLog.Snapshot reports = GpuReportLog.Snapshot.EMPTY;

    /** A counter's look: the formation type's text and its owner's camouflage and colour. */
    private record Counter(String type, Camouflage camouflage, int rgb) { }

    GpuSbfCapture(SBFGame game, IntSupplier localPlayerId) {
        this.game = game;
        this.localPlayerId = localPlayerId;
    }

    /** The formations and placeholders standing on {@code boardId}. */
    List<BoardScene.Unit> units(int boardId) {
        List<BoardScene.Unit> units = new ArrayList<>();
        for (InGameObject object : byId()) {
            BoardLocation location = position(object);
            if (location == null || location.boardId() != boardId) {
                continue;
            }
            Player owner = game.getPlayer(object.getOwnerId());
            int rgb = owner == null ? Color.GRAY.getRGB() : owner.getColour().getColour().getRGB();
            BoardScene.Waypoint point = new BoardScene.Waypoint(location.coords(), level(location), 0);
            if (object instanceof SBFFormation formation) {
                units.add(new BoardScene.Unit(formation.getId(), -1, formation.getName(), point,
                      counter(formation.getType().toString(), owner), false, null, 1, false, null, rgb));
            } else {
                units.add(new BoardScene.Unit(object.getId(), -1, contactName(object), point, null, true, null, 1,
                      false, null, rgb));
            }
        }
        return units;
    }

    /** The HUD's status: the turn order, the local player's formations first, then the others in sight. */
    GpuBattleStatus.Snapshot status(int actorId) {
        int localId = localPlayerId.getAsInt();
        Player local = game.getPlayer(localId);
        SBFTurn turn = game.getTurn();
        boolean myTurn = turn != null && turn.isValid(localId, game);
        GamePhase phase = game.getPhase();
        List<GpuBattleStatus.Slot> slots = new ArrayList<>();
        for (SBFTurn each : game.getTurnsList()) {
            Player player = game.getPlayer(each.playerId());
            slots.add(new GpuBattleStatus.Slot(each.playerId(), player == null ? "" : player.getName(),
                  player == null ? 0 : player.getColour().getColour().getRGB(),
                  side(each.playerId() == localId, player, local), Entity.NONE));
        }
        boolean turnsLeft = !phase.isReport() && game.hasMoreTurns();
        List<GpuBattleStatus.UnitStatus> own = new ArrayList<>();
        List<GpuBattleStatus.UnitStatus> others = new ArrayList<>();
        for (InGameObject object : byId()) {
            BoardLocation location = position(object);
            if (location == null) {
                continue;
            }
            Player owner = game.getPlayer(object.getOwnerId());
            GpuBattleStatus.Side side = side(object.getOwnerId() == localId, owner, local);
            List<GpuBattleStatus.UnitStatus> list = side == GpuBattleStatus.Side.OWN ? own : others;
            if (object instanceof SBFFormation formation) {
                boolean eligible = formation.isEligibleForPhase(phase);
                boolean canActNow = myTurn && turn instanceof SBFFormationTurn formationTurn
                      && formationTurn.isValidEntity(formation, game);
                list.add(formationStatus(formation, side, canActNow, turnsLeft && eligible, location, owner));
            } else {
                list.add(new GpuBattleStatus.UnitStatus(object.getId(), side, true, "", "", "", 0, "", "", "", 0, 0,
                      IArmorState.ARMOR_NA, IArmorState.ARMOR_NA, 0, 0, "", 0, "", 0, "", 0, 0, -1, 0, false, false,
                      false, false, Entity.DMG_NONE, List.of(), "", location.coords(), location.boardId(), null,
                      List.of(), 0, 0, Player.PLAYER_NONE, List.of(), "", GpuBattleStatus.Marks.NONE));
            }
        }
        own.addAll(others);
        var next = new GpuBattleStatus.Snapshot(game.getCurrentRound(), phase, myTurn, localId, actorId, slots,
              game.getTurnIndex(), own, List.of(), false);
        if (!next.equals(status)) {
            status = next;
        }
        return status;
    }

    /**
     * The SBF reports for the log: an entry for each part of a round, which begins at a report header (the round's
     * initiative, a phase); the text before the round's first header is an entry headed by the round.
     */
    GpuReportLog.Snapshot reports() {
        List<GpuReportLog.Entry> entries = new ArrayList<>();
        for (int round = 0; round <= game.getCurrentRound(); round++) {
            if (!game.getGameReport().hasReportsForRound(round)) {
                continue;
            }
            String heading = Messages.getString("MiniReportDisplay.Round") + " " + round;
            StringBuilder html = new StringBuilder();
            for (SBFReportEntry entry : game.getGameReport().get(round)) {
                if (entry instanceof SBFReportHeader) {
                    addEntry(entries, round, heading, html);
                    heading = GpuReportLog.linkedText(entry.text()).text().strip();
                    html.setLength(0);
                } else if (entry != null) {
                    html.append(entry.text());
                }
            }
            addEntry(entries, round, heading, html);
        }
        var next = new GpuReportLog.Snapshot(game.getCurrentRound(), game.getPhase(), entries, Map.of(), List.of(),
              List.of(), List.of(), List.of());
        if (!next.equals(reports)) {
            reports = next;
        }
        return reports;
    }

    /** The shown name of the formation or placeholder, or "" for none. */
    String name(int id) {
        return game.getInGameObject(id).map(object -> object instanceof SBFFormation formation
              ? formation.getName() : position(object) == null ? "" : contactName(object)).orElse("");
    }

    /** The game's formations and placeholders in ascending id order, the order of the phase's next-unit command. */
    private List<InGameObject> byId() {
        return game.getInGameObjects().stream().sorted(Comparator.comparingInt(InGameObject::getId)).toList();
    }

    private static void addEntry(List<GpuReportLog.Entry> entries, int round, String heading, StringBuilder html) {
        GpuReportLog.Text text = GpuReportLog.linkedText(html.toString());
        if (text.text().isBlank()) {
            return;
        }
        entries.add(new GpuReportLog.Entry(round, heading, GamePhase.UNKNOWN, heading, text.text().strip(), List.of(),
              "", text.links(), GpuReportLog.Kind.OTHER, Entity.NONE, Entity.NONE, null, List.of(), false, false,
              null, null, null, null, null, null, null, false, false));
    }

    private GpuBattleStatus.UnitStatus formationStatus(SBFFormation formation, GpuBattleStatus.Side side,
          boolean canActNow, boolean pending, BoardLocation location, Player owner) {
        int armor = 0;
        int currentArmor = 0;
        for (SBFUnit unit : formation.getUnits()) {
            armor += unit.getArmor();
            currentArmor += unit.getCurrentArmor();
        }
        double armorShare = armor == 0 ? IArmorState.ARMOR_NA : currentArmor / (double) armor;
        String type = formation.getType().toString();
        return new GpuBattleStatus.UnitStatus(formation.getId(), side, false, formation.getName(), formation.getName(), "", 0,
              type + " " + formation.getSize(), "", "", formation.getSkill(), 0, armorShare, IArmorState.ARMOR_NA, 0, 0,
              "", formation.getMovement(), "", formation.getJumpMove(), "", 0, 0, -1, formation.getTmm(), canActNow,
              pending, formation.isDone(), false, Entity.DMG_NONE, List.of(), "", location.coords(),
              location.boardId(), counter(type, owner), List.of(), formation.getSize(), 0, formation.getOwnerId(),
              List.of(), formation.getName(), GpuBattleStatus.Marks.NONE);
    }

    private static GpuBattleStatus.Side side(boolean own, Player owner, Player local) {
        return own ? GpuBattleStatus.Side.OWN
              : owner == null || local == null || owner.isEnemyOf(local) ? GpuBattleStatus.Side.ENEMY
              : GpuBattleStatus.Side.ALLY;
    }

    private static BoardLocation position(InGameObject object) {
        return object instanceof SBFFormation formation ? formation.getPosition()
              : object instanceof SBFUnitPlaceHolder placeHolder ? placeHolder.getPosition() : null;
    }

    private static String contactName(InGameObject object) {
        return object instanceof SBFSomethingOutThereUnitPlaceHolder typed ? typed.getType().toString()
              : Messages.getString("BoardView1.sensorReturn");
    }

    private float level(BoardLocation location) {
        Board board = game.getBoard(location.boardId());
        return board == null || board.getHex(location.coords()) == null ? 0
              : board.getHex(location.coords()).getLevel();
    }

    /** The counter of {@code type} for {@code owner}, drawn once per look. */
    private BoardScene.Pixels counter(String type, Player owner) {
        Color colour = owner == null ? Color.GRAY : owner.getColour().getColour();
        return counters.computeIfAbsent(new Counter(type, owner == null ? null : owner.getCamouflage(),
              colour.getRGB()), GpuSbfCapture::draw);
    }

    /** A counter with the camouflage showing: a plate rimmed in the owner's colour. */
    private static BoardScene.Pixels draw(Counter look) {
        Image camouflage = look.camouflage() == null ? null : look.camouflage().getImage();
        Color colour = new Color(look.rgb(), true);
        var image = new BufferedImage(HexTileset.HEX_W, HexTileset.HEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graph = image.createGraphics();
        UIUtil.setHighQualityRendering(graph);
        Shape plate = new RoundRectangle2D.Float(INSET, INSET, HexTileset.HEX_W - 2 * INSET,
              HexTileset.HEX_H - 2 * INSET, 2 * INSET, 2 * INSET);
        graph.setColor(colour);
        graph.fill(plate);
        if (camouflage != null) {
            graph.setClip(plate);
            graph.drawImage(camouflage, INSET, INSET, HexTileset.HEX_W - 2 * INSET, HexTileset.HEX_H - 2 * INSET, null);
            graph.setClip(null);
        }
        graph.setColor(colour);
        graph.setStroke(new BasicStroke(RIM));
        graph.draw(plate);
        new StringDrawer(look.type()).at(HexTileset.HEX_W / 2, HexTileset.HEX_H / 2).absoluteCenter()
              .color(Color.WHITE).outline(Color.BLACK, 2).fontSize(22).draw(graph);
        graph.dispose();
        return new BoardScene.Pixels(image);
    }
}
