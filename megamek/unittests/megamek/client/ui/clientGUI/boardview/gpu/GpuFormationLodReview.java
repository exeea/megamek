/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;

import com.badlogic.gdx.graphics.g3d.model.Node;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.battleArmor.BattleArmor;

/**
 * A battle armour squad with a far suit, using the shipped Elemental: full suits up close, far suits once a suit is
 * small on screen, and full suits again whenever the unit is in focus. Counts only the triangles actually drawn.
 */
final class GpuFormationLodReview {
    private static final String ROOT = "units/modular/";

    private GpuFormationLodReview() { }

    static void verify(GpuUnitModels library) throws Exception {
        var tileset = new MekTileset(new File(Configuration.dataDir(), "images/units"));
        tileset.loadFromFile("mekset.txt");
        int fullSuit = library.modular(ROOT + "troops/elemental-standing.json").triangles();
        int lod1Suit = library.modular(ROOT + "troops/elemental-standing.json").triangles(1);
        int lod2Suit = library.modular(ROOT + "troops/elemental-standing.json").triangles(2);
        for (int squad : new int[] { 5, 6 }) {
            var armor = new BattleArmor();
            armor.setId(300 + squad);
            armor.setChassis("Elemental Battle Armor");
            armor.setModel("[Laser](Sqd" + squad + ")");
            armor.setSquadSize(squad);
            for (int member = 1; member <= squad; member++) {
                armor.initializeInternal(1, member);
            }
            var selection = UnitModelSelection.capture(armor, -1, false, tileset);
            assertEquals(ROOT + "battle-armor/elemental.json", selection.asset());
            GpuUnitModel squadModel = library.get(selection, armor.getId());
            assertNotNull(squadModel, "A " + squad + "-suit Elemental squad must fit the per-suit budget");
            assertFalse(squadModel.detailLevels().lod1().isEmpty());
            var instance = new GpuUnitInstance(squadModel);
            assertEquals(squad * fullSuit, drawnTriangles(instance.nodes));
            // A suit only 20 pixels tall shows its far detail.
            float small = 20 / squadModel.figureHeight();
            instance.bodyDetail(small, false);
            assertEquals(1, instance.detailLevel());
            assertEquals(squad * lod1Suit, drawnTriangles(instance.nodes));
            instance.bodyDetail(10 / squadModel.figureHeight(), false);
            assertEquals(squadModel.detailLevels().resolvedLevel(2), instance.detailLevel());
            assertEquals(squad * lod2Suit, drawnTriangles(instance.nodes));
            // Zoomed in again, the full suits return.
            instance.bodyDetail(10, false);
            assertEquals(0, instance.detailLevel());
            assertEquals(squad * fullSuit, drawnTriangles(instance.nodes));
            // A unit in focus keeps its full suits however small it is.
            instance.bodyDetail(small, true);
            assertEquals(0, instance.detailLevel());
            assertEquals(squad * fullSuit, drawnTriangles(instance.nodes));
        }
    }

    private static int drawnTriangles(Iterable<Node> nodes) {
        int total = 0;
        for (Node node : nodes) {
            for (var part : node.parts) {
                if (part.enabled) {
                    total += part.meshPart.size / 3;
                }
            }
            total += drawnTriangles(node.getChildren());
        }
        return total;
    }
}
