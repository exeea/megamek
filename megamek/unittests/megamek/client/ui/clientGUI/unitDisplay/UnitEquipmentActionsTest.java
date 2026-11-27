/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.unitDisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.INarcPod;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.NarcPod;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the unit's equipment actions send and what its conditions read, for a real unit. */
class UnitEquipmentActionsTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static File originalDataDir;

    private final Game game = new Game();
    private final Player local = new Player(0, "Local");
    private final Player enemy = new Player(1, "Enemy");
    private final Client client = mock(Client.class);
    private final ClientGUI gui = mock(ClientGUI.class);
    private final RulesManager rules = Game.rulesManager;
    private Entity atlas;

    /** Earlier test classes can leave the data folder on testresources; the board is in the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    @BeforeEach
    void setUp() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        game.setBoard(board);
        game.setRoundCount(1);
        enemy.setTeam(2);
        game.addPlayer(local.getId(), local);
        game.addPlayer(enemy.getId(), enemy);
        atlas = unit(1, local);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(gui.getClient()).thenReturn(client);
        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = rules;
    }

    @Test
    void aVibrobladeIsSwitchedOnlyInThePhysicalPhase() throws Exception {
        Mounted<?> blade = atlas.addEquipment(EquipmentType.get("ISMediumVibroblade"), Mek.LOC_RIGHT_ARM);
        int bladeNum = atlas.getEquipmentNum(blade);
        game.setPhase(GamePhase.MOVEMENT);
        UnitEquipmentActions.changeMode(gui, atlas, blade, 1);
        verify(client, never()).sendModeChange(anyInt(), anyInt(), anyInt());
        verify(gui).systemMessage(Messages.getString("MekDisplay.VibrobladeModePhase"));

        game.setPhase(GamePhase.PHYSICAL);
        UnitEquipmentActions.changeMode(gui, atlas, blade, 1);
        verify(client).sendModeChange(1, bladeNum, 1);
        assertEquals("Active", blade.curMode().getName());
    }

    @Test
    void ammoIsDumpedAndTheDumpCancelledOnlyAfterTheOwnerConfirms() throws Exception {
        game.initializeRulesManager(OptionsConstants.RULES_TW);
        Mounted<?> ammo = atlas.getCritical(Mek.LOC_RIGHT_TORSO, 10).getMount();
        int ammoNum = atlas.getEquipmentNum(ammo);
        game.setPhase(GamePhase.FIRING);
        UnitEquipmentActions.toggleDump(gui, atlas, ammo);
        verify(gui).doYesNoDialog(Messages.getString("MekDisplay.Dump.title"),
              Messages.getString("MekDisplay.Dump.message", ammo.getName()));
        verify(client).sendModeChange(1, ammoNum, -1);
        assertTrue(ammo.isPendingDump());

        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(false);
        UnitEquipmentActions.toggleDump(gui, atlas, ammo);
        verify(client, never()).sendModeChange(1, ammoNum, 0);
        assertTrue(ammo.isPendingDump(), "Answering no keeps the dump");

        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
        UnitEquipmentActions.toggleDump(gui, atlas, ammo);
        verify(gui, times(2)).doYesNoDialog(Messages.getString("MekDisplay.CancelDumping.title"),
              Messages.getString("MekDisplay.CancelDumping.message", ammo.getName()));
        verify(client).sendModeChange(1, ammoNum, 0);
        assertFalse(ammo.isPendingDump());
    }

    @Test
    void aSensorChoiceSendsTheSensorChange() throws Exception {
        game.setPhase(GamePhase.MOVEMENT);
        UnitDisplayData.setNextSensor(gui, atlas, 1);
        verify(client).sendSensorChange(1, 1);
        assertEquals(atlas.getSensors().get(1), atlas.getNextSensor());
        verify(gui).systemMessage("Active Sensors will switch to Mek IR at end of turn.");
    }

    @Test
    void conditionsAndCarriedItemsListWhatAffectsTheUnitAndWhatItCarries() throws Exception {
        atlas.attachNarcPod(new NarcPod(enemy.getTeam(), Mek.LOC_CENTER_TORSO));
        atlas.attachINarcPod(new INarcPod(enemy.getTeam(), INarcPod.HOMING, Mek.LOC_CENTER_TORSO));
        atlas.attachINarcPod(new INarcPod(enemy.getTeam(), INarcPod.HAYWIRE, Mek.LOC_CENTER_TORSO));
        atlas.newRound(1);
        atlas.getWeaponList().getFirst().setJammedImmediately(true);
        atlas.setLocationStatus(Mek.LOC_LEFT_LEG, ILocationExposureStatus.BREACHED);
        atlas.setTaserFeedback(2);
        atlas.addEquipment(EquipmentType.get("ISMediumVibroblade"), Mek.LOC_RIGHT_ARM);
        atlas.setExternalSearchlight(true);
        assertEquals(List.of("NARCed by Team of Enemy [Team 2]",
              "iNarc Homing Pod from Team of Enemy [Team 2] attached.", "iNarc Haywire Pod attached.",
              "2 rounds of +1 to-Hit due to Taser Feedback", "Medium Laser is Jammed", "Left Leg Breached"),
              UnitDisplayData.affectedBy(game, atlas));
        assertEquals(List.of("Vibroblade (Medium)"), UnitDisplayData.carried(game, atlas));
        assertEquals("Searchlight (off)", UnitDisplayData.searchlightText(atlas));
    }

    private Entity unit(int id, Player owner) throws Exception {
        Entity entity = new MekFileParser(new File(UNITS + "Atlas AS7-D.mtf")).getEntity();
        entity.setId(id);
        entity.setOwner(owner);
        entity.setPosition(new Coords(5, 4 + id));
        entity.setDeployed(true);
        game.addEntity(entity, false);
        return entity;
    }
}
