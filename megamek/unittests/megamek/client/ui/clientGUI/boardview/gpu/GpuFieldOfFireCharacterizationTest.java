/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.sprite.FieldOfFireSprite;
import megamek.client.ui.clientGUI.boardview.sprite.TextMarkerSprite;
import megamek.client.ui.clientGUI.boardview.spriteHandler.FiringArcSpriteHandler;
import megamek.common.Configuration;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Characterizes the field of fire the firing-arc sprite handler draws for the board fixture's Atlas AS7-D at (5, 5)
 * facing north in the firing phase: per range bracket (0 minimum, 1 short, 2 medium, 3 long) the outlined hexes and
 * edges and the range labels, for a medium laser in the right arm, the same laser after a right torso twist, and a
 * small laser in the left arm (short, medium and long one hex each, so every hex of a bracket is outlined). The
 * values were recorded on the code before stage E3a extracted the hex sets into fieldOfFire(), and must not change.
 */
@Timeout(120)
class GpuFieldOfFireCharacterizationTest {
    private static File originalDataDir;

    /** Earlier test classes can leave the data folder on testresources; the fixture needs the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    @Test
    void eachBracketKeepsItsOutlineAndLabels() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean shown = preferences.getShowFieldOfFire();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity atlas = fixture.entity;
            WeaponMounted laser = weapon(atlas, "Medium Laser", Mek.LOC_RIGHT_ARM);
            WeaponMounted small = onSwing(() -> (WeaponMounted) atlas.addEquipment(
                  EquipmentType.get("ISSmallLaser"), Mek.LOC_LEFT_ARM));
            ClientGUI gui = mock(ClientGUI.class);
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(fixture.game);
            when(gui.getClient()).thenReturn(client);
            when(gui.boardStates()).thenReturn(List.of(fixture.view));
            when(gui.getBoardState(any(Targetable.class))).thenReturn(fixture.view);
            List<String> observed = onSwing(() -> {
                preferences.setShowFieldOfFire(true);
                fixture.game.setPhase(GamePhase.FIRING);
                atlas.setFacing(0);
                atlas.setSecondaryFacing(0);
                FiringArcSpriteHandler handler = new FiringArcSpriteHandler(gui);
                String forward = show(handler, gui, fixture, atlas, laser);
                atlas.setSecondaryFacing(1);
                String twisted = show(handler, gui, fixture, atlas, laser);
                atlas.setSecondaryFacing(0);
                String thin = show(handler, gui, fixture, atlas, small);
                handler.clearValues();
                return List.of(forward, twisted, thin);
            });
            assertEquals(List.of(
                  "1:16/38 2:33/72 3:31/78 [1@4,4, 1@6,4, 1@7,5, 2@10,5, 2@3,1, 2@7,1, 3@13,5, 3@2,0, 3@9,0]",
                  // The twist turns the arc clockwise; the long bracket no longer runs off the north edge as much
                  "1:16/38 2:33/72 3:43/94 [1@6,4, 1@6,7, 1@7,5, 2@10,5, 2@8,2, 2@8,9, 3@13,5, 3@9,0, 3@9,11]",
                  // One hex per bracket: each bracket's hexes are all outlined (4, 7 and 10 hexes)
                  "1:4/18 2:7/30 3:10/42 [1@4,5, 1@5,4, 1@5,4, 1@6,5, 2@3,5, 2@4,4, 2@6,4, 3@2,5, 3@4,3, 3@6,3]"),
                  observed);
        } finally {
            onSwing(() -> {
                preferences.setShowFieldOfFire(shown);
                return null;
            });
        }
    }

    /**
     * EDT: shows the weapon's field of fire and returns, per bracket, the outlined hexes and edges, then the range
     * labels as bracket@hex.
     */
    static String show(FiringArcSpriteHandler handler, ClientGUI gui, GpuBoardFixture fixture, Entity unit,
          WeaponMounted weapon) {
        when(gui.getDisplayedWeapon()).thenReturn(Optional.of(weapon));
        handler.update(unit, weapon);
        Map<Integer, int[]> outline = new TreeMap<>();
        fixture.view.getAllSprites().stream().filter(FieldOfFireSprite.class::isInstance)
              .map(FieldOfFireSprite.class::cast).filter(FieldOfFireSprite::isWeaponRange).forEach(sprite -> {
                  int[] counts = outline.computeIfAbsent(sprite.getRangeBracket(), bracket -> new int[2]);
                  counts[0]++;
                  counts[1] += Integer.bitCount(sprite.getBorders());
              });
        StringBuilder text = new StringBuilder();
        outline.forEach((bracket, counts) -> text.append(bracket).append(':').append(counts[0]).append('/')
              .append(counts[1]).append(' '));
        text.append(fixture.view.getAllSprites().stream().filter(TextMarkerSprite.class::isInstance)
              .map(TextMarkerSprite.class::cast)
              .map(label -> label.getRangeBracket() + "@" + label.getPosition().getX() + "," + label.getPosition()
                    .getY()).sorted().toList());
        return text.toString();
    }
}
