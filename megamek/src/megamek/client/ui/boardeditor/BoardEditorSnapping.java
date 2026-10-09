/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import java.util.List;
import java.util.Set;

import megamek.client.ui.tileset.HexTileset;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Translation-only joins between explicitly authored, compatible connector ends. No model loading or ID guessing. */
public final class BoardEditorSnapping {
    private BoardEditorSnapping() { }

    private record End(double x, double y, double z, double heading) { }

    static BoardDecoration snap(Board board, BoardEditorBlueprint blueprint, Coords owner, BoardDecoration moving, Set<String> excluded) {
        var asset = blueprint.asset(moving.asset());
        // The catalog's rail/barrier connectors describe level runs. A tilted part cannot join their horizontal ends.
        if (asset == null || asset.snap() == null || tilted(moving)) { return moving; }
        var definition = asset.snap();
        var ends = ends(board, owner, moving, definition);
        double centreX = globalX(owner, moving.x());
        double centreY = globalY(owner, moving.y());
        // Equal-size targets (scale and stretch) cannot have a connector outside this catalog-derived neighbourhood.
        double reach = blueprint.assets().stream().filter(a -> a.snap() != null && a.snap().set().equals(definition.set()))
              .flatMap(a -> a.snap().connectors().stream()).mapToDouble(end -> Math.hypot(end.x(), end.y())).max().orElse(0)
              * moving.scale() * Math.max(moving.stretch().x(), moving.stretch().y()) * 2 * HexTileset.HEX_W / HexTileset.HEX_H
              + definition.radius();
        int left = Math.max(0, (int) Math.floor((centreX - reach) / .75));
        int right = Math.min(board.getWidth() - 1, (int) Math.ceil((centreX + reach) / .75));
        int top = Math.max(0, (int) Math.floor(-centreY - reach - .5));
        int bottom = Math.min(board.getHeight() - 1, (int) Math.ceil(-centreY + reach + .5));
        double best = definition.radius() * definition.radius(), dx = 0, dy = 0;
        for (int col = left; col <= right; col++) {
            for (int row = top; row <= bottom; row++) {
                Coords at = new Coords(col, row);
                for (var target : board.getHex(at).getDecorations()) {
                    if (excluded.contains(target.id()) || target.id().equals(moving.id()) || tilted(target)
                          || Math.abs(target.scale() - moving.scale()) > .000001 || !target.stretch().equals(moving.stretch())) {
                        continue;
                    }
                    var targetAsset = blueprint.asset(target.asset());
                    if (targetAsset == null || targetAsset.snap() == null || !targetAsset.snap().set().equals(definition.set())) { continue; }
                    var targetDefinition = targetAsset.snap();
                    var targetEnds = ends(board, at, target, targetDefinition);
                    for (End from : ends) {
                        for (End to : targetEnds) {
                            double angle = Math.abs(Math.IEEEremainder(from.heading() - to.heading() - 180, 360));
                            if (angle > Math.min(definition.angleTolerance(), targetDefinition.angleTolerance())
                                  || !Double.isFinite(from.z()) || !Double.isFinite(to.z()) || Math.abs(from.z() - to.z()) > .02) { continue; }
                            double x = to.x() - from.x(), y = to.y() - from.y();
                            double distance = x * x + Math.pow(y * HexTileset.HEX_H / HexTileset.HEX_W, 2);
                            if (distance < best && distance <= targetDefinition.radius() * targetDefinition.radius()) {
                                double q = owner.toCube().q() + (moving.x() + x) / .75;
                                double r = owner.toCube().r() - moving.y() - y - (moving.x() + x) / 1.5;
                                if (!board.contains(new megamek.common.board.CubeCoords(q, r, -q - r).roundToNearestHex().toOffset())) { continue; }
                                best = distance; dx = x; dy = y;
                            }
                        }
                    }
                }
            }
        }
        return moving.transform(moving.x() + dx, moving.y() + dy, moving.rotation(), moving.mirror(), moving.scale(), moving.placement());
    }

    /** Board-global east of an owner-relative offset, in hex widths; metric space scales it by {@code HEX_W}. */
    static double globalX(Coords owner, double x) { return owner.getX() * .75 + x; }

    /** Board-global north of an owner-relative offset, in hex heights; metric space scales it by {@code HEX_H}. */
    static double globalY(Coords owner, double y) { return -owner.getY() - (owner.getX() & 1) * .5 + y; }

    private static boolean tilted(BoardDecoration object) {
        return Math.abs(Math.IEEEremainder(object.rotationX(), 360)) > .000001
              || Math.abs(Math.IEEEremainder(object.rotationY(), 360)) > .000001;
    }

    private static List<End> ends(Board board, Coords owner, BoardDecoration object, BoardEditorBlueprint.Snap definition) {
        double radians = Math.toRadians(object.rotation()), cos = Math.cos(radians), sin = Math.sin(radians);
        double base = height(board, owner, object);
        return definition.connectors().stream().map(end -> {
            double x = end.x() * HexTileset.HEX_W * object.scale() * object.stretch().x() * (object.mirror() ? -1 : 1);
            double y = end.y() * HexTileset.HEX_H * object.scale() * object.stretch().y();
            return new End(globalX(owner, object.x()) + (x * cos - y * sin) / HexTileset.HEX_W,
                  globalY(owner, object.y()) + (x * sin + y * cos) / HexTileset.HEX_H,
                  base + end.z() * object.scale() * object.stretch().z(), object.rotation() + (object.mirror() ? 180 - end.heading() : end.heading()));
        }).toList();
    }

    private static double height(Board board, Coords owner, BoardDecoration object) {
        var placement = object.placement();
        if (placement.mode().equals("absolute")) { return placement.level(); }
        // During a drag the anchor may be over another hex while the owning record deliberately stays put.
        double q = owner.toCube().q() + object.x() / .75, r = owner.toCube().r() - object.y() - object.x() / 1.5;
        Coords at = new megamek.common.board.CubeCoords(q, r, -q - r).roundToNearestHex().toOffset();
        return level(board.getHex(at), placement);
    }

    /**
     * The nominal level {@code placement} puts an object's anchor at on {@code hex}, by {@link
     * BoardDecoration.Placement#level(double, java.util.function.ToDoubleFunction)}: each support's top from the hex's
     * rules terrain, or NaN where the hex lacks that support.
     */
    public static double level(megamek.common.Hex hex, BoardDecoration.Placement placement) {
        return placement.level(hex == null ? Double.NaN : hex.getLevel(), receiver -> {
            int type = switch (receiver) {
                case "bridge" -> Terrains.BRIDGE_ELEV;
                case "building" -> Terrains.BLDG_ELEV;
                case "industrial" -> Terrains.INDUSTRIAL;
                case "fuelTank" -> Terrains.FUEL_TANK_ELEV;
                case "ice" -> Terrains.ICE;
                default -> throw new IllegalArgumentException("Unknown receiver " + receiver);
            };
            if (hex == null || !hex.containsTerrain(type)) { return Double.NaN; }
            // Ice lies at the hex's own level, on its water or ground.
            return hex.getLevel() + (type == Terrains.ICE ? 0 : hex.terrainLevel(type));
        });
    }

    /**
     * The lowest level an object's anchor may take on {@code hex}: one level below its floor (its ground, or the bed
     * under water), deep enough to bury a foot in uneven ground. The side view and the inspector stop there.
     */
    public static double lowestLevel(megamek.common.Hex hex) { return hex.floor() - 1; }

    /** {@code placement} on {@code hex}, raised to {@link #lowestLevel} if it lies below it; its support is kept. */
    public static BoardDecoration.Placement clamp(megamek.common.Hex hex, BoardDecoration.Placement placement) {
        double at = level(hex, placement);
        // An object whose support is missing stands on the ground, where the editor draws it.
        if (Double.isNaN(at)) { at = hex.getLevel() + placement.offset(); }
        double lowest = lowestLevel(hex);
        if (at >= lowest) { return placement; }
        return placement.mode().equals("absolute") ? BoardDecoration.Placement.absolute(lowest)
              : BoardDecoration.Placement.surface(placement.receiver().terrain(), placement.receiver().surface(),
                    placement.offset() + lowest - at);
    }
}
