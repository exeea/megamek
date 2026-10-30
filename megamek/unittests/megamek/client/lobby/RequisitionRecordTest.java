/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.client.ui.entityreadout.EntityReadout.Row.Kind;
import megamek.common.alphaStrike.conversion.ASConverter;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The dossier's full record and what the Readout, AS card and Damage tabs build from it. */
class RequisitionRecordTest {
    private static Entity atlas;

    @BeforeAll
    static void readAtlas() throws Exception {
        EquipmentType.initializeTypes();
        atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
    }

    // ---- The record ----

    @Test
    void theRecordReadsTheSummaryAndTheTro() {
        MekSummary unit = RequisitionCatalogTest.mek("Atlas", "AS7-D");
        RequisitionRecord record = RequisitionRecord.read(unit, summary -> atlas);
        assertEquals(unit, record.unit());
        assertNotNull(record.entity());
        assertFalse(record.summary().isEmpty());
        assertFalse(record.tro().isEmpty());
        assertTrue(text(record.summary()).contains("Atlas"), "the summary names the unit");
        assertTrue(text(record.tro()).contains("Atlas"), "the TRO names the unit");
        assertTrue(record.tro().stream().anyMatch(row -> row.kind() == Kind.TABLE_ROW), "the TRO has tables");
        assertTrue(text(record.tro()).contains("AC/20") || text(record.tro()).contains("Autocannon/20"),
              "the TRO lists the weapons");
    }

    @Test
    void aRecordThatFailsHasNoEntity() {
        RequisitionRecord failed = RequisitionRecord.read(RequisitionCatalogTest.mek("Atlas", "AS7-D"), unit -> {
            throw new IllegalStateException("no file");
        });
        assertNull(failed.entity());
        assertTrue(failed.summary().isEmpty());
        assertNull(RequisitionRecord.read(RequisitionCatalogTest.mek("Atlas", "AS7-D"), unit -> null).entity());
    }

    @Test
    void theStatsTakeTheHeatFromTheRecord() {
        RequisitionDossier.Stats pending = new RequisitionDossier.Stats("3 / 5", RequisitionDossier.HEAT_PENDING, 304,
              304, 152, 152, "");
        assertEquals(atlas.getHeatCapacity(), pending.with(atlas).heat());
        assertTrue(RequisitionDossier.strip(atlas).contains("4 "), "four medium lasers lead the strip");
    }

    // ---- The readout ----

    @Test
    void theHtmlBecomesRows() {
        List<Row> rows = RequisitionReadout.fromHtml("<html><head><style>p{}</style></head><body>"
              + "<font size='+1'><b>Atlas AS7-D</b></font><br>"
              + "<b>Mass:</b> 100 tons<br>"
              + "<table><tr><th>Type</th><th>Loc</th></tr><tr><td>Medium&nbsp;Laser</td><td>LT</td></tr>"
              + "<tr><td></td><td></td></tr></table>"
              + "<p>Plain &amp; simple</p><ul><li>One</li></ul></body></html>");
        assertEquals(new Row(Kind.TITLE, List.of("Atlas AS7-D")), rows.get(0));
        assertEquals(new Row(Kind.LABELED, List.of("Mass", "100 tons")), rows.get(1));
        assertEquals(new Row(Kind.TABLE_HEADER, List.of("Type", "Loc")), rows.get(2));
        assertEquals(new Row(Kind.TABLE_ROW, List.of("Medium Laser", "LT")), rows.get(3));
        assertTrue(rows.contains(new Row(Kind.TEXT, List.of("Plain & simple"))), rows.toString());
        assertTrue(rows.contains(new Row(Kind.TEXT, List.of("\u2022 One"))), rows.toString());
        assertTrue(rows.stream().noneMatch(row -> text(List.of(row)).contains("p{}")), "the style is left out");
    }

    @Test
    void entitiesAndSpacesAreCleaned() {
        assertEquals("a < b > c \" d ' e", RequisitionReadout.decode("a &lt; b &gt; c &quot; d &#39; e"));
        assertEquals("a b", RequisitionReadout.clean("  a \u00A0\n b  "));
    }

    // ---- The AS card ----

    @Test
    void theCardCarriesTheAlphaStrikeValues() {
        RequisitionAlphaStrike card = RequisitionAlphaStrike.of(ASConverter.convert(atlas));
        assertNotNull(card);
        assertEquals("Atlas AS7-D", card.name());
        assertEquals("BM", card.type());
        assertEquals(4, card.size());
        assertTrue(card.pv() > 0);
        assertTrue(card.armor() > 0 && card.structure() > 0);
        assertEquals(List.of("S", "M", "L"), card.damage().stream().map(RequisitionAlphaStrike.Cell::label).toList());
        assertTrue(card.arcs().isEmpty(), "a Mek has no arcs");
        assertFalse(card.tmm().isEmpty());
        assertFalse(card.move().isEmpty());
    }

    // ---- The damage ----

    @Test
    void theDamageRunsToTheLongestRange() {
        RequisitionDamage damage = RequisitionDamage.of(atlas, 4);
        assertTrue(damage.hasWeapons());
        assertEquals(4, damage.gunnery());
        assertFalse(damage.capital());
        assertEquals(21, damage.reach(), "the LRM 20's long range");
        assertEquals(6, damage.directions().size());
        assertTrue(damage.peak() >= damage.maximum()[0]);
        for (int range = 0; range < damage.reach(); range++) {
            assertTrue(damage.expected()[range] <= damage.maximum()[range] + 1e-9, "range " + (range + 1));
        }
        RequisitionDamage better = RequisitionDamage.of(atlas, 2);
        assertTrue(better.expected()[0] > damage.expected()[0], "a better gunner hits more");
        List<RequisitionDamage.Band> bands = damage.bands();
        assertEquals(1, bands.get(0).from());
        assertEquals(21, bands.get(bands.size() - 1).to());
    }

    @Test
    void bandsHoldTheRangesWhereTheValuesStay() {
        List<RequisitionDamage.Band> bands = RequisitionDamage.bands(new double[] { 5, 5, 5.01, 2 },
              new double[] { 3, 3, 3, 1 }, new double[] { 1, 1, 1, 1 });
        assertEquals(2, bands.size());
        assertEquals("1\u20133", bands.get(0).ranges());
        assertEquals("4", bands.get(1).ranges());
        assertTrue(RequisitionDamage.bands(new double[0], new double[0], new double[0]).isEmpty());
    }

    @Test
    void valuesShowToOneDecimal() {
        assertEquals("5", RequisitionDamage.format(5));
        assertEquals("2.5", RequisitionDamage.format(2.46));
        assertEquals("0", RequisitionDamage.format(0));
    }

    // ---- The loader ----

    @Test
    void theNewestLoadWins() {
        List<Runnable> queued = new ArrayList<>();
        RequisitionLoader loader = new RequisitionLoader(queued::add);
        AtomicReference<String> done = new AtomicReference<>();
        List<String> ran = new ArrayList<>();
        loader.load(() -> {
            ran.add("first");
            return "first";
        }, done::set);
        loader.load(() -> {
            ran.add("second");
            return "second";
        }, done::set);
        queued.forEach(Runnable::run);
        assertEquals(List.of("second"), ran, "work overtaken before it started is skipped");
        assertEquals("second", done.get());

        queued.clear();
        done.set(null);
        loader.load(() -> {
            loader.load(() -> "newer", value -> { });
            return "overtaken";
        }, done::set);
        queued.get(0).run();
        assertNull(done.get(), "work overtaken while it ran is dropped");

        queued.clear();
        loader.load(() -> "cancelled", done::set);
        loader.cancel();
        queued.forEach(Runnable::run);
        assertNull(done.get());
    }

    private static String text(List<Row> rows) {
        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            text.append(String.join(" ", row.cells())).append('\n');
        }
        return text.toString();
    }
}
