/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;

/** One native ruler card for every native camera and map preview, tethered to the measurement's midpoint. */
final class GpuLosPanel implements GpuHud.Component {
    /** Stage units: keep a visible gap around the whole ray, including the two crosshairs. */
    static final float LINE_CLEARANCE = 24;
    private final UiKit ui;
    private final BoardSource source;
    private final BoardCamera camera;
    private final Stage stage;
    private final Group root;
    private final Table panel;
    private final Table body = new Table();
    private final Table details = new Table();
    private final Cell<Table> detailsCell;
    private final Table comparison = new Table();
    private final Label range;
    private final Label status;
    private final Label modifiers;
    private final Cell<Label> modifiersCell;
    private final Label hint;
    private final UiButton flip;
    private final UiButton compare;
    private final UiButton expand;
    private final Side start;
    private final Side end;
    private final UiPopover choices;
    private RulerModel.Snapshot shown = RulerModel.Snapshot.NONE;
    private long generation;
    private Vector2 anchor;
    private boolean expanded;

    GpuLosPanel(UiKit ui, Stage stage, BoardSource source, BoardCamera camera) {
        this.ui = ui; this.stage = stage; this.source = source; this.camera = camera;
        Texture white = ui.skin.get("white", Texture.class);
        root = new Group() {
            @Override public void draw(Batch batch, float parentAlpha) {
                if (anchor != null) {
                    float x = MathUtils.clamp(anchor.x, panel.getX(), panel.getRight());
                    float y = MathUtils.clamp(anchor.y, panel.getY(), panel.getTop());
                    Vector2 line = new Vector2(anchor).sub(x, y);
                    float color = batch.getPackedColor();
                    batch.setColor(1, 1, 1, .75f * parentAlpha);
                    batch.draw(white, getX() + x, getY() + y, 0, 1, line.len(), 2, 1, 1,
                          line.angleDeg(), 0, 0, 1, 1, false, false);
                    batch.draw(white, getX() + anchor.x - 3, getY() + anchor.y - 3, 6, 6);
                    batch.setPackedColor(color);
                }
                super.draw(batch, parentAlpha);
            }
        };
        root.setTransform(false);
        root.setTouchable(Touchable.childrenOnly);
        root.setName("los-panel-layer");
        panel = ui.panel(); panel.setName("los-panel"); panel.setTouchable(Touchable.enabled);
        root.addActor(panel);
        UiButton close = ui.closeButton(this::close); close.setName("los-close");
        Table header = new Table(); header.pad(2, 7, 0, 2);
        range = label("", "hud-title", 14, UiTheme.TEXT); range.setName("los-range");
        header.add(range).growX(); header.add(close).size(26);
        panel.add(header).growX().row();
        body.pad(0, 7, 6, 7).defaults().growX();
        status = label("", "hud-small", 11, UiTheme.MUTED);
        body.add(status).padBottom(4).row();
        modifiers = label("", "hud-small", 12, UiTheme.TEXT); modifiers.setName("los-modifiers");
        ui.tip(modifiers).getActor().setText("From → To · LOS / terrain modifiers; movement and weapons excluded.");
        modifiersCell = body.add((Label) null); body.row();
        choices = new UiPopover(ui); stage.addActor(choices);
        start = new Side(true); end = new Side(false);
        body.add(start.table).padBottom(3).row();
        body.add(end.table).row();
        hint = label("", "hud-small", 11, UiTheme.MUTED);
        Table buttons = new Table();
        flip = button("Flip", "los-flip", RulerModel::flip);
        compare = button("Compare rules", "los-compare", model -> model.compare(shown.comparison().isEmpty()));
        expand = ui.button("hud-mini", null, "Details ▾", null); expand.setName("los-details");
        buttons.add(flip).height(26).padRight(6);
        buttons.add().expandX();
        buttons.add(expand).height(26);
        body.add(buttons).padTop(4).row();
        Table endpointDetails = new Table();
        endpointDetails.add(start.details).growX().uniformX().top().padRight(10);
        endpointDetails.add(end.details).growX().uniformX().top();
        details.defaults().growX();
        details.add(endpointDetails).row();
        details.add(hint).padTop(8).row();
        details.add(compare).left().padTop(8).row();
        details.add(comparison).row();
        detailsCell = body.add((Table) null); body.row();
        UiKit.onChange(expand, () -> {
            expanded = !expanded;
            expand.setText(expanded ? "Less ▴" : "Details ▾");
            detailsCell.setActor(expanded ? details : null);
            detailsCell.padTop(expanded ? 8 : 0);
            if (!expanded) { dismiss(); send(model -> model.compare(false)); }
        });
        panel.add(ui.scrollList(body)).grow().minHeight(0);
        root.setVisible(false);
    }

    private Label label(String text, String font, float size, Color color) {
        Label label = ui.label(text, font, size, color); label.setWrap(true); return label;
    }

    private UiButton button(String text, String name, Consumer<RulerModel> action) {
        UiButton button = ui.button("hud-mini", null, text, null); button.setName(name);
        UiKit.onChange(button, () -> send(action)); return button;
    }

    private Label comparisonResult(String direction, String description) {
        int separator = description.indexOf(" =");
        String value = separator < 0 ? description : description.substring(0, separator);
        Label result = ui.label(direction + " " + value.trim(), "hud-small", 11, UiTheme.MUTED);
        result.setEllipsis(true);
        ui.tip(result).getActor().setText(description);
        return result;
    }

    private void send(Consumer<RulerModel> action) { source.changeRuler(generation, action); }

    private final class Side {
        final boolean first;
        final Table table = new Table();
        final Table details = new Table();
        final Label title;
        final Label name;
        final Cell<Label> nameCell;
        final Label info;
        final Label terrain;
        final Label result;
        final UiNumber height;
        final UiButton lock;
        final Label lockTip;
        final UiButton unit;
        final Cell<UiButton> unitCell;
        RulerModel.Endpoint endpoint;

        Side(boolean first) {
            this.first = first;
            String id = first ? "los-start" : "los-end";
            table.top().defaults().left();
            title = label(first ? "FROM" : "TO", "hud-title", 12,
                  first ? UiTheme.MINT : UiTheme.CORAL);
            title.setWrap(false); title.setEllipsis(true);
            name = ui.label("", "hud-small", 11, UiTheme.TEXT); name.setEllipsis(true);
            info = label("", "hud-small", 11, UiTheme.MUTED);
            terrain = label("", "hud-small", 11, UiTheme.MUTED);
            result = label("", "hud-body", 12, UiTheme.TEXT); result.setName(id + "-result");
            height = new UiNumber(ui, stage, "Height", 0, -10, 30, 1,
                  (value, done) -> send(model -> model.height(first, Integer.parseInt(value))))
                  .fieldSize(36, 24).names(id + "-height");
            unit = ui.select("Choose unit", false); unit.setName(id + "-unit");
            UiKit.onChange(unit, () -> {
                if (endpoint == null) { return; }
                UiMenuList menu = new UiMenuList(ui);
                for (RulerModel.Choice choice : endpoint.choices()) {
                    menu.item(choice.name(), "", choice.id() == endpoint.entityId(), false, true, () -> {
                        choices.cancel(); send(model -> model.select(first, choice.id()));
                    });
                }
                choices.header("Endpoint", "Select a visible unit or use manual heights").content(menu);
                choices.showAbove(unit, 0);
            });
            lock = ui.button("hud-icon", "unlock", null, null); lock.setName(id + "-lock");
            lockTip = ui.tip(lock).getActor();
            UiKit.onChange(lock, () -> {
                if (endpoint == null) { return; }
                boolean value = !endpoint.locked();
                send(model -> model.lock(first, value));
            });
            Table identity = new Table();
            identity.add(title).growX().minWidth(0);
            identity.add(lock).size(22).padRight(3).row();
            nameCell = identity.add(name).colspan(2).growX().minWidth(0); identity.row();
            table.add(identity).growX().minWidth(0).padRight(4);
            table.add(height).width(110).fillX();
            details.defaults().growX().left();
            unitCell = details.add(unit); details.row();
            details.add(info).row();
            details.add(terrain).padTop(4).row();
            details.add(result).padTop(6).row();
        }

        void update(RulerModel.Endpoint next, String los) {
            endpoint = next;
            title.setText((first ? "FROM" : "TO")
                  + (next == null ? "" : " " + next.coords().getBoardNum()));
            name.setText(next == null ? "Pick a hex or unit" : next.name());
            nameCell.setActor(next == null || next.entityId() != megamek.common.units.Entity.NONE ? name : null);
            for (Actor actor : List.of(height, info, terrain, lock, unit)) { actor.setVisible(next != null); }
            unitCell.setActor(next != null && next.choices().size() > 1 ? unit : null);
            if (next != null) {
                height.value(next.height());
                info.setText((first ? "Start" : "End") + " · " + next.name() + "\n" + next.heightLabel() + " " + next.height()
                      + "\nGround " + next.ground()
                      + " · LOS level " + next.absoluteHeight());
                terrain.setText(next.detail().equals(next.name()) ? "" : next.detail());
                lock.pressed(next.locked());
                ((UiKit.Icon) lock.icons.getFirst()).setDrawable(ui.skin.getDrawable(next.locked() ? "icon-lock" : "icon-unlock"));
                lockTip.setText((next.locked() ? "Unlock " : "Lock ") + (first ? "start" : "end") + " endpoint");
                unit.setText(next.entityId() == megamek.common.units.Entity.NONE ? "Terrain / manual" : "Change unit…");
            }
            result.setText(los.isEmpty() ? "" : (first ? "Start → end\n" : "End → start\n")
                  + (los.trim().equals("0 =") ? "No LOS / terrain modifiers" : los));
        }
    }

    @Override public Actor actor() { return root; }

    @Override public void update(GpuHud.Inputs inputs) {
        generation = inputs.frame().boardGeneration();
        RulerModel.Snapshot next = inputs.frame().panels().los();
        root.setVisible(next.open() && inputs.dialog() == null);
        if (!root.isVisible()) {
            dismiss();
            Actor focus = stage.getKeyboardFocus();
            if (focus != null && focus.isDescendantOf(root)) { stage.setKeyboardFocus(null); }
            shown = next;
            return;
        }
        if (!next.equals(shown)) {
            start.update(next.start(), next.forward()); end.update(next.end(), next.reverse());
            boolean complete = next.start() != null && next.end() != null;
            range.setText(complete ? next.distance() + (next.distance() == 1 ? " hex" : " hexes")
                  + " · " + next.distance() * 30 + " m" : "Line of sight");
            status.setText(complete ? (next.clear() ? "Clear" : "Blocked")
                  + (next.ruler().blockedAt() == null ? "" : " at " + next.ruler().blockedAt().getBoardNum())
                  + " · " + next.mode() : "Click " + (next.start() == null ? "the start" : "the end") + " point");
            status.setColor(complete ? next.clear() ? UiTheme.MINT : UiTheme.CORAL : UiTheme.MUTED);
            modifiers.setText(next.modifierSummary());
            boolean hasModifiers = complete && !next.modifierSummary().isEmpty();
            modifiersCell.setActor(hasModifiers ? modifiers : null).padBottom(hasModifiers ? 4 : 0);
            hint.setText(complete ? (next.entityBased() ? "Actual unit heights" : "Manual height scenario")
                  + " · LOS / terrain modifiers; movement and weapons excluded."
                  : "Alt + click starts a measurement. A plain click completes it.");
            flip.setDisabled(!complete); compare.setDisabled(!complete);
            compare.pressed(!next.comparison().isEmpty());
            if (!shown.comparison().equals(next.comparison())) {
                comparison.clearChildren();
                for (RulerModel.Comparison row : next.comparison()) {
                    comparison.add(ui.label(row.mode(), "hud-title", 12, UiTheme.TEXT)).left().padRight(10).padTop(4);
                    comparison.add(comparisonResult("→", row.forward())).growX().uniformX().minWidth(0).padRight(8).padTop(4);
                    comparison.add(comparisonResult("←", row.reverse())).growX().uniformX().minWidth(0).padTop(4).row();
                }
            }
            shown = next;
        }
        // The last server-clamped value may have arrived while a field still held focus.
        if (next.start() != null) { start.height.value(next.start().height()); }
        if (next.end() != null) { end.height.value(next.end().height()); }
        anchor = null;
        GpuCardPlacement.Line line = null;
        if (next.ruler() != null && camera != null) {
            var ruler = next.ruler();
            Vector2 from = project(ruler.start(), ruler.startHeight());
            Vector2 to = ruler.end() == null ? from : project(ruler.end(), ruler.endHeight());
            if (from != null && to != null) {
                anchor = new Vector2(from).lerp(to, .5f);
                line = new GpuCardPlacement.Line(from.x, from.y, to.x, to.y, LINE_CLEARANCE);
            }
        }
        float width = Math.min(expanded ? 320 : 240, Math.max(1, root.getWidth() - 24));
        panel.setWidth(width); panel.validate();
        float height = Math.min(panel.getPrefHeight(), Math.max(1, root.getHeight() - 32));
        boolean editing = start.height.editing() || end.height.editing() || choices.isVisible();
        if (!editing || panel.getHeight() == 0 || line != null && line.overlaps(
              new Rectangle(panel.getX(), panel.getY(), panel.getWidth(), panel.getHeight()))) {
            Vector2 target = anchor == null ? new Vector2(root.getWidth() / 2, root.getHeight() / 2 - height / 2) : anchor;
            Rectangle bounds = GpuCardPlacement.place(List.of(new GpuCardPlacement.Card(0, width, height,
                        new Rectangle(target.x, target.y, 0, 0))),
                  new Rectangle(12, 16, Math.max(1, root.getWidth() - 24), Math.max(1, root.getHeight() - 32)),
                  inputs.panelBounds(), List.copyOf(inputs.view().unitRects().values()), line).get(0);
            panel.setBounds(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    private Vector2 project(megamek.common.board.Coords coords, int height) {
        Vector2 point = GpuNameplates.project(camera.camera, BoardGeometry.center(coords, height));
        return point == null ? null : point.scl(root.getWidth() / camera.camera.viewportWidth,
              root.getHeight() / camera.camera.viewportHeight);
    }

    void dismiss() { start.height.dismiss(); end.height.dismiss(); choices.cancel(); }

    boolean pending() { return shown.pending() != 0; }

    private void close() { dismiss(); stage.setKeyboardFocus(null); send(RulerModel::clear); }

    boolean cancel() {
        if (!shown.open()) { return false; }
        if (start.height.editing() || end.height.editing() || choices.isVisible()) { dismiss(); }
        else { close(); }
        return true;
    }

    @Override public void dispose() { start.height.close(); end.height.close(); choices.cancel(); choices.remove(); }
}
