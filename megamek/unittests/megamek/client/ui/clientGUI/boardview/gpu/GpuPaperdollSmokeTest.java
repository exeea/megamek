/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.Cell;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.equipment.IArmorState;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Aero;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The paperdoll renderer against the approved mockup (unit panel design 14 U2): the damaged Atlas AS7-K on the card and
 * on the sheet's ARMOR tab (front, rear over structure), the Centurion CN11-OD with its LA shield on the card and the
 * sheet, the Demolisher Heavy Tank (Mk. I) card and the Shilone SL-17 sheet, in the mockup's damage states and sizes.
 * The card dolls are drawn at the 1920 x 1080 mockup's HUD scale (1.08), the sheet dolls at 1 as in its slices. It
 * writes paperdoll-review.png; the comparison with the mockup renders is made by eye.
 */
@Tag("on-demand")
class GpuPaperdollSmokeTest {
    private static final String MEK_FILES = "../../mm-data/data/mekfiles/";
    private static final float MOCK_SCALE = 1.08f;
    private static final Color PANEL = new Color(UiTheme.PANEL.r, UiTheme.PANEL.g, UiTheme.PANEL.b, 1);

    @Test
    void paperdollsAtTheMockupsSizes() throws Exception {
        GpuPaperdolls geometry = new GpuPaperdolls(Path.of("../../mm-data/data/images/paperdolls"));
        // Unit data is read and damaged on this thread before the window opens; the GL body draws only cells.
        Entity atlas = load("meks/3050U/Atlas AS7-K.mtf");
        atlas.setArmor(0, Mek.LOC_CENTER_TORSO);
        atlas.setInternal(24, Mek.LOC_CENTER_TORSO);
        atlas.setArmor(29, Mek.LOC_RIGHT_TORSO);
        atlas.setArmor(4, Mek.LOC_RIGHT_TORSO, true);
        atlas.setArmor(18, Mek.LOC_LEFT_TORSO);
        atlas.setArmor(22, Mek.LOC_RIGHT_ARM);
        atlas.setArmor(IArmorState.ARMOR_DESTROYED, Mek.LOC_LEFT_ARM);
        atlas.setInternal(IArmorState.ARMOR_DESTROYED, Mek.LOC_LEFT_ARM);
        atlas.setArmor(12, Mek.LOC_RIGHT_LEG);
        atlas.setArmor(38, Mek.LOC_LEFT_LEG);
        Set<String> atlasCrits = Set.of("CT", "RL");
        Entity centurion = load("meks/3145/Davion/Centurion CN11-OD.mtf");
        centurion.setArmor(15, Mek.LOC_CENTER_TORSO);
        centurion.setArmor(9, Mek.LOC_RIGHT_TORSO);
        centurion.setArmor(14, Mek.LOC_LEFT_LEG);
        Entity demolisher = load("vehicles/3039u/Demolisher Heavy Tank (Mk. I).blk");
        demolisher.setArmor(14, Tank.LOC_FRONT);
        demolisher.setArmor(27, Tank.LOC_LEFT);
        demolisher.setArmor(0, Tank.LOC_RIGHT);
        demolisher.setInternal(5, Tank.LOC_RIGHT);
        demolisher.setArmor(25, Tank.LOC_TURRET);
        Aero shilone = (Aero) load("fighters/TRO3039u/Shilone SL-17.blk");
        shilone.setArmor(38, Aero.LOC_NOSE);
        shilone.setArmor(21, Aero.LOC_RIGHT_WING);
        shilone.setSI(6);

        Map<String, Cell> atlasCard = front(atlas, View.CARD, atlasCrits);
        Map<String, Cell> atlasArmor = front(atlas, View.ARMOR, atlasCrits);
        Map<String, Cell> atlasRear = rear(atlas);
        Map<String, Cell> atlasStructure = structure(atlas);
        Map<String, Cell> centurionCard = front(centurion, View.CARD, Set.of());
        centurionCard.put("LL", centurionCard.get("LL").with(true, 0));
        // Shield (Medium): capacity 11 of 18 left, absorption 5 of 5 (the mockup's state).
        centurionCard.putAll(GpuPaperdoll.shieldCells("LA", true, 11, 18, 5, 5));
        Map<String, Cell> centurionFront = front(centurion, View.ARMOR, Set.of());
        centurionFront.put("LL", centurionFront.get("LL").with(true, 0));
        centurionFront.putAll(GpuPaperdoll.shieldCells("LA", true, 11, 18, 5, 5));
        Map<String, Cell> tankArmor = front(demolisher, View.CARD, Set.of());
        // The card's structure boxes show their fill only (design 4.2).
        Map<String, Cell> tankStructure = new HashMap<>();
        structure(demolisher).forEach((code, cell) -> tankStructure.put(code, new Cell(cell.tier(), "", false, false,
              0)));
        Map<String, Cell> aeroArmor = front(shilone, View.ARMOR, Set.of());
        Map<String, Cell> aeroBoxes = Map.of("SI", GpuPaperdoll.cell(shilone.getSI(), shilone.getOSI(), false, 1,
              false, View.STRUCTURE));
        String atlasFamily = GpuPaperdolls.family(atlas);
        String centurionFamily = GpuPaperdolls.family(centurion);
        String tankFamily = GpuPaperdolls.family(demolisher);
        String aeroFamily = GpuPaperdolls.family(shilone);
        assertEquals(List.of("biped", "biped", "vehicle-turret", "fighter-aerospace"),
              List.of(atlasFamily, centurionFamily, tankFamily, aeroFamily));

        GpuHudTestStage.run(hud -> {
            // The card slot's dolls at the mockup's HUD scale: 84 x 118 for a Mek, 90 x 140 for a vehicle.
            Group card = new Group();
            card.setScale(MOCK_SCALE);
            hud.window.addActor(card);
            GpuPaperdoll atlasOnCard = doll(hud, card, 20, 20, 84, 118, 14,
                  geometry.doll(atlasFamily, GpuPaperdolls.ARMOR, Set.of()), atlasCard, Map.of());
            GpuPaperdoll centurionOnCard = doll(hud, card, 124, 20, 84, 118, 14,
                  geometry.doll(centurionFamily, GpuPaperdolls.ARMOR, Set.of("LA")), centurionCard, Map.of());
            doll(hud, card, 228, 20, 90, 140, 14, geometry.doll(tankFamily, GpuPaperdolls.ARMOR, Set.of()),
                  tankArmor, tankStructure);
            // The sheet's ARMOR tab at wide density: front 280, rear 160 over structure 160.
            GpuPaperdoll atlasFront = doll(hud, hud.window, 20, 220, 280, 403, 16,
                  geometry.doll(atlasFamily, GpuPaperdolls.ARMOR, Set.of()), atlasArmor, Map.of());
            atlasFront.marks("", "LA", "");
            doll(hud, hud.window, 334, 220, 160, 141, 16, geometry.doll(atlasFamily, GpuPaperdolls.REAR, Set.of()),
                  atlasRear, Map.of()).marks("", "LA", "");
            GpuPaperdoll atlasInside = doll(hud, hud.window, 334, 390, 160, 233, 16,
                  geometry.doll(atlasFamily, GpuPaperdolls.STRUCTURE, Set.of()), Map.of(), atlasStructure);
            atlasInside.marks("", "LA", "");
            GpuPaperdoll centurionSheet = doll(hud, hud.window, 530, 220, 280, 433, 16,
                  geometry.doll(centurionFamily, GpuPaperdolls.ARMOR, Set.of("LA")), centurionFront, Map.of());
            centurionSheet.marks("", "DCLA", "");
            doll(hud, hud.window, 850, 220, 260, 366, 16, geometry.doll(aeroFamily, GpuPaperdolls.ARMOR, Set.of()),
                  aeroArmor, aeroBoxes).marks("", "RWG", "");
            hud.draw();
            Pixmap image = hud.capture("paperdoll-review");
            image.dispose();

            // The card prints the approved cues; the sheet's armor doll leaves the exposed and destroyed values to
            // the structure doll (R3-3); the card shows both shield numbers on the shield (user correction 17).
            assertTrue(shows(atlasOnCard, "(24)"), "the exposed CT on the card");
            assertTrue(shows(atlasOnCard, GpuPaperdoll.CROSS), "the destroyed LA on the card");
            assertFalse(shows(atlasFront, "(24)"));
            assertFalse(shows(atlasFront, GpuPaperdoll.CROSS));
            assertTrue(shows(atlasFront, "\u2022"), "the CT crit dot on the sheet");
            assertTrue(shows(atlasInside, GpuPaperdoll.CROSS), "LA's cross on the structure doll");
            assertTrue(shows(centurionOnCard, "11") && shows(centurionOnCard, "5"), "both shield numbers on the card");
            assertTrue(shows(centurionSheet, "11") && shows(centurionSheet, "5"), "both shield numbers on the sheet");
            assertEquals("DCLA", centurionOnCard.pick(label(centurionOnCard, "11").getX()
                  + label(centurionOnCard, "11").getWidth() / 2, label(centurionOnCard, "11").getY()
                  + label(centurionOnCard, "11").getHeight() / 2), "the capacity number sits on the shield");
            assertEquals("DCLA", centurionOnCard.pick(label(centurionOnCard, "5").getX()
                  + label(centurionOnCard, "5").getWidth() / 2, label(centurionOnCard, "5").getY()
                  + label(centurionOnCard, "5").getHeight() / 2), "the absorption number sits on the shield");
        });
    }

    private static GpuPaperdoll doll(GpuHudTestStage hud, Group parent, float x, float top, float width, float height,
          float maxFont, GpuPaperdolls.Doll geometry, Map<String, Cell> armor, Map<String, Cell> structure) {
        // Stage y is up; the layout above is given from the window's top.
        float y = 1080 / (parent == hud.window ? 1 : MOCK_SCALE) - top - height;
        Image panel = new Image(hud.theme.skin.newDrawable("white", PANEL));
        panel.setBounds(x - 6, y - 6, width + 12, height + 12);
        parent.addActor(panel);
        GpuPaperdoll doll = new GpuPaperdoll(hud.theme.skin);
        doll.maxFont(maxFont);
        doll.show(geometry, armor, structure);
        doll.setBounds(x, y, width, height);
        parent.addActor(doll);
        return doll;
    }

    /** Whether the doll prints the text as one of its visible labels. */
    private static boolean shows(GpuPaperdoll doll, String text) {
        doll.validate();
        for (Actor child : doll.getChildren()) {
            if (child instanceof Label label && label.isVisible() && label.getText().toString().equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static Label label(GpuPaperdoll doll, String text) {
        for (Actor child : doll.getChildren()) {
            if (child instanceof Label label && label.getText().toString().equals(text)) {
                return label;
            }
        }
        throw new AssertionError("No label " + text);
    }

    private static Entity load(String file) throws Exception {
        return new MekFileParser(new File(MEK_FILES + file)).getEntity();
    }

    /** Front armor cells, as U3 maps the record: armor over structure, with the given critical hits. */
    private static Map<String, Cell> front(Entity entity, View view, Set<String> crits) {
        Map<String, Cell> cells = new HashMap<>();
        for (int loc = 0; loc < entity.locations(); loc++) {
            String code = entity.getLocationAbbr(loc);
            cells.put(code, GpuPaperdoll.cell(Math.max(0, entity.getArmor(loc)), entity.getOArmor(loc),
                  entity.isLocationBad(loc), Math.max(0, entity.getInternal(loc)), crits.contains(code), view));
        }
        return cells;
    }

    private static Map<String, Cell> rear(Entity entity) {
        Map<String, Cell> cells = new HashMap<>();
        for (int loc = 0; loc < entity.locations(); loc++) {
            if (entity.hasRearArmor(loc)) {
                cells.put(entity.getLocationAbbr(loc), GpuPaperdoll.cell(Math.max(0, entity.getArmor(loc, true)),
                      entity.getOArmor(loc, true), entity.isLocationBad(loc), Math.max(0, entity.getInternal(loc)),
                      false, View.ARMOR));
            }
        }
        return cells;
    }

    private static Map<String, Cell> structure(Entity entity) {
        Map<String, Cell> cells = new HashMap<>();
        for (int loc = 0; loc < entity.locations(); loc++) {
            int internal = Math.max(0, entity.getInternal(loc));
            cells.put(entity.getLocationAbbr(loc), GpuPaperdoll.cell(internal, entity.getOInternal(loc),
                  entity.isLocationBad(loc), internal, false, View.STRUCTURE));
        }
        return cells;
    }
}
