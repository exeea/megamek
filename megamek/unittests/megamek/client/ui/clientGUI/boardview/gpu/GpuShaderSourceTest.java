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
 * A CPU check for forbidden identifiers in shipped shaders. GpuGlsl selects GLSL 3.30 or newer; legal language
 * keywords such as layout, uint and sampler2DArray must remain available. Native smoke tests check compilation.
 */
class GpuShaderSourceTest {
    /**
     * Reserved for future use through GLSL 4.60, section 3.6:
     * https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf
     */
    private static final Set<String> RESERVED = Set.of("common", "partition", "active", "asm", "class", "union",
          "enum", "typedef", "template", "this", "resource", "goto", "inline", "noinline", "public", "static",
          "extern", "external", "interface", "long", "short", "half", "fixed", "unsigned", "superp", "input",
          "output", "hvec2", "hvec3", "hvec4", "fvec2", "fvec3", "fvec4", "filter", "sizeof", "cast",
          "namespace", "using", "sampler3DRect");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");

    @Test
    void shadersAvoidKeywordsReservedForFutureUse() throws Exception {
        Path folder = Path.of(GpuShaderSourceTest.class
              .getResource("/megamek/client/ui/clientGUI/boardview/gpu/terrain-sculpt.frag").toURI()).getParent();
        List<String> found = new ArrayList<>();
        int shaders = 0;
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches(".*\\.(frag|vert|glsl|comp|tesc|tese)")).toList()) {
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
