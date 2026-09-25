/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import megamek.common.units.BipedMek;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class UnitFamilyScaleTest {
    @ParameterizedTest
    @CsvSource({ "1, 1, 18", ".9, 1, 18", "1, 1.3, 12", ".9, 1.3, 12", "1, .7, 30", ".9, .7, 30" })
    void referenceAssaultIsTwoLevelsBeforeUniformUnitScaling(float size, float hex, int level) throws Exception {
        GdxNativesLoader.load();
        var original = BoardGeometry.tuning();
        var model = model(UnitFamilyScale.MEK);
        var reference = UnitModelDescriptor.read(Configuration.dataDir().toPath()
              .resolve("models/units/modular/bodies/atlas.json"));
        float authoredHeight = reference.bounds().max().get(2) - reference.bounds().min().get(2);
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(hex, size, 1, level, .8f));
            Vector3 placed = place(model, mek(100));
            assertEquals(2 * size, authoredHeight * placed.z / BoardGeometry.LEVEL, .00001f);
            assertEquals(placed.x, placed.y, .00001f);
            assertEquals(placed.x, placed.z, .00001f, "The authored proportions must survive world-unit conversion");
        } finally {
            BoardGeometry.tune(original);
            model.dispose();
        }
    }

    @ParameterizedTest
    @EnumSource(UnitFamilyScale.class)
    void everyFamilyUsesTheSameUniformConversionBeforeItsReadabilityMultiplier(UnitFamilyScale family) {
        GdxNativesLoader.load();
        var original = BoardGeometry.tuning();
        var reference = model(UnitFamilyScale.DEFAULT);
        var model = model(family);
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            for (boolean multiHex : new boolean[] { false, true }) {
                var unit = unit(multiHex);
                Vector3 baseline = place(reference, unit);
                Vector3 scaled = place(model, unit);
                assertEquals(baseline.x, baseline.z, .00001f, "Fitting a footprint must not flatten its model");
                assertScale(baseline.scl(family.unitScale()), scaled);
                assertEquals(scaled.x, scaled.z, .00001f);
            }
        } finally {
            BoardGeometry.tune(original);
            reference.dispose();
            model.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void familyScaleStacksWithBoardTuningWithoutMovingTheUnitOrChangingOtherFamilies(boolean multiHex) {
        GdxNativesLoader.load();
        var original = BoardGeometry.tuning();
        var family = UnitFamilyScale.INFANTRY;
        float originalSize = family.UNIT_SCALE, originalHeight = family.HEIGHT_SCALE;
        var model = model(family);
        var other = model(UnitFamilyScale.BATTLE_ARMOR);
        var unit = unit(multiHex);
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(1.3f, .55f, 1.2f, 20, .8f, .81f));
            Vector3 baseline = place(model, unit);
            Vector3 otherBaseline = place(other, unit);
            Vector3 anchor = model.instance.transform.getTranslation(new Vector3());
            family.UNIT_SCALE = originalSize * 1.25f;
            family.HEIGHT_SCALE = originalHeight * 1.4f;
            assertScale(new Vector3(baseline).scl(1.25f, 1.25f, 1.75f), place(model, unit));
            assertScale(otherBaseline, place(other, unit));
            assertScale(anchor, model.instance.transform.getTranslation(new Vector3()));
            assertEquals(model.instance.transform.getScaleX(), model.horizontalScale(unit), .0001f,
                  "Animation distance conversion must match actual placement");
            assertEquals(model.instance.transform.getScaleZ(), model.verticalScale(model.horizontalScale(unit), unit), .0001f);

            var board = BoardGeometry.tuning();
            BoardGeometry.tune(new BoardGeometry.Tuning(board.hexScale(), board.unitScale() * 1.2f,
                  board.unitHeightScale() * .8f, board.levelHeight(), board.gridShade(), board.multiHexUnitScale() * .9f));
            float horizontal = multiHex ? .9f : 1.2f;
            assertScale(new Vector3(baseline).scl(1.25f * horizontal, 1.25f * horizontal, 1.75f * horizontal * .8f),
                  place(model, unit));
            assertScale(anchor, model.instance.transform.getTranslation(new Vector3()));
        } finally {
            family.UNIT_SCALE = originalSize;
            family.HEIGHT_SCALE = originalHeight;
            BoardGeometry.tune(original);
            model.dispose();
            other.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void levelHeightResizesUnitsEvenlySoTheyStayAsManyLevelsTall(boolean multiHex) {
        GdxNativesLoader.load();
        var original = BoardGeometry.tuning();
        var model = model(UnitFamilyScale.MEK);
        var unit = unit(multiHex);
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(1.3f, .55f, 1.2f, 20, .8f, .81f));
            Vector3 baseline = place(model, unit);
            float levels = baseline.z / BoardGeometry.LEVEL;
            var board = BoardGeometry.tuning();
            BoardGeometry.tune(new BoardGeometry.Tuning(board.hexScale(), board.unitScale(), board.unitHeightScale(),
                  30, board.gridShade(), board.multiHexUnitScale()));
            // Half as high again per level: the whole unit grows by half, not only its height.
            assertScale(new Vector3(baseline).scl(1.5f), place(model, unit));
            assertEquals(levels, model.instance.transform.getScaleZ() / BoardGeometry.LEVEL, .0001f);
        } finally {
            BoardGeometry.tune(original);
            model.dispose();
        }
    }

    @ParameterizedTest
    @CsvSource({
          "15, MEK_LIGHT", "35, MEK_LIGHT", "36, MEK_MEDIUM", "55, MEK_MEDIUM", "56, MEK_HEAVY",
          "75, MEK_HEAVY", "76, MEK_ASSAULT", "100, MEK_ASSAULT", "101, MEK_SUPER_HEAVY", "150, MEK_SUPER_HEAVY"
    })
    void mekWeightScaleUsesCapturedRulesClassAndStacksWithFamilyTuning(double weight, UnitFamilyScale weightScale) {
        GdxNativesLoader.load();
        var original = BoardGeometry.tuning();
        var family = UnitFamilyScale.MEK;
        float familySize = family.UNIT_SCALE, familyHeight = family.HEIGHT_SCALE;
        float classSize = weightScale.UNIT_SCALE, classHeight = weightScale.HEIGHT_SCALE;
        var unit = mek(weight);
        var otherClass = mek(weight <= 35 ? 100 : 35);
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(1.3f, .55f, 1.2f, 20, .8f));
            for (boolean modular : new boolean[] { false, true }) {
                var model = model(family, modular);
                var otherFamily = model(UnitFamilyScale.VEHICLE, modular);
                try {
                    Vector3 baseline = place(model, unit);
                    Vector3 otherClassBaseline = place(model, otherClass);
                    Vector3 otherFamilyBaseline = place(otherFamily, unit);
                    Vector3 anchor = model.instance.transform.getTranslation(new Vector3());
                    family.UNIT_SCALE = familySize * 1.1f;
                    family.HEIGHT_SCALE = familyHeight * 1.2f;
                    weightScale.UNIT_SCALE = classSize * 1.25f;
                    weightScale.HEIGHT_SCALE = classHeight * 1.4f;
                    assertScale(new Vector3(baseline).scl(1.375f, 1.375f, 2.31f), place(model, unit));
                    assertScale(anchor, model.instance.transform.getTranslation(new Vector3()));
                    if (modular) {
                        assertEquals(model.instance.transform.getScaleX(), model.horizontalScale(unit), .0001f);
                        assertEquals(model.instance.transform.getScaleZ(),
                              model.verticalScale(model.horizontalScale(unit), unit), .0001f);
                    }
                    assertScale(new Vector3(otherClassBaseline).scl(1.1f, 1.1f, 1.32f), place(model, otherClass));
                    assertScale(otherFamilyBaseline, place(otherFamily, unit));
                } finally {
                    family.UNIT_SCALE = familySize;
                    family.HEIGHT_SCALE = familyHeight;
                    weightScale.UNIT_SCALE = classSize;
                    weightScale.HEIGHT_SCALE = classHeight;
                    model.dispose();
                    otherFamily.dispose();
                }
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    private static BoardScene.Unit unit(boolean multiHex) {
        var coords = new Coords(2, 2);
        var footprint = new ArrayList<>(List.of(coords));
        if (multiHex) {
            for (int direction = 0; direction < 6; direction++) { footprint.add(coords.translated(direction)); }
        }
        return new BoardScene.Unit(1, -1, "Scale review", new BoardScene.Waypoint(coords, 2, 0),
              null, false, null, 1, false, null, 0, footprint);
    }

    private static BoardScene.Unit mek(double weight) {
        var mek = new BipedMek();
        mek.setWeight(weight);
        var model = new BoardScene.UnitModel(null, null, null, 1, 0, BoardScene.LocationDamage.NONE,
              UnitModelState.capture(mek));
        return new BoardScene.Unit(1, -1, "Mek scale review", new BoardScene.Waypoint(new Coords(2, 2), 2, 0),
              null, false, null, 2, false, model, 0);
    }

    private static GpuUnitModel model(UnitFamilyScale family) {
        return model(family, true);
    }

    private static GpuUnitModel model(UnitFamilyScale family, boolean modular) {
        return new GpuUnitModel(new Model(), null, modular, List.of(), new Vector3(100, 100, 54), List.of(), family);
    }

    private static Vector3 place(GpuUnitModel model, BoardScene.Unit unit) {
        model.place(model.instance, new OrthographicCamera(), BoardGeometry.center(unit.location().coords(), 2), 0, unit);
        return model.instance.transform.getScale(new Vector3());
    }

    private static void assertScale(Vector3 expected, Vector3 actual) {
        assertEquals(expected.x, actual.x, .0001f);
        assertEquals(expected.y, actual.y, .0001f);
        assertEquals(expected.z, actual.z, .0001f);
    }
}
