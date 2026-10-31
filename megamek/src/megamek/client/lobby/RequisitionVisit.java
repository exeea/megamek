/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One visit to the Requisition view: which units it added, read from the lobby's forces as the server sends them back.
 * A unit counts when it was not in the lobby as the visit began and belongs to a player the visit added units for, so
 * units others add meanwhile stay out of the strip. Its totals are the strip's running Battle Value and tonnage.
 */
public final class RequisitionVisit {

    /** What a visit has added so far: how many units, their Battle Value and their tonnage. */
    public record Totals(int units, int bv, double tons) {
        public static final Totals NONE = new Totals(0, 0, 0);
    }

    private final Set<Integer> before;
    private final Set<Integer> owners = new HashSet<>();

    /** A visit that begins with the units {@code existing} in the lobby. */
    public RequisitionVisit(Collection<Integer> existing) {
        before = new HashSet<>(existing);
    }

    /** A visit that begins with the units of {@code forces} in the lobby. */
    public static RequisitionVisit of(LobbyForces forces) {
        return new RequisitionVisit(forces.units().stream().map(LobbyUnit::id).toList());
    }

    /** Notes that units were sent for the player {@code ownerId}, whose new units then count as this visit's. */
    public void sentFor(int ownerId) {
        owners.add(ownerId);
    }

    /** Leaves unit {@code id} out from now on, as when it is taken back out. */
    public void forget(int id) {
        before.add(id);
    }

    /** The units of {@code forces} this visit added, in the forces' order. */
    public List<LobbyUnit> added(LobbyForces forces) {
        List<LobbyUnit> added = new ArrayList<>();
        for (LobbyUnit unit : forces.units()) {
            if (!before.contains(unit.id()) && owners.contains(unit.ownerId())) {
                added.add(unit);
            }
        }
        return added;
    }

    /** The count, Battle Value and tonnage of {@code units}. */
    public static Totals totals(List<LobbyUnit> units) {
        int bv = 0;
        double tons = 0;
        for (LobbyUnit unit : units) {
            bv += unit.bv();
            tons += unit.tons();
        }
        return new Totals(units.size(), bv, tons);
    }
}
