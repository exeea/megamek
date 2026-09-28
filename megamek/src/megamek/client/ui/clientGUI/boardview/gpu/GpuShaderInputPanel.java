/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

/** A generic inspector: blank overrides use supplied values, typed overrides are validated on the GL thread. */
final class GpuShaderInputPanel extends JPanel {
    private final JComboBox<String> programs = new JComboBox<>();
    private final JLabel status = new JLabel("Blank = supplied value. Enter commits. Vectors/matrices use comma-separated components.");
    private final AtomicReference<GpuShaderInputs.Snapshot> incoming = new AtomicReference<>();
    private List<GpuShaderInputs.Row> rows = List.of();
    private final InputModel model = new InputModel();
    private final JTable table = new JTable(model) {
        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0 || row >= rows.size()) { return null; }
            var input = rows.get(row);
            return input.note().isEmpty() ? input.effective() : input.note();
        }
    };
    private final Timer refresh;
    private String file;
    private boolean updating;
    private volatile GpuShaderInputs.Request requested;
    private BiConsumer<GpuShaderInputs.Edit, Consumer<String>> send = (edit, result) -> result.accept("No renderer connected.");

    GpuShaderInputPanel() {
        super(new BorderLayout(4, 4));
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.add(new JLabel("Program")); toolbar.add(programs);
        var clear = new JButton("Clear overrides");
        clear.setToolTipText("Restore supplied values for the selected program, including overrides on inactive inputs.");
        clear.addActionListener(event -> submit(null, ""));
        toolbar.add(clear);
        toolbar.add(new JLabel("Uniform overrides affect the board and preview."));
        add(toolbar, BorderLayout.NORTH);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(1).setPreferredWidth(75);
        for (int column = 2; column < 5; column++) { table.getColumnModel().getColumn(column).setPreferredWidth(220); }
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        programs.addActionListener(event -> { if (!updating) { rows = List.of(); model.fireTableDataChanged(); publish(); } });
        refresh = new Timer(150, event -> {
            if (table.isEditing()) { return; }
            var snapshot = incoming.getAndSet(null);
            if (snapshot == null) { return; }
            updating = true;
            boolean same = programs.getItemCount() == snapshot.programs().size();
            for (int i = 0; same && i < programs.getItemCount(); i++) { same = programs.getItemAt(i).equals(snapshot.programs().get(i)); }
            if (!same) { programs.removeAllItems(); snapshot.programs().forEach(programs::addItem); }
            programs.setSelectedItem(snapshot.program());
            updating = false;
            rows = snapshot.rows();
            model.fireTableDataChanged();
            publish();
        });
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                publish();
                if (isShowing()) { refresh.start(); } else { refresh.stop(); }
            }
        });
        publish();
    }

    void connect(BiConsumer<GpuShaderInputs.Edit, Consumer<String>> callback) { send = callback; }
    void select(String name) {
        if (Objects.equals(name, file)) { return; }
        if (table.isEditing()) { table.getCellEditor().stopCellEditing(); }
        file = name;
        programs.setSelectedItem(null);
        rows = List.of(); model.fireTableDataChanged();
        incoming.set(null);
        publish();
    }

    private void submit(String name, String text) {
        String program = (String) programs.getSelectedItem();
        if (program == null) { return; }
        send.accept(new GpuShaderInputs.Edit(program, name, text), status::setText);
    }

    private void publish() { requested = new GpuShaderInputs.Request(file, (String) programs.getSelectedItem(), isShowing()); }
    GpuShaderInputs.Request request() { return requested; }
    void receive(GpuShaderInputs.Snapshot snapshot) { incoming.set(snapshot); }
    void close() { refresh.stop(); incoming.set(null); }

    private final class InputModel extends AbstractTableModel {
        private static final String[] COLUMNS = { "Input", "Type", "Supplied", "Effective", "Override (blank = supplied)" };
        @Override
        public int getRowCount() { return rows.size(); }
        @Override
        public int getColumnCount() { return COLUMNS.length; }
        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }
        @Override
        public Object getValueAt(int row, int column) {
            var input = rows.get(row);
            return switch (column) {
                case 0 -> input.name();
                case 1 -> input.type() == null ? "read-only" : input.type().toString();
                case 2 -> input.supplied();
                case 3 -> input.effective();
                default -> input.override();
            };
        }
        @Override
        public boolean isCellEditable(int row, int column) { return column == 4 && rows.get(row).type() != null; }
        @Override
        public void setValueAt(Object value, int row, int column) { submit(rows.get(row).name(), value.toString()); }
    }
}
