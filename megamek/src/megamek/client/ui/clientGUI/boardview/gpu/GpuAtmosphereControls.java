/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Window;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.common.board.Board;
import megamek.common.planetaryConditions.PlanetaryConditions;

/** Window-owned visual previews; authoritative scenario conditions are never modified here. */
final class GpuAtmosphereControls implements AutoCloseable {
    private final Supplier<Window> owner;
    private final Supplier<Board> board;
    private final Supplier<PlanetaryConditions> scenario;
    private final BooleanSupplier closed;
    private final double timeSample = ThreadLocalRandom.current().nextDouble();
    private double previewTimeSample = timeSample;
    private PlanetaryConditions previewConditions;
    private PlanetaryConditionsDialog dialog;

    GpuAtmosphereControls(Supplier<Window> owner, Supplier<Board> board,
          Supplier<PlanetaryConditions> scenario, BooleanSupplier closed) {
        this.owner = owner;
        this.board = board;
        this.scenario = scenario;
        this.closed = closed;
    }

    void edit(Consumer<BoardAtmosphere.Settings> completed) {
        SwingUtilities.invokeLater(() -> {
            if (closed.getAsBoolean() || dialog != null) { completed.accept(null); return; }
            Board initialBoard = board.get();
            BoardAtmosphere.Settings result = null;
            try {
                var initial = new PlanetaryConditions(previewConditions == null ? scenario.get() : previewConditions);
                dialog = new PlanetaryConditionsDialog(owner.get() instanceof JFrame frame ? frame : null, initial);
                dialog.setAlwaysOnTop(true);
                if (dialog.showDialog() && !closed.getAsBoolean() && board.get() == initialBoard) {
                    var selected = dialog.getConditions();
                    if (selected.getLight() != initial.getLight()) { previewTimeSample = timeSample; }
                    previewConditions = new PlanetaryConditions(selected);
                    result = BoardAtmosphere.fromScenario(previewConditions, initialBoard.isSpace(), previewTimeSample);
                }
            } finally {
                if (dialog != null) { dialog.dispose(); }
                dialog = null;
                completed.accept(result);
            }
        });
    }

    BoardAtmosphere.Settings settings(PlanetaryConditions conditions, boolean space) {
        return BoardAtmosphere.fromScenario(conditions, space, timeSample);
    }

    BoardAtmosphere.Settings settings(AtmospherePreset preset) { return preset.settings(timeSample); }

    BoardAtmosphere.Settings preview(AtmospherePreset preset) {
        onEdt(() -> {
            if (!closed.getAsBoolean()) {
                previewConditions = preset.conditions();
                previewTimeSample = preset.timeSample(timeSample);
            }
        });
        return settings(preset);
    }

    void reset() {
        onEdt(() -> { previewConditions = null; previewTimeSample = timeSample; });
    }

    public void close() { onEdt(() -> { if (dialog != null) { dialog.dispose(); } }); }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); } else { SwingUtilities.invokeLater(action); }
    }
}
