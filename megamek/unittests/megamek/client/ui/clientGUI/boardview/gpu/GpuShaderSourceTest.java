/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The shipped GLSL, checked without a GPU. Drivers differ: some reject words that later GLSL versions reserve even in
 * shaders without a {@code #version} line, while the software renderer used by the smoke tests accepts them.
 */
class GpuShaderSourceTest {
    /** Keywords and reserved words of GLSL 1.30 to 4.60 and GLSL ES 3.x that the shaders' own GLSL 1.10 lacks. */
    private static final Set<String> RESERVED = Set.of("layout", "centroid", "flat", "smooth", "noperspective", "patch",
          "sample", "subroutine", "invariant", "precise", "buffer", "shared", "coherent", "volatile", "restrict",
          "readonly", "writeonly", "resource", "atomic_uint", "uint", "uvec2", "uvec3", "uvec4", "switch", "case",
          "default", "common", "partition", "active", "filter", "input", "output", "demote", "double", "dvec2", "dvec3",
          "dvec4", "dmat2", "dmat3", "dmat4", "half", "fixed", "long", "short", "unsigned", "superp", "class", "union",
          "enum", "typedef", "template", "this", "packed", "goto", "inline", "noinline", "public", "static", "extern",
          "external", "interface", "namespace", "using", "sizeof", "cast", "asm", "hvec2", "hvec3", "hvec4", "fvec2",
          "fvec3", "fvec4", "sampler3DRect", "image1D", "image2D", "image3D", "imageCube", "sampler2DArray");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");

    @Test
    void shadersAvoidWordsThatNewerGlslReserves() throws Exception {
        Path folder = Path.of(GpuShaderSourceTest.class
              .getResource("/megamek/client/ui/clientGUI/boardview/gpu/terrain-sculpt.frag").toURI()).getParent();
        List<String> found = new ArrayList<>();
        int shaders = 0;
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches(".*\\.(frag|vert|glsl)")).toList()) {
                shaders++;
                String[] lines = COMMENT.matcher(Files.readString(file))
                      .replaceAll(match -> match.group().replaceAll("[^\\n]", " ")).split("\n", -1);
                for (int line = 0; line < lines.length; line++) {
                    Matcher word = IDENTIFIER.matcher(lines[line]);
                    while (word.find()) {
                        if (RESERVED.contains(word.group())) {
                            found.add(file.getFileName() + ":" + (line + 1) + " '" + word.group() + "'");
                        }
                    }
                }
            }
        }
        assertTrue(shaders > 10, "The shader resources were found: " + folder);
        assertTrue(found.isEmpty(), "GLSL reserved words used in shader code: " + found);
    }
}
