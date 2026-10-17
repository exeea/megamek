/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Content shared by the editor and artwork resolver. Loading this index never loads a model or texture. It also holds
 * the Assets palette: categories of sections of cards, in data order. An entry (preset, variant or asset) is on the
 * palette when it names a section; entries sharing a {@code family} are one card.
 */
public final class BoardEditorBlueprint {
    public record Choice(int value, String label, String material) { }
    /** Height fields store levels above the owning hex and can be dragged in the editor's side section. */
    public record Field(String terrain, String label, boolean edges, boolean optional, List<Choice> choices,
          double min, double max, double step, boolean brush, boolean height) { }
    public record Component(String id, String label, List<String> present, String defaults, List<Field> fields,
          String category, String receiver, String preview, int previewLevel, boolean palette, boolean replaceExisting, List<Preset> presets) {
        public boolean isPresent(java.util.function.Predicate<String> hasTerrain) {
            return present.isEmpty() || present.stream().anyMatch(hasTerrain);
        }
    }
    /**
     * A complete paintable terrain choice; optional fields no longer require painting an empty component first.
     * {@code settings} are optional fields the Brush offers although the preset leaves them out; {@code hexes} make it a
     * multi-hex blueprint: by direction (0-5 = N, NE, SE, S, SW, NW) the terrain of that neighbour of the painted hex.
     */
    public record Preset(String id, String label, String terrain, List<String> sections, List<String> settings,
          Map<Integer, String> hexes) { }
    /** {@code pieces}: a model per building weight and exits ({@link #model}) for a look that names no single asset. */
    public record Variant(String id, String label, String owner, String selectors, String asset, String thumbnail,
          String material, boolean blend, List<String> sections, String family, String pieces) {
        /** The model this design shows on {@code hex}: its asset, else its pieces for the hex's building weight and exits ("" if none). */
        public String model(Hex hex) {
            Terrain building = hex.getTerrain(Terrains.BUILDING);
            if (!asset.isBlank() || pieces.isBlank() || building == null || building.getLevel() < 1 || building.getLevel() > WEIGHTS.size()) {
                return asset;
            }
            return pieces.replace("{weight}", WEIGHTS.get(building.getLevel() - 1))
                  .replace("{nn}", String.format(Locale.ROOT, "%02d", building.getExits()));
        }
    }
    /** Local X/Y use hex width/height; Z uses board levels. Heading is the connector's outward XY direction. */
    public record Connector(double x, double y, double z, double heading) { }
    public record Snap(String set, List<Connector> connectors, double radius, double angleTolerance) { }
    /**
     * A palette object. {@code layout}, when not null, makes it a stamp: painting places that scenery layout's
     * objects as one new group, and no object of this id is ever created. Otherwise painting places {@code model} (the
     * id, unless the entry names another model) in {@code colours}: a pond is the pool of its outline in pond colours.
     * A decal's card shows its image by path convention ({@link BoardDecalArt#image}); {@code thumbnail} is a stamp's.
     */
    public record Asset(String id, String label, String kind, String thumbnail, Snap snap, String layout,
          String model, BoardDecoration.Colours colours, List<String> sections, String family) { }
    /** One palette entry; {@code key} is the session's brush key: "owner/presetId", a variant id or an asset id. */
    public record Entry(String key, String owner, String label, Preset preset, Variant variant, Asset asset) {
        public List<String> sections() {
            return preset != null ? preset.sections() : variant != null ? variant.sections() : asset.sections();
        }

        /** The family label, or null. */
        public String family() { return variant != null ? variant.family() : asset != null ? asset.family() : null; }

        /** A composition: a stamp layout or a multi-hex preset (the BLUEPRINT chip). */
        public boolean blueprint() { return asset != null && asset.layout() != null || preset != null && !preset.hexes().isEmpty(); }
    }
    /** A card: one entry, a picker family (assets) or a gallery (variants), labelled with the family. */
    public record Card(String label, List<Entry> members) {
        public boolean family() { return members.size() > 1; }
        public boolean gallery() { return family() && members.getFirst().variant() != null; }
        /** A picker family: assets on one card, which a Type row swaps. */
        public boolean picker() { return family() && !gallery(); }
    }
    public record Section(String category, String id, List<Card> cards) {
        public String path() { return category + "/" + id; }
    }
    /** {@code themes}: null except for the Theme category (its special themes); {@code iconNode}: one hex in the .board2 form. */
    public record Category(String id, List<String> themes, List<Section> sections, String iconNode) {
        /** A fresh hex each call. */
        public Hex icon() {
            try { return BoardFile.fromClipboard(HEX + iconNode); }
            catch (IOException failure) { throw new IllegalStateException("Unreadable icon of " + id, failure); }
        }
    }

    private static final String HEX = "MegaMek Hex2\n";
    private static final List<String> DIRECTIONS = List.of("N", "NE", "SE", "S", "SW", "NW");
    /** Building levels 1-4 name their pieces' weight. */
    private static final List<String> WEIGHTS = List.of("light", "medium", "heavy", "hard");

    private final List<Component> components;
    private final Map<String, Variant> variants;
    /**
     * The variants with one selector, by its terrain type: the designs a legacy token can stand for. An asset design
     * (a building model) is not among them: its selector is the art its model's tileset image draws beside that model,
     * which only the model decides ({@link BoardSceneryLayouts}'s conversion); the 2D board of a {@code .board2} still
     * draws it through the selector ({@link #artwork(Hex)}).
     */
    private final Map<Integer, List<Variant>> selecting = new HashMap<>();
    private final Map<String, Asset> assets;
    private final List<Category> categories;
    private final Map<String, Entry> entries;
    private final Map<String, Card> cards;

    private static BoardEditorBlueprint shared;

    public static synchronized BoardEditorBlueprint get() {
        if (shared == null) { shared = loadDefault(); }
        return shared;
    }

    private static BoardEditorBlueprint loadDefault() {
        try {
            return read(Configuration.dataDir().toPath().resolve("board-editor/blueprint.json"));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the board editor blueprint", failure);
        }
    }

    private BoardEditorBlueprint(List<Component> components, Map<String, Variant> variants, Map<String, Asset> assets,
          List<Category> categories, Map<String, Entry> entries, Map<String, Card> cards) {
        this.components = List.copyOf(components);
        this.variants = Collections.unmodifiableMap(variants);
        this.assets = Collections.unmodifiableMap(assets);
        this.categories = List.copyOf(categories);
        this.entries = Collections.unmodifiableMap(entries);
        this.cards = Map.copyOf(cards);
        for (Variant variant : variants.values()) {
            if (!variant.selectors().isBlank() && !variant.selectors().contains(";") && variant.asset().isBlank()) {
                selecting.computeIfAbsent(new Terrain(variant.selectors()).getType(), type -> new ArrayList<>()).add(variant);
            }
        }
    }

    public static BoardEditorBlueprint read(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (!"megamek-editor-blueprint".equals(root.path("format").asText()) || root.path("version").asInt() != 1) {
            throw new IOException("Unsupported board editor blueprint: " + file);
        }
        // The palette's sections by path, in declared order; each collects its cards below.
        Map<String, List<Card>> sections = new LinkedHashMap<>();
        for (JsonNode category : root.withArray("categories")) {
            String id = required(category, "id");
            for (JsonNode section : category.withArray("sections")) {
                if (sections.putIfAbsent(id + "/" + section.asText(), new ArrayList<>()) != null) {
                    throw new IOException("Duplicate palette section " + id + "/" + section.asText());
                }
            }
            try { BoardFile.fromClipboard(HEX + category.path("icon")); }
            catch (IOException failure) { throw new IOException("Unreadable icon of " + id, failure); }
        }
        List<Component> components = new ArrayList<>();
        Map<String, Variant> variants = new LinkedHashMap<>();
        Map<String, Asset> assets = new LinkedHashMap<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (JsonNode entry : root.withArray("components")) {
            List<Field> fields = new ArrayList<>();
            for (JsonNode field : entry.withArray("fields")) {
                List<Choice> choices = new ArrayList<>();
                for (JsonNode choice : field.withArray("choices")) {
                    choices.add(new Choice(choice.path("value").asInt(), required(choice, "label"), choice.path("material").asText()));
                }
                String terrain = required(field, "terrain");
                if (Terrains.getType(terrain) <= 0) { throw new IOException("Unknown terrain " + terrain); }
                double min = field.path("min").asDouble(0), max = field.path("max").asDouble(10), step = field.path("step").asDouble(1);
                if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || min >= max || step <= 0) {
                    throw new IOException("Invalid slider range for " + terrain);
                }
                fields.add(new Field(terrain, required(field, "label"), field.path("edges").asBoolean(),
                      field.path("optional").asBoolean(), List.copyOf(choices), min, max, step, field.path("brush").asBoolean(),
                      field.path("height").asBoolean()));
            }
            List<String> present = strings(entry.path("present"));
            for (String terrain : present) {
                if (Terrains.getType(terrain) <= 0) { throw new IOException("Unknown component terrain " + terrain); }
            }
            String id = required(entry, "id");
            if (components.stream().anyMatch(c -> c.id().equals(id))) { throw new IOException("Duplicate component"); }
            List<Preset> presets = new ArrayList<>();
            for (JsonNode node : entry.withArray("presets")) {
                Map<Integer, String> hexes = new TreeMap<>();
                for (var neighbour : node.path("hexes").properties()) {
                    int direction = DIRECTIONS.indexOf(neighbour.getKey());
                    if (direction < 0) { throw new IOException("Unknown direction " + neighbour.getKey() + " of " + node.path("id")); }
                    hexes.put(direction, terrain(node, neighbour.getValue().asText()));
                }
                List<String> settings = strings(node.path("settings"));
                for (String setting : settings) {
                    if (fields.stream().noneMatch(field -> field.terrain().equals(setting))) {
                        throw new IOException("Setting " + setting + " of " + node.path("id") + " is no field of " + id);
                    }
                }
                Preset preset = new Preset(required(node, "id"), required(node, "label"), terrain(node, node.path("terrain").asText()),
                      sections(node, sections), settings, Collections.unmodifiableMap(hexes));
                presets.add(preset);
                add(entries, new Entry(id + "/" + preset.id(), id, preset.label(), preset, null, null));
            }
            components.add(new Component(id, required(entry, "label"), present,
                  entry.path("defaults").asText(), List.copyOf(fields), entry.path("category").asText("Terrain"),
                  entry.path("receiver").asText(id),
                  entry.path("preview").path("terrain").asText(entry.path("defaults").asText()),
                  entry.path("preview").path("level").asInt(0), entry.path("palette").asBoolean(true),
                  entry.path("replaceExisting").asBoolean(true), List.copyOf(presets)));
        }
        for (JsonNode entry : root.withArray("variants")) {
            Variant variant = new Variant(required(entry, "id"), required(entry, "label"), required(entry, "owner"),
                  entry.path("selectors").asText(), entry.path("asset").asText(), entry.path("thumbnail").asText(),
                  entry.path("material").asText(), entry.path("blend").asBoolean(), sections(entry, sections),
                  entry.path("family").asText(null), entry.path("pieces").asText());
            if (!HexAppearance.OWNERS.contains(variant.owner()) || variants.putIfAbsent(variant.id(), variant) != null) {
                throw new IOException("Invalid or duplicate variant " + variant.id());
            }
            add(entries, new Entry(variant.id(), variant.owner(), variant.label(), null, variant, null));
        }
        for (JsonNode entry : root.withArray("assets")) {
            Asset asset;
            // Reuse the document's key, transform and colour validation; the catalog cannot escape the asset root.
            try {
                List<String> colours = new ArrayList<>();
                for (JsonNode colour : entry.withArray("colours")) { colours.add(colour.isNull() ? null : colour.asText()); }
                asset = new Asset(required(entry, "id"), required(entry, "label"), required(entry, "kind"),
                      entry.path("thumbnail").asText(), snap(entry),
                      entry.hasNonNull("layout") ? entry.get("layout").asText() : null,
                      entry.path("model").asText(entry.path("id").asText()), new BoardDecoration.Colours(colours),
                      sections(entry, sections), entry.path("family").asText(null));
                new BoardDecoration("check", asset.kind(), asset.id(), null, 0, 0, 0, false, 1,
                      BoardDecoration.Placement.ground(), 0).withColours(asset.colours());
                new BoardDecoration("check", asset.kind(), asset.model(), null, 0, 0, 0, false, 1,
                      BoardDecoration.Placement.ground(), 0);
            } catch (IllegalArgumentException failure) { throw new IOException("Invalid asset " + entry.path("id"), failure); }
            if (assets.putIfAbsent(asset.id(), asset) != null) { throw new IOException("Duplicate asset " + asset.id()); }
            add(entries, new Entry(asset.id(), "", asset.label(), null, null, asset));
        }
        // Cards in data order (presets, variants, assets); a family is one card where its first member stands.
        Map<String, List<Entry>> families = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            if (entry.family() != null) { families.computeIfAbsent(entry.family(), family -> new ArrayList<>()).add(entry); }
        }
        for (var family : families.entrySet()) {
            Entry first = family.getValue().getFirst();
            if (family.getValue().size() < 2 || family.getValue().stream().anyMatch(member -> (member.variant() == null) != (first.variant() == null)
                  || !member.owner().equals(first.owner()) || !member.sections().equals(first.sections()) || member.blueprint() != first.blueprint()
                  || member.asset() != null && !member.asset().kind().equals(first.asset().kind()))) {
                throw new IOException("Family " + family.getKey() + " needs two or more members of one kind, owner and sections");
            }
        }
        Map<String, Card> cards = new HashMap<>();
        for (Entry entry : entries.values()) {
            if (cards.containsKey(entry.key())) { continue; }
            List<Entry> members = entry.family() == null ? List.of(entry) : List.copyOf(families.get(entry.family()));
            Card card = new Card(entry.family() == null ? entry.label() : entry.family(), members);
            members.forEach(member -> cards.put(member.key(), card));
            entry.sections().forEach(path -> sections.get(path).add(card));
        }
        List<Category> categories = new ArrayList<>();
        for (JsonNode category : root.withArray("categories")) {
            String id = category.path("id").asText();
            List<Section> list = new ArrayList<>();
            for (JsonNode section : category.withArray("sections")) {
                list.add(new Section(id, section.asText(), List.copyOf(sections.get(id + "/" + section.asText()))));
            }
            categories.add(new Category(id, category.has("themes") ? strings(category.get("themes")) : null, List.copyOf(list),
                  category.path("icon").toString()));
        }
        return new BoardEditorBlueprint(components, variants, assets, categories, entries, cards);
    }

    private static String required(JsonNode node, String name) throws IOException {
        String value = node.path(name).asText();
        if (value.isBlank()) { throw new IOException("Blueprint requires " + name); }
        return value;
    }

    /** A string or a list of strings; none when absent or empty. */
    private static List<String> strings(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value.isArray()) { value.forEach(item -> result.add(item.asText())); }
        else if (!value.asText().isEmpty()) { result.add(value.asText()); }
        return List.copyOf(result);
    }

    /** The entry's palette sections ({@code section}: one path or a list); each must be declared. */
    private static List<String> sections(JsonNode entry, Map<String, ?> declared) throws IOException {
        List<String> paths = strings(entry.path("section"));
        for (String path : paths) {
            if (!declared.containsKey(path)) { throw new IOException("Unknown palette section " + path + " of " + entry.path("id")); }
        }
        return paths;
    }

    private static String terrain(JsonNode preset, String terrain) throws IOException {
        List<String> errors = new ArrayList<>();
        if (!new Hex(0, terrain, "").isValid(errors)) { throw new IOException("Invalid brush " + preset.path("id") + ": " + errors); }
        return terrain;
    }

    /** Only palette entries (with a section) are entries; their keys are unique. */
    private static void add(Map<String, Entry> entries, Entry entry) throws IOException {
        if (!entry.sections().isEmpty() && entries.putIfAbsent(entry.key(), entry) != null) {
            throw new IOException("Duplicate palette entry " + entry.key());
        }
    }

    private static Snap snap(JsonNode asset) throws IOException {
        if (!asset.has("snap")) { return null; }
        JsonNode definition = asset.path("snap");
        double radius = definition.path("radius").asDouble(.1), angle = definition.path("angleTolerance").asDouble(2);
        if (!Double.isFinite(radius) || radius <= 0 || radius > .5 || !Double.isFinite(angle) || angle < 0 || angle > 15) {
            throw new IOException("Invalid snapping tolerance for " + asset.path("id"));
        }
        List<Connector> connectors = new ArrayList<>();
        for (JsonNode point : definition.withArray("connectors")) {
            if (!point.isArray() || point.size() != 4) { throw new IOException("A connector needs [x, y, z, heading]"); }
            for (JsonNode number : point) {
                if (!number.isNumber() || !Double.isFinite(number.asDouble())) { throw new IOException("A connector needs finite coordinates"); }
            }
            connectors.add(new Connector(point.get(0).asDouble(), point.get(1).asDouble(), point.get(2).asDouble(), point.get(3).asDouble()));
        }
        if (connectors.isEmpty()) { throw new IOException("Snapping needs at least one connector"); }
        return new Snap(required(definition, "set"), List.copyOf(connectors), radius, angle);
    }

    public List<Component> components() { return components; }
    public List<Variant> variants(String owner) { return variants.values().stream().filter(v -> v.owner().equals(owner)).toList(); }
    public Variant variant(String id) { return variants.get(id); }
    public List<Asset> assets() { return List.copyOf(assets.values()); }
    public Asset asset(String id) { return assets.get(id); }
    public Component component(String id) {
        return components.stream().filter(c -> c.id().equals(id)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown component " + id));
    }

    /** The palette's categories in start-page order. */
    public List<Category> categories() { return categories; }

    /** The palette entry of a brush key; null for a key that is no palette entry ("hex/sample", an automatic piece). */
    public Entry entry(String key) { return entries.get(key); }

    /** The card that shows {@code entry} (the same card in each of its sections). */
    public Card card(Entry entry) { return cards.get(entry.key()); }

    /** The Theme category (the one with {@code themes}), or null. */
    public Category themeCategory() { return categories.stream().filter(c -> c.themes() != null).findFirst().orElse(null); }

    /** The Theme category's themes: the special ones the tilesets do not list. */
    public List<String> specialThemes() { return themeCategory() == null ? List.of() : themeCategory().themes(); }

    /** The category of a section path ("category/section"). */
    public static String categoryOf(String section) { return section.substring(0, section.indexOf('/')); }

    /** The palette asset a placed object came from: the alias whose model and label match (a pond), else its own asset. */
    public Asset assetOf(BoardDecoration object) {
        return assets.values().stream().filter(a -> !a.model().equals(a.id()) && a.model().equals(object.asset())
              && a.label().equals(object.name())).findFirst().orElseGet(() -> assets.get(object.asset()));
    }

    /**
     * The design that stands for a legacy art token: the variant whose one selector is {@code token}'s type and level
     * (and its exits, where the selector names them), owned by a component present on {@code hex}; a blend variant
     * only where the token's exits are a strength of one to five sixths ({@link #artwork(Hex, String)} adds them back).
     * Null when no variant reproduces it.
     */
    public HexAppearance reproducing(Hex hex, Terrain token) {
        for (Variant variant : selecting.getOrDefault(token.getType(), List.of())) {
            Terrain selector = new Terrain(variant.selectors());
            int exits = token.hasExitsSpecified() ? token.getExits() : 0;
            if (selector.getLevel() != token.getLevel()
                  || selector.hasExitsSpecified() && (!token.hasExitsSpecified() || selector.getExits() != exits)
                  || variant.blend() && (!token.hasExitsSpecified() || exits < 1 || exits > 5)
                  || !component(variant.owner()).isPresent(name -> hex.containsTerrain(Terrains.getType(name)))) {
                continue;
            }
            return new HexAppearance(variant.id(), null, null, variant.blend() ? exits / 6.0 : null);
        }
        return null;
    }

    /** {@code source} with every owner's design selectors ({@link #artwork(Hex, String)}): what its tokens would draw. */
    public Hex artwork(Hex source) {
        Hex result = source;
        for (String owner : source.getAppearance().keySet()) { result = artwork(result, owner); }
        return result;
    }

    /** Temporary, owner-scoped artwork query. Gameplay terrain and other owners' selectors are never rewritten. */
    public Hex artwork(Hex source, String owner) {
        HexAppearance appearance = source.getAppearance().get(owner);
        Variant variant = appearance == null ? null : variants.get(appearance.variant());
        if (variant == null || !variant.owner().equals(owner) || variant.selectors().isBlank()) { return source; }
        Hex result = source.duplicate();
        for (String selector : variant.selectors().split(";")) { result.addTerrain(new Terrain(selector)); }
        if (variant.blend()) {
            Terrain transition = result.getTerrain(Terrains.GROUND_FLUFF);
            if (transition != null) {
                result.addTerrain(new Terrain(Terrains.GROUND_FLUFF, transition.getLevel(), true,
                      appearance.strength() == null ? 3 : (int) Math.round(appearance.strength() * 6)));
            }
        }
        return result;
    }
}
