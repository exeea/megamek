/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.Test;

/** Which landings draw an artillery blast, which shape they take, and how long the blasts live. */
class GpuArtilleryBlastTest {

    private static ResolvedAttack.Shot shot(boolean artillery, String... munitions) {
        return new ResolvedAttack.Shot("", Set.of(munitions), artillery, false, 1, 0, artillery, null, null, null,
              true, 20, false, false, false, null, 20);
    }

    @Test
    void highExplosiveArtilleryExplodes() {
        assertTrue(GpuArtilleryBlast.explodes(shot(true)));
        assertTrue(GpuArtilleryBlast.explodes(shot(true, "M_STANDARD")));
    }

    @Test
    void directFireWeaponsAndMissingProfilesDoNotExplode() {
        assertFalse(GpuArtilleryBlast.explodes(shot(false)));
        assertFalse(GpuArtilleryBlast.explodes(null));
    }

    @Test
    void minesSmokeAndFlaresKeepTheirOwnPresentation() {
        for (String munition : new String[] { "M_FASCAM", "M_THUNDER", "M_SMOKE", "M_FLARE", "M_LASER_INHIB" }) {
            assertFalse(GpuArtilleryBlast.explodes(shot(true, munition)), munition);
        }
    }

    @Test
    void specialWarheadsChooseTheirShape() {
        assertEquals(GpuArtilleryBlast.Style.MUSHROOM, GpuArtilleryBlast.style(shot(true, "M_DAVY_CROCKETT_M"), 1));
        assertEquals(GpuArtilleryBlast.Style.FIREBALL, GpuArtilleryBlast.style(shot(true, "M_FAE"), 1));
        assertEquals(GpuArtilleryBlast.Style.FIREBALL, GpuArtilleryBlast.style(shot(true, "M_ADA"), 1));
    }

    @Test
    void aBarrageUsesEveryShapeAndTheSameShellAlwaysLooksTheSame() {
        var seen = EnumSet.noneOf(GpuArtilleryBlast.Style.class);
        for (int seed = 0; seed < 64; seed++) {
            var style = GpuArtilleryBlast.style(shot(true), seed);
            assertEquals(style, GpuArtilleryBlast.style(shot(true), seed));
            seen.add(style);
        }
        // CLUSTER belongs to cluster rounds alone, never to a high-explosive shell.
        assertEquals(EnumSet.of(GpuArtilleryBlast.Style.CROWN, GpuArtilleryBlast.Style.MUSHROOM,
              GpuArtilleryBlast.Style.FIREBALL), seen);
    }

    @Test
    void aShellReportedOnEveryFrameOfItsContactStartsOneBlast() {
        var blasts = new GpuArtilleryBlast();
        for (int frame = 0; frame < 10; frame++) {
            blasts.detonate(Vector3.Zero, shot(true), null, 99);
            blasts.advance(.016f);
        }
        assertEquals(1, blasts.size());
        blasts.detonate(Vector3.Zero, shot(true), null, 100);
        assertEquals(2, blasts.size());
    }

    @Test
    void blastsOutliveTheirAttackThenClear() {
        var blasts = new GpuArtilleryBlast();
        blasts.detonate(Vector3.Zero, shot(true), null, 7);
        blasts.advance(1);
        assertEquals(1, blasts.size(), "Still burning a second after contact, long after the attack's own clock");
        blasts.advance(GpuArtilleryBlast.SECONDS);
        assertEquals(0, blasts.size());
    }

    /** The R values of the Artillery Ordnance Table, asked of the same rule that deals the damage. */
    @Test
    void debrisReachesTheRingsTheShellDamages() {
        EquipmentType.initializeTypes();
        assertEquals(2, radius("ISLongTom", shot(true, "M_STANDARD")), "Long Tom 25/15/5 (R2)");
        assertEquals(1, radius("ISSniper", shot(true, "M_STANDARD")), "Sniper 20/10 (R1)");
        assertEquals(1, radius("ISThumper", shot(true, "M_STANDARD")), "Thumper 15/5 (R1)");
        assertEquals(1, radius("ISArrowIV", shot(true, "M_STANDARD")), "Arrow IV 20/10 (R1)");
        assertEquals(1, radius("ISLongTom", shot(true, "M_CLUSTER")), "Long Tom cluster 20/10 (R1)");
        assertEquals(2, radius("ISLongTom", shot(true)), "No munitions captured is standard");
    }

    @Test
    void anUnknownWeaponKeepsTheDebrisInItsHex() {
        EquipmentType.initializeTypes();
        assertEquals(0, radius("NoSuchWeapon", shot(true, "M_STANDARD")));
        assertEquals(0, radius(null, shot(true, "M_STANDARD")));
        assertEquals(0, radius("ISLongTom", null));
    }

    /** Each ring's scorch mark is sized for the damage the table gives it: a Long Tom's 25/15/5. */
    @Test
    void ringMarksTakeTheRingsDamage() {
        EquipmentType.initializeTypes();
        var longTom = GpuArtilleryBlast.areaEffect("ISLongTom", shot(true, "M_STANDARD"));
        assertEquals(25, longTom.ringDamage(0));
        assertEquals(15, longTom.ringDamage(1));
        assertEquals(5, longTom.ringDamage(2));
    }

    /** Every hex of every damaged ring takes one piece: 6 in the first ring and 12 in the second. */
    @Test
    void debrisHitsEveryHexOfEveryRing() {
        var pieces = GpuArtilleryBlast.debrisPieces(2, 41);
        var markers = pieces.stream().filter(GpuArtilleryBlast.Piece::marksGround).toList();
        long firstRing = markers.stream().filter(piece -> piece.ring() == 1).count();
        long secondRing = markers.stream().filter(piece -> piece.ring() == 2).count();
        assertEquals(6, firstRing, "One marking fragment per first-ring hex");
        assertEquals(12, secondRing, "One marking fragment per second-ring hex");
        assertEquals(54, pieces.size(), "A dense spray: three fragments in each of the 18 hexes");
        float hex = BoardGeometry.height();
        for (var piece : pieces) {
            float distance = (float) Math.hypot(piece.offsetX(), piece.offsetY()) / hex;
            // A ring's hexes sit between about 0.87 and 1 times its number of hexes out; a fragment stays within
            // 0.4 of a hex of its hex's centre.
            float nearest = piece.ring() * .86f - .4f;
            float farthest = piece.ring() + .4f;
            assertTrue(distance > nearest && distance < farthest, "A fragment lands inside its ring: " + piece);
        }
        assertEquals(30, GpuArtilleryBlast.debrisPieces(1, 41).size(), "Five fragments in each first-ring hex for R1");
    }

    /** The ring hexes are hit one after another, not all in the same instant. */
    @Test
    void debrisLandsAtDifferentMoments() {
        var pieces = GpuArtilleryBlast.debrisPieces(2, 41);
        var landings = pieces.stream().map(GpuArtilleryBlast.Piece::landing).sorted().toList();
        assertTrue(landings.getLast() - landings.getFirst() > .1f, "The strikes ripple rather than land together");
        assertTrue(landings.getLast() < .5f, "Fragments are fast: every hex is struck within half a second");
        assertEquals(landings.size(), landings.stream().distinct().count(), "No two fragments land together");
        double innerRing = pieces.stream().filter(piece -> piece.ring() == 1)
              .mapToDouble(GpuArtilleryBlast.Piece::landing).average().orElseThrow();
        double outerRing = pieces.stream().filter(piece -> piece.ring() == 2)
              .mapToDouble(GpuArtilleryBlast.Piece::landing).average().orElseThrow();
        assertTrue(innerRing < outerRing, "The ripple runs outward: the inner ring is struck first");
    }

    /** The casing smoke of a cluster round drifts with the board's wind, and stays put in calm air. */
    @Test
    void downwindFollowsTheWeather() {
        var explosions = new GpuExplosionEffects();
        explosions.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 1, 90));
        var drifted = explosions.downwind(new Vector3(), 1);
        assertTrue(drifted.x > BoardGeometry.height() * .5f, "A full eastward wind carries smoke most of a hex east");
        assertEquals(0, drifted.y, .001f);
        explosions.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 90));
        assertEquals(0, explosions.downwind(new Vector3(), 1).len(), .001f, "Calm air leaves it where it is");
    }

    @Test
    void aShellDamagingOnlyItsHexKeepsItsDebrisThere() {
        for (var piece : GpuArtilleryBlast.debrisPieces(0, 41)) {
            assertEquals(0, piece.ring());
            assertTrue(Math.hypot(piece.offsetX(), piece.offsetY()) < BoardGeometry.height() / 2);
        }
    }

    /**
     * A Long Tom landing on flat ground marks all 18 hexes of its two rings, each mark on the ground in its own ring.
     * The landscape reports squared distances, as the board's does; reading them as plain distances buried every mark.
     */
    @Test
    void ringMarksLandOnTheGroundInEveryDamagedHex() {
        EquipmentType.initializeTypes();
        var profile = shot(true, "M_STANDARD");
        var attack = GroundDamagePlaybackTest.shot("ISLongTom", true, profile);
        var targetHex = attack.event.destination().coords();
        var crater = BoardGeometry.center(targetHex, 0);
        var blasts = new GpuArtilleryBlast();
        blasts.detonate(crater, profile, attack, 5);
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        blasts.groundMarks(marks::add);
        assertEquals(0, marks.size(), "Nothing has landed at contact");
        blasts.advance(GpuArtilleryBlast.SECONDS - .1f);
        blasts.groundMarks(marks::add);
        assertEquals(18, marks.size(), "Every hex of both rings is marked");
        Set<Vector3> spots = new HashSet<>();
        float hex = BoardGeometry.height();
        for (var mark : marks) {
            assertEquals(0, mark.point().z, .01f, "The mark lies on the ground: " + mark.point());
            float rings = mark.point().dst(crater) / hex;
            assertTrue(rings > .5f && rings < 2.5f, "The mark lies in a damaged ring: " + rings);
            assertTrue(mark.shot().damagePerHit() == 15 || mark.shot().damagePerHit() == 5, "Sized for its ring");
            spots.add(mark.point());
        }
        assertEquals(18, spots.size(), "No two marks share a spot");
        blasts.groundMarks(marks::add);
        assertEquals(18, marks.size(), "Each mark is handed over once");
    }

    /** All cluster munitions are R1, and the rule that deals their damage says so; high explosive is not cluster. */
    @Test
    void clusterRoundsAreRecognisedByTheDamageRule() {
        EquipmentType.initializeTypes();
        var longTomCluster = GpuArtilleryBlast.areaEffect("ISLongTom", shot(true, "M_CLUSTER"));
        assertTrue(longTomCluster.cluster());
        assertEquals(1, longTomCluster.hexRadius());
        assertEquals(20, longTomCluster.ringDamage(0), "Long Tom cluster 20/10");
        assertEquals(10, longTomCluster.ringDamage(1));
        assertTrue(GpuArtilleryBlast.scattersBomblets("ISLongTom", shot(true, "M_CLUSTER")));
        assertFalse(GpuArtilleryBlast.scattersBomblets("ISLongTom", shot(true, "M_STANDARD")));
    }

    /** A Long Tom cluster round scatters eight bomblets in its target hex and 27 over the six around it. */
    @Test
    void bombletsCoverTheTargetHexAndItsRingMoreDenselyWhereDamageIsHigher() {
        EquipmentType.initializeTypes();
        var area = GpuArtilleryBlast.areaEffect("ISLongTom", shot(true, "M_CLUSTER"));
        var bomblets = GpuArtilleryBlast.bomblets(area, 41);
        long inTargetHex = bomblets.stream().filter(bomblet -> bomblet.ring() == 0).count();
        long inRing = bomblets.stream().filter(bomblet -> bomblet.ring() == 1).count();
        assertEquals(8, inTargetHex);
        assertEquals(27, inRing, "4.5 a hex: three ring hexes get five and three get four");
        var landings = bomblets.stream().map(GpuArtilleryBlast.Piece::landing).sorted().toList();
        assertTrue(landings.getLast() - landings.getFirst() > .3f, "The pops ripple rather than going off together");
    }

    /** A cluster round leaves no crater, but every bomblet marks the ground, the target hex included. */
    @Test
    void aClusterRoundFrecklesTheGroundInsteadOfLeavingACrater() {
        EquipmentType.initializeTypes();
        var cluster = shot(true, "M_CLUSTER");
        var standard = shot(true, "M_STANDARD");
        assertEquals(0, craters("ISLongTom", cluster), "No single crater for a cluster round");
        assertEquals(1, craters("ISLongTom", standard), "A high-explosive shell still leaves its crater");

        var attack = GroundDamagePlaybackTest.shot("ISLongTom", true, cluster);
        var blasts = new GpuArtilleryBlast();
        blasts.detonate(BoardGeometry.center(attack.event.destination().coords(), 0), cluster, attack, 9);
        blasts.advance(GpuArtilleryBlast.SECONDS - .1f);
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        blasts.groundMarks(marks::add);
        assertEquals(35, marks.size(), "Every bomblet marks the ground, the target hex's eight included");
        for (var mark : marks) {
            assertTrue(mark.shot().damagePerHit() <= 4, "Each mark is one bomblet's share: " + mark.shot().damagePerHit());
        }
    }

    /** FASCAM and Vibrabomb-IV lay mines: no blast, no crater, but a landing that is drawn. */
    @Test
    void minefieldRoundsLayMinesInsteadOfExploding() {
        EquipmentType.initializeTypes();
        for (String munition : new String[] { "M_FASCAM", "M_VIBRABOMB_IV" }) {
            var round = shot(true, munition);
            assertTrue(GpuArtilleryBlast.laysMines(round), munition);
            assertFalse(GpuArtilleryBlast.explodes(round), munition + " must not draw a high-explosive blast");
            assertTrue(GpuArtilleryBlast.drawsLanding(round), munition);
        }
        assertFalse(GpuArtilleryBlast.laysMines(shot(true, "M_STANDARD")));
        assertFalse(GpuArtilleryBlast.laysMines(shot(false, "M_FASCAM")), "Only artillery lays a minefield this way");
        assertEquals(0, craters("ISLongTom", shot(true, "M_FASCAM")), "Mines leave no crater");
    }

    /** A minefield round's mines land in the hex it hit and nowhere else, and leave no scorch marks. */
    @Test
    void minesStayInTheHexTheRoundLandedOn() {
        EquipmentType.initializeTypes();
        var round = shot(true, "M_FASCAM");
        var attack = GroundDamagePlaybackTest.shot("ISLongTom", true, round);
        var blasts = new GpuArtilleryBlast();
        blasts.detonate(BoardGeometry.center(attack.event.destination().coords(), 0), round, attack, 3);
        blasts.advance(GpuArtilleryBlast.SECONDS - .1f);
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        blasts.groundMarks(marks::add);
        assertEquals(0, marks.size(), "Mines scorch nothing");
        var mines = GpuArtilleryBlast.mines(3);
        assertTrue(mines.size() >= 6, "A handful of mines");
        for (var mine : mines) {
            assertEquals(0, mine.ring());
            assertTrue(Math.hypot(mine.offsetX(), mine.offsetY()) < BoardGeometry.height() * .45f,
                  "Every mine lands inside the hex: " + mine);
        }
    }

    /** A known minefield's mines lie on the ground every frame until it is gone, and only then disappear. */
    @Test
    void minesStayOnTheGroundUntilTheMinefieldIsGone() {
        Coords hex = new Coords(3, 6);
        var blasts = new GpuArtilleryBlast();
        var explosions = new GpuExplosionEffects();
        explosions.begin();
        blasts.restingMines(explosions, List.of(hex), GpuArtilleryBlastTest::flatGround);
        int mines = explosions.size();
        assertTrue(mines >= 6, "The minefield's mines lie there: " + mines);
        for (Vector3 mine : GpuArtilleryBlast.placeMines(hex, GpuArtilleryBlastTest::flatGround)) {
            assertEquals(0, mine.z, .01f, "On the ground");
            assertTrue(mine.dst(BoardGeometry.center(hex, 0)) < BoardGeometry.height() * .45f, "Inside the hex");
        }
        explosions.begin();
        blasts.restingMines(explosions, List.of(hex), GpuArtilleryBlastTest::flatGround);
        assertEquals(mines, explosions.size(), "Still there a frame later, with no blast to keep them");
        explosions.begin();
        blasts.restingMines(explosions, List.of(), GpuArtilleryBlastTest::flatGround);
        assertEquals(0, explosions.size(), "Gone once the minefield is detonated or cleared");
    }

    /** While its drop is still drawn the hex is left to it, which lays the mines in the same places; then they stay. */
    @Test
    void theDropHandsItsMinesOverToTheMinefield() {
        EquipmentType.initializeTypes();
        var round = shot(true, "M_FASCAM");
        var attack = GroundDamagePlaybackTest.shot("ISLongTom", true, round);
        Coords hex = attack.event.destination().coords();
        var blasts = new GpuArtilleryBlast();
        blasts.detonate(BoardGeometry.center(hex, 0), round, attack, 3);
        var explosions = new GpuExplosionEffects();
        explosions.begin();
        blasts.restingMines(explosions, List.of(hex), GpuArtilleryBlastTest::flatGround);
        assertEquals(0, explosions.size(), "The drop draws this hex's mines itself");
        blasts.advance(GpuArtilleryBlast.SECONDS);
        explosions.begin();
        blasts.restingMines(explosions, List.of(hex), GpuArtilleryBlastTest::flatGround);
        assertTrue(explosions.size() > 0, "After the drop, the minefield's mines stay");
    }

    @Test
    void onlyMinefieldMarkersHaveMines() {
        Coords mined = new Coords(2, 2);
        List<BoardMarker> markers = List.of(new BoardMarker(BoardMarker.Kind.MINEFIELD, mined, 0),
              new BoardMarker(BoardMarker.Kind.ARTILLERY_TARGET, new Coords(4, 4), 0));
        assertEquals(List.of(mined), GpuArtilleryBlast.minedHexes(markers));
    }

    /** Level ground at height 0 that reports squared distances, as the board's landscape does. */
    private static BoardGeometry.Hit flatGround(Ray ray) {
        if (ray.direction.z >= 0) {
            return null;
        }
        float distance = -ray.origin.z / ray.direction.z;
        return distance < 0 ? null : new BoardGeometry.Hit(new Coords(0, 0), distance * distance);
    }

    private static int craters(String weapon, ResolvedAttack.Shot profile) {
        var attack = GroundDamagePlaybackTest.shot(weapon, false, profile);
        attack.seconds = attack.contactSeconds + UnitAttack.RECOVERY_SECONDS / 2;
        var effects = new GpuAttackEffects();
        effects.update(List.of(attack), null, Map.of());
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        effects.groundImpacts(List.of(), marks::add);
        return marks.size();
    }

    private static int radius(String weapon, ResolvedAttack.Shot profile) {
        return GpuArtilleryBlast.areaEffect(weapon, profile).hexRadius();
    }

    @Test
    void aHeavyBarrageIsBounded() {
        var blasts = new GpuArtilleryBlast();
        for (int key = 0; key < GpuArtilleryBlast.MAX_BLASTS + 3; key++) {
            blasts.detonate(Vector3.Zero, shot(true), null, key);
        }
        assertEquals(GpuArtilleryBlast.MAX_BLASTS, blasts.size());
    }
}
