package com.viris.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.viris.Interop;
import com.viris.Viris;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's default "Graphics API" means OpenGL first, where VIRIS has nothing to do: try Vulkan first then.
 * An explicit OpenGL choice stays, so Voxy can still draw by itself.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {

    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void viris$vulkanFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
        if ((Object) this == PreferredGraphicsApi.DEFAULT && (Interop.IS_WINDOWS || Interop.IS_LINUX)) {
            Viris.LOGGER.info("VIRIS: trying the Vulkan graphics backend first");
            cir.setReturnValue(new GpuBackend[] {new VulkanBackend(), new GlBackend()});
        }
    }
}
