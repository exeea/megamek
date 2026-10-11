/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuTreeInstancesTest {
    /** The placed object's drawn transform, as the terrain builds it. */
    private static com.badlogic.gdx.math.Matrix4 transform(BoardDecoration object) {
        var feature = new BoardScene.Feature(object.asset(), 0, 0, (float) object.rotation(), (float) object.scale(), 1, 0,
              BoardScene.FeatureKind.PROP, 0, false, object);
        return BoardFeatures.decorationTransform(new Coords(3, 4), feature, 17);
    }

    private static boolean placeable(BoardDecoration object) {
        return GpuTreeInstances.placeable(transform(object));
    }

    private static boolean instanceable(BoardDecoration object) {
        return GpuTreeInstances.instanceable(transform(object));
    }

    private static BoardDecoration object(double rotation, boolean mirror, double rotationX, double rotationY) {
        return new BoardDecoration("id", "prop", "scenery/parks/picnic-table", null, .2, -.1, rotation, mirror, 1.7,
              BoardDecoration.Placement.ground(), 0, false, rotationX, rotationY);
    }

    @Test
    void instancesHoldTurnedAndScaledObjectsButNotTiltsMirrorsOrUnevenFootprints() {
        assertTrue(placeable(object(0, false, 0, 0)));
        assertTrue(placeable(object(137, false, 0, 0)));
        assertTrue(placeable(object(-90, false, 0, 0).withStretch(new BoardDecoration.Stretch(1.4, 1.4, 3))),
              "A taller object with an even footprint keeps its vertical scale");
        assertFalse(placeable(object(30, true, 0, 0)), "A stand's species keep the counter-clockwise front face");
        assertFalse(placeable(object(30, false, 12, 0)));
        assertFalse(placeable(object(30, false, 0, -5)));
        assertFalse(placeable(object(30, false, 0, 0).withStretch(new BoardDecoration.Stretch(2, 1, 1))),
              "One horizontal scale per instance");
    }

    /** An object part's instance also holds a mirror image (its batch flips the front face), still no tilt or shear. */
    @Test
    void objectInstancesAlsoHoldMirrorImages() {
        for (double rotation : new double[] { 0, 30, 137, -90 }) {
            assertTrue(instanceable(object(rotation, true, 0, 0)), "Mirrored at " + rotation);
            assertTrue(instanceable(object(rotation, false, 0, 0)), "Unmirrored at " + rotation);
            assertTrue(GpuTreeInstances.mirrored(transform(object(rotation, true, 0, 0))));
            assertFalse(GpuTreeInstances.mirrored(transform(object(rotation, false, 0, 0))));
        }
        assertTrue(instanceable(object(30, true, 0, 0).withStretch(new BoardDecoration.Stretch(1.4, 1.4, 3))));
        assertFalse(instanceable(object(30, true, 12, 0)), "A tilted mirror image");
        assertFalse(instanceable(object(30, true, 0, 0).withStretch(new BoardDecoration.Stretch(2, 1, 1))));
    }
}
