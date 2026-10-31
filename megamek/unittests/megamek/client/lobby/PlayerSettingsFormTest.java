/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.PlayerSettingsForm.Faction;
import megamek.client.lobby.PlayerSettingsForm.Field;
import megamek.client.lobby.PlayerSettingsForm.GroundObject;
import megamek.client.lobby.PlayerSettingsForm.Position;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.equipment.Briefcase;
import megamek.common.equipment.ICarryable;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerSettingsFormTest {
    private Game game;
    private Player local;
    private Player enemy;
    private Client client;
    private PlayerSettingsForm form;

    @BeforeEach
    void setUp() {
        game = new Game();
        local = new Player(1, "Local");
        local.setTeam(1);
        local.setConstantInitBonus(2);
        local.setStartOffset(1);
        game.addPlayer(1, local);
        enemy = new Player(2, "Enemy");
        enemy.setTeam(2);
        enemy.setStartingPos(5);
        game.addPlayer(2, enemy);
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
        form = new PlayerSettingsForm(client, new LobbyCommands(() -> client, mock(LobbyPrompts.class)),
              board(), List.of(new Faction("IS", "Inner Sphere"), new Faction("FS", "Federated Suns")));
    }

    @Test
    void itStartsFromThePlayersSettings() {
        assertEquals("2", form.text(Field.INIT));
        assertEquals("1", form.text(Field.OFFSET));
        assertEquals(local.getStartingPos(), form.startPos());
        assertEquals(PlayerSettingsForm.DEFAULT_FACTION, form.faction());
        assertFalse(form.bot());
        assertEquals(16, form.boardWidth());
        assertEquals(17, form.boardHeight());
        assertTrue(form.valid());
    }

    @Test
    void thePositionsFollowTheGridAndShowWhoElseDeploysThere() {
        List<Position> positions = form.positions();
        assertEquals(11, positions.size());
        assertEquals(1, positions.get(0).id());
        Position southEast = positions.stream().filter(p -> p.id() == 5).findFirst().orElseThrow();
        assertEquals(List.of(new PlayerSettingsForm.Deployer("Enemy", LobbySnapshot.Relation.ENEMY)),
              southEast.others());
    }

    @Test
    void numbersAreChecked() {
        form.text(Field.OFFSET, "x");
        assertEquals(Optional.of("Lobby.settings.wholeNumber"), form.error(Field.OFFSET));
        form.text(Field.OFFSET, "-1");
        assertEquals(Optional.of("Lobby.settings.notNegative"), form.error(Field.OFFSET));
        assertFalse(form.valid());
        form.text(Field.OFFSET, "3");
        form.text(Field.INIT, "-2");
        assertTrue(form.valid());
    }

    @Test
    void theCustomBoxOnlyCountsForTheAnyPosition() {
        form.startPos(5);
        form.text(Field.ANY_NW_X, "99");
        assertFalse(form.applies(Field.ANY_NW_X));
        assertTrue(form.valid());
        form.startPos(Board.START_ANY);
        assertEquals(Optional.of("Lobby.settings.offBoard"), form.error(Field.ANY_NW_X));
        assertFalse(form.valid());
    }

    @Test
    void anInvalidFormSendsNothing() {
        form.text(Field.INIT, "");
        assertFalse(form.apply());
        verify(client, never()).sendPlayerInfo(local);
        assertEquals(2, local.getConstantInitBonus());
    }

    @Test
    void applyingGivesThePlayerTheSettingsAndSendsThem() {
        form.startPos(5);
        form.text(Field.OFFSET, "2");
        form.text(Field.INIT, "-1");
        form.email("  me@example.com ");
        assertTrue(form.apply());
        assertEquals(5, local.getStartingPos());
        assertEquals(2, local.getStartOffset());
        assertEquals(-1, local.getConstantInitBonus());
        assertEquals("me@example.com", local.getEmail());
        verify(client).sendPlayerInfo(local);
    }

    @Test
    void theCustomBoxCornersAreOrderedAndZeroBased() {
        form.startPos(Board.START_ANY);
        form.text(Field.ANY_NW_X, "10");
        form.text(Field.ANY_NW_Y, "12");
        form.text(Field.ANY_SE_X, "2");
        form.text(Field.ANY_SE_Y, "3");
        assertTrue(form.apply());
        assertEquals(1, local.getStartingAnyNWx());
        assertEquals(2, local.getStartingAnyNWy());
        assertEquals(9, local.getStartingAnySEx());
        assertEquals(11, local.getStartingAnySEy());
    }

    @Test
    void groundObjectsAreAddedAsTheSwingDialogDoesAndPlacedOnApply() {
        form.addGroundObject("A very long cargo name indeed", "lots", true);
        GroundObject added = form.groundObjects().get(0);
        assertEquals(PlayerSettingsForm.MAX_OBJECT_NAME, added.name().length());
        assertEquals(0, added.tonnage());
        assertNull(added.source());
        form.addGroundObject("Crate", "2.5", false);
        form.removeGroundObject(0);
        assertTrue(form.apply());
        List<ICarryable> placed = local.getGroundObjectsToPlace();
        assertEquals(1, placed.size());
        assertTrue(placed.get(0) instanceof Briefcase);
        assertEquals(2.5, placed.get(0).getTonnage());
    }

    private static Board board() {
        Hex[] hexes = new Hex[16 * 17];
        for (int i = 0; i < hexes.length; i++) {
            hexes[i] = new Hex();
        }
        return new Board(16, 17, hexes);
    }
}
