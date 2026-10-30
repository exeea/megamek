/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.annotation.Nullable;
import megamek.common.icons.AbstractIcon;
import megamek.common.icons.Camouflage;
import megamek.common.util.fileUtils.AbstractDirectory;

/**
 * What the camo chooser offers, without a toolkit: the categories of the camo directory as a tree, with the colour
 * camos as their own category first, and the camos in a category or matching a search. It follows the Swing chooser
 * ({@code CamoChooserPanel}): the tree's root, {@link AbstractIcon#ROOT_CATEGORY}, holds the colour camos' category
 * and then the directory's categories in their natural order; a category is named by its path in the tree, each
 * name followed by a slash ("" for the root); a search of three letters or more shows every camo of a category whose
 * path holds it and every other camo whose file name holds it.
 */
public final class CamoCatalog {
    /** The shortest search that replaces the category's camos, as in the Swing chooser. */
    public static final int MIN_SEARCH = 3;

    /**
     * A category of the camo directory.
     *
     * @param name     its name in the tree
     * @param path     its directory path, as camos of it carry for their category, such as "Clans/Wolf/"
     * @param items    its camos' file names, in order
     * @param children its sub-categories, in order
     */
    public record Folder(String name, String path, List<String> items, List<Folder> children) {
        public Folder {
            items = List.copyOf(items);
            children = List.copyOf(children);
        }
    }

    /**
     * A category in the tree for the chooser to show.
     *
     * @param id       the category's tree path, such as "Clans/Wolf/" ("" for the root)
     * @param label    its name
     * @param children its sub-categories
     */
    public record Node(String id, String label, List<Node> children) {
        public Node {
            children = List.copyOf(children);
        }
    }

    private final Folder root;
    private final List<String> colours;

    /**
     * @param root    the camo directory's root category, or null when there is no directory
     * @param colours the colour camos' names, such as the {@code PlayerColour} names
     */
    public CamoCatalog(@Nullable Folder root, List<String> colours) {
        this.root = root == null ? new Folder("", "", List.of(), List.of()) : root;
        this.colours = List.copyOf(colours);
    }

    /** The directory's categories as folders; null for no directory. */
    public static @Nullable Folder of(@Nullable AbstractDirectory directory) {
        if (directory == null) {
            return null;
        }
        List<Folder> children = new ArrayList<>();
        for (AbstractDirectory child : directory.getCategories().values()) {
            children.add(of(child));
        }
        return new Folder(directory.getRootName(), directory.getRootPath(),
              new ArrayList<>(directory.getItems().keySet()), children);
    }

    /** The category tree: the root, the colour camos, then the directory's categories. */
    public Node tree() {
        List<Node> top = new ArrayList<>();
        top.add(new Node(Camouflage.COLOUR_CAMOUFLAGE + "/", Camouflage.COLOUR_CAMOUFLAGE, List.of()));
        for (Folder child : root.children()) {
            top.add(node(child, ""));
        }
        return new Node("", AbstractIcon.ROOT_CATEGORY, top);
    }

    private static Node node(Folder folder, String parent) {
        String id = parent + folder.name() + "/";
        List<Node> children = new ArrayList<>();
        for (Folder child : folder.children()) {
            children.add(node(child, id));
        }
        return new Node(id, folder.name(), children);
    }

    /**
     * The camos of the category {@code id}, a tree path, with those of its sub-categories when {@code nested}; the
     * colour camos for their category, and none for a category that is not there.
     */
    public List<Camouflage> items(String id, boolean nested) {
        List<Camouflage> found = new ArrayList<>();
        if (id.startsWith(Camouflage.COLOUR_CAMOUFLAGE)) {
            for (String colour : colours) {
                found.add(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, colour));
            }
            return found;
        }
        Folder folder = folder(id);
        if (folder != null) {
            add(folder, nested, found);
        }
        return found;
    }

    private static void add(Folder folder, boolean nested, List<Camouflage> found) {
        for (String item : folder.items()) {
            found.add(new Camouflage(folder.path(), item));
        }
        if (nested) {
            for (Folder child : folder.children()) {
                add(child, true, found);
            }
        }
    }

    /** The category at tree path {@code id} ("" for the root), or null. */
    public @Nullable Folder folder(String id) {
        Folder at = root;
        for (String name : id.split("/")) {
            if (name.isEmpty()) {
                continue;
            }
            Folder next = null;
            for (Folder child : at.children()) {
                if (child.name().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            at = next;
        }
        return at;
    }

    /**
     * The camos that match {@code text}: every camo of a category whose path holds it, and every other camo whose
     * file name holds it, ignoring case; none for a search shorter than {@link #MIN_SEARCH}.
     */
    public List<Camouflage> search(String text) {
        List<Camouflage> found = new ArrayList<>();
        String searched = text.trim().toLowerCase(Locale.ROOT);
        if (searched.length() < MIN_SEARCH) {
            return found;
        }
        search(root, searched, found);
        return found;
    }

    /** Depth first and a category after its sub-categories, as the directory lists its non-empty categories. */
    private static void search(Folder folder, String searched, List<Camouflage> found) {
        for (Folder child : folder.children()) {
            search(child, searched, found);
        }
        if (folder.items().isEmpty()) {
            return;
        }
        boolean whole = folder.path().toLowerCase(Locale.ROOT).contains(searched);
        for (String item : folder.items()) {
            if (whole || item.toLowerCase(Locale.ROOT).contains(searched)) {
                found.add(new Camouflage(folder.path(), item));
            }
        }
    }

    /**
     * The tree path of {@code camo}'s category, found by its names in the tree, or "" (the root) when it is not there
     * or there is no camo.
     */
    public String categoryOf(@Nullable Camouflage camo) {
        if (camo == null) {
            return "";
        }
        Node at = tree();
        StringBuilder id = new StringBuilder();
        for (String name : camo.getCategory().split(Pattern.quote("/"))) {
            Node next = null;
            for (Node child : at.children()) {
                if (child.label().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return "";
            }
            id.append(name).append('/');
            at = next;
        }
        return id.toString();
    }

    /** The camo as the chooser lists it, without rotation, scale or overlay, to find it among the items. */
    public static Camouflage plain(Camouflage camo) {
        return new Camouflage(camo.getCategory(), camo.getFilename());
    }
}
