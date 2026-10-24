/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackTest.attack;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackTest.scene;
import static megamek.client.ui.clientGUI.boardview.gpu.UnitPlaybackTest.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;

/**
 * A replay shows each unit's whole action, as live play did: an Atlas's three shots at a King Crab play as one volley
 * before the Warhammer's shot, and a fighter's attack run is flown again with its shot along it, on a motion of its own.
 */
class ReplayByActionTest {
    private static final BoardScene.Unit ATLAS = unit(1, 0);
    private static final BoardScene.Unit WARHAMMER = unit(2, 1);
    private static final BoardScene.Unit KING_CRAB = unit(7, 8);
    private static final int FIGHTER = 10;

    private final BoardScene.Combat first = attack(ATLAS, KING_CRAB, ResolvedAttack.Kind.SHOT, true);
    private final BoardScene.Combat second = attack(ATLAS, KING_CRAB, ResolvedAttack.Kind.SHOT, false);
    private final BoardScene.Combat third = attack(ATLAS, KING_CRAB, ResolvedAttack.Kind.SHOT, true);
    private final BoardScene.Combat warhammer = attack(WARHAMMER, KING_CRAB, ResolvedAttack.Kind.SHOT, true);

    @Test
    void aUnitsShotsFromOnePoseAreOneActionAndTheNextUnitStartsAnother() {
        List<GpuPlaybackHistory.Step> steps = steps(first, second, third, warhammer);
        List<ReplayAction> actions = new ReplayActions().group(steps, retained(first, second, third, warhammer));

        assertEquals(2, actions.size());
        assertEquals(steps.subList(0, 3), actions.getFirst().steps(), "the Atlas's volley");
        assertEquals(List.of(first, second, third), actions.getFirst().volley());
        assertEquals(List.of(warhammer), actions.getLast().volley());
    }

    @Test
    void aCardBetweenTwoShotsBelongsToTheVolleyAndOneAfterItDoesNot() {
        GpuPlaybackHistory.Step roll = new GpuPlaybackHistory.Step(null, null,
              new GpuReportLog.PsrItem(3L << 32 | 40, 3, GamePhase.FIRING, 7, 5, 4, false, "took 20+ damage"));
        List<GpuPlaybackHistory.Step> steps = new ArrayList<>(steps(first, second, warhammer));
        steps.add(1, roll);
        List<GpuPlaybackHistory.Step> afterwards = new ArrayList<>(steps(first, warhammer));
        afterwards.add(1, roll);

        List<ReplayAction> between = new ReplayActions().group(steps, retained(first, second, warhammer));
        List<ReplayAction> after = new ReplayActions().group(afterwards, retained(first, warhammer));

        assertEquals(List.of(steps.get(0), roll, steps.get(2)), between.getFirst().steps());
        assertEquals(3, after.size(), "the Atlas's shot, the roll after it, the Warhammer's shot");
        assertEquals(List.of(roll), after.get(1).steps());
        assertTrue(after.get(1).volley().isEmpty(), "a card-only step animates nothing");
    }

    @Test
    void aShotFromAnotherPoseOrAPhysicalAttackStartsANewAction() {
        BoardScene.Unit moved = new BoardScene.Unit(ATLAS.id(), -1, "Unit 1", new BoardScene.Waypoint(new Coords(0, 0), 0,
              3), null, false, null, 2, false);
        BoardScene.Combat turned = attack(moved, KING_CRAB, ResolvedAttack.Kind.SHOT, true);
        BoardScene.Combat kick = attack(ATLAS, KING_CRAB, ResolvedAttack.Kind.KICK, true);

        assertEquals(2, new ReplayActions().group(steps(first, turned), retained(first, turned)).size());
        assertEquals(2, new ReplayActions().group(steps(first, kick), retained(first, kick)).size());
    }

    @Test
    void replayPlaysTheVolleyTogetherAndEachShotPopsUpAsItLands() {
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene scene = scene(ATLAS, WARHAMMER, KING_CRAB);
        GpuReportLog.Snapshot log = log(first, second, third, warhammer);
        play(history, List.of(first, second, third, warhammer), scene, log);
        history.takeShown();

        history.replay(null);
        assertTrue(playback.reviewing());
        assertEquals(3, playback.attacks().size(), "the Atlas's three shots play at once");
        assertEquals(first.result().id(), history.current().attack().id());
        List<UUID> popped = new ArrayList<>();
        List<UUID> warhammerFirstSeenAfter = new ArrayList<>();
        for (int frame = 0; frame < 2000 && history.replaying(); frame++) {
            frame(history, List.of(), scene, log, .02);
            for (GpuPlaybackHistory.Step step : history.takeShown()) {
                popped.add(step.attack().id());
            }
            if (warhammerFirstSeenAfter.isEmpty() && playback.reviewing() && (warhammerShotIn(playback) != null)) {
                warhammerFirstSeenAfter.addAll(popped);
            }
        }
        // Within the volley the shots land in the order of their small launch spread, not report order.
        assertEquals(Set.of(first, second, third), new HashSet<>(byId(warhammerFirstSeenAfter)),
              "the whole volley landed before the Warhammer's shot played");
        assertEquals(new HashSet<>(ids(first, second, third, warhammer)), new HashSet<>(popped),
              "each shot popped up once");
        assertEquals(4, popped.size());
        assertFalse(playback.busy(), "a replay never feeds the live queue");
    }

    @Test
    void steppingMovesAWholeAction() {
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene scene = scene(ATLAS, WARHAMMER, KING_CRAB);
        GpuReportLog.Snapshot log = log(first, second, third, warhammer);
        play(history, List.of(first, second, third, warhammer), scene, log);

        assertEquals(warhammer.result().id(), history.current().attack().id(), "the cursor follows live play");
        history.step(-1);
        assertEquals(3, playback.attacks().size(), "back one action is the Atlas's whole volley");
        history.step(1);
        assertEquals(warhammer.result().id(), history.current().attack().id(), "forward one action");
        assertEquals(1, playback.attacks().size());
        history.step(-1);
        assertEquals(3, playback.attacks().size(), "and back over the whole volley again");
    }

    @Test
    void replayFliesTheAttackRunAgainOnItsOwnMotion() {
        BoardScene.Unit fighter = new BoardScene.Unit(FIGHTER, -1, "Fighter", new BoardScene.Waypoint(new Coords(5, 0), 3,
              2), null, false, null, 2, true);
        BoardScene.Unit target = new BoardScene.Unit(3, -1, "Target", new BoardScene.Waypoint(new Coords(3, 0), 0, 0), null,
              false, null, 2, false);
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int column = 0; column <= 5; column++) {
            path.add(new BoardScene.Waypoint(new Coords(column, 0), 3, 2));
        }
        BoardScene.Movement flight = new BoardScene.Movement(FIGHTER, 0, path, EntityMovementType.MOVE_FLYING, 0, 6,
              fighter);
        ResolvedAttack result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(FIGHTER, new Coords(2, 0), 2, 3, 0), new UnitLocation(3, target.location().coords(), 0, 0,
              0), Targetable.TYPE_ENTITY, 0, "ISMediumLaser", 4, true);
        BoardScene.Combat strafe = new BoardScene.Combat(result, fighter, target, target.location());
        UnitPlayback playback = new UnitPlayback();
        GpuPlaybackHistory history = new GpuPlaybackHistory(playback);
        BoardScene scene = scene(fighter, target);
        GpuReportLog.Snapshot log = log(strafe);
        play(history, List.of(new FiringBurst(true, Set.of(FIGHTER), 0), flight, strafe,
              new FiringBurst(false, Set.of(), 0)), scene, log);
        UnitMotion live = playback.motions.get(FIGHTER);
        Vector3 parked = live.position().cpy();
        history.takeShown();

        history.replay(null);
        assertTrue(playback.reviewing());
        assertNotNull(playback.movement(), "the camera frames the run's flight");
        assertEquals(FIGHTER, playback.movement().entityId());
        UnitMotion replayed = playback.motion(FIGHTER);
        assertNotSame(live, replayed, "the run flies a motion of its own");
        assertEquals(List.of(target.location().coords()), playback.runTargets());
        Vector3 start = replayed.position().cpy();
        boolean popped = false;
        float travelled = 0;
        for (int frame = 0; frame < 2000 && history.replaying(); frame++) {
            frame(history, List.of(), scene, log, .02);
            popped |= !history.takeShown().isEmpty();
            if (playback.reviewing()) {
                travelled = Math.max(travelled, playback.motion(FIGHTER).position().dst(start));
            }
        }
        assertTrue(travelled > BoardGeometry.height() * 3, "the fighter flew its path again: " + travelled);
        assertTrue(popped, "its shot popped up as it landed");
        assertSame(live, playback.motion(FIGHTER), "the live motion is back once the review ends");
        assertEquals(parked, live.position(), "and the review never moved it");
        assertNull(playback.movement());
    }

    /** The Warhammer's shot when the review presents it, else {@code null}. */
    private BoardScene.Combat warhammerShotIn(UnitPlayback playback) {
        for (UnitAttack shot : playback.attacks()) {
            if (shot.event == warhammer) {
                return warhammer;
            }
        }
        return null;
    }

    private List<BoardScene.Combat> byId(List<UUID> ids) {
        Map<UUID, BoardScene.Combat> all = retained(first, second, third, warhammer);
        List<BoardScene.Combat> shots = new ArrayList<>();
        for (UUID id : ids) {
            shots.add(all.get(id));
        }
        return shots;
    }

    private static List<UUID> ids(BoardScene.Combat... shots) {
        List<UUID> ids = new ArrayList<>();
        for (BoardScene.Combat shot : shots) {
            ids.add(shot.result().id());
        }
        return ids;
    }

    private static Map<UUID, BoardScene.Combat> retained(BoardScene.Combat... shots) {
        Map<UUID, BoardScene.Combat> retained = new LinkedHashMap<>();
        for (BoardScene.Combat shot : shots) {
            retained.put(shot.result().id(), shot);
        }
        return retained;
    }

    private static List<GpuPlaybackHistory.Step> steps(BoardScene.Combat... shots) {
        List<GpuPlaybackHistory.Step> steps = new ArrayList<>();
        for (BoardScene.Combat shot : shots) {
            steps.add(new GpuPlaybackHistory.Step(event(shot), null, null));
        }
        return steps;
    }

    /** One frame of the board view: the history takes the frame's events and log, then advances. */
    private static void frame(GpuPlaybackHistory history, List<BoardScene.Animation> events, BoardScene scene,
          GpuReportLog.Snapshot log, double seconds) {
        history.accept(events, scene, log, unit -> false);
        history.advance(seconds, ignored -> true);
    }

    /** The events played live until the playback is done. */
    private static void play(GpuPlaybackHistory history, List<BoardScene.Animation> events, BoardScene scene,
          GpuReportLog.Snapshot log) {
        frame(history, List.of(), scene, log(), 0);
        frame(history, events, scene, log, 0);
        for (int frame = 0; frame < 40 && history.running(); frame++) {
            frame(history, List.of(), scene, log, 1);
        }
        assertFalse(history.running(), "live play finished");
    }

    private static GpuReportLog.Snapshot log(BoardScene.Combat... shots) {
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>();
        for (BoardScene.Combat shot : shots) {
            combat.add(event(shot));
        }
        return new GpuReportLog.Snapshot(3, GamePhase.FIRING_REPORT, List.of(), Map.of(), combat, List.of(), List.of(),
              List.of());
    }

    private static GpuReportLog.CombatEvent event(BoardScene.Combat combat) {
        ResolvedAttack result = combat.result();
        return new GpuReportLog.CombatEvent(result.id(), 3, GamePhase.FIRING, result.kind(), combat.attacker().id(),
              combat.target().id(), "Medium Laser", "LA", result.hit(), result.hit() ? 5 : 0, List.of(), null, 0);
    }
}
