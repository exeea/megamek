/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardFeaturesTest {
    @ParameterizedTest
    @ValueSource(strings = { "", "desert", "snow", "volcano", "dirt", "lunar" })
    void modeledBridgesKeepTheUnderlyingTerrainMaterial(String theme) {
        Hex hex = new Hex(-2);
        hex.setTheme(theme);
        var surface = BoardFeatures.surface(hex);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 100));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_REPAIRED, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "Bridge geometry must not force the legacy ground artwork");
        assertEquals(surface, BoardFeatures.surface(hex));
        assertFalse(BoardLiquid.capture(hex).present(), "A suspended bridge does not imply liquid beneath it");
        hex.addTerrain(new Terrain(Terrains.WATER, 2));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardLiquid.capture(hex).present(), "Authored water beneath a bridge must remain water");
        hex.removeTerrain(Terrains.WATER);
        hex.addTerrain(new Terrain(Terrains.RUBBLE, 1));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Unmodeled ground markings still keep their artwork");
    }

    @ParameterizedTest
    @EnumSource(BoardScene.Surface.class)
    void modeledStructuresKeepTheirGroundWithoutErasingOtherTerrainMarkings(BoardScene.Surface surface) {
        Hex hex = new Hex(0);
        hex.setTheme(surface.name().toLowerCase(Locale.ROOT));
        if (surface == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
        assertEquals(surface, BoardFeatures.surface(hex));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        for (int terrain : new int[] { Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV,
              Terrains.BLDG_CLASS, Terrains.BLDG_ARMOR, Terrains.BLDG_BASEMENT_TYPE, Terrains.BLDG_FLUFF,
              Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV, Terrains.FUEL_TANK_MAGN,
              Terrains.INDUSTRIAL }) {
            hex.addTerrain(new Terrain(terrain, 1));
        }
        var models = new HashMap<>(Map.of(Terrains.BUILDING, "building",
              Terrains.FUEL_TANK, "tank", Terrains.INDUSTRIAL, "industrial"));
        for (int type : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
            String model = models.remove(type);
            assertFalse(BoardFeatures.detailedGround(hex, models), "Every structure needs a replacement model: " + type);
            models.put(type, model);
        }
        assertTrue(BoardFeatures.detailedGround(hex, models), "Models must retain the native " + surface + " ground");
        assertEquals(surface, BoardFeatures.surface(hex));
        for (int terrain : new int[] { Terrains.ROAD_FLUFF, Terrains.RUBBLE,
              Terrains.FORTIFIED, Terrains.GROUND_FLUFF, Terrains.FLUFF }) {
            hex.addTerrain(new Terrain(terrain, 1));
            assertFalse(BoardFeatures.detailedGround(hex, models), "Keep separate terrain markings: " + terrain);
            hex.removeTerrain(terrain);
        }
    }

    @Test
    void lunarThemeKeepsItsOwnGeologyWithoutReplacingExplicitSurfaceTerrain() {
        Hex hex = new Hex(0);
        hex.setTheme("lunar");
        assertEquals(BoardScene.Surface.LUNAR, BoardFeatures.surface(hex));
        hex.setTheme("rock");
        assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
        hex.setTheme("lunar");
        hex.addTerrain(new Terrain(Terrains.MAGMA, 1));
        assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
        hex.removeTerrain(Terrains.MAGMA);
        hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
        assertEquals(BoardScene.Surface.CONCRETE, BoardFeatures.surface(hex));
    }

    @Test
    void waterDecorationDoesNotChangeTheBedMaterialOrInventWater() {
        Hex hex = new Hex(-2);
        hex.setTheme("volcano");
        hex.addTerrain(new Terrain(Terrains.WATER_FLUFF, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
        assertFalse(BoardLiquid.capture(hex).present());
        hex.addTerrain(new Terrain(Terrains.WATER, 2));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardLiquid.capture(hex).present());
    }

    @Test
    void treeCountsFollowAuthoritativeCoverReductionUntilTheHexIsClear() {
        Coords coords = new Coords(2, 3);
        for (int type : new int[] { Terrains.WOODS, Terrains.JUNGLE }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
            int previous = Integer.MAX_VALUE;
            for (int density = 3; density >= 0; density--) {
                if (density == 0) {
                    hex.removeTerrain(type);
                } else {
                    hex.addTerrain(new Terrain(type, density));
                }
                var features = BoardFeatures.capture(hex, coords, Map.of());
                int count = (int) features.stream().filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).count();
                assertTrue(count < previous, "Each cover reduction must visibly reduce the number of trees");
                assertEquals(density == 0, count == 0, "Trees disappear only when the hex becomes clear");
                assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()), "Unchanged cover must keep its scenery");
                previous = count;
            }
        }
    }

    @Test
    void collectableLimbCountsControlStableGroundProps() {
        Hex hex = new Hex(0);
        Coords coords = new Coords(2, 3);
        hex.addTerrain(new Terrain(Terrains.ARMS, 2));
        hex.addTerrain(new Terrain(Terrains.LEGS, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "A dropped limb must not replace the grass ground");
        var before = BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList();
        assertEquals(3, before.size());
        assertTrue(before.stream().allMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB
              && feature.asset().equals("Limb Club")));
        assertEquals(3, before.stream().map(BoardScene.Feature::rotation).distinct().count());
        assertEquals(before, BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList());
        hex.addTerrain(new Terrain(Terrains.ARMS, 1));
        var after = BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).toList();
        assertEquals(2, after.size());
        assertTrue(before.containsAll(after), "Picking up one limb must not move the others");
        hex.removeTerrain(Terrains.ARMS);
        hex.removeTerrain(Terrains.LEGS);
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
              .noneMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB));
    }

    @Test
    void snowTerrainAndThemeSelectSnowAssetsWhileJungleUsesPalms() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.JUNGLE, 2));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        Coords coords = new Coords(1, 1);
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().startsWith("palm")));
        hex.addTerrain(new Terrain(Terrains.SNOW, 1));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().endsWith("-snow")));
        hex.removeTerrain(Terrains.SNOW);
        hex.setTheme("snow");
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().endsWith("-snow")));
        hex.setTheme("");
        assertFalse(BoardFeatures.capture(hex, coords, Map.of()).stream().anyMatch(feature -> feature.asset().endsWith("-snow")));
    }

    @Test
    void desertAndSandyWoodsGrowDesertSpeciesAtEveryDensity() {
        Coords coords = new Coords(3, 2);
        for (int density = 1; density <= 3; density++) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.WOODS, density));
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
            hex.setTheme("Desert");
            var themed = BoardFeatures.capture(hex, coords, Map.of());
            assertFalse(themed.isEmpty());
            assertTrue(themed.stream().allMatch(feature -> feature.asset().startsWith("palm")
                  || feature.asset().startsWith("cactus") || feature.asset().equals("tree-dead")),
                  "Desert woodland grows cacti, palms and dead trees");
            hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            assertEquals(themed, BoardFeatures.capture(hex, coords, Map.of()), "Ground paving must not change the biome's trees");
            hex.removeTerrain(Terrains.PAVEMENT);
            hex.setTheme("");
            hex.addTerrain(new Terrain(Terrains.SAND, 1));
            assertEquals(themed, BoardFeatures.capture(hex, coords, Map.of()), "Sandy woods use the same palm selection");
            hex.addTerrain(new Terrain(Terrains.SNOW, 1));
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().allMatch(feature -> feature.asset().endsWith("-snow")),
                  "Snow retains the existing winter variants");
        }
    }

    @Test
    void woodsStandAtLeastTheirFoliageHeightAndTheirTreesFitTheGround() {
        Coords coords = new Coords(4, 4);
        for (int density = 1; density <= 3; density++) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.WOODS, density));
            hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, density == 3 ? 3 : 2));
            for (var tree : BoardFeatures.capture(hex, coords, Map.of())) {
                assertTrue(tree.height() >= (density == 3 ? 3 : 2), "Trees are as tall as the woods block sight");
                assertTrue(tree.scale() >= 1.2f, "Woods are drawn oversized, so their cover reads at a glance");
            }
        }
        Hex highland = new Hex(3);
        highland.addTerrain(new Terrain(Terrains.WOODS, 2));
        highland.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        long conifers = BoardFeatures.capture(highland, coords, Map.of()).stream()
              .filter(tree -> tree.asset().startsWith("pine")).count();
        assertTrue(conifers > 9 / 2, "Highland woods are mostly conifers");
        Hex snowfield = new Hex(0);
        snowfield.addTerrain(new Terrain(Terrains.WOODS, 2));
        snowfield.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        snowfield.addTerrain(new Terrain(Terrains.SNOW, 1));
        assertTrue(BoardFeatures.capture(snowfield, coords, Map.of()).stream()
              .filter(tree -> tree.asset().startsWith("pine")).count() > 9 / 2, "Snowfields grow snow-laden conifers");
        highland.setTheme("rock");
        assertTrue(BoardFeatures.capture(highland, coords, Map.of()).stream()
              .allMatch(tree -> tree.asset().startsWith("pine") || tree.asset().equals("tree-dead")),
              "Rocky ground grows hardy conifers and dead trees");
    }

    @Test
    void woodlandMixesSilhouettesWhileRubbleKeepsSmallScatter() {
        Hex hex = new Hex(0);
        Coords coords = new Coords(3, 2);
        hex.addTerrain(new Terrain(Terrains.WOODS, 2));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().map(BoardScene.Feature::asset).distinct().count() >= 5);
        for (String theme : new String[] { "", "snow", "desert" }) {
            for (int terrain : new int[] { Terrains.RUBBLE }) {
                hex.removeAllTerrains();
                hex.setTheme(theme);
                hex.addTerrain(new Terrain(terrain, 4));
                assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
                      .allMatch(feature -> feature.kind() == BoardScene.FeatureKind.SCATTER));
                hex.addTerrain(new Terrain(Terrains.WOODS, 1));
                assertEquals(3, BoardFeatures.capture(hex, coords, Map.of()).size(),
                      "Cosmetic scatter must preserve coexisting woodland");
            }
        }
    }

    @Test
    void coexistingFeaturesRetainTheirOwnHeightsAndBridgeExits() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BUILDING, 3, true, 9));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 4));
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 18));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, 3));
        String selectedRoof = "buildings/saxarba/building_hard/building_hard_09";
        String industrialRoof = "buildings/saxarba/misc/heavy_industrial_a";
        var models = Map.of(Terrains.BUILDING, selectedRoof, Terrains.INDUSTRIAL, industrialRoof);
        var features = BoardFeatures.capture(hex, new Coords(2, 2), models);
        assertTrue(features.stream().anyMatch(feature -> feature.asset().equals(selectedRoof) && feature.height() == 4));
        var bridges = features.stream().filter(feature -> feature.asset().equals("bridge")).toList();
        assertEquals(1, bridges.size(), "One connected deck per bridge hex");
        assertEquals(2, bridges.getFirst().elevation());
        assertEquals(18, bridges.getFirst().bridgeExits());
        assertTrue(features.stream().anyMatch(feature -> feature.asset().equals(industrialRoof) && feature.height() == 3));
        assertEquals(features, BoardFeatures.capture(hex, new Coords(2, 2), models), "Placement must remain stable across snapshots");
        assertFalse(BoardFeatures.capture(hex, new Coords(2, 2), Map.of()).stream()
              .anyMatch(feature -> feature.asset().startsWith("building")), "Blank tileset artwork must stay blank");
    }

    @Test
    void surfaceMaterialsAndCropsFollowTheHex() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.SAND, 1));
        assertEquals(BoardScene.Surface.SAND, BoardFeatures.surface(hex));
        hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
        assertEquals(BoardScene.Surface.CONCRETE, BoardFeatures.surface(hex));
        for (int scatter : new int[] { Terrains.ROUGH, Terrains.RUBBLE }) {
            hex.removeAllTerrains();
            hex.setTheme("");
            hex.addTerrain(new Terrain(scatter, 1));
            assertEquals(BoardScene.Surface.GRASS, BoardFeatures.surface(hex), "Scatter keeps the underlying geology");
            hex.setTheme("rock");
            assertEquals(BoardScene.Surface.ROCK, BoardFeatures.surface(hex));
            hex.addTerrain(new Terrain(Terrains.SAND, 1));
            assertEquals(BoardScene.Surface.SAND, BoardFeatures.surface(hex));
        }
        hex.removeAllTerrains();
        hex.setTheme("");
        hex.addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertEquals(BoardScene.Biome.FIELD, BoardFeatures.biome(hex));
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream()
              .noneMatch(feature -> feature.asset().equals("field")), "Instanced crops replace the legacy field prop");
    }
}
