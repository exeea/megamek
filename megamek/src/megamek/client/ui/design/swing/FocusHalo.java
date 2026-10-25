/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyBoundsAdapter;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.DesignTokens;

/**
 * The focus ring of a cream face (docs/design-system/tokens.md, Focus): {@code focus.width} of
 * {@code Component.focusColor} a {@code focus.gap} outside the face, as around a check box. Swing clips a component's
 * painting to its bounds, so the ring is drawn by this overlay in the root pane's layered pane, above the content and
 * below popups. A focused control asks for it from its paint method with {@link #around} and paints its own inside
 * ring instead when that returns false: where the ring would leave a scroll pane's view, or the control is in a popup
 * or another layer above the content. The overlay leaves the layered pane once no control needs it.
 */
final class FocusHalo extends JComponent {
    private static final String KEY = FocusHalo.class.getName();

    private final JLayeredPane layers;
    /** The faces with a ring, each in its owner's coordinates. */
    private final Map<JComponent, RoundRectangle2D> faces = new WeakHashMap<>();
    /** The ring bounds last painted for each owner, in this overlay's coordinates. */
    private final Map<JComponent, Rectangle> painted = new WeakHashMap<>();
    private final HierarchyBoundsAdapter follow = new HierarchyBoundsAdapter() {
        @Override
        public void ancestorMoved(HierarchyEvent event) {
            follow(event.getComponent());
        }

        @Override
        public void ancestorResized(HierarchyEvent event) {
            follow(event.getComponent());
        }
    };
    private final ComponentAdapter moved = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent event) {
            follow(event.getComponent());
        }

        @Override
        public void componentResized(ComponentEvent event) {
            follow(event.getComponent());
        }

        @Override
        public void componentHidden(ComponentEvent event) {
            if (event.getComponent() instanceof JComponent owner) {
                clear(owner);
            }
        }
    };
    private final ComponentAdapter layersResized = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            setBounds(0, 0, layers.getWidth(), layers.getHeight());
        }
    };

    private FocusHalo(JLayeredPane layers) {
        this.layers = layers;
        setOpaque(false);
        setFocusable(false);
        setBounds(0, 0, layers.getWidth(), layers.getHeight());
    }

    /**
     * Rings {@code face} (in {@code owner}'s coordinates, its arc a diameter as in FlatLaf) from outside, if the ring
     * fits; call it while painting a focused cream face. Returns false, and drops any ring of {@code owner}, when it
     * does not fit: the owner then paints its inside ring.
     */
    static boolean around(JComponent owner, RoundRectangle2D face) {
        JRootPane root = SwingUtilities.getRootPane(owner);
        if (root == null || !owner.isShowing() || !fits(owner, face, root.getLayeredPane())) {
            clear(owner);
            return false;
        }
        halo(root).put(owner, face);
        return true;
    }

    /** Drops {@code owner}'s ring; call it while painting a face that is not focused or not cream. */
    static void clear(JComponent owner) {
        JRootPane root = SwingUtilities.getRootPane(owner);
        Object halo = root == null ? null : root.getClientProperty(KEY);
        if (halo instanceof FocusHalo focusHalo) {
            focusHalo.remove(owner);
        }
    }

    /** The space the ring takes outside the face: {@code focus.width} plus {@code focus.gap}, scaled. */
    static float room() {
        return width() + gap();
    }

    private static float width() {
        return UIScale.scale(DesignTokens.shipped().size().get("focus.width"));
    }

    private static float gap() {
        return UIScale.scale(DesignTokens.shipped().size().get("focus.gap"));
    }

    /**
     * Whether the ring around {@code face} shows whole: inside every scroll pane view around {@code owner}, and drawn
     * over the content layer rather than a popup or palette above it.
     */
    private static boolean fits(JComponent owner, RoundRectangle2D face, JLayeredPane layers) {
        Rectangle ring = ring(face);
        for (Component child = owner; child != null; child = child.getParent()) {
            Component parent = child.getParent();
            if (parent == layers) {
                return child instanceof JComponent top && JLayeredPane.getLayer(top) < JLayeredPane.PALETTE_LAYER;
            }
            if (parent instanceof JViewport viewport) {
                Rectangle inView = SwingUtilities.convertRectangle(owner, ring, viewport);
                if (!new Rectangle(0, 0, viewport.getWidth(), viewport.getHeight()).contains(inView)) {
                    return false;
                }
            }
        }
        return false;
    }

    /** The bounds of the ring around {@code face}, in the face's coordinates. */
    private static Rectangle ring(RoundRectangle2D face) {
        float room = room();
        int x = (int) Math.floor(face.getX() - room);
        int y = (int) Math.floor(face.getY() - room);
        return new Rectangle(x, y, (int) Math.ceil(face.getX() + face.getWidth() + room) - x,
              (int) Math.ceil(face.getY() + face.getHeight() + room) - y);
    }

    private static FocusHalo halo(JRootPane root) {
        Object existing = root.getClientProperty(KEY);
        if (existing instanceof FocusHalo halo) {
            return halo;
        }
        FocusHalo halo = new FocusHalo(root.getLayeredPane());
        root.putClientProperty(KEY, halo);
        return halo;
    }

    private void put(JComponent owner, RoundRectangle2D face) {
        RoundRectangle2D old = faces.put(owner, face);
        if (old == null) {
            owner.addHierarchyBoundsListener(follow);
            owner.addComponentListener(moved);
            attach();
        }
        if (old == null || !old.equals(face)) {
            follow(owner);
        }
    }

    private void remove(JComponent owner) {
        if (faces.remove(owner) == null) {
            return;
        }
        owner.removeHierarchyBoundsListener(follow);
        owner.removeComponentListener(moved);
        Rectangle old = painted.remove(owner);
        if (old != null) {
            repaint(old);
        }
        if (faces.isEmpty()) {
            detach();
        }
    }

    /** Repaints where {@code component}'s ring was and where it is now, if it moved. */
    private void follow(Component component) {
        if (!(component instanceof JComponent owner) || !faces.containsKey(owner)) {
            return;
        }
        Rectangle now = owner.isShowing() ? SwingUtilities.convertRectangle(owner, ring(faces.get(owner)), layers)
              : null;
        Rectangle old = now == null ? painted.remove(owner) : painted.put(owner, now);
        if (old != null && !old.equals(now)) {
            repaint(old);
        }
        if (now != null && !now.equals(old)) {
            repaint(now);
        }
    }

    /** Joins the layered pane after the paint that asked for a ring; a paint must not change the hierarchy. */
    private void attach() {
        SwingUtilities.invokeLater(() -> {
            if (!faces.isEmpty() && getParent() != layers) {
                setBounds(0, 0, layers.getWidth(), layers.getHeight());
                layers.add(this, JLayeredPane.PALETTE_LAYER);
                layers.addComponentListener(layersResized);
                painted.keySet().forEach(owner -> repaint(painted.get(owner)));
            }
        });
    }

    private void detach() {
        SwingUtilities.invokeLater(() -> {
            if (faces.isEmpty() && getParent() == layers) {
                layers.removeComponentListener(layersResized);
                layers.remove(this);
            }
        });
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (faces.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            FlatUIUtils.setRenderingHints(g);
            Color color = UIManager.getColor("Component.focusColor");
            g.setColor(color == null ? Color.WHITE : color);
            float width = width();
            float gap = gap();
            for (Map.Entry<JComponent, RoundRectangle2D> entry : faces.entrySet()) {
                JComponent owner = entry.getKey();
                if (owner == null || !owner.isShowing() || SwingUtilities.getRootPane(owner) != getRootPane()) {
                    continue;
                }
                RoundRectangle2D face = entry.getValue();
                Point at = SwingUtilities.convertPoint(owner, 0, 0, this);
                float x = (float) face.getX() + at.x;
                float y = (float) face.getY() + at.y;
                float arc = (float) face.getArcWidth();
                Area ring = new Area(grown(x, y, face, arc, gap + width));
                ring.subtract(new Area(grown(x, y, face, arc, gap)));
                g.fill(ring);
            }
        } finally {
            g.dispose();
        }
    }

    /** {@code face} at ({@code x}, {@code y}) grown by {@code by} on every side; a rounded arc grows with it. */
    private static RoundRectangle2D grown(float x, float y, RoundRectangle2D face, float arc, float by) {
        float grownArc = arc > 0 ? arc + 2 * by : 0;
        return new RoundRectangle2D.Float(x - by, y - by, (float) face.getWidth() + 2 * by,
              (float) face.getHeight() + 2 * by, grownArc, grownArc);
    }
}
