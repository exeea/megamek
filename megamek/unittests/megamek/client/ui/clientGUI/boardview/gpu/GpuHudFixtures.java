/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.IArmorState;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;

/**
 * The hud-v3 mock's battle as HUD snapshots: its 5 v 5 roster (rules.js templates, shots 02 and 12), one enemy of
 * which, the Archer, the local side sees only as a sensor contact, in round 3 of the movement phase with the Atlas
 * acting. Sprites come from the staged data/images/units. These are display values copied from the mock's pictures;
 * nothing here is a rule.
 */
final class GpuHudFixtures {
    static final int LOCAL_PLAYER = 1;
    static final int ENEMY_PLAYER = 2;
    static final int ATLAS = 1;
    /** The enemy Archer, shown only as a sensor contact (shot 12: "Unidentified", hex 1004). */
    static final int CONTACT = 9;

    /** One unit of the mock's roster; GpuParityBattle builds the real units of the parity battle from it. */
    record Roster(int id, GpuBattleStatus.Side side, String chassis, String model, String sprite, int tons,
          String weightClass, int weightClassIndex, String formation, String pilot, int gunnery, int piloting,
          double armor, int heat, int sinks, int walk, int run, int jump, Coords position, int facing) { }

    private static final String ALPHA = "Alpha lance";
    private static final String BRAVO = "Bravo lance";
    private static final String OPPOSITION = "Opposition lance 1";
    static final List<Roster> ROSTER = List.of(
          new Roster(1, OWN, "Atlas", "AS7-D", "Atlas", 100, "Assault", EntityWeightClass.WEIGHT_ASSAULT, ALPHA,
                "Cpt. R. Hayes", 3, 4, .91, 0, 20, 3, 5, 0, new Coords(14, 13), 0),
          new Roster(2, OWN, "Warhammer", "WHM-6R", "Warhammer", 70, "Heavy", EntityWeightClass.WEIGHT_HEAVY, ALPHA,
                "Lt. M. Okafor", 4, 5, .88, 4, 18, 4, 6, 0, new Coords(18, 12), 0),
          new Roster(3, OWN, "Marauder", "MAD-3R", "Marauder", 75, "Heavy", EntityWeightClass.WEIGHT_HEAVY, ALPHA,
                "Sgt. J. Vance", 4, 5, 1, 2, 16, 4, 6, 0, new Coords(15, 14), 0),
          new Roster(4, OWN, "Panther", "PNT-9R", "Panther", 35, "Light", EntityWeightClass.WEIGHT_LIGHT, BRAVO,
                "Sgt. L. Moreau", 4, 5, .96, 3, 13, 4, 6, 4, new Coords(16, 13), 0),
          new Roster(5, OWN, "Locust", "LCT-1V", "Locust", 20, "Light", EntityWeightClass.WEIGHT_LIGHT, BRAVO,
                "MW A. Petrov", 4, 5, 1, 0, 10, 8, 12, 0, new Coords(22, 14), 0),
          new Roster(6, ENEMY, "Timber Wolf", "Prime", "MadCat", 75, "Heavy", EntityWeightClass.WEIGHT_HEAVY,
                OPPOSITION, "MW Kael", 3, 4, .92, 4, 34, 5, 8, 0, new Coords(14, 4), 3),
          new Roster(7, ENEMY, "King Crab", "KGC-000", "KingCrab", 100, "Assault", EntityWeightClass.WEIGHT_ASSAULT,
                OPPOSITION, "Sgt. V. Kross", 4, 5, .95, 0, 15, 2, 3, 0, new Coords(12, 3), 3),
          new Roster(8, ENEMY, "BattleMaster", "BLR-1G", "BattleMaster", 85, "Assault",
                EntityWeightClass.WEIGHT_ASSAULT, OPPOSITION, "Lt. H. Brandt", 4, 5, 1, 0, 18, 4, 6, 0,
                new Coords(18, 2), 3),
          new Roster(CONTACT, ENEMY, "Archer", "ARC-2K", "Archer", 70, "Heavy", EntityWeightClass.WEIGHT_HEAVY,
                OPPOSITION, "MW S. Iga", 4, 5, 1, 6, 10, 4, 6, 0, new Coords(9, 3), 3),
          new Roster(10, ENEMY, "Locust", "LCT-1M", "Locust", 20, "Light", EntityWeightClass.WEIGHT_LIGHT, OPPOSITION,
                "MW B. Tal", 4, 5, 1, 2, 10, 8, 12, 0, new Coords(23, 6), 4));

    private GpuHudFixtures() { }

    /**
     * The status the HUD presents: every roster unit pending, the Atlas able to act now, the Archer anonymous. The
     * five turns of each side alternate, the local side first, as in shots 01 and 02 (Princess won the initiative).
     */
    static GpuBattleStatus.Snapshot status() {
        List<GpuBattleStatus.UnitStatus> units = new ArrayList<>();
        for (Roster unit : ROSTER) {
            units.add(unit.id() == CONTACT ? contact(unit) : status(unit));
        }
        List<GpuBattleStatus.Slot> turns = new ArrayList<>();
        for (int turn = 0; turn < ROSTER.size(); turn++) {
            boolean own = turn % 2 == 0;
            turns.add(new GpuBattleStatus.Slot(own ? LOCAL_PLAYER : ENEMY_PLAYER, own ? "Player" : "Princess",
                  (own ? PlayerColour.BLUE : PlayerColour.RED).getColour().getRGB(), own ? OWN : ENEMY, Entity.NONE));
        }
        return new GpuBattleStatus.Snapshot(3, GamePhase.MOVEMENT, true, LOCAL_PLAYER, ATLAS, turns, 0, units,
              List.of(), false);
    }

    /** The roster on the board, facing as in the mock; {@code level} gives each hex's ground level. */
    static List<BoardScene.Unit> units(ToIntFunction<Coords> level) {
        List<BoardScene.Unit> units = new ArrayList<>();
        for (Roster unit : ROSTER) {
            boolean contact = unit.id() == CONTACT;
            BoardScene.Waypoint location = new BoardScene.Waypoint(unit.position(),
                  level.applyAsInt(unit.position()), contact ? 0 : unit.facing());
            units.add(new BoardScene.Unit(unit.id(), -1,
                  contact ? Messages.getString("BoardView1.sensorReturn") : unit.chassis() + " " + unit.model(),
                  location, contact ? blip() : sprite(unit.sprite()), contact, null, contact ? 1 : 2, false));
        }
        return units;
    }

    /** A classic unit sprite, data/images/units/meks/{name}.png. */
    static BoardScene.Pixels sprite(String name) {
        return pixels(new File(new File(Configuration.unitImagesDir(), "meks"), name + ".png"));
    }

    /** The radar blip that stands for a sensor contact, as BoardView loads it. */
    static BoardScene.Pixels blip() {
        return pixels(new File(Configuration.miscImagesDir(), "radarBlip.png"));
    }

    private static BoardScene.Pixels pixels(File file) {
        try {
            return new BoardScene.Pixels(ImageIO.read(file));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static GpuBattleStatus.UnitStatus status(Roster unit) {
        return new GpuBattleStatus.UnitStatus(unit.id(), unit.side(), false, unit.chassis() + " " + unit.model(),
              unit.chassis(), unit.model(), unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(),
              unit.gunnery(), unit.piloting(), unit.armor(), 1, unit.heat(),
              GUIPreferences.getInstance().getColorForHeat(unit.heat()).getRGB(), String.valueOf(unit.sinks()),
              unit.walk(), String.valueOf(unit.run()), unit.jump(), "", 0, 0, unit.facing(), 0, unit.id() == ATLAS,
              true, false, false, Entity.DMG_NONE, List.of(), "", unit.position(), 0, sprite(unit.sprite()),
              List.of(), unit.weightClassIndex(), 0, Player.PLAYER_NONE, List.of());
    }

    /** A sensor contact carries only its id, side, position, board and blip, as GpuBattleStatus captures one. */
    private static GpuBattleStatus.UnitStatus contact(Roster unit) {
        return new GpuBattleStatus.UnitStatus(unit.id(), unit.side(), true, "", "", "", 0, "", "", "", 0, 0,
              IArmorState.ARMOR_NA, IArmorState.ARMOR_NA, 0, 0, "", 0, "", 0, "", 0, 0, -1, 0, false, false, false,
              false, Entity.DMG_NONE, List.of(), "", unit.position(), 0, blip(), List.of(), 0, 0, Player.PLAYER_NONE,
              List.of());
    }
}
