/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiTheme.alpha;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.RangeType;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * The hud-v3 board overlay (rebuild plan C.1 G7; overlay.js in 3D, flat.js in the Tactical View) as world meshes:
 * reach envelopes, the route and its ghost, unit rings and glows, range bands, the front arc or the displayed weapon's
 * arc, the physical-attack neighbours and the Tactical View's elevation-drop edges; over them the plotted route's
 * {@link GpuRoutePulse}. It draws the frame's snapshots and decides no rule; it rebuilds only when what it draws
 * changed. Owns its meshes, its pulse and its batch on the GL thread.
 */
final class GpuBoardOverlay implements Disposable {
    /** One prototype pixel in hex radii at the Tactical View zoom GpuUnitIcons follows: an icon of 38 = 1.25 radii. */
    static final float PIXEL = 1.25f / 38;
    // overlay.js:10-12, the 3D palette.
    private static final Color MINT = new Color(.45f, .92f, .80f, 1);
    private static final Color RUN = new Color(.93f, .76f, .40f, 1);
    private static final Color CORAL = new Color(1, .44f, .40f, 1);
    private static final Color VIOLET = new Color(.72f, .62f, 1, 1);
    private static final Color BLIP = new Color(1, .66f, .34f, 1);
    private static final Color WRECK = new Color(.3f, .3f, .3f, 1);
    /** Short, medium and long range, in both views. */
    private static final Color[] BANDS = {
          new Color(.49f, .94f, .82f, 1), new Color(.93f, .78f, .46f, 1), new Color(.62f, .72f, .95f, 1) };
    // flat.js:16-75, the Tactical View palette; its mint is the HUD's mint token.
    private static final Color FLAT_RUN = new Color(.9f, .76f, .45f, 1);
    private static final Color FLAT_SELECTED = Color.valueOf("a3ffe0");
    private static final Color FLAT_TARGET = Color.valueOf("ff7a70");
    private static final Color FLAT_ARROW = Color.valueOf("e9fff7");
    private static final Color FLAT_PIN = Color.valueOf("f2f5f1");
    private static final Color DROP = new Color(20 / 255f, 30 / 255f, 24 / 255f, .7f);
    /** overlay.js:66: the displayed weapon's mount arc, a wedge from .8 to 2.2 hex radii in 28 steps. */
    private static final float WEDGE_INNER = .8f;
    private static final float WEDGE_OUTER = 2.2f;
    private static final int WEDGE_SEGMENTS = 28;
    /**
     * The ghost's cyan (engine.js material kind 8, without its scanlines and rim light), the share of it that glows
     * whatever the model's own colours, and its strength.
     */
    private static final Color GHOST = new Color(.25f, .72f, .95f, 1);
    private static final Color GHOST_GLOW = new Color(.15f, .43f, .57f, 1);
    private static final float GHOST_OPACITY = .75f;
    /** The Tactical View's ghost is the unit's icon at half strength (rebuild plan A.7 G4). */
    private static final float GHOST_ICON_OPACITY = .5f;
    /** The marks the board hides show at this opacity (rimshaderv1's occluded selection outlines). */
    private static final float HIDDEN_OPACITY = .5f;

    private final ModelBatch batch = new ModelBatch((camera, renderables) -> { });
    private final BoardSurface.Cache surfaces = new BoardSurface.Cache();
    /** Light for the 3D ghost: enough ambient to keep it bright, one light from above to show its shape. */
    private final Environment ghostLight = new Environment();
    private ModelInstance overlay;
    /**
     * The acting unit's, the focused enemy's and the hovered unit's rings once more, drawn only where the terrain or a
     * model hides them, so that a unit behind a building keeps its mark (3D only); null without such a ring.
     */
    private ModelInstance hidden;
    private ModelInstance dropEdges;
    private List<BoardScene.Tile> dropTiles;
    private int dropRevision = -1;
    private long builds;
    // The inputs of the shown overlay: snapshots by identity, the scene by what the overlay reads of it, view state
    // by value. The hovered hex counts only while its ring shows.
    private BoardScene scene;
    private GpuBattleStatus.Snapshot status;
    private List<GpuBattleStatus.UnitStatus> units;
    private GpuMovePlan.Snapshot move;
    private GpuFireOrders.Snapshot fire;
    private GpuPhysicalOptions.Snapshot physical;
    private boolean envelopeShown = true;
    private int minRangeRgb;
    private int extremeRangeRgb;
    /** MegaMek's sprint envelope colour; the prototype has no sprint band. */
    private int sprintRgb;
    private Coords hovered;
    private int hoveredUnit = Entity.NONE;
    private int inspected = Entity.NONE;
    private boolean tactical;
    private int revision = -1;
    /** The enemy the shown overlay focuses (its coral glow), or {@code Entity.NONE}. */
    private int focused = Entity.NONE;
    /** The hex radius in world units while building. */
    private float radius;
    // The ghost of the plotted route: the moving unit as the scene shows it and where the route leaves it, or null.
    private BoardScene.Unit mover;
    private BoardScene.Waypoint arrival;
    /** The ghost last drawn: a copy of {@link #ghostSource} as it looked at build {@link #ghostBuild}. */
    private ModelInstance ghost;
    private ModelInstance ghostSource;
    private long ghostBuild;
    /** Each ghost material's opacity at rest, which the pulse's surge raises. */
    private float[] ghostOpacity;
    /** The plotted route's pulse, drawn over the route each frame; the meshes above never change for it. */
    private final GpuRoutePulse pulse = new GpuRoutePulse();

    GpuBoardOverlay() {
        ghostLight.set(new ColorAttribute(ColorAttribute.AmbientLight, .6f, .6f, .6f, 1));
        ghostLight.add(new DirectionalLight().set(.5f, .5f, .5f, -.4f, .3f, -1));
    }

    /**
     * Shows the frame's overlay. The meshes are rebuilt only when what they draw changed: the scene's units, terrain
     * or range bands and borders, the status, the presented units, the movement, fire or physical snapshot, the
     * envelope preference, the range and sprint colours or the view state. {@code view} gives the Tactical View flag
     * and the hovered hex and unit; {@code state} the inspected unit and the presented units (C.6). Called once a
     * frame, it also moves the route's pulse on by the frame's time.
     */
    void update(GpuBoardSource.Frame captured, GpuHud.HudView view, GpuBoardSource.UiPreferences preferences,
          GpuHudState state) {
        pulse.advance(Gdx.graphics.getDeltaTime());
        // As the HUD presents it: no route, envelope or orders while the player cleared the selection in the turn.
        GpuBoardSource.Frame frame = state.presented(captured);
        GpuHudData panels = frame.panels();
        // The source captures a new scene at every refresh; one that shows the same keeps the meshes.
        boolean sameScene = sameDrawing(scene, frame.scene());
        scene = frame.scene();
        // The view draws a building floor's column instead of the hex's ring (GpuBattleView.renderHoverRings).
        Coords ring = view.tactical() || view.hoveredUnit() != Entity.NONE || !Float.isNaN(view.hoverTop())
              || showsEnvelope(panels.move(), preferences.moveEnvelope()) ? null : view.hovered();
        if (sameScene && frame.status() == status && state.presentedUnits() == units
              && panels.move() == move && panels.fire() == fire && panels.physical() == physical
              && preferences.moveEnvelope() == envelopeShown && preferences.minRangeRgb() == minRangeRgb
              && preferences.extremeRangeRgb() == extremeRangeRgb && preferences.moveSprintRgb() == sprintRgb
              && Objects.equals(ring, hovered) && view.hoveredUnit() == hoveredUnit && state.inspected == inspected
              && view.tactical() == tactical && revision == BoardGeometry.revision()) {
            return;
        }
        status = frame.status();
        units = state.presentedUnits();
        move = panels.move();
        fire = panels.fire();
        physical = panels.physical();
        envelopeShown = preferences.moveEnvelope();
        minRangeRgb = preferences.minRangeRgb();
        extremeRangeRgb = preferences.extremeRangeRgb();
        sprintRgb = preferences.moveSprintRgb();
        hovered = ring;
        hoveredUnit = view.hoveredUnit();
        inspected = state.inspected;
        tactical = view.tactical();
        revision = BoardGeometry.revision();
        builds++;
        mover = null;
        arrival = null;
        focused = Entity.NONE;
        pulse.begin(tactical, BoardGeometry.WIDTH / 2);
        if (overlay != null) {
            overlay.model.dispose();
            overlay = null;
        }
        if (hidden != null) {
            hidden.model.dispose();
            hidden = null;
        }
        if (scene == null) {
            return;
        }
        radius = BoardGeometry.WIDTH / 2;
        overlay = build();
        if (tactical && !(sameLevels(dropTiles, scene.tiles()) && dropRevision == revision)) {
            if (dropEdges != null) {
                dropEdges.model.dispose();
            }
            dropRevision = revision;
            dropEdges = dropEdges();
        }
        if (tactical) {
            dropTiles = scene.tiles();
        }
    }

    /** How often the overlay was rebuilt; the drop edges are built again only when the board's levels change. */
    long builds() {
        return builds;
    }

    /** The plotted route's pulse. */
    GpuRoutePulse pulse() {
        return pulse;
    }

    /**
     * The enemy the shown overlay focuses, whose glow is coral (overlay.js focusT): the declaration's focused target,
     * the physical attack's target, else the inspected enemy; {@code Entity.NONE} for none.
     */
    int focus() {
        return focused;
    }

    /**
     * Whether two captures show the same overlay: the same units, terrain (by identity, as the terrain caches compare
     * it) and range bands and borders.
     */
    private static boolean sameDrawing(BoardScene shown, BoardScene next) {
        return shown == next || shown != null && next != null && shown.boardId() == next.boardId()
              && shown.tiles() == next.tiles() && shown.units().equals(next.units())
              && shown.rangeBands().equals(next.rangeBands()) && shown.rangeBorders().equals(next.rangeBorders());
    }

    /** Whether two terrain captures have the same hexes at the same levels; the source repaints tiles as views pan. */
    private static boolean sameLevels(List<BoardScene.Tile> shown, List<BoardScene.Tile> next) {
        if (shown == next) {
            return true;
        }
        if (shown == null || next == null || shown.size() != next.size()) {
            return false;
        }
        for (int index = 0; index < shown.size(); index++) {
            BoardScene.Tile before = shown.get(index);
            BoardScene.Tile after = next.get(index);
            if (!before.coords().equals(after.coords()) || before.elevation() != after.elevation()) {
                return false;
            }
        }
        return true;
    }

    /** overlay.js:33: the envelope shows for the local mover while the client's move-envelope preference is on. */
    private static boolean showsEnvelope(GpuMovePlan.Snapshot move, boolean preference) {
        return move.active() && preference && !move.envelope().isEmpty();
    }

    /**
     * Draws over the terrain and units with the board camera: depth-tested in 3D, flat on top in the Tactical View;
     * the route's pulse last, over the route.
     */
    void render(Camera camera) {
        if (scene == null || overlay == null && !(tactical && dropEdges != null)) {
            return;
        }
        batch.begin(camera);
        if (tactical && dropEdges != null) {
            batch.render(dropEdges);
        }
        if (overlay != null) {
            batch.render(overlay);
        }
        if (hidden != null) {
            batch.render(hidden);
        }
        Renderable pulsing = pulse.renderable(camera);
        if (pulsing != null) {
            batch.render(pulsing);
        }
        batch.end();
    }

    /**
     * The plotted route's ghost (rebuild plan A.7 G4): a copy of the instance the view draws for the moving unit
     * ({@code shown} gives it for a scene unit, or null), sharing its meshes, at the route's destination in the final
     * facing. In 3D a translucent cyan copy of the model, drawn before {@link #render} so that the marks under it stay
     * hidden, as in the prototype; in the Tactical View the unit's icon at half strength, drawn after the icons.
     * When the route's pulse lands, the ghost surges. Nothing without a plotted route of the planner.
     */
    void renderGhost(Camera camera, Function<BoardScene.Unit, ModelInstance> shown) {
        ModelInstance source = mover == null ? null : shown.apply(mover);
        if (source == null) {
            return;
        }
        if (ghost == null || ghostSource != source || ghostBuild != builds) {
            // The copy owns copies of the shown materials; the meshes stay the shown model's.
            ghost = new ModelInstance(source);
            ghostOpacity = new float[ghost.materials.size];
            for (int index = 0; index < ghost.materials.size; index++) {
                Material material = ghost.materials.get(index);
                ghostly(material);
                ghostOpacity[index] = ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity;
            }
            ghostSource = source;
            ghostBuild = builds;
        }
        surge(pulse.surge());
        // Moves the shown placement from the unit's hex to the destination; facings turn clockwise, Z rotations not.
        Vector3 from = BoardGeometry.center(mover.location().coords(), mover.location().elevation());
        Vector3 to = BoardGeometry.center(arrival.coords(), arrival.elevation());
        ghost.transform.setToTranslation(to).rotate(Vector3.Z, (mover.location().facing() - arrival.facing()) * 60)
              .translate(-from.x, -from.y, -from.z).mul(source.transform);
        batch.begin(camera);
        if (tactical) {
            batch.render(ghost);
        } else {
            // Depth first, then colour only where the copy is nearest: one translucent surface.
            Gdx.gl.glColorMask(false, false, false, false);
            batch.render(ghost, ghostLight);
            batch.flush();
            Gdx.gl.glColorMask(true, true, true, true);
            batch.render(ghost, ghostLight);
        }
        batch.end();
    }

    /**
     * The pulse's surge on the ghost's copied materials: its glow rises and it turns more opaque, then both ease back
     * as the surge does. It never grows: units keep their size.
     */
    private void surge(float surge) {
        float opaque = Math.min(1, surge) * GpuRoutePulse.GHOST_SURGE_OPACITY;
        for (int index = 0; index < ghost.materials.size; index++) {
            Material material = ghost.materials.get(index);
            ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity =
                  ghostOpacity[index] + (1 - ghostOpacity[index]) * opaque;
            Color glow = ((ColorAttribute) material.get(ColorAttribute.Emissive)).color;
            if (tactical) {
                glow.set(pulse.arrivalColour()).mul(GpuRoutePulse.ICON_SURGE * surge);
            } else {
                glow.set(GpuRoutePulse.GHOST_SURGE).mul(surge).add(GHOST_GLOW);
            }
        }
    }

    /**
     * Turns a copied material into the ghost's: in the Tactical View the icon's own at half strength, with a glow for
     * the pulse's surge (none at rest); in 3D a lit, translucent cyan that keeps only a cut-out (alpha-tested) texture,
     * so that a flat sprite keeps its outline.
     */
    private void ghostly(Material material) {
        if (tactical) {
            BlendingAttribute blending = (BlendingAttribute) material.get(BlendingAttribute.Type);
            if (blending == null) {
                material.set(new BlendingAttribute(GHOST_ICON_OPACITY));
            } else {
                blending.opacity *= GHOST_ICON_OPACITY;
            }
            material.set(ColorAttribute.createEmissive(Color.BLACK));
            return;
        }
        Attribute alphaTest = material.get(FloatAttribute.AlphaTest);
        Attribute cutOut = alphaTest == null ? null : material.get(TextureAttribute.Diffuse);
        Attribute cullFace = material.get(IntAttribute.CullFace);
        material.clear();
        material.set(ColorAttribute.createDiffuse(GHOST), ColorAttribute.createEmissive(GHOST_GLOW),
              new BlendingAttribute(GHOST_OPACITY), new DepthTestAttribute(GL20.GL_LEQUAL, true));
        if (cutOut != null) {
            material.set(alphaTest, cutOut);
        }
        if (cullFace != null) {
            material.set(cullFace);
        }
    }

    /** The prototype's layers in its drawing order; the batch keeps that order. */
    private ModelInstance build() {
        Sink sink = new Sink(material(tactical));
        Sink behind = new Sink(hiddenMaterial());
        GamePhase phase = status.phase();
        Map<Integer, GpuBattleStatus.UnitStatus> listed = units.stream()
              .collect(Collectors.toMap(GpuBattleStatus.UnitStatus::id, unit -> unit, (a, b) -> a));
        Map<Integer, Set<Coords>> hexes = new LinkedHashMap<>();
        Map<Integer, BoardScene.Unit> shown = new HashMap<>();
        for (BoardScene.Unit unit : scene.units()) {
            hexes.computeIfAbsent(unit.id(), id -> new LinkedHashSet<>()).addAll(unit.footprint());
            shown.putIfAbsent(unit.id(), unit);
        }
        // Glows mark the acting unit and the focused enemy outside the initiative phases (overlay.js:21-28).
        boolean glows = !phase.isInitiative() && !phase.isInitiativeReport();
        int actor = glows && eligible(listed.get(status.actorId()), shown.get(status.actorId()), false)
              ? status.actorId() : Entity.NONE;
        // The local declaration's focused target; a read-only draft (H33) has none, so the inspected unit glows.
        int focusId = fire.editable() ? fire.focusTargetId() : physical.active() ? physical.targetId() : inspected;
        int focus = glows && eligible(listed.get(focusId), shown.get(focusId), true) ? focusId : Entity.NONE;
        focused = focus;
        if (!tactical) {
            unitRings(sink, behind, listed, hexes, shown, actor, focus, phase.isMovement());
        }
        if (move.active()) {
            if (showsEnvelope(move, envelopeShown)) {
                envelopes(sink);
            }
            BoardScene.Unit moving = shown.get(move.entityId());
            if (move.planner() && moving != null) {
                route(sink, moving);
            }
        }
        rangeBands(sink);
        if (fire.active() && !tactical) {
            if (fire.frontArc() != null) {
                outline(sink, fire.frontArc().hexes(), .03f, false, alpha(Color.WHITE, .3f));
            }
            if (fire.solution() != null && fire.solution().wedge() != null) {
                wedge(sink, fire.solution().wedge());
            }
            for (GpuFireOrders.Target target : fire.targets()) {
                if (target.id() != fire.focusTargetId()) {
                    rings(sink, hexes.get(target.id()), .05f, .04f, .02f, alpha(CORAL, .95f));
                }
            }
        }
        BoardScene.Unit attacker = shown.get(physical.actorId());
        if (physical.active() && attacker != null) {
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile tile = scene.tile(attacker.location().coords().translated(direction));
                if (tile != null && tactical) {
                    ring(sink, tile, 1.5f * PIXEL, .08f - .75f * PIXEL, 0, alpha(Color.WHITE, .35f));
                } else if (tile != null) {
                    ring(sink, tile, .03f, .1f, .02f, alpha(Color.WHITE, .22f));
                }
            }
        }
        if (tactical) {
            flatGlow(sink, hexes.get(actor), FLAT_SELECTED);
            flatGlow(sink, hexes.get(focus), FLAT_TARGET);
        } else {
            hover(sink, behind, hexes, actor);
        }
        hidden = behind.end();
        return sink.end();
    }

    /** A glow needs a listed, shown, living unit that is no contact: the actor is friendly, the focus an enemy. */
    private static boolean eligible(GpuBattleStatus.UnitStatus listing, BoardScene.Unit unit, boolean enemy) {
        return listing != null && unit != null && !unit.sensorContact() && !listing.destroyed()
              && (listing.side() == GpuBattleStatus.Side.ENEMY) == enemy;
    }

    /**
     * overlay.js:21-31: contacts, wrecks, the acting and focused glows and the side rings; moved units fade. The glows'
     * rings also go {@code behind}.
     */
    private void unitRings(Sink sink, Sink behind, Map<Integer, GpuBattleStatus.UnitStatus> listed,
          Map<Integer, Set<Coords>> hexes, Map<Integer, BoardScene.Unit> shown, int actor, int focus,
          boolean movement) {
        for (Map.Entry<Integer, Set<Coords>> entry : hexes.entrySet()) {
            int id = entry.getKey();
            GpuBattleStatus.UnitStatus listing = listed.get(id);
            if (shown.get(id).sensorContact()) {
                for (Coords coords : entry.getValue()) {
                    BoardScene.Tile tile = scene.tile(coords);
                    if (tile != null) {
                        fill(sink, tile, .05f, .018f, alpha(BLIP, .22f));
                        ring(sink, tile, .035f, .08f, .02f, alpha(BLIP, .7f));
                    }
                }
            } else if (listing == null) {
                continue;
            } else if (listing.destroyed()) {
                rings(sink, entry.getValue(), .03f, .1f, .02f, alpha(WRECK, .6f));
            } else if (id == actor || id == focus) {
                for (Coords coords : entry.getValue()) {
                    BoardScene.Tile tile = scene.tile(coords);
                    if (tile != null) {
                        glow(sink, behind, tile, id == actor ? MINT : CORAL);
                    }
                }
            } else {
                boolean enemy = listing.side() == GpuBattleStatus.Side.ENEMY;
                float strength = movement && listing.done() ? .2f : enemy ? .5f : .45f;
                rings(sink, entry.getValue(), .03f, .08f, .02f, alpha(enemy ? CORAL : MINT, strength));
            }
        }
    }

    /** overlay.js:20: a fill and three rings of rising strength; the strongest also {@code behind}. */
    private void glow(Sink sink, Sink behind, BoardScene.Tile tile, Color color) {
        fill(sink, tile, .08f, .025f, alpha(color, .16f));
        ring(sink, tile, .2f, -.03f, .035f, alpha(color, .14f));
        ring(sink, tile, .1f, 0, .04f, alpha(color, .3f));
        ring(sink, tile, .045f, .04f, .05f, alpha(color, 1));
        ring(behind, tile, .045f, .04f, .05f, alpha(color, 1));
    }

    /** flat.js:65: a 3-pixel outline with a 12-pixel blur, the blur drawn as three fading halos. */
    private void flatGlow(Sink sink, Set<Coords> coords, Color color) {
        if (coords == null) {
            return;
        }
        for (Coords hex : coords) {
            BoardScene.Tile tile = scene.tile(hex);
            if (tile != null) {
                float middle = .04f;
                ring(sink, tile, 21 * PIXEL, middle - 10.5f * PIXEL, 0, alpha(color, .08f));
                ring(sink, tile, 13 * PIXEL, middle - 6.5f * PIXEL, 0, alpha(color, .16f));
                ring(sink, tile, 7 * PIXEL, middle - 3.5f * PIXEL, 0, alpha(color, .3f));
                ring(sink, tile, 3 * PIXEL, middle - 1.5f * PIXEL, 0, alpha(color, 1));
            }
        }
    }

    /**
     * Each envelope band with the hexes of the bands inside it (a hex reachable walking is reachable running),
     * outermost first: in 3D a faint fill and a dashed border (overlay.js:33-39), in the Tactical View a stronger
     * fill with every hex outlined (flat.js:37-42).
     */
    private void envelopes(Sink sink) {
        Map<Coords, GpuMovePlan.Band> envelope = move.envelope();
        for (GpuMovePlan.Band band : List.of(GpuMovePlan.Band.JUMP, GpuMovePlan.Band.SPRINT, GpuMovePlan.Band.RUN,
              GpuMovePlan.Band.WALK)) {
            if (!envelope.containsValue(band)) {
                continue;
            }
            Set<Coords> set = envelope.entrySet().stream().filter(entry -> within(entry.getValue(), band))
                  .map(Map.Entry::getKey).collect(Collectors.toSet());
            Color color = moveColor(band);
            // flat.js:37-40: walk and jump fill .18, run .14 (sprint as run); strokes .55, .5 and .45.
            boolean strong = band == GpuMovePlan.Band.WALK || band == GpuMovePlan.Band.JUMP;
            for (Coords coords : set) {
                BoardScene.Tile tile = scene.tile(coords);
                if (tile == null) {
                    continue;
                }
                if (tactical) {
                    fill(sink, tile, .03f, 0, alpha(color, strong ? .18f : .14f));
                    ring(sink, tile, PIXEL, .03f - PIXEL / 2, 0,
                          alpha(color, band == GpuMovePlan.Band.JUMP ? .5f : strong ? .55f : .45f));
                } else {
                    fill(sink, tile, .05f, .018f, alpha(color, .07f));
                }
            }
            if (!tactical) {
                outline(sink, set, .035f, true, alpha(color, .9f));
            }
        }
    }

    private static boolean within(GpuMovePlan.Band hex, GpuMovePlan.Band band) {
        return switch (band) {
            case JUMP -> hex == GpuMovePlan.Band.JUMP;
            case SPRINT -> hex == GpuMovePlan.Band.WALK || hex == GpuMovePlan.Band.RUN
                  || hex == GpuMovePlan.Band.SPRINT;
            case RUN -> hex == GpuMovePlan.Band.WALK || hex == GpuMovePlan.Band.RUN;
            default -> hex == band;
        };
    }

    /**
     * The plotted route, else the hover preview at half strength while no unit is hovered (overlay.js:40-47,
     * flat.js:43-47). It runs from the unit's hex through every hex a step enters, coloured by the band of that
     * step, and ends in the destination ring or outline, the facing arrow and the waypoints. A jump is one arc from
     * where it starts to where it lands, as in the prototype, although MegaMek's jump path lists every hex it passes.
     * A plotted route also places the ghost and hands the pulse its line and marks.
     */
    private void route(Sink sink, BoardScene.Unit moving) {
        Coords origin = moving.location().coords();
        boolean plotted = !move.route().isEmpty();
        List<GpuMovePlan.Step> steps = plotted ? move.route()
              : hoveredUnit == Entity.NONE ? move.hover() : List.of();
        if (steps.isEmpty() || scene.tile(origin) == null) {
            return;
        }
        float strength = plotted ? 1 : .5f;
        List<Coords> points = new ArrayList<>(List.of(origin));
        List<GpuMovePlan.Band> bands = new ArrayList<>();
        bands.add(null);
        for (GpuMovePlan.Step step : steps) {
            if (step.boardId() != scene.boardId() || step.coords().equals(points.getLast())
                  || scene.tile(step.coords()) == null) {
                continue;
            }
            if (step.band() == GpuMovePlan.Band.JUMP && bands.getLast() == GpuMovePlan.Band.JUMP) {
                points.set(points.size() - 1, step.coords());
            } else {
                points.add(step.coords());
                bands.add(step.band());
            }
        }
        GpuMovePlan.Step end = steps.getLast();
        int facing = plotted && move.facing() >= 0 ? move.facing() : end.facing();
        BoardScene.Tile destination = scene.tile(points.getLast());
        if (plotted && end.boardId() == scene.boardId() && scene.tile(end.coords()) != null) {
            mover = moving;
            arrival = new BoardScene.Waypoint(end.coords(), end.level(), facing);
            // The pulse runs to the ghost; it keeps its rhythm while the plan stays the same.
            int hexes = 0;
            for (int i = 1; i < points.size(); i++) {
                hexes += points.get(i - 1).distance(points.get(i));
            }
            pulse.arrive(List.of(moving.id(), origin, steps), hexes,
                  on(destination, BoardGeometry.center(points.getLast(), 0), 0),
                  moveColor(bands.getLast() == null ? end.band() : bands.getLast()));
        }
        if (points.size() == 1) {
            arrow(sink, destination, facing, 1);
            return;
        }
        boolean pulsing = mover != null;
        List<Vector3> at = new ArrayList<>();
        for (Coords coords : points) {
            at.add(on(scene.tile(coords), BoardGeometry.center(coords, 0), .07f));
        }
        float travelled = 0;
        for (int i = 1; i < at.size(); i++) {
            Color color = alpha(moveColor(bands.get(i)), strength);
            Vector3 a = at.get(i - 1);
            Vector3 b = at.get(i);
            float length = Vector3.dst(a.x, a.y, 0, b.x, b.y, 0) / radius;
            boolean jump = !tactical && bands.get(i) == GpuMovePlan.Band.JUMP;
            if (pulsing) {
                trace(a, b, jump, travelled, length, color);
            }
            if (tactical) {
                // flat.js:44: one 3-pixel line dashed 7 on, 5 off along the whole route.
                float period = 12 * PIXEL;
                for (float start = (float) Math.floor(travelled / period) * period; start < travelled + length;
                      start += period) {
                    float from = Math.max(start, travelled);
                    float to = Math.min(start + 7 * PIXEL, travelled + length);
                    if (to > from) {
                        dash(sink, above(a, b, (from - travelled) / length), above(a, b, (to - travelled) / length),
                              1.5f * PIXEL, (from + to) / 2, color, pulsing);
                    }
                }
            } else if (jump) {
                for (int j = 1; j <= 14; j++) {
                    float t = j / 15f;
                    Vector3 dot = arc(a, b, t);
                    disc(sink, dot, .05f, 10, color);
                    if (pulsing) {
                        pulse.mark(dot, dot, .05f, travelled + t * length, color);
                    }
                }
            } else {
                for (float d = .12f; d < length - .1f; d += .42f) {
                    dash(sink, above(a, b, d / length), above(a, b, Math.min(1, (d + .24f) / length)), .04f,
                          travelled + d + .12f, color, pulsing);
                }
            }
            if (pulsing && !tactical) {
                // The step's disc at its hex's centre, drawn below.
                pulse.mark(b, b, .085f, travelled + length, color);
            }
            travelled += length;
        }
        Color last = moveColor(bands.getLast());
        if (tactical) {
            ring(sink, destination, 3 * PIXEL, .06f - 1.5f * PIXEL, 0, last);
        } else {
            for (int i = 1; i < at.size(); i++) {
                disc(sink, at.get(i), .085f, 16, alpha(moveColor(bands.get(i)), strength));
            }
            ring(sink, destination, .05f, .02f, .02f, alpha(last, strength));
        }
        arrow(sink, destination, facing, strength);
        for (Coords pin : move.pins()) {
            BoardScene.Tile tile = scene.tile(pin);
            if (tile == null) {
                continue;
            }
            if (tactical) {
                disc(sink, on(tile, BoardGeometry.center(pin, 0), 0), 8 * PIXEL, 24, FLAT_PIN);
            } else {
                ring(sink, tile, .06f, .2f, .02f, alpha(Color.WHITE, .95f));
                disc(sink, on(tile, BoardGeometry.center(pin, 0), .1f), .16f, 20, alpha(Color.WHITE, .9f));
            }
        }
    }

    /**
     * The facing arrow at the destination: overlay.js:48, a notched white head in 3D; flat.js:101, a plain
     * triangle in the Tactical View, always at full strength.
     */
    private void arrow(Sink sink, BoardScene.Tile tile, int facing, float strength) {
        Vector3 center = on(tile, BoardGeometry.center(tile.coords(), 0), .09f);
        float angle = facing * 60 * MathUtils.degreesToRadians;
        Vector3 ahead = new Vector3(MathUtils.sin(angle), MathUtils.cos(angle), 0).scl(radius);
        Vector3 across = new Vector3(ahead.y, -ahead.x, 0);
        if (tactical) {
            sink.triangle(point(center, ahead, .85f, across, 0), point(center, ahead, .45f, across, .22f),
                  point(center, ahead, .45f, across, -.22f), FLAT_ARROW);
        } else {
            Color color = alpha(Color.WHITE, strength);
            Vector3 tip = point(center, ahead, .9f, across, 0);
            Vector3 notch = point(center, ahead, .65f, across, 0);
            sink.triangle(tip, point(center, ahead, .55f, across, .24f), notch, color);
            sink.triangle(tip, notch, point(center, ahead, .55f, across, -.24f), color);
        }
    }

    /**
     * A point of the route between two hex centres, kept at the route's lift above the ground it crosses, so a step
     * up a cliff runs over the higher hex rather than inside it.
     */
    private Vector3 above(Vector3 a, Vector3 b, float t) {
        Vector3 point = lerp(a, b, t);
        float ground = UnitLandingSupports.surface(scene, point.x, point.y, surfaces);
        if (Float.isFinite(ground)) {
            point.z = Math.max(point.z, ground + .07f * radius);
        }
        return point;
    }

    /** overlay.js:86: a point of a jump's arc, a sine 1.2 hex radii high over the line between its ends. */
    private Vector3 arc(Vector3 a, Vector3 b, float t) {
        return lerp(a, b, t).add(0, 0, MathUtils.sin(MathUtils.PI * t) * 1.2f * radius);
    }

    /** A dash of the route, {@code distance} hex radii along it; the pulse lights it as it passes. */
    private void dash(Sink sink, Vector3 from, Vector3 to, float halfWidth, float distance, Color color,
          boolean pulsing) {
        segment(sink, from, to, halfWidth, color);
        if (pulsing) {
            pulse.mark(from, to, halfWidth, distance, color);
        }
    }

    /**
     * Hands the pulse the route's line from {@code a} to {@code b} as the route is drawn, kept over the ground or along
     * the jump's arc, from {@code from} hex radii along the route.
     */
    private void trace(Vector3 a, Vector3 b, boolean jump, float from, float length, Color color) {
        int steps = jump ? 24 : Math.max(1, MathUtils.ceil(length / .25f));
        for (int step = 0; step <= steps; step++) {
            float t = step / (float) steps;
            Vector3 point = jump ? arc(a, b, t) : above(a, b, t);
            float ground = UnitLandingSupports.surface(scene, point.x, point.y, surfaces);
            pulse.point(point, Float.isFinite(ground) ? ground : point.z - .07f * radius, from + t * length, color);
        }
    }

    private static Vector3 point(Vector3 center, Vector3 ahead, float along, Vector3 across, float aside) {
        return new Vector3(center).mulAdd(ahead, along).mulAdd(across, aside);
    }

    /**
     * The displayed weapon's range bands: each hex filled in its bracket's colour, and in 3D the handler's bracket
     * borders (overlay.js:51-53); in the Tactical View every band hex is outlined (flat.js:50-51). Short, medium and
     * long use the prototype's colours, minimum and extreme the client's field-of-fire colours; a border whose hex
     * has no bracket keeps the handler's colour.
     */
    private void rangeBands(Sink sink) {
        for (Map.Entry<Coords, Integer> entry : scene.rangeBands().entrySet()) {
            BoardScene.Tile tile = scene.tile(entry.getKey());
            if (tile == null) {
                continue;
            }
            Color color = rangeColor(entry.getValue(), 0);
            if (tactical) {
                fill(sink, tile, .05f, 0, alpha(color, .28f));
                ring(sink, tile, 1.2f * PIXEL, .05f - .6f * PIXEL, 0, alpha(color, .7f));
            } else {
                int bracket = entry.getValue();
                float strength = bracket == RangeType.RANGE_MEDIUM ? .11f
                      : bracket >= RangeType.RANGE_LONG ? .08f : .16f;
                fill(sink, tile, .045f, .03f, alpha(color, strength));
            }
        }
        if (tactical) {
            return;
        }
        for (BoardScene.RangeBorder border : scene.rangeBorders()) {
            BoardScene.Tile tile = scene.tile(border.coords());
            if (tile == null) {
                continue;
            }
            Color color = alpha(rangeColor(scene.rangeBands().get(border.coords()), border.rgb()), .75f);
            for (int direction = 0; direction < 6; direction++) {
                if ((border.edges() & 1 << direction) != 0) {
                    side(sink, tile, direction, .03f, false, color);
                }
            }
        }
    }

    private Color rangeColor(Integer bracket, int handlerRgb) {
        if (bracket == null) {
            return rgb(handlerRgb);
        }
        return switch (bracket) {
            case RangeType.RANGE_SHORT -> BANDS[0];
            case RangeType.RANGE_MEDIUM -> BANDS[1];
            case RangeType.RANGE_LONG -> BANDS[2];
            case RangeType.RANGE_MINIMUM -> rgb(minRangeRgb);
            default -> rgb(extremeRangeRgb);
        };
    }

    /**
     * overlay.js:66: the displayed weapon's mount arc as a flat mint wedge around the actor, from its start to its
     * end clockwise, measured from the facing the weapon fires from (E3b's WeaponArc).
     */
    private void wedge(Sink sink, GpuFireOrders.WeaponArc arc) {
        BoardScene.Tile tile = scene.tile(arc.origin());
        if (tile == null) {
            return;
        }
        Vector3 center = on(tile, BoardGeometry.center(arc.origin(), 0), .08f);
        // FacingArc's end below its start wraps through 0; 0 to 360 is all around.
        int degrees = arc.end() > arc.start() ? arc.end() - arc.start() : arc.end() + 360 - arc.start();
        float from = (arc.facing() * 60 + arc.start()) * MathUtils.degreesToRadians;
        float sweep = degrees * MathUtils.degreesToRadians / WEDGE_SEGMENTS;
        Color color = alpha(MINT, .22f);
        for (int step = 0; step < WEDGE_SEGMENTS; step++) {
            float a = from + step * sweep;
            float b = a + sweep;
            sink.quad(polar(center, a, WEDGE_INNER), polar(center, a, WEDGE_OUTER), polar(center, b, WEDGE_OUTER),
                  polar(center, b, WEDGE_INNER), color);
        }
    }

    /** The point {@code distance} hex radii from {@code center}, {@code angle} radians clockwise from north. */
    private Vector3 polar(Vector3 center, float angle, float distance) {
        return new Vector3(center).add(MathUtils.sin(angle) * distance * radius,
              MathUtils.cos(angle) * distance * radius, 0);
    }

    /**
     * overlay.js:56-57: a white ring on the hovered hex while no unit is hovered and no envelope is shown (see
     * {@link #update}), a bright one on a hovered unit, which also goes {@code behind}.
     */
    private void hover(Sink sink, Sink behind, Map<Integer, Set<Coords>> hexes, int actor) {
        if (hovered != null && scene.tile(hovered) != null) {
            ring(sink, scene.tile(hovered), .035f, .05f, .02f, alpha(Color.WHITE, .45f));
        }
        if (hoveredUnit != Entity.NONE && hoveredUnit != actor) {
            rings(sink, hexes.get(hoveredUnit), .06f, .05f, .06f, alpha(Color.WHITE, .85f));
            rings(behind, hexes.get(hoveredUnit), .06f, .05f, .06f, alpha(Color.WHITE, .85f));
        }
    }

    /** flat.js:27: a dark line on each hex side whose neighbour lies lower, thicker for a bigger drop. */
    private ModelInstance dropEdges() {
        Sink sink = new Sink(material(true));
        for (BoardScene.Tile tile : scene.tiles()) {
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile neighbour = scene.tile(tile.coords().translated(direction));
                if (neighbour != null && neighbour.elevation() < tile.elevation()) {
                    float width = Math.min(4, 1.2f + (tile.elevation() - neighbour.elevation()) * .9f) * PIXEL;
                    edge(sink, tile, direction, 1, width / 2, 0, false, DROP);
                }
            }
        }
        return sink.end();
    }

    private Color moveColor(GpuMovePlan.Band band) {
        return switch (band) {
            case WALK -> tactical ? UiTheme.MINT : MINT;
            case RUN -> tactical ? FLAT_RUN : RUN;
            case SPRINT -> rgb(sprintRgb);
            case JUMP -> VIOLET;
            case ILLEGAL -> CORAL;
        };
    }

    // Primitives. Sizes are in hex radii, as the prototype's (its hex radius is 1); dy lifts above the surface.

    private void rings(Sink sink, Set<Coords> coords, float width, float inset, float dy, Color color) {
        if (coords == null) {
            return;
        }
        for (Coords hex : coords) {
            BoardScene.Tile tile = scene.tile(hex);
            if (tile != null) {
                ring(sink, tile, width, inset, dy, color);
            }
        }
    }

    /** Engine.js hexFill: the hex shrunk by {@code inset} radii. */
    private void fill(Sink sink, BoardScene.Tile tile, float inset, float dy, Color color) {
        Vector3 center = on(tile, BoardGeometry.center(tile.coords(), 0), dy);
        Vector3[] corners = corners(tile, 1 - inset, dy);
        for (int corner = 0; corner < 6; corner++) {
            sink.triangle(center, corners[corner], corners[(corner + 1) % 6], color);
        }
    }

    /** Engine.js hexRing: {@code width} radii wide, its outer edge {@code inset} radii inside the hex edge. */
    private void ring(Sink sink, BoardScene.Tile tile, float width, float inset, float dy, Color color) {
        Vector3[] outer = corners(tile, 1 - inset, dy);
        Vector3[] inner = corners(tile, 1 - inset - width, dy);
        for (int corner = 0; corner < 6; corner++) {
            int next = (corner + 1) % 6;
            sink.quad(outer[corner], outer[next], inner[next], inner[corner], color);
        }
    }

    /** The border of a set of hexes: the sides whose neighbour is outside it (overlay.js outlineSet). */
    private void outline(Sink sink, Set<Coords> set, float halfWidth, boolean dashed, Color color) {
        for (Coords coords : set) {
            BoardScene.Tile tile = scene.tile(coords);
            if (tile == null) {
                continue;
            }
            for (int direction = 0; direction < 6; direction++) {
                if (!set.contains(coords.translated(direction))) {
                    side(sink, tile, direction, halfWidth, dashed, color);
                }
            }
        }
    }

    /** A side of a set's border, just inside the hex edge (overlay.js outlineSet). */
    private void side(Sink sink, BoardScene.Tile tile, int direction, float halfWidth, boolean dashed, Color color) {
        edge(sink, tile, direction, .97f, halfWidth, .035f, dashed, color);
    }

    /**
     * The side of a hex toward MegaMek direction {@code direction} at {@code fraction} of the radius: solid, or the
     * prototype's three dashes of 20 % every 34 %.
     */
    private void edge(Sink sink, BoardScene.Tile tile, int direction, float fraction, float halfWidth, float dy,
          boolean dashed, Color color) {
        Vector3[] corners = corners(tile, fraction, dy);
        int edge = Math.floorMod(1 - direction, 6);
        Vector3 a = corners[edge];
        Vector3 b = corners[(edge + 1) % 6];
        if (!dashed) {
            segment(sink, a, b, halfWidth, color);
            return;
        }
        for (float t = 0; t < 1; t += .34f) {
            segment(sink, lerp(a, b, t), lerp(a, b, Math.min(1, t + .2f)), halfWidth, color);
        }
    }

    /** A flat band from {@code a} to {@code b}, {@code halfWidth} radii to each side (engine.js seg). */
    private void segment(Sink sink, Vector3 a, Vector3 b, float halfWidth, Color color) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float length = (float) Math.hypot(dx, dy);
        if (length < .001f) {
            return;
        }
        float scale = halfWidth * radius / length;
        float x = -dy * scale;
        float y = dx * scale;
        sink.quad(new Vector3(a.x + x, a.y + y, a.z), new Vector3(a.x - x, a.y - y, a.z),
              new Vector3(b.x - x, b.y - y, b.z), new Vector3(b.x + x, b.y + y, b.z), color);
    }

    private void disc(Sink sink, Vector3 center, float discRadius, int segments, Color color) {
        float size = discRadius * radius;
        for (int i = 0; i < segments; i++) {
            float from = MathUtils.PI2 * i / segments;
            float to = MathUtils.PI2 * (i + 1) / segments;
            sink.triangle(center, new Vector3(center).add(MathUtils.cos(from) * size, MathUtils.sin(from) * size, 0),
                  new Vector3(center).add(MathUtils.cos(to) * size, MathUtils.sin(to) * size, 0), color);
        }
    }

    /** The six corners of a hex scaled by {@code fraction} about its centre, on its surface. */
    private Vector3[] corners(BoardScene.Tile tile, float fraction, float dy) {
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        Vector3[] corners = new Vector3[6];
        for (int corner = 0; corner < 6; corner++) {
            corners[corner] = on(tile, BoardGeometry.inset(BoardGeometry.corner(tile.coords(), 0, corner), center,
                  1 - fraction), dy);
        }
        return corners;
    }

    /**
     * Sets a point's height to its hex's own surface there, water included, as units stand on it, lifted by
     * {@code dy} radii. In the hex's footprint that is the drawn ground, which beside a step can be the step's face
     * lying back over the hex rather than its top (the user's report of 2026-10-03: the envelope's border lay under
     * those faces). The hex's own surface keeps a ring that reaches past the hex edge at the hex's level.
     */
    private Vector3 on(BoardScene.Tile tile, Vector3 point, float dy) {
        float ground = tile.frozen() ? BoardGeometry.surfaceZ(tile)
              : BoardGeometry.contains(tile.coords(), point.x, point.y)
              ? UnitLandingSupports.surface(scene, point.x, point.y, surfaces)
              : surfaces.get(scene, tile).height(point.x, point.y);
        if (tile.liquid().present()) {
            ground = Math.max(ground, BoardGeometry.waterZ(tile));
        }
        point.z = ground + dy * radius;
        return point;
    }

    private static Vector3 lerp(Vector3 a, Vector3 b, float t) {
        return new Vector3(a).lerp(b, t);
    }

    private static Color rgb(int rgb) {
        return new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1);
    }

    /**
     * Unlit vertex colours blended in drawing order, without writing depth: depth-tested against the terrain and
     * models in 3D, over everything in the flat Tactical View, where the unit icons are drawn after it.
     */
    private static Material material(boolean flat) {
        return new Material(ColorAttribute.createDiffuse(Color.WHITE),
              new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
              new DepthTestAttribute(flat ? GL20.GL_ALWAYS : GL20.GL_LEQUAL, false),
              IntAttribute.createCullFace(GL20.GL_NONE));
    }

    /** {@link #material}'s marks where the terrain or a model hides them: only behind the drawn depth, faintly. */
    private static Material hiddenMaterial() {
        return new Material(ColorAttribute.createDiffuse(Color.WHITE),
              new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, HIDDEN_OPACITY),
              new DepthTestAttribute(GL20.GL_GREATER, false), IntAttribute.createCullFace(GL20.GL_NONE));
    }

    @Override
    public void dispose() {
        if (overlay != null) {
            overlay.model.dispose();
            overlay = null;
        }
        if (hidden != null) {
            hidden.model.dispose();
            hidden = null;
        }
        if (dropEdges != null) {
            dropEdges.model.dispose();
            dropEdges = null;
        }
        dropTiles = null;
        // The ghost owns no GL object: its meshes and textures are the shown model's.
        ghost = null;
        ghostSource = null;
        pulse.dispose();
        surfaces.clear();
        batch.dispose();
    }

    /** One model of vertex-coloured triangles, in parts small enough for 16-bit indices. */
    private static final class Sink {
        /** ModelBuilder starts a new mesh once one holds 32768 vertices, so a part may add this many more. */
        private static final int PART_VERTICES = 30000;
        private final ModelBuilder builder = new ModelBuilder();
        private final Material material;
        private MeshPartBuilder part;
        private int vertices;
        private int parts;

        Sink(Material material) {
            this.material = material;
            builder.begin();
        }

        void triangle(Vector3 a, Vector3 b, Vector3 c, Color color) {
            MeshPartBuilder mesh = reserve(3);
            mesh.triangle(mesh.vertex(a, null, color, null), mesh.vertex(b, null, color, null),
                  mesh.vertex(c, null, color, null));
        }

        void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Color color) {
            MeshPartBuilder mesh = reserve(4);
            short first = mesh.vertex(a, null, color, null);
            short second = mesh.vertex(b, null, color, null);
            short third = mesh.vertex(c, null, color, null);
            short fourth = mesh.vertex(d, null, color, null);
            mesh.triangle(first, second, third);
            mesh.triangle(first, third, fourth);
        }

        private MeshPartBuilder reserve(int count) {
            if (part == null || vertices + count > PART_VERTICES) {
                part = builder.part("overlay-" + parts++, GL20.GL_TRIANGLES,
                      VertexAttributes.Usage.Position | VertexAttributes.Usage.ColorPacked, material);
                vertices = 0;
            }
            vertices += count;
            return part;
        }

        /** The finished model, or null when nothing was drawn. */
        ModelInstance end() {
            Model model = builder.end();
            if (parts == 0) {
                model.dispose();
                return null;
            }
            return new ModelInstance(model);
        }
    }
}
