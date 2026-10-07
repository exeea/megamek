/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Hex;
import megamek.common.units.Terrains;

/** Shared board-file boundary. Native documents contain the Board's rules and authored visuals, never a projection. */
public final class BoardFile {
    public static final String EXTENSION = ".board2";
    private static final ObjectMapper JSON = new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BoardFile() { }

    public static boolean isBoardName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".board") || lower.endsWith(EXTENSION);
    }

    public static boolean isNativeName(String name) { return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION); }

    public static String withoutExtension(String name) {
        return isBoardName(name) ? name.substring(0, name.length() - (isNativeName(name) ? 7 : 6)) : name;
    }

    /** Names sent by the lobby retain the native extension; historical extensionless selections remain valid. */
    public static String fileName(String name) { return isBoardName(name) ? name : name + ".board"; }
    public static String selectionName(String file) { return isNativeName(file) ? file : withoutExtension(file); }

    public record Metadata(BoardDimensions size, Set<String> tags) { }

    /** Index native headers without constructing hexes, models, or a game. Also accepts reordered JSON fields. */
    public static Metadata metadata(Path file) throws IOException {
        ObjectNode header = JSON.createObjectNode();
        try (JsonParser parser = JSON.getFactory().createParser(file.toFile())) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) { throw new IOException("Expected board object"); }
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.FIELD_NAME) { throw new IOException("Malformed board header"); }
                String name = parser.currentName(); parser.nextToken();
                if (Set.of("format", "version", "size", "tags").contains(name)) {
                    header.set(name, JSON.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser));
                } else { parser.skipChildren(); }
            }
            if (parser.nextToken() != null) { throw new IOException("Trailing board data"); }
        }
        try {
            require("megamek-board".equals(string(header, "format", "")) && integer(header, "version", -1) == 2,
                  "Unsupported native board");
            JsonNode size = header.get("size"); only(size, "width", "height");
            int width = integer(size, "width", 0), height = integer(size, "height", 0);
            require(width > 0 && height > 0, "Invalid board size");
            Set<String> tags = new HashSet<>();
            if (header.has("tags")) {
                require(header.get("tags").isArray(), "Invalid tags");
                for (JsonNode tag : header.get("tags")) { require(tag.isTextual(), "Invalid tag"); tags.add(tag.textValue()); }
            }
            return new Metadata(new BoardDimensions(width, height), Set.copyOf(tags));
        } catch (IllegalArgumentException failure) { throw new IOException(failure.getMessage(), failure); }
    }

    /** Keep old names resolving to old files when both formats exist; explicit native names are never rewritten. */
    public static Path resolve(Path directory, String name) {
        if (isBoardName(name)) { return directory.resolve(name); }
        Path legacy = directory.resolve(name + ".board");
        return Files.exists(legacy) ? legacy : directory.resolve(name + EXTENSION);
    }

    public static boolean looksNative(String text) {
        String start = text.stripLeading();
        if (start.startsWith("\uFEFF")) { start = start.substring(1).stripLeading(); }
        return start.startsWith("{") || start.startsWith("[");
    }

    public static Board read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (isNativeName(path.toString()) || looksNative(text)) { return readNative(text); }
        Board board = new Board();
        List<String> errors = new ArrayList<>();
        try {
            board.load(text, errors);
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read board: " + failure.getMessage(), failure);
        }
        if (board.getWidth() <= 0 || board.getHeight() <= 0 || !errors.isEmpty()) {
            throw new IOException("Invalid board: " + String.join("; ", errors));
        }
        return board;
    }

    public static Board readNative(String text) throws IOException {
        try {
            JsonNode root = JSON.readTree(text.startsWith("\uFEFF") ? text.substring(1) : text);
            only(root, "format", "version", "size", "hexes", "name", "description", "tags", "boardType", "options", "notes", "sourceHeader");
            require("megamek-board".equals(string(root, "format", null)), "Not a MegaMek board document");
            require(integer(root, "version", -1) == 2, "Unsupported board version; editing refused");
            JsonNode size = root.get("size");
            only(size, "width", "height");
            int width = integer(size, "width", 0), height = integer(size, "height", 0);
            JsonNode entries = root.get("hexes");
            require(entries != null && entries.isArray(), "hexes must be an array");
            long count = (long) width * height;
            require(width > 0 && height > 0 && count <= Integer.MAX_VALUE && count == entries.size(),
                  "Board dimensions must match the complete hex array");
            Hex[] hexes = new Hex[(int) count];
            Set<String> ids = new HashSet<>();
            for (JsonNode entry : entries) {
                only(entry, "at", "elevation", "terrain", "theme", "appearance", "decorations");
                Coords coords = coordinate(entry.get("at"));
                require(coords.getX() >= 0 && coords.getY() >= 0 && coords.getX() < width && coords.getY() < height,
                      "Hex outside board: " + coords);
                int index = coords.getY() * width + coords.getX();
                require(hexes[index] == null, "Duplicate hex coordinates: " + coords);
                require(entry.has("elevation"), "Hex elevation is required");
                String terrain = string(entry, "terrain", "");
                validateTerrain(terrain);
                Hex hex = new Hex(integer(entry, "elevation", 0), terrain, string(entry, "theme", null), coords);
                readVisuals(entry, hex);
                for (BoardDecoration decoration : hex.getDecorations()) {
                    require(ids.add(decoration.id()), "Duplicate board object ID: " + decoration.id());
                }
                hexes[index] = hex;
            }
            Board board = new Board();
            if (root.has("options")) {
                JsonNode options = root.get("options");
                only(options, "exitRoadsToPavement");
                board.setRoadsAutoExit(bool(options, "exitRoadsToPavement", true));
            }
            board.setBoardType(BoardType.valueOf(string(root, "boardType", "GROUND")));
            board.setDocumentName(string(root, "name", null));
            board.setDescription(string(root, "description", null));
            board.setSourceHeader(string(root, "sourceHeader", null));
            if (root.has("tags")) {
                require(root.get("tags").isArray(), "tags must be an array");
                for (JsonNode tag : root.get("tags")) {
                    require(tag.isTextual(), "A tag must be a string");
                    require(!board.getTags().contains(tag.textValue()), "Duplicate tag");
                    board.addTag(tag.textValue());
                }
            }
            if (root.has("notes")) {
                require(root.get("notes").isArray(), "notes must be an array");
                for (JsonNode note : root.get("notes")) {
                    only(note, "at", "text");
                    Coords at = coordinate(note.get("at"));
                    require(!board.getAnnotations().containsKey(at), "Duplicate note coordinate");
                    require(note.has("text") && note.get("text").isArray(), "Note text must be an array");
                    List<String> lines = new ArrayList<>();
                    for (JsonNode line : note.get("text")) {
                        require(line.isTextual(), "Note text must contain strings");
                        lines.add(line.textValue());
                    }
                    board.setAnnotations(at, List.copyOf(lines));
                }
            }
            List<String> errors = new ArrayList<>();
            board.newData(width, height, hexes, errors);
            board.isValid(errors);
            require(errors.isEmpty(), "Invalid terrain: " + String.join("; ", errors));
            board.setNativeFormat(true);
            return board;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid .board2: " + failure.getMessage(), failure);
        }
    }

    private static void validateTerrain(String terrain) {
        Set<Integer> seen = new HashSet<>();
        if (terrain.isEmpty()) { return; }
        for (String token : terrain.split(";", -1)) {
            String[] parts = token.split(":", -1);
            require(parts.length == 2 || parts.length == 3, "Malformed terrain: " + token);
            int type = Terrains.getType(parts[0]);
            require(type != 0 || parts[0].equals(Terrains.getName(0)), "Unknown terrain: " + parts[0]);
            require(seen.add(type), "Duplicate terrain: " + parts[0]);
            for (int i = 1; i < parts.length; i++) { Integer.parseInt(parts[i]); }
        }
    }

    private static void readVisuals(JsonNode entry, Hex hex) {
        if (entry.has("appearance")) {
            JsonNode appearances = entry.get("appearance");
            only(appearances, HexAppearance.OWNERS.toArray(String[]::new));
            Map<String, HexAppearance> values = new LinkedHashMap<>();
            appearances.fields().forEachRemaining(field -> {
                JsonNode style = field.getValue();
                only(style, "variant", "asset", "material", "strength");
                HexAppearance value = new HexAppearance(string(style, "variant", null), string(style, "asset", null),
                      string(style, "material", null), style.has("strength") ? number(style, "strength", 0) : null);
                value.validateOwner(field.getKey());
                values.put(field.getKey(), value);
            });
            hex.setAppearance(values);
        }
        if (entry.has("decorations")) {
            require(entry.get("decorations").isArray(), "decorations must be an array");
            List<BoardDecoration> values = new ArrayList<>();
            for (JsonNode object : entry.get("decorations")) {
                only(object, "id", "kind", "asset", "name", "position", "rotation", "mirror", "scale", "placement", "drawOrder", "clipToHex");
                double x = 0, y = 0;
                if (object.has("position")) {
                    JsonNode position = object.get("position");
                    require(position.isArray() && position.size() == 2, "position must contain X and Y");
                    x = finite(position.get(0)); y = finite(position.get(1));
                }
                String kind = string(object, "kind", null);
                require(!"prop".equals(kind) || !object.has("drawOrder"), "Props cannot have paint order");
                JsonNode placement = object.get("placement");
                only(placement, "mode", "level", "receiver", "offset");
                BoardDecoration.Receiver receiver = null;
                if (placement.has("receiver")) {
                    JsonNode target = placement.get("receiver");
                    only(target, "terrain", "surface");
                    receiver = new BoardDecoration.Receiver(string(target, "terrain", null), string(target, "surface", null));
                }
                values.add(new BoardDecoration(string(object, "id", null), kind, string(object, "asset", null),
                      string(object, "name", null), x, y, number(object, "rotation", 0), bool(object, "mirror", false),
                      number(object, "scale", 1), new BoardDecoration.Placement(string(placement, "mode", null),
                            placement.has("level") ? number(placement, "level", 0) : null, receiver,
                            placement.has("offset") ? number(placement, "offset", 0) : null), integer(object, "drawOrder", 0),
                      bool(object, "clipToHex", "decal".equals(kind))));
            }
            hex.setDecorations(values);
        }
    }

    /** Compact, deterministic output: even a decorated hex occupies a single physical line. */
    public static void write(Board board, OutputStream stream) throws IOException {
        if (board.getWidth() <= 0 || board.getHeight() <= 0) { throw new IOException("Cannot save an empty board"); }
        List<String> errors = new ArrayList<>();
        if (!board.isValid(errors)) { throw new IOException("Cannot save invalid terrain: " + String.join("; ", errors)); }
        Set<String> ids = new HashSet<>();
        ObjectNode root = JSON.createObjectNode().put("format", "megamek-board").put("version", 2);
        root.putObject("size").put("width", board.getWidth()).put("height", board.getHeight());
        put(root, "name", board.getDocumentName());
        put(root, "description", board.getDescription());
        put(root, "sourceHeader", board.getSourceHeader());
        if (board.getBoardType() != BoardType.GROUND) { root.put("boardType", board.getBoardType().name()); }
        if (!board.getRoadsAutoExit()) { root.putObject("options").put("exitRoadsToPavement", false); }
        if (!board.getTags().isEmpty()) {
            ArrayNode tags = root.putArray("tags"); board.getTags().stream().sorted().forEach(tags::add);
        }
        if (!board.getAnnotations().isEmpty()) {
            ArrayNode notes = root.putArray("notes");
            board.getAnnotations().entrySet().stream().sorted(Comparator
                  .comparingInt((Map.Entry<Coords, ?> e) -> e.getKey().getY()).thenComparingInt(e -> e.getKey().getX()))
                  .forEach(entry -> {
                      ObjectNode note = notes.addObject(); at(note, entry.getKey());
                      ArrayNode lines = note.putArray("text"); entry.getValue().forEach(lines::add);
                  });
        }
        // Validate identity before touching the stream. Atomic disk saves below also isolate all write failures.
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Hex hex = board.getHex(x, y);
                if (hex == null) { throw new IOException("Cannot save a missing hex"); }
                for (BoardDecoration decoration : hex.getDecorations()) {
                    if (!ids.add(decoration.id())) { throw new IOException("Duplicate board object ID: " + decoration.id()); }
                }
            }
        }
        String header = JSON.writeValueAsString(root);
        stream.write((header.substring(0, header.length() - 1) + ",\n\"hexes\":[\n").getBytes(StandardCharsets.UTF_8));
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                ObjectNode entry = hexNode(board.getHex(x, y)); at(entry, new Coords(x, y));
                String comma = x == board.getWidth() - 1 && y == board.getHeight() - 1 ? "" : ",";
                stream.write((JSON.writeValueAsString(entry) + comma + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        stream.write("]}\n".getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }

    public static void save(Board board, Path destination) throws IOException {
        if (!isNativeName(destination.toString())) { throw new IOException("New boards must be saved as .board2"); }
        Path absolute = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".board2-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { write(board, output); }
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
            board.setNativeFormat(true);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static ObjectNode hexNode(Hex hex) {
        ObjectNode node = JSON.createObjectNode().put("elevation", hex.getLevel());
        String terrain = Arrays.stream(hex.getTerrainTypes()).filter(t -> !Terrains.AUTOMATIC.contains(t)).sorted()
              .mapToObj(t -> hex.getTerrain(t).toString()).collect(Collectors.joining(";"));
        if (!terrain.isEmpty()) { node.put("terrain", terrain); }
        if (hex.getTheme() != null && !hex.getTheme().isEmpty()) { node.put("theme", hex.getTheme()); }
        if (!hex.getAppearance().isEmpty()) {
            ObjectNode styles = node.putObject("appearance");
            hex.getAppearance().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                ObjectNode style = styles.putObject(entry.getKey()); HexAppearance value = entry.getValue();
                put(style, "variant", value.variant()); put(style, "asset", value.asset()); put(style, "material", value.material());
                if (value.strength() != null) { style.put("strength", value.strength()); }
            });
        }
        if (!hex.getDecorations().isEmpty()) {
            ArrayNode objects = node.putArray("decorations");
            hex.getDecorations().forEach(value -> {
                ObjectNode object = objects.addObject().put("id", value.id()).put("kind", value.kind()).put("asset", value.asset());
                put(object, "name", value.name());
                if (value.x() != 0 || value.y() != 0) { object.putArray("position").add(value.x()).add(value.y()); }
                if (value.rotation() != 0) { object.put("rotation", value.rotation()); }
                if (value.mirror()) { object.put("mirror", true); }
                if (value.scale() != 1) { object.put("scale", value.scale()); }
                if (value.kind().equals("decal")) { object.put("clipToHex", value.clipToHex()); }
                if (value.drawOrder() != 0) { object.put("drawOrder", value.drawOrder()); }
                ObjectNode placement = object.putObject("placement").put("mode", value.placement().mode());
                if (value.placement().level() != null) { placement.put("level", value.placement().level()); }
                if (value.placement().receiver() != null) {
                    placement.putObject("receiver").put("terrain", value.placement().receiver().terrain())
                          .put("surface", value.placement().receiver().surface());
                    placement.put("offset", value.placement().offset());
                }
            });
        }
        return node;
    }

    public static String clipboard(Hex hex) { return "MegaMek Hex2\n" + hexNode(hex); }

    public static Hex fromClipboard(String text) throws IOException {
        try {
            JsonNode node = JSON.readTree(text.substring("MegaMek Hex2\n".length()));
            only(node, "elevation", "terrain", "theme", "appearance", "decorations");
            String terrain = string(node, "terrain", ""); validateTerrain(terrain);
            Hex hex = new Hex(integer(node, "elevation", 0), terrain, string(node, "theme", null));
            readVisuals(node, hex); hex.duplicateDecorationIds();
            return hex;
        } catch (RuntimeException failure) { throw new IOException("Invalid clipboard hex", failure); }
    }

    private static void at(ObjectNode node, Coords at) { node.putArray("at").add(at.getX() + 1).add(at.getY() + 1); }
    private static void put(ObjectNode node, String key, String value) { if (value != null) { node.put(key, value); } }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalArgumentException(message); } }
    private static void only(JsonNode node, String... names) {
        require(node != null && node.isObject(), "Expected an object");
        Set<String> allowed = Set.of(names);
        node.fieldNames().forEachRemaining(name -> require(allowed.contains(name), "Unknown field: " + name));
    }
    private static String string(JsonNode node, String key, String fallback) {
        if (!node.has(key)) { return fallback; }
        require(node.get(key).isTextual(), key + " must be a string"); return node.get(key).textValue();
    }
    private static int integer(JsonNode node, String key, int fallback) {
        if (!node.has(key)) { return fallback; }
        return intValue(node.get(key));
    }
    private static int intValue(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "Expected an integer"); return value.intValue();
    }
    private static double finite(JsonNode value) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()), "Expected a finite number"); return value.doubleValue();
    }
    private static double number(JsonNode node, String key, double fallback) { return node.has(key) ? finite(node.get(key)) : fallback; }
    private static boolean bool(JsonNode node, String key, boolean fallback) {
        if (!node.has(key)) { return fallback; }
        require(node.get(key).isBoolean(), key + " must be boolean"); return node.get(key).booleanValue();
    }
    private static Coords coordinate(JsonNode value) {
        require(value != null && value.isArray() && value.size() == 2, "Expected [column,row]");
        int x = intValue(value.get(0)), y = intValue(value.get(1));
        require(x != Integer.MIN_VALUE && y != Integer.MIN_VALUE, "Coordinate overflow");
        return new Coords(x - 1, y - 1);
    }
}
