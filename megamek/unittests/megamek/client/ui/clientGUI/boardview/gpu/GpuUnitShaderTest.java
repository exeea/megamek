/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.function.UnaryOperator;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** Checks the upstream GLSL and classpath resources without creating an OpenGL context. */
class GpuUnitShaderTest {
    private Files previousFiles;

    @BeforeEach
    void setUpFiles() {
        previousFiles = Gdx.files;
        Gdx.files = new Lwjgl3Files();
    }

    @AfterEach
    void restoreFiles() {
        Gdx.files = previousFiles;
    }

    @Test
    void composesTheBundledLibGdxShadersWithTheShippedResources() {
        assertDoesNotThrow(() -> unitVertex(DefaultShader.getDefaultVertexShader()));
        assertDoesNotThrow(() -> GpuUnitShader.fragmentSource(GpuUnitShader.fragment()));
        assertDoesNotThrow(() -> GpuThermalUnits.vertexSource(DefaultShader.getDefaultVertexShader()));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
          vertex, void main() {
          vertex, v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;
          fragment, void main() {
          fragment, // unit-overlays
          vertex, v_color = a_color;
          """)
    void rejectsMissingOrAmbiguousUpstreamInsertionPoints(String stage, String anchor) {
        boolean vertex = "vertex".equals(stage);
        // The vertex stage is libGDX's; the fragment stage is model.frag with the shared lighting.
        String source = vertex ? DefaultShader.getDefaultVertexShader() : GpuUnitShader.fragment();
        UnaryOperator<String> compose = vertex ? GpuUnitShaderTest::unitVertex : GpuUnitShader::fragmentSource;
        // Whitespace alone must not silently drop an effect; a second match must not inject it twice.
        for (String incompatible : List.of(source.replace(anchor, anchor.replace(" ", "  ")), source + "\n" + anchor)) {
            var error = assertThrows(IllegalStateException.class, () -> compose.apply(incompatible));
            assertTrue(error.getMessage().contains(stage));
            assertTrue(error.getMessage().contains(anchor));
        }
    }

    @ParameterizedTest
    @CsvSource({ "true,false", "true,true", "false,false", "false,true" })
    void materialUniformUploadsRespectPassAndAuthoredDomainsWithoutMutatingSharedColors(
          boolean linearInputs, boolean imported) {
        var shader = mock(DefaultShader.class);
        GpuUnitShader.registerMaterialColors(shader, linearInputs);
        var inputs = new BaseShader.Uniform[] { DefaultShader.Inputs.diffuseColor,
              DefaultShader.Inputs.emissiveColor, DefaultShader.Inputs.specularColor };
        long[] types = { ColorAttribute.Diffuse, ColorAttribute.Emissive, ColorAttribute.Specular };
        var part = new Renderable();
        part.material = new Material();
        if (imported) { part.material.set(new GpuLinearColors()); }
        for (int index = 0; index < inputs.length; index++) {
            var color = new ColorAttribute(types[index], new Color(.02f, .5f, .8f, .37f));
            part.material.set(color);
            var setter = ArgumentCaptor.forClass(BaseShader.Setter.class);
            verify(shader).register(eq(inputs[index]), setter.capture());
            var target = mock(BaseShader.class);
            boolean decode = linearInputs && !imported;
            setter.getValue().set(target, index, part, part.material);
            // Independent sRGB reference values, including the linear toe; alpha is coverage in either domain.
            assertUploadedColor(target, index, decode ? .02f / 12.92f : .02f,
                  decode ? .21404114f : .5f, decode ? .60382736f : .8f, .37f);
            assertEquals(new Color(.02f, .5f, .8f, .37f), color.color,
                  "The same material remains available to scene and display consumers");
            assertEquals(imported, part.material.has(GpuLinearColors.TYPE),
                  "Uploading factors must not relabel shared vertex colors");

            // Dynamic paint/emission changes must be read at each upload, without a stale converted-color cache.
            color.color.set(.5f, .02f, .8f, .61f);
            clearInvocations(target);
            setter.getValue().set(target, index, part, part.material);
            assertUploadedColor(target, index, decode ? .21404114f : .5f,
                  decode ? .02f / 12.92f : .02f, decode ? .60382736f : .8f, .61f);
            assertEquals(new Color(.5f, .02f, .8f, .61f), color.color);
        }
    }

    private static void assertUploadedColor(BaseShader target, int id, float r, float g, float b, float alpha) {
        var values = ArgumentCaptor.forClass(Float.class);
        verify(target).set(eq(id), values.capture(), values.capture(), values.capture(), values.capture());
        var actual = values.getAllValues();
        assertEquals(r, actual.get(0), .0000001f);
        assertEquals(g, actual.get(1), .0000001f);
        assertEquals(b, actual.get(2), .0000001f);
        assertEquals(alpha, actual.get(3).floatValue(), "Uniform alpha remains unchanged");
    }

    /** The unit vertex stage as GpuUnitShader.provider() composes it. */
    private static String unitVertex(String source) {
        return GpuUnitShader.vertexSource(GpuUnitShader.linearVertex(source));
    }
}
