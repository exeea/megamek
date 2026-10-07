/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;

/** A small screen-readable compass pinned to the selected hex's north edge, below all editor panels. */
final class GpuEditorNorth extends Group {
    private final Texture white;
    private final Label label, shadow;
    private final Vector2 direction = new Vector2(0, 1);
    private float length;

    GpuEditorNorth(UiKit ui) {
        white = ui.skin.get("white", Texture.class);
        setName("editor-north"); setTouchable(Touchable.disabled); setTransform(false); setSize(80, 80);
        shadow = ui.label("N", "hud-title", 14, Color.BLACK); shadow.pack(); addActor(shadow);
        label = ui.label("N", "hud-title", 14, UiTheme.TEXT); label.pack(); addActor(label);
        setVisible(false);
    }

    void update(BoardScene scene, Coords selected, BoardCamera camera) {
        if (scene == null || selected == null || scene.tile(selected) == null) { setVisible(false); return; }
        float z = BoardTacticalGeometry.floatingZ(scene, selected);
        Vector3 center = BoardGeometry.center(selected, 0); center.z = z;
        // Side zero lies between corners 1 and 2: derive north from the same lattice as the selection ring.
        Vector3 north = BoardGeometry.corner(selected, 0, 1).add(BoardGeometry.corner(selected, 0, 2)).scl(.5f);
        north.z = z;
        Vector2 base = GpuNameplates.project(camera.camera, center);
        Vector2 tip = GpuNameplates.project(camera.camera, north);
        if (base == null || tip == null) { setVisible(false); return; }
        float scale = getStage().getWidth() / camera.camera.viewportWidth;
        base.scl(scale); tip.scl(scale);
        direction.set(tip).sub(base);
        if (direction.isZero(.001f)) { setVisible(false); return; }
        length = MathUtils.clamp(direction.len() * .35f, 14, 22);
        direction.nor();
        setPosition(tip.x - 40, tip.y - 40);
        float textOffset = length / 2 + 6
              + (Math.abs(direction.x) * label.getWidth() + Math.abs(direction.y) * label.getHeight()) / 2;
        float x = 40 + direction.x * textOffset - label.getWidth() / 2;
        float y = 40 + direction.y * textOffset - label.getHeight() / 2;
        label.setPosition(x, y); shadow.setPosition(x + 1, y - 1);
        setVisible(true);
    }

    @Override public void draw(Batch batch, float parentAlpha) {
        float previous = batch.getPackedColor();
        float x = getX() + 40 + direction.x * length / 2;
        float y = getY() + 40 + direction.y * length / 2;
        for (int pass = 0; pass < 2; pass++) {
            Color color = pass == 0 ? Color.BLACK : UiTheme.MINT;
            batch.setColor(color.r, color.g, color.b, parentAlpha * getColor().a);
            float width = pass == 0 ? 4.5f : 2;
            line(batch, x - direction.x * length, y - direction.y * length, x, y, width);
            for (int sign : new int[] {-1, 1}) {
                line(batch, x - direction.x * 6 + sign * direction.y * 4,
                      y - direction.y * 6 - sign * direction.x * 4, x, y, width);
            }
        }
        batch.setPackedColor(previous);
        super.draw(batch, parentAlpha);
    }

    private void line(Batch batch, float ax, float ay, float bx, float by, float width) {
        batch.draw(white, ax, ay - width / 2, 0, width / 2, (float) Math.hypot(bx - ax, by - ay), width,
              1, 1, (float) Math.toDegrees(Math.atan2(by - ay, bx - ax)), 0, 0, 1, 1, false, false);
    }
}
