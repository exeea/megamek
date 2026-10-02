/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Asset names and load-time fallback: LOD0 is required; missing simpler levels reuse the preceding mesh. */
final class MeshLod {
    private MeshLod() { }

    static String name(String shape, int level) {
        if (level < 0) { throw new IllegalArgumentException("Negative mesh LOD"); }
        return shape + "-lod" + level;
    }

    /** The lookup returns null only for an absent level; malformed assets must still report their load error. */
    static <T> List<T> load(String shape, int count, Function<String, T> lookup) {
        if (count < 1) { throw new IllegalArgumentException("A mesh needs LOD0"); }
        List<T> levels = new ArrayList<>(count);
        T previous = null;
        for (int level = 0; level < count; level++) {
            T mesh = lookup.apply(name(shape, level));
            if (mesh != null) { previous = mesh; }
            if (previous == null) { throw new IllegalArgumentException("Missing mesh " + name(shape, 0)); }
            levels.add(previous);
        }
        return List.copyOf(levels);
    }
}
