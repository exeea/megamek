/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import megamek.common.CriticalSlot;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.Engine;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Mek;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How many engine hits destroy a Mek at each of the server's three engine checks: a critical hit, a hull breach and a
 * damage edit. Three for a standard engine, two for a superheavy Mek's compact engine (recorded before U1 moved the
 * threshold into {@code Mek}).
 */
class EngineDestructionThresholdTest {
    private static final String ATLAS = "testresources/megamek/common/units/Atlas AS7-D.mtf";
    private static final File ZIP = new File("data/mekfiles/unit_files.zip");
    private static final String OMEGA = "meks/3145/NTNU RS/NTNU/Omega SHP-5R.mtf";

    private RulesManager previousRules;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void keepRules() {
        previousRules = Game.rulesManager;
    }

    @AfterEach
    void restoreRules() {
        Game.rulesManager = previousRules;
    }

    @Test
    void aCriticalHitDestroysTheEngineAtTheThreshold() throws Exception {
        List<Boolean> destroyed = new ArrayList<>();
        for (Mek mek : List.of(atlas(), compactOmega())) {
            TWGameManager manager = manager(mek);
            doReturn(false).when(manager).checkEngineExplosion(any(), any(), anyInt());
            for (CriticalSlot slot : engineSlots(mek).subList(0, 3)) {
                manager.applyCriticalHit(mek, Mek.LOC_CENTER_TORSO, slot, true, 0, false);
                destroyed.add(mek.isDoomed() || mek.isDestroyed());
            }
        }
        assertEquals(List.of(false, false, true, false, true, true), destroyed);
    }

    @Test
    void aHullBreachDestroysTheEngineAtTheThreshold() throws Exception {
        List<Boolean> destroyed = new ArrayList<>();
        for (Mek mek : List.of(atlas(), compactOmega())) {
            TWGameManager manager = manager(mek);
            manager.getGame().initializeRulesManager(OptionsConstants.RULES_TW);
            mek.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_ENGINE, Mek.LOC_CENTER_TORSO, 2);
            mek.setLocationStatus(Mek.LOC_RIGHT_ARM, ILocationExposureStatus.VACUUM);
            mek.setArmor(0, Mek.LOC_RIGHT_ARM);
            manager.breachCheck(mek, Mek.LOC_RIGHT_ARM, null);
            destroyed.add(mek.isDoomed() || mek.isDestroyed());
        }
        // Two engine hits, then a breached arm: only the compact superheavy engine is lost
        assertEquals(List.of(false, true), destroyed);
    }

    @Test
    void aDamageEditDestroysTheEngineAtTheThreshold() throws Exception {
        List<String> destructions = new ArrayList<>();
        for (Mek mek : List.of(atlas(), compactOmega())) {
            Game game = new Game();
            Player owner = new Player(0, "Owner");
            game.addPlayer(0, owner);
            mek.setId(5);
            mek.setOwner(owner);
            game.addEntity(mek);
            game.setPhase(GamePhase.MOVEMENT);
            TWGameManager manager = mock(TWGameManager.class);
            doCallRealMethod().when(manager).setGame(any());
            doCallRealMethod().when(manager).handlePacket(anyInt(), any());
            manager.setGame(game);
            mek.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_ENGINE, Mek.LOC_CENTER_TORSO, 2);
            manager.handlePacket(0, new Packet(PacketCommand.ENTITY_UPDATE, mek));
            destructions.add(mockingDetails(manager).getInvocations().stream()
                  .filter(call -> call.getMethod().getName().equals("destroyEntity"))
                  .map(call -> String.valueOf((Object) call.getArgument(1))).toList().toString());
        }
        assertEquals(List.of("[]", "[engine destruction]"), destructions);
    }

    /** A spied server for a game with the Mek's owner, in the firing phase. */
    private static TWGameManager manager(Mek mek) {
        TWGameManager manager = spy(new TWGameManager());
        doNothing().when(manager).send(any(Packet.class));
        doNothing().when(manager).sendServerChat(anyString());
        Game game = manager.getGame();
        Player owner = new Player(0, "Owner");
        game.addPlayer(0, owner);
        game.setPhase(GamePhase.FIRING);
        mek.setId(1);
        mek.setOwner(owner);
        game.addEntity(mek);
        return manager;
    }

    private static List<CriticalSlot> engineSlots(Mek mek) {
        List<CriticalSlot> slots = new ArrayList<>();
        for (int slot = 0; slot < mek.getNumberOfCriticalSlots(Mek.LOC_CENTER_TORSO); slot++) {
            CriticalSlot critical = mek.getCritical(Mek.LOC_CENTER_TORSO, slot);
            if ((critical != null) && (critical.getType() == CriticalSlot.TYPE_SYSTEM)
                  && (critical.getIndex() == Mek.SYSTEM_ENGINE)) {
                slots.add(critical);
            }
        }
        return slots;
    }

    private static Mek atlas() throws Exception {
        return (Mek) new MekFileParser(new File(ATLAS)).getEntity();
    }

    /** A superheavy Mek given a compact engine; its slots stay as loaded, only the engine type counts here. */
    private static Mek compactOmega() throws Exception {
        Mek omega = (Mek) new MekFileParser(ZIP, OMEGA).getEntity();
        omega.setEngine(new Engine(300, Engine.COMPACT_ENGINE, Engine.SUPERHEAVY_ENGINE));
        return omega;
    }
}
