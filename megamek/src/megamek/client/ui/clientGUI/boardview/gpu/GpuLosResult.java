/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Comparator;
import java.util.Objects;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.RulerDialog.LosEnd;
import megamek.client.ui.clientGUI.boardview.RulerDialog.LosView;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.IGame;
import megamek.common.units.Entity;

/** EDT service for line-of-sight results: the open LOS card and guarded measurement commands. */
final class GpuLosResult {
    /**
     * One measured line of sight with the views from both ends (the ruler's two views, with MegaMek's modifier
     * text). {@code fromUnit} and {@code toUnit} are the units the ends were opened for ({@link Entity#NONE} for a bare
     * hex); the height buttons keep them. A height flagged as altitude is an aerospace unit's altitude, not a height
     * above the hex.
     */
    record Card(long id, Coords from, int fromUnit, int fromHeight, boolean fromAltitude, Coords to, int toUnit,
          int toHeight, boolean toAltitude, int range, LosView attackerView, LosView targetView) { }

    /** The open card; {@link #NONE} (a null card) when it is closed. */
    record Snapshot(Card card) {
        static final Snapshot NONE = new Snapshot(null);
    }

    private static final String DEFAULT_BOARD_ONLY = "GpuBoard.hud.los.defaultBoardOnly";

    private final GpuBoardSource source;
    private Snapshot snapshot = Snapshot.NONE;
    private long lastId;
    /**
     * The board ruler's last seen measurement and the board it was seen on (each board view keeps its own ruler); a
     * new complete one opens the card once.
     */
    private int rulerBoard = Board.BOARD_NONE;
    private Coords rulerStart;
    private Coords rulerEnd;

    GpuLosResult(GpuBoardSource source) {
        this.source = source;
    }

    /**
     * EDT: the open card. A new measurement of MegaMek's ruler (Ctrl or Alt clicks) opens it; a ruler that starts over
     * (one point) closes it, as the Swing ruler hides until its second point. A ruler that takes the open card's hexes
     * (its elevation diagram, {@link #showDiagram}) keeps the card with its heights. Another board closes the card and
     * opens none for that board's standing measurement, so a card never shows over another board and a closed
     * measurement does not come back after a board switch.
     */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        BoardClientState view = source.currentView();
        Coords start = view.getRulerStart();
        Coords end = view.getRulerEnd();
        if (view.getBoardId() != rulerBoard) {
            rulerBoard = view.getBoardId();
            rulerStart = start;
            rulerEnd = end;
            snapshot = Snapshot.NONE;
        } else if (!Objects.equals(start, rulerStart) || !Objects.equals(end, rulerEnd)) {
            rulerStart = start;
            rulerEnd = end;
            Card open = snapshot.card();
            if (open == null || !open.from().equals(start) || !open.to().equals(end)) {
                snapshot = rulerCard(view);
            }
        }
        return snapshot;
    }

    // GL-safe commands. Each is posted through the source's input guard and rechecked on the EDT.

    /**
     * The menu's line of sight from an own unit to a hex, reported as a toast, or on the card while toasts are switched
     * off. {@code targetId} is the unit the menu was opened on ({@link Entity#NONE} for a hex); it is measured while
     * the player identifies it at that hex. Otherwise the hex's default end applies, so a sensor contact's hex is
     * measured bare and its unit's real height and cover stay hidden. On another board than the default one a toast
     * says that line of sight is measured on the main map only.
     */
    void lineOfSight(int fromEntityId, int targetId, Coords to) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            Entity from = view.game.getEntity(fromEntityId);
            if (view.getClientgui() == null || from == null || !source.owned(from) || !source.visible(from)) {
                return;
            }
            if (view.getBoardId() != IGame.DEFAULT_BOARD_ID) {
                view.getClientgui().addToast(ToastLevel.INFO, Messages.getString(DEFAULT_BOARD_ONLY));
                return;
            }
            if (!measurable(view, from.getPosition()) || !measurable(view, to)
                  || from.getOccupiedCoords().contains(to)) {
                return;
            }
            LosEnd attacker = LosEnd.of(from);
            LosEnd target = end(to, targetId);
            if (GUIPreferences.getInstance().getToastEnabled()) {
                LosView seen = RulerDialog.lineOfSight(view.game, view.getLocalPlayer(), attacker, target).attacker();
                view.getClientgui().addToast(ToastLevel.INFO, toast(seen, attacker.hex().distance(to)));
            } else {
                // ClientGUI drops toasts while they are switched off; this explicit request still gets its answer.
                snapshot = card(attacker, target);
            }
        });
    }

    /**
     * Opens the card for the ruler's current measurement (LOS settings). Without a complete one an open card stays
     * open, and when none is open a toast tells how to measure (or, on another board, that it cannot be measured).
     */
    void open() {
        source.command(() -> {
            BoardClientState view = source.currentView();
            Snapshot measured = rulerCard(view);
            if (measured.card() != null) {
                snapshot = measured;
            } else if (snapshot.card() == null && view.getClientgui() != null) {
                view.getClientgui().addToast(ToastLevel.INFO, Messages.getString(
                      view.getBoardId() == IGame.DEFAULT_BOARD_ID ? "GpuBoard.hud.los.hint" : DEFAULT_BOARD_ONLY));
            }
        });
    }

    /**
     * Opens or updates the card at those heights, kept within the Swing ruler's spinner bounds. Each end keeps the
     * unit the open card was opened for while the player still identifies it at that hex; a height other than the
     * unit's own measures the bare hex with the unit's class (the Swing spinners' rule).
     */
    void measure(Coords from, Coords to, int fromHeight, int toHeight) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            if (measurable(view, from) && measurable(view, to) && !from.equals(to)) {
                Card open = snapshot.card();
                snapshot = card(end(from, open == null ? Entity.NONE : open.fromUnit()), rulerHeight(fromHeight),
                      end(to, open == null ? Entity.NONE : open.toUnit()), rulerHeight(toHeight));
            }
        });
    }

    private static int rulerHeight(int height) {
        return Math.clamp(height, RulerDialog.MIN_HEIGHT, RulerDialog.MAX_HEIGHT);
    }

    /**
     * Shows MegaMek's ruler with its elevation diagram (kept in Swing, user item 6 U2) over the native window, for the
     * open card's hexes and heights: the ruler takes them, so its line on the board follows. The ruler's Close ends
     * the measurement, and with it the card.
     */
    void showDiagram() {
        source.command(() -> {
            Card open = snapshot.card();
            BoardClientState view = source.currentView();
            if (open != null && view.getClientgui() != null) {
                view.getClientgui().showRulerDiagram(view.getBoardId(), open.from(), open.fromHeight(), open.to(),
                      open.toHeight());
            }
        });
    }

    /** Closes the card and, as the Swing ruler's Close does, the board's ruler with its line. */
    void closeCard() {
        source.command(() -> {
            snapshot = Snapshot.NONE;
            BoardClientState view = source.currentView();
            if (view.getClientgui() != null) {
                view.getClientgui().closeRuler(view.getBoardId());
            }
        });
    }

    /**
     * The ruler's line of sight reads the game's default board only (LOSModifierCalculator uses game.getBoard()), so a
     * hex is measured only on that board.
     */
    private static boolean measurable(BoardClientState view, Coords hex) {
        return view.getBoardId() == IGame.DEFAULT_BOARD_ID && view.getBoard().contains(hex);
    }

    private Snapshot rulerCard(BoardClientState view) {
        Coords start = view.getRulerStart();
        Coords end = view.getRulerEnd();
        return start != null && end != null && measurable(view, start) && measurable(view, end)
              ? card(end(start), end(end)) : Snapshot.NONE;
    }

    /** The given unit's end at the hex while the player identifies it there, else the hex's default end. */
    private LosEnd end(Coords hex, int unitId) {
        BoardClientState view = source.currentView();
        Entity unit = view.game.getEntity(unitId);
        return unit != null && unit.getOccupiedCoords().contains(hex) && unit.isOnBoard(view.getBoardId())
              && source.identified(unit) ? LosEnd.of(unit, hex) : end(hex);
    }

    /**
     * The card's default end at a hex: the tallest unit there that the local player identifies, else the bare hex at
     * height 1. A sensor contact is never identified, so its hex is measured bare, as the Swing ruler measures a
     * sensor return; unlike the ruler's combo, the card never picks a sensor return over an identified unit. The end
     * keeps the hex, as the ruler keeps its clicked point on a large unit's secondary hex.
     */
    private LosEnd end(Coords hex) {
        BoardClientState view = source.currentView();
        return view.game.getEntitiesVector(hex, view.getBoardId()).stream().filter(source::identified)
              .map(unit -> LosEnd.of(unit, hex)).max(Comparator.comparingInt(LosEnd::height))
              .orElse(new LosEnd(hex, 1, false, false, null));
    }

    private static LosEnd atHeight(LosEnd end, int height) {
        return height == end.height() ? end : new LosEnd(end.hex(), height, end.mek(), end.altitude(), null);
    }

    private Snapshot card(LosEnd from, LosEnd to) {
        return card(from, from.height(), to, to.height());
    }

    /** The card between two ends, each with the unit it was opened for, measured at the given heights. */
    private Snapshot card(LosEnd from, int fromHeight, LosEnd to, int toHeight) {
        BoardClientState view = source.currentView();
        LosEnd attacker = atHeight(from, fromHeight);
        LosEnd target = atHeight(to, toHeight);
        RulerDialog.LosViews views = RulerDialog.lineOfSight(view.game, view.getLocalPlayer(), attacker, target);
        return new Snapshot(new Card(++lastId, attacker.hex(), unitId(from), attacker.height(), attacker.altitude(),
              target.hex(), unitId(to), target.height(), target.altitude(), attacker.hex().distance(target.hex()),
              views.attacker(), views.target()));
    }

    private static int unitId(LosEnd end) {
        return end.unit() == null ? Entity.NONE : end.unit().getId();
    }

    /** The attacker's view as the menu's toast: clear with its modifiers, if any, or the reason it is blocked. */
    private static String toast(LosView seen, int distance) {
        if (!seen.clear()) {
            return Messages.getString("GpuBoard.hud.los.blocked", seen.detail());
        }
        return seen.detail().isBlank() ? Messages.getString("GpuBoard.hud.los.clear", distance)
              : Messages.getString("GpuBoard.hud.los.clearModifiers", seen.detail(), distance);
    }
}
