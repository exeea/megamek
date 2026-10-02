/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import megamek.common.board.Coords;
import megamek.common.units.BipedMek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiniatureHeightsTest {
    private static final String ATLAS = "019f583e-c357-773c-a8b1-81bde37a6571";
    private static final String LOCUST = "019f583e-0000-7000-8000-000000000001";
    private static final String NIGHTSKY = "019f583e-c852-7505-957d-449c769e5001";
    private static final String QUOTED = "019f583e-0000-7000-8000-000000000002";

    @Test
    void listedUnitsAreMeasuredAgainstTheAssaultReference(@TempDir Path directory) throws IOException {
        var heights = MiniatureHeights.load(list(directory));

        assertEquals(55.47f / MiniatureHeights.REFERENCE_ASSAULT_MM, heights.heightScale(ATLAS), .0001f);
        assertEquals(39.88f / MiniatureHeights.REFERENCE_ASSAULT_MM, heights.heightScale(LOCUST), .0001f);
    }

    @Test
    void aQuotedNameWithACommaStillReadsTheUuidAndHeight(@TempDir Path directory) throws IOException {
        var heights = MiniatureHeights.load(list(directory));

        assertEquals(40 / MiniatureHeights.REFERENCE_ASSAULT_MM, heights.heightScale(QUOTED), .0001f);
    }

    @Test
    void rowsWithoutAUsableHeightAndUnlistedUnitsGiveNoScale(@TempDir Path directory) throws IOException {
        var heights = MiniatureHeights.load(list(directory));

        assertNull(heights.heightScale(NIGHTSKY), "A blank height must fall back to the weight class");
        assertNull(heights.heightScale("019f583e-ffff-7fff-8fff-ffffffffffff"));
        assertNull(heights.heightScale(null));
    }

    @Test
    void aMissingFileLeavesEveryUnitAtItsWeightClassHeight(@TempDir Path directory) {
        var heights = MiniatureHeights.load(directory.resolve(MiniatureHeights.FILE_NAME));

        assertNull(heights.heightScale(ATLAS));
    }

    @Test
    void aListedMekMeepleUsesItsMiniatureHeightOnTheAssaultRuler() {
        var listedLight = mek(20, 39.88f / MiniatureHeights.REFERENCE_ASSAULT_MM);

        assertEquals(UnitFamilyScale.MEK_ASSAULT.meepleHeightScale() * 39.88f / MiniatureHeights.REFERENCE_ASSAULT_MM,
              MeepleAnimator.meepleHeightScale(listedLight, UnitFamilyScale.MEK_LIGHT), .0001f);
    }

    @Test
    void anUnlistedMekMeepleKeepsItsWeightClassHeight() {
        var unlistedLight = mek(20, null);

        assertEquals(UnitFamilyScale.MEK_LIGHT.meepleHeightScale(),
              MeepleAnimator.meepleHeightScale(unlistedLight, UnitFamilyScale.MEK_LIGHT), .0001f);
    }

    @Test
    void aListedSuperheavyKeepsItsWeightClassHeight() {
        var listedSuperHeavy = mek(150, 1.2f);

        assertEquals(UnitFamilyScale.MEK_SUPER_HEAVY.meepleHeightScale(),
              MeepleAnimator.meepleHeightScale(listedSuperHeavy, UnitFamilyScale.MEK_SUPER_HEAVY), .0001f);
    }

    private static Path list(Path directory) throws IOException {
        Path file = directory.resolve(MiniatureHeights.FILE_NAME);
        Files.writeString(file, String.join("\n",
              "Chassis,Model,UUID,Height",
              "Atlas,AS7-K," + ATLAS + ",55.47",
              "Locust,LCT-1V," + LOCUST + ",39.88",
              "Nightsky,NGS-4S," + NIGHTSKY + ",",
              "\"Test, Mek\",X," + QUOTED + ",40",
              "not a row"), StandardCharsets.UTF_8);
        return file;
    }

    /** A Mek whose captured anatomy carries the given miniature scale, as capture would for a listed unit. */
    private static BoardScene.Unit mek(double weight, Float miniatureHeightScale) {
        var mek = new BipedMek();
        mek.setWeight(weight);
        var captured = UnitModelState.capture(mek);
        var structure = captured.structure();
        var anatomy = structure.anatomy();
        var listedAnatomy = new UnitModelState.MekAnatomy(anatomy.configuration(), anatomy.hands(), anatomy.lowerArms(),
              anatomy.size(), anatomy.weightClass(), miniatureHeightScale);
        var state = new UnitModelState(new UnitModelState.Structure(structure.movement(), structure.equipment(),
              structure.members(), structure.activeTroopers(), structure.externalSearchlight(), listedAnatomy,
              structure.bodyForm(), structure.family()), captured.appearance(), captured.pose());
        var model = new BoardScene.UnitModel(null, null, null, 1, 0, BoardScene.LocationDamage.NONE, state);
        return new BoardScene.Unit(1, -1, "Miniature height review", new BoardScene.Waypoint(new Coords(2, 2), 2, 0),
              null, false, null, 2, false, model, 0);
    }
}
