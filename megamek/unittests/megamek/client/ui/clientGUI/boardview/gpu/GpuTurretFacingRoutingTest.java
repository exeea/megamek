/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.dispose;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuChoiceRoutingTest.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuChoiceRoutingTest.labels;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuChoiceRoutingTest.pick;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.button;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.components;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuImplantBlowFacingRoutingTest.previewingClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JRadioButton;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.Asked;
import megamek.client.ui.dialogs.TurretFacingDialog;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The turret facing picker (swing-inventory table 2, D12) asked in the native battle window: a Mek's shoulder turret
 * turns, and a vehicle's main turret hands its facing to the attack display, exactly as the Swing dialog does for the
 * same choice, cancelling included; while no native window draws dialogs the Swing dialog shows as before. The test
 * thread plays the GL thread; the Swing dialogs need a display.
 */
@Timeout(120)
class GpuTurretFacingRoutingTest {
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
    void swingTurretFacingTurnsTheMeksTurretOrHandsTheVehiclesFacingOn() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        Turrets turrets = Turrets.create();
        try {
            // The Atlas faces north-east; its left shoulder turret cannot face the right-hand side (east).
            List<String> shown = new ArrayList<>();
            onSwing(() -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.atlas, turrets.turret,
                      turrets.gui);
                dialog.setVisible(true);
                shown.add(dialog.getTitle());
                shown.add(facings(dialog).stream().map(JRadioButton::isEnabled).toList().toString());
                shown.add(selected(dialog).toString());
                facings(dialog).get(5).doClick(0);
                button(dialog, "Okay").doClick(0);
                shown.add("disposed " + !dialog.isDisplayable());
                return null;
            });
            assertEquals(List.of("Turret facing", "[true, true, false, false, true, true]", "[1]", "disposed true"),
                  shown, "The turret's own facing is selected; the facings it cannot take are greyed out");
            verify(turrets.client).sendMountFacingChange(1, turrets.atlas.getEquipmentNum(turrets.turret), 4);
            assertEquals(4, turrets.turret.getFacing(), "North-west is four facings right of the Atlas' north-east");

            onSwing(() -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.atlas, turrets.turret,
                      turrets.gui);
                dialog.setVisible(true);
                facings(dialog).get(3).doClick(0);
                button(dialog, "Cancel").doClick(0);
                return null;
            });
            verify(turrets.client).sendMountFacingChange(anyInt(), anyInt(), anyInt());
            onSwing(() -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.atlas, turrets.turret,
                      turrets.gui);
                dialog.setVisible(true);
                button(dialog, "Okay").doClick(0);
                return null;
            });
            verify(turrets.client).sendMountFacingChange(1, turrets.atlas.getEquipmentNum(turrets.turret), 0);
            verify(turrets.client, times(2)).sendMountFacingChange(anyInt(), anyInt(), anyInt());

            // The Bulldog's main turret turns by a turret twist that the attack display declares.
            List<Integer> handed = new ArrayList<>();
            List<String> vehicle = new ArrayList<>();
            onSwing(() -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.bulldog, turrets.gui,
                      handed::add);
                dialog.setVisible(true);
                vehicle.add(facings(dialog).stream().map(JRadioButton::isEnabled).toList().toString());
                vehicle.add(selected(dialog).toString());
                facings(dialog).get(2).doClick(0);
                button(dialog, "Okay").doClick(0);
                return null;
            });
            assertEquals(List.of("[true, true, true, true, true, true]", "[0]"), vehicle);
            assertEquals(List.of(2), handed);
            onSwing(() -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.bulldog, turrets.gui,
                      handed::add);
                dialog.setVisible(true);
                facings(dialog).get(4).doClick(0);
                button(dialog, "Cancel").doClick(0);
                return null;
            });
            assertEquals(List.of(2), handed, "Cancel hands nothing on");
            verify(turrets.client, never()).sendUpdateEntity(turrets.bulldog);
            assertNull(turrets.fixture.source.dialog(), "No native dialog while the window is not presented");
            assertFalse(turrets.anyShowing(), "Every Swing dialog closed with its button");
        } finally {
            turrets.close();
        }
    }

    @Test
    void nativeTurretFacingTurnsTheSameMountAndHandsOnTheSameFacing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        Turrets turrets = Turrets.create();
        try {
            turrets.presentWindow();
            int turret = turrets.atlas.getEquipmentNum(turrets.turret);
            Asked<Boolean> turned = ask(turrets.fixture.source, () -> turrets.atlasPicker(), pick(0, 5));
            DialogRequest request = turned.request();
            assertEquals(DialogKind.CHOICE, request.kind());
            assertEquals("Turret facing", request.title());
            assertEquals("", request.message());
            assertEquals(List.of("North", "North-East", "South-East", "South", "South-West", "North-West"),
                  labels(request));
            assertEquals(List.of(true, true, false, false, true, true),
                  request.rows().stream().map(DialogRow::enabled).toList(), "Only the facings the turret can take");
            assertEquals(List.of(1), request.initiallySelected(), "The turret's own facing");
            assertEquals(List.of("Okay", "Cancel"), request.buttons());
            assertEquals(1, request.cancelButton(), "Esc cancels, as the Cancel button does");
            assertFalse(turned.result(), "The picker is gone once answered");
            verify(turrets.client).sendMountFacingChange(1, turret, 4);
            assertEquals(4, turrets.turret.getFacing());

            assertFalse(ask(turrets.fixture.source, () -> turrets.atlasPicker(), pick(1, 3)).result());
            verify(turrets.client).sendMountFacingChange(anyInt(), anyInt(), anyInt());
            // A facing the turret cannot take is never chosen: OK keeps its present one, as the Swing OK does.
            ask(turrets.fixture.source, () -> turrets.atlasPicker(), pick(0, 2));
            verify(turrets.client).sendMountFacingChange(1, turret, 0);
            verify(turrets.client, times(2)).sendMountFacingChange(anyInt(), anyInt(), anyInt());

            List<Integer> handed = new ArrayList<>();
            Asked<Boolean> twisted = ask(turrets.fixture.source, () -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.bulldog, turrets.gui,
                      handed::add);
                dialog.setVisible(true);
                return dialog.isDisplayable();
            }, pick(0, 2));
            assertEquals(List.of(true, true, true, true, true, true),
                  twisted.request().rows().stream().map(DialogRow::enabled).toList());
            assertEquals(List.of(0), twisted.request().initiallySelected());
            assertEquals(List.of(2), handed);
            ask(turrets.fixture.source, () -> {
                new TurretFacingDialog(turrets.frame, turrets.bulldog, turrets.gui, handed::add).setVisible(true);
                return true;
            }, pick(1, 4));
            assertEquals(List.of(2), handed, "Cancel hands nothing on");
            assertFalse(turrets.anyShowing(), "No Swing picker showed");
        } finally {
            turrets.close();
        }
    }

    /**
     * The board fixture's Atlas (1) facing north-east with a shoulder turret in its left torso, and a Bulldog (60) of
     * the same player facing north; a client of the fixture's game that draws a picker's unit picture, records what it
     * sends and owns its frame. Its native window is registered but not presented until {@link #presentWindow()}.
     */
    static final class Turrets implements AutoCloseable {
        final GpuBoardFixture fixture;
        final ClientGUI gui = previewingClient();
        final Client client = mock(Client.class);
        final JFrame frame;
        final Mek atlas;
        final Mounted<?> turret;
        final Tank bulldog;

        private Turrets(GpuBoardFixture fixture) throws Exception {
            this.fixture = fixture;
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(gui.getClient()).thenReturn(client);
            frame = clientFrame(gui);
            when(gui.getFrame()).thenReturn(frame);
            atlas = (Mek) fixture.entity;
            turret = onSwing(() -> {
                atlas.setFacing(1);
                return atlas.addEquipment(EquipmentType.get("ISShoulderTurret"), Mek.LOC_LEFT_TORSO);
            });
            Tank tank = (Tank) new MekFileParser(new File("testresources/megamek/common/units/Bulldog Medium Tank.blk"))
                  .getEntity();
            bulldog = onSwing(() -> {
                tank.setId(60);
                tank.setOwner(fixture.player);
                tank.setPosition(new Coords(8, 8));
                tank.setFacing(0);
                tank.setSecondaryFacing(0);
                tank.setDeployed(true);
                fixture.game.addEntity(tank, false);
                return tank;
            });
            GpuDialogRoutingTest.set(present(gui, fixture.view, fixture.source), "presented", false);
        }

        static Turrets create() throws Exception {
            GpuBoardFixture fixture = GpuBoardFixture.create();
            try {
                return new Turrets(fixture);
            } catch (Exception | Error failure) {
                fixture.close();
                throw failure;
            }
        }

        /** Registers this client's native window as presented, so it draws the client's dialogs. */
        void presentWindow() throws Exception {
            present(gui, fixture.view, fixture.source);
        }

        /** EDT: shows the picker of the Atlas' turret, as the attack display's rotate button does; still open? */
        boolean atlasPicker() {
            TurretFacingDialog dialog = new TurretFacingDialog(frame, atlas, turret, gui);
            dialog.setVisible(true);
            return dialog.isDisplayable();
        }

        boolean anyShowing() throws Exception {
            return onSwing(() -> List.of(Window.getWindows()).stream()
                  .anyMatch(window -> window instanceof TurretFacingDialog && window.isShowing()));
        }

        @Override
        public void close() throws Exception {
            try {
                dismiss();
                dispose(frame);
            } finally {
                fixture.close();
            }
        }
    }

    /** EDT: the picker's six facing buttons in facing order (north first), wherever its layout places them. */
    private static List<JRadioButton> facings(Container dialog) {
        return components(dialog, JRadioButton.class).stream()
              .sorted(Comparator.comparingInt(radio -> Integer.parseInt(radio.getActionCommand()))).toList();
    }

    /** EDT: the selected facings. */
    private static List<Integer> selected(Container dialog) {
        return facings(dialog).stream().filter(AbstractButton::isSelected)
              .map(radio -> Integer.parseInt(radio.getActionCommand())).toList();
    }
}
