/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import megamek.common.units.EntityMovementMode;
import megamek.logging.MMLogger;

/** Immutable, exact chassis lookup in a locally converted CAB installation. Contains no game state. */
final class HbsUnitCatalog {
    static final String ENABLED_PROPERTY = "megamek.hbs.models";
    static final String CACHE_PROPERTY = "megamek.hbs.cache";
    private static final MMLogger LOGGER = MMLogger.create(HbsUnitCatalog.class);
    private final Map<String, String> models;
    private final Properties aliases = new Properties();
    final Path root;

    HbsUnitCatalog(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        var catalog = new JsonReader().parse(new FileHandle(contained("catalog.json").toFile()));
        if (catalog.getInt("schema", 0) != 1 || catalog.get("models") == null) {
            throw new IOException("Unsupported HBS catalog");
        }
        Map<String, String> entries = new HashMap<>();
        for (var entry : catalog.get("models")) {
            entries.put(key(entry.name), entry.getString("descriptor"));
        }
        models = Map.copyOf(entries);
        try (var stream = HbsUnitCatalog.class.getResourceAsStream("hbs-chassis.properties")) {
            if (stream != null) {
                aliases.load(stream);
            }
        }
    }

    static HbsUnitCatalog configured() {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return null;
        }
        String cache = System.getProperty(CACHE_PROPERTY, "userdata/hbs-models");
        try {
            var catalog = new HbsUnitCatalog(Path.of(cache));
            LOGGER.info("[HBS] {} locally imported chassis available from {}", catalog.models.size(), catalog.root);
            return catalog;
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("[HBS] Cannot read {}; using Gaea models: {}", cache, error.getMessage());
            return null;
        }
    }

    Path contained(String relative) throws IOException {
        return UnitModelDescriptor.contained(root, root.resolve(relative));
    }

    String descriptor(BoardScene.UnitModel selection) {
        // Alternate LAM/QuadVee forms and non-bipeds keep their existing form-specific models.
        if (selection == null || selection.chassis() == null || selection.state() == null
              || selection.state().structure().movement() != EntityMovementMode.BIPED) {
            return null;
        }
        return descriptor(selection.chassis());
    }

    String descriptor(String chassis) {
        String match = lookup(chassis);
        if (match != null) {
            return match;
        }
        // MegaMek often writes both IS and Clan names: "Mad Cat (Timber Wolf)".
        int open = chassis.indexOf('('), close = chassis.indexOf(')');
        if (open > 0 && close == chassis.length() - 1) {
            match = lookup(chassis.substring(0, open));
            return match == null ? lookup(chassis.substring(open + 1, close)) : match;
        }
        return null;
    }

    private String lookup(String chassis) {
        String normalized = key(chassis);
        String direct = models.get(normalized);
        if (direct != null) {
            return direct;
        }
        for (String alias : aliases.getProperty(normalized, "").split(",")) {
            String match = models.get(alias.strip());
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
