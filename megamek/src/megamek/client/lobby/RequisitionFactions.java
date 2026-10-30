/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.AvailabilityRating;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.ModelRecord;
import megamek.client.ratgenerator.RATGenerator;
import megamek.common.eras.Era;
import megamek.common.eras.Eras;
import megamek.common.loaders.MekSummary;

/**
 * The Requisition dossier's Factions tab (gap analysis 7.3): how available a unit is to each faction in each era, as
 * the unit selector's availability table shows it, read from the Force Generator's data. Only the eras where some faction
 * has the unit show; a faction without the unit in an era shows "-". Reading the data waits
 * for the Force Generator to load and loads every era's year once, so this runs away from the GPU thread.
 *
 * @param eras     the eras shown, in order
 * @param factions each faction that fields the unit, with its availability per era shown
 */
public record RequisitionFactions(List<EraColumn> eras, List<Faction> factions) {
    /** What a faction without the unit in an era shows. */
    public static final String NONE = "-";
    /** How long to wait for the Force Generator to load, in milliseconds. */
    private static final long WAIT_MILLIS = 60_000;
    private static final Object LOCK = new Object();
    private static volatile boolean yearsLoaded;

    /** The empty availability, for a unit the Force Generator does not know. */
    public static final RequisitionFactions EMPTY = new RequisitionFactions(List.of(), List.of());

    /** One era's column: its name and its years. */
    public record EraColumn(String name, String years) { }

    /**
     * One faction's row: its name and code, and its availability in each era shown, one rating a line when the
     * faction's sub-commands differ (as "Very Rare (B)" over "Rare (D)").
     */
    public record Faction(String name, String code, List<String> cells) {
        public Faction {
            cells = List.copyOf(cells);
        }
    }

    /** The ratings in one era's {@code cell}, one per line, without blanks; none for {@link #NONE}. */
    public static List<String> ratings(String cell) {
        if (NONE.equals(cell)) {
            return List.of();
        }
        return cell.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    public RequisitionFactions {
        eras = List.copyOf(eras);
        factions = List.copyOf(factions);
    }

    /**
     * The availability of {@code unit}, waiting for the Force Generator to load; {@link #EMPTY} when it does not know
     * the unit or does not load in time.
     */
    public static RequisitionFactions of(MekSummary unit) {
        RATGenerator generator = RATGenerator.getInstance();
        long until = System.currentTimeMillis() + WAIT_MILLIS;
        while (!generator.isInitialized() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return EMPTY;
            }
        }
        if (!generator.isInitialized()) {
            return EMPTY;
        }
        if (!yearsLoaded) {
            synchronized (LOCK) {
                if (!yearsLoaded) {
                    generator.getEraSet().forEach(generator::loadYear);
                    yearsLoaded = true;
                }
            }
        }
        ModelRecord record = generator.getModelRecord((unit.getChassis() + " " + unit.getModel()).trim());
        if (record == null) {
            return EMPTY;
        }
        return of(record.getIncludedFactions(), Eras.getEras(), generator.getEraSet(),
              new Source() {
                  @Override
                  public @Nullable FactionRecord faction(String code) {
                      return generator.getFaction(code);
                  }

                  @Override
                  public String merged(String code, List<Integer> years) {
                      List<AvailabilityRating> ratings = new ArrayList<>();
                      for (int year : years) {
                          AvailabilityRating rating = generator.findModelAvailabilityRecord(year, record.getKey(),
                                code, year);
                          if (rating != null) {
                              ratings.add(rating);
                          }
                      }
                      if (ratings.isEmpty()) {
                          return "";
                      }
                      AvailabilityRating merged = generator.mergeFactionAvailability(code, ratings);
                      return (merged == null) ? "" : merged.formatAvailability(generator.getFaction(code));
                  }
              });
    }

    /** Where {@link #of(Set, List, Set, Source)} reads the Force Generator's data. */
    interface Source {
        /** The faction of {@code code}, or null. */
        @Nullable FactionRecord faction(String code);

        /**
         * The unit's availability to the faction {@code code} over the Force Generator's {@code years} of one era,
         * merged and formatted, or empty when it has none.
         */
        String merged(String code, List<Integer> years);
    }

    /**
     * The availability table of the factions {@code codes} over {@code eras}, reading the Force Generator's
     * {@code years}: a year counts towards the era it falls in, while the faction is active then.
     */
    static RequisitionFactions of(Set<String> codes, List<Era> eras, Set<Integer> years, Source source) {
        List<List<String>> table = new ArrayList<>();
        boolean[] hasData = new boolean[eras.size()];
        for (String code : codes) {
            FactionRecord faction = source.faction(code);
            List<String> row = new ArrayList<>();
            for (int i = 0; i < eras.size(); i++) {
                Era era = eras.get(i);
                List<Integer> inEra = new ArrayList<>();
                for (int year : years) {
                    if ((Eras.getEra(year) == era) && ((faction == null) || faction.isActiveInYear(year))) {
                        inEra.add(year);
                    }
                }
                String text = inEra.isEmpty() ? "" : source.merged(code, inEra).trim();
                hasData[i] |= !text.isEmpty();
                row.add(text);
            }
            table.add(row);
        }
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < hasData.length; i++) {
            if (hasData[i]) {
                shown.add(i);
            }
        }
        if (shown.isEmpty()) {
            return EMPTY;
        }
        List<EraColumn> columns = new ArrayList<>();
        for (int i : shown) {
            Era era = eras.get(i);
            columns.add(new EraColumn(era.name().replace('\n', ' '), years(era)));
        }
        List<Faction> factions = new ArrayList<>();
        int index = 0;
        for (String code : codes) {
            List<String> row = table.get(index++);
            if (row.stream().allMatch(String::isEmpty)) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int i : shown) {
                cells.add(row.get(i).isEmpty() ? NONE : row.get(i));
            }
            FactionRecord faction = source.faction(code);
            String name = ((faction != null) && (faction.getName() != null)) ? faction.getName() : code;
            factions.add(new Faction(name, code, cells));
        }
        factions.sort((one, other) -> one.name().compareToIgnoreCase(other.name()));
        return new RequisitionFactions(columns, factions);
    }

    /** An era's years, as "3050–3061", "3151–" for one that has not ended or "–2005" for the first. */
    static String years(Era era) {
        String start = ((era.start() == null) || era.start().equals(LocalDate.MIN)) ? ""
              : Integer.toString(era.start().getYear());
        String end = ((era.end() == null) || era.isLastEra()) ? "" : Integer.toString(era.end().getYear());
        return start + "\u2013" + end;
    }
}
