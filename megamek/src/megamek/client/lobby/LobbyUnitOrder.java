/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;

import megamek.client.Client;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.BVSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.C3IDSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.IDSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.MekTableSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.NameSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.PlayerBVSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.PlayerTonnageSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.PlayerTransportIDSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.PlayerUnitRoleSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.Sorting;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.TonnageSorter;
import megamek.client.ui.panels.phaseDisplay.lobby.sorters.TypeSorter;
import megamek.common.options.IGameOptions;
import megamek.common.units.Entity;

/**
 * The order of the lobby's units: the orders the game's options allow, by name, and the one in use. Orders by BV are
 * left out under a blind drop; when the order in use is no longer allowed, the next allowed one takes its place.
 * Trains and C3 networks are kept together whatever the order.
 */
public final class LobbyUnitOrder {

    /** How far {@link #settingsChanged} looks for an allowed order before giving up. */
    private static final int MAX_STEPS = 100;

    private final Client client;
    private final List<MekTableSorter> unitSorters = new ArrayList<>();
    private final List<MekTableSorter> bvSorters = new ArrayList<>();
    private MekTableSorter active;

    /** @param client the local client */
    public LobbyUnitOrder(Client client) {
        this.client = client;
        unitSorters.add(new PlayerTransportIDSorter(client));
        unitSorters.add(new PlayerTonnageSorter(client, Sorting.ASCENDING));
        unitSorters.add(new PlayerTonnageSorter(client, Sorting.DESCENDING));
        unitSorters.add(new PlayerUnitRoleSorter(client, Sorting.ASCENDING));
        unitSorters.add(new PlayerUnitRoleSorter(client, Sorting.DESCENDING));
        unitSorters.add(new IDSorter(Sorting.ASCENDING));
        unitSorters.add(new IDSorter(Sorting.DESCENDING));
        unitSorters.add(new NameSorter(Sorting.ASCENDING));
        unitSorters.add(new NameSorter(Sorting.DESCENDING));
        unitSorters.add(new TypeSorter(Sorting.ASCENDING));
        unitSorters.add(new TypeSorter(Sorting.DESCENDING));
        unitSorters.add(new TonnageSorter(Sorting.ASCENDING));
        unitSorters.add(new TonnageSorter(Sorting.DESCENDING));
        unitSorters.add(new C3IDSorter(client));
        bvSorters.add(new PlayerBVSorter(client, Sorting.ASCENDING));
        bvSorters.add(new PlayerBVSorter(client, Sorting.DESCENDING));
        bvSorters.add(new BVSorter(Sorting.ASCENDING));
        bvSorters.add(new BVSorter(Sorting.DESCENDING));
        active = unitSorters.getFirst();
    }

    /** The orders the game's options allow. */
    private List<MekTableSorter> allowed() {
        IGameOptions options = client.getGame().getOptions();
        List<MekTableSorter> allowed = new ArrayList<>();
        for (MekTableSorter sorter : unitSorters) {
            if (sorter.isAllowed(options)) {
                allowed.add(sorter);
            }
        }
        for (MekTableSorter sorter : bvSorters) {
            if (sorter.isAllowed(options)) {
                allowed.add(sorter);
            }
        }
        return allowed;
    }

    /**
     * @return the names of the orders the game's options allow; an order that comes both ways carries an arrow for its
     *       direction
     */
    public List<String> names() {
        List<MekTableSorter> sorters = allowed();
        List<String> names = new ArrayList<>();
        for (MekTableSorter sorter : sorters) {
            String name = sorter.getDisplayName();
            boolean twoWays = sorters.stream().filter(other -> other.getDisplayName().equals(name)).count() > 1;
            names.add(twoWays ? name + (sorter.getSortingDirection() == Sorting.ASCENDING ? " \u2191" : " \u2193")
                  : name);
        }
        return names;
    }

    /** @return the index of the order in use in {@link #names()}, or -1 */
    public int index() {
        return allowed().indexOf(active);
    }

    /** Uses the order at {@code index} in {@link #names()}; an index out of range changes nothing. */
    public void select(int index) {
        List<MekTableSorter> sorters = allowed();
        if (index >= 0 && index < sorters.size()) {
            active = sorters.get(index);
        }
    }

    /** After the game's options changed: when the order in use is no longer allowed, the next allowed one is used. */
    public void settingsChanged() {
        IGameOptions options = client.getGame().getOptions();
        if (active.isAllowed(options)) {
            return;
        }
        int index = unitSorters.indexOf(active);
        MekTableSorter next = index < 0 ? unitSorters.getFirst() : active;
        for (int step = 0; step < MAX_STEPS && !next.isAllowed(options); step++) {
            index = (index + 1) % unitSorters.size();
            next = unitSorters.get(index);
        }
        active = next;
    }

    /** @return every unit of the game in the order in use, trains and C3 networks kept together */
    public List<Entity> sorted() {
        List<Entity> units = new ArrayList<>(client.getEntitiesVector());
        units.sort(MekTableSorter.keepingC3NetworksTogether(units,
              MekTableSorter.keepingCarriedUnitsTogether(active)));
        return units;
    }
}
