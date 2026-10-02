/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.List;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.actions.KickAttackAction;
import megamek.common.actions.PhysicalAttackPsr;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.PilotingRollData;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The piloting skill roll a kick causes, as the server resolves it at the end of the physical phase: the kicker's after
 * a miss and the target's after a hit. A preview made before the attack from the unit's base piloting roll and the
 * shared kick PSR gives the same target number. Two Atlases stand face to face.
 */
class KickPsrTest {
    private static final String ATLAS = "testresources/megamek/common/units/Atlas AS7-D.mtf";
    private static final int PSR_REPORT = 2299;
    private static final Pattern TARGET = Pattern.compile("needs <a href='#tooltip:([^']*)'>([^<]*)</a>");

    private RulesManager previousRules;
    private TWGameManager manager;
    private Game game;
    private Entity kicker;
    private Entity target;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() throws Exception {
        previousRules = Game.rulesManager;
        manager = spy(new TWGameManager());
        doNothing().when(manager).send(any(Packet.class));
        doNothing().when(manager).send(anyInt(), any(Packet.class));
        doNothing().when(manager).sendServerChat(anyString());
        game = manager.getGame();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        for (int id = 0; id < 2; id++) {
            Player player = new Player(id, "Player " + id);
            player.setTeam(id + 1);
            game.addPlayer(id, player);
        }
        game.setBoard(ClearBoard.of(16, 17));
        game.setPhase(GamePhase.PHYSICAL);
        kicker = atlas(1, 0, new Coords(7, 12), 0, "Mara Voss", 5);
        target = atlas(2, 1, new Coords(7, 11), 3, "Ilya Brandt", 4);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = previousRules;
        Compute.setRNG(MMRandom.R_DEFAULT);
    }

    /** Kick to-hit 3 (piloting 5, kick -2); a roll of 2 misses. */
    @Test
    void aMissedKickCostsTheKickerAPsrAtHisPilotingSkill() {
        Compute.setRNG(new FixedDice(1));
        PilotingRollData preview = kicker.getBasePilotingRoll();
        preview.append(PhysicalAttackPsr.missedKick(kicker));

        List<String> targets = resolvedPsrs(kicker);

        assertEquals(List.of("5|5 (Base piloting skill) + 0 (missed a kick)"), targets);
        assertEquals("5|5 (Base piloting skill) + 0 (missed a kick)", preview.getValue() + "|" + preview.getDesc(),
              "preview before the attack");
        assertFalse(PhysicalAttackPsr.isControlRoll(kicker));
    }

    @Test
    void aKickThatHitsCostsTheTargetAPsrAtHerPilotingSkill() {
        Compute.setRNG(new FixedDice(6));

        List<String> targets = resolvedPsrs(target);

        assertEquals(List.of("4|4 (Base piloting skill) + 0 (was kicked)"), targets);
    }

    /** TacOps physical attack PSR: an assault target gets -2. */
    @Test
    void theTacOpsOptionAddsTheTargetWeightClassModifier() {
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_PHYSICAL_PSR).setValue(true);
        Compute.setRNG(new FixedDice(6));
        PilotingRollData preview = target.getBasePilotingRoll();
        preview.append(PhysicalAttackPsr.kickOrPush(game, target, target, "was kicked"));

        List<String> targets = resolvedPsrs(target);

        assertEquals(List.of("2|4 (Base piloting skill) + 0 (was kicked) - 2 (weight class modifier -2)"), targets);
        assertEquals("2|4 (Base piloting skill) + 0 (was kicked) - 2 (weight class modifier -2)",
              preview.getValue() + "|" + preview.getDesc(), "preview before the attack");
    }

    /** Declares the right-leg kick, resolves the phase and returns "value|description" of each PSR of the unit. */
    private List<String> resolvedPsrs(Entity unit) {
        game.addAction(new KickAttackAction(kicker.getId(), target.getTargetType(), target.getId(),
              KickAttackAction.RIGHT));
        manager.resolvePhysicalAttacks();
        Vector<Report> reports = manager.resolvePilotingRolls();
        return reports.stream().filter(report -> (report.messageId == PSR_REPORT) && (report.subject == unit.getId()))
              .map(report -> {
                  Matcher matcher = TARGET.matcher(report.text());
                  if (!matcher.find()) {
                      throw new AssertionError("no target number in " + report.text());
                  }
                  return matcher.group(2) + '|' + matcher.group(1);
              }).toList();
    }

    private Entity atlas(int id, int owner, Coords position, int facing, String pilot, int piloting)
          throws Exception {
        Entity unit = new MekFileParser(new File(ATLAS)).getEntity();
        unit.setId(id);
        unit.setOwner(game.getPlayer(owner));
        unit.getCrew().setName(pilot, 0);
        unit.getCrew().setPiloting(piloting, 0);
        game.addEntity(unit);
        unit.setPosition(position);
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);
        unit.setDeployed(true);
        return unit;
    }

    /** Every die shows the same face. */
    private static final class FixedDice extends MMRandom {
        private final int face;

        private FixedDice(int face) {
            this.face = face;
        }

        @Override
        public int randomInt(int maxValue) {
            return Math.min(face, maxValue) - 1;
        }

        @Override
        public float randomFloat() {
            return (face - 1) / 6f;
        }
    }
}
