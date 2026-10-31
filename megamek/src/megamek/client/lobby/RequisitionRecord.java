/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;
import java.util.function.Function;

import jakarta.annotation.Nullable;
import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * A catalogue unit's full record for the Requisition dossier (gap analysis 7.3): the unit read from its file, with its
 * readout's Summary and Technical Readout as rows, made away from the GPU thread once the Commander stops on the unit.
 *
 * @param unit    the catalogue unit
 * @param entity  the unit read from its file, or null when it could not be read
 * @param summary the unit readout's rows
 * @param tro     the Technical Readout's rows
 */
public record RequisitionRecord(MekSummary unit, @Nullable Entity entity, List<Row> summary, List<Row> tro) {
    private static final MMLogger LOGGER = MMLogger.create(RequisitionRecord.class);

    public RequisitionRecord {
        summary = List.copyOf(summary);
        tro = List.copyOf(tro);
    }

    /** The record of {@code unit}, read through {@code reader}; one without an entity when it fails. */
    public static RequisitionRecord read(MekSummary unit, Function<MekSummary, Entity> reader) {
        Entity entity;
        try {
            entity = reader.apply(unit);
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The requisition could not read {}", unit.getName());
            entity = null;
        }
        if (entity == null) {
            return new RequisitionRecord(unit, null, List.of(), List.of());
        }
        return new RequisitionRecord(unit, entity, RequisitionReadout.summary(entity),
              RequisitionReadout.tro(entity));
    }
}
