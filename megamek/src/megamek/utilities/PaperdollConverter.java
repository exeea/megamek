/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.utilities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.utils.ShortArray;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.BipedMek;
import megamek.common.units.ConvFighter;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.Jumpship;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadMek;
import megamek.common.units.QuadVee;
import megamek.common.units.SmallCraft;
import megamek.common.units.SpaceStation;
import megamek.common.units.SuperHeavyTank;
import megamek.common.units.Tank;
import megamek.common.units.TripodMek;
import megamek.common.units.VTOL;
import megamek.common.units.Warship;
import megamek.common.util.SvgUtil;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.gvt.CanvasGraphicsNode;
import org.apache.batik.gvt.CompositeGraphicsNode;
import org.apache.batik.gvt.CompositeShapePainter;
import org.apache.batik.gvt.FillShapePainter;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.gvt.ShapeNode;
import org.apache.batik.gvt.ShapePainter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Offline converter of the paperdoll art (unit panel design 6.2; user corrections 9-18). It reads the editable SVG
 * source of every unit family (MekBay's location cut set), makes the location regions exclusive in paint order,
 * validates them against MegaMek's unit classes and writes the platform-neutral polygon JSON that the GPU HUD draws,
 * with a review PNG per family and a validation report. Only location polygons, their outlines, one label anchor per
 * location and the shield variants are kept: no pips, rails, arrows or decorations. Desktop only (Batik parses the SVG,
 * java.awt.geom does the boolean geometry, Java2D draws the review); the game never runs it.
 *
 * <p>Usage: {@code PaperdollConverter <svg source dir> <json dir> <review dir>}; exits with 1 when a family fails
 * validation, whose JSON is then not written.</p>
 */
public final class PaperdollConverter {
    static final String ARMOR = "armor";
    static final String STRUCTURE = "structure";
    static final String SHIELD = "shield";
    /** MegaMek locations no record sheet draws: body, fuselage, a fighter's wings as a whole, hull, broadsides. */
    private static final Set<String> NOT_DRAWN = Set.of("BD", "BOD", "WNG", "FSLG", "HULL", "LBS", "RBS");
    /** Value boxes of aerospace and capital units: structural integrity, K-F drive, sail and docking collars. */
    private static final Set<String> BOXES = Set.of("SI", "KF", "SAIL", "DC");
    /** Left and right locations that mirror each other about the drawing's axis. */
    private static final List<List<String>> MIRRORED = List.of(List.of("LA", "RA"), List.of("LT", "RT"),
          List.of("LL", "RL"), List.of("FLL", "FRL"), List.of("RLL", "RRL"), List.of("LS", "RS"),
          List.of("FRLS", "FRRS"), List.of("RRLS", "RRRS"), List.of("LWG", "RWG"), List.of("FLS", "FRS"),
          List.of("ALS", "ARS"));
    private static final List<String> ARMS = List.of("LA", "RA");
    /** Curve flattening and Douglas-Peucker tolerance, in SVG units (design 6.2 step 4). */
    private static final double FLATNESS = .1;
    private static final double SIMPLIFY = .15;
    /** A polygon part smaller than this (unit^2), or thinner than MIN_THICKNESS, is a sliver of boolean operations. */
    private static final double MIN_PART = .25;
    private static final double MIN_THICKNESS = .25;
    /** A ring point whose triangle with its neighbours is thinner than this (units) is dropped. */
    private static final double DEGENERATE = .01;
    /** Validation limits (design 6.2 step 7). */
    private static final double MAX_OVERLAP = .5;
    private static final double MIN_IOU = .9;
    private static final double MIN_RADIUS = 3;
    /**
     * A structure box of a one-drawing family (vehicle, ProtoMek) shows only its fill on the card and its number where
     * it fits on the sheet (design 4.2, 6.6); ProtoMek structure bars are that thin.
     */
    private static final double MIN_STRUCTURE_RADIUS = 1;
    /**
     * Left / right pairs whose record-sheet drawing is asymmetric, with the IoU they must still reach; each was checked
     * in the review PNGs and is listed in the README's corrections table. The superheavy vehicle's inner-hull divider
     * lies right of the hull axis and the two turrets leave thin structure quadrants; the JumpShip's side armor boxes
     * lie 4 units left of its hull axis.
     */
    private static final Map<String, Double> ASYMMETRIC = Map.of(
          "vehicle-superheavy-dualturret structure FRLS/FRRS", .85,
          "vehicle-superheavy-dualturret structure RRLS/RRRS", .85,
          "jumpship armor FLS/FRS", .7,
          "jumpship armor ALS/ARS", .7);
    /** A piece of Mek art outside every region fails when it is larger than this (unit^2) and not a hairline. */
    private static final double MAX_GAP = 1;
    private static final double GAP_THICKNESS = .5;
    /** A region of a shield variant equals the plain one when their difference is smaller than this (unit^2). */
    private static final double SAME = .05;
    /** Polylabel precision, in SVG units. */
    private static final double POLE_PRECISION = .02;
    private static final int PANEL = 460;

    /**
     * A unit family: its id (the JSON and SVG file prefix), whether it is a Mek family (front armor, rear armor and
     * structure files) or one drawing, whether that drawing has a structure box per location, a unit of its MegaMek
     * class for the expected locations, the locations of that class it does not draw, and its value boxes.
     */
    private record Family(String id, boolean mek, boolean structureBoxes, Supplier<Entity> unit, Set<String> without,
          Set<String> boxes) {
        List<String> views() {
            return mek ? List.of("armor", "armor-rear", STRUCTURE) : List.of("armor");
        }

        String file(String view) {
            return !mek ? id + ".svg" : id + switch (view) {
                case "armor" -> "-armor.svg";
                case "armor-rear" -> "-armor-back.svg";
                default -> "-structure.svg";
            };
        }
    }

    /** A located element of the source in paint order: its layer, code, the shield arm it belongs to ("" for none). */
    private record Part(int z, String layer, String code, String arm, Area area) { }

    /** Visible filled art: whether its fill is white (the silhouette the regions must cover) and its area. */
    private record Art(boolean white, Area area) { }

    /** A pip placeholder's centre: a positional reference for the location it names. */
    private record Seed(String code, String arm, double x, double y) { }

    private record Source(String file, List<Part> parts, List<Art> art, List<Seed> seeds) { }

    private record Key(String layer, String code) { }

    /** A location's exclusive area and the paint order of its topmost element. */
    private record Merged(Area area, int z) { }

    /** A polygon: its outer ring and holes as x, y pairs. */
    private record Poly(double[] outer, List<double[]> holes) { }

    /** A converted region: exact area, simplified polygons, triangles over their vertices, and the label anchor. */
    private record Region(String code, String layer, int z, Area exact, List<Poly> polygons, float[] vertices,
          short[] triangles, double[] anchor, double dropped) {
        List<double[]> rings() {
            List<double[]> rings = new ArrayList<>();
            polygons.forEach(poly -> {
                rings.add(poly.outer());
                rings.addAll(poly.holes());
            });
            return rings;
        }

        Area simplified() {
            Area area = new Area();
            polygons.forEach(poly -> area.add(shape(poly)));
            return area;
        }
    }

    /**
     * A shield variant: its regions (the shield's panel and strip, and the arm when it changes), the plain regions it
     * would hide entirely ({@code lost}, a failure), the shield's outline and split, and two anchors in the capacity
     * panel's halves (the half away from the split first) that hold both numbers where the strip is too thin for
     * its own (user correction 17: the card shows both).
     */
    private record Variant(String arm, List<Region> regions, List<String> lost, List<double[]> outline,
          double[] split, int z, double[][] panelAnchors) { }

    /** A review panel: a view, alone or with one shield variant. */
    private record Panel(View view, Variant variant) { }

    /** One converted view of a family. */
    private record View(String name, Source source, Rectangle2D bounds, List<Region> regions, Region hull,
          Map<String, Variant> variants, Area white, Area uncovered) { }

    private static final List<Family> FAMILIES = List.of(
          new Family("biped", true, false, BipedMek::new, Set.of(), Set.of()),
          new Family("quad", true, false, QuadMek::new, Set.of(), Set.of()),
          new Family("quadvee", true, false, QuadVee::new, Set.of(), Set.of()),
          new Family("tripod", true, false, TripodMek::new, Set.of(), Set.of()),
          new Family("vehicle-noturret", false, true, () -> tank(new Tank(), 0), Set.of(), Set.of()),
          new Family("vehicle-turret", false, true, () -> tank(new Tank(), 1), Set.of(), Set.of()),
          new Family("vehicle-dualturret", false, true, () -> tank(new Tank(), 2), Set.of(), Set.of()),
          new Family("vehicle-superheavy-noturret", false, true, () -> tank(new SuperHeavyTank(), 0), Set.of(),
                Set.of()),
          new Family("vehicle-superheavy-turret", false, true, () -> tank(new SuperHeavyTank(), 1), Set.of(),
                Set.of()),
          new Family("vehicle-superheavy-dualturret", false, true, () -> tank(new SuperHeavyTank(), 2), Set.of(),
                Set.of()),
          new Family("vtol-noturret", false, true, () -> tank(new VTOL(), 0), Set.of(), Set.of()),
          new Family("vtol-turret", false, true, () -> tank(new VTOL(), 1), Set.of(), Set.of()),
          new Family("naval-noturret", false, true, () -> tank(new Tank(), 0), Set.of(), Set.of()),
          new Family("naval-turret", false, true, () -> tank(new Tank(), 1), Set.of(), Set.of()),
          new Family("naval-dualturret", false, true, () -> tank(new Tank(), 2), Set.of(), Set.of()),
          new Family("naval-superheavy-noturret", false, true, () -> tank(new SuperHeavyTank(), 0), Set.of(),
                Set.of()),
          new Family("naval-superheavy-turret", false, true, () -> tank(new SuperHeavyTank(), 1), Set.of(),
                Set.of()),
          new Family("naval-superheavy-dualturret", false, true, () -> tank(new SuperHeavyTank(), 2), Set.of(),
                Set.of()),
          new Family("wige-noturret", false, true, () -> tank(new Tank(), 0), Set.of(), Set.of()),
          new Family("wige-turret", false, true, () -> tank(new Tank(), 1), Set.of(), Set.of()),
          new Family("wige-dualturret", false, true, () -> tank(new Tank(), 2), Set.of(), Set.of()),
          new Family("protomek-biped", false, true, PaperdollConverter::protoMek, Set.of(), Set.of()),
          new Family("protomek-glider", false, true, PaperdollConverter::protoMek, Set.of(), Set.of()),
          // A quad ProtoMek keeps MegaMek's arm locations, but they are never used and its sheet draws no arms.
          new Family("protomek-quad", false, true, PaperdollConverter::protoMek, Set.of("RA", "LA"), Set.of()),
          new Family("fighter-aerospace", false, false, AeroSpaceFighter::new, Set.of(), Set.of("SI")),
          new Family("fighter-conventional", false, false, ConvFighter::new, Set.of(), Set.of("SI")),
          new Family("smallcraft-aerodyne", false, false, SmallCraft::new, Set.of(), Set.of("SI")),
          new Family("smallcraft-spheroid", false, false, SmallCraft::new, Set.of(), Set.of("SI")),
          new Family("dropship-aerodyne", false, false, Dropship::new, Set.of(), Set.of("SI")),
          new Family("dropship-spheroid", false, false, Dropship::new, Set.of(), Set.of("SI")),
          new Family("jumpship", false, false, Jumpship::new, Set.of(), Set.of("SI", "KF", "SAIL", "DC")),
          new Family("warship", false, false, Warship::new, Set.of(), Set.of("SI", "KF", "SAIL", "DC")),
          // A space station has no K-F drive.
          new Family("spacestation", false, false, SpaceStation::new, Set.of(), Set.of("SI", "SAIL", "DC")));

    private PaperdollConverter() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: PaperdollConverter <svg source dir> <json dir> <review dir>");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path json = Path.of(args[1]);
        Path review = Path.of(args[2]);
        Files.createDirectories(json);
        Files.createDirectories(review);
        StringBuilder report = new StringBuilder();
        List<String> failures = new ArrayList<>();
        for (Family family : FAMILIES) {
            List<View> views = new ArrayList<>();
            List<String> familyFailures = new ArrayList<>();
            for (String name : family.views()) {
                View view = convert(family, name, parse(source.resolve(family.file(name))));
                views.add(view);
                familyFailures.addAll(validate(family, view, report));
            }
            review(review.resolve(family.id() + ".png"), family, views);
            if (familyFailures.isEmpty()) {
                for (View view : views) {
                    Files.writeString(json.resolve(family.id() + "-" + view.name() + ".json"), json(family, view),
                          StandardCharsets.UTF_8);
                }
            }
            failures.addAll(familyFailures);
            System.out.println(family.id() + (familyFailures.isEmpty() ? ": ok" : ": FAILED"));
        }
        report.append(failures.isEmpty() ? "\nAll families valid.\n" : "\nFAILURES\n" + String.join("\n", failures));
        Files.writeString(review.resolve("validation.txt"), report, StandardCharsets.UTF_8);
        if (!failures.isEmpty()) {
            failures.forEach(System.err::println);
            System.exit(1);
        }
    }

    private static Entity tank(Tank tank, int turrets) {
        tank.setHasNoTurret(turrets == 0);
        tank.setHasNoDualTurret(turrets < 2);
        return tank;
    }

    /** A ProtoMek with a main gun: every ProtoMek drawing has the MG location; a unit without one leaves it empty. */
    private static Entity protoMek() {
        ProtoMek protoMek = new ProtoMek();
        protoMek.setHasMainGun(true);
        return protoMek;
    }

    // ------------------------------------------------------------------------------------------------ parsing

    private static Source parse(Path file) throws IOException {
        Document document = SvgUtil.document(file.toFile());
        Element svg = document.getDocumentElement();
        prepare(svg);
        BridgeContext context = SvgUtil.context(true);
        GraphicsNode root = new GVTBuilder().build(context, document);
        Source source = new Source(file.getFileName().toString(), new ArrayList<>(), new ArrayList<>(),
              new ArrayList<>());
        collect(root, context, source);
        return source;
    }

    /** Drops what the converter never reads (text, images) and the CSS3 "transparent" paint, which SVG 1.1 lacks. */
    private static void prepare(Element element) {
        Node child = element.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child instanceof Element childElement) {
                String tag = childElement.getLocalName();
                if ("text".equals(tag) || "image".equals(tag)) {
                    element.removeChild(child);
                } else {
                    prepare(childElement);
                }
            }
            child = next;
        }
        for (String paint : List.of("fill", "stroke")) {
            if ("transparent".equals(element.getAttribute(paint))) {
                element.setAttribute(paint, "none");
            }
        }
    }

    /**
     * Sorts the leaf shapes, in paint order, into location parts ({@code unitLocation}), pip placeholders (seeds) and
     * visible filled art. Pip rails, transfer arrows and MekBay's random-hit button are neither.
     */
    private static void collect(GraphicsNode node, BridgeContext context, Source source) {
        if (node instanceof CompositeGraphicsNode group) {
            for (Object child : group) {
                collect((GraphicsNode) child, context, source);
            }
            return;
        }
        if (!(node instanceof ShapeNode shape) || shape.getShape() == null) {
            return;
        }
        Element element = context.getElement(node);
        if (element == null || !inherited(element, "data-rail").isEmpty() || classed(element, "arrow")
              || classed(element, "mek-random-hit-button")) {
            return;
        }
        String classes = " " + element.getAttribute("class") + " ";
        if (classes.contains(" unitLocation ")) {
            String layer = classes.contains(" shield ") ? SHIELD : classes.contains(" structure ") ? STRUCTURE : ARMOR;
            source.parts().add(new Part(source.parts().size(), layer, element.getAttribute("data-loc"),
                  inherited(element, "data-mekbay-shield"), geometry(shape)));
            return;
        }
        String location = element.getAttribute("data-location");
        if (!location.isEmpty() && (element.hasAttribute("data-canon") || element.hasAttribute("data-fill"))) {
            // A shield's placeholders belong to its variant: the tripod keeps them outside its shield groups.
            String fill = element.getAttribute("data-fill");
            boolean shield = fill.startsWith("shield-");
            String code = shield ? fill.substring(7).toUpperCase(Locale.ROOT) + location : location.replace("_R", "");
            Rectangle2D bounds = user(node).createTransformedShape(shape.getShape()).getBounds2D();
            source.seeds().add(new Seed(code, shield ? location : "", bounds.getCenterX(), bounds.getCenterY()));
            return;
        }
        Color fill = fill(shape);
        if (visible(node) && fill != null && fill.getAlpha() > 0) {
            boolean white = fill.getRed() > 240 && fill.getGreen() > 240 && fill.getBlue() > 240;
            source.art().add(new Art(white, geometry(shape)));
        }
    }

    /** The attribute on the element or its nearest ancestor that has it; "" for none. */
    private static String inherited(Element element, String name) {
        for (Node node = element; node instanceof Element current; node = node.getParentNode()) {
            if (current.hasAttribute(name)) {
                return current.getAttribute(name);
            }
        }
        return "";
    }

    /** Whether the element or an ancestor has the class. */
    private static boolean classed(Element element, String name) {
        for (Node node = element; node instanceof Element current; node = node.getParentNode()) {
            if ((" " + current.getAttribute("class") + " ").contains(" " + name + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean visible(GraphicsNode node) {
        for (GraphicsNode current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Color fill(ShapeNode node) {
        ShapePainter painter = node.getShapePainter();
        if (painter instanceof CompositeShapePainter composite) {
            for (int index = 0; index < composite.getShapePainterCount(); index++) {
                if (composite.getShapePainter(index) instanceof FillShapePainter fill) {
                    return fill.getPaint() instanceof Color color ? color : null;
                }
            }
        }
        return painter instanceof FillShapePainter fill && fill.getPaint() instanceof Color color ? color : null;
    }

    /**
     * The shape in the root's user (viewBox) space, cut by its own clip path and its ancestors'. Batik defines each
     * node's clip in that node's user space, after its transform; the root canvas' viewport clip is left out, so art
     * outside the view box (the shields) is kept.
     */
    private static Area geometry(ShapeNode node) {
        Area area = new Area(user(node).createTransformedShape(node.getShape()));
        for (GraphicsNode current = node; current != null && !(current instanceof CanvasGraphicsNode);
              current = current.getParent()) {
            if (current.getClip() != null) {
                area.intersect(new Area(user(current).createTransformedShape(current.getClip().getClipPath())));
            }
        }
        return area;
    }

    /** The node's transform to the root's user space: every transform below the root canvas. */
    private static AffineTransform user(GraphicsNode node) {
        Deque<GraphicsNode> chain = new ArrayDeque<>();
        for (GraphicsNode current = node; current != null && !(current instanceof CanvasGraphicsNode);
              current = current.getParent()) {
            chain.push(current);
        }
        AffineTransform transform = new AffineTransform();
        for (GraphicsNode current : chain) {
            if (current.getTransform() != null) {
                transform.concatenate(current.getTransform());
            }
        }
        return transform;
    }

    // ------------------------------------------------------------------------------------------------ conversion

    /**
     * One view: its regions, and the white art outside every region, which a Mek drawing must not have and which the
     * other families keep as their neutral hull. The exact regions are simplified, then made exclusive once more in
     * paint order, so that the simplified outlines of neighbours never overlap.
     */
    private static View convert(Family family, String name, Source source) {
        Map<Key, Merged> plain = exclusive(source.parts(), Set.of());
        Map<Key, Area> simplified = new LinkedHashMap<>();
        plain.forEach((key, merged) -> simplified.put(key, simplified(merged.area())));
        List<Key> keys = new ArrayList<>(plain.keySet());
        List<Region> regions = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            Area own = new Area(simplified.get(keys.get(index)));
            for (Key above : keys.subList(index + 1, keys.size())) {
                own.subtract(simplified.get(above));
            }
            Key key = keys.get(index);
            regions.add(region(key.code(), key.layer(), plain.get(key).z(), plain.get(key).area(), own));
        }
        Area covered = new Area();
        plain.values().forEach(merged -> covered.add(merged.area()));
        Area white = new Area();
        source.art().stream().filter(Art::white).forEach(art -> white.add(art.area()));
        Area uncovered = new Area(white);
        uncovered.subtract(covered);
        Region hull = null;
        if (!family.mek() && !uncovered.isEmpty()) {
            Area own = simplified(uncovered);
            simplified.values().forEach(own::subtract);
            hull = region("", "hull", -1, uncovered, own);
        }
        Map<String, Variant> variants = new LinkedHashMap<>();
        Set<String> arms = new TreeSet<>();
        source.parts().stream().filter(part -> part.layer().equals(SHIELD)).forEach(part -> arms.add(part.arm()));
        for (String arm : arms) {
            variants.put(arm, variant(source, plain, simplified, arm));
        }
        Rectangle2D bounds = null;
        for (Region region : regions) {
            bounds = union(bounds, region.exact().getBounds2D());
        }
        if (hull != null && !hull.polygons().isEmpty()) {
            bounds = union(bounds, hull.simplified().getBounds2D());
        }
        return new View(name, source, bounds, regions, hull, variants, white, uncovered);
    }

    /**
     * Each location's area with the areas of every later part cut away (a later element owns an overlap, design 6.2
     * step 3), the parts of one location united, for the plain view and the shield parts of {@code arms}; in paint
     * order of each location's topmost part.
     */
    private static Map<Key, Merged> exclusive(List<Part> parts, Set<String> arms) {
        List<Part> active = parts.stream().filter(part -> part.arm().isEmpty() || arms.contains(part.arm())).toList();
        Map<Key, Merged> merged = new LinkedHashMap<>();
        Area above = new Area();
        for (int index = active.size() - 1; index >= 0; index--) {
            Part part = active.get(index);
            Area own = new Area(part.area());
            own.subtract(above);
            above.add(part.area());
            Key key = new Key(part.layer(), part.code());
            Merged previous = merged.get(key);
            if (previous == null) {
                merged.put(key, new Merged(own, part.z()));
            } else {
                previous.area().add(own);
            }
        }
        List<Map.Entry<Key, Merged>> ordered = new ArrayList<>(merged.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> entry.getValue().z()));
        Map<Key, Merged> result = new LinkedHashMap<>();
        ordered.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    /**
     * The shield variant of one arm: the regions that differ from the plain view (the shield's capacity panel and
     * absorption strip, and the arm when the file redraws it over the shield), the shield's one-shape outline (its
     * panel and strip before exclusivity: the clip shape) and the split between them. The variant's simplified regions
     * give way to every plain region they do not replace, so the plain regions stay as they are.
     */
    private static Variant variant(Source source, Map<Key, Merged> plain, Map<Key, Area> plainSimplified,
          String arm) {
        Map<Key, Merged> shielded = exclusive(source.parts(), Set.of(arm));
        List<Key> changed = shielded.keySet().stream().filter(key -> !plain.containsKey(key)
              || differs(plain.get(key).area(), shielded.get(key).area())).toList();
        Map<Key, Area> simplified = new LinkedHashMap<>();
        changed.forEach(key -> simplified.put(key, simplified(shielded.get(key).area())));
        List<Region> regions = new ArrayList<>();
        for (int index = 0; index < changed.size(); index++) {
            Key key = changed.get(index);
            Area own = new Area(simplified.get(key));
            plainSimplified.forEach((other, area) -> {
                if (!changed.contains(other)) {
                    own.subtract(area);
                }
            });
            for (Key above : changed.subList(index + 1, changed.size())) {
                own.subtract(simplified.get(above));
            }
            Merged merged = shielded.get(key);
            regions.add(region(key.code(), key.layer(), merged.z(), merged.area(), own));
        }
        List<String> lost = plain.keySet().stream().filter(key -> !shielded.containsKey(key)).map(Key::code)
              .toList();
        Area capacity = new Area();
        Area absorption = new Area();
        int z = 0;
        for (Part part : source.parts()) {
            if (part.layer().equals(SHIELD) && part.arm().equals(arm)) {
                (part.code().startsWith("DC") ? capacity : absorption).add(part.area());
                z = Math.max(z, part.z());
            }
        }
        Area outline = new Area(capacity);
        outline.add(absorption);
        List<double[]> rings = new ArrayList<>();
        polygons(outline, new double[1], true).forEach(poly -> {
            rings.add(poly.outer());
            rings.addAll(poly.holes());
        });
        double[] split = split(capacity, absorption);
        Region panel = regions.stream().filter(region -> region.code().equals("DC" + arm)).findFirst().orElse(null);
        return new Variant(arm, regions, lost, rings, split, z,
              panel == null ? new double[0][] : panelAnchors(panel, split));
    }

    /**
     * The capacity panel cut in two by the line through its centroid parallel to the split, and the anchor of each
     * half: the half away from the split first (the capacity), then the one beside the strip (the absorption).
     */
    private static double[][] panelAnchors(Region panel, double[] split) {
        Poly largest = panel.polygons().stream().max(Comparator.comparingDouble(PaperdollConverter::area))
              .orElseThrow();
        double[] centroid = centroid(largest.outer());
        double length = Math.hypot(split[2] - split[0], split[3] - split[1]);
        double dx = (split[2] - split[0]) / length * 1e4;
        double dy = (split[3] - split[1]) / length * 1e4;
        double[][] anchors = new double[2][];
        double[] distances = new double[2];
        for (int side = 0; side < 2; side++) {
            double nx = (side == 0 ? -dy : dy);
            double ny = (side == 0 ? dx : -dx);
            Area half = new Area(path(new double[] { centroid[0] + dx, centroid[1] + dy, centroid[0] + dx + nx,
                  centroid[1] + dy + ny, centroid[0] - dx + nx, centroid[1] - dy + ny, centroid[0] - dx,
                  centroid[1] - dy }));
            half.intersect(panel.simplified());
            double[] best = { centroid[0], centroid[1], 0 };
            for (Poly poly : polygons(half, new double[1], false)) {
                double[] candidate = anchor(poly);
                if (candidate[2] > best[2]) {
                    best = candidate;
                }
            }
            anchors[side] = best;
            distances[side] = segmentDistance(best[0], best[1], split[0], split[1], split[2], split[3]);
        }
        return distances[0] >= distances[1] ? anchors : new double[][] { anchors[1], anchors[0] };
    }

    /** The segment where the capacity panel meets the absorption strip: their shared edges, end to end. */
    private static double[] split(Area capacity, Area absorption) {
        List<double[]> points = new ArrayList<>();
        List<double[]> strip = rings(absorption);
        for (double[] ring : rings(capacity)) {
            for (int index = 0; index < ring.length; index += 2) {
                int next = (index + 2) % ring.length;
                double x = (ring[index] + ring[next]) / 2;
                double y = (ring[index + 1] + ring[next + 1]) / 2;
                if (distanceToRings(strip, x, y) < .01) {
                    points.add(new double[] { ring[index], ring[index + 1] });
                    points.add(new double[] { ring[next], ring[next + 1] });
                }
            }
        }
        double[] split = new double[4];
        double longest = -1;
        for (double[] a : points) {
            for (double[] b : points) {
                double length = Math.hypot(a[0] - b[0], a[1] - b[1]);
                if (length > longest) {
                    longest = length;
                    split = new double[] { a[0], a[1], b[0], b[1] };
                }
            }
        }
        return split;
    }

    private static boolean differs(Area a, Area b) {
        Area difference = new Area(a);
        difference.exclusiveOr(b);
        return area(difference) > SAME;
    }

    /** The area flattened and simplified (design 6.2 step 4), without slivers. */
    private static Area simplified(Area exact) {
        Area area = new Area();
        polygons(exact, new double[1], true).forEach(poly -> area.add(shape(poly)));
        return area;
    }

    /**
     * A converted region from its final (simplified, exclusive) shape: its polygons without slivers, their triangles
     * (design 6.2 step 5) and the label anchor of its deepest part (step 6).
     */
    private static Region region(String code, String layer, int z, Area exact, Area shape) {
        double[] dropped = new double[1];
        List<Poly> polygons = polygons(shape, dropped, false);
        List<Float> vertices = new ArrayList<>();
        List<Short> triangles = new ArrayList<>();
        double[] anchor = { 0, 0, 0 };
        EarClippingTriangulator triangulator = new EarClippingTriangulator();
        for (Poly poly : polygons) {
            for (Poly piece : pieces(poly)) {
                double[] ring = piece.outer();
                float[] flat = new float[ring.length];
                for (int index = 0; index < ring.length; index++) {
                    flat[index] = (float) round(ring[index]);
                }
                ShortArray indices = triangulator.computeTriangles(flat);
                int offset = vertices.size() / 2;
                for (float value : flat) {
                    vertices.add(value);
                }
                for (int index = 0; index < indices.size; index++) {
                    triangles.add((short) (indices.get(index) + offset));
                }
            }
            double[] candidate = anchor(poly);
            if (candidate[2] > anchor[2]) {
                anchor = candidate;
            }
        }
        float[] vertexArray = new float[vertices.size()];
        for (int index = 0; index < vertexArray.length; index++) {
            vertexArray[index] = vertices.get(index);
        }
        short[] triangleArray = new short[triangles.size()];
        for (int index = 0; index < triangleArray.length; index++) {
            triangleArray[index] = triangles.get(index);
        }
        return new Region(code, layer, z, exact, polygons, vertexArray, triangleArray, anchor, dropped[0]);
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** The rings of a flattened area, as closed x, y pairs without the repeated first point. */
    private static List<double[]> rings(Area area) {
        List<double[]> rings = new ArrayList<>();
        List<Double> ring = new ArrayList<>();
        double[] coordinates = new double[6];
        for (PathIterator iterator = area.getPathIterator(null, FLATNESS); !iterator.isDone(); iterator.next()) {
            int type = iterator.currentSegment(coordinates);
            if (type == PathIterator.SEG_MOVETO && !ring.isEmpty()) {
                rings.add(array(ring));
                ring.clear();
            }
            if (type == PathIterator.SEG_CLOSE) {
                rings.add(array(ring));
                ring.clear();
            } else {
                int size = ring.size();
                if (size < 2 || Math.abs(ring.get(size - 2) - coordinates[0]) > 1e-9
                      || Math.abs(ring.get(size - 1) - coordinates[1]) > 1e-9) {
                    ring.add(coordinates[0]);
                    ring.add(coordinates[1]);
                }
            }
        }
        if (!ring.isEmpty()) {
            rings.add(array(ring));
        }
        List<double[]> closed = new ArrayList<>();
        for (double[] points : rings) {
            int length = points.length;
            if (length >= 4 && points[0] == points[length - 2] && points[1] == points[length - 1]) {
                points = Arrays.copyOf(points, length - 2);
            }
            if (points.length >= 6) {
                closed.add(points);
            }
        }
        return closed;
    }

    private static double[] array(List<Double> values) {
        double[] array = new double[values.size()];
        for (int index = 0; index < array.length; index++) {
            array[index] = values.get(index);
        }
        return array;
    }

    /**
     * The area's polygons: flattened rings grouped into outer rings and their holes, without slivers, simplified
     * (Douglas-Peucker) or only cleared of degenerate points; {@code dropped[0]} receives the area of the dropped
     * slivers.
     */
    private static List<Poly> polygons(Area area, double[] dropped, boolean simplify) {
        List<Poly> polygons = new ArrayList<>();
        for (Poly raw : grouped(area)) {
            double size = area(raw);
            double thickness = pole(raw)[2];
            if (size < MIN_PART || thickness < MIN_THICKNESS) {
                dropped[0] += size;
                continue;
            }
            List<double[]> simpleHoles = new ArrayList<>();
            for (double[] hole : raw.holes()) {
                double[] simple = simplify ? simplify(hole) : clean(hole);
                // A hole below the sliver size is filled; the region grows by at most that much.
                if (simple.length >= 6 && Math.abs(signedArea(simple)) >= MIN_PART) {
                    simpleHoles.add(simple);
                }
            }
            polygons.add(new Poly(simplify ? simplify(raw.outer()) : clean(raw.outer()), simpleHoles));
        }
        return polygons;
    }

    /** The area's rings grouped into outer rings (largest first) and the holes inside each. */
    private static List<Poly> grouped(Area area) {
        List<double[]> rings = rings(area);
        if (rings.isEmpty()) {
            return List.of();
        }
        double largest = 0;
        for (double[] ring : rings) {
            double signed = signedArea(ring);
            if (Math.abs(signed) > Math.abs(largest)) {
                largest = signed;
            }
        }
        List<double[]> outers = new ArrayList<>();
        List<double[]> holes = new ArrayList<>();
        for (double[] ring : rings) {
            (Math.signum(signedArea(ring)) == Math.signum(largest) ? outers : holes).add(ring);
        }
        outers.sort(Comparator.comparingDouble(ring -> Math.abs(signedArea(ring))));
        List<List<double[]>> holesOf = new ArrayList<>();
        outers.forEach(outer -> holesOf.add(new ArrayList<>()));
        for (double[] hole : holes) {
            // The smallest outer ring around the hole; a vertex may touch the ring, the first edge's middle does not.
            double x = (hole[0] + hole[2]) / 2;
            double y = (hole[1] + hole[3]) / 2;
            for (int index = 0; index < outers.size(); index++) {
                if (inside(outers.get(index), hole[0], hole[1]) || inside(outers.get(index), x, y)) {
                    holesOf.get(index).add(hole);
                    break;
                }
            }
        }
        List<Poly> polygons = new ArrayList<>();
        for (int index = outers.size() - 1; index >= 0; index--) {
            polygons.add(new Poly(outers.get(index), holesOf.get(index)));
        }
        return polygons;
    }


    /**
     * The ring without degenerate points: a point whose triangle with its neighbours is thinner than 0.01 units lies
     * on a straight line between them or is the tip of a zero-width spike, which the boolean operations leave where
     * two simplified neighbours meet and which ear clipping cannot handle. Removing it moves the outline by less
     * than that.
     */
    private static double[] clean(double[] ring) {
        List<double[]> points = new ArrayList<>();
        for (int index = 0; index < ring.length; index += 2) {
            points.add(new double[] { ring[index], ring[index + 1] });
        }
        boolean changed = true;
        while (changed && points.size() > 3) {
            changed = false;
            for (int index = 0; index < points.size() && points.size() > 3; index++) {
                double[] previous = points.get((index + points.size() - 1) % points.size());
                double[] point = points.get(index);
                double[] next = points.get((index + 1) % points.size());
                double twiceArea = Math.abs((point[0] - previous[0]) * (next[1] - previous[1])
                      - (next[0] - previous[0]) * (point[1] - previous[1]));
                double longest = Math.max(Math.hypot(point[0] - previous[0], point[1] - previous[1]),
                      Math.max(Math.hypot(next[0] - point[0], next[1] - point[1]),
                            Math.hypot(next[0] - previous[0], next[1] - previous[1])));
                if (longest < 1e-6 || twiceArea / longest < DEGENERATE) {
                    points.remove(index--);
                    changed = true;
                }
            }
        }
        double[] cleaned = new double[points.size() * 2];
        for (int index = 0; index < points.size(); index++) {
            cleaned[2 * index] = points.get(index)[0];
            cleaned[2 * index + 1] = points.get(index)[1];
        }
        return cleaned;
    }

    /** Douglas-Peucker on a closed ring, split at its first point and the point farthest from it. */
    private static double[] simplify(double[] ring) {
        int count = ring.length / 2;
        if (count <= 4) {
            return ring;
        }
        int far = 0;
        double farthest = -1;
        for (int index = 1; index < count; index++) {
            double distance = Math.hypot(ring[2 * index] - ring[0], ring[2 * index + 1] - ring[1]);
            if (distance > farthest) {
                farthest = distance;
                far = index;
            }
        }
        boolean[] keep = new boolean[count + 1];
        keep[0] = true;
        keep[far] = true;
        keep[count] = true;
        mark(ring, 0, far, keep);
        mark(ring, far, count, keep);
        List<Double> kept = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (keep[index]) {
                kept.add(ring[2 * index]);
                kept.add(ring[2 * index + 1]);
            }
        }
        return kept.size() >= 6 ? array(kept) : ring;
    }

    private static void mark(double[] ring, int from, int to, boolean[] keep) {
        int count = ring.length / 2;
        double ax = ring[2 * (from % count)];
        double ay = ring[2 * (from % count) + 1];
        double bx = ring[2 * (to % count)];
        double by = ring[2 * (to % count) + 1];
        double worst = -1;
        int worstIndex = -1;
        for (int index = from + 1; index < to; index++) {
            double distance = segmentDistance(ring[2 * index], ring[2 * index + 1], ax, ay, bx, by);
            if (distance > worst) {
                worst = distance;
                worstIndex = index;
            }
        }
        if (worst > SIMPLIFY) {
            keep[worstIndex] = true;
            mark(ring, from, worstIndex, keep);
            mark(ring, worstIndex, to, keep);
        }
    }


    /**
     * Hole-free pieces of a polygon for ear clipping: a polygon with a hole is cut in two by a vertical line through
     * that hole's middle, which opens it on both sides, and each half is cut again while it has a hole. Unlike a bridge
     * from a hole to the outer ring, the cut also handles a hole that touches the ring.
     */
    private static List<Poly> pieces(Poly poly) {
        if (poly.holes().isEmpty()) {
            return List.of(poly);
        }
        double cut = path(poly.holes().getFirst()).getBounds2D().getCenterX();
        Area area = shape(poly);
        Rectangle2D bounds = area.getBounds2D();
        List<Poly> pieces = new ArrayList<>();
        for (Rectangle2D side : List.of(new Rectangle2D.Double(bounds.getX() - 1, bounds.getY() - 1,
                    cut - bounds.getX() + 1, bounds.getHeight() + 2),
              new Rectangle2D.Double(cut, bounds.getY() - 1, bounds.getMaxX() - cut + 1, bounds.getHeight() + 2))) {
            Area half = new Area(area);
            half.intersect(new Area(side));
            for (Poly part : grouped(half)) {
                List<double[]> holes = part.holes().stream().map(PaperdollConverter::clean).toList();
                pieces.addAll(pieces(new Poly(clean(part.outer()), holes)));
            }
        }
        return pieces;
    }

    /**
     * The pole of inaccessibility (Mapbox's polylabel): the inside point farthest from every edge, as x, y and that
     * distance, the inscribed radius that the HUD fits a number into (design 6.6).
     */
    private static double[] pole(Poly poly) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double[] outer = poly.outer();
        for (int index = 0; index < outer.length; index += 2) {
            minX = Math.min(minX, outer[index]);
            maxX = Math.max(maxX, outer[index]);
            minY = Math.min(minY, outer[index + 1]);
            maxY = Math.max(maxY, outer[index + 1]);
        }
        double size = Math.min(maxX - minX, maxY - minY);
        if (size <= 0) {
            return new double[] { minX, minY, 0 };
        }
        PriorityQueue<double[]> cells = new PriorityQueue<>(Comparator.comparingDouble(cell -> -cell[4]));
        double half = size / 2;
        for (double x = minX; x < maxX; x += size) {
            for (double y = minY; y < maxY; y += size) {
                cells.add(cell(poly, x + half, y + half, half));
            }
        }
        double[] best = cell(poly, (minX + maxX) / 2, (minY + maxY) / 2, 0);
        while (!cells.isEmpty()) {
            double[] cell = cells.poll();
            if (cell[3] > best[3]) {
                best = cell;
            }
            if (cell[4] - best[3] <= POLE_PRECISION) {
                continue;
            }
            double quarter = cell[2] / 2;
            cells.add(cell(poly, cell[0] - quarter, cell[1] - quarter, quarter));
            cells.add(cell(poly, cell[0] + quarter, cell[1] - quarter, quarter));
            cells.add(cell(poly, cell[0] - quarter, cell[1] + quarter, quarter));
            cells.add(cell(poly, cell[0] + quarter, cell[1] + quarter, quarter));
        }
        return new double[] { best[0], best[1], Math.max(0, best[3]) };
    }

    /**
     * The label anchor of a polygon: of its points nearly as deep as the pole of inaccessibility (95 % of that
     * inscribed radius, sampled every 5 % of it), the one nearest its centroid, so that a long even strip gets its
     * number in its middle rather than at an arbitrary end; with that point's depth as the radius.
     */
    private static double[] anchor(Poly poly) {
        double[] pole = pole(poly);
        double[] centroid = centroid(poly.outer());
        Rectangle2D bounds = path(poly.outer()).getBounds2D();
        double step = Math.max(pole[2] * .05, .1);
        double[] best = pole;
        double nearest = Math.hypot(pole[0] - centroid[0], pole[1] - centroid[1]);
        for (double x = bounds.getMinX() + step / 2; x < bounds.getMaxX(); x += step) {
            for (double y = bounds.getMinY() + step / 2; y < bounds.getMaxY(); y += step) {
                double distance = Math.hypot(x - centroid[0], y - centroid[1]);
                if (distance < nearest) {
                    double depth = signedDistance(poly, x, y);
                    if (depth >= .95 * pole[2]) {
                        best = new double[] { x, y, depth };
                        nearest = distance;
                    }
                }
            }
        }
        return best;
    }

    private static double[] centroid(double[] ring) {
        double x = 0;
        double y = 0;
        double twice = 0;
        for (int index = 0, previous = ring.length - 2; index < ring.length; previous = index, index += 2) {
            double cross = ring[previous] * ring[index + 1] - ring[index] * ring[previous + 1];
            twice += cross;
            x += (ring[previous] + ring[index]) * cross;
            y += (ring[previous + 1] + ring[index + 1]) * cross;
        }
        return twice == 0 ? new double[] { ring[0], ring[1] } : new double[] { x / (3 * twice), y / (3 * twice) };
    }

    /** A polylabel cell: centre, half size, signed distance to the polygon and the best distance it may hold. */
    private static double[] cell(Poly poly, double x, double y, double half) {
        double distance = signedDistance(poly, x, y);
        return new double[] { x, y, half, distance, distance + half * Math.sqrt(2) };
    }

    private static double signedDistance(Poly poly, double x, double y) {
        boolean inside = inside(poly.outer(), x, y);
        double distance = distanceToRing(poly.outer(), x, y);
        for (double[] hole : poly.holes()) {
            inside &= !inside(hole, x, y);
            distance = Math.min(distance, distanceToRing(hole, x, y));
        }
        return inside ? distance : -distance;
    }

    private static double distanceToRings(List<double[]> rings, double x, double y) {
        double distance = Double.POSITIVE_INFINITY;
        for (double[] ring : rings) {
            distance = Math.min(distance, distanceToRing(ring, x, y));
        }
        return distance;
    }

    private static double distanceToRing(double[] ring, double x, double y) {
        double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < ring.length; index += 2) {
            int next = (index + 2) % ring.length;
            distance = Math.min(distance, segmentDistance(x, y, ring[index], ring[index + 1], ring[next],
                  ring[next + 1]));
        }
        return distance;
    }

    private static double segmentDistance(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / length, 0, 1);
        return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
    }

    /** Even-odd point in ring. */
    private static boolean inside(double[] ring, double x, double y) {
        boolean inside = false;
        for (int index = 0, previous = ring.length - 2; index < ring.length; previous = index, index += 2) {
            double ax = ring[index];
            double ay = ring[index + 1];
            double bx = ring[previous];
            double by = ring[previous + 1];
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Shoelace area; positive for a counterclockwise ring in a y-up system. */
    private static double signedArea(double[] ring) {
        double sum = 0;
        for (int index = 0, previous = ring.length - 2; index < ring.length; previous = index, index += 2) {
            sum += ring[previous] * ring[index + 1] - ring[index] * ring[previous + 1];
        }
        return sum / 2;
    }

    private static double area(Poly poly) {
        double size = Math.abs(signedArea(poly.outer()));
        for (double[] hole : poly.holes()) {
            size -= Math.abs(signedArea(hole));
        }
        return size;
    }

    /** The area of a java.awt.geom.Area: its flattened rings' signed areas, holes against outer rings. */
    private static double area(Area area) {
        double sum = 0;
        for (double[] ring : rings(area)) {
            sum += signedArea(ring);
        }
        return Math.abs(sum);
    }

    private static Area shape(Poly poly) {
        Area area = new Area(path(poly.outer()));
        poly.holes().forEach(hole -> area.subtract(new Area(path(hole))));
        return area;
    }

    private static Path2D path(double[] ring) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(ring[0], ring[1]);
        for (int index = 2; index < ring.length; index += 2) {
            path.lineTo(ring[index], ring[index + 1]);
        }
        path.closePath();
        return path;
    }

    private static Rectangle2D union(Rectangle2D a, Rectangle2D b) {
        if (a == null) {
            return b;
        }
        Rectangle2D result = new Rectangle2D.Double();
        Rectangle2D.union(a, b, result);
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    // ------------------------------------------------------------------------------------------------ validation

    /**
     * The design 6.2 step 7 checks of one view; each failure names the family, view and region. The metrics go to
     * {@code report}.
     */
    private static List<String> validate(Family family, View view, StringBuilder report) {
        List<String> failures = new ArrayList<>();
        String name = family.id() + " " + view.name();
        report.append("\n== ").append(name).append(" (").append(view.source().file()).append(")\n");
        Entity unit = family.unit().get();
        Set<String> drawn = new LinkedHashSet<>();
        Set<String> rear = new LinkedHashSet<>();
        for (int loc = 0; loc < unit.locations(); loc++) {
            String code = unit.getLocationAbbr(loc);
            if (!NOT_DRAWN.contains(code) && !family.without().contains(code)) {
                drawn.add(code);
                if (unit.hasRearArmor(loc)) {
                    rear.add(code);
                }
            }
        }
        Set<String> expectedArmor = view.name().equals(STRUCTURE) ? Set.of()
              : view.name().equals("armor-rear") ? rear : drawn;
        Set<String> expectedStructure = new TreeSet<>(view.name().equals(STRUCTURE) || family.structureBoxes()
              ? drawn : Set.of());
        expectedStructure.addAll(family.boxes());
        check(failures, name, "armor locations", new TreeSet<>(expectedArmor), codes(view.regions(), ARMOR));
        check(failures, name, "structure locations and boxes", expectedStructure, codes(view.regions(), STRUCTURE));
        // Bipeds and tripods can mount a shield on either arm (user correction 16); their front armor has both.
        boolean arms = family.mek() && view.name().equals("armor") && drawn.containsAll(ARMS);
        check(failures, name, "shield arms", new TreeSet<>(arms ? ARMS : List.of()),
              new TreeSet<>(view.variants().keySet()));

        for (Region region : view.regions()) {
            double exact = area(region.exact());
            double simplified = region.polygons().stream().mapToDouble(PaperdollConverter::area).sum();
            double triangles = triangleArea(region);
            report.append(String.format(Locale.ROOT, "  %-9s %-5s area %8.1f parts %d holes %d anchor %6.1f %6.1f"
                        + " r %5.1f triangles %d%s%n", region.layer(), region.code(), exact, region.polygons().size(),
                  region.polygons().stream().mapToInt(poly -> poly.holes().size()).sum(), region.anchor()[0],
                  region.anchor()[1], region.anchor()[2], region.triangles().length / 3,
                  region.dropped() > .01 ? String.format(Locale.ROOT, " dropped %.2f", region.dropped()) : ""));
            // A structure location inside a one-drawing family shows its number only where it fits (design 4.2).
            double radius = region.layer().equals(STRUCTURE) && !BOXES.contains(region.code()) && !family.mek()
                  ? MIN_STRUCTURE_RADIUS : MIN_RADIUS;
            if (region.anchor()[2] < radius) {
                failures.add(name + ": " + region.layer() + " " + region.code() + " anchor radius "
                      + format(region.anchor()[2]) + " < " + radius);
            }
            if (Math.abs(triangles - simplified) > .01 * simplified + .05) {
                failures.add(name + ": " + region.code() + " triangles cover " + format(triangles) + " of "
                      + format(simplified));
            }
            if (Math.abs(simplified - exact) > .02 * exact + 1) {
                failures.add(name + ": " + region.code() + " simplified area " + format(simplified) + " vs "
                      + format(exact));
            }
        }
        overlaps(failures, report, name, view.regions());
        mirrors(failures, report, family, name, view.regions());
        seeds(failures, report, name, view.regions(), view.source().seeds(), "");
        coverage(failures, report, family, name, view);

        Map<Key, Area> variantAreas = new LinkedHashMap<>();
        for (Variant variant : view.variants().values()) {
            String variantName = name + " +" + variant.arm() + " shield";
            List<Region> merged = merge(view.regions(), variant);
            if (!variant.lost().isEmpty()) {
                failures.add(variantName + " hides " + variant.lost());
            }
            for (Region region : variant.regions()) {
                boolean shield = region.layer().equals(SHIELD);
                boolean arm = region.layer().equals(ARMOR) && region.code().equals(variant.arm());
                if (!shield && !arm) {
                    failures.add(variantName + " changes " + region.code() + ", not only the shield and its arm");
                }
                if (shield && !region.code().endsWith(variant.arm())) {
                    failures.add(variantName + " holds " + region.code());
                }
                report.append(String.format(Locale.ROOT, "  variant %s %-6s area %8.1f anchor %6.1f %6.1f r %5.1f%n",
                      variant.arm(), region.code(), area(region.exact()), region.anchor()[0], region.anchor()[1],
                      region.anchor()[2]));
                if (region.anchor()[2] < MIN_RADIUS && region.code().startsWith("DC")) {
                    failures.add(variantName + ": " + region.code() + " anchor radius " + format(region.anchor()[2]));
                }
                variantAreas.put(new Key(region.layer(), region.code()), region.exact());
            }
            if (codes(variant.regions(), SHIELD).size() != 2) {
                failures.add(variantName + ": not one capacity panel and one absorption strip");
            }
            if (variant.outline().isEmpty() || Math.hypot(variant.split()[2] - variant.split()[0],
                  variant.split()[3] - variant.split()[1]) < 1) {
                failures.add(variantName + ": no outline or split");
            }
            report.append("  variant ").append(variant.arm()).append(" panel anchors");
            for (double[] anchor : variant.panelAnchors()) {
                report.append(String.format(Locale.ROOT, " %.1f %.1f r %.1f;", anchor[0], anchor[1], anchor[2]));
            }
            report.append('\n');
            if (variant.panelAnchors().length != 2 || variant.panelAnchors()[0][2] < MIN_RADIUS
                  || variant.panelAnchors()[1][2] < MIN_RADIUS) {
                failures.add(variantName + ": the capacity panel holds no two numbers");
            }
            overlaps(failures, report, variantName, merged);
            seeds(failures, report, variantName, merged, view.source().seeds(), variant.arm());
        }
        if (view.variants().size() == 2) {
            // A unit may mount both shields: the runtime merges the two variants, so neither may touch the other's
            // regions. Every region of both shields at once is that of the variant that changes it, else the plain one.
            Map<Key, Area> expected = new LinkedHashMap<>();
            view.regions().forEach(region -> expected.put(new Key(region.layer(), region.code()), region.exact()));
            expected.putAll(variantAreas);
            Map<Key, Merged> both = exclusive(view.source().parts(), Set.copyOf(ARMS));
            both.forEach((key, merged) -> {
                if (!expected.containsKey(key) || differs(expected.get(key), merged.area())) {
                    failures.add(name + ": the two shields together change " + key.code());
                }
            });
            List<Region> shields = new ArrayList<>();
            view.variants().values().forEach(variant -> shields.addAll(variant.regions()));
            mirrorPairs(failures, report, name + " shields", family.id() + " shield", shields,
                  List.of(List.of("DCLA", "DCRA"), List.of("DALA", "DARA"), List.of("LA", "RA")));
        }
        return failures;
    }

    private static void check(List<String> failures, String name, String what, Set<String> expected,
          Set<String> actual) {
        if (!expected.equals(actual)) {
            failures.add(name + ": " + what + " " + actual + ", expected " + expected);
        }
    }

    private static Set<String> codes(List<Region> regions, String layer) {
        Set<String> codes = new TreeSet<>();
        regions.stream().filter(region -> region.layer().equals(layer)).forEach(region -> codes.add(region.code()));
        return codes;
    }

    private static double triangleArea(Region region) {
        double sum = 0;
        float[] v = region.vertices();
        short[] t = region.triangles();
        for (int index = 0; index < t.length; index += 3) {
            int a = 2 * t[index];
            int b = 2 * t[index + 1];
            int c = 2 * t[index + 2];
            sum += Math.abs((v[b] - v[a]) * (v[c + 1] - v[a + 1]) - (v[c] - v[a]) * (v[b + 1] - v[a + 1])) / 2;
        }
        return sum;
    }

    /** The plain regions with a variant's regions in place of the ones they replace, in paint order. */
    private static List<Region> merge(List<Region> plain, Variant variant) {
        List<Region> merged = new ArrayList<>(plain);
        for (Region region : variant.regions()) {
            merged.removeIf(existing -> existing.code().equals(region.code())
                  && existing.layer().equals(region.layer()));
            merged.add(region);
        }
        merged.sort(Comparator.comparingInt(Region::z));
        return merged;
    }

    private static void overlaps(List<String> failures, StringBuilder report, String name, List<Region> regions) {
        double worst = 0;
        String pair = "";
        List<Area> areas = regions.stream().map(Region::simplified).toList();
        for (int a = 0; a < regions.size(); a++) {
            for (int b = a + 1; b < regions.size(); b++) {
                Area overlap = new Area(areas.get(a));
                overlap.intersect(areas.get(b));
                double size = overlap.isEmpty() ? 0 : area(overlap);
                if (size > worst) {
                    worst = size;
                    pair = regions.get(a).code() + "/" + regions.get(b).code();
                }
                if (size > MAX_OVERLAP) {
                    failures.add(name + ": " + regions.get(a).code() + " and " + regions.get(b).code() + " overlap "
                          + format(size));
                }
            }
        }
        report.append(String.format(Locale.ROOT, "  largest overlap %.2f %s%n", worst, pair));
    }

    private static void mirrors(List<String> failures, StringBuilder report, Family family, String name,
          List<Region> regions) {
        for (String layer : List.of(ARMOR, STRUCTURE)) {
            mirrorPairs(failures, report, name + " " + layer, family.id() + " " + layer, regions.stream()
                  .filter(region -> region.layer().equals(layer)).toList(), MIRRORED);
        }
    }

    /**
     * Left and right regions mirror about the drawing's axis (the median of the pairs' midpoints): IoU >= 0.9
     * (area shared / area covered), or the lower IoU accepted for an asymmetric drawing ({@link #ASYMMETRIC}, keyed
     * "{family} {layer} {left}/{right}").
     */
    private static void mirrorPairs(List<String> failures, StringBuilder report, String name, String key,
          List<Region> regions, List<List<String>> pairs) {
        List<Region[]> found = new ArrayList<>();
        List<Double> middles = new ArrayList<>();
        for (List<String> pair : pairs) {
            Region left = find(regions, pair.get(0));
            Region right = find(regions, pair.get(1));
            if (left != null && right != null) {
                found.add(new Region[] { left, right });
                middles.add((left.exact().getBounds2D().getCenterX() + right.exact().getBounds2D().getCenterX()) / 2);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        middles.sort(Double::compare);
        double axis = middles.get(middles.size() / 2);
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  %s mirror axis %.1f:", name, axis));
        for (Region[] pair : found) {
            Area mirrored = new Area(pair[0].exact());
            mirrored.transform(new AffineTransform(-1, 0, 0, 1, 2 * axis, 0));
            Area shared = new Area(mirrored);
            shared.intersect(pair[1].exact());
            Area covered = new Area(mirrored);
            covered.add(pair[1].exact());
            double iou = area(shared) / area(covered);
            String codes = pair[0].code() + "/" + pair[1].code();
            double minimum = ASYMMETRIC.getOrDefault(key + " " + codes, MIN_IOU);
            line.append(String.format(Locale.ROOT, " %s %.3f%s", codes, iou,
                  minimum < MIN_IOU ? " (asymmetric drawing, at least " + minimum + ")" : ""));
            if (iou < minimum) {
                failures.add(name + ": " + codes + " mirror IoU " + format(iou));
            }
        }
        report.append(line).append('\n');
    }

    private static Region find(List<Region> regions, String code) {
        return regions.stream().filter(region -> region.code().equals(code)).findFirst().orElse(null);
    }

    /**
     * The pip placeholders of each location (copies of the record sheet's pip rows) lie mostly in the region of their
     * own code, never mostly in another's.
     */
    private static void seeds(List<String> failures, StringBuilder report, String name, List<Region> regions,
          List<Seed> seeds, String arm) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Seed seed : seeds) {
            if (!seed.arm().equals(arm) || !arm.isEmpty() && !seed.code().endsWith(arm)) {
                continue;
            }
            int[] count = counts.computeIfAbsent(seed.code(), code -> new int[3]);
            String hit = null;
            for (int index = regions.size() - 1; index >= 0 && hit == null; index--) {
                Region region = regions.get(index);
                for (Poly poly : region.polygons()) {
                    if (signedDistance(poly, seed.x(), seed.y()) > 0) {
                        hit = region.code();
                        break;
                    }
                }
            }
            count[hit == null ? 2 : hit.equals(seed.code()) ? 0 : 1]++;
        }
        if (counts.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder("  seeds own/other/none:");
        counts.forEach((code, count) -> {
            line.append(' ').append(code).append(' ').append(count[0]).append('/').append(count[1]).append('/')
                  .append(count[2]);
            if (count[1] > count[0]) {
                failures.add(name + ": the pip placeholders of " + code + " lie mostly in another region");
            }
        });
        report.append(line).append('\n');
    }

    /**
     * Mek art is covered: every white-filled piece of a Mek drawing (the silhouette, the hands included; user
     * correction 18) lies in a region. Other families keep their unassigned hull (the record sheet's inner hull, which
     * belongs to no location) as neutral art.
     */
    private static void coverage(List<String> failures, StringBuilder report, Family family, String name,
          View view) {
        double white = area(view.white());
        double uncovered = view.uncovered().isEmpty() ? 0 : area(view.uncovered());
        report.append(String.format(Locale.ROOT, "  white art %.1f, outside every region %.2f%n", white, uncovered));
        if (!family.mek()) {
            return;
        }
        for (Poly piece : polygons(view.uncovered(), new double[1], false)) {
            double size = area(piece);
            double thickness = pole(piece)[2];
            if (size > MAX_GAP && thickness > GAP_THICKNESS) {
                Rectangle2D bounds = new Area(path(piece.outer())).getBounds2D();
                failures.add(name + ": art outside every region, " + format(size) + " unit^2 at "
                      + format(bounds.getX()) + ", " + format(bounds.getY()) + " (" + format(bounds.getWidth()) + " x "
                      + format(bounds.getHeight()) + ")");
            }
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ------------------------------------------------------------------------------------------------ output

    private static String json(Family family, View view) {
        StringBuilder out = new StringBuilder();
        out.append("{\"format\":2,\"family\":\"").append(family.id()).append("\",\"view\":\"").append(view.name())
              .append("\",\n\"source\":\"tools/paperdolls/src/").append(view.source().file()).append("\",\n")
              .append("\"bounds\":").append(numbers(bounds(view.bounds()))).append(",\n\"locations\":[\n");
        List<Region> locations = view.regions().stream().filter(region -> !BOXES.contains(region.code())).toList();
        List<Region> boxes = view.regions().stream().filter(region -> BOXES.contains(region.code())).toList();
        regions(out, locations);
        out.append("],\n\"boxes\":[\n");
        regions(out, boxes);
        out.append("]");
        if (view.hull() != null && !view.hull().polygons().isEmpty()) {
            out.append(",\n\"hull\":{");
            mesh(out, view.hull());
            out.append('}');
        }
        if (!view.variants().isEmpty()) {
            out.append(",\n\"shieldVariants\":{\n");
            int count = 0;
            for (Variant variant : view.variants().values()) {
                Rectangle2D bounds = null;
                for (Region region : variant.regions()) {
                    bounds = union(bounds, region.exact().getBounds2D());
                }
                out.append(count++ > 0 ? ",\n" : "").append('"').append(variant.arm()).append("\":{\"bounds\":")
                      .append(numbers(bounds(bounds))).append(",\"z\":").append(variant.z())
                      .append(",\"split\":").append(numbers(variant.split())).append(",\"panelAnchors\":")
                      .append(rings(List.of(variant.panelAnchors()))).append(",\"outline\":")
                      .append(rings(variant.outline())).append(",\n\"locations\":[\n");
                regions(out, variant.regions());
                out.append("]}");
            }
            out.append("}");
        }
        return out.append("}\n").toString();
    }

    private static void regions(StringBuilder out, List<Region> regions) {
        for (int index = 0; index < regions.size(); index++) {
            Region region = regions.get(index);
            out.append("{\"abbr\":\"").append(region.code()).append("\",\"layer\":\"").append(region.layer())
                  .append("\",\"z\":").append(region.z()).append(",\"anchor\":").append(numbers(region.anchor()))
                  .append(',');
            mesh(out, region);
            out.append(index < regions.size() - 1 ? "},\n" : "}\n");
        }
    }

    private static void mesh(StringBuilder out, Region region) {
        double[] vertices = new double[region.vertices().length];
        for (int index = 0; index < vertices.length; index++) {
            vertices[index] = region.vertices()[index];
        }
        StringBuilder triangles = new StringBuilder("[");
        for (int index = 0; index < region.triangles().length; index++) {
            triangles.append(index > 0 ? "," : "").append(region.triangles()[index]);
        }
        out.append("\"vertices\":").append(numbers(vertices)).append(",\"triangles\":").append(triangles)
              .append("],\"rings\":").append(rings(region.rings()));
    }

    private static String rings(List<double[]> rings) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < rings.size(); index++) {
            out.append(index > 0 ? "," : "").append(numbers(rings.get(index)));
        }
        return out.append(']').toString();
    }

    private static double[] bounds(Rectangle2D bounds) {
        return new double[] { bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight() };
    }

    private static String numbers(double[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            double value = round(values[index]);
            if (value == 0) {
                value = 0;
            }
            String text = String.format(Locale.ROOT, "%.2f", value);
            text = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
            out.append(index > 0 ? "," : "").append(text);
        }
        return out.append(']').toString();
    }

    // ------------------------------------------------------------------------------------------------ review

    /**
     * The family's review image: one panel per view and shield variant, each region in a distinct colour with its code
     * and a sample number at its anchor (the dashed circle is the inscribed radius), the unassigned hull hatched grey,
     * Mek art outside every region in red, and a shield's outline and split in black.
     */
    private static void review(Path file, Family family, List<View> views) throws IOException {
        List<Panel> panels = new ArrayList<>();
        for (View view : views) {
            panels.add(new Panel(view, null));
            for (Variant variant : view.variants().values()) {
                panels.add(new Panel(view, variant));
            }
        }
        int width = panels.size() * (PANEL + 10) + 10;
        int height = PANEL * 4 / 3 + 60;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        for (int index = 0; index < panels.size(); index++) {
            View view = panels.get(index).view();
            Variant variant = panels.get(index).variant();
            int left = 10 + index * (PANEL + 10);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.drawString(family.id() + " " + view.name() + (variant == null ? "" : " + " + variant.arm() + " shield"),
                  left, 22);
            panel(g, view, variant, family.mek(), left, 34, PANEL, PANEL * 4 / 3);
        }
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    private static void panel(Graphics2D g, View view, Variant variant, boolean mek, int left, int top, int width,
          int height) {
        List<Region> regions = variant == null ? view.regions() : merge(view.regions(), variant);
        Rectangle2D frame = view.bounds();
        if (variant != null) {
            for (Region region : variant.regions()) {
                frame = union(frame, region.exact().getBounds2D());
            }
        }
        double scale = Math.min((width - 20) / frame.getWidth(), (height - 20) / frame.getHeight());
        AffineTransform toPanel = new AffineTransform();
        toPanel.translate(left + (width - frame.getWidth() * scale) / 2,
              top + (height - frame.getHeight() * scale) / 2);
        toPanel.scale(scale, scale);
        toPanel.translate(-frame.getX(), -frame.getY());
        g.setColor(new Color(0xF4F4F4));
        g.fillRect(left, top, width, height);
        g.setColor(new Color(0xDDDDDD));
        g.fill(toPanel.createTransformedShape(view.white()));
        if (view.hull() != null) {
            Area hull = view.hull().simplified();
            g.setColor(new Color(0xB8B8B8));
            g.fill(toPanel.createTransformedShape(hull));
            g.setClip(toPanel.createTransformedShape(hull));
            g.setColor(new Color(0x8C8C8C));
            for (int x = left - height; x < left + width; x += 6) {
                g.drawLine(x, top + height, x + height, top);
            }
            g.setClip(null);
        }
        List<String> codes = new ArrayList<>();
        regions.forEach(region -> {
            if (!codes.contains(region.code())) {
                codes.add(region.code());
            }
        });
        for (Region region : regions) {
            Color color = Color.getHSBColor(codes.indexOf(region.code()) * .618034f % 1,
                  region.layer().equals(STRUCTURE) ? .35f : .55f, region.layer().equals(STRUCTURE) ? .75f : .95f);
            g.setColor(color);
            g.fill(toPanel.createTransformedShape(region.simplified()));
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1));
            for (double[] ring : region.rings()) {
                g.draw(toPanel.createTransformedShape(path(ring)));
            }
        }
        if (mek && !view.uncovered().isEmpty()) {
            g.setColor(new Color(255, 0, 0, 170));
            g.fill(toPanel.createTransformedShape(view.uncovered()));
        }
        if (variant != null) {
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(2.5f));
            for (double[] ring : variant.outline()) {
                g.draw(toPanel.createTransformedShape(path(ring)));
            }
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[] { 4, 3 },
                  0));
            double[] split = variant.split();
            g.draw(toPanel.createTransformedShape(new Line2D.Double(split[0], split[1], split[2], split[3])));
            // The capacity panel's two anchors, where both numbers go when the strip is too thin (dark blue).
            g.setColor(new Color(0x1A2F9C));
            g.setStroke(new BasicStroke(1.5f));
            for (double[] anchor : variant.panelAnchors()) {
                double[] center = new double[2];
                toPanel.transform(new double[] { anchor[0], anchor[1] }, 0, center, 0, 1);
                double radius = anchor[2] * scale;
                g.draw(new Ellipse2D.Double(center[0] - radius, center[1] - radius, 2 * radius, 2 * radius));
            }
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        FontMetrics metrics = g.getFontMetrics();
        for (Region region : regions) {
            double[] anchor = region.anchor();
            double[] center = new double[2];
            toPanel.transform(new double[] { anchor[0], anchor[1] }, 0, center, 0, 1);
            double radius = anchor[2] * scale;
            g.setColor(new Color(0, 0, 0, 120));
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[] { 2, 2 }, 0));
            g.draw(new Ellipse2D.Double(center[0] - radius, center[1] - radius, 2 * radius, 2 * radius));
            String label = (region.layer().equals(STRUCTURE) ? region.code().toLowerCase(Locale.ROOT) : region.code());
            String number = String.valueOf(12 + (Math.abs(region.code().hashCode()) % 40));
            g.setColor(Color.BLACK);
            g.drawString(label, (float) (center[0] - metrics.stringWidth(label) / 2.0), (float) center[1] - 1);
            g.drawString(number, (float) (center[0] - metrics.stringWidth(number) / 2.0),
                  (float) center[1] + metrics.getAscent() - 2);
        }
    }
}
