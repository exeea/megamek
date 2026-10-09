/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuTreeInstancesTest {
    /** The placed object's drawn transform, as the terrain builds it. */
    private static boolean placeable(BoardDecoration object) {
        var feature = new BoardScene.Feature(object.asset(), 0, 0, (float) object.rotation(), (float) object.scale(), 1, 0,
              BoardScene.FeatureKind.PROP, 0, false, object);
        return GpuTreeInstances.placeable(BoardFeatures.decorationTransform(new Coords(3, 4), feature, 17));
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
        assertFalse(placeable(object(30, true, 0, 0)), "A mirror reverses the winding");
        assertFalse(placeable(object(30, false, 12, 0)));
        assertFalse(placeable(object(30, false, 0, -5)));
        assertFalse(placeable(object(30, false, 0, 0).withStretch(new BoardDecoration.Stretch(2, 1, 1))),
              "One horizontal scale per instance");
    }
}
