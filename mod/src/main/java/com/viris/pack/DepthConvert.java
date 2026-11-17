package com.viris.pack;

import org.lwjgl.opengl.GL45C;

/**
 * GL pass that turns Voxy's reverse-Z depth (near = 1) into the depth shader packs are written for
 * (far = 1), into an R32F texture: what Voxy's own patch rewrite does to depth reads under Iris.
 */
public final class DepthConvert {

    private static final String VERTEX = """
        #version 450 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    private static final String FRAGMENT = """
        #version 450 core
        layout(binding = 0) uniform sampler2D source;
        layout(location = 0) uniform bool raw;
        layout(location = 0) out float depth;
        void main() {
            float d = texelFetch(source, ivec2(gl_FragCoord.xy), 0).r;
            depth = raw ? d : 1.0 - d;
        }
        """;

    private static int program;
    private static int vertexArray;
    private static int framebuffer;

    private DepthConvert() {
    }

    /** {@code target} (R32F, same size) = 1 - {@code source} depth. */
    public static void run(int source, int target, int width, int height) {
        draw(source, target, width, height, false);
    }

    /** {@code target} (R32F, same size) = {@code source} depth unchanged (AMD path: no shared depth images). */
    public static void copy(int source, int target, int width, int height) {
        draw(source, target, width, height, true);
    }

    private static void draw(int source, int target, int width, int height, boolean raw) {
        if (program == 0) {
            program = GL45C.glCreateProgram();
            int vertex = shader(GL45C.GL_VERTEX_SHADER, VERTEX);
            int fragment = shader(GL45C.GL_FRAGMENT_SHADER, FRAGMENT);
            GL45C.glAttachShader(program, vertex);
            GL45C.glAttachShader(program, fragment);
            GL45C.glLinkProgram(program);
            if (GL45C.glGetProgrami(program, GL45C.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("VIRIS depth conversion: " + GL45C.glGetProgramInfoLog(program));
            }
            GL45C.glDeleteShader(vertex);
            GL45C.glDeleteShader(fragment);
            vertexArray = GL45C.glCreateVertexArrays();
            framebuffer = GL45C.glCreateFramebuffers();
        }
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, target, 0);
        GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL45C.GL_COLOR_ATTACHMENT0);
        GL45C.glBindFramebuffer(GL45C.GL_FRAMEBUFFER, framebuffer);
        GL45C.glViewport(0, 0, width, height);
        GL45C.glDisable(GL45C.GL_DEPTH_TEST);
        GL45C.glDisable(GL45C.GL_STENCIL_TEST);
        GL45C.glDisable(GL45C.GL_BLEND);
        GL45C.glColorMask(true, true, true, true);
        GL45C.glUseProgram(program);
        GL45C.glUniform1i(0, raw ? 1 : 0);
        GL45C.glBindTextureUnit(0, source);
        GL45C.glBindSampler(0, 0);
        GL45C.glBindVertexArray(vertexArray);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        GL45C.glBindVertexArray(0);
        GL45C.glUseProgram(0);
        GL45C.glBindFramebuffer(GL45C.GL_FRAMEBUFFER, 0);
    }

    /** {@link #run} in the middle of Voxy's pipeline, which goes on with the GL state it had set up. */
    public static void runKeepingState(int source, int target, int width, int height) {
        int framebuffer = GL45C.glGetInteger(GL45C.GL_DRAW_FRAMEBUFFER_BINDING);
        int program = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int vertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int[] viewport = new int[4];
        GL45C.glGetIntegerv(GL45C.GL_VIEWPORT, viewport);
        boolean depth = GL45C.glIsEnabled(GL45C.GL_DEPTH_TEST);
        boolean stencil = GL45C.glIsEnabled(GL45C.GL_STENCIL_TEST);
        boolean blend = GL45C.glIsEnabled(GL45C.GL_BLEND);
        run(source, target, width, height);
        GL45C.glBindFramebuffer(GL45C.GL_FRAMEBUFFER, framebuffer);
        GL45C.glUseProgram(program);
        GL45C.glBindVertexArray(vertexArray);
        GL45C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        setEnabled(GL45C.GL_DEPTH_TEST, depth);
        setEnabled(GL45C.GL_STENCIL_TEST, stencil);
        setEnabled(GL45C.GL_BLEND, blend);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL45C.glEnable(capability);
        } else {
            GL45C.glDisable(capability);
        }
    }

    private static int shader(int type, String source) {
        int shader = GL45C.glCreateShader(type);
        GL45C.glShaderSource(shader, source);
        GL45C.glCompileShader(shader);
        if (GL45C.glGetShaderi(shader, GL45C.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("VIRIS depth conversion: " + GL45C.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
