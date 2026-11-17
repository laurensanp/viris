package com.viris.mixin;

import com.viris.Viris;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = HiZBuffer.class, remap = false)
public class HiZBufferMixin {

    @ModifyArg(method = "<init>(Lme/cortex/voxy/client/core/RenderProperties;)V",
            at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/util/HiZBuffer;<init>(Lme/cortex/voxy/client/core/RenderProperties;I)V"),
            index = 1)
    private static int viris$useDepth32F(int type) {
        if (Viris.isVulkan()) {
            return GL30C.GL_DEPTH_COMPONENT32F;
        }
        return type;
    }
}
