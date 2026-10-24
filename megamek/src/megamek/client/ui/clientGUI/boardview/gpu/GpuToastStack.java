/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.clientGUI.boardview.overlay.BoardToastOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTheme;

/**
 * Toast stack above the dock (C.1 G15, r1 3.23, plan A.15 O5): the client's toasts (GpuToasts) in MegaMek's stack of
 * {@link BoardToastOverlay#MAX_VISIBLE} with its five levels, each in the prototype's toast with a left bar in its
 * level's color. The newest sits at the bottom, older ones above it, as the Swing stack orders them; each fades in,
 * stays for its level's duration and fades out, and a toast the full stack drops for a newer one leaves at once.
 * Presses pass through to the board, as in the prototype.
 */
final class GpuToastStack implements GpuHud.Component {
    /**
     * The prototype's toast (#toast): at most 640 units wide, padded 9 14, after a 3-unit bar; 6 between toasts. The
     * HUD's toast slot takes the same width.
     */
    static final float MAX_WIDTH = 640;
    private static final float BAR = 3;
    private static final float PAD_X = 14;
    private static final float PAD_Y = 9;
    private static final float GAP = 6;
    /** The prototype's opacity transition (#toast: transition opacity .2s), in seconds. */
    private static final float FADE = .2f;
    /** A toast's unit: the fire preview row's sprite box (.prow .spr), before the text. */
    private static final float SPRITE_WIDTH = 34;
    private static final float SPRITE_HEIGHT = 30;
    private static final float SPRITE_GAP = 10;
    /** Token v0's toast: at most 480 wide, and the unit's sprite fitted in 40 x 34. */
    private static final float TOKEN_MAX_WIDTH = 480;
    private static final float TOKEN_SPRITE_WIDTH = 40;
    private static final float TOKEN_SPRITE_HEIGHT = 34;

    private final GpuHudKit kit;
    /** Token v0's toast card (docs/design-system/tokens.md, Toasts) rather than hud-v3's. */
    private final boolean token;
    private final VerticalGroup root = new VerticalGroup();
    private final Map<ToastLevel, Drawable> backgrounds = new EnumMap<>(ToastLevel.class);
    /** The toasts on screen by id, until they have faded out or the stack dropped them. */
    private final Map<Long, Actor> shown = new HashMap<>();
    /** The ids of the current snapshot that were shown already, so that a faded toast never shows again. */
    private final Set<Long> seen = new HashSet<>();
    private GpuToasts.Snapshot toasts;

    GpuToastStack(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this(kit, source, state, false);
    }

    /**
     * A stack of hud-v3's toasts, or with {@code token} Token v0's cards: the popover surface with the level's bar
     * and icon and a close button, which alone takes presses. Where the stack sits and in which order is still
     * hud-v3's.
     */
    GpuToastStack(GpuHudKit kit, GpuBoardSource source, GpuHudState state, boolean token) {
        this.kit = kit;
        this.token = token;
        root.setName("toast-stack");
        root.space(GAP);
        root.columnCenter();
        root.setTouchable(token ? Touchable.childrenOnly : Touchable.disabled);
        Texture white = kit.ui.skin.get("white", Texture.class);
        for (ToastLevel level : ToastLevel.values()) {
            if (token) {
                backgrounds.put(level, kit.ui.skin.getDrawable(UiTheme.TOKEN_TOAST
                      + level.name().toLowerCase(Locale.ROOT)));
                continue;
            }
            UiTheme.EdgeBox box = new UiTheme.EdgeBox(white, UiTheme.PANEL2, bar(level), 0, 0, 0, BAR);
            UiTheme.pad(box, PAD_Y, PAD_X).setLeftWidth(BAR + PAD_X);
            backgrounds.put(level, box);
        }
    }

    /** The left bar of each level (plan O5): INFO ink2, SUCCESS mint, WARNING amber, ERROR coral, GAMEMASTER violet. */
    private static Color bar(ToastLevel level) {
        return switch (level) {
            case INFO -> UiTheme.ACCENT;
            case SUCCESS -> UiTheme.MINT;
            case WARNING -> UiTheme.AMBER;
            case ERROR -> UiTheme.CORAL;
            case GAMEMASTER -> UiTheme.VIOLET;
        };
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Shows each toast of a new snapshot once, below the shown ones, and removes those the snapshot dropped. */
    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuToasts.Snapshot next = inputs.frame().panels().toasts();
        if (next == toasts) {
            return;
        }
        toasts = next;
        Set<Long> ids = next.toasts().stream().map(GpuToasts.Toast::id).collect(Collectors.toSet());
        shown.entrySet().removeIf(entry -> {
            boolean dropped = !ids.contains(entry.getKey());
            if (dropped) {
                entry.getValue().remove();
            }
            return dropped;
        });
        for (GpuToasts.Toast toast : next.toasts()) {
            if (seen.add(toast.id())) {
                Actor row = toast(toast);
                shown.put(toast.id(), row);
                root.addActor(row);
            }
        }
        seen.retainAll(ids);
    }

    /**
     * One toast: the unit's sprite when it has one, and its text, on one line up to the stack's width and wrapped
     * beyond it. It fades in, stays for the toast's duration, fades out and leaves the stack.
     */
    Actor toast(GpuToasts.Toast toast) {
        if (token) {
            return tokenToast(toast);
        }
        Table row = new Table();
        row.setBackground(backgrounds.get(toast.level()));
        float room = MAX_WIDTH - BAR - 2 * PAD_X;
        if (toast.icon() != null) {
            row.add(kit.sprite(SPRITE_WIDTH, SPRITE_HEIGHT).set(toast.icon(), GpuHudKit.FRIEND_SPRITE))
                  .padRight(SPRITE_GAP);
            room -= SPRITE_WIDTH + SPRITE_GAP;
        }
        Label text = kit.ui.label(toast.text(), "hud-body", 12.5f, UiTheme.TEXT);
        Cell<Label> cell = row.add(text).left();
        if (text.getPrefWidth() > room) {
            text.setWrap(true);
            cell.width(room);
        }
        long id = toast.id();
        row.getColor().a = 0;
        row.addAction(Actions.sequence(Actions.fadeIn(FADE), Actions.delay(toast.durationMs() / 1000f),
              Actions.fadeOut(FADE), Actions.run(() -> shown.remove(id)), Actions.removeActor()));
        return row;
    }

    /**
     * One toast in Token v0's card, at most {@link #TOKEN_MAX_WIDTH} wide: the level's icon, the unit's sprite when it
     * has one, the text in body type, wrapped when it needs more room, and the close button. It fades in over
     * {@code medium}, stays for the toast's duration, fades out over {@code slow} and leaves the stack.
     */
    private Actor tokenToast(GpuToasts.Toast toast) {
        DesignTokens tokens = DesignTokens.shipped();
        float md = tokens.space().get("md");
        float iconSize = tokens.size().get("icon.md");
        ToastLevel level = toast.level();
        Drawable card = backgrounds.get(level);
        Table row = new Table();
        row.setTouchable(Touchable.childrenOnly);
        row.setBackground(card);
        Actor icon = kit.ui.icon(icon(level), iconSize, UiTheme.tokenColor(role(level)));
        icon.setTouchable(Touchable.disabled);
        row.add(icon);
        long id = toast.id();
        UiButton close = kit.ui.closeButton(() -> {
            shown.remove(id);
            row.clearActions();
            row.addAction(Actions.sequence(Actions.fadeOut(tokens.duration("medium") / 1000f, Interpolation.pow2Out),
                  Actions.removeActor()));
        }, true);
        float room = TOKEN_MAX_WIDTH - card.getLeftWidth() - card.getRightWidth() - iconSize - 2 * md
              - close.getPrefWidth();
        if (toast.icon() != null) {
            Actor sprite = kit.sprite(TOKEN_SPRITE_WIDTH, TOKEN_SPRITE_HEIGHT).set(toast.icon(),
                  GpuHudKit.FRIEND_SPRITE);
            sprite.setTouchable(Touchable.disabled);
            row.add(sprite).padLeft(md);
            room -= TOKEN_SPRITE_WIDTH + md;
        }
        Label text = kit.ui.typeLabel(toast.text(), "body", "text.primary");
        text.setTouchable(Touchable.disabled);
        Cell<Label> cell = row.add(text).left().padLeft(md).growX();
        if (text.getPrefWidth() > room) {
            text.setWrap(true);
            cell.width(room);
        }
        row.add(close).padLeft(md);
        row.getColor().a = 0;
        row.addAction(Actions.sequence(Actions.fadeIn(tokens.duration("medium") / 1000f, Interpolation.pow2Out),
              Actions.delay(toast.durationMs() / 1000f),
              Actions.fadeOut(tokens.duration("slow") / 1000f, Interpolation.pow2Out),
              Actions.run(() -> shown.remove(id)), Actions.removeActor()));
        return row;
    }

    /** A level's icon: info, check, warning, error, and the Game Master's shield with a person. */
    private static String icon(ToastLevel level) {
        return switch (level) {
            case INFO -> "info";
            case SUCCESS -> "check";
            case WARNING -> "warn";
            case ERROR -> "error";
            case GAMEMASTER -> "shield-person";
        };
    }

    /** A level's color role; the Game Master has status.gamemaster. */
    private static String role(ToastLevel level) {
        return switch (level) {
            case INFO -> "status.info";
            case SUCCESS -> "status.success";
            case WARNING -> "status.warning";
            case ERROR -> "status.danger";
            case GAMEMASTER -> "status.gamemaster";
        };
    }
}
