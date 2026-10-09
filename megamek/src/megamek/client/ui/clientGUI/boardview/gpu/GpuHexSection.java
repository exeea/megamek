/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Level;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;

/**
 * The side view of the inspected hex: an orthographic side camera over its installed geometry, looking along the main
 * camera's yaw snapped to a hex direction, beside an auto-ranged level ruler, with a dashed guide and a label pill in
 * the right-hand column for each of the session's {@link Level}s (pills at one level stack; the column stays inside the
 * widget, see {@link #stack}). Dragging a pill, or the geometry, moves its levels by the session's HEIGHT command, one
 * undo step per drag: whole levels, Ctrl quarter levels for objects. With a prop or decal card armed, a click on a guide places it on that support, in air at a fixed level.
 * Only its framebuffer is owned here; the image is rendered again when the document, the hex, the direction, the size
 * or the installed geometry changes, and while the terrain is building.
 */
final class GpuHexSection extends Widget implements com.badlogic.gdx.utils.Disposable {
    private static final String[] LOOKING = { "looking N", "looking NE", "looking SE", "looking S", "looking SW", "looking NW" };
    /** The left ruler, the right pill column, the margin above the plot and the hint line below it. */
    private static final float RULER = 34, PILLS = 136, TOP = 8, BOTTOM = 22;
    /** A pill's height and spacing, and the pointer's reach around a guide. */
    private static final float PILL_H = 18, PILL_GAP = 3, BAND = 8;
    /** The plot's background, behind the rendered hex and the hint line. */
    private static final Color BACKDROP = new Color(.035f, .055f, .062f, 1);
    /** The tint of a see-through liquid's cut. */
    private static final Color WATER = new Color(.33f, .62f, .78f, 1);
    private final BoardSource source;
    private final Supplier<GpuTerrain> terrain;
    private final UiKit ui;
    private final DoubleSupplier azimuth;
    private final Function<BoardDecoration, String> objectLabel;
    private final BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
    private final OrthographicCamera camera = new OrthographicCamera();
    private final Label hint, ghostText;
    /** Labels reused by the ruler and the pills; their texts change only when the layout does. */
    private final List<Label> tickLabels = new ArrayList<>(), pillLabels = new ArrayList<>();
    private final List<Float> ticks = new ArrayList<>();
    private final List<Pill> pills = new ArrayList<>();
    private BoardEditorSession.Snapshot snapshot, laidOut;
    private long generation;
    private List<GpuTerrain.EditorObject> objects = List.of();
    private FrameBuffer buffer;
    private TextureRegion image;
    /** The ground cut in widget coordinates, projected when the image was rendered; {@code solid}: under a liquid, its bed. */
    private List<Vector2> profile = List.of(), solid = List.of();
    private boolean reveal, stale = true, render, wasBusy;
    /** Images rendered so far; an idle side view renders none. */
    int renders;
    private int direction, laidWidth, laidHeight, laidDirection = -1;
    /** The ruler's range, pixels per level, the hex centre's x and the offset along the view's right axis shown there. */
    private float lo, hi = 6, ppl = 1, cx, centreOffset;
    /** The view's horizontal direction and its right axis, unit vectors. */
    private final Vector3 look = new Vector3(), right = new Vector3();
    /** What the pointer is over here, and what the editor highlights (the same item in Layers, here and in 3D). */
    private String pointerHover = "", highlight = "";
    /** Every level key the pointer is over: a shared pill stands for several items. */
    private List<String> pointerKeys = List.of();
    private Drag drag;
    /** The armed pointer's position, or null. */
    private Vector2 ghost;
    private Spot spot;

    /**
     * One pill: the levels it stands for, its guide, its place and its text. Alike items at one level share a pill; when
     * the column is too short, all objects at one level do ({@code mixed}). A {@code chip} gathers the items beyond the
     * plotted range, or those that still do not fit; it has no guide and is not dragged.
     */
    private static final class Pill {
        final List<Level> levels = new ArrayList<>();
        String name;
        boolean mixed, chip;
        float guide, y, width;
        Label text;
        Pill(Level level, String name) { levels.add(level); this.name = name; }
        Level level() { return levels.getFirst(); }
        boolean has(String key) { return levels.stream().anyMatch(level -> level.key().equals(key)); }
        String text() {
            return chip ? name : name + (!mixed && levels.size() > 1 ? " ×" + levels.size() : "") + " · L" + UiNumber.format(level().level());
        }
    }

    /**
     * A press on a pill or on the geometry: its levels (all at one level; a drag moves every movable one), the press, the
     * value applied so far and whether it moved.
     */
    private static final class Drag {
        final List<Level> levels;
        final String name;
        final float pressY, from;
        final boolean selectOnClick, movable;
        double applied;
        boolean moved, began;
        Drag(List<Level> levels, String name, float pressY, boolean selectOnClick, boolean movable) {
            this.levels = List.copyOf(levels); this.name = name; this.pressY = pressY; this.selectOnClick = selectOnClick;
            this.movable = movable; from = (float) levels.getFirst().level(); applied = from;
        }
    }

    /** Where a click would put the armed item: on a support ({@code receiver}) or at a fixed level (receiver ""). */
    private record Spot(String receiver, double level, String text) { }

    GpuHexSection(BoardSource source, Supplier<GpuTerrain> terrain, UiKit ui, DoubleSupplier azimuth,
          Function<BoardDecoration, String> objectLabel) {
        this.source = source; this.terrain = terrain; this.ui = ui; this.azimuth = azimuth; this.objectLabel = objectLabel;
        setName("editor-hex-section");
        hint = ui.label("", "hud-small", 10.5f, UiTheme.MUTED); hint.setAlignment(Align.center); hint.setEllipsis(true);
        ghostText = ui.label("", "hud-small", 11, UiTheme.MINT);
        addListener(new InputListener() {
            @Override public boolean mouseMoved(InputEvent event, float x, float y) { hover(x, y); return false; }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(GpuHexSection.this))) { hover(List.of()); ghost = null; }
            }
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                return button == Input.Buttons.LEFT && press(x, y);
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) { dragTo(y); }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                release(event.isTouchFocusCancel());
            }
        });
    }

    void update(BoardEditorSession.Snapshot state, long boardGeneration, List<GpuTerrain.EditorObject> installedObjects,
          boolean placed) {
        if (boardGeneration != generation || snapshot != null && !Objects.equals(snapshot.selected(), state.selected())) { drag = null; }
        snapshot = state; generation = boardGeneration; objects = installedObjects; stale |= placed;
        direction = Math.floorMod(Math.round((float) -azimuth.getAsDouble() / 60), 6);
    }

    void reveal(boolean value) { reveal = value; stale = true; }
    boolean dragging() { return drag != null; }
    /** The item under the pointer here, a level key, or "". */
    String hovered() { return pointerHover; }
    /** Every item under the pointer here: all of a pill's, else the hovered one; empty when none. */
    List<String> hoveredKeys() { return pointerKeys; }
    /** Highlights the pill of {@code key}, the item hovered here, in Layers or on the board. */
    void highlight(String key) { highlight = key; }
    /** The direction the view looks along, the main camera's yaw snapped to a hex side: "looking N". */
    String looking() { return LOOKING[direction]; }

    private Coords shown() { return snapshot == null ? null : snapshot.selected(); }

    /** A pill's centre in widget coordinates, or null when no pill shows {@code key}. */
    Vector2 pill(String key) {
        for (Pill pill : pills) {
            if (pill.has(key)) { return new Vector2(getWidth() - PILLS + pill.width / 2, pill.y + PILL_H / 2); }
        }
        return null;
    }

    /** The widget y of {@code level}. */
    float y(double level) { return BOTTOM + (float) (level - lo) * ppl; }
    private double levelAt(float y) { return lo + (y - BOTTOM) / ppl; }
    private float plotRight() { return getWidth() - PILLS - 12; }
    /** The widget x of an offset along the view's right axis, in world units from the hex centre. */
    private float x(float offset) { return cx + (offset - centreOffset) * ppl / BoardGeometry.level(); }

    private boolean armed() {
        var object = snapshot == null ? null : snapshot.activeBrush().object();
        return object != null && snapshot.tool() == BoardEditorSession.Tool.PAINT && !megamek.common.board.MaglevRoute.isRoute(object);
    }

    private static boolean ctrl() { return Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT); }

    private Level level(String key) {
        return snapshot.levels().stream().filter(level -> level.key().equals(key)).findFirst().orElse(null);
    }

    private String name(Level level) {
        if (level.object()) {
            return snapshot.objects().stream().filter(object -> object.id().equals(level.key())).findFirst()
                  .map(objectLabel).orElse(level.key());
        }
        return switch (level.key()) {
            case "ground" -> snapshot.property("water") == null ? "Ground" : "Water surface";
            case "water" -> "Water bed";
            default -> blueprint.component(level.key()).label();
        };
    }

    private void hover(float x, float y) {
        if (snapshot == null || shown() == null) { return; }
        if (armed()) {
            ghost = new Vector2(x, y);
            Spot next = spot(y, ctrl());
            if (!Objects.equals(next, spot)) { spot = next; if (spot != null) { ghostText.setText(spot.text()); } }
            return;
        }
        ghost = null;
        Pill pill = pillAt(x, y);
        String key = pill != null ? null : geometryKey(x, y);
        hover(pill != null ? pill.levels.stream().map(Level::key).toList() : key == null ? List.of() : List.of(key));
    }

    private void hover(List<String> keys) {
        if (!keys.equals(pointerKeys)) { pointerKeys = keys; pointerHover = keys.isEmpty() ? "" : keys.getFirst(); }
    }

    private Pill pillAt(float x, float y) {
        float left = getWidth() - PILLS;
        for (Pill pill : pills) {
            if (x >= left - 4 && x <= left + pill.width + 4 && y >= pill.y - 2 && y <= pill.y + PILL_H + 2) { return pill; }
        }
        return null;
    }

    /** The level of the geometry under the pointer: a placed object, a picked structure, or the ground cut. */
    private String geometryKey(float x, float y) {
        if (x > plotRight() || terrain.get() == null) { return null; }
        var hit = terrain.get().editorSectionHit(shown(), camera.getPickRay(x, Gdx.graphics.getHeight() - y, 0, 0, getWidth(), getHeight()));
        if (hit != null) { return hit.object().isEmpty() ? hit.component() : hit.object(); }
        return groundAt(x, y) ? "ground" : null;
    }

    /** The internal ground cut is drawn by this widget even where the board has no exposed wall. */
    private boolean groundAt(float x, float y) {
        for (int i = 1; i < profile.size(); i++) {
            Vector2 a = profile.get(i - 1), b = profile.get(i);
            if (x >= Math.min(a.x, b.x) && x <= Math.max(a.x, b.x) && y >= BOTTOM && y <= Math.max(a.y, b.y) + 5) { return true; }
        }
        return false;
    }

    private boolean press(float x, float y) {
        if (snapshot == null || shown() == null || laidOut == null) { return false; }
        if (armed()) {
            Spot target = spot(y, ctrl());
            if (target != null) { place(x, target); }
            return true;
        }
        Pill pill = pillAt(x, y);
        if (pill != null) { drag = new Drag(pill.levels, pill.name, y, true, draggable(pill)); return true; }
        String key = geometryKey(x, y);
        if (key == null) { return true; }
        // A press on the geometry selects it, and a drag moves its level.
        if (select(key)) {
            Level level = level(key);
            if (level != null) { drag = new Drag(List.of(level), name(level), y, false, level.min() < level.max()); }
        }
        return true;
    }

    /** Selects a pill's items: several objects as a box selects them (whole groups), else the first item. */
    private void select(List<String> keys) {
        if (keys.size() > 1 && keys.stream().allMatch(key -> snapshot.objects().stream().anyMatch(object -> object.id().equals(key)))) {
            source.editorSelect(keys.stream().map(key -> new BoardEditorSession.Selection(shown(), key)).toList(),
                  BoardEditorSession.SelectMode.REPLACE, generation);
        } else { select(keys.getFirst()); }
    }

    /** Selects the item of a level key, as its Layers row does; false when the hex has no such item. */
    private boolean select(String key) {
        if (snapshot.objects().stream().anyMatch(object -> object.id().equals(key))) {
            source.editorCommand(new Command(Action.SELECT_OBJECT, key), generation);
        } else if (blueprint.components().stream().anyMatch(component -> component.id().equals(key))
              && blueprint.component(key).isPresent(name -> snapshot.property(name) != null)) {
            source.editorCommand(new Command(Action.COMPONENT, key), generation);
        } else { return false; }
        return true;
    }

    private void dragTo(float y) {
        Drag gesture = drag;
        if (gesture == null || !gesture.moved && Math.abs(y - gesture.pressY) < 3) { return; }
        gesture.moved = true;
        if (!gesture.movable) { return; }
        List<Level> movable = movable(gesture);
        double step = movable.stream().anyMatch(Level::object) && ctrl() ? .25 : 1;
        double value = Math.round((gesture.from + (y - gesture.pressY) / ppl) / step) * step;
        value = Math.max(movable.stream().mapToDouble(Level::min).max().orElseThrow(),
              Math.min(movable.stream().mapToDouble(Level::max).min().orElseThrow(), value));
        if (value != gesture.applied) {
            gesture.applied = value; gesture.began = true;
            apply(movable, value, false);
        }
    }

    private static List<Level> movable(Drag gesture) { return gesture.levels.stream().filter(level -> level.min() < level.max()).toList(); }

    /** Moves every level to {@code value} in one continuous edit; {@code finished} ends it as one undo step. */
    private void apply(List<Level> levels, double value, boolean finished) {
        for (int i = 0; i < levels.size(); i++) {
            source.editorValue(new Command(Action.HEIGHT, levels.get(i).key(), Double.toString(value)),
                  finished && i == levels.size() - 1, generation);
        }
    }

    private void release(boolean cancelled) {
        Drag gesture = drag;
        if (gesture == null) { return; }
        drag = null; stale = true;
        // Losing touch focus keeps the last previewed height.
        if (gesture.began) { apply(movable(gesture), gesture.applied, true); }
        else if (!gesture.moved && !cancelled && gesture.selectOnClick) { select(gesture.levels.stream().map(Level::key).toList()); }
    }

    /** A guide within reach of {@code y} that the armed item can stand on, else (a prop only) the fixed level there. */
    private Spot spot(float y, boolean quarter) {
        Level best = null;
        for (Level level : snapshot.levels()) {
            if (!level.receiver().isEmpty() && Math.abs(y(level.level()) - y) <= BAND
                  && (best == null || Math.abs(y(level.level()) - y) < Math.abs(y(best.level()) - y))) { best = level; }
        }
        if (best != null) { return new Spot(best.receiver(), best.level(), "On " + name(best).toLowerCase(java.util.Locale.ROOT) + " · L" + UiNumber.format(best.level())); }
        if (!snapshot.activeBrush().object().kind().equals("prop")) { return null; }
        double step = quarter ? .25 : 1, level = Math.round(levelAt(y) / step) * step;
        return new Spot("", level, "Fixed · L" + UiNumber.format(level));
    }

    /** Places the armed item at the pointer's offset along the view, kept inside the hex. */
    private void place(float x, Spot target) {
        float limit = .45f * BoardGeometry.width();
        float offset = Math.max(-limit, Math.min(limit, centreOffset + (x - cx) * BoardGeometry.level() / ppl));
        double east = offset * right.x / BoardGeometry.width(), north = offset * right.y / BoardGeometry.height();
        source.editorCommand(new Command(Action.PLACE, target.receiver(), east + "," + north + "," + target.level()), generation);
    }

    /** The ruler range, the scale, the ticks and the pills, from the snapshot and the installed geometry. */
    private void layoutView() {
        laidOut = snapshot; laidWidth = (int) getWidth(); laidHeight = (int) getHeight(); laidDirection = direction;
        stale = false; render = true;
        float angle = direction * 60 * com.badlogic.gdx.math.MathUtils.degreesToRadians;
        look.set(com.badlogic.gdx.math.MathUtils.sin(angle), com.badlogic.gdx.math.MathUtils.cos(angle), 0);
        right.set(look.y, -look.x, 0);
        float level = BoardGeometry.level(), plot = getHeight() - TOP - BOTTOM, plotWidth = plotRight() - RULER - 6;
        cx = (RULER + 6 + plotRight()) / 2;
        if (drag == null) {
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for (Level item : snapshot.levels()) { low = Math.min(low, item.level()); high = Math.max(high, item.level()); }
            double top = high;
            for (var object : objects) {
                low = Math.min(low, object.bounds().min.z / level); top = Math.max(top, object.bounds().max.z / level);
            }
            var roof = terrain.get().roofBounds(shown());
            if (roof != null) { top = Math.max(top, roof.max.z / level); }
            var selected = reveal ? objects.stream().filter(o -> o.id().equals(snapshot.object())).findFirst().orElse(null) : null;
            centreOffset = 0;
            if (selected != null) {
                // Reveal fits the selected object, centred.
                float bottom = Math.min(selected.anchorLevel(), selected.bounds().min.z / level);
                float upper = Math.max(selected.anchorLevel(), selected.bounds().max.z / level);
                float margin = Math.max(.15f, (upper - bottom) * .15f);
                lo = bottom - margin; hi = upper + margin; ppl = plot / (hi - lo);
                Vector3 centre = selected.bounds().getCenter(new Vector3());
                centreOffset = (centre.x - BoardGeometry.centerX(shown())) * right.x + (centre.y - BoardGeometry.centerY(shown())) * right.y;
            } else {
                lo = (float) Math.floor(low) - 1;
                hi = (float) Math.max(Math.max(Math.ceil(high) + 1, Math.ceil(top)), lo + 6);
                ppl = plot / (hi - lo);
                // The whole hex fits across the plot; a taller range then fills the height.
                float hexLevels = BoardGeometry.width() / level;
                if (hexLevels * ppl > plotWidth - 8) { ppl = (plotWidth - 8) / hexLevels; hi = lo + plot / ppl; }
            }
        }
        ticks.clear();
        float step = 1;
        for (float candidate : new float[] { 1, 2, 5, 10, 20, 50 }) { step = candidate; if (candidate * ppl >= 15) { break; } }
        for (float tick = (float) Math.ceil(lo / step) * step; tick <= hi + .001f; tick += step) {
            Label label = reuse(tickLabels, ticks.size(), 10, UiTheme.MUTED);
            label.setText(UiNumber.format(tick)); label.setAlignment(Align.right);
            ticks.add(tick);
        }
        pills.clear();
        for (Level item : snapshot.levels()) {
            String name = name(item);
            Pill same = pills.stream().filter(p -> p.name.equals(name) && p.level().object() == item.object()
                  && p.level().level() == item.level()).findFirst().orElse(null);
            if (same != null) { same.levels.add(item); } else { pills.add(new Pill(item, name)); }
        }
        stack();
        for (int i = 0; i < pills.size(); i++) {
            Pill pill = pills.get(i);
            pill.text = reuse(pillLabels, i, 11, UiTheme.TEXT);
            pill.text.setText(pill.text());
            pill.text.setEllipsis(true);
            pill.width = Math.min(pill.text.getPrefWidth() + (draggable(pill) ? 22 : 14), PILLS - 4);
        }
        Drag gesture = drag;
        hint.setText(gesture != null && gesture.began ? gesture.name + " L" + UiNumber.format(gesture.from) + " → L" + UiNumber.format(gesture.applied)
              : !armed() ? "Drag a label to change its height · Ctrl: quarter levels for objects"
              : snapshot.activeBrush().object().kind().equals("decal") ? "Click a line to place on it"
              : "Click a line to place on it · click air for a fixed level · Ctrl: quarter levels");
    }

    /**
     * Places the pills in the column, bounded by the widget: those whose guide is plotted stack in the order of their
     * guides, as near them as they fit; items above or below the plot gather in one dimmed chip at the column's top or
     * bottom, which never displaces the rest. When the column is too short, the objects at the busiest level share a
     * pill, and the highest pills that still do not fit join the top chip.
     */
    private void stack() {
        List<Pill> inside = new ArrayList<>(), above = new ArrayList<>(), below = new ArrayList<>();
        for (Pill pill : pills) {
            pill.guide = y(pill.level().level());
            (pill.guide < BOTTOM - 1 ? below : pill.guide > getHeight() - TOP + 1 ? above : inside).add(pill);
        }
        inside.sort(Comparator.comparingDouble(pill -> pill.guide));
        float slot = PILL_H + PILL_GAP, low = BOTTOM + (below.isEmpty() ? 0 : slot);
        float high = getHeight() - 2 - PILL_H - (above.isEmpty() ? 0 : slot);
        int room = Math.max(1, (int) Math.floor((high - low) / slot) + 1);
        while (inside.size() > room && mergeBusiestLevel(inside)) { }
        if (inside.size() > room) {
            // The top chip takes the overflow too; it then needs its own slot.
            if (above.isEmpty()) { high -= slot; room = Math.max(1, room - 1); }
            above.addAll(0, inside.subList(room, inside.size()));
            inside.subList(room, inside.size()).clear();
        }
        pills.clear();
        Pill under = chip(below, "below"), over = chip(above, above.stream().allMatch(p -> p.guide > getHeight() - TOP + 1) ? "above" : "more");
        if (under != null) { under.y = BOTTOM; pills.add(under); }
        float next = low;
        for (Pill pill : inside) { pill.y = Math.max(pill.guide - PILL_H / 2, next); next = pill.y + slot; }
        float limit = high;
        for (int i = inside.size() - 1; i >= 0; i--) { Pill pill = inside.get(i); pill.y = Math.min(pill.y, limit); limit = pill.y - slot; }
        pills.addAll(inside);
        if (over != null) { over.y = high + slot; pills.add(over); }
    }

    /** Merges the object pills at the level holding most of them into one; false when no level holds two. */
    private static boolean mergeBusiestLevel(List<Pill> inside) {
        var counts = new java.util.HashMap<Double, Integer>();
        for (Pill pill : inside) { if (pill.level().object() && !pill.chip) { counts.merge(pill.level().level(), 1, Integer::sum); } }
        var busiest = counts.entrySet().stream().filter(entry -> entry.getValue() > 1).max(java.util.Map.Entry.comparingByValue()).orElse(null);
        if (busiest == null) { return false; }
        Pill merged = null;
        for (var iterator = inside.iterator(); iterator.hasNext(); ) {
            Pill pill = iterator.next();
            if (!pill.level().object() || pill.level().level() != busiest.getKey()) { continue; }
            if (merged == null) { merged = pill; merged.mixed = true; }
            else { merged.levels.addAll(pill.levels); iterator.remove(); }
        }
        merged.name = merged.levels.size() + " objects";
        return true;
    }

    /** One dimmed chip for {@code gathered}, "3 above", or null when there are none. */
    private static Pill chip(List<Pill> gathered, String where) {
        if (gathered.isEmpty()) { return null; }
        Pill chip = new Pill(gathered.getFirst().level(), "");
        chip.levels.clear(); gathered.forEach(pill -> chip.levels.addAll(pill.levels));
        chip.chip = true; chip.guide = Float.NaN;
        chip.name = chip.levels.size() + " " + where;
        return chip;
    }

    private static boolean draggable(Pill pill) { return !pill.chip && pill.levels.stream().anyMatch(level -> level.min() < level.max()); }

    private Label reuse(List<Label> labels, int index, float size, Color color) {
        while (labels.size() <= index) { Label label = ui.label("", "hud-small", size, color); labels.add(label); }
        return labels.get(index);
    }

    @Override public void draw(Batch batch, float parentAlpha) {
        if (getWidth() < 1 || getHeight() < 1) { return; }
        if (snapshot == null || shown() == null || terrain.get() == null) {
            hint.setText("Select a hex to see its side elevation");
            hint.setBounds(getX(), getY() + getHeight() / 2 - 8, getWidth(), 16); hint.draw(batch, parentAlpha);
            laidOut = null; return;
        }
        try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.get().settings())) {
            if (stale || laidOut != snapshot || laidWidth != (int) getWidth() || laidHeight != (int) getHeight()
                  || laidDirection != direction) { layoutView(); }
            boolean busy = terrain.get().busy();
            if (render || busy || wasBusy) { renderImage(batch); }
            wasBusy = busy;
            drawOverlay(batch, parentAlpha);
        }
    }

    /** Renders the hex into the framebuffer and projects the ground cut. */
    private void renderImage(Batch batch) {
        render = false;
        int width = Math.max(1, (int) getWidth()), height = Math.max(1, (int) getHeight());
        float level = BoardGeometry.level();
        batch.end();
        // Scene2D owns clipping here. Querying GL synchronously waits for the entire board render on some drivers.
        boolean scissor = ScissorStack.peekScissors() != null;
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        try {
            if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
                if (buffer != null) { buffer.dispose(); }
                buffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, true);
                image = new TextureRegion(buffer.getColorBufferTexture()); image.flip(false, true);
            }
            camera.viewportHeight = height / ppl * level; camera.viewportWidth = width / ppl * level;
            // The widget's centre: the plot's hex centre lies at cx, the plot's levels from its bottom margin.
            float offset = centreOffset + (width / 2f - cx) * level / ppl;
            Vector3 centre = new Vector3(BoardGeometry.centerX(shown()), BoardGeometry.centerY(shown()),
                  (lo + (height / 2f - BOTTOM) / ppl) * level).mulAdd(right, offset);
            camera.position.set(centre).mulAdd(look, -10000);
            camera.up.set(Vector3.Z); camera.lookAt(centre); camera.near = 1; camera.far = 20000; camera.update();
            buffer.begin();
            renders++;
            try {
                ScreenUtils.clear(BACKDROP.r, BACKDROP.g, BACKDROP.b, 1, true);
                terrain.get().renderEditorSection(camera, shown(), reveal ? snapshot.object() : "");
            } finally { buffer.end(); }
            profile = reveal ? List.of() : cut(false, width, height);
            solid = reveal ? List.of() : cut(true, width, height);
        } finally {
            getStage().getViewport().apply();
            if (scissor) { Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); }
            batch.begin();
        }
    }

    /** The ground profile ({@link GpuTerrain#editorGroundProfile}) in widget coordinates. */
    private List<Vector2> cut(boolean bed, int width, int height) {
        List<Vector2> cut = new ArrayList<>();
        for (Vector3 point : terrain.get().editorGroundProfile(shown(), right.x, right.y, bed)) {
            Vector3 screen = camera.project(point, 0, 0, width, height);
            cut.add(new Vector2(screen.x, screen.y));
        }
        return cut;
    }

    /** Fills the plot below a cut. */
    private void fill(Batch batch, List<Vector2> cut, Color colour, float alpha) {
        for (int i = 1; i < cut.size(); i++) {
            Vector2 a = cut.get(i - 1), b = cut.get(i);
            ui.fill(batch, colour, alpha, getX() + Math.min(a.x, b.x), getY() + BOTTOM, Math.abs(b.x - a.x) + 1,
                  Math.max(0, Math.min(a.y, b.y) - BOTTOM));
        }
    }

    private void drawOverlay(Batch batch, float parentAlpha) {
        float colour = batch.getPackedColor();
        float left = getX(), bottom = getY(), plotRight = plotRight(), pillLeft = left + getWidth() - PILLS;
        batch.setColor(1, 1, 1, parentAlpha);
        batch.draw(image, left, bottom, getWidth(), getHeight());
        // The hint line keeps a plain backdrop; geometry below the plot would run through its text.
        ui.fill(batch, BACKDROP, parentAlpha, left, bottom, getWidth(), BOTTOM);
        // The ground cut: a see-through liquid tinted down to its bed, so what stands in it shows; the solid ground filled
        // below; the line on top.
        fill(batch, profile, WATER, .25f * parentAlpha);
        fill(batch, solid, UiTheme.POP, parentAlpha);
        for (int i = 1; i < profile.size(); i++) {
            Vector2 a = profile.get(i - 1), b = profile.get(i);
            ui.fill(batch, UiTheme.MINT, .6f * parentAlpha, left + Math.min(a.x, b.x), bottom + Math.min(a.y, b.y),
                  Math.abs(b.x - a.x) + 1, 2);
        }
        // The hex's sides along the view.
        float half = BoardGeometry.width() / 2;
        for (float side : new float[] { x(-half), x(half) }) {
            if (side > RULER && side < plotRight) { dashed(batch, UiTheme.MUTED, .25f * parentAlpha, left + side, bottom + BOTTOM, getHeight() - TOP - BOTTOM, true); }
        }
        // The ruler.
        ui.fill(batch, UiTheme.MUTED, .55f * parentAlpha, left + RULER - 6, bottom + BOTTOM, 1, getHeight() - TOP - BOTTOM);
        for (int i = 0; i < ticks.size(); i++) {
            float y = bottom + y(ticks.get(i));
            if (y < bottom + BOTTOM - 1 || y > bottom + getHeight() - TOP + 1) { continue; }
            ui.fill(batch, UiTheme.MUTED, .55f * parentAlpha, left + RULER - 11, y, 5, 1);
            Label label = tickLabels.get(i); label.setBounds(left, y - 8, RULER - 13, 16); label.draw(batch, parentAlpha);
        }
        // Guides and their pills; the hovered or selected item's guide is brighter.
        for (Pill pill : pills) {
            boolean hot = !highlight.isEmpty() && pill.has(highlight), chosen = selected(pill);
            Color tone = pill.chip ? UiTheme.MUTED : pill.level().object() ? UiTheme.MINT : UiTheme.AMBER;
            float alpha = (hot || chosen ? .9f : .45f) * parentAlpha, guide = bottom + pill.guide, centre = bottom + pill.y + PILL_H / 2;
            if (!pill.chip) {
                // Only plotted guides have pills of their own; a chip has none.
                dashed(batch, tone, alpha, left + RULER - 6, guide, plotRight - RULER + 6, false);
                ui.fill(batch, tone, alpha, left + plotRight, guide, pillLeft - 5 - left - plotRight, 1);
                ui.fill(batch, tone, parentAlpha, left + plotRight - 2, guide - 2, 5, 5);
                ui.fill(batch, tone, alpha, pillLeft - 5, Math.min(guide, centre), 1, Math.abs(centre - guide) + 1);
                ui.fill(batch, tone, alpha, pillLeft - 5, centre, 5, 1);
            }
            batch.setColor(1, 1, 1, parentAlpha);
            ui.skin.getDrawable(chosen ? "row-selected" : hot ? "row-over" : "row").draw(batch, pillLeft, bottom + pill.y, pill.width, PILL_H);
            float textLeft = pillLeft + 7;
            if (draggable(pill)) {
                for (int dot = 0; dot < 3; dot++) {
                    ui.fill(batch, tone, parentAlpha, pillLeft + 5, bottom + pill.y + 5 + dot * 3.5f, 1.6f, 1.6f);
                    ui.fill(batch, tone, parentAlpha, pillLeft + 8.5f, bottom + pill.y + 5 + dot * 3.5f, 1.6f, 1.6f);
                }
                textLeft = pillLeft + 15;
            }
            pill.text.setColor(chosen || hot ? UiTheme.TEXT : tone);
            pill.text.setBounds(textLeft, bottom + pill.y + 1, pillLeft + pill.width - textLeft - 4, PILL_H - 2);
            pill.text.draw(batch, parentAlpha);
        }
        // With a card armed: where a click would place it.
        Spot target = spot;
        if (ghost != null && armed() && target != null) {
            float y = bottom + y(target.level()), x = left + Math.max(RULER, Math.min(plotRight, ghost.x));
            dashed(batch, UiTheme.MINT, parentAlpha, left + RULER - 6, y, plotRight - RULER + 6, false);
            ui.fill(batch, UiTheme.MINT, .5f * parentAlpha, x - 6, y, 12, 10);
            ghostText.setBounds(Math.min(x + 10, left + plotRight - ghostText.getPrefWidth()), y + 2, ghostText.getPrefWidth(), 16);
            ghostText.draw(batch, parentAlpha);
        }
        hint.setBounds(left + RULER, bottom + 2, getWidth() - RULER - 4, 16); hint.draw(batch, parentAlpha);
        batch.setPackedColor(colour);
    }

    /** Whether the pill shows the selected object, or the selected component when no object is selected. */
    private boolean selected(Pill pill) {
        return snapshot.object().isEmpty() ? pill.has(snapshot.component()) : pill.has(snapshot.object());
    }

    /** A dashed line of 4-unit dashes: horizontal from (x, y) over {@code length}, or vertical upwards. */
    private void dashed(Batch batch, Color color, float alpha, float x, float y, float length, boolean vertical) {
        for (float at = 0; at < length; at += 8) {
            float dash = Math.min(4, length - at);
            if (vertical) { ui.fill(batch, color, alpha, x, y + at, 1, dash); } else { ui.fill(batch, color, alpha, x + at, y, dash, 1); }
        }
    }

    @Override public float getPrefHeight() { return 196; }
    @Override public void dispose() { if (buffer != null) { buffer.dispose(); buffer = null; } }
}
