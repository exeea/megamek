/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.plaf.LayerUI;

import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.Vision;

/** Paints its view through a color vision deficiency simulation, so reviewers can see what affected players see. */
final class VisionLayerUI extends LayerUI<JComponent> {
    private Vision vision = Vision.NORMAL;
    private final Map<Integer, Integer> cache = new HashMap<>();

    void setVision(Vision vision, JComponent layer) {
        this.vision = vision;
        cache.clear();
        layer.repaint();
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
        if (vision == Vision.NORMAL || component.getWidth() <= 0 || component.getHeight() <= 0) {
            super.paint(graphics, component);
            return;
        }
        // Paint at the device scale so the simulated view stays as sharp as the normal one on HiDPI screens.
        AffineTransform transform = ((Graphics2D) graphics).getTransform();
        double scaleX = Math.max(1, transform.getScaleX());
        double scaleY = Math.max(1, transform.getScaleY());
        int width = (int) Math.ceil(component.getWidth() * scaleX);
        int height = (int) Math.ceil(component.getHeight() * scaleY);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D offscreen = image.createGraphics();
        try {
            offscreen.scale(scaleX, scaleY);
            super.paint(offscreen, component);
        } finally {
            offscreen.dispose();
        }
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            if ((pixel >>> 24) != 0) {
                pixels[index] = cache.computeIfAbsent(pixel, color -> ColorMath.simulate(color, vision));
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        graphics.drawImage(image, 0, 0, component.getWidth(), component.getHeight(), null);
    }
}
