/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;

/** Road material masks on terrain or bridge decks; the supporting surfaces own their physical shape. */
final class GpuRoads {
    static final VertexAttributes VERTICES = new VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(),
          VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0),
          new VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_roadMaskUV"),
          new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_roadMaskRegion", 1));
    record MaskData(BoardScene.Pixels pixels, float x, float y, float width, float height) { }

    /** Renderer-owned exact sharing across chunks/workers; only live materials keep masks in memory. */
    static final class Masks {
        private final Map<MaskData, WeakReference<MaskData>> masks = new WeakHashMap<>();

        synchronized MaskData share(MaskData mask) {
            WeakReference<MaskData> reference = masks.get(mask);
            MaskData shared = reference == null ? null : reference.get();
            if (shared != null) { return shared; }
            masks.put(mask, new WeakReference<>(mask));
            return mask;
        }
    }

    /** Borrowed chunk-atlas region, retained per tile range so an edit can relocate unchanged road vertices. */
    static final class Mask extends TextureAttribute {
        static final long TYPE = register("roadMask");
        static { TextureAttribute.Mask |= TYPE; }
        final MaskData data;

        Mask(TextureRegion region, MaskData data) {
            super(TYPE, region);
            this.data = data;
        }

        Mask withRegion(TextureRegion region) { return new Mask(region, data); }

        @Override
        public Mask copy() {
            var copy = new Mask(new TextureRegion(textureDescription.texture), data);
            copy.textureDescription.set(textureDescription);
            copy.offsetU = offsetU; copy.offsetV = offsetV; copy.scaleU = scaleU; copy.scaleV = scaleV;
            return copy;
        }
    }
    private static final class Surface extends FloatAttribute {
        static final long TYPE = register("roadSurface");
        final float lift;
        Surface(float transition, float lift) { super(TYPE, transition); this.lift = lift; }
        @Override public Surface copy() { return new Surface(value, lift); }
        @Override public int hashCode() { return 31 * super.hashCode() + Float.floatToIntBits(lift); }
        @Override public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            int transition = Float.compare(value, ((Surface) other).value);
            return transition != 0 ? transition : Float.compare(lift, ((Surface) other).lift);
        }
    }
    static final class Maps extends TextureAttribute {
        static final long TYPE = register("roadMaterialMaps");
        static { TextureAttribute.Mask |= TYPE; }
        Maps(Texture texture) { super(TYPE, texture); }
        @Override
        public Maps copy() { return new Maps(textureDescription.texture); }
    }
    static final class Soil extends FloatAttribute {
        static final long TYPE = register("roadSoil");
        Soil() { super(TYPE, 1); }
    }
    /** Road materials, in the order of their maps in {@link GpuAssets#roadArray}. */
    static final List<String> MATERIALS = List.of("asphalt", "dirt", "gravel");
    /**
     * The road and sculpt map arrays that merged coats sample. Each coat's own maps, finish and wet response travel in
     * its vertices ({@link #coat}), so all coats of a chunk share one material and one draw.
     */
    static final class Coats extends Attribute {
        static final long TYPE = register("roadCoats");
        final GpuAssets.MaterialArrays roads, sculpt;

        Coats(GpuAssets.MaterialArrays roads, GpuAssets.MaterialArrays sculpt) {
            super(TYPE);
            this.roads = roads;
            this.sculpt = sculpt;
        }

        @Override
        public Coats copy() { return new Coats(roads, sculpt); }

        @Override
        public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            int result = Integer.compare(System.identityHashCode(roads), System.identityHashCode(((Coats) other).roads));
            return result != 0 ? result : Integer.compare(System.identityHashCode(sculpt), System.identityHashCode(((Coats) other).sculpt));
        }

        @Override
        public int hashCode() { return java.util.Objects.hash(super.hashCode(), roads, sculpt); }
    }
    static final long TYPE = Surface.TYPE;
    /** Pavement and bridge decks share this small clearance above their supporting elevation. */
    static final float SURFACE_LIFT = .04f;
    static final float WHEEL_TINT = .92f;
    /** The paved carriageway's edge fades out over this width inside its half width (car parks meet its middle). */
    static final float FEATHER = .45f;
    private static final float LOOSE_FEATHER = 3;

    record Fade(float outer, float inner, float end, boolean wheels, BoardRoad.Join join, float setback, boolean landing) {
        Fade(float outer, float inner, float end) { this(outer, inner, end, false, null, 0, false); }
        Fade(float outer, float inner, float end, boolean wheels, BoardRoad.Join join) {
            this(outer, inner, end, wheels, join, 0, false);
        }
    }
    record Patch(Area shape, String texture, Color tint, float repeat, float lift, Fade fade) {
        boolean blended() { return fade != null; }
        float endFade() { return fade == null ? 0 : fade.end(); }
    }

    private GpuRoads() { }

    static String vertex(String source) {
        return source.replace("void main() {",
              GpuShaderSource.read("road-mask.vert") + "\nvoid main() {\nroadMaskCoordinates();\n");
    }

    static FloatAttribute attribute(Patch patch) { return new Surface(finish(patch), patch.lift()); }

    private static float finish(Patch patch) {
        boolean transition = patch.fade() != null && patch.fade().join() != null;
        // Positive: material joins. Negative: loose (-1), wheels (-2), landing asphalt (-3) or aggregate/soil (-4).
        float finish = patch.fade() != null && patch.fade().wheels() ? -2
              : patch.fade() != null && patch.fade().landing() ? patch.texture().equals("roads/asphalt") ? -3 : -4
              : patch.texture().equals("roads/dirt") || patch.texture().equals("roads/gravel") ? -1 : 0;
        return transition ? patch.texture().equals("roads/dirt") ? 2 : 1 : finish;
    }

    /**
     * A merged coat's vertex colour: its maps (a {@link #MATERIALS} index, or 3 plus a sculpt material's index), its
     * finish offset by 4, and its wet response in 255ths. Unmerged coats keep white.
     */
    static float coat(int maps, Patch patch, float response) {
        return Color.toFloatBits(maps, Math.round(finish(patch)) + 4, Math.round(response * 255), 255);
    }

    /** Region placement is vertex data; only the atlas page and actual surface state split draw calls. */
    static Material batchMaterial(Material material) {
        Mask mask = material.get(Mask.class, Mask.TYPE);
        if (mask == null) { return material; }
        Material shared = new Material(material);
        shared.set(new TextureAttribute(Mask.TYPE, mask.textureDescription));
        // A merged coat's finish is in its vertices, and its lift orders it within the chunk's one draw.
        if (shared.has(Coats.TYPE)) { shared.set(new Surface(0, 0)); }
        return shared;
    }

    /** A merged draw's continuation in another mesh starts at the given coat's lift, which orders it after the first. */
    static Material lifted(Material batch, Material coat) {
        Material continued = new Material(batch);
        continued.set(new Surface(0, layer(coat)));
        return continued;
    }

    static float layer(Material material) { return material.get(Surface.class, TYPE).lift; }

    /** Atlas replacements must update every reused vertex, even when different masks now share a draw. */
    static void relocate(float[] vertices, Mask mask) {
        int stride = VERTICES.vertexSize / Float.BYTES;
        int region = VERTICES.get(VERTICES.size() - 1).offset / Float.BYTES;
        for (int i = region; i < vertices.length; i += stride) {
            vertices[i] = mask.offsetU; vertices[i + 1] = mask.offsetV;
            vertices[i + 2] = mask.scaleU; vertices[i + 3] = mask.scaleV;
        }
    }

    static String texture(BoardRoad.Kind kind) {
        return "roads/" + switch (kind) {
            case PAVED, ALLEY -> "asphalt";
            case GRAVEL -> "gravel";
            default -> "dirt";
        };
    }

    static List<Patch> patches(BoardScene.Tile tile, BoardRoad road) {
        return patches(tile.coords(), tile.surface(), tile.road(), tile.roadExits(), road, false,
              HexAppearance.trafficControl(tile.appearance(), "road", tile.roadExits()), road.crossing(tile, false));
    }

    /** The authored material of this road layer's own paint; verges and neighbouring transition coats keep theirs. */
    static String material(BoardScene.Tile tile, boolean deck, String texture) {
        if (!texture.startsWith("roads/")) { return null; }
        String base = texture.substring("roads/".length());
        if (!texture.equals(texture(deck ? tile.deckRoad() : tile.road()))) { return base; }
        var style = tile.appearance().get(deck ? "bridgeRoad" : "road");
        if (style == null) { return base; }
        var variant = BoardEditorBlueprint.get().variant(style.variant());
        String authored = style.material() != null ? style.material() : variant == null ? "" : variant.material();
        return authored.isBlank() ? base : authored;
    }

    /** The deck road's paint: its kind on its own sides ({@link BoardBridge.Deck#road}). */
    static List<Patch> deckPatches(BoardScene.Tile tile, BoardBridge.Deck deck, BoardRoad road) {
        // A rock arch carries ordinary ground paint, including loose shoulders and worn ends. Only a built deck
        // clips them to its carriageway; the arch's own top facets already keep the paint off its sides.
        return patches(tile.coords(), tile.surface(), deck.kind(), deck.roadExits(), road, !deck.natural(),
              HexAppearance.trafficControl(tile.appearance(), "bridgeRoad", deck.roadExits()), road.crossing(tile, true));
    }

    /** Tile pixels a paved deck's asphalt stays solid past its footing on a bare bank, before fading over the apron. */
    static float landingSetback(BoardBridge.Deck deck) { return setback(paved(deck.kind())); }

    /** Tile pixels asphalt stays solid before a bare end, before fading over its apron; none for loose roads. */
    private static float setback(boolean paved) { return paved ? BoardRelief.metres(2.5f) / BoardGeometry.hexScale() : 0; }

    private static boolean paved(BoardRoad.Kind kind) { return kind == BoardRoad.Kind.PAVED || kind == BoardRoad.Kind.ALLEY; }

    /** A bare bank gets a grounded material apron after the solid deck and its tapered rails have ended. */
    static List<Patch> deckPatches(BoardScene.Tile tile, BoardBridge.Deck deck, BoardRoad road, BoardBridgeFooting footing) {
        var patches = deckPatches(tile, deck, road);
        return footing.bareExits() == 0 ? patches : bare(patches, road, paved(deck.kind()));
    }

    /**
     * The road painting a hex's ground: one ending on a cliff's top (BoardRoadGrade#overDrop) runs on just past the rim,
     * so its paint wears out to gravel at the very edge, as if crumbling with the cliff's fall (#bare).
     */
    static BoardRoad painted(BoardScene scene, BoardScene.Tile tile, BoardRoad road) {
        int drops = 0;
        List<Float> past = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f));
        for (int d = 0; d < 6; d++) {
            if ((tile.roadExits() & 1 << d) != 0 && BoardRoadGrade.overDrop(tile, scene.neighbor(tile, d), d)) {
                drops |= 1 << d;
                past.set(d, BoardRelief.metres(CRUMBLE) / BoardGeometry.hexScale());
            }
        }
        return drops == 0 ? road : road.extended(past, drops);
    }

    /** How far, in metres, a road ending on a cliff's top runs on past its rim: its asphalt there is mostly gravel. */
    private static final float CRUMBLE = 3.5f;

    /**
     * The paint of a road, worn out towards the ends {@link #painted} gives it on cliff tops. A road built in concrete
     * (BoardRoadGrade#poured) stands on its cast base, whose top shows beside the paint.
     */
    static List<Patch> patches(BoardScene scene, BoardScene.Tile tile, BoardRoad painted, BoardRoad road) {
        var patches = new ArrayList<>(patches(tile, painted));
        var grade = BoardRoadGrade.of(scene, tile, true);
        if (grade != null && grade.poured()) {
            float scale = BoardGeometry.hexScale();
            Area base = grade.region().createTransformedArea(new AffineTransform(1 / scale, 0, 0, 1 / scale,
                  -BoardGeometry.centerX(tile.coords()) / scale, -BoardGeometry.centerY(tile.coords()) / scale));
            patches.add(new Patch(base, "concrete", Color.WHITE, 1, CAST_LIFT, null));
        }
        return painted == road ? patches : bare(patches, painted, paved(tile.road()));
    }

    /** A cast base's lift: over the loose verge, under the carriageway's own paint. */
    private static final float CAST_LIFT = .03f;

    /** A road carried on through a ground portal to the end of its tunnel. */
    static BoardRoad through(BoardRoad road, BoardTunnel tunnel) {
        List<Float> lengths = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f));
        lengths.set(tunnel.direction(), BoardTunnel.DEPTH - BoardTunnel.SETBACK);
        return road.extended(lengths, 1 << tunnel.direction());
    }

    /**
     * The paint a road {@link #through} a ground portal carries on past its hex's edge: its verges and markings, on the
     * tunnel's floor, which carries the road's own surface.
     */
    static List<Patch> tunnelPatches(BoardScene.Tile tile, BoardRoad road, BoardTunnel tunnel) {
        float scale = BoardGeometry.hexScale(), cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        Path2D.Float outline = new Path2D.Float();
        for (Vector3 corner : tunnelFloor(tunnel)) {
            float x = (corner.x - cx) / scale, y = (corner.y - cy) / scale;
            if (outline.getCurrentPoint() == null) { outline.moveTo(x, y); } else { outline.lineTo(x, y); }
        }
        outline.closePath();
        Area floor = new Area(outline);
        String surface = texture(tile.road());
        List<Patch> result = new ArrayList<>();
        for (var patch : patches(tile, road)) {
            if (patch.texture().equals(surface)) { continue; }
            Area shape = new Area(patch.shape());
            shape.intersect(floor);
            if (!shape.isEmpty()) {
                result.add(new Patch(shape, patch.texture(), patch.tint(), patch.repeat(), patch.lift(), patch.fade()));
            }
        }
        return result;
    }

    /** The floor a tunnel's paint lies on, flat at the portal's base: the opening's width, from the hex's edge in. */
    static List<BoardTacticalGeometry.Triangle> tunnelFloor(BoardTunnel tunnel, Patch patch) {
        var corners = tunnelFloor(tunnel);
        float lift = patch.lift() * BoardGeometry.hexScale();
        for (var corner : corners) { corner.z += lift; }
        return List.of(new BoardTacticalGeometry.Triangle(corners.get(0), corners.get(1), corners.get(2), -1),
              new BoardTacticalGeometry.Triangle(corners.get(0), corners.get(2), corners.get(3), -1));
    }

    private static List<Vector3> tunnelFloor(BoardTunnel tunnel) {
        float scale = BoardGeometry.hexScale(), half = BoardTunnel.HALF_WIDTH * tunnel.widthScale() * scale;
        Vector3 along = tunnel.along(), across = new Vector3(along.y, -along.x, 0);
        List<Vector3> corners = new ArrayList<>();
        for (float[] c : new float[][] { { -1, BoardTunnel.SETBACK }, { 1, BoardTunnel.SETBACK },
              { 1, BoardTunnel.DEPTH }, { -1, BoardTunnel.DEPTH } }) {
            corners.add(new Vector3(tunnel.origin()).mulAdd(across, c[0] * half).mulAdd(along, c[1] * scale));
        }
        return corners;
    }

    /** A tunnel's paint mask, darkening into it as its floor does (BoardTunnel#light). */
    static MaskData tunnelMask(BoardScene.Tile tile, BoardRoad road, Patch patch, BoardTunnel tunnel) {
        float scale = BoardGeometry.hexScale(), cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        return mask(patch, (x, y, scratch) -> {
            Color tint = color(road, patch, x, y, scratch);
            // The shader reads a mask's tint as sRGB.
            float light = (float) Math.pow(BoardTunnel.light(tunnel.depth(cx + x * scale, cy + y * scale)), 1 / 2.2);
            return tint.set(tint.r * light, tint.g * light, tint.b * light, tint.a);
        });
    }

    /** Paint ending on bare ground: a grounded material apron, the road's own paint fading out over it. */
    private static List<Patch> bare(List<Patch> patches, BoardRoad road, boolean paved) {
        float apron = BoardBridgeFooting.apronLength();
        float setback = setback(paved);
        Area landing = road.endZone(apron);
        var result = new ArrayList<Patch>();
        if (paved) {
            float shoulder = BoardRoad.SHOULDER * 3;
            Area earth = road.footprint(shoulder + 1);
            earth.intersect(landing);
            result.add(new Patch(earth, "roads/dirt", Color.WHITE, 1, .02f,
                  new Fade(shoulder + 1, -1, apron, false, null, 0, true)));
            Area gravel = road.footprint(shoulder);
            gravel.intersect(landing);
            result.add(new Patch(gravel, "roads/gravel", Color.WHITE, 1, .025f,
                  new Fade(shoulder, -1, apron, false, null, 0, true)));
        }
        for (var patch : patches) {
            Area head = new Area(patch.shape());
            if (patch.texture().equals("concrete")) {
                head.subtract(road.endZone(apron
                      + BoardBridgeFooting.terminalLength() + BoardRelief.metres(.3f) / BoardGeometry.hexScale()));
                result.add(new Patch(head, patch.texture(), patch.tint(), patch.repeat(), patch.lift(), patch.fade()));
                continue;
            }
            Area tail = new Area(patch.shape());
            tail.intersect(landing);
            head.subtract(tail);
            result.add(new Patch(head, patch.texture(), patch.tint(), patch.repeat(), patch.lift(), patch.fade()));
            if (tail.isEmpty()) { continue; }
            var fade = patch.fade();
            result.add(new Patch(tail, patch.texture(), patch.tint(), patch.repeat(), patch.lift(),
                  new Fade(0, paved ? -FEATHER : -LOOSE_FEATHER, apron - setback,
                        fade != null && fade.wheels(), fade == null ? null : fade.join(), setback, true)));
        }
        return result;
    }

    private static List<Patch> patches(Coords coords, BoardScene.Surface family, BoardRoad.Kind kind,
          int exits, BoardRoad road, boolean deck, HexAppearance.TrafficControl control, BoardRoad.Crossing crossing) {
        boolean sand = family == BoardScene.Surface.SAND;
        boolean snow = family == BoardScene.Surface.SNOW;
        boolean paved = kind == BoardRoad.Kind.PAVED || kind == BoardRoad.Kind.ALLEY;
        // The route's compacted soil continues across biome borders; only its loose verge takes the local cover.
        String texture = texture(kind);
        Color tint = Color.WHITE;
        float repeat = 1;
        float endFade = deck || paved || Integer.bitCount(exits) > 1 ? 0 : kind == BoardRoad.Kind.DIRT ? 17 : 7;
        List<Patch> patches = new ArrayList<>();
        float feather = deck ? 0 : paved ? FEATHER : LOOSE_FEATHER;
        Area core = road.footprint(-feather);
        Area tail = new Area(core);
        tail.intersect(road.endZone(endFade));
        core.subtract(tail);
        if (paved && !deck) {
            Area verge = road.footprint(BoardRoad.SHOULDER);
            verge.subtract(road.footprint(-FEATHER));
            patches.add(new Patch(verge, snow ? "snow" : sand ? "sand" : "roads/gravel", Color.WHITE,
                  snow ? 3 : sand ? 1.4f : 1, .025f, new Fade(BoardRoad.SHOULDER, -.2f, endFade)));
        }
        patches.add(new Patch(core, texture, tint, repeat, SURFACE_LIFT, null));
        if (!tail.isEmpty()) {
            patches.add(new Patch(tail, texture, tint, repeat, SURFACE_LIFT, new Fade(0, 0, endFade)));
        }
        float outer = deck || paved ? 0 : BoardRoad.SHOULDER;
        Area edge = road.footprint(outer);
        edge.subtract(road.footprint(-feather));
        patches.add(new Patch(edge, texture, tint, repeat, .045f, new Fade(outer, -feather, endFade)));
        for (var join : road.joins()) {
            String next = texture(join.kind());
            if (texture.equals(next)) { continue; }
            boolean looseJoin = !paved && (join.kind() == BoardRoad.Kind.DIRT || join.kind() == BoardRoad.Kind.GRAVEL);
            float joinOuter = !deck && looseJoin ? BoardRoad.SHOULDER : 0;
            float joinFeather = deck ? 0 : looseJoin ? LOOSE_FEATHER : FEATHER;
            Area change = road.footprint(joinOuter);
            change.intersect(join.area());
            core.subtract(change);
            tail.subtract(change);
            edge.subtract(change);
            // Both tiles use the same layer order and world UVs: dirt over asphalt, gravel over either.
            // Replacing the base in this small region avoids two opposing blends with a seam at the hex edge.
            String base = texture.compareTo(next) < 0 ? texture : next;
            String cover = base.equals(texture) ? next : texture;
            Area baseCore = new Area(change);
            baseCore.intersect(road.footprint(-joinFeather));
            Area baseEdge = new Area(change);
            baseEdge.subtract(baseCore);
            patches.add(new Patch(baseCore, base, tint, repeat, SURFACE_LIFT, null));
            patches.add(new Patch(baseEdge, base, tint, repeat, .045f, new Fade(joinOuter, -joinFeather, 0)));
            patches.add(new Patch(baseCore, cover, tint, repeat, .075f, new Fade(0, 0, 0, false, join)));
            patches.add(new Patch(baseEdge, cover, tint, repeat, .075f, new Fade(joinOuter, -joinFeather, 0, false, join)));
        }
        if (kind == BoardRoad.Kind.PAVED) {
            var approaches = road.stopApproaches(exits, crossing);
            if (control != HexAppearance.TrafficControl.STOPS) {
                Area box = keepClear(road, exits, approaches);
                box.subtract(crossing.clearance());
                if (!box.isEmpty()) {
                    patches.add(new Patch(box, "concrete", new Color(1, .78f, .08f, 1), 1.5f, .065f, null));
                }
            }
            var divider = markings(road, coords, exits);
            divider.shape().add(road.approachLines(exits, crossing.approaches()));
            divider.shape().subtract(crossing.clearance());
            patches.add(divider);
            Area stops = road.stopLines(approaches);
            stops.add(road.flowMarkings());
            stops.subtract(crossing.clearance());
            if (!stops.isEmpty()) {
                patches.add(new Patch(stops, "concrete", new Color(.96f, .96f, .92f, 1), 1.5f, .065f, null));
            }
            Area median = road.medianLines();
            median.subtract(crossing.clearance());
            if (!median.isEmpty()) {
                patches.add(new Patch(median, "concrete", new Color(.96f, .96f, .92f, 1), 1.5f, .065f, null));
            }
        } else if (!paved) {
            Area tracks = road.tracks(exits);
            Area worn = new Area(tracks);
            worn.intersect(road.endZone(6));
            tracks.subtract(worn);
            Color wornSoil = new Color(WHEEL_TINT, WHEEL_TINT, WHEEL_TINT, 1);
            patches.add(new Patch(tracks, texture, wornSoil, repeat, .065f, new Fade(0, 0, 0, true, null)));
            if (!worn.isEmpty()) {
                patches.add(new Patch(worn, texture, wornSoil, repeat, .065f, new Fade(0, 0, 6, true, null)));
            }
        }
        Area concrete = road.concreteClearance();
        if (!concrete.isEmpty()) {
            for (var patch : patches) {
                if (patch.texture().equals("concrete")) { patch.shape().subtract(concrete); }
            }
        }
        // Rails bound a deck: loose margins and wear must never float outside its carriageway.
        if (deck) {
            Area carriageway = road.footprint(0);
            for (var patch : patches) { patch.shape().intersect(carriageway); }
        }
        return patches;
    }

    /** The chosen box-junction convention: a hollow yellow lattice inside the four controlled stop planes. */
    static Area keepClear(BoardRoad road, int exits) {
        return keepClear(road, exits, road.stopApproaches(exits));
    }

    private static Area keepClear(BoardRoad road, int exits, List<BoardRoad.StopApproach> approaches) {
        if (Integer.bitCount(exits & 63) != 4) { return new Area(); }
        if (approaches.size() != 4) { return new Area(); }
        Area box = road.footprint(-1);
        for (var approach : approaches) {
            float x = approach.x() - approach.nx() * 3, y = approach.y() - approach.ny() * 3;
            Path2D.Float beyond = new Path2D.Float();
            beyond.moveTo(x - 128 * approach.ny(), y + 128 * approach.nx());
            beyond.lineTo(x + 128 * approach.ny(), y - 128 * approach.nx());
            beyond.lineTo(x + 128 * (approach.ny() + approach.nx()), y + 128 * (approach.ny() - approach.nx()));
            beyond.lineTo(x + 128 * (-approach.ny() + approach.nx()), y + 128 * (approach.nx() + approach.ny()));
            beyond.closePath();
            box.subtract(new Area(beyond));
        }
        if (box.isEmpty()) { return box; }
        Area paint = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(box));
        var first = approaches.getFirst();
        double angle = Math.atan2(first.ny(), first.nx()) + Math.PI / 4;
        var bounds = AffineTransform.getRotateInstance(-angle).createTransformedShape(box).getBounds2D();
        float margin = .65f / 2 + .001f;
        Path2D.Float grid = new Path2D.Float();
        // Keep the same lattice phase and endpoints, but omit strokes that cannot reach the box.
        for (int offset = -128; offset <= 128; offset += 8) {
            if (offset >= bounds.getMinX() - margin && offset <= bounds.getMaxX() + margin) {
                grid.moveTo(offset, -128); grid.lineTo(offset, 128);
            }
            if (offset >= bounds.getMinY() - margin && offset <= bounds.getMaxY() + margin) {
                grid.moveTo(-128, offset); grid.lineTo(128, offset);
            }
        }
        grid.transform(AffineTransform.getRotateInstance(angle));
        paint.add(new Area(new BasicStroke(.65f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER).createStrokedShape(grid)));
        paint.intersect(box);
        Area reserve = road.medianBand();
        // Leave a small paint-free margin so antialiasing cannot bleed yellow onto the ballast edge.
        reserve.add(new Area(new BasicStroke(.5f).createStrokedShape(reserve)));
        paint.subtract(reserve);
        return paint;
    }

    static Patch markings(BoardRoad road, Coords coords, int exits) {
        return new Patch(road.markings(coords, exits), "concrete", new Color(.96f, .96f, .92f, 1), 1.5f, .065f, null);
    }

    /** Flat carriers clip deck materials at the hex edges without draping them onto the riverbed. */
    static List<BoardTacticalGeometry.Triangle> deck(BoardScene.Tile tile, BoardScene.Feature bridge, Patch patch) {
        return deck(tile, bridge, patch, null);
    }

    static List<BoardTacticalGeometry.Triangle> deck(BoardScene.Tile tile, BoardScene.Feature bridge, Patch patch,
          BoardBridge.Shape footing) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float elevation = tile.elevation() + bridge.elevation();
        // Keep the base coat above the authored deck while preserving the ordinary road layer order.
        float lift = (patch.lift() + .01f) * BoardGeometry.hexScale();
        Vector3 center = BoardGeometry.center(tile.coords(), elevation).add(0, 0, lift);
        for (int i = 0; i < 6; i++) {
            result.add(new BoardTacticalGeometry.Triangle(center,
                  BoardGeometry.corner(tile.coords(), elevation, i).add(0, 0, lift),
                  BoardGeometry.corner(tile.coords(), elevation, i + 1).add(0, 0, lift), -1));
        }
        if (footing != null) {
            result.addAll(deck(patch, footing, SURFACE_LIFT));
        }
        return result;
    }

    /**
     * Every coat on the same triangles as the structural surface, the TOP facets of {@code shape}, whose top already
     * stands {@code lift} (model px) above the deck's plane: a built deck's slab and sloped decks at the road lift, a rock
     * arch's top at none.
     */
    static List<BoardTacticalGeometry.Triangle> deck(Patch patch, BoardBridge.Shape shape, float lift) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float offset = (patch.lift() + .01f - lift) * BoardGeometry.hexScale();
        for (var face : shape.facets()) {
            if (face.part() != BoardBridge.Part.TOP) { continue; }
            result.add(new BoardTacticalGeometry.Triangle(new Vector3(face.a()).add(0, 0, offset),
                  new Vector3(face.b()).add(0, 0, offset), new Vector3(face.c()).add(0, 0, offset), -1));
        }
        return result;
    }

    static List<BoardTacticalGeometry.Triangle> deck(BoardScene.Tile tile, BoardScene.Feature bridge, Patch patch,
          BoardBridgeFooting footing, Map<Coords, BoardSurface> surfaces, BoardBridge.Shape slope) {
        var result = slope == null ? deck(tile, bridge, patch, footing.shape()) : deck(patch, slope, SURFACE_LIFT);
        for (int d = 0; d < 6; d++) {
            if ((footing.bareExits() & (1 << d)) == 0) { continue; }
            // Only the existing dry bank carries the apron. Never drape it onto the surface under the span.
            result.addAll(drape(tile, surfaces.get(tile.coords().translated(d)), patch));
        }
        return result;
    }

    /** Whole terrain triangles carry the splat. Road borders, paint and tyre wear do not split them. */
    static List<BoardTacticalGeometry.Triangle> drape(BoardScene.Tile tile, BoardSurface surface, Patch patch) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float scale = BoardGeometry.hexScale();
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        var bounds = patch.shape().getBounds2D();
        // A neighbour's ground road at another level paints only the road surface it reserves here; a bridge's deck
        // carries its apron onto the bank on purpose.
        boolean apart = tile.elevation() != surface.tile.elevation() && BoardBridge.feature(tile) == null;
        for (var face : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP || apart && !surface.carries(face, tile)) { continue; }
            float area = (face.b().x - face.a().x) * (face.c().y - face.a().y)
                  - (face.b().y - face.a().y) * (face.c().x - face.a().x);
            // Nearly vertical slivers can remain where terrain strips meet. They have no paintable footprint.
            if (area <= .0001f * scale * scale) { continue; }
            float minX = (Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - cx) / scale;
            float maxX = (Math.max(face.a().x, Math.max(face.b().x, face.c().x)) - cx) / scale;
            float minY = (Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - cy) / scale;
            float maxY = (Math.max(face.a().y, Math.max(face.b().y, face.c().y)) - cy) / scale;
            if (!bounds.intersects(minX, minY, maxX - minX, maxY - minY)) { continue; }
            result.add(new BoardTacticalGeometry.Triangle(new Vector3(face.a()).add(0, 0, patch.lift() * scale),
                  new Vector3(face.b()).add(0, 0, patch.lift() * scale), new Vector3(face.c()).add(0, 0, patch.lift() * scale), -1));
        }
        return result;
    }

    /** Shape/coverage data only: repeating albedo, normals and roughness keep their original full resolution. */
    static MaskData mask(BoardRoad road, Patch patch) {
        return mask(patch, (x, y, scratch) -> color(road, patch, x, y, scratch));
    }

    /**
     * The mask texels this carrier can display. A neighbour's shared mask can change outside these triangles without
     * changing this hex. Keep a texel around the edges for linear filtering; discard wholly invisible patches.
     */
    static MaskData visibleMask(Coords at, MaskData mask, List<BoardTacticalGeometry.Triangle> triangles) {
        var pixels = mask.pixels();
        float density = pixels.width() / mask.width();
        var path = new Path2D.Float();
        for (var triangle : triangles) {
            var points = List.of(triangle.a(), triangle.b(), triangle.c());
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i);
                float x = maskU(at, mask, p) * pixels.width(), y = maskV(at, mask, p) * pixels.height();
                if (i == 0) { path.moveTo(x, y); } else { path.lineTo(x, y); }
            }
            path.closePath();
        }
        var image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fill(path);
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(path);
        } finally { graphics.dispose(); }
        int[] visible = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        // Decode a compacted mask once, not by a search per texel.
        var decoded = java.nio.IntBuffer.allocate(visible.length);
        pixels.writeRgba(decoded, 0, visible.length);
        int minX = pixels.width(), minY = pixels.height(), maxX = -1, maxY = -1;
        for (int y = 0; y < pixels.height(); y++) {
            for (int x = 0; x < pixels.width(); x++) {
                int i = y * pixels.width() + x, rgba = decoded.get(i);
                if (visible[i] == 0 || (rgba & 255) == 0) { visible[i] = 0; continue; }
                visible[i] = Integer.rotateRight(rgba, 8);
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX) { return null; }
        return new MaskData(new BoardScene.Pixels(image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1)).compact(),
              Math.round(mask.x() * density + minX) / density, Math.round(mask.y() * density + minY) / density,
              (maxX - minX + 1) / density, (maxY - minY + 1) / density);
    }

    @FunctionalInterface
    interface MaskColor {
        Color at(float x, float y, Color scratch);
    }

    /** Ground treatments share the same soft mask carrier and original surface material maps as road coats. */
    static MaskData mask(Patch patch, MaskColor color) {
        // The supporting terrain already clips the paint. Its fitted edge can extend beyond the original hex box.
        var bounds = patch.shape().getBounds2D();
        int x = (int) Math.floor(bounds.getMinX()) - 1;
        int y = (int) Math.floor(bounds.getMinY()) - 1;
        int w = Math.max(1, (int) Math.ceil(bounds.getMaxX()) - x + 1);
        int h = Math.max(1, (int) Math.ceil(bounds.getMaxY()) - y + 1);
        int density = 4;
        var image = new BufferedImage(w * density, h * density, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(density, density);
        graphics.translate(-x, -y);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fill(patch.shape());
        graphics.dispose();
        // This newly allocated ARGB image owns a contiguous raster. Paint it in place; Pixels takes the final copy.
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        Color scratch = new Color();
        for (int py = 0; py < image.getHeight(); py++) {
            for (int px = 0; px < image.getWidth(); px++) {
                int i = py * image.getWidth() + px;
                float alpha = (pixels[i] >>> 24) / 255f;
                Color tint = alpha == 0 ? patch.tint()
                      : color.at(x + (px + .5f) / density, y + (py + .5f) / density, scratch);
                pixels[i] = Math.round(alpha * tint.a * 255) << 24 | Math.round(tint.r * 255) << 16
                      | Math.round(tint.g * 255) << 8 | Math.round(tint.b * 255);
            }
        }
        return new MaskData(new BoardScene.Pixels(image).compact(), x, y, w, h);
    }

    static void write(Supplier<MeshPartBuilder> meshes, List<BoardTacticalGeometry.Triangle> triangles,
          Patch patch, Mask mask, BoardSurface surface, boolean flat, float coat) {
        Map<Vector3, Vector3> normals = new HashMap<>();
        for (var face : flat ? List.<BoardSurface.Face>of() : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (var p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(normalKey(p, 0), unused -> new Vector3()).add(normal);
            }
        }
        for (var t : triangles) {
            float repeat = BoardRelief.detailMetres(patch.repeat());
            float lift = patch.lift() * BoardGeometry.hexScale();
            Vector3 fallback = new Vector3(t.b()).sub(t.a()).crs(new Vector3(t.c()).sub(t.a())).nor();
            Vector3 a = flat ? fallback : new Vector3(normals.getOrDefault(normalKey(t.a(), lift), fallback)).nor();
            Vector3 b = flat ? fallback : new Vector3(normals.getOrDefault(normalKey(t.b(), lift), fallback)).nor();
            Vector3 c = flat ? fallback : new Vector3(normals.getOrDefault(normalKey(t.c(), lift), fallback)).nor();
            MeshPartBuilder mesh = meshes.get();
            mesh.triangle(carrier(mesh, t.a(), a, repeat, mask, surface.tile, coat),
                  carrier(mesh, t.b(), b, repeat, mask, surface.tile, coat),
                  carrier(mesh, t.c(), c, repeat, mask, surface.tile, coat));
        }
    }

    private static Vector3 normalKey(Vector3 p, float lift) {
        return new Vector3(p.x, p.y, Math.round((p.z - lift) / (.001f * BoardGeometry.hexScale())));
    }

    private static short carrier(MeshPartBuilder mesh, Vector3 p, Vector3 normal, float repeat, Mask mask, BoardScene.Tile tile,
          float coat) {
        return mesh.vertex(p.x, p.y, p.z, normal.x, normal.y, normal.z, coat, p.x / repeat, -p.y / repeat,
              maskU(tile.coords(), mask.data, p), maskV(tile.coords(), mask.data, p),
              mask.offsetU, mask.offsetV, mask.scaleU, mask.scaleV);
    }

    /** Where the point {@code p} of the hex at {@code at} lies across {@code mask}: 0 at its left edge, 1 at its right. */
    static float maskU(Coords at, MaskData mask, Vector3 p) {
        return ((p.x - BoardGeometry.centerX(at)) / BoardGeometry.hexScale() - mask.x()) / mask.width();
    }

    /** Where the point {@code p} of the hex at {@code at} lies down {@code mask}: 0 at its first row, 1 past its last. */
    static float maskV(Coords at, MaskData mask, Vector3 p) {
        return ((p.y - BoardGeometry.centerY(at)) / BoardGeometry.hexScale() - mask.y()) / mask.height();
    }

    static float coverage(BoardRoad road, Patch patch, float x, float y) {
        Fade fade = patch.fade();
        if (fade == null) { return 1; }
        if (fade.landing()) {
            float t = Math.clamp((-road.endDistance(x, y) - fade.setback()) / fade.end(), 0, 1);
            float metre = BoardRelief.metres(1) / BoardGeometry.hexScale();
            float wx = (x + BoardGeometry.centerX(road.coords()) / BoardGeometry.hexScale()) / metre;
            float wy = (y + BoardGeometry.centerY(road.coords()) / BoardGeometry.hexScale()) / metre;
            float wear = .7f * BoardRelief.noise(wx * 1.3f, wy * 1.3f)
                  + .3f * BoardRelief.noise(wx * 3.7f + 17, wy * 3.7f - 9) - .5f;
            t = Math.clamp(t + wear * .55f * t * (1 - t), 0, 1);
            // The landing fans out, then returns to the bank. The shader resolves actual chips and aggregate
            // from the surface's grain, instead of stretching a few large noise blobs into fingers.
            float fan = .35f + .65f * (float) Math.sin(Math.PI * t);
            float outer = fade.outer() * fan;
            boolean asphalt = patch.texture().equals("roads/asphalt");
            float inner = fade.inner() - (asphalt ? (1 - t) * 4 : 0);
            float edge = Math.clamp((outer - road.distance(x, y)) / (outer - inner), 0, 1);
            // Aggregate remains substantial beyond the last asphalt fragments, then separates into grains.
            if (!asphalt) { t = Math.min(1, t / .6f); }
            float alpha = t * t * (3 - 2 * t) * edge;
            return fade.wheels() ? alpha * road.wheelCoverage(x, y) : alpha;
        }
        float alpha = fade.outer() > fade.inner()
              ? Math.clamp((fade.outer() - road.distance(x, y)) / (fade.outer() - fade.inner()), 0, 1) : 1;
        if (fade.wheels()) { alpha *= road.wheelCoverage(x, y); }
        return fade.end() > 0 ? alpha * road.endCoverage(x, y, fade.end()) : alpha;
    }

    static float materialCoverage(Patch patch, float x, float y) {
        BoardRoad.Join join = patch.fade().join();
        float cover = join.coverage(x, y);
        return patch.texture().equals(texture(join.kind())) ? cover : 1 - cover;
    }

    private static Color color(BoardRoad road, Patch patch, float x, float y, Color tint) {
        tint.set(patch.tint());
        if (patch.blended()) {
            tint.a = coverage(road, patch, x, y);
        }
        if (patch.fade() != null && patch.fade().join() != null) {
            // Transition masks carry along/across coordinates in RG, separately from edge coverage in A.
            // Their shader supplies white material tint. Neither coordinate can taper the road's footprint.
            tint.r = materialCoverage(patch, x, y);
            tint.g = Math.clamp(.5f + patch.fade().join().lateral(x, y) / (2 * BoardRoad.JOIN_REACH), 0, 1);
            tint.b = 0;
        } else if (patch.fade() != null && patch.fade().wheels()) {
            var direction = road.wheelDirection(x, y);
            // Road UVs point down in Y. RG carries the wear direction, not an albedo tint.
            tint.r = Math.round(128 + direction.x * 127) / 255f;
            tint.g = Math.round(128 - direction.y * 127) / 255f;
            tint.b = 0;
        }
        return tint;
    }
}
