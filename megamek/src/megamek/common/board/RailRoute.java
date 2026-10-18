/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.game.IGame;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.units.VTOL;

/**
 * The one route model of rails and maglev ({@link Kind}): route markers at hex centres. A marker holds the sides it
 * leaves by ({@link BoardDecoration#connections()}, N=1 ... NW=32) and, only where it differs from the default layout of
 * those sides ({@link #pieces(int)}), the track pieces it lays between them ({@link BoardDecoration#tracks()}). Rails as
 * terrain ({@link Terrains#RAIL} on the ground, {@link Terrains#BRIDGE_RAIL} on a bridge deck, exits = sides) yield
 * derived markers, never stored ({@link #markers}); the legacy conversion lays rails as terrain ({@link #lay}), the
 * editor still stores route markers.
 * A hex may hold several markers of one kind, at different levels (routes crossing over each other), whose sides are
 * disjoint. A side joins the neighbour's marker of the same kind that has the opposite side, so parallel routes in
 * adjacent hexes stay apart and the kinds never join.
 * <p>
 * Every piece is gentle: a straight between opposite sides or a 60-degree arc between sides two apart; no rail vehicle
 * takes the 120-degree turn between adjacent sides, so none is ever laid. A layout is a 9-bit piece mask: bit d (0-2) is
 * the straight from side d to side d + 3, bit 3 + k the arc from side k to side k + 2. A side with no gentle partner (two
 * adjacent sides, or the middle of three) is stranded ({@link #stranded}): it ends in a short stub.
 */
public final class RailRoute {
    /** The two route kinds: one model, one builder, two looks. */
    public enum Kind {
        /** Suspended on its fixed level. */
        MAGLEV("scenery/maglev/route"),
        /** On the ground, following it as roads do, or on a bridge deck. */
        RAIL("scenery/rail/route");

        public final String asset;

        Kind(String asset) { this.asset = asset; }

        /** The kind whose route marker this asset is, or null. */
        public static Kind of(String asset) {
            for (Kind kind : values()) {
                if (kind.asset.equals(asset)) { return kind; }
            }
            return null;
        }

        /** The folder of the kind's pieces (rolling stock, platforms), which ride its route ({@link #rides}). */
        String folder() { return asset.substring(0, asset.lastIndexOf('/') + 1); }
    }

    /** The rail top above the track's carrier plane (the ground or a deck), in model px: rolling stock rides on it. */
    public static final float RAIL_TOP = 1.4f;
    /** The rail kind's buffer stop, which closes every dead end and stranded stub. */
    public static final String BUFFER_STOP = "scenery/rail/buffer-stop";
    /** The maglev's station platform: it rides the route's level but stands beside the track, not on it. */
    public static final String PLATFORM = "scenery/maglev/platform";
    /** The maglev train's gangway: a vehicle that rides between two cars, not a car of its own length. */
    public static final String COUPLER = "scenery/maglev/coupler";
    /** The rail kind's fixed pieces, which stand on the route's support rather than ride the rails. */
    private static final List<String> FIXED = List.of(BUFFER_STOP, "scenery/rail/crossing-signal");
    /** How far a stranded side's stub reaches in from its border: beside two other sides, and beside one. */
    public static final float STUB_SHORT = 13, STUB_LONG = 16;
    /** A buffer stop stands this far before its track's end, so its struts stay on the track (model px). */
    public static final float STOP_DEPTH = 6.5f;
    /** Half the length of the stub a marker without sides draws (model px). */
    public static final float LONE_STUB = 12;
    /** Track pieces per layout: three straights and six arcs. */
    public static final int PIECES = 9;
    /** A course's handles, as a share of each end's distance from the hex centre ({@link #course}). */
    public static final float HANDLE = .35f;

    private RailRoute() { }

    /** The marker's kind, or null when the object is no route marker. */
    public static Kind kind(BoardDecoration object) {
        return object == null || !object.kind().equals("prop") ? null : Kind.of(object.asset());
    }

    public static boolean isRoute(BoardDecoration object) { return kind(object) != null; }

    /** The derived markers' ids: the rail on a hex's ground and the rail on its bridge deck. */
    public static final String GROUND_RAIL = "rail", DECK_RAIL = "bridge-rail";

    /** A hex's markers of {@code kind}: the ones its rail terrain yields ({@link #derived}), then the stored ones in order. */
    public static List<BoardDecoration> markers(Hex hex, Kind kind) {
        if (hex == null) { return List.of(); }
        List<BoardDecoration> result = kind == Kind.RAIL ? derived(hex, "") : new ArrayList<>();
        hex.getDecorations().stream().filter(object -> kind(object) == kind).forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * The markers a hex's rail terrain yields: its intact ground rail ({@link Terrains#RAIL}, id {@code prefix} +
     * {@link #GROUND_RAIL}, on the ground) and its deck rail ({@link Terrains#BRIDGE_RAIL}, id {@code prefix} +
     * {@link #DECK_RAIL}, on the bridge deck). Each leaves by its terrain's exits and lays the junction layout the hex's
     * appearance stores for it ({@link HexAppearance#tracks}) where that is a layout of them, else the default. A
     * destroyed rail yields none.
     */
    public static List<BoardDecoration> derived(Hex hex, String prefix) {
        List<BoardDecoration> result = new ArrayList<>(2);
        derive(hex, Terrains.RAIL, prefix + GROUND_RAIL, "rail", BoardDecoration.Placement.ground(), result);
        derive(hex, Terrains.BRIDGE_RAIL, prefix + DECK_RAIL, "bridgeRail", BoardDecoration.Placement.on("bridge", 0), result);
        return result;
    }

    /**
     * The one writer of a hex's rail: an intact rail with these sides on its ground ({@link Terrains#RAIL}) or on its
     * bridge deck ({@code deck}, {@link Terrains#BRIDGE_RAIL}, whose sides the caller keeps within the bridge's exits),
     * with its junction layout {@code tracks} on the rail's appearance, where it is not the default (0).
     */
    public static void lay(Hex hex, boolean deck, int sides, int tracks) {
        hex.addTerrain(new Terrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL, 1, true, sides));
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        HexAppearance layout = HexAppearance.tracks(tracks);
        if (layout == null) { appearance.remove(deck ? "bridgeRail" : "rail"); }
        else { appearance.put(deck ? "bridgeRail" : "rail", layout); }
        hex.setAppearance(appearance);
    }

    private static void derive(Hex hex, int type, String id, String owner, BoardDecoration.Placement placement,
          List<BoardDecoration> result) {
        Terrain rail = hex.getTerrain(type);
        if (rail == null || rail.getLevel() <= 0) { return; }
        int sides = rail.getExits() & 63, tracks = HexAppearance.tracks(hex.getAppearance(), owner);
        result.add(new BoardDecoration(id, "prop", Kind.RAIL.asset, null, 0, 0, 0, false, 1, placement, 0, false, 0, 0,
              null, false, sides, BoardDecoration.Stretch.NONE, BoardDecoration.Colours.NONE, valid(sides, tracks) ? tracks : 0));
    }

    /** Whether TacOps rails are in play (TO:AR p.49). With the option off, rails are drawn and play as nothing. */
    public static boolean rules(IGame game) {
        return game != null && game.getOptions().booleanOption(OptionsConstants.ADVANCED_TAC_OPS_RAILS);
    }

    /** Whether a rail hinders {@code entity} (TO:AR p.49): 'Meks and ground vehicles; infantry, ProtoMeks and aircraft pass. */
    public static boolean affects(Entity entity) {
        return entity instanceof Mek || entity instanceof Tank && !(entity instanceof VTOL);
    }

    /** Whether the hex holds the rules' rail: an intact {@link Terrains#RAIL} on its ground (a destroyed one is clear). */
    public static boolean intact(Hex hex) {
        return hex != null && hex.terrainLevel(Terrains.RAIL) > 0;
    }

    /** Whether a step from {@code from} into the adjacent {@code to}, leaving by {@code direction}, follows an intact rail. */
    public static boolean along(Hex from, Hex to, int direction) {
        return intact(from) && intact(to) && from.containsTerrainExit(Terrains.RAIL, direction)
              && to.containsTerrainExit(Terrains.RAIL, (direction + 3) % 6);
    }

    /** The hex's first marker of {@code kind}, or null. */
    public static BoardDecoration find(Hex hex, Kind kind) {
        var markers = markers(hex, kind);
        return markers.isEmpty() ? null : markers.getFirst();
    }

    /** The side bit of a {@link Coords#translated(int)} direction. */
    public static int side(int direction) { return 1 << direction; }

    /**
     * True when {@code route} leaves by {@code direction} and {@code neighbour}, a marker of the same kind that way,
     * leaves back.
     */
    public static boolean joined(BoardDecoration route, int direction, BoardDecoration neighbour) {
        return isRoute(route) && kind(route) == kind(neighbour) && (route.connections() & side(direction)) != 0
              && (neighbour.connections() & side((direction + 3) % 6)) != 0;
    }

    /**
     * The marker of {@code route}'s kind on {@code neighbour} (the hex in {@code direction}) that joins it, or null: the
     * one that leaves back by the opposite side, unique because a hex's markers own disjoint sides.
     */
    public static BoardDecoration joined(BoardDecoration route, int direction, Hex neighbour) {
        if (!isRoute(route)) { return null; }
        return markers(neighbour, kind(route)).stream().filter(other -> joined(route, direction, other))
              .findFirst().orElse(null);
    }

    /** A rail marker on a bridge deck, which it rides; any other rail lies on the ground. */
    public static boolean onDeck(BoardDecoration marker) {
        var receiver = marker.placement().receiver();
        return receiver != null && receiver.terrain().equals("bridge");
    }

    /**
     * Whether the hex holds an intact rail on its ground (a RAIL marker that does not ride a deck), which keeps the ground
     * from being clear ({@link Hex#isClear}). Maglev never does.
     */
    public static boolean groundRail(Hex hex) {
        return markers(hex, Kind.RAIL).stream().anyMatch(marker -> !onDeck(marker));
    }

    /** Whether the hex's bridge deck carries a rail ({@link Hex#isClearDeck}). */
    public static boolean deckRail(Hex hex) {
        return markers(hex, Kind.RAIL).stream().anyMatch(RailRoute::onDeck);
    }

    /**
     * Whether a rail with these sides may ride a bridge with these exits: one of its sides runs along the deck. A deck
     * carries track only along itself ({@link #drawnSides}), so the conversion and the editor put a rail on a deck only
     * then; any other rail on a bridge hex, one crossing under it, lies beneath.
     */
    public static boolean alongDeck(int sides, int bridgeExits) { return (sides & bridgeExits & 63) != 0; }

    /** The exits of the hex's bridge, 0 without one. */
    public static int bridgeExits(Hex hex) {
        var bridge = hex.getTerrain(Terrains.BRIDGE);
        return bridge == null ? 0 : bridge.getExits();
    }

    /**
     * The sides of the hex's rails that lie on the ground, a destroyed rail's too: the road engine shapes and clears the
     * ground for them as for a road with those exits.
     */
    public static int groundSides(Hex hex) {
        Terrain rail = hex == null ? null : hex.getTerrain(Terrains.RAIL);
        int result = rail == null ? 0 : rail.getExits() & 63;
        for (var marker : markers(hex, Kind.RAIL)) {
            if (!onDeck(marker)) { result |= marker.connections(); }
        }
        return result;
    }

    /**
     * The sides a marker draws: all of them, but on a deck only those that are exits of the bridge
     * ({@code bridgeExits}), since a deck carries track only along itself.
     */
    public static int drawnSides(BoardDecoration marker, int bridgeExits) {
        return kind(marker) == Kind.RAIL && onDeck(marker) ? marker.connections() & bridgeExits : marker.connections();
    }

    /** {@link #drawnSides(BoardDecoration, int)} of a marker on {@code hex}, from its bridge's exits. */
    public static int drawnSides(BoardDecoration marker, Hex hex) { return drawnSides(marker, bridgeExits(hex)); }

    /**
     * The kind whose route an object of {@code asset} rides (its rolling stock, the maglev's platform), or null: maglev
     * pieces at the route's own placement, their ride height being in their meshes; rail vehicles on the rail top.
     */
    public static Kind rides(String asset) {
        for (Kind kind : Kind.values()) {
            if (asset.startsWith(kind.folder()) && !asset.equals(kind.asset) && !FIXED.contains(asset)) { return kind; }
        }
        return null;
    }

    /**
     * Where a piece riding {@code marker} stands: at the marker's own placement. A maglev piece's ride height is in its
     * mesh. A rail rider ({@link #railRider}) has its origin at rail-top height: the renderer stands it on the rail top
     * the track draws over that support, in model px as the track (on ballast, or set into a road), never as a stored
     * offset in levels.
     */
    public static BoardDecoration.Placement ride(BoardDecoration marker) { return marker.placement(); }

    /** Whether an object of {@code asset} stands on a rail's rail top: rail rolling stock and the buffer stop. */
    public static boolean railRider(String asset) { return rides(asset) == Kind.RAIL || asset.equals(BUFFER_STOP); }

    // ---------------------------------------------------------------- pieces

    /** The two sides a piece joins, as a side mask. */
    public static int pieceSides(int piece) {
        return piece < 3 ? side(piece) | side(piece + 3) : side(piece - 3) | side((piece - 1) % 6);
    }

    /** The piece between two sides, or -1 for sides that are no gentle pair (the same side, or adjacent sides). */
    public static int piece(int from, int to) {
        int apart = Math.floorMod(to - from, 6);
        if (apart == 3) { return Math.min(from, to); }
        if (apart == 2) { return 3 + from; }
        if (apart == 4) { return 3 + to; }
        return -1;
    }

    /** Whether the piece is a straight (else a 60-degree arc). */
    public static boolean straight(int piece) { return piece < 3; }

    private static int distance(int a, int b) {
        int apart = Math.floorMod(a - b, 6);
        return Math.min(apart, 6 - apart);
    }

    /** The sides of a mask of two or more that have no gentle partner: each ends in a stub. */
    public static int stranded(int sides) {
        sides &= 63;
        if (Integer.bitCount(sides) < 2) { return 0; }
        int result = 0;
        for (int d = 0; d < 6; d++) {
            int partners = side((d + 2) % 6) | side((d + 3) % 6) | side((d + 4) % 6);
            if ((sides & side(d)) != 0 && (sides & partners) == 0) { result |= side(d); }
        }
        return result;
    }

    /**
     * The default layout of {@code sides}, which reproduces every legacy rail sprite's gentle pieces: each opposite
     * pair a straight; every other side turns out onto each through line it can reach, else onto each side two apart; a
     * Y without a through line (N, SE, SW or NE, S, NW) has its stem on the N or S side, as the legacy art.
     */
    public static int pieces(int sides) {
        sides &= 63;
        if (sides == 21 || sides == 42) { return stem(sides, (sides & side(0)) != 0 ? 0 : 3); }
        int straights = 0, on = 0;
        for (int d = 0; d < 3; d++) {
            int pair = side(d) | side(d + 3);
            if ((sides & pair) == pair) { straights |= 1 << d; on |= pair; }
        }
        int result = straights;
        for (int d = 0; d < 6; d++) {
            if ((sides & side(d)) == 0 || (on & side(d)) != 0) { continue; }
            int reach = sides & (side((d + 2) % 6) | side((d + 4) % 6));
            int onto = (reach & on) != 0 ? reach & on : reach;
            for (int e = 0; e < 6; e++) {
                if ((onto & side(e)) != 0) { result |= 1 << piece(d, e); }
            }
        }
        return result;
    }

    /** A Y without a through line, from {@code stem} to the other two sides. */
    private static int stem(int sides, int stem) {
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if (d != stem && (sides & side(d)) != 0) { result |= 1 << piece(stem, d); }
        }
        return result;
    }

    /**
     * Whether {@code tracks} is a layout of {@code sides}: none (0, the default), or pieces that each join two of the
     * sides and together reach every side that has a gentle partner.
     */
    public static boolean valid(int sides, int tracks) {
        if (tracks == 0) { return true; }
        if (tracks < 0 || tracks >= 1 << PIECES) { return false; }
        int covered = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((tracks & 1 << piece) == 0) { continue; }
            if ((pieceSides(piece) & ~sides) != 0) { return false; }
            covered |= pieceSides(piece);
        }
        int reachable = Integer.bitCount(sides & 63) < 2 ? 0 : sides & 63 & ~stranded(sides);
        return (reachable & ~covered) == 0;
    }

    /** The pieces {@code marker} lays on its drawn {@code sides}: its stored layout where valid, else the default. */
    public static int pieces(BoardDecoration marker, int sides) {
        return marker.tracks() != 0 && valid(sides, marker.tracks()) ? marker.tracks() : pieces(sides);
    }

    /**
     * The layout to store ({@link BoardDecoration#tracks}) for {@code sides} that keeps the pieces of {@code kept} whose
     * sides it has (what a hex laid before, and what a stroke drew through it): none (0) when the default lays them all,
     * else those pieces plus each default piece that reaches a side they leave uncovered.
     */
    public static int layout(int sides, int kept) {
        sides &= 63;
        int fallback = pieces(sides), result = 0, covered = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((kept & 1 << piece) != 0 && (pieceSides(piece) & ~sides) == 0) { result |= 1 << piece; covered |= pieceSides(piece); }
        }
        if ((fallback & result) == result) { return 0; }
        for (int piece = 0; piece < PIECES; piece++) {
            if ((fallback & 1 << piece) != 0 && (pieceSides(piece) & ~covered) != 0) { result |= 1 << piece; }
        }
        return result;
    }

    /** The marker of {@code kind} on {@code hex} other than {@code except} that owns side {@code direction}, or null. */
    public static BoardDecoration owner(Hex hex, Kind kind, int direction, BoardDecoration except) {
        return markers(hex, kind).stream().filter(marker -> (except == null || !marker.id().equals(except.id()))
              && (marker.connections() & side(direction)) != 0).findFirst().orElse(null);
    }

    /** A marker of a connected run and the hex that holds it. */
    public record Member(Coords at, BoardDecoration marker) { }

    /**
     * The connected run of {@code marker} on {@code at}: itself first, then every marker of its kind joined to it,
     * directly or through others ({@link #joined(BoardDecoration, int, Hex)}). One hex may hold two members, where a
     * route passes over itself at another level. A maglev's Pillars toggle applies to its whole run, as a bridge's to its
     * whole span.
     */
    public static List<Member> run(java.util.function.Function<Coords, Hex> board, Coords at, BoardDecoration marker) {
        List<Member> result = new ArrayList<>(List.of(new Member(at, marker)));
        java.util.Set<Member> found = new java.util.HashSet<>(result);
        for (int index = 0; index < result.size(); index++) {
            Member member = result.get(index);
            for (int d = 0; d < 6; d++) {
                Coords next = member.at().translated(d);
                BoardDecoration other = (member.marker().connections() & side(d)) == 0 ? null
                      : joined(member.marker(), d, board.apply(next));
                if (other != null && found.add(new Member(next, other))) { result.add(new Member(next, other)); }
            }
        }
        return result;
    }

    /**
     * A sensible layout of a junction's sides ({@link #junctions}): its tracks and its descriptive name, a message key
     * suffix ({@code BoardEditor.junction.<name>}) that names the side {@code side} where it has one (-1: none).
     */
    public record Junction(int tracks, String name, int side) { }

    /** The tracks of {@link #junctions}, the default first. */
    public static List<Integer> variants(int sides) { return junctions(sides).stream().map(Junction::tracks).toList(); }

    /**
     * The sensible layouts of {@code sides}, the default first, each named: for a Y without a through line its three
     * stems ("Y from N") and the wye triangle ("Wye"); for a crossing (two or three straights) the plain crossing and
     * all slips ("Double slip" for two lines); for a crossing with a fifth side both turnouts, its turnout into one line
     * or the other ("Turnout to SE"), and all curves; for a straight with two more sides two apart both turnouts, the
     * curve crossing the straight, and all curves. Every other mask has one layout, the default.
     */
    public static List<Junction> junctions(int sides) {
        sides &= 63;
        List<Junction> result = new ArrayList<>();
        int count = Integer.bitCount(sides), straights = 0, on = 0, gentle = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((pieceSides(piece) & ~sides) != 0) { continue; }
            gentle |= 1 << piece;
            if (straight(piece)) { straights |= 1 << piece; on |= pieceSides(piece); }
        }
        int lines = Integer.bitCount(straights), extras = sides & ~on;
        int a = Integer.numberOfTrailingZeros(extras), b = 31 - Integer.numberOfLeadingZeros(extras);
        if (sides == 21 || sides == 42) {
            // The default's stem is on the N or S side, as the legacy art.
            add(result, new Junction(pieces(sides), "yFrom", (sides & side(0)) != 0 ? 0 : 3));
            for (int d = 0; d < 6; d++) {
                if ((sides & side(d)) != 0) { add(result, new Junction(stem(sides, d), "yFrom", d)); }
            }
            add(result, new Junction(gentle, "wye", -1));
        } else if (lines >= 2 && (count == 4 || count == 6)) {
            add(result, new Junction(pieces(sides), "crossing", -1));
            add(result, new Junction(gentle, lines == 2 ? "doubleSlip" : "allCurves", -1));
        } else if (lines == 2 && count == 5) {
            // The fifth side is the branch.
            add(result, new Junction(pieces(sides), "bothTurnouts", -1));
            for (int line = 0; line < 3; line++) {
                if ((straights & 1 << line) == 0) { continue; }
                int target = distance(line, a) == 2 ? line : line + 3;
                add(result, new Junction(straights | 1 << piece(a, target), "turnoutTo", target));
            }
            add(result, new Junction(gentle, "allCurves", -1));
        } else if (lines == 1 && count == 4 && distance(a, b) == 2) {
            // Both extra sides turn onto the line toward the same side of it.
            add(result, new Junction(pieces(sides), "turnoutsTo", (on & side((a + 2) % 6)) != 0 ? (a + 2) % 6 : (a + 4) % 6));
            add(result, new Junction(straights | 1 << piece(a, b), "crossingCurve", -1));
            add(result, new Junction(gentle, "allCurves", -1));
        } else {
            result.add(new Junction(pieces(sides), "default", -1));
        }
        return List.copyOf(result);
    }

    /** Adds {@code layout} unless its tracks are listed already. */
    private static void add(List<Junction> layouts, Junction layout) {
        if (layouts.stream().noneMatch(listed -> listed.tracks() == layout.tracks())) { layouts.add(layout); }
    }

    /** A rail appearance's junction layout ({@link HexAppearance#tracks}) reflected as a board flip reflects its exits. */
    public static HexAppearance flip(HexAppearance look, boolean horizontal, boolean vertical) {
        return look.tracks() == null ? look
              : HexAppearance.tracks(flip(look.tracks(), sides -> BoardDecoration.flipSides(sides, horizontal, vertical)));
    }

    /** {@code tracks} reflected as {@link BoardDecoration#flip} reflects the sides ({@code flipSides}). */
    static int flip(int tracks, java.util.function.IntUnaryOperator flipSides) {
        int result = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((tracks & 1 << piece) == 0) { continue; }
            int sides = flipSides.applyAsInt(pieceSides(piece));
            result |= 1 << piece(Integer.numberOfTrailingZeros(sides), 31 - Integer.numberOfLeadingZeros(sides));
        }
        return result;
    }

    // ---------------------------------------------------------------- geometry shared by the builder and its stops

    /** A buffer stop on a rail: where it stands in its hex (model px, +y north) and the turn that faces its buffers. */
    public record Stop(float x, float y, float rotation) { }

    /** The midpoint of the border in {@code direction}, from the hex centre, in model px (+y north). */
    public static float[] border(int direction) {
        Coords origin = new Coords(0, 0), next = origin.translated(direction);
        return new float[] { (HexTile.centerX(next, HexTile.WIDTH) - HexTile.centerX(origin, HexTile.WIDTH)) / 2,
              (HexTile.centerY(next, HexTile.HEIGHT) - HexTile.centerY(origin, HexTile.HEIGHT)) / 2 };
    }

    /** How far the stub of a stranded side reaches in from its border. */
    public static float stub(int sides) { return Integer.bitCount(sides & 63) == 3 ? STUB_SHORT : STUB_LONG; }

    /**
     * The buffer stops of a rail with these drawn sides: one where a single side's track ends at the centre, one at the
     * end of each stranded side's stub, and one at each end of the stub a marker without sides draws. Each faces the
     * track it closes and stands {@link #STOP_DEPTH} before the track's end.
     */
    public static List<Stop> stops(int sides) {
        sides &= 63;
        List<Stop> result = new ArrayList<>(2);
        if (sides == 0) {
            result.add(new Stop(0, LONE_STUB - STOP_DEPTH, 180));
            result.add(new Stop(0, STOP_DEPTH - LONE_STUB, 0));
            return result;
        }
        boolean deadEnd = Integer.bitCount(sides) == 1;
        int ends = deadEnd ? sides : stranded(sides);
        for (int d = 0; d < 6; d++) {
            if ((ends & side(d)) == 0) { continue; }
            float[] edge = border(d);
            float length = (float) Math.hypot(edge[0], edge[1]);
            // From the border inwards: to the centre for a dead end, to the stub's end for a stranded side.
            float t = 1 - ((deadEnd ? length : stub(sides)) - STOP_DEPTH) / length;
            // The buffers (the model's +y) face the border the track comes from.
            result.add(new Stop(edge[0] * t, edge[1] * t, (float) Math.toDegrees(Math.atan2(-edge[0], edge[1]))));
        }
        return result;
    }

    /**
     * The point at {@code t} (0 to 1) of the course from end {@code a} to end {@code b} (model px from the hex centre): a
     * cubic whose handles ({@link #HANDLE}) leave each end along the line through the hex centre, so it continues the
     * neighbour's course squarely. Between opposite sides it is the straight; between sides two apart it stays within
     * half a pixel of the 60-degree arc.
     */
    public static float[] course(float ax, float ay, float bx, float by, float t) {
        float u = 1 - t, wa = u * u * u + 3 * u * u * t * HANDLE, wb = 3 * u * t * t * HANDLE + t * t * t;
        return new float[] { ax * wa + bx * wb, ay * wa + by * wb };
    }

    /**
     * One centre line of a track in plan, model px from the hex centre (+y north): the course ({@link #course}) from
     * {@code a} to {@code b}, each end on the border of a side ({@code from}, {@code to}) or, -1, inside the hex.
     */
    public record Line(int from, int to, float[] a, float[] b) { }

    /**
     * The centre lines of a track with these drawn {@code sides} and {@code pieces}, the one composition the builder
     * lays and rolling stock rides: each piece's course between its sides, each stranded side's stub from its border, a
     * single side's run from the centre, or the short N-S stub of a marker without sides.
     */
    public static List<Line> lines(int sides, int pieces) {
        sides &= 63;
        if (sides == 0) { return List.of(new Line(-1, -1, new float[] { 0, -LONE_STUB }, new float[] { 0, LONE_STUB })); }
        if (Integer.bitCount(sides) == 1) {
            int d = Integer.numberOfTrailingZeros(sides);
            return List.of(new Line(-1, d, new float[] { 0, 0 }, border(d)));
        }
        List<Line> result = new ArrayList<>();
        for (int piece = 0; piece < PIECES; piece++) {
            if ((pieces & 1 << piece) == 0) { continue; }
            int pair = pieceSides(piece), a = Integer.numberOfTrailingZeros(pair), b = 31 - Integer.numberOfLeadingZeros(pair);
            result.add(new Line(a, b, border(a), border(b)));
        }
        int stranded = stranded(sides);
        for (int d = 0; d < 6; d++) {
            if ((stranded & side(d)) == 0) { continue; }
            float[] edge = border(d);
            float share = 1 - stub(sides) / (float) Math.hypot(edge[0], edge[1]);
            result.add(new Line(d, -1, edge, new float[] { edge[0] * share, edge[1] * share }));
        }
        return List.copyOf(result);
    }

    // ---------------------------------------------------------------- what rides the track

    /** A point on a route's track, model px from its hex's centre (+y north), heading as an object's rotation there. */
    public record Spot(float x, float y, float rotation) { }

    /** Whether an object of {@code asset} is a vehicle that rides the track's centre line (a platform stands beside it). */
    public static boolean vehicle(String asset) { return rides(asset) != null && !asset.equals(PLATFORM); }

    /** One centre line of a marker's track, sampled: its points, and the side each end lies on (-1: inside the hex). */
    private record Track(List<float[]> points, int from, int to) { }

    /** Points per piece's course: a 3 px step at most along a 62 px arc. */
    private static final int SAMPLES = 24;

    /** The centre lines ({@link #lines(int, int)}) of a marker's track on its drawn {@code sides}, a piece's course sampled. */
    private static List<Track> tracks(BoardDecoration marker, int sides) {
        List<Track> result = new ArrayList<>();
        for (Line line : lines(sides, pieces(marker, sides & 63))) {
            int samples = line.from() >= 0 && line.to() >= 0 ? SAMPLES : 1;
            List<float[]> points = new ArrayList<>(samples + 1);
            for (int i = 0; i <= samples; i++) {
                points.add(course(line.a()[0], line.a()[1], line.b()[0], line.b()[1], (float) i / samples));
            }
            result.add(new Track(points, line.from(), line.to()));
        }
        return result;
    }

    /** A place on a track: the track, the segment from point {@code index}, and how far along it (0 to 1). */
    private record Place(Track line, int index, float share) {
        float[] point() {
            float[] a = line.points.get(index), b = line.points.get(index + 1);
            return new float[] { a[0] + (b[0] - a[0]) * share, a[1] + (b[1] - a[1]) * share };
        }

        /** The segment's direction, along the line from its first end to its second. */
        float[] direction() {
            float[] a = line.points.get(index), b = line.points.get(index + 1);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            return new float[] { (b[0] - a[0]) / length, (b[1] - a[1]) / length };
        }
    }

    /** The place of the marker's track nearest (x, y). */
    private static Place nearest(List<Track> tracks, float x, float y) {
        Place best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Track line : tracks) {
            for (int i = 0; i < line.points.size() - 1; i++) {
                float[] a = line.points.get(i), b = line.points.get(i + 1);
                float dx = b[0] - a[0], dy = b[1] - a[1];
                float share = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy)));
                double gap = Math.hypot(a[0] + dx * share - x, a[1] + dy * share - y);
                if (gap < distance) { distance = gap; best = new Place(line, i, share); }
            }
        }
        return best;
    }

    /** An object's rotation that heads its +y along {@code direction}. */
    private static float heading(float[] direction) {
        return (float) Math.toDegrees(Math.atan2(-direction[0], direction[1]));
    }

    /** A rotation in [0, 360). */
    private static float normal(float rotation) { return (rotation % 360 + 360) % 360; }

    /** How far apart two rotations are, in degrees (0 to 180). */
    private static float turn(float a, float b) { return Math.abs(((a - b) % 360 + 540) % 360 - 180); }

    /**
     * The point of {@code marker}'s track on {@code hex} nearest (x, y) (model px from the hex centre), and of the
     * track's two headings there the one nearer {@code rotation}: where a vehicle placed there stands.
     */
    public static Spot snap(Hex hex, BoardDecoration marker, float x, float y, float rotation) {
        Place place = nearest(tracks(marker, drawnSides(marker, hex)), x, y);
        float[] point = place.point();
        float along = heading(place.direction());
        return new Spot(point[0], point[1], normal(turn(along, rotation) <= 90 ? along : along + 180));
    }

    /**
     * Where {@link #follow} arrives: the hex and its marker whose track the spot lies on, and the spot in that hex's
     * frame. A vehicle standing there rides that marker ({@link #ride}) and belongs to that hex. {@code beyond} is how
     * far (model px) the spot lies past the track's end, on along its last heading; 0 on the track.
     */
    public record OnTrack(Coords at, BoardDecoration marker, Spot spot, float beyond) { }

    /**
     * The spot {@code distance} px along the track from {@code start} (on {@code marker} at {@code at}), the way it heads
     * (negative: behind it). Across a joined side it runs on along the neighbour's track, on the line that leaves the
     * opposite side, a straight before a curve; where the track ends it runs on along its last heading (by
     * {@link OnTrack#beyond}), still on the last marker it reached.
     */
    public static OnTrack follow(java.util.function.Function<Coords, Hex> board, Coords at, BoardDecoration marker,
          Spot start, float distance) {
        Place place = nearest(tracks(marker, drawnSides(marker, board.apply(at))), start.x(), start.y());
        float[] point = place.point(), direction = place.direction();
        // Forward along the line where it heads with the start, and the other way for a negative distance.
        boolean forward = turn(heading(direction), start.rotation()) <= 90 == distance >= 0;
        if (!forward) { direction = new float[] { -direction[0], -direction[1] }; }
        float left = Math.abs(distance);
        Track line = place.line();
        int index = place.index();
        Coords here = at;
        BoardDecoration current = marker;
        float beyond = 0;
        while (true) {
            float[] next = line.points.get(forward ? index + 1 : index);
            float step = (float) Math.hypot(next[0] - point[0], next[1] - point[1]);
            if (step > 0) { direction = new float[] { (next[0] - point[0]) / step, (next[1] - point[1]) / step }; }
            if (left <= step) {
                point = new float[] { point[0] + direction[0] * left, point[1] + direction[1] * left };
                break;
            }
            left -= step;
            point = next;
            index += forward ? 1 : -1;
            if (index >= 0 && index < line.points.size() - 1) { continue; }
            // The line's end: across a joined side onto the neighbour's track, else on along the last heading.
            int side = forward ? line.to() : line.from();
            Coords neighbour = side < 0 ? null : here.translated(side);
            Hex hex = neighbour == null ? null : board.apply(neighbour);
            BoardDecoration joined = hex == null ? null : joined(current, side, hex);
            Track onward = joined == null ? null : onward(tracks(joined, drawnSides(joined, hex)), (side + 3) % 6);
            if (onward == null) {
                point = new float[] { point[0] + direction[0] * left, point[1] + direction[1] * left };
                beyond = left;
                break;
            }
            here = neighbour; current = joined; line = onward;
            forward = onward.from() == (side + 3) % 6;
            index = forward ? 0 : line.points.size() - 1;
            point = line.points.get(index);
        }
        float rotation = heading(direction);
        return new OnTrack(here, current, new Spot(point[0], point[1], normal(distance >= 0 ? rotation : rotation + 180)),
              beyond);
    }

    /**
     * The line that leaves {@code side}: the straight through it before a curve, a curve before a run that ends inside
     * the hex (a dead end's run to its buffer stop, a stranded side's stub), else none.
     */
    private static Track onward(List<Track> tracks, int side) {
        Track result = null;
        for (Track line : tracks) {
            if (line.from() != side && line.to() != side) { continue; }
            int other = line.from() == side ? line.to() : line.from();
            if (other == (side + 3) % 6) { return line; }
            if (result == null || other >= 0 && (result.from() < 0 || result.to() < 0)) { result = line; }
        }
        return result;
    }
}
