/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.equipment.EquipmentType;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LobbyUnitCleanupTest {

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void hidingNeedsHiddenUnits() {
        GameOptions options = new GameOptions();
        options.getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(false);
        Entity mek = new BipedMek();
        mek.setHidden(true);

        LobbyUnitCleanup.apply(List.of(mek), options);
        assertFalse(mek.isHidden());

        options.getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
        mek.setHidden(true);
        LobbyUnitCleanup.apply(mek, options);
        assertTrue(mek.isHidden());
    }

    @Test
    void carriedUnitsDeployNeitherHiddenNorProneNorHullDown() {
        GameOptions options = new GameOptions();
        options.getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
        Entity mek = new BipedMek();
        mek.setHidden(true);
        mek.setProne(true);
        mek.setTransportId(7);

        LobbyUnitCleanup.apply(mek, options);

        assertFalse(mek.isHidden());
        assertFalse(mek.isProne());
    }

    @Test
    void edgeNeedsTheEdgeOption() {
        GameOptions options = new GameOptions();
        Entity mek = new BipedMek();
        mek.getCrew().getOptions().getOption(OptionsConstants.EDGE_WHEN_HEAD_HIT).setValue(true);

        LobbyUnitCleanup.apply(mek, options);

        assertFalse(mek.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_HEAD_HIT));
    }
}
