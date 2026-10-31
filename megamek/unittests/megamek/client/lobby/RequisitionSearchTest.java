/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.lobby.RequisitionCatalog.Category;
import megamek.client.lobby.RequisitionCatalog.Filters;
import megamek.client.lobby.RequisitionCatalog.Rules;
import megamek.client.lobby.RequisitionSearch.Query;
import megamek.client.lobby.RequisitionSearch.Result;
import megamek.common.loaders.MekSummary;
import org.junit.jupiter.api.Test;

class RequisitionSearchTest {

    private final AtomicInteger reads = new AtomicInteger();
    private final MekSummary atlas = RequisitionCatalogTest.mek("Atlas", "AS7-D");
    private final MekSummary archer = RequisitionCatalogTest.mek("Archer", "ARC-2R");

    private RequisitionCatalog.Index read() {
        reads.incrementAndGet();
        return RequisitionCatalog.index(new MekSummary[] { atlas, archer }, unit -> false, unit -> null);
    }

    private static Query query(String search) {
        return new Query(Rules.OPEN, Category.ALL, Filters.NONE, search);
    }

    @Test
    void aSearchReadsTheCatalogueOnceAndFindsTheUnits() {
        RequisitionSearch search = new RequisitionSearch(this::read, Runnable::run);
        List<Result> results = new ArrayList<>();

        search.search(query("atlas"), results::add);
        search.search(query(null), results::add);

        assertEquals(1, reads.get());
        assertEquals(List.of("Atlas AS7-D"), results.get(0).rows().stream().map(RequisitionRow::name).toList());
        assertEquals(2, results.get(0).total());
        assertTrue(results.get(0).loadMillis() > 0);
        assertEquals(0, results.get(1).loadMillis());
        assertEquals("", results.get(1).query().search());
        assertEquals(2, results.get(1).rows().size());
        assertSame(results.get(0).rows().get(0), search.row(atlas), "rows are made once");
    }

    @Test
    void onlyTheNewestQueryIsAnswered() {
        Deque<Runnable> queue = new ArrayDeque<>();
        RequisitionSearch search = new RequisitionSearch(this::read, queue::add);
        List<Result> results = new ArrayList<>();

        search.search(query("a"), results::add);
        search.search(query("ar"), results::add);
        search.search(query("arch"), results::add);
        queue.forEach(Runnable::run);

        assertEquals(1, results.size());
        assertEquals("arch", results.get(0).query().search());
        assertTrue(results.get(0).loadMillis() > 0, "the catalogue's reading is reported with the answered query");
    }

    @Test
    void aStaleQueryLeavesTheLoadToBeReported() {
        Deque<Runnable> queue = new ArrayDeque<>();
        List<Result> results = new ArrayList<>();
        RequisitionSearch[] search = new RequisitionSearch[1];
        // A newer query comes in while the first one reads the catalogue, so the first one's result goes unused.
        search[0] = new RequisitionSearch(() -> {
            search[0].search(query("ar"), results::add);
            return read();
        }, queue::add);

        search[0].search(query("a"), results::add);
        Runnable first = queue.poll();
        assertNotNull(first);
        first.run();
        assertTrue(results.isEmpty());
        queue.forEach(Runnable::run);

        assertEquals(1, results.size());
        assertEquals("ar", results.get(0).query().search());
        assertTrue(results.get(0).loadMillis() > 0);
    }

    @Test
    void reloadReadsTheCatalogueAgain() {
        RequisitionSearch search = new RequisitionSearch(this::read, Runnable::run);
        search.search(query(""), result -> { });
        search.reload();
        search.search(query(""), result -> { });
        assertEquals(2, reads.get());
    }

    @Test
    void aMissingCatalogueFindsNothing() {
        RequisitionSearch search = new RequisitionSearch(() -> null, Runnable::run);
        List<Result> results = new ArrayList<>();
        search.search(query(""), results::add);
        assertEquals(0, results.get(0).total());
    }
}
