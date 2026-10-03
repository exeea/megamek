/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.moves.MovePathSummary;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.FirePreview;
import megamek.common.units.Mek;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.other.innerSphere.ISAMS;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The movement fire preview publishes complete rows only, keeps them while nothing changes and follows the plan. */
class GpuFirePreviewTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final Coords START = new Coords(7, 12);

    private final AtomicInteger publishes = new AtomicInteger();
    private final AtomicBoolean acceptsInput = new AtomicBoolean(true);
    private RulesManager previousRules;
    private Game game;
    private Player local;
    private Entity archer;
    private Entity atlas;
    private Entity bulldog;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() throws Exception {
        previousRules = Game.rulesManager;
        game = new Game();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        for (int id = 0; id < 2; id++) {
            Player player = new Player(id, "Player " + id);
            player.setTeam(id + 1);
            game.addPlayer(id, player);
        }
        local = game.getPlayer(0);
        Board board = ClearBoard.of(16, 17);
        Coords pond = new Coords(7, 9);
        board.setHex(pond, new Hex(0, "water:1", "", pond));
        game.setBoard(board);
        game.setPhase(GamePhase.MOVEMENT);
        archer = unit("Archer ARC-2R.mtf", 1, 0, START, 0);
        atlas = unit("Atlas AS7-D.mtf", 2, 1, new Coords(7, 4), 3);
        bulldog = unit("Bulldog Medium Tank.blk", 3, 1, new Coords(11, 5), 4);
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = previousRules;
    }

    @Test
    void completesOneEdtEventLaterAndKeepsTheSnapshotWhileNothingChanges() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath walk = plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS);

        GpuFirePreview.Snapshot updating = onEdt(() -> capture(preview, walk));
        drain();
        GpuFirePreview.Snapshot done = onEdt(() -> capture(preview, walk));

        assertTrue(updating.active());
        assertFalse(updating.complete(), "the first capture only starts the job");
        assertEquals(1, publishes.get());
        assertTrue(done.complete());
        assertSame(done, onEdt(() -> capture(preview, walk)), "an unchanged key keeps the instance");
    }

    @Test
    void recomputesWhenThePlanTheGameOrTheLoadoutChanges() throws Exception {
        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_MANUAL_AMS).setValue(true);
        ISAMS amsType = new ISAMS();
        amsType.adaptToGameOptions(game.getOptions());
        WeaponMounted ams = (WeaponMounted) archer.addEquipment(amsType, Mek.LOC_RIGHT_ARM);
        archer.loadWeapon(ams);
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath walk = plan(MoveStepType.FORWARDS);
        GpuFirePreview.Snapshot previous = completed(preview, walk);

        walk.addStep(MoveStepType.FORWARDS);
        GpuFirePreview.Snapshot longer = completed(preview, walk);
        onEdt(() -> {
            game.processGameEvent(new GameEntityChangeEvent(this, atlas));
            return null;
        });
        GpuFirePreview.Snapshot afterEvent = completed(preview, walk);
        assertTrue(ams.setModeImmediately(Weapon.MODE_AMS_MANUAL) >= 0);
        GpuFirePreview.Snapshot afterMode = completed(preview, walk);

        assertEquals(4, publishes.get(), "each change is computed and published once");
        assertNotEquals(previous.from(), longer.from());
        assertEquals(longer, afterEvent, "an unchanged game gives equal rows");
        assertEquals(FirePreview.Reason.FIRES_AUTOMATICALLY.description(), line(afterEvent, "Anti-Missile").detail());
        assertNotEquals(line(afterEvent, "Anti-Missile"), line(afterMode, "Anti-Missile"), "manual AMS is a weapon");
    }

    @Test
    void publishesOnlyCompleteJobsAndDropsInvalidatedOnes() throws Exception {
        GpuFirePreview preview = preview(0);
        MovePath walk = plan(MoveStepType.FORWARDS);

        // With no time per slice, each enemy takes a slice of its own. The invalidation is queued in the capture's
        // event, so it runs after the first slice and before the second, which the first slice posts behind it.
        onEdt(() -> {
            GpuFirePreview.Snapshot started = capture(preview, walk);
            SwingUtilities.invokeLater(preview::invalidate);
            return started;
        });
        drain();
        assertEquals(0, publishes.get(), "an invalidated job never publishes");

        GpuFirePreview.Snapshot updating = onEdt(() -> capture(preview, walk));
        drain();

        assertFalse(updating.complete());
        assertEquals(1, publishes.get());
        assertTrue(onEdt(() -> capture(preview, walk)).complete());
    }

    @Test
    void noSliceRunsWhileADialogIsPending() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath walk = plan(MoveStepType.FORWARDS);

        acceptsInput.set(false);
        GpuFirePreview.Snapshot pending = onEdt(() -> capture(preview, walk));
        drain();
        acceptsInput.set(true);
        onEdt(() -> {
            capture(preview, walk);
            // The dialog opens after the job was posted.
            acceptsInput.set(false);
            return null;
        });
        drain();
        assertEquals(0, publishes.get());
        acceptsInput.set(true);

        assertFalse(pending.complete());
        assertTrue(completed(preview, walk).complete());
        assertEquals(1, publishes.get());
    }

    @Test
    void closeRemovesTheListenerAndDropsTheJob() throws Exception {
        int before = game.getGameListeners().size();
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        assertEquals(before + 1, game.getGameListeners().size());

        onEdt(() -> {
            capture(preview, plan(MoveStepType.FORWARDS));
            preview.close();
            return null;
        });
        drain();

        assertEquals(before, game.getGameListeners().size());
        assertEquals(0, publishes.get());
        assertSame(GpuFirePreview.Snapshot.NONE, onEdt(() -> capture(preview, plan())));
    }

    @Test
    void inactiveOutsideTheMovementPhaseForOtherPlayersUnitsAndUnitsNotShown() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath none = plan();

        GpuFirePreview.Snapshot enemy = onEdt(() -> preview.capture(local, new GameTurn(0), atlas.getId(), none,
              unit -> true, unit -> false));
        GpuFirePreview.Snapshot hidden = onEdt(() -> preview.capture(local, new GameTurn(0), archer.getId(), none,
              unit -> unit != archer, unit -> false));
        GpuFirePreview.Snapshot nobody = onEdt(() -> preview.capture(local, new GameTurn(0), Entity.NONE, none,
              unit -> true, unit -> false));
        game.setPhase(GamePhase.FIRING);
        GpuFirePreview.Snapshot firing = onEdt(() -> capture(preview, none));

        for (GpuFirePreview.Snapshot snapshot : List.of(enemy, hidden, nobody, firing)) {
            assertSame(GpuFirePreview.Snapshot.NONE, snapshot);
        }
    }

    @Test
    void onTheOpponentsTurnTheOwnUnitIsPreviewedWhereItStands() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        // The movement display may still hold a path from the last turn; it is not this unit's move now.
        MovePath stale = plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS);

        onEdt(() -> preview.capture(local, null, archer.getId(), stale, unit -> true, unit -> false));
        drain();
        GpuFirePreview.Snapshot here = onEdt(() -> preview.capture(local, null, archer.getId(), stale,
              unit -> true, unit -> false));

        assertTrue(here.complete());
        assertEquals(archer.getId(), here.unitId());
        assertFalse(here.fromDestination());
        assertEquals(START, here.from());
        assertEquals("None", here.moved());
        assertEquals(2, here.contacts().size());
    }

    @Test
    void aBurstOfEnemyMovesRestartsOneJobOnceItSettlesAndAStaleJobNeverPublishes() throws Exception {
        GpuFirePreview preview = preview(0);
        // The opponent's turn: the Archer is previewed where it stands, captured after every move as the board
        // source's timer does.
        Callable<GpuFirePreview.Snapshot> capture = () -> preview.capture(local, null, archer.getId(), null,
              unit -> true, unit -> false);
        for (int move = 0; move < 5; move++) {
            onEdt(() -> {
                moveAtlas();
                return capture.call();
            });
            drain();
        }
        assertEquals(0, publishes.get(), "no job starts while the enemies keep moving");

        settle();
        // With no time per slice each enemy takes a slice of its own. The move is queued in the capture's event, so
        // it runs after the first slice and before the second, which the first slice posts behind it.
        onEdt(() -> {
            GpuFirePreview.Snapshot started = capture.call();
            SwingUtilities.invokeLater(this::moveAtlas);
            return started;
        });
        drain();
        assertEquals(0, publishes.get(), "the job the move made stale never publishes");

        settle();
        GpuFirePreview.Snapshot updating = onEdt(capture);
        drain();
        GpuFirePreview.Snapshot done = onEdt(capture);

        assertFalse(updating.complete());
        assertEquals(1, publishes.get(), "one job ran to the end");
        assertTrue(done.complete());
        assertEquals(START.distance(atlas.getPosition()), done.contacts().stream()
              .filter(row -> row.id() == atlas.getId()).findFirst().orElseThrow().distance(), "after the last move");
    }

    @Test
    void theSnapshotCarriesWhatTheContactsPanelAndTheBoardLabelsShow() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);

        GpuFirePreview.Snapshot walk = completed(preview, plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.TURN_RIGHT));

        assertEquals("unit 1 from (8, 11) facing 1 Walked attacker 1 tmm 0 targets 2 threats 2 destination true ''",
              header(walk));
        assertEquals("[3 d7 out twist 0 turret false 4/6 best 5 83% [Medium Laser@LA=9, Medium Laser@RA=9, LRM 20@LT=5,"
              + " LRM 20@RT=5, Medium Laser (R)@CT=x, Medium Laser (R)@CT=x]"
              + " in twist 0 turret true 3/4 best 6 72% [Machine Gun@FR=f, SRM 4@TU=8, SRM 4@TU=8, Large Laser@TU=6]"
              + ","
              + " 2 d6 out twist 0 turret false 4/6 best 6 72% [Medium Laser@LA=7, Medium Laser@RA=7, LRM 20@LT=6,"
              + " LRM 20@RT=6, Medium Laser (R)@CT=x, Medium Laser (R)@CT=x]"
              + " in twist 0 turret false 5/7 best 5 83% [Medium Laser@LA=6, Medium Laser@RA=6, LRM 20@LT=5,"
              + " SRM 6@LT=6, AC/20@RT=6, Medium Laser (R)@CT=x, Medium Laser (R)@CT=x]]", rows(walk));
    }

    @Test
    void moreThanSixTargetsAreAllPreviewed() throws Exception {
        for (int id = 4; id <= 9; id++) {
            unit("Bulldog Medium Tank.blk", id, 1, new Coords(id, 6), 3);
        }
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);

        GpuFirePreview.Snapshot standing = completed(preview, plan());

        assertEquals(8, standing.contacts().size());
        assertEquals(8, standing.targets());
    }

    @Test
    void theTargetMovementModifierIsTheOneAttackersGet() throws Exception {
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_STANDING_STILL).setValue(true);
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath none = plan();

        GpuFirePreview.Snapshot standing = completed(preview, none);

        assertEquals(-1, standing.tmm(), "attackers get the TacOps standing-still modifier");
        assertEquals(0, MovePathSummary.tmm(none).getValue(), "the path display shows no such term");
    }

    @Test
    void aMoveIntoWaterIsPreviewedAndFlagsTheBreachRollsItCannotPredict() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);

        GpuFirePreview.Snapshot dry = completed(preview, plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS));
        GpuFirePreview.Snapshot wading = completed(preview, plan(MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS));

        assertFalse(dry.breachNotPredicted());
        assertTrue(wading.breachNotPredicted());
        assertEquals("", wading.unavailable());
        assertTrue(wading.targets() > 0);
    }

    @Test
    void aPlanTheRulesCannotPreviewKeepsTheContactsWithTheReason() throws Exception {
        Entity vtol = unit("Cobra Transport VTOL.blk", 4, 0, new Coords(3, 12), 0);
        vtol.setElevation(1);
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath plan = new MovePath(game, vtol);
        plan.addStep(MoveStepType.FORWARDS);

        onEdt(() -> preview.capture(local, new GameTurn(0), vtol.getId(), plan, unit -> true, unit -> false));
        drain();
        GpuFirePreview.Snapshot refused = onEdt(() -> preview.capture(local, new GameTurn(0), vtol.getId(), plan,
              unit -> true, unit -> false));

        assertEquals(FirePreview.Reason.UNSUPPORTED_UNIT.description(), refused.unavailable());
        assertTrue(refused.complete());
        assertEquals(List.of(atlas.getId(), bulldog.getId()), refused.contacts().stream()
              .map(GpuFirePreview.Contact::id).sorted().toList());
        assertEquals(0, refused.targets());
    }

    @Test
    void sensorContactsGetADistanceOnly() throws Exception {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);
        MovePath none = plan();

        onEdt(() -> preview.capture(local, new GameTurn(0), archer.getId(), none, unit -> true,
              unit -> unit == bulldog));
        drain();
        GpuFirePreview.Snapshot snapshot = onEdt(() -> preview.capture(local, new GameTurn(0), archer.getId(), none,
              unit -> true, unit -> unit == bulldog));

        GpuFirePreview.Contact contact = snapshot.contacts().getLast();
        assertEquals(bulldog.getId(), contact.id());
        assertTrue(contact.sensor());
        assertEquals(START.distance(bulldog.getPosition()), contact.distance());
        assertSame(GpuFirePreview.Side.NONE, contact.outgoing());
    }

    @Test
    void chooseTakesTheMostAvailableShotsThenTheLowerBestThenTheEarlierTwist() {
        GpuFirePreview.Side forward = side(0, 2, 8);
        GpuFirePreview.Side left = side(-1, 3, 9);
        GpuFirePreview.Side right = side(1, 3, 7);
        GpuFirePreview.Side sameAsForward = side(1, 2, 8);

        assertSame(left, GpuFirePreview.choose(List.of(forward, left)));
        assertSame(right, GpuFirePreview.choose(List.of(forward, left, right)));
        assertSame(forward, GpuFirePreview.choose(List.of(forward, sameAsForward)), "a tie keeps forward");
        assertSame(GpuFirePreview.Side.NONE, GpuFirePreview.choose(List.of()));
    }

    @Test
    void rankPutsShotsFirstAndSensorContactsLastAndKeepsEveryRow() {
        List<GpuFirePreview.Contact> rows = new ArrayList<>();
        rows.add(contact(100, true, 3, GpuFirePreview.Side.NONE, GpuFirePreview.Side.NONE));
        for (int id = 1; id <= 8; id++) {
            // Enemy 8 has no shot either way; the others shoot at 9 - id and fire back at 4 + id.
            GpuFirePreview.Side shot = (id == 8) ? side(0, 0, TargetRoll.IMPOSSIBLE) : side(0, 1, 9 - id);
            GpuFirePreview.Side back = (id == 8) ? side(0, 0, TargetRoll.IMPOSSIBLE) : side(0, 1, 4 + id);
            rows.add(contact(id, false, 10 - id, shot, back));
        }

        List<GpuFirePreview.Contact> ranked = GpuFirePreview.rank(rows);

        assertEquals(List.of(7, 6, 5, 4, 3, 2, 1, 8, 100), ranked.stream().map(GpuFirePreview.Contact::id).toList());
    }

    @Test
    void theBoardSourceRepublishesThePreviewOfItsFocusUnit() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            assertSame(GpuFirePreview.Snapshot.NONE, fixture.source.takeFrame().panels().preview(),
                  "no focus unit and no acting unit");

            fixture.source.setFocusUnit(fixture.entity.getId());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            drain();

            GpuFirePreview.Snapshot preview = fixture.source.takeFrame().panels().preview();
            assertTrue(preview.complete(), "the finished job republished the frame");
            assertEquals(fixture.entity.getId(), preview.unitId(), "the focus unit");
            assertEquals(fixture.entity.getPosition(), preview.from());
            assertFalse(preview.fromDestination(), "no client, so not this unit's turn: where it stands");
        }
    }

    @Test
    void captureOffTheEdtThrows() {
        GpuFirePreview preview = preview(GpuFirePreview.SLICE_NANOS);

        assertThrows(IllegalStateException.class, () -> capture(preview, plan()));
    }

    /** EDT: the local player's turn with this plan for the Archer. */
    private GpuFirePreview.Snapshot capture(GpuFirePreview preview, MovePath plan) {
        return preview.capture(local, new GameTurn(0), archer.getId(), plan, unit -> true, unit -> false);
    }

    /** Captures, runs the job and captures again. */
    private GpuFirePreview.Snapshot completed(GpuFirePreview preview, MovePath plan) throws Exception {
        onEdt(() -> capture(preview, plan));
        drain();
        GpuFirePreview.Snapshot result = onEdt(() -> capture(preview, plan));
        assertTrue(result.complete());
        return result;
    }

    private GpuFirePreview preview(long sliceNanos) {
        return new GpuFirePreview(game, publishes::incrementAndGet, acceptsInput::get, sliceNanos);
    }

    /** EDT: the Atlas moves one hex, as a server update of an enemy move does. */
    private void moveAtlas() {
        atlas.setPosition(atlas.getPosition().translated(1));
        game.processGameEvent(new GameEntityChangeEvent(this, atlas));
    }

    /** Waits until the game has been unchanged for longer than the preview waits after a change. */
    private static void settle() throws InterruptedException {
        Thread.sleep(TimeUnit.NANOSECONDS.toMillis(GpuFirePreview.SETTLE_NANOS) + 50);
    }

    /** Runs every EDT event, including the ones those events post, until the queue has no preview slice left. */
    private static void drain() throws Exception {
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }

    private static String header(GpuFirePreview.Snapshot snapshot) {
        return String.join(" ", "unit " + snapshot.unitId(), "from " + snapshot.from().toFriendlyString(),
              "facing " + snapshot.facing(),
              snapshot.moved(), "attacker " + snapshot.attackerModifier(), "tmm " + snapshot.tmm(),
              "targets " + snapshot.targets(), "threats " + snapshot.threats(),
              "destination " + snapshot.fromDestination(), "'" + snapshot.unavailable() + "'");
    }

    private static String rows(GpuFirePreview.Snapshot snapshot) {
        List<String> result = new ArrayList<>();
        for (GpuFirePreview.Contact row : snapshot.contacts()) {
            result.add(row.id() + " d" + row.distance() + " out " + side(row.outgoing()) + " in "
                  + side(row.incoming()));
        }
        return result.toString();
    }

    private static String side(GpuFirePreview.Side side) {
        return "twist " + side.twist() + " turret " + side.turret() + " " + side.available() + "/" + side.total()
              + " best " + side.best() + " " + Math.round(side.odds()) + "% " + side.lines().stream()
              .map(line -> line.weapon() + "@" + line.location() + "=" + value(line.value())).toList();
    }

    private static String value(int value) {
        return (value == TargetRoll.IMPOSSIBLE) ? "x"
              : (value == TargetRoll.AUTOMATIC_FAIL) ? "f" : Integer.toString(value);
    }

    private static GpuFirePreview.Line line(GpuFirePreview.Snapshot snapshot, String weapon) {
        return snapshot.contacts().getFirst().outgoing().lines().stream()
              .filter(line -> line.weapon().contains(weapon)).findFirst().orElseThrow();
    }

    private static GpuFirePreview.Side side(int twist, int available, int best) {
        return new GpuFirePreview.Side("", twist, false, available, 6, best, 0, List.of());
    }

    private static GpuFirePreview.Contact contact(int id, boolean sensor, int distance, GpuFirePreview.Side outgoing,
          GpuFirePreview.Side incoming) {
        return new GpuFirePreview.Contact(id, sensor, distance, outgoing, incoming);
    }

    private MovePath plan(MoveStepType... steps) {
        MovePath plan = new MovePath(game, archer);
        for (MoveStepType step : steps) {
            plan.addStep(step);
        }
        return plan;
    }

    private Entity unit(String file, int id, int owner, Coords position, int facing) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(id);
        unit.setOwner(game.getPlayer(owner));
        game.addEntity(unit);
        unit.setPosition(position);
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);
        unit.setDeployed(true);
        return unit;
    }

    @Test
    void aNativeAskOfTheBoardSourcePausesItsPreview() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            FutureTask<GpuBoardWindow.DialogAnswer> ask = new FutureTask<>(() -> fixture.source.ask(
                  new GpuBoardWindow.DialogRequest(0, GpuBoardWindow.DialogKind.MESSAGE, "Confirm", "Continue?", false,
                        List.of("Yes", "No"), 0, 1, List.of(), List.of(), "Do not ask again", true, "", null, null,
                        List.of())));
            SwingUtilities.invokeLater(ask);
            while (fixture.source.dialog() == null) {
                Thread.sleep(5);
            }

            // Inside the ask's nested loop, as the board source's timer does: refresh, then let every posted slice run.
            fixture.source.setFocusUnit(fixture.entity.getId());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            drainSwing();
            boolean completeDuringTheAsk = fixture.source.takeFrame().panels().preview().complete();
            fixture.source.answer(fixture.source.dialog().id(),
                  new GpuBoardWindow.DialogAnswer(0, List.of(), null, false, List.of()));
            ask.get(20, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            drainSwing();

            assertFalse(completeDuringTheAsk, "no preview slice runs inside a native ask");
            assertTrue(fixture.source.takeFrame().panels().preview().complete(), "the job runs after the answer");
        }
    }

    private static void drainSwing() throws Exception {
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }
}
