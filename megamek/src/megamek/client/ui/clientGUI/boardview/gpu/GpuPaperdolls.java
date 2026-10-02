/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.Configuration;
import megamek.common.equipment.GunEmplacement;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.ConvFighter;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.Jumpship;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadMek;
import megamek.common.units.QuadVee;
import megamek.common.units.SmallCraft;
import megamek.common.units.SpaceStation;
import megamek.common.units.Tank;
import megamek.common.units.TripodMek;
import megamek.common.units.Warship;

/**
 * The converted paperdoll geometry (unit panel design 6.2): each unit family's location regions per view, read lazily
 * from the platform-neutral JSON in mm-data's images/paperdolls, with the shield variants of the mounted arms merged in
 * once per (family, view, arms). It uses no AWT and no GL, holds nothing to dispose, and its dolls are immutable.
 */
final class GpuPaperdolls {
    /** Views: the front armor (the only view of a non-Mek family), and a Mek's rear armor and structure. */
    static final String ARMOR = "armor";
    static final String REAR = "armor-rear";
    static final String STRUCTURE = "structure";
    /** Region layers: armor (front or rear), structure (a location or a value box such as SI) and a shield's part. */
    static final String ARMOR_LAYER = "armor";
    static final String STRUCTURE_LAYER = "structure";
    static final String SHIELD_LAYER = "shield";

    /**
     * A location region or value box: its code, layer, paint order, label anchor (x, y and inscribed radius), triangles
     * over its vertices and its outline rings, in doll units with y down. The arrays are never modified.
     */
    record Region(String code, String layer, int z, float[] anchor, float[] vertices, short[] triangles,
          List<float[]> rings) {
        /** The location a point here picks; a shield's capacity panel and absorption strip pick one shield. */
        String location() {
            return layer.equals(SHIELD_LAYER) ? "DC" + code.substring(2) : code;
        }

        boolean contains(float x, float y) {
            for (int index = 0; index < triangles.length; index += 3) {
                int a = 2 * triangles[index];
                int b = 2 * triangles[index + 1];
                int c = 2 * triangles[index + 2];
                float d1 = side(x, y, vertices[a], vertices[a + 1], vertices[b], vertices[b + 1]);
                float d2 = side(x, y, vertices[b], vertices[b + 1], vertices[c], vertices[c + 1]);
                float d3 = side(x, y, vertices[c], vertices[c + 1], vertices[a], vertices[a + 1]);
                boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
                boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
                if (!(negative && positive)) {
                    return true;
                }
            }
            return false;
        }

        private static float side(float x, float y, float ax, float ay, float bx, float by) {
            return (x - bx) * (ay - by) - (ax - bx) * (y - by);
        }
    }

    /**
     * A mounted shield: its one-shape outline (the capacity panel and absorption strip together) and the split between
     * them, drawn right after the topmost of its two regions ({@code z}), so that a later arm covers them; and two
     * anchors in the capacity panel's halves that hold both numbers where the strip is too thin for its own.
     */
    record Shield(String arm, int z, List<float[]> outline, float[] split, float[] capacityAnchor,
          float[] absorptionAnchor) { }

    /**
     * One view of a family: its frame (x, y, width, height, grown to the mounted shields), its regions in paint order,
     * the neutral hull that belongs to no location (null for none) and its mounted shields.
     */
    record Doll(String family, String view, float[] bounds, List<Region> regions, Region hull, List<Shield> shields) {
        /** The location at a point in doll units, the topmost region first; null outside every region. */
        String pick(float x, float y) {
            for (int index = regions.size() - 1; index >= 0; index--) {
                if (regions.get(index).contains(x, y)) {
                    return regions.get(index).location();
                }
            }
            return null;
        }
    }

    private final Path root;
    private final Map<String, JsonValue> files = new HashMap<>();
    private final Map<String, Doll> dolls = new HashMap<>();

    GpuPaperdolls() {
        this(Configuration.imagesDir().toPath().resolve("paperdolls"));
    }

    /** Tests may read the geometry from another folder, such as mm-data's source copy. */
    GpuPaperdolls(Path root) {
        this.root = root;
    }

    /**
     * The view of a family with the shield variants of {@code arms} ("LA", "RA") merged in, or null when the family has
     * no converted view (its units fall back to tiles, design 14 U2).
     */
    synchronized Doll doll(String family, String view, Set<String> arms) {
        String key = family + "/" + view + "/" + new TreeSet<>(arms);
        if (!dolls.containsKey(key)) {
            JsonValue json = file(family + "-" + view);
            dolls.put(key, json == null ? null : merge(family, view, json, arms));
        }
        return dolls.get(key);
    }

    private JsonValue file(String name) {
        if (!files.containsKey(name)) {
            Path path = root.resolve(name + ".json");
            files.put(name, Files.isRegularFile(path) ? new JsonReader().parse(new FileHandle(path.toFile())) : null);
        }
        return files.get(name);
    }

    private static Doll merge(String family, String view, JsonValue json, Set<String> arms) {
        List<Region> regions = new ArrayList<>();
        regions(json.get("locations"), regions);
        regions(json.get("boxes"), regions);
        float[] bounds = json.get("bounds").asFloatArray();
        List<Shield> shields = new ArrayList<>();
        JsonValue variants = json.get("shieldVariants");
        for (String arm : new TreeSet<>(arms)) {
            JsonValue variant = variants == null ? null : variants.get(arm);
            if (variant == null) {
                continue;
            }
            List<Region> replacements = new ArrayList<>();
            regions(variant.get("locations"), replacements);
            for (Region replacement : replacements) {
                regions.removeIf(region -> region.code().equals(replacement.code())
                      && region.layer().equals(replacement.layer()));
                regions.add(replacement);
            }
            List<float[]> anchors = rings(variant.get("panelAnchors"));
            shields.add(new Shield(arm, variant.getInt("z"), rings(variant.get("outline")),
                  variant.get("split").asFloatArray(), anchors.get(0), anchors.get(1)));
            bounds = union(bounds, variant.get("bounds").asFloatArray());
        }
        regions.sort(Comparator.comparingInt(Region::z));
        JsonValue hull = json.get("hull");
        return new Doll(family, view, bounds, List.copyOf(regions), hull == null ? null : region(hull, "", "hull"),
              List.copyOf(shields));
    }

    private static void regions(JsonValue list, List<Region> regions) {
        for (JsonValue value = list == null ? null : list.child; value != null; value = value.next) {
            regions.add(region(value, value.getString("abbr"), value.getString("layer")));
        }
    }

    private static Region region(JsonValue value, String code, String layer) {
        JsonValue anchor = value.get("anchor");
        return new Region(code, layer, value.getInt("z", -1), anchor == null ? new float[3] : anchor.asFloatArray(),
              value.get("vertices").asFloatArray(), value.get("triangles").asShortArray(), rings(value.get("rings")));
    }

    private static List<float[]> rings(JsonValue list) {
        List<float[]> rings = new ArrayList<>();
        for (JsonValue ring = list.child; ring != null; ring = ring.next) {
            rings.add(ring.asFloatArray());
        }
        return List.copyOf(rings);
    }

    private static float[] union(float[] a, float[] b) {
        float left = Math.min(a[0], b[0]);
        float top = Math.min(a[1], b[1]);
        return new float[] { left, top, Math.max(a[0] + a[2], b[0] + b[2]) - left,
              Math.max(a[1] + a[3], b[1] + b[3]) - top };
    }

    /**
     * EDT: the paperdoll family of a unit, "" for a unit drawn as tiles, dots or a stat block (battle armor, infantry,
     * squadrons and fighters in capital-fighter mode, gun emplacements, buildings, handheld weapons, battlefield
     * support). A port of MegaMekLab's record sheet choice ({@code Print*.getSVGFileName}); LAMs use the biped art and
     * submarines the naval art, as their templates are the same drawings.
     */
    static String family(Entity entity) {
        if (entity instanceof QuadVee) {
            return "quadvee";
        } else if (entity instanceof QuadMek) {
            return "quad";
        } else if (entity instanceof TripodMek) {
            return "tripod";
        } else if (entity instanceof Mek) {
            return "biped";
        } else if (entity instanceof ProtoMek protoMek) {
            return protoMek.isQuad() ? "protomek-quad" : protoMek.isGlider() ? "protomek-glider" : "protomek-biped";
        } else if (entity instanceof Tank tank && !(entity instanceof GunEmplacement)) {
            return vehicle(tank);
        } else if (entity.isCapitalFighter()) {
            // A squadron, or a fighter in capital-fighter mode: tiles (design 4.2).
            return "";
        } else if (entity instanceof Warship) {
            return "warship";
        } else if (entity instanceof SpaceStation) {
            return "spacestation";
        } else if (entity instanceof Jumpship) {
            return "jumpship";
        } else if (entity instanceof Dropship dropship) {
            return dropship.isSpheroid() ? "dropship-spheroid" : "dropship-aerodyne";
        } else if (entity instanceof SmallCraft smallCraft) {
            return smallCraft.isSpheroid() ? "smallcraft-spheroid" : "smallcraft-aerodyne";
        } else if (entity instanceof ConvFighter) {
            return "fighter-conventional";
        } else if (entity instanceof AeroSpaceFighter) {
            return "fighter-aerospace";
        }
        return "";
    }

    /** MegaMekLab PrintTank: the movement mode's art, its turrets, and the superheavy art (never for a VTOL). */
    private static String vehicle(Tank tank) {
        String turrets = tank.hasNoTurret() ? "noturret" : tank.hasNoDualTurret() ? "turret" : "dualturret";
        return switch (tank.getMovementMode()) {
            case VTOL -> "vtol-" + (tank.hasNoTurret() ? "noturret" : "turret");
            case NAVAL, HYDROFOIL, SUBMARINE -> "naval-" + (tank.isSuperHeavy() ? "superheavy-" : "") + turrets;
            case WIGE -> (tank.isSuperHeavy() ? "vehicle-superheavy-" : "wige-") + turrets;
            default -> "vehicle-" + (tank.isSuperHeavy() ? "superheavy-" : "") + turrets;
        };
    }
}
