/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.panels;

import static megamek.client.ui.Messages.CLIENT_BUNDLE;
import static megamek.common.internationalization.I18n.getTextAt;
import static megamek.utilities.ImageUtilities.addTintToImageIcon;
import static megamek.utilities.ImageUtilities.scaleImageIcon;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.settings.*;
import megamek.client.ui.util.UIUtil;
import megamek.common.Configuration;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;

/** Searchable settings-tree presentation for metadata-backed game option groups. */
public class GameOptionsPane extends JPanel {
    private static final int START_HEIGHT = 800;
    private static final int HEADER_IMAGE_SIZE = 80;
    private static final int LANDING_HEADER_IMAGE_SIZE = 200;
    private static final int IMPORTANT_ICON = 0xE002;
    private static final int ADVANCED_ICON = 0xE8B8;
    private static final int UNOFFICIAL_ICON = 0xEA4B;
    private static final int LEGACY_ICON = 0xE889;
    private static final Color UNOFFICIAL_COLOR = new Color(0xE6, 0x9F, 0x00);
    private static final SettingsTextProvider TEXT = SettingsTextProvider.megaMek();
    private static final SettingsBadge IMPORTANT_BADGE = new SettingsBadge(IMPORTANT_ICON, null,
          getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.legend.important"));
    private static final SettingsBadge ADVANCED_BADGE = new SettingsBadge(ADVANCED_ICON, null,
          getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.legend.advanced"));
    private static final SettingsBadge UNOFFICIAL_BADGE = new SettingsBadge(UNOFFICIAL_ICON, UNOFFICIAL_COLOR,
          getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.legend.unofficial"));
    private static final SettingsBadge LEGACY_BADGE = new SettingsBadge(LEGACY_ICON, null,
          getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.legend.legacy"));
    private static final Map<String, String> PAGE_FACTION_LOGOS = Map.ofEntries(
          Map.entry("basic", "logo_federated_suns.png"),
          Map.entry("gameMaster", "logo_star_league.png"),
          Map.entry("victory", "logo_clan_wolf.png"),
          Map.entry("allowedUnits", "logo_comstar.png"),
          Map.entry("advancedRules", "logo_republic_of_the_sphere.png"),
          Map.entry("advancedCombat", "logo_draconis_combine.png"),
          Map.entry("advancedGroundMovement", "logo_free_worlds_league.png"),
          Map.entry("advancedAeroRules", "logo_clan_smoke_jaguar.png"),
          Map.entry("initiative", "logo_clan_ghost_bear.png"),
          Map.entry("rpg", "logo_outworld_alliance.png"));
    private static final Map<String, Icon> PAGE_HEADER_ICONS = new LinkedHashMap<>();

    private final List<GroupPage> pages = new ArrayList<>();
    private final SettingsPane settingsPane;

    /**
     * @param groups           option groups to present
     * @param optionVisibility current category visibility policy
     */
    public GameOptionsPane(List<OptionGroup> groups, Predicate<IOption> optionVisibility) {
        this(groups, optionVisibility, Set.of());
    }

    /**
     * @param groups              option groups to present
     * @param optionVisibility    current category visibility policy
     * @param excludedOptionNames options owned by the caller and omitted from this presentation
     */
    public GameOptionsPane(List<OptionGroup> groups, Predicate<IOption> optionVisibility,
          Set<String> excludedOptionNames) {
        super(new BorderLayout());
        setName("gameOptionsPane");
        Objects.requireNonNull(optionVisibility);
        Set<String> excludedNames = Set.copyOf(excludedOptionNames);

        Map<GameOptionsPresentation.PageDefinition, PageSeed> pageSeeds = new LinkedHashMap<>();
        Map<String, DialogOptionComponentYPanel> componentsByName = new LinkedHashMap<>();
        for (OptionGroup group : groups) {
            for (DialogOptionComponentYPanel component : group.components()) {
                if ((component.getOption().getName().equals(OptionsConstants.BASE_HIDE_UNOFFICIAL)
                      || component.getOption().getName().equals(OptionsConstants.BASE_HIDE_LEGACY))
                      || excludedNames.contains(component.getOption().getName())) {
                    continue;
                }
                componentsByName.put(component.getOption().getName(), component);
                GameOptionsPresentation.Location location = GameOptionsPresentation.locationOrFallback(
                      group.id(), component.getOption().getName());
                pageSeeds.computeIfAbsent(location.page(), PageSeed::new).add(group, component, location);
            }
        }
        configureSettingsDependencies(componentsByName);
        List<PageSeed> orderedPageSeeds = new ArrayList<>(pageSeeds.values());
        orderedPageSeeds.sort(Comparator.comparingInt(page -> page.definition().order()));

        List<SettingsRoute> routes = new ArrayList<>();
        Map<String, Supplier<Component>> pageFactories = new LinkedHashMap<>();
        for (PageSeed pageSeed : orderedPageSeeds) {
            String pageTitle = pageSeed.title();
            GroupPage page = new GroupPage(pageSeed, pageTitle, optionVisibility);
            SettingsRoute route = new SettingsRoute(pageSeed.definition().routeId(),
                pageSeed.path(),
                  pageSeed.definition().pathIds(), pageSeed.searchAliases(), true);
            page.setRoute(route);
            pages.add(page);
            routes.add(route);
            pageFactories.put(route.getId(), () -> page);
        }

        SettingsNavigationText navigationText = new SettingsNavigationText(
              getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.Search"),
              getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.SearchToolTip"),
              getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.SearchNoMatches"),
              getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.SearchMatches"),
              getTextAt(CLIENT_BUNDLE, "SettingsPagePanel.expandAll.text"),
              getTextAt(CLIENT_BUNDLE, "SettingsPagePanel.collapseAll.text"),
              getTextAt(CLIENT_BUNDLE, "SettingsNavigation.searching"));
        settingsPane = new SettingsPane(routes, pageFactories, navigationText);
        add(settingsPane, BorderLayout.CENTER);
    }

    /** @return the normalized active search filter */
    public String getActiveFilter() {
        return settingsPane.getActiveFilter();
    }

    /** @return the search field's unmodified display text */
    public String getFilterText() {
        return settingsPane.getFilterText();
    }

    /** Sets the displayed search text and reapplies its normalized form to all staged pages; {@code null} clears it. */
    public void setFilterText(@Nullable String filterText) {
        settingsPane.setFilterText(filterText);
        String normalizedFilter = SettingsRoute.normalizeSearchText(Objects.requireNonNullElse(filterText, ""));
        // Game Options stages every page up front, and callers inspect option visibility independently of navigation.
        for (GroupPage page : pages) {
            page.refreshVisibility(normalizedFilter);
        }
    }

    /** Re-evaluates option visibility after the unofficial-options setting changes. */
    public void refreshVisibility() {
        String filter = settingsPane.getActiveFilter();
        for (GroupPage page : pages) {
            page.refreshVisibility(filter);
        }
        settingsPane.refreshFilter();
    }

    private static void configureSettingsDependencies(
          Map<String, DialogOptionComponentYPanel> componentsByName) {
        // the victory dependents (a threshold behind its master switch) share their table with the lobby dialog
        GameOptionsCatalog.editableWhenAny().forEach((dependentOptionName, controllingOptionNames) -> {
            DialogOptionComponentYPanel dependent = componentsByName.get(dependentOptionName);
            DialogOptionComponentYPanel[] controlling = controllingOptionNames.stream()
                  .map(componentsByName::get)
                  .toArray(DialogOptionComponentYPanel[]::new);
            if (dependent == null || Arrays.stream(controlling).anyMatch(Objects::isNull)) {
                return;
            }
            if (controlling.length == 1) {
                dependent.setEditableWhenSelected(controlling[0]);
            } else {
                dependent.setEditableWhenAnySelected(controlling);
            }
        });
    }

    /** @return the ordered badges explained by the Game Options legend */
    public static List<SettingsBadge> legendEntries() {
        return List.of(IMPORTANT_BADGE, ADVANCED_BADGE, UNOFFICIAL_BADGE, LEGACY_BADGE);
    }

    /** @return the marker used for unofficial game options */
    public static SettingsBadge unofficialBadge() {
        return UNOFFICIAL_BADGE;
    }

    /** @return the marker used for legacy game options */
    public static SettingsBadge legacyBadge() {
        return LEGACY_BADGE;
    }

    /** {@inheritDoc} */
    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        int floorWidth = UIUtil.scaleForGUI(SettingsNavigationPanel.DEFAULT_NAVIGATION_WIDTH)
              + UIUtil.scaleForGUI(SettingsPagePanel.DEFAULT_MAXIMUM_PAGE_WIDTH);
        int floorHeight = UIUtil.scaleForGUI(START_HEIGHT);
        return new Dimension(Math.max(preferred.width, floorWidth), Math.max(preferred.height, floorHeight));
    }

    /**
     * One source option group and its staged editor components.
     *
     * @param id          stable source-group identifier
     * @param displayName localized source-group name used as a search alias or fallback page title
     * @param components  option editors belonging to the group
     */
    public record OptionGroup(String id, String displayName, List<DialogOptionComponentYPanel> components) {
        public OptionGroup {
            Objects.requireNonNull(id);
            Objects.requireNonNull(displayName);
            components = List.copyOf(components);
        }
    }

    private static final class PageSeed {
        private final GameOptionsPresentation.PageDefinition definition;
        private final Map<String, String> sourceGroups = new LinkedHashMap<>();
        private final List<OptionPlacement> placements = new ArrayList<>();

        private PageSeed(GameOptionsPresentation.PageDefinition definition) {
            this.definition = definition;
        }

        private void add(OptionGroup group, DialogOptionComponentYPanel component,
              GameOptionsPresentation.Location location) {
            sourceGroups.put(group.id(), group.displayName());
            placements.add(new OptionPlacement(component, location.sectionId(), location.sectionOrder(),
                location.optionOrder()));
        }

        private GameOptionsPresentation.PageDefinition definition() {
            return definition;
        }

        private String title() {
            return definition.title(sourceGroups);
        }

        private List<String> path() {
            return definition.path(sourceGroups);
        }

        private String effectiveIconGroupId() {
            return definition.effectiveIconGroupId(sourceGroups);
        }

        private List<String> searchAliases() {
            List<String> aliases = new ArrayList<>();
            sourceGroups.forEach((id, displayName) -> {
                aliases.add(id);
                aliases.add(displayName);
            });
            return aliases;
        }
    }

    private record OptionPlacement(DialogOptionComponentYPanel component, String sectionId, int sectionOrder,
          int optionOrder) {
    }

    private static final class SectionRows {
        private final int order;
        private final List<OptionRow> rows = new ArrayList<>();

        private SectionRows(int order) {
            this.order = order;
        }
    }

    private static class GroupPage extends JPanel implements SettingsFilterable {
        private final List<OptionRow> rows;
        private final Predicate<IOption> optionVisibility;
        private final String groupSearchableText;
        private final String pageSearchableText;
        private final SettingsPagePanel pagePanel;
        private final Map<SettingsFormPanel, List<OptionRow>> sectionContentRows = new LinkedHashMap<>();
        private final Map<SettingsFormPanel, List<OptionRow>> displayedSectionRows = new LinkedHashMap<>();
        private SettingsRoute route;

        private GroupPage(PageSeed pageSeed, String pageTitle, Predicate<IOption> optionVisibility) {
            super(new BorderLayout());
            this.optionVisibility = optionVisibility;
            groupSearchableText = SettingsRoute.normalizeSearchText(String.join(" ", pageSeed.searchAliases()));
            pageSearchableText = SettingsRoute.normalizeSearchText(String.join(" ", pageSeed.path()));
            setName("gameOptions" + pageSeed.definition().id() + "Page");

            rows = pageSeed.placements.stream()
                  .map(placement -> OptionRow.create(placement.component(), placement.optionOrder(),
                        pageSearchableText, sectionSearchableText(placement.sectionId())))
                  .toList();
            Map<String, SectionRows> sectionRows = new LinkedHashMap<>();
            for (int index = 0; index < rows.size(); index++) {
                OptionPlacement placement = pageSeed.placements.get(index);
                SectionRows section = sectionRows.computeIfAbsent(placement.sectionId(),
                    ignored -> new SectionRows(placement.sectionOrder()));
                section.rows.add(rows.get(index));
            }
            sectionRows.values().forEach(section ->
                  section.rows.sort(Comparator.comparingInt(row -> row.optionOrder())));

            boolean directPage = pageSeed.definition().isDirect();
            Icon icon = directPage ? landingIcon() : pageIcon(pageSeed.effectiveIconGroupId());
            SettingsPagePanel.Builder builder = SettingsPagePanel.builder(pageSeed.definition().id(), TEXT,
                  "GameOptionsDialog.title", icon)
                  .header(new SettingsHeaderPanel(pageSeed.definition().id(), pageTitle, icon))
                  .showDetailsPanel(!directPage)
                  .standardContentWidth();
            if (directPage) {
                builder.intro("GameOptionsDialog.page.landing.intro")
                      .quote("GameOptionsDialog.page.landing.quote");
            }
            List<Map.Entry<String, SectionRows>> orderedSections = new ArrayList<>(sectionRows.entrySet());
            orderedSections.sort(Comparator.comparingInt(entry -> entry.getValue().order));
            int labelWidth = sharedLabelWidth(rows);
            for (Map.Entry<String, SectionRows> entry : orderedSections) {
                SettingsFormPanel content = createSectionContent(
                      pageSeed.definition().id() + entry.getKey(), entry.getValue().rows, labelWidth);
                sectionContentRows.put(content, entry.getValue().rows);
                displayedSectionRows.put(content, List.copyOf(entry.getValue().rows));
                builder.literalSection(sectionTitle(entry.getKey()), sectionSummary(entry.getKey()), content,
                      sectionBadges(pageSeed.definition().advanced()), pageSeed.searchAliases());
            }
            pagePanel = builder.build();
            add(pagePanel, BorderLayout.CENTER);
        }

        private static int sharedLabelWidth(List<OptionRow> rows) {
            int width = UIUtil.scaleForGUI(SettingsFormPanel.DEFAULT_LABEL_WIDTH);
            for (OptionRow row : rows) {
                Component label = row.option().getType() == IOption.BOOLEAN
                      ? row.component().settingsCheckBox()
                      : row.component().settingsLabel();
                width = Math.max(width, label.getPreferredSize().width);
            }
            return (int) Math.ceil(width / GUIPreferences.getInstance().getGUIScale());
        }

        private static String sectionSearchableText(String sectionId) {
            return SettingsRoute.normalizeSearchText(sectionTitle(sectionId) + ' ' + sectionSummary(sectionId));
        }

        private static SettingsFormPanel createSectionContent(String name, List<OptionRow> rows, int labelWidth) {
            SettingsFormPanel content = new SettingsFormPanel(name,
                  labelWidth, SettingsFormPanel.DEFAULT_CONTROL_WIDTH);
            populateSectionContent(content, rows);
            return content;
        }

        private static void populateSectionContent(SettingsFormPanel content, List<OptionRow> rows) {
            content.clear();
            List<JCheckBox> checkBoxes = new ArrayList<>();
            for (OptionRow row : rows) {
                DialogOptionComponentYPanel component = row.component();
                if (row.option().getType() == IOption.BOOLEAN) {
                    checkBoxes.add(component.settingsCheckBox());
                    continue;
                }
                addCheckBoxes(content, checkBoxes);
                configureSettingsControl(component);
                content.addRow(component.settingsLabel(), component.settingsControl());
                if (row.supplementalContent() != null) {
                    content.addFullWidthComponent(row.supplementalContent());
                }
            }
            addCheckBoxes(content, checkBoxes);
        }

        private static void configureSettingsControl(DialogOptionComponentYPanel component) {
            String optionName = component.getOption().getName();
            Map<Integer, String> integerChoices = GameOptionsCatalog.integerChoices(optionName);
            if (!integerChoices.isEmpty()) {
                component.useIntegerChoice(integerChoices);
                return;
            }
            int[] bounds = GameOptionsCatalog.integerBounds(optionName);
            if (bounds == null) {
                return;
            }
            if (bounds[1] == Integer.MAX_VALUE) {
                component.useIntegerSpinner(bounds[0]);
            } else {
                component.useIntegerSpinner(bounds[0], bounds[1]);
            }
        }

        private static void addCheckBoxes(SettingsFormPanel content, List<JCheckBox> checkBoxes) {
            if (checkBoxes.isEmpty()) {
                return;
            }
            if (checkBoxes.size() == 1) {
                content.addCheckBox(checkBoxes.getFirst());
            } else {
                content.addCheckBoxGrid(2, checkBoxes.toArray(JCheckBox[]::new));
            }
            checkBoxes.clear();
        }

        private void setRoute(SettingsRoute route) {
            this.route = route;
            refreshVisibility("");
        }

        private void refreshVisibility(String normalizedFilter) {
            StringBuilder searchableText = new StringBuilder();
            for (OptionRow row : rows) {
                boolean generallyVisible = optionVisibility.test(row.option());
                row.component().setVisible(generallyVisible && row.matches(normalizedFilter, groupSearchableText));
                if (generallyVisible) {
                    searchableText.append(' ').append(row.searchableText());
                }
            }
            sectionContentRows.forEach((content, sectionRows) -> {
                List<OptionRow> visibleRows = sectionRows.stream()
                      .filter(row -> row.component().isVisible())
                      .toList();
                if (!sameRowsByIdentity(visibleRows, displayedSectionRows.get(content))) {
                    populateSectionContent(content, visibleRows);
                    displayedSectionRows.put(content, visibleRows);
                }
                pagePanel.setSectionVisible(content, !visibleRows.isEmpty());
            });
            if (route != null) {
                route.setDynamicSearchText(searchableText.toString());
            }
            revalidate();
            repaint();
        }

        @Override
        public void applySettingsFilter(String normalizedFilter) {
            refreshVisibility(normalizedFilter);
        }

        private static boolean sameRowsByIdentity(List<OptionRow> first, List<OptionRow> second) {
            if (second == null || first.size() != second.size()) {
                return false;
            }
            for (int index = 0; index < first.size(); index++) {
                if (first.get(index) != second.get(index)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static List<SettingsBadge> sectionBadges(boolean advanced) {
        return advanced ? List.of(ADVANCED_BADGE) : List.of();
    }

    private static List<SettingsBadge> optionBadges(IOption option) {
        List<SettingsBadge> badges = new ArrayList<>();
        if (GameOptionsCatalog.isImportant(option)) {
            badges.add(IMPORTANT_BADGE);
        }
        if (isUnofficialOption(option)) {
            badges.add(UNOFFICIAL_BADGE);
        }
        if (isLegacyOption(option)) {
            badges.add(LEGACY_BADGE);
        }
        return badges;
    }

    private static String optionDisplayName(IOption option) {
        return GameOptionsCatalog.displayName(option);
    }

    static String stripCategoryMarker(String displayName) {
        return GameOptionsCatalog.stripCategoryMarker(displayName);
    }

    private static boolean hasDetails(IOption option) {
        return GameOptionsCatalog.hasDetails(option);
    }

    /** @return whether the option is classified as an unofficial rule by stable option name */
    public static boolean isUnofficialOption(IOption option) {
        return GameOptionsCatalog.isUnofficial(option.getName());
    }

    static Set<String> unofficialOptionNames() {
        return GameOptionsCatalog.UNOFFICIAL_OPTIONS;
    }

    /** @return whether the option is classified as a legacy rule by stable option name */
    public static boolean isLegacyOption(IOption option) {
        return GameOptionsCatalog.isLegacy(option.getName());
    }

    private static String sectionTitle(String sectionId) {
        return GameOptionsCatalog.sectionTitle(sectionId);
    }

    private static String sectionSummary(String sectionId) {
        return GameOptionsCatalog.sectionSummary(sectionId);
    }

    static String sectionId(String groupId, String optionName) {
        return GameOptionsPresentation.location(groupId, optionName).sectionId();
    }

    private static Icon landingIcon() {
        return PAGE_HEADER_ICONS.computeIfAbsent("landing", ignored -> {
            File logo = new File(Configuration.miscImagesDir(), "MegaMek.png");
            return scaleImageIcon(new ImageIcon(logo.getAbsolutePath()), LANDING_HEADER_IMAGE_SIZE, true);
        });
    }

    private static Icon pageIcon(String groupId) {
        return PAGE_HEADER_ICONS.computeIfAbsent(groupId, ignored -> {
            File factionsDir = new File(Configuration.universeImagesDir(), "factions");
            File logo = new File(factionsDir, PAGE_FACTION_LOGOS.getOrDefault(groupId, "logo_star_league.png"));
            ImageIcon scaled = scaleImageIcon(new ImageIcon(logo.getAbsolutePath()), HEADER_IMAGE_SIZE, true);
            return addTintToImageIcon(scaled.getImage(), Color.BLACK);
        });
    }

    static Map<String, String> factionLogos() {
        return PAGE_FACTION_LOGOS;
    }

    private static JComponent rulesSystemDescription(DialogOptionComponentYPanel component) {
        JLabel description = new JLabel();
        description.setName("lblRulesSystemDescription");
        description.setVerticalAlignment(SwingConstants.TOP);
        int textWidth = UIUtil.scaleForGUI(SettingsPagePanel.DEFAULT_SECTION_STACK_WIDTH - 30);
        Runnable updateText = () -> {
            String key = rulesSystemDescriptionKey(component.getValue());
            description.setText("<html><div style='width: " + textWidth + "'>" + TEXT.getText(key)
                  + "</div></html>");
            description.revalidate();
            description.repaint();
        };
        if (component.settingsControl() instanceof JComboBox<?> choices) {
            choices.addActionListener(event -> updateText.run());
        }
        updateText.run();
        return description;
    }

    static String rulesSystemDescriptionKey(Object value) {
        return GameOptionsCatalog.rulesSystemDescriptionKey(value);
    }

    private record OptionRow(DialogOptionComponentYPanel component, IOption option, String searchableText,
          String pageSearchableText, String sectionSearchableText, @Nullable JComponent supplementalContent,
          int optionOrder) {
        private static OptionRow create(DialogOptionComponentYPanel component, int optionOrder,
              String pageSearchableText, String sectionSearchableText) {
            IOption option = component.getOption();
            String displayName = optionDisplayName(option);
            component.setSettingsPresentation(displayName, optionBadges(option));
            if (hasDetails(option)) {
                component.setSettingsHelpText(GameOptionsCatalog.details(option));
            }
            JComponent supplementalContent = OptionsConstants.RULES_SYSTEM.equals(option.getName())
                  ? rulesSystemDescription(component)
                  : null;
            String supplementalSearchText = supplementalContent == null ? "" : " "
                  + TEXT.getText("GameOptionsDialog.page.landing.rulesSystem.core") + " "
                  + TEXT.getText("GameOptionsDialog.page.landing.rulesSystem.totalWarfare");
            return new OptionRow(component, option, SettingsRoute.normalizeSearchText(
                  option.getName() + ' ' + option.getDisplayableName() + ' ' + displayName + supplementalSearchText),
                  pageSearchableText, sectionSearchableText, supplementalContent, optionOrder);
        }

        private boolean matches(String normalizedFilter, String groupSearchableText) {
            if (normalizedFilter.isBlank()) {
                return true;
            }
            String combinedSearchableText = groupSearchableText + ' ' + pageSearchableText + ' '
                  + sectionSearchableText + ' ' + searchableText;
            for (String token : normalizedFilter.split("\\s+")) {
                if (!combinedSearchableText.contains(token)) {
                    return false;
                }
            }
            return true;
        }
    }
}
