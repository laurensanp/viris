package com.viris.mixin;

import com.viris.GlContext;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AMD path: the read buffer is host memory ({@link GlPersistentMappedBufferMixin}), so GL reads into it directly. */
@Mixin(value = DownloadStream.class, remap = false)
public class DownloadStreamMixin {

    @Shadow @Final private GlPersistentMappedBuffer downloadBuffer;

    @Redirect(method = "commit", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45;glCopyNamedBufferSubData(IIJJJ)V"))
    private void viris$readToHost(int source, int target, long sourceOffset, long targetOffset, long size) {
        if (GlContext.amd()) {
            // shortcut: glGetNamedBufferSubData waits for the GPU here, where Voxy's copy didn't. AMD only.
            GL45C.nglGetNamedBufferSubData(source, sourceOffset, size, downloadBuffer.addr() + targetOffset);
        } else {
            GL45.glCopyNamedBufferSubData(source, target, sourceOffset, targetOffset, size);
        }
    }
}
