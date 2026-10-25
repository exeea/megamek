/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import megamek.common.Configuration;
import megamek.common.scenario.Scenario;
import org.junit.jupiter.api.Test;

class StartScenariosTest {
    private static final File SCENARIOS = Configuration.scenariosDir();

    @Test
    void aScenarioIsInTheGroupOfTheFirstFolderUnderScenarios() {
        assertEquals("", StartScenarios.group(new File(SCENARIOS, "Basic.mms").getPath()));
        assertEquals("tukkayid", StartScenarios.group(new File(SCENARIOS, "tukkayid/first.mms").getPath()));
        assertEquals("tukkayid",
              StartScenarios.group(new File(SCENARIOS, "tukkayid/second/encounter.mms").getPath()));
        assertEquals("", StartScenarios.group(new File("elsewhere/mine.mms").getPath()));
    }

    @Test
    void ungroupedScenariosComeFirstThenEachGroupByName() {
        StartScenarios.Entry zulu = entry("", "Zulu");
        StartScenarios.Entry alpha = entry("", "alpha");
        StartScenarios.Entry bravo = entry("tukkayid", "Bravo");
        StartScenarios.Entry charlie = entry("Luthien", "Charlie");

        List<StartScenarios.Entry> sorted = StartScenarios.sorted(List.of(bravo, zulu, charlie, alpha));

        assertEquals(List.of(alpha, zulu, charlie, bravo), sorted);
        assertEquals(List.of("", "Luthien", "tukkayid"), StartScenarios.groups(sorted));
    }

    @Test
    void aScenarioWithoutANameIsListedByItsFile() {
        Scenario scenario = mock(Scenario.class);
        when(scenario.getName()).thenReturn(" ");
        when(scenario.getFileName()).thenReturn(new File(SCENARIOS, "nameless.mms").getPath());
        assertEquals("nameless.mms", new StartScenarios.Entry("", scenario).name());
    }

    private static StartScenarios.Entry entry(String group, String name) {
        Scenario scenario = mock(Scenario.class);
        when(scenario.getName()).thenReturn(name);
        return new StartScenarios.Entry(group, scenario);
    }
}
