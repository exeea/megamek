/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.PriorityTargetChoices.Candidate;
import megamek.client.bot.princess.PriorityTargetChoices.ForceRole;
import megamek.client.lobby.BotTargetPicker.Kind;
import megamek.client.lobby.BotTargetPicker.Row;
import org.junit.jupiter.api.Test;

class BotTargetPickerTest {
    private static final List<Candidate> CANDIDATES = List.of(
          new Candidate(1, "Atlas", 5, "Enemy", 2, 10, "Lance", ForceRole.NONE, true, false),
          new Candidate(2, "Locust", 5, "Enemy", 2, 10, "Lance", ForceRole.NONE, true, false),
          new Candidate(3, "Truck", 5, "Enemy", 2, 11, "Wagons", ForceRole.CONVOY, true, true),
          new Candidate(4, "Hunchback", 5, "Enemy", 2, -1, "", ForceRole.NONE, true, false),
          new Candidate(6, "Wasp", 8, "Pirate", 3, 20, "Raiders", ForceRole.NONE, true, false));

    private static BotTargetPicker picker(Map<Integer, Integer> priorities) {
        return new BotTargetPicker(true, CANDIDATES, Map.of(1, "Atlas", 99, "Lost"), priorities);
    }

    private static List<String> ids(BotTargetPicker picker) {
        return picker.rows().stream().map(Row::id).toList();
    }

    private static Row row(BotTargetPicker picker, String id) {
        return picker.rows().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void ownersOpenAndForcesStartFolded() {
        assertEquals(List.of("owner-5", "force-5:10", "force-5:11", "force-5:-1", "owner-8", "force-8:20"),
              ids(picker(Map.of())));
        assertEquals(Kind.NO_FORCE, row(picker(Map.of()), "force-5:-1").kind());
    }

    @Test
    void foldsToggle() {
        BotTargetPicker picker = picker(Map.of());

        picker.toggle("force-5:10");
        assertEquals(List.of("owner-5", "force-5:10", "unit-1", "unit-2", "force-5:11", "force-5:-1", "owner-8",
              "force-8:20"), ids(picker));

        picker.toggle("owner-5");
        assertEquals(List.of("owner-5", "owner-8", "force-8:20"), ids(picker));
        assertFalse(row(picker, "owner-5").open());
    }

    @Test
    void tickingAForcePicksItsUnitsAndSetsTheirPriority() {
        BotTargetPicker picker = picker(Map.of());

        picker.tick("force-5:10", true);
        assertEquals(Map.of(1, BehaviorSettings.DEFAULT_TARGET_PRIORITY, 2, BehaviorSettings.DEFAULT_TARGET_PRIORITY),
              picker.picked());
        assertTrue(row(picker, "force-5:10").checked());

        picker.priority("force-5:10", 9);
        assertEquals(Map.of(1, BehaviorSettings.LOWEST_TARGET_PRIORITY, 2, BehaviorSettings.LOWEST_TARGET_PRIORITY),
              picker.picked());

        picker.tick("unit-2", false);
        assertFalse(row(picker, "force-5:10").checked());
        assertEquals(BehaviorSettings.LOWEST_TARGET_PRIORITY, row(picker, "force-5:10").priority());

        picker.tick("force-5:10", false);
        assertTrue(picker.picked().isEmpty());
    }

    @Test
    void convoyUnitsAreAlwaysTargetsAndCannotBePicked() {
        BotTargetPicker picker = picker(Map.of(3, 2));
        assertTrue(picker.picked().isEmpty());

        picker.tick("unit-3", true);
        assertTrue(picker.picked().isEmpty());
        picker.toggle("force-5:11");
        Row truck = row(picker, "unit-3");
        assertTrue(truck.checked());
        assertFalse(truck.enabled());
        assertEquals(BehaviorSettings.HIGHEST_TARGET_PRIORITY, truck.priority());
        assertFalse(row(picker, "force-5:11").enabled());
    }

    @Test
    void unitsPickedButNotListedShowAsOtherTargets() {
        BotTargetPicker picker = picker(Map.of(99, 4, 100, 1));

        List<Row> rows = picker.rows();
        int other = ids(picker).indexOf("other");
        assertEquals(Kind.OTHER, rows.get(other).kind());
        assertEquals(List.of("unit-99", "unit-100"), ids(picker).subList(other + 1, other + 3));
        assertEquals(4, rows.get(other + 1).priority());
        assertTrue(rows.get(other + 1).text().contains("Lost"));
    }

    @Test
    void searchingOpensTheMatches() {
        BotTargetPicker picker = picker(Map.of());

        picker.search("wasp");

        assertEquals(List.of("owner-8", "force-8:20", "unit-6"), ids(picker));
        picker.search("nothing like it");
        assertEquals(List.of("empty"), ids(picker));
    }

    @Test
    void withoutAGameOnlyOtherTargetsShow() {
        BotTargetPicker picker = new BotTargetPicker(false, List.of(), Map.of(), Map.of());

        assertEquals(List.of(Kind.EMPTY), picker.rows().stream().map(Row::kind).toList());
    }

    @Test
    void theCountMentionsConvoys() {
        BotTargetPicker withConvoy = picker(Map.of(1, 3));
        BotTargetPicker without = new BotTargetPicker(true, CANDIDATES.subList(0, 2), Map.of(), Map.of(1, 3));

        assertFalse(withConvoy.count().equals(without.count()));
        assertTrue(without.count().contains("1"));
    }
}
