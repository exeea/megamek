/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import megamek.common.Configuration;
import megamek.common.annotations.Nullable;
import megamek.logging.MMLogger;

/**
 * Measured heights of physical BattleTech miniatures, keyed by unit file UUID, read from {@code BTHeights.csv} in the
 * units directory. A listed Mek's meeple is drawn at its miniature's height instead of its weight class's default.
 *
 * <p>Every listed Mek is measured against one ruler: the average assault miniature ({@link #REFERENCE_ASSAULT_MM})
 * stands as tall as today's assault meeple. An Atlas at 55.47 mm therefore stands 1.10 times an assault meeple, and a
 * Locust at 39.88 mm 0.79 times, whatever its weight class.</p>
 */
final class MiniatureHeights {
    private static final MMLogger LOGGER = MMLogger.create(MiniatureHeights.class);
    static final String FILE_NAME = "BTHeights.csv";
    /** Average height of the assault Mek miniatures on the list, each chassis counted once. */
    static final float REFERENCE_ASSAULT_MM = 50.17f;

    private final Map<String, Float> heightsByUuid;
    private final Set<String> reportedUuids = ConcurrentHashMap.newKeySet();

    MiniatureHeights(Map<String, Float> heightsByUuid) {
        this.heightsByUuid = Map.copyOf(heightsByUuid);
    }

    /** Loaded once, on first use, from the configured units directory. */
    private static final class Holder {
        private static final MiniatureHeights INSTANCE = load(Configuration.unitsDir().toPath().resolve(FILE_NAME));
    }

    static MiniatureHeights instance() {
        return Holder.INSTANCE;
    }

    /**
     * Reads the height list. A missing or unreadable file gives an empty list, so every Mek keeps its weight class
     * default. Rows with no height, or a height that is not a positive number, are skipped.
     *
     * @param file the CSV to read: columns Chassis, Model, UUID, Height, with a header row
     *
     * @return the heights found in the file
     */
    static MiniatureHeights load(Path file) {
        if (!Files.isRegularFile(file)) {
            LOGGER.info("[MeepleHeight] {} not found; all Mek meeples use their weight class height", file);
            return new MiniatureHeights(Map.of());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("[MeepleHeight] Could not read {}; all Mek meeples use their weight class height", file,
                  exception);
            return new MiniatureHeights(Map.of());
        }
        Map<String, Float> heights = new HashMap<>();
        int skippedRows = 0;
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            Row row = Row.parse(line);
            if (row == null) {
                skippedRows++;
                continue;
            }
            heights.put(row.uuid(), row.heightMillimetres());
        }
        LOGGER.info("[MeepleHeight] Loaded {} miniature heights from {} ({} rows without a usable height)",
              heights.size(), file, skippedRows);
        return new MiniatureHeights(heights);
    }

    /**
     * One usable line of the list.
     *
     * @param uuid               the unit file UUID
     * @param heightMillimetres  the miniature's height without its base
     */
    private record Row(String uuid, float heightMillimetres) {
        /**
         * Reads the last two columns. They are split from the right because the UUID and height never contain a
         * comma, while a quoted chassis or model name may.
         *
         * @return the row, or {@code null} when it has no UUID or no positive numeric height
         */
        static @Nullable Row parse(String line) {
            int heightComma = line.lastIndexOf(',');
            int uuidComma = heightComma <= 0 ? -1 : line.lastIndexOf(',', heightComma - 1);
            if (uuidComma < 0) {
                return null;
            }
            String uuid = line.substring(uuidComma + 1, heightComma).trim();
            String height = line.substring(heightComma + 1).trim();
            if (uuid.isEmpty() || height.isEmpty()) {
                return null;
            }
            try {
                float heightMillimetres = Float.parseFloat(height);
                return heightMillimetres > 0 ? new Row(uuid, heightMillimetres) : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }

    /**
     * @param unitFileUuid the unit file UUID of the Mek, or {@code null} when it has none
     *
     * @return the miniature's height as a multiple of the reference assault miniature, or {@code null} when the unit
     *       is not on the list
     */
    @Nullable Float heightScale(@Nullable String unitFileUuid) {
        if (unitFileUuid == null) {
            return null;
        }
        Float height = heightsByUuid.get(unitFileUuid);
        // Units are captured on every board refresh; report each one's outcome once.
        if (reportedUuids.add(unitFileUuid)) {
            if (height == null) {
                LOGGER.debug("[MeepleHeight] {}: not on the miniature list, weight class height used", unitFileUuid);
            } else {
                LOGGER.debug("[MeepleHeight] {}: miniature {} mm, {} x assault height", unitFileUuid, height,
                      height / REFERENCE_ASSAULT_MM);
            }
        }
        return height == null ? null : height / REFERENCE_ASSAULT_MM;
    }
}
