/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Production finite-source shader against independent disk quadrature and hemispherical energy integrals. */
@Tag("on-demand")
class GpuDirectionalLightSmokeTest {
    private static final int WIDTH = 8, HEIGHT = 6;
    private static final String PROBE = """
          uniform int u_mode;
          uniform vec3 u_probe;
          layout(location=0) out vec4 fragColor;
          void main() {
              vec3 n=vec3(0,0,1);
              if (u_mode == 1) {
                  float nv=u_probe.x, angle=acos(nv)+u_probe.z*asin(u_directionalRadiusSine);
                  vec3 v=normalize(vec3(sqrt(1.0-nv*nv),0,nv));
                  vec3 l=normalize(vec3(-sin(angle),0,cos(angle)));
                  // Diagnostic scaling only: expose the canonical response without overflowing the 16F readback.
                  fragColor=vec4(surfaceReflectance(n,l,v,u_probe.y,vec3(.02037,.04,1))/10000.0,1);
                  return;
              }
              const float views[8]=float[8](.000005,.0001,.001,.01,.05,.2,.5,1);
              const float roughnesses[6]=float[6](.045,.08,.15,.35,.65,1);
              float nv=views[int(gl_FragCoord.x)], r=roughnesses[int(gl_FragCoord.y)];
              vec3 v=normalize(vec3(sqrt(1.0-nv*nv),0,nv));
              float a2=r*r*r*r, radius2=.25*u_directionalRadiusSine*u_directionalRadiusSine;
              vec2 axes2=a2+vec2(radius2,radius2/(nv*nv+radius2)), axes=sqrt(axes2);
              vec3 stretched=normalize(vec3(axes.x*v.x,0,nv));
              vec3 first=vec3(0,1,0), second=cross(stretched,first);
              float viewLength=length(vec3(axes*v.xy,nv));
              vec3 integral=vec3(0); float peak=0;
              const int samples=16384;
              // A golden-angle visible-normal quadrature uses a different sequence and 16x the LUT samples.
              // Evaluate the production BRDF, divided by the independent VNDF-to-light sampling density.
              for (int i=0;i<samples;i++) {
                  float u=(float(i)+.5)/float(samples);
                  float phi=6.28318530718*fract((float(i)+.5)*.61803398875);
                  vec2 disk=sqrt(u)*vec2(cos(phi),sin(phi));
                  float blend=.5*(1.0+stretched.z);
                  disk.y=(1.0-blend)*sqrt(max(0.0,1.0-disk.x*disk.x))+blend*disk.y;
                  vec3 h0=first*disk.x+second*disk.y+stretched*sqrt(max(0.0,1.0-dot(disk,disk)));
                  vec3 h=normalize(vec3(axes*h0.xy,max(0.0,h0.z)));
                  vec3 l=normalize(reflect(-v,h));
                  if (l.z<=0.0) continue;
                  float denominator=dot(h.xy*h.xy,1.0/axes2)+h.z*h.z;
                  float distribution=1.0/(3.14159265359*axes.x*axes.y*denominator*denominator);
                  float pdf=distribution/(2.0*(nv+viewLength));
                  vec3 response=surfaceReflectance(n,l,v,r,vec3(.02037,.04,1));
                  integral+=response/(3.14159265359*pdf);
                  peak=max(peak,response.b);
              }
              vec3 expected=specularAlbedo(nv,r,vec3(.02037,.04,1));
              fragColor=vec4(integral/float(samples)-expected,peak/64.0);
          }
          """;

    @Test
    void finiteSourcePreservesMaterialEnergyAndMatchesResolvedSunHighlights() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Finite directional source", failure.get()); }
    }

    private static void check() {
        var environment = new GpuEnvironmentLighting();
        var target = GpuHeatGlow.buffer(WIDTH, HEIGHT, false);
        var quad = GpuAtmosphere.screenQuad();
        var shader = GpuGlsl.compile("Finite directional source probe", "#define environmentLightingFlag\n",
              GpuShaderSource.read("atmosphere.vert"), "#version 330 core\n"
                    + GpuShaderSource.read("light-model.glsl") + GpuShaderSource.read("environment-lighting.glsl") + PROBE);
        try {
            environment.texture().bind(0);
            shader.bind(); shader.setUniformi("u_environmentLut", 0);
            shader.setUniformf("u_directionalRadiusSine", GpuEnvironmentLighting.SOURCE_RADIUS_SINE);
            float[] energy = draw(target, quad, shader, 0, 0, 0, 0);
            double source = GpuDirectionalLightTest.maximumSource();
            double maximumResidual = 0, maximumStored = 0;
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                for (int channel = 0; channel < 3; channel++) {
                    maximumResidual = Math.max(maximumResidual, Math.abs(energy[i * 4 + channel]));
                    assertEquals(0, energy[i * 4 + channel], .012,
                          "Finite source must retain its original material's directional energy at pixel " + i
                                + ", Fresnel channel " + channel);
                }
                double stored = energy[i * 4 + 3] * 64 * source * GpuSceneRadiance.SCALE;
                maximumStored = Math.max(maximumStored, stored);
                assertTrue(Double.isFinite(stored) && stored < 65504, "Sampled highlight must fit 16F: " + stored);
            }
            double maximumPeakError = 0, maximumShapeError = 0;
            for (float nv : new float[] { .2f, .5f, .9f }) {
                for (float roughness : new float[] { .045f, .06f, .12f, .35f }) {
                    for (float offset : new float[] { 0, 1, 2 }) {
                        float[] pixels = draw(target, quad, shader, 1, nv, roughness, offset);
                        for (int channel = 0; channel < 3; channel++) {
                            double f0 = new double[] { .02037, .04, 1 }[channel];
                            double reference = GpuDirectionalLightTest.capReference(nv, roughness, offset, f0);
                            double ratio = pixels[channel] * 10000 / reference, error = Math.abs(1 - ratio);
                            if (offset == 0) { maximumPeakError = Math.max(maximumPeakError, error); }
                            else { maximumShapeError = Math.max(maximumShapeError, error); }
                            // A single normalized lobe approximates the disk; assess its explicit approximation
                            // envelope independently instead of pretending that it is exact source quadrature.
                            double tolerance = offset == 0 ? .06 : roughness >= .12f ? .06 : .36;
                            assertEquals(1, ratio, tolerance, "Disk reference nv=" + nv + ", roughness="
                                  + roughness + ", offset radii=" + offset + ", F0=" + f0);
                        }
                    }
                }
            }
            System.out.println("Finite source: energy residual=" + maximumResidual + ", peak error="
                  + maximumPeakError + ", off-peak shape error=" + maximumShapeError + ", stored maximum=" + maximumStored);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { shader.dispose(); quad.dispose(); target.dispose(); environment.dispose(); }
    }

    private static float[] draw(FrameBuffer target, Mesh quad, ShaderProgram shader, int mode, float nv,
          float roughness, float offset) {
        target.begin();
        Gdx.gl.glDisable(GL20.GL_BLEND); Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        shader.bind(); shader.setUniformi("u_mode", mode); shader.setUniformf("u_probe", nv, roughness, offset);
        quad.render(shader, GL20.GL_TRIANGLES);
        var buffer = BufferUtils.newFloatBuffer(WIDTH * HEIGHT * 4);
        Gdx.gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        float[] pixels = new float[WIDTH * HEIGHT * 4]; buffer.get(pixels); target.end(); return pixels;
    }
}
