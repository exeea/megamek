/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * A print's JPEG, written a band of rows at a time, so no whole print is held in memory. The JDK's JPEG writer encodes
 * each band with the same tables, and restart markers join the bands' scans into one baseline JPEG: a restart resets the
 * encoder's state, so a band encodes as it would inside the whole picture. Quality 0.92 without chroma subsampling; the
 * JFIF density keeps the hex size in print programs that honour it. Closing it before every row is written deletes the
 * unfinished file.
 */
final class GpuPrintJpeg implements Closeable {
    /** The JPEG writer's (libjpeg's) largest side. */
    static final int MAX_SIDE = 65500;
    private static final String FORMAT = "javax_imageio_jpeg_image_1.0";
    private final Path target;
    private final int width, height, dotsPerInchX, dotsPerInchY;
    /** Rows per restart interval: a power of two from 8 to 1024, so that a 1024-row band holds whole intervals. */
    private final int interval;
    private final ImageWriter writer;
    private final ImageWriteParam parameters;
    private IIOMetadata metadata;
    private OutputStream output;
    private int rows, restarts;

    GpuPrintJpeg(Path target, int width, int height, int dotsPerInchX, int dotsPerInchY) throws IOException {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
            throw new IllegalArgumentException("A JPEG holds 1 to " + MAX_SIDE + " pixels a side: " + width + " x " + height);
        }
        if (dotsPerInchX <= 0 || dotsPerInchY <= 0) { throw new IllegalArgumentException("Print resolution must be positive"); }
        this.target = target;
        this.width = width;
        this.height = height;
        this.dotsPerInchX = dotsPerInchX;
        this.dotsPerInchY = dotsPerInchY;
        // An interval of 8 x 8 blocks (no chroma subsampling) counts at most 65,535 of them, a 16-bit marker field.
        int rowsPerInterval = 1024;
        while ((width + 7) / 8 * (rowsPerInterval / 8) > 65535) { rowsPerInterval /= 2; }
        interval = rowsPerInterval;
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) { throw new IOException("No JPEG writer"); }
        writer = writers.next();
        parameters = writer.getDefaultWriteParam();
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameters.setCompressionQuality(.92f);
        output = new BufferedOutputStream(Files.newOutputStream(target));
    }

    /** The next rows, at the full width. Only the last band may end within a restart interval. */
    void write(BufferedImage band) throws IOException {
        if (band.getWidth() != width || rows + band.getHeight() > height) {
            throw new IllegalArgumentException("Band " + band.getWidth() + " x " + band.getHeight() + " after row " + rows
                  + " does not fit " + width + " x " + height);
        }
        for (int top = 0; top < band.getHeight(); top += interval) {
            int count = Math.min(interval, band.getHeight() - top);
            if (count < interval && rows + count < height) {
                throw new IllegalArgumentException("Only the picture's last rows may end within a restart interval");
            }
            byte[] encoded = encode(band.getSubimage(0, top, width, count));
            int scan = startOfScan(encoded);
            int data = scan + 2 + ((encoded[scan + 2] & 0xFF) << 8 | encoded[scan + 3] & 0xFF);
            if (rows == 0) {
                // The first band's header, with the whole height, and the restart interval before its scan.
                output.write(encoded, 0, scan);
                int blocks = (width + 7) / 8 * (interval / 8);
                output.write(new byte[] { (byte) 0xFF, (byte) 0xDD, 0, 4, (byte) (blocks >> 8), (byte) blocks });
                output.write(encoded, scan, data - scan);
            } else {
                output.write(0xFF);
                output.write(0xD0 | restarts++ & 7);
            }
            // The entropy-coded scan, without the band's end-of-image marker.
            output.write(encoded, data, encoded.length - 2 - data);
            rows += count;
        }
    }

    private byte[] encode(BufferedImage band) throws IOException {
        if (metadata == null) { metadata = metadata(band); }
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(band, null, metadata), parameters);
        }
        byte[] result = bytes.toByteArray();
        if (result.length < 4 || result[result.length - 2] != (byte) 0xFF || result[result.length - 1] != (byte) 0xD9) {
            throw new IOException("The JPEG writer ended a band without its end-of-image marker");
        }
        return result;
    }

    private IIOMetadata metadata(BufferedImage band) throws IOException {
        var result = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(band), parameters);
        var root = (IIOMetadataNode) result.getAsTree(FORMAT);
        var density = (IIOMetadataNode) root.getElementsByTagName("app0JFIF").item(0);
        density.setAttribute("resUnits", "1");
        density.setAttribute("Xdensity", Integer.toString(dotsPerInchX));
        density.setAttribute("Ydensity", Integer.toString(dotsPerInchY));
        // Full-resolution colour: thin coloured lines and label outlines keep their colour edges.
        var components = root.getElementsByTagName("componentSpec");
        for (int index = 0; index < components.getLength(); index++) {
            var component = (IIOMetadataNode) components.item(index);
            component.setAttribute("HsamplingFactor", "1");
            component.setAttribute("VsamplingFactor", "1");
        }
        result.setFromTree(FORMAT, root);
        return result;
    }

    /** The start-of-scan marker's offset in a band's JPEG, after setting its frame to the whole picture's height. */
    private int startOfScan(byte[] jpeg) throws IOException {
        for (int at = 2; at + 4 <= jpeg.length && jpeg[at] == (byte) 0xFF; ) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA) { return at; }
            if (marker == 0xC0 || marker == 0xC1) {
                jpeg[at + 5] = (byte) (height >> 8);
                jpeg[at + 6] = (byte) height;
            }
            at += 2 + ((jpeg[at + 2] & 0xFF) << 8 | jpeg[at + 3] & 0xFF);
        }
        throw new IOException("The JPEG writer wrote no sequential scan");
    }

    @Override
    public void close() throws IOException {
        if (output == null) { return; }
        try {
            if (rows == height) {
                output.write(0xFF);
                output.write(0xD9);
            }
            output.close();
        } finally {
            output = null;
            writer.dispose();
            if (rows < height) { Files.deleteIfExists(target); }
        }
    }
}
