/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.LinkedHashMap;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The shipped suspended maglev routes before/after their conversion to rules decks. */
@Tag("on-demand")
class GpuMaglevDeckSmokeTest {
    @Test
    void shippedMaglevDecks() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "maglev-decks");
        var views = new LinkedHashMap<String, GpuRoadRailSmokeTest.View>();
        views.put("karkand-route", GpuRoadRailSmokeTest.load("unofficial/Derv_Maps/35x35 Karkand.board", new Coords(12, 10), false));
        views.put("karkand-station", GpuRoadRailSmokeTest.load("unofficial/Derv_Maps/35x35 Karkand.board", new Coords(12, 6), false));
        views.put("koryo-structures", GpuRoadRailSmokeTest.load("unofficial/Derv_Maps/35x35 Koryo.board", new Coords(11, 17), false));
        views.put("rail-crossing", GpuRoadRailSmokeTest.load("unofficial/EVS/86x51 Rail Crossing.board", new Coords(32, 23), false));
        GpuRoadRailSmokeTest.render(output, views, "");
    }
}
