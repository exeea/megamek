/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A tree of rows (the board editor's Layers, the Client Settings sidebar): each node a list row (.brow .b) with an
 * optional icon, its label, an optional muted second line and optional trailing action buttons (such as the red remove
 * ×), and children to any depth. A node with children has a chevron that expands or collapses it; nodes start expanded,
 * and {@link #expandAll} and {@link #collapseAll} change them all. Expansion, the selection and the keyboard's row are
 * kept by node id, so a new model ({@link #nodes}) keeps them; an equal model changes nothing, and a row whose node did
 * not change is kept with its hover and tooltip. The tree does nothing per frame.
 * <p>
 * A click on a row, or Enter on the keyboard's row, selects its node and the view hears it; the view's own
 * {@link #select} is silent, expands the node's ancestors and scrolls its row into view in the scroll pane around the
 * tree. While the tree holds the stage's keyboard focus (a click gives it), Up and Down, Home and End move the keyboard's
 * row (shown with the hover look), Left collapses it or goes to its parent, Right expands it or goes to its first child.
 * {@link #filter} shows the nodes whose label, second line or keywords contain the text, with their ancestors, expanded;
 * clearing it restores the expansion from before. With {@link #reorderable}, siblings that follow each other with the
 * same {@code stack} are a {@link UiList} whose rows (with their shown descendants) drag by a grip before the icon.
 */
public final class UiTree extends Table {
    /** A row's height without and with a second line; each level's indent, which is also the chevron's column. */
    private static final float ROW = 30, TALL_ROW = 40, INDENT = 16;
    private static final float CHEVRON = 13, ACTION = 26;
    /** The Token v0 rows: control.sm, and control.lg with a second line. */
    private static final float TOKEN_HEIGHT = DesignTokens.shipped().size().get("control.sm");
    private static final float TOKEN_TALL = DesignTokens.shipped().size().get("control.lg");

    /**
     * A trailing action button of a row: its actor's {@code name} (unique in the tree), which the view hears with the
     * node's id, its icon, an optional tooltip, and whether it is a red delete button (else a quiet icon button).
     */
    public record Action(String name, String icon, String tooltip, boolean danger) { }

    /**
     * A node: its {@code id}, unique in the tree and also its row button's actor name; its label; an optional second
     * line ({@code detail}), icon (a {@link UiTheme#ICONS} name) and tooltip; {@code keywords} that the filter matches
     * besides the texts; trailing {@code actions}; a {@code stack} name (siblings next to each other with the same
     * name reorder by dragging, see {@link #reorderable}); and its children. Built with {@code new Node(id, label)} and
     * the methods that return a changed copy; nodes compare by value.
     */
    public record Node(String id, String label, String detail, String icon, String tooltip, List<String> keywords,
          List<Action> actions, String stack, List<Node> children) {
        public Node {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            actions = actions == null ? List.of() : List.copyOf(actions);
            children = children == null ? List.of() : List.copyOf(children);
        }

        public Node(String id, String label) {
            this(id, label, null, null, null, null, null, null, null);
        }

        public Node detail(String text) {
            return new Node(id, label, text, icon, tooltip, keywords, actions, stack, children);
        }

        public Node icon(String name) {
            return new Node(id, label, detail, name, tooltip, keywords, actions, stack, children);
        }

        public Node tooltip(String text) {
            return new Node(id, label, detail, icon, text, keywords, actions, stack, children);
        }

        public Node keywords(List<String> words) {
            return new Node(id, label, detail, icon, tooltip, words, actions, stack, children);
        }

        /** A copy with one more trailing action, after the others. */
        public Node action(Action action) {
            List<Action> all = new ArrayList<>(actions);
            all.add(action);
            return new Node(id, label, detail, icon, tooltip, keywords, all, stack, children);
        }

        public Node stack(String name) {
            return new Node(id, label, detail, icon, tooltip, keywords, actions, name, children);
        }

        public Node children(List<Node> nodes) {
            return new Node(id, label, detail, icon, tooltip, keywords, actions, stack, nodes);
        }
    }

    /** Hears a dragged row dropped: its node's id, the sibling it now stands next to, and whether before that one. */
    public interface Move {
        void moved(String id, String target, boolean before);
    }

    /** What a row shows, apart from its state: a row is kept while this stays equal. */
    private record Look(Node node, boolean parent, int depth, boolean grip, boolean chevrons) { }

    private final UiKit kit;
    /** Whether the rows take the Token v0 look ({@link UiTheme#TOKEN_ROW}). */
    private final boolean token;
    private List<Node> roots = List.of();
    /** Every node of the model by id, and each one's parent's id (none for a root). */
    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Set<String> collapsed = new HashSet<>();
    /** The collapsed nodes from before the filter, which clearing it restores; null without a filter. */
    private Set<String> unfiltered;
    private String query = "";
    /** The shown rows by node id, and their ids from the top. */
    private final Map<String, Row> rows = new HashMap<>();
    private final List<String> shown = new ArrayList<>();
    private final List<UiList> stacks = new ArrayList<>();
    private String selected = "", cursor = "", hover = "";
    /** The node to scroll into view at the next layout (its ancestors expanded), until a draw has shown its row. */
    private String reveal;
    private Set<String> highlighted = Set.of();
    private boolean focused;
    private String empty = "", gripTip;
    private Consumer<String> onSelect = id -> { };
    private Consumer<String> onHover = id -> { };
    private BiConsumer<String, String> onAction = (id, action) -> { };
    private Move move;

    /** An empty tree of the kit's rows. */
    public UiTree(UiKit kit) {
        this(kit, null);
    }

    /**
     * An empty tree as {@link #UiTree(UiKit)}; with {@link UiTheme#TOKEN}, in the Token v0 look
     * (docs/design-system/tokens.md, Lists and trees): {@link UiTheme#TOKEN_ROW} rows without a box, text.secondary
     * chevrons and second lines.
     */
    public UiTree(UiKit kit, String style) {
        this.kit = kit;
        token = UiTheme.TOKEN.equals(style);
        top();
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return UiTree.this.keyDown(keycode);
            }
        });
        addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focus) {
                if (actor == UiTree.this) {
                    focused = focus;
                    restyle();
                }
            }
        });
    }

    // ------------------------------------------------------------------ the view's side

    /**
     * Shows {@code model}, the top-level nodes. Expansion, the selection and the keyboard's row stay by id; an equal
     * model changes nothing.
     */
    public void nodes(List<Node> model) {
        if (model.equals(roots)) {
            return;
        }
        roots = List.copyOf(model);
        nodes.clear();
        parents.clear();
        index(roots, null);
        expandToReveal();
        rebuild();
    }

    /** The view hears each node the user selects: a click on its row, or Enter on the keyboard's row. */
    public void onSelect(Consumer<String> listener) {
        onSelect = listener;
    }

    /** The view hears the node whose row the pointer enters, and "" when it leaves the rows. */
    public void onHover(Consumer<String> listener) {
        onHover = listener;
    }

    /** The view hears a click on a trailing action: the node's id and the action's name. */
    public void onAction(BiConsumer<String, String> listener) {
        onAction = listener;
    }

    /**
     * Turns on drag-to-reorder for siblings next to each other with the same stack: each such row leads with a grip
     * (with {@code tip} as its tooltip, null for none), and {@code moved} hears each drop once the rows rest. The tree
     * keeps the new order until the view gives it a new model, and the rows glide back when none comes.
     */
    public void reorderable(String tip, Move moved) {
        gripTip = tip;
        move = moved;
        rebuild();
    }

    /** The text shown when no row is (no nodes, or none matches the filter); "" shows nothing. */
    public void empty(String text) {
        empty = text;
        rebuild();
    }

    /**
     * Selects a node without an event, as the view's model says: its ancestors expand and its row scrolls into view.
     * A node the model does not have yet is selected when it comes. Selecting the selected node again does nothing, so
     * a view may repeat its selection with every model.
     */
    public void select(String id) {
        String next = id == null ? "" : id;
        if (next.equals(selected)) {
            return;
        }
        selected = next;
        cursor = next;
        reveal = next.isEmpty() ? null : next;
        if (expandToReveal()) {
            rebuild();
        } else {
            restyle();
            invalidateHierarchy();
        }
    }

    /** The selected node's id, or "". */
    public String selected() {
        return selected;
    }

    /** Shows the hover look on the rows of these nodes, as another view hovers their items. */
    public void highlight(Collection<String> ids) {
        highlighted = Set.copyOf(ids);
        restyle();
    }

    /** Expands or collapses a node, as its chevron does. */
    public void expand(String id, boolean open) {
        if (open ? collapsed.remove(id) : collapsed.add(id)) {
            rebuild();
        }
    }

    /** Whether the node is expanded; a node without children counts as expanded. */
    public boolean expanded(String id) {
        return !collapsed.contains(id);
    }

    public void expandAll() {
        if (!collapsed.isEmpty()) {
            collapsed.clear();
            rebuild();
        }
    }

    public void collapseAll() {
        nodes.values().stream().filter(node -> !node.children().isEmpty()).forEach(node -> collapsed.add(node.id()));
        rebuild();
    }

    /**
     * Shows only the nodes whose label, second line or keywords contain {@code text} (ignoring case), and their
     * ancestors, all expanded; "" shows every node again with the expansion from before the filter.
     */
    public void filter(String text) {
        String next = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (next.equals(query)) {
            return;
        }
        if (unfiltered == null) {
            unfiltered = new HashSet<>(collapsed);
        }
        collapsed.clear();
        if (next.isEmpty()) {
            collapsed.addAll(unfiltered);
            unfiltered = null;
        }
        query = next;
        rebuild();
    }

    /** The ids of the shown rows, from the top. */
    public List<String> shown() {
        return List.copyOf(shown);
    }

    /** The keyboard's row's id, or "". */
    public String cursor() {
        return cursor;
    }

    /** Whether a reorderable row is dragged, flies or slides; a view keeps its model meanwhile. */
    public boolean busy() {
        return stacks.stream().anyMatch(UiList::busy);
    }

    /**
     * The tree's tool row, as the Client Settings sidebar has it: a search field that filters the tree as it is typed
     * ({@code placeholder} its hint), and Expand all and Collapse all. Its parts are named {@code tree-search},
     * {@code tree-expand-all} and {@code tree-collapse-all}.
     */
    public Table controls(String placeholder) {
        UiKit.SearchField search = kit.search(placeholder);
        search.field.setName("tree-search");
        UiKit.onChange(search.field, () -> filter(search.field.getText()));
        Table controls = new Table();
        controls.add(search).growX().minWidth(0);
        controls.add(tool("tree-expand-all", "unfold-more", "SettingsPagePanel.expandAll.text", this::expandAll))
              .padLeft(4);
        controls.add(tool("tree-collapse-all", "unfold-less", "SettingsPagePanel.collapseAll.text",
              this::collapseAll)).padLeft(2);
        return controls;
    }

    private UiButton tool(String name, String icon, String tip, Runnable action) {
        UiButton button = kit.button("hud-icon", icon, null, null);
        button.setName(name);
        kit.tip(button).getActor().setText(UiKit.text(tip));
        UiKit.onChange(button, action);
        return button;
    }

    // ------------------------------------------------------------------ keys

    /**
     * The tree's keys: Up and Down move the keyboard's row (from none, Down starts at the top and Up at the bottom),
     * Home and End to the first and last row; Left collapses it, or goes to its parent; Right expands it, or goes to its
     * first child; Enter selects it as a click does. Returns false for other keys and without rows.
     */
    public boolean keyDown(int key) {
        if (shown.isEmpty()) {
            return false;
        }
        int index = shown.indexOf(cursor);
        Node node = nodes.get(cursor);
        boolean parent = node != null && !node.children().isEmpty();
        switch (key) {
            case Input.Keys.DOWN -> point(index < 0 ? 0 : Math.min(index + 1, shown.size() - 1));
            case Input.Keys.UP -> point(index < 0 ? shown.size() - 1 : Math.max(index - 1, 0));
            case Input.Keys.HOME -> point(0);
            case Input.Keys.END -> point(shown.size() - 1);
            case Input.Keys.LEFT -> {
                if (index < 0) {
                    return false;
                }
                if (parent && expanded(cursor)) {
                    expand(cursor, false);
                } else if (parents.containsKey(cursor)) {
                    point(shown.indexOf(parents.get(cursor)));
                }
            }
            case Input.Keys.RIGHT -> {
                if (index < 0) {
                    return false;
                }
                if (parent && !expanded(cursor)) {
                    expand(cursor, true);
                } else if (parent && index + 1 < shown.size()) {
                    point(index + 1);
                }
            }
            case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> {
                if (index < 0) {
                    return false;
                }
                choose(cursor);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Moves the keyboard's row to the shown row at {@code index} and keeps it in view. */
    private void point(int index) {
        cursor = shown.get(index);
        reveal = cursor;
        restyle();
        invalidateHierarchy();
    }

    /** A click on a row, or Enter: the node is selected, the tree takes the keys, and the view hears it. */
    private void choose(String id) {
        if (getStage() != null) {
            getStage().setKeyboardFocus(this);
        }
        selected = id;
        cursor = id;
        restyle();
        onSelect.accept(id);
    }

    // ------------------------------------------------------------------ rows

    private void index(List<Node> level, String parent) {
        for (Node node : level) {
            nodes.put(node.id(), node);
            if (parent != null) {
                parents.put(node.id(), parent);
            }
            index(node.children(), node.id());
        }
    }

    /** Expands the ancestors of the node to reveal, once the model has it; true when one was collapsed. */
    private boolean expandToReveal() {
        boolean changed = false;
        if (reveal != null && nodes.containsKey(reveal)) {
            for (String parent = parents.get(reveal); parent != null; parent = parents.get(parent)) {
                changed |= collapsed.remove(parent);
            }
        }
        return changed;
    }

    /** Lays the shown rows out again, keeping the rows whose look did not change. */
    private void rebuild() {
        clearChildren();
        stacks.clear();
        shown.clear();
        Map<String, Row> previous = new HashMap<>(rows);
        rows.clear();
        boolean chevrons = nodes.values().stream().anyMatch(node -> !node.children().isEmpty());
        addRows(this, roots, 0, chevrons, previous);
        if (shown.isEmpty() && !empty.isEmpty()) {
            Label text = kit.empty(this, empty, token);
            text.setName("tree-empty");
        }
        // The keyboard's row hidden by a collapse goes to its nearest shown ancestor.
        while (!cursor.isEmpty() && !rows.containsKey(cursor)) {
            cursor = parents.getOrDefault(cursor, "");
        }
        if (!hover.isEmpty() && !rows.containsKey(hover)) {
            hover = "";
            onHover.accept("");
        }
        restyle();
        invalidateHierarchy();
    }

    /** Adds the shown rows of {@code level} at {@code depth}: runs of a stack as one reorderable list each. */
    private void addRows(Table table, List<Node> level, int depth, boolean chevrons, Map<String, Row> previous) {
        List<Node> visible = level.stream().filter(this::visible).toList();
        int start = 0;
        while (start < visible.size()) {
            String stack = visible.get(start).stack();
            int end = start + 1;
            while (move != null && stack != null && end < visible.size() && stack.equals(visible.get(end).stack())) {
                end++;
            }
            if (end - start > 1) {
                List<Node> run = visible.subList(start, end);
                UiList list = new UiList(kit, token ? UiTheme.TOKEN : null);
                stacks.add(list);
                for (Node node : run) {
                    // A row moves with its shown descendants.
                    Table block = new Table();
                    Row row = addRow(block, node, depth, true, chevrons, previous);
                    list.add(block, row.grip);
                }
                list.reorderable((from, to) -> move.moved(run.get(from).id(), run.get(to).id(), to < from));
                table.add(list).growX().minWidth(0).row();
            } else {
                addRow(table, visible.get(start), depth, false, chevrons, previous);
            }
            start = end;
        }
    }

    private Row addRow(Table table, Node node, int depth, boolean grip, boolean chevrons, Map<String, Row> previous) {
        Look look = new Look(node.children(List.of()), !node.children().isEmpty(), depth, grip, chevrons);
        Row row = previous.get(node.id());
        if (row == null || !row.look.equals(look)) {
            row = new Row(look);
        }
        rows.put(node.id(), row);
        shown.add(node.id());
        table.add(row).growX().minWidth(0).row();
        if (look.parent() && expanded(node.id())) {
            addRows(table, node.children(), depth + 1, chevrons, previous);
        }
        return row;
    }

    /** Whether a node shows: every node without a filter, else one that matches or has a descendant that does. */
    private boolean visible(Node node) {
        return query.isEmpty() || matches(node) || node.children().stream().anyMatch(this::visible);
    }

    private boolean matches(Node node) {
        return contains(node.label()) || contains(node.detail()) || node.keywords().stream().anyMatch(this::contains);
    }

    private boolean contains(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    /** Each row's state: pressed while selected, hovered while highlighted or the focused keyboard's row. */
    private void restyle() {
        rows.forEach((id, row) -> {
            row.button.pressed(id.equals(selected));
            row.button.hovered(highlighted.contains(id) || focused && id.equals(cursor));
            if (row.chevron != null) {
                row.chevron.setDrawable(kit.skin.getDrawable(expanded(id) ? "icon-chevron-down" : "icon-chevron-right"));
            }
        });
    }

    /** Scrolls the row to reveal into view in the scroll pane around the tree, once its place is known. */
    @Override
    public void layout() {
        super.layout();
        Row row = reveal == null ? null : rows.get(reveal);
        ScrollPane pane = firstAscendant(ScrollPane.class);
        if (row != null && pane != null && pane.getActor() != null && row.isDescendantOf(pane.getActor())) {
            Vector2 corner = row.button.localToAscendantCoordinates(pane.getActor(), new Vector2());
            // ScrollPane.scrollTo measures the rectangle's y at its top edge (libGDX 1.14.2).
            pane.scrollTo(corner.x, corner.y + row.button.getHeight(), row.button.getWidth(), row.button.getHeight());
            pane.updateVisualScroll();
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        // The frame's layouts have scrolled to it; a row that is not shown yet is revealed when it is.
        if (reveal != null && rows.containsKey(reveal)) {
            reveal = null;
        }
    }

    /** A shown node's row: its indent, the chevron's column and the row button, as wide as the tree. */
    private final class Row extends Table {
        final Look look;
        final UiButton button;
        final UiKit.Icon chevron;
        /** The drag handle of a reorderable row, else null. */
        final Actor grip;

        Row(Look look) {
            this.look = look;
            Node node = look.node();
            String id = node.id();
            float indent = look.depth() * INDENT;
            if (look.chevrons()) {
                Table toggle = new Table();
                if (look.parent()) {
                    chevron = kit.icon("chevron-down", CHEVRON,
                          token ? UiTheme.tokenColor("text.secondary") : UiTheme.MUTED);
                    toggle.add(chevron);
                    toggle.setName(id + "-chevron");
                    toggle.setTouchable(Touchable.enabled);
                    toggle.addListener(new ClickListener() {
                        @Override
                        public void clicked(InputEvent event, float x, float y) {
                            if (getStage() != null) {
                                getStage().setKeyboardFocus(UiTree.this);
                            }
                            expand(id, !expanded(id));
                        }
                    });
                } else {
                    chevron = null;
                }
                add(toggle).width(INDENT).fillY().padLeft(indent);
                indent = 0;
            } else {
                chevron = null;
            }
            button = rowButton(node, look.grip());
            grip = look.grip() ? button.icons.getFirst() : null;
            if (token) {
                add(button).growX().minWidth(0).height(node.detail() == null ? TOKEN_HEIGHT : TOKEN_TALL)
                      .padLeft(indent);
            } else {
                add(button).growX().minWidth(0).height(node.detail() == null ? ROW : TALL_ROW)
                      .pad(2, 2 + indent, 2, 2);
            }
        }

        /** The row button (.brow .b): the grip and icon, the label over the second line, the trailing actions. */
        private UiButton rowButton(Node node, boolean grip) {
            String id = node.id();
            UiButton row = kit.button(token ? UiTheme.TOKEN_ROW : "hud-plain",
                  node.icon() != null ? node.icon() : grip ? "grip" : null,
                  node.label(), null);
            row.setName(id);
            if (grip) {
                Actor type = row.icons.getFirst();
                Actor handle = type;
                if (node.icon() != null) {
                    handle = kit.icon("grip", ((UiKit.Icon) type).getPrefWidth(), Color.WHITE);
                    Table pair = new Table();
                    row.getCell(type).setActor(pair);
                    pair.add(handle);
                    pair.add(type).padLeft(2);
                    row.icons.addFirst(handle);
                }
                handle.setName(id + "-grip");
                handle.setTouchable(Touchable.enabled);
                if (gripTip != null) {
                    kit.tip(handle).getActor().setText(gripTip);
                }
            }
            row.getLabel().setAlignment(Align.left);
            row.getLabel().setEllipsis(true);
            row.getLabelCell().growX().minWidth(0);
            if (node.detail() != null) {
                row.row();
                Label detail = token
                      ? kit.label(node.detail(), "hud-small", 11.5f, UiTheme.tokenColor("text.secondary"))
                      : kit.label(node.detail(), "hud-small", 10.5f, UiTheme.MUTED);
                detail.setEllipsis(true);
                row.add(detail).colspan(2).growX().minWidth(0).left().padTop(3);
            }
            if (node.tooltip() != null && !node.tooltip().isEmpty()) {
                kit.tip(row).getActor().setText(node.tooltip());
            }
            if (!node.actions().isEmpty()) {
                Table actions = new Table();
                node.actions().forEach(action -> actions.add(actionButton(id, action)).size(ACTION));
                row.trailingAction(actions, ACTION);
            }
            UiKit.onChange(row, () -> choose(id));
            row.addListener(new InputListener() {
                @Override
                public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                    if (pointer == -1 && !hover.equals(id)) {
                        hover = id;
                        onHover.accept(id);
                    }
                }

                @Override
                public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                    if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(row)) && hover.equals(id)) {
                        hover = "";
                        onHover.accept("");
                    }
                }
            });
            return row;
        }

        /** A trailing action: its press and click stay its own, so the row is neither pressed nor selected. */
        private UiButton actionButton(String id, Action action) {
            UiButton button = UiKit.ownEvents(kit.button(action.danger() ? "hud-delete-icon" : "hud-icon", action.icon(),
                  null, null));
            button.pad(2);
            button.setName(action.name());
            if (action.tooltip() != null) {
                kit.tip(button).getActor().setText(action.tooltip());
            }
            UiKit.onChange(button, () -> onAction.accept(id, action.name()));
            return button;
        }
    }
}
