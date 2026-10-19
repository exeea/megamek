/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.Renderable;
import org.junit.jupiter.api.Test;

class GpuMeshPageTest {
    @Test
    void emptyEndRangesNeitherTriggerCopyingNorReachRendering() {
        var empty = new Renderable();
        empty.meshPart.offset = 48066;
        empty.meshPart.size = 0;
        var triangle = new Renderable();
        triangle.meshPart.size = 3;
        var page = new GpuMeshPage();
        try {
            page.begin();
            page.add(empty, true);
            page.add(triangle, true);
            page.update();
            assertFalse(page.needsBuild(), "An empty material must not turn one live range into a page copy");
            assertEquals(3, page.indices);
            List<Renderable> submitted = new ArrayList<>();
            page.forEachVisible(submitted::add);
            assertEquals(List.of(triangle), submitted);

            page.begin();
            page.add(empty, true);
            page.add(triangle, false);
            page.update();
            submitted.clear();
            page.forEachVisible(submitted::add);
            assertTrue(submitted.isEmpty(), "An empty visible range must not expose hidden geometry");
            assertFalse(page.needsBuild());
        } finally { page.dispose(); }
    }
}
