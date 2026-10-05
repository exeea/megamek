/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.unitDisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.tooltip.PilotToolTip;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayData;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.clientGUI.unitDisplay.UnitEquipmentActions;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.ProstheticEnhancementType;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The texts the Unit Display shows for real units: the weapon list's rows, the Systems tab's slot rows, the weapon
 * display's statistics and the ammunition list (characterization of the Swing panels, recorded before U1 extracted
 * them for the GPU unit record).
 */
class UnitDisplayTextsTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    /** Every crew's Edge triggers, which are on unless a player turns them off. */
    private static final List<String> EDGE_TRIGGERS = List.of("(Mek) Use Edge for head hits",
          "(Mek) Use Edge for TACs", "(Mek) Use Edge for pilot KOs", "(Mek) Use Edge for explosions.",
          "(Mek) Use Edge for MASC/Supercharger failures.", "(Aero) Use Edge for atmospheric altitude loss.",
          "(Aero) Use Edge for explosions.", "(Aero, ASF/CF/SC only) Use Edge for pilot KOs.",
          "(Aero) Use Edge for crits resulting from 12-to-hit rolls.",
          "(Aero) Use Edge for crits resulting from nuclear missile hits.",
          "(Aero, SC/DS/JS/WS/SS) Use Edge for crits resulting in the loss of transported units.",
          "(Aero) Use Edge for catastrophic critical hits.", "(Vehicle) Use Edge for immobilizing motive system hits.",
          "(Vehicle) Use Edge for catastrophic critical hits.", "(Vehicle) Use Edge for turret blown off.",
          "(Infantry) Use Edge for zip line checks.", "(Mek/Aero) Use Edge for failed ejection rolls.",
          "(General) Use Edge for location breaches.", "Use Edge for autocannon jams (Ultra/RAC/rapid-fire).",
          "(General) Use Edge for RISC equipment malfunctions.", "Use Edge to avoid fire damage.");
    private static File originalDataDir;

    private final Game game = new Game();
    private final Player local = new Player(0, "Local");
    private final Client client = mock(Client.class);
    private final ClientGUI gui = mock(ClientGUI.class);
    private final UnitDisplayPanel display = mock(UnitDisplayPanel.class);
    private final List<WeaponPanel> weaponPanels = new ArrayList<>();
    private int nextId = 1;

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
        game.addPlayer(local.getId(), local);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(gui.getClient()).thenReturn(client);
        when(display.getClientGUI()).thenReturn(gui);
        UnitDisplayState state = new UnitDisplayState(gui);
        when(display.getDisplayState()).thenReturn(state);
        when(gui.getUnitDisplayState()).thenReturn(state);
    }

    @AfterEach
    void tearDown() throws Exception {
        onSwing(() -> {
            weaponPanels.forEach(WeaponPanel::disposeDisplay);
            return null;
        });
    }

    @Test
    void weaponRowsShowStateMarksMountMarksShotsAndModes() throws Exception {
        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_CALLED_SHOTS).setValue(true);
        Entity atlas = unit("Atlas AS7-D.mtf");
        atlas.setMixedTech(true);
        List<WeaponMounted> weapons = atlas.getWeaponList();
        weapons.get(0).setJammedImmediately(true);
        weapons.get(1).setUsedThisRound(true);
        Mounted<?> module = atlas.addEquipment(EquipmentType.get("ISRISCLaserPulseModule"), Mek.LOC_RIGHT_ARM);
        module.setLinked(weapons.get(1));
        weapons.get(2).setHotLoad(true);
        weapons.get(2).setMode(1);
        weapons.get(3).setSplit(true);
        weapons.get(3).setSecondLocation(Mek.LOC_CENTER_TORSO);
        weapons.get(4).setDestroyed(true);
        weapons.get(4).setRepairable(false);
        weapons.get(5).setFired(true);
        Mounted<?> atm = atlas.addEquipment(EquipmentType.get("CLATM6"), Mek.LOC_LEFT_ARM);
        atlas.addEquipment(EquipmentType.get("CLATM6 ER Ammo"), Mek.LOC_LEFT_ARM);
        Mounted<?> rocket = atlas.addEquipment(EquipmentType.get("RL10"), Mek.LOC_LEFT_TORSO);
        Mounted<?> bombast = atlas.addEquipment(EquipmentType.get("ISBombastLaser"), Mek.LOC_RIGHT_ARM);
        Mounted<?> gun = atlas.addEquipment(EquipmentType.get("ISMG"), Mek.LOC_RIGHT_ARM);
        gun.setRapidFire(true);
        atlas.loadAllWeapons();
        // A C3 master switches its mode at the end of the turn
        Entity command = unit("Atlas AS7-D.mtf");
        Mounted<?> master = command.addEquipment(EquipmentType.get("ISC3MasterUnit"), Mek.LOC_LEFT_ARM);
        master.setMode(1);
        List<Object> rows = onSwing(() -> {
            WeaponPanel panel = weaponPanel();
            panel.displayMek(atlas);
            List<Object> all = items(panel.weaponList);
            panel.displayMek(command);
            all.add(items(panel.weaponList).getLast());
            return all;
        });
        assertEquals(List.of("(IS) j Medium Laser [LA]  ", "(IS) +Medium Laser+RISC Laser Module [RA]  ",
              "(IS) LRM 20 [LT] (6/12) is Hot-Loaded Indirect ", "(IS) SRM 6 [LT/CT] (15/15) ",
              "(IS) **AC/20 [RT] (5/10) ", "(IS) - Medium Laser (R) [CT]  ", "(IS) Medium Laser (R) [CT]  ",
              "(C) ATM 6 [LA] (10/10) ", "(IS) Rocket Launcher 10 [LT] ",
              "(IS) Bombast Laser [RA] damage 16 No charge ", "(IS) Machine Gun [RA] (0/0) [Rapid Fire] ",
              "C3 Computer (Master) [LA] On (next turn, Off) "), rows,
              "rows of " + List.of(atm.getName(), rocket.getName(), bombast.getName()));

        // The parts the GPU unit record reads: shots, hot-loading, the current and queued mode, rapid fire
        UnitDisplayData.RowParts launcher = UnitDisplayData.rowParts(game, weapons.get(2));
        assertEquals(List.of(6, 12, true, "Indirect"), List.of(launcher.loadedShots(), launcher.totalShots(),
              launcher.hotLoaded(), launcher.mode()));
        UnitDisplayData.RowParts computer = UnitDisplayData.rowParts(game, (WeaponMounted) master);
        assertEquals(List.of("On", "Off"), List.of(computer.mode(), computer.pendingMode()));
        assertEquals(-1, UnitDisplayData.rowParts(game, (WeaponMounted) rocket).totalShots(),
              "The list shows no shots for a one-shot launcher");
        assertEquals(true, UnitDisplayData.rowParts(game, (WeaponMounted) gun).rapidFire());
    }

    @Test
    void slotRowsShowMountMarksSecondMountsHotLoadingAndModes() throws Exception {
        game.getOptions().getOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS).setValue(true);
        Entity atlas = unit("Atlas AS7-D.mtf");
        // A turret-mounted laser, a torso-mounted laser that turned to the rear right and locked, a hot-loaded LRM
        Mounted<?> turret = atlas.addEquipment(EquipmentType.get("ISMediumLaser"), Mek.LOC_RIGHT_ARM);
        turret.setMekTurretMounted(true);
        Mounted<?> directional = atlas.addEquipment(EquipmentType.get("ISSmallLaser"), Mek.LOC_LEFT_TORSO);
        directional.getQuirks().getOption(OptionsConstants.QUIRK_WEAPON_POS_DIRECT_TORSO_MOUNT).setValue(true);
        directional.setDirectionalMountFacing(2);
        directional.setDirectionalMountLocked(true);
        atlas.getWeaponList().get(2).setHotLoad(true);
        Entity omega = unit(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"),
              "meks/3145/NTNU RS/NTNU/Omega SHP-5R.mtf");
        List<String> texts = new ArrayList<>();
        texts.add(UnitEquipmentActions.slotText(atlas, Mek.LOC_CENTER_TORSO, 10));
        texts.add(UnitEquipmentActions.slotText(atlas, Mek.LOC_RIGHT_ARM, atlas.slotNumber(turret)));
        texts.add(UnitEquipmentActions.slotText(atlas, Mek.LOC_LEFT_TORSO, atlas.slotNumber(directional)));
        texts.add(UnitEquipmentActions.slotText(atlas, Mek.LOC_LEFT_TORSO, atlas.slotNumber(atlas.getWeaponList().get(2))));
        texts.add(UnitEquipmentActions.slotText(omega, Mek.LOC_LEFT_ARM, dualSlot(omega)));
        texts.add(UnitEquipmentActions.slotText(omega, Mek.LOC_CENTER_TORSO, 0));
        assertEquals(List.of("Medium Laser (R)", "Medium Laser (T)", "Small Laser (DTM) (RR) (Locked)",
              "LRM 20 is Hot-Loaded",
              "LB 10-X AC Ammo (10) LB 10-X AC Ammo (10)", "Engine"), texts);
    }

    @Test
    void weaponStatsAreTheWeaponDisplayLabels() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");
        WeaponMounted atm = (WeaponMounted) atlas.addEquipment(EquipmentType.get("CLATM6"), Mek.LOC_LEFT_ARM);
        AmmoMounted erAmmo = (AmmoMounted) atlas.addEquipment(EquipmentType.get("CLATM6 ER Ammo"),
              Mek.LOC_LEFT_ARM);
        atlas.loadWeapon(atm, erAmmo);
        Entity tank = unit("Bulldog Medium Tank.blk");
        tank.addEquipment(EquipmentType.get("ISArrowIVSystem"), Tank.LOC_BODY);
        tank.loadAllWeapons();
        Entity platoon = unit("Foot Platoon (AFFS) (Laser 3067+).blk");
        Entity aegis = unit("Aegis Heavy Cruiser (2372).blk");
        List<List<String>> stats = onSwing(() -> List.of(
              labels(atlas, atlas.getWeaponList().indexOf(atlas.getWeaponList().get(2))),
              labels(atlas, atlas.getWeaponList().indexOf(atm)),
              labels(tank, tank.getWeaponList().size() - 1),
              labels(platoon, 0),
              labels(aegis, 0)));
        String none = "---";
        assertEquals(List.of(
              List.of("6", "10", "Missile", none, "6", "1 - 7", "8 - 14", "15 - 21", "22 - 28", none, none, none,
                    none),
              List.of("4", "7", "Missile", none, "4", "1 - 9", "10 - 18", "19 - 27", "28 - 36", none, none, none, none),
              List.of("10", "10", "20/10", none, none, "1", "2", "3 - 8", "8", none, none, none, none),
              List.of("0", "0", "Variable", "0.28", none, "0", "0", "1 - 6", "7 - 8", none, none, none, none, "0=-2",
                    "1-2=+0", "3-4=+2", "5-6=+4"),
              List.of("120", "120", "Capital", none, none, "1-12", "13-24", "25-40", none, "40", "40", "40", none)),
              stats);
        assertEquals(stats, onSwing(() -> List.of(statTexts(atlas, atlas.getWeaponList().get(2)),
              statTexts(atlas, atm), statTexts(tank, tank.getWeaponList().getLast()),
              statTexts(platoon, platoon.getWeaponList().getFirst()),
              statTexts(aegis, aegis.getWeaponList().getFirst()))));
    }

    @Test
    void ammoChoicesAreTheAmmoList() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");
        WeaponMounted atm = (WeaponMounted) atlas.addEquipment(EquipmentType.get("CLATM6"), Mek.LOC_LEFT_ARM);
        atlas.addEquipment(EquipmentType.get("CLATM6 Ammo"), Mek.LOC_LEFT_ARM);
        AmmoMounted erAmmo = (AmmoMounted) atlas.addEquipment(EquipmentType.get("CLATM6 ER Ammo"),
              Mek.LOC_LEFT_ARM);
        atlas.loadWeapon(atm, erAmmo);
        atlas.addEquipment(EquipmentType.get("RL10"), Mek.LOC_LEFT_TORSO);
        atlas.loadAllWeapons();
        atlas.loadWeapon(atm, erAmmo);
        List<List<Object>> choices = onSwing(() -> {
            List<List<Object>> all = new ArrayList<>();
            for (int index = 0; index < atlas.getWeaponList().size(); index++) {
                all.add(ammoCombo(atlas, index));
            }
            return all;
        });
        List<Object> none = List.of(-1, false);
        assertEquals(List.of(none, none, List.of("[LT] LRM 20  (6)", "[LT] LRM 20  (6)", 0, true),
              List.of("[LT] SRM 6  (15)", 0, true), List.of("[RT] AC/20  (5)", "[RT] AC/20  (5)", 0, true), none,
              none, List.of("[LA] Standard ATM/6  (10)", "[LA] Extended-Range ATM/6  (10)", 1, true),
              List.of("RL 10 ", 0, false)), choices);

        List<String> feeds = new ArrayList<>();
        for (WeaponMounted weapon : atlas.getWeaponList()) {
            WeaponDisplayData.AmmoChoices offered = WeaponDisplayData.ammoChoices(atlas, weapon, weapon);
            feeds.add(offered.feed() + " " + offered.ammo().stream()
                  .map(ammo -> WeaponDisplayData.formatAmmo(atlas, ammo)).toList());
        }
        assertEquals(List.of("NONE []", "NONE []", "BINS [[LT] LRM 20  (6), [LT] LRM 20  (6)]",
              "BINS [[LT] SRM 6  (15)]", "BINS [[RT] AC/20  (5), [RT] AC/20  (5)]", "NONE []", "NONE []",
              "BINS [[LA] Standard ATM/6  (10), [LA] Extended-Range ATM/6  (10)]", "FIXED []"), feeds);
    }

    @Test
    void heatBuildupIsTheWeaponDisplayLabel() throws Exception {
        game.setPhase(GamePhase.FIRING);
        Entity atlas = unit("Atlas AS7-D.mtf");
        atlas.heat = 5;
        atlas.heatBuildup = 2;
        atlas.getWeaponList().get(0).setUsedThisRound(true);
        atlas.getWeaponList().get(2).setUsedThisRound(true);
        Entity hot = unit("Atlas AS7-D.mtf");
        hot.heat = 30;
        Entity union = unit("Union (3055).blk");
        union.getWeaponList().get(0).setUsedThisRound(true);
        union.getWeaponList().get(1).setUsedThisRound(true);
        List<String> labels = onSwing(() -> {
            List<String> texts = new ArrayList<>();
            for (Entity entity : List.of(atlas, hot, union)) {
                WeaponPanel panel = weaponPanel();
                panel.displayMek(entity);
                texts.add(((JLabel) field(panel, "currentHeatBuildupR")).getText());
            }
            return texts;
        });
        // 5 carried + 2 moved + 3 and 6 fired; 30 over 20 sinks; the Union's two fired bays heat their arcs
        assertEquals(List.of("16 (20)", "30* (20) 10 over", "34 (170)"), labels);
        assertEquals(labels, onSwing(() -> List.of(atlas, hot, union).stream()
              .map(entity -> WeaponDisplayData.heatBuildup(game, entity).text()).toList()));
    }

    @Test
    void crewAdvantagesListTheActiveAbilitiesByGroup() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");
        atlas.getCrew().getOptions().getOption(OptionsConstants.PILOT_MELEE_SPECIALIST).setValue(true);
        atlas.getCrew().getOptions().getOption(OptionsConstants.PILOT_HOT_DOG).setValue(true);
        ConvInfantry platoon = (ConvInfantry) unit("Foot Platoon (AFFS) (Laser 3067+).blk");
        platoon.getCrew().getOptions().getOption(OptionsConstants.MD_PL_ENHANCED).setValue(true);
        platoon.setProstheticEnhancement1(ProstheticEnhancementType.LASER);
        platoon.setProstheticEnhancement1Count(2);
        platoon.getCrew().getOptions().getOption(OptionsConstants.MD_PL_EXTRA_LIMBS).setValue(true);
        platoon.setExtraneousPair1(ProstheticEnhancementType.LASER);
        List<String> texts = onSwing(() -> {
            List<String> all = new ArrayList<>();
            for (Entity entity : List.of(atlas, platoon)) {
                all.add(plain(PilotToolTip.getCrewAdvantages(entity, true).toString()));
                all.add(plain(PilotToolTip.getCrewAdvantages(entity, false).toString()));
                PilotPanel panel = new PilotPanel(display);
                panel.displayMek(entity);
                all.add(pilotTabAdvantages(panel));
            }
            game.getOptions().getOption(OptionsConstants.EDGE).setValue(true);
            all.add(plain(PilotToolTip.getCrewAdvantages(atlas, true).toString()));
            PilotPanel panel = new PilotPanel(display);
            panel.displayMek(atlas);
            all.add(pilotTabAdvantages(panel));
            return all;
        });
        String advantages = "Hot Dog (CamOps)\n  Melee Specialist (CamOps)";
        String edge = String.join("\n  ", EDGE_TRIGGERS);
        String implants = "Prosthetic Limbs, Enhanced (Laser x2)\n  Prosthetic Limbs, Extraneous (Enhanced)";
        // The Pilot tab lists what the tooltip lists (U1 made both use PilotToolTip.crewAbilities): the Edge group only
        // while the Edge option is on, and the extraneous limbs with their enhancements
        assertEquals(List.of("Advantages:\n  " + advantages, "Advantages (2)", "Advantages\n  " + advantages,
              "Manei Domini Implants:\n  " + implants + " (Laser x2)", "Manei Domini Implants (2)",
              "Manei Domini Implants\n  " + implants + " (Laser x2)",
              "Advantages:\n  " + advantages + "\nEdge:\n  " + edge,
              "Advantages\n  " + advantages + "\nEdge\n  " + edge), texts);
    }

    /** The Pilot tab's advantage lines, one per label, blank ones left out. */
    private static String pilotTabAdvantages(PilotPanel panel) {
        Object mapSet = field(panel, "pilotMapSet");
        Object[] labels = field(mapSet, "advantagesR");
        List<String> lines = new ArrayList<>();
        for (Object label : labels) {
            String text = field(label, "string");
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return String.join("\n", lines);
    }

    /** A tooltip fragment as text: line breaks kept, tags and no-break spaces gone. */
    private static String plain(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]*>", "").replace("&nbsp;", " ").strip();
    }

    /** The weapon display's labels after selecting one weapon of the unit in a fresh panel. */
    private List<String> labels(Entity entity, int weapon) {
        WeaponPanel panel = weaponPanel();
        panel.displayMek(entity);
        int row = ((WeaponListModel) panel.weaponList.getModel()).getIndex(entity.getWeaponList().get(weapon));
        panel.weaponList.setSelectedIndex(row);
        List<String> texts = new ArrayList<>();
        for (String name : List.of("wHeatR", "wArcHeatR", "wDamR", "wDamageTrooperR", "wMinR", "wShortR", "wMedR",
              "wLongR", "wExtR", "wShortAVR", "wMedAVR", "wLongAVR", "wExtAVR")) {
            JLabel label = field(panel, name);
            texts.add(label.getText());
        }
        for (int bracket = 0; bracket <= 5; bracket++) {
            JLabel range = field(panel, "wInfantryRange" + bracket + "L");
            JLabel modifier = field(panel, "wInfantryRange" + bracket + "R");
            if (range.isVisible()) {
                texts.add(range.getText() + "=" + modifier.getText());
            }
        }
        return texts;
    }

    /**
     * The extracted statistics in the order {@link #labels} reads them; a label the weapon display leaves alone (the
     * damage per trooper of other weapons, the attack values of a ground attack) keeps its "---".
     */
    private List<String> statTexts(Entity entity, WeaponMounted weapon) {
        WeaponDisplayData.WeaponStats stats = WeaponDisplayData.stats(game, entity, weapon, weapon.getLinkedAmmo(), false);
        List<String> attackValues = stats.attackValues().isEmpty() ? List.of("---", "---", "---", "---")
              : stats.attackValues();
        List<String> texts = new ArrayList<>(List.of(stats.heat(), stats.arcHeat(), stats.damage(),
              stats.byTrooper().isEmpty() ? "---" : stats.byTrooper(), stats.min(), stats.shortRange(),
              stats.mediumRange(), stats.longRange(), stats.extremeRange()));
        texts.addAll(attackValues);
        stats.infantryRanges().forEach(range -> texts.add(range.range() + "=" + range.modifier()));
        return texts;
    }

    /** The ammunition list's items, then its selected index and whether it is enabled, for one weapon. */
    private List<Object> ammoCombo(Entity entity, int weapon) {
        WeaponPanel panel = weaponPanel();
        panel.displayMek(entity);
        int row = ((WeaponListModel) panel.weaponList.getModel()).getIndex(entity.getWeaponList().get(weapon));
        panel.weaponList.setSelectedIndex(row);
        JComboBox<?> ammo = field(panel, "m_chAmmo");
        List<Object> result = new ArrayList<>(items(ammo));
        result.add(ammo.getSelectedIndex());
        result.add(ammo.isEnabled());
        return result;
    }

    /** The left arm slot of a superheavy Mek that holds two ammunition bins. */
    private static int dualSlot(Entity entity) {
        for (int slot = 0; slot < entity.getNumberOfCriticalSlots(Mek.LOC_LEFT_ARM); slot++) {
            var critical = entity.getCritical(Mek.LOC_LEFT_ARM, slot);
            if ((critical != null) && (critical.getMount2() != null)) {
                return slot;
            }
        }
        throw new IllegalStateException("no dual slot");
    }

    private WeaponPanel weaponPanel() {
        WeaponPanel panel = new WeaponPanel(display);
        weaponPanels.add(panel);
        return panel;
    }

    private Entity unit(String file) throws Exception {
        return add(new MekFileParser(new File(UNITS + file)).getEntity());
    }

    private Entity unit(File zip, String file) throws Exception {
        return add(new MekFileParser(zip, file).getEntity());
    }

    private Entity add(Entity entity) {
        entity.setId(nextId++);
        entity.setOwner(local);
        entity.setPosition(new Coords(5, 4 + entity.getId()));
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
