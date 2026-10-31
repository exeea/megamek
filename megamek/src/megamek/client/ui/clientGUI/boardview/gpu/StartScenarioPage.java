/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.SuiteConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartScreen.Page;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.Configuration;
import megamek.common.preference.PreferenceManager;

/** The scenario browser: the scenarios, by group and search, each opening its briefing. */
final class StartScenarioPage {
    private final GpuStartScreen screen;
    /** The scenarios, read once per start screen; null until they are. */
    private List<StartScenarios.Entry> catalog;
    private int groupIndex;
    private UiSelect groupSelect;
    /** What the browser's search field holds; kept while the start screen is open, as the group is. */
    private String scenarioQuery = "";
    private UiTextField searchInput;
    private Table scenarioFilters;
    private Table scenarioRows;
    private ScrollPane scenarioList;
    private UiButton openScenario;

    StartScenarioPage(GpuStartScreen screen) {
        this.screen = screen;
    }

    /** Shows the scenarios, reading them first the first time. */
    void showScenarios() {
        openScenario = screen.kit.button("button-quiet", null, Messages.getString("StartScreen.openScenario"), null);
        GpuStartScreen.leftAligned(openScenario);
        UiKit.onChange(openScenario, () -> screen.window.chooseFile(Configuration.scenariosDir(),
              Messages.getString("MegaMek.SelectScenarioDialog.title"), new FileNameExtensionFilter(
                    Messages.getString("StartScreen.scenarioFiles"), SuiteConstants.SCENARIO_EXT.substring(1)),
              file -> {
                  if (screen.page == Page.SCENARIOS) {
                      screen.briefingPage.openBriefing(file, Page.SCENARIOS);
                  }
              }));
        if (catalog != null) {
            buildScenarioPage();
            fillScenarioColumn();
            return;
        }
        screen.setColumn(List.of(screen.overline("StartScreen.scenario"),
              screen.note(Messages.getString("StartScreen.readingScenarios"), "text.muted"), openScenario,
              screen.backButton()));
        screen.background("scenarios", StartScenarios::read, entries -> {
            catalog = entries;
            buildScenarioPage();
            fillScenarioColumn();
        });
    }

    private void buildScenarioPage() {
        List<String> groups = StartScenarios.groups(catalog);
        groupSelect = null;
        if (groups.size() > 1) {
            List<String> names = new ArrayList<>();
            names.add(Messages.getString("StartScreen.allScenarios"));
            groups.forEach(group -> names.add(groupName(group)));
            groupSelect = screen.kit.tokenSelect(false, names.toArray(String[]::new));
            groupIndex = Math.min(groupIndex, names.size() - 1);
            groupSelect.select(groupIndex);
            UiKit.onChange(groupSelect, () -> {
                groupIndex = groupSelect.getSelectedIndex();
                fillScenarioRows();
                scenarioList.setScrollY(0);
                fillScenarioColumn();
            });
        } else {
            groupIndex = 0;
        }
        UiKit.TokenSearch search = screen.kit.tokenSearch(Messages.getString("StartScreen.searchScenarios"), false);
        searchInput = search.field;
        searchInput.setFocusTraversal(false);
        searchInput.setText(scenarioQuery);
        // Only the rows change while typing, so the field keeps the keyboard
        UiKit.onChange(searchInput, () -> {
            scenarioQuery = searchInput.getText();
            fillScenarioRows();
            scenarioList.setScrollY(0);
        });
        scenarioFilters = new Table();
        scenarioFilters.left().top();
        scenarioFilters.add(search).growX().minWidth(0);
        if (groupSelect != null) {
            scenarioFilters.add(groupSelect).width(GpuStartScreen.ROLE_WIDTH + 30).padLeft(8);
        }
        scenarioRows = new Table();
        scenarioRows.top().left();
        scenarioRows.defaults().growX().left();
        scenarioList = screen.kit.scrollList(scenarioRows, true);
        fillScenarioRows();
    }

    /** The scenarios of the chosen group, or all of them, that answer the search. */
    private void fillScenarioRows() {
        scenarioRows.clearChildren();
        List<String> groups = StartScenarios.groups(catalog);
        String group = groupIndex == 0 ? null : groups.get(groupIndex - 1);
        for (StartScenarios.Entry entry : catalog) {
            if (group != null && !group.equals(entry.group()) || !StartScenarios.matches(entry, scenarioQuery)) {
                continue;
            }
            List<String> about = new ArrayList<>();
            if (group == null && groups.size() > 1) {
                about.add(groupName(entry.group()));
            }
            String planet = entry.scenario().getPlanet();
            if (planet != null && !planet.isBlank()) {
                about.add(planet.trim());
            }
            UiButton row = screen.kit.button("button-quiet", null, entry.name(),
                  about.isEmpty() ? null : String.join("  \u00b7  ", about));
            GpuStartScreen.leftAligned(row);
            UiKit.onChange(row, () -> screen.briefingPage.openBriefing(new File(entry.scenario().getFileName()),
                  Page.SCENARIOS));
            scenarioRows.add(row).row();
        }
        if (!scenarioRows.hasChildren()) {
            scenarioRows.add(screen.note(Messages.getString("StartScreen.noMatches"), "text.muted")).pad(8);
        }
    }

    private void fillScenarioColumn() {
        List<Actor> items = new ArrayList<>();
        items.add(screen.overline("StartScreen.scenario"));
        if (catalog.isEmpty()) {
            items.add(screen.note(Messages.getString("StartScreen.noScenarios"), "text.muted"));
        } else {
            items.add(scenarioFilters);
            items.add(scenarioList);
        }
        items.add(openScenario);
        items.add(screen.backButton());
        if (!screen.column.isEmpty() && screen.column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        screen.setColumn(items);
    }

    /** A group's name as the browser shows it: the scenarios folder's own are the basic ones. */
    static String groupName(String group) {
        if (group.isBlank()) {
            return Messages.getString("StartScreen.basicScenarios");
        }
        return group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
    }

    /** Ctrl+Scenario: the last scenario's briefing, or the browser when there is none. */
    void lastScenario() {
        String last = PreferenceManager.getClientPreferences().getLastScenario();
        if (last == null || last.isBlank() || !new File(last).isFile()) {
            screen.showPage(Page.SCENARIOS);
        } else {
            screen.briefingPage.openBriefing(new File(last), Page.MAIN);
        }
    }
}
