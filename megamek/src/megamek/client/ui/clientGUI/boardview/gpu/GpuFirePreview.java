/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.ToHitData;
import megamek.common.annotations.Nullable;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GameNewActionEvent;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.moves.MovePath;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.FirePreview;
import megamek.common.units.Tank;
import megamek.logging.MMLogger;

/**
 * EDT service for the movement fire preview: runs the what-if rules (FirePreview) for the local movement plan, or for
 * an own unit where it stands, in time-sliced jobs and publishes immutable rows. No Entity, MovePath or ToHitData
 * leaves it.
 */
final class GpuFirePreview implements AutoCloseable {
    private static final MMLogger LOGGER = MMLogger.create(GpuFirePreview.class);
    /** EDT time a slice may use before it posts the rest of its job as a new event. */
    static final long SLICE_NANOS = 8_000_000L;
    /**
     * How long the game must stay unchanged before a unit is previewed where it stands again, so that a burst of
     * enemy moves restarts one job after it instead of one per move.
     */
    static final long SETTLE_NANOS = 250_000_000L;
    /**
     * One weapon: {@code value} is the TargetRoll value (n, AUTOMATIC_SUCCESS, AUTOMATIC_FAIL, IMPOSSIBLE; IMPOSSIBLE
     * too when the weapon was not previewed); odds 0-100; {@code detail} is the modifier list, the rules' impossible
     * reason, or why the weapon was not previewed.
     */
    record Line(String weapon, String location, int value, double odds, String detail) { }

    /**
     * One direction's chosen salvo. {@code note} is "" or why the direction was not previewed; {@code twist} is -2..3
     * from the attacker's facing (0 = torso or turret forward); {@code best} is IMPOSSIBLE when {@code available == 0}.
     */
    record Side(String note, int twist, boolean turret, int available, int total, int best, double odds,
          List<Line> lines) {
        static final Side NONE = new Side("", 0, false, 0, 0, TargetRoll.IMPOSSIBLE, 0, List.of());

        Side {
            lines = List.copyOf(lines);
        }
    }

    /** One visible enemy. Name, sprite, variant and side come from the battle status unit with the same id. */
    record Contact(int id, boolean sensor, int distance, Side outgoing, Side incoming) { }

    /**
     * The preview of one plan; {@code unitId} is the previewed own unit and {@code from} is null while inactive.
     * {@code attackerModifier} and {@code tmm} are the movement modifiers the rules give attacks by and against the
     * unit there (Compute), so the target movement modifier can differ from the path display's (MovePathSummary.tmm:
     * no sprint, skid, pilot or standing-still terms); show this one while a preview exists.
     * {@code breachNotPredicted}: a location of the unit is under water there, and hits on it roll for breaches the
     * preview does not predict.
     */
    record Snapshot(boolean active, boolean complete, int unitId, boolean fromDestination, Coords from, int boardId,
          int facing, String moved, int attackerModifier, int tmm, String unavailable, boolean breachNotPredicted,
          int targets, int threats, List<Contact> contacts) {
        static final Snapshot NONE = new Snapshot(false, false, Entity.NONE, false, null, Board.BOARD_NONE, -1, "", 0,
              0, "", false, 0, 0, List.of());

        Snapshot {
            contacts = List.copyOf(contacts);
        }

        /** The same preview while its successor is being computed. */
        Snapshot updating() {
            return new Snapshot(active, false, unitId, fromDestination, from, boardId, facing, moved, attackerModifier,
                  tmm, unavailable, breachNotPredicted, targets, threats, contacts);
        }
    }

    /** What a preview is computed from; values only, never entity identity (the game replaces its entities). */
    private record Key(int unitId, int localPlayerId, boolean inPlace, boolean fromDestination, FirePreview.End end,
          FirePreview.Reason unsupported, String loadout, long revision, List<Integer> enemyIds,
          List<Integer> sensorIds) { }

    private final Game game;
    private final Runnable published;
    private final BooleanSupplier acceptsInput;
    private final long sliceNanos;
    /** Marks the preview out of date; runs no game work. */
    private final GameListener listener = new GameListenerAdapter() {
        @Override
        public void gameEntityChange(GameEntityChangeEvent event) {
            invalidate();
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent event) {
            invalidate();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent event) {
            invalidate();
        }

        @Override
        public void gameNewAction(GameNewActionEvent event) {
            invalidate();
        }

        @Override
        public void gameBoardNew(GameBoardNewEvent event) {
            invalidate();
        }

        @Override
        public void gameBoardChanged(GameBoardChangeEvent event) {
            invalidate();
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent event) {
            invalidate();
        }

        @Override
        public void gamePlayerChange(GamePlayerChangeEvent event) {
            invalidate();
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent event) {
            invalidate();
        }

        @Override
        public void gameTurnChange(GameTurnChangeEvent event) {
            invalidate();
        }
    };
    private long revision;
    /** System.nanoTime() of the last {@link #invalidate()}. */
    private long invalidatedAt = System.nanoTime() - SETTLE_NANOS;
    private Key publishedKey;
    private Snapshot publishedSnapshot = Snapshot.NONE;
    private Job job;
    private boolean closed;

    /**
     * EDT: listens to {@code game} until {@link #close()}; {@code published} republishes the frame once a job
     * completes; a slice computes only while {@code acceptsInput} holds (no dialog pending); {@code sliceNanos} is the
     * EDT time a slice may use.
     */
    GpuFirePreview(Game game, Runnable published, BooleanSupplier acceptsInput, long sliceNanos) {
        this.game = game;
        this.published = published;
        this.acceptsInput = acceptsInput;
        this.sliceNanos = sliceNanos;
        game.addGameListener(listener);
    }

    /**
     * EDT: the preview for the own unit {@code unitId} in the movement phase: its plan ({@code planned} when it
     * belongs to that unit, else standing still) while it may act in {@code activeTurn}, otherwise where it stands
     * now. Evaluated against the enemies that pass {@code visible}; sensor contacts get a distance only. Returns the
     * published instance while nothing changed; while a job runs, the last preview of the unit marked incomplete.
     * A preview where the unit stands starts only once the game has not changed for {@link #SETTLE_NANOS}.
     */
    Snapshot capture(@Nullable Player local, @Nullable GameTurn activeTurn, int unitId, @Nullable MovePath planned,
          Predicate<Entity> visible, Predicate<Entity> sensorContact) {
        GpuBoardSource.requireSwingThread();
        Entity unit = game.getEntity(unitId);
        if (closed || (local == null) || (unit == null) || !game.getPhase().isMovement()
              || (unit.getOwnerId() != local.getId()) || !visible.test(unit)) {
            job = null;
            return Snapshot.NONE;
        }
        MovePath plan = null;
        if ((activeTurn != null) && activeTurn.isValidEntity(unit, game)) {
            plan = ((planned != null) && (planned.getEntity().getId() == unitId)) ? planned : new MovePath(game, unit);
        }
        Key key = key(local, unit, plan, visible, sensorContact);
        if (key.equals(publishedKey)) {
            job = null;
            return publishedSnapshot;
        }
        if ((job == null) || !job.key.equals(key)) {
            Snapshot updating = ((publishedKey != null) && (publishedKey.unitId() == unitId))
                  ? publishedSnapshot.updating() : header(key, unit, false, "");
            job = null;
            if (!acceptsInput.getAsBoolean()
                  || ((plan == null) && ((System.nanoTime() - invalidatedAt) < SETTLE_NANOS))) {
                // No rules work while a dialog is pending, nor while the game around a standing unit still changes
                // (enemies moving); the first capture after that starts the job.
                return updating;
            }
            job = new Job(key, local, plan, visible, sensorContact, updating);
            SwingUtilities.invokeLater(job);
        }
        return job.updating;
    }

    /** EDT (reposts itself from elsewhere): the preview is out of date; cancels a running job before it publishes. */
    void invalidate() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::invalidate);
            return;
        }
        revision++;
        invalidatedAt = System.nanoTime();
        job = null;
    }

    /** EDT: removes the listener and drops a running job; called by {@link GpuBoardSource#close()}. */
    @Override
    public void close() {
        closed = true;
        job = null;
        game.removeGameListener(listener);
    }

    /** The salvo the prototype shows for one direction: the most available shots, then the lower best, then first. */
    static Side choose(List<Side> salvos) {
        Side chosen = Side.NONE;
        for (int i = 0; i < salvos.size(); i++) {
            Side salvo = salvos.get(i);
            if ((i == 0) || (salvo.available() > chosen.available())
                  || ((salvo.available() == chosen.available()) && (salvo.best() < chosen.best()))) {
                chosen = salvo;
            }
        }
        return chosen;
    }

    /**
     * The prototype's order: rows with an outgoing shot first, sensor contacts last, then the best outgoing value,
     * distance and id.
     */
    static List<Contact> rank(List<Contact> rows) {
        return rows.stream()
              .sorted(Comparator.comparing((Contact row) -> row.outgoing().available() == 0)
              .thenComparing(Contact::sensor)
              .thenComparingInt(row -> row.outgoing().best())
              .thenComparingInt(Contact::distance)
              .thenComparingInt(Contact::id)).toList();
    }

    private Key key(Player local, Entity unit, @Nullable MovePath plan, Predicate<Entity> visible,
          Predicate<Entity> sensorContact) {
        List<Integer> enemyIds = new ArrayList<>();
        List<Integer> sensorIds = new ArrayList<>();
        for (Entity other : game.getEntitiesVector()) {
            if (unit.isEnemyOf(other) && visible.test(other)) {
                (sensorContact.test(other) ? sensorIds : enemyIds).add(other.getId());
            }
        }
        return new Key(unit.getId(), local.getId(), plan == null, (plan != null) && (plan.length() > 0),
              (plan == null) ? FirePreview.End.current(unit) : FirePreview.End.of(plan),
              (plan == null) ? null : FirePreview.unsupportedReason(plan), loadout(unit), revision,
              List.copyOf(enemyIds), List.copyOf(sensorIds));
    }

    /** What changes locally without a server update: each weapon's mode, its linked ammo and whether that is dumped. */
    private static String loadout(Entity unit) {
        StringBuilder text = new StringBuilder();
        for (WeaponMounted weapon : unit.getWeaponList()) {
            AmmoMounted ammo = weapon.getLinkedAmmo();
            text.append(unit.getEquipmentNum(weapon)).append(' ').append(weapon.curMode().getName()).append(' ')
                  .append((ammo == null) ? Entity.NONE : unit.getEquipmentNum(ammo)).append(' ')
                  .append((ammo != null) && ammo.isDumping()).append(';');
        }
        return text.toString();
    }

    /** The unit's position and movement only: while nothing is computed yet, or when the computation failed. */
    private static Snapshot header(Key key, Entity unit, boolean complete, String unavailable) {
        FirePreview.End end = key.end();
        return new Snapshot(true, complete, key.unitId(), key.fromDestination(), end.position(), end.boardId(),
              end.facing(), unit.getMovementString(end.moved()), 0, 0, unavailable, false, 0, 0, List.of());
    }

    /** One preview computation, run in slices of at most sliceNanos of EDT time each. */
    private final class Job implements Runnable {
        private final Key key;
        private final Player local;
        private final MovePath plan;
        private final Predicate<Entity> visible;
        private final Predicate<Entity> sensorContact;
        private final Snapshot updating;
        private final Deque<Integer> queue;
        private final List<FirePreview.Exchange> exchanges = new ArrayList<>();
        private FirePreview.Result header;
        private int slices;
        private long nanos;

        Job(Key key, Player local, @Nullable MovePath plan, Predicate<Entity> visible,
              Predicate<Entity> sensorContact, Snapshot updating) {
            this.key = key;
            this.local = local;
            this.plan = plan;
            this.visible = visible;
            this.sensorContact = sensorContact;
            this.updating = updating;
            queue = new ArrayDeque<>(key.enemyIds());
        }

        @Override
        public void run() {
            // Never inside a dialog's nested event loop, and never for a plan, unit or game that changed meanwhile
            // (MovementDisplay edits its path in place).
            Entity unit = game.getEntity(key.unitId());
            if ((job != this) || !acceptsInput.getAsBoolean() || (unit == null)
                  || !key.equals(key(local, unit, plan, visible, sensorContact))) {
                if (job == this) {
                    job = null;
                }
                return;
            }
            long start = System.nanoTime();
            slices++;
            try {
                do {
                    step(unit);
                } while (!queue.isEmpty() && ((System.nanoTime() - start) < sliceNanos));
            } catch (RuntimeException error) {
                LOGGER.warn(error, "Movement fire preview failed for {}", unit.getDisplayName());
                publish(header(key, unit, true, Messages.getString("GpuBoard.hud.contacts.previewFailed")));
                return;
            }
            nanos += System.nanoTime() - start;
            if (!queue.isEmpty()) {
                SwingUtilities.invokeLater(this);
                return;
            }
            LOGGER.debug("Movement fire preview: {} enemies, {} slices, {} ms", key.enemyIds().size(), slices,
                  nanos / 1e6);
            publish(snapshot(unit));
        }

        /** One rules call: the next enemy, or only the header when there is none. */
        private void step(Entity unit) {
            Integer enemyId = queue.poll();
            Entity enemy = (enemyId == null) ? null : game.getEntity(enemyId);
            List<Entity> enemies = (enemy == null) ? List.of() : List.of(enemy);
            FirePreview.Result result = (plan == null) ? FirePreview.computeInPlace(unit, enemies)
                  : FirePreview.compute(plan, enemies);
            if (header == null) {
                header = result;
            }
            if (result.notPreviewed() != null) {
                queue.clear();
            }
            exchanges.addAll(result.exchanges());
        }

        private void publish(Snapshot snapshot) {
            job = null;
            publishedKey = key;
            publishedSnapshot = snapshot;
            published.run();
        }

        private Snapshot snapshot(Entity unit) {
            FirePreview.End end = header.end();
            Coords from = (end.position() == null) ? unit.getPosition() : end.position();
            List<Contact> rows = new ArrayList<>();
            if (header.notPreviewed() != null) {
                for (int id : key.enemyIds()) {
                    rows.add(new Contact(id, false, from.distance(game.getEntity(id).getPosition()), Side.NONE,
                          Side.NONE));
                }
            }
            for (FirePreview.Exchange exchange : exchanges) {
                Entity enemy = game.getEntity(exchange.enemyId());
                rows.add(new Contact(enemy.getId(), false, from.distance(enemy.getPosition()),
                      side(unit, end.facing(), exchange.outgoing()),
                      side(enemy, enemy.getFacing(), exchange.incoming())));
            }
            for (int id : key.sensorIds()) {
                rows.add(new Contact(id, true, from.distance(game.getEntity(id).getPosition()), Side.NONE,
                      Side.NONE));
            }
            List<Contact> ranked = rank(rows);
            int targets = (int) ranked.stream().filter(row -> row.outgoing().available() > 0).count();
            int threats = (int) ranked.stream().filter(row -> row.incoming().available() > 0).count();
            return new Snapshot(true, true, key.unitId(), key.fromDestination(), from, end.boardId(), end.facing(),
                  unit.getMovementString(end.moved()), value(header.attackerMovement()),
                  value(header.targetMovement()),
                  (header.notPreviewed() == null) ? "" : header.notPreviewed().description(), header.wet(), targets,
                  threats, ranked);
        }
    }

    private static int value(@Nullable ToHitData modifier) {
        return (modifier == null) ? 0 : modifier.getValue();
    }

    /** The chosen salvo of one direction, as rows the HUD can show. */
    private static Side side(Entity attacker, int facing, FirePreview.Direction direction) {
        if (direction.notPreviewed() != null) {
            return new Side(direction.notPreviewed().description(), 0, false, 0, 0, TargetRoll.IMPOSSIBLE, 0,
                  List.of());
        }
        boolean turret = (attacker instanceof Tank tank) && !tank.hasNoTurret();
        List<Side> salvos = new ArrayList<>();
        for (FirePreview.Salvo salvo : direction.salvos()) {
            List<Line> lines = new ArrayList<>();
            int available = 0;
            int best = TargetRoll.IMPOSSIBLE;
            boolean bestAptitude = false;
            for (FirePreview.Shot shot : salvo.shots()) {
                Mounted<?> weapon = attacker.getEquipment(shot.weaponId());
                // Natural aptitude is the pilot's per weapon.
                boolean aptitude = attacker.isUseNaturalAptitudeGunnery(attacker.getGame(), weapon);
                int value = (shot.toHit() == null) ? TargetRoll.IMPOSSIBLE : shot.toHit().getValue();
                if (shot.available()) {
                    available++;
                    if (value < best) {
                        best = value;
                        bestAptitude = aptitude;
                    }
                }
                lines.add(new Line(weapon.getDesc(), attacker.getLocationAbbr(weapon.getLocation()), value,
                      shot.available() ? Compute.oddsAbove(value, aptitude) : 0,
                      (shot.toHit() == null) ? shot.notPreviewed().description() : shot.toHit().getDesc()));
            }
            int rotation = (salvo.secondaryFacing() - facing + 6) % 6;
            salvos.add(new Side("", (rotation > 3) ? (rotation - 6) : rotation, turret, available,
                  salvo.shots().size(), best, (available > 0) ? Compute.oddsAbove(best, bestAptitude) : 0, lines));
        }
        return choose(salvos);
    }
}
