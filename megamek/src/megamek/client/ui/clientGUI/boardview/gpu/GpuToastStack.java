/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
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

    private final GpuHudKit kit;
    private final VerticalGroup root = new VerticalGroup();
    private final Map<ToastLevel, Drawable> backgrounds = new EnumMap<>(ToastLevel.class);
    /** The toasts on screen by id, until they have faded out or the stack dropped them. */
    private final Map<Long, Actor> shown = new HashMap<>();
    /** The ids of the current snapshot that were shown already, so that a faded toast never shows again. */
    private final Set<Long> seen = new HashSet<>();
    private GpuToasts.Snapshot toasts;

    GpuToastStack(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this.kit = kit;
        root.setName("toast-stack");
        root.space(GAP);
        root.columnCenter();
        root.setTouchable(Touchable.disabled);
        Texture white = kit.ui.skin.get("white", Texture.class);
        for (ToastLevel level : ToastLevel.values()) {
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
    private Actor toast(GpuToasts.Toast toast) {
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
}
