package com.viris.mixin;

import com.viris.Viris;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import org.lwjgl.opengl.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DownloadStream.class, remap = false)
public class VoxyRenderSystemMixin {

    @Shadow @Final private GlPersistentMappedBuffer downloadBuffer;

    @Redirect(method = "commit",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45;glCopyNamedBufferSubData(IIJJJ)V"))
    private void viris$copyToHostReadBuffer(int readBuffer, int writeBuffer, long readOffset, long writeOffset, long size) {
        if (Viris.isVulkan()) {
            int prev = GL15C.glGetInteger(GL42C.GL_COPY_READ_BUFFER_BINDING);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, readBuffer);
            GL15C.nglGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, readOffset, size, this.downloadBuffer.addr() + writeOffset);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, prev);
            return;
        }
        GL45.glCopyNamedBufferSubData(readBuffer, writeBuffer, readOffset, writeOffset, size);
    }
}
