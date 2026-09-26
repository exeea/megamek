/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Draws a real Atlas the way the unit readout does, through the hidden window (no board window is open), from the
 * front and from its left side. Saves both pictures next to the other GPU review images for a visual check.
 */
@Tag("on-demand")
class GpuUnitPortraitSmokeTest {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 420;
    private static final int BACKGROUND = 0x404040;

    @Test
    void readoutDrawsTheAtlasFromAnyAngleThroughTheHiddenWindow() throws Exception {
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        AtomicReference<GpuUnitPortraits.Subject> subject = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> subject.set(GpuUnitPortraits.capture(atlas)));
        assertNotNull(subject.get(), "The Atlas has a model in the mekset, so it must be captured");

        try {
            BufferedImage front = draw(subject.get(), UnitPortraitAngle.DEFAULT);
            BufferedImage side = draw(subject.get(), UnitPortraitAngle.DEFAULT.turned(90, 0));
            BufferedImage otherFront = draw(subject.get(), new UnitPortraitAngle(-35, 20));
            BufferedImage above = draw(subject.get(), new UnitPortraitAngle(0, UnitPortraitAngle.MAX_PITCH));
            save(front, "unit-portrait-atlas-front");
            save(side, "unit-portrait-atlas-left");
            save(otherFront, "unit-portrait-atlas-front-right");
            save(above, "unit-portrait-atlas-above");
            // Zoomed in on the head (the upper part of the picture), and on the middle of the left side.
            BufferedImage headOnClose = draw(subject.get(), new UnitPortraitAngle(0, 10),
                  new UnitPortraitZoom(UnitPortraitZoom.MAX_FACTOR, 0, 0.6f));
            BufferedImage sideClose = draw(subject.get(), new UnitPortraitAngle(90, 10),
                  new UnitPortraitZoom(UnitPortraitZoom.MAX_FACTOR, 0, 0));
            save(headOnClose, "unit-portrait-atlas-zoomed-front");
            save(sideClose, "unit-portrait-atlas-zoomed-left");

            assertTrue(unitPixels(front) > (WIDTH * HEIGHT) / 20, "The Atlas must cover part of the picture");
            assertTrue(unitPixels(side) > (WIDTH * HEIGHT) / 20, "The Atlas must cover part of the side view");
            assertFalse(samePicture(front, side), "Turning the camera must change the picture");
            // A close-up of the head still has sky above it, so it covers more of the picture, not all of it.
            assertTrue(unitPixels(headOnClose) > unitPixels(front), "Zooming in must make the Atlas larger");
            assertTrue(unitPixels(sideClose) > unitPixels(side), "Zooming in on the side must make it larger too");
        } finally {
            SwingUtilities.invokeAndWait(GpuUnitPortraits::releaseForBoard);
        }
    }

    /**
     * Vehicles, Battle Armor, infantry, VTOLs and large craft are built by other assemblers than Meks; each must still
     * be drawn.
     */
    @ParameterizedTest
    @ValueSource(strings = { "Bulldog Medium Tank.blk", "Elemental BA [Laser] (Sqd5).blk",
                             "Foot Platoon (AFFS) (Laser 3067+).blk", "Cobra Transport VTOL.blk",
                             "Explorer JumpShip.blk" })
    void readoutDrawsOtherUnitFamilies(String unitFile) throws Exception {
        Entity unit = new MekFileParser(new File("testresources/megamek/common/units/" + unitFile)).getEntity();
        AtomicReference<GpuUnitPortraits.Subject> subject = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> subject.set(GpuUnitPortraits.capture(unit)));
        assertNotNull(subject.get(), unitFile + " has a model in the mekset, so it must be captured");

        try {
            BufferedImage picture = draw(subject.get(), UnitPortraitAngle.DEFAULT);
            save(picture, "unit-portrait-" + unitFile.replaceAll("[^A-Za-z0-9]+", "-"));

            // Squads spread over a hex and a VTOL's thin rotor-framed body cover little of the picture; this only
            // proves the unit was drawn at all. The Atlas test checks the framing of a solid unit.
            assertTrue(unitPixels(picture) > (WIDTH * HEIGHT) / 100, unitFile + " must be drawn in the picture");
        } finally {
            SwingUtilities.invokeAndWait(GpuUnitPortraits::releaseForBoard);
        }
    }

    private static BufferedImage draw(GpuUnitPortraits.Subject subject, UnitPortraitAngle angle) throws Exception {
        return draw(subject, angle, UnitPortraitZoom.DEFAULT);
    }

    private static BufferedImage draw(GpuUnitPortraits.Subject subject, UnitPortraitAngle angle, UnitPortraitZoom zoom)
          throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        AtomicReference<GpuUnitPortraits.Result> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> GpuUnitPortraits.request(subject, angle, zoom, WIDTH, HEIGHT, BACKGROUND,
              answer -> {
                  result.set(answer);
                  answered.countDown();
              }));
        assertTrue(answered.await(60, TimeUnit.SECONDS), "No picture came back from the hidden window");
        assertNotNull(result.get().image(), "The hidden window could not draw the Atlas; see megamek.log");
        return result.get().image();
    }

    /** Counts the pixels that are not the background, which is roughly the unit's size in the picture. */
    private static int unitPixels(BufferedImage image) {
        int count = 0;
        for (int row = 0; row < image.getHeight(); row++) {
            for (int column = 0; column < image.getWidth(); column++) {
                if ((image.getRGB(column, row) & 0xFFFFFF) != BACKGROUND) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean samePicture(BufferedImage first, BufferedImage second) {
        for (int row = 0; row < first.getHeight(); row++) {
            for (int column = 0; column < first.getWidth(); column++) {
                if (first.getRGB(column, row) != second.getRGB(column, row)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void save(BufferedImage image, String name) throws Exception {
        String directory = System.getProperty("megamek.gpu.screenshots");
        if (directory != null) {
            File output = new File(directory);
            output.mkdirs();
            ImageIO.write(image, "png", new File(output, name + ".png"));
        }
    }
}
