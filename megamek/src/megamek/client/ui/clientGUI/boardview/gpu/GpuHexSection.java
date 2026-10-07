/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;

/** Orthographic side camera over the installed hex; only its framebuffer is owned here. */
final class GpuHexSection extends Widget implements com.badlogic.gdx.utils.Disposable {
    private final BoardSource source;
    private final Supplier<GpuTerrain> terrain;
    private final UiKit ui;
    private final BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
    private final OrthographicCamera camera = new OrthographicCamera();
    private final Label tick, reading;
    private BoardEditorSession.Snapshot snapshot;
    private long generation;
    private List<GpuTerrain.EditorObject> objects = List.of();
    private FrameBuffer buffer;
    private TextureRegion image;
    private boolean dragging, edited, reveal;
    private boolean east = true;
    private Coords shown;
    private float pressY, levelStep;
    private HeightControl dragControl;
    private float previewValue;
    private float minLevel, maxLevel = 6;

    /** A view of one authoritative property; commands and undo remain owned by the editor session. */
    private record HeightControl(Action action, String property, float value, float level,
          float min, float max, float step) {
        Command command(float next) { return new Command(action, property, UiNumber.format(next)); }
    }

    GpuHexSection(BoardSource source, Supplier<GpuTerrain> terrain, UiKit ui) {
        this.source = source; this.terrain = terrain; this.ui = ui;
        setName("editor-hex-section");
        tick = ui.label("", "hud-small", 10, UiTheme.MUTED);
        reading = ui.label("", "hud-small", 11, UiTheme.MINT);
        camera.up.set(Vector3.Z);
        addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT || snapshot == null || snapshot.selected() == null) { return false; }
                HeightControl control = heightControl(snapshot.object(), snapshot.component());
                boolean onLine = control != null && Math.abs(y - height(control.level())) < 13;
                if (onLine && x >= getWidth() - 34) { begin(control, y); return true; }
                var hit = terrain.get().editorSectionHit(snapshot.selected(), camera.getPickRay(x,
                      com.badlogic.gdx.Gdx.graphics.getHeight() - y, 0, 0, getWidth(), getHeight()));
                if (hit == null && groundAt(x, y)) { hit = new GpuTerrain.EditorSectionHit("", "ground"); }
                if (hit != null) {
                    if (!hit.object().isEmpty()) {
                        source.editorCommand(new Command(Action.SELECT_OBJECT, hit.object()), generation);
                    } else if (blueprint.component(hit.component()).isPresent(name -> snapshot.property(name) != null)) {
                        source.editorCommand(new Command(Action.COMPONENT, hit.component()), generation);
                    } else { return true; }
                    control = heightControl(hit.object(), hit.component());
                    if (control != null) { begin(control, y); }
                } else if (onLine) {
                    begin(control, y);
                }
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (dragging) { drag(y, false); }
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                if (dragging) {
                    if (event.isTouchFocusCancel()) {
                        if (edited) {
                            source.editorValue(dragControl.command(previewValue), true, generation);
                        }
                    } else if (edited) { drag(y, true); }
                    dragging = false;
                }
            }
        });
    }

    private void begin(HeightControl control, float y) {
        dragging = true; edited = false; pressY = y; dragControl = control; previewValue = control.value();
        levelStep = (maxLevel - minLevel) / Math.max(1, getHeight() - 36);
    }

    private void drag(float y, boolean finished) {
        if (!edited && Math.abs(y - pressY) < 3) { return; }
        float value = Math.round((dragControl.value() + (y - pressY) * levelStep) / dragControl.step()) * dragControl.step();
        value = Math.max(dragControl.min(), Math.min(dragControl.max(), value));
        if (value != previewValue || finished && edited) {
            previewValue = value;
            edited = true;
            source.editorValue(dragControl.command(value), finished, generation);
        }
    }

    private HeightControl heightControl(String objectId, String componentId) {
        if (!objectId.isEmpty()) {
            BoardDecoration object = snapshot.objects().stream().filter(d -> d.id().equals(objectId)).findFirst().orElse(null);
            var placed = objects.stream().filter(o -> o.id().equals(objectId)).findFirst().orElse(null);
            if (object == null || !object.kind().equals("prop") || placed == null) { return null; }
            // An imported absolute prop becomes ground-relative when its height is edited, like the numeric inspector.
            float value = object.placement().offset() == null ? placed.anchorLevel() - snapshot.elevation()
                  : object.placement().offset().floatValue();
            return new HeightControl(Action.OBJECT_VALUE, "offset", value, placed.anchorLevel(), -Float.MAX_VALUE, Float.MAX_VALUE, .1f);
        }
        if (componentId.equals("ground")) {
            return new HeightControl(Action.ELEVATION, "", snapshot.elevation(), snapshot.elevation(), -Float.MAX_VALUE, Float.MAX_VALUE, 1);
        }
        for (var field : blueprint.component(componentId).fields()) {
            var value = snapshot.property(field.terrain());
            if (field.height() && value != null) {
                return new HeightControl(Action.TERRAIN, field.terrain(), value.value(), snapshot.elevation() + value.value(),
                      (float) field.min(), Math.max((float) field.max(), value.value()), Math.max(1, (float) field.step()));
            }
        }
        return null;
    }

    /** The internal ground cut is drawn by this widget even where the board has no exposed wall. */
    private boolean groundAt(float x, float y) {
        if (reveal) { return false; }
        Vector3 previous = null;
        for (var point : terrain.get().editorGroundProfile(shown, east)) {
            Vector3 next = camera.project(point.cpy(), 0, 0, getWidth(), getHeight());
            if (previous != null && x >= Math.min(previous.x, next.x) && x <= Math.max(previous.x, next.x)
                  && y >= height(minLevel) && y <= Math.max(previous.y, next.y) + 5) { return true; }
            previous = next;
        }
        return false;
    }
    void update(BoardEditorSession.Snapshot state, long boardGeneration, List<GpuTerrain.EditorObject> installedObjects) {
        if (terrain.get() == null) { return; }
        objects = installedObjects;
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.get().settings())) {
            updateInstalled(state, boardGeneration);
        }
    }
    private void updateInstalled(BoardEditorSession.Snapshot state, long boardGeneration) {
        if (boardGeneration != generation || !java.util.Objects.equals(shown, state.selected())) { dragging = false; }
        snapshot = state; generation = boardGeneration; shown = state.selected();
        if (!dragging) {
            minLevel = state.elevation() - 1; maxLevel = state.elevation() + 5;
            for (var object : objects) {
                minLevel = Math.min(minLevel, object.bounds().min.z / BoardGeometry.level() - .5f);
                maxLevel = Math.max(maxLevel, object.bounds().max.z / BoardGeometry.level() + .8f);
            }
            var bounds = terrain.get() == null || shown == null ? null : terrain.get().roofBounds(shown);
            if (bounds != null) { maxLevel = Math.max(maxLevel, bounds.max.z / BoardGeometry.level() + .8f); }
            if (shown != null) {
                var control = heightControl(state.object(), state.component());
                if (control != null) {
                    minLevel = Math.min(minLevel, control.level() - .5f);
                    maxLevel = Math.max(maxLevel, control.level() + .8f);
                }
            }
            if (reveal && resolved() != null) {
                var selected = resolved();
                float bottom = Math.min(selected.anchorLevel(), selected.bounds().min.z / BoardGeometry.level());
                float top = Math.max(selected.anchorLevel(), selected.bounds().max.z / BoardGeometry.level());
                float margin = Math.max(.15f, (top - bottom) * .15f);
                minLevel = bottom - margin; maxLevel = top + margin;
            }
        }
    }
    void reveal(boolean value) { reveal = value; }
    void east(boolean value) { east = value; }
    boolean dragging() { return dragging; }
    private GpuTerrain.EditorObject resolved() {
        return objects.stream().filter(o -> o.id().equals(snapshot.object())).findFirst().orElse(null);
    }
    private float height(float level) { return 18 + (level - minLevel) / (maxLevel - minLevel) * (getHeight() - 36); }

    @Override public void draw(Batch batch, float parentAlpha) {
        if (snapshot == null || shown == null || terrain.get() == null || getWidth() < 1 || getHeight() < 1) { return; }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.get().settings())) {
            drawSection(batch, parentAlpha);
        }
    }
    private void drawSection(Batch batch, float parentAlpha) {
        int width = Math.max(1, (int) getWidth()), height = Math.max(1, (int) getHeight());
        batch.end();
        boolean scissor = com.badlogic.gdx.Gdx.gl.glIsEnabled(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST);
        com.badlogic.gdx.Gdx.gl.glDisable(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST);
        try {
            if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
                if (buffer != null) { buffer.dispose(); }
                buffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, true);
                image = new TextureRegion(buffer.getColorBufferTexture()); image.flip(false, true);
            }
            float vertical = (maxLevel - minLevel) * BoardGeometry.level() * height / Math.max(1, height - 36);
            camera.viewportHeight = vertical; camera.viewportWidth = vertical * width / height;
            Vector3 center = new Vector3(BoardGeometry.centerX(shown), BoardGeometry.centerY(shown),
                  (minLevel + maxLevel) * .5f * BoardGeometry.level());
            if (reveal && resolved() != null) {
                Vector3 object = resolved().bounds().getCenter(new Vector3());
                center.x = object.x; center.y = object.y;
            }
            camera.position.set(center).add(east ? -10000 : 0, east ? 0 : -10000, 0);
            camera.lookAt(center); camera.near = 1; camera.far = 20000; camera.update();
            buffer.begin();
            try {
                ScreenUtils.clear(.035f, .055f, .062f, 1, true);
                terrain.get().renderEditorSection(camera, shown, reveal ? snapshot.object() : "");
            } finally { buffer.end(); }
        } finally {
            getStage().getViewport().apply();
            if (scissor) { com.badlogic.gdx.Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_SCISSOR_TEST); }
            batch.begin();
        }
        float colour = batch.getPackedColor(); batch.setColor(1, 1, 1, parentAlpha);
        batch.draw(image, getX(), getY(), getWidth(), getHeight());
        var profile = reveal ? List.<Vector3>of() : terrain.get().editorGroundProfile(shown, east);
        Vector3 previous = null;
        for (Vector3 point : profile) {
            Vector3 screen = camera.project(point.cpy(), 0, 0, getWidth(), getHeight());
            if (previous != null) {
                float x = Math.min(screen.x, previous.x), y = Math.min(screen.y, previous.y);
                if (!reveal) {
                    ui.fill(batch, UiTheme.POP, parentAlpha, getX() + x, getY() + height(minLevel),
                          Math.abs(screen.x - previous.x) + 1, Math.max(0, y - height(minLevel)));
                }
                ui.fill(batch, UiTheme.MINT, .6f * parentAlpha, getX() + x, getY() + y, Math.abs(screen.x - previous.x) + 1, 2);
            }
            previous = screen;
        }
        float spacing = maxLevel - minLevel < 2 ? .25f : Math.max(1, (float) Math.ceil((maxLevel - minLevel) / 8));
        for (float level = (float) Math.ceil(minLevel / spacing) * spacing; level <= maxLevel; level += spacing) {
            float y = getY() + height(level);
            ui.fill(batch, UiTheme.MUTED, .2f * parentAlpha, getX() + 30, y, getWidth() - 40, 1);
            tick.setText(UiNumber.format(level)); tick.setBounds(getX() + 4, y - 7, 26, 16); tick.draw(batch, parentAlpha);
        }
        var control = dragging ? dragControl : heightControl(snapshot.object(), snapshot.component());
        if (control != null) {
            float level = control.level() + (dragging ? previewValue - control.value() : 0);
            float y = getY() + height(level);
            ui.fill(batch, UiTheme.MINT, parentAlpha, getX() + 34, y, getWidth() - 48, 2);
            ui.fill(batch, UiTheme.MINT, parentAlpha, getX() + getWidth() - 22, y - 6, 12, 14);
            reading.setText("L" + UiNumber.format(level) + "  ↕");
            reading.setBounds(getX() + getWidth() - 92, y + 3, 90, 18); reading.draw(batch, parentAlpha);
        }
        batch.setPackedColor(colour);
    }
    @Override public float getPrefHeight() { return 180; }
    @Override public void dispose() { if (buffer != null) { buffer.dispose(); buffer = null; } }
}
