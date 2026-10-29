/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;

import megamek.client.Client;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbyUnitOrderTest {

    private Game game;
    private Client client;
    private LobbyUnitOrder order;

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        Player local = new Player(1, "Local");
        game.addPlayer(1, local);
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(new HashMap<>());
        when(client.getEntitiesVector()).thenAnswer(call -> game.getEntitiesVector());
        order = new LobbyUnitOrder(client);
    }

    @Test
    void ordersThatComeBothWaysCarryArrows() {
        List<String> names = order.names();

        assertEquals(18, names.size());
        assertEquals(0, order.index());
        assertTrue(names.stream().anyMatch(name -> name.endsWith(" \u2191")));
        assertTrue(names.stream().anyMatch(name -> name.endsWith(" \u2193")));
        assertFalse(names.getFirst().endsWith(" \u2191"), names.getFirst());
    }

    @Test
    void selectIgnoresIndicesOutOfRange() {
        order.select(5);
        assertEquals(5, order.index());

        order.select(-1);
        order.select(99);
        assertEquals(5, order.index());
    }

    @Test
    void aBlindDropHidesOrdersByBvAndTonnage() {
        blindDrop(true);

        assertEquals(14, order.names().size());
    }

    @Test
    void aBlindDropMovesAnOrderByTonnageToTheNextAllowedOne() {
        List<String> names = order.names();
        order.select(11);
        String c3 = names.get(13);

        blindDrop(true);
        order.settingsChanged();

        assertEquals(c3, order.names().get(order.index()));
    }

    @Test
    void sortedFollowsTheSelectedOrder() {
        Entity light = mek(20, "Locust");
        Entity heavy = mek(80, "Atlas");
        List<String> names = order.names();

        order.select(11);
        assertEquals(List.of(light, heavy), order.sorted(), names.get(11));
        order.select(12);
        assertEquals(List.of(heavy, light), order.sorted(), names.get(12));
    }

    private Entity mek(int weight, String chassis) {
        BipedMek mek = new BipedMek();
        mek.setChassis(chassis);
        mek.setModel("X");
        mek.setWeight(weight);
        mek.setOwner(game.getPlayer(1));
        game.addEntity(mek);
        return mek;
    }

    private void blindDrop(boolean on) {
        game.getOptions().getOption(OptionsConstants.BASE_BLIND_DROP).setValue(on);
    }
}
