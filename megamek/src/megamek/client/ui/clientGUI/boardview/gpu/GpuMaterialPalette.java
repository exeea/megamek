/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.badlogic.gdx.Gdx;

/**
 * Tuning → Terrain → Material palette: every board material ({@link GpuMaterialMaps#boardTextures}) side by side, each
 * on a flat tile, a cube or a sphere as the game draws it (GpuMaterialSwatches). Swing, on the EDT; the render thread
 * draws a few swatches a frame. Each opening rereads the folder.
 */
final class GpuMaterialPalette {
    private static final int COLUMNS = 6, PER_FRAME = 4;
    private static final String BYTES = "bytes", MODIFIED = "modified";
    private static GpuMaterialPalette shown;

    /** How the swatches are laid out; an order by look ({@code drawn}) sorts the drawn ones, the rest follow by folder. */
    private enum Order {
        FOLDER("Folder", false), SIMILARITY("Similarity", true), BRIGHTNESS("Brightness", true), SIZE("Map size", false),
        CHANGED("Recently changed", false);

        private final String title;
        private final boolean drawn;
        Order(String title, boolean drawn) { this.title = title; this.drawn = drawn; }
        @Override
        public String toString() { return title; }
    }

    private final JFrame window = new JFrame("MegaMek — Material palette");
    private final JComboBox<GpuMaterialSwatches.Shape> shape = new JComboBox<>(GpuMaterialSwatches.Shape.values());
    private final JComboBox<Order> order = new JComboBox<>(Order.values());
    private final JLabel status = new JLabel();
    private final JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 6, 6));
    /** The current pass's swatches, by name. */
    private final List<JLabel> labels = new ArrayList<>();
    /** The current render pass; a newer one (another shape, a reopening) or closing the window ends the older. */
    private final AtomicInteger pass = new AtomicInteger();
    /** The board's Reload assets, run on the render thread; null without a board. */
    private Runnable reloadAssets;

    /** Opens the palette, or brings it to the front, and draws every material on disk. */
    static void show(Runnable reloadAssets) {
        if (shown == null) { shown = new GpuMaterialPalette(); }
        shown.reloadAssets = reloadAssets;
        shown.window.setVisible(true);
        shown.window.toFront();
        shown.render();
    }

    private GpuMaterialPalette() {
        window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { pass.incrementAndGet(); }
        });
        shape.addActionListener(event -> render());
        order.setToolTipText("<html>Folder: by path. Similarity: each swatch next to the drawn swatch it looks most "
              + "like.<br>Brightness: darkest first, as drawn. Map size: most bytes on disk first. Recently changed: "
              + "newest file first.</html>");
        order.addActionListener(event -> arrange());
        JButton reload = new JButton("Reload assets");
        reload.setToolTipText("Reload the board's assets from disk, as Tuning's Reload assets does, and draw the "
              + "palette again from the files on disk.");
        reload.addActionListener(event -> {
            if (reloadAssets != null) { Gdx.app.postRunnable(reloadAssets); }
            render();
        });
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        toolbar.add(new JLabel("Shape"));
        toolbar.add(shape);
        toolbar.add(new JLabel("Order"));
        toolbar.add(order);
        toolbar.add(reload);
        toolbar.add(status);
        JScrollPane scroll = new JScrollPane(grid);
        scroll.getVerticalScrollBar().setUnitIncrement(32);
        window.add(toolbar, BorderLayout.NORTH);
        window.add(scroll, BorderLayout.CENTER);
        window.setSize(COLUMNS * (GpuMaterialSwatches.SIZE + 14) + 40, 900);
        window.setLocationByPlatform(true);
    }

    private void render() {
        List<String> names = materials();
        List<JLabel> cells = new ArrayList<>();
        for (String name : names) {
            JLabel label = new JLabel(name.substring(name.lastIndexOf('/') + 1), SwingConstants.CENTER);
            files(label, name);
            label.setVerticalTextPosition(SwingConstants.BOTTOM);
            label.setHorizontalTextPosition(SwingConstants.CENTER);
            label.setPreferredSize(new Dimension(GpuMaterialSwatches.SIZE + 8, GpuMaterialSwatches.SIZE + 24));
            cells.add(label);
        }
        labels.clear();
        labels.addAll(cells);
        arrange();
        int number = pass.incrementAndGet();
        var chosen = (GpuMaterialSwatches.Shape) shape.getSelectedItem();
        status.setText("Drawing " + names.size() + " materials…");
        Gdx.app.postRunnable(new Runnable() {
            private GpuMaterialSwatches swatches;
            private int next;

            @Override
            public void run() {
                if (pass.get() != number) { dispose(); return; }
                if (swatches == null) { swatches = new GpuMaterialSwatches(); }
                for (int end = Math.min(names.size(), next + PER_FRAME); next < end; next++) {
                    String name = names.get(next);
                    JLabel label = cells.get(next);
                    try {
                        var icon = new ImageIcon(swatches.render(name, chosen));
                        SwingUtilities.invokeLater(() -> label.setIcon(icon));
                    } catch (RuntimeException failure) {
                        SwingUtilities.invokeLater(() -> label.setToolTipText(name + ": " + failure.getMessage()));
                    }
                }
                int drawn = next;
                SwingUtilities.invokeLater(() -> {
                    if (pass.get() != number) { return; }
                    status.setText(drawn + " of " + names.size() + " materials");
                    if (drawn == names.size() && ((Order) order.getSelectedItem()).drawn) { arrange(); }
                });
                if (next < names.size()) { Gdx.app.postRunnable(this); } else { dispose(); }
            }

            private void dispose() {
                if (swatches != null) { swatches.dispose(); swatches = null; }
            }
        });
    }

    /** Lays the swatches out in the chosen order (a pass re-sorts when it ends, for an order by look). */
    private void arrange() {
        grid.removeAll();
        (switch ((Order) order.getSelectedItem()) {
            case FOLDER -> labels;
            case SIMILARITY -> similar(labels);
            case BRIGHTNESS -> brightness(labels);
            case SIZE -> labels.stream().sorted(Comparator.comparingLong(label -> -(long) label.getClientProperty(BYTES)))
                  .toList();
            case CHANGED -> labels.stream()
                  .sorted(Comparator.comparingLong(label -> -(long) label.getClientProperty(MODIFIED))).toList();
        }).forEach(grid::add);
        grid.revalidate();
        grid.repaint();
    }

    /**
     * The material's files on disk, its {@code .tres} and every {@code <name>_*.png} beside it: their bytes and newest
     * change for the orders, and with the albedo's size in its tooltip.
     */
    private static void files(JLabel label, String name) {
        File resource = new File(GpuMaterialMaps.boardTextures(), name + ".tres");
        String stem = resource.getName().substring(0, resource.getName().length() - ".tres".length());
        File[] maps = resource.getParentFile().listFiles((folder, file) -> file.startsWith(stem + "_")
              && file.endsWith(".png"));
        long bytes = resource.length(), modified = resource.lastModified();
        for (File map : maps == null ? new File[0] : maps) {
            bytes += map.length();
            modified = Math.max(modified, map.lastModified());
        }
        File albedo = new File(resource.getParentFile(), stem + "_albedo.png");
        String size = albedo.isFile() ? GpuMaterialMaps.size(albedo)[0] + " px albedo, " : "";
        label.putClientProperty(BYTES, bytes);
        label.putClientProperty(MODIFIED, modified);
        String amount = bytes < 1_000_000 ? (bytes + 500) / 1000 + " KB"
              : String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
        label.setToolTipText(String.format(Locale.ROOT, "<html>%s<br>%s%s in %d maps, changed %tF %<tR</html>", name,
              size, amount, maps == null ? 0 : maps.length, modified));
    }

    /**
     * The drawn swatches from the darkest to the brightest: the mean luminance of the material's own pixels, without
     * the backdrop (the swatch's corner colour) that a cutout or a sphere leaves; the rest follow.
     */
    private static List<JLabel> brightness(List<JLabel> labels) {
        Map<JLabel, Double> brightness = new LinkedHashMap<>();
        for (JLabel label : labels) {
            if (label.getIcon() instanceof ImageIcon icon && icon.getImage() instanceof BufferedImage image) {
                int backdrop = image.getRGB(0, 0), count = 0;
                double sum = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int rgb = image.getRGB(x, y);
                        if (rgb == backdrop) { continue; }
                        sum += .299 * (rgb >> 16 & 255) + .587 * (rgb >> 8 & 255) + .114 * (rgb & 255);
                        count++;
                    }
                }
                brightness.put(label, count == 0 ? 0 : sum / count);
            }
        }
        List<JLabel> result = new ArrayList<>(brightness.keySet().stream()
              .sorted(Comparator.comparingDouble(brightness::get)).toList());
        labels.stream().filter(label -> !brightness.containsKey(label)).forEach(result::add);
        return result;
    }

    /**
     * The drawn swatches as one chain from the darkest, each followed by the remaining one it looks most like (nearest
     * {@link #look}); swatches not drawn yet follow by folder.
     */
    private static List<JLabel> similar(List<JLabel> labels) {
        Map<JLabel, float[]> looks = new LinkedHashMap<>();
        List<JLabel> undrawn = new ArrayList<>();
        for (JLabel label : labels) {
            if (label.getIcon() instanceof ImageIcon icon && icon.getImage() instanceof BufferedImage image) {
                looks.put(label, look(image));
            } else { undrawn.add(label); }
        }
        List<JLabel> chain = new ArrayList<>();
        JLabel last = looks.keySet().stream().min(Comparator.comparingDouble(label -> sum(looks.get(label)))).orElse(null);
        while (last != null) {
            float[] previous = looks.remove(last);
            chain.add(last);
            last = looks.keySet().stream().min(Comparator.comparingDouble(label -> distance(previous, looks.get(label))))
                  .orElse(null);
        }
        chain.addAll(undrawn);
        return chain;
    }

    /** A swatch's look: the mean colour and the luminance contrast of each cell of a 4 x 4 grid over its image. */
    private static float[] look(BufferedImage image) {
        int cells = 4, cell = image.getWidth() / cells;
        float[] look = new float[cells * cells * 4];
        for (int index = 0; index < cells * cells; index++) {
            double r = 0, g = 0, b = 0, luminance = 0, square = 0;
            int count = 0;
            for (int y = index / cells * cell; y < (index / cells + 1) * cell; y++) {
                for (int x = index % cells * cell; x < (index % cells + 1) * cell; x++) {
                    int rgb = image.getRGB(x, y);
                    double red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                    double value = .299 * red + .587 * green + .114 * blue;
                    r += red; g += green; b += blue; luminance += value; square += value * value;
                    count++;
                }
            }
            double mean = luminance / count;
            look[index * 4] = (float) (r / count);
            look[index * 4 + 1] = (float) (g / count);
            look[index * 4 + 2] = (float) (b / count);
            look[index * 4 + 3] = (float) Math.sqrt(Math.max(0, square / count - mean * mean));
        }
        return look;
    }

    private static double sum(float[] look) {
        double sum = 0;
        for (float value : look) { sum += value; }
        return sum;
    }

    private static double distance(float[] a, float[] b) {
        double sum = 0;
        for (int index = 0; index < a.length; index++) { sum += (a[index] - b[index]) * (a[index] - b[index]); }
        return sum;
    }

    /** Every {@code .tres} under the board's material folder, by its path there without the extension. */
    private static List<String> materials() {
        Path root = GpuMaterialMaps.boardTextures().toPath();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".tres"))
                  .map(path -> root.relativize(path).toString().replace('\\', '/'))
                  .map(path -> path.substring(0, path.length() - ".tres".length())).sorted().toList();
        } catch (IOException failure) {
            return List.of();
        }
    }
}
