/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import megamek.common.ECMInfo;
import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.TargetRollModifier;
import megamek.common.ToHitData;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.compute.ComputeECM;
import megamek.common.enums.AimingMode;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.FirePreview;
import megamek.common.units.Mek;
import megamek.common.units.ProneCause;
import megamek.common.units.Targetable;
import megamek.common.weapons.lrms.innerSphere.ISLRM20;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The movement fire preview gives the numbers the firing phase declares once the real server movement handler has
 * executed the same plan. This pins the end state HypotheticalState writes to what MovePathHandler commits, for the
 * scenarios below. Every die shows 6, so every piloting roll passes and the plan succeeds.
 */
class FirePreviewServerAgreementTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final String ARCHER = "Archer ARC-2R.mtf";
    private static final String ATLAS = "Atlas AS7-D.mtf";
    private static final String BULLDOG = "Bulldog Medium Tank.blk";
    private static final Coords START = new Coords(7, 12);

    private RulesManager previousRules;
    private TWGameManager manager;
    private Game game;
    private Entity mover;
    private Entity atlas;
    private Entity bulldog;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        previousRules = Game.rulesManager;
        Compute.setRNG(new MaxRolls());
        manager = spy(new TWGameManager());
        doNothing().when(manager).send(any(Packet.class));
        doNothing().when(manager).sendServerChat(anyString());
        game = manager.getGame();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        for (int id = 0; id < 2; id++) {
            Player player = new Player(id, "Player " + id);
            player.setTeam(id + 1);
            game.addPlayer(id, player);
        }
        game.setPhase(GamePhase.MOVEMENT);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = previousRules;
        Compute.setRNG(MMRandom.R_DEFAULT);
    }

    @Test
    void walkIntoWoodsEndingWithATurn() throws Exception {
        start(ARCHER, hex(7, 10, 0, "woods:1;foliage_elev:2"));
        MovePath plan = plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.TURN_RIGHT);
        assertEquals(EntityMovementType.MOVE_WALK, plan.getLastStepMovementType());

        FirePreview.Result preview = assertAgrees(plan, atlas, bulldog);

        assertEquals(1, preview.attackerMovement().getValue(), "walked");
        assertTrue(mentions(incoming(preview, atlas), "woods"), "the destination's woods protect the mover");
    }

    @Test
    void run() throws Exception {
        start(ARCHER);
        MovePath plan = plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS);
        assertEquals(EntityMovementType.MOVE_RUN, plan.getLastStepMovementType());

        FirePreview.Result preview = assertAgrees(plan, atlas, bulldog);

        assertEquals(2, preview.attackerMovement().getValue(), "ran");
        assertEquals(2, preview.targetMovement().getValue(), "moved 6 hexes");
    }

    @Test
    void jump() throws Exception {
        start("Dervish DV-11DK.mtf");
        MovePath plan = plan(MoveStepType.START_JUMP, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS);
        assertEquals(EntityMovementType.MOVE_JUMP, plan.getLastStepMovementType());

        FirePreview.Result preview = assertAgrees(plan, atlas, bulldog);

        assertEquals(3, preview.attackerMovement().getValue(), "jumped");
        assertEquals(2, preview.targetMovement().getValue(), "jumped 3 hexes");
    }

    @Test
    void standingStillWithTheTacOpsOption() throws Exception {
        start(ARCHER);
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_STANDING_STILL).setValue(true);
        // A twist left from a firing phase, which standing still resets.
        game.setPhase(GamePhase.FIRING);
        mover.setSecondaryFacing(1);
        game.setPhase(GamePhase.MOVEMENT);

        FirePreview.Result preview = assertAgrees(plan(), atlas, bulldog);

        assertEquals(-1, preview.targetMovement().getValue(), "target did not move");
        assertEquals(0, mover.getSecondaryFacing(), "the stand-still commit put the torso forward");
    }

    @Test
    void standingStillInDeepWater() throws Exception {
        start(ARCHER, hex(7, 12, 0, "water:2"));
        mover.setElevation(-2);
        // The exposure the server set when the Mek entered the water.
        manager.doSetLocationsExposure(mover, game.getBoard().getHex(START), false, -2);

        FirePreview.Result preview = assertAgrees(plan(), atlas, bulldog);

        assertNull(outgoing(preview, atlas).notPreviewed(), "standing still keeps the live exposure");
        assertTrue(mentions(outgoing(preview, atlas), "water"), "the weapons fire from under water");
    }

    @Test
    void wadingThroughWaterOntoDryLand() throws Exception {
        start(ARCHER, hex(7, 11, 0, "water:1"));

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS), atlas,
              bulldog);

        assertNull(outgoing(preview, atlas).notPreviewed(), "the water on the way leaves the exposure as it was");
    }

    @Test
    void bracingEndsWhenTheMoveSpendsMp() throws Exception {
        start(ARCHER);
        // Braced in an earlier round; a new round keeps the brace.
        mover.setBraceLocation(Mek.LOC_RIGHT_ARM);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas, bulldog);

        assertFalse(mover.isBracing(), "the move ended the brace");
        assertEquals(3, outgoing(preview, atlas).salvos().size(), "no longer bracing, the Mek may twist");
        assertFalse(mentions(incoming(preview, atlas), "target immobile"));
    }

    @Test
    void bracingLastsWhileStandingStill() throws Exception {
        start(ARCHER);
        mover.setBraceLocation(Mek.LOC_RIGHT_ARM);

        FirePreview.Result preview = assertAgrees(plan(), atlas, bulldog);

        assertTrue(mover.isBracing());
        assertEquals(1, outgoing(preview, atlas).salvos().size(), "a bracing Mek cannot twist");
        assertTrue(mentions(incoming(preview, atlas), "target immobile"));
    }

    @Test
    void turnInPlaceMovesAnEnemyOutOfTheFrontArc() throws Exception {
        start(ARCHER);
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_STANDING_STILL).setValue(true);
        long rearOnly = impossible(outgoing(FirePreview.compute(plan(), List.of(atlas), true), atlas).salvos()
              .getFirst());

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.TURN_RIGHT, MoveStepType.TURN_RIGHT,
              MoveStepType.TURN_RIGHT), atlas, bulldog);

        assertTrue(impossible(outgoing(preview, atlas).salvos().getFirst()) > rearOnly,
              "facing away, the front weapons lose the Atlas and only the two rear lasers reach it");
        assertEquals(0, preview.targetMovement().getValue(), "turning spends MP, so it is not standing still");
    }

    @Test
    void torsoTwistReachesAnEnemyOnTheFlank() throws Exception {
        start(ARCHER);
        place(atlas, new Coords(11, 11), 5);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas, bulldog);

        List<FirePreview.Salvo> salvos = outgoing(preview, atlas).salvos();
        assertEquals(3, salvos.size(), "forward, left and right");
        long forward = impossible(salvos.getFirst());
        assertTrue(salvos.stream().anyMatch(salvo -> impossible(salvo) < forward), "a twist brings more weapons");
    }

    @Test
    void rearWeaponsReachAnEnemyBehind() throws Exception {
        start(ARCHER);
        place(atlas, START.translated(3, 3), 0);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas, bulldog);

        FirePreview.Salvo forward = outgoing(preview, atlas).salvos().getFirst();
        assertTrue(impossible(forward) > 0, "front weapons cannot reach behind");
        assertTrue(forward.shots().stream().anyMatch(FirePreview.Shot::available), "rear lasers can");
    }

    @Test
    void goingProne() throws Exception {
        start(ARCHER);
        place(atlas, START.translated(0), 3);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.GO_PRONE), atlas, bulldog);

        assertTrue(modifiers(incoming(preview, atlas)).contains(-2), "prone target adjacent");
        assertTrue(modifiers(incoming(preview, bulldog)).contains(1), "prone target at range");
        assertEquals(1, outgoing(preview, atlas).salvos().size(), "a prone Mek cannot twist");
    }

    @Test
    void gettingUpAndWalking() throws Exception {
        start(ARCHER);
        mover.setProne(ProneCause.VOLUNTARY);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.GET_UP, MoveStepType.FORWARDS), atlas, bulldog);

        assertEquals(3, outgoing(preview, atlas).salvos().size(), "standing again, the Mek may twist");
        assertFalse(mentions(outgoing(preview, atlas), "prone"));
    }

    @Test
    void mekHullDownBehindAHill() throws Exception {
        start(ARCHER, hex(7, 11, 1, ""));
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN).setValue(true);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.HULL_DOWN), atlas, bulldog);

        assertTrue(mentions(incoming(preview, atlas), "Hull down"), "hull-down behind cover");
    }

    @Test
    void climbingOntoABuildingRoof() throws Exception {
        start(ARCHER, hex(7, 11, 0, "bldg_elev:2;building:2:8;bldg_cf:100"), hex(7, 9, 2, ""));
        MovePath plan = plan(MoveStepType.CLIMB_MODE_ON, MoveStepType.FORWARDS);
        assertTrue(plan.isMoveLegal());
        assertEquals(2, plan.getFinalElevation());

        FirePreview.Result preview = assertAgrees(plan, atlas, bulldog);

        assertEquals(2, mover.getElevation(), "the oracle stands on the roof");
        assertTrue(outgoing(preview, atlas).salvos().getFirst().shots().stream().anyMatch(FirePreview.Shot::available),
              "from the roof the Mek sees over the hill that hides the Atlas from the ground");
    }

    @Test
    void lineOfSightBlockedAtTheDestination() throws Exception {
        start(ARCHER, hex(7, 8, 3, ""));

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas);

        assertEquals(outgoing(preview, atlas).salvos().getFirst().shots().size(),
              impossible(outgoing(preview, atlas).salvos().getFirst()), "nothing reaches through the block");
        assertEquals(incoming(preview, atlas).salvos().getFirst().shots().size(),
              impossible(incoming(preview, atlas).salvos().getFirst()), "nothing comes back through it");
    }

    @Test
    void outOfRangeForLasersButNotForMissiles() throws Exception {
        start(ARCHER);
        place(atlas, new Coords(7, 0), 3);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas);

        FirePreview.Salvo forward = outgoing(preview, atlas).salvos().getFirst();
        assertTrue(impossible(forward) > 0, "the lasers are out of range");
        assertTrue(forward.shots().stream().anyMatch(FirePreview.Shot::available), "the LRMs reach");
    }

    @Test
    void indirectFireThroughAFriendlySpotter() throws Exception {
        start(ARCHER, hex(7, 8, 3, ""));
        game.getOptions().getOption(OptionsConstants.BASE_INDIRECT_FIRE).setValue(true);
        WeaponMounted lrm = indirectLrm();
        Entity spotter = unit(ATLAS, 4, 0, new Coords(9, 5), 5);
        // Spotting is a firing-phase declaration; set here so both paths see the same spotter.
        spotter.setSpotting(true);
        spotter.setSpotTargetId(atlas.getId());

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas);

        FirePreview.Shot indirect = shot(outgoing(preview, atlas), lrm);
        assertTrue(indirect.available(), "the spotter sees the Atlas: " + indirect.toHit().getDesc());
    }

    @Test
    void indirectFireWithoutASpotter() throws Exception {
        start(ARCHER, hex(7, 8, 3, ""));
        game.getOptions().getOption(OptionsConstants.BASE_INDIRECT_FIRE).setValue(true);
        WeaponMounted lrm = indirectLrm();
        unit(ATLAS, 4, 0, new Coords(9, 5), 5);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas);

        assertFalse(shot(outgoing(preview, atlas), lrm).available(), "no one spots the Atlas");
    }

    @Test
    void c3MemberCloserToTheEnemy() throws Exception {
        start(ARCHER);
        place(atlas, new Coords(7, 0), 3);
        c3Member();

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas);

        assertTrue(mentions(outgoing(preview, atlas), "due to C3 spotter"), "the member's range bracket");
    }

    @Test
    void enemyEcmAtTheDestinationCutsTheC3Network() throws Exception {
        start(ARCHER);
        place(atlas, new Coords(7, 0), 3);
        c3Member();
        MovePath plan = plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS);
        FirePreview.Result withoutEcm = FirePreview.compute(plan, List.of(atlas), true);
        Entity jammer = unit(ATLAS, 5, 1, new Coords(5, 5), 2);
        jammer.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_LEFT_ARM);
        assertTrue(START.distance(jammer.getPosition()) > 6, "the start is outside the bubble");
        assertTrue(plan.getFinalCoords().distance(jammer.getPosition()) <= 6, "the destination is inside");

        FirePreview.Result preview = assertAgrees(plan, atlas);

        assertTrue(mentions(outgoing(withoutEcm, atlas), "due to C3 spotter"));
        assertNotEquals(values(outgoing(withoutEcm, atlas)), values(outgoing(preview, atlas)), "C3 is lost");
    }

    @Test
    void ownEcmMovesWithTheMoverAndJamsTheEnemyC3Network() throws Exception {
        start(ARCHER);
        place(atlas, new Coords(7, 2), 3);
        atlas.addEquipment(EquipmentType.get("ISC3MasterComputer"), Mek.LOC_LEFT_ARM);
        Entity spotter = unit(ATLAS, 4, 1, new Coords(7, 5), 3);
        spotter.addEquipment(EquipmentType.get("ISC3SlaveUnit"), Mek.LOC_LEFT_ARM);
        spotter.setC3Master(atlas.getId(), true);
        assertTrue(spotter.onSameC3NetworkAs(atlas));
        MovePath plan = plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS);
        FirePreview.Result withoutEcm = FirePreview.compute(plan, List.of(atlas), true);
        mover.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_RIGHT_ARM);
        assertTrue(START.distance(spotter.getPosition()) > 6, "from the start the bubble misses the enemy spotter");
        assertTrue(plan.getFinalCoords().distance(spotter.getPosition()) <= 6, "from the destination it covers it");

        FirePreview.Result preview = assertAgrees(plan, atlas);

        assertTrue(mentions(incoming(withoutEcm, atlas), "due to C3 spotter"));
        assertFalse(mentions(incoming(withoutEcm, atlas), "under ECM"));
        assertTrue(mentions(incoming(preview, atlas), "due to C3 spotter under ECM"), "the C3 bonus is halved");
    }

    @Test
    void twistDeclaredInTheTargetingPhaseStays() throws Exception {
        start(ARCHER);
        game.setPhase(GamePhase.TARGETING);
        mover.setSecondaryFacing(1);
        game.setPhase(GamePhase.MOVEMENT);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.TURN_LEFT), atlas, bulldog);

        assertEquals(List.of(1), outgoing(preview, atlas).salvos().stream().map(FirePreview.Salvo::secondaryFacing)
              .toList());
    }

    @Test
    void evadingTurretTank() throws Exception {
        start(BULLDOG);
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_EVADE).setValue(true);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS, MoveStepType.EVADE), atlas);

        assertTrue(preview.end().evading());
        assertEquals(6, outgoing(preview, atlas).salvos().size(), "a turret turns all the way");
        assertTrue(mentions(incoming(preview, atlas), "evading"), "the evading target is harder to hit");
    }

    /** Water case 1: a standing Mek in water up to its partial depth wets its legs only. */
    @Test
    void standingInPartialWaterWetsOnlyTheLegs() throws Exception {
        start(ARCHER, hex(7, 11, 0, "water:1"));
        WeaponMounted legLaser = (WeaponMounted) mover.addEquipment(EquipmentType.get("ISMediumLaser"),
              Mek.LOC_LEFT_LEG);

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas, bulldog);

        assertTrue(preview.wet());
        assertEquals(ILocationExposureStatus.WET, mover.getLocationStatus(Mek.LOC_LEFT_LEG));
        assertEquals(ILocationExposureStatus.NORMAL, mover.getLocationStatus(Mek.LOC_LEFT_ARM));
        assertEquals("Weapon underwater, but not target.", shot(outgoing(preview, atlas), legLaser).toHit().getDesc());
        assertTrue(outgoing(preview, atlas).salvos().getFirst().shots().stream().anyMatch(FirePreview.Shot::available),
              "the arm and torso weapons fire from above the surface");
    }

    /** Water case 2: deeper than the partial depth, every location is wet. */
    @Test
    void deepWaterWetsEveryLocation() throws Exception {
        start(ARCHER, hex(7, 11, 0, "water:2"));
        MovePath plan = plan(MoveStepType.FORWARDS);
        assertEquals(-2, plan.getFinalElevation());

        FirePreview.Result preview = assertAgrees(plan, atlas, bulldog);

        assertEveryLocation(ILocationExposureStatus.WET);
        assertEquals(List.of("Cannot shoot in to or out of water."), descriptions(outgoing(preview, atlas)));
        assertEquals(List.of("Cannot shoot in to or out of water."), descriptions(incoming(preview, atlas)));
    }

    /** Water case 2: a prone Mek is under water even in partial water. */
    @Test
    void goingProneInPartialWaterWetsEveryLocation() throws Exception {
        start(ARCHER, hex(7, 11, 0, "water:1"));

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS, MoveStepType.GO_PRONE), atlas, bulldog);

        assertTrue(preview.end().prone());
        assertEveryLocation(ILocationExposureStatus.WET);
    }

    /** Water case 4: the last exposure update of a move is never a jump's, so a jump landing in water gets wet. */
    @Test
    void landingAJumpInWater() throws Exception {
        start("Dervish DV-11DK.mtf", hex(7, 9, 0, "water:1"));
        MovePath plan = plan(MoveStepType.START_JUMP, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS);
        assertEquals(new Coords(7, 9), plan.getFinalCoords());

        FirePreview.Result preview = assertAgrees(plan, atlas, bulldog);

        assertEquals(-1, preview.end().elevation());
        assertEquals(ILocationExposureStatus.WET, mover.getLocationStatus(Mek.LOC_LEFT_LEG));
        assertTrue(preview.wet());
    }

    /** Water case 5: a breached location stays breached, also out of the water. */
    @Test
    void aBreachedLocationStaysBreachedOnDryLand() throws Exception {
        start(ARCHER, hex(7, 12, 0, "water:2"));
        breachTheBareLeftArmInDeepWater();

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas, bulldog);

        assertFalse(preview.wet());
        assertEquals(ILocationExposureStatus.BREACHED, mover.getLocationStatus(Mek.LOC_LEFT_ARM));
        assertEquals(ILocationExposureStatus.NORMAL, mover.getLocationStatus(Mek.LOC_RIGHT_ARM));
        WeaponMounted armLaser = mover.getWeaponList().stream()
              .filter(weapon -> weapon.getLocation() == Mek.LOC_LEFT_ARM).findFirst().orElseThrow();
        assertEquals("Weapon is not in a state where it can be fired", shot(outgoing(preview, atlas), armLaser).toHit()
              .getDesc());
    }

    /** Water case 5: a breached bare location is never checked again, so standing on in deep water is previewed. */
    @Test
    void aBreachedBareLocationStandingInDeepWater() throws Exception {
        start(ARCHER, hex(7, 12, 0, "water:2"));
        breachTheBareLeftArmInDeepWater();

        FirePreview.Result preview = assertAgrees(plan(), atlas, bulldog);

        assertTrue(preview.wet());
        assertEquals(ILocationExposureStatus.BREACHED, mover.getLocationStatus(Mek.LOC_LEFT_ARM));
        assertEquals(ILocationExposureStatus.WET, mover.getLocationStatus(Mek.LOC_RIGHT_ARM));
    }

    /** Water case 5: wading from deep into partial water with a breached bare location. */
    @Test
    void aBreachedBareLocationWadingIntoPartialWater() throws Exception {
        start(ARCHER, hex(7, 12, 0, "water:2"), hex(7, 11, 0, "water:1"));
        breachTheBareLeftArmInDeepWater();

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas, bulldog);

        assertEquals(-1, preview.end().elevation());
        assertEquals(ILocationExposureStatus.WET, mover.getLocationStatus(Mek.LOC_LEFT_LEG));
        assertEquals(ILocationExposureStatus.BREACHED, mover.getLocationStatus(Mek.LOC_LEFT_ARM));
        assertEquals(ILocationExposureStatus.NORMAL, mover.getLocationStatus(Mek.LOC_RIGHT_ARM));
    }

    /** Water case 6: under water only underwater-capable weapons fire, and a torpedo is previewed like any other. */
    @Test
    void aTorpedoFiresFromUnderWater() throws Exception {
        String[] lake = new String[7];
        for (int y = 5; y <= 10; y++) {
            lake[y - 5] = hex(7, y, 0, "water:1");
        }
        lake[6] = hex(7, 11, 0, "water:2");
        start(ARCHER, lake);
        place(atlas, new Coords(7, 5), 3);
        atlas.setElevation(-1);
        manager.doSetLocationsExposure(atlas, game.getBoard().getHex(atlas.getPosition()), false, -1);
        WeaponMounted torpedo = (WeaponMounted) mover.addEquipment(EquipmentType.get("ISLRT5"), Mek.LOC_LEFT_LEG);
        mover.addEquipment(EquipmentType.get("ISLRT5 Ammo"), Mek.LOC_RIGHT_LEG);
        mover.loadWeapon(torpedo);
        WeaponMounted lrm = mover.getWeaponList().stream()
              .filter(weapon -> weapon.getType().getAmmoType() == AmmoType.AmmoTypeEnum.LRM).findFirst().orElseThrow();

        FirePreview.Result preview = assertAgrees(plan(MoveStepType.FORWARDS), atlas);

        assertTrue(shot(outgoing(preview, atlas), torpedo).available(),
              shot(outgoing(preview, atlas), torpedo).toHit().getDesc());
        assertEquals("Weapon cannot fire underwater.", shot(outgoing(preview, atlas), lrm).toHit().getDesc());
    }

    /** In place after its move: the unit's numbers are the ones the firing phase declares. */
    @Test
    void inPlaceAfterTheMove() throws Exception {
        start(ARCHER);
        new MovePathHandler(manager, mover, plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.TURN_RIGHT), null).processMovement();
        assertEquals(GamePhase.MOVEMENT, game.getPhase(), "other units still move");

        FirePreview.Result preview = FirePreview.computeInPlace(mover, List.of(atlas, bulldog), true);

        assertNull(preview.notPreviewed());
        assertEquals(EntityMovementType.MOVE_WALK, preview.end().moved(), "already moved");
        assertFiringPhaseAgrees(preview);
    }

    /** In place before its move: the unit stands still when its turn comes. */
    @Test
    void inPlaceBeforeTheMoveIsStandingStill() throws Exception {
        start(ARCHER);
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_STANDING_STILL).setValue(true);
        game.setPhase(GamePhase.FIRING);
        mover.setSecondaryFacing(1);
        game.setPhase(GamePhase.MOVEMENT);

        FirePreview.Result preview = FirePreview.computeInPlace(mover, List.of(atlas, bulldog), true);
        new MovePathHandler(manager, mover, plan(), null).processMovement();

        assertEquals(EntityMovementType.MOVE_NONE, preview.end().moved(), "not moved yet");
        assertEquals(-1, preview.targetMovement().getValue(), "target did not move");
        assertFiringPhaseAgrees(preview);
    }

    /**
     * Previews the plan with every twist, executes it through the real MovePathHandler, then compares as
     * {@link #assertFiringPhaseAgrees}.
     */
    private FirePreview.Result assertAgrees(MovePath plan, Entity... enemies) {
        FirePreview.Result preview = FirePreview.compute(plan, List.of(enemies), true);
        assertNull(preview.notPreviewed(), "the plan must be previewable");

        new MovePathHandler(manager, mover, plan, null).processMovement();
        assertFiringPhaseAgrees(preview);
        return preview;
    }

    /**
     * Starts the firing phase and compares every previewed number with the declaration to-hit of the same weapon,
     * target and twist.
     */
    private void assertFiringPhaseAgrees(FirePreview.Result preview) {
        Seen attackerMovement = seen(preview.attackerMovement());
        Seen targetMovement = seen(preview.targetMovement());
        manager.resetEntityPhase(GamePhase.FIRING);
        game.setPhase(GamePhase.FIRING);

        assertEquals(attackerMovement, seen(Compute.getAttackerMovementModifier(game, mover.getId())));
        assertEquals(targetMovement, seen(Compute.getTargetMovementModifier(game, mover.getId())));
        // One ECM list for the whole state, used for every weapon, twist and direction below.
        List<ECMInfo> ecm = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        for (FirePreview.Exchange exchange : preview.exchanges()) {
            Entity enemy = game.getEntity(exchange.enemyId());
            // Incoming first, while the mover still holds the secondary facing its move left it with.
            assertSalvosAgree(enemy, mover, exchange.incoming(), ecm);
        }
        for (FirePreview.Exchange exchange : preview.exchanges()) {
            if (exchange.outgoing().notPreviewed() == null) {
                assertEquals(mover.getSecondaryFacing(), exchange.outgoing().salvos().getFirst().secondaryFacing(),
                      "forward is the secondary facing the move leaves");
                assertSalvosAgree(mover, game.getEntity(exchange.enemyId()), exchange.outgoing(), ecm);
            }
        }
    }

    /** The Mek stands in the depth-2 water at START, and the server breached its bare left arm without a roll. */
    private void breachTheBareLeftArmInDeepWater() {
        mover.setElevation(-2);
        mover.setArmor(0, Mek.LOC_LEFT_ARM);
        manager.doSetLocationsExposure(mover, game.getBoard().getHex(START), false, -2);
        assertEquals(ILocationExposureStatus.BREACHED, mover.getLocationStatus(Mek.LOC_LEFT_ARM));
    }

    private void assertEveryLocation(int status) {
        for (int location = 0; location < mover.locations(); location++) {
            assertEquals(status, mover.getLocationStatus(location), mover.getLocationName(location));
        }
    }

    /**
     * Each previewed shot equals the declaration to-hit. That declaration gives the same numbers when it takes a
     * precomputed ECM list, as the preview's does.
     */
    private void assertSalvosAgree(Entity attacker, Targetable target, FirePreview.Direction direction,
          List<ECMInfo> ecm) {
        assertNull(direction.notPreviewed());
        int forward = attacker.getSecondaryFacing();
        assertEquals(legalTwists(attacker), direction.salvos().stream().map(FirePreview.Salvo::secondaryFacing)
              .collect(Collectors.toCollection(TreeSet::new)), "the twists FiringDisplay allows");
        for (FirePreview.Salvo salvo : direction.salvos()) {
            attacker.setSecondaryFacing(salvo.secondaryFacing());
            assertEquals(salvo.secondaryFacing(), attacker.getSecondaryFacing());
            for (FirePreview.Shot shot : salvo.shots()) {
                if (shot.toHit() != null) {
                    String what = attacker.getShortName() + " weapon " + shot.weaponId() + " at twist "
                          + salvo.secondaryFacing();
                    ToHitData real = WeaponAttackAction.toHit(game, attacker.getId(), target, shot.weaponId(),
                          Entity.LOC_NONE, AimingMode.NONE, false);
                    ToHitData withEcmList = new WeaponAttackAction(attacker.getId(), target.getTargetType(),
                          target.getId(), shot.weaponId()).toHit(game, ecm);
                    assertEquals(seen(real), seen(withEcmList), what + " with the ECM list");
                    assertEquals(seen(real), seen(shot.toHit()), what);
                }
            }
        }
        attacker.setSecondaryFacing(forward);
    }

    /** What FiringDisplay lets the unit choose now. */
    private static Set<Integer> legalTwists(Entity unit) {
        Set<Integer> result = new TreeSet<>();
        for (int direction = 0; direction < 6; direction++) {
            if (unit.canTwistNow() ? unit.isValidSecondaryFacing(direction)
                  : (direction == unit.getSecondaryFacing())) {
                result.add(direction);
            }
        }
        return result;
    }

    /** Everything about a to-hit roll that the declaration and the hit resolution use. */
    private record Seen(int value, String desc, List<TargetRollModifier> modifiers, int sideTable, int hitTable,
          int cover) { }

    private static Seen seen(ToHitData toHit) {
        return new Seen(toHit.getValue(), toHit.getDesc(), List.copyOf(toHit.getModifiers()), toHit.getSideTable(),
              toHit.getHitTable(), toHit.getCover());
    }

    private void start(String moverFile, String... special) throws Exception {
        game.setBoard(board(special));
        mover = unit(moverFile, 1, 0, START, 0);
        atlas = unit(ATLAS, 2, 1, new Coords(7, 4), 3);
        bulldog = unit(BULLDOG, 3, 1, new Coords(11, 5), 4);
    }

    private Entity unit(String file, int id, int owner, Coords position, int facing) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(id);
        unit.setOwner(game.getPlayer(owner));
        game.addEntity(unit);
        place(unit, position, facing);
        unit.setDeployed(true);
        return unit;
    }

    private static void place(Entity unit, Coords position, int facing) {
        unit.setPosition(position);
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);
    }

    private MovePath plan(MoveStepType... steps) {
        MovePath plan = new MovePath(game, mover);
        for (MoveStepType step : steps) {
            plan.addStep(step);
        }
        return plan;
    }

    /** An extra LRM 20 in indirect mode, as GpuFiringCaptureTest mounts one. */
    private WeaponMounted indirectLrm() throws Exception {
        ISLRM20 type = new ISLRM20();
        type.adaptToGameOptions(game.getOptions());
        WeaponMounted lrm = (WeaponMounted) mover.addEquipment(type, Mek.LOC_LEFT_ARM);
        mover.loadWeapon(lrm);
        assertTrue(lrm.setModeImmediately("Indirect") >= 0);
        return lrm;
    }

    /** A friendly Atlas in a C3 network with the mover, close to the enemy Atlas. */
    private void c3Member() throws Exception {
        mover.addEquipment(EquipmentType.get("ISC3MasterComputer"), Mek.LOC_LEFT_TORSO);
        Entity member = unit(ATLAS, 4, 0, new Coords(9, 3), 5);
        member.addEquipment(EquipmentType.get("ISC3SlaveUnit"), Mek.LOC_LEFT_ARM);
        member.setC3Master(mover.getId(), true);
        assertTrue(member.onSameC3NetworkAs(mover));
    }

    private static FirePreview.Direction outgoing(FirePreview.Result result, Entity enemy) {
        return exchange(result, enemy).outgoing();
    }

    private static FirePreview.Direction incoming(FirePreview.Result result, Entity enemy) {
        return exchange(result, enemy).incoming();
    }

    private static FirePreview.Exchange exchange(FirePreview.Result result, Entity enemy) {
        return result.exchanges().stream().filter(exchange -> exchange.enemyId() == enemy.getId()).findFirst()
              .orElseThrow();
    }

    private static FirePreview.Shot shot(FirePreview.Direction direction, WeaponMounted weapon) {
        int weaponId = weapon.getEntity().getEquipmentNum(weapon);
        return direction.salvos().getFirst().shots().stream().filter(shot -> shot.weaponId() == weaponId)
              .findFirst().orElseThrow();
    }

    private static long impossible(FirePreview.Salvo salvo) {
        return salvo.shots().stream().filter(shot -> (shot.toHit() != null)
              && (shot.toHit().getValue() == TargetRoll.IMPOSSIBLE)).count();
    }

    private static boolean mentions(FirePreview.Direction direction, String text) {
        return direction.salvos().stream().flatMap(salvo -> salvo.shots().stream())
              .anyMatch(shot -> (shot.toHit() != null) && shot.toHit().getDesc().contains(text));
    }

    /** The distinct descriptions of the forward salvo. */
    private static List<String> descriptions(FirePreview.Direction direction) {
        return direction.salvos().getFirst().shots().stream().map(shot -> shot.toHit().getDesc()).distinct().toList();
    }

    private static List<Integer> modifiers(FirePreview.Direction direction) {
        List<Integer> result = new ArrayList<>();
        direction.salvos().getFirst().shots().stream().filter(shot -> shot.toHit() != null)
              .forEach(shot -> shot.toHit().getModifiers().forEach(modifier -> result.add(modifier.value())));
        return result;
    }

    private static List<Integer> values(FirePreview.Direction direction) {
        return direction.salvos().getFirst().shots().stream()
              .map(shot -> (shot.toHit() == null) ? null : shot.toHit().getValue()).toList();
    }

    private static String hex(int x, int y, int level, String terrain) {
        return String.format("hex %02d%02d %d \"%s\" \"\"", x + 1, y + 1, level, terrain);
    }

    /** A clear 16 x 17 board with the given special hexes. */
    private static Board board(String... special) {
        StringBuilder data = new StringBuilder("size 16 17\n");
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 16; x++) {
                String line = hex(x, y, 0, "");
                for (String hex : special) {
                    if (hex.startsWith(line.substring(0, 9))) {
                        line = hex;
                    }
                }
                data.append(line).append('\n');
            }
        }
        return BoardLoader.initializeBoard(data.append("end").toString());
    }

    /** Every die shows 6. */
    private static final class MaxRolls extends MMRandom {
        @Override
        public int randomInt(int maxValue) {
            return maxValue - 1;
        }

        @Override
        public float randomFloat() {
            return 0.999f;
        }
    }
}
