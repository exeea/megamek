/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.GunEmplacement;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual equipment files, production GLB loader, assembler, LOD switching and game shader. */
@Tag("on-demand")
class GpuBuildingTurretSmokeTest {
    private record Fixture(String label, Entity entity) { }
    private record View(String label, GpuUnitInstance instance, int triangles) { }

    @Test
    void actualLoadoutsKeepAllWeaponsAndEmittersAtBothDetailLevels() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1800, 1600);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var models = new ArrayList<GpuUnitModel>();
                var batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    EquipmentType.initializeTypes();
                    var fixtures = new ArrayList<Fixture>();
                    for (var entry : List.of(
                          "Light Turrets/Light Sniper Turret 2300.blk",
                          "Medium Turrets/Medium Sniper Turret 2300.blk",
                          "Medium Turrets/Medium Missile Turret 3039.blk",
                          "Heavy Turrets/Heavy Laser Turret 2300.blk",
                          "Heavy Turrets/Heavy Shredder Turret 2300.blk",
                          "Heavy Turrets/Heavy Flak Turret 2300.blk",
                          "Heavy Turrets/Heavy Missile Turret 2300.blk")) {
                        var entity = new MekFileParser(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"),
                              "ge/" + entry).getEntity();
                        fixtures.add(new Fixture(entity.getChassis(), entity));
                    }
                    fixtures.add(example("Single PPC", "PPC"));
                    fixtures.add(example("Twin LRM 20", "LRM 20", "LRM 20"));
                    fixtures.add(example("AC + SRM 6", "Autocannon/5", "SRM 6"));
                    fixtures.add(example("Twin large lasers", "Large Laser", "Large Laser"));
                    fixtures.add(example("SRM 2 + 4 + 6", "SRM 2", "SRM 4", "SRM 6"));
                    fixtures.add(example("Twin LRM 5 pods", "LRM 5", "LRM 5"));
                    fixtures.add(example("Single SRM 4 pod", "SRM 4"));
                    fixtures.add(example("LRM 10 + AC + PPC", "LRM 10", "Autocannon/10", "PPC"));
                    fixtures.add(example("Quad SRM 6", "SRM 6", "SRM 6", "SRM 6", "SRM 6"));
                    fixtures.add(example("Six LRM 15 + AC", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15",
                          "Autocannon/5"));
                    var recipe = library.descriptor(BuildingTurretVisual.ROOT + "turret.json");
                    var unsupported = UnitModelState.capture(example("Gauss", "Gauss Rifle").entity());
                    assertThrows(IllegalArgumentException.class,
                          () -> BuildingTurretVisual.assemble(library, recipe, unsupported.structure()),
                          "An unsupported weapon must not silently become a small legacy module");
                    // Any mix assembles: launchers take side arms, extra exposed guns and launchers use front bays.
                    var mixedLoadout = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(
                          example("LRM + AC + PPC", "LRM 10", "Autocannon/10", "PPC").entity()).structure());
                    models.add(mixedLoadout);
                    assertEquals(1, arms(mixedLoadout, "right"), "A launcher beside front guns rides a flank arm");
                    assertEquals(2, mixedLoadout.equipment().stream().filter(b -> !b.pitchOnly()).count());
                    var quad = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(
                          example("Quad SRM 6", "SRM 6", "SRM 6", "SRM 6", "SRM 6").entity()).structure());
                    models.add(quad);
                    assertEquals(2, arms(quad, "left"), "The pillar stacks two launchers per side");
                    assertEquals(2, arms(quad, "right"));
                    var overflow = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(example("Seven",
                          "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "Autocannon/5").entity()).structure());
                    models.add(overflow);
                    assertEquals(4, arms(overflow, "left") + arms(overflow, "right"));
                    assertEquals(1, overflow.equipment().stream().filter(b -> b.asset().endsWith("-pod.json")).count(),
                          "The pillar roof takes one launcher yoke");
                    assertEquals(2, overflow.equipment().stream().filter(b -> !b.pitchOnly()).count(),
                          "A launcher beyond the pillar's mounts sits encased in the front bay with the cannon");
                    var exposedBank = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                    exposedBank.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                          "{\"center\":{\"encased\":false},\"left-1\":{\"encased\":false},\"right-1\":{\"encased\":false}}"));
                    var threeGuns = BuildingTurretVisual.assemble(library, exposedBank, UnitModelState.capture(
                          example("Three external guns", "Autocannon/5", "Autocannon/5", "Autocannon/5").entity()).structure());
                    models.add(threeGuns);
                    assertEquals(2, threeGuns.equipment().stream().filter(b -> b.pitchOnly()).count(),
                          "Two roof cradles; the third exposed gun moves into a front bay");
                    var mixedGuns = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                    mixedGuns.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                          "{\"left\":{\"encased\":false},\"right\":{\"encased\":true}}"));
                    var exposedAndFront = BuildingTurretVisual.assemble(library, mixedGuns, UnitModelState.capture(
                          example("Mixed cannon installation", "Autocannon/5", "Autocannon/5").entity()).structure());
                    models.add(exposedAndFront);
                    var roofGun = exposedAndFront.equipment().stream().filter(b -> b.pitchOnly()).findFirst().orElseThrow();
                    assertEquals(BuildingTurretVisual.FRONT_BAY_DEPRESSION, roofGun.depression(), .001f,
                          "An exposed cannon stops depressing before it reaches the front-mounted cannon");
                    var report = new StringBuilder("Fixture,LOD0,LOD1,bindings\n");
                    var levels = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : fixtures) {
                        var state = UnitModelState.capture(fixture.entity());
                        var model = BuildingTurretVisual.assemble(library, recipe, state.structure());
                        models.add(model);
                        var weapons = fixture.entity().getWeaponList();
                        for (var weapon : weapons) {
                            var binding = model.equipment().stream().filter(b -> b.index() == weapon.getEquipmentNum()).findFirst().orElseThrow();
                            assertFalse(binding.embedded(), fixture.label());
                            assertFalse(binding.emitters().isEmpty(), fixture.label());
                            assertTrue(binding.asset().startsWith(BuildingTurretVisual.ROOT), binding.asset());
                        }
                        assertEquals(model.equipment().size(), model.equipment().stream().map(b -> b.index()).distinct().count());
                        int[] counts = new int[2];
                        for (int lod = 0; lod < 2; lod++) {
                            var instance = new GpuUnitInstance(model);
                            instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                            assertEquals(lod, instance.detailLevel(), fixture.label());
                            counts[lod] = triangles(instance.nodes);
                            for (var binding : model.equipment()) {
                                assertTrue(triangles(List.of(instance.getNode(binding.node()))) > 0, fixture.label() + " LOD" + lod);
                                for (var emitter : binding.emitters()) {
                                    assertNotNull(instance.getNode(emitter.node()), emitter.node());
                                    var p = new Vector3(); var d = new Vector3();
                                    UnitModelAttachment.emitter(instance, emitter, p, d);
                                    assertTrue(Float.isFinite(p.len()) && Math.abs(d.len()-1) < .001f);
                                }
                            }
                            levels.get(lod).add(new View(fixture.label(), instance, counts[lod]));
                        }
                        report.append(fixture.label()).append(',').append(counts[0]).append(',').append(counts[1])
                              .append(',').append(model.equipment().size()).append('\n');
                        assertTrue(counts[1] < counts[0], fixture.label());
                        checkRecoil(model);
                        checkRetainedYaw(model, state);
                    }
                    Path output = Path.of(System.getProperty("megamek.gpu.screenshots"));
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("turret-palette-counts.csv"), report);
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, levels.get(lod), output, "turret-palette", lod, false);
                        palette(batch, levels.get(lod), output, "turret-palette", lod, true);
                        palette(batch, levels.get(lod).subList(0, 4), output, "receiver-housings-rear", lod, false);
                    }
                    var completeWeapons = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : List.of(example("AC/2", "Autocannon/2"), example("AC/5", "Autocannon/5"),
                          example("AC/10", "Autocannon/10"), example("AC/20", "Autocannon/20"), example("MG", "Machine Gun"),
                          example("Small laser", "Small Laser"), example("Medium laser", "Medium Laser"),
                          example("Large laser", "Large Laser"), example("Light PPC", "Light PPC"),
                          example("PPC", "PPC"), example("Heavy PPC", "Heavy PPC"),
                          example("Twin AC", "Autocannon/5", "Autocannon/5"), example("AC + PPC", "Autocannon/5", "PPC"))) {
                        for (boolean encased : new boolean[] { false, true }) {
                            var spec = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            spec.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                                  "{\"center\":{\"encased\":" + encased + "},\"left\":{\"encased\":" + encased
                                        + "},\"right\":{\"encased\":" + encased + "}}"));
                            var state = UnitModelState.capture(fixture.entity());
                            var model = BuildingTurretVisual.assemble(library, spec, state.structure()); models.add(model);
                            checkRecoil(model); checkRetainedYaw(model, state);
                            for (var binding : model.equipment()) {
                                assertEquals(!encased, binding.pitchOnly());
                                assertEquals(!encased, binding.asset().endsWith("-exposed.json"));
                                if (!encased) {
                                    var hinge = model.instance.getNode(binding.aimNode()).globalTransform.getTranslation(new Vector3());
                                    assertTrue(hinge.z > 16 && hinge.z < 18 && hinge.y < 1,
                                          "Complete gun is seated in hull shoulders with receiver elevation clearance");
                                    assertNotNull(model.instance.getNode(binding.node() + "-cradle"));
                                }
                            }
                            for (int lod = 0; lod < 2; lod++) {
                                var instance = new GpuUnitInstance(model); instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                completeWeapons.get(lod).add(new View(fixture.label() + (encased ? " | armored bay" : " | exposed"),
                                      instance, triangles(instance.nodes)));
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, completeWeapons.get(lod), output, "weapon-installations", lod, false);
                        palette(batch, completeWeapons.get(lod), output, "weapon-installations", lod, true);
                    }
                    var pitchedLaunchers = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : List.of(example("AC + SRM 4", "Autocannon/5", "SRM 4"),
                          example("AC + LRM 20", "Autocannon/5", "LRM 20"),
                          example("Twin LRM 20", "LRM 20", "LRM 20"))) {
                        var state = UnitModelState.capture(fixture.entity());
                        var model = BuildingTurretVisual.assemble(library, recipe, state.structure()); models.add(model);
                        for (int lod = 0; lod < 2; lod++) {
                            for (int pitch : new int[] { -20, 0, 25 }) {
                                var instance = new GpuUnitInstance(model);
                                instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                for (var binding : model.equipment()) {
                                    if (binding.pitchOnly()) {
                                        instance.getNode(binding.aimNode()).rotation.set(Vector3.X, pitch);
                                    }
                                }
                                instance.calculateTransforms();
                                pitchedLaunchers.get(lod).add(new View(fixture.label() + " | pitch " + pitch,
                                      instance, triangles(instance.nodes)));
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, pitchedLaunchers.get(lod), output, "mixed-installations", lod, false);
                    }
                    var exposures = List.of(new ArrayList<View>(), new ArrayList<View>());
                    var launchers = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var example : List.of(example("AC", "Autocannon/5"), example("Laser", "Medium Laser"),
                          example("PPC", "PPC"), example("SRM 2", "SRM 2"), example("SRM 4", "SRM 4"),
                          example("SRM 6", "SRM 6"), example("LRM 5", "LRM 5"), example("LRM 10", "LRM 10"),
                          example("LRM 15", "LRM 15"), example("LRM 20", "LRM 20"))) {
                        var state = UnitModelState.capture(example.entity());
                        var full = BuildingTurretVisual.assemble(library, recipe, state.structure()); models.add(full);
                        var emitter = full.equipment().getFirst().emitters().getFirst();
                        var module = library.modular(full.equipment().getFirst().asset());
                        boolean launcher = module.descriptor().family().equals("missile");
                        if (launcher) {
                            var invalid = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            invalid.addChild("installations", new com.badlogic.gdx.utils.JsonReader()
                                  .parse("{\"center\":{\"insertion\":1.5}}"));
                            var error = assertThrows(IllegalArgumentException.class,
                                  () -> BuildingTurretVisual.assemble(library, invalid, state.structure()));
                            assertTrue(error.getMessage().contains("encased true/false"));
                        }
                        float length = module.descriptor().bounds().max().get(1);
                        var initial = new Vector3();
                        UnitModelAttachment.emitter(full.instance, emitter, initial, new Vector3());
                        for (float fraction : launcher ? new float[] { 0, .75f } : new float[] { 0, .4f, .7f, .88f }) {
                            var spec = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            var installations = new com.badlogic.gdx.utils.JsonValue(com.badlogic.gdx.utils.JsonValue.ValueType.object);
                            var center = new com.badlogic.gdx.utils.JsonValue(com.badlogic.gdx.utils.JsonValue.ValueType.object);
                            if (launcher) { center.addChild("encased", new com.badlogic.gdx.utils.JsonValue(fraction > 0)); }
                            else { center.addChild("insertion", new com.badlogic.gdx.utils.JsonValue(length * fraction)); }
                            installations.addChild("center", center); spec.addChild("installations", installations);
                            var model = BuildingTurretVisual.assemble(library, spec, state.structure()); models.add(model);
                            var tip = new Vector3();
                            UnitModelAttachment.emitter(model.instance, model.equipment().getFirst().emitters().getFirst(), tip, new Vector3());
                            var sourceBinding = full.equipment().getFirst();
                            var installedBinding = model.equipment().getFirst();
                            if (launcher) {
                                assertEquals(fraction == 0, installedBinding.asset().endsWith("-pod.json"));
                                assertEquals(fraction == 0, installedBinding.pitchOnly());
                                if (fraction == 0) {
                                    var hinge = model.instance.getNode(installedBinding.aimNode()).globalTransform.getTranslation(new Vector3());
                                    assertTrue(hinge.z > 18 && Math.abs(hinge.y) < 1, "External pod pivots above the roof centre");
                                    assertTrue(tip.z > 16 && tip.y < 5.1f, "Exposed pod is carried over the hull, not stuck on its nose");
                                } else {
                                    var recessed = library.modular(installedBinding.asset());
                                    var muzzle = installedBinding.emitters().getFirst();
                                    assertEquals(9.45f - .75f * recessed.descriptor().bounds().max().get(1)
                                          + muzzle.position().get(1), tip.y, .03f);
                                }
                            } else {
                                assertEquals(initial.y - length * fraction, tip.y, .03f, "Recessed emitter follows weapon without scaling");
                                assertEquals(initial.x, tip.x, .001f);
                                assertEquals(initial.z, tip.z, .001f);
                            }
                            assertEquals(sourceBinding.emitters().size(), installedBinding.emitters().size());
                            for (int cell = 0; cell < sourceBinding.emitters().size(); cell++) {
                                var originalCell = new Vector3(); var installedCell = new Vector3();
                                UnitModelAttachment.emitter(full.instance, sourceBinding.emitters().get(cell), originalCell, new Vector3());
                                UnitModelAttachment.emitter(model.instance, installedBinding.emitters().get(cell), installedCell, new Vector3());
                                assertTrue(originalCell.sub(initial).epsilonEquals(installedCell.sub(tip), .001f), "Cell spacing is invariant");
                            }
                            assertTrue(full.instance.getNode(sourceBinding.node()).scale
                                  .epsilonEquals(model.instance.getNode(installedBinding.node()).scale, .001f));
                            for (int lod = 0; lod < 2; lod++) {
                                var instance = new GpuUnitInstance(model); instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                String label = launcher ? example.label() + (fraction == 0 ? " | exposed" : " | encased 75%")
                                      : example.label() + " | inserted " + Math.round(fraction * 100) + "%";
                                (launcher ? launchers : exposures).get(lod).add(new View(label, instance, triangles(instance.nodes)));
                                var lodTip = new Vector3();
                                UnitModelAttachment.emitter(instance, installedBinding.emitters().getFirst(), lodTip, new Vector3());
                                assertTrue(lodTip.epsilonEquals(tip, .001f), "LOD keeps installation and launch position");
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, exposures.get(lod), output, "turret-exposure", lod, false);
                        palette(batch, launchers.get(lod), output, "missile-installations", lod, false);
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        var components = new ArrayList<View>();
                        var root = Configuration.dataDir().toPath().resolve("models/" + BuildingTurretVisual.ROOT);
                        try (var files = Files.list(root)) {
                            for (var file : files.filter(p -> p.toString().endsWith(".glb")).sorted().toList()) {
                                String name = file.getFileName().toString().replace(".glb", "");
                                var asset = library.modular(BuildingTurretVisual.ROOT + name + ".json");
                                var instance = new GpuUnitInstance(asset.model(lod));
                                components.add(new View(name, instance, triangles(instance.nodes)));
                            }
                        }
                        for (int start = 0; start < components.size(); start += 16) {
                            var page = components.subList(start, Math.min(start + 16, components.size()));
                            String name = start == 0 ? "weapon-components" : "weapon-components-page" + (start / 16 + 1);
                            palette(batch, page, output, name, lod, false);
                            palette(batch, page, output, name, lod, true);
                        }
                        var detailNames = List.of("autocannon-10-exposed", "laser-medium-exposed", "ppc-exposed",
                              "srm-2-pod", "srm-4-pod", "lrm-20-pod");
                        palette(batch, components.stream().filter(v -> detailNames.contains(v.label())).toList(),
                              output, "weapon-components-detail", lod, false);
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    models.forEach(GpuUnitModel::dispose);
                    batch.dispose(); library.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Turret palette failed", failure.get()); }
    }

    private static long arms(GpuUnitModel model, String side) {
        return model.equipment().stream().filter(b -> b.asset().endsWith("-arm-" + side + ".json")).count();
    }

    private static Fixture example(String label, String... weapons) throws Exception {
        var entity = new GunEmplacement();
        for (var name : weapons) {
            var type = EquipmentType.get(name);
            assertNotNull(type, name);
            entity.addEquipment(type, GunEmplacement.LOC_GUNS);
        }
        return new Fixture(label, entity);
    }

    private static int triangles(Iterable<Node> nodes) {
        int count = 0;
        for (var node : nodes) {
            for (var part : node.parts) { if (part.enabled) { count += part.meshPart.size / 3; } }
            count += triangles(node.getChildren());
        }
        return count;
    }

    private static void checkRecoil(GpuUnitModel model) {
        var posed = new GpuUnitInstance(model);
        for (var binding : model.equipment()) {
            if (binding.aimNode() == null) { continue; }
            var tip = binding.emitters().getFirst();
            var before = new Vector3(); var after = new Vector3();
            UnitModelAttachment.emitter(posed, tip, before, new Vector3());
            var mount = posed.getNode(binding.node());
            var rest = mount.translation.cpy();
            var collar = posed.getNode(binding.node() + "-collar");
            var collarRest = collar == null ? null : collar.globalTransform.cpy();
            var cradle = posed.getNode(binding.node() + "-cradle");
            var cradleRest = cradle == null ? null : cradle.globalTransform.cpy();
            UnitAnimator.applyWeaponRecoil(posed, model.instance, binding, 1);
            UnitModelAttachment.emitter(posed, tip, after, new Vector3());
            assertTrue(mount.translation.epsilonEquals(rest, .0001f), "The attachment must not recoil");
            if (collar != null) { assertArrayEquals(collarRest.val, collar.globalTransform.val, .0001f, "Receiver stays on its bearings"); }
            if (cradle != null) { assertArrayEquals(cradleRest.val, cradle.globalTransform.val, .0001f, "Cradle stays seated in the roof"); }
            if (binding.recoilNode() == null) { assertTrue(after.epsilonEquals(before, .0001f)); }
            else {
                assertTrue(before.y > after.y + .1f);
                UnitAnimator.applyWeaponRecoil(posed, model.instance, binding, 0);
                UnitModelAttachment.emitter(posed, tip, after, new Vector3());
                assertTrue(after.epsilonEquals(before, .0001f), "Return to battery");
            }
        }
    }

    private static void checkRetainedYaw(GpuUnitModel model, UnitModelState state) {
        var source = UnitPlaybackTest.unit(1, 0);
        var appearance = new BoardScene.UnitModel("", "", "", 1, 0, BoardScene.LocationDamage.NONE, state);
        var unit = new BoardScene.Unit(1, -1, "Turret", source.location(), null, false, null, 2, false, appearance, 0);
        var placed = new GpuUnitInstance(model);
        var animator = new UnitAnimator();
        var motion = new UnitMotion(unit.location()).sample();
        animator.apply(model, placed, unit, motion, 0, 0, false, 0);
        var bearing = placed.getNode("yaw");
        var restForward = new Vector3(Vector3.Y).mul(bearing.rotation);
        var fixed = placed.getNode("mount").globalTransform.cpy();
        var attack = new UnitAttack(UnitPlaybackTest.attack(unit, UnitPlaybackTest.unit(2, 3), ResolvedAttack.Kind.SHOT, true));
        attack.seconds = UnitAttack.ANTICIPATION_SECONDS + UnitAttack.RECOIL_KICK_SECONDS;
        animator.aimShots(model, unit, List.of(attack), ignored -> null);
        var fired = bearing.rotation.cpy();
        for (var binding : model.equipment()) {
            if (!binding.pitchOnly()) { continue; }
            var aim = placed.getNode(binding.aimNode());
            assertTrue(new Vector3(Vector3.X).mul(aim.rotation).epsilonEquals(Vector3.X, .001f), "Trunnion remains aligned with the cradle");
            assertTrue(placed.getNode(binding.node() + "-cradle").translation
                  .epsilonEquals(model.instance.getNode(binding.node() + "-cradle").translation, .001f));
        }
        assertTrue(restForward.dot(new Vector3(Vector3.Y).mul(fired)) < .99f, "Bearing must turn toward the target");
        assertTrue(new Vector3(Vector3.Z).mul(fired).epsilonEquals(Vector3.Z, .001f), "Bearing may yaw, never pitch");
        assertArrayEquals(fixed.val, placed.getNode("mount").globalTransform.val, .001f, "Fixed collar stays attached");
        animator.apply(model, placed, unit, motion, 1, 1, false, 0);
        assertEquals(1, Math.abs(fired.dot(bearing.rotation)), .001f, "Keep last firing direction");
        var rebound = new GpuUnitInstance(model);
        rebound.bodyDetail(.01f, false);
        animator.apply(model, rebound, unit, motion, 2, 1, false, 0);
        assertEquals(1, Math.abs(fired.dot(rebound.getNode("yaw").rotation)), .001f, "Rebinding at LOD1 retains direction");
    }

    private static void palette(ModelBatch batch, List<View> views, Path output, String name, int lod, boolean top) {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        int cols = views.size() > 12 || name.equals("turret-exposure") ? 4 : 3;
        int rows = (views.size() + cols - 1) / cols;
        int cw = w / cols, ch = h / rows;
        var env = new Environment();
        env.set(ColorAttribute.createAmbientLight(.7f, .7f, .7f, 1));
        env.add(new DirectionalLight().set(.8f, .8f, .8f, -.4f, -.7f, -1));
        float width = top ? 78 : 52;
        var camera = new OrthographicCamera(width, width * ch / (float) cw);
        camera.near = 1; camera.far = 1000;
        try (var surface = new GpuSceneTestOutput(w, h)) {
            surface.begin(.16f, .18f, .20f, 1);
            for (int i = 0; i < views.size(); i++) {
                Gdx.gl.glDepthMask(true); Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
                Gdx.gl.glViewport((i % cols) * cw, (rows - 1 - i / cols) * ch, cw, ch);
                camera.position.set(top ? new Vector3(0, 0, 200) : new Vector3(60, name.endsWith("-rear") ? -100 : 100, 67));
                camera.up.set(top ? Vector3.Y : Vector3.Z);
                if (name.startsWith("weapon-components")) {
                    var bounds = views.get(i).instance().calculateBoundingBox(new com.badlogic.gdx.math.collision.BoundingBox());
                    var center = bounds.getCenter(new Vector3()); var size = bounds.getDimensions(new Vector3());
                    camera.viewportWidth = Math.max(6, Math.max(size.x, Math.max(size.y, size.z)) * 1.8f);
                    camera.viewportHeight = camera.viewportWidth * ch / (float) cw;
                    camera.position.add(center); camera.lookAt(center);
                } else { camera.lookAt(0, 5, top ? 0 : 8); }
                camera.update();
                batch.begin(camera); batch.render(views.get(i).instance(), env); batch.end();
            }
            Gdx.gl.glViewport(0, 0, w, h); surface.display();
            var font = new BitmapFont(); var captions = new SpriteBatch();
            try {
                font.getData().setScale(1.3f);
                captions.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, w, h));
                captions.begin();
                for (int i = 0; i < views.size(); i++) {
                    var view = views.get(i);
                    font.draw(captions, view.label() + " | LOD" + lod + " | " + view.triangles() + " tris",
                          (i % cols) * cw + 16, (rows - i / cols) * ch - 15);
                }
                captions.end();
            } finally { captions.dispose(); font.dispose(); }
            var pixels = Pixmap.createFromFrameBuffer(0, 0, w, h);
            try { PixmapIO.writePNG(new FileHandle(output.resolve(name + "-lod" + lod + (top ? "-top" : "") + ".png").toFile()), pixels, -1, true); }
            finally { pixels.dispose(); }
        }
    }
}
