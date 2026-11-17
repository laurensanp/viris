package com.viris;

import com.viris.mixin.FrontendGpuDeviceAccessor;
import com.viris.mixin.VulkanGpuTextureAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.nio.IntBuffer;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaPoolCreateInfo;
import org.lwjgl.vulkan.KHRExternalMemoryFd;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;

/**
 * Vulkan images that the OpenGL context imports (EXT_memory_object_win32 on Windows, EXT_memory_object_fd
 * on Linux). The images are ordinary RenderPearl textures whose memory comes from a VMA pool of exportable
 * dedicated allocations ({@code VulkanGpuTextureMixin}), so the game samples them like any other texture
 * while Voxy's GL code draws into them.
 */
public final class Interop {

    public static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    public static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

    private static final int MEMORY_HANDLE_TYPE = IS_WINDOWS
            ? VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
            : VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
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

    /** The device was created with the external memory extension for this OS. */
    public static boolean supported() {
        VulkanDevice device = device();
        if (device == null) {
            return false;
        }
        var caps = device.vkDevice().getCapabilities();
        return IS_WINDOWS ? caps.VK_KHR_external_memory_win32 : IS_LINUX && caps.VK_KHR_external_memory_fd;
    }

    public static boolean exportingNext() {
        return exportNext;
    }

    /** Called from VulkanGpuTextureMixin right before vmaCreateImage of a shared texture. */
    public static void prepareExport(long vma, VkImageCreateInfo image, VmaAllocationCreateInfo allocation, MemoryStack stack) {
        VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                .handleTypes(MEMORY_HANDLE_TYPE);
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
                exportAllocate = VkExportMemoryAllocateInfo.calloc().sType$Default().handleTypes(MEMORY_HANDLE_TYPE);
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
            long size = info.size();
            int memory = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameterivEXT(memory, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,
                    new int[] {GL45C.GL_TRUE});

            int glTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureParameteri(glTexture, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT);

            if (IS_WINDOWS) {
                VkMemoryGetWin32HandleInfoKHR handleInfo = VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                        .memory(info.deviceMemory()).handleType(MEMORY_HANDLE_TYPE);
                PointerBuffer handle = stack.mallocPointer(1);
                check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device.vkDevice(), handleInfo, handle), "memory handle");
                EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memory, size,
                        EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle.get(0));
                EXTMemoryObject.glTextureStorageMem2DEXT(glTexture, 1, glFormat, width, height, memory, 0L);
                GL45C.glFinish();
                closeHandle(handle.get(0));
            } else {
                VkMemoryGetFdInfoKHR fdInfo = VkMemoryGetFdInfoKHR.calloc(stack).sType$Default()
                        .memory(info.deviceMemory()).handleType(MEMORY_HANDLE_TYPE);
                IntBuffer fd = stack.mallocInt(1);
                check(KHRExternalMemoryFd.vkGetMemoryFdKHR(device.vkDevice(), fdInfo, fd), "memory fd");
                // Per GL_EXT_memory_object_fd, a successful import transfers fd ownership to OpenGL.
                EXTMemoryObjectFD.glImportMemoryFdEXT(memory, size,
                        EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.get(0));
                EXTMemoryObject.glTextureStorageMem2DEXT(glTexture, 1, glFormat, width, height, memory, 0L);
                GL45C.glFinish();
            }

            int error = GL45C.glGetError();
            if (error != 0) {
                throw new IllegalStateException("VIRIS: OpenGL could not import " + label + " (GL error " + error + ")");
            }
            return new SharedTexture(texture, RenderSystem.getDevice().createTextureView(texture), glTexture, memory);
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
