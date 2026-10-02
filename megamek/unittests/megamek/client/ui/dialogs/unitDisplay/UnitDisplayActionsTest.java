/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.unitDisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JToggleButton;
import javax.swing.JTextArea;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.SliderDialog;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.INarcPod;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.NarcPod;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/**
 * What the Unit Display shows and what each of its unit action controls sends, for real units (characterization of
 * the Swing panels).
 */
class UnitDisplayActionsTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static File originalDataDir;

    private final Game game = new Game();
    private final Player local = new Player(0, "Local");
    private final Player enemy = new Player(1, "Enemy");
    private final Client client = mock(Client.class);
    private final ClientGUI gui = mock(ClientGUI.class);
    private final UnitDisplayPanel display = mock(UnitDisplayPanel.class);
    private final RulesManager rules = Game.rulesManager;
    private final List<WeaponPanel> weaponPanels = new ArrayList<>();
    private Entity atlas;

    /** Earlier test classes can leave the data folder on testresources; the panels read the staged widget images. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    @BeforeEach
    void setUp() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        game.setBoard(board);
        game.setRoundCount(1);
        enemy.setTeam(2);
        game.addPlayer(local.getId(), local);
        game.addPlayer(enemy.getId(), enemy);
        atlas = unit(1, local);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(gui.getClient()).thenReturn(client);
        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
        when(display.getClientGUI()).thenReturn(gui);
        onSwing(() -> {
            display.wPan = weaponPanel();
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        Game.rulesManager = rules;
        onSwing(() -> {
            weaponPanels.forEach(GUIPreferences.getInstance()::removePreferenceChangeListener);
            return null;
        });
    }

    @Test
    void anEcmModeChoiceSendsTheModeChange() throws Exception {
        Mounted<?> ecm = atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_LEFT_ARM);
        // ECM modes are shared type state that each game sets from its options, as it does when a unit joins it.
        atlas.setGameOptions();
        game.setPhase(GamePhase.MOVEMENT);
        onSwing(() -> {
            SystemPanel panel = new SystemPanel(display);
            panel.displayMek(atlas);
            JList<?> slots = field(panel, "slotList");
            slots.setSelectedIndex(atlas.getMisc().indexOf(ecm));
            JComboBox<?> modes = field(panel, "m_chMode");
            assertEquals(List.of("ECM", "Off"), items(modes));
            assertTrue(modes.isEnabled());
            modes.setSelectedIndex(1);
            return null;
        });
        verify(client).sendModeChange(1, atlas.getEquipmentNum(ecm), 1);
        assertEquals("Off", ecm.pendingMode().getName());
        verify(gui).systemMessage("ECM Suite (Guardian) will switch to Off at end of turn.");
        verify(gui).addToast(ToastLevel.INFO, "ECM Suite (Guardian) -> Off", atlas);
    }

    @Test
    void aVibrobladeIsSwitchedOnlyInThePhysicalPhase() throws Exception {
        Mounted<?> blade = atlas.addEquipment(EquipmentType.get("ISMediumVibroblade"), Mek.LOC_RIGHT_ARM);
        int bladeNum = atlas.getEquipmentNum(blade);
        game.setPhase(GamePhase.MOVEMENT);
        onSwing(() -> {
            selectMode(blade, 1);
            return null;
        });
        verify(client, never()).sendModeChange(anyInt(), anyInt(), anyInt());
        verify(gui).systemMessage(Messages.getString("MekDisplay.VibrobladeModePhase"));

        game.setPhase(GamePhase.PHYSICAL);
        onSwing(() -> {
            selectMode(blade, 1);
            return null;
        });
        verify(client).sendModeChange(1, bladeNum, 1);
        assertEquals("Active", blade.curMode().getName());
    }

    @Test
    void ammoIsDumpedOnlyAfterMovementAndAfterTheOwnerConfirms() throws Exception {
        game.initializeRulesManager(OptionsConstants.RULES_TW);
        Mounted<?> ammo = atlas.getCritical(Mek.LOC_RIGHT_TORSO, 10).getMount();
        int ammoNum = atlas.getEquipmentNum(ammo);
        game.setPhase(GamePhase.MOVEMENT);
        assertFalse(onSwing(() -> dumpButton(ammo).isEnabled()));

        game.setPhase(GamePhase.FIRING);
        onSwing(() -> {
            var dump = dumpButton(ammo);
            assertTrue(dump.isEnabled());
            dump.doClick();
            return null;
        });
        verify(gui).doYesNoDialog(Messages.getString("MekDisplay.Dump.title"),
              Messages.getString("MekDisplay.Dump.message", ammo.getName()));
        verify(client).sendModeChange(1, ammoNum, -1);
        assertTrue(ammo.isPendingDump());

        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(false);
        onSwing(() -> {
            dumpButton(ammo).doClick();
            return null;
        });
        verify(client, never()).sendModeChange(1, ammoNum, 0);
        assertTrue(ammo.isPendingDump(), "Answering no keeps the dump");

        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
        onSwing(() -> {
            dumpButton(ammo).doClick();
            return null;
        });
        verify(gui, times(2)).doYesNoDialog(Messages.getString("MekDisplay.CancelDumping.title"),
              Messages.getString("MekDisplay.CancelDumping.message", ammo.getName()));
        verify(client).sendModeChange(1, ammoNum, 0);
        assertFalse(ammo.isPendingDump());
    }

    @Test
    void aSensorChoiceSendsTheSensorChange() throws Exception {
        game.setPhase(GamePhase.MOVEMENT);
        onSwing(() -> {
            ExtraPanel panel = new ExtraPanel(display);
            panel.displayMek(atlas);
            JComboBox<?> sensors = field(panel, "chSensors");
            assertEquals(List.of("Mek Radar", "Mek IR", "Mek Magscan", "Mek Seismic"), items(sensors));
            assertTrue(sensors.isEnabled());
            sensors.setSelectedIndex(1);
            return null;
        });
        verify(client).sendSensorChange(1, 1);
        assertEquals(atlas.getSensors().get(1), atlas.getNextSensor());
        verify(gui).systemMessage("Active Sensors will switch to Mek IR at end of turn.");
    }

    @Test
    void aHeatSinkChoiceSendsTheActiveSinks() throws Exception {
        // The control asks with a modal slider dialog. Its construction is mocked to answer 10 as the player would; a
        // mocked construction still runs a JDialog constructor, which needs a display.
        assumeFalse(GraphicsEnvironment.isHeadless(), "The heat sink dialog needs a display");
        CommonMenuBar menuBar = mock(CommonMenuBar.class);
        when(gui.getMenuBar()).thenReturn(menuBar);
        game.setPhase(GamePhase.MOVEMENT);
        List<Object> slider = onSwing(() -> {
            List<Object> arguments = new ArrayList<>();
            // Mockito mocks constructions only on the thread that opens the mock, here the EDT that clicks.
            try (MockedConstruction<SliderDialog> dialogs = mockConstruction(SliderDialog.class, (dialog, context) -> {
                arguments.addAll(context.arguments().subList(3, 6));
                when(dialog.showDialog()).thenReturn(true);
                when(dialog.getValue()).thenReturn(10);
            })) {
                ExtraPanel panel = new ExtraPanel(display);
                panel.displayMek(atlas);
                JButton sinks = field(panel, "sinks2B");
                assertTrue(sinks.isEnabled());
                sinks.doClick();
                assertEquals(1, dialogs.constructed().size());
            }
            return arguments;
        });
        assertEquals(List.of(20, 0, 20), slider, "Current, fewest and most active heat sinks");
        verify(client).sendSinksChange(1, 10);
        assertEquals(10, ((Mek) atlas).getActiveSinksNextRound());
        // The control also hands its action to the menu bar's listeners, among them the current phase display.
        verify(menuBar).actionPerformed(argThat(event -> "changeSinks".equals(event.getActionCommand())));
    }

    @Test
    void activatingAHiddenUnitSendsTheChosenPhase() throws Exception {
        atlas.setHidden(true);
        game.setPhase(GamePhase.MOVEMENT);
        onSwing(() -> {
            ExtraPanel panel = new ExtraPanel(display);
            panel.displayMek(atlas);
            assertEquals(List.of(GamePhase.UNKNOWN, GamePhase.MOVEMENT, GamePhase.FIRING, GamePhase.PHYSICAL),
                  items(panel.comboActivateHiddenPhase));
            assertTrue(panel.activateHidden.isEnabled());
            panel.comboActivateHiddenPhase.setSelectedItem(GamePhase.FIRING);
            panel.activateHidden.doClick();

            Entity other = unit(2, enemy);
            other.setHidden(true);
            panel.displayMek(other);
            assertFalse(panel.activateHidden.isEnabled(), "Only the owner activates a hidden unit");
            return null;
        });
        verify(client).sendActivateHidden(1, GamePhase.FIRING);
    }

    @Test
    void aCommandConsoleRoleSwapSendsTheUnit() throws Exception {
        atlas.setCrew(new Crew(CrewType.COMMAND_CONSOLE));
        onSwing(() -> {
            PilotPanel panel = new PilotPanel(display);
            panel.displayMek(atlas);
            JToggleButton swap = field(panel, "btnSwapRoles");
            assertTrue(swap.isEnabled());
            swap.doClick();
            assertEquals(Messages.getString("PilotMapSet.keepRoles.text"), swap.getText());
            return null;
        });
        assertTrue(atlas.getCrew().getSwapConsoleRoles());
        verify(client).sendUpdateEntity(atlas);
    }

    @Test
    void aWeaponOrderChoiceSortsTheListAndSendsItForOwnUnitsOnly() throws Exception {
        Entity other = unit(2, enemy);
        onSwing(() -> {
            WeaponPanel panel = weaponPanel();
            panel.displayMek(atlas);
            assertEquals(List.of("Medium Laser [LA] ", "Medium Laser [RA] ", "LRM 20 [LT] (6/12) ",
                  "SRM 6 [LT] (15/15)", "AC/20 [RT] (5/10)", "Medium Laser (R) [CT] ", "Medium Laser (R) [CT] "),
                  items(panel.weaponList));
            JComboBox<?> order = field(panel, "comboWeaponSortOrder");
            order.setSelectedItem(WeaponSortOrder.DAMAGE_HIGH_LOW);
            assertEquals(List.of("AC/20 [RT] (5/10)", "Medium Laser [LA] ", "Medium Laser [RA] ",
                  "Medium Laser (R) [CT] ", "Medium Laser (R) [CT] ", "LRM 20 [LT] (6/12) ", "SRM 6 [LT] (15/15)"),
                  items(panel.weaponList));

            panel.displayMek(other);
            order.setSelectedItem(WeaponSortOrder.RANGE_LOW_HIGH);
            return null;
        });
        assertEquals(WeaponSortOrder.DAMAGE_HIGH_LOW, atlas.getWeaponSortOrder());
        verify(client).sendEntityWeaponOrderUpdate(atlas);
        assertEquals(WeaponSortOrder.RANGE_LOW_HIGH, other.getWeaponSortOrder());
        verify(client, never()).sendEntityWeaponOrderUpdate(other);
    }

    @Test
    void anAmmunitionChoiceLoadsTheBinAndSendsItForOwnUnitsOnly() throws Exception {
        Entity other = unit(2, enemy);
        WeaponMounted lrm = atlas.getWeaponList().get(2);
        List<AmmoMounted> bins = atlas.getAmmo().stream()
              .filter(bin -> bin.getType().getAmmoType() == lrm.getType().getAmmoType()).toList();
        // One shot fired from the second bin tells the list's two entries apart
        bins.get(1).setShotsLeft(5);
        onSwing(() -> {
            WeaponPanel panel = weaponPanel();
            panel.displayMek(atlas);
            panel.weaponList.setSelectedIndex(2);
            JComboBox<?> ammo = field(panel, "m_chAmmo");
            assertEquals(List.of("[LT] LRM 20  (6)", "[LT] LRM 20  (5)"), items(ammo));
            assertEquals(0, ammo.getSelectedIndex(), "The loaded bin");
            ammo.setSelectedIndex(1);

            panel.displayMek(other);
            panel.weaponList.setSelectedIndex(2);
            ((JComboBox<?>) field(panel, "m_chAmmo")).setSelectedIndex(1);
            return null;
        });
        assertEquals("LRM 20", lrm.getName());
        assertEquals(List.of(10, 12, 13), List.of(atlas.getEquipmentNum(lrm), atlas.getEquipmentNum(bins.get(0)),
              atlas.getEquipmentNum(bins.get(1))));
        // The unit, the weapon, the bin on its own carrier (a trailer may share it), the carrier and no mode
        verify(client).sendAmmoChange(1, 10, 13, 1, 0);
        assertSame(bins.get(1), lrm.getLinkedAmmo());
        verify(client, times(1)).sendAmmoChange(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void draggingAWeaponStoresACustomOrderThatIsSentLater() throws Exception {
        onSwing(() -> {
            WeaponPanel panel = weaponPanel();
            panel.displayMek(atlas);
            drag(panel.weaponList, 0, 1);
            assertEquals(List.of("Medium Laser [RA] ", "Medium Laser [LA] ", "LRM 20 [LT] (6/12) ",
                  "SRM 6 [LT] (15/15)", "AC/20 [RT] (5/10)", "Medium Laser (R) [CT] ", "Medium Laser (R) [CT] "),
                  items(panel.weaponList));
            return null;
        });
        assertEquals(WeaponSortOrder.CUSTOM, atlas.getWeaponSortOrder());
        assertTrue(atlas.isWeaponOrderChanged());
        assertEquals(List.of(1, 0, 2, 3, 4, 5, 6), atlas.getWeaponList().stream().map(atlas::getCustomWeaponOrder)
              .toList());
        verify(client, never()).sendEntityWeaponOrderUpdate(any());
    }

    @Test
    void theSystemsTabListsEachSlotWithItsDamageAndMode() throws Exception {
        Mounted<?> ecm = atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_LEFT_ARM);
        atlas.setGameOptions();
        atlas.getCritical(Mek.LOC_CENTER_TORSO, 0).setDestroyed(true);
        Mounted<?> rearLaser = atlas.getCritical(Mek.LOC_CENTER_TORSO, 10).getMount();
        rearLaser.setDestroyed(true);
        atlas.getCritical(Mek.LOC_CENTER_TORSO, 10).setHit(true);
        ecm.setMode(1);
        onSwing(() -> {
            SystemPanel panel = new SystemPanel(display);
            panel.displayMek(atlas);
            JList<?> locations = field(panel, "locList");
            JList<?> slots = field(panel, "slotList");
            locations.setSelectedIndex(3 + Mek.LOC_CENTER_TORSO);
            assertEquals(List.of("*Engine", "Engine", "Engine", "Standard Gyro", "Standard Gyro", "Standard Gyro",
                  "Standard Gyro", "Engine", "Engine", "Engine", "*Medium Laser (R)", "Medium Laser (R)"),
                  items(slots));
            locations.setSelectedIndex(3 + Mek.LOC_LEFT_ARM);
            assertEquals(List.of("Shoulder", "Upper Arm", "Lower Arm", "Hand", "Heat Sink (On)", "Medium Laser",
                  "ECM Suite (Guardian) (ECM) (next turn, Off)", "ECM Suite (Guardian) (ECM) (next turn, Off)", "---",
                  "---", "---", "---"), items(slots));
            locations.setSelectedIndex(0);
            List<Object> equipment = items(slots);
            assertEquals(22, equipment.size());
            assertEquals(List.of("Standard", "Heat Sink (On)"), equipment.subList(0, 2));
            assertEquals("ECM Suite (Guardian) (ECM) (next turn, Off)", equipment.getLast());
            return null;
        });
    }

    @Test
    void theExtrasTabListsWhatAffectsTheUnitAndWhatItCarries() throws Exception {
        atlas.attachNarcPod(new NarcPod(enemy.getTeam(), Mek.LOC_CENTER_TORSO));
        atlas.attachINarcPod(new INarcPod(enemy.getTeam(), INarcPod.HOMING, Mek.LOC_CENTER_TORSO));
        atlas.attachINarcPod(new INarcPod(enemy.getTeam(), INarcPod.HAYWIRE, Mek.LOC_CENTER_TORSO));
        atlas.newRound(1);
        atlas.getWeaponList().getFirst().setJammedImmediately(true);
        atlas.setLocationStatus(Mek.LOC_LEFT_LEG, ILocationExposureStatus.BREACHED);
        atlas.setTaserFeedback(2);
        atlas.addEquipment(EquipmentType.get("ISMediumVibroblade"), Mek.LOC_RIGHT_ARM);
        atlas.setExternalSearchlight(true);
        atlas.setLastTarget(2);
        atlas.setLastTargetDisplayName("Warhammer WHM-6R");
        onSwing(() -> {
            ExtraPanel panel = new ExtraPanel(display);
            panel.displayMek(atlas);
            assertEquals(List.of("NARCed by Team of Enemy [Team 2]",
                  "iNarc Homing Pod from Team of Enemy [Team 2] attached.", "iNarc Haywire Pod attached.",
                  "2 rounds of +1 to-Hit due to Taser Feedback", "Medium Laser is Jammed", "Left Leg Breached"),
                  items((JList<?>) field(panel, "narcList")));
            assertEquals("Vibroblade (Medium)\nSearchlight (off)", ((JTextArea) field(panel, "carriesR")).getText());
            assertEquals("\nOne BA-MagClamp squad\nOne protomek\nOne protomek\n",
                  ((JTextArea) field(panel, "unusedR")).getText());
            assertEquals("Warhammer WHM-6R", ((JTextArea) field(panel, "lastTargetR")).getText());

            panel.displayMek(unit(2, enemy));
            assertEquals(List.of(" "), items((JList<?>) field(panel, "narcList")));
            assertEquals("", ((JTextArea) field(panel, "carriesR")).getText());
            assertEquals(Messages.getString("MekDisplay.None"), ((JTextArea) field(panel, "lastTargetR")).getText());
            return null;
        });
    }

    @Test
    void theWeaponsTabShowsEachWeaponsDamage() throws Exception {
        Entity tank = new MekFileParser(new File(UNITS + "Bulldog Medium Tank.blk")).getEntity();
        tank.setId(3);
        tank.setOwner(local);
        game.addEntity(tank, false);
        tank.addEquipment(EquipmentType.get("ISArrowIVSystem"), Tank.LOC_BODY);
        Entity dropShip = new MekFileParser(new File(UNITS + "Union (3055).blk")).getEntity();
        dropShip.setId(4);
        dropShip.setOwner(local);
        game.addEntity(dropShip, false);
        onSwing(() -> {
            WeaponPanel panel = weaponPanel();
            panel.displayMek(atlas);
            assertEquals(List.of("5", "5", "Missile", "Missile", "20", "5", "5"), damageTexts(panel));
            panel.displayMek(tank);
            assertEquals(List.of("2", "Missile", "Missile", "8", "20/10"), damageTexts(panel));
            // Bays attack with attack values, which the range display shows (not characterized on the old panel)
            panel.displayMek(dropShip);
            assertEquals(Collections.nCopies(15, "Standard"), damageTexts(panel));
            return null;
        });
    }

    /** The damage the weapon panel shows for each weapon of its list, selected one after the other. */
    private static List<String> damageTexts(WeaponPanel panel) {
        JLabel damage = field(panel, "wDamR");
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < panel.weaponList.getModel().getSize(); index++) {
            panel.weaponList.setSelectedIndex(index);
            texts.add(damage.getText());
        }
        return texts;
    }

    private void selectMode(Mounted<?> equipment, int mode) {
        SystemPanel panel = new SystemPanel(display);
        panel.displayMek(atlas);
        JList<?> slots = field(panel, "slotList");
        slots.setSelectedIndex(atlas.getMisc().indexOf(equipment));
        JComboBox<?> modes = field(panel, "m_chMode");
        modes.setSelectedIndex(mode);
    }

    private JButton dumpButton(Mounted<?> equipment) {
        SystemPanel panel = new SystemPanel(display);
        panel.displayMek(atlas);
        JList<?> locations = field(panel, "locList");
        locations.setSelectedIndex(3 + equipment.getLocation());
        JList<?> slots = field(panel, "slotList");
        for (int slot = 0; slot < atlas.getNumberOfCriticalSlots(equipment.getLocation()); slot++) {
            var critical = atlas.getCritical(equipment.getLocation(), slot);
            if ((critical != null) && (critical.getMount() == equipment)) {
                slots.setSelectedIndex(slot);
                break;
            }
        }
        return field(panel, "m_bDumpAmmo");
    }

    private WeaponPanel weaponPanel() {
        WeaponPanel panel = new WeaponPanel(display, client);
        weaponPanels.add(panel);
        return panel;
    }

    /** Presses the left button on one row of the list and drags it onto another, as the list's drag reorder does. */
    private static void drag(JList<?> list, int from, int to) {
        MouseInputAdapter reorder = null;
        for (MouseListener listener : list.getMouseListeners()) {
            if (listener.getClass().getSimpleName().equals("WeaponListMouseAdapter")) {
                reorder = (MouseInputAdapter) listener;
            }
        }
        list.setSelectedIndex(from);
        var start = list.indexToLocation(from);
        reorder.mousePressed(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, start.x,
              start.y, 1, false, MouseEvent.BUTTON1));
        list.setSelectedIndex(to);
        var end = list.indexToLocation(to);
        reorder.mouseDragged(new MouseEvent(list, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON1_DOWN_MASK, end.x,
              end.y + 1, 0, false, MouseEvent.NOBUTTON));
        reorder.mouseReleased(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, 0, 0, end.x, end.y + 1, 1, false,
              MouseEvent.BUTTON1));
    }

    private Entity unit(int id, Player owner) throws Exception {
        Entity entity = new MekFileParser(new File(UNITS + "Atlas AS7-D.mtf")).getEntity();
        entity.setId(id);
        entity.setOwner(owner);
        entity.setPosition(new Coords(5, 4 + id));
        entity.setDeployed(true);
        game.addEntity(entity, false);
        return entity;
    }

    private static List<Object> items(JComboBox<?> combo) {
        List<Object> items = new ArrayList<>();
        for (int index = 0; index < combo.getItemCount(); index++) {
            items.add(combo.getItemAt(index));
        }
        return items;
    }

    private static List<Object> items(JList<?> list) {
        ListModel<?> model = list.getModel();
        List<Object> items = new ArrayList<>();
        for (int index = 0; index < model.getSize(); index++) {
            items.add(model.getElementAt(index));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
