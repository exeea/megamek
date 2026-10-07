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
final class BoardEditorSnapping {
    private BoardEditorSnapping() { }

    private record End(double x, double y, double z, double heading) { }

    static BoardDecoration snap(Board board, BoardEditorBlueprint blueprint, Coords owner, BoardDecoration moving, Set<String> excluded) {
        var asset = blueprint.asset(moving.asset());
        if (asset == null || asset.snap() == null) { return moving; }
        var definition = asset.snap();
        var ends = ends(board, owner, moving, definition);
        double centreX = owner.getX() * .75 + moving.x();
        double centreY = -owner.getY() - (owner.getX() & 1) * .5 + moving.y();
        // Equal-scale targets cannot have a connector outside this catalog-derived neighbourhood.
        double reach = blueprint.assets().stream().filter(a -> a.snap() != null && a.snap().set().equals(definition.set()))
              .flatMap(a -> a.snap().connectors().stream()).mapToDouble(end -> Math.hypot(end.x(), end.y())).max().orElse(0)
              * moving.scale() * 2 * HexTileset.HEX_W / HexTileset.HEX_H + definition.radius();
        int left = Math.max(0, (int) Math.floor((centreX - reach) / .75));
        int right = Math.min(board.getWidth() - 1, (int) Math.ceil((centreX + reach) / .75));
        int top = Math.max(0, (int) Math.floor(-centreY - reach - .5));
        int bottom = Math.min(board.getHeight() - 1, (int) Math.ceil(-centreY + reach + .5));
        double best = definition.radius() * definition.radius(), dx = 0, dy = 0;
        for (int col = left; col <= right; col++) {
            for (int row = top; row <= bottom; row++) {
                Coords at = new Coords(col, row);
                for (var target : board.getHex(at).getDecorations()) {
                    if (excluded.contains(target.id()) || target.id().equals(moving.id()) || Math.abs(target.scale() - moving.scale()) > .000001) { continue; }
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

    private static List<End> ends(Board board, Coords owner, BoardDecoration object, BoardEditorBlueprint.Snap definition) {
        double radians = Math.toRadians(object.rotation()), cos = Math.cos(radians), sin = Math.sin(radians);
        double base = height(board, owner, object);
        return definition.connectors().stream().map(end -> {
            double x = end.x() * HexTileset.HEX_W * object.scale() * (object.mirror() ? -1 : 1);
            double y = end.y() * HexTileset.HEX_H * object.scale();
            return new End(owner.getX() * .75 + object.x() + (x * cos - y * sin) / HexTileset.HEX_W,
                  -owner.getY() - (owner.getX() & 1) * .5 + object.y() + (x * sin + y * cos) / HexTileset.HEX_H,
                  base + end.z() * object.scale(), object.rotation() + (object.mirror() ? 180 - end.heading() : end.heading()));
        }).toList();
    }

    private static double height(Board board, Coords owner, BoardDecoration object) {
        var placement = object.placement();
        if (placement.mode().equals("absolute")) { return placement.level(); }
        // During a drag the anchor may be over another hex while the owning record deliberately stays put.
        double q = owner.toCube().q() + object.x() / .75, r = owner.toCube().r() - object.y() - object.x() / 1.5;
        Coords at = new megamek.common.board.CubeCoords(q, r, -q - r).roundToNearestHex().toOffset();
        var hex = board.getHex(at);
        if (hex == null) { return Double.NaN; }
        int type = switch (placement.receiver().terrain()) {
            case "bridge" -> Terrains.BRIDGE_ELEV;
            case "building" -> Terrains.BLDG_ELEV;
            case "industrial" -> Terrains.INDUSTRIAL;
            default -> 0;
        };
        if (type != 0 && !hex.containsTerrain(type)) { return Double.NaN; }
        return hex.getLevel() + (type == 0 ? 0 : hex.terrainLevel(type)) + placement.offset();
    }
}
