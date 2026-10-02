/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFieldOfFireCharacterizationTest.show;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuFiringFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Set;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.spriteHandler.FiringArcSpriteHandler;
import megamek.common.Configuration;
import megamek.common.board.Coords;
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
 * The firing-arc handler's field of fire as hex sets (stage E3a), for the weapons GpuFieldOfFireCharacterizationTest
 * outlines: every hex of each range bracket, whatever the field of fire setting, and none once the values are cleared.
 */
@Timeout(120)
class GpuFieldOfFireTest {
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
    void theFieldOfFireHoldsEveryHexOfEachBracket() throws Exception {
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
                show(handler, gui, fixture, atlas, laser);
                String forward = sizes(handler.fieldOfFire());
                atlas.setSecondaryFacing(1);
                show(handler, gui, fixture, atlas, laser);
                String twisted = sizes(handler.fieldOfFire());
                atlas.setSecondaryFacing(0);
                show(handler, gui, fixture, atlas, small);
                String thin = sizes(handler.fieldOfFire());
                Set<Coords> shortRange = handler.fieldOfFire().get(1);
                preferences.setShowFieldOfFire(false);
                String outline = show(handler, gui, fixture, atlas, small);
                String switchedOff = sizes(handler.fieldOfFire());
                String sameHexes = String.valueOf(shortRange.equals(handler.fieldOfFire().get(1)));
                handler.clearValues();
                return List.of(forward, twisted, thin, outline, switchedOff, sameHexes, sizes(handler.fieldOfFire()));
            });
            assertEquals(List.of(
                  // minimum (none), short, medium, long; the outline counts were 16, 33 and 31 of these hexes
                  "[0, 21, 46, 40]",
                  "[0, 21, 47, 60]",
                  // the old outline counts 4, 7 and 10: every hex of these one-hex brackets is outlined
                  "[0, 4, 7, 10]",
                  // with the field of fire setting off nothing is drawn, the hexes are the same
                  "[]", "[0, 4, 7, 10]", "true",
                  // cleared
                  "[]"), observed);
        } finally {
            onSwing(() -> {
                preferences.setShowFieldOfFire(shown);
                return null;
            });
        }
    }

    private static String sizes(List<Set<Coords>> brackets) {
        return brackets.stream().map(Set::size).toList().toString();
    }
}
