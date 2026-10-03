/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.swing.JComponent;

import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay.PhysicalCommand;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay.PhysicalOption;
import megamek.common.ToHitData;
import megamek.common.actions.KickAttackAction;
import megamek.common.actions.PhysicalAttackPsr;
import megamek.common.actions.PunchAttackAction;
import megamek.common.annotations.Nullable;
import megamek.common.compute.Compute;
import megamek.common.game.Game;
import megamek.common.rolls.PilotingRollData;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;

/** EDT service for physical attacks: the actor's per-limb options against one target and guarded declarations. */
final class GpuPhysicalOptions {
    /**
     * One attack option. {@code id} is stable ("punchLeft", "punchRight", "kickLeft", "kickRight", "muleKickLeft",
     * "muleKickRight", else the attack's {@link PhysicalCommand} command such as "push"); {@code limb} is "L" or "R"
     * for a punch or kick, else "". An unavailable option keeps its label and says why in {@code reason}; its other
     * values are empty. {@code value} is the roll ({@code TargetRoll.AUTOMATIC_SUCCESS} for an automatic hit),
     * {@code odds} the chance in percent (0-100, as {@code Compute.oddsAbove}), {@code table} the hit table MegaMek
     * names ("Punch table", "Rear Kick table"; empty for the standard front table) and {@code consequences} the
     * piloting rolls the attack causes as far as MegaMek's shared rules know them, one text each (keys
     * {@code GpuBoard.hud.physical.missPsr} and {@code hitPsr}); empty when none is known, which does not mean that a
     * miss has no effect. {@code combinable} marks the two punches, which declare together.
     */
    record Option(String id, String label, String limb, boolean available, String reason, int value, double odds,
          int damage, String table, List<String> consequences, boolean combinable) {
        Option {
            consequences = List.copyOf(consequences);
        }
    }

    /**
     * The acting unit's options against its target while the local player declares physical attacks. The target is
     * the physical display's target, else the first adjacent target; {@code targetId} is {@code Entity.NONE} without
     * one or for a target that is not a unit (a building chosen on the board). {@code adjacentTargets} are the
     * identified units the actor can attack (see {@link #adjacentTargets}), which {@link #target(int)} switches
     * between.
     */
    record Snapshot(boolean active, int actorId, int targetId, List<Integer> adjacentTargets, List<Option> options) {
        static final Snapshot EMPTY = new Snapshot(false, Entity.NONE, Entity.NONE, List.of(), List.of());

        Snapshot {
            adjacentTargets = List.copyOf(adjacentTargets);
            options = List.copyOf(options);
        }
    }

    private static final String LIMB_LABEL = "GpuBoard.hud.physical.";
    private static final String MISS_PSR = "GpuBoard.hud.physical.missPsr";
    private static final String HIT_PSR = "GpuBoard.hud.physical.hitPsr";
    private static final String TABLE_PREFIX = "(using ";

    private final GpuBoardSource source;
    // EDT state: the display of the last capture while it acts, and that capture. The commands carry no actor (plan
    // B.5), so they act only while the display still acts for the unit that capture showed.
    private PhysicalDisplay display;
    private Snapshot snapshot = Snapshot.EMPTY;

    GpuPhysicalOptions(GpuBoardSource source) {
        this.source = source;
    }

    /** EDT: the options of the panel's actor; the previous instance while nothing changed. */
    Snapshot capture(JComponent panel) {
        GpuBoardSource.requireSwingThread();
        display = acting(panel);
        Snapshot next = Snapshot.EMPTY;
        if (display != null) {
            Game game = source.currentView().game;
            Entity actor = display.currentEntity();
            List<Entity> adjacent = adjacentTargets(game, actor, source::identified);
            Targetable target = target(display, adjacent);
            next = new Snapshot(true, actor.getId(), (target instanceof Entity unit) ? unit.getId() : Entity.NONE,
                  adjacent.stream().map(Entity::getId).toList(), options(game, display, actor, target));
        }
        if (!next.equals(snapshot)) {
            snapshot = next;
        }
        return snapshot;
    }

    /**
     * The units a unit can make a physical attack against, in the game's order: its valid targets
     * ({@code Game.getValidTargets}) that pass {@code identified}, are at an effective distance of at most 1 and that
     * {@code Compute.canPhysicalTarget} accepts, the test of {@code Entity.isEligibleForPhysical}. A unit "can make a
     * physical attack" against a unit when this list is not empty (the forces panel's adjacency, G2).
     */
    static List<Entity> adjacentTargets(Game game, Entity unit, Predicate<Entity> identified) {
        List<Entity> targets = new ArrayList<>();
        if (unit.getPosition() == null) {
            return targets;
        }
        for (Entity target : game.getValidTargets(unit)) {
            if (identified.test(target) && (Compute.effectiveDistance(game, unit, target) <= 1)
                  && Compute.canPhysicalTarget(game, unit.getId(), target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    // GL-safe commands. Each is posted through the source's input guard, then rechecks that the local player still
    // declares the physical attacks of the unit the last capture showed, and calls the physical display.

    /** Makes an adjacent target the physical display's target. */
    void target(int targetId) {
        source.command(() -> {
            PhysicalDisplay acting = recheck();
            if (acting != null) {
                adjacentTargets(source.currentView().game, acting.currentEntity(), source::identified).stream()
                      .filter(unit -> unit.getId() == targetId).findFirst().ifPresent(acting::target);
            }
        });
    }

    /**
     * Declares the chosen options against the target the capture shows: one option, or both punches together, which
     * is the punch with both arms. Every chosen option must still be available. Each attack has one declaration path,
     * the physical display's: {@code punch(arm)}, {@code kick(leg)} or the attack's button command, with their rule
     * prompts; the physical display commits the declaration.
     */
    void declare(List<String> optionIds) {
        List<String> chosen = List.copyOf(optionIds);
        source.command(() -> {
            PhysicalDisplay acting = recheck();
            if ((acting == null) || chosen.isEmpty()) {
                return;
            }
            Targetable target = target(acting, adjacentTargets(source.currentView().game, acting.currentEntity(),
                  source::identified));
            if (target == null) {
                return;
            }
            List<PhysicalOption> options = acting.physicalOptions(target).stream()
                  .filter(option -> chosen.contains(id(option))).toList();
            boolean punches = options.stream().allMatch(option -> option.command() == PhysicalCommand.PHYSICAL_PUNCH);
            if ((options.size() != chosen.size()) || !options.stream().allMatch(PhysicalOption::possible)
                  || ((options.size() > 1) && !(punches && (options.size() == 2)))) {
                return;
            }
            if (acting.getTarget() != target) {
                acting.target(target);
            }
            PhysicalOption option = options.getFirst();
            switch (option.command()) {
                case PHYSICAL_PUNCH -> acting.punch((options.size() == 2) ? PunchAttackAction.BOTH : option.limb());
                case PHYSICAL_KICK -> acting.kick(option.limb());
                default -> acting.actionPerformed(new ActionEvent(acting, ActionEvent.ACTION_PERFORMED,
                      option.command().getCmd()));
            }
        });
    }

    /** EDT: the display of the last capture while it still acts for the unit that capture showed, else null. */
    private @Nullable PhysicalDisplay recheck() {
        PhysicalDisplay acting = (display == null) ? null : acting(display);
        return ((acting != null) && (acting.currentEntity().getId() == snapshot.actorId())) ? acting : null;
    }

    /**
     * The physical display while the local player declares the physical attacks of an own unit on the board with it,
     * else null.
     */
    private @Nullable PhysicalDisplay acting(JComponent panel) {
        BoardClientState view = source.currentView();
        ClientGUI gui = view.getClientgui();
        if (!(panel instanceof PhysicalDisplay physical) || (gui == null) || !gui.getClient().isMyTurn()
              || !view.game.getPhase().isPhysical()) {
            return null;
        }
        Entity actor = physical.currentEntity();
        return ((actor != null) && (actor.getPosition() != null) && source.owned(actor)) ? physical : null;
    }

    /** The physical display's target, else the first adjacent target (the prototype's default), else null. */
    private static @Nullable Targetable target(PhysicalDisplay physical, List<Entity> adjacent) {
        Targetable chosen = physical.getTarget();
        return (chosen != null) ? chosen : adjacent.isEmpty() ? null : adjacent.getFirst();
    }

    private List<Option> options(Game game, PhysicalDisplay physical, Entity actor, @Nullable Targetable target) {
        if (target == null) {
            return List.of();
        }
        boolean aptitude = actor.isUseNaturalAptitudePiloting();
        List<Option> options = new ArrayList<>();
        for (PhysicalOption option : physical.physicalOptions(target)) {
            ToHitData roll = option.toHit();
            boolean available = option.possible();
            options.add(new Option(id(option), label(actor, option), limb(option), available,
                  available ? "" : roll.getDesc(), roll.getValue(),
                  available ? Compute.oddsAbove(roll.getValue(), aptitude) : 0, option.damage(),
                  available ? table(roll) : "", available ? consequences(game, actor, target, option) : List.of(),
                  option.command() == PhysicalCommand.PHYSICAL_PUNCH));
        }
        return options;
    }

    private static String id(PhysicalOption option) {
        return switch (option.command()) {
            case PHYSICAL_PUNCH -> (option.limb() == PunchAttackAction.LEFT) ? "punchLeft" : "punchRight";
            case PHYSICAL_KICK -> switch (option.limb()) {
                case KickAttackAction.LEFT -> "kickLeft";
                case KickAttackAction.RIGHT -> "kickRight";
                case KickAttackAction.LEFT_MULE -> "muleKickLeft";
                default -> "muleKickRight";
            };
            default -> option.command().getCmd();
        };
    }

    /** A punch or kick names its limb; the other attacks take their button's label. */
    private static String label(Entity actor, PhysicalOption option) {
        return switch (option.command()) {
            case PHYSICAL_PUNCH, PHYSICAL_KICK -> Messages.getString(LIMB_LABEL + id(option));
            case PHYSICAL_CLUB -> PhysicalDisplay.clubLabel(actor);
            default -> option.command().toString();
        };
    }

    private static String limb(PhysicalOption option) {
        return switch (option.command()) {
            case PHYSICAL_PUNCH -> (option.limb() == PunchAttackAction.LEFT) ? "L" : "R";
            case PHYSICAL_KICK -> ((option.limb() == KickAttackAction.LEFT)
                  || (option.limb() == KickAttackAction.LEFT_MULE)) ? "L" : "R";
            default -> "";
        };
    }

    /** The hit table of {@code ToHitData.getTableDesc}, " (using Punch table)", without its wrapping. */
    private static String table(ToHitData roll) {
        String description = roll.getTableDesc().strip();
        return (description.startsWith(TABLE_PREFIX) && description.endsWith(")"))
              ? description.substring(TABLE_PREFIX.length(), description.length() - 1) : description;
    }

    /**
     * The piloting rolls of {@code PhysicalAttackPsr}, as of now (the unit's base piloting roll plus the attack's): a
     * missed kick's roll of the attacker (not the control roll of an airborne LAM), and the roll of a kicked, pushed
     * or tripped identified unit that can fall. Rolls without a number (an automatic failure) are left out.
     */
    private List<String> consequences(Game game, Entity actor, Targetable target, PhysicalOption option) {
        String hit = switch (option.command()) {
            case PHYSICAL_KICK -> "was kicked";
            case PHYSICAL_PUSH -> "was pushed";
            case PHYSICAL_TRIP -> "was tripped";
            default -> null;
        };
        List<String> consequences = new ArrayList<>();
        if ((option.command() == PhysicalCommand.PHYSICAL_KICK) && !PhysicalAttackPsr.isControlRoll(actor)) {
            addRoll(consequences, MISS_PSR, actor, PhysicalAttackPsr.missedKick(actor));
        }
        if ((hit != null) && (target instanceof Entity unit) && source.identified(unit) && unit.canFall()) {
            addRoll(consequences, HIT_PSR, unit, PhysicalAttackPsr.kickOrPush(game, unit, unit, hit));
        }
        return consequences;
    }

    private static void addRoll(List<String> consequences, String key, Entity unit, PilotingRollData roll) {
        PilotingRollData total = unit.getBasePilotingRoll();
        total.append(roll);
        if (total.needsRoll()) {
            consequences.add(Messages.getString(key, unit.getChassis(), total.getValue()));
        }
    }
}
