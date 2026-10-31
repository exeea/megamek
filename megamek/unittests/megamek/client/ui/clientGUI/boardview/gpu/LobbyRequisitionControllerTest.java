/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.Client;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.RequisitionSkills;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LobbyRequisitionControllerTest {

    private static final RequisitionSkills SKILLS = RequisitionSkills.initial(false);

    private final Player local = new Player(1, "Local");
    private final Player bot = new Player(2, "Bot");
    private final AtomicInteger reads = new AtomicInteger();
    private Client client;
    private Game game;
    private LobbyCommands commands;
    private LobbyRequisitionController controller;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        game = mock(Game.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(game.getPlayer(2)).thenReturn(bot);
        commands = mock(LobbyCommands.class);
        when(commands.addUnits(anyList(), any(), any())).thenReturn(bot);
        controller = new LobbyRequisitionController(() -> client, commands, unit -> {
            reads.incrementAndGet();
            return mock(Entity.class);
        });
    }

    @Test
    void quantitiesStayBetweenOneAndTwelve() {
        assertEquals(1, LobbyRequisitionController.clampQuantity(0));
        assertEquals(5, LobbyRequisitionController.clampQuantity(5));
        assertEquals(LobbyRequisitionController.MAX_QUANTITY, LobbyRequisitionController.clampQuantity(40));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAddReadsEachCopyFromItsFileAndSendsThemTogether() {
        LobbyRequisitionController.Outcome outcome = controller.add(new MekSummary(), 3, 2, SKILLS);

        assertEquals(new LobbyRequisitionController.Outcome(2, 3), outcome);
        assertEquals(3, reads.get());
        ArgumentCaptor<List<Entity>> sent = ArgumentCaptor.forClass(List.class);
        verify(commands).addUnits(sent.capture(), eq(bot), eq(SKILLS));
        assertEquals(3, sent.getValue().size());
    }

    @Test
    void anUnreadableUnitAddsNothing() {
        controller = new LobbyRequisitionController(() -> client, commands, unit -> null);
        assertEquals(LobbyRequisitionController.Outcome.NONE, controller.add(new MekSummary(), 2, 2, SKILLS));
        verify(commands, never()).addUnits(anyList(), any(), any());
    }

    @Test
    void nothingIsAddedBeforeTheClientHasAPlayer() {
        when(client.getLocalPlayer()).thenReturn(null);
        assertEquals(LobbyRequisitionController.Outcome.NONE, controller.add(new MekSummary(), 1, 1, SKILLS));
        assertEquals(0, reads.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeDeletesTheUnitWithoutAsking() {
        Entity unit = mock(Entity.class);
        when(game.getEntity(7)).thenReturn(unit);

        controller.remove(7);
        controller.remove(8);

        ArgumentCaptor<List<Entity>> deleted = ArgumentCaptor.forClass(List.class);
        verify(commands).delete(anyList(), deleted.capture(), eq(false));
        assertEquals(new ArrayList<>(List.of(unit)), deleted.getValue());
    }

    @Test
    void theResultsColumnsGoFromTheLastAsTheRowNarrows() {
        float[] widths = { 60, 40, 80, 70 };
        float all = RequisitionLayout.NAME_MIN + 4 * 10 + 60 + 40 + 80 + 70;
        assertEquals(4, RequisitionLayout.columns(all, widths, 10));
        assertEquals(3, RequisitionLayout.columns(all - 1, widths, 10));
        assertEquals(1, RequisitionLayout.columns(RequisitionLayout.NAME_MIN + 70 + 49, widths, 10));
        assertEquals(0, RequisitionLayout.columns(RequisitionLayout.NAME_MIN + 69, widths, 10));
        assertEquals(0, RequisitionLayout.columns(0, widths, 10));
    }

    @Test
    void theDossierDocksBesideTheResultsOrOverThem() {
        RequisitionLayout.Dock shut = RequisitionLayout.dock(1200, false, 16);
        assertEquals(new RequisitionLayout.Dock(1200, 1200, 0, false), shut);

        RequisitionLayout.Dock wide = RequisitionLayout.dock(1200, true, 16);
        assertEquals(540, wide.dossierWidth(), .01f);
        assertEquals(1200 - 540 - 16, wide.resultsWidth(), .01f);
        assertEquals(660, wide.dossierX(), .01f);

        RequisitionLayout.Dock narrow = RequisitionLayout.dock(700, true, 16);
        assertEquals(true, narrow.overlay());
        assertEquals(700, narrow.resultsWidth(), .01f);
        assertEquals(160, narrow.dossierX(), .01f);
        assertEquals(540, narrow.dossierWidth(), .01f);

        assertEquals(0, RequisitionLayout.dock(-5, true, 16).resultsWidth(), .01f);
    }
}
