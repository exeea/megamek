/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/**
 * The board models' stable ids, for saved boards alone: {@code models/board/uids.json} maps each model's opaque id, never
 * reused, to its current path there without {@code .glb}. The game and its code name a model by its path; a saved
 * board ({@link BoardFile}) stores the path and the id, so a model moved or renamed since still loads.
 * {@code tools/move_model.py} moves a model's file and its line together.
 */
public final class BoardModelIds {
    private static final MMLogger LOGGER = MMLogger.create(BoardModelIds.class);

    /** The ids by path and the paths by id. */
    record Index(Map<String, String> paths, Map<String, String> uids) {
        static Index read(File file) {
            if (!file.isFile()) { return new Index(Map.of(), Map.of()); }
            Map<String, String> paths = new HashMap<>(), uids = new HashMap<>();
            try {
                for (var entry : new ObjectMapper().readTree(file).properties()) {
                    paths.put(entry.getKey(), entry.getValue().asText());
                    uids.put(entry.getValue().asText(), entry.getKey());
                }
            } catch (IOException error) {
                LOGGER.error("Cannot read the board model ids {}: {}", file, error.getMessage());
            }
            return new Index(Map.copyOf(paths), Map.copyOf(uids));
        }
    }

    private static final class Holder {
        static final Index INDEX = Index.read(new File(Configuration.dataDir(), "models/board/uids.json"));
    }

    private BoardModelIds() { }

    /** The stable id of the model at {@code path}; null for a path no model has (a decal, a tree's winter form). */
    public static String uid(String path) { return path == null ? null : Holder.INDEX.uids().get(path); }

    /** A saved model's current path: its id's, or the path saved with it when the id is unknown or absent. */
    public static String path(String uid, String saved) {
        return uid == null ? saved : Holder.INDEX.paths().getOrDefault(uid, saved);
    }
}
