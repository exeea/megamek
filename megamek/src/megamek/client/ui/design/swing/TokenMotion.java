/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;

import megamek.client.ui.design.DesignTokens;

/**
 * The motion of the MegaMek look and feels (docs/design-system/tokens.md, Motion): one shared animator that eases the
 * values controls paint with, on the {@code motion} tokens.
 * <p>
 * A control asks for the value it would paint now, for example its background for the current state, and gets the
 * value on its way there instead: when the target changes, the value moves from what is on screen to the new target
 * over the given duration, and the animator repaints the control until it arrives. The first value a control asks for
 * shows at once, so nothing animates when it appears. One timer serves every control and only runs while something
 * moves.
 * <p>
 * The rules: hover comes in quickly ({@code fast}) and lingers on the way out ({@code medium}); a press shows at once
 * and its release fades. With reduce motion on, every value jumps to its target, and FlatLaf's own animations and
 * smooth scrolling stop too. Call everything on the event dispatch thread.
 */
public final class TokenMotion {
    private static final String STATE = "MegaMek.motion";
    private static final int FRAME = 1000 / 60;

    private static final Map<JComponent, Long> MOVING = new IdentityHashMap<>();
    private static Timer timer;
    private static boolean reduced;
    private static LongSupplier clock = () -> System.nanoTime() / 1_000_000L;
    private static int fast = -1;
    private static int medium;
    private static int slow;
    private static float[] curve;

    private TokenMotion() {
    }

    /** Whether reduce motion is on. */
    public static boolean isReduced() {
        return reduced;
    }

    /**
     * Turns reduce motion on or off for every control, including FlatLaf's own animations and smooth scrolling.
     */
    public static void setReduced(boolean reduce) {
        reduced = reduce;
        System.setProperty("flatlaf.animation", Boolean.toString(!reduce));
        UIManager.put("ScrollPane.smoothScrolling", !reduce);
        if (reduce) {
            for (JComponent component : MOVING.keySet()) {
                component.repaint();
            }
            MOVING.clear();
            if (timer != null) {
                timer.stop();
            }
        }
    }

    /** The {@code fast} duration: hover coming in, small state changes. */
    public static int fast() {
        load();
        return fast;
    }

    /** The {@code medium} duration: hover leaving, releases, opening sections. */
    public static int medium() {
        load();
        return medium;
    }

    /** The {@code slow} duration: emphasis moments. */
    public static int slow() {
        load();
        return slow;
    }

    /** The duration for a hover change: {@link #fast()} coming in, {@link #medium()} going out. */
    public static int hover(boolean entering) {
        return entering ? fast() : medium();
    }

    /**
     * The duration for a button's next state: none while pressed, {@link #fast()} for hover and {@link #medium()} for
     * everything else, so a press shows at once and its release fades.
     */
    public static int buttonState(AbstractButton button) {
        ButtonModel model = button.getModel();
        if (model.isPressed() && model.isArmed()) {
            return 0;
        }
        return model.isRollover() ? fast() : medium();
    }

    /** The token easing at progress {@code t} between 0 and 1. */
    public static float ease(float t) {
        load();
        if (t <= 0) {
            return 0;
        }
        if (t >= 1) {
            return 1;
        }
        return bezier(curve, t);
    }

    /**
     * The value to paint for {@code key} on the component, on its way to {@code target}.
     *
     * @param duration milliseconds a new target takes to reach; 0 jumps at once
     */
    public static float value(JComponent component, Object key, float target, int duration) {
        Track track = track(component, key, false, target, null, duration);
        return track.number(now());
    }

    /** A 0 to 1 value for a flag, such as hover: 1 when on, 0 when off, eased in between. */
    public static float value(JComponent component, Object key, boolean on, int duration) {
        return value(component, key, on ? 1 : 0, duration);
    }

    /**
     * A 0 to 1 value for a hover flag, fast in and lingering out.
     */
    public static float hover(JComponent component, Object key, boolean hovered) {
        return value(component, key, hovered, hover(hovered));
    }

    /**
     * The color to paint for {@code key} on the component, on its way to {@code target}. A null target means nothing
     * painted: the color fades out to transparent and then gives null, and fades in from transparent when a color
     * returns.
     *
     * @param duration milliseconds a new target takes to reach; 0 jumps at once
     */
    public static Color color(JComponent component, Object key, Color target, int duration) {
        Track track = track(component, key, true, 0, target, duration);
        return track.color(now());
    }

    /** Whether the value for {@code key} is still moving. */
    public static boolean isMoving(JComponent component, Object key) {
        Track track = tracks(component).get(key);
        return track != null && now() < track.end();
    }

    /**
     * Drops the value for {@code key}, so the next request starts afresh. For keys made per item, such as table rows,
     * once their value has settled.
     */
    public static void forget(JComponent component, Object key) {
        tracks(component).remove(key);
    }

    /** Mixes two colors, including alpha, {@code amount} of the way from {@code from} to {@code to}. */
    public static Color mix(Color from, Color to, float amount) {
        if (amount <= 0) {
            return from;
        }
        if (amount >= 1) {
            return to;
        }
        return new Color(channel(from.getRed(), to.getRed(), amount),
              channel(from.getGreen(), to.getGreen(), amount),
              channel(from.getBlue(), to.getBlue(), amount),
              channel(from.getAlpha(), to.getAlpha(), amount));
    }

    /** Replaces the clock, in milliseconds, for tests. */
    static void setClock(LongSupplier millis) {
        clock = millis;
    }

    /** Stops every animation and forgets the moving controls, for tests. */
    static void reset() {
        MOVING.clear();
        if (timer != null) {
            timer.stop();
        }
    }

    private static long now() {
        return clock.getAsLong();
    }

    private static Track track(JComponent component, Object key, boolean colored, float number, Color color,
          int duration) {
        Map<Object, Track> tracks = tracks(component);
        Track track = tracks.get(key);
        long now = now();
        if (track == null || track.colored != colored) {
            track = new Track(colored, number, color);
            tracks.put(key, track);
            return track;
        }
        if (track.reaches(number, color)) {
            return track;
        }
        if (reduced || duration <= 0) {
            track.jump(number, color);
        } else {
            track.retarget(number, color, now, duration);
            animate(component, track.end());
        }
        return track;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Track> tracks(JComponent component) {
        Object state = component.getClientProperty(STATE);
        if (state instanceof Map) {
            return (Map<Object, Track>) state;
        }
        Map<Object, Track> tracks = new HashMap<>();
        component.putClientProperty(STATE, tracks);
        return tracks;
    }

    private static void animate(JComponent component, long end) {
        MOVING.merge(component, end, Math::max);
        if (timer == null) {
            timer = new Timer(FRAME, event -> tick());
            timer.setCoalesce(true);
        }
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    private static void tick() {
        long now = now();
        Iterator<Map.Entry<JComponent, Long>> moving = MOVING.entrySet().iterator();
        while (moving.hasNext()) {
            Map.Entry<JComponent, Long> entry = moving.next();
            Component component = entry.getKey();
            component.repaint();
            if (now >= entry.getValue() || !component.isShowing()) {
                moving.remove();
            }
        }
        if (MOVING.isEmpty()) {
            timer.stop();
        }
    }

    private static void load() {
        if (fast >= 0) {
            return;
        }
        DesignTokens tokens = DesignTokens.shipped();
        medium = tokens.duration("medium");
        slow = tokens.duration("slow");
        curve = tokens.easing();
        fast = tokens.duration("fast");
    }

    private static int channel(int from, int to, float amount) {
        return Math.round(from + (to - from) * amount);
    }

    /** CSS cubic-bezier(x1, y1, x2, y2) at progress x: solves the curve's x for its parameter, then returns y. */
    static float bezier(float[] points, float x) {
        float x1 = points[0];
        float y1 = points[1];
        float x2 = points[2];
        float y2 = points[3];
        float low = 0;
        float high = 1;
        float t = x;
        for (int i = 0; i < 24; i++) {
            float guess = curve(x1, x2, t) - x;
            if (Math.abs(guess) < 1e-5f) {
                break;
            }
            if (guess > 0) {
                high = t;
            } else {
                low = t;
            }
            t = (low + high) / 2;
        }
        return curve(y1, y2, t);
    }

    private static float curve(float p1, float p2, float t) {
        float u = 1 - t;
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
    }

    /** One animated value: a number or a color (null for transparent) moving from where it was to its target. */
    private static final class Track {
        private final boolean colored;
        private float fromNumber;
        private float toNumber;
        private Color fromColor;
        private Color toColor;
        private long start;
        private int duration;

        Track(boolean colored, float number, Color color) {
            this.colored = colored;
            jump(number, color);
        }

        boolean reaches(float number, Color color) {
            return colored ? Objects.equals(color, toColor) : toNumber == number;
        }

        void jump(float number, Color color) {
            fromNumber = number;
            toNumber = number;
            fromColor = color;
            toColor = color;
            duration = 0;
        }

        void retarget(float number, Color color, long now, int time) {
            if (colored) {
                fromColor = color(now);
            } else {
                fromNumber = number(now);
            }
            toNumber = number;
            toColor = color;
            start = now;
            duration = time;
        }

        long end() {
            return start + duration;
        }

        float progress(long now) {
            if (duration <= 0 || reduced) {
                return 1;
            }
            return ease(Math.min(1, (now - start) / (float) duration));
        }

        float number(long now) {
            float progress = progress(now);
            return fromNumber + (toNumber - fromNumber) * progress;
        }

        Color color(long now) {
            float progress = progress(now);
            if (progress >= 1 || fromColor == null && toColor == null) {
                return toColor;
            }
            Color from = fromColor != null ? fromColor : clear(toColor);
            Color to = toColor != null ? toColor : clear(fromColor);
            return mix(from, to, progress);
        }

        private static Color clear(Color color) {
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        }
    }
}
