/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import megamek.client.ratgenerator.FactionRecord;
import megamek.common.eras.Era;
import megamek.common.eras.Eras;
import org.junit.jupiter.api.Test;

class RequisitionFactionsTest {

    /** Davion has the unit in the Succession Wars, Steiner only in the Jihad, Marik never. */
    private static final RequisitionFactions.Source SOURCE = new RequisitionFactions.Source() {
        @Override
        public FactionRecord faction(String code) {
            return null;
        }

        @Override
        public String merged(String code, List<Integer> years) {
            return switch (code) {
                case "FS" -> years.contains(3025) ? "8" : "";
                case "LA" -> years.contains(3075) ? "5" : "";
                default -> "";
            };
        }
    };

    @Test
    void onlyTheErasWithTheUnitShow() {
        List<Era> eras = Eras.getEras();
        Set<String> codes = new LinkedHashSet<>(List.of("LA", "FW", "FS"));
        RequisitionFactions table = RequisitionFactions.of(codes, eras, Set.of(3025, 3050, 3075), SOURCE);

        Era first = Eras.getEra(3025);
        assertTrue(eras.indexOf(Eras.getEra(3075)) - eras.indexOf(first) > 1, "eras lie between the two");
        assertEquals(2, table.eras().size(), "the eras between without the unit do not show");
        assertTrue(table.eras().get(0).years().contains(Integer.toString(first.start().getYear())));
        assertEquals(List.of("FS", "LA"), table.factions().stream().map(RequisitionFactions.Faction::code).toList(),
              "sorted by name, without the faction that never has it");
        assertEquals(List.of("8", RequisitionFactions.NONE), table.factions().get(0).cells());
        assertEquals(List.of(RequisitionFactions.NONE, "5"), table.factions().get(1).cells());
    }

    @Test
    void ratingsSplitOnePerLine() {
        assertEquals(List.of("Very Rare (B)", "Rare (D)"), RequisitionFactions.ratings("Very Rare (B)\n Rare (D)\n"));
        assertEquals(List.of("8"), RequisitionFactions.ratings("8"));
        assertEquals(List.of(), RequisitionFactions.ratings(RequisitionFactions.NONE));
    }

    @Test
    void theLastEraIsAnOpenRange() {
        List<Era> eras = Eras.getEras();
        Era last = eras.get(eras.size() - 1);
        assertTrue(last.isLastEra());
        assertEquals(last.start().getYear() + "\u2013", RequisitionFactions.years(last));
        Era first = eras.get(0);
        assertEquals("\u2013" + first.end().getYear(), RequisitionFactions.years(first));
    }

    @Test
    void noAvailabilityIsAnEmptyTable() {
        assertSame(RequisitionFactions.EMPTY, RequisitionFactions.of(Set.of("FW"), Eras.getEras(), Set.of(3025),
              SOURCE));
    }
}
