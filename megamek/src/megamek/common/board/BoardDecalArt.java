/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;

/**
 * The 3D board's decal art (data/models/board/decals). A decal id {@code decal/<group>/<name>} is the image
 * {@code decals/<group>/<name>.png}, so no catalog is needed to find it. {@code decals/decals.json} gives each decal's
 * footprint and a car park's slots, and decodes the legacy decal ids (the 2D tileset's images) into decals.
 */
public final class BoardDecalArt {
    /** A hex in model px; a decal without a footprint entry covers one hex. */
    private static final Footprint HEX = new Footprint(84, 72, List.of());

    /** A parking place in the decal's frame: model px from its centre, and the car's heading (CCW degrees). */
    public record Slot(double x, double y, double heading) { }

    /** The decal's size at scale 1 in model px, centred on the object's position, and its car slots. */
    public record Footprint(double width, double height, List<Slot> slots) { }

    /**
     * A legacy decal id's decal. A one-hex variant is the decal at {@code scale} and {@code rotation} (CCW degrees). A
     * piece of a 7-hex set ({@code set}) is one decal at scale 1 on the set's centre hex; {@code direction} is the piece's
     * hex direction from the centre (0 N to 5 NW, the order of {@link Coords#translated(int)}), -1 for the centre piece.
     */
    public record Legacy(String decal, double scale, double rotation, boolean set, int direction) {
        /** The centre hex of the set this piece at {@code at} belongs to. */
        public Coords centre(Coords at) { return direction < 0 ? at : at.translated((direction + 3) % 6); }
    }

    private record Table(Map<String, Footprint> footprints, Map<String, Legacy> legacy) { }

    private static final class Holder {
        static final Table TABLE = read(new File(Configuration.dataDir(), "models/board/decals/decals.json"));
    }

    private BoardDecalArt() { }

    /** The image of decal {@code id} by path convention; it need not exist. */
    public static File image(String id) {
        return new File(Configuration.dataDir(), "models/board/decals/" + id.substring(id.indexOf('/') + 1) + ".png");
    }

    public static Footprint footprint(String id) { return Holder.TABLE.footprints().getOrDefault(id, HEX); }

    /** The decal a legacy decal id decodes to, or null. */
    public static Legacy legacy(String id) { return Holder.TABLE.legacy().get(id); }

    private static Table read(File file) {
        if (!file.isFile()) { return new Table(Map.of(), Map.of()); }
        try {
            JsonNode root = new ObjectMapper().readTree(file);
            Map<String, Footprint> footprints = new HashMap<>();
            root.path("decals").fields().forEachRemaining(entry -> {
                JsonNode size = entry.getValue().path("size");
                List<Slot> slots = new ArrayList<>();
                for (JsonNode slot : entry.getValue().path("slots")) {
                    slots.add(new Slot(slot.path("position").get(0).asDouble(), slot.path("position").get(1).asDouble(),
                          slot.path("heading").asDouble()));
                }
                footprints.put(entry.getKey(), new Footprint(size.get(0).asDouble(), size.get(1).asDouble(), List.copyOf(slots)));
            });
            Map<String, Legacy> legacy = new HashMap<>();
            root.path("legacy").fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                legacy.put(entry.getKey(), new Legacy(value.path("decal").asText(), value.path("scale").asDouble(1),
                      value.path("rotation").asDouble(0), value.path("set").asBoolean(false), value.path("direction").asInt(-1)));
            });
            return new Table(Map.copyOf(footprints), Map.copyOf(legacy));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the 3D board's decal table " + file, failure);
        }
    }
}
