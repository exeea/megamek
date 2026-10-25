/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import megamek.SuiteConstants;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.common.Configuration;
import megamek.common.preference.PreferenceManager;
import megamek.common.scenario.Scenario;
import megamek.common.scenario.ScenarioLoader;
import megamek.logging.MMLogger;

/**
 * The scenarios the start screen's browser lists: those in MegaMek's scenario folder, the user folder's and, in a
 * development checkout, the test setups, each in the group of the first folder under scenarios it sits in, as the old
 * scenario chooser grouped them.
 */
final class StartScenarios {
    private static final MMLogger LOGGER = MMLogger.create(StartScenarios.class);
    private static final String SCENARIOS = "scenarios";
    private static final File TEST_SETUPS = new File("testresources/data/scenarios/test_setups");

    /**
     * A scenario the browser lists.
     *
     * @param group the first folder under scenarios it sits in, or blank for the scenarios folder itself
     */
    record Entry(String group, Scenario scenario) {
        String name() {
            String name = scenario.getName();
            return name == null || name.isBlank() ? new File(scenario.getFileName()).getName() : name.trim();
        }
    }

    private StartScenarios() {
    }

    /** Blocks: reads every scenario, the ungrouped ones first, then by group and name; unreadable ones are skipped. */
    static List<Entry> read() {
        List<Scenario> scenarios = new ArrayList<>(parse(Configuration.scenariosDir()));
        String userDir = PreferenceManager.getClientPreferences().getUserDir();
        if (!userDir.isBlank()) {
            scenarios.addAll(parse(new File(userDir, Configuration.scenariosDir().toString())));
        }
        // Not present in releases
        if (TEST_SETUPS.isDirectory()) {
            scenarios.addAll(parse(TEST_SETUPS));
        }
        return sorted(scenarios.stream().map(scenario -> new Entry(group(scenario.getFileName()), scenario)).toList());
    }

    static List<Entry> sorted(List<Entry> entries) {
        return entries.stream()
              .sorted(Comparator.comparing((Entry entry) -> !entry.group().isBlank())
                    .thenComparing(Entry::group, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER))
              .toList();
    }

    /** The groups of {@code entries}, in their order, without repeats. */
    static List<String> groups(List<Entry> entries) {
        return entries.stream().map(Entry::group).distinct().toList();
    }

    /**
     * The first folder under a "scenarios" folder in {@code fileName}, or blank when the file sits in it directly or
     * outside the scenario folder; a scenario in scenarios/tukkayid/secondencounter/ is in "tukkayid".
     */
    static String group(String fileName) {
        if (!fileName.contains(Configuration.scenariosDir().toString())) {
            return "";
        }
        Path path = Path.of(fileName);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < path.getNameCount(); i++) {
            parts.add(path.getName(i).toString());
        }
        int index = parts.indexOf(SCENARIOS);
        // The last part is the file itself
        return index >= 0 && index + 2 < parts.size() ? parts.get(index + 1) : "";
    }

    private static List<Scenario> parse(File directory) {
        List<Scenario> scenarios = new ArrayList<>();
        for (String file : CommonSettingsDialog.filteredFilesWithSubDirs(directory, SuiteConstants.SCENARIO_EXT)) {
            try {
                scenarios.add(new ScenarioLoader(file).load());
            } catch (Exception ex) {
                LOGGER.warn("Skipping the scenario {}: {}", file, ex.getMessage());
            }
        }
        return scenarios;
    }
}
