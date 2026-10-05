/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Disposable;

/** One shared GLB kit and compact assembly recipes. Only the kit and generated interiors own GPU resources. */
final class GpuBuilding implements Disposable {
    static final float LEVEL_HEIGHT = BoardGeometry.MODEL_LEVEL_HEIGHT;
    private static final Pattern PART = Pattern.compile(".*-(base|floor|roof)(0|[1-9][0-9]*)");
    private final List<Model> models = new ArrayList<>();
    private final List<Map<String, String>> names;
    private final Map<Recipe, Assembly> assemblies = new HashMap<>();
    private final List<String> roles;
    private final Map<String, Area> footprints = new HashMap<>();
    private final Map<String, List<Vector3>> triangles = new HashMap<>();
    private final Map<String, BoundingBox> bounds = new HashMap<>();
    private final Map<Interior, Model> interiors = new HashMap<>();

    private record Interior(List<Vector3> footprint, int levels) { }
    private record Recipe(String modules, boolean withInterior) { }

    // One module index per character; offsets are implicit (index * LEVEL_HEIGHT). No expanded Model is cached.
    record Assembly(String modules, Model interior, GpuBuilding kit) {
        int lodCount() { return kit.models.size(); }
        Model model(int lod) { return kit.models.get(lod); }
        ModelInstance instance(int lod) { return kit.instance(modules, lod); }
        boolean hit(Ray ray, Vector3 result) { return kit.hit(modules, ray, result); }
    }

    GpuBuilding(FileHandle file, Path textureRoot, Function<ModelData, Model> upload) {
        var data = lods(file.nameWithoutExtension(), RigidGlb.loadLods(file, textureRoot));
        names = data.stream().map(GpuBuilding::parts).toList();
        roles = names.getFirst().keySet().stream().sorted().toList();
        require(roles.size() <= Character.MAX_VALUE + 1, "Too many building modules");
        for (var level : names) {
            require(level.keySet().equals(names.getFirst().keySet()), "Building variants must match across LODs");
        }
        try {
            for (int lod = 0; lod < data.size(); lod++) {
                int previous = data.indexOf(data.get(lod));
                models.add(previous < lod ? models.get(previous) : upload.apply(data.get(lod)));
            }
            for (int lod = 0; lod < models.size(); lod++) {
                if (models.indexOf(models.get(lod)) < lod) { continue; }
                for (var entry : names.get(lod).entrySet()) {
                    Node node = models.get(lod).getNode(entry.getValue());
                    BoundingBox bounds = node.calculateBoundingBox(new BoundingBox());
                    require(bounds.isValid(), "Empty building module: " + entry.getValue());
                    if (!entry.getKey().startsWith("roof")) {
                        require(Math.abs(bounds.getDepth() - LEVEL_HEIGHT) < .001f,
                              entry.getValue() + " must be exactly one level (18 units) high");
                    }
                    // Authoring placement and object origins are display-only. Derive each Lego piece's
                    // centered footprint and base from its geometry, including baked vertex translations.
                    node.translation.add(moduleOffset(bounds));
                }
                models.get(lod).calculateTransforms();
            }
            // Every kit shares its picking geometry; only enterable buildings need wall/roof footprints.
            for (var entry : names.getFirst().entrySet()) {
                var node = models.getFirst().getNode(entry.getValue());
                List<Vector3> geometry = GpuTerrain.triangles(node);
                triangles.put(entry.getKey(), geometry);
                bounds.put(entry.getKey(), node.calculateBoundingBox(new BoundingBox()));
            }
        } catch (RuntimeException | Error error) {
            dispose();
            throw error;
        }
    }

    /** Trim trailing fallbacks, retaining authored level numbers and any gaps filled by the generic reader. */
    static List<ModelData> lods(String name, List<ModelData> loaded) {
        require(loaded.getFirst().id.equals(MeshLod.name(name, 0)), "Building requires -lod0");
        int last = loaded.size() - 1;
        while (last > 0 && loaded.get(last) == loaded.get(last - 1)) { last--; }
        return List.copyOf(loaded.subList(0, last + 1));
    }

    /** Validate module roles before allocating GPU resources. Sparse variant numbers are supported. */
    static Map<String, String> parts(ModelData data) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var node : data.nodes) {
            var match = PART.matcher(node.id);
            require(match.matches(), "Expected -baseN, -floorN or -roofN: " + node.id);
            require(node.parts != null && node.parts.length > 0 && (node.children == null || node.children.length == 0),
                  "Building modules must be mesh nodes: " + node.id);
            String role = match.group(1) + match.group(2);
            require(result.put(role, node.id) == null, "Duplicate building module: " + role);
        }
        require(result.keySet().stream().anyMatch(id -> id.startsWith("base")), "Building requires a base variant");
        require(result.keySet().stream().anyMatch(id -> id.startsWith("floor")), "Building requires a floor variant");
        require(result.keySet().stream().anyMatch(id -> id.startsWith("roof")), "Building requires a roof variant");
        return Map.copyOf(result);
    }

    /** A stable per-placement seed, independent of camera, height, LOD, frame order and JVM hash randomization. */
    static long seed(BoardScene.Tile tile, BoardScene.Feature feature) {
        return tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L ^ feature.asset().hashCode();
    }

    /** Authored module origins are display-only; the mesh bounds define placement for rendering and clearance. */
    static Vector3 moduleOffset(BoundingBox bounds) {
        Vector3 center = bounds.getCenter(new Vector3());
        return new Vector3(-center.x, -center.y, -bounds.min.z);
    }

    /** A stable per-placement seed, independent of camera, height, LOD, frame order and JVM hash randomization. */
    static List<String> select(Map<String, String> parts, int levels, long seed) {
        require(levels >= 1, "A building needs at least one level");
        List<String> roofs = parts.keySet().stream().filter(id -> id.startsWith("roof")).sorted().toList();
        List<String> bases = parts.keySet().stream().filter(id -> id.startsWith("base")).sorted().toList();
        List<String> floors = parts.keySet().stream().filter(id -> id.startsWith("floor")).sorted().toList();
        SplittableRandom random = new SplittableRandom(seed);
        // Choose roof and base before floors so height edits preserve them and the existing lower floors.
        String roof = roofs.get(random.nextInt(roofs.size()));
        List<String> result = new ArrayList<>();
        result.add(bases.get(random.nextInt(bases.size())));
        for (int level = 1; level < levels; level++) { result.add(floors.get(random.nextInt(floors.size()))); }
        result.add(roof);
        return List.copyOf(result);
    }

    Assembly assemble(int levels, long seed) {
        return assemble(levels, seed, true);
    }

    /** Industrial terrain and fuel tanks borrow the same modules without allocating occupiable interiors. */
    Assembly assemble(int levels, long seed, boolean withInterior) {
        List<String> selected = select(names.getFirst(), levels, seed);
        char[] modules = new char[selected.size()];
        for (int i = 0; i < modules.length; i++) { modules[i] = (char) roles.indexOf(selected.get(i)); }
        return assemblies.computeIfAbsent(new Recipe(new String(modules), withInterior), key -> {
            if (!withInterior) { return new Assembly(key.modules(), null, this); }
            Area volume = new Area(footprint(selected.getLast()));
            for (String floor : selected.subList(0, selected.size() - 1).stream().distinct().toList()) {
                volume.intersect(footprint(floor));
            }
            var footprint = GpuBuildingInterior.triangles(volume);
            Model interior = interiors.computeIfAbsent(new Interior(footprint, levels),
                  ignored -> GpuBuildingInterior.build(footprint, levels * LEVEL_HEIGHT, levels));
            return new Assembly(key.modules(), interior, this);
        });
    }

    private Area footprint(String role) {
        return footprints.computeIfAbsent(role, key -> {
            boolean roof = key.startsWith("roof");
            // The full roof projection preserves notches; the simplest wall LOD excludes facade seams and ledges.
            Area area = roof ? GpuBuildingInterior.area(triangles.get(key))
                  : GpuBuildingInterior.walls(GpuTerrain.triangles(models.getLast().getNode(names.getLast().get(key))),
                        LEVEL_HEIGHT * .5f);
            require(!area.isEmpty(), names.getFirst().get(key) + (roof ? " requires a roof footprint"
                  : " requires a closed mid-storey wall outline"));
            return area;
        });
    }

    /** Create only the scene instance, at load/edit or a LOD transition; mesh buffers stay in the shared kit. */
    private ModelInstance instance(String modules, int lod) {
        Model source = models.get(lod);
        ModelInstance instance = new ModelInstance(source, new String[0]);
        // Placement materials are needed for cutaway opacity; their textures remain shared.
        for (var material : source.materials) { instance.materials.add(material.copy()); }
        for (int level = 0; level < modules.length(); level++) {
            String role = roles.get(modules.charAt(level));
            Node node = source.getNode(names.get(lod).get(role)).copy();
            node.id = role + "-at-" + level;
            node.translation.z += level * LEVEL_HEIGHT;
            for (var part : node.parts) { part.material = instance.getMaterial(part.material.id); }
            instance.nodes.add(node);
        }
        instance.calculateTransforms();
        return instance;
    }

    /** Keep only assemblies used by installed/cached chunks, at the scene commit boundary on the GL thread. */
    void retain(Set<Assembly> live) {
        assemblies.values().removeIf(assembly -> !live.contains(assembly));
        Set<Model> needed = new java.util.HashSet<>();
        assemblies.values().forEach(assembly -> needed.add(assembly.interior()));
        interiors.values().removeIf(model -> {
            if (needed.contains(model)) { return false; }
            model.dispose();
            return true;
        });
    }

    /** Picking borrows one triangle set per module, never expands every floor of every random combination. */
    private boolean hit(String modules, Ray ray, Vector3 result) {
        float nearest = Float.POSITIVE_INFINITY;
        Vector3 point = new Vector3();
        Ray local = new Ray();
        for (int level = 0; level < modules.length(); level++) {
            String module = roles.get(modules.charAt(level));
            float height = level * LEVEL_HEIGHT;
            local.set(ray.origin, ray.direction);
            local.origin.z -= height;
            if (Intersector.intersectRayBoundsFast(local, bounds.get(module))
                  && Intersector.intersectRayTriangles(local, triangles.get(module), point)) {
                point.z += height;
                float distance = point.dst2(ray.origin);
                if (distance < nearest) { nearest = distance; result.set(point); }
            }
        }
        return Float.isFinite(nearest);
    }

    int assemblyCount() { return assemblies.size(); }
    int interiorCount() { return interiors.size(); }

    @Override
    public void dispose() {
        interiors.values().forEach(Model::dispose);
        interiors.clear();
        models.stream().distinct().forEach(Model::dispose);
        models.clear();
        assemblies.clear();
        footprints.clear();
        triangles.clear();
        bounds.clear();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
