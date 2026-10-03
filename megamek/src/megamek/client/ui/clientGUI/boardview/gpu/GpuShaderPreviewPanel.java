/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Swing owns the controls; the render thread reads an immutable request and publishes a finished image. */
final class GpuShaderPreviewPanel extends JPanel {
    private final JComboBox<GpuShaderPreview.Preset> presets = new JComboBox<>(Arrays.stream(GpuShaderPreview.Preset.values())
          .filter(preset -> preset != GpuShaderPreview.Preset.NONE).toArray(GpuShaderPreview.Preset[]::new));
    private final JCheckBox play = new JCheckBox("Play", true);
    private final JButton replay = new JButton("Restart");
    private final JComboBox<String> speed = new JComboBox<>(new String[] { "0.25×", "0.5×", "1×", "2×" });
    private final JLabel caption = new JLabel("Effect preview");
    private final AtomicReference<GpuShaderPreview.Frame> incoming = new AtomicReference<>();
    private final Timer refresh;
    private final JPanel canvas;
    private GpuShaderPreview.Frame displayed;
    private String file;
    private float yaw = -75, pitch = 18, zoom = 1;
    private int restart;
    private volatile GpuShaderPreview.Settings requested;

    GpuShaderPreviewPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createTitledBorder("Object / effect preview"));
        setMinimumSize(new Dimension(230, 220));
        var selection = new JPanel(new BorderLayout(4, 0));
        selection.add(presets, BorderLayout.CENTER);
        add(selection, BorderLayout.NORTH);
        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                var frame = displayed;
                if (frame != null && frame.image() != null) {
                    var image = frame.image();
                    double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
                    int width = (int) (image.getWidth() * scale), height = (int) (image.getHeight() * scale);
                    var copy = (Graphics2D) graphics.create();
                    try {
                        copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        copy.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
                    } finally { copy.dispose(); }
                } else {
                    graphics.setColor(Color.LIGHT_GRAY);
                    String message = frame == null ? "Preparing preview…" : frame.message();
                    int line = 24;
                    for (String part : message.split("\n")) { graphics.drawString(part, 10, line); line += 18; }
                }
            }
        };
        canvas.setBackground(new Color(14, 17, 22));
        canvas.setPreferredSize(new Dimension(320, 200));
        canvas.setToolTipText("Drag to orbit. Wheel to zoom. Double-click to reset the view.");
        add(canvas, BorderLayout.CENTER);
        var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controls.add(play);
        controls.add(replay);
        speed.setSelectedIndex(1);
        controls.add(speed);
        var footer = new JPanel(new BorderLayout(0, 3));
        footer.add(controls, BorderLayout.NORTH);
        footer.add(caption, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        presets.addActionListener(event -> { restart++; displayed = null; publish(); canvas.repaint(); });
        play.addActionListener(event -> publish());
        speed.addActionListener(event -> publish());
        replay.addActionListener(event -> { restart++; publish(); });
        var orbit = new MouseAdapter() {
            private int x, y;
            @Override
            public void mousePressed(MouseEvent event) { x = event.getX(); y = event.getY(); }
            @Override
            public void mouseDragged(MouseEvent event) {
                yaw += (event.getX() - x) * .5f;
                pitch = Math.clamp(pitch + (event.getY() - y) * .4f, -10, 80);
                x = event.getX(); y = event.getY(); publish();
            }
            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                zoom = Math.clamp(zoom * (float) Math.pow(1.12, event.getPreciseWheelRotation()), .3f, 3);
                event.consume(); publish();
            }
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) { yaw = -75; pitch = 18; zoom = 1; publish(); }
            }
        };
        canvas.addMouseListener(orbit);
        canvas.addMouseMotionListener(orbit);
        canvas.addMouseWheelListener(orbit);
        refresh = new Timer(33, event -> {
            var frame = incoming.getAndSet(null);
            if (frame != null) {
                displayed = frame;
                caption.setText(frame.preset() == GpuShaderPreview.Preset.NONE ? "Scene shader · use the live board" : frame.preset().title);
                caption.setToolTipText(frame.message());
                canvas.repaint();
            }
        });
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                publish();
                if (isShowing()) { refresh.start(); } else { refresh.stop(); }
            }
        });
        publish();
    }

    void select(String name) {
        if (java.util.Objects.equals(file, name)) { return; }
        file = name;
        displayed = null;
        publish();
        canvas.repaint();
    }

    private void publish() {
        requested = new GpuShaderPreview.Settings(file, (GpuShaderPreview.Preset) presets.getSelectedItem(), isShowing(),
              play.isSelected(), new float[] { .25f, .5f, 1, 2 }[speed.getSelectedIndex()], yaw, pitch, zoom, restart,
              GpuShaderPreview.Inputs.DEFAULT);
    }

    GpuShaderPreview.Settings settings() { return requested; }
    void receive(GpuShaderPreview.Frame frame) { incoming.set(frame); }
    void close() { refresh.stop(); incoming.set(null); }
}
