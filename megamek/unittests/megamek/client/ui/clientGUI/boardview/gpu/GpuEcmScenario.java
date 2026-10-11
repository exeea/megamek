/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.io.File;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Mek;

/**
 * The ECM design's scenario (b11 design section 5, its mock-ups): 32x17 Grasslands E with TacOps ECCM on. Own Atlas
 * with Guardian ECM at 1108, own Crab with Guardian ECM in ECCM mode at 1613, own Archer 0810 and Quickdraw 1305
 * (its ECM off; it stands in the enemy field); enemy Warwolf with Guardian ECM at 1908, enemy Dervish 2211 and
 * Exterminator 2105. Every field has radius 6; the Crab is 7 hexes from the Warwolf, so its ECCM only counters where
 * the fields overlap.
 */
final class GpuEcmScenario implements AutoCloseable {
    static final String BOARD = "data/boards/Grasslands BattleMats/32x17 Grasslands E.board";
    final GpuBoardFixture fixture;
    final Player enemy = new Player(2, "Enemy");
    Entity atlas, crab, archer, quickdraw, warwolf, dervish, exterminator;

    private GpuEcmScenario(GpuBoardFixture fixture) {
        this.fixture = fixture;
    }

    static GpuEcmScenario create() throws Exception {
        Board board = new Board();
        board.load(new File(BOARD));
        GpuEcmScenario scenario = new GpuEcmScenario(GpuBoardFixture.create(board));
        FutureTask<Void> setup = new FutureTask<>(() -> {
            scenario.setup();
            return null;
        });
        SwingUtilities.invokeAndWait(setup);
        setup.get();
        return scenario;
    }

    private void setup() throws Exception {
        var game = fixture.game;
        game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_ECCM).setValue(true);
        enemy.setTeam(2);
        game.addPlayer(enemy.getId(), enemy);
        atlas = place(fixture.entity, 10, 7, 1, "ECM");
        crab = place(unit("Crab CRB-20", fixture.player, 2), 15, 12, 1, "ECCM");
        archer = place(unit("Archer ARC-2R", fixture.player, 3), 7, 9, 1, null);
        quickdraw = place(unit("Quickdraw QKD-8X", fixture.player, 4), 12, 4, 2, null);
        warwolf = place(unit("Warwolf A", enemy, 5), 18, 7, 4, "ECM");
        dervish = place(unit("Dervish DV-11DK", enemy, 6), 21, 10, 5, null);
        exterminator = place(unit("Exterminator EXT-4A", enemy, 7), 20, 4, 4, null);
        fixture.view.updateEcmList();
        fixture.source.setVisibleArea(new Rectangle(0, 0, game.getBoard().getWidth(), game.getBoard().getHeight()));
        fixture.source.refresh();
    }

    private Entity unit(String file, Player owner, int id) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file + ".mtf")).getEntity();
        entity.setId(id);
        entity.setOwner(owner);
        entity.setDeployed(true);
        return entity;
    }

    /** Places the unit; with a mode it gets a Guardian suite in that mode, else its own ECM is switched off. */
    private Entity place(Entity entity, int x, int y, int facing, String mode) throws Exception {
        entity.setPosition(new Coords(x, y));
        entity.setFacing(facing);
        entity.setSecondaryFacing(facing);
        if (entity != fixture.entity) { fixture.game.addEntity(entity, false); }
        entity.setGameOptions();
        if (mode != null) {
            MiscMounted suite = null;
            for (int location : new int[] { Mek.LOC_LEFT_ARM, Mek.LOC_RIGHT_ARM, Mek.LOC_LEFT_TORSO,
                  Mek.LOC_RIGHT_TORSO, Mek.LOC_CENTER_TORSO, Mek.LOC_LEFT_LEG, Mek.LOC_RIGHT_LEG }) {
                try {
                    suite = (MiscMounted) entity.addEquipment(EquipmentType.get("ISGuardianECMSuite"), location);
                    break;
                } catch (Exception full) {
                    // the next location
                }
            }
            if (suite == null) { throw new IllegalStateException("No room for ECM on " + entity.getShortName()); }
            entity.setGameOptions();
            suite.setModeImmediately(mode);
        } else {
            for (MiscMounted misc : entity.getMisc()) {
                if (misc.getType().hasFlag(MiscType.F_ECM)) { misc.setModeImmediately("Off"); }
            }
        }
        return entity;
    }

    @Override
    public void close() throws Exception {
        fixture.close();
    }
}
