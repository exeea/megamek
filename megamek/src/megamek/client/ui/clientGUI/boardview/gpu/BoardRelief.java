/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * Canonical sculpting of dry terrain. Every vertex that two meshes share (hex corners, edge samples, cliff rows) is a
 * pure function of its world position and of the board data around it, so neighbouring tops, their cliffs, picking
 * and unit support agree exactly without exchanging meshes. Game data stays authoritative: a rim sits at its hex's
 * level, a cliff foot at the lower neighbour's level, and the unit anchor in each hex stays level.
 *
 * <p>Corners are identified on the integer corner lattice ({@link #cornerX}), so all three hexes around a corner
 * evaluate the same {@link Corner}. A cliff between two hexes is owned by the higher one; its foot row is also the
 * lower hex's boundary. Walls use rows at fixed absolute heights, so cliffs of different spans meeting on a corner
 * line share every vertex. Visual displacement is horizontal and bounded; it never changes a game elevation.</p>
 */
final class BoardRelief {
    /**
     * Tessellation of a whole board. {@code steps}: samples per open hex edge (edges with a sculpted cliff use twice
     * as many; both adjoining hexes share them). {@code rows}: wall rows inside every level, in level fractions, dense
     * at the foot (talus) and the rim (caprock). {@code rings}/{@code ringSamples}: interior rings between the unit
     * anchor and the rim band, as fractions of the band's local minimum radius, and their samples per edge; they
     * share the boundary's bearings, so no triangle can fold over a broken rim. Every hex of one board uses the same
     * detail, so shared edges still match.
     */
    private record Detail(int steps, float[] rows, float[] rings, int[] ringSamples, boolean rocks) { }

    private static final Detail FULL = new Detail(12,
          new float[] { 0f, .045f, .11f, .20f, .31f, .43f, .55f, .67f, .78f, .87f, .935f, .975f },
          new float[] { .26f, .52f, .78f }, new int[] { 3, 6, 12 }, true);
    /** Large boards (up to 10,000 hexes) keep every feature at about half the vertices. */
    private static final Detail MEDIUM = new Detail(8,
          new float[] { 0f, .07f, .18f, .35f, .55f, .75f, .9f, .97f },
          new float[] { .35f, .7f }, new int[] { 3, 8 }, true);
    /** Very large boards keep their landforms at a fraction of the vertices, and without the rock kit. */
    private static final Detail COARSE = new Detail(4, new float[] { 0f, .15f, .5f, .85f },
          new float[] { .55f }, new int[] { 2 }, false);
    /** Boards up to this many hexes (50 by 50, nine map sheets) are sculpted in full detail. */
    static final int FULL_DETAIL_HEXES = 2500;
    static final int MEDIUM_DETAIL_HEXES = 10_000;
    /** Samples per open edge at full detail. */
    static final int EDGE_STEPS = FULL.steps();
    private static final int[] CORNER_DX = { 2, 1, -1, -2, -1, 1 };
    private static final int[] CORNER_DY = { 0, 1, 1, 0, -1, -1 };
    private static final float EPSILON = .0005f;
    /**
     * How far, in edges, a water hex's bank strips may stitch ahead of their parameter order: a third of an edge,
     * about as far as the steps through a corner slide the boundary's samples along it (12.9 of 42 world units).
     */
    private static final float LEAD = 1 / 3f;
    /**
     * The farthest, in hex-scale units, the water's shore moves a corner (see {@link Corner#move}): far enough for the
     * water to run on past a land hex's corner to where the land's islet turns it, {@link #SHORE_ISLE} from the land's
     * centre, with the room beyond.
     */
    private static final float SHORE_SHIFT = 28;
    /**
     * The room, in hex-scale units, a corner the shore moves keeps beyond the water where land lies at the water's
     * level: BoardSurface's bank of 4.5, a unit, and most of the reach of the bank's rounding, so the shore runs on
     * past the corner as its field lies instead of bending short of it.
     */
    private static final float SHORE_ROOM = 8;
    /**
     * Reach, in hex-scale units, of each hex centre's pull on the shore field (see {@link #shore}). The pull falls off
     * as (1 - d^2 / r^2)^3, about as a Gaussian 40 units wide does: the shore runs smooth over about a hex, so a bank
     * along a row of hexes runs straight, a river bends round its turns and a lake is one rounded body of water.
     */
    private static final float SHORE_REACH = 128;
    /**
     * How much harder a hex pulls for each of its six neighbours of the other kind, water or land: a river a hex wide
     * keeps its width and a spit of land its tip, where a plain sum would narrow both toward their middles.
     */
    private static final float SHORE_NARROW = .75f;
    /** The pull of land whose corners stay put (paving, special artwork, roads): it keeps the shore back from them. */
    private static final float SHORE_HARD = 2.5f;
    /**
     * Radii, in hex-scale units, of the water every water hex keeps round its centre and of the land every land hex
     * keeps round its own, blended in over {@link #SHORE_BLEND}: a lone water hex is a round pond, a lone land hex in a
     * lake an islet, and a unit always stands in its own hex's water or on its own hex's land.
     */
    private static final float SHORE_POOL = 28;
    private static final float SHORE_ISLE = 22;
    private static final float SHORE_BLEND = 10;
    /**
     * How far, in hex-scale units, the bank wanders to either side of the field's own line, and the noise cell it
     * wanders over: bends one and a half to three hexes long, as on the printed maps, so a long bank curves gently
     * instead of running like a ruler.
     */
    private static final float SHORE_WANDER = 8;
    private static final float WANDER_CELL = 140;
    /**
     * How far, in hex-scale units, every bank stands out past the shore field's own line into the land: how fat the
     * water is. A river a hex wide grows by twice this and a lake's shore moves out by it; negative slims both.
     */
    static final float SHORE_SPREAD = -3;
    /**
     * The share of itself every land hex keeps however far the water's shore would run into it: the shore moves the
     * land's corners no further than leaves it that much (see {@link #keep}).
     */
    static final float LAND_KEEP = .75f;
    /** Hexes on each side of this one whose pulls on the water's shore field are kept (see {@link #shoreWeight}). */
    private static final int SHORE_WINDOW = 6;
    /** Width, in hex-scale units, of a water hex's bank from its level lip down to the water. */
    private static final float SHORE_LIP = 5;

    /**
     * Shape of one exposed geology, in metres: jointed rock masses (cell size and relief), the dark fractures between
     * them, bedding, broad buttresses, how far the face has retreated behind the hex edge, and the relief of the
     * ground above. {@code cap} and {@code talus} scale the caprock lip and the talus apron. {@code round} is the
     * fillet of every cliff corner as a fraction of the hex edge. Steps of up to two levels are banks of the soil
     * mantle: they keep {@code bank} of the rock's jointing and lean back by {@code lean} metres per metre of depth.
     * {@code cast} is 1 for poured concrete: flat faces, and from three levels a slab one level thick on
     * {@link #BEDROCK}. {@code stones} and {@code shrubs} are average loose ground-cover counts per hex.
     */
    record Geology(float cellWidth, float cellHeight, float cells, float fractures, float strata, float bedding,
          float buttress, float recess, float relief, float cap, float talus, float round, float bank, float lean,
          float cast, float stones, float shrubs) {
        Geology plus(Geology o) {
            return new Geology(cellWidth + o.cellWidth, cellHeight + o.cellHeight, cells + o.cells,
                  fractures + o.fractures, strata + o.strata, bedding + o.bedding, buttress + o.buttress,
                  recess + o.recess, relief + o.relief, cap + o.cap, talus + o.talus, round + o.round,
                  bank + o.bank, lean + o.lean, cast + o.cast, stones + o.stones, shrubs + o.shrubs);
        }

        Geology scale(float f) {
            return new Geology(cellWidth * f, cellHeight * f, cells * f, fractures * f, strata * f, bedding * f,
                  buttress * f, recess * f, relief * f, cap * f, talus * f, round * f, bank * f, lean * f, cast * f,
                  stones * f, shrubs * f);
        }
    }

    /** Indexed by {@link BoardScene.Surface#ordinal()}. */
    private static final Geology[] GEOLOGY = {
          // GRASS: earth banks on low steps, blocky granite from three levels.
          new Geology(4.2f, 3.6f, 1.15f, .70f, .40f, 3.4f, 1.10f, 1.0f, .85f, 1, 1, .5f, .3f, .42f, 0, .8f, .6f),
          // DIRT: soft, gullied earth.
          new Geology(2.6f, 9.0f, .45f, .75f, .25f, 1.8f, .80f, .8f, .45f, .6f, 1.1f, .5f, .55f, .38f, 0, 1.2f, 1.4f),
          // SAND: sandstone mesas of jointed columns: tall masses split by deep vertical joints, faint bedding.
          new Geology(5.0f, 14f, 1.8f, 2.2f, .3f, 3.6f, .8f, 1.2f, .95f, 1, 1, .45f, 1, .06f, 0, 1, 3),
          // ROCK: jointed bedrock.
          new Geology(3.6f, 4.2f, 1.25f, .75f, .45f, 2.9f, 1.25f, 1.1f, .75f, 1, 1, .42f, 1, .12f, 0, 2.5f, .4f),
          // CONCRETE: flat cast slabs with crisp arrises; from three levels one slab caps the bedrock below.
          new Geology(6.0f, 6.0f, 0, 0, 0, 3.0f, 0, 0, 0, 0, 0, .05f, 1, 0, 1, 0, 0),
          // SNOW: rock under a snow mantle that buries the low steps.
          new Geology(4.0f, 3.8f, 1.0f, .60f, .40f, 3.2f, 1.05f, 1.0f, .85f, 1, 1, .5f, .5f, .3f, 0, 1, 0),
    };

    /** The rock that carries a concrete slab: jointed bedrock whose top is the slab's underside, so without caprock. */
    private static final Geology BEDROCK = new Geology(3.6f, 4.2f, 1.25f, .75f, .45f, 2.9f, 1.25f, 1.1f, .75f, 0, 1,
          .42f, 1, .12f, 0, 0, 0);

    /**
     * Metres of room that a step between natural grounds takes on each side of its edge when the tuning turns hex
     * transitions on: up to two levels a slope from the upper hex's ground down into the lower one, from three levels a
     * cliff standing back from the edge above a talus that spreads past it.
     */
    static final float TRANSITION = 4;

    /** Visual landform controls. Fixed lattice, stitching and sampling rules remain shared by every hex. */
    record Tuning(float shoreShift, float shoreRoom, float shoreReach, float shoreNarrow, float shoreHard,
          float shorePool, float shoreIsle, float shoreBlend, float shoreWander, float wanderCell,
          float shoreSpread, float landKeep, float shoreLip, float transition, int fullDetailHexes,
          int mediumDetailHexes, float riverWidth) {
        Tuning {
            for (float value : new float[] { shoreShift, shoreRoom, shoreReach, shoreNarrow, shoreHard,
                  shorePool, shoreIsle, shoreBlend, shoreWander, wanderCell, landKeep, shoreLip, transition, riverWidth }) {
                if (!Float.isFinite(value) || value < 0) { throw new IllegalArgumentException("Invalid terrain tuning"); }
            }
            // The fixed shore neighbourhood covers this reach and corner displacement.
            if (shoreShift > SHORE_SHIFT || shoreReach < 64 || shoreReach > SHORE_REACH || shoreHard < 1
                  || shorePool <= 0 || shoreIsle <= 0 || shoreBlend <= 0 || wanderCell <= 0 || shoreLip <= 0
                  || !Float.isFinite(shoreSpread) || landKeep > 1 || transition > TRANSITION
                  || fullDetailHexes < 1 || mediumDetailHexes < fullDetailHexes || riverWidth < .05f || riverWidth > 1) {
                throw new IllegalArgumentException("Invalid terrain tuning");
            }
        }
    }

    static final Tuning DEFAULTS = new Tuning(SHORE_SHIFT, SHORE_ROOM, SHORE_REACH, SHORE_NARROW, SHORE_HARD,
          SHORE_POOL, SHORE_ISLE, SHORE_BLEND, SHORE_WANDER, WANDER_CELL, SHORE_SPREAD, LAND_KEEP, SHORE_LIP,
          TRANSITION, FULL_DETAIL_HEXES, MEDIUM_DETAIL_HEXES, 1);
    private static Tuning tuning = DEFAULTS;
    private static final List<Geology> DEFAULT_GEOLOGY = List.of(GEOLOGY[0], GEOLOGY[1], GEOLOGY[2], GEOLOGY[3],
          GEOLOGY[4], GEOLOGY[5], BEDROCK);
    private static List<Geology> geology = DEFAULT_GEOLOGY;

    static Tuning tuning() { return tuning; }

    static void tune(Tuning next) {
        if (next.equals(tuning)) { return; }
        tuning = next;
        BoardGeometry.terrainChanged();
    }

    /** The six surface families followed by the bedrock beneath concrete. Records and returned lists are immutable. */
    static List<Geology> geology() { return geology; }

    static List<Geology> defaultGeology() { return DEFAULT_GEOLOGY; }

    static void tuneGeology(List<Geology> next) {
        next = List.copyOf(next);
        if (next.size() != DEFAULT_GEOLOGY.size()) { throw new IllegalArgumentException("Missing terrain family"); }
        for (Geology g : next) {
            for (float value : new float[] { g.cellWidth(), g.cellHeight(), g.cells(), g.fractures(), g.strata(),
                  g.bedding(), g.buttress(), g.recess(), g.relief(), g.cap(), g.talus(), g.round(), g.bank(),
                  g.lean(), g.cast(), g.stones(), g.shrubs() }) {
                if (!Float.isFinite(value) || value < 0) { throw new IllegalArgumentException("Invalid geology"); }
            }
            if (g.cellWidth() == 0 || g.cellHeight() == 0 || g.bedding() == 0 || g.round() > .5f
                  || g.bank() > 1 || g.lean() > 1 || g.cast() > 1) {
                throw new IllegalArgumentException("Invalid geology");
            }
        }
        if (next.equals(geology)) { return; }
        geology = next;
        BoardGeometry.terrainChanged();
    }

    /**
     * World units of room a step takes on each side of its edge: the fixed {@link #TRANSITION} of hex transitions, or
     * with padding half the gap the padding opens between the hexes, so each hex keeps its own flat top and the gap
     * holds the step; zero when neither is on.
     */
    static float stepRoom() {
        BoardGeometry.Tuning geometry = BoardGeometry.tuning();
        return geometry.padding() > 0 ? metres(geometry.padding() / 2) : geometry.transitions() ? metres(tuning.transition()) : 0;
    }
    private static final int CONCRETE = BoardScene.Surface.CONCRETE.ordinal();
    private static final int SAND = BoardScene.Surface.SAND.ordinal();

    /** Surface kinds written into the rendered vertex data. */
    enum Kind { GROUND, PLANT, CLIFF, PIT, ROCK }

    /**
     * Per-vertex presentation data. Ground: {@code level} is the owning game level and rim/foot are distances in
     * metres to the nearest drop and rise. Cliff: {@code level} is how much of a rock cliff the face is, from 0 (a bank
     * of up to two levels) to 1 (three levels or more), blended across corners where steps of different heights meet;
     * rim/foot are the height above the foot and depth below the rim, in metres. Rock: rim/foot are the height above
     * its root and below its top. {@code tint} in [0, 1] is the hardness of the bed a cliff vertex lies in (the same
     * beds its ledges follow), one rock's own variation, or for ground the height of the nearest step. A tree pit's
     * earth has its variation below one half and its kerb stone one.
     */
    record Shade(Vector3 normal, Kind kind, float occlusion, float level, float rim, float foot, float tint) { }

    /**
     * One hex as the sculpt sees it. A water hex's family is the ground of the land around it (see
     * {@link #waterFamily}).
     */
    record Site(Coords coords, int level, boolean sculpted, boolean detailed, int family, boolean liquid, int depth,
          int ix, int iy) {
        float x() { return cornerX(ix); }

        float y() { return cornerY(iy); }
    }

    private final BoardScene scene;
    private final BoardScene.Tile tile;
    private final Detail detail;
    private final Site self;
    private final BoardRiver river;
    private final boolean sculpted;
    private final Map<Coords, Site> sites = new HashMap<>();
    private final Map<Long, Corner> corners = new HashMap<>();
    private final Edge[] edges = new Edge[6];
    private final Map<Vector3, Shade> shades = new IdentityHashMap<>();
    /** This hex's rim samples per edge, including the closing corner; cliffs reuse them as their top row. */
    private final Vector3[][] rims = new Vector3[6][];
    /** The outline of this hex's sculpted top, counter-clockwise; null until the top is built. */
    private List<Vector3> outline;
    /** A water hex's waterline, counter-clockwise and {@link BoardSurface#SHORE_SEGMENTS} per edge; null on land. */
    private Vector3[] waterline;
    /** The edges a water hex falls over, as a bit mask by edge. */
    private int falls;
    private List<Site> candidates;
    /** The relief amplitude when all joined hexes around share one family; NaN until computed, negative if mixed. */
    private float uniformRelief = Float.NaN;
    private float[] seams;
    /** Pulls on the water's shore field of the hexes within {@link #SHORE_WINDOW} of this one, NaN until computed. */
    private float[] shoreWeights;
    /** Shares of the shore's moves the land hexes allow, by coordinates; see {@link #keep}. */
    private final Map<Coords, Float> keeps = new HashMap<>();
    private float[] drops;
    private float[] rises;
    private final float[] scratchA = new float[2];
    private final float[] scratchB = new float[2];
    private final float[] bandA = new float[2];
    private final float[] bandB = new float[2];

    BoardRelief(BoardScene scene, BoardScene.Tile tile, int ramps) {
        this.scene = scene;
        this.tile = tile;
        river = new BoardRiver(scene, tuning);
        int hexes = scene.width() * scene.height();
        detail = hexes <= tuning.fullDetailHexes() ? FULL : hexes <= tuning.mediumDetailHexes() ? MEDIUM : COARSE;
        self = site(scene, tile, ramps);
        sculpted = self.sculpted();
        sites.put(tile.coords(), self);
    }

    /**
     * Natural ground and water are sculpted; road hexes, their ramps and molten lava keep their flat tops. A water hex
     * ignores roads, as its surface does.
     */
    private static Site site(BoardScene scene, BoardScene.Tile tile, int ramps) {
        boolean liquid = tile.liquid().present();
        boolean shaped = !tile.liquid().molten() && (liquid || ramps == 0 && tile.roadExits() == 0);
        BoardScene.Surface family = liquid ? waterFamily(scene, tile) : tile.surface();
        return new Site(tile.coords(), tile.elevation(), shaped, shaped && tile.detailedGround(), family.ordinal(),
              liquid, liquid ? Math.max(0, tile.waterDepth()) : 0, centerIx(tile.coords()), centerIy(tile.coords()));
    }

    /**
     * The ground a water hex's banks, bed and walls take: the commonest of its dry, natural neighbours' (the first of
     * them on a tie), so a river on a desert map runs between sand, not the meadow a plain water hex would default to;
     * its own where no such neighbour exists.
     */
    private static BoardScene.Surface waterFamily(BoardScene scene, BoardScene.Tile tile) {
        int[] counts = new int[BoardScene.Surface.values().length];
        BoardScene.Surface best = null;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile land = scene.tile(tile.coords().translated(direction));
            // Buildings and roads can keep authored artwork while still standing on a concrete slab. Their banks
            // continue that material without changing the original land or its foundations.
            if (land == null || land.liquid().present()
                  || !land.detailedGround() && land.surface() != BoardScene.Surface.CONCRETE) { continue; }
            int count = ++counts[land.surface().ordinal()];
            if (best == null || count > counts[best.ordinal()]) { best = land.surface(); }
        }
        return best == null ? tile.surface() : best;
    }

    // ---- Units and lattice ---------------------------------------------------------------------------------

    /** The point-to-point hex footprint is 30 metres; elevation remains the user's board tuning. */
    static float metres(float value) { return value * BoardGeometry.WIDTH / 30; }

    /** Enlarged art scale kept for legacy material repeats; game distances still use {@link #metres}. */
    static float detailMetres(float value) { return value * BoardGeometry.WIDTH / 5; }

    static int centerIx(Coords coords) { return 3 * coords.getX() + 2; }

    static int centerIy(Coords coords) { return -(2 * coords.getY() + (coords.getX() & 1) + 1); }

    static float cornerX(int ix) { return ix * (BoardGeometry.WIDTH / 4); }

    static float cornerY(int iy) { return iy * (BoardGeometry.HEIGHT / 2); }

    /** Largest height any sculpted vertex reaches above its hex level: relief crowns and rim rocks. */
    static float headroom(BoardScene.Tile tile) {
        return tile.detailedGround() && !tile.liquid().molten() && tile.surface() != BoardScene.Surface.CONCRETE
              ? BoardGeometry.WIDTH * .12f : BoardGeometry.WIDTH * .02f;
    }

    /** Largest height a tile's own ground decoration reaches above its level: relief crowns, rim rocks and scatter. */
    static float decoration(BoardScene.Tile tile) {
        float scatter = 0;
        for (BoardScene.Feature feature : tile.features()) {
            if (feature.kind() == BoardScene.FeatureKind.SCATTER) {
                scatter = Math.max(scatter, feature.height() * BoardGeometry.LEVEL);
            }
        }
        return headroom(tile) + scatter;
    }

    /** Largest horizontal reach of sculpted geometry beyond a hex's own footprint. */
    static float overhang() {
        return BoardGeometry.WIDTH * .18f + stepRoom();
    }

    boolean sculpted() { return sculpted; }

    /**
     * How far the step on edge e reaches into this hex at its own level, in world units: the foot of a slope down to it
     * or the rim of a slope down from it, laid out in the step's room. A slope down to a water hex's bed meets the water
     * on the edge. A water hex keeps its waterline beyond it.
     */
    float reach(int e) {
        Edge edge = edge(e);
        if (edge.room <= 0) { return 0; }
        float z = self.level() * BoardGeometry.LEVEL;
        return Math.max(0, edge.lower == self ? band(edge.upper, edge.lower, z) : -band(edge.upper, edge.lower, z));
    }

    /**
     * Whether the step on edge e is a slope with room up from this hex, not a wall: in a water hex it runs on under the
     * water to the bed, and the waterline hugs it.
     */
    boolean slope(int e) {
        Edge edge = edge(e);
        return edge.room > 0 && edge.lower == self && !wall(edge.upper, edge.lower);
    }

    /**
     * How far the steps through corner k move its line at this hex's level, in world units. Water hexes that share an
     * open mouth both compute it from the corner's three hexes, so they place the mouth's ends alike.
     */
    float cornerReach(int k) {
        float[] band = new float[2];
        bandOffset(corner(self, k), self.level() * BoardGeometry.LEVEL, band);
        return (float) Math.hypot(band[0], band[1]);
    }

    /**
     * How far this hex's outline at its own level stands in from corner k toward the hex's centre, in world units: the
     * steps through the corner and its fillet move it. A water hex keeps its waterline beyond it.
     */
    float cornerInset(int k) {
        Corner corner = corner(self, k);
        float z = self.level() * BoardGeometry.LEVEL;
        float[] band = new float[2], fillet = new float[2];
        bandOffset(corner, z, band);
        filletOffset(corner, z, fillet);
        float ix = self.x() - corner.x, iy = self.y() - corner.y, length = (float) Math.hypot(ix, iy);
        return Math.max(0, ((band[0] + fillet[0]) * ix + (band[1] + fillet[1]) * iy) / length);
    }

    /**
     * Whether a hex is land of natural ground, whose corners the water's shore may move (see {@link Corner#move});
     * paving, special artwork and roads keep their corners.
     */
    private static boolean shoreGround(Site site) {
        return !site.liquid() && site.detailed() && site.family() != CONCRETE;
    }

    // ---- Shore field -----------------------------------------------------------------------------------------

    /**
     * The shore field at (x, y) of water, or of lava when {@code molten}, in world units: roughly how far the point
     * lies inside that liquid's banks, negative on land. Each hex centre within {@link #SHORE_REACH} pulls the field by
     * its {@link #shoreWeight}, the sum divided by its slope measures distance, the bank wanders a little, and every
     * hex keeps its own pond or islet round its centre. The field is the board's, whichever hex asks: the hexes that
     * reach a point follow from the point alone and are summed in the order of their coordinates, so every hex
     * evaluating a point gets the same value.
     */
    float shore(float x, float y, boolean molten) {
        float value = shoreBase(x, y, molten);
        if (molten) { return value; }
        float channel = river.field(x, y);
        if (channel == Float.NEGATIVE_INFINITY) { return value; }
        return Math.min(value, channel);
    }

    private float shoreBase(float x, float y, boolean molten) {
        float scale = BoardGeometry.HEX_SCALE, r2 = square(tuning.shoreReach() * scale), f = 0, gx = 0, gy = 0;
        float wet = Float.POSITIVE_INFINITY, dry = Float.POSITIVE_INFINITY;
        float width = BoardGeometry.WIDTH, height = BoardGeometry.HEIGHT, step = .75f * width;
        int column = Math.round((x - width / 2) / step);
        for (int cx = column - 2; cx <= column + 2; cx++) {
            float dx = x - (cx * step + width / 2);
            int row = Math.round((-y - height / 2 - (cx & 1) * height / 2) / height);
            for (int cy = row - 2; cy <= row + 2; cy++) {
                float dy = y + cy * height + (cx & 1) * height / 2 + height / 2, d2 = dx * dx + dy * dy;
                float weight = shoreWeight(cx, cy, molten);
                if (weight > 0) {
                    wet = Math.min(wet, d2);
                } else {
                    dry = Math.min(dry, d2);
                }
                float t = 1 - d2 / r2;
                if (t <= 0) { continue; }
                f += weight * t * t * t;
                gx += weight * t * t * dx;
                gy += weight * t * t * dy;
            }
        }
        float slope = 6 / r2 * (float) Math.hypot(gx, gy);
        float wander = Math.clamp(gradient(x / (tuning.wanderCell() * scale) + 3.1f, y / (tuning.wanderCell() * scale) - 1.7f), -1, 1);
        float value = f / Math.max(slope, 1e-4f / scale) + (tuning.shoreSpread() + tuning.shoreWander() * wander) * scale;
        value = -smoothMin(-value, (float) Math.sqrt(wet) - tuning.shorePool() * scale, tuning.shoreBlend() * scale);
        return smoothMin(value, (float) Math.sqrt(dry) - tuning.shoreIsle() * scale, tuning.shoreBlend() * scale);
    }

    /**
     * How far from (x, y) along (ux, uy) the water's shore field turns to land, in world units, negative where it
     * turns before the point: within {@link #SHORE_SHIFT} either way, where even steps find the first dry one and
     * halvings the turn within it.
     */
    private float shoreReach(float x, float y, float ux, float uy) {
        // Narrowing a stream changes the beach inside its existing banks, not the cliff and land topology.
        float span = tuning.shoreShift() * BoardGeometry.HEX_SCALE, lo = -span, hi = Float.NaN;
        if (shoreBase(x + ux * lo, y + uy * lo, false) <= 0) { return lo; }
        for (int i = 1; i <= 8 && Float.isNaN(hi); i++) {
            float s = -span + 2 * span * i / 8;
            if (shoreBase(x + ux * s, y + uy * s, false) <= 0) {
                hi = s;
            } else {
                lo = s;
            }
        }
        if (Float.isNaN(hi)) { return span; }
        for (int i = 0; i < 12; i++) {
            float s = (lo + hi) / 2;
            if (shoreBase(x + ux * s, y + uy * s, false) > 0) {
                lo = s;
            } else {
                hi = s;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * The pull of the hex at column cx and row cy on the shore field of water, or of lava when {@code molten}: toward
     * that liquid for its own hexes, away from it for land, {@link #SHORE_HARD} times as hard for land whose corners
     * stay put, and harder by {@link #SHORE_NARROW} the more of its neighbours are of the other kind. Beyond the
     * board's edge a hex pulls as the nearest board hex does, so a river runs straight on into the edge.
     */
    private float shoreWeight(int cx, int cy, boolean molten) {
        int size = 2 * SHORE_WINDOW + 1;
        int ix = cx - tile.coords().getX() + SHORE_WINDOW, iy = cy - tile.coords().getY() + SHORE_WINDOW;
        int index = !molten && ix >= 0 && iy >= 0 && ix < size && iy < size ? ix * size + iy : -1;
        if (index >= 0 && shoreWeights == null) {
            shoreWeights = new float[size * size];
            Arrays.fill(shoreWeights, Float.NaN);
        }
        if (index >= 0 && !Float.isNaN(shoreWeights[index])) { return shoreWeights[index]; }
        Coords coords = new Coords(cx, cy);
        Site site = boardSite(coords);
        boolean wet = wet(site, molten);
        int other = 0;
        for (int direction = 0; direction < 6; direction++) {
            if (wet(boardSite(coords.translated(direction)), molten) != wet) { other++; }
        }
        float pull = wet ? 1 : site.sculpted() && shoreGround(site) ? -1 : -tuning.shoreHard();
        // A broad body of water fills out its banks; a one-hex stream keeps its existing pull. Interior water
        // contributes gradually through the same shared field, so the shore bows across several hexes.
        if (wet && !molten) { pull += Math.max(0, 3 - other) / 3f; }
        float weight = pull * (1 + tuning.shoreNarrow() * other / 6);
        if (index >= 0) { shoreWeights[index] = weight; }
        return weight;
    }

    /**
     * How much of the moves the water's shore wants of a land hex's corners the hex allows, from 0 to 1: all of them,
     * unless together they would take more than 1 - {@link #LAND_KEEP} of the hex, when each is cut back alike. A unit
     * of move toward the hex's centre takes about half the hex's height of its area, one along the edge between it and
     * another land hex half that.
     */
    private float keep(Site land) {
        Float known = keeps.get(land.coords());
        if (known != null) { return known; }
        float loss = 0, height = BoardGeometry.HEIGHT;
        for (int k = 0; k < 6; k++) {
            Corner corner = corner(land, k);
            if (corner.want <= 0) { continue; }
            int lands = 0;
            for (Site site : corner.around) { lands += site.liquid() ? 0 : 1; }
            loss += corner.want * (lands == 1 ? height / 2 : height / 4);
        }
        float budget = (1 - tuning.landKeep()) * .75f * BoardGeometry.WIDTH * height;
        float result = loss > budget ? budget / loss : 1;
        keeps.put(land.coords(), result);
        return result;
    }

    /** The site at these coordinates, or beyond the board's edge the nearest board hex's. */
    private Site boardSite(Coords coords) {
        return site(new Coords(Math.clamp(coords.getX(), 0, scene.width() - 1),
              Math.clamp(coords.getY(), 0, scene.height() - 1)));
    }

    /** Whether a hex holds water, or lava when {@code molten}: molten lava is the one liquid the sculpt leaves flat. */
    private static boolean wet(Site site, boolean molten) {
        return site.liquid() && site.sculpted() != molten;
    }

    /**
     * The seam of edge e at this hex's level, the fraction t along it from corner k, one of its ends: the outline the
     * relief lays there, which both hexes of the edge share.
     */
    Vector3 seam(int e, int k, float t) {
        Edge edge = edge(e);
        return edgePoint(edge, edge.a == corner(self, k) ? t : 1 - t, self.level() * BoardGeometry.LEVEL);
    }

    /** How far the shore moves this hex's corner k, in world units (x, y): see {@link Corner#move}. */
    float[] shoreShift(int k) {
        return corner(self, k).move().clone();
    }

    /** The surface family this hex's sculpted ground takes, as a {@link BoardScene.Surface} ordinal. */
    int family() { return self.family(); }

    Shade shade(Vector3 vertex) { return shades.get(vertex); }

    // ---- Board context ---------------------------------------------------------------------------------------

    private Site site(Coords coords) {
        if (coords == null) { return null; }
        Site known = sites.get(coords);
        if (known != null || sites.containsKey(coords)) { return known; }
        BoardScene.Tile other = scene.tile(coords);
        Site result = other == null ? null : site(scene, other, BoardSurface.ramps(scene, other));
        sites.put(coords, result);
        return result;
    }

    private Site neighbor(Site site, int edge) {
        if (site == null) { return null; }
        return site(site.coords().translated(BoardGeometry.edgeDirection(edge)));
    }

    /**
     * Hexes whose surfaces continue into each other without a cliff, bank or special artwork seam. A water hex's banks
     * are its own, so it joins no neighbour.
     */
    private static boolean joined(Site a, Site b) {
        return a != null && b != null && a.detailed() && b.detailed() && a.level() == b.level() && !a.liquid()
              && !b.liquid();
    }

    private List<Site> candidates() {
        if (candidates == null) {
            candidates = new ArrayList<>(7);
            candidates.add(self);
            for (int e = 0; e < 6; e++) {
                Site site = neighbor(self, e);
                if (site != null) { candidates.add(site); }
            }
        }
        return candidates;
    }

    // ---- Canonical corners -----------------------------------------------------------------------------------

    /** The three hexes around one lattice corner, in canonical order; every hex that shares it derives the same. */
    private final class Corner {
        final long key;
        final float x;
        final float y;
        final Site[] around;
        final boolean pinned;
        final int low;
        final int mid;
        final int high;
        final float variation;
        final Map<Integer, float[]> offsets = new HashMap<>();
        /** Transition band offsets per height; see {@link #bandOffset(Corner, float)}. */
        final Map<Integer, float[]> bands = new HashMap<>();
        /**
         * Whether hex transitions or padding are on and all three hexes are natural ground, so the steps through this
         * corner take transition room and its relief leaves the apron to the band.
         */
        final boolean banded;
        /** Length of the corner's fillet along each edge, in world units; zero where the corner stays sharp. */
        final float fillet;
        /**
         * Fillet displacements (a sixth of the fillet length) toward the hex on the other side of the outline: the
         * lone high hex of a convex corner or the lone low hex of a concave one ({@code odd}); on a three-level
         * corner toward the high, middle and low hex for the upper span, the middle hex's own level and the lower span.
         */
        final float[] odd = new float[2];
        final float[] towardHigh = new float[2];
        final float[] towardMid = new float[2];
        final float[] towardLow = new float[2];
        /**
         * Where water meets land of natural ground at or above its level, the way away from the water, as a unit
         * vector: toward the land's centre between two water hexes, out along the edge between two land hexes past a
         * water hex's corner. Zero elsewhere.
         */
        private final float[] away = new float[2];
        /**
         * How far along {@link #away} the water's shore would move the corner, in world units, at every height of the
         * steps there, so the slopes and walls round the water follow its shore too: to where the shore field turns to
         * land, with room for the water's bank beyond where land lies at the water's level, at most
         * {@link #SHORE_SHIFT} either way, and at one level never toward the water. A step up from the water keeps its
         * own room: its foot reaches on into the water, which follows it there.
         */
        private float want;
        /** The move itself, once every land hex round the corner has kept its share; see {@link #move}. */
        private float[] move;

        Corner(int ix, int iy, Site[] around) {
            key = ((long) ix << 32) ^ (iy & 0xffffffffL);
            x = cornerX(ix);
            y = cornerY(iy);
            this.around = around;
            variation = hash(ix * 7 + 3, iy * 13 - 5);
            boolean anyPinned = false;
            for (Site site : around) { anyPinned |= site == null || !site.sculpted(); }
            // Where two water hexes step down to each other, the straight walls under a fall meet the corner: it keeps
            // its place, as it does beside paving, special artwork and roads.
            for (int i = 0; i < 3; i++) {
                Site p = around[i], q = around[(i + 1) % 3];
                anyPinned |= p != null && q != null && p.liquid() && q.liquid() && p.level() != q.level();
            }
            pinned = anyPinned;
            if (pinned) {
                low = mid = high = 0;
                fillet = 0;
                banded = false;
                return;
            }
            boolean natural = BoardGeometry.tuning().stepsBetweenTops();
            for (Site site : around) { natural &= site.detailed() && site.family() != CONCRETE; }
            banded = natural;
            int a = around[0].level(), b = around[1].level(), c = around[2].level();
            low = Math.min(a, Math.min(b, c));
            high = Math.max(a, Math.max(b, c));
            mid = a + b + c - low - high;
            float round = 0;
            for (Site site : around) { round += BoardRelief.geology.get(site.family()).round(); }
            fillet = low == high ? 0 : Math.min(.5f, round / 3 * (.8f + .4f * variation)) * BoardGeometry.WIDTH / 2;
            for (Site site : around) {
                float dx = site.x() - x, dy = site.y() - y, scale = fillet / 6 / (float) Math.hypot(dx, dy);
                float[] target = site.level() == high && mid != high ? towardHigh
                      : site.level() == low && mid != low ? towardLow : towardMid;
                target[0] = dx * scale;
                target[1] = dy * scale;
            }
            float[] lone = mid == low ? towardHigh : towardLow;
            odd[0] = lone[0];
            odd[1] = lone[1];
            wantShore();
        }

        /** Sets {@link #away} and {@link #want}. */
        private void wantShore() {
            Site water = null, dry = null;
            int wet = 0;
            boolean flat = false;
            for (Site site : around) {
                if (site.liquid()) {
                    wet++;
                    water = site;
                } else {
                    dry = site;
                }
            }
            if (wet == 0 || wet == 3) { return; }
            for (Site site : around) {
                if (site.liquid()) { continue; }
                if (!shoreGround(site) || site.level() < water.level()) { return; }
                flat |= site.level() == water.level();
            }
            float dx = wet == 2 ? dry.x() - x : x - water.x(), dy = wet == 2 ? dry.y() - y : y - water.y();
            float length = (float) Math.hypot(dx, dy), span = tuning.shoreShift() * BoardGeometry.HEX_SCALE;
            away[0] = dx / length;
            away[1] = dy / length;
            float reach = shoreReach(x, y, away[0], away[1]) + (flat ? tuning.shoreRoom() * BoardGeometry.HEX_SCALE : 0);
            float most = span;
            if (wet == 2) {
                // A land's tip moves no nearer its centre than leaves its top, inside the steps' rim, its islet.
                float z = dry.level() * BoardGeometry.LEVEL;
                float[] band = new float[2], rounding = new float[2];
                bandOffset(this, z, band);
                filletOffset(this, z, rounding);
                float rim = (band[0] + rounding[0]) * away[0] + (band[1] + rounding[1]) * away[1];
                most = Math.clamp(length - rim - tuning.shoreIsle() * BoardGeometry.HEX_SCALE, 0, span);
            }
            want = Math.clamp(reach, low == high ? 0 : -span, most);
        }

        /**
         * How far the shore moves this corner, in world units (x, y): {@link #want} along {@link #away}, but into land
         * no further than every land hex round it allows (see {@link #keep}).
         */
        float[] move() {
            if (move == null) {
                float length = want;
                for (Site site : around) {
                    if (length > 0 && !site.liquid()) { length = Math.min(length, want * keep(site)); }
                }
                move = new float[] { away[0] * length, away[1] * length };
            }
            return move;
        }

        boolean seamless() {
            return !pinned && BoardRelief.joined(around[0], around[1]) && BoardRelief.joined(around[1], around[2])
                  && BoardRelief.joined(around[0], around[2]);
        }

        /** The cliff span containing z: [from, to] levels, with a pin at an intermediate level of a three-way corner. */
        int[] span(float z) {
            float level = BoardGeometry.LEVEL;
            if (mid == low || mid == high) { return new int[] { low, high, 0 }; }
            return z >= mid * level ? new int[] { mid, high, 1 } : new int[] { low, mid, 2 };
        }
    }

    private Corner corner(Site site, int index) {
        int k = Math.floorMod(index, 6);
        int ix = site.ix() + CORNER_DX[k], iy = site.iy() + CORNER_DY[k];
        long key = ((long) ix << 32) ^ (iy & 0xffffffffL);
        Corner existing = corners.get(key);
        if (existing != null) { return existing; }
        Site[] around = { site, neighbor(site, k - 1), neighbor(site, k) };
        Arrays.sort(around, (p, q) -> p == null ? (q == null ? 0 : 1) : q == null ? -1
              : p.coords().getX() != q.coords().getX() ? Integer.compare(p.coords().getX(), q.coords().getX())
              : Integer.compare(p.coords().getY(), q.coords().getY()));
        Corner result = new Corner(ix, iy, around);
        corners.put(key, result);
        return result;
    }

    /** Relief displacement of the vertical line through a corner, at height z (cached per height). */
    private void cornerOffset(Corner corner, float z, float[] out) {
        if (corner.pinned || corner.low == corner.high) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        float[] cached = corner.offsets.computeIfAbsent(Float.floatToIntBits(z), key -> computeCornerOffset(corner, z));
        out[0] = cached[0];
        out[1] = cached[1];
    }

    private float[] computeCornerOffset(Corner corner, float z) {
        float level = BoardGeometry.LEVEL;
        int[] span = corner.span(z);
        int from = span[0], to = span[1];
        float bottom = from * level, top = to * level;
        if (z < bottom - EPSILON || z > top + EPSILON) { return new float[2]; }
        float[] direction = cornerDirection(corner, z);
        if (direction == null) { return new float[2]; }
        Geology geology = null;
        int solids = 0;
        for (Site site : corner.around) {
            if (site.level() >= to) {
                solids++;
                geology = geology == null ? BoardRelief.geology.get(site.family()) : geology.plus(BoardRelief.geology.get(site.family()));
            }
        }
        geology = geology.scale(1f / solids);
        float pin = (span[2] == 1 ? smooth((z - bottom) / (.2f * level)) : 1)
              * (span[2] == 2 ? smooth((top - z) / (.2f * level)) : 1);
        // Water keeps its outline at its own level, as along the edges (see Edge).
        boolean footPinned = false, rimPinned = false;
        float drop = 0;
        for (Site site : corner.around) {
            footPinned |= site.liquid() && site.level() == from;
            rimPinned |= site.liquid() && site.level() == to;
            for (Site other : corner.around) {
                if (site.level() == to && other.level() == from) { drop = Math.max(drop, drop(site, other)); }
            }
        }
        // Where the band moves the corner line, its relief is that of the place it stands.
        float[] band = new float[2];
        bandOffset(corner, z, band);
        float d = profile(corner.x + band[0], corner.y + band[1], z, bottom, top, drop, geology, footPinned, rimPinned,
              corner.banded) * pin;
        return new float[] { direction[0] * d, direction[1] * d };
    }

    /**
     * The transition band of a corner's vertical line at height z. Every seam through the corner asks for its own band
     * along its normal (a seam without one, for its line to stay), and the offset meets those requests in the weighted
     * least-squares sense. A seam asks throughout its span and strongly near its ends, where it bounds a hex's top, and
     * that strong request fades within a third of a level beyond them. So where two seams meet at 120 degrees their
     * offset lines cross exactly, each hex's top keeps an exact offset corner at its own level, and the line turns
     * smoothly between the levels of a three-level corner.
     */
    private void bandOffset(Corner corner, float z, float[] out) {
        // A corner that touches paving, special artwork or a building keeps its place: the bands of the other steps
        // through it close there, so no outline that keeps its edge is ever pushed aside. One that touches water moves
        // with the slopes through it; the water hexes there keep clear of it (cornerReach, cornerInset).
        if (!corner.banded || corner.low == corner.high) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        float[] cached = corner.bands.computeIfAbsent(Float.floatToIntBits(z), key -> computeBandOffset(corner, z));
        out[0] = cached[0];
        out[1] = cached[1];
    }

    private float[] computeBandOffset(Corner corner, float z) {
        float[] result = new float[2];
        float level = BoardGeometry.LEVEL, reach = .35f * level, a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0, widest = 0;
        for (int i = 0; i < 3; i++) {
            Site p = corner.around[i], q = corner.around[(i + 1) % 3];
            if (joined(p, q)) { continue; }
            Site upper = p.level() >= q.level() ? p : q, lower = upper == p ? q : p;
            float bottom = lower.level() * level, top = upper.level() * level;
            boolean inside = z >= bottom - EPSILON * level && z <= top + EPSILON * level;
            float end = Math.max(0, 1 - Math.min(Math.abs(z - bottom), Math.abs(z - top)) / reach);
            float weight = (inside ? 1 : 0) + 8 * end * end;
            if (weight <= 0) { continue; }
            float nx = lower.x() - upper.x(), ny = lower.y() - upper.y(), length = (float) Math.hypot(nx, ny);
            nx /= length;
            ny /= length;
            float b = band(upper, lower, z);
            widest = Math.max(widest, room(upper, lower));
            a11 += weight * nx * nx;
            a12 += weight * nx * ny;
            a22 += weight * ny * ny;
            b1 += weight * nx * b;
            b2 += weight * ny * b;
        }
        float determinant = a11 * a22 - a12 * a12;
        if (widest <= 0 || determinant < 1e-4f) { return result; }
        result[0] = (a22 * b1 - a12 * b2) / determinant;
        result[1] = (a11 * b2 - a12 * b1) / determinant;
        // Seams asking for opposite bands on a narrow angle could otherwise throw the corner far out.
        float length = (float) Math.hypot(result[0], result[1]), limit = 1.5f * widest;
        if (length > limit) {
            result[0] *= limit / length;
            result[1] *= limit / length;
        }
        return result;
    }

    /**
     * The fillet displacement of a corner at height z. On a three-level corner it turns from the upper span's direction
     * to the middle hex's and on to the lower span's within a quarter level of the middle level, where the middle
     * hex's own outline turns.
     */
    private static void filletOffset(Corner corner, float z, float[] out) {
        float level = BoardGeometry.LEVEL;
        if (corner.fillet <= 0 || z < corner.low * level - EPSILON || z > corner.high * level + EPSILON) {
            out[0] = 0;
            out[1] = 0;
        } else if (corner.mid == corner.low || corner.mid == corner.high) {
            out[0] = corner.odd[0];
            out[1] = corner.odd[1];
        } else {
            float middle = corner.mid * level;
            float[] far = z >= middle ? corner.towardHigh : corner.towardLow;
            float f = smooth(Math.abs(z - middle) / (.25f * level));
            out[0] = lerp(corner.towardMid[0], far[0], f);
            out[1] = lerp(corner.towardMid[1], far[1], f);
        }
    }

    /** Outward direction of the rock at a corner and height, or null where the corner line is pinned. */
    private float[] cornerDirection(Corner corner, float z) {
        return cornerDirection(corner, z, false);
    }

    /** As above; a rim of the middle hex of a three-level corner faces away from that hex, whose outline turns there. */
    private float[] cornerDirection(Corner corner, float z, boolean rim) {
        if (corner.pinned || corner.low == corner.high) { return null; }
        if (rim && corner.mid != corner.low && corner.mid != corner.high
              && Math.abs(z - corner.mid * BoardGeometry.LEVEL) < EPSILON * BoardGeometry.LEVEL) {
            float length = (float) Math.hypot(corner.towardMid[0], corner.towardMid[1]);
            return length <= 0 ? null : new float[] { -corner.towardMid[0] / length, -corner.towardMid[1] / length };
        }
        int[] span = corner.span(z);
        Site solid = null, empty = null;
        int solids = 0;
        for (Site site : corner.around) {
            if (site.level() >= span[1]) {
                solids++;
                solid = site;
            } else if (site.level() <= span[0]) {
                empty = site;
            }
        }
        float ux, uy;
        if (solids == 1) {
            ux = corner.x - solid.x();
            uy = corner.y - solid.y();
        } else if (solids == 2 && empty != null) {
            ux = empty.x() - corner.x;
            uy = empty.y() - corner.y;
        } else {
            return null;
        }
        float length = (float) Math.sqrt(ux * ux + uy * uy);
        return new float[] { ux / length, uy / length };
    }

    // ---- Canonical edges -------------------------------------------------------------------------------------

    /** One geometric hex edge, oriented from the smaller corner key; both adjacent hexes build the same one. */
    private final class Edge {
        final Corner a;
        final Corner b;
        final Site upper;
        final Site lower;
        final float nx;
        final float ny;
        final float length;
        final boolean profiled;
        /** A water hex keeps its outline where it is the lower or the upper hex: no relief at the foot or the rim. */
        final boolean footPinned;
        final boolean rimPinned;
        /** The room of the step; see {@link #room(Site, Site)}. */
        final float room;
        /** How far the step's foot lies out from the edge in the lower hex; see {@link #band}. */
        final float footRoom;
        /** The step's height for its landforms; see {@link #drop(Site, Site)}. */
        final float drop;

        Edge(Corner first, Corner second, Site one, Site other) {
            boolean ordered = first.key < second.key;
            a = ordered ? first : second;
            b = ordered ? second : first;
            length = (float) Math.hypot(b.x - a.x, b.y - a.y);
            if (one != null && other != null && one.level() != other.level()) {
                upper = one.level() > other.level() ? one : other;
                lower = upper == one ? other : one;
            } else if (one != null && other == null) {
                upper = one;
                lower = null;
            } else {
                upper = null;
                lower = null;
            }
            float dx = (b.x - a.x) / length, dy = (b.y - a.y) / length;
            float px = dy, py = -dx;
            float sx = upper == null ? 0 : (lower == null ? (a.x + b.x) / 2 : lower.x()) - upper.x();
            float sy = upper == null ? 0 : (lower == null ? (a.y + b.y) / 2 : lower.y()) - upper.y();
            if (px * sx + py * sy < 0) {
                px = -px;
                py = -py;
            }
            nx = px;
            ny = py;
            footPinned = lower == null || !lower.sculpted() || lower.liquid();
            rimPinned = upper != null && upper.liquid();
            profiled = upper != null && lower != null && upper.sculpted() && (lower.sculpted() || lower.liquid());
            room = profiled ? room(upper, lower) : 0;
            footRoom = room > 0 ? band(upper, lower, bottom()) : 0;
            drop = upper != null && lower != null ? drop(upper, lower) : 0;
        }

        float bottom() { return lower == null ? Float.NEGATIVE_INFINITY : lower.level() * BoardGeometry.LEVEL; }

        float top() { return upper == null ? Float.POSITIVE_INFINITY : upper.level() * BoardGeometry.LEVEL; }
    }

    private Edge edge(int index) {
        int e = Math.floorMod(index, 6);
        if (edges[e] == null) {
            edges[e] = new Edge(corner(self, e), corner(self, e + 1), self, neighbor(self, e));
        }
        return edges[e];
    }

    /** Canonical sample count of an edge: cliffs need finer columns than open ground. */
    private int samples(Edge edge) {
        return edge.profiled ? 2 * detail.steps() : detail.steps();
    }

    private float canonical(int index, int sample, int count) {
        // Integer arithmetic first: both hexes of an edge must produce the bit-identical parameter.
        return (edge(index).a == corner(self, index) ? sample : count - sample) / (float) count;
    }

    /**
     * Displaced point of an edge at canonical parameter t and height z (horizontal offset only). Each corner's fillet
     * moves the outline by its displacement times (1 - u/s)^3, u being the distance from the corner and s the fillet
     * length. A displacement of s/6 toward the far side makes the outlines meeting at the corner tangent-continuous,
     * so no corner keeps a crease. The relief then follows the rounded outline's own normal and blends into each
     * corner's canonical relief, which every edge through that corner shares.
     */
    private Vector3 edgePoint(Edge edge, float t, float z) {
        float lx = edge.b.x - edge.a.x, ly = edge.b.y - edge.a.y;
        float px = edge.a.x + lx * t, py = edge.a.y + ly * t;
        float along = t * edge.length;
        filletOffset(edge.a, z, scratchA);
        filletOffset(edge.b, z, scratchB);
        float ua = edge.a.fillet > 0 ? Math.min(1, along / edge.a.fillet) : 1;
        float ub = edge.b.fillet > 0 ? Math.min(1, (edge.length - along) / edge.b.fillet) : 1;
        float fa = (1 - ua) * (1 - ua) * (1 - ua), fb = (1 - ub) * (1 - ub) * (1 - ub);
        float ga = edge.a.fillet > 0 ? -3 * (1 - ua) * (1 - ua) / edge.a.fillet : 0;
        float gb = edge.b.fillet > 0 ? 3 * (1 - ub) * (1 - ub) / edge.b.fillet : 0;
        float rx = scratchA[0] * fa + scratchB[0] * fb, ry = scratchA[1] * fa + scratchB[1] * fb;
        float tx = lx / edge.length + scratchA[0] * ga + scratchB[0] * gb;
        float ty = ly / edge.length + scratchA[1] * ga + scratchB[1] * gb;
        float nl = (float) Math.sqrt(tx * tx + ty * ty), nx = ty / nl, ny = -tx / nl;
        if (nx * edge.nx + ny * edge.ny < 0) {
            nx = -nx;
            ny = -ny;
        }
        float blend = .3f * edge.length;
        float wa = 1 - smooth(along / blend), wb = 1 - smooth((edge.length - along) / blend), rest = 1 - wa - wb;
        boolean spans = edge.profiled && z >= edge.bottom() - EPSILON && z <= edge.top() + EPSILON;
        float ex = lx / edge.length, ey = ly / edge.length;
        // The transition band offsets the outline as a polygon: along the edge's normal by its own band, turning into
        // each corner's band near that corner, and along the edge by a shift running evenly from one corner's to the
        // other's. Where two seams meet, the corner's band is where their offset lines cross, so the edge stays straight.
        // Written, like the relief below, so that at a corner every edge through it adds the corner's own band exactly.
        float bx = 0, by = 0;
        if (BoardGeometry.tuning().stepsBetweenTops()) {
            float own = spans && edge.room > 0 ? band(edge.upper, edge.lower, z) * rest : 0;
            bandOffset(edge.a, z, bandA);
            bandOffset(edge.b, z, bandB);
            float ta = bandA[0] * ex + bandA[1] * ey, tb = bandB[0] * ex + bandB[1] * ey;
            float slide = ta * (1 - t) + tb * t - ta * wa - tb * wb;
            bx = edge.nx * own + bandA[0] * wa + bandB[0] * wb + ex * slide;
            by = edge.ny * own + bandA[1] * wa + bandB[1] * wb + ey * slide;
        }
        cornerOffset(edge.a, z, scratchA);
        cornerOffset(edge.b, z, scratchB);
        float d = 0, dx = nx, dy = ny;
        if (rest > 0 && spans) {
            // The relief is a function of where the face stands, so a band moves it along instead of squeezing it.
            float fx = px + bx, fy = py + by;
            d = profile(fx, fy, z, edge.bottom(), edge.top(), edge.drop, BoardRelief.geology.get(edge.upper.family()),
                  edge.footPinned, edge.rimPinned, edge.room > 0);
            if (edge.room > 0) {
                float up = Math.clamp((z - edge.bottom()) / (edge.top() - edge.bottom()), 0, 1);
                // The relief of a transition moves its rim along rays from the upper hex's centre and its foot along
                // rays to the lower one's, so both tops stay star-shaped around their anchors however deep the cuts;
                // between them it follows the rounded outline, as the relief of every wall does.
                float ux = fx - edge.upper.x(), uy = fy - edge.upper.y();
                float vx = edge.lower.x() - fx, vy = edge.lower.y() - fy;
                float ul = (float) Math.hypot(ux, uy), vl = (float) Math.hypot(vx, vy);
                float rim = smooth((up - .75f) / .25f), foot = 1 - smooth(up / .25f), face = 1 - rim - foot;
                dx = nx * face + ux / ul * rim + vx / vl * foot;
                dy = ny * face + uy / ul * rim + vy / vl * foot;
                float length = (float) Math.hypot(dx, dy);
                dx /= length;
                dy /= length;
            }
        }
        // A corner's relief slides the edges through it along themselves too. That part fades over at least 1.6 times
        // its own length, so the points along an edge keep their order however far a talus spreads at the corner.
        float ta = scratchA[0] * ex + scratchA[1] * ey, tb = scratchB[0] * ex + scratchB[1] * ey;
        float sa = 1 - smooth(along / Math.max(blend, 1.6f * Math.abs(ta)));
        float sb = 1 - smooth((edge.length - along) / Math.max(blend, 1.6f * Math.abs(tb)));
        // A shore's move of a corner runs out evenly along each edge through it, so those edges stay straight.
        float[] ma = edge.a.move(), mb = edge.b.move();
        float qx = ma[0] * (1 - t) + mb[0] * t, qy = ma[1] * (1 - t) + mb[1] * t;
        // Written so that at a corner (weights exactly one) every edge through it adds the identical offset.
        return new Vector3(px + rx + bx + qx + dx * d * rest + scratchA[0] * wa + ta * ex * (sa - wa)
                    + scratchB[0] * wb + tb * ex * (sb - wb),
              py + ry + by + qy + dy * d * rest + scratchA[1] * wa + ta * ey * (sa - wa)
                    + scratchB[1] * wb + tb * ey * (sb - wb), z);
    }

    // ---- Wall profile ----------------------------------------------------------------------------------------

    /**
     * The height of a step for its landforms, in world units: from the upper hex's level, a water hex's surface, down
     * to the ground units stand on below it, which in a water hex is its bed, since tanks and meks do not walk on
     * water. Land a level above water a level deep takes a two-level slope; land two levels above it stands over a
     * three-level cliff, as does water three levels above land.
     */
    private static float drop(Site upper, Site lower) {
        return (upper.level() - lower.level() + lower.depth()) * BoardGeometry.LEVEL;
    }

    /** 0 for drops up to two levels, 1 from three levels: the user-visible prominence of rim formations. */
    private static float prominence(float drop) {
        return smooth((drop / BoardGeometry.LEVEL - 2.15f) / .85f);
    }

    /**
     * Room a step takes on each side of its edge, in world units: none unless hex transitions or padding are on, and
     * only between natural grounds, water among them. Paving, special artwork, buildings and roads keep their outline
     * on the hex edge; water keeps its own where {@link #band} says.
     */
    private static float room(Site upper, Site lower) {
        return BoardGeometry.tuning().stepsBetweenTops() && upper != null && lower != null
              && upper.level() != lower.level() && upper.detailed() && lower.detailed()
              && upper.family() != CONCRETE && lower.family() != CONCRETE && !(upper.liquid() && lower.liquid())
              ? stepRoom() : 0;
    }

    /**
     * Whether a step is a wall of three levels or more (see {@link #drop}), rather than a slope a tank (one level) or a
     * mek (two) can climb.
     */
    private static boolean wall(Site upper, Site lower) {
        return drop(upper, lower) > 2.5f * BoardGeometry.LEVEL;
    }

    /**
     * How far a step's face at height z stands out from its edge into the lower hex (negative: back in the upper one):
     * its room at the foot, following {@link #transition} up to its room back at the rim. A slope down to water runs
     * on under it to the bed: land a level above water a level deep takes a two-level slope, which meets the water on
     * the edge. Where a wall of three levels or more stands above water, or water stands above land, the water keeps
     * its outline on the edge, so the land takes a step half as wide, all on its own side.
     */
    private static float band(Site upper, Site lower, float z) {
        float room = room(upper, lower);
        if (room <= 0) { return 0; }
        boolean wall = wall(upper, lower);
        float bottom = (lower.level() - (wall ? 0 : lower.depth())) * BoardGeometry.LEVEL;
        float top = upper.level() * BoardGeometry.LEVEL;
        float t = transition(Math.clamp(z, bottom, top), bottom, top, drop(upper, lower));
        return wall && lower.liquid() ? room / 2 * (t - 1) : upper.liquid() ? room / 2 * (t + 1) : room * t;
    }

    /**
     * Where a transition puts a step's face at height z, as a fraction of its room: 1 at the foot, out in the lower hex,
     * and -1 at the rim, back in the upper one. Up to two levels a slope, gentler toward the rim and the foot than in its
     * middle; from three a concave talus that rises from its toe to a face standing the whole room back from the edge.
     * {@code drop} is the step's height for its landforms (see {@link #drop(Site, Site)}).
     */
    private static float transition(float z, float bottom, float top, float drop) {
        float span = top - bottom;
        float t = Math.clamp((z - bottom) / span, 0, 1);
        float slope = 1 - 2 * (t + .45f * (float) Math.sin(2 * Math.PI * t) / (2 * (float) Math.PI));
        float u = Math.clamp((z - bottom) / Math.min(.3f * span, metres(6)), 0, 1);
        float cliff = 1 - 2 * (1 - (1 - u) * (1 - u));
        return lerp(slope, cliff, prominence(drop));
    }

    /**
     * Outward displacement of a cliff face at base point (px, py) and height z, for a cliff spanning [bottom, top]
     * whose landforms are those of a step {@code drop} high (see {@link #drop(Site, Site)}).
     * Positive values stand out into the lower hex. Caprock, talus, jointed rock masses, fractures, bedding and
     * broad buttresses are all functions of world position, so adjoining walls continue each other.
     */
    private static float profile(float px, float py, float z, float bottom, float top, float drop, Geology g,
          boolean footPinned, boolean rimPinned, boolean banded) {
        float m = metres(1);
        float big = prominence(drop);
        float amplitude = .6f + .4f * smooth((drop / BoardGeometry.LEVEL - 1) / 2);
        // A transition's room lets a tall cliff's masses stand out and cut in further; its bedding stays as it is.
        float masses = banded ? 1 + .25f * big : 1;
        float h = z - bottom, d = top - z;
        float mx = px / m, my = py / m, mz = z / m;
        // Rim formations follow the drop: a thin lip on one- and two-level steps, a heavy, frequent caprock from three.
        float capHeight = m * lerp(.9f, 1.8f, big);
        float capNoise = noise(mx / 9 + 17.3f, my / 9 - 4.1f);
        float capOut = m * lerp(.28f, 1.5f, big) * smooth((capNoise - lerp(.42f, .12f, big)) / .2f)
              * (.6f + .8f * capNoise) * g.cap();
        float under = m * lerp(.2f, .75f, big) * (.4f + capNoise) * g.cap();
        float t = d / capHeight;
        float cap = capOut * capShape(t) - under * bump((t - 1.6f) / .75f);
        // Soft ground's low steps are banks of the soil mantle: little jointing, leaning back from the rim. A
        // transition's low steps are slopes of every ground's mantle, with little of the rock showing through.
        float firm = lerp(banded ? Math.min(g.bank(), .35f) : g.bank(), 1, big);
        // A transition's band lays the bank back itself.
        float lean = banded ? 0 : g.lean() * (1 - big) * d;
        // The face stands back from the hex edge, further on taller cliffs; the talus apron fills the gap it leaves.
        float retreat = g.recess() * m * (.35f + .65f * noise(mx / 14 - 8.2f, my / 14 + 2.9f))
              * Math.clamp(drop / (12 * m), .5f, 1.6f) * (banded ? .4f : 1);
        // Talus: a concave apron of fallen rock from the retreated face out past the edge; lumpy above its contact line.
        float talusNoise = noise(mx / 6 + 5.1f, my / 6 + 9.7f);
        float talusHeight = Math.min(.3f * drop, m * 6f) * (.7f + .6f * noise(mx / 9 - 3.3f, my / 9 + 6.6f));
        // A transition's band lays the apron itself; its relief keeps only the lumpy toe of the scree.
        float reach = Math.min(m * .9f + .1f * drop, m * 3.2f) * (.45f + .75f * talusNoise) * (banded ? .35f : 1);
        float slope = Math.max(0, 1 - h / talusHeight);
        float foot = 0;
        if (!footPinned && slope > 0) {
            float scree = 1 + (.45f * gradient(mx / 1.7f + 2.2f, my / 1.7f - 7.1f, mz / 1.4f)
                  + .3f * gradient(mx / 4.5f, my / 4.5f, mz / 3f)) * smooth(h / Math.max(m * .5f, talusHeight * .3f));
            foot = (retreat + reach) * (float) Math.pow(slope, 1.6) * scree * g.talus();
        }
        // Body: broad buttresses, jointed masses split by dark fractures, and bedding ledges.
        float[] cell = CELL.get();
        cells(mx / g.cellWidth(), my / g.cellWidth(), mz / g.cellHeight(), cell);
        float body = (g.buttress() * gradient(mx / 12 + 3.3f, my / 12 + .7f)
              + g.cells() * cell[0]
              - g.fractures() * (1 - smooth(cell[1] / .16f))) * masses
              + g.strata() * strata(mx, my, mz, g.bedding())
              + .18f * (1 - g.cast()) * gradient(mx / 1.3f, my / 1.3f, mz / 1.3f);
        // The lip keeps part of the joint pattern, so the rim outline is broken rather than smooth; deep rims more so.
        float rimBody = lerp(.3f, .75f, big);
        float taper = smooth(h / Math.max(m * 1.0f, talusHeight * .6f))
              * (rimBody + (1 - rimBody) * smooth(d / (capHeight * 1.2f)));
        float result = cap + foot + lean + (body * m * amplitude * taper - retreat) * firm;
        if (banded && big > 0) {
            // Clefts: from three levels a transition cuts gullies into the face every few metres. They barely notch
            // the hard caprock and are choked with scree below; each sheds a cone of debris onto the talus.
            cells(mx / 7, my / 7, 3.5f, cell);
            float cut = (1 - smooth(cell[1] / .22f)) * big * (.4f + .6f * noise(mx / 11 + 3.7f, my / 11 - 1.9f));
            result -= m * 2.2f * cut * smooth(h / Math.max(m, talusHeight * .8f))
                  * lerp(.2f, 1, smooth(d / (capHeight * 2)));
            result += m * 1.4f * cut * slope * slope;
        }
        if (footPinned) { result *= smooth(h / (m * 1.5f)); }
        if (rimPinned) { result *= smooth(d / (m * 1.5f)); }
        // Concrete from three levels: the top level is one flat slab. Below its underside the bedrock that carries it
        // stands back at least half a metre, so the slab's lower arris casts a clean line of shadow; toward the foot
        // the rock and its talus spread out freely.
        float slab = g.cast() * big;
        float underside = top - BoardGeometry.LEVEL;
        if (slab > 0 && z < underside - .01f * BoardGeometry.LEVEL) {
            float rock = profile(px, py, z, bottom, underside, drop - (top - underside),
                  BoardRelief.geology.get(GEOLOGY.length), footPinned, false, banded);
            float free = 1 - smooth(h / (.45f * (underside - bottom)));
            result = lerp(result, lerp(smoothMin(rock - m, -.5f * m, m), rock, free), slab);
        }
        return softClamp(result, banded);
    }

    /** The smaller of a and b, with the corner between them rounded over a width k. */
    static float smoothMin(float a, float b, float k) {
        float h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k * .25f;
    }

    private static float capShape(float t) {
        if (t < 0) { return .5f; }
        if (t < .25f) { return lerp(.5f, 1, smooth(t / .25f)); }
        return lerp(1, 0, smooth((t - .25f) / 1.1f));
    }

    private static float bump(float t) {
        float x = Math.abs(t);
        return x >= 1 ? 0 : (1 - x * x) * (1 - x * x);
    }

    private static final ThreadLocal<float[]> CELL = ThreadLocal.withInitial(() -> new float[56]);

    /**
     * Jointed rock masses: a smoothed 3D Voronoi in cell units. out[0] is the blended protrusion of the nearest masses
     * in [-1, 1]; out[1] approximates the distance to the nearest joint between two masses. out[2..] is scratch.
     */
    private static void cells(float x, float y, float z, float[] out) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float nearest = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
        int n = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = ix + dx, cy = iy + dy, cz = iz + dz;
                    int h = hashInt(cx, cy, cz);
                    float fx = cx + .15f + .7f * ((h & 1023) / 1023f) - x;
                    float fy = cy + .15f + .7f * (((h >>> 10) & 1023) / 1023f) - y;
                    float fz = cz + .15f + .7f * (((h >>> 20) & 1023) / 1023f) - z;
                    float d = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
                    int g = hashInt(cz, cx, cy);
                    float r = ((g & 0xffff) / 32767.5f) - 1;
                    out[2 + n] = d;
                    // Bias toward the extremes: most masses stand clearly in or out.
                    out[29 + n] = r * (1.35f - .35f * Math.abs(r));
                    n++;
                    if (d < nearest) {
                        second = nearest;
                        nearest = d;
                    } else if (d < second) {
                        second = d;
                    }
                }
            }
        }
        float sum = 0, weight = 0;
        for (int i = 0; i < n; i++) {
            float excess = out[2 + i] - nearest;
            if (excess > .16f) { continue; }
            float w = 1 - excess / .16f;
            w *= w;
            w *= w;
            sum += w * out[29 + i];
            weight += w;
        }
        out[0] = sum / weight;
        out[1] = (second - nearest) * .5f;
    }

    /** Irregular bedding: alternating hard and soft layers of varying thickness, gently undulating. */
    private static float strata(float x, float y, float z, float bedding) {
        float u = bedding(x, y, z, bedding);
        int bed = (int) Math.floor(u);
        float f = u - bed;
        float hardness = hash(bed * 17 + 3, 91) * 2 - 1;
        // Hard beds stand out with a steep lower edge and a sloping top where sand or snow can lie.
        float shape = smooth(f / .07f) * smooth((1 - f) / .3f);
        return hardness * shape;
    }

    /** Continuous bed coordinate at a point in metres: its integer part numbers the bed. */
    private static float bedding(float x, float y, float z, float bedding) {
        return z / bedding + .8f * gradient(x / 30, y / 30) + .2f * gradient(x / 7 + 3.1f, y / 7 - 1.7f);
    }

    /** Hardness in [0, 1] of the bed holding a world point: the colour of the same bed whose ledge stands out. */
    private static float bed(Vector3 p, Geology geology) {
        float m = metres(1);
        return hash((int) Math.floor(bedding(p.x / m, p.y / m, p.z / m, geology.bedding())) * 17 + 3, 91);
    }

    /** Bounds a wall's relief smoothly; a transition's clefts may cut two metres deeper. */
    private static float softClamp(float value, boolean banded) {
        float inward = BoardGeometry.WIDTH * .15f + (banded ? metres(2) : 0), outward = BoardGeometry.WIDTH * .12f;
        return value >= 0 ? outward * (float) Math.tanh(value / outward) : -inward * (float) Math.tanh(-value / inward);
    }

    // ---- Top surface -----------------------------------------------------------------------------------------

    /** Sculpted height of this hex's top at (x, y); exact level near cliffs, banks, special art and the anchor. */
    float groundHeight(float x, float y) {
        float base = tile.elevation() * BoardGeometry.LEVEL;
        if (!self.detailed()) { return base; }
        float envelope = envelope(x, y);
        if (envelope <= 0) { return base; }
        float m = metres(1), mx = x / m, my = y / m;
        float relief = .6f * gradient(mx / 26 + 1.7f, my / 26 - 2.2f) + .3f * gradient(mx / 11 - 4.4f, my / 11 + 3.9f)
              + .1f * gradient(mx / 4.5f + 7.2f, my / 4.5f + 1.1f);
        return base + amplitude(x, y) * m * relief * envelope;
    }

    Vector3 groundNormal(Vector3 point) {
        float delta = metres(.35f);
        return new Vector3(groundHeight(point.x - delta, point.y) - groundHeight(point.x + delta, point.y),
              groundHeight(point.x, point.y - delta) - groundHeight(point.x, point.y + delta), 2 * delta).nor();
    }

    /** Canonical relief amplitude: a smooth blend of the families around the point at this hex's level. */
    private float amplitude(float x, float y) {
        if (Float.isNaN(uniformRelief)) {
            // Usually every joined hex around shares one family: the blend is then that family's constant.
            uniformRelief = -1;
            for (Site site : candidates()) {
                if (!site.detailed() || site.level() != self.level()) { continue; }
                float relief = BoardRelief.geology.get(site.family()).relief();
                if (uniformRelief >= 0 && relief != uniformRelief) { uniformRelief = -2; break; }
                uniformRelief = relief;
            }
        }
        if (uniformRelief >= 0) { return uniformRelief; }
        float weight = 0, sum = 0, radius = BoardGeometry.WIDTH * .75f;
        for (Site site : candidates()) {
            if (!site.detailed() || site.level() != self.level()) { continue; }
            float dx = x - site.x(), dy = y - site.y();
            float w = Math.max(0, 1 - (float) Math.sqrt(dx * dx + dy * dy) / radius);
            w *= w;
            weight += w;
            sum += w * BoardRelief.geology.get(site.family()).relief();
        }
        return weight <= 0 ? 0 : sum / weight;
    }

    /**
     * 0 near every seam that must stay at an exact level (cliffs, banks, special art, board edges) and inside the
     * unit anchor, 1 in open ground. Computed from the hexes around the point only, so both sides of a joined edge
     * derive the same value.
     */
    private float envelope(float x, float y) {
        if (seams == null) { segments(); }
        float nearest = nearest(seams, x, y);
        float near = BoardGeometry.WIDTH * .13f, far = BoardGeometry.WIDTH * .26f;
        float seam = smooth((nearest - near) / (far - near));
        float anchor = 1, reach = BoardGeometry.WIDTH * .34f;
        for (Site site : candidates()) {
            if (!site.detailed()) { continue; }
            float dx = x - site.x(), dy = y - site.y();
            float squared = dx * dx + dy * dy;
            if (squared > reach * reach) { continue; }
            float distance = (float) Math.sqrt(squared);
            float c = distance > 0 ? dx / distance : 1, s = distance > 0 ? dy / distance : 0;
            // A radius that wanders with bearing (second and third harmonics), phased per hex: no two anchors match.
            float c2 = c * c - s * s, s2 = 2 * s * c, c3 = c * (4 * c * c - 3), s3 = s * (3 - 4 * s * s);
            float p2 = site.ix() * 1.3f, p3 = site.iy() * .7f;
            float radius = BoardGeometry.WIDTH * (.15f + .045f * (s2 * (float) Math.cos(p2) + c2 * (float) Math.sin(p2))
                  + .035f * (s3 * (float) Math.cos(p3) - c3 * (float) Math.sin(p3)));
            // Level where units stand, a gentle rise, then open ground.
            anchor = Math.min(anchor, Math.min(1, .12f * smooth(distance / (radius * .6f))
                  + .88f * smooth((distance - radius) / (BoardGeometry.WIDTH * .13f))));
        }
        return Math.min(seam, anchor);
    }

    /**
     * Seams (every unjoined edge of this hex and its neighbours), and among the edges of hexes at this hex's level,
     * the drops and rises between hexes. Arrays of [ax, ay, bx, by, levels, room] segments; levels is the step's
     * height and room how far a cliff's transition moves its rim and foot from the edge, so distances are measured to
     * the rim or foot itself.
     */
    private void segments() {
        List<float[]> seam = new ArrayList<>(), drop = new ArrayList<>(), rise = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Site site : candidates()) {
            for (int e = 0; e < 6; e++) {
                int n = (e + 1) % 6;
                int ax = site.ix() + CORNER_DX[e], ay = site.iy() + CORNER_DY[e];
                int bx = site.ix() + CORNER_DX[n], by = site.iy() + CORNER_DY[n];
                long keyA = ((long) ax << 32) ^ (ay & 0xffffffffL), keyB = ((long) bx << 32) ^ (by & 0xffffffffL);
                Site other = neighbor(site, e);
                boolean higher = other == null || site.level() > other.level();
                Site upper = higher ? site : other, lower = higher ? other : site;
                float levels = other == null ? 0 : drop(upper, lower) / BoardGeometry.LEVEL;
                // A cliff's rim and foot move with its band; a slope's ground effects stay near its edge line, so they
                // fade across the slope's own gentle rim and toe.
                float big = prominence(levels * BoardGeometry.LEVEL);
                float rim = other == null ? 0 : -band(upper, lower, upper.level() * BoardGeometry.LEVEL) * big;
                float foot = other == null ? 0 : band(upper, lower, lower.level() * BoardGeometry.LEVEL) * big;
                // Seams at this level run between the corners as the shore moves them.
                Corner ca = corner(site, e), cb = corner(site, n);
                float x0 = ca.x + ca.move()[0], y0 = ca.y + ca.move()[1];
                float x1 = cb.x + cb.move()[0], y1 = cb.y + cb.move()[1];
                if (seen.add(Math.min(keyA, keyB) * 31 + Math.max(keyA, keyB)) && !joined(site, other)) {
                    // Hexes at one level see one side of every step alike, so joined tops fade their relief alike.
                    seam.add(new float[] { x0, y0, x1, y1, levels, self.level() >= upper.level() ? rim : foot });
                }
                // The board's own edge is a cut, not a landform: it has no rim material.
                if (site.level() == self.level() && other != null && other.level() != site.level()) {
                    boolean down = other.level() < site.level();
                    (down ? drop : rise).add(new float[] { x0, y0, x1, y1, levels, down ? rim : foot });
                }
            }
        }
        seams = flatten(seam);
        drops = flatten(drop);
        rises = flatten(rise);
    }

    private static float[] flatten(List<float[]> list) {
        float[] result = new float[list.size() * SEGMENT];
        for (int i = 0; i < list.size(); i++) { System.arraycopy(list.get(i), 0, result, i * SEGMENT, SEGMENT); }
        return result;
    }

    /** Floats per seam segment: its two ends, its step in levels and its transition room. */
    private static final int SEGMENT = 6;

    /** Distance from (x, y) to the nearest segment's own rim or foot: its line, moved by its transition room. */
    private static float nearest(float[] segments, float x, float y) {
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < segments.length; i += SEGMENT) {
            nearest = Math.min(nearest, distance(x, y, segments[i], segments[i + 1], segments[i + 2], segments[i + 3])
                  - segments[i + 5]);
        }
        return nearest;
    }

    /** The step height in levels of the segment nearest to (x, y); zero if there is none. */
    private static float nearestLevels(float[] segments, float x, float y) {
        float nearest = Float.POSITIVE_INFINITY, levels = 0;
        for (int i = 0; i < segments.length; i += SEGMENT) {
            float d = distance(x, y, segments[i], segments[i + 1], segments[i + 2], segments[i + 3]) - segments[i + 5];
            if (d < nearest) {
                nearest = d;
                levels = segments[i + 4];
            }
        }
        return levels;
    }

    /**
     * Replaces the flat top with the sculpted surface. A water hex keeps its bed and replaces its flat banks: waterline
     * is its full outline and mouths its open mouths by edge (see {@link BoardSurface#mouth}); crests the waterline
     * with each fall's mouth moved out to its crest, and falls the edges it falls over. Null and 0 on land.
     */
    void top(List<BoardSurface.Face> destination, Vector3[] waterline, int mouths, Vector3[] crests, int falls) {
        if (!sculpted) { return; }
        this.waterline = waterline;
        this.falls = falls;
        if (waterline == null) {
            destination.clear();
        } else {
            destination.removeIf(face -> face.finish() == BoardSurface.Finish.TOP
                  || face.finish() == BoardSurface.Finish.SHORE);
        }
        float base = tile.elevation() * BoardGeometry.LEVEL;
        List<Vector3> boundary = new ArrayList<>();
        List<Float> parameters = new ArrayList<>();
        int[] starts = new int[7];
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            boolean seamless = joined(self, neighbor(self, e));
            int count = samples(edge);
            starts[e] = boundary.size();
            for (int i = 0; i < count; i++) {
                Vector3 p = edgePoint(edge, canonical(e, i, count), base);
                if (i == 0) {
                    p.z = corner(self, e).seamless() ? groundHeight(p.x, p.y) : base;
                } else if (seamless) {
                    p.z = groundHeight(p.x, p.y);
                }
                boundary.add(p);
                parameters.add(e + i / (float) count);
            }
        }
        starts[6] = boundary.size();
        outline = boundary;
        for (int e = 0; e < 6; e++) {
            int count = starts[e + 1] - starts[e];
            rims[e] = new Vector3[count + 1];
            for (int i = 0; i <= count; i++) { rims[e][i] = boundary.get((starts[e] + i) % boundary.size()); }
        }
        for (int e = 0; e < 6; e++) { rimShades(e); }
        Vector3 center = new Vector3(BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()), 0);
        if (waterline != null) {
            bank(destination, boundary, starts, mouths);
            rocks(destination, center);
            fallRocks(destination, center, crests, falls, mouths);
            return;
        }
        center.z = groundHeight(center.x, center.y);
        shades.put(center, groundShade(center));
        // A narrow band follows every boundary sample radially, so the broken rim never folds the surface.
        int count = boundary.size();
        float band = BoardGeometry.WIDTH * .045f;
        List<Vector3> rim = new ArrayList<>(count);
        float[] radius = new float[count];
        float[] reach = new float[count];
        for (int j = 0; j < count; j++) {
            Vector3 b = boundary.get(j);
            reach[j] = (float) Math.hypot(b.x - center.x, b.y - center.y);
        }
        for (int j = 0; j < count; j++) {
            // Inside the nearest boundary samples too, so a jog in a jointed rim cannot fold the band.
            float minimum = reach[j];
            for (int w = -2; w <= 2; w++) { minimum = Math.min(minimum, reach[Math.floorMod(j + w, count)]); }
            radius[j] = minimum - band;
            Vector3 b = boundary.get(j);
            float f = radius[j] / reach[j];
            Vector3 p = new Vector3(center.x + (b.x - center.x) * f, center.y + (b.y - center.y) * f, 0);
            p.z = groundHeight(p.x, p.y);
            rim.add(p);
            shades.put(p, groundShade(p));
        }
        for (int j = 0; j < count; j++) {
            int n = (j + 1) % count;
            Vector3 a = rim.get(j), b = boundary.get(j), c = boundary.get(n), d = rim.get(n);
            // Where a joint notches the rim, one side of the notch can run radially; split each quad of the band along
            // the diagonal that keeps both of its triangles facing up.
            if (Math.min(upward(a, b, c), upward(a, c, d)) >= Math.min(upward(a, b, d), upward(b, c, d))) {
                destination.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP));
                destination.add(new BoardSurface.Face(a, c, d, BoardSurface.Finish.TOP));
            } else {
                destination.add(new BoardSurface.Face(a, b, d, BoardSurface.Finish.TOP));
                destination.add(new BoardSurface.Face(b, c, d, BoardSurface.Finish.TOP));
            }
        }
        List<Vector3> outer = rim;
        List<Float> outerParameters = parameters;
        for (int ring = detail.rings().length - 1; ring >= 0; ring--) {
            int per = detail.ringSamples()[ring];
            List<Vector3> inner = new ArrayList<>(6 * per);
            List<Float> innerParameters = new ArrayList<>(6 * per);
            for (int e = 0; e < 6; e++) {
                int edgeCount = starts[e + 1] - starts[e];
                for (int k = 0; k < per; k++) {
                    // Bearing of a boundary sample; radius below the band's minimum around that bearing.
                    int sample = starts[e] + Math.round(k * edgeCount / (float) per);
                    float minimum = Float.POSITIVE_INFINITY;
                    int window = Math.max(1, edgeCount / per);
                    for (int w = -window; w <= window; w++) {
                        minimum = Math.min(minimum, radius[Math.floorMod(sample + w, count)]);
                    }
                    Vector3 bearing = boundary.get(sample);
                    float distance = (float) Math.hypot(bearing.x - center.x, bearing.y - center.y);
                    float f = detail.rings()[ring] * minimum / distance;
                    Vector3 p = new Vector3(center.x + (bearing.x - center.x) * f, center.y + (bearing.y - center.y) * f, 0);
                    p.z = groundHeight(p.x, p.y);
                    inner.add(p);
                    innerParameters.add(parameters.get(sample));
                    shades.put(p, groundShade(p));
                }
            }
            zipper(destination, outer, outerParameters, inner, innerParameters);
            outer = inner;
            outerParameters = innerParameters;
        }
        for (int j = 0; j < outer.size(); j++) {
            destination.add(new BoardSurface.Face(center, outer.get(j), outer.get((j + 1) % outer.size()),
                  BoardSurface.Finish.TOP));
        }
        rocks(destination, center);
        field(destination, center);
        pits(destination);
    }

    // ---- Water hex banks -------------------------------------------------------------------------------------

    /**
     * A water hex's banks: the ground between its canonical boundary and its waterline, level at the hex's level and
     * dropping over a narrow shore to the water. An open mouth has none but for its short stretch from each corner to
     * where the waterline meets the mouth, which both hexes of the mouth build alike. The bed inside the waterline and
     * the water itself are {@link BoardSurface}'s.
     */
    private void bank(List<BoardSurface.Face> destination, List<Vector3> boundary, int[] starts, int mouths) {
        List<BoardSurface.Face> strip = new ArrayList<>();
        if (mouths == 0) {
            bankRun(strip, boundary, starts, 0, 6, false);
        } else {
            for (int e = 0; e < 6; e++) {
                // A run of banks starts after each mouth and ends before the next.
                if ((mouths & 1 << e) != 0 || (mouths & 1 << (e + 5) % 6) == 0) { continue; }
                int length = 1;
                while ((mouths & 1 << (e + length) % 6) == 0) { length++; }
                bankRun(strip, boundary, starts, e, length, true);
            }
        }
        // The bank's own vertices shade like the ground they are, facing as the bank does; the boundary keeps the
        // shades it shares with the neighbours' walls and tops.
        Map<Vector3, Vector3> normals = new IdentityHashMap<>(), byPosition = new HashMap<>();
        for (BoardSurface.Face face : strip) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (!shades.containsKey(p)) {
                    normals.computeIfAbsent(p, key -> byPosition.computeIfAbsent(key, position -> new Vector3())).add(normal);
                }
            }
        }
        // Exposed shallow bars join the dry bank. Share their normal across the former waterline, which is no
        // longer an actual edge of the water and must not leave a lighting seam through continuous sand.
        for (BoardSurface.Face face : destination) {
            if (face.finish() != BoardSurface.Finish.BED) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                Vector3 shared = byPosition.get(p);
                if (shared != null) {
                    shared.add(normal);
                    normals.put(p, shared);
                }
            }
        }
        for (var entry : normals.entrySet()) {
            Shade ground = groundShade(entry.getKey());
            shades.put(entry.getKey(), new Shade(entry.getValue().nor(), Kind.GROUND, ground.occlusion(),
                  ground.level(), ground.rim(), ground.foot(), ground.tint()));
        }
        destination.addAll(strip);
    }

    /**
     * One run of banks over {@code count} edges from edge {@code first}, strip by strip: the boundary, a level lip and
     * the waterline. With {@code mouths} the run starts and ends where the waterline meets the open mouth before it and
     * after it, and takes in the stretch of each mouth from its corner to that point.
     */
    private void bankRun(List<BoardSurface.Face> destination, List<Vector3> boundary, int[] starts, int first,
          int count, boolean mouths) {
        int perEdge = waterline.length / 6;
        float base = self.level() * BoardGeometry.LEVEL;
        List<Vector3> outer = new ArrayList<>(), lip = new ArrayList<>(), inner = new ArrayList<>();
        List<Float> outerParameters = new ArrayList<>(), lipParameters = new ArrayList<>();
        List<Float> innerParameters = new ArrayList<>();
        Vector3 start = null, end = null;
        float from = 0, to = 0;
        if (mouths) {
            start = new Vector3(waterline[first * perEdge]);
            end = new Vector3(waterline[(first + count) % 6 * perEdge]);
            from = along(first + 5, start);
            to = along(first + count, end);
            outer.add(start);
            outerParameters.add(first - 1 + from);
            stub(outer, outerParameters, boundary, starts, first + 5, first - 1, from, true);
        }
        for (int k = 0; k < count; k++) {
            int e = (first + k) % 6, samples = starts[e + 1] - starts[e];
            for (int i = 0; i < samples; i++) {
                outer.add(boundary.get(starts[e] + i));
                outerParameters.add(first + k + i / (float) samples);
            }
        }
        if (mouths) {
            // The corner that closes the run, and the mouth after it.
            outer.add(boundary.get(starts[(first + count) % 6]));
            outerParameters.add((float) (first + count));
            stub(outer, outerParameters, boundary, starts, first + count, first + count, to, false);
            outer.add(end);
            outerParameters.add(first + count + to);
            // The anchors end the boundary and the waterline, not the lip: each stub fans from a lip point inside the
            // bank, never from the anchor at its own end, which would fold over where a rounded corner bends it.
            inner.add(start);
            innerParameters.add(first - 1 + from);
        }
        float shore = tuning.shoreLip() * BoardGeometry.HEX_SCALE;
        for (int k = mouths ? 1 : 0; k < count * perEdge; k++) {
            int index = (first * perEdge + k) % waterline.length, e = index / perEdge;
            Vector3 w = new Vector3(waterline[index]);
            // The lip lies toward the boundary sample at the same place along the edge, at most halfway to it.
            int samples = starts[e + 1] - starts[e];
            Vector3 b = boundary.get((starts[e] + Math.round(index % perEdge * samples / (float) perEdge))
                  % boundary.size());
            float gap = (float) Math.hypot(b.x - w.x, b.y - w.y);
            float room = shore;
            // Opposing banks of a tight bend must not push their lip rows across each other.
            for (int j = 0; j < waterline.length; j++) {
                int separation = Math.floorMod(j - index, waterline.length);
                if (separation <= 1 || separation >= waterline.length - 2) { continue; }
                Vector3 p = waterline[j], q = waterline[(j + 1) % waterline.length];
                float dx = q.x - p.x, dy = q.y - p.y, length2 = dx * dx + dy * dy;
                float t = length2 == 0 ? 0 : Math.clamp(((w.x - p.x) * dx + (w.y - p.y) * dy) / length2, 0, 1);
                room = Math.min(room, .4f * (float) Math.hypot(w.x - p.x - t * dx, w.y - p.y - t * dy));
            }
            float f = Math.min(.5f, room / Math.max(gap, 1e-4f));
            lip.add(lip(boundary, waterline[(index + waterline.length - 1) % waterline.length], w,
                  waterline[(index + 1) % waterline.length], (b.x - w.x) * f, (b.y - w.y) * f, base));
            lipParameters.add(first + k / (float) perEdge);
            inner.add(w);
            innerParameters.add(first + k / (float) perEdge);
        }
        if (mouths) {
            inner.add(end);
            innerParameters.add(first + count + to);
        } else {
            // The ring closes where it began. Its boundary starts at the sample nearest the first lip, so the strip
            // starts between points that face each other even where the steps through corner 0 slide its samples.
            int nearest = 0, n = outer.size();
            for (int i = 1; i < n; i++) {
                if (outer.get(i).dst2(lip.getFirst()) < outer.get(nearest).dst2(lip.getFirst())) { nearest = i; }
            }
            float shift = outerParameters.get(nearest) > 3 ? -6 : 0;
            List<Vector3> ring = new ArrayList<>(n + 1);
            List<Float> ringParameters = new ArrayList<>(n + 1);
            for (int i = nearest; i <= nearest + n; i++) {
                ring.add(outer.get(i % n));
                ringParameters.add(outerParameters.get(i % n) + shift + (i >= n ? 6 : 0));
            }
            outer = ring;
            outerParameters = ringParameters;
            lip.add(lip.getFirst());
            lipParameters.add(6f);
            inner.add(inner.getFirst());
            innerParameters.add(6f);
        }
        strip(destination, outer, outerParameters, lip, lipParameters);
        strip(destination, lip, lipParameters, inner, innerParameters);
    }

    /**
     * The boundary samples of mouth edge {@code e} between its corner and where the waterline meets it (at {@code t},
     * this hex's fraction of the edge), falling from the hex's level to the water there over the bank's lip, or over
     * all of the stub where it is shorter. Both hexes of the mouth build them alike; a straight wall under a fall's
     * mouth tops out on them too.
     */
    private void stub(List<Vector3> outer, List<Float> parameters, List<Vector3> boundary, int[] starts, int e,
          int unwrapped, float t, boolean before) {
        int edge = Math.floorMod(e, 6), samples = starts[edge + 1] - starts[edge];
        float base = self.level() * BoardGeometry.LEVEL, margin = .02f;
        float water = waterline[(before ? (edge + 1) % 6 : edge) * (waterline.length / 6)].z;
        Corner a = corner(self, edge), b = corner(self, edge + 1);
        float length = (float) Math.hypot(b.x + b.move()[0] - a.x - a.move()[0], b.y + b.move()[1] - a.y - a.move()[1]);
        float ramp = Math.min(tuning.shoreLip() * BoardGeometry.HEX_SCALE, (before ? 1 - t : t) * length);
        for (int i = 1; i < samples; i++) {
            // Where the steps through a corner slide the samples along the mouth, each counts where it lies.
            Vector3 p = new Vector3(boundary.get(starts[edge] + i));
            float s = along(edge, p);
            if (before ? s <= t + margin : s >= t - margin) { continue; }
            p.z = base - (base - water) * Math.max(0, 1 - Math.abs(s - t) * length / ramp);
            outer.add(p);
            parameters.add(unwrapped + s);
        }
    }

    /** Where p lies along this hex's edge e at its level, as its fraction from the edge's first corner. */
    private float along(int e, Vector3 p) {
        Corner a = corner(self, e), b = corner(self, e + 1);
        float ax = a.x + a.move()[0], ay = a.y + a.move()[1];
        float dx = b.x + b.move()[0] - ax, dy = b.y + b.move()[1] - ay;
        return Math.clamp(((p.x - ax) * dx + (p.y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
    }

    /**
     * The lip at offset (dx, dy) from waterline point w, turned where it would lean back over the waterline on either
     * side of w, from {@code before} to {@code after}, so the shore between them never folds; turned, it stays at most
     * halfway to the boundary.
     */
    private static Vector3 lip(List<Vector3> boundary, Vector3 before, Vector3 w, Vector3 after, float dx, float dy,
          float z) {
        float length = (float) Math.hypot(dx, dy);
        boolean turned = false;
        for (Vector3[] side : new Vector3[][] { { before, w }, { w, after } }) {
            float sx = side[1].x - side[0].x, sy = side[1].y - side[0].y, sl = (float) Math.hypot(sx, sy);
            float lean = sl > 0 ? (sx * dy - sy * dx) / sl + .1f * length : 0;
            if (lean > 0) {
                dx += lean * sy / sl;
                dy -= lean * sx / sl;
                turned = true;
            }
        }
        float turnedLength = (float) Math.hypot(dx, dy);
        if (turned && turnedLength > 0) {
            float scale = Math.min(length, gap(boundary, w, dx / turnedLength, dy / turnedLength) / 2) / turnedLength;
            dx *= scale;
            dy *= scale;
        }
        return new Vector3(w.x + dx, w.y + dy, z);
    }

    /** Distance from p out along (dx, dy) to the closed polygon, or zero where the ray meets none of it. */
    private static float gap(List<Vector3> polygon, Vector3 p, float dx, float dy) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Vector3 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            float ex = b.x - a.x, ey = b.y - a.y, denominator = dx * ey - dy * ex;
            if (Math.abs(denominator) < 1e-9f) { continue; }
            float qx = a.x - p.x, qy = a.y - p.y;
            float s = (qx * ey - qy * ex) / denominator, u = (qx * dy - qy * dx) / denominator;
            if (s > 0 && u >= 0 && u <= 1) { best = Math.min(best, s); }
        }
        return Float.isFinite(best) ? best : 0;
    }

    /**
     * Stitches two open chains that run the same way, sampled at increasing parameters, with upward triangles. Within
     * {@link #LEAD} of the parameter order it goes on along the chain whose next point makes the shorter new diagonal,
     * so each triangle joins points that face each other where the steps through a corner slide the boundary's samples.
     * First check which choices can finish the strip: a locally upward triangle can otherwise close off a concave
     * corner and leave only downward triangles to finish it.
     */
    private static void strip(List<BoardSurface.Face> out, List<Vector3> outer, List<Float> outerParameters,
          List<Vector3> inner, List<Float> innerParameters) {
        boolean[][] reaches = new boolean[outer.size()][inner.size()];
        for (int i = outer.size() - 1; i >= 0; i--) {
            for (int j = inner.size() - 1; j >= 0; j--) {
                reaches[i][j] = i + 1 == outer.size() && j + 1 == inner.size()
                      || i + 1 < outer.size() && reaches[i + 1][j]
                      && upward(inner.get(j), outer.get(i), outer.get(i + 1)) >= 0
                      || j + 1 < inner.size() && reaches[i][j + 1]
                      && upward(inner.get(j), outer.get(i), inner.get(j + 1)) >= 0;
            }
        }
        int i = 0, j = 0;
        while (i + 1 < outer.size() || j + 1 < inner.size()) {
            boolean alongOuter = j + 1 >= inner.size() || i + 1 < outer.size()
                  && (outerParameters.get(i + 1) < innerParameters.get(j + 1) - LEAD
                  || outerParameters.get(i + 1) <= innerParameters.get(j + 1) + LEAD
                  && inner.get(j).dst2(outer.get(i + 1)) <= outer.get(i).dst2(inner.get(j + 1)));
            if (i + 1 < outer.size() && j + 1 < inner.size()) {
                float byOuter = upward(inner.get(j), outer.get(i), outer.get(i + 1));
                float byInner = upward(inner.get(j), outer.get(i), inner.get(j + 1));
                boolean outerValid = byOuter >= 0 && reaches[i + 1][j];
                boolean innerValid = byInner >= 0 && reaches[i][j + 1];
                if (outerValid != innerValid) {
                    alongOuter = outerValid;
                } else if ((alongOuter ? byOuter : byInner) < 0 && (alongOuter ? byInner : byOuter) > 0) {
                    alongOuter = !alongOuter;
                }
            }
            if (alongOuter) {
                addTriangle(out, inner.get(j), outer.get(i), outer.get(i + 1), BoardSurface.Finish.TOP);
                i++;
            } else {
                addTriangle(out, inner.get(j), outer.get(i), inner.get(j + 1), BoardSurface.Finish.TOP);
                j++;
            }
        }
    }

    // ---- Rim formations and fallen rock ----------------------------------------------------------------------

    /**
     * Rock kit placements, each derived from one canonical edge: formations standing on the rim of a drop (few and
     * small up to two levels, larger and more frequent from three) and fallen blocks on the talus and ground below a
     * rise. They belong to the hex whose footprint holds them, so picking and unit support meet them there, and
     * never enter the unit anchor.
     */
    private void rocks(List<BoardSurface.Face> destination, Vector3 anchor) {
        // Special ground art keeps its top clear; very large boards skip the kit.
        if (!detail.rocks() || !self.detailed()) { return; }
        float m = metres(1);
        boolean blocky = tile.surface() != BoardScene.Surface.DIRT;
        boolean paved = tile.surface() == BoardScene.Surface.CONCRETE;
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            boolean rim = edge.upper == self;
            Geology geology = edge.upper == null ? null : BoardRelief.geology.get(edge.upper.family());
            // Concrete rims stay crisp slab edges.
            if (!edge.profiled || !rim && edge.lower != self || rim && geology.cast() > 0) { continue; }
            float top = edge.top(), bottom = edge.bottom(), drop = top - bottom, big = prominence(edge.drop);
            Random random = new Random(edge.a.key * 1_000_003L + edge.b.key * 31 + (rim ? 7 : 13));
            float yaw = (float) Math.atan2(edge.b.y - edge.a.y, edge.b.x - edge.a.x);
            // Earth banks of the soil mantle shed few blocks; rock cliffs many. Concrete walls shed none, but the
            // bedrock under a concrete slab does. A transition's slopes shed as few as banks do, and its wider talus
            // below a cliff gathers more and larger blocks.
            boolean banded = edge.room > 0;
            float firm = lerp(banded ? Math.min(geology.bank(), .35f) : geology.bank(), 1, big)
                  * (1 - geology.cast() * (1 - big));
            float apron = !rim && banded ? 1 + .8f * big : 1;
            // Jointed sandstone breaks away in whole columns: its talus gathers more and larger fallen blocks.
            float fallen = !rim && edge.upper.family() == SAND ? 1.8f : 1;
            int count = Math.round((rim ? lerp(.7f, 4.5f, big) : lerp(1.6f, 5.5f, big)) * firm * apron * fallen
                  * (.5f + random.nextFloat()));
            float t = random.nextFloat();
            for (int i = 0; i < count; i++) {
                // Rocks come in small groups: most follow the previous one closely.
                t = random.nextFloat() < .55f ? t + (random.nextFloat() - .3f) * .16f : random.nextFloat();
                t = .1f + .8f * (t - (float) Math.floor(t));
                // Mostly small pieces, now and then a large block.
                float r = random.nextFloat();
                float size = m * (rim ? lerp(.8f, 2.7f, big)
                      : lerp(.7f, 1.8f, big) * (1 + .3f * (apron - 1)) * (1 + .4f * (fallen - 1))) * (.35f + 1.15f * r * r);
                BoardRocks.Rock rock = BoardRocks.rock(blocky || random.nextFloat() < .3f, random.nextInt(64));
                float length = size * (1f + .7f * random.nextFloat()), width = size * (.55f + .45f * random.nextFloat());
                float height = size * (.35f + .75f * random.nextFloat());
                float turn = yaw + (float) random.nextGaussian() * (rim ? .55f : .9f);
                Vector3 base;
                if (rim) {
                    // Standing on the rim, set back by part of its width, its root inside the caprock.
                    Vector3 p = edgePoint(edge, t, top);
                    float back = width * (.05f + .5f * random.nextFloat());
                    base = new Vector3(p.x - edge.nx * back, p.y - edge.ny * back, top - height * (.35f + .35f * random.nextFloat()));
                    height = Math.min(height, (headroom(tile) * .9f + top - base.z));
                } else if (random.nextFloat() < .6f || paved) {
                    // On the talus apron; paved ground beyond it stays clear.
                    float h = Math.min(.3f * drop, m * 6) * .45f * random.nextFloat();
                    Vector3 p = edgePoint(edge, t, bottom + h);
                    base = new Vector3(p.x + edge.nx * width * .2f, p.y + edge.ny * width * .2f, p.z - height * (.3f + .15f * random.nextFloat()));
                } else {
                    // Rolled out onto the ground beyond the apron: smaller.
                    size *= .6f;
                    length *= .6f;
                    width *= .6f;
                    height *= .6f;
                    Vector3 p = edgePoint(edge, t, bottom);
                    float out = m * (.3f + 2.4f * random.nextFloat());
                    base = new Vector3(p.x + edge.nx * out, p.y + edge.ny * out, 0);
                    base.z = groundHeight(base.x, base.y) - height * (.3f + .15f * random.nextFloat());
                }
                // Clear of the unit anchor, below the picking headroom, inside this hex but for the cliff itself, and
                // on a water hex's bank rather than over its water.
                if (Math.hypot(base.x - anchor.x, base.y - anchor.y) - length < BoardGeometry.WIDTH * .2f
                      || base.z + height > self.level() * BoardGeometry.LEVEL + headroom(tile)
                      || margin(base.x, base.y, e) < Math.max(length, width) * .55f || wet(base.x, base.y)) {
                    continue;
                }
                place(destination, rock, base, turn, length, width, height / rock.height(), Kind.ROCK);
            }
        }
    }

    /**
     * The rock of a fall, of this hex's own ground: blocks straddling the lip of each fall this hex pours over, where
     * the water parts round them, and boulders in the pool at the foot of each fall that pours into it. None on the
     * unit anchor.
     */
    private void fallRocks(List<BoardSurface.Face> destination, Vector3 anchor, Vector3[] crests, int falls,
          int mouths) {
        if (!detail.rocks() || !self.detailed()) { return; }
        float m = metres(1);
        boolean blocky = self.family() != BoardScene.Surface.DIRT.ordinal();
        int perEdge = crests.length / 6;
        List<BoardSurface.Face> bed = destination.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.BED).toList();
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            Random random = new Random(edge.a.key * 1_000_003L + edge.b.key * 31 + 41);
            int n = (e + 1) % 6;
            float ax = cornerX(self.ix() + CORNER_DX[e]), ay = cornerY(self.iy() + CORNER_DY[e]);
            float bx = cornerX(self.ix() + CORNER_DX[n]), by = cornerY(self.iy() + CORNER_DY[n]);
            float length = (float) Math.hypot(bx - ax, by - ay), ox = (by - ay) / length, oy = -(bx - ax) / length;
            Site other = neighbor(self, e);
            boolean lip = (falls & 1 << e) != 0;
            boolean foot = (mouths & 1 << e) != 0 && other != null && other.liquid() && other.level() - self.level() >= 3;
            if (!lip && !foot) { continue; }
            // Two shoulder blocks where the lip meets its banks, a few breakers on its ledge, or boulders in the pool.
            int count = lip ? 3 + random.nextInt(3) : 3 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                float r = random.nextFloat();
                boolean shoulder = lip && i < 2;
                float size = m * (shoulder ? 2f + 1.5f * r : lip ? .7f + 1.6f * r * r : .8f + 2.4f * r * r);
                float along = shoulder ? i : .15f + .7f * random.nextFloat();
                Vector3 base;
                if (lip) {
                    float k = along * perEdge;
                    int j = Math.min(perEdge - 1, (int) k);
                    Vector3 p = new Vector3(crests[e * perEdge + j]).lerp(crests[(e * perEdge + j + 1) % crests.length],
                          k - j);
                    // A shoulder sits on the bank where the lip ends; a breaker on the ledge just behind the lip, the
                    // water parting round it.
                    float back = shoulder ? size * .35f : size * (.2f + .9f * random.nextFloat());
                    float seat = shoulder ? self.level() * BoardGeometry.LEVEL : p.z - BoardGeometry.HEX_SCALE;
                    base = new Vector3(p.x - ox * back, p.y - oy * back, seat + size * .1f);
                } else {
                    // Out in the pool below the fall, on its bed.
                    float out = m * (2.5f + 3 * random.nextFloat());
                    base = new Vector3(ax + (bx - ax) * along - ox * out, ay + (by - ay) * along - oy * out, 0);
                    base.z = BoardSurface.sampleHeight(bed, base.x, base.y, self.level() * BoardGeometry.LEVEL);
                }
                BoardRocks.Rock rock = BoardRocks.rock(blocky || random.nextFloat() < .3f, random.nextInt(64));
                float width = size * (.6f + .4f * random.nextFloat()), height = size * (.5f + .5f * random.nextFloat());
                base.z -= height * .3f;
                if (Math.hypot(base.x - anchor.x, base.y - anchor.y) - size < BoardGeometry.WIDTH * .2f
                      || base.z + height > self.level() * BoardGeometry.LEVEL + headroom(tile)
                      || margin(base.x, base.y, lip ? e : -1) < Math.max(size, width) * .55f) { continue; }
                place(destination, rock, base, (float) Math.atan2(by - ay, bx - ax) + (random.nextFloat() - .5f),
                      size * (1 + .5f * random.nextFloat()), width, height / rock.height(), Kind.ROCK);
            }
        }
    }

    /**
     * Sparse field cover: loose stones and low shrubs on open ground, from the hex's own seed and outside the unit
     * anchor. Hexes with features of their own (woods, buildings, rubble) keep only those.
     */
    private void field(List<BoardSurface.Face> destination, Vector3 anchor) {
        if (!detail.rocks() || !self.detailed() || self.liquid() || !tile.features().isEmpty()) { return; }
        Random random = new Random(tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L ^ 0x5f1e1dL);
        float m = metres(1);
        int stones = Math.round(BoardRelief.geology.get(self.family()).stones() * (.3f + 1.4f * random.nextFloat()));
        int shrubs = Math.round(BoardRelief.geology.get(self.family()).shrubs() * (.3f + 1.4f * random.nextFloat()));
        // Desert bushes (sage, creosote) grow larger and rounder than a meadow's low shrubs.
        boolean desert = self.family() == SAND;
        for (int i = 0; i < stones + shrubs; i++) {
            boolean shrub = i >= stones;
            float angle = random.nextFloat() * (float) Math.PI * 2;
            float radius = BoardGeometry.WIDTH * (.24f + .2f * random.nextFloat());
            float x = anchor.x + radius * (float) Math.cos(angle), y = anchor.y + radius * (float) Math.sin(angle);
            float r = random.nextFloat();
            float size = m * (shrub ? (.8f + .8f * r) * (desert ? 1.5f : 1) : .35f + 1.1f * r * r);
            // Wholly inside this hex and on its own top, so no neighbour's ground or picking ever meets half of it and
            // nothing hangs over a receding rim.
            float keep = size * (shrub ? 1.25f : .8f);
            // Clear of the unit anchor too, as the rock kit keeps it.
            if (margin(x, y, -1) < keep || clearance(x, y) < keep
                  || Math.hypot(x - anchor.x, y - anchor.y) - keep < BoardGeometry.WIDTH * .2f) { continue; }
            float turn = random.nextFloat() * (float) Math.PI * 2;
            // A shrub is a low, open clump of rounded masses; a stone one faceted piece, half buried.
            int parts = shrub ? 3 + random.nextInt(2) : 1;
            for (int part = 0; part < parts; part++) {
                float scale = part == 0 ? .8f : .45f + .35f * random.nextFloat();
                float px = x + (part == 0 ? 0 : (random.nextFloat() - .5f) * size * 1.2f);
                float py = y + (part == 0 ? 0 : (random.nextFloat() - .5f) * size * 1.2f);
                BoardRocks.Rock rock = shrub ? BoardRocks.bush(random.nextInt(64))
                      : BoardRocks.rock(self.family() != BoardScene.Surface.DIRT.ordinal(), random.nextInt(64));
                float height = size * scale * (shrub ? (desert ? .6f : .45f) + .25f * random.nextFloat()
                      : .35f + .5f * random.nextFloat());
                Vector3 base = new Vector3(px, py, groundHeight(px, py) - height * (shrub ? .15f : .35f));
                place(destination, rock, base, turn + part, size * scale, size * scale * (.7f + .3f * random.nextFloat()),
                      height / rock.height(), shrub ? Kind.PLANT : Kind.ROCK);
            }
        }
    }

    /**
     * Planting pits for the trees of paved ground, as in a pavement: a square of loose earth inside a low kerb at each
     * tree's foot, lying on the slab. They are thin dressing: drawn and picked with the ground, but they never raise
     * what stands on it, so the tree and any unit keep the game level.
     */
    private void pits(List<BoardSurface.Face> destination) {
        if (!detail.rocks() || !self.detailed() || tile.surface() != BoardScene.Surface.CONCRETE) { return; }
        float m = metres(1);
        List<BoardScene.Feature> trees = tile.features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).toList();
        // Every pit of a stand has the same size, small enough that no two of them touch.
        float spacing = Float.POSITIVE_INFINITY;
        for (int i = 0; i < trees.size(); i++) {
            for (int j = i + 1; j < trees.size(); j++) {
                spacing = Math.min(spacing, BoardGeometry.HEX_SCALE * Math.max(Math.abs(trees.get(i).x() - trees.get(j).x()),
                      Math.abs(trees.get(i).y() - trees.get(j).y())));
            }
        }
        for (BoardScene.Feature feature : trees) {
            float x = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.HEX_SCALE;
            float y = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.HEX_SCALE;
            // Sized to the drawn crown, whose trunk is wider than life too.
            float outer = Math.min(m * (1 + .45f * feature.scale()), .46f * spacing), inner = outer - m * .25f;
            if (margin(x, y, -1) < outer * 1.45f) { continue; }
            float ground = groundHeight(x, y), kerb = ground + m * .12f, soil = ground + m * .05f;
            float[][] square = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
            for (int k = 0; k < 4; k++) {
                float[] a = square[k], b = square[(k + 1) % 4];
                // The kerb: its top, the face toward the pavement (its foot set into the slab) and the face toward the soil.
                dressing(destination, false, new Vector3(x + a[0] * outer, y + a[1] * outer, kerb),
                      new Vector3(x + b[0] * outer, y + b[1] * outer, kerb), new Vector3(x + b[0] * inner, y + b[1] * inner, kerb),
                      new Vector3(x + a[0] * inner, y + a[1] * inner, kerb));
                dressing(destination, false, new Vector3(x + a[0] * outer, y + a[1] * outer, ground - m * .02f),
                      new Vector3(x + b[0] * outer, y + b[1] * outer, ground - m * .02f),
                      new Vector3(x + b[0] * outer, y + b[1] * outer, kerb), new Vector3(x + a[0] * outer, y + a[1] * outer, kerb));
                dressing(destination, false, new Vector3(x + b[0] * inner, y + b[1] * inner, soil),
                      new Vector3(x + a[0] * inner, y + a[1] * inner, soil),
                      new Vector3(x + a[0] * inner, y + a[1] * inner, kerb), new Vector3(x + b[0] * inner, y + b[1] * inner, kerb));
                // The earth, mounded a little around the trunk.
                dressing(destination, true, new Vector3(x + a[0] * inner, y + a[1] * inner, soil),
                      new Vector3(x + b[0] * inner, y + b[1] * inner, soil), new Vector3(x, y, soil + m * .05f));
            }
        }
    }

    /** One flat face of a tree pit, its earth or its kerb, counter-clockwise from above or outside, own vertices. */
    private void dressing(List<BoardSurface.Face> destination, boolean earth, Vector3... points) {
        Vector3 normal = new Vector3(points[1]).sub(points[0]).crs(new Vector3(points[2]).sub(points[0])).nor();
        float tint = earth ? .45f * hash(Float.floatToIntBits(points[0].x), Float.floatToIntBits(points[0].y)) : 1;
        for (Vector3 p : points) {
            // Only the kerb's foot is shaded by the pavement around it.
            float occlusion = normal.z > .5f || p.z > self.level() * BoardGeometry.LEVEL ? 1 : .8f;
            shades.put(p, new Shade(normal, Kind.PIT, occlusion, self.level(), 99, 99, tint));
        }
        for (int i = 1; i + 1 < points.length; i++) {
            destination.add(new BoardSurface.Face(points[0], points[i], points[i + 1], BoardSurface.Finish.DRESSING));
        }
    }

    /** Average rockiness of the steps through a corner (each pair of its hexes at different levels); else fallback. */
    private static float cornerRock(Corner corner, float fallback) {
        float sum = 0;
        int steps = 0;
        for (int i = 0; i < 3; i++) {
            Site p = corner.around[i], q = corner.around[(i + 1) % 3];
            if (p == null || q == null || p.level() == q.level()) { continue; }
            sum += prominence(p.level() > q.level() ? drop(p, q) : drop(q, p));
            steps++;
        }
        return steps == 0 ? fallback : sum / steps;
    }

    /**
     * The point nearest (x, y) on the way to the unit anchor that lies on this hex's own top at least {@code margin}
     * inside its outline, so trees and ground clutter stand on the ground, never over a receding rim or a step's slope.
     */
    float[] settle(float x, float y, float margin) {
        if (outline == null || clearance(x, y) >= margin) { return new float[] { x, y }; }
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        float inside = 0, outside = 1;
        for (int i = 0; i < 12; i++) {
            float f = (inside + outside) / 2;
            if (clearance(cx + (x - cx) * f, cy + (y - cy) * f) >= margin) {
                inside = f;
            } else {
                outside = f;
            }
        }
        return new float[] { cx + (x - cx) * inside, cy + (y - cy) * inside };
    }

    /** Signed distance from (x, y) to the outline of this hex's top, positive inside; infinite before it is built. */
    private float clearance(float x, float y) {
        if (outline == null) { return Float.POSITIVE_INFINITY; }
        boolean inside = false;
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            Vector3 a = outline.get(i), b = outline.get(j);
            if (a.y > y != b.y > y && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) { inside = !inside; }
            nearest = Math.min(nearest, distance(x, y, a.x, a.y, b.x, b.y));
        }
        return inside ? nearest : -nearest;
    }

    /** Whether (x, y) lies inside a water hex's waterline. */
    private boolean wet(float x, float y) {
        if (waterline == null) { return false; }
        boolean inside = false;
        for (int i = 0, j = waterline.length - 1; i < waterline.length; j = i++) {
            Vector3 a = waterline[i], b = waterline[j];
            if (a.y > y != b.y > y && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) { inside = !inside; }
        }
        return inside;
    }

    /** Distance from (x, y) to this hex's boundary, ignoring edge {@code skip} (-1 for none); negative outside. */
    private float margin(float x, float y, int skip) {
        float result = Float.POSITIVE_INFINITY;
        for (int k = 0; k < 6; k++) {
            if (k == skip) { continue; }
            float ax = cornerX(self.ix() + CORNER_DX[k]), ay = cornerY(self.iy() + CORNER_DY[k]);
            float ex = cornerX(self.ix() + CORNER_DX[(k + 1) % 6]) - ax, ey = cornerY(self.iy() + CORNER_DY[(k + 1) % 6]) - ay;
            result = Math.min(result, (ex * (y - ay) - ey * (x - ax)) / (float) Math.hypot(ex, ey));
        }
        return result;
    }

    /** One rock or shrub mass, scaled and turned about z; flat faces with their own vertices, darker at the root. */
    private void place(List<BoardSurface.Face> destination, BoardRocks.Rock rock, Vector3 base, float turn,
          float sx, float sy, float sz, Kind kind) {
        float c = (float) Math.cos(turn), s = (float) Math.sin(turn), m = metres(1);
        float tint = hash(Float.floatToIntBits(base.x), Float.floatToIntBits(base.y));
        float height = rock.height() * sz;
        if (self.liquid() && kind == Kind.ROCK) {
            // A lip's nominal level and a pool's waterline are not foundations. Extend the closed rock down into
            // the actual bank/bed across its footprint, keeping its visible summit where it was placed.
            List<BoardSurface.Face> ground = destination.stream().filter(face -> face.finish() != BoardSurface.Finish.OUTCROP
                  && face.finish() != BoardSurface.Finish.DRESSING && face.finish() != BoardSurface.Finish.ICE).toList();
            float foundation = Float.POSITIVE_INFINITY;
            for (BoardRocks.Polygon polygon : rock.polygons()) {
                for (Vector3 point : polygon.points()) {
                    float x = base.x + c * point.x * sx - s * point.y * sy;
                    float y = base.y + s * point.x * sx + c * point.y * sy;
                    float groundZ = BoardSurface.sampleHeight(ground, x, y, Float.NaN);
                    if (Float.isFinite(groundZ)) { foundation = Math.min(foundation, groundZ - height * .3f); }
                }
            }
            if (!Float.isFinite(foundation)) { return; }
            foundation = Math.min(base.z, foundation);
            height += base.z - foundation;
            base = new Vector3(base.x, base.y, foundation);
            sz = height / rock.height();
        }
        for (BoardRocks.Polygon polygon : rock.polygons()) {
            Vector3 n = polygon.normal();
            float nx = n.x / sx, ny = n.y / sy;
            Vector3 normal = new Vector3(c * nx - s * ny, s * nx + c * ny, n.z / sz).nor();
            Vector3[] points = new Vector3[polygon.points().length];
            for (int i = 0; i < points.length; i++) {
                Vector3 q = polygon.points()[i];
                float x = q.x * sx, y = q.y * sy, z = q.z * sz;
                points[i] = new Vector3(base.x + c * x - s * y, base.y + s * x + c * y, base.z + z);
                float occlusion = .45f + .55f * smooth(z / (height * .7f));
                Vector3 shading = normal;
                if (kind == Kind.PLANT) {
                    // Foliage reads as a soft mass: each corner leans its facet's normal outward from the mass's heart.
                    float ox = q.x / sx, oy = q.y / sy, oz = (q.z - rock.height() * .35f) / sz;
                    shading = new Vector3(c * ox - s * oy, s * ox + c * oy, oz).nor().scl(1.5f).add(normal).nor();
                }
                shades.put(points[i], new Shade(shading, kind, occlusion, self.level(), z / m, (height - z) / m, tint));
            }
            for (int i = 1; i + 1 < points.length; i++) {
                destination.add(new BoardSurface.Face(points[0], points[i], points[i + 1], BoardSurface.Finish.OUTCROP));
            }
        }
    }

    /**
     * Shades of one edge's boundary samples. A cliff rim gets a normal rounded between the ground and the face below,
     * computed exactly as the cliff grid computes its own top row, which reuses these vertices.
     */
    private void rimShades(int e) {
        Edge edge = edge(e);
        Vector3[] points = rims[e];
        int count = points.length - 1;
        boolean drop = edge.upper == self && edge.profiled;
        Vector3[] below = null;
        if (drop) {
            float[] rows = rows(edge.bottom(), edge.top());
            float next = rows[rows.length - 2];
            below = new Vector3[count + 1];
            for (int i = 0; i <= count; i++) { below[i] = edgePoint(edge, canonical(e, i, count), next); }
        }
        for (int i = 0; i < count; i++) {
            Vector3 p = points[i];
            float[] seam = seamDistances(p.x, p.y);
            Vector3 normal;
            if (drop) {
                normal = gridNormal(edge, e, points, null, below, i, count).add(0, 0, 1.2f).nor();
            } else if (edge.upper == null || joined(self, neighbor(self, e))) {
                normal = groundNormal(p);
            } else {
                normal = new Vector3(0, 0, 1);
            }
            // At the foot of a step with room this ground and the step's lowest row take one occlusion, from the step.
            float occlusion = edge.lower == self && edge.footRoom > 0 ? footOcclusion(edge, p)
                  : groundOcclusion(seam[1]);
            shades.putIfAbsent(p, new Shade(normal, Kind.GROUND, occlusion, self.level(), seam[0], seam[1],
                  cliffTint(p.x, p.y, seam)));
        }
    }

    /** Stitch two closed, counter-clockwise rings sampled at different parameters (edge index plus fraction). */
    /** The vertical component of a triangle's unit normal: 1 lying flat and facing up, negative when folded over. */
    private static float upward(Vector3 a, Vector3 b, Vector3 c) {
        float ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z, vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return nz / Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-12f);
    }

    private static void zipper(List<BoardSurface.Face> out, List<Vector3> outer, List<Float> outerParameters,
          List<Vector3> inner, List<Float> innerParameters) {
        int i = 0, j = 0, n = outer.size(), m = inner.size();
        while (i < n || j < m) {
            float nextOuter = i + 1 < n ? outerParameters.get(i + 1) : 6;
            float nextInner = j + 1 < m ? innerParameters.get(j + 1) : 6;
            if (i < n && (j >= m || nextOuter <= nextInner)) {
                out.add(new BoardSurface.Face(inner.get(j % m), outer.get(i), outer.get((i + 1) % n),
                      BoardSurface.Finish.TOP));
                i++;
            } else {
                out.add(new BoardSurface.Face(inner.get(j % m), outer.get(i % n), inner.get((j + 1) % m),
                      BoardSurface.Finish.TOP));
                j++;
            }
        }
    }

    private Shade groundShade(Vector3 p) {
        float[] seam = seamDistances(p.x, p.y);
        return new Shade(groundNormal(p), Kind.GROUND, groundOcclusion(seam[1]), self.level(), seam[0], seam[1],
              cliffTint(p.x, p.y, seam));
    }

    /**
     * Occlusion of the ground where a step with room meets it at its foot, from that one step, so the step's lowest row
     * and the lower hex's own boundary take exactly the same value there.
     */
    private static float footOcclusion(Edge edge, Vector3 p) {
        float reach = distance(p.x, p.y, edge.a.x, edge.a.y, edge.b.x, edge.b.y)
              - edge.footRoom * prominence(edge.drop);
        return groundOcclusion(Math.max(0, reach) / metres(1));
    }

    /** Ground in front of a taller cliff sees less sky; open ground and rims are unoccluded. */
    private static float groundOcclusion(float footMetres) {
        return 1 - .3f * (float) Math.exp(-footMetres / 3.5f);
    }

    /** Distances in metres to the nearest drop (rim) and rise (cliff foot) at this hex's level. */
    private float[] seamDistances(float x, float y) {
        if (seams == null) { segments(); }
        float m = metres(1);
        return new float[] { Math.clamp(nearest(drops, x, y) / m, 0, 99), Math.clamp(nearest(rises, x, y) / m, 0, 99) };
    }

    /**
     * Ground vertex tint: the height in levels of the nearer of the closest drop and rise, as .3 + .1 per level up to
     * seven. Linear, so it interpolates meaningfully; the shader tells earth banks from rock cliffs by it.
     */
    private float cliffTint(float x, float y, float[] seam) {
        float levels = seam[0] <= seam[1] ? nearestLevels(drops, x, y) : nearestLevels(rises, x, y);
        return .3f + .1f * Math.min(levels, 7);
    }

    // ---- Cliffs -----------------------------------------------------------------------------------------------

    /** Canonical rows of a wall spanning [bottom, top], including both ends. */
    private float[] rows(float bottom, float top) {
        float level = BoardGeometry.LEVEL;
        List<Float> result = new ArrayList<>();
        result.add(bottom);
        int first = (int) Math.floor(bottom / level) - 1, last = (int) Math.ceil(top / level) + 1;
        for (int l = first; l <= last; l++) {
            for (float fraction : detail.rows()) {
                float z = (l + fraction) * level;
                if (z > bottom + EPSILON * level && z < top - EPSILON * level) { result.add(z); }
            }
        }
        result.add(top);
        result.sort(Float::compare);
        float[] array = new float[result.size()];
        for (int i = 0; i < array.length; i++) { array[i] = result.get(i); }
        return array;
    }

    /**
     * Cliff faces for this hex's exposed sides. A sculpted edge whose sides span it at the lower neighbour's level
     * uses the canonical grid; road gates, special seams and the board's plinth keep straight faces.
     */
    List<BoardSurface.Face> walls(List<BoardSurface.Side> sides) {
        List<BoardSurface.Face> result = new ArrayList<>();
        Map<Integer, List<BoardSurface.Side>> byEdge = new TreeMap<>();
        for (BoardSurface.Side side : sides) { byEdge.computeIfAbsent(side.edge(), key -> new ArrayList<>()).add(side); }
        for (var entry : byEdge.entrySet()) {
            int e = entry.getKey();
            Edge edge = sculpted ? edge(e) : null;
            if (edge != null && edge.upper == self && edge.profiled && spans(entry.getValue(), e, edge.bottom())) {
                canonicalWall(e, edge, result);
            } else if (edge != null && edge.upper == self && self.liquid()
                  && (edge.lower == null ? (falls & 1 << e) != 0 : edge.lower.liquid())) {
                for (BoardSurface.Side side : entry.getValue()) { fallWall(side, result); }
            } else {
                for (BoardSurface.Side side : entry.getValue()) { straightWall(side, result); }
            }
        }
        return result;
    }

    /** Whether the sides of one edge, as the shore moves its corners, cover it completely at one foot height. */
    private boolean spans(List<BoardSurface.Side> group, int e, float bottom) {
        float covered = 0;
        for (BoardSurface.Side side : group) {
            if (Math.abs(side.lowA() - bottom) > .01f || Math.abs(side.lowB() - bottom) > .01f) { return false; }
            covered += (float) Math.hypot(side.b().x - side.a().x, side.b().y - side.a().y);
        }
        Edge edge = edge(e);
        float length = (float) Math.hypot(edge.b.x + edge.b.move()[0] - edge.a.x - edge.a.move()[0],
              edge.b.y + edge.b.move()[1] - edge.a.y - edge.a.move()[1]);
        return Math.abs(covered - length) < .01f * length;
    }

    private void straightWall(BoardSurface.Side side, List<BoardSurface.Face> result) {
        int from = result.size();
        // Split at this hex's own boundary samples, so the face shares every vertex of the rim above it.
        Vector3 start = BoardGeometry.corner(tile.coords(), tile.elevation(), side.edge());
        Vector3 end = BoardGeometry.corner(tile.coords(), tile.elevation(), side.edge() + 1);
        float ta = along(side.edge(), side.a()), tb = along(side.edge(), side.b());
        int count = sculpted ? samples(edge(side.edge())) : 1;
        List<Float> cuts = new ArrayList<>(List.of(ta));
        for (int i = 1; i < count; i++) {
            float t = i / (float) count;
            if (t > ta + .0001f && t < tb - .0001f) { cuts.add(t); }
        }
        cuts.add(tb);
        Vector3 previous = rimPoint(side, ta, side.a()), previousLow = new Vector3(previous.x, previous.y, side.lowA());
        for (int i = 1; i < cuts.size(); i++) {
            float t = cuts.get(i), u = (t - ta) / (tb - ta);
            Vector3 top = rimPoint(side, t, i == cuts.size() - 1 ? side.b() : new Vector3(start).lerp(end, t));
            top.z = side.a().z + (side.b().z - side.a().z) * u;
            Vector3 low = new Vector3(top.x, top.y, side.lowA() + (side.lowB() - side.lowA()) * u);
            addQuad(result, previous, previousLow, low, top, BoardSurface.Finish.WALL, side.edge());
            previous = top;
            previousLow = low;
        }
        float rimZ = side.a().z;
        for (int i = from; i < result.size(); i++) {
            BoardSurface.Face face = result.get(i);
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                shades.putIfAbsent(p, new Shade(normal, Kind.CLIFF, 1, prominence(rimZ - Math.min(side.lowA(), side.lowB())),
                      (p.z - Math.min(side.lowA(), side.lowB())) / metres(1), (rimZ - p.z) / metres(1),
                      bed(p, BoardRelief.geology.get(self.family()))));
            }
        }
    }

    /** Rows of every fall wall column, so the columns that neighbouring sides share match. */
    private static final int FALL_ROWS = 8;

    /**
     * A wall under or beside a fall, where two water hexes step down to each other: rock of this hex's ground, its
     * masses and joints relieved like a cliff's, but held to its line at the rim and the foot and fading out toward the
     * corners, so it meets the banks, the crest, the pool below and the walls round the corners without a crack. Under
     * the crest it only recedes, so it stays behind the sheet. Beside the crest its columns are the rim's own samples;
     * under it, points along the crest.
     */
    private void fallWall(BoardSurface.Side side, List<BoardSurface.Face> result) {
        int e = side.edge(), n = (e + 1) % 6;
        float m = metres(1);
        float ax = cornerX(self.ix() + CORNER_DX[e]), ay = cornerY(self.iy() + CORNER_DY[e]);
        float bx = cornerX(self.ix() + CORNER_DX[n]), by = cornerY(self.iy() + CORNER_DY[n]);
        float length = (float) Math.hypot(bx - ax, by - ay), ox = (by - ay) / length, oy = -(bx - ax) / length;
        boolean crest = Math.abs((side.a().x - ax) * ox + (side.a().y - ay) * oy) > .01f * BoardGeometry.HEX_SCALE
              || Math.abs((side.b().x - ax) * ox + (side.b().y - ay) * oy) > .01f * BoardGeometry.HEX_SCALE;
        List<Vector3> tops = new ArrayList<>();
        List<Float> lows = new ArrayList<>();
        if (crest) {
            for (int k = 0; k <= 3; k++) {
                float u = k / 3f;
                tops.add(new Vector3(side.a()).lerp(side.b(), u));
                lows.add(side.lowA() + (side.lowB() - side.lowA()) * u);
            }
        } else {
            // The rim's own samples, as a straight wall splits: the wall meets the bank above at every one.
            Vector3 start = BoardGeometry.corner(tile.coords(), tile.elevation(), e);
            Vector3 end = BoardGeometry.corner(tile.coords(), tile.elevation(), e + 1);
            float ta = along(e, side.a()), tb = along(e, side.b());
            int count = samples(edge(e));
            List<Float> cuts = new ArrayList<>(List.of(ta));
            for (int i = 1; i < count; i++) {
                float t = i / (float) count;
                if (t > ta + .0001f && t < tb - .0001f) { cuts.add(t); }
            }
            cuts.add(tb);
            for (int i = 0; i < cuts.size(); i++) {
                float t = cuts.get(i), u = (t - ta) / (tb - ta);
                Vector3 top = i == 0 ? rimPoint(side, ta, side.a())
                      : rimPoint(side, t, i == cuts.size() - 1 ? side.b() : new Vector3(start).lerp(end, t));
                top.z = side.a().z + (side.b().z - side.a().z) * u;
                tops.add(top);
                lows.add(side.lowA() + (side.lowB() - side.lowA()) * u);
            }
        }
        Geology geology = BoardRelief.geology.get(self.family());
        Vector3[][] grid = new Vector3[tops.size()][FALL_ROWS + 1];
        float[][] depth = new float[tops.size()][FALL_ROWS + 1];
        for (int c = 0; c < tops.size(); c++) {
            Vector3 top = tops.get(c);
            float low = lows.get(c), drop = top.z - low;
            float along = (top.x - ax) * (bx - ax) / length + (top.y - ay) * (by - ay) / length;
            float fade = smooth(Math.min(along, length - along) / (2 * m));
            for (int r = 0; r <= FALL_ROWS; r++) {
                float z = low + drop * r / FALL_ROWS;
                float d = drop > m * .5f ? profile(top.x, top.y, z, low, top.z, drop, geology, true, true, false) : 0;
                // The bank and crest share their end columns. Both must use the same retreat there, otherwise
                // a protruding bank column pulls away from the recessed wall behind the curtain.
                d = ((falls & 1 << e) != 0 ? Math.min(0, d) : d) * fade;
                depth[c][r] = d;
                grid[c][r] = new Vector3(top.x + ox * d, top.y + oy * d, z);
            }
        }
        int columns = tops.size() - 1;
        for (int c = 0; c <= columns; c++) {
            float low = lows.get(c), top = tops.get(c).z;
            for (int r = 0; r <= FALL_ROWS; r++) {
                Vector3 p = grid[c][r];
                Vector3 across = new Vector3(grid[Math.min(columns, c + 1)][r]).sub(grid[Math.max(0, c - 1)][r]);
                Vector3 up = new Vector3(grid[c][Math.min(FALL_ROWS, r + 1)]).sub(grid[c][Math.max(0, r - 1)]);
                Vector3 normal = across.crs(up).nor();
                if (Float.isNaN(normal.x) || normal.len2() < .5f) { normal.set(ox, oy, 0); }
                if (normal.x * ox + normal.y * oy < 0) { normal.scl(-1); }
                // Recesses between standing masses see less of the sky.
                float occlusion = 1 - .35f * smooth(-depth[c][r] / (m * 1.2f));
                shades.put(p, new Shade(normal, Kind.CLIFF, occlusion, 1, (p.z - low) / m, (top - p.z) / m,
                      bed(p, geology)));
            }
        }
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < FALL_ROWS; r++) {
                addQuad(result, grid[c][r + 1], grid[c][r], grid[c + 1][r], grid[c + 1][r + 1],
                      BoardSurface.Finish.WALL, e);
            }
        }
    }

    /** The top of a straight face: this hex's own canonical boundary point where one exists, else the side's point. */
    private Vector3 rimPoint(BoardSurface.Side side, float t, Vector3 fallback) {
        if (!sculpted) { return new Vector3(fallback); }
        int count = samples(edge(side.edge()));
        float scaled = t * count;
        if (Math.abs(scaled - Math.round(scaled)) > .0005f) { return new Vector3(fallback); }
        return edgePoint(edge(side.edge()), canonical(side.edge(), Math.round(scaled), count), fallback.z);
    }

    private void canonicalWall(int e, Edge edge, List<BoardSurface.Face> result) {
        float bottom = edge.bottom(), top = edge.top();
        float[] rows = rows(bottom, top);
        int columns = samples(edge), last = rows.length - 1;
        Vector3[][] grid = new Vector3[rows.length][];
        for (int r = 0; r < rows.length; r++) {
            grid[r] = new Vector3[columns + 1];
            for (int i = 0; i <= columns; i++) {
                // The top row repeats the rim's own coordinates, so the cliff meets the top without a crack while
                // each keeps its own material.
                grid[r][i] = r == last && rims[e] != null ? new Vector3(rims[e][i])
                      : edgePoint(edge, canonical(e, i, columns), rows[r]);
            }
        }
        float m = metres(1);
        Geology geology = BoardRelief.geology.get(edge.upper.family());
        // Bank or rock: this step's own height, blending into the average of every step through each corner, so a
        // cliff that grows from two to three levels turns from earth to rock gradually and both walls agree there.
        float own = prominence(edge.drop), atA = cornerRock(edge.a, own), atB = cornerRock(edge.b, own);
        float[] rock = new float[columns + 1];
        for (int i = 0; i <= columns; i++) {
            float along = canonical(e, i, columns) * edge.length, blend = .3f * edge.length;
            float wa = 1 - smooth(along / blend), wb = 1 - smooth((edge.length - along) / blend);
            rock[i] = own * (1 - wa - wb) + atA * wa + atB * wb;
        }
        // A concrete slab's lower arris stays crisp: the rows on either side of its underside take their normals from
        // their own faces only.
        float underside = geology.cast() > 0 && own > 0 ? top - BoardGeometry.LEVEL : Float.NaN;
        for (int r = 0; r <= last; r++) {
            boolean slabRow = Math.abs(rows[r] - underside) < .005f * BoardGeometry.LEVEL;
            boolean rockRow = r < last && Math.abs(rows[r + 1] - underside) < .005f * BoardGeometry.LEVEL;
            Vector3[] above = r == last ? null : rockRow ? grid[r] : grid[r + 1];
            Vector3[] below = slabRow ? grid[r] : grid[Math.max(0, r - 1)];
            for (int i = 0; i <= columns; i++) {
                Vector3 p = grid[r][i];
                Shade rim = r == last && rims[e] != null ? shades.get(rims[e][i]) : null;
                // Where a step with room meets the ground at its foot, its lowest row faces up and is shaded like the
                // ground there, easing into the step's own shading within a metre and a half, so no seam shows.
                boolean toe = edge.footRoom > 0 && r == 0;
                Vector3 normal = rim != null ? rim.normal() : toe ? new Vector3(0, 0, 1)
                      : gridNormal(edge, e, grid[r], above, below, i, columns);
                float occlusion = wallOcclusion(edge, e, grid, r, i, columns);
                if (edge.footRoom > 0) {
                    occlusion = lerp(footOcclusion(edge, grid[0][i]), occlusion, smooth((rows[r] - bottom) / (1.5f * m)));
                }
                shades.put(p, new Shade(normal, Kind.CLIFF, occlusion, rock[i], (rows[r] - bottom) / m, (top - rows[r]) / m,
                      bed(p, geology)));
            }
        }
        for (int i = 0; i < columns; i++) {
            for (int r = last; r > 0; r--) {
                addQuad(result, grid[r][i], grid[r - 1][i], grid[r - 1][i + 1], grid[r][i + 1],
                      r == last ? BoardSurface.Finish.CAP : BoardSurface.Finish.WALL, e);
            }
        }
    }

    /**
     * Normal of a cliff grid vertex from its neighbours in the grid (above may be null for the top row). The corner
     * columns, which other hexes' cliffs share, use the canonical outward direction of the corner line tilted by the
     * column's own slope.
     */
    private Vector3 gridNormal(Edge edge, int e, Vector3[] row, Vector3[] above, Vector3[] below, int i, int columns) {
        Vector3 up = above == null ? row[i] : above[i], down = below == null ? row[i] : below[i];
        Vector3 vertical = new Vector3(up).sub(down);
        if (vertical.len2() < 1e-8f) { vertical.set(0, 0, 1); }
        Corner end = i == 0 ? corner(self, e) : i == columns ? corner(self, e + 1) : null;
        float[] direction = end == null ? null : cornerDirection(end, row[i].z, above == null && edge.upper == self);
        Vector3 normal;
        if (direction != null) {
            Vector3 tangent = new Vector3(-direction[1], direction[0], 0);
            normal = tangent.crs(vertical).nor();
            if (normal.x * direction[0] + normal.y * direction[1] < 0) { normal.scl(-1); }
        } else {
            Vector3 along = new Vector3(row[Math.min(columns, i + 1)]).sub(row[Math.max(0, i - 1)]);
            normal = along.crs(vertical).nor();
            if (normal.x * edge.nx + normal.y * edge.ny < 0) { normal.scl(-1); }
        }
        if (Float.isNaN(normal.x)) { normal.set(edge.nx, edge.ny, 0); }
        return normal;
    }

    /** Contact at the foot, shelter under the caprock, and recesses between standing masses. */
    private float wallOcclusion(Edge edge, int e, Vector3[][] grid, int r, int i, int columns) {
        float m = metres(1);
        Vector3 p = grid[r][i];
        float h = (p.z - edge.bottom()) / m, d = (edge.top() - p.z) / m;
        // A transition's slope opens out at its toe, but still shades it a little, so the step reads from above; a
        // cliff's foot meets the ground in a sheltered corner.
        float contact = edge.room > 0 ? .25f + .15f * prominence(edge.drop) : .4f;
        float foot = 1 - contact * (float) Math.exp(-h / 1.6f);
        float shelter = 1 - .25f * bump((d - 2.2f) / 1.6f);
        float offset = outward(edge, e, p, i, columns), around = 0;
        int n = 0;
        for (int dr = -2; dr <= 2; dr++) {
            for (int di = -2; di <= 2; di++) {
                int rr = Math.clamp(r + dr, 0, grid.length - 1), ii = Math.clamp(i + di, 0, columns);
                around += outward(edge, e, grid[rr][ii], ii, columns);
                n++;
            }
        }
        float cavity = 1 - .35f * smooth((around / n - offset) / (m * 1.2f));
        return Math.clamp(foot * shelter * cavity, 0, 1);
    }

    /** How far a wall vertex stands out from its edge, leaving out the transition band's even lean. */
    private float outward(Edge edge, int e, Vector3 p, int i, int columns) {
        float t = canonical(e, i, columns);
        float px = edge.a.x + (edge.b.x - edge.a.x) * t, py = edge.a.y + (edge.b.y - edge.a.y) * t;
        float band = edge.room > 0 ? band(edge.upper, edge.lower, p.z) : 0;
        return (p.x - px) * edge.nx + (p.y - py) * edge.ny - band;
    }

    /** A wall's quad, tagged with the edge it stands on. */
    private static void addQuad(List<BoardSurface.Face> out, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          BoardSurface.Finish finish, int edge) {
        addTriangle(out, a, b, c, finish, edge);
        addTriangle(out, a, c, d, finish, edge);
    }

    private static void addTriangle(List<BoardSurface.Face> out, Vector3 a, Vector3 b, Vector3 c,
          BoardSurface.Finish finish) {
        addTriangle(out, a, b, c, finish, -1);
    }

    private static void addTriangle(List<BoardSurface.Face> out, Vector3 a, Vector3 b, Vector3 c,
          BoardSurface.Finish finish, int edge) {
        float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z, acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
        float cx = aby * acz - abz * acy, cy = abz * acx - abx * acz, cz = abx * acy - aby * acx;
        if (cx * cx + cy * cy + cz * cz > 1e-9f) { out.add(new BoardSurface.Face(a, b, c, finish, edge)); }
    }

    // ---- Maths -----------------------------------------------------------------------------------------------

    static float smooth(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float square(float value) { return value * value; }

    private static float distance(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
        float ex = x - ax - dx * t, ey = y - ay - dy * t;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0x00ffffff) / (float) 0x01000000;
    }

    private static int hashInt(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1440662683;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    /** Value noise in [0, 1]. */
    static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float u = x - ix, v = y - iy;
        u = u * u * (3 - 2 * u);
        v = v * v * (3 - 2 * v);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }

    /** 2D gradient noise, roughly in [-1, 1]. */
    static float gradient(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float u = fx * fx * fx * (fx * (fx * 6 - 15) + 10), v = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        float a = grad(hashInt(ix, iy, 0), fx, fy), b = grad(hashInt(ix + 1, iy, 0), fx - 1, fy);
        float c = grad(hashInt(ix, iy + 1, 0), fx, fy - 1), d = grad(hashInt(ix + 1, iy + 1, 0), fx - 1, fy - 1);
        return ((a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v) * 1.4f;
    }

    /** 3D gradient noise, roughly in [-1, 1]. */
    static float gradient(float x, float y, float z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        float u = fx * fx * fx * (fx * (fx * 6 - 15) + 10), v = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        float w = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
        float x00 = lerp(grad(hashInt(ix, iy, iz), fx, fy, fz), grad(hashInt(ix + 1, iy, iz), fx - 1, fy, fz), u);
        float x10 = lerp(grad(hashInt(ix, iy + 1, iz), fx, fy - 1, fz), grad(hashInt(ix + 1, iy + 1, iz), fx - 1, fy - 1, fz), u);
        float x01 = lerp(grad(hashInt(ix, iy, iz + 1), fx, fy, fz - 1), grad(hashInt(ix + 1, iy, iz + 1), fx - 1, fy, fz - 1), u);
        float x11 = lerp(grad(hashInt(ix, iy + 1, iz + 1), fx, fy - 1, fz - 1),
              grad(hashInt(ix + 1, iy + 1, iz + 1), fx - 1, fy - 1, fz - 1), u);
        return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w) * 1.2f;
    }

    private static float grad(int hash, float x, float y) {
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }

    private static float grad(int hash, float x, float y, float z) {
        return switch (hash & 15) {
            case 0, 12 -> x + y;
            case 1, 13 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x + z;
            case 5 -> -x + z;
            case 6 -> x - z;
            case 7 -> -x - z;
            case 8 -> y + z;
            case 9, 14 -> -y + z;
            case 10 -> y - z;
            default -> -y - z;
        };
    }
}
