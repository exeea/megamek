/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import megamek.logging.MMLogger;

/** The normal posed instance, with an opaque depth/outline view that borrows exactly the same mesh buffers. */
final class GpuUnitInstance extends ModelInstance {
    /** Hide a complete attachment only once its full bounding diameter is this small on screen. */
    static final float EQUIPMENT_HIDE_PIXELS = 4;
    static final float EQUIPMENT_LOD_HYSTERESIS = .15f;
    private static final class Attachment {
        final float diameter;
        boolean hidden;

        Attachment(float diameter) { this.diameter = diameter; }
    }
    private static final MMLogger LOGGER = MMLogger.create(GpuUnitInstance.class);
    private final RenderableProvider depth = this::depthParts;
    /** The parts each level of detail draws; both empty for a unit with only one level. */
    private final List<NodePart> nearParts = new ArrayList<>();
    private final List<NodePart> farParts = new ArrayList<>();
    private float figureHeight;
    private float farPixels;
    private int detailLevel;
    private final Map<Node, Attachment> attachments = new IdentityHashMap<>();
    private final Vector3 detailPosition = new Vector3();
    private float detailPixels = Float.NaN;
    private boolean forcedDetail;
    private int detailRevision;

    GpuUnitInstance(Model model) { super(model); }

    GpuUnitInstance(GpuUnitModel model) {
        this(model.instance.model);
        for (var binding : model.equipment()) {
            Node node = getNode(binding.node());
            if (!binding.embedded() && node != null) {
                attachments.put(node, new Attachment(UnitBounds.subtree(node).getDimensions(new Vector3()).len()));
            }
        }
        var levels = model.detailLevels();
        if (!levels.far().isEmpty()) {
            figureHeight = model.figureHeight();
            farPixels = levels.farPixels();
            sortDetailParts(nodes, levels);
        }
    }

    /** Parts that belong to neither level, such as a Mek's weapons, are left alone and show at both. */
    private void sortDetailParts(Iterable<Node> nodes, GpuUnitModel.DetailLevels levels) {
        for (Node node : nodes) {
            for (NodePart part : node.parts) {
                Mesh mesh = part.meshPart.mesh;
                if (levels.far().contains(mesh)) {
                    farParts.add(part);
                } else if (levels.near().isEmpty() || levels.near().contains(mesh)) {
                    nearParts.add(part);
                }
            }
            sortDetailParts(node.getChildren(), levels);
        }
    }

    /**
     * Render selection only: rigs, emitters, picking, damage flags and shared mesh buffers stay intact. Small
     * equipment is hidden, and a unit with far detail (a battle armour squad's far suits, a Mek's far body) switches
     * to it, once too small on screen to read.
     */
    void equipmentDetail(Camera camera, boolean forceFull) {
        if (attachments.isEmpty() && farParts.isEmpty()) { return; }
        float pixels = BoardCamera.pixelsPerUnit(camera, transform.getTranslation(detailPosition))
              * Math.max(transform.getScaleX(), Math.max(transform.getScaleY(), transform.getScaleZ()));
        if (pixels == detailPixels && forceFull == forcedDetail) { return; }
        detailPixels = pixels;
        forcedDetail = forceFull;
        farDetail(pixels, forceFull);
        for (Attachment attachment : attachments.values()) {
            float threshold = EQUIPMENT_HIDE_PIXELS * (attachment.hidden ? 1 + EQUIPMENT_LOD_HYSTERESIS : 1 - EQUIPMENT_LOD_HYSTERESIS);
            boolean next = !forceFull && attachment.diameter * pixels < threshold;
            if (next != attachment.hidden) {
                attachment.hidden = next;
                detailRevision++;
            }
        }
    }

    int hiddenEquipment() { return (int) attachments.values().stream().filter(attachment -> attachment.hidden).count(); }

    /**
     * Shows the far detail while the unit (one figure, for a squad) stands less than its model's far height on
     * screen, the near detail otherwise, and always the near detail for a unit in focus.
     *
     * @param pixelsPerModelUnit framebuffer pixels per model unit at the instance's current scale
     * @param forceFull          {@code true} for the selected unit or one in an attack
     */
    void farDetail(float pixelsPerModelUnit, boolean forceFull) {
        if (farParts.isEmpty()) { return; }
        float unitPixels = figureHeight * pixelsPerModelUnit;
        int next = forceFull ? 0 : FormationLod.level(unitPixels, detailLevel, farPixels);
        if (next == detailLevel) { return; }
        detailLevel = next;
        boolean far = next == 1;
        nearParts.forEach(part -> part.enabled = !far);
        farParts.forEach(part -> part.enabled = far);
        detailRevision++;
        LOGGER.debug("[FormationLod] unit now shows its {} detail ({} pixels tall{})", far ? "far" : "near",
              Math.round(unitPixels), forceFull ? ", held near while in focus" : "");
    }

    /** @return {@code 0} while the near detail shows, {@code 1} while the far detail does */
    int detailLevel() { return detailLevel; }

    int detailRevision() { return detailRevision; }

    @Override
    protected void getRenderables(Node node, Array<Renderable> renderables, Pool<Renderable> pool) {
        var attachment = attachments.get(node);
        if (attachment == null || !attachment.hidden) { super.getRenderables(node, renderables, pool); }
    }

    static void renderDepth(ModelBatch batch, ModelInstance instance) {
        batch.render(instance instanceof GpuUnitInstance unit ? unit.depth : instance);
    }

    private void depthParts(Array<Renderable> renderables, Pool<Renderable> pool) {
        int start = renderables.size;
        super.getRenderables(renderables, pool);
        int end = start;
        for (int index = start; index < renderables.size; index++) {
            var current = renderables.get(index);
            var previous = end == start ? null : renderables.get(end - 1);
            if (previous != null && opaque(previous) && opaque(current) && previous.bones == null && current.bones == null
                  && previous.meshPart.mesh == current.meshPart.mesh
                  && previous.meshPart.primitiveType == current.meshPart.primitiveType
                  && current.meshPart.primitiveType == GL20.GL_TRIANGLES
                  && previous.meshPart.size % 3 == 0 && current.meshPart.size % 3 == 0
                  && previous.meshPart.offset + previous.meshPart.size == current.meshPart.offset
                  && java.util.Objects.equals(previous.material.get(IntAttribute.CullFace), current.material.get(IntAttribute.CullFace))
                  && java.util.Objects.equals(previous.material.get(DepthTestAttribute.Type), current.material.get(DepthTestAttribute.Type))
                  && java.util.Arrays.equals(previous.worldTransform.val, current.worldTransform.val)) {
                // ModelBatch's flushable pool still owns all obtained renderables. Only the command range changes.
                previous.meshPart.size += current.meshPart.size;
            } else {
                renderables.set(end++, current);
            }
        }
        renderables.truncate(end);
    }

    private static boolean opaque(Renderable part) {
        var blending = part.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        return (blending == null || !blending.blended) && !part.material.has(FloatAttribute.AlphaTest);
    }
}
