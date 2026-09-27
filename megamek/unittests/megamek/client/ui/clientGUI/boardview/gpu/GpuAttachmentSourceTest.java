/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.Vector;
import javax.swing.SwingUtilities;

import megamek.common.ResolvedAttack;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.battleArmor.BattleArmorHandles;
import megamek.common.event.GameAttackResolvedEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;

class GpuAttachmentSourceTest {
    @Test
    void nullPositionExteriorPassengersAreCapturedAndAnUnloadKeepsBothEndpoints() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            var armor = armor(fixture);
            var handles = org.mockito.Mockito.mock(BattleArmorHandles.class);
            SwingUtilities.invokeAndWait(() -> {
                org.mockito.Mockito.when(handles.getExternalUnits()).thenReturn(List.of(armor));
                fixture.entity.addTransporter(handles);
                armor.setTransportId(fixture.entity.getId());
                armor.setPosition(null);
                fixture.source.refresh();
            });
            var frame = fixture.source.takeFrame();
            var passenger = frame.scene().units().stream().filter(unit -> unit.id() == armor.getId()).findFirst().orElseThrow();
            assertEquals(new BoardScene.Attachment(fixture.entity.getId(), false), passenger.attachment());
            assertEquals(fixture.entity.getPosition(), passenger.location().coords());
            assertTrue(frame.animations().stream().anyMatch(BoardScene.AttachmentChange.class::isInstance));
            var destination = fixture.entity.getPosition().translated(0);
            SwingUtilities.invokeAndWait(() -> {
                org.mockito.Mockito.when(handles.getExternalUnits()).thenReturn(List.of());
                armor.setTransportId(Entity.NONE);
                armor.setPosition(destination);
                fixture.source.refresh();
            });
            var unload = fixture.source.takeFrame().animations().stream().filter(BoardScene.AttachmentChange.class::isInstance)
                  .map(BoardScene.AttachmentChange.class::cast).findFirst().orElseThrow();
            assertEquals(BoardScene.Release.CLIMB_DOWN, unload.release());
            assertEquals(passenger.location(), unload.before().location());
            assertEquals(destination, unload.destination().coords());
        }
    }

    @Test
    void concealedSwarmersCannotBeRestoredByADeferredShakeAttempt() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            var armor = armor(fixture);
            SwingUtilities.invokeAndWait(() -> {
                var enemy = new megamek.common.Player(2, "Enemy");
                enemy.setTeam(2);
                fixture.game.addPlayer(2, enemy);
                armor.setOwner(enemy);
                fixture.entity.setSwarmAttackerId(armor.getId());
                armor.setSwarmTargetId(fixture.entity.getId());
                fixture.source.refresh();
                var at = fixture.entity.getPosition();
                var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHAKE_OFF,
                      new UnitLocation(1, at, 0, 0, 0), new UnitLocation(2, at, 0, 0, 0),
                      Targetable.TYPE_ENTITY, -1, "", Entity.LOC_NONE, false);
                fixture.game.processGameEvent(new GameAttackResolvedEvent(this, result, fixture.entity, armor));
                armor.setHidden(true);
                fixture.source.refresh();
            });
            fixture.source.takeFrame();
            SwingUtilities.invokeAndWait(() -> {
                var at = fixture.entity.getPosition();
                fixture.entity.setPosition(at.translated(0));
                fixture.entity.moved = EntityMovementType.MOVE_WALK;
                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fixture.entity, new Vector<>(List.of(
                      new UnitLocation(1, at, 0, 0, 0), new UnitLocation(1, at.translated(0), 0, 0, 0)))));
            });
            var frame = fixture.source.takeFrame();
            assertTrue(frame.scene().units().stream().noneMatch(unit -> unit.id() == armor.getId()));
            assertTrue(frame.animations().stream().noneMatch(BoardScene.Combat.class::isInstance));
        }
    }

    @Test
    void waterReleaseSplitsTheObservedRouteAtTheDropHex() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            var armor = armor(fixture);
            var start = fixture.entity.getPosition();
            var water = start.translated(0);
            var end = water.translated(0);
            SwingUtilities.invokeAndWait(() -> {
                var hex = fixture.game.getBoard().getHex(water);
                hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.WATER, 2));
                fixture.game.getBoard().setHex(water, hex);
                fixture.entity.setSwarmAttackerId(armor.getId());
                armor.setSwarmTargetId(fixture.entity.getId());
                fixture.source.refresh();
            });
            fixture.source.takeFrame();
            SwingUtilities.invokeAndWait(() -> {
                armor.setSwarmTargetId(Entity.NONE);
                armor.setPosition(water);
                fixture.source.refresh();
                fixture.entity.setSwarmAttackerId(Entity.NONE);
                fixture.entity.setPosition(end);
                fixture.entity.moved = EntityMovementType.MOVE_WALK;
                var path = new Vector<>(List.of(new UnitLocation(1, start, 0, 0, 0), new UnitLocation(1, water, 0, -2, 0),
                      new UnitLocation(1, end, 0, 0, 0)));
                fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fixture.entity, path));
            });
            var events = fixture.source.takeFrame().animations();
            assertEquals(3, events.size());
            assertEquals(water, ((BoardScene.Movement) events.get(0)).path().getLast().coords());
            var release = (BoardScene.AttachmentChange) events.get(1);
            assertEquals(BoardScene.Release.WATER, release.release());
            assertEquals(water, release.carrier().location().coords());
            assertEquals(water, release.destination().coords());
            assertEquals(end, ((BoardScene.Movement) events.get(2)).path().getLast().coords());
        }
    }

    @Test
    void earlyPassengerUpdateWaitsForJumpPathAndFailedShakeHasItsOwnConfirmedClip() throws Exception {
        for (boolean detached : List.of(false, true)) {
            try (var fixture = GpuBoardFixture.create()) {
                var armor = armor(fixture);
                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.setSwarmAttackerId(armor.getId());
                    armor.setSwarmTargetId(fixture.entity.getId());
                    fixture.source.refresh();
                });
                fixture.source.takeFrame();
                var start = fixture.entity.getPosition();
                var destination = start.translated(0);
                SwingUtilities.invokeAndWait(() -> {
                    if (!detached) {
                        var origin = new UnitLocation(fixture.entity.getId(), start, 0, 0, 0);
                        var target = new UnitLocation(armor.getId(), start, 0, 0, 0);
                        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHAKE_OFF, origin, target,
                              Targetable.TYPE_ENTITY, -1, "", Entity.LOC_NONE, false);
                        fixture.game.processGameEvent(new GameAttackResolvedEvent(this, result, fixture.entity, armor));
                    }
                    if (detached) { armor.setSwarmTargetId(Entity.NONE); }
                    armor.setPosition(destination);
                    fixture.source.refresh();
                });
                var early = fixture.source.takeFrame();
                assertFalse(early.animations().stream().anyMatch(BoardScene.AttachmentChange.class::isInstance));
                assertNotNull(early.scene().units().stream().filter(unit -> unit.id() == armor.getId()).findFirst().orElseThrow().attachment());
                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.setPosition(destination);
                    fixture.entity.moved = detached ? EntityMovementType.MOVE_JUMP : EntityMovementType.MOVE_WALK;
                    if (detached) { fixture.entity.setSwarmAttackerId(Entity.NONE); }
                    var path = new Vector<>(List.of(new UnitLocation(1, start, 0, 0, 0), new UnitLocation(1, destination, 0, 0, 0)));
                    fixture.game.fireGameEvent(new GameEntityChangeEvent(fixture.game, fixture.entity, path));
                });
                var captured = fixture.source.takeFrame();
                var actions = captured.animations();
                assertTrue(actions.getFirst() instanceof BoardScene.Movement);
                if (detached) {
                    var change = actions.stream().filter(BoardScene.AttachmentChange.class::isInstance)
                          .map(BoardScene.AttachmentChange.class::cast).findFirst().orElseThrow();
                    assertEquals(BoardScene.Release.JUMP, change.release());
                    assertEquals(destination, change.destination().coords());
                } else {
                    var clip = actions.stream().filter(BoardScene.Combat.class::isInstance).map(BoardScene.Combat.class::cast)
                          .findFirst().orElseThrow();
                    assertEquals(ResolvedAttack.Kind.SHAKE_OFF, clip.result().kind());
                    assertFalse(clip.result().hit());
                    assertEquals(destination, clip.attacker().location().coords());
                    assertFalse(actions.stream().anyMatch(BoardScene.AttachmentChange.class::isInstance));
                }
            }
        }
    }

    private static BattleArmor armor(GpuBoardFixture fixture) throws Exception {
        var armor = new BattleArmor();
        SwingUtilities.invokeAndWait(() -> {
            armor.setId(2);
            armor.setChassis("Attachment review");
            armor.setModel("Test");
            armor.setOwner(fixture.player);
            armor.setSquadSize(4);
            for (int member = 1; member <= 4; member++) { armor.initializeInternal(1, member); }
            armor.setPosition(fixture.entity.getPosition());
            armor.setDeployed(true);
            fixture.game.addEntity(armor, false);
            fixture.view.redrawAllEntities();
            fixture.source.refresh();
        });
        fixture.source.takeFrame();
        return armor;
    }
}
