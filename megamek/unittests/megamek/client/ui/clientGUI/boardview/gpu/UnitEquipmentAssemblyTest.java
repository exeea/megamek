/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.client.ui.tileset.EquipmentModelPolicy;
import megamek.client.ui.tileset.UnitModelEquipment;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Exercises socket selection, node ownership and damage together without allocating GPU buffers. */
class UnitEquipmentAssemblyTest {
    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

    @Test
    void weaponsSharingAHardPointKeepTheStandardGapUnlessTheChassisSetsItsOwn() {
        assertEquals(.4f, UnitEquipmentAssembly.stackGap(new JsonReader().parse("{}")), 1e-6);
        assertEquals(-.2f, UnitEquipmentAssembly.stackGap(new JsonReader().parse("{\"stackGap\":-0.2}")), 1e-6);
    }

    @Test
    void armVentSpotsAreNeverTheAuthorsDefaultVents() {
        // A variant with no slotted heat sinks keeps the author's torso vents; the arm's spot is not one of them.
        JsonValue vents = new JsonReader().parse("""
              [{"node": "LA#vent-rear-0", "location": "LA", "side": "rear", "authored": true},
               {"node": "LT#vent-rear-1", "location": "LT", "side": "rear", "authored": true},
               {"node": "RT#vent-rear-2", "location": "RT", "side": "rear", "authored": true}]""");
        JsonValue descriptor = new JsonReader().parse("{}");
        assertEquals(List.of("LT", "RT"), UnitEquipmentAssembly.ventLocations(descriptor, vents, "rear", Map.of()));
        // With sinks slotted, the torso holding them takes the face's vents; the arm spot plays no part.
        assertEquals(List.of("LT", "LT"),
              UnitEquipmentAssembly.ventLocations(descriptor, vents, "rear", Map.of("LT", 2)));
    }

    @Test
    void rowsRunTheFaceWidthUnlessTheChassisSetsARowWidth() {
        assertEquals(5f, UnitEquipmentAssembly.rowWidth(new JsonReader().parse("{}"), 5f), 1e-6);
        assertEquals(3.4f, UnitEquipmentAssembly.rowWidth(new JsonReader().parse("{\"rowWidth\":3.4}"), 5f), 1e-6);
    }

    @ParameterizedTest
    @CsvSource({ "LT, LA, hand", "RT, RA, hand", "LT, LA, wrist", "RT, RA, wrist", "LT, LA, elbow", "RT, RA, elbow" })
    void splitGunFollowsActualArmAnatomyAndDamageWithoutDuplicatingEquipment(String torso, String arm, String form) {
        var anatomy = new UnitModelState.MekAnatomy("biped", form.equals("hand") ? List.of(arm) : List.of(),
              form.equals("elbow") ? List.of() : List.of(arm));
        var gun = mount(7, torso, arm);
        var model = assemble(anatomy, List.of(gun, gun, mount(8, torso, "")));
        try {
            assertEquals(2, model.equipment().size(), "One physical weapon per equipment index, including split criticals");
            var binding = model.equipment().stream().filter(item -> item.index() == 7).findFirst().orElseThrow();
            var torsoBinding = model.equipment().stream().filter(item -> item.index() == 8).findFirst().orElseThrow();
            assertEquals(arm + "@" + form, model.instance.getNode(binding.node()).getParent().id);
            assertEquals(torso, model.instance.getNode(torsoBinding.node()).getParent().id);
            assertEquals(torso, binding.location(), "Firing/damage identity still describes the authoritative mount");
            assertEquals(torso, gun.location());
            assertEquals(arm, gun.secondLocation());
            assertFalse(binding.embedded());
            assertEquals(1, binding.emitters().size());
            assertTrue(binding.emitters().getFirst().node().startsWith(arm + "-equipment-7-"));

            var damaged = new ModelInstance(model.instance.model);
            model.showEquipment(damaged, new UnitModelState.Appearance(Set.of(7), false, null));
            assertTrue(damaged.getNode(binding.emitters().getFirst().node()).parts.first().material.id
                  .endsWith(UnitDamageDisplay.WRECKED_SUFFIX), "A critical hit still finds the gun by its original index");
            assertFalse(damaged.getNode(torsoBinding.emitters().getFirst().node()).parts.first().material.id
                  .endsWith(UnitDamageDisplay.WRECKED_SUFFIX));
            UnitDamageDisplay.show(damaged, new BoardScene.LocationDamage(Set.of(arm), Set.of()));
            assertFalse(damaged.getNode(binding.emitters().getFirst().node()).parts.first().enabled,
                  "A blown-off arm cannot leave its split gun floating behind");
            assertTrue(damaged.getNode(torsoBinding.emitters().getFirst().node()).parts.first().enabled);
            assertTrue(model.instance.getNode(binding.emitters().getFirst().node()).parts.first().enabled,
                  "Damage cannot mutate the reusable assembly");
        } finally {
            model.dispose();
        }
    }

    @ParameterizedTest
    @CsvSource({ "RT, '', true", "LT, LA, false", "CT, HD, true", "LT, RA, true" })
    void unsplitTorsoGunsAndOtherLocationPairsKeepTheirPrimarySocket(String primary, String secondary, boolean mek) {
        var anatomy = mek ? new UnitModelState.MekAnatomy("biped", List.of("LA", "RA"), List.of("LA", "RA")) : null;
        var model = assemble(anatomy, List.of(mount(7, primary, secondary)));
        try {
            var binding = model.equipment().getFirst();
            assertEquals(primary, model.instance.getNode(binding.node()).getParent().id);
            assertEquals(primary, binding.location());
        } finally {
            model.dispose();
        }
    }

    @Test
    void aHeldGunLeavesTheMiddleOfItsGunBodyHoweverCrowdedTheArm() {
        // A big launcher packed into the same hand first used to shove the held barrel off the gun body's face.
        var anatomy = new UnitModelState.MekAnatomy("biped", List.of("RA"), List.of("RA"));
        var gun = new UnitModelEquipment.Mount(7, "Gun", "RA", "", false, false, 0, EquipmentModelPolicy.WEAPON,
              "ballistic", List.of());
        var launcher = new UnitModelEquipment.Mount(8, "Box", "RA", "", false, false, 0, EquipmentModelPolicy.WEAPON,
              "missile", List.of());
        var model = assembleHeld(anatomy, List.of(gun, launcher));
        try {
            var held = model.equipment().stream().filter(item -> item.index() == 7).findFirst().orElseThrow();
            var crowding = model.equipment().stream().filter(item -> item.index() == 8).findFirst().orElseThrow();
            assertFalse(held.embedded());
            assertFalse(crowding.embedded(), "The launcher still finds room of its own");
            Vector3 barrel = model.instance.getNode(held.node(), true).globalTransform.getTranslation(new Vector3());
            assertEquals(0f, barrel.x, 1e-4, "The barrel stays on the gun body's face, not beside it");
            assertEquals(0f, barrel.z, 1e-4, "The barrel stays on the gun body's face, not above or below it");
        } finally {
            model.dispose();
        }
    }

    /** One right arm with a hand whose spot draws a gun held in the fist, and a launcher spot on the same face. */
    private static GpuUnitModel assembleHeld(UnitModelState.MekAnatomy anatomy,
          List<UnitModelEquipment.Mount> equipment) {
        var library = mock(GpuUnitModels.class);
        when(library.descriptor("equipment.json")).thenReturn(new JsonReader().parse("""
              {"schema":2,"equipment":{"Gun":{"model":"gun.json","profiles":{"held":"gun.json"}},
               "Box":{"model":"box.json"}},"fallbacks":{"weapon":"gun.json"}}
              """));
        registerModule(library, "gun.json", "ballistic", new UnitModelDescriptor.Bounds(List.of(-.5f, 0f, -.5f),
              List.of(.5f, 2f, .5f)));
        registerModule(library, "box.json", "missile", new UnitModelDescriptor.Bounds(List.of(-3f, 0f, -3f),
              List.of(3f, 2f, 3f)));
        Model assembled = new Model();
        Node arm = node("RA");
        assembled.nodes.add(arm);
        arm.addChild(node("RA@hand"));
        List<UnitModelDescriptor.Hardpoint> points = List.of(
              new UnitModelDescriptor.Hardpoint("RA-hand", "RA", "front", "RA", List.of(0f, 0f, 0f),
                    List.of(0f, 0f, 0f, 1f), List.of(8f, 20f, 8f), .5f, 1f, List.of("weapon")),
              new UnitModelDescriptor.Hardpoint("RA@hand:missile-0", "RA", "front", "RA", List.of(0f, 0f, 0f),
                    List.of(0f, 0f, 0f, 1f), List.of(8f, 20f, 8f), .5f, 1f, List.of("weapon")));
        var recipe = new JsonReader().parse("""
              {"equipment":"equipment.json","mounts":[
               {"hardpoint":"RA-hand","form":"hand","profile":"held"},
               {"hardpoint":"RA@hand:missile-0","family":"missile","form":"hand"}]}
              """);
        assembled.calculateTransforms();
        var bounds = new UnitModelDescriptor.Bounds(List.of(-1f, 0f, -1f), List.of(1f, 2f, 1f));
        var body = new UnitModelDescriptor(2, "body", "mek", "body.g3dj", bounds, "biped-v1",
              Map.of("root", "RA"), Map.of(), points, List.of(), List.of(), Map.of(), null);
        var structure = new UnitModelState.Structure(EntityMovementMode.BIPED, equipment, List.of(), 0, false, anatomy);
        var bindings = UnitEquipmentAssembly.attachAll(library, recipe, new GpuUnitModels.ModularAsset(body, assembled, 1),
              structure, assembled);
        assembled.calculateTransforms();
        return new GpuUnitModel(assembled, "RA", true, bindings);
    }

    private static void registerModule(GpuUnitModels library, String asset, String family,
          UnitModelDescriptor.Bounds bounds) {
        Model shape = new Model();
        Node root = node("barrel");
        MeshPart mesh = new MeshPart();
        mesh.mesh = mock(Mesh.class);
        root.parts.add(new NodePart(mesh, new Material("paint")));
        shape.nodes.add(root);
        var emitter = new UnitModelDescriptor.Emitter("muzzle", "barrel", List.of(0f, 2f, 0f), List.of(0f, 1f, 0f),
              "muzzle", "bullet");
        var descriptor = new UnitModelDescriptor(2, "equipment", family, asset.replace(".json", ".g3dj"), bounds,
              "rigid-v1",
              Map.of("root", "barrel"), Map.of(), List.of(), List.of(emitter), List.of(), Map.of(), null);
        when(library.modular(asset)).thenReturn(new GpuUnitModels.ModularAsset(descriptor, shape, 1));
    }

    private static UnitModelEquipment.Mount mount(int index, String primary, String secondary) {
        return new UnitModelEquipment.Mount(index, "Gun", primary, secondary, false, false, 0,
              EquipmentModelPolicy.WEAPON, "ballistic", List.of());
    }

    private static GpuUnitModel assemble(UnitModelState.MekAnatomy anatomy, List<UnitModelEquipment.Mount> equipment) {
        var library = mock(GpuUnitModels.class);
        when(library.descriptor("equipment.json")).thenReturn(new JsonReader().parse("""
              {"schema":2,"equipment":{"Gun":{"model":"gun.json"}},"fallbacks":{"weapon":"gun.json"}}
              """));
        var bounds = new UnitModelDescriptor.Bounds(List.of(-1f, 0f, -1f), List.of(1f, 2f, 1f));
        Model gun = new Model();
        Node barrel = node("barrel");
        MeshPart mesh = new MeshPart();
        mesh.mesh = mock(Mesh.class);
        barrel.parts.add(new NodePart(mesh, new Material("paint")));
        gun.nodes.add(barrel);
        var emitter = new UnitModelDescriptor.Emitter("muzzle", "barrel", List.of(0f, 2f, 0f), List.of(0f, 1f, 0f),
              "muzzle", "bullet");
        var weapon = new UnitModelDescriptor(2, "equipment", "ballistic", "gun.g3dj", bounds, "rigid-v1",
              Map.of("root", "barrel"), Map.of(), List.of(), List.of(emitter), List.of(), Map.of(), null);
        when(library.modular("gun.json")).thenReturn(new GpuUnitModels.ModularAsset(weapon, gun, 1));

        Model assembled = new Model();
        List<UnitModelDescriptor.Hardpoint> points = new ArrayList<>();
        var recipe = new JsonReader().parse("{\"equipment\":\"equipment.json\",\"mounts\":[]}");
        for (String location : List.of("CT", "LT", "RT", "LA", "RA")) {
            Node part = node(location);
            assembled.nodes.add(part);
            for (String form : location.endsWith("A") ? List.of("hand", "wrist", "elbow") : List.of("")) {
                String id = location + (form.isEmpty() ? "" : "@" + form);
                if (!form.isEmpty()) { part.addChild(node(id)); }
                points.add(new UnitModelDescriptor.Hardpoint(id, location, "front", id,
                      List.of(0f, 0f, 0f), List.of(0f, 0f, 0f, 1f), List.of(20f, 20f, 20f), .5f, 1f, List.of("weapon")));
                JsonValue preference = new JsonReader().parse("{\"hardpoint\":\"" + id + "\",\"form\":\"" + form + "\"}");
                recipe.get("mounts").addChild(preference);
            }
        }
        assembled.calculateTransforms();
        var body = new UnitModelDescriptor(2, "body", "mek", "body.g3dj", bounds, "biped-v1",
              Map.of("root", "CT"), Map.of(), points, List.of(), List.of(), Map.of(), null);
        var structure = new UnitModelState.Structure(EntityMovementMode.BIPED, equipment, List.of(), 0, false, anatomy);
        var bindings = UnitEquipmentAssembly.attachAll(library, recipe, new GpuUnitModels.ModularAsset(body, assembled, 1),
              structure, assembled);
        assembled.calculateTransforms();
        return new GpuUnitModel(assembled, "CT", true, bindings);
    }

    private static Node node(String id) {
        Node node = new Node();
        node.id = id;
        return node;
    }
}
