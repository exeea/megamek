/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.Configuration;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class InfantryVisualTest {
    private static final String MEEPLES = "units/modular/meeples/";

    private static JsonValue descriptor(String family) {
        return new JsonReader().parse(new FileHandle(Configuration.dataDir().toPath()
              .resolve("models/units/modular/" + family + ".json").toFile()));
    }

    private static UnitModelState.Structure state(EntityMovementMode movement, int alive, List<Integer> members) {
        return new UnitModelState.Structure(movement, List.of(), members, alive, false);
    }

    private static UnitModelState.Structure infantry(EntityMovementMode movement, int alive, String icon, int guns) {
        return new UnitModelState.Structure(movement, List.of(), List.of(), alive, false,
              null, null, UnitFamilyScale.INFANTRY, icon, guns);
    }

    @ParameterizedTest
    @CsvSource({
          "Infantry/Branth.png, branth-rider", "Infantry/Camel.png, camel-horse-rider",
          "Infantry/Horse.png, camel-horse-rider", "Infantry/Raxx.png, odessan-raxx-rider",
          "Infantry/Elephant.png, elephant-rider", "Infantry/Orcainfy.png, orca-rider",
          "Infantry/Infantry_VTOL_Squad.png, vtol-trooper", "Infantry/Infantry_VTOL_Platoon.png, vtol-trooper",
          // CHECKSTYLE IGNORE ForbiddenWords FOR 1 LINES - literal icon and asset filenames
          "Infantry/mechwarrior.png, mechwarrior", "Infantry/Miniguncycle.png, motorcycle-rider",
          "INFANTRY\\HORSE.PNG, camel-horse-rider"
    })
    void specializedIconsSupplyWholeMountedFiguresWithoutExtraTransports(String icon, String mesh) {
        var state = infantry(EntityMovementMode.INF_MOTORIZED, 28, icon, 0);
        var parts = InfantryVisual.parts(state, descriptor("meeples/infantry"));
        assertEquals(6, parts.size());
        assertTrue(parts.stream().allMatch(part -> part.asset().equals(MEEPLES + mesh + ".json")));
        assertTrue(parts.stream().allMatch(part -> part.id().startsWith("trooper-") && part.scale() == 1));
        // The ordinary articulated formation is unaffected by meeple-only icon overrides.
        assertEquals(InfantryVisual.parts(infantry(state.movement(), 28, null, 0), descriptor("infantry")),
              InfantryVisual.parts(state, descriptor("infantry")));
    }

    @Test
    void fieldGunsReplaceFiguresOneForOneAndNeverComeFromAnIcon() {
        var recipe = descriptor("meeples/infantry");
        var before = InfantryVisual.parts(infantry(EntityMovementMode.INF_LEG, 28, "Infantry/fieldgun.png", 0), recipe);
        assertTrue(before.stream().allMatch(part -> part.asset().equals(MEEPLES + "infantry-trooper.json")));
        for (int guns : List.of(1, 2)) {
            var armed = InfantryVisual.parts(infantry(EntityMovementMode.INF_LEG, 28, "Infantry/unknown.png", guns), recipe);
            assertEquals(before.size(), armed.size());
            assertEquals(guns, armed.stream().filter(part -> part.asset().equals(MEEPLES + "field-gun.json")).count());
            for (int index = 0; index < armed.size(); index++) {
                assertEquals(before.get(index).id(), armed.get(index).id());
                assertEquals(before.get(index).x(), armed.get(index).x());
                assertEquals(before.get(index).y(), armed.get(index).y());
                if (index >= guns) { assertEquals(before.get(index), armed.get(index)); }
            }
        }
        for (var movement : List.of(EntityMovementMode.INF_LEG, EntityMovementMode.INF_MOTORIZED)) {
            var armed = InfantryVisual.parts(infantry(movement, 2, null, 2), recipe);
            assertEquals(2, armed.size());
            assertTrue(armed.stream().allMatch(part -> part.asset().equals(MEEPLES + "field-gun.json")));
            assertTrue(InfantryVisual.parts(infantry(movement, 0, null, 2), recipe).isEmpty());
        }
        var unknown = infantry(EntityMovementMode.INF_MOTORIZED, 28, "Infantry/unknown.png", 0);
        assertEquals(InfantryVisual.parts(infantry(unknown.movement(), 28, null, 0), recipe),
              InfantryVisual.parts(unknown, recipe));
    }

    @ParameterizedTest
    @EnumSource(value = EntityMovementMode.class, names = { "INF_MOTORIZED", "TRACKED", "WHEELED", "HOVER" })
    void transportsTakeOneSlotUpToFourAndTwoAboveThat(EntityMovementMode movement) {
        for (int slots = 0; slots <= 6; slots++) {
            var parts = InfantryVisual.parts(state(movement, slots * slots, List.of()), descriptor("infantry"));
            assertEquals(slots, parts.size());
            assertEquals(slots == 0 ? 0 : slots <= 4 ? 1 : 2,
                  parts.stream().filter(part -> part.id().startsWith("vehicle")).count());
            assertEquals(slots - (slots == 0 ? 0 : slots <= 4 ? 1 : 2),
                  parts.stream().filter(part -> part.id().startsWith("trooper")).count());
        }
        var six = InfantryVisual.parts(state(movement, 28, List.of()), descriptor("infantry"));
        var four = InfantryVisual.parts(state(movement, 16, List.of()), descriptor("infantry"));
        for (var part : four) {
            assertEquals(part, six.stream().filter(before -> before.id().equals(part.id())).findFirst().orElseThrow());
        }
        assertEquals(2, six.stream().filter(part -> part.id().startsWith("vehicle"))
              .map(InfantryVisual.Part::heading).distinct().count());
    }

    @Test
    void battleArmorKeepsEachLivingMembersIdentityAndHasOptionalCompression() {
        var before = BattleArmorVisual.parts(state(EntityMovementMode.INF_JUMP, 6, List.of(1, 2, 3, 4, 5, 6)),
              descriptor("battle-armor"));
        var after = BattleArmorVisual.parts(state(EntityMovementMode.INF_JUMP, 1, List.of(6)), descriptor("battle-armor"));
        assertEquals(6, before.size());
        assertEquals(1, before.stream().map(InfantryVisual.Part::asset).distinct().count(), "Every suit shares its base mesh");
        assertEquals(List.of(before.get(5)), after);
        assertEquals(0, BattleArmorVisual.parts(state(EntityMovementMode.INF_JUMP, 0, List.of()),
              descriptor("battle-armor")).size());
        assertEquals(5, BattleArmorVisual.figures(5, false));
        assertEquals(3, BattleArmorVisual.figures(5, true));
    }

    @Test
    void jumpInfantryUsesJumpPackMeshesAndFootInfantryStaysCompressed() {
        var jumps = InfantryVisual.parts(state(EntityMovementMode.INF_JUMP, 28, List.of()), descriptor("infantry"));
        assertEquals(6, jumps.size());
        assertEquals(1, jumps.stream().map(InfantryVisual.Part::asset).distinct().count());
        assertEquals(6, jumps.stream().filter(part -> part.asset().contains("/jump-")).count());
        var foot = InfantryVisual.parts(state(EntityMovementMode.INF_LEG, 9, List.of()), descriptor("infantry"));
        assertEquals(3, foot.size());
        assertEquals(1, foot.stream().map(InfantryVisual.Part::asset).distinct().count());
        assertEquals(3, foot.stream().filter(part -> part.asset().contains("/rifle-")).count());
    }
}
