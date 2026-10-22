/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The suite's design tokens, read from {@code design-tokens.json}: the one source of truth that the Swing and libGDX
 * themes are built from. Read-only; colors are packed 0xAARRGGBB. No AWT, so the native toolkit can use it too.
 */
public final class DesignTokens {
    /** The classpath location of the token file. */
    public static final String RESOURCE = "/megamek/client/ui/design/design-tokens.json";

    private static volatile DesignTokens shipped;

    /** The two color modes. Native battle views are dark only for now. */
    public enum Mode {
        DARK,
        LIGHT
    }

    /** A color role with a value per mode; {@code overlay} is the translucent board-overlay value, or null. */
    public record ColorToken(String name, int dark, int light, Integer overlay, String use) {
        public int value(Mode mode) {
            return mode == Mode.DARK ? dark : light;
        }
    }

    /** A text style: size in logical pixels, CSS weight, upper case or sentence case, tracking in em. */
    public record TypeStyle(String name, float size, int weight, boolean upperCase, float tracking, String use) {
    }

    /** Every listed foreground must reach {@code min} contrast on every listed background, in both modes. */
    public record ContrastCheck(List<String> foregrounds, List<String> backgrounds, String over, double min,
          String why) {
    }

    /** Every pair of the listed colors must differ by at least {@code minDeltaE} for every {@link Vision}. */
    public record DistinctCheck(List<String> colors, double minDeltaE, String why) {
    }

    private final String version;
    private final Map<String, ColorToken> colors;
    private final String family;
    private final String iconFamily;
    private final String caseRule;
    private final Map<String, TypeStyle> typeStyles;
    private final Map<String, Float> space;
    private final Map<String, Float> radius;
    private final Map<String, Float> size;
    private final Map<String, Float> opacity;
    private final Map<String, Float> motion;
    private final List<ContrastCheck> contrastChecks;
    private final List<DistinctCheck> distinctChecks;

    private DesignTokens(JsonNode root) {
        version = root.path("version").asText();
        Map<String, ColorToken> colorMap = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(root.path("color"))) {
            JsonNode node = entry.getValue();
            String name = entry.getKey();
            JsonNode overlay = node.get("overlay");
            colorMap.put(name, new ColorToken(name, ColorMath.parse(required(node, "dark", name)),
                  ColorMath.parse(required(node, "light", name)),
                  overlay == null ? null : ColorMath.parse(overlay.asText()), node.path("use").asText()));
        }
        colors = Collections.unmodifiableMap(colorMap);

        JsonNode typography = root.path("typography");
        family = typography.path("family").asText();
        iconFamily = typography.path("iconFamily").asText();
        caseRule = typography.path("caseRule").asText();
        Map<String, TypeStyle> styles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(typography.path("styles"))) {
            JsonNode node = entry.getValue();
            styles.put(entry.getKey(), new TypeStyle(entry.getKey(), (float) node.path("size").asDouble(),
                  node.path("weight").asInt(400), "upper".equals(node.path("case").asText()),
                  (float) node.path("tracking").asDouble(), node.path("use").asText()));
        }
        typeStyles = Collections.unmodifiableMap(styles);

        space = numbers(root.path("space"));
        radius = numbers(root.path("radius"));
        size = numbers(root.path("size"));
        opacity = numbers(root.path("opacity"));
        motion = numbers(root.path("motion"));

        List<ContrastCheck> contrast = new ArrayList<>();
        for (JsonNode node : root.path("checks").path("contrast")) {
            contrast.add(new ContrastCheck(strings(node.path("fg")), strings(node.path("bg")),
                  node.hasNonNull("over") ? node.get("over").asText() : null, node.path("min").asDouble(),
                  node.path("why").asText()));
        }
        contrastChecks = List.copyOf(contrast);
        List<DistinctCheck> distinct = new ArrayList<>();
        for (JsonNode node : root.path("checks").path("distinct")) {
            distinct.add(new DistinctCheck(strings(node.path("colors")), node.path("minDeltaE").asDouble(),
                  node.path("why").asText()));
        }
        distinctChecks = List.copyOf(distinct);
    }

    /** The tokens shipped with the application, read once. */
    public static DesignTokens shipped() {
        DesignTokens tokens = shipped;
        if (tokens == null) {
            synchronized (DesignTokens.class) {
                tokens = shipped;
                if (tokens == null) {
                    try (InputStream stream = DesignTokens.class.getResourceAsStream(RESOURCE)) {
                        if (stream == null) {
                            throw new IllegalStateException("Missing design token resource " + RESOURCE);
                        }
                        tokens = read(stream);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                    shipped = tokens;
                }
            }
        }
        return tokens;
    }

    /** Reads a token file. */
    public static DesignTokens read(InputStream stream) throws IOException {
        return new DesignTokens(new ObjectMapper().readTree(stream));
    }

    public String version() {
        return version;
    }

    /** Every color role in file order. */
    public Map<String, ColorToken> colors() {
        return colors;
    }

    public ColorToken colorToken(String name) {
        ColorToken token = colors.get(name);
        if (token == null) {
            throw new IllegalArgumentException("Unknown color token " + name);
        }
        return token;
    }

    /** The color of a role in a mode, 0xAARRGGBB. */
    public int color(String name, Mode mode) {
        return colorToken(name).value(mode);
    }

    public String family() {
        return family;
    }

    public String iconFamily() {
        return iconFamily;
    }

    public String caseRule() {
        return caseRule;
    }

    /** Every text style in file order. */
    public Map<String, TypeStyle> typeStyles() {
        return typeStyles;
    }

    public TypeStyle typeStyle(String name) {
        TypeStyle style = typeStyles.get(name);
        if (style == null) {
            throw new IllegalArgumentException("Unknown text style " + name);
        }
        return style;
    }

    public Map<String, Float> space() {
        return space;
    }

    public Map<String, Float> radius() {
        return radius;
    }

    public Map<String, Float> size() {
        return size;
    }

    /** Alphas from 0 to 1, such as the tint of a status color laid over a hovered or pressed face. */
    public Map<String, Float> opacity() {
        return opacity;
    }

    /** The alpha {@code opacity.name} as a 0-255 channel. */
    public int alpha(String name) {
        Float value = opacity.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown opacity " + name);
        }
        return Math.round(value * 255);
    }

    /** Durations in milliseconds. */
    public Map<String, Float> motion() {
        return motion;
    }

    public List<ContrastCheck> contrastChecks() {
        return contrastChecks;
    }

    public List<DistinctCheck> distinctChecks() {
        return distinctChecks;
    }

    private static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        return node.properties();
    }

    private static String required(JsonNode node, String field, String token) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Color token " + token + " has no " + field + " value");
        }
        return value.asText();
    }

    private static Map<String, Float> numbers(JsonNode node) {
        Map<String, Float> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(node)) {
            if (entry.getValue().isNumber()) {
                values.put(entry.getKey(), (float) entry.getValue().asDouble());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value.asText());
        }
        return List.copyOf(values);
    }
}
