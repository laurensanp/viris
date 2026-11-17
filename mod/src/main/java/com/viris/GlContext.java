package com.viris;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;

/**
 * A hidden OpenGL 4.6 context for Voxy's own renderer, current on the render thread for the whole game.
 * The game itself renders through Vulkan and never touches OpenGL, so the context stays current; Voxy's
 * GL code runs on it unchanged and shares its output with Vulkan through {@link Interop}.
 */
public final class GlContext {

    private static long window;
    private static long context;
    private static GLCapabilities capabilities;

    private GlContext() {
    }

    public static boolean ready() {
        return capabilities != null;
    }

    /** Creates the context (main thread, after the game's window exists). False when OpenGL 4.6 or interop is missing. */
    public static boolean create() {
        // The game's window is SDL's (Vulkan); this one is hidden and only carries the GL context.
        SDLVideo.SDL_GL_ResetAttributes();
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 6);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_DEPTH_SIZE, 24);
        SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_STENCIL_SIZE, 8);
        window = SDLVideo.SDL_CreateWindow("VIRIS GL", 16, 16, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);

        context = window == 0L ? 0L : SDLVideo.SDL_GL_CreateContext(window);
        SDLVideo.SDL_GL_ResetAttributes();
        if (context == 0L) {
            Viris.LOGGER.error("VIRIS: could not create an OpenGL 4.6 context ({}), Voxy stays off",
                SDLError.SDL_GetError());
            destroy();
            return false;
        }
        SDLVideo.SDL_GL_MakeCurrent(window, context);
        GLCapabilities caps = GL.createCapabilities();
        boolean hasMemInterop = caps.GL_EXT_memory_object
                && (Interop.IS_WINDOWS ? caps.GL_EXT_memory_object_win32 : Interop.IS_LINUX && caps.GL_EXT_memory_object_fd);
        if (!hasMemInterop) {
            Viris.LOGGER.error("VIRIS: the OpenGL driver can't share memory with Vulkan, Voxy stays off");
            destroy();
            return false;
        }
        // Same clip space as the game's device: Voxy reads isZZeroToOne from it.
        if (RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) {
            GL45C.glClipControl(GL45C.GL_LOWER_LEFT, GL45C.GL_ZERO_TO_ONE);
        }
        capabilities = caps;
        if (Boolean.getBoolean("viris.glDebug")) {
            // Dev: every GL error with the Java stack that caused it.
            GL45C.glEnable(GL45C.GL_DEBUG_OUTPUT);
            GL45C.glEnable(GL45C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            GL45C.glDebugMessageCallback((source, type, id, severity, length, message, user) -> {
                if (type == GL45C.GL_DEBUG_TYPE_ERROR) {
                    Viris.LOGGER.warn("VIRIS GL: {}", org.lwjgl.opengl.GLDebugMessageCallback.getMessage(length, message),
                        new Throwable());
                }
            }, 0L);
        }
        Viris.LOGGER.info("VIRIS: OpenGL context {} on {}", GL45C.glGetString(GL45C.GL_VERSION),
            GL45C.glGetString(GL45C.GL_RENDERER));
        return true;
    }

    public static void destroy() {
        if (context != 0L) {
            SDLVideo.SDL_GL_MakeCurrent(window, 0L);
            GL.setCapabilities(null);
            SDLVideo.SDL_GL_DestroyContext(context);
            context = 0L;
        }
        if (window != 0L) {
            SDLVideo.SDL_DestroyWindow(window);
            window = 0L;
        }
        capabilities = null;
    }
}
