/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardFeaturesTest {
    @Test
    void modeledBuildingsUseTheConcreteEngineWithoutErasingOtherTerrainMarkings() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        for (int terrain : new int[] { Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV,
              Terrains.BLDG_CLASS, Terrains.BLDG_ARMOR, Terrains.BLDG_BASEMENT_TYPE, Terrains.BLDG_FLUFF }) {
            hex.addTerrain(new Terrain(terrain, 1));
        }
        var models = Map.of(Terrains.BUILDING, "building");
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "An unmodeled building still needs its artwork");
        assertTrue(BoardFeatures.detailedGround(hex, models), "The model stands on the normal concrete material");
        for (int terrain : new int[] { Terrains.ROAD, Terrains.RUBBLE, Terrains.BLDG_BASE_COLLAPSED,
              Terrains.FORTIFIED }) {
            hex.addTerrain(new Terrain(terrain, 1));
            assertFalse(BoardFeatures.detailedGround(hex, models), "Keep separate terrain markings: " + terrain);
            hex.removeTerrain(terrain);
        }
        hex.removeTerrain(Terrains.PAVEMENT);
        assertFalse(BoardFeatures.detailedGround(hex, models), "This change is specific to concrete ground");
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
        assertEquals(2, features.stream().filter(feature -> feature.asset().equals("bridge") && feature.elevation() == 2).count());
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
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream()
              .anyMatch(feature -> feature.asset().equals("field") && feature.height() == 1));
    }
}
