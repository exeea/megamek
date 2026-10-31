/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbySession;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.units.Entity;

/**
 * The native lobby's unit and formation actions that its forces column runs itself, on the Swing thread: dropping
 * dragged units and formations, moving one up or down, configuring a unit, and deleting. They follow the old Swing lobby's
 * force tree's drop rules and use the lobby's commands ({@link LobbyCommands}), whose checks refuse what
 * the local player may not do. It needs neither OpenGL nor Swing components, so it can be tested on its own. The unit
 * menu itself is {@link megamek.client.lobby.LobbyUnitMenu}.
 */
final class LobbyForcesController {

    /** Where dragged units or formations are dropped: on empty space, a formation or a unit. */
    enum TargetKind {
        NONE, FORMATION, UNIT
    }

    /**
     * A drop target.
     *
     * @param id the formation's or unit's id; ignored for {@link TargetKind#NONE}
     */
    record Target(TargetKind kind, int id) {
        static final Target NONE = new Target(TargetKind.NONE, -1);

        static Target formation(int id) {
            return new Target(TargetKind.FORMATION, id);
        }

        static Target unit(int id) {
            return new Target(TargetKind.UNIT, id);
        }
    }

    private final LobbySession session;
    private final LobbyCommands commands;

    LobbyForcesController(LobbySession session, LobbyCommands commands) {
        this.session = session;
        this.commands = commands;
    }

    /**
     * Whether units or formations may be dropped on {@code target}, as the Swing force tree allows it: either units or
     * formations, never both or neither, and formations only one at a time onto another formation or onto empty space.
     */
    static boolean accepts(Collection<Integer> unitIds, Collection<Integer> formationIds, Target target) {
        if (unitIds.isEmpty() == formationIds.isEmpty()) {
            return false;
        }
        if (!formationIds.isEmpty()) {
            return target.kind() == TargetKind.NONE
                  || (target.kind() == TargetKind.FORMATION && formationIds.size() == 1
                  && !formationIds.contains(target.id()));
        }
        return true;
    }

    /**
     * Drops units or formations on {@code target}: units dropped on a formation or on a unit in one join it, dropped on
     * empty space or a unit in none they leave their formation; a formation dropped on another becomes part of it, and
     * dropped on empty space becomes a top-level formation.
     *
     * @return whether anything was asked of the server
     */
    boolean drop(Collection<Integer> unitIds, Collection<Integer> formationIds, Target target) {
        if (!accepts(unitIds, formationIds, target)) {
            return false;
        }
        Forces forces = session.game().getForces();
        if (!formationIds.isEmpty()) {
            if (target.kind() == TargetKind.NONE) {
                commands.forcePromote(formationIds);
            } else {
                commands.forceAttach(formationIds.iterator().next(), target.id());
            }
            return true;
        }
        List<Entity> units = units(unitIds);
        if (units.isEmpty()) {
            return false;
        }
        int formation = switch (target.kind()) {
            case NONE -> Force.NO_FORCE;
            case FORMATION -> target.id();
            case UNIT -> session.game().getEntity(target.id()) instanceof Entity onto ? onto.getForceId()
                  : Force.NO_FORCE;
        };
        if (formation == Force.NO_FORCE || forces == null || !forces.contains(formation)) {
            if (units.stream().noneMatch(Entity::partOfForce)) {
                return false;
            }
            commands.forceRemoveEntity(units);
        } else {
            commands.forceAddEntity(units, formation);
        }
        return true;
    }

    /** Moves a single unit or formation one place up or down within its formation. */
    void move(Collection<Integer> unitIds, Collection<Integer> formationIds, boolean up) {
        commands.forceMove(formations(formationIds), units(unitIds), up);
    }

    /** Opens the unit's configuration, as a double click in the Swing lobby does, when the local player may edit it. */
    boolean configure(int unitId) {
        if (session.game().getEntity(unitId) instanceof Entity entity && commands.isEditable(entity)) {
            commands.customizeMek(entity);
            return true;
        }
        return false;
    }

    /** Deletes the units and formations, the formations with their units, after asking. */
    void delete(Collection<Integer> unitIds, Collection<Integer> formationIds) {
        List<Entity> units = units(unitIds);
        List<Force> formations = formations(formationIds);
        if (!units.isEmpty() || !formations.isEmpty()) {
            commands.delete(formations, units, true);
        }
    }

    private List<Entity> units(Collection<Integer> ids) {
        List<Entity> units = new ArrayList<>();
        for (int id : ids) {
            if (session.game().getEntity(id) instanceof Entity entity) {
                units.add(entity);
            }
        }
        return units;
    }

    private List<Force> formations(Collection<Integer> ids) {
        Forces forces = session.game().getForces();
        List<Force> formations = new ArrayList<>();
        for (int id : ids) {
            if (forces != null && forces.contains(id)) {
                formations.add(forces.getForce(id));
            }
        }
        return formations;
    }
}
