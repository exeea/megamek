/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.strategicBattleSystems;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import megamek.common.Player;
import megamek.common.SBFFullGameReport;
import org.junit.jupiter.api.Test;

class SBFReportSectionsTest {
    @Test
    void privateEndPhaseDoesNotRevealAnEventToObserversLiveOrAfterReconnect() {
        var history = new SBFFullGameReport();
        var check = new SBFReportEntry(3400);
        var rally = new SBFReportEntry(3401);
        var outcome = new SBFReportHeader(3502);
        var draw = new SBFPublicReportEntry(3500);
        history.restrictTo(check, Set.of(1));
        history.restrictTo(rally, Set.of(2));
        var reports = new ArrayList<SBFReportEntry>(List.of(check, rally, outcome, draw));
        history.prependSectionHeader(reports, 3399);
        history.add(1, reports);
        var observer = new Player(3, "Observer");
        assertEquals(List.of(outcome, draw), history.visibleTo(observer, reports));
        assertEquals(List.of(outcome, draw), history.createFilteredReport(observer).get(1));
        assertEquals(List.of(reports.getFirst(), check, outcome, draw), history.visibleTo(new Player(1, "Blue"), reports));
        assertEquals(List.of(reports.getFirst(), rally, outcome, draw), history.visibleTo(new Player(2, "Red"), reports));
    }

    @Test
    void publicNarrativeKeepsAPublicHeadingAndAnOutcomeAloneNeedsNoEndHeading() {
        var history = new SBFFullGameReport();
        var outcome = new SBFReportHeader(3502);
        var reports = new ArrayList<SBFReportEntry>(List.of(outcome));
        history.prependSectionHeader(reports, 3399);
        assertEquals(List.of(outcome), reports);
        reports.addFirst(new SBFPublicReportEntry(3511));
        history.prependSectionHeader(reports, 3399);
        assertEquals(3, reports.size());
        assertEquals(reports, history.visibleTo(new Player(5, "Late observer"), reports));
    }
}
