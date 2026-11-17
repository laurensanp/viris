package com.viris.mixin;

import com.viris.Viris;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlPersistentMappedBuffer.class, remap = false)
public class InnerWorkDebugMixin {

    @Shadow @Final public int id;

    @Redirect(method = "<init>(JI)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glNamedBufferStorage(IJI)V"))
    private void viris$skipReadStorage(int id, long size, int flags) {
        if (Viris.isVulkan() && (flags & GL30C.GL_MAP_READ_BIT) != 0) {
            return;
        }
        GL45C.glNamedBufferStorage(id, size, flags);
    }

    @Redirect(method = "<init>(JI)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;nglMapNamedBufferRange(IJJI)J"))
    private long viris$allocHostReadBuffer(int id, long offset, long length, int access) {
        if (Viris.isVulkan() && (access & GL30C.GL_MAP_READ_BIT) != 0) {
            long ptr = MemoryUtil.nmemAlloc(length);
            return ptr;
        }
        return GL45C.nglMapNamedBufferRange(id, offset, length, access);
    }

    @Inject(method = "free", at = @At("HEAD"), cancellable = true)
    private void viris$freeHostReadBuffer(CallbackInfo ci) {
        if (Viris.isVulkan() && !GL45C.glIsBuffer(this.id)) {
            GlPersistentMappedBuffer self = (GlPersistentMappedBuffer) (Object) this;
            if (self.addr() != 0L) {
                MemoryUtil.nmemFree(self.addr());
            }
            ci.cancel();
        }
    }
}
