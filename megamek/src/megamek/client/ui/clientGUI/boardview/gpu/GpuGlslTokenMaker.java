/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.modes.CTokenMaker;

/** GLSL keywords on the editor library's C lexer; public for its token-maker factory. */
public final class GpuGlslTokenMaker extends CTokenMaker {
    private static final TokenMap WORDS = keywords();

    @Override
    public void addToken(char[] text, int start, int end, int type, int offset) {
        int glsl = type == Token.IDENTIFIER ? WORDS.get(text, start, end) : -1;
        super.addToken(text, start, end, glsl < 0 ? type : glsl, offset);
    }

    private static TokenMap keywords() {
        TokenMap words = new TokenMap();
        for (String word : ("uniform attribute varying layout in out inout flat smooth noperspective centroid "
              + "invariant precision highp mediump lowp discard sampler2D sampler3D samplerCube sampler2DShadow "
              + "sampler2DArray isampler2D usampler2D uint vec2 vec3 vec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 "
              + "bvec2 bvec3 bvec4 mat2 mat3 mat4 mat2x3 mat2x4 mat3x2 mat3x4 mat4x2 mat4x3").split(" ")) {
            words.put(word, Token.DATA_TYPE);
        }
        for (String word : ("texture textureLod textureGrad textureSize texelFetch normalize length distance dot cross "
              + "reflect refract faceforward mix clamp smoothstep step fract mod abs sign floor ceil round min max "
              + "sin cos tan asin acos atan pow exp exp2 log log2 sqrt inversesqrt dFdx dFdy fwidth "
              + "radians degrees transpose inverse determinant").split(" ")) {
            words.put(word, Token.FUNCTION);
        }
        for (String word : "gl_Position gl_FragCoord gl_FrontFacing gl_FragDepth gl_VertexID gl_InstanceID".split(" ")) {
            words.put(word, Token.VARIABLE);
        }
        return words;
    }
}
