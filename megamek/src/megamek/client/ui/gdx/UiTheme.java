/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;

/**
 * The hud-v3 design system (proto3.css) for every native view: its color tokens, the Roboto type scale, the icon sheet
 * and a skin of hud-* styles and drawables, whose textures and fonts the theme owns. A window creates one on its GL
 * thread and disposes it there, after its stage; the tokens are shared and read-only.
 */
public final class UiTheme implements Disposable {
    // hud-v3 tokens (docs/design/claude-ui-concepts/hud-v3/src/proto3.css); TEXT, ACCENT, DISABLED = ink, ink2, dim.
    public static final Color TEXT = Color.valueOf("F1F4F0");
    public static final Color ACCENT = Color.valueOf("CBD3D0");
    public static final Color MUTED = Color.valueOf("9AA6A3");
    public static final Color DISABLED = Color.valueOf("5F6B69");
    public static final Color PANEL = rgba(18, 24, 26, .92f);
    public static final Color PANEL2 = rgba(28, 36, 38, .97f);
    public static final Color POP = Color.valueOf("172022");
    public static final Color LINE = rgba(255, 255, 255, .08f);
    public static final Color RAIL = rgba(158, 170, 164, .6f);
    public static final Color CORNER = Color.valueOf("EEF5EF");
    public static final Color TICK = Color.valueOf("75817C");
    public static final Color MINT = Color.valueOf("82E2CE");
    public static final Color CORAL = Color.valueOf("EC9189");
    public static final Color AMBER = Color.valueOf("E6C991");
    public static final Color VIOLET = Color.valueOf("B9A8FF");
    public static final Color BLIP = Color.valueOf("FFAE66");
    public static final Color FILL = Color.valueOf("F0F2EE");
    public static final Color FILL_INK = Color.valueOf("16201E");
    // Secondary text on a filled surface: a pressed button's sub-label, a highlighted popover item's detail.
    public static final Color FILL_MUTED = Color.valueOf("39433F");
    public static final Color MAIN = Color.valueOf("F1F3F2");
    public static final Color QUIET = rgba(174, 187, 180, .24f);
    public static final Color HOVER = Color.valueOf("E8F1EB");
    /** A bar's track (.meter .bar). */
    public static final Color TRACK = new Color(1, 1, 1, .1f);

    public static final String ROBOTO = "Roboto/Roboto-VariableFont_wdth,wght.ttf";
    // FreeType's 1-based named instances of the shipped variable Roboto, in its fvar order.
    public static final int REGULAR = 4;
    public static final int MEDIUM = 5;
    public static final int CONDENSED_MEDIUM = 14;
    public static final int CONDENSED_SEMIBOLD = 15;
    public static final int CONDENSED_BOLD = 16;

    /** One step of the hud-v3 type scale: Roboto instance, size in stage units, CSS tracking (em), label color. */
    public record HudFont(String name, int instance, float size, float tracking, Color color) { }

    /** The single hud-v3 font table; each entry is also registered as a Label style of the same name. */
    public static final List<HudFont> HUD_FONTS = List.of(
          new HudFont("hud-body", REGULAR, 13, 0, TEXT),
          new HudFont("hud-small", REGULAR, 11.5f, 0, MUTED),
          new HudFont("hud-medium", MEDIUM, 12.5f, 0, TEXT),
          new HudFont("hud-utility", MEDIUM, 10.5f, .02f, ACCENT),
          new HudFont("hud-sub", CONDENSED_MEDIUM, 10, .04f, MUTED),
          new HudFont("hud-caption", CONDENSED_SEMIBOLD, 10.5f, .14f, MUTED),
          new HudFont("hud-mini", CONDENSED_SEMIBOLD, 11, .06f, ACCENT),
          new HudFont("hud-button", CONDENSED_SEMIBOLD, 12.5f, .08f, ACCENT),
          new HudFont("hud-name", CONDENSED_BOLD, 13.5f, .05f, TEXT),
          new HudFont("hud-title", CONDENSED_BOLD, 16, .07f, TEXT),
          new HudFont("hud-heading", CONDENSED_BOLD, 20, .04f, TEXT),
          new HudFont("hud-phase", CONDENSED_BOLD, 23, .02f, TEXT),
          new HudFont("hud-main", CONDENSED_BOLD, 17, .1f, TEXT));

    private static final String ICON_FONT = "Icons/MaterialSymbolsRounded[FILL,GRAD,opsz,wght].ttf";
    private static final int ICON_SIZE = 48;
    private static final int ICON_CELL = 64;
    /** The font icons' live area in their cell: Material Symbols keep 20 of their 24 units, texels 12 to 52. */
    private static final int ICON_LIVE = 40;
    /**
     * Material Symbols Rounded codepoints baked once as icon-name; the first thirteen keep the earlier names. Help is
     * help_center, the prototype's question mark in a rounded square; tune marks the developer tuning utility; walk is
     * the prototype's footprints; list is its bulleted list (shot 11); wireframe is the open grid of three lines each
     * way (U+F016). arrow-right, arrow-up, arrow-down and triangle-left are image uses only: Labels draw U+2190-U+2193
     * and U+25C2 as text through FALLBACK_FONTS.
     */
    public static final Map<String, Integer> ICONS = Map.ofEntries(
          Map.entry("target", 0xE1B3), Map.entry("move", 0xE569), Map.entry("group", 0xE241),
          Map.entry("orders", 0xE241), Map.entry("info", 0xE88E), Map.entry("unit", 0xE9E0),
          Map.entry("hex", 0xEB39), Map.entry("close", 0xE5CD), Map.entry("search", 0xE8B6),
          Map.entry("arrow", 0xE5CC), Map.entry("lock", 0xE897), Map.entry("checkbox-off", 0xE835),
          Map.entry("checkbox-on", 0xE834),
          Map.entry("tactical", 0xE3B4), Map.entry("map", 0xE55B), Map.entry("report", 0xE873),
          Map.entry("help", 0xF1C0), Map.entry("settings", 0xE8B8), Map.entry("menu", 0xE5D2),
          Map.entry("chat", 0xE0B7), Map.entry("grid", 0xE9B0),
          Map.entry("list", 0xE896), Map.entry("check", 0xE5CA), Map.entry("flame", 0xEF55),
          Map.entry("chevron-down", 0xE5CF), Map.entry("chevron-up", 0xE5CE), Map.entry("twist-left", 0xE419),
          Map.entry("twist-right", 0xE41A), Map.entry("more", 0xE5D3), Map.entry("undo", 0xE166),
          Map.entry("locate", 0xE55C), Map.entry("play", 0xE037), Map.entry("pause", 0xE034),
          Map.entry("skip", 0xE044), Map.entry("warn", 0xE002), Map.entry("walk", 0xF87D),
          Map.entry("chevron-right", 0xE5CC), Map.entry("expand", 0xF1CE), Map.entry("grip", 0xE945),
          Map.entry("layers", 0xE53B), Map.entry("replay", 0xE042), Map.entry("rewind", 0xE020),
          Map.entry("star", 0xE838), Map.entry("star-outline", 0xE838), Map.entry("tune", 0xE429),
          Map.entry("arrow-right", 0xE941), Map.entry("arrow-up", 0xE986), Map.entry("arrow-down", 0xE984),
          Map.entry("triangle-left", 0xE5DE), Map.entry("wireframe", 0xF016));
    /**
     * Icons the prototype draws filled. The shipped font has no filled named instance, so their outlines are closed:
     * every texel that the glyph's outline encloses becomes opaque.
     */
    private static final Set<String> FILLED_ICONS = Set.of("play", "pause", "rewind", "star");

    /** A shipped face (OFL, in fonts/) for the characters a font lacks, drawn at this share of the font's size. */
    private record Fallback(String path, float size) { }

    /**
     * The faces that supply the characters Roboto and Noto Sans lack, first match wins. Noto Sans Symbols 2 has marks
     * such as U+25C2, U+2715 and the stars U+2605 and U+2606; Bitter has the arrows U+2190-U+2193. They are drawn in
     * its Bold weight at 0.8 of the font's size: small and low like the prototype's, with shafts about as thick as
     * Roboto's hyphen, which stay visible at every sub-pixel position in every hud font at 1 pixel per unit, down to
     * the 10-unit hud-sub. Thinner shafts (Medium at 0.65) vanished at some positions in the 10-11-unit fonts.
     */
    private static final List<Fallback> FALLBACK_FONTS = List.of(
          new Fallback("Noto Symbols 2/NotoSansSymbols2-Regular.ttf", 1),
          new Fallback("Bitter/Bitter-Bold.ttf", .8f));
    /**
     * Characters no shipped face has, drawn as a lookalike that one has, instead of a blank: the midline ellipsis
     * U+22EF as the ellipsis U+2026, and the white diamond with a centred dot U+27D0 as the white diamond containing a
     * black small diamond U+25C8. Texts the views show from MegaMek use both.
     */
    private static final Map<Character, Character> LOOKALIKES = Map.of('⋯', '…', '⟐', '◈');
    // Texels of transparent white around each hud glyph's ink that its quad takes in, so the quad is at least 7 texels
    // high and wide: 1.75 units, which always covers a pixel centre, even for a hyphen drawn at 10 units from an
    // 11.5-unit face. They are the packer's margin, not libGDX's glyph padding, which is transparent black and would
    // darken the filtered ink edges.
    private static final int GLYPH_PAD = 2;
    // Texels between two hud glyphs' quads: mip level 1 (2x2-texel averages), filtered bilinearly, reaches up to 3
    // texels past a quad, so with the next glyph's own GLYPH_PAD it never meets that glyph's ink.
    private static final int GLYPH_PADDING = 2;

    /** The skin of the hud-* styles and drawables; it holds every texture and font the theme makes. */
    public final Skin skin = new Skin();
    private final File fonts;
    private final List<FreeTypeFontGenerator> generators = new ArrayList<>();
    // The hud-* fonts share glyph pages instead of allocating a 1024x1024 page each.
    private final PixmapPacker hudGlyphs = new PixmapPacker(1024, 1024, Pixmap.Format.RGBA8888,
          GLYPH_PADDING + 2 * GLYPH_PAD, false, new PixmapPacker.GuillotineStrategy());
    // The first face of the FALLBACK_FONTS chain; every font of this theme draws the characters it lacks from it.
    private final Generator fallback;

    /** Builds the theme from the shipped faces in {@code fonts} (MegaMek's fonts directory), on the GL thread. */
    public UiTheme(File fonts) {
        this.fonts = fonts;
        Pixmap white = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        white.setColor(Color.WHITE);
        white.fill();
        skin.add("white", new Texture(white));
        white.dispose();
        Generator next = null;
        for (Fallback face : FALLBACK_FONTS.reversed()) {
            next = new Generator(fontFile(fonts, face.path()), 0, face.size(), next);
            generators.add(next);
        }
        fallback = next;
        // Transparent white keeps linearly filtered glyph edges from darkening.
        hudGlyphs.setTransparentColor(new Color(1, 1, 1, 0));
        for (HudFont hud : HUD_FONTS) {
            BitmapFont face = font(hud.name(), ROBOTO, hud.instance(), hud.size(), hud.tracking(), hudGlyphs);
            skin.add(hud.name(), new Label.LabelStyle(face, hud.color()));
        }
        hudGlyphs.getPages().forEach(page -> bindGlyphPage(page.getTexture()));

        hudDrawables();
        TextButton.TextButtonStyle hud = quietButton("hud-button", 8, 12, 36);
        skin.add("hud", hud);
        skin.add("hud-mini", quietButton("hud-mini", 3, 9, 26));
        TextButton.TextButtonStyle segment = quietButton("hud-medium", 6, 4, 0);
        // A pressed segment stays filled under the pointer: proto3.css's .seg button.on outranks its hover rule.
        segment.checkedOver = segment.checked;
        segment.checkedDown = segment.checked;
        segment.checkedOverFontColor = FILL_INK;
        segment.checkedDownFontColor = FILL_INK;
        skin.add("hud-seg", segment);
        // The sections of a pill: bare, the hovered one lighter, the pressed one filled.
        TextButton.TextButtonStyle section = new TextButton.TextButtonStyle();
        section.font = skin.getFont("hud-medium");
        section.over = skin.getDrawable("pill-section-over");
        section.down = section.over;
        section.checked = skin.getDrawable("pill-section-on");
        section.checkedOver = section.checked;
        section.checkedDown = section.checked;
        section.fontColor = ACCENT;
        section.overFontColor = Color.WHITE;
        section.checkedFontColor = FILL_INK;
        section.checkedOverFontColor = FILL_INK;
        section.checkedDownFontColor = FILL_INK;
        section.disabledFontColor = DISABLED;
        skin.add("hud-pill", section);
        TextButton.TextButtonStyle hudMain = new TextButton.TextButtonStyle();
        hudMain.font = skin.getFont("hud-main");
        hudMain.up = skin.getDrawable("button-main");
        hudMain.over = skin.getDrawable("button-main-over");
        hudMain.down = hudMain.over;
        hudMain.disabled = skin.getDrawable("button-main-disabled");
        hudMain.fontColor = FILL_INK;
        hudMain.overFontColor = Color.valueOf("0D1412");
        hudMain.disabledFontColor = Color.valueOf("6F7A78");
        skin.add("hud-main", hudMain);
        // ImageTextButton lays out in a row, so a utility places an icon Image above this style's label.
        TextButton.TextButtonStyle utility = utility(hud, "hud-utility", 6, 9, 5, 62, 56);
        skin.add("hud-utility", utility);
        // At W <= 1350 a utility is at least 54 units wide, with 7 units of side padding (.b.util).
        skin.add("hud-utility-narrow", utility(hud, "hud-utility", 6, 7, 5, 54, 56));
        skin.add("bracket", utility.up, Drawable.class);
        skin.add("bracket-over", utility.over, Drawable.class);
        skin.add("bracket-checked", utility.checked, Drawable.class);
        // The row utility (.b.util.row) and the small bracket button (.b.brk.sm).
        skin.add("hud-utility-row", utility(hud, "hud-medium", 0, 14, 0, 62, 36));
        skin.add("hud-utility-small", utility(hud, "hud-mini", 4, 10, 4, 0, 28));
        // Popover items (.pop .it): filled under the pointer or the keyboard (checked); details use FILL_MUTED then.
        TextButton.TextButtonStyle hudMenuRow = new TextButton.TextButtonStyle();
        hudMenuRow.font = skin.getFont("hud-body");
        hudMenuRow.up = flat(Color.CLEAR, 8, 14);
        hudMenuRow.over = flat(FILL, 8, 14);
        hudMenuRow.down = hudMenuRow.over;
        hudMenuRow.checked = hudMenuRow.over;
        hudMenuRow.disabled = hudMenuRow.up;
        hudMenuRow.fontColor = TEXT;
        hudMenuRow.overFontColor = FILL_INK;
        hudMenuRow.downFontColor = FILL_INK;
        hudMenuRow.checkedFontColor = FILL_INK;
        hudMenuRow.disabledFontColor = DISABLED;
        skin.add("hud-menu-row", hudMenuRow);
        // The lists' thin scroll bar (.list).
        skin.add("hud-list", scrollStyle(4, 24));

        Drawable selection = skin.newDrawable("white", alpha(MINT, .3f));
        TextField.TextFieldStyle hudField = new TextField.TextFieldStyle(skin.getFont("hud-body"), TEXT,
              skin.newDrawable("white", TEXT), selection, skin.getDrawable("field"));
        hudField.focusedBackground = skin.getDrawable("field-focused");
        hudField.messageFont = hudField.font;
        hudField.messageFontColor = DISABLED;
        skin.add("hud", hudField);
        kitStyles(hud);
        // Hover tooltips: hud-small text on the panel surface, wrapped at 340 units.
        TextTooltip.TextTooltipStyle tooltip = new TextTooltip.TextTooltipStyle(
              skin.get("hud-small", Label.LabelStyle.class), skin.getDrawable("panel"));
        tooltip.wrapWidth = 340;
        skin.add("hud", tooltip);
        icons();
    }

    /** hud-v3 upper-cases titles, captions and buttons at display time, so source strings stay searchable. */
    public static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    /**
     * A generator for a file in {@code fonts}, MegaMek's fonts directory; instance > 0 selects that named instance of
     * a variable font.
     */
    public static FreeTypeFontGenerator generator(File fonts, String path, int instance) {
        return new Generator(fontFile(fonts, path), instance, 1, null);
    }

    private static FileHandle fontFile(File fonts, String path) {
        return new FileHandle(new File(fonts, path));
    }

    /** A CSS rgba() color: channels of 0 to 255 and an alpha of 0 to 1. */
    public static Color rgba(int red, int green, int blue, float alpha) {
        return new Color(red / 255f, green / 255f, blue / 255f, alpha);
    }

    /** A token's hue at another alpha, so a derived surface follows its token. */
    public static Color alpha(Color token, float alpha) {
        return new Color(token.r, token.g, token.b, alpha);
    }

    /** The tint that turns a skin drawable of the {@code base} color into {@code color} at {@code alpha}. */
    public static Color tint(Color color, Color base, float alpha) {
        return new Color(color.r / base.r, color.g / base.g, color.b / base.b, alpha);
    }

    /**
     * Registers a resource of a view that adds its own styles in this look, as the battle view's skin does. Each name
     * has one owner: a name the skin already holds for {@code type}, the theme's or the view's, throws.
     */
    public <T> T add(String name, T resource, Class<?> type) {
        if (skin.has(name, type)) {
            throw new IllegalStateException("The skin already has the " + type.getSimpleName() + " \"" + name + "\"");
        }
        skin.add(name, resource, type);
        return resource;
    }

    /** A flat fill from the skin's white texel, with padding. */
    public Drawable flat(Color color, float vertical, float horizontal) {
        Drawable drawable = skin.newDrawable("white", color);
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        return drawable;
    }

    /** The hud-v3 frames, buttons, rows, fields, selects, chips, the badge and the rule, all owned by the skin. */
    private void hudDrawables() {
        Texture white = skin.get("white", Texture.class);
        skin.add("panel", pad(new HudFrame(white, PANEL, RAIL, CORNER, 13, null), 12, 14), Drawable.class);
        skin.add("panel-foe", pad(new HudFrame(white, PANEL, RAIL, CORAL, 13, null), 12, 14), Drawable.class);
        skin.add("panel-pop", pad(new HudFrame(white, POP, RAIL, CORNER, 13, null), 6, 0), Drawable.class);

        Color faint = rgba(255, 255, 255, .012f);
        box("button", faint, QUIET, 1, 3, null, 8, 12, 36);
        box("button-over", rgba(255, 255, 255, .04f), HOVER, 1, 3, null, 8, 12, 36);
        box("button-checked", FILL, FILL, 1, 3, null, 8, 12, 36);
        box("button-disabled", faint, alpha(QUIET, .1f), 1, 3, null, 8, 12, 36);
        box("button-auto", faint, Color.WHITE, 1, 3, null, 8, 12, 36);
        box("button-main", MAIN, Color.valueOf("E5EEE7"), 1, 3, null, 8, 12, 46);
        box("button-main-over", Color.WHITE, Color.WHITE, 1, 3, null, 8, 12, 46);
        box("button-main-disabled", rgba(255, 255, 255, .08f), Color.CLEAR, 1, 3, null, 8, 12, 46);
        Color rowFill = rgba(255, 255, 255, .015f);
        box("row", rowFill, alpha(QUIET, .16f), 1, 3, null, 7, 10, 0);
        box("row-over", rowFill, alpha(HOVER, .55f), 1, 3, null, 7, 10, 0);
        box("row-selected", alpha(MINT, .07f), MINT, 1, 3, MINT, 7, 10, 0);
        box("field", rgba(255, 255, 255, .05f), rgba(255, 255, 255, .18f), 1, 3, null, 7, 10, 0);
        box("field-focused", alpha(MINT, .06f), MINT, 1, 3, null, 7, 10, 0);
        // Drop-down selects (.fsel).
        box("select", rgba(255, 255, 255, .04f), alpha(QUIET, .3f), 1, 3, null, 6, 8, 0);
        // Chips (.chip, .chip.w, .chip.x): opaque borders without a fill.
        box("chip", Color.CLEAR, Color.valueOf("4B5654"), 1, 3, null, 2, 7, 0);
        box("chip-warn", Color.CLEAR, Color.valueOf("6F6035"), 1, 3, null, 2, 7, 0);
        box("chip-bad", Color.CLEAR, Color.valueOf("7A3F3A"), 1, 3, null, 2, 7, 0);
        // A utility's count badge (.b.util .badge).
        box("badge", AMBER, null, 0, 8.5f, null, 0, 4, 17).setMinWidth(17);
        // A pill split in sections (UiKit.segmented "hud-pill"): one frame, the hovered and the pressed section in it.
        box("pill-frame", faint, QUIET, 1, 3, null, 0, 0, 0);
        box("pill-section-over", rgba(255, 255, 255, .06f), null, 0, 2, null, 0, 0, 0);
        box("pill-section-on", FILL, null, 0, 2, null, 0, 0, 0);
        Drawable rule = skin.newDrawable("white", LINE);
        rule.setMinHeight(1);
        skin.add("rule", rule, Drawable.class);
    }

    /** Pads a drawable: {@code vertical} units above and below its content, {@code horizontal} beside it. */
    public static <T extends Drawable> T pad(T drawable, float vertical, float horizontal) {
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        return drawable;
    }

    /**
     * A standalone utility (.b.brk): corner ticks at rest, a full outline under the pointer, filled when pressed. As on
     * hud buttons, the hover rule outranks the pressed one, so a hovered pressed utility is outlined.
     */
    private TextButton.TextButtonStyle utility(TextButton.TextButtonStyle hud, String font, float top,
          float horizontal, float bottom, float minWidth, float minHeight) {
        Texture white = skin.get("white", Texture.class);
        Color outline = Color.valueOf("F5F7F3");
        UnaryOperator<HudFrame> size = frame -> {
            pad(frame, top, horizontal).setBottomHeight(bottom);
            frame.setMinWidth(minWidth);
            frame.setMinHeight(minHeight);
            return frame;
        };
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(hud);
        style.font = skin.getFont(font);
        style.up = size.apply(new HudFrame(white, rgba(25, 33, 33, .9f), null, TICK, 8, null));
        style.over = size.apply(new HudFrame(white, rgba(25, 33, 33, .95f), null, null, 0, outline));
        style.down = style.over;
        style.checked = size.apply(new HudFrame(white, FILL, null, null, 0, outline));
        style.checkedOver = style.over;
        style.checkedDown = style.over;
        style.disabled = style.up;
        return style;
    }

    /**
     * The styles only the toolkit's widgets use: the 30-unit icon button (.b.ib), the normal-case buttons (.brow .b),
     * list rows (.row), drop-down faces (.fsel, .amsel), underline tabs (.tabs, .ltabs), the search field, the dot,
     * and UiList's flying row, its shadow and its slot.
     */
    private void kitStyles(TextButton.TextButtonStyle hud) {
        Texture white = skin.get("white", Texture.class);
        // .brow .b: a quiet button in 500 12px Roboto without tracking or upper-casing, at least 34 units tall.
        skin.add("hud-plain", quietButton("hud-medium", 8, 12, 34));
        Drawable none = flat(Color.CLEAR, 0, 0);
        none.setMinWidth(30);
        none.setMinHeight(30);
        TextButton.TextButtonStyle icon = new TextButton.TextButtonStyle(hud);
        icon.up = none;
        icon.over = box("button-icon-over", rgba(255, 255, 255, .04f), alpha(HOVER, .6f), 1, 3, null, 0, 0, 30);
        icon.over.setMinWidth(30);
        icon.down = icon.over;
        icon.checked = resized("button-checked", 0, 0, 30);
        icon.checked.setMinWidth(30);
        // A pressed icon button stays filled under the pointer (.b.ib[aria-pressed] follows the .b hover rule).
        icon.checkedOver = icon.checked;
        icon.checkedDown = icon.checked;
        icon.checkedOverFontColor = FILL_INK;
        icon.checkedDownFontColor = FILL_INK;
        icon.disabled = none;
        skin.add("hud-icon", icon);

        TextButton.TextButtonStyle row = new TextButton.TextButtonStyle();
        row.font = skin.getFont("hud-body");
        row.fontColor = TEXT;
        row.up = skin.getDrawable("row");
        row.over = skin.getDrawable("row-over");
        row.down = row.over;
        // .row.sel comes after .row:hover, so a selected row keeps its look under the pointer.
        row.checked = skin.getDrawable("row-selected");
        row.checkedOver = row.checked;
        row.checkedDown = row.checked;
        skin.add("hud-row", row);

        TextButton.TextButtonStyle select = new TextButton.TextButtonStyle();
        select.font = skin.getFont("hud-body");
        select.fontColor = TEXT;
        select.disabledFontColor = DISABLED;
        select.up = skin.getDrawable("select");
        skin.add("hud-select", select);
        TextButton.TextButtonStyle inline = new TextButton.TextButtonStyle(select);
        inline.font = skin.getFont("hud-small");
        inline.fontColor = ACCENT;
        inline.overFontColor = TEXT;
        // .amsel: no box, a dotted underline, two units of room for the chevron.
        Drawable dotted = new Dashes(white, Color.valueOf("7C8886"), 1, 1, 1, false);
        dotted.setRightWidth(2);
        inline.up = dotted;
        skin.add("hud-select-inline", inline);

        TextButton.TextButtonStyle tab = new TextButton.TextButtonStyle();
        tab.font = skin.getFont("hud-medium");
        tab.fontColor = MUTED;
        tab.checkedFontColor = Color.WHITE;
        tab.up = flat(Color.CLEAR, 0, 0);
        tab.checked = new Dashes(white, MINT, 2, 0, 0, false);
        for (Drawable state : List.of(tab.up, tab.checked)) {
            state.setTopHeight(6);
            state.setBottomHeight(8);
        }
        skin.add("hud-tab", tab);
        // The log's tabs (.ltabs span): the same underline with upper-case condensed captions.
        TextButton.TextButtonStyle capsTab = new TextButton.TextButtonStyle(tab);
        capsTab.font = skin.getFont("hud-button");
        skin.add("hud-tab-caps", capsTab);
        // The tab bar's hairline under every tab (.tabs border-bottom).
        skin.add("tabs", new Dashes(white, LINE, 1, 0, 0, false), Drawable.class);

        NinePatchDrawable search = new NinePatchDrawable((NinePatchDrawable) skin.getDrawable("field"));
        search.setRightWidth(30);
        NinePatchDrawable searchFocused = new NinePatchDrawable((NinePatchDrawable) skin.getDrawable("field-focused"));
        searchFocused.setRightWidth(30);
        // .field's box: 13-unit text at its normal line height (15.2) with 7 units of padding and a 1-unit border
        // above and below, where the field itself measures the font's cap height and descent (30)
        for (NinePatchDrawable box : List.of(search, searchFocused)) {
            box.setMinHeight(31.2f);
        }
        TextField.TextFieldStyle field = new TextField.TextFieldStyle(skin.get("hud", TextField.TextFieldStyle.class));
        field.background = search;
        field.focusedBackground = searchFocused;
        skin.add("hud-search", field);

        // The unread dot (.b.util .dotb).
        box("dot", AMBER, null, 0, 3.5f, null, 0, 0, 7).setMinWidth(7);

        // A reorderable list's flying row: the selected row's look (.row.sel) on an opaque raised face over a soft
        // shadow; and the slot it will drop into, recessed and outlined in mint.
        box("row-lifted", rgba(31, 46, 46, .96f), MINT, 1, 3, MINT, 0, 0, 0);
        shadow("shadow", 9, 3, .5f);
        box("row-slot", rgba(0, 0, 0, .22f), alpha(MINT, .5f), 1, 3, null, 0, 0, 0);
    }

    /**
     * Registers a rounded box as a nine-patch: fill, border (none when null), optional 3-unit left bar; sizes in
     * stage units. It is rasterised with analytic coverage at twice the stage density and drawn at half scale. A
     * radius of half the minimum height gives a disc or a pill that can be drawn at that height.
     */
    public NinePatchDrawable box(String name, Color fill, Color border, float borderWidth, float radius, Color bar,
          float vertical, float horizontal, float minHeight) {
        Color edge = border == null ? fill : border;
        int round = Math.round(radius * 2);
        int line = border == null ? 0 : Math.round(borderWidth * 2);
        int barEnd = bar == null ? 0 : line + 6;
        // Corners hold the rounding and the bar plus two plain texels, so filtering the stretched middle never
        // picks them up; a disc or a pill is all corner, so it keeps its diameter.
        int split = radius * 2 == minHeight ? round : Math.max(round, Math.max(line, barEnd)) + 2;
        int size = 2 * split + 4;
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        Color pixel = new Color();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float outer = coverage(x, y, size, 0, round);
                float inner = coverage(x, y, size, line, Math.max(round - line, 0));
                pixel.set(0, 0, 0, 0);
                over(pixel, fill, inner);
                if (x < barEnd) {
                    over(pixel, bar, inner);
                }
                over(pixel, edge, outer - inner);
                // Straight alpha; empty texels take the edge color, so filtering adds no dark fringe.
                if (pixel.a > 0) {
                    pixel.set(pixel.r / pixel.a, pixel.g / pixel.a, pixel.b / pixel.a, pixel.a);
                } else {
                    pixel.set(edge.r, edge.g, edge.b, 0);
                }
                pixels.drawPixel(x, y, Color.rgba8888(pixel));
            }
        }
        Texture texture = new Texture(pixels);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        add(name + "-texture", texture, Texture.class);
        NinePatch patch = new NinePatch(texture, split, split, split, split);
        patch.scale(.5f, .5f);
        NinePatchDrawable drawable = new NinePatchDrawable(patch);
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        drawable.setMinWidth(0);
        drawable.setMinHeight(minHeight);
        return add(name, drawable, Drawable.class);
    }

    /** Coverage of texel (x, y) by a rounded square inset in a size-texel box, from its signed distance. */
    private static float coverage(int x, int y, int size, float inset, float radius) {
        return MathUtils.clamp(.5f - distance(x, y, size, inset, radius), 0, 1);
    }

    /** The signed distance in texels (negative inside) of texel (x, y)'s centre from a rounded square's edge. */
    private static float distance(int x, int y, int size, float inset, float radius) {
        float half = size / 2f;
        float edge = half - inset - radius;
        float qx = Math.abs(x + .5f - half) - edge;
        float qy = Math.abs(y + .5f - half) - edge;
        return (float) Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /**
     * Registers a soft shadow (CSS box-shadow's blur) of a rounded box as a nine-patch: black whose alpha eases from
     * {@code alpha} to none across {@code blur} units on each side of the box's edge, rasterised at twice the stage
     * density. Its padding is the blur: drawn that much larger than the box on every side, the falloff centres on the
     * box's edge. Its minimum size is the smallest it draws without overlapping its corners.
     */
    private void shadow(String name, float blur, float radius, float alpha) {
        int soft = Math.round(blur * 2);
        int round = Math.round(radius * 2);
        // Corners hold the falloff outside and inside the edge and the rounding, so the stretched middle is uniform.
        int split = 2 * soft + round + 2;
        int size = 2 * split + 4;
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float across = MathUtils.clamp((distance(x, y, size, soft, round) + soft) / (2 * soft), 0, 1);
                pixels.drawPixel(x, y, Color.rgba8888(0, 0, 0, alpha * (1 - across * across * (3 - 2 * across))));
            }
        }
        Texture texture = new Texture(pixels);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        add(name + "-texture", texture, Texture.class);
        NinePatch patch = new NinePatch(texture, split, split, split, split);
        patch.scale(.5f, .5f);
        add(name, pad(new NinePatchDrawable(patch), blur, blur), Drawable.class);
    }

    /** Premultiplied source-over of a straight-alpha color at the given coverage. */
    private static void over(Color premultiplied, Color color, float coverage) {
        float alpha = color.a * coverage;
        premultiplied.r = color.r * alpha + premultiplied.r * (1 - alpha);
        premultiplied.g = color.g * alpha + premultiplied.g * (1 - alpha);
        premultiplied.b = color.b * alpha + premultiplied.b * (1 - alpha);
        premultiplied.a = alpha + premultiplied.a * (1 - alpha);
    }

    /** A copy of the registered nine-patch {@code name} with other padding and minimum height. */
    public NinePatchDrawable resized(String name, float vertical, float horizontal, float minHeight) {
        NinePatchDrawable drawable = (NinePatchDrawable) skin.newDrawable(name);
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        drawable.setMinHeight(minHeight);
        return drawable;
    }

    /**
     * The prototype's quiet button: ink2 label, white on hover, filled when checked, dim when disabled. proto3.css's
     * hover rule outranks its pressed rule, so a hovered or held checked button shows the hover look.
     */
    private TextButton.TextButtonStyle quietButton(String font, float vertical, float horizontal, float minHeight) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = skin.getFont(font);
        style.up = resized("button", vertical, horizontal, minHeight);
        style.over = resized("button-over", vertical, horizontal, minHeight);
        style.down = style.over;
        style.checked = resized("button-checked", vertical, horizontal, minHeight);
        style.checkedOver = style.over;
        style.checkedDown = style.over;
        style.disabled = resized("button-disabled", vertical, horizontal, minHeight);
        style.fontColor = ACCENT;
        style.overFontColor = Color.WHITE;
        style.checkedFontColor = FILL_INK;
        style.checkedOverFontColor = Color.WHITE;
        style.checkedDownFontColor = Color.WHITE;
        style.disabledFontColor = DISABLED;
        return style;
    }

    /** The prototype's thin list scroll bar: a #4a5553 knob on a transparent track. */
    public ScrollPane.ScrollPaneStyle scrollStyle(float width, float knobHeight) {
        ScrollPane.ScrollPaneStyle style = new ScrollPane.ScrollPaneStyle();
        style.vScroll = skin.newDrawable("white", Color.CLEAR);
        style.vScrollKnob = skin.newDrawable("white", Color.valueOf("4A5553"));
        style.vScroll.setMinWidth(width);
        style.vScrollKnob.setMinWidth(width);
        style.vScrollKnob.setMinHeight(knobHeight);
        return style;
    }

    /** Bakes the Material Symbols glyphs once into one mipmapped sheet of em-square cells, then frees the face. */
    private void icons() {
        List<String> names = ICONS.keySet().stream().sorted().toList();
        int columns = 8;
        Pixmap sheet = new Pixmap(columns * ICON_CELL, (names.size() + columns - 1) / columns * ICON_CELL,
              Pixmap.Format.RGBA8888);
        sheet.setBlending(Pixmap.Blending.None);
        // Transparent white, so the mipmaps average coverage only.
        sheet.setColor(1, 1, 1, 0);
        sheet.fill();
        int margin = (ICON_CELL - ICON_SIZE) / 2;
        FreeType.Library library = FreeType.initFreeType();
        try {
            FreeType.Face face = library.newFace(fontFile(fonts, ICON_FONT), 0);
            try {
                face.setPixelSizes(0, ICON_SIZE);
                for (int i = 0; i < names.size(); i++) {
                    int codepoint = ICONS.get(names.get(i));
                    if (face.getCharIndex(codepoint) == 0 || !face.loadChar(codepoint, FreeType.FT_LOAD_DEFAULT)
                          || !face.getGlyph().renderGlyph(FreeType.FT_RENDER_MODE_NORMAL)) {
                        throw new IllegalStateException("The icon font has no glyph for icon-" + names.get(i));
                    }
                    FreeType.GlyphSlot slot = face.getGlyph();
                    Pixmap glyph = slot.getBitmap().getPixmap(Pixmap.Format.RGBA8888, Color.WHITE, 1);
                    if (FILLED_ICONS.contains(names.get(i))) {
                        closeOutline(glyph);
                    }
                    // Icons are drawn on the em square above the baseline; keep that box and its margins.
                    sheet.drawPixmap(glyph, i % columns * ICON_CELL + margin + slot.getBitmapLeft(),
                          i / columns * ICON_CELL + margin + ICON_SIZE - slot.getBitmapTop());
                    glyph.dispose();
                }
            } finally {
                face.dispose();
            }
        } finally {
            library.dispose();
        }
        Texture texture = new Texture(sheet, true);
        sheet.dispose();
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        skin.add("icon-texture", texture);
        for (int i = 0; i < names.size(); i++) {
            TextureRegion region = new TextureRegion(texture, i % columns * ICON_CELL + margin,
                  i / columns * ICON_CELL + margin, ICON_SIZE, ICON_SIZE);
            skin.add("icon-" + names.get(i), new TextureRegionDrawable(region), Drawable.class);
        }
    }

    /**
     * Adds a symbol image as icon-{@code name}, for a view's own symbol that the icon font lacks. The image's alpha is
     * the symbol and its colour is ignored, so a black or a white image will do. Its longer side spans the font icons'
     * live area, centred, so it lines up, scales and tints as they do. Call it on the GL thread; the theme owns the
     * icon's mipmapped texture and the caller keeps the image.
     */
    public void addIcon(String name, Pixmap image) {
        // Checked before the texture exists, which a refused name would leave unowned.
        if (skin.has("icon-" + name, Drawable.class)) {
            throw new IllegalStateException("The skin already has the icon-" + name);
        }
        Pixmap cell = iconCell(image);
        Texture texture = add("icon-" + name + "-texture", new Texture(cell, true), Texture.class);
        cell.dispose();
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        int margin = (ICON_CELL - ICON_SIZE) / 2;
        add("icon-" + name, new TextureRegionDrawable(new TextureRegion(texture, margin, margin, ICON_SIZE,
              ICON_SIZE)), Drawable.class);
    }

    /**
     * A symbol image as a sheet cell: white, its coverage the image's alpha box-filtered so that the image's longer
     * side spans the live area.
     */
    private static Pixmap iconCell(Pixmap image) {
        int width = image.getWidth();
        int height = image.getHeight();
        // Image texels per cell texel, and the cell's corner in image texels.
        float step = Math.max(width, height) / (float) ICON_LIVE;
        float left = (width - ICON_CELL * step) / 2;
        float top = (height - ICON_CELL * step) / 2;
        Pixmap cell = new Pixmap(ICON_CELL, ICON_CELL, Pixmap.Format.RGBA8888);
        cell.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < ICON_CELL; y++) {
            float top0 = top + y * step;
            for (int x = 0; x < ICON_CELL; x++) {
                float left0 = left + x * step;
                // The image texels under this cell texel, each weighted by the area they share.
                float alpha = 0;
                for (int row = Math.max(0, (int) top0); row < Math.min(height, top0 + step); row++) {
                    float rowShare = Math.min(top0 + step, row + 1) - Math.max(top0, row);
                    for (int column = Math.max(0, (int) left0); column < Math.min(width, left0 + step); column++) {
                        float share = rowShare * (Math.min(left0 + step, column + 1) - Math.max(left0, column));
                        alpha += share * (image.getPixel(column, row) & 0xFF);
                    }
                }
                // Transparent white around the symbol, as in the font sheet, so the mipmaps average coverage only.
                cell.drawPixel(x, y, Color.rgba8888(1, 1, 1, alpha / (255 * step * step)));
            }
        }
        return cell;
    }

    /**
     * Fills a white glyph's enclosed interior: texels that no path of mostly transparent texels (4-connected) joins to
     * the bitmap's border become opaque, except the outline's outer edge, which keeps its antialiasing.
     */
    private static void closeOutline(Pixmap glyph) {
        int width = glyph.getWidth();
        int height = glyph.getHeight();
        boolean[] outside = new boolean[width * height];
        ArrayDeque<Integer> open = new ArrayDeque<>();
        for (int x = 0; x < width; x++) {
            reach(glyph, outside, open, x, 0);
            reach(glyph, outside, open, x, height - 1);
        }
        for (int y = 0; y < height; y++) {
            reach(glyph, outside, open, 0, y);
            reach(glyph, outside, open, width - 1, y);
        }
        while (!open.isEmpty()) {
            int index = open.poll();
            int x = index % width;
            int y = index / width;
            reach(glyph, outside, open, x - 1, y);
            reach(glyph, outside, open, x + 1, y);
            reach(glyph, outside, open, x, y - 1);
            reach(glyph, outside, open, x, y + 1);
        }
        glyph.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!outside(outside, width, height, x, y) && !outside(outside, width, height, x - 1, y)
                      && !outside(outside, width, height, x + 1, y) && !outside(outside, width, height, x, y - 1)
                      && !outside(outside, width, height, x, y + 1)) {
                    glyph.drawPixel(x, y, 0xFFFFFFFF);
                }
            }
        }
    }

    private static void reach(Pixmap glyph, boolean[] outside, ArrayDeque<Integer> open, int x, int y) {
        int index = y * glyph.getWidth() + x;
        if (x >= 0 && y >= 0 && x < glyph.getWidth() && y < glyph.getHeight() && !outside[index]
              && (glyph.getPixel(x, y) & 0xFF) < 128) {
            outside[index] = true;
            open.add(index);
        }
    }

    /** Texels beyond the bitmap count as outside. */
    private static boolean outside(boolean[] outside, int width, int height, int x, int y) {
        return x < 0 || y < 0 || x >= width || y >= height || outside[y * width + x];
    }

    /**
     * A font for a view's own styles in this look, registered as {@code name}: the face at {@code path} in the fonts
     * directory at {@code size} stage units, on pages of its own, with the theme's fallback faces.
     */
    public BitmapFont font(String name, String path, float size) {
        return font(name, path, 0, size, 0, null);
    }

    /**
     * Rasterised at 4x and drawn at a quarter, so fractional CSS sizes such as 10.5 work; tracking is in em. Characters
     * the face lacks come from the FALLBACK_FONTS chain. The hud fonts (those on the shared pages) lost thin strokes,
     * so "AS7-D" read "AS7 D": a glyph quad thinner than a pixel, such as a 4-texel hyphen drawn at 10 units, covers no
     * pixel centre at some sub-pixel positions, and linear filtering minified 4:1 reads only two of every four texel
     * rows. Their glyphs are therefore mipmapped, which marks them for Generator: their quads take in GLYPH_PAD
     * texels of the packer's transparent margin, and their pages are sampled from mip level 0 or 1 (see
     * bindGlyphPage), whichever is nearer.
     */
    private BitmapFont font(String name, String path, int instance, float size, float tracking, PixmapPacker packer) {
        FreeTypeFontGenerator generator = new Generator(fontFile(fonts, path), instance, 1, fallback);
        generators.add(generator);
        FreeTypeFontGenerator.FreeTypeFontParameter settings = new FreeTypeFontGenerator.FreeTypeFontParameter();
        settings.size = Math.round(size * 4);
        settings.spaceX = Math.round(tracking * size * 4);
        settings.packer = packer;
        settings.incremental = true;
        settings.minFilter = Texture.TextureFilter.Linear;
        settings.magFilter = Texture.TextureFilter.Linear;
        boolean hud = packer != null;
        if (hud) {
            settings.genMipMaps = true;
            settings.minFilter = Texture.TextureFilter.MipMapLinearNearest;
        }
        BitmapFont font = generator.generateFont(settings);
        if (hud) {
            // GlyphLayout subtracts these from a line's first and last glyph, so lines measure their ink as before.
            font.getData().padLeft = GLYPH_PAD;
            font.getData().padRight = GLYPH_PAD;
        }
        font.getData().setScale(0.25f);
        font.setUseIntegerPositions(false);
        return add(name, font, BitmapFont.class);
    }

    @Override
    public void dispose() {
        skin.dispose();
        // The shared glyph pages belong to the theme, not to the hud fonts packed into them.
        for (PixmapPacker.Page page : hudGlyphs.getPages()) {
            if (page.getTexture() != null) {
                page.getTexture().dispose();
            }
        }
        hudGlyphs.dispose();
        generators.forEach(FreeTypeFontGenerator::dispose);
    }

    /**
     * A generator that takes each character its face lacks from the first face of its fallback chain that has it. That
     * face renders it at its share of this font's size, on this font's baseline and into this font's pages, so one
     * BitmapFont draws the whole text. A hud glyph's quad grows by GLYPH_PAD on every side with its ink kept in place,
     * and a glyph written straight into a mipmapped page texture (incremental glyphs after the first layout) rebuilds
     * that page's mipmaps.
     */
    private static final class Generator extends FreeTypeFontGenerator {
        private final float size;
        private final Generator fallback;

        /** size is the face's share of the size of the fonts it serves as a fallback. */
        Generator(FileHandle file, int instance, float size, Generator fallback) {
            // FreeType takes the named instance from bits 16-30 of the face index.
            super(file, instance << 16);
            this.size = size;
            this.fallback = fallback;
        }

        @Override
        protected BitmapFont.Glyph createGlyph(char c, FreeTypeBitmapFontData data, FreeTypeFontParameter parameter,
              FreeType.Stroker stroker, float baseLine, PixmapPacker packer) {
            if (c != 0 && !hasGlyph(c)) {
                for (Generator next = fallback; next != null; next = next.fallback) {
                    if (next.hasGlyph(c)) {
                        // A fallback face serves fonts of every size; scaleForPixelHeight is the public way to size it.
                        next.scaleForPixelHeight(Math.round(parameter.size * next.size));
                        return next.createGlyph(c, data, parameter, stroker, baseLine, packer);
                    }
                }
                // No shipped face has it: its lookalike, drawn under its own code.
                Character lookalike = LOOKALIKES.get(c);
                BitmapFont.Glyph glyph = lookalike == null ? null
                      : createGlyph(lookalike, data, parameter, stroker, baseLine, packer);
                if (glyph != null) {
                    glyph.id = c;
                }
                return glyph;
            }
            BitmapFont.Glyph glyph = super.createGlyph(c, data, parameter, stroker, baseLine, packer);
            if (glyph == null || !parameter.genMipMaps) {
                return glyph;
            }
            // The hud packer keeps GLYPH_PADDING + 2 * GLYPH_PAD texels of transparent white around each glyph's ink.
            if (glyph.width > 0 && glyph.height > 0) {
                glyph.srcX -= GLYPH_PAD;
                glyph.srcY -= GLYPH_PAD;
                glyph.width += 2 * GLYPH_PAD;
                glyph.height += 2 * GLYPH_PAD;
                glyph.yoffset -= GLYPH_PAD;
            } else {
                // An inkless glyph, such as the space, spans its advance, as libGDX makes the space's width.
                glyph.width = glyph.xadvance + 2 * GLYPH_PAD;
            }
            glyph.xoffset -= GLYPH_PAD;
            Texture page = packer.getPages().get(glyph.page).getTexture();
            if (page != null && packer.getPackToTexture()) {
                bindGlyphPage(page);
                Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
            }
            return glyph;
        }
    }

    /**
     * Binds a mipmapped hud glyph page and limits its sampling to mip level 1 (2x2-texel averages): at 1 pixel per unit
     * that level keeps every stroke of 3 texels or more, while level 2 blurred the text by about a pixel.
     */
    private static void bindGlyphPage(Texture page) {
        page.bind();
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 1);
    }

    /** Physical pixels per stage unit, from the orthographic projection the stage draws with. */
    public static float pixelScale(Batch batch) {
        return batch.getProjectionMatrix().val[Matrix4.M00] * Gdx.graphics.getBackBufferWidth() / 2;
    }

    /** {@code value} in stage units, rounded to whole pixels at {@code scale} pixels per unit. */
    public static float snap(float value, float scale) {
        return Math.round(value * scale) / scale;
    }

    /** A hud-v3 frame: flat fill, optional 2-unit rails top and bottom, corner strokes or an outline, pixel-snapped. */
    public static final class HudFrame extends BaseDrawable {
        private static final float STROKE = 2;
        private final Texture white;
        private final Color fill;
        private final Color rail;
        private final Color corner;
        private final float cornerLength;
        private final Color outline;
        private final Color tint = new Color();

        /** The fill, then rails, an outline and corner strokes of {@code cornerLength} units; each may be null. */
        public HudFrame(Texture white, Color fill, Color rail, Color corner, float cornerLength, Color outline) {
            this.white = white;
            this.fill = fill;
            this.rail = rail;
            this.corner = corner;
            this.cornerLength = cornerLength;
            this.outline = outline;
        }

        @Override
        public void draw(Batch batch, float x, float y, float width, float height) {
            float scale = pixelScale(batch);
            float left = snap(x, scale);
            float bottom = snap(y, scale);
            float right = snap(x + width, scale);
            float top = snap(y + height, scale);
            float line = Math.max(1, Math.round(STROKE * scale)) / scale;
            float previous = batch.getPackedColor();
            tint.set(batch.getColor());
            rect(batch, fill, left, bottom, right - left, top - bottom);
            if (rail != null) {
                rect(batch, rail, left, top - line, right - left, line);
                rect(batch, rail, left, bottom, right - left, line);
            }
            if (outline != null) {
                rect(batch, outline, left, top - line, right - left, line);
                rect(batch, outline, left, bottom, right - left, line);
                rect(batch, outline, left, bottom + line, line, top - bottom - 2 * line);
                rect(batch, outline, right - line, bottom + line, line, top - bottom - 2 * line);
            }
            if (corner != null) {
                float length = Math.min(snap(cornerLength, scale), Math.min(right - left, top - bottom) / 2);
                rect(batch, corner, left, bottom, length, line);
                rect(batch, corner, left, bottom, line, length);
                rect(batch, corner, right - length, bottom, length, line);
                rect(batch, corner, right - line, bottom, line, length);
                rect(batch, corner, left, top - line, length, line);
                rect(batch, corner, left, top - length, line, length);
                rect(batch, corner, right - length, top - line, length, line);
                rect(batch, corner, right - line, top - length, line, length);
            }
            batch.setPackedColor(previous);
        }

        private void rect(Batch batch, Color color, float x, float y, float width, float height) {
            batch.setColor(color.r * tint.r, color.g * tint.g, color.b * tint.b, color.a * tint.a);
            batch.draw(white, x, y, width, height);
        }
    }

    /**
     * A pixel-snapped line along the bottom edge, or a box outline when {@code box}; solid when {@code dash} is 0,
     * otherwise dashes of {@code dash} units with {@code gap} units between them (CSS dashed and dotted borders).
     */
    public static final class Dashes extends BaseDrawable {
        private final Texture white;
        private final Color color;
        private final float thickness;
        private final float dash;
        private final float gap;
        private final boolean box;

        /** A line or outline {@code thickness} units wide in {@code color}, drawn from the {@code white} texel. */
        public Dashes(Texture white, Color color, float thickness, float dash, float gap, boolean box) {
            this.white = white;
            this.color = color;
            this.thickness = thickness;
            this.dash = dash;
            this.gap = gap;
            this.box = box;
        }

        @Override
        public void draw(Batch batch, float x, float y, float width, float height) {
            float scale = pixelScale(batch);
            float left = snap(x, scale);
            float bottom = snap(y, scale);
            float right = snap(x + width, scale);
            float top = snap(y + height, scale);
            float line = Math.max(1, Math.round(thickness * scale)) / scale;
            float previous = batch.getPackedColor();
            Color tint = batch.getColor();
            batch.setColor(color.r * tint.r, color.g * tint.g, color.b * tint.b, color.a * tint.a);
            horizontal(batch, left, right, bottom, line);
            if (box) {
                horizontal(batch, left, right, top - line, line);
                vertical(batch, bottom + line, top - line, left, line);
                vertical(batch, bottom + line, top - line, right - line, line);
            }
            batch.setPackedColor(previous);
        }

        private void horizontal(Batch batch, float from, float to, float y, float line) {
            float step = dash > 0 ? dash + gap : to - from;
            for (float x = from; x < to; x += step) {
                batch.draw(white, x, y, Math.min(dash > 0 ? dash : step, to - x), line);
            }
        }

        private void vertical(Batch batch, float from, float to, float x, float line) {
            float step = dash > 0 ? dash + gap : to - from;
            for (float y = from; y < to; y += step) {
                batch.draw(white, x, y, line, Math.min(dash > 0 ? dash : step, to - y));
            }
        }
    }

    /**
     * A flat fill (none when null) under edges of one color, drawn from the white texel at the batch color's alpha
     * and not pixel-snapped. Each side's edge is as wide as given, in the order of CSS border widths (top, right,
     * bottom, left; 0 for none), solid or {@link #dashed}; the top and bottom edges span the box and the side edges
     * run between them. Views draw their tags, badges and cards with it.
     */
    public static final class EdgeBox extends BaseDrawable {
        private final Texture white;
        private final Color fill;
        private final Color edge;
        private final float top;
        private final float right;
        private final float bottom;
        private final float left;
        private float dash;

        /** The fill and edges of the given CSS border widths, drawn from the {@code white} texel. */
        public EdgeBox(Texture white, Color fill, Color edge, float top, float right, float bottom, float left) {
            this.white = white;
            this.fill = fill;
            this.edge = edge;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.left = left;
        }

        /** Dashes of {@code length} units with gaps as long instead of solid edges (a CSS dashed border). */
        public EdgeBox dashed(float length) {
            dash = length;
            return this;
        }

        @Override
        public void draw(Batch batch, float x, float y, float width, float height) {
            float alpha = batch.getColor().a;
            float previous = batch.getPackedColor();
            if (fill != null) {
                batch.setColor(fill.r, fill.g, fill.b, fill.a * alpha);
                batch.draw(white, x, y, width, height);
            }
            batch.setColor(edge.r, edge.g, edge.b, edge.a * alpha);
            float step = dash > 0 ? 2 * dash : width;
            for (float along = 0; along < width; along += step) {
                float length = Math.min(dash > 0 ? dash : width, width - along);
                if (bottom > 0) {
                    batch.draw(white, x + along, y, length, bottom);
                }
                if (top > 0) {
                    batch.draw(white, x + along, y + height - top, length, top);
                }
            }
            float side = height - bottom - top;
            step = dash > 0 ? 2 * dash : side;
            for (float along = bottom; along < height - top; along += step) {
                float length = Math.min(dash > 0 ? dash : side, height - top - along);
                if (left > 0) {
                    batch.draw(white, x, y + along, left, length);
                }
                if (right > 0) {
                    batch.draw(white, x + width - right, y + along, right, length);
                }
            }
            batch.setPackedColor(previous);
        }
    }
}
