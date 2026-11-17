package com.viris;

import com.viris.mixin.FrontendGpuDeviceAccessor;
import com.viris.mixin.VulkanGpuTextureAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaPoolCreateInfo;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;

/**
 * Vulkan images and semaphores that the OpenGL context imports (EXT_memory_object_win32 /
 * EXT_semaphore_win32). The images are ordinary RenderPearl textures whose memory comes from a VMA pool
 * of exportable dedicated allocations ({@code VulkanGpuTextureMixin}), so the game samples them like any
 * other texture while Voxy's GL code draws into them.
 */
public final class Interop {

    private static final int HANDLE_OPAQUE_WIN32 = 0x2;
    private static final int VMA_DEDICATED = Vma.VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT;

    /** Set while a shared texture is created: its image gets exportable memory. */
    private static boolean exportNext;
    private static final Int2LongOpenHashMap POOLS = new Int2LongOpenHashMap();
    private static VkExportMemoryAllocateInfo exportAllocate;
    private static long closeHandle;

    private Interop() {
    }

    /** The game's Vulkan device, or null on another backend. */
    public static VulkanDevice device() {
        return RenderSystem.tryGetDevice() instanceof FrontendGpuDeviceAccessor frontend
            && frontend.viris$backend() instanceof VulkanDevice vulkan ? vulkan : null;
    }

    /** The device was created with the Win32 external memory and semaphore extensions. */
    public static boolean supported() {
        VulkanDevice device = device();
        return device != null && device.vkDevice().getCapabilities().VK_KHR_external_memory_win32
            && device.vkDevice().getCapabilities().VK_KHR_external_semaphore_win32;
    }

    public static boolean exportingNext() {
        return exportNext;
    }

    /** Called from VulkanGpuTextureMixin right before vmaCreateImage of a shared texture. */
    public static void prepareExport(long vma, VkImageCreateInfo image, VmaAllocationCreateInfo allocation, MemoryStack stack) {
        VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
            .handleTypes(HANDLE_OPAQUE_WIN32);
        external.pNext(image.pNext());
        image.pNext(external.address());
        IntBuffer typeIndex = stack.mallocInt(1);
        int result = Vma.vmaFindMemoryTypeIndexForImageInfo(vma, image, allocation, typeIndex);
        if (result != 0) {
            throw new IllegalStateException("VIRIS: no memory type for a shared image (" + result + ")");
        }
        long pool = POOLS.get(typeIndex.get(0));
        if (pool == 0L) {
            if (exportAllocate == null) {
                exportAllocate = VkExportMemoryAllocateInfo.calloc().sType$Default().handleTypes(HANDLE_OPAQUE_WIN32);
            }
            VmaPoolCreateInfo poolInfo = VmaPoolCreateInfo.calloc(stack).memoryTypeIndex(typeIndex.get(0))
                .pMemoryAllocateNext(exportAllocate.address());
            PointerBuffer poolHandle = stack.mallocPointer(1);
            result = Vma.vmaCreatePool(vma, poolInfo, poolHandle);
            if (result != 0) {
                throw new IllegalStateException("VIRIS: export pool failed (" + result + ")");
            }
            pool = poolHandle.get(0);
            POOLS.put(typeIndex.get(0), pool);
        }
        allocation.pool(pool).flags(allocation.flags() | VMA_DEDICATED);
    }

    /** A RenderPearl texture whose memory OpenGL sees as {@code glTexture}. */
    public record SharedTexture(GpuTexture texture, GpuTextureView view, int glTexture, int glMemory) {

        public void close() {
            GL45C.glDeleteTextures(glTexture);
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemory);
            view.close();
            texture.close();
        }
    }

    public static SharedTexture createTexture(String label, int usage, GpuFormat format, int glFormat, int width, int height) {
        int stale = GL45C.glGetError();
        if (stale != 0) {
            // An earlier call's error, not this import's (-Dviris.glDebug=true names it).
            Viris.LOGGER.warn("VIRIS: GL error {} left before importing {}", stale, label);
        }
        GpuTexture texture;
        exportNext = true;
        try {
            texture = RenderSystem.getDevice().createTexture(label, usage, format, width, height, 1, 1);
        } finally {
            exportNext = false;
        }
        VulkanDevice device = device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            Vma.vmaGetAllocationInfo(device.vma(), ((VulkanGpuTextureAccessor) texture).viris$vmaAllocation(), info);
            VkMemoryGetWin32HandleInfoKHR handleInfo = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                .memory(info.deviceMemory()).handleType(HANDLE_OPAQUE_WIN32);
            PointerBuffer handle = stack.mallocPointer(1);
            check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device.vkDevice(), handleInfo, handle), "memory handle");
            // Dedicated allocation: the memory object is exactly this image, at offset 0.
            long size = info.offset() + info.size();
            int memory = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameterivEXT(memory, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,
                new int[] {GL45C.GL_TRUE});
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memory, size,
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle.get(0));
            int glTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureParameteri(glTexture, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
            EXTMemoryObject.glTextureStorageMem2DEXT(glTexture, 1, glFormat, width, height, memory, info.offset());
            // AMD's driver still used the handle after the import returned (issue #3): close it once GL is done.
            GL45C.glFinish();
            closeHandle(handle.get(0));
            int error = GL45C.glGetError();
            if (error != 0) {
                throw new IllegalStateException("VIRIS: OpenGL could not import " + label + " (GL error " + error + ")");
            }
            return new SharedTexture(texture, RenderSystem.getDevice().createTextureView(texture), glTexture, memory);
        }
    }

    /** A binary semaphore both APIs see: {vkSemaphore, glSemaphore}. */
    public static long[] createSemaphore() {
        VkDevice device = device().vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default()
                .handleTypes(HANDLE_OPAQUE_WIN32);
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address());
            LongBuffer semaphore = stack.mallocLong(1);
            check(VK11.vkCreateSemaphore(device, info, null, semaphore), "semaphore");
            VkSemaphoreGetWin32HandleInfoKHR handleInfo = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                .semaphore(semaphore.get(0)).handleType(HANDLE_OPAQUE_WIN32);
            PointerBuffer handle = stack.mallocPointer(1);
            check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device, handleInfo, handle), "semaphore handle");
            int glSemaphore = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glSemaphore, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
                handle.get(0));
            closeHandle(handle.get(0));
            return new long[] {semaphore.get(0), glSemaphore};
        }
    }

    private static void closeHandle(long handle) {
        if (closeHandle == 0L) {
            closeHandle = Kernel32.getLibrary().getFunctionAddress("CloseHandle");
        }
        JNI.invokePI(handle, closeHandle);
    }

    private static void check(int result, String what) {
        if (result != 0) {
            throw new IllegalStateException("VIRIS: Vulkan failed to export the " + what + " (" + result + ")");
        }
    }
}
