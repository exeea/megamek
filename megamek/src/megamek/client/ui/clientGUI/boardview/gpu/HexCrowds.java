/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * The resting troop figures of a hex shared with other units, packed together as one crowd whatever formation they
 * belong to, around the other units standing there. Formations register their figures each frame; the view packs the
 * hexes after all of them and formations walk to the spots on the next frame. Render thread only.
 */
final class HexCrowds {
    /** The spiral search for a free spot: its spacing in world units, and its candidates in the hex and beyond. */
    private static final float SEARCH_STEP = 1.5f;
    private static final int INSIDE_CANDIDATES = 1500;
    private static final int SPILL_CANDIDATES = 6000;
    private static final float GOLDEN_ANGLE = 2.399963f;

    /**
     * A resting figure, in world units: where its own formation would stand it and its ground there, centred on it.
     *
     * @param room the world units steps take from each edge of the hex, as the formation fits its own layout
     */
    record Figure(int unit, String member, Vector2 preferred, Polygon ground, boolean vehicle, float[] room) { }

    private final Map<Coords, Map<String, Figure>> registered = new HashMap<>();
    private final Map<Coords, float[]> packedInputs = new HashMap<>();
    private final Map<String, Vector2> spots = new HashMap<>();

    /** A figure posed twice in one frame, as when a movement completes, counts once with its latest pose. */
    void register(Coords hex, Figure figure) {
        registered.computeIfAbsent(hex, ignored -> new LinkedHashMap<>()).put(figure.unit() + ":" + figure.member(), figure);
    }

    /** @return the figure's packed spot from the last frame, in world units, or {@code null} to keep its own */
    Vector2 spot(int unit, String member) {
        return spots.get(unit + ":" + member);
    }

    /**
     * Once per frame, after every formation registered: pack the hexes whose troops share them with other units.
     *
     * @param occupied the ground of the units other than troops, by the hexes they occupy, which the crowd keeps clear
     */
    void pack(Map<Coords, List<Polygon>> occupied) {
        Map<String, Vector2> previous = new HashMap<>(spots);
        spots.clear();
        packedInputs.keySet().retainAll(registered.keySet());
        registered.forEach((hex, members) -> {
            var figures = List.copyOf(members.values());
            var obstacles = occupied.getOrDefault(hex, List.of());
            if (obstacles.isEmpty() && figures.stream().map(Figure::unit).distinct().count() < 2) {
                packedInputs.remove(hex);
                return;
            }
            float[] inputs = inputs(figures, obstacles);
            if (Arrays.equals(inputs, packedInputs.get(hex))) {
                figures.forEach(figure -> {
                    String key = figure.unit() + ":" + figure.member();
                    if (previous.containsKey(key)) { spots.put(key, previous.get(key)); }
                });
                return;
            }
            packedInputs.put(hex, inputs);
            pack(hex, figures, obstacles);
        });
        registered.clear();
    }

    private void pack(Coords hex, List<Figure> figures, List<Polygon> obstacles) {
        Vector3 center = BoardGeometry.center(hex, 0);
        // Vehicles first, then the figures nearest the centre, mixing the formations; obstacles keep their ground.
        var order = new ArrayList<>(figures);
        order.sort(Comparator.comparing((Figure figure) -> !figure.vehicle())
              .thenComparingDouble(figure -> figure.preferred().dst2(center.x, center.y))
              .thenComparingInt(Figure::unit).thenComparing(Figure::member));
        List<Polygon> placed = new ArrayList<>(obstacles);
        for (Figure figure : order) {
            Vector2 spot = search(figure, center, placed, true);
            if (spot == null) { spot = search(figure, center, placed, false); }
            if (spot == null) { spot = figure.preferred(); }
            var ground = new Polygon(figure.ground().getVertices());
            ground.setPosition(spot.x, spot.y);
            placed.add(ground);
            spots.put(figure.unit() + ":" + figure.member(), spot);
        }
    }

    /** The free spot nearest the figure's own, spiralling out; first within the hex's level ground, then beyond. */
    private static Vector2 search(Figure figure, Vector3 center, List<Polygon> placed, boolean inside) {
        var ground = new Polygon(figure.ground().getVertices());
        var relative = new Vector3();
        int candidates = inside ? INSIDE_CANDIDATES : SPILL_CANDIDATES;
        for (int candidate = 0; candidate < candidates; candidate++) {
            float radius = SEARCH_STEP * (float) Math.sqrt(candidate);
            float x = figure.preferred().x + MathUtils.cos(candidate * GOLDEN_ANGLE) * radius;
            float y = figure.preferred().y + MathUtils.sin(candidate * GOLDEN_ANGLE) * radius;
            if (inside && !InfantryFootprint.inside(relative.set(x - center.x, y - center.y, 0), ground, 1, figure.room())) {
                continue;
            }
            ground.setPosition(x, y);
            if (placed.stream().noneMatch(other -> Intersector.overlapConvexPolygons(ground, other))) {
                return new Vector2(x, y);
            }
        }
        return null;
    }

    /** Everything a hex's packing depends on, so an unchanged crowd keeps its spots without searching again. */
    private static float[] inputs(List<Figure> figures, List<Polygon> obstacles) {
        List<Float> values = new ArrayList<>();
        for (Figure figure : figures) {
            values.add((float) figure.unit());
            values.add((float) figure.member().hashCode());
            values.add(figure.preferred().x);
            values.add(figure.preferred().y);
            for (float value : figure.ground().getVertices()) { values.add(value); }
            for (float value : figure.room()) { values.add(value); }
        }
        for (var obstacle : obstacles) {
            for (float value : obstacle.getVertices()) { values.add(value); }
        }
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) { result[i] = values.get(i); }
        return result;
    }
}
