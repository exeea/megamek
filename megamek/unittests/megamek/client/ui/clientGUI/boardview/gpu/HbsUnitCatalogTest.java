/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import megamek.common.units.BipedMek;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HbsUnitCatalogTest {
    @TempDir
    Path directory;

    private HbsUnitCatalog catalog() throws IOException {
        Files.writeString(directory.resolve("catalog.json"), """
              {"schema":1,"models":{
                "bushwacker":{"descriptor":"bushwacker/model.json"},
                "madcat":{"descriptor":"madcat/model.json"},
                "ionkingfisher":{"descriptor":"kingfisher/model.json"}
              }}
              """);
        return new HbsUnitCatalog(directory);
    }

    @Test
    void matchesChassisAliasesButNeverSimilarChassisOrVariants() throws Exception {
        var catalog = catalog();
        assertEquals("bushwacker/model.json", catalog.descriptor("Bushwacker"));
        assertEquals("madcat/model.json", catalog.descriptor("Mad Cat (Timber Wolf)"));
        assertEquals("madcat/model.json", catalog.descriptor("Timber Wolf"));
        assertEquals("kingfisher/model.json", catalog.descriptor("Kingfisher"));
        assertNull(catalog.descriptor("Mad Cat Mk II"));
        assertNull(catalog.descriptor("Bushwacker II"));
        assertNull(catalog.descriptor("Unknown Chassis"));
        assertNull(catalog.descriptor((BoardScene.UnitModel) null));
        assertNull(catalog.descriptor(new BoardScene.UnitModel("own.json", null, "Bushwacker BSW-X1", 1)));
    }

    @Test
    void rejectsAssetsOutsideTheCacheAndUnsupportedCatalogs() throws Exception {
        var catalog = catalog();
        assertThrows(IllegalArgumentException.class, () -> catalog.contained("../outside.json"));
        assertThrows(IOException.class, () -> catalog.contained("missing.json"));
        Files.writeString(directory.resolve("catalog.json"), "{\"schema\":9,\"models\":{}}");
        assertThrows(IOException.class, () -> new HbsUnitCatalog(directory));
    }

    @Test
    void alternateFormsKeepTheirOwnModelsAndAppearancePreservesVisibleIdentity() throws Exception {
        var catalog = catalog();
        var mek = new BipedMek();
        mek.setChassis("Bushwacker");
        mek.setMovementMode(EntityMovementMode.BIPED);
        var selection = new BoardScene.UnitModel("own.json", "generic.json", "custom", 1, 0,
              BoardScene.LocationDamage.NONE, UnitModelState.capture(mek), mek.getChassis());
        assertEquals("bushwacker/model.json", catalog.descriptor(selection));
        assertEquals("Bushwacker", new UnitCamouflage().resolve(selection).chassis());
        mek.setMovementMode(EntityMovementMode.AERODYNE);
        selection = new BoardScene.UnitModel("fighter.json", "generic.json", "custom", 1, 0,
              BoardScene.LocationDamage.NONE, UnitModelState.capture(mek), mek.getChassis());
        assertNull(catalog.descriptor(selection));
    }
}
