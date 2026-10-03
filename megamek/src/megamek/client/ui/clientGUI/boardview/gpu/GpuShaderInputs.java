/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Render-thread-owned overrides. Uniform reflection and explicit sample inputs share one editing contract. */
final class GpuShaderInputs {
    static final String SAMPLE = "Preview setup (CPU inputs)";
    record Program(String name, GpuShaderUniforms shader) { }
    record Row(String name, GpuShaderValue.Type type, String supplied, String effective, String override,
          Double minimum, Double maximum, String note) { }
    record Request(String file, String program, boolean visible) { }
    record Snapshot(Request request, List<String> programs, String program, List<Row> rows) { }
    record Edit(String program, String name, String text) { }

    private final Map<String, Map<String, GpuShaderValue>> overrides = new HashMap<>();
    private long revision;

    long revision() { return revision; }
    Map<String, GpuShaderValue> values(String program) { return overrides.getOrDefault(program, Map.of()); }

    void edit(Edit edit, List<Row> rows) {
        var next = new HashMap<>(values(edit.program()));
        if (edit.name() == null) { next.clear(); }
        else if (edit.text().isBlank()) { next.remove(edit.name()); }
        else {
            var row = rows.stream().filter(candidate -> candidate.name().equals(edit.name())).findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("This input is no longer active."));
            var value = GpuShaderValue.parse(row.type(), edit.text());
            if (row.minimum() != null && value.scalar() < row.minimum()
                  || row.maximum() != null && value.scalar() > row.maximum()) {
                throw new IllegalArgumentException("Value must be between " + row.minimum() + " and " + row.maximum() + ".");
            }
            next.put(edit.name(), value);
        }
        if (edit.program().equals(SAMPLE)) {
            int missiles = integer(next, "missiles", GpuShaderPreview.Inputs.DEFAULT.missiles()), hits = integer(next, "missileHits", missiles);
            if (hits > missiles) { throw new IllegalArgumentException("Missile hits cannot exceed missiles. Clear or lower the hits override first."); }
        }
        if (next.isEmpty()) { overrides.remove(edit.program()); }
        else { overrides.put(edit.program(), Map.copyOf(next)); }
        revision++;
    }

    GpuShaderPreview.Inputs sample() {
        var values = values(SAMPLE);
        var defaults = GpuShaderPreview.Inputs.DEFAULT;
        int missiles = integer(values, "missiles", defaults.missiles());
        return new GpuShaderPreview.Inputs(integer(values, "rackSize", defaults.rackSize()), integer(values, "shots", defaults.shots()), missiles,
              integer(values, "missileHits", missiles), integer(values, "hit", defaults.hit() ? 1 : 0) != 0);
    }

    private static int integer(Map<String, GpuShaderValue> values, String name, int fallback) {
        var value = values.get(name);
        return value == null ? fallback : value.integer();
    }

    List<Row> sampleRows(GpuShaderPreview.Preset preset) {
        List<Row> rows = new ArrayList<>();
        var defaults = GpuShaderPreview.Inputs.DEFAULT;
        if (preset.ballistic()) {
            rows.add(sampleRow("rackSize", GpuShaderValue.Type.INT, Integer.toString(defaults.rackSize()), 1, 100,
                  "Calibre controls spark geometry and projectile size before shading."));
            rows.add(sampleRow("shots", GpuShaderValue.Type.INT, Integer.toString(defaults.shots()), 1, 16, "Rounds in the sample attack."));
        }
        if (preset == GpuShaderPreview.Preset.MISSILE) {
            rows.add(sampleRow("missiles", GpuShaderValue.Type.INT, Integer.toString(defaults.missiles()), 1, 100, "Missiles launched in the sample salvo."));
            rows.add(sampleRow("missileHits", GpuShaderValue.Type.INT, Integer.toString(sample().missiles()), 0, sample().missiles(),
                  "Defaults to all sample missiles hitting."));
        } else if (!preset.weapon.isEmpty()) {
            rows.add(sampleRow("hit", GpuShaderValue.Type.BOOL, Boolean.toString(defaults.hit()), 0, 1, "Resolved target hit or miss in the sample."));
        }
        return rows;
    }

    private Row sampleRow(String name, GpuShaderValue.Type type, String supplied, double minimum, double maximum, String note) {
        var override = values(SAMPLE).get(name);
        return new Row(name, type, supplied, override == null ? supplied : override.toString(), override == null ? "" : override.toString(),
              minimum, maximum, note);
    }
}
