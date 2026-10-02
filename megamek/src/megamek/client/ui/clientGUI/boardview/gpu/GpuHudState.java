/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * The HUD's cross-component view state on the render thread (rebuild plan B.3, C.5, C.6): what hotkeys, buttons and
 * several components change or read together. It is presentation only and never game state; everything else a view
 * shows lives in the component that shows it.
 */
final class GpuHudState {
    /** The centred panels; opening one closes the others (Help closes Menu, Players and Tuning). */
    enum Dialog { NONE, HELP, MENU, PLAYERS, TUNING }

    /**
     * The unit sheet's tabs with the Unit Display's keys that open them, F1 to F6 (unit panel design 5.1). SYSTEMS is
     * a Mek's critical table and every other unit's systems.
     */
    enum SheetTab {
        STATUS(KeyCommandBind.UD_GENERAL), CREW(KeyCommandBind.UD_PILOT), ARMOR(KeyCommandBind.UD_ARMOR),
        WEAPONS(KeyCommandBind.UD_WEAPONS), SYSTEMS(KeyCommandBind.UD_SYSTEMS), EXTRAS(KeyCommandBind.UD_EXTRAS);

        final KeyCommandBind key;

        SheetTab(KeyCommandBind key) {
            this.key = key;
        }
    }

    /**
     * The round's playback history over the board view's playback, which the board feeds each frame; the dock's
     * transport, the log, the board's pop-ups and the playback keys share it.
     */
    final GpuPlaybackHistory history;
    /** The unit the player inspects; the unit card shows it instead of the focus unit. */
    int inspected = Entity.NONE;
    /** The equipment number of the weapon armed for click-to-assign, or -1. */
    int armedWeapon = -1;
    Dialog dialog = Dialog.NONE;
    boolean overview;
    boolean chatOpen;
    /** The unit sheet is open (unit panel design 2.3): one flag, one step of the Esc chain. */
    boolean recordOpen;
    /** The sheet's tab; it never changes with the phase and stays when the sheet closes. */
    SheetTab sheetTab = SheetTab.STATUS;
    /**
     * The ARMOR inspector's location, which the card doll's click and the ARMOR dolls' arrow keys choose: a location's
     * abbreviation, "DC" + arm for a shield, "" for none. Another unit keeps it while it has that location (blink
     * memory).
     */
    String selectedLocation = "";
    /**
     * The location under the pointer (graft 6): a hovered weapon row, critical slot, equipment line or doll region;
     * every visible doll outlines it and the rows and table blocks of it are tinted. "" for none; it never rebuilds the
     * sheet.
     */
    String linkedLocation = "";
    /** The expanded weapon row: its name and its ordinal among the weapons of that name; "" for none. */
    String expandedWeapon = "";
    int expandedOrdinal;
    /** The sheet sections the player collapsed, by their caption keys. */
    final Set<String> collapsedSections = new HashSet<>();
    boolean forcesGrid;
    /** The nameplate key (SHOW_NAMEPLATES) is held. */
    boolean altHeld;
    private boolean logOpen;
    /** A report phase opened the closed log, so the next phase that is no report closes it again. */
    private boolean logAuto;
    private int focus = Entity.NONE;
    /** The player picked the focus outside the local turn; the next C.5 event ends the pick. */
    private boolean picked;
    private GpuBattleStatus.Snapshot status;
    private GpuUnitRecord.Snapshot record;
    private boolean playbackBusy;
    private List<GpuBattleStatus.UnitStatus> presentedUnits = List.of();
    private GpuUnitRecord.Snapshot presentedRecord = GpuUnitRecord.Snapshot.EMPTY;

    GpuHudState(GpuPlaybackHistory history) {
        this.history = history;
    }

    /** Opens the panel, or closes it when it is already open. */
    void toggle(Dialog panel) {
        dialog = dialog == panel ? Dialog.NONE : panel;
    }

    boolean logOpen() {
        return logOpen;
    }

    /** The Log utility, its key and the log's close button. A log the phase opened stays closed until reopened. */
    void toggleLog() {
        logOpen = !logOpen;
        logAuto = false;
    }

    /** The own focus unit (C.5), or {@code Entity.NONE}. */
    int focus() {
        return focus;
    }

    /**
     * An own unit the player picked outside the local turn becomes the focus, the unit the card and the panels show as
     * selected (the user's decision of 2026-10-02). It stays the focus even where no turn of the phase accepts it,
     * until the local turn begins or ends; at the next phase's start it stays while a turn of that phase accepts it.
     */
    void pick(int id) {
        focus = id;
        picked = true;
    }

    /**
     * The units every component shows (C.6): held while the playback still has live events to present, those a review
     * holds back included.
     */
    List<GpuBattleStatus.UnitStatus> presentedUnits() {
        return presentedUnits;
    }

    /** The presented unit {@code id} (C.6), or null. */
    GpuBattleStatus.UnitStatus presented(int id) {
        return presentedUnits.stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /** The record sheet data, held like the units while it is still the same unit. */
    GpuUnitRecord.Snapshot presentedRecord() {
        return presentedRecord;
    }

    /**
     * Applies the newest status. The units are presented from the last status captured while the playback was idle
     * (C.6); a closed log opens itself for the report phases after initiative and closes again with the next phase
     * that is no report, while a log the player opened stays open (A.10 J1); the focus unit follows C.5.
     *
     * @return the unit the HUD selects once because the local turn began with the focus able to act, or
     *       {@code Entity.NONE}
     */
    int update(GpuBattleStatus.Snapshot next, GpuUnitRecord.Snapshot nextRecord, boolean busy) {
        if (next == status && nextRecord == record && busy == playbackBusy) {
            return Entity.NONE;
        }
        GpuBattleStatus.Snapshot previous = status == null ? GpuBattleStatus.Snapshot.EMPTY : status;
        status = next;
        record = nextRecord;
        playbackBusy = busy;
        if (!busy || presentedUnits.isEmpty()) {
            presentedUnits = next.units();
            presentedRecord = nextRecord;
        } else if (nextRecord.unitId() != presentedRecord.unitId()) {
            presentedRecord = nextRecord;
        }
        boolean phaseStart = next.round() != previous.round() || next.phase() != previous.phase();
        if (phaseStart) {
            boolean autoLog = next.phase().isReport() && !next.phase().isInitiativeReport();
            if (autoLog && !logOpen) {
                logOpen = true;
                logAuto = true;
            } else if (!autoLog && logAuto) {
                logOpen = false;
                logAuto = false;
            }
        }
        return updateFocus(previous, next, phaseStart);
    }

    /**
     * C.5: candidates are the own pending units in ascending id order, the order the phase's "next" command walks. A
     * phase starts at the first, or at the player's pick while it is a candidate; a finished local turn, or a focus
     * that stops being a candidate without a pick holding it, moves to the next candidate after it; during the local
     * turn the focus is the actor. The actor's commit reaches the status before its turn ends, so the focus waits for
     * the turn's end and moves on once.
     */
    private int updateFocus(GpuBattleStatus.Snapshot previous, GpuBattleStatus.Snapshot next, boolean phaseStart) {
        List<Integer> candidates = next.units().stream()
              .filter(unit -> unit.side() == GpuBattleStatus.Side.OWN && !unit.sensorContact() && unit.pending())
              .map(GpuBattleStatus.UnitStatus::id).sorted().toList();
        boolean newTurn = next.turnIndex() != previous.turnIndex();
        boolean turnBegins = next.myTurn() && (phaseStart || !previous.myTurn() || newTurn);
        boolean turnEnded = !phaseStart && previous.myTurn() && (!next.myTurn() || newTurn);
        if (phaseStart && !(picked && candidates.contains(focus))) {
            focus = candidates.isEmpty() ? Entity.NONE : candidates.get(0);
        } else if (turnEnded) {
            focus = after(candidates, focus);
        } else if (!turnBegins && next.myTurn() && next.actorId() != previous.actorId()
              && own(next, next.actorId())) {
            // A selection during the local turn; the turn's first actor is the phase display's choice (below).
            focus = next.actorId();
        }
        // The player's pick holds until one of these events, or until the unit is no own unit any more.
        picked &= !phaseStart && !turnBegins && !turnEnded && own(next, focus);
        boolean duringTurn = next.myTurn() && !turnBegins;
        if (!duringTurn && !picked && !candidates.contains(focus)) {
            focus = after(candidates, focus);
        }
        int select = Entity.NONE;
        if (turnBegins) {
            GpuBattleStatus.UnitStatus unit = unit(next, focus);
            GamePhase phase = next.phase();
            if (unit != null && unit.canActNow() && (phase.isMovement() || phase.isFiring() || phase.isPhysical())) {
                // Overrides the phase display's own choice at the start of its turn.
                select = focus == next.actorId() ? Entity.NONE : focus;
            } else if (own(next, next.actorId())) {
                focus = next.actorId();
            }
        }
        return select;
    }

    /** The first candidate after {@code id}, wrapping around, or {@code Entity.NONE} without candidates. */
    private static int after(List<Integer> candidates, int id) {
        return candidates.stream().filter(candidate -> candidate > id).findFirst()
              .orElse(candidates.isEmpty() ? Entity.NONE : candidates.get(0));
    }

    /** The status of unit {@code id}, or null. */
    static GpuBattleStatus.UnitStatus unit(GpuBattleStatus.Snapshot status, int id) {
        return status.units().stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    private static boolean own(GpuBattleStatus.Snapshot status, int id) {
        GpuBattleStatus.UnitStatus unit = unit(status, id);
        return unit != null && unit.side() == GpuBattleStatus.Side.OWN;
    }
}
