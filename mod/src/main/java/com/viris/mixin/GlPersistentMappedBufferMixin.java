package com.viris.mixin;

import com.viris.GlContext;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * AMD path (issue #3): persistently mapped read buffers failed once external memory objects existed. Voxy's read
 * buffer (only DownloadStream's) becomes plain host memory, filled by {@link DownloadStreamMixin}.
 */
@Mixin(value = GlPersistentMappedBuffer.class, remap = false)
public class GlPersistentMappedBufferMixin {

    @Unique
    private boolean viris$host;

    @Redirect(method = "<init>(JI)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glNamedBufferStorage(IJI)V"))
    private void viris$storage(int buffer, long size, int flags) {
        viris$host = GlContext.amd() && (flags & GL30C.GL_MAP_READ_BIT) != 0;
        if (!viris$host) {
            GL45C.glNamedBufferStorage(buffer, size, flags);
        }
    }

    @Redirect(method = "<init>(JI)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglMapNamedBufferRange(IJJI)J"))
    private long viris$map(int buffer, long offset, long length, int access) {
        return viris$host ? MemoryUtil.nmemAlloc(length) : GL45C.nglMapNamedBufferRange(buffer, offset, length, access);
    }

    @Redirect(method = "free", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glUnmapNamedBuffer(I)Z"))
    private boolean viris$unmap(int buffer) {
        if (viris$host) {
            MemoryUtil.nmemFree(((GlPersistentMappedBuffer) (Object) this).addr());
            return true;
        }
        return GL45C.glUnmapNamedBuffer(buffer);
    }
}
