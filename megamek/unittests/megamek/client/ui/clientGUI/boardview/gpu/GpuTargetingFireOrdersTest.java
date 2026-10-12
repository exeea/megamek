/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import megamek.common.Configuration;
import megamek.common.HexTarget;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The 3D board's weapons panel in the Targeting phase, where artillery declares its attacks: it lists the unit's
 * weapons, switches the loaded ammunition and declares a shot at a hex, through the Targeting phase's display.
 */
@Timeout(180)
class GpuTargetingFireOrdersTest {
    /**
     * The Long Tom's place and target on the fixture's 16x17 board: opposite corners, more than 17 hexes apart. On the
     * map, artillery declares in the Targeting phase only beyond 17 hexes; nearer targets are shot in the Firing phase.
     */
    private static final Coords LONG_TOM_HEX = new Coords(0, 0);
    private static final Coords TARGET_HEX = new Coords(15, 16);
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
    void thePanelListsTheArtilleryUnitsWeaponsInTheTargetingPhase() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            GpuFireOrders.Snapshot orders = capture(targeting);
            assertTrue(orders.active(), "The panel is active in the local Targeting turn");
            assertEquals(targeting.attacker.getId(), orders.actorId());
            GpuFireOrders.WeaponRow longTom = row(orders, eqNum(targeting));
            assertTrue(longTom.usable(), "The Long Tom may be assigned in the Targeting phase");
            assertFalse(longTom.ammo().isEmpty(), "Its ammunition is offered to switch between");
        }
    }

    /** Outside the attack phases the panel stays off: the Targeting turn above is not just any turn. */
    @Test
    void thePanelIsOffOutsideTheAttackPhases() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            onSwing(() -> {
                targeting.board.game.setPhase(GamePhase.MOVEMENT);
                return null;
            });
            assertFalse(capture(targeting).active());
        }
    }

    /** Picking another bin in the panel loads it into the Long Tom, as the Unit Display's ammunition list does. */
    @Test
    void thePanelSwitchesTheLongTomsAmmunition() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            AmmoMounted cluster = onSwing(() -> {
                AmmoType clusterAmmo = AmmoType.getMunitionsFor(AmmoType.AmmoTypeEnum.LONG_TOM).stream()
                      .filter(ammo -> ammo.getMunitionType().contains(AmmoType.Munitions.M_CLUSTER))
                      .findFirst().orElseThrow();
                int location = freeLocation(targeting);
                return (AmmoMounted) targeting.attacker.addEquipment(clusterAmmo, location);
            });
            int longTom = eqNum(targeting);
            GpuUnitRecord.AmmoChoice clusterChoice = row(capture(targeting), longTom).ammo().stream()
                  .filter(choice -> choice.eqNum() == targeting.attacker.getEquipmentNum(cluster))
                  .findFirst().orElseThrow();
            targeting.source.fire().setAmmo(longTom, clusterChoice);
            capture(targeting);
            assertEquals(cluster, onSwing(() -> targeting.longTom.getLinkedAmmo()), "The cluster bin is loaded");
        }
    }

    /** Assigning the Long Tom to a hex queues its artillery attack there through the Targeting display's Fire. */
    @Test
    void thePanelDeclaresTheLongTomAtAHex() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            int longTom = eqNum(targeting);
            Targetable hex = new HexTarget(TARGET_HEX, targeting.attacker.getBoardId(), Targetable.TYPE_HEX_ARTILLERY);
            onSwing(() -> {
                targeting.attacker.setPosition(LONG_TOM_HEX);
                // Facing south, the far corner to the south-east lies in the left arm's arc.
                targeting.attacker.setFacing(3);
                targeting.attacker.setSecondaryFacing(3);
                return null;
            });
            assertTrue(LONG_TOM_HEX.distance(TARGET_HEX) > 17, "Far enough for the Targeting phase");
            // The panel acts for the unit it shows, as a player's click on it does.
            capture(targeting);
            targeting.source.fire().assign(longTom, TargetKey.of(hex));
            GpuFireOrders.Snapshot orders = capture(targeting);
            List<EntityAction> queue = onSwing(targeting.display::getAttacks);
            String seen = onSwing(() -> "target " + targeting.board.game.getTarget(hex.getTargetType(), hex.getId())
                  + ", to-hit " + targeting.display.toHitFor(targeting.longTom, hex).getDesc()
                  + ", display target " + targeting.display.getTarget() + ", fire allowed "
                  + targeting.display.isFireAllowed() + ", selected " + targeting.unitDisplay.getSelectedWeapon());
            assertEquals(1, queue.size(), "One attack queued; " + seen);
            ArtilleryAttackAction attack = (ArtilleryAttackAction) queue.getFirst();
            assertEquals(longTom, attack.getWeaponId());
            assertEquals(TARGET_HEX, attack.getTarget(targeting.board.game).getPosition());
            assertEquals(1, orders.attacks().size(), "The panel lists the queued attack");
        }
    }

    private static GpuFireOrders.Snapshot capture(GpuTargetingFixture targeting) throws Exception {
        return onSwing(() -> targeting.source.fire().capture(targeting.display, null));
    }

    /** A location of the attacker with a free critical slot for one more ammunition bin. */
    private static int freeLocation(GpuTargetingFixture targeting) {
        for (int location = 0; location < targeting.attacker.locations(); location++) {
            if (targeting.attacker.getEmptyCriticalSlots(location) > 0) {
                return location;
            }
        }
        throw new IllegalStateException("The attacker has no free critical slot");
    }

    private static int eqNum(GpuTargetingFixture targeting) {
        return targeting.attacker.getEquipmentNum(targeting.longTom);
    }

    private static GpuFireOrders.WeaponRow row(GpuFireOrders.Snapshot orders, int eqNum) {
        GpuFireOrders.WeaponRow found = orders.weapons().stream().filter(row -> row.eqNum() == eqNum).findFirst()
              .orElse(null);
        assertNotNull(found, "The weapon has a row");
        return found;
    }
}
