/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StartScreenProbeTest {
    @Test
    void noVariablesMeansNoProbe() {
        assertNull(StartScreenProbe.from(Map.of()));
        assertNull(StartScreenProbe.from(Map.of("MM_START_PAGE", " ", "MM_START_QUIT", "true")));
    }

    @Test
    void readsThePageAndItsOptions() {
        StartScreenProbe probe = StartScreenProbe.from(Map.of("MM_START_PAGE", "Host+Online+reveal"));
        assertEquals("HOST", probe.page());
        assertTrue(probe.has("online"));
        assertTrue(probe.has("reveal"));
        assertNull(probe.shot());
        assertFalse(probe.quit(), "only a probe that saves a shot quits");
    }

    @Test
    void shotTimesDefaultToTheSettledScreen() {
        StartScreenProbe page = StartScreenProbe.from(Map.of("MM_START_PAGE", "host", "MM_START_SHOT", "a.png"));
        assertEquals(StartScreenProbe.PAGE_SHOT_AT, page.shotAt());
        StartScreenProbe intro = StartScreenProbe.from(Map.of("MM_START_SHOT", "a.png"));
        assertNull(intro.page());
        assertEquals(StartTimeline.INTRO_END + 1, intro.shotAt());
    }

    @Test
    void readsTheShotTimeAndQuit() {
        StartScreenProbe probe = StartScreenProbe.from(Map.of("MM_START_SHOT", "out.png", "MM_START_SHOT_AT", "1.2",
              "MM_START_QUIT", "true"));
        assertEquals(new File("out.png"), probe.shot());
        assertEquals(1.2, probe.shotAt());
        assertTrue(probe.quit());
        StartScreenProbe bad = StartScreenProbe.from(Map.of("MM_START_SHOT", "out.png", "MM_START_SHOT_AT", "soon"));
        assertEquals(StartTimeline.INTRO_END + 1, bad.shotAt());
    }
}
