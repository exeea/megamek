/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;

/** One hex per terrain section. Borrows the loading skin's font and white texture; owns no GL resources. */
final class GpuLoadingGrid extends Widget {
    static final Color WAITING = Color.valueOf("34464D");
    static final Color LOADING = Color.valueOf("E5B768");
    static final Color READY = Color.valueOf("69CFAE");
    private static final Color EMPTY = Color.valueOf("111E23");
    private static final Color INK = Color.valueOf("102923");
    private static final float HALF_HEIGHT = .8660254f;
    private final Texture white;
    private final BitmapFont font;
    private final GlyphLayout label = new GlyphLayout();
    private final Color tint = new Color();
    private final Color fontColor = new Color();
    private final float[] vertices = new float[20];
    private TerrainLoadProgress.Sections sections = TerrainLoadProgress.Sections.EMPTY;
    private float pulse;

    GpuLoadingGrid(Skin skin) {
        white = skin.get("white", Texture.class);
        font = skin.getFont("small-font");
        setName("board-loading-grid");
        setTouchable(Touchable.disabled);
    }

    void update(TerrainLoadProgress.Sections next) {
        sections = next;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        pulse = (pulse + delta * 3) % MathUtils.PI2;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (sections.states().isEmpty()) { return; }
        // Swap tall maps' axes to fit the screen while keeping the first section at the top left.
        int columns = sections.columns(), rows = sections.rows();
        float across = 2 + 1.5f * (columns - 1);
        float down = 2 * HALF_HEIGHT * (rows + (columns > 1 ? .5f : 0));
        boolean turned = rows > columns;
        float radius = Math.min(30, Math.min(getWidth() / (turned ? down : across),
              getHeight() / (turned ? across : down)));
        if (radius <= 0) { return; }
        float centerX = getX() + getWidth() / 2, centerY = getY() + getHeight() / 2;
        fontColor.set(font.getColor());
        float batchColor = batch.getPackedColor();
        batch.setColor(Color.WHITE);
        try {
            for (int index = 0; index < sections.states().size(); index++) {
                int column = index / rows, row = index % rows;
                float mapX = (1 + column * 1.5f - across / 2) * radius;
                float mapY = (down / 2 - (1 + 2 * row + column % 2) * HALF_HEIGHT) * radius;
                float x = centerX + (turned ? -mapY : mapX);
                float y = centerY + (turned ? -mapX : mapY);
                var state = sections.states().get(index);
                Color edge = switch (state) {
                    case WAITING -> WAITING;
                    case LOADING -> LOADING;
                    case READY -> READY;
                };
                float inset = Math.min(2, radius * .12f);
                hex(batch, x, y, radius - inset / 2, color(edge, 1, parentAlpha), turned);
                float brightness = state == TerrainLoadProgress.SectionState.LOADING
                      ? .30f + .12f * (1 + MathUtils.sin(pulse)) : 1;
                Color fill = state == TerrainLoadProgress.SectionState.WAITING ? EMPTY : edge;
                hex(batch, x, y, radius - inset, color(fill, brightness, parentAlpha), turned);
                if (radius >= 14) {
                    Color text = state == TerrainLoadProgress.SectionState.READY ? INK : GpuBoardSkin.TEXT;
                    font.setColor(text.r, text.g, text.b, getColor().a * parentAlpha);
                    label.setText(font, Integer.toString(TerrainLoadProgress.displayIndex(index, columns, rows) + 1));
                    if (label.width < radius * 1.4f) {
                        font.draw(batch, label, x - label.width / 2, y + label.height / 2);
                    }
                }
            }
        } finally {
            font.setColor(fontColor);
            batch.setPackedColor(batchColor);
        }
    }

    private float color(Color base, float brightness, float parentAlpha) {
        return tint.set(base).mul(getColor()).mul(brightness, brightness, brightness, parentAlpha).toFloatBits();
    }

    /** Two trapezoids use the existing sprite batch, without interrupting the stage or allocating meshes. */
    private void hex(Batch batch, float x, float y, float radius, float color, boolean turned) {
        float h = HALF_HEIGHT * radius;
        vertex(0, x, y, -radius, 0, color, turned);
        vertex(5, x, y, -radius / 2, -h, color, turned);
        vertex(10, x, y, radius / 2, -h, color, turned);
        vertex(15, x, y, radius, 0, color, turned);
        batch.draw(white, vertices, 0, vertices.length);
        vertex(5, x, y, radius, 0, color, turned);
        vertex(10, x, y, radius / 2, h, color, turned);
        vertex(15, x, y, -radius / 2, h, color, turned);
        batch.draw(white, vertices, 0, vertices.length);
    }

    private void vertex(int offset, float x, float y, float dx, float dy, float color, boolean turned) {
        vertices[offset] = x + (turned ? dy : dx);
        vertices[offset + 1] = y + (turned ? -dx : dy);
        vertices[offset + 2] = color;
        vertices[offset + 3] = .5f;
        vertices[offset + 4] = .5f;
    }
}
