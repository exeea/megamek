/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
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

package megamek.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import megamek.common.interfaces.FullGameReport;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;

/**
 * This class gathers the full game report for an SBF game.
 */
public class SBFFullGameReport implements FullGameReport<SBFReportEntry> {

    private final Map<Integer, List<SBFReportEntry>> fullReport = new HashMap<>();
    // Kept on the server so a recipient cannot inspect who else saw the same formation.
    private Map<SBFReportEntry, Set<Integer>> audiences = new HashMap<>();

    @Override
    public void add(int round, List<SBFReportEntry> reports) {
        if ((reports != null) && !reports.isEmpty()) {
            fullReport.computeIfAbsent(round, k -> new ArrayList<>()).addAll(reports);
        }
    }

    @Override
    public boolean hasReportsForRound(int round) {
        return fullReport.containsKey(round);
    }

    @Override
    public List<SBFReportEntry> get(int round) {
        return fullReport.getOrDefault(round, new ArrayList<>());
    }

    @Override
    public void clear() {
        fullReport.clear();
        if (audiences != null) {
            audiences.clear();
        }
    }

    public void replaceAllReports(Map<Integer, List<SBFReportEntry>> newReports) {
        clear();
        fullReport.putAll(newReports);
    }


    public void restrictTo(SBFReportEntry entry, Set<Integer> recipientIds) {
        if (audiences == null) {
            audiences = new HashMap<>(); // Games saved before report visibility was recorded.
        }
        audiences.put(entry, new HashSet<>(recipientIds));
    }

    /** Introduce the leading narrative for exactly the players who can read at least one entry in it. */
    public void prependSectionHeader(List<SBFReportEntry> reports, int messageId) {
        var narrative = reports.stream().takeWhile(entry -> !(entry instanceof SBFReportHeader)).toList();
        if (narrative.isEmpty()) {
            return;
        }
        var header = new SBFReportHeader(messageId);
        if (audiences != null && narrative.stream().allMatch(audiences::containsKey)) {
            restrictTo(header, narrative.stream().flatMap(entry -> audiences.get(entry).stream())
                  .collect(Collectors.toSet()));
        }
        reports.addFirst(header);
    }

    /** Uses the visibility at the time of the event, including when serving historical reports on reconnect. */
    public List<SBFReportEntry> visibleTo(Player recipient, List<SBFReportEntry> entries) {
        return entries.stream().filter(entry -> (audiences == null) || !audiences.containsKey(entry)
              || audiences.get(entry).contains(recipient.getId())).collect(Collectors.toCollection(ArrayList::new));
    }

    public Map<Integer, List<SBFReportEntry>> createFilteredReport(Player recipient) {
        Map<Integer, List<SBFReportEntry>> filteredReports = new HashMap<>();
        for (int round : fullReport.keySet()) {
            filteredReports.put(round, visibleTo(recipient, fullReport.get(round)));
        }
        return filteredReports;
    }
}
