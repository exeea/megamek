/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;

/** One orbit camera with top/isometric presets and shared geometry for every orientation. */
final class BoardCamera {
    private static final float ISOMETRIC_TILT = 54.73561f;
    // Manual zoom limits: smaller values zoom in, larger values zoom out.
    private static final float MIN_ZOOM = 0.05f;
    private static final float MAX_ZOOM = 20f;
    static final float ENTRANCE_SECONDS = 1.2f;
    private static final float ENTRANCE_ZOOM = 1.35f;
    static final float MAX_TILT = 80;
    static final float DEFAULT_FIELD_OF_VIEW = 45;
    static final float MIN_FIELD_OF_VIEW = 20;
    static final float MAX_FIELD_OF_VIEW = 100;
    /** One keyboard turn. Hex rows line up again every sixth of a circle, so each turn lands on a matching view. */
    static final float ROTATION_STEP = 60;
    static final float ROTATION_SECONDS = 0.25f;
    /** Initial Camera-menu settings; false applies the same framing immediately. */
    static final boolean ANIMATE_CAMERA_ON_SELECTION_CHANGE = true;
    static final boolean ANIMATE_CAMERA_COMBAT_PLAYBACK = true;
    static final boolean ANIMATE_CAMERA_ON_MOVE = true;
    /** Maximum wall-clock seconds for automatic framing, independent of playback speed. Zero snaps. */
    static final float CAMERA_FRAMING_SECONDS = .4f;
    /** Degrees from overhead: at or below this tilt, keep visible actions still; otherwise only pan or zoom out. */
    static final float ATTACK_TOP_VIEW_TILT_DEGREES = 30;
    private static final float FRAMING_MARGIN_PIXELS = 64;
    final BoardProjectionCamera camera = new BoardProjectionCamera();
    final Vector3 focus = new Vector3();
    // Render-thread settings shared by both views and their Camera-menu checkboxes.
    boolean animateOnSelectionChange = ANIMATE_CAMERA_ON_SELECTION_CHANGE;
    boolean animateCombatPlayback = ANIMATE_CAMERA_COMBAT_PLAYBACK;
    boolean animateOnMove = ANIMATE_CAMERA_ON_MOVE;
    private float azimuth;
    private float tilt;
    private float overviewZoom;
    private Vector3 overviewFocus;
    private boolean overviewFit;
    private boolean fitToWindow;
    private float displayScale = 1;
    private float entranceElapsed = ENTRANCE_SECONDS;
    private float entranceZoom;
    /** Screen composition only: focus remains the world-space orbit pivot in the unobstructed board area. */
    private float viewOffsetPixels;
    private float viewLeftPixels;
    private long revision;
    private float rotationStart;
    private float rotationSweep;
    private float rotationTarget;
    private float rotationElapsed = ROTATION_SECONDS;
    /** Render-owned endpoints of one camera move; later volley targets keep its original deadline. */
    private record Pose(Vector3 focus, float zoom, float azimuth, float tilt) {
        boolean sameAs(Pose other) {
            return other != null && focus.epsilonEquals(other.focus(), .001f)
                  && MathUtils.isEqual(zoom, other.zoom()) && MathUtils.isEqual(azimuth, other.azimuth())
                  && MathUtils.isEqual(tilt, other.tilt());
        }
    }
    private Pose framingStart;
    private Pose framingTarget;
    private float framingElapsed;
    private float framingStartTime;
    private Object framedAction;
    private int framedCount;
    private float framedWidth, framedLeft, framedViewportWidth, framedHeight;
    private int framedGeometry;

    BoardCamera() {
        camera.near = 1;
        camera.far = 100000;
        camera.zoom = 1;
    }

    /** Projected size at the orbit pivot, including framebuffer/display scaling. */
    static float pixelsPerUnit(Camera camera) {
        float scale = Gdx.graphics == null ? 1
              : Gdx.graphics.getBackBufferHeight() / (float) Math.max(1, Gdx.graphics.getHeight());
        return scale / worldUnitsPerPixel(camera);
    }

    static float worldUnitsPerPixel(Camera camera) {
        Vector3 point = camera instanceof BoardProjectionCamera board
              ? new Vector3(camera.position).mulAdd(camera.direction, board.distance()) : camera.position;
        return worldUnitsPerPixel(camera, point);
    }

    static float pixelsPerUnit(Camera camera, Vector3 point) {
        float scale = Gdx.graphics == null ? 1
              : Gdx.graphics.getBackBufferHeight() / (float) Math.max(1, Gdx.graphics.getHeight());
        return scale / worldUnitsPerPixel(camera, point);
    }

    /** World distance corresponding to one logical screen pixel at the supplied point's camera depth. */
    static float worldUnitsPerPixel(Camera camera, Vector3 point) {
        if (camera instanceof OrthographicCamera orthographic) { return orthographic.zoom; }
        if (camera instanceof BoardProjectionCamera board && !board.perspective) { return board.zoom; }
        float fieldOfView = camera instanceof BoardProjectionCamera board ? board.fieldOfView
              : camera instanceof PerspectiveCamera perspective ? perspective.fieldOfView : DEFAULT_FIELD_OF_VIEW;
        float depth = new Vector3(point).sub(camera.position).dot(camera.direction);
        return 2 * Math.max(camera.near, depth) * (float) Math.tan(Math.toRadians(fieldOfView / 2))
              / Math.max(1, camera.viewportHeight);
    }

    /** Bounds of visible points within a horizontal slab; horizon-crossing views conservatively retain it all. */
    static BoundingBox viewportBounds(Camera view, BoundingBox bounds) {
        if (view == null) { return new BoundingBox(bounds); }
        BoundingBox visible = new BoundingBox().inf();
        for (int x : new int[] { -1, 1 }) {
            for (int y : new int[] { -1, 1 }) {
                Vector3 origin = new Vector3(x, y, -1).prj(view.invProjectionView);
                Vector3 ray = new Vector3(x, y, 1).prj(view.invProjectionView).sub(origin).nor();
                if (ray.z >= -.00001f || origin.z < bounds.max.z) { return new BoundingBox(bounds); }
                for (float z : new float[] { bounds.min.z, bounds.max.z }) {
                    visible.ext(new Vector3(origin).mulAdd(ray, (z - origin.z) / ray.z));
                }
            }
        }
        visible.min.x = MathUtils.clamp(visible.min.x, bounds.min.x, bounds.max.x);
        visible.min.y = MathUtils.clamp(visible.min.y, bounds.min.y, bounds.max.y);
        visible.max.x = MathUtils.clamp(visible.max.x, bounds.min.x, bounds.max.x);
        visible.max.y = MathUtils.clamp(visible.max.y, bounds.min.y, bounds.max.y);
        visible.min.z = bounds.min.z;
        visible.max.z = bounds.max.z;
        visible.update();
        return visible;
    }

    void setPerspective(boolean value) {
        if (camera.perspective == value) { return; }
        stopFraming();
        fitToWindow = false;
        camera.perspective = value;
        update();
    }

    boolean perspective() {
        return camera.perspective;
    }

    void setFieldOfView(float value) {
        if (!Float.isFinite(value)) { return; }
        float clamped = MathUtils.clamp(value, MIN_FIELD_OF_VIEW, MAX_FIELD_OF_VIEW);
        if (MathUtils.isEqual(camera.fieldOfView, clamped)) { return; }
        stopFraming();
        fitToWindow = false;
        camera.fieldOfView = clamped;
        update();
    }

    float fieldOfView() {
        return camera.fieldOfView;
    }

    void resize(int width, int height) {
        resize(width, height, null, 1);
    }

    void resize(int width, int height, BoardScene scene, float scale) {
        camera.viewportWidth = Math.max(1, width);
        camera.viewportHeight = Math.max(1, height);
        camera.zoom *= displayScale / scale;
        overviewZoom *= displayScale / scale;
        displayScale = scale;
        if (fitToWindow && scene != null) {
            float elapsed = entranceElapsed;
            fit(scene);
            if (elapsed < ENTRANCE_SECONDS) {
                entranceElapsed = elapsed;
                entranceZoom = camera.zoom;
                updateEntrance();
            }
        }
        update();
    }

    /** The UI owns the unobstructed area's left edge; later framing uses the same area. */
    void viewableArea(float left, float availableWidth) {
        viewLeftPixels = left;
        viewableWidth(availableWidth);
    }

    /** Move the orbit pivot to the usable area's center without moving the displayed board. */
    void viewableWidth(float availableWidth) {
        float offset = (camera.viewportWidth - MathUtils.clamp(availableWidth, 1, camera.viewportWidth)) / 2 - viewLeftPixels;
        if (MathUtils.isEqual(viewOffsetPixels, offset)) { return; }
        float shift = viewOffsetPixels - offset;
        Vector3 right = new Vector3(camera.direction).crs(camera.up).nor();
        focus.mulAdd(right, shift * camera.zoom);
        if (framingTarget != null) {
            Vector3 outward = new Vector3(), up = new Vector3();
            orientation(framingTarget.azimuth(), framingTarget.tilt(), outward, up);
            framingTarget.focus().mulAdd(up.crs(outward).nor(), shift * framingTarget.zoom());
            framingStart = new Pose(focus.cpy(), camera.zoom, azimuth, tilt);
            framingStartTime = framingElapsed;
        }
        viewOffsetPixels = offset;
        update();
    }

    void setIsometric(boolean value) {
        stopFraming();
        stopRotation();
        azimuth = value ? 45 : 0;
        tilt = value ? ISOMETRIC_TILT : 0;
        update();
    }

    boolean isIsometric() {
        return MathUtils.isEqual(azimuth, 45) && MathUtils.isEqual(tilt, ISOMETRIC_TILT);
    }

    boolean isTopDown() {
        return tilt == 0;
    }

    float tilt() {
        return tilt;
    }

    float azimuth() {
        return azimuth;
    }

    long revision() {
        return revision;
    }

    void orbit(float rotation, float inclination) {
        stopRotation();
        azimuth = wrapDegrees(azimuth + rotation);
        tilt(inclination);
    }

    /** Changes only the viewing angle, so holding a tilt key does not interrupt a keyboard turn in progress. */
    void tilt(float inclination) {
        stopFraming();
        fitToWindow = false;
        tilt = MathUtils.clamp(tilt + inclination, 0, MAX_TILT);
        update();
    }

    /**
     * Starts an eased keyboard turn of one {@link #ROTATION_STEP}. A turn requested while another is still playing
     * is added to it, so quick taps queue up and an opposite tap turns back.
     *
     * @param direction {@code -1} to turn left, {@code 1} to turn right
     */
    void rotateStep(int direction) {
        stopFraming();
        fitToWindow = false;
        float remaining = isRotating() ? rotationSweep * (1 - rotationProgress()) : 0;
        // The target is tracked apart from the eased path so that whole steps from a preset land exactly on it again.
        rotationTarget = wrapDegrees((isRotating() ? rotationTarget : azimuth) + direction * ROTATION_STEP);
        rotationStart = azimuth;
        rotationSweep = remaining + direction * ROTATION_STEP;
        rotationElapsed = 0;
    }

    boolean isRotating() {
        return rotationElapsed < ROTATION_SECONDS;
    }

    /** Advances a camera transition in wall-clock time, independently of the combat playback speed. */
    void advance(float seconds) {
        if (entranceElapsed < ENTRANCE_SECONDS) {
            entranceElapsed = Math.min(ENTRANCE_SECONDS, entranceElapsed + Math.max(0, seconds));
            updateEntrance();
            update();
            return;
        }
        if (framingTarget != null) {
            boolean animate = framedAction instanceof UnitAttack ? animateCombatPlayback
                  : framedAction instanceof BoardScene.Movement ? animateOnMove : animateOnSelectionChange;
            framingElapsed = animate ? Math.min(framingElapsed + Math.max(0, seconds), CAMERA_FRAMING_SECONDS)
                  : CAMERA_FRAMING_SECONDS;
            float remaining = CAMERA_FRAMING_SECONDS - framingStartTime;
            float progress = remaining <= 0 ? 1 : Interpolation.smooth.apply((framingElapsed - framingStartTime) / remaining);
            focus.set(framingStart.focus()).lerp(framingTarget.focus(), progress);
            camera.zoom = MathUtils.lerp(framingStart.zoom(), framingTarget.zoom(), progress);
            azimuth = MathUtils.lerpAngleDeg(framingStart.azimuth(), framingTarget.azimuth(), progress);
            tilt = MathUtils.lerp(framingStart.tilt(), framingTarget.tilt(), progress);
            if (framingElapsed >= CAMERA_FRAMING_SECONDS) {
                framingStart = null;
                framingTarget = null;
            }
            update();
            return;
        }
        if (!isRotating()) {
            return;
        }
        rotationElapsed = Math.min(rotationElapsed + seconds, ROTATION_SECONDS);
        azimuth = isRotating() ? wrapDegrees(rotationStart + rotationSweep * rotationProgress()) : rotationTarget;
        update();
    }

    private float rotationProgress() {
        return Interpolation.smooth.apply(rotationElapsed / ROTATION_SECONDS);
    }

    private void stopRotation() {
        rotationElapsed = ROTATION_SECONDS;
    }

    boolean isFraming() {
        return framingTarget != null;
    }

    private void stopFraming() {
        entranceElapsed = ENTRANCE_SECONDS;
        framingStart = null;
        framingTarget = null;
        framingElapsed = CAMERA_FRAMING_SECONDS;
    }

    void clearPlaybackFrame() {
        if (framedAction != null) {
            stopFraming();
            framedAction = null;
        }
    }

    /** A render-owned action identity prevents repeated frames and late packets from restarting the deadline. */
    private boolean beginFrame(Object action, int count, float width) {
        boolean changedAction = framedAction != action;
        if (!changedAction && framedCount == count && framedWidth == width && framedLeft == viewLeftPixels
              && framedViewportWidth == camera.viewportWidth && framedHeight == camera.viewportHeight
              && framedGeometry == BoardGeometry.revision()) {
            return false;
        }
        if (changedAction) {
            stopFraming();
            framingElapsed = 0;
        }
        framedAction = action;
        framedCount = count;
        framedWidth = width;
        framedLeft = viewLeftPixels;
        framedViewportWidth = camera.viewportWidth;
        framedHeight = camera.viewportHeight;
        framedGeometry = BoardGeometry.revision();
        return true;
    }

    /** Fit authorized volley participants into the board area to the left of any open side panel. */
    void frameAttacks(List<UnitAttack> attacks, float availableWidth) {
        if (attacks.isEmpty()) {
            clearPlaybackFrame();
            return;
        }
        float width = MathUtils.clamp(availableWidth, 1, camera.viewportWidth);
        viewableWidth(width);
        if (!beginFrame(attacks.getFirst(), attacks.size(), width)) { return; }
        List<Vector3> points = new ArrayList<>();
        Vector3 axis = new Vector3();
        for (var attack : attacks) {
            var event = attack.event;
            addUnit(points, event.attacker());
            if (event.target() != null) {
                addUnit(points, event.target());
            } else {
                addHex(points, event.destination().coords(), event.destination().elevation() - .5f,
                      event.destination().elevation() + .5f);
            }
            Vector3 direction = BoardGeometry.center(event.destination().coords(), 0)
                  .sub(BoardGeometry.center(event.attacker().location().coords(), 0));
            if (direction.len2() > axis.len2()) { axis.set(direction); }
        }
        // Show the longest shot across the usable area's long axis, choosing the nearer of the two sides.
        float bearing = azimuth;
        boolean topView = tilt <= ATTACK_TOP_VIEW_TILT_DEGREES;
        if (!topView && !axis.isZero(.001f)) {
            bearing = MathUtils.atan2(axis.y, axis.x) * MathUtils.radiansToDegrees;
            if (width < camera.viewportHeight) { bearing -= 90; }
            float turn = (wrapDegrees(bearing - azimuth) + 90) % 180 - 90;
            bearing = wrapDegrees(azimuth + turn);
        }
        float inclination = topView ? tilt : Math.min(tilt, ISOMETRIC_TILT);
        float plane = (float) attacks.stream().mapToDouble(attack ->
              (attack.event.attacker().location().elevation() + attack.event.destination().elevation()) / 2)
              .average().orElse(0) * BoardGeometry.LEVEL;
        animateTo(fittedPose(points, width, bearing, inclination, topView ? camera.zoom : .5f / displayScale, !topView, plane),
              plane, animateCombatPlayback);
    }

    /** Keep the chosen viewing angle and zoom, widening only when the selected unit cannot fit. */
    void frameSelection(BoardScene.Unit unit, float availableWidth) {
        viewableWidth(availableWidth);
        clearPlaybackFrame();
        List<Vector3> points = new ArrayList<>();
        addUnit(points, unit);
        animateTo(fittedPose(points, availableWidth, azimuth, tilt, camera.zoom, tilt > ATTACK_TOP_VIEW_TILT_DEGREES,
                    unit.location().elevation() * BoardGeometry.LEVEL),
              unit.location().elevation() * BoardGeometry.LEVEL, animateOnSelectionChange);
    }

    /** Explicit hex navigation shares the selection transition and animation setting. */
    void frameLocation(Vector3 position, float availableWidth) {
        viewableWidth(availableWidth);
        clearPlaybackFrame();
        animateTo(new Pose(position.cpy(), camera.zoom, azimuth, tilt), position.z, animateOnSelectionChange);
    }

    /** Fit the complete rendered route once, with the smallest pan and no unnecessary zoom or rotation. */
    void frameMovement(BoardScene.Movement move, UnitMotion motion, BoardScene scene, float availableWidth) {
        float width = MathUtils.clamp(availableWidth, 1, camera.viewportWidth);
        viewableWidth(width);
        if (!beginFrame(move, move.path().size(), width)) { return; }
        var unit = move.unit() != null ? move.unit() : scene.units().stream()
              .filter(candidate -> candidate.id() == move.entityId()).findFirst().orElse(null);
        List<Vector3> points = new ArrayList<>();
        if (unit != null && motion != null && motion.isMoving()) {
            for (var pose : motion.framingPath(scene, unit)) {
                for (var occupied : unit.footprint()) {
                    for (int corner = 0; corner < 6; corner++) {
                        var base = pose.outlinePoint(occupied, corner, 0);
                        points.add(base.cpy().add(0, 0, -.25f * BoardGeometry.LEVEL));
                        points.add(base.add(0, 0, Math.max(1, unit.height() + 1) * BoardGeometry.LEVEL));
                    }
                }
            }
        }
        if (points.isEmpty()) {
            for (var point : move.path()) {
                addHex(points, point.coords(), point.elevation() - .25f, point.elevation() + 3);
            }
        }
        if (!points.isEmpty()) {
            animateTo(fittedPose(points, width, azimuth, tilt, camera.zoom, false,
                        move.path().getFirst().elevation() * BoardGeometry.LEVEL),
                  move.path().getFirst().elevation() * BoardGeometry.LEVEL, animateOnMove);
        }
    }

    private Pose fittedPose(List<Vector3> points, float availableWidth, float bearing, float inclination,
          float minimumZoom, boolean centered, float plane) {
        if (camera.perspective) {
            return perspectiveFit(points, availableWidth, bearing, inclination, minimumZoom, centered, plane);
        }
        float width = MathUtils.clamp(availableWidth, 1, camera.viewportWidth);
        Vector3 outward = new Vector3(), up = new Vector3();
        orientation(bearing, inclination, outward, up);
        Vector3 right = new Vector3(up).crs(outward).nor();
        Vector3 origin = focus;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (Vector3 point : points) {
            Vector3 relative = new Vector3(point).sub(origin);
            float x = relative.dot(right), y = relative.dot(up);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (!centered && minX >= -width * camera.zoom / 2
              && maxX <= width * camera.zoom / 2
              && minY >= -camera.viewportHeight * camera.zoom / 2 && maxY <= camera.viewportHeight * camera.zoom / 2) {
            return new Pose(focus.cpy(), camera.zoom, azimuth, tilt);
        }
        float margin = Math.min(FRAMING_MARGIN_PIXELS * displayScale, Math.min(width, camera.viewportHeight) * .2f);
        float zoom = Math.max(minimumZoom, Math.max((maxX - minX) / (width - 2 * margin),
              (maxY - minY) / (camera.viewportHeight - 2 * margin)));
        float x = (minX + maxX) / 2;
        float y = (minY + maxY) / 2;
        if (!centered) {
            // Clamp the current pivot to the interval that fits the route, rather than centering it.
            x = MathUtils.clamp(0, maxX - (width / 2 - margin) * zoom,
                  minX + (width / 2 - margin) * zoom);
            y = MathUtils.clamp(0, maxY - (camera.viewportHeight / 2 - margin) * zoom,
                  minY + (camera.viewportHeight / 2 - margin) * zoom);
        }
        return new Pose(new Vector3(origin).mulAdd(right, x).mulAdd(up, y), zoom, bearing, inclination);
    }

    /** Fits depth as well as screen extent, including the off-center orbit pivot beside open panels. */
    private Pose perspectiveFit(List<Vector3> points, float availableWidth, float bearing, float inclination,
          float minimumZoom, boolean centered, float plane) {
        float width = MathUtils.clamp(availableWidth, 1, camera.viewportWidth);
        if (!centered && points.stream().allMatch(point -> insideView(point, width))) {
            return new Pose(focus.cpy(), camera.zoom, azimuth, tilt);
        }
        Vector3 outward = new Vector3(), up = new Vector3();
        orientation(bearing, inclination, outward, up);
        Vector3 right = new Vector3(up).crs(outward).nor();
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (Vector3 point : points) {
            Vector3 relative = new Vector3(point).sub(focus);
            float x = relative.dot(right), y = relative.dot(up);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        Vector3 pivot = new Vector3(focus).mulAdd(right, (minX + maxX) / 2).mulAdd(up, (minY + maxY) / 2);
        pivot.mulAdd(outward, (plane - pivot.z) / outward.z);
        float margin = Math.min(FRAMING_MARGIN_PIXELS * displayScale, Math.min(width, camera.viewportHeight) * .2f);
        float halfWidth = width / 2 - margin, halfHeight = camera.viewportHeight / 2 - margin;
        float focalLength = camera.viewportHeight / (2 * (float) Math.tan(Math.toRadians(camera.fieldOfView / 2)));
        float distancePerZoom = camera.distance() / camera.zoom;
        float distance = Math.max(camera.near, minimumZoom * distancePerZoom);
        if (!centered) {
            Vector3 panned = perspectivePivot(points, right, up, outward, distance, focalLength, halfWidth, halfHeight, plane);
            if (panned != null) { return new Pose(panned, distance / distancePerZoom, bearing, inclination); }
        }
        for (Vector3 point : points) {
            Vector3 relative = new Vector3(point).sub(pivot);
            float depth = relative.dot(outward);
            float horizontal = Math.abs(focalLength * relative.dot(right) - viewOffsetPixels * depth) / halfWidth;
            float vertical = Math.abs(focalLength * relative.dot(up)) / halfHeight;
            distance = Math.max(distance, depth + Math.max(camera.near * 2, Math.max(horizontal, vertical)));
        }
        if (!centered) {
            Vector3 panned = perspectivePivot(points, right, up, outward, distance * 1.00001f,
                  focalLength, halfWidth, halfHeight, plane);
            if (panned != null) { pivot = panned; distance *= 1.00001f; }
        }
        return new Pose(pivot, distance / distancePerZoom, bearing, inclination);
    }

    /** Find the smallest pan at a fixed distance, keeping the orbit pivot on the action's support plane. */
    private Vector3 perspectivePivot(List<Vector3> points, Vector3 right, Vector3 up, Vector3 outward,
          float distance, float focalLength, float halfWidth, float halfHeight, float plane) {
        float depthOffset = (plane - focus.z) / outward.z, slope = up.z / outward.z;
        float lowerX = Float.NEGATIVE_INFINITY, upperX = Float.POSITIVE_INFINITY;
        float[] vertical = { Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY };
        for (Vector3 point : points) {
            Vector3 relative = new Vector3(point).sub(focus);
            float x = relative.dot(right), y = relative.dot(up), depth = relative.dot(outward) - depthOffset;
            lowerX = Math.max(lowerX, x - halfWidth * distance / focalLength
                  + (halfWidth - viewOffsetPixels) * depth / focalLength);
            upperX = Math.min(upperX, x + halfWidth * distance / focalLength
                  - (halfWidth + viewOffsetPixels) * depth / focalLength);
            if (!restrict(vertical, halfHeight * slope - focalLength, halfHeight * (distance - depth) - focalLength * y)
                  || !restrict(vertical, halfHeight * slope + focalLength, halfHeight * (distance - depth) + focalLength * y)
                  || !restrict(vertical, slope, distance - depth - 2 * camera.near)) { return null; }
        }
        if (!restrict(vertical, 2 * halfWidth * slope / focalLength, upperX - lowerX)) { return null; }
        float y = MathUtils.clamp(0, vertical[0], vertical[1]);
        float x = MathUtils.clamp(0, lowerX + (halfWidth - viewOffsetPixels) * slope * y / focalLength,
              upperX - (halfWidth + viewOffsetPixels) * slope * y / focalLength);
        return focus.cpy().mulAdd(right, x).mulAdd(up, y).mulAdd(outward, depthOffset - slope * y);
    }

    /** Intersect a one-dimensional interval with coefficient * value <= limit. */
    private static boolean restrict(float[] interval, float coefficient, float limit) {
        if (Math.abs(coefficient) < .000001f) { return limit >= -.00001f; }
        if (coefficient > 0) {
            interval[1] = Math.min(interval[1], limit / coefficient);
        } else {
            interval[0] = Math.max(interval[0], limit / coefficient);
        }
        return interval[0] <= interval[1];
    }

    private boolean insideView(Vector3 point, float width) {
        if (new Vector3(point).sub(camera.position).dot(camera.direction) <= camera.near) { return false; }
        Vector3 screen = camera.project(new Vector3(point), 0, 0, camera.viewportWidth, camera.viewportHeight);
        return screen.x >= viewLeftPixels && screen.x <= viewLeftPixels + width
              && screen.y >= 0 && screen.y <= camera.viewportHeight;
    }

    private void animateTo(Pose target, float plane, boolean animate) {
        var current = new Pose(focus.cpy(), camera.zoom, azimuth, tilt);
        if (current.sameAs(target)) {
            stopFraming();
            return;
        }
        // The projected fit does not determine depth. Put its orbit pivot on the action's support plane,
        // sliding along the viewing ray so the fit stays unchanged on screen.
        Vector3 outward = new Vector3(), up = new Vector3();
        orientation(target.azimuth(), target.tilt(), outward, up);
        if (!camera.perspective) {
            target.focus().mulAdd(outward, (plane - target.focus().z) / outward.z);
        }
        if (target.sameAs(framingTarget)) {
            return; // Repeated selection/centering notifications keep the same move and its original deadline.
        }
        if (framedAction == null) { framingElapsed = 0; }
        entranceElapsed = ENTRANCE_SECONDS;
        stopRotation();
        fitToWindow = false;
        framingStart = current;
        framingTarget = target;
        framingStartTime = framingElapsed;
        if (!animate) { framingElapsed = CAMERA_FRAMING_SECONDS; }
        if (framingElapsed >= CAMERA_FRAMING_SECONDS) { advance(0); }
    }

    private static void addUnit(List<Vector3> points, BoardScene.Unit unit) {
        var footprint = unit.footprint().isEmpty() ? List.of(unit.location().coords()) : unit.footprint();
        for (var coords : footprint) {
            addHex(points, coords, unit.location().elevation() - .25f,
                  unit.location().elevation() + Math.max(1, unit.height() + 1));
        }
    }

    private static void addHex(List<Vector3> points, Coords coords, float bottom, float top) {
        for (int corner = 0; corner < 6; corner++) {
            points.add(BoardGeometry.corner(coords, bottom, corner));
            points.add(BoardGeometry.corner(coords, top, corner));
        }
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360;
        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    void reset(BoardScene scene) {
        overviewFocus = null;
        setIsometric(true);
        fit(scene);
    }

    /** A presentation-only entrance; manual camera input immediately takes control. */
    void enter(BoardScene scene) {
        reset(scene);
        entranceZoom = camera.zoom;
        entranceElapsed = 0;
        updateEntrance();
        update();
    }

    float entranceOpacity() {
        return Interpolation.pow3Out.apply(entranceElapsed / ENTRANCE_SECONDS);
    }

    private void updateEntrance() {
        camera.zoom = MathUtils.lerp(entranceZoom * ENTRANCE_ZOOM, entranceZoom, entranceOpacity());
    }

    void zoom(float factor) {
        stopFraming();
        fitToWindow = false;
        camera.zoom = MathUtils.clamp(camera.zoom * factor, MIN_ZOOM, MAX_ZOOM);
        update();
    }

    /** Zoom around the pointer's position on the focus plane. Coordinates are local to the board viewport. */
    void zoomAt(float factor, float x, float y) {
        if (camera.perspective) {
            Vector3 before = pointOnPlane(x, y, focus.z);
            zoom(factor);
            Vector3 after = pointOnPlane(x, y, focus.z);
            if (before != null && after != null) { focus.add(before.sub(after)); }
            update();
            return;
        }
        float before = camera.zoom;
        zoom(factor);
        float difference = before - camera.zoom;
        moveOnBoard((x - camera.viewportWidth / 2 + viewOffsetPixels) * difference,
              (y - camera.viewportHeight / 2) * difference);
        update();
    }

    void fit(BoardScene scene) {
        stopFraming();
        fitToWindow = true;
        focus.set(scene.width() * BoardGeometry.WIDTH * 0.375f,
              -(scene.height() + 0.5f) * BoardGeometry.HEIGHT / 2, 0);
        update();
        if (camera.perspective) {
            List<Vector3> points = new ArrayList<>();
            float floor = BoardGeometry.floor(scene) / BoardGeometry.LEVEL;
            for (BoardScene.Tile tile : scene.tiles()) {
                float top = tile.elevation();
                for (BoardScene.Feature feature : tile.features()) {
                    top = Math.max(top, tile.elevation() + feature.elevation() + feature.height());
                }
                addHex(points, tile.coords(), floor, top);
            }
            if (!points.isEmpty()) {
                var pose = perspectiveFit(points, camera.viewportWidth - 2 * (viewOffsetPixels + viewLeftPixels),
                      azimuth, tilt, 0, true, 0);
                focus.set(pose.focus());
                camera.zoom = pose.zoom();
                update();
            }
            return;
        }
        Vector3 right = new Vector3(camera.direction).crs(camera.up).nor();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float floor = BoardGeometry.floor(scene) / BoardGeometry.LEVEL;
        for (BoardScene.Tile tile : scene.tiles()) {
            float top = tile.elevation();
            for (BoardScene.Feature feature : tile.features()) {
                top = Math.max(top, tile.elevation() + feature.elevation() + feature.height());
            }
            for (int corner = 0; corner < 6; corner++) {
                for (float elevation : new float[] { top, floor }) {
                    Vector3 point = BoardGeometry.corner(tile.coords(), elevation, corner).sub(focus);
                    float x = point.dot(right);
                    float y = point.dot(camera.up);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        moveOnBoard((minX + maxX) / 2, (minY + maxY) / 2);
        camera.zoom = Math.max((maxX - minX) / Math.max(1, camera.viewportWidth - 2 * (viewOffsetPixels + viewLeftPixels)),
              (maxY - minY) / camera.viewportHeight) * 1.05f;
        update();
    }

    void pan(float dx, float dy) {
        stopFraming();
        fitToWindow = false;
        if (camera.perspective) {
            float x = camera.viewportWidth / 2 - viewOffsetPixels, y = camera.viewportHeight / 2;
            Vector3 before = pointOnPlane(x, y, focus.z);
            Vector3 after = pointOnPlane(x + dx, y - dy, focus.z);
            if (before != null && after != null) { focus.add(before.sub(after)); }
        } else {
            moveOnBoard(-dx * camera.zoom, dy * camera.zoom);
        }
        update();
    }

    /** Screen coordinates use the bottom-left origin, without relying on a global graphics viewport. */
    private Vector3 pointOnPlane(float x, float y, float plane) {
        Vector3 right = new Vector3(camera.direction).crs(camera.up).nor();
        // The perspective ray uses the exact camera basis; inverting a very deep frustum loses anchor precision.
        Vector3 direction = new Vector3(camera.direction)
              .mulAdd(right, (2 * x / camera.viewportWidth - 1) / camera.projection.val[Matrix4.M00])
              .mulAdd(camera.up, (2 * y / camera.viewportHeight - 1) / camera.projection.val[Matrix4.M11]).nor();
        if (direction.z >= -.00001f) { return null; }
        float distance = (plane - camera.position.z) / direction.z;
        return distance >= 0 ? new Vector3(camera.position).mulAdd(direction, distance) : null;
    }

    private void moveOnBoard(float dx, float dy) {
        Vector3 right = new Vector3(camera.direction).crs(camera.up).nor();
        Vector3 up = new Vector3(camera.up.x, camera.up.y, 0);
        // Invert the projected length so dragging follows the pointer without lifting the orbit pivot.
        up.scl(1 / up.len2());
        focus.mulAdd(right, dx).mulAdd(up, dy);
    }

    void center(Vector3 position) {
        stopFraming();
        fitToWindow = false;
        focus.set(position);
        update();
    }

    void toggleOverview(BoardScene scene) {
        stopFraming();
        if (overviewFocus == null) {
            overviewZoom = camera.zoom;
            overviewFocus = new Vector3(focus);
            overviewFit = fitToWindow;
            fit(scene);
        } else {
            camera.zoom = overviewZoom;
            focus.set(overviewFocus);
            overviewFocus = null;
            fitToWindow = overviewFit;
            if (fitToWindow) {
                fit(scene);
            } else {
                update();
            }
        }
    }

    /** Bounds of the camera's rays at both terrain height extremes, in board hex coordinates. */
    Rectangle visibleArea(BoardScene scene) {
        float low = BoardGeometry.floor(scene);
        float high = scene.tiles().stream().mapToInt(BoardScene.Tile::elevation).max().orElse(0) * BoardGeometry.LEVEL;
        BoundingBox bounds = viewportBounds(camera, new BoundingBox(
              new Vector3(-BoardGeometry.WIDTH, -(scene.height() + 1) * BoardGeometry.HEIGHT, low),
              new Vector3((scene.width() + 1) * BoardGeometry.WIDTH * .75f, BoardGeometry.HEIGHT, high)));
        int left = Math.max(0, (int) Math.floor(bounds.min.x / (BoardGeometry.WIDTH * 0.75f)) - 2);
        int top = Math.max(0, (int) Math.floor(-bounds.max.y / BoardGeometry.HEIGHT) - 2);
        int rightHex = Math.min(scene.width(), (int) Math.ceil(bounds.max.x / (BoardGeometry.WIDTH * 0.75f)) + 2);
        int bottom = Math.min(scene.height(), (int) Math.ceil(-bounds.min.y / BoardGeometry.HEIGHT) + 2);
        return new Rectangle(left, top, Math.max(0, rightHex - left), Math.max(0, bottom - top));
    }

    void update() {
        orientation(azimuth, tilt, camera.direction, camera.up);
        camera.direction.scl(-1);
        float distance = camera.perspective ? camera.distance() : 10000;
        camera.position.set(camera.direction).scl(-distance).add(focus);
        camera.position.mulAdd(new Vector3(camera.direction).crs(camera.up).nor(), viewOffsetPixels * camera.zoom);
        // Perspective depth precision falls with the square of distance over the near plane: keep the near plane a
        // fiftieth of the way to the pivot, so depth comparisons (occluded outlines, text, fog edges) stay exact there
        // while terrain rising close to a low camera is not clipped.
        camera.near = camera.perspective ? Math.max(1, distance / 50) : 1;
        camera.far = Math.max(100000, distance * 4);
        camera.update();
        revision++;
    }

    private static void orientation(float azimuth, float tilt, Vector3 outward, Vector3 up) {
        float sin = MathUtils.sinDeg(azimuth);
        float cos = MathUtils.cosDeg(azimuth);
        float horizontal = MathUtils.sinDeg(tilt);
        float vertical = MathUtils.cosDeg(tilt);
        outward.set(sin * horizontal, -cos * horizontal, vertical);
        // An explicit up vector also defines a stable bearing at the directly overhead pole.
        up.set(-sin * vertical, cos * vertical, horizontal);
    }
}
