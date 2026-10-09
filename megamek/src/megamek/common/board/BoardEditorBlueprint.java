/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Content shared by the editor and artwork resolver. Loading this index never loads a model or texture. */
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
    /** A complete paintable terrain choice; optional fields no longer require painting an empty component first. */
    public record Preset(String id, String label, String terrain) { }
    public record Variant(String id, String label, String owner, String selectors, String asset, String thumbnail,
          String material, boolean blend, String group) { }
    /** Local X/Y use hex width/height; Z uses board levels. Heading is the connector's outward XY direction. */
    public record Connector(double x, double y, double z, double heading) { }
    public record Snap(String set, List<Connector> connectors, double radius, double angleTolerance) { }
    /**
     * A palette object. {@code layout}, when not null, makes it a stamp: painting places that scenery layout's
     * objects as one new group, and no object of this id is ever created. Otherwise painting places {@code model} (the
     * id, unless the entry names another model) in {@code colours}: a pond is the pool of its outline in pond colours.
     * A decal's card shows its image by path convention ({@link BoardDecalArt#image}); {@code thumbnail} is a stamp's.
     */
    public record Asset(String id, String label, String kind, String thumbnail, String group,
          Snap snap, boolean palette, String layout, String model, BoardDecoration.Colours colours) { }

    private final List<Component> components;
    private final Map<String, Variant> variants;
    private final Map<String, Asset> assets;

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

    private BoardEditorBlueprint(List<Component> components, Map<String, Variant> variants, Map<String, Asset> assets) {
        this.components = List.copyOf(components);
        this.variants = java.util.Collections.unmodifiableMap(variants);
        this.assets = java.util.Collections.unmodifiableMap(assets);
    }

    public static BoardEditorBlueprint read(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (!"megamek-editor-blueprint".equals(root.path("format").asText()) || root.path("version").asInt() != 1) {
            throw new IOException("Unsupported board editor blueprint: " + file);
        }
        List<Component> components = new ArrayList<>();
        Map<String, Variant> variants = new LinkedHashMap<>();
        Map<String, Asset> assets = new LinkedHashMap<>();
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
            List<String> present = new ArrayList<>();
            JsonNode condition = entry.path("present");
            if (condition.isArray()) { condition.forEach(value -> present.add(value.asText())); }
            else if (!condition.asText().isEmpty()) { present.add(condition.asText()); }
            for (String terrain : present) {
                if (Terrains.getType(terrain) <= 0) { throw new IOException("Unknown component terrain " + terrain); }
            }
            if (components.stream().anyMatch(c -> c.id().equals(entry.path("id").asText()))) { throw new IOException("Duplicate component"); }
            List<Preset> presets = new ArrayList<>();
            for (JsonNode preset : entry.withArray("presets")) {
                String terrain = preset.path("terrain").asText();
                Hex sample = new Hex(0, terrain, "");
                List<String> errors = new ArrayList<>();
                if (!sample.isValid(errors)) { throw new IOException("Invalid brush " + preset.path("id") + ": " + errors); }
                presets.add(new Preset(required(preset, "id"), required(preset, "label"), terrain));
            }
            components.add(new Component(required(entry, "id"), required(entry, "label"), List.copyOf(present),
                  entry.path("defaults").asText(), List.copyOf(fields), entry.path("category").asText("Terrain"),
                  entry.path("receiver").asText(entry.path("id").asText()),
                  entry.path("preview").path("terrain").asText(entry.path("defaults").asText()),
                  entry.path("preview").path("level").asInt(0), entry.path("palette").asBoolean(true),
                  entry.path("replaceExisting").asBoolean(true), List.copyOf(presets)));
        }
        for (JsonNode entry : root.withArray("variants")) {
            Variant variant = new Variant(required(entry, "id"), required(entry, "label"), required(entry, "owner"),
                  entry.path("selectors").asText(), entry.path("asset").asText(), entry.path("thumbnail").asText(),
                  entry.path("material").asText(), entry.path("blend").asBoolean(), entry.path("group").asText("Designs"));
            if (!HexAppearance.OWNERS.contains(variant.owner()) || variants.putIfAbsent(variant.id(), variant) != null) {
                throw new IOException("Invalid or duplicate variant " + variant.id());
            }
        }
        for (JsonNode entry : root.withArray("assets")) {
            Asset asset;
            // Reuse the document's key, transform and colour validation; the catalog cannot escape the asset root.
            try {
                List<String> colours = new ArrayList<>();
                for (JsonNode colour : entry.withArray("colours")) { colours.add(colour.isNull() ? null : colour.asText()); }
                asset = new Asset(required(entry, "id"), required(entry, "label"), required(entry, "kind"),
                      entry.path("thumbnail").asText(), entry.path("group").asText("Other"),
                      snap(entry), entry.path("palette").asBoolean(true),
                      entry.hasNonNull("layout") ? entry.get("layout").asText() : null,
                      entry.path("model").asText(entry.path("id").asText()), new BoardDecoration.Colours(colours));
                new BoardDecoration("check", asset.kind(), asset.id(), null, 0, 0, 0, false, 1,
                      BoardDecoration.Placement.ground(), 0).withColours(asset.colours());
                new BoardDecoration("check", asset.kind(), asset.model(), null, 0, 0, 0, false, 1,
                      BoardDecoration.Placement.ground(), 0);
            } catch (IllegalArgumentException failure) { throw new IOException("Invalid asset " + entry.path("id"), failure); }
            if (assets.putIfAbsent(asset.id(), asset) != null) { throw new IOException("Duplicate asset " + asset.id()); }
        }
        return new BoardEditorBlueprint(components, variants, assets);
    }

    private static String required(JsonNode node, String name) throws IOException {
        String value = node.path(name).asText();
        if (value.isBlank()) { throw new IOException("Blueprint requires " + name); }
        return value;
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
