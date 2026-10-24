/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * A screen's text case mode (docs/design-system/tokens.md, Text case): app screens upper-case only overline tags,
     * game screens also the short framing labels. The mode belongs to a screen, not to an app.
     */
    public enum TextMode {
        APP,
        GAME
    }

    /** A color role with a value per mode; {@code overlay} is the translucent board-overlay value, or null. */
    public record ColorToken(String name, int dark, int light, Integer overlay, String use) {
        public int value(Mode mode) {
            return mode == Mode.DARK ? dark : light;
        }
    }

    /**
     * A text style: size in logical pixels, CSS weight, tracking in em for sentence case, and {@code capsTracking} in
     * em for upper case, or null for a style that is never upper case.
     */
    public record TypeStyle(String name, float size, int weight, float tracking, Float capsTracking, String use) {
        /** Whether a text role may show this style in upper case. */
        public boolean hasCaps() {
            return capsTracking != null;
        }

        /** The tracking for the given case. */
        public float tracking(boolean upperCase) {
            return upperCase ? capsTracking : tracking;
        }
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
    private final Map<String, String> textRoles;
    private final Map<TextMode, Set<String>> upperRoles;
    private final Map<String, Float> space;
    private final Map<String, Float> radius;
    private final Map<String, Float> size;
    private final Map<String, Float> opacity;
    private final Map<String, Float> motion;
    private final float[] easing;
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
            JsonNode caps = node.get("capsTracking");
            styles.put(entry.getKey(), new TypeStyle(entry.getKey(), (float) node.path("size").asDouble(),
                  node.path("weight").asInt(400), (float) node.path("tracking").asDouble(),
                  caps == null ? null : (float) caps.asDouble(), node.path("use").asText()));
        }
        typeStyles = Collections.unmodifiableMap(styles);

        JsonNode textCase = typography.path("case");
        Map<String, String> roles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(textCase.path("roles"))) {
            String style = entry.getValue().asText();
            if (!styles.containsKey(style)) {
                throw new IllegalArgumentException("Text role " + entry.getKey() + " names no text style " + style);
            }
            roles.put(entry.getKey(), style);
        }
        textRoles = Collections.unmodifiableMap(roles);
        Map<TextMode, Set<String>> upper = new EnumMap<>(TextMode.class);
        for (TextMode textMode : TextMode.values()) {
            Set<String> upperInMode = new LinkedHashSet<>();
            for (JsonNode role : textCase.path("upper").path(textMode.name().toLowerCase(Locale.ROOT))) {
                String name = role.asText();
                String style = roles.get(name);
                if (style == null) {
                    throw new IllegalArgumentException("Upper-case role " + name + " is not a text role");
                }
                if (!styles.get(style).hasCaps()) {
                    throw new IllegalArgumentException("Text style " + style + " of upper-case role " + name
                          + " has no capsTracking");
                }
                upperInMode.add(name);
            }
            upper.put(textMode, Collections.unmodifiableSet(upperInMode));
        }
        upperRoles = Collections.unmodifiableMap(upper);

        space = numbers(root.path("space"));
        radius = numbers(root.path("radius"));
        size = numbers(root.path("size"));
        opacity = numbers(root.path("opacity"));
        motion = numbers(root.path("motion"));
        JsonNode curve = root.path("motion").path("easing");
        easing = new float[] { 0, 0, 1, 1 };
        if (curve.isArray() && curve.size() == 4) {
            for (int i = 0; i < 4; i++) {
                easing[i] = (float) curve.get(i).asDouble();
            }
        }

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

    /** Every text role (button, tab, title...) and the text style it uses, in file order. */
    public Map<String, String> textRoles() {
        return textRoles;
    }

    /** The text style of {@code role}. */
    public TypeStyle roleStyle(String role) {
        String style = textRoles.get(role);
        if (style == null) {
            throw new IllegalArgumentException("Unknown text role " + role);
        }
        return typeStyles.get(style);
    }

    /** Whether text of {@code role} is upper case on a screen in {@code textMode}. */
    public boolean upperCase(String role, TextMode textMode) {
        roleStyle(role);
        return upperRoles.get(textMode).contains(role);
    }

    /**
     * {@code text} as a {@code role} shows it in {@code textMode}: upper-cased by the rules of {@code locale}, or as
     * is. Strings stay sentence case in their bundles; only the drawn text changes. HTML text is never changed.
     */
    public String cased(String role, TextMode textMode, String text, Locale locale) {
        if (text == null || !upperCase(role, textMode) || text.regionMatches(true, 0, "<html>", 0, 6)) {
            return text;
        }
        return text.toUpperCase(locale);
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

    /** The easing curve's control points x1, y1, x2, y2, as in CSS cubic-bezier(). */
    public float[] easing() {
        return easing.clone();
    }

    /** A duration in milliseconds, such as {@code fast}. */
    public int duration(String name) {
        Float value = motion.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown motion " + name);
        }
        return Math.round(value);
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
