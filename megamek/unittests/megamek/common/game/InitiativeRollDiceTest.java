/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.game;

import static megamek.common.options.OptionsConstants.ATOW_COMBAT_PARALYSIS;
import static megamek.common.options.OptionsConstants.ATOW_COMBAT_SENSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.regex.Pattern;

import megamek.common.MMRandom;
import megamek.common.compute.Compute;
import megamek.common.net.marshalling.SanityInputFilter;
import megamek.common.util.SerializationHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Initiative rolls with scripted dice: the report text and totals, and the kept dice of each roll. */
class InitiativeRollDiceTest {

    @AfterEach
    void restoreDice() {
        Compute.setRNG(MMRandom.R_DEFAULT);
    }

    @Test
    void aPlainRollWithABonus() {
        Compute.setRNG(new ScriptedDice(3, 5));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.fromTotal(2), "");

        assertEquals("10[8+2 (+2 Base)]", roll.toString());
        assertEquals(10, roll.getRoll(0));
        assertEquals(List.of(3, 5), roll.getKeptDice(0));
    }

    @Test
    void aTieBreakAddsASecondRoll() {
        Compute.setRNG(new ScriptedDice(6, 1, 2, 2));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");

        assertEquals("7[7+0] / 4[4+0]", roll.toString());
        assertEquals(List.of(6, 1), roll.getKeptDice(0));
        assertEquals(List.of(2, 2), roll.getKeptDice(1));
    }

    @Test
    void tacticalGeniusReplacesTheLastRoll() {
        Compute.setRNG(new ScriptedDice(1, 2, 6, 4));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");
        roll.replaceRoll(InitiativeBonusBreakdown.zero(), "");

        assertEquals("3[3+0](10[10+0]) (Tactical Genius ability used)", roll.toString());
        assertEquals(10, roll.getRoll(0));
        assertEquals(List.of(6, 4), roll.getKeptDice(0));
    }

    @Test
    void combatSenseKeepsTheHighestTwoOfThreeDice() {
        // The plain 2d6 is still rolled first and discarded.
        Compute.setRNG(new ScriptedDice(1, 1, 2, 6, 4));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), ATOW_COMBAT_SENSE);

        assertEquals("10[10+0]", roll.toString());
        assertEquals(List.of(6, 4), roll.getKeptDice(0));
    }

    @Test
    void combatParalysisKeepsTheLowestTwoOfThreeDice() {
        Compute.setRNG(new ScriptedDice(6, 6, 2, 6, 4));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), ATOW_COMBAT_PARALYSIS);

        assertEquals("6[6+0]", roll.toString());
        assertEquals(List.of(2, 4), roll.getKeptDice(0));
    }

    @Test
    void anObserverRollsNothing() {
        Compute.setRNG(new ScriptedDice());
        InitiativeRoll roll = new InitiativeRoll();
        roll.observerRoll();

        assertEquals("-1[-1+0]", roll.toString());
        assertEquals(List.of(), roll.getKeptDice(0));
    }

    @Test
    void clearingForgetsTheDice() {
        Compute.setRNG(new ScriptedDice(3, 5, 1, 1));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");
        InitiativeRoll copy = new InitiativeRoll(roll);
        roll.clear();
        roll.observerRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");

        assertEquals(List.of(), roll.getKeptDice(0));
        assertEquals(List.of(1, 1), roll.getKeptDice(1));
        assertEquals(List.of(3, 5), copy.getKeptDice(0), "a copy keeps its own dice");
    }

    /** The network sends the roll with Java serialization, read through the same input filter. */
    @Test
    void theDiceSurviveTheNetwork() throws Exception {
        InitiativeRoll roll = scriptedRoll();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(roll);
        }
        InitiativeRoll received;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            in.setObjectInputFilter(new SanityInputFilter());
            received = (InitiativeRoll) in.readObject();
        }

        assertEquals("7[7+0] / 4[4+0]", received.toString());
        assertEquals(List.of(6, 1), received.getKeptDice(0));
        assertEquals(List.of(2, 2), received.getKeptDice(1));
    }

    @Test
    void theDiceSurviveASaveGame() {
        String xml = SerializationHelper.getSaveGameXStream().toXML(scriptedRoll());
        InitiativeRoll loaded = (InitiativeRoll) SerializationHelper.getLoadSaveGameXStream().fromXML(xml);

        assertEquals("7[7+0] / 4[4+0]", loaded.toString());
        assertEquals(List.of(6, 1), loaded.getKeptDice(0));
        assertEquals(List.of(2, 2), loaded.getKeptDice(1));
    }

    /** A save written before the dice were kept has no such element; its rolls load without dice and roll on. */
    @Test
    void aSaveWithoutTheDiceLoadsWithoutDice() {
        String xml = SerializationHelper.getSaveGameXStream().toXML(scriptedRoll());
        String oldXml = Pattern.compile("<keptDice[^>]*>.*?</keptDice>", Pattern.DOTALL).matcher(xml).replaceAll("");
        assertNotEquals(xml, oldXml);
        InitiativeRoll loaded = (InitiativeRoll) SerializationHelper.getLoadSaveGameXStream().fromXML(oldXml);

        assertEquals("7[7+0] / 4[4+0]", loaded.toString());
        assertEquals(List.of(), loaded.getKeptDice(0));
        assertEquals(List.of(), loaded.getKeptDice(1));

        Compute.setRNG(new ScriptedDice(5, 5));
        loaded.replaceRoll(InitiativeBonusBreakdown.zero(), "");
        assertEquals(List.of(), loaded.getKeptDice(0));
        assertEquals(List.of(5, 5), loaded.getKeptDice(1));
        assertEquals(List.of(), new InitiativeRoll(loaded).getKeptDice(0));
    }

    /** Two rolls: 6 + 1, then 2 + 2. */
    private static InitiativeRoll scriptedRoll() {
        Compute.setRNG(new ScriptedDice(6, 1, 2, 2));
        InitiativeRoll roll = new InitiativeRoll();
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");
        roll.addRoll(InitiativeBonusBreakdown.zero(), "");
        return roll;
    }

    /** Deals the given die faces in order; any further die fails the test. */
    private static final class ScriptedDice extends MMRandom {
        private final int[] faces;
        private int next;

        private ScriptedDice(int... faces) {
            this.faces = faces;
        }

        @Override
        public int randomInt(int maxValue) {
            return faces[next++] - 1;
        }

        @Override
        public float randomFloat() {
            throw new UnsupportedOperationException("no float expected");
        }
    }
}
