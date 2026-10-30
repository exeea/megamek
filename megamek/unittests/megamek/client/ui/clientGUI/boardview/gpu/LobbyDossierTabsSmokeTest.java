/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.RequisitionDossier;
import megamek.client.lobby.RequisitionFactions;
import megamek.client.lobby.RequisitionRecord;
import megamek.client.lobby.RequisitionRow;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.gdx.UiStepChart;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the Requisition dossier's tabs and its Readout switch through the stage, as a pointer would: only the tab in
 * view is built, the full record comes once the pick has rested, and the tab picked stays from unit to unit.
 */
@Tag("on-demand")
class LobbyDossierTabsSmokeTest {

    @Test
    void theTabsShowTheirPagesAndStayFromUnitToUnit() throws Exception {
        EquipmentType.initializeTypes();
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        UiTestStage.run(ui -> {
            DossierActions actions = new DossierActions(atlas);
            LobbyRequisitionPanel requisition = new LobbyRequisitionPanel(ui.kit, actions, key -> null, true);
            Table body = requisition.body();
            ui.window.addActor(body);
            body.setBounds(20, 20, 1400, 900);
            requisition.opened(LobbyForces.EMPTY);
            requisition.probePick();
            run(ui, .05f);
            assertTrue(requisition.dossierOpen(), "the first result's dossier shows");
            assertEquals(RequisitionDossier.Tab.OVERVIEW, requisition.dossierTab());
            assertFalse(requisition.recordLoaded(), "the record waits for the pick to rest");
            run(ui, LobbyRequisitionPanel.LOAD_DELAY * 2);
            assertTrue(requisition.recordLoaded(), "then it loads");
            assertEquals(1, actions.loads);
            assertEquals(0, actions.factionsAsked, "the Factions tab is not built until it shows");
            assertTrue(text(requisition.dossierBody()).contains("20"), "the Overview has the Atlas's heat");

            click(ui, requisition.dossierTab("readout"));
            assertEquals(RequisitionDossier.Tab.READOUT, requisition.dossierTab());
            assertFalse(requisition.readoutTro());
            assertTrue(text(requisition.dossierBody()).contains("Atlas"), "the summary readout shows");
            click(ui, requisition.readoutOption(true));
            assertTrue(requisition.readoutTro(), "the switch shows the TRO");
            assertTrue(actions.tro, "and keeps it as the preference");

            click(ui, requisition.dossierTab("ascard"));
            assertEquals(RequisitionDossier.Tab.AS_CARD, requisition.dossierTab());
            assertNotNull(requisition.dossierBody());

            click(ui, requisition.dossierTab("factions"));
            assertEquals(RequisitionDossier.Tab.FACTIONS, requisition.dossierTab());
            assertEquals(1, actions.factionsAsked, "the Factions tab asks for the availability once it shows");

            click(ui, requisition.dossierTab("damage"));
            assertEquals(RequisitionDossier.Tab.DAMAGE, requisition.dossierTab());
            UiStepChart chart = ((Group) requisition.dossierBody()).findActor("lobby-requisition-damage-chart");
            assertNotNull(chart, "the Damage tab draws its chart");
            assertTrue(chart.ceiling() >= 20, "the Atlas's damage fits under the guides: " + chart.ceiling());

            requisition.resultsList().select(1, true);
            run(ui, .05f);
            assertEquals(RequisitionDossier.Tab.DAMAGE, requisition.dossierTab(), "the tab stays for the next unit");
            assertFalse(requisition.recordLoaded(), "the next unit's record waits again");
            run(ui, LobbyRequisitionPanel.LOAD_DELAY * 2);
            assertTrue(requisition.recordLoaded());
            assertEquals(2, actions.loads);

            requisition.resultsList().select(0, true);
            requisition.resultsList().select(1, true);
            requisition.resultsList().select(0, true);
            run(ui, LobbyRequisitionPanel.LOAD_DELAY * 2);
            assertEquals(3, actions.loads, "running down the list loads only the unit it stops on");

            ui.stage.setKeyboardFocus(requisition.resultsList());
            ui.stage.keyDown(Input.Keys.LEFT);
            ui.draw();
            assertFalse(requisition.dossierOpen(), "Left closes the dossier");
            ui.stage.keyDown(Input.Keys.RIGHT);
            ui.draw();
            assertTrue(requisition.dossierOpen(), "Right opens it again");
            assertEquals(RequisitionDossier.Tab.DAMAGE, requisition.dossierTab());
            body.remove();
        });
    }

    @Test
    void theStepChartsGuidesRoundUp() {
        assertEquals(1, UiStepChart.ceiling(0));
        assertEquals(1, UiStepChart.ceiling(1));
        assertEquals(2, UiStepChart.ceiling(1.5f));
        assertEquals(50, UiStepChart.ceiling(41));
        assertEquals(100, UiStepChart.ceiling(51));
        assertEquals(.5f, UiStepChart.ceiling(.31f), 1e-6);
    }

    private static String text(Actor actor) {
        StringBuilder text = new StringBuilder();
        collect(actor, text);
        return text.toString();
    }

    private static void collect(Actor actor, StringBuilder text) {
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.Label label) {
            text.append(label.getText()).append('\n');
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                collect(child, text);
            }
        }
    }

    private static void run(UiTestStage ui, float seconds) {
        for (float t = 0; t < seconds; t += 1 / 60f) {
            ui.stage.act(1 / 60f);
            ui.draw();
        }
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor);
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static MekSummary unit(String model) {
        MekSummary unit = new MekSummary();
        unit.setChassis("Atlas");
        unit.setModel(model);
        unit.setName("Atlas " + model);
        unit.setUnitType(UnitType.getTypeName(UnitType.MEK));
        unit.setYear(2755);
        unit.setTons(100);
        unit.setBV(1897);
        unit.setPointValue(52);
        unit.setWalkMp(3);
        unit.setRunMp(5);
        unit.setTotalArmor(304);
        unit.setTotalInternal(152);
        unit.setTechBase("Inner Sphere");
        unit.setLevel("A");
        return unit;
    }

    /** Hands back two Atlases at once, and the one record for whichever is picked. */
    private static final class DossierActions implements LobbyRequisitionPanel.Actions {
        private final Entity atlas;
        private final List<MekSummary> units = List.of(unit("AS7-D"), unit("AS7-K"));
        int loads;
        int factionsAsked;
        boolean tro;

        DossierActions(Entity atlas) {
            this.atlas = atlas;
        }

        @Override
        public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
            List<RequisitionRow> rows = new ArrayList<>();
            units.forEach(unit -> rows.add(RequisitionRow.of(unit)));
            done.accept(new RequisitionSearch.Result(query, rows, rows.size(), List.of(), 0, 0));
        }

        @Override
        public void load(MekSummary unit, Consumer<RequisitionRecord> done) {
            loads++;
            done.accept(RequisitionRecord.read(unit, summary -> atlas));
        }

        @Override
        public void factions(MekSummary unit, Consumer<RequisitionFactions> done) {
            factionsAsked++;
            done.accept(RequisitionFactions.EMPTY);
        }

        @Override
        public boolean readoutTro() {
            return tro;
        }

        @Override
        public void readoutTro(boolean value) {
            tro = value;
        }

        @Override
        public void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
        }

        @Override
        public void remove(int id) {
        }

        @Override
        public void sprites(List<MekSummary> units) {
        }

        @Override
        public void source(LobbyForcesPanel.Command command) {
        }
    }
}
