/*
 * Copyright (c) 2000-2002 Ben Mazur (bmazur@sev.org)
 * Copyright (c) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 * Copyright (C) 2002-2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.common.board;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.StringUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;

/**
 * The text of a hex tileset ({@code .tileset}): its base, super and orthographic entries, which of them a hex selects,
 * and the coordinate-seeded pick of each selected entry's file. It holds no image, so it runs headless. The one matcher
 * of the 2D tileset ({@code HexTileset}, which maps the picks to images) and of the 3D board's scenery classification
 * ({@link TilesetScenery}, which reads the picked file names). Thread-safe once loaded.
 */
public final class TilesetMatcher {
    private static final MMLogger logger = MMLogger.create(TilesetMatcher.class);

    /** The synthetic theme every tileset offers for a transparent hex image. */
    public static final String TRANSPARENT_THEME = "transparent";

    /** A tileset line: the terrain its image is for, and its image files (one is picked per hex). */
    public record Entry(Hex hex, List<String> files) {
        public Entry { files = List.copyOf(files); }

        @Override
        public String toString() { return "HexTileset: " + hex.toString(); }
    }

    /**
     * A selected entry and the index of its file that the hex's coordinate-seeded pick chose; {@code index} is -1 when
     * the entry names no file.
     */
    public record Pick(Entry entry, int index) {
        /** The chosen file as the tileset names it, or null when the entry names none. */
        public String file() { return index < 0 ? null : entry.files().get(index); }

        /** The chosen file with '/' separators, as the 3D board's keys use it; "" when the entry names none. */
        public String source() { return index < 0 ? "" : file().replace('\\', '/'); }

        /** Whether the chosen image is art for {@code terrain}: its entry names that terrain. */
        public boolean has(int terrain) { return index >= 0 && entry.hex().containsTerrain(terrain); }
    }

    /** What a hex is drawn with: its base image (null when no base entry fits) and its super and orthographic images. */
    public record Selection(Pick base, List<Pick> supers, List<Pick> orthographic) { }

    private record TerrainMatch(int type, int level, int exits) { }

    private record MatchKey(int level, String theme, List<TerrainMatch> terrains) {
        static MatchKey of(Hex hex) {
            return new MatchKey(hex.getLevel(), hex.getTheme(), Arrays.stream(hex.getTerrainTypes()).sorted()
                  .mapToObj(type -> new TerrainMatch(type, hex.terrainLevel(type), hex.getTerrain(type).getExits()))
                  .toList());
        }
    }

    private record Match(Entry base, List<Entry> supers, List<Entry> orthographic) { }

    private final List<Entry> bases = new ArrayList<>();
    private final List<Entry> superimposed = new ArrayList<>();
    private final List<Entry> orthographic = new ArrayList<>();
    private final Set<String> themes = new TreeSet<>();
    private final Map<MatchKey, Match> matches = new ConcurrentHashMap<>();
    /** Each super and orthographic entry's place in the tileset text, the order a hex's images are drawn in. */
    private final Map<Entry, Integer> ranks = new IdentityHashMap<>();
    private final File root;

    /** An empty tileset whose files and includes resolve against {@code root}. */
    public TilesetMatcher(File root) {
        this.root = root;
    }

    /** The directory this tileset's files and includes resolve against, where its images lie too. */
    public File root() { return root; }

    /**
     * Reads the tileset file {@code filename} and its includes, adding their entries to this tileset. Load before the
     * tileset is shared between threads.
     */
    public void load(String filename) throws IOException {
        matches.clear();
        long startTime = System.currentTimeMillis();
        read(filename, 0);
        ranks.clear();
        superimposed.forEach(entry -> ranks.put(entry, ranks.size()));
        orthographic.forEach(entry -> ranks.put(entry, ranks.size()));
        String loadInfo = String.format("Loaded %o base images, %o super images and %o orthographic images",
              bases.size(), superimposed.size(), orthographic.size());
        logger.debug(loadInfo);
        logger.info("HexTileset {} loaded in {}ms.", filename, System.currentTimeMillis() - startTime);
    }

    /** One file; {@code depth} counts the includes that led here, which stop at 100 to break circular includes. */
    private void read(String filename, int depth) throws IOException {
        // make input stream for board
        try (Reader r = new BufferedReader(new FileReader(new MegaMekFile(root, filename).getFile()))) {
            // read board, looking for "size"
            StreamTokenizer st = new StreamTokenizer(r);
            st.eolIsSignificant(true);
            st.commentChar('#');
            st.quoteChar('"');
            st.wordChars('_', '_');
            while (st.nextToken() != StreamTokenizer.TT_EOF) {
                int elevation = 0;
                String terrain;
                String theme;
                String imageName;
                if ((st.ttype == StreamTokenizer.TT_WORD)
                      && (st.sval.equals("base") || st.sval.equals("super") || st.sval.equals("ortho"))) {
                    boolean bas = st.sval.equals("base");
                    boolean sup = st.sval.equals("super");
                    boolean ort = st.sval.equals("ortho");

                    if (st.nextToken() == StreamTokenizer.TT_NUMBER) {
                        elevation = (int) st.nval;
                    } else {
                        if (st.ttype == 62) { // ">"
                            if (st.nextToken() == StreamTokenizer.TT_NUMBER) { // e.g. ">4"
                                elevation = (int) (Terrain.AT_LEAST + st.nval);
                            }
                        } else {
                            elevation = Terrain.WILDCARD;
                        }
                    }
                    st.nextToken();
                    terrain = st.sval;
                    st.nextToken();
                    theme = st.sval;
                    themes.add(theme);
                    st.nextToken();
                    imageName = st.sval;
                    // add to list
                    if (bas) {
                        bases.add(new Entry(new Hex(elevation, terrain, theme), StringUtil.splitString(imageName, ";")));
                    }
                    if (sup) {
                        superimposed.add(new Entry(new Hex(elevation, terrain, theme),
                              StringUtil.splitString(imageName, ";")));
                    }
                    if (ort) {
                        orthographic.add(new Entry(new Hex(elevation, terrain, theme),
                              StringUtil.splitString(imageName, ";")));
                    }
                } else if ((st.ttype == StreamTokenizer.TT_WORD) && st.sval.equals("include")) {
                    st.nextToken();
                    if (depth + 1 < 100) {
                        String incFile = st.sval;
                        logger.debug("Including {}", incFile);
                        read(incFile, depth + 1);
                    }
                }
            }
        }
        themes.add(TRANSPARENT_THEME);
    }

    /**
     * A picked super or orthographic image's place in the drawing order: its entry's place in the tileset text, supers
     * before orthographic images, as {@link #select} lists them.
     */
    public int order(Pick pick) {
        return ranks.getOrDefault(pick.entry(), Integer.MAX_VALUE);
    }

    public Set<String> getThemes() {
        return new TreeSet<>(themes);
    }

    /**
     * The images a hex is drawn with. First its super and orthographic entries: each must match fully, and the terrain
     * of every match is removed from further consideration. Any terrain left selects the base entry, the first best
     * match. Each entry's file is picked by the hex's coordinates: its hash for the supers and orthographic images, a
     * number drawn from it for the base.
     */
    public Selection select(Hex hex) {
        MatchKey key = MatchKey.of(hex);
        Match match = matches.get(key);
        if (match == null) {
            // Match rules depend on terrain, elevation and theme; image variants still depend on coordinates.
            // Bound this small metadata cache independently of the number of hexes or subsequent board edits.
            if (matches.size() >= 4096) {
                matches.clear();
            }
            Hex hexCopy = hex.duplicate();
            List<Entry> orthographic = orthographicFor(hexCopy);
            List<Entry> supers = superimposedFor(hexCopy);
            match = new Match(baseFor(hexCopy), List.copyOf(supers), List.copyOf(orthographic));
            matches.put(key, match);
        }
        int seed = hex.getCoords().hashCode();
        Random random = new Random(seed);
        return new Selection(pick(match.base(), Math.abs(random.nextInt() * random.nextInt())),
              picks(match.supers(), seed), picks(match.orthographic(), seed));
    }

    private static List<Pick> picks(List<Entry> entries, int seed) {
        List<Pick> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(pick(entry, seed));
        }
        return List.copyOf(result);
    }

    /** The entry's file for {@code seed}: one of several by the seed, else its only one. */
    private static Pick pick(Entry entry, int seed) {
        if (entry == null) {
            return null;
        }
        int count = entry.files().size();
        return new Pick(entry, count == 0 ? -1 : count > 1 ? seed % count : 0);
    }

    /**
     * The levels of a decorative terrain that this tileset actually has images for, given the structure described.
     *
     * <p>A fluff terrain picks which picture a hex is drawn with, and the numbers are not a range - the tileset
     * defines particular ones and ties each to a particular structure. One entry reads
     * {@code building:2:00;bldg_cf:*;bldg_elev:*;bldg_fluff:10}, so that image belongs to a medium building and
     * nothing else; a light building has four images where a hardened one has fifty-four. Offering a plain number
     * field therefore offers mostly numbers that draw nothing at all, which is what this exists to avoid.</p>
     *
     * @param structure    A hex holding the structure the images are wanted for, as the board would hold it
     * @param fluffTerrain The decorative terrain to look for, such as {@link Terrains#BLDG_FLUFF}
     *
     * @return The levels this tileset has images for, in order; empty when it has none
     */
    public List<Integer> definedFluffLevels(Hex structure, int fluffTerrain) {
        Set<Integer> levels = new TreeSet<>();
        for (Entry entry : superimposed) {
            addFluffLevel(entry, structure, fluffTerrain, levels);
        }
        for (Entry entry : bases) {
            addFluffLevel(entry, structure, fluffTerrain, levels);
        }
        return List.copyOf(levels);
    }

    /** Takes one entry's fluff level, when everything else that entry asks for is what the structure actually is. */
    private static void addFluffLevel(Entry entry, Hex structure, int fluffTerrain, Set<Integer> levels) {
        Hex entryHex = entry.hex();
        int fluffLevel = entryHex.terrainLevel(fluffTerrain);
        if ((fluffLevel == Terrain.LEVEL_NONE) || (fluffLevel == Terrain.WILDCARD)) {
            // no fluff at all, or the catch-all entry that matches every level and names no particular picture
            return;
        }
        // the same rule the matching above uses, minus exits and theme: an entry only suits a hex that has every
        // terrain the entry names, at the level it names unless it names a wildcard
        for (int terrainType : entryHex.getTerrainTypes()) {
            if (terrainType == fluffTerrain) {
                continue;
            }
            int requiredLevel = entryHex.terrainLevel(terrainType);
            int actualLevel = structure.terrainLevel(terrainType);
            if ((actualLevel == Terrain.LEVEL_NONE)
                  || ((requiredLevel != Terrain.WILDCARD) && (actualLevel != requiredLevel))) {
                return;
            }
        }
        levels.add(fluffLevel);
    }

    /**
     * Returns a list of orthographic images to be tiled above the hex. As noted above, all matches must be 1.0, and if
     * such a match is achieved, all terrain elements from the tileset hex are removed from the hex. Thus, you want to
     * pass a copy of the original to this function.
     */
    private List<Entry> orthographicFor(Hex hex) {
        ArrayList<Entry> matches = new ArrayList<>();

        // find orthographic image matches
        for (Entry entry : orthographic) {
            if (orthographicMatch(hex, entry.hex()) >= 1.0) {
                matches.add(entry);
                // remove involved terrain from consideration
                for (int terr : entry.hex().getTerrainTypes()) {
                    if (entry.hex().containsTerrain(terr)) {
                        hex.removeTerrain(terr);
                    }
                }
            }
        }
        return matches;
    }

    /**
     * Returns a list of images to be superimposed on the hex. As noted above, all matches must be 1.0, and if such a
     * match is achieved, all terrain elements from the tileset hex are removed from the hex. Thus, you want to pass a
     * copy of the original to this function.
     */
    private List<Entry> superimposedFor(Hex hex) {
        ArrayList<Entry> matches = new ArrayList<>();

        // find superimposed image matches
        for (Entry entry : superimposed) {
            if (superMatch(hex, entry.hex()) >= 1.0) {
                matches.add(entry);
                // remove involved terrain from consideration
                for (int terr : entry.hex().getTerrainTypes()) {
                    if (entry.hex().containsTerrain(terr)) {
                        hex.removeTerrain(terr);
                    }
                }
            }
        }
        return matches;
    }

    /**
     * Returns the best matching base image for this hex. This works best if any terrain with a "super" image is
     * removed.
     */
    private Entry baseFor(Hex hex) {
        // A hazard with no dedicated artwork must never force the first unrelated base as a zero-score fallback.
        // Keep it available to explicit super rules; remove it only from the remaining base match.
        hex.removeTerrain(Terrains.BLACK_ICE);
        hex.removeTerrain(Terrains.METAL_CONTENT);
        Entry bestMatch = null;
        double match = -1;

        // match a base image to the hex
        for (Entry entry : bases) {

            double thisMatch = baseMatch(hex, entry.hex());
            // stop if perfect match
            if (thisMatch == 1.0) {
                bestMatch = entry;
                break;
            }
            // compare match with best
            if (thisMatch > match) {
                bestMatch = entry;
                match = thisMatch;
            }
        }

        return bestMatch;
    }

    // perfect match
    // all but theme
    // all but elevation
    // all but elevation & theme

    /**
     * Match the two hexes using the "orthographic" super* formula. All matches must be exact, however the match only
     * depends on the original hex matching all the elements of the comparison, not vice versa.
     * <p>
     * EXCEPTION: a themed original matches any unthemed comparison.
     */
    private static double orthographicMatch(Hex org, Hex com) {
        // exact elevation
        if ((com.getLevel() != Terrain.WILDCARD) && (com.getLevel() < Terrain.AT_LEAST)
              && (org.getLevel() != com.getLevel())) {
            return 0;
        }

        // greater than elevation (e.g. >4), the "-100" to allow >-3
        if ((com.getLevel() != Terrain.WILDCARD) && (com.getLevel() >= Terrain.AT_LEAST - 100)
              && (org.getLevel() < com.getLevel() - Terrain.AT_LEAST)) {
            return 0;
        }

        // A themed original matches any unthemed comparison.
        if ((com.getTheme() != null) && !com.getTheme().equalsIgnoreCase(org.getTheme())) {
            return 0.0;
        }

        // org terrains must match com terrains
        if (org.terrainsPresent() < com.terrainsPresent()) {
            return 0.0;
        }

        // check terrain
        int[] cTerrainTypes = com.getTerrainTypes();
        for (int cTerrType : cTerrainTypes) {
            Terrain cTerr = com.getTerrain(cTerrType);
            Terrain oTerr = org.getTerrain(cTerrType);

            if (cTerr != null && ((oTerr == null)
                  || ((cTerr.getLevel() != Terrain.WILDCARD) && (oTerr.getLevel() != cTerr.getLevel()))
                  || (cTerr.hasExitsSpecified() && (oTerr.getExits() != cTerr.getExits())))) {
                return 0;
            }
        }

        return 1.0;
    }

    /**
     * Match the two hexes using the "super" formula. All matches must be exact, however the match only depends on the
     * original hex matching all the elements of the comparison, not vice versa.
     * <p>
     * EXCEPTION: a themed original matches any unthemed comparison.
     */
    private static double superMatch(Hex org, Hex com) {
        // exact elevation
        if ((com.getLevel() != Terrain.WILDCARD) && (com.getLevel() < Terrain.AT_LEAST)
              && (org.getLevel() != com.getLevel())) {
            return 0;
        }

        // greater than elevation (e.g. >4), the "-100" to allow >-3
        if ((com.getLevel() != Terrain.WILDCARD) && (com.getLevel() >= Terrain.AT_LEAST - 100)
              && (org.getLevel() < com.getLevel() - Terrain.AT_LEAST)) {
            return 0;
        }

        // A themed original matches any unthemed comparison.
        if ((com.getTheme() != null) && !com.getTheme().equalsIgnoreCase(org.getTheme())) {
            return 0.0;
        }

        // org terrains must match com terrains
        if (org.terrainsPresent() < com.terrainsPresent()) {
            return 0.0;
        }

        // check terrain
        int[] cTerrainTypes = com.getTerrainTypes();
        for (int cTerrType : cTerrainTypes) {
            Terrain cTerr = com.getTerrain(cTerrType);
            Terrain oTerr = org.getTerrain(cTerrType);
            if (cTerr != null && ((oTerr == null)
                  || ((cTerr.getLevel() != Terrain.WILDCARD) && (oTerr.getLevel() != cTerr.getLevel()))
                  || (cTerr.hasExitsSpecified() && (oTerr.getExits() != cTerr.getExits())))) {
                return 0;
            }
        }

        return 1.0;
    }

    /**
     * Match the two hexes using the "base" formula.
     *
     * @return a value indicating how close of a match the original hex is to the comparison hex. 0 means no match, 1
     *       means perfect match.
     */
    private static double baseMatch(Hex org, Hex com) {
        double elevation;
        double terrain;
        double theme;

        // check elevation
        if (com.getLevel() == Terrain.WILDCARD) {
            elevation = 1.0;
        } else if (com.getLevel() >= Terrain.AT_LEAST - 100) {
            if (org.getLevel() >= com.getLevel() - Terrain.AT_LEAST) {
                elevation = 1.0;
            } else {
                elevation = 1.01 / (Math.abs(org.getLevel() - com.getLevel() - Terrain.AT_LEAST) + 1.01);
            }
        } else {
            elevation = 1.01 / (Math.abs(org.getLevel() - com.getLevel()) + 1.01);
        }

        // Determine maximum number of terrain matches.
        // Bug 732188: Have a non-zero minimum terrain match.
        double maxTerrains = Math.max(org.terrainsPresent(), com.terrainsPresent());
        double matches = 0.0;

        int[] orgTerrains = org.getTerrainTypes();

        for (int terrType : orgTerrains) {
            Terrain cTerr = com.getTerrain(terrType);
            Terrain oTerr = org.getTerrain(terrType);
            if ((cTerr == null) || (oTerr == null)) {
                continue;
            }
            double thisMatch;

            if (cTerr.getLevel() == Terrain.WILDCARD) {
                thisMatch = 1.0;
            } else if (cTerr.getLevel() >= Terrain.AT_LEAST - 100) {
                if (oTerr.getLevel() >= com.getLevel() - Terrain.AT_LEAST) {
                    thisMatch = 1.0;
                } else {
                    thisMatch = 1.0 / (Math.abs(oTerr.getLevel() - cTerr.getLevel() - Terrain.AT_LEAST) + 1.0);
                }
            } else {
                thisMatch = 1.0 / (Math.abs(oTerr.getLevel() - cTerr.getLevel()) + 1.0);
            }
            // without exit match, terrain counts... um, half?
            if (cTerr.hasExitsSpecified() && (oTerr.getExits() != cTerr.getExits())) {
                thisMatch *= 0.5;
            }
            // add up match value
            matches += thisMatch;
        }
        if (maxTerrains == 0) {
            terrain = 1.0;
        } else {
            terrain = matches / maxTerrains;
        }

        // check theme
        if ((Objects.equals(com.getTheme(), org.getTheme()))
              || ((com.getTheme() != null) && com.getTheme().equalsIgnoreCase(org.getTheme()))) {
            theme = 1.0;
        } else if ((org.getTheme() != null) && (com.getTheme() == null)) {
            // If no precise themed match, slightly favor unthemed comparisons
            theme = 0.001;
        } else {
            // also don't throw a match entirely out because the theme is off
            theme = 0.0001;
        }

        return elevation * terrain * theme;
    }
}
