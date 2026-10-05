/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.unitDisplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.dialogs.phaseDisplay.EcmSuiteChoiceDialog;
import megamek.common.CriticalSlot;
import megamek.common.annotations.Nullable;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.equipment.*;
import megamek.common.equipment.enums.MiscTypeFlag;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.options.OptionsConstants;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.Tank;

/** Equipment modes, dumping and critical-slot presentation shared by unit views. */
public final class UnitEquipmentActions {
    private UnitEquipmentActions() { }

    private static final String DUMP_BLOCKED = "MekDisplay.DumpBlocked.";

    /**
     * The slot list's text for one critical slot of a location: "---" when empty; a system's name, marked "*" when it
     * is destroyed or missing and "x" when breached; or the equipment's description with its state and modes. An
     * armored slot ends in "(armored)". The Unit Display and the GPU record sheet both use it.
     */
    public static String slotText(Entity en, int loc, int slot) {
        final CriticalSlot cs = en.getCritical(loc, slot);
        StringBuilder sb = new StringBuilder(32);
        if (cs == null) {
            sb.append("---");
        } else {
            switch (cs.getType()) {
                case CriticalSlot.TYPE_SYSTEM:
                    if (cs.isDestroyed() || cs.isMissing()) {
                        sb.append("*");
                    }
                    if (cs.isBreached()) {
                        sb.append("x");
                    }
                    // ProtoMeks have different system names.
                    if (en instanceof ProtoMek) {
                        sb.append(ProtoMek.SYSTEM_NAMES[cs.getIndex()]);
                    } else {
                        sb.append(((Mek) en).getSystemName(cs
                              .getIndex()));
                    }
                    break;
                case CriticalSlot.TYPE_EQUIPMENT:
                    sb.append(getMountedDisplay(en, cs.getMount(), cs));
                    break;
                default:
            }
            if (cs.isArmored()) {
                sb.append(" (armored)");
            }
        }
        return sb.toString();
    }

    /**
     * Appends the remaining bridge building budget to the Infantry Bridge Kit line so a Bridge-Building Engineer
     * platoon can see, in the systems tab, how many bridges it can still raise this scenario (TO:AUE p.152). The budget
     * of 2 points covers either 2 Light Bridges (1 point each) or 1 Medium Bridge (2 points); the counts drop as
     * bridges are built.
     *
     * @param en          the unit
     * @param displayText the systems-tab display text being built
     * @param mounted     the mounted equipment for this row
     */
    private static void appendBridgeKitBudget(Entity en, StringBuilder displayText, Mounted<?> mounted) {
        if (!(en instanceof ConvInfantry convInfantry)) {
            return;
        }
        boolean isBridgeKit = (mounted.getType() instanceof MiscType miscType)
              && miscType.hasFlag(MiscType.F_TOOLS)
              && miscType.hasFlag(MiscTypeFlag.S_BRIDGE_KIT);
        if (!isBridgeKit) {
            return;
        }
        int pointsLeft = convInfantry.getBridgeBuildPoints();
        int lightLeft = pointsLeft / ConvInfantry.BRIDGE_TYPE_LIGHT;
        int mediumLeft = pointsLeft / ConvInfantry.BRIDGE_TYPE_MEDIUM;
        displayText.append(' ').append(Messages.getString("MekDisplay.bridgeKitBudget", lightLeft, mediumLeft));
    }

    /**
     * Appends the Bridge-Layer (AVLB) state to a systems-tab row: the carried bridge's remaining Construction Factor,
     * "deploying" once a deployment has been declared but the bridge has not yet been laid (the stationary turn between
     * the pre-end declaration and placement), "deployed" once the folding bridge has been laid, or "mechanism disabled"
     * if a critical hit has knocked out the deploy mechanism (TM p.242 / TW). Does nothing for non-bridgelayer
     * equipment.
     *
     * @param displayText the systems-tab display text being built
     * @param mounted     the mounted equipment for this row
     */
    private static void appendBridgeLayerState(StringBuilder displayText, Mounted<?> mounted) {
        if (!(mounted instanceof MiscMounted miscMounted)) {
            return;
        }
        BridgeLayerState bridgeState = miscMounted.getBridgeLayerState();
        if (bridgeState == null) {
            return;
        }
        if (bridgeState.isDeployed()) {
            displayText.append(' ').append(Messages.getString("MekDisplay.bridgeLayerDeployed"));
        } else if (bridgeState.isDeployPending()) {
            displayText.append(' ').append(Messages.getString("MekDisplay.bridgeLayerDeploying",
                  bridgeState.getCurrentCF()));
        } else if (bridgeState.isDeployMechanismDisabled()) {
            displayText.append(' ').append(Messages.getString("MekDisplay.bridgeLayerMechanismDisabled"));
        } else {
            displayText.append(' ').append(Messages.getString("MekDisplay.bridgeLayerCF", bridgeState.getCurrentCF()));
        }
    }

    public static String getMountedDisplay(Entity en, Mounted<?> m, @Nullable CriticalSlot cs) {
        String hotLoaded = Messages.getString("MekDisplay.isHotLoaded");
        StringBuilder sb = new StringBuilder();

        sb.append(m.getDesc());
        appendBridgeKitBudget(en, sb, m);
        appendBridgeLayerState(sb, m);

        if ((cs != null) && cs.getMount2() != null) {
            sb.append(" ");
            sb.append(cs.getMount2().getDesc());
        }

        if (m.isHotLoaded()) {
            sb.append(hotLoaded);
        }

        if (m.hasModes()) {
            // Both of these describe what the equipment is or is about to be, so they take the state label
            if (!m.curMode().getStateName(m.getType()).isEmpty()) {
                sb.append(" (");
                sb.append(m.curMode().getStateName(m.getType()));
                sb.append(')');
            }

            if (!m.pendingMode().equals(Mounted.MODE_NONE)) {
                sb.append(" (next turn, ");
                sb.append(m.pendingMode().getStateName(m.getType()));
                sb.append(')');
            }

            if ((m instanceof MiscMounted) && ((MiscMounted) m).getType().hasFlag(MiscType.F_SHIELD)) {
                sb.append(" ").append(((MiscMounted) m).getDamageAbsorption(en, m.getLocation())).append('/')
                      .append(((MiscMounted) m).getCurrentDamageCapacity(en, m.getLocation())).append(')');
            }
        }
        return sb.toString();
    }

    /**
     * Switches the unit's equipment to the mode picked from the mode list, after the phase and equipment checks, and
     * tells the server and the player. The Unit Display and the GPU record sheet both use it; it never touches the
     * Unit Display itself.
     *
     * @param clientgui the client GUI, for confirmations, messages and the server connection
     * @param en        the unit that carries the equipment
     * @param m         the equipment, which has modes
     * @param nMode     the picked mode index, as listed by {@link #modeChoices(Game, Entity, Mounted)}
     *
     * @return {@code true} if the mode was switched or queued and sent, {@code false} if a check refused it
     */
    public static boolean changeMode(ClientGUI clientgui, Entity en, Mounted<?> m, int nMode) {
        if ((m.getType() instanceof MiscType miscType) &&
              miscType.isBoobyTrap()) {
            // Verify is it is in the correct phase to arm it
            // This should be controlled by the equipment itself
            // TODO: Refactor so the equipment knows the phase they can be armed/disarmed

            if ((clientgui.getClient().getGame().getPhase().isFiring() ||
                  clientgui.getClient().getGame().getPhase().isPhysical())) {
                if (nMode == 1) {
                    if (!clientgui.doYesNoDialog(Messages.getString("MekDisplay.BoobyTrapWarningTitle"),
                          Messages.getString("MekDisplay.BoobyTrapWarning"))) {
                        return false;
                    }
                }
            } else {
                clientgui.addToast(ToastLevel.WARNING,
                      Messages.getString("MekDisplay.BoobyTrapMode"));
                return false;
            }
        }

        if ((m.getType() instanceof MiscType)
              && ((MiscType) m.getType()).hasFlag(MiscType.F_SHIELD)
              && !Game.rulesManager.getRulesPhysical().phaseChangeShield()
              && !clientgui.getClient().getGame().getPhase().isFiring()) {
            clientgui.systemMessage(Messages.getString("MekDisplay.ShieldModePhase"));
            return false;
        }

        if ((m.getType() instanceof MiscType)
              && ((MiscType) m.getType()).isVibroblade()
              && !clientgui.getClient().getGame().getPhase().isPhysical()) {
            clientgui.systemMessage(Messages.getString("MekDisplay.VibrobladeModePhase"));
            return false;
        }

        if ((m.getType() instanceof MiscType)
              && m.getType().hasFlag(MiscTypeFlag.S_RETRACTABLE_BLADE)
              && !clientgui.getClient().getGame().getPhase().isMovement()) {
            clientgui.systemMessage(Messages.getString("MekDisplay.RetractableBladeModePhase"));
            return false;
        }

        // Can only charge a capacitor if the weapon has not been fired.
        if ((m.getType() instanceof MiscType)
              && (m.getLinked() != null)
              && m.getType().hasFlag(MiscType.F_PPC_CAPACITOR)
              && m.getLinked().isUsedThisRound()
              && (nMode == 1)) {
            clientgui.systemMessage(Messages.getString("MekDisplay.CapacitorCharging"));
            return false;
        }

        if (!resolveEcmSuiteConflict(clientgui, en, m, nMode)) {
            return false;
        }

        m.setMode(nMode);
        // send the event to the server
        clientgui.getClient().sendModeChange(en.getId(), en.getEquipmentNum(m), nMode);

        // notify the player
        // These report the state the equipment has reached, or will reach, so they take the
        // state label rather than the label the player picked from the dropdown
        if (m.canInstantSwitch(nMode)) {
            clientgui.systemMessage(Messages.getString("MekDisplay.switched",
                  m.getName(), m.curMode().getStateName(m.getType())));
            clientgui.addToast(ToastLevel.INFO,
                  m.getName() + ": " + m.curMode().getStateName(m.getType()), en);
        } else {
            String pendingModeName = m.pendingMode().getStateName(m.getType());
            if (clientgui.getClient().getGame().getPhase().isDeployment()) {
                clientgui.systemMessage(Messages.getString("MekDisplay.willSwitchAtStart",
                      m.getName(), pendingModeName));
            } else {
                clientgui.systemMessage(Messages.getString("MekDisplay.willSwitchAtEnd",
                      m.getName(), pendingModeName));
            }
            clientgui.addToast(ToastLevel.INFO,
                  m.getName() + " -> " + pendingModeName, en);
        }
        return true;
    }

    /**
     * Asks the player which ECM suite to leave on when the mode they just picked would put a second one into use, and
     * switches off the suites they did not keep. A unit may use only one ECM suite at a time, of any type (TM p.213,
     * CO p.200), and every mode other than {@code "Off"} counts as using the suite - ECCM and Ghost Targets included.
     *
     * <p>The choice is always between the suite already in use and the one the player just switched, so keeping the
     * suite that was already on means abandoning the requested switch. Cancelling does the same.</p>
     *
     * @param clientgui        the client GUI, used for the parent frame and to send the switches that are applied
     * @param en               the unit that carries the suites
     * @param changedEquipment the equipment whose mode the player just picked
     * @param newMode          the requested mode index
     *
     * @return {@code true} if the requested mode change should go ahead, {@code false} if it should be abandoned
     */
    private static boolean resolveEcmSuiteConflict(ClientGUI clientgui, Entity en, Mounted<?> changedEquipment,
          int newMode) {
        if (!(changedEquipment instanceof MiscMounted changedSuite)
              || !changedSuite.getType().hasFlag(MiscType.F_ECM)) {
            return true;
        }
        if ((newMode < 0) || (newMode >= changedSuite.getType().getModesCount())) {
            return true;
        }
        EquipmentMode requestedMode = changedSuite.getType().getMode(newMode);
        if (requestedMode.equals(Mounted.MODE_OFF)) {
            return true;
        }

        // Walk the mounts rather than appending the changed suite to the list of those already in use, so the
        // dialog offers the suites in mount order and its numbering runs 1, 2, 3 down the list
        List<MiscMounted> alreadyInUse = EquipmentActivation.ecmSuitesInUseNextRound(en);
        List<MiscMounted> suitesInUse = new ArrayList<>();
        for (MiscMounted suite : en.getMisc()) {
            if (suite.equals(changedSuite) || alreadyInUse.contains(suite)) {
                suitesInUse.add(suite);
            }
        }
        if (suitesInUse.size() < 2) {
            return true;
        }

        // Making room for this suite means switching another one off, which the server refuses for any ECM suite
        // while stealth armor is engaged or engaging - so there is no choice to offer here
        if (EquipmentActivation.isStealthOnOrActivating(en)) {
            clientgui.systemMessage(Messages.getString("MekDisplay.EcmSuiteStealthEngaged",
                  EquipmentActivation.ecmSuiteLabel(en, changedSuite)));
            return false;
        }

        // The mode the player just picked has not been applied yet, so it has to be named for the dialog rather
        // than read back off the equipment
        Map<MiscMounted, String> suiteModeNames = new LinkedHashMap<>();
        for (MiscMounted suite : suitesInUse) {
            suiteModeNames.put(suite, suite.equals(changedSuite)
                  ? requestedMode.getStateName(changedSuite.getType())
                  : suite.modeNextRound().getStateName(suite.getType()));
        }
        MiscMounted keptSuite = EcmSuiteChoiceDialog.showSingleChoiceDialog(clientgui.getFrame(), en,
              suiteModeNames);
        if (!changedSuite.equals(keptSuite)) {
            clientgui.systemMessage(Messages.getString("MekDisplay.EcmSuiteNotSwitched",
                  EquipmentActivation.ecmSuiteLabel(en, changedSuite)));
            return false;
        }

        for (MiscMounted suite : suitesInUse) {
            if (suite.equals(keptSuite)) {
                continue;
            }
            int offModeIndex = suite.setMode(Mounted.MODE_OFF);
            if (offModeIndex >= 0) {
                clientgui.getClient().sendModeChange(en.getId(), en.getEquipmentNum(suite), offModeIndex);
                clientgui.systemMessage(Messages.getString("MekDisplay.willSwitchAtEnd",
                      EquipmentActivation.ecmSuiteLabel(en, suite),
                      suite.pendingMode().getStateName(suite.getType())));
            }
        }
        return true;
    }

    /**
     * Starts or cancels, for the unit's owner, the dumping of ammunition or the jettisoning of a detachable weapon pack
     * or of a battle armor body-mounted missile launcher, and tells the server; the Dump button's action. Ammunition
     * and weapon packs ask the owner first. The Unit Display and the GPU record sheet both use it.
     *
     * @return {@code true} if a dump was started or cancelled and sent
     */
    public static boolean toggleDump(ClientGUI clientgui, Entity en, @Nullable Mounted<?> m) {
        boolean bOwner = clientgui.getClient().getLocalPlayer().equals(en.getOwner());
        if ((m == null) || !bOwner) {
            return false;
        }
        boolean changed = false;

        // Check for BA dumping SRM launchers
        if ((en instanceof BattleArmor) && (!m.isMissing())
              && m.isBodyMounted()
              && m.getType().hasFlag(WeaponType.F_MISSILE)
              && (m.getLinked() != null)
              && (m.getLinked().getUsableShotsLeft() > 0)) {
            boolean isDumping = !m.isPendingDump();
            m.setPendingDump(isDumping);
            clientgui.getClient().sendModeChange(en.getId(),
                  en.getEquipmentNum(m), isDumping ? -1 : 0);
            changed = true;
        }

        if (((!(m.getType() instanceof AmmoType) || (m.getUsableShotsLeft() <= 0))
              && !m.isDWPMounted()) || (m.isDWPMounted() && m.isMissing())) {
            return changed;
        }

        boolean bDumping;
        boolean bConfirmed;

        if (m.isPendingDump()) {
            bDumping = false;
            if (m.getType() instanceof AmmoType) {
                String title = Messages.getString("MekDisplay.CancelDumping.title");
                String body = Messages.getString("MekDisplay.CancelDumping.message", m.getName());
                bConfirmed = clientgui.doYesNoDialog(title, body);
            } else {
                String title = Messages.getString("MekDisplay.CancelJettison.title");
                String body = Messages.getString("MekDisplay.CancelJettison.message", m.getName());
                bConfirmed = clientgui.doYesNoDialog(title, body);
            }
        } else {
            bDumping = true;
            if (m.getType() instanceof AmmoType) {
                String title = Messages.getString("MekDisplay.Dump.title");
                String body = Messages.getString("MekDisplay.Dump.message", m.getName());
                bConfirmed = clientgui.doYesNoDialog(title, body);
            } else {
                String title = Messages.getString("MekDisplay.Jettison.title");
                String body = Messages.getString("MekDisplay.Jettison.message", m.getName());
                bConfirmed = clientgui.doYesNoDialog(title, body);
            }
        }

        if (bConfirmed) {
            m.setPendingDump(bDumping);
            clientgui.getClient().sendModeChange(en.getId(),
                  en.getEquipmentNum(m), bDumping ? -1 : 0);
            return true;
        }
        return changed;
    }

    /**
     * Whether the Dump button is offered for this equipment: the owner's ammunition with shots left outside the
     * deployment and movement phases, a detachable weapon pack or a battle armor body-mounted missile launcher, when
     * the rules allow dumping. The Unit Display and the GPU record sheet both use it.
     */
    public static boolean canDump(Client client, Entity en, @Nullable Mounted<?> mounted) {
        return dumpBlocker(client, en, mounted).isEmpty();
    }

    /**
     * Why the Dump button is not offered for this equipment now ({@link #canDump}), as the message key of the reason,
     * or "" when it is offered. The GPU unit record shows the reason on its disabled control.
     */
    public static String dumpBlocker(Client client, Entity en, @Nullable Mounted<?> mounted) {
        if (mounted == null) {
            return DUMP_BLOCKED + "notDumpable";
        } else if (!client.getLocalPlayer().equals(en.getOwner())) {
            return DUMP_BLOCKED + "notOwner";
        }
        String rules = Game.rulesManager.getRulesGame().ammoDumping() ? "" : DUMP_BLOCKED + "rules";
        if (mounted.getType() instanceof AmmoType) {
            boolean carryingBAsOnBack = (en instanceof Mek)
                  && ((en.getExteriorUnitAt(Mek.LOC_CENTER_TORSO, true) != null)
                  || (en.getExteriorUnitAt(Mek.LOC_LEFT_TORSO, true) != null) || (en
                  .getExteriorUnitAt(Mek.LOC_RIGHT_TORSO, true) != null));

            boolean invalidEnvironment = (en instanceof Mek)
                  && (en.getLocationStatus(Mek.LOC_CENTER_TORSO) > ILocationExposureStatus.NORMAL);

            if ((en instanceof Tank) && !(en instanceof GunEmplacement)
                  && (en.getLocationStatus(Tank.LOC_REAR) > ILocationExposureStatus.NORMAL)) {
                invalidEnvironment = true;
            }
            if (client.getGame().getPhase().isDeployment() || client.getGame().getPhase().isMovement()) {
                return DUMP_BLOCKED + "phase";
            } else if (client.getGame().getOptions().intOption(OptionsConstants.BASE_DUMPING_FROM_ROUND)
                  > client.getGame().getRoundCount()) {
                return DUMP_BLOCKED + "round";
            } else if (mounted.getUsableShotsLeft() <= 0) {
                return DUMP_BLOCKED + "empty";
            } else if (mounted.isDumping()) {
                return DUMP_BLOCKED + "dumping";
            } else if (!en.isActive()) {
                return DUMP_BLOCKED + "inactive";
            } else if (carryingBAsOnBack) {
                return DUMP_BLOCKED + "battleArmorOnBack";
            } else if (invalidEnvironment) {
                return DUMP_BLOCKED + "environment";
            }
            return rules;
        } else if ((mounted.getType() instanceof WeaponType)
              && !mounted.isMissing() && mounted.isDWPMounted()) {
            return rules;
            // Allow dumping of body-mounted missile launchers on BA
        } else if ((en instanceof BattleArmor)
              && (mounted.getType() instanceof WeaponType)
              && !mounted.isMissing() && mounted.isBodyMounted()
              && mounted.getType().hasFlag(WeaponType.F_MISSILE)
              && (mounted.getLinked() != null)
              && (mounted.getLinked().getUsableShotsLeft() > 0)) {
            return rules;
        }
        return DUMP_BLOCKED + "notDumpable";
    }

    /**
     * Whether the tab lists this equipment for selection, which its mode list and Dump button need: All Equipment holds
     * the unit's misc equipment, All Weapons its weapons (bays for a unit with weapon bays), and each location its
     * critical slots' equipment. Ammunition outside the critical slots, such as a one-shot launcher's, is in none of
     * them. The GPU record sheet offers its controls for the same equipment. A bomb bay's slot selects the ordnance at
     * the end of the bay's links, which counts here only when it has a slot of its own (a LAM bomb weapon's has none).
     */
    public static boolean isListed(Entity en, Mounted<?> mounted) {
        return en.getMisc().contains(mounted) || en.getWeaponList().contains(mounted) || (en.slotNumber(mounted) >= 0);
    }

    /**
     * The mode list the owner is offered for this equipment: its labels in mode-index order (the selected mode reads
     * as its state, every other as an action), the selected label, and whether it may be switched now.
     *
     * @param labels   the labels; empty when fewer than two modes are offered
     * @param selected the label of the queued mode, or of the current one when none is queued
     * @param enabled  whether the equipment may be switched now
     */
    public record ModeChoices(List<String> labels, String selected, boolean enabled) {
        public ModeChoices {
            labels = List.copyOf(labels);
        }

        /** The index of the entry the mode list selects (the first with the selected label); -1 without labels. */
        public int selectedIndex() {
            return labels.indexOf(selected);
        }
    }

    /**
     * The mode list for the owner's equipment with the checks that decide whether it may be switched now. Modes the
     * server would refuse are left out; they are always last, so a label's index is its mode index. The Unit Display
     * and the GPU record sheet both use it.
     */
    public static ModeChoices modeChoices(Game game, Entity en, Mounted<?> mounted) {
        int round = game.getRoundCount();
        boolean inSquadron = en.isPartOfFighterSquadron();
        EquipmentType mountedType = mounted.getType();
        boolean enabled = false;
        List<String> labels = new ArrayList<>();
        boolean isMiscEquipment = mountedType instanceof MiscType;
        if (!mounted.isInoperable() && !mounted.isDumping()
              && (en.isActive() || en.isActive(round) || inSquadron)
              && mounted.isModeSwitchable()) {
            enabled = true;
        }
        if (!mounted.isInoperable()
              && isMiscEquipment
              && mountedType.hasFlag(MiscType.F_STEALTH)
              && mounted.isModeSwitchable()) {
            enabled = true;
        }
        // Nova CEWS has built-in "ECM"/"Off" modes and should always be switchable
        if (!mounted.isInoperable()
              && isMiscEquipment
              && mountedType.hasFlag(MiscType.F_NOVA)
              && mounted.isModeSwitchable()) {
            enabled = true;
        }
        // EI Interface modes should be switchable even when not deployed (IO p.69)
        if (!mounted.isInoperable()
              && isMiscEquipment
              && mountedType.hasFlag(MiscType.F_EI_INTERFACE)
              && mounted.isModeSwitchable()) {
            enabled = true;
        }
        // Heat sinks can be activated or deactivated even while the unit is shut down (TW,
        // Deactivating Heat Sinks)
        boolean isSingleHeatSink = isMiscEquipment && mountedType.hasFlag(MiscType.F_HEAT_SINK);
        boolean isDoubleHeatSink = isMiscEquipment && mountedType.hasFlag(MiscType.F_DOUBLE_HEAT_SINK);
        boolean isPrototypeDoubleHeatSink = isMiscEquipment
              && mountedType.hasFlag(MiscType.F_IS_DOUBLE_HEAT_SINK_PROTOTYPE);
        boolean isHeatSinkMount = isSingleHeatSink || isDoubleHeatSink || isPrototypeDoubleHeatSink;
        if (!mounted.isInoperable() && isHeatSinkMount && mounted.isModeSwitchable()) {
            enabled = true;
        }
        // Every ECM suite now carries at least the "ECM"/"Off" pair (activation/deactivation
        // rules), so the mode switcher is always offered - the ECCM and Ghost Target game
        // options merely add further modes.
        // Iterate the type's per-mount mode count rather than its raw mode count: it filters
        // modes that need linked equipment to be available, such as the "Pulse ..." laser
        // modes that require a RISC Laser Pulse Module (LaserWeapon keeps them last and
        // reduces the count when no module is linked).
        // This must come from the TYPE, not from Mounted#getModesCount(), because the loop
        // below indexes into the type's mode list. InfantryWeaponMounted overrides
        // getModesCount() to report a merged primary+secondary list that can be longer than
        // the type's, which would index past the end.
        int visibleModeCount = mountedType.getModesCount(mounted);
        // A queued switch is what the combo shows as selected, so that is the mode described as
        // the equipment's state; without one it is simply the current mode.
        EquipmentMode selectedMode = mounted.pendingMode().equals(Mounted.MODE_NONE)
              ? mounted.curMode()
              : mounted.pendingMode();
        for (int modeIndex = 0; modeIndex < visibleModeCount; modeIndex++) {
            EquipmentMode equipmentMode = mountedType.getMode(modeIndex);
            // Hack to prevent showing an option that is disabled by the server, but would
            // be overwritten by every entity update if made also in the client
            if (equipmentMode.equals("HotLoad") && en instanceof Mek
                  && !game.getOptions()
                  .booleanOption(OptionsConstants.ADVANCED_COMBAT_HOT_LOAD_IN_GAME)) {
                continue;
            }
            // Hide the Off mode for ECM suites while the stealth armor system is engaged or
            // queued to engage this round - the server rejects that switch (stealth armor
            // requires an operating ECM). Off is always the LAST mode in every ECM mode list, so
            // skipping it keeps the combo indices aligned with the equipment type's mode indices
            // (the selection is applied by index).
            if (equipmentMode.equals(Mounted.MODE_OFF)
                  && isMiscEquipment
                  && mountedType.hasFlag(MiscType.F_ECM)
                  && EquipmentActivation.isStealthOnOrActivating(en)) {
                continue;
            }
            // Mirror case: hide the On mode for stealth armor while no ECM suite will be
            // operating next round (deactivated or deactivating). On is the LAST stealth mode,
            // so the combo indices stay aligned.
            if (equipmentMode.equals(Mounted.MODE_ON)
                  && isMiscEquipment
                  && mountedType.hasFlag(MiscType.F_STEALTH)
                  && !EquipmentActivation.hasEcmAvailableForStealth(en)) {
                continue;
            }
            // Blue shield prevents stealth systems from being activated.
            if (equipmentMode.equals(Mounted.MODE_ON)
                  && isMiscEquipment
                  && (mountedType.hasFlag(MiscType.F_STEALTH)
                  || mountedType.hasFlag(MiscType.F_CHAMELEON_SHIELD)
                  || mountedType.hasFlag(MiscType.F_VOID_SIG)
                  || mountedType.hasFlag(MiscType.F_NULL_SIG))
                  && Game.rulesManager.getRulesEquipment()
                  .blueShieldStealth(mounted.getEntity().hasActiveBlueShield())) {
                continue;
            }
            // Mirror case. If Stealth is active, Blue Shield can't be turned on.
            if (equipmentMode.equals(Mounted.MODE_ON)
                  && isMiscEquipment
                  && mountedType.hasFlag(MiscType.F_BLUE_SHIELD)
                  && (EquipmentActivation.isStealthOnOrActivating(en)
                  || en.isNullSigOn()
                  || en.isVoidSigOn()
                  || en.isChameleonShieldOn())
                  && Game.rulesManager.getRulesEquipment().blueShieldStealth()) {
                continue;
            }
            // The mode the combo will end up selected on describes the equipment's state; every
            // other entry is a change the player can make, and reads as an instruction.
            if (equipmentMode.equals(selectedMode)) {
                labels.add(equipmentMode.getStateName(mountedType));
            } else {
                labels.add(equipmentMode.getActionName(mountedType));
            }
        }
        if (labels.size() <= 1) {
            return new ModeChoices(List.of(), "", false);
        }
        return new ModeChoices(labels, selectedMode.getStateName(mountedType), enabled);
    }
}
