package com.viris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.util.Optional;
import java.util.OptionalDouble;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL45C;

/**
 * One frame of Voxy on Vulkan. Right before the game's opaque world pass, Voxy's own renderer runs on
 * the OpenGL context into two shared images (colour with alpha, depth in the game's projection), as it
 * would into the game's targets on OpenGL, but over an empty picture: the game's opaque world isn't drawn
 * yet. Vulkan waits for it and writes the LODs into the main target, colour blended the way Voxy's final
 * blit blends and depth written; the terrain and everything after it then draw over them by depth, which
 * is where Voxy's stencil would have let the LODs through.
 *
 * <p>Order across the two APIs, with binary semaphores per image set: GL draws the frame and signals
 * glDone; Vulkan waits for it, composites, copies the game's lightmap into the set (Voxy reads it the next
 * time it draws into this set) and signals vkDone; GL waits for vkDone before it draws into the set again.
 * The texture lists of GL's waits and signals hand the images between the APIs; without them (a GL fence
 * and a CPU wait instead) the driver serialised the two and hung with DLSS. Timeline semaphores didn't
 * work either: NVIDIA 617 passed only GL's first signal to Vulkan.
 */
public final class VoxyFrame {

    private static final String NAMESPACE = "viris";
    private static final int LIGHTMAP_SIZE = 16;
    /** Dev option -Dviris.timing=true: logs the render thread's time in Voxy and a GL finish (stalls the GPU queue). */
    private static final boolean TIMING = Boolean.getBoolean("viris.timing");
    private static int timingFrames;
    private static long timingCpu;
    private static long timingGpu;

    private static final String VERTEX_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        void main() {
            vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    // LodDepth is the clear value where Voxy drew nothing; there the pixel stays as it is. RenderPearl's
    // Vulkan images keep OpenGL's row order (bottom row first), so pixels are read where GL wrote them.
    private static final String COMPOSITE_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        uniform sampler2D LodColor;
        uniform sampler2D LodDepth;
        layout(location = 0) out vec4 fragColor;
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            float depth = texelFetch(LodDepth, p, 0).r;
            if (depth == CLEAR_DEPTH) {
                discard;
            }
            fragColor = texelFetch(LodColor, p, 0);
            gl_FragDepth = depth;
        }
        """;

    private static final String LIGHTMAP_SHADER = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        uniform sampler2D Lightmap;
        layout(location = 0) out vec4 fragColor;
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            fragColor = texelFetch(Lightmap, p, 0);
        }
        """;

    private static final ShaderSource SHADERS = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            return switch (id.toString()) {
                case NAMESPACE + ":fullscreen" -> VERTEX_SHADER;
                case NAMESPACE + ":composite" -> COMPOSITE_SHADER;
                case NAMESPACE + ":lightmap" -> LIGHTMAP_SHADER;
                default -> PACK_SHADERS.get(id.toString());
            };
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
        }
    };

    // Sodium's matrices, camera and fog of this frame (SodiumWorldRendererMixin), as Voxy takes them.
    private static Matrix4fc projection;
    private static Matrix4fc modelView;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static FogParameters fog;

    /** Image sets used in turn, so GL draws frame N while Vulkan may still read frames N-1 and N-2. */
    public static final int SETS = 3;
    private static final ImageSet[] sets = new ImageSet[SETS];
    private static int frame;
    private static long lastRecreate;
    private static ImageSet current;
    private static CompiledRenderPipeline compositePipeline;
    private static GpuFormat compositeFormat;
    private static CompiledRenderPipeline lightmapPipeline;
    private static boolean failed;
    private static boolean loggedFirstFrame;

    private VoxyFrame() {
    }

    public static void capture(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix, double x, double y, double z,
        FogParameters fogParameters) {
        projection = new Matrix4f(projectionMatrix);
        modelView = new Matrix4f(modelViewMatrix);
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        fog = fogParameters;
    }

    /** Shared images of one frame. */
    private static final class ImageSet {
        Interop.SharedTexture color;
        Interop.SharedTexture depth;
        /** The game's lightmap, copied in when this set was last composited; Voxy reads it when it next draws into the set. */
        final Interop.SharedTexture lightmap = Interop.createTexture("Voxy lightmap", GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, GL45C.GL_RGBA8, LIGHTMAP_SIZE, LIGHTMAP_SIZE);
        /** GL signals it once Voxy drew into the set, Vulkan waits for it before the composite. */
        final long[] glDone = Interop.createSemaphore();
        /** Vulkan signals it after the composite, GL waits for it before it draws into the set again. */
        final long[] vkDone = Interop.createSemaphore();
        /** Vulkan signalled vkDone and GL hasn't waited for it yet. */
        boolean vkDonePending;

        /** Shader pack mode: copies of the colour targets Voxy's pack patch wrote, and its depth (far = 1). */
        Interop.SharedTexture[] packTargets;
        /** The pack's colour targets its Voxy patch reads, from when this set was last composited. */
        Interop.SharedTexture[] packInputs = new Interop.SharedTexture[0];
        Interop.SharedTexture vxOpaque;
        Interop.SharedTexture vxTrans;

        int[] glTextures() {
            int extra = packTargets == null ? 0 : packTargets.length + 2 + packInputs.length;
            int[] out = new int[3 + extra];
            out[0] = color.glTexture();
            out[1] = depth.glTexture();
            out[2] = lightmap.glTexture();
            if (packTargets != null) {
                for (int i = 0; i < packTargets.length; i++) {
                    out[3 + i] = packTargets[i].glTexture();
                }
                out[3 + packTargets.length] = vxOpaque.glTexture();
                out[4 + packTargets.length] = vxTrans.glTexture();
                for (int i = 0; i < packInputs.length; i++) {
                    out[5 + packTargets.length + i] = packInputs[i].glTexture();
                }
            }
            return out;
        }

        int[] layouts() {
            int[] out = new int[glTextures().length];
            java.util.Arrays.fill(out, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
            return out;
        }

        void ensurePack(int count, int inputs, int width, int height) {
            if (packTargets != null && packTargets.length == count && packInputs.length == inputs
                && vxOpaque.texture().getWidth(0) == width
                && vxOpaque.texture().getHeight(0) == height) {
                return;
            }
            waitForVulkan();
            GL45C.glFinish();
            closePack();
            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
            packTargets = new Interop.SharedTexture[count];
            for (int i = 0; i < count; i++) {
                packTargets[i] = Interop.createTexture("Voxy pack target", usage, GpuFormat.RGBA32_FLOAT, GL45C.GL_RGBA32F, width, height);
            }
            packInputs = new Interop.SharedTexture[inputs];
            for (int i = 0; i < inputs; i++) {
                packInputs[i] = Interop.createTexture("Voxy pack input", usage, GpuFormat.RGBA32_FLOAT, GL45C.GL_RGBA32F, width, height);
            }
            vxOpaque = Interop.createTexture("Voxy depth opaque", usage, GpuFormat.R32_FLOAT, GL45C.GL_R32F, width, height);
            vxTrans = Interop.createTexture("Voxy depth translucent", usage, GpuFormat.R32_FLOAT, GL45C.GL_R32F, width, height);
        }

        void closePack() {
            if (packTargets != null) {
                for (Interop.SharedTexture target : packTargets) {
                    target.close();
                }
                for (Interop.SharedTexture input : packInputs) {
                    input.close();
                }
                packInputs = new Interop.SharedTexture[0];
                vxOpaque.close();
                vxTrans.close();
                packTargets = null;
            }
        }

        void waitForVulkan() {
            if (vkDonePending) {
                EXTSemaphore.glWaitSemaphoreEXT((int) vkDone[1], new int[0], glTextures(), layouts());
                vkDonePending = false;
            }
        }

        void resize(int width, int height) {
            if (color != null && color.texture().getWidth(0) == width && color.texture().getHeight(0) == height) {
                return;
            }
            if (color != null) {
                // GL is done with the old images before they go; Vulkan frames still in flight keep them
                // until RenderPearl destroys them.
                waitForVulkan();
                GL45C.glFinish();
                color.close();
                depth.close();
            }
            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
            color = Interop.createTexture("Voxy colour", usage, GpuFormat.RGBA8_UNORM, GL45C.GL_RGBA8, width, height);
            depth = Interop.createTexture("Voxy depth", usage, GpuFormat.D32_FLOAT, GL45C.GL_DEPTH_COMPONENT32F, width, height);
        }
    }

    private static final long ALL_COMMANDS = 0x10000L;

    /** Voxy's lightmap texture on the GL side (LightMapHelperMixin): the game's lightmap of SETS frames ago. */
    public static int lightmapGlTexture() {
        if (current == null) {
            current = new ImageSet();
            sets[0] = current;
        }
        return current.lightmap.glTexture();
    }

    /** Called right before the game opens its opaque world pass on {@code main}. */
    public static void beforeOpaquePass(RenderTarget main) {
        // Only this frame's LODs count: without a new set (Voxy switched off in its settings, nothing drawn) the
        // pack's vxDepthTex reads far, else it shaded last frame's LOD depth over the sky.
        packReadSet = null;
        if (failed || !GlContext.ready() || projection == null) {
            return;
        }
        VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
        if (system == null) {
            return;
        }
        try {
            ensureFarView();
            if (ShaderPacks.active()) {
                packFrame(system, main);
            } else if (packPipeline != null) {
                // Shaders went off: back to Voxy's own pipeline.
                dropPackPipeline();
                recreateRenderer();
            } else {
                render(system, main);
            }
        } catch (RuntimeException e) {
            failed = true;
            Viris.LOGGER.error("VIRIS: drawing Voxy failed, LODs stay off for this session", e);
        }
    }

    private static void render(VoxyRenderSystem system, RenderTarget main) {
        int width = main.width;
        int height = main.height;
        ensureResources(width, height, main.getColorTexture().getFormat());
        ImageSet set = current;
        set.waitForVulkan();
        GL45C.glClearTexImage(set.color.glTexture(), 0, GL45C.GL_RGBA, GL45C.GL_FLOAT, new float[] {0, 0, 0, 0});
        GL45C.glClearTexImage(set.depth.glTexture(), 0, GL45C.GL_DEPTH_COMPONENT, GL45C.GL_FLOAT, new float[] {clearDepth()});
        long start = System.nanoTime();
        Viewport<?> viewport = system.setupViewport(projection, modelView, fog, width, height, cameraX, cameraY, cameraZ);
        if (viewport != null) {
            system.renderOpaque(viewport, set.depth.glTexture(), set.color.glTexture());
        }
        long drawn = System.nanoTime();
        EXTSemaphore.glSignalSemaphoreEXT((int) set.glDone[1], new int[0], set.glTextures(), set.layouts());
        GL45C.glFlush();
        if (TIMING) {
            GL45C.glFinish();
            long finished = System.nanoTime();
            timingFrames++;
            timingCpu += drawn - start;
            timingGpu += finished - drawn;
            if (timingFrames == 50) {
                // GL_NVX_gpu_memory_info: free video memory (kB) and evictions so far.
                Viris.LOGGER.info("VIRIS timing: Voxy calls {} ms, GL finish {} ms per frame, VRAM free {} MB, evictions {}",
                    String.format("%.2f", timingCpu / 50e6), String.format("%.2f", timingGpu / 50e6),
                    GL45C.glGetInteger(0x9049) / 1024, GL45C.glGetInteger(0x904A));
                timingFrames = 0;
                timingCpu = 0;
                timingGpu = 0;
            }
        }

        if (com.viris.pack.VitrailBridge.present()) {
            // While a shader pack switches off or reloads, Vitrail can still hold a pass open; a semaphore wait in
            // there threw and switched the LODs off for the session.
            closeVitrailPasses();
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        VulkanCommandEncoder vulkan = (VulkanCommandEncoder) ((FrontendCommandEncoder) encoder).backend();
        vulkan.waitSemaphore(set.glDone[0], 0L, ALL_COMMANDS);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Voxy LODs", main.getColorTextureView(), Optional.empty(),
            main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(compositePipeline);
            pass.setUniform("LodColor", set.color.view(), nearest);
            pass.setUniform("LodDepth", set.depth.view(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Voxy lightmap", set.lightmap.view(), Optional.empty())) {
            pass.setPipeline(lightmapPipeline);
            pass.setUniform("Lightmap", Minecraft.getInstance().gameRenderer.levelLightmap(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        vulkan.signalSemaphore(set.vkDone[0], 0L, ALL_COMMANDS);
        set.vkDonePending = true;
        frame = (frame + 1) % SETS;
        if (sets[frame] == null) {
            sets[frame] = new ImageSet();
        }
        current = sets[frame];
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            Viris.LOGGER.info("VIRIS: Voxy draws through OpenGL into shared images ({}x{})", width, height);
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Shader pack mode: Voxy's pack pipeline (the pack's own voxy_opaque/voxy_translucent code) draws the
    // LODs' gbuffer data on GL; before the pack's deferred passes Vulkan writes it into the pack's colour
    // targets where no block was drawn, and the pack's passes read Voxy's depth as vxDepthTexOpaque/Trans.
    // ---------------------------------------------------------------------------------------------

    private static com.viris.pack.PackPipeline packPipeline;
    private static String packKey = "";
    /** Set GL drew into this frame, waiting for the composite before the pack's deferred passes. */
    private static ImageSet pendingPackSet;
    /** Set that takes a copy of the pack's targets once its deferred passes are done. */
    private static ImageSet pendingInputSet;
    /** Set whose depth the pack's passes read this frame; its vkDone is signalled with the frame's submit. */
    private static ImageSet packReadSet;
    private static boolean vkDoneAtSubmit;
    /** Set GL drew into this frame: its vkDone goes with the frame's submit. */
    private static ImageSet frameSet;
    private static int packSourceDepth;
    private static int packSourceColour;
    private static int packSourceWidth;
    private static int packSourceHeight;
    private static final java.util.Map<String, CompiledRenderPipeline> PACK_COMPOSITES = new java.util.HashMap<>();
    private static GpuTexture farTexture;
    private static GpuTextureView farView;

    /** The pipeline data Voxy's RenderPipelineFactory builds its IrisVoxyRenderPipeline from (RenderPipelineFactoryMixin). */
    public static com.viris.pack.PackPipeline packPipelineForFactory() {
        return ShaderPacks.active() ? packPipeline : null;
    }

    private static void packFrame(VoxyRenderSystem system, RenderTarget main) {
        consumeUnusedPackSet();
        com.viris.pack.VitrailBridge.Pack pack = com.viris.pack.VitrailBridge.pack();
        if (pack == null) {
            return;
        }
        int width = main.width;
        int height = main.height;
        String key = pack.path() + "|" + pack.chosen() + "|" + pack.profile() + "|" + pack.world() + "|" + width + "x" + height;
        if (!key.equals(packKey)) {
            dropPackPipeline();
            packKey = key;
            packPipeline = com.viris.pack.PackPipeline.create(pack, width, height);
            Viris.LOGGER.info("VIRIS: shader pack {} {}", pack.path().getFileName(),
                packPipeline == null ? "has no Voxy support, LODs stay off" : "draws the LODs with its own Voxy code");
            recreateRenderer();
            return;
        }
        if (packPipeline == null) {
            return;
        }
        if (packPipeline.data().thePipeline == null) {
            // Voxy built its renderer while the pack wasn't on (e.g. joining a world again): it runs its own
            // pipeline, so ask for the pack's again, at most every 2 seconds.
            long now = System.nanoTime();
            if (now - lastRecreate > 2_000_000_000L) {
                lastRecreate = now;
                recreateRenderer();
            }
            return;
        }
        ensureResources(width, height, main.getColorTexture().getFormat());
        ImageSet set = current;
        set.ensurePack(packPipeline.targets.length, packPipeline.inputTargets.size(), width, height);
        set.waitForVulkan();
        ensurePackSource(width, height);
        packPipeline.clearTargets();
        GL45C.glClearTexImage(packSourceDepth, 0, GL45C.GL_DEPTH_COMPONENT, GL45C.GL_FLOAT, new float[] {clearDepth()});
        Viewport<?> viewport = system.setupViewport(projection, modelView, fog, width, height, cameraX, cameraY, cameraZ);
        if (viewport == null) {
            return;
        }
        com.viris.pack.VoxyPackUniforms.update(viewport);
        system.renderOpaque(viewport, packSourceDepth, packSourceColour);
        for (int i = 0; i < packPipeline.targets.length; i++) {
            GL45C.glCopyImageSubData(packPipeline.targetTextures[i], GL45C.GL_TEXTURE_2D, 0, 0, 0, 0,
                set.packTargets[i].glTexture(), GL45C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        }
        int[] depths = com.viris.pack.PackPipeline.voxyDepths(packPipeline.data().thePipeline);
        if (depths != null) {
            com.viris.pack.DepthConvert.run(depths[0], set.vxOpaque.glTexture(), width, height);
            com.viris.pack.DepthConvert.run(depths[1], set.vxTrans.glTexture(), width, height);
        } else {
            GL45C.glClearTexImage(set.vxOpaque.glTexture(), 0, GL45C.GL_RED, GL45C.GL_FLOAT, new float[] {1, 1, 1, 1});
            GL45C.glClearTexImage(set.vxTrans.glTexture(), 0, GL45C.GL_RED, GL45C.GL_FLOAT, new float[] {1, 1, 1, 1});
        }
        EXTSemaphore.glSignalSemaphoreEXT((int) set.glDone[1], new int[0], set.glTextures(), set.layouts());
        GL45C.glFlush();
        // Vulkan waits here, before the world pass: at the pack's deferred stage the game's level pass is
        // still open, and no semaphore operation may go in there. vkDone follows with the frame's submit.
        closeVitrailPasses();
        VulkanCommandEncoder vulkan = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
        vulkan.waitSemaphore(set.glDone[0], 0L, ALL_COMMANDS);
        pendingPackSet = set;
        frameSet = set;
        vkDoneAtSubmit = true;
        frame = (frame + 1) % SETS;
        if (sets[frame] == null) {
            sets[frame] = new ImageSet();
        }
        current = sets[frame];
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            Viris.LOGGER.info("VIRIS: the shader pack's Voxy code draws through OpenGL ({}x{})", width, height);
        }
    }

    /**
     * Vitrail keeps passes open across stages (the level's pass, held geometry passes such as its sky) and
     * reopens them by itself; semaphore operations and our own passes need them closed.
     */
    private static void closeVitrailPasses() {
        dev.vitrail.render.GeometryHold.flushIdle(() -> "VIRIS");
        dev.vitrail.render.GraphicsApi.suspendLevelPass();
    }

    /** A set no composite took (the pack skipped its deferred stage) is simply dropped; its semaphores went by. */
    private static void consumeUnusedPackSet() {
        pendingPackSet = null;
        pendingInputSet = null;
    }

    /**
     * Between Voxy's opaque and translucent draws: the pack's translucent code samples this depth as vxDepthTex.
     * Its water stays in the colour target, the pack's deferred passes fog it (as they do under Iris).
     */
    public static void beforePackTranslucents(int opaqueDepth) {
        if (packPipeline != null) {
            com.viris.pack.DepthConvert.runKeepingState(opaqueDepth, packPipeline.patchDepth, packPipeline.width,
                packPipeline.height);
        }
    }

    private static void ensurePackSource(int width, int height) {
        if (packSourceDepth != 0 && packSourceWidth == width && packSourceHeight == height) {
            return;
        }
        if (packSourceDepth != 0) {
            GL45C.glDeleteTextures(packSourceDepth);
            GL45C.glDeleteTextures(packSourceColour);
        }
        packSourceDepth = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(packSourceDepth, 1, GL45C.GL_DEPTH_COMPONENT32F, width, height);
        packSourceColour = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(packSourceColour, 1, GL45C.GL_RGBA8, width, height);
        packSourceWidth = width;
        packSourceHeight = height;
    }

    /**
     * Right before the pack's deferred passes (PackChainMixin, outside any render pass): the LODs' gbuffer
     * data goes into the pack's colour targets where no block was drawn.
     */
    public static void packComposite() {
        if (pendingPackSet == null || packPipeline == null || failed) {
            return;
        }
        try {
            ImageSet set = pendingPackSet;
            pendingPackSet = null;
            compositePack(set, true);
        } catch (RuntimeException e) {
            // An exception here would stop Vitrail drawing the pack: only the LODs go off.
            failed = true;
            pendingPackSet = null;
            packReadSet = null;
            Viris.LOGGER.error("VIRIS: writing the LODs into the shader pack failed, LODs stay off for this session", e);
        }
    }

    /**
     * After Vitrail's scene seed (PackChainMixin): the seed copies the game's picture into the pack's first draw
     * buffer wherever no block was drawn, over the LODs when that buffer is their gbuffer (Photon's colortex1),
     * so the LODs go in again on top.
     */
    public static void afterSceneSeed() {
        if (packReadSet == null || packPipeline == null || failed) {
            return;
        }
        try {
            compositePack(packReadSet, false);
        } catch (RuntimeException e) {
            failed = true;
            packReadSet = null;
            Viris.LOGGER.error("VIRIS: writing the LODs into the shader pack failed, LODs stay off for this session", e);
        }
    }

    private static void compositePack(ImageSet set, boolean withLightmap) {
        closeVitrailPasses();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int count = packPipeline.targets.length;
        GpuTextureView[] targets = new GpuTextureView[count];
        StringBuilder formats = new StringBuilder();
        for (int i = 0; i < count; i++) {
            targets[i] = com.viris.pack.VitrailBridge.gbufferTarget(packPipeline.targets[i]);
            if (targets[i] == null) {
                return;
            }
            formats.append(targets[i].texture().getFormat()).append(',');
        }
        // The pack sees the LOD depth only once their colours go in too, else it shades bare depth (grey outlines).
        packReadSet = set;
        CompiledRenderPipeline pipeline = PACK_COMPOSITES.computeIfAbsent(formats.toString(), k -> packCompositePipeline(targets));
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        com.mojang.renderpearl.api.commands.RenderPassDescriptor.Builder descriptor =
            com.mojang.renderpearl.api.commands.RenderPassDescriptor.builder(() -> "Voxy LOD gbuffer");
        for (GpuTextureView target : targets) {
            descriptor.withColorAttachment(target, Optional.empty());
        }
        try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(pipeline);
            for (int i = 0; i < count; i++) {
                pass.setUniform("LodTarget" + i, set.packTargets[i].view(), nearest);
            }
            pass.setUniform("LodOpaque", set.vxOpaque.view(), nearest);
            pass.setUniform("LodTrans", set.vxTrans.view(), nearest);
            pass.setUniform("SceneDepth", main.getDepthTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        if (!withLightmap) {
            return;
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Voxy lightmap", set.lightmap.view(), Optional.empty())) {
            pass.setPipeline(lightmapPipeline);
            pass.setUniform("Lightmap", Minecraft.getInstance().gameRenderer.levelLightmap(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        pendingInputSet = set;
    }

    /** Right after the pack's deferred passes (PackChainMixin). */
    public static void packInputs() {
        ImageSet set = pendingInputSet;
        pendingInputSet = null;
        if (set == null || packPipeline == null || failed) {
            return;
        }
        try {
            closeVitrailPasses();
            copyPackInputs(set, RenderSystem.getDevice().createCommandEncoder(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        } catch (RuntimeException e) {
            failed = true;
            Viris.LOGGER.error("VIRIS: copying the pack's targets for the LODs' water failed, LODs stay off for this session", e);
        }
    }

    /**
     * The pack's targets its LOD water reads for reflections, as the deferred passes left them. GL reads them
     * when this set comes round again, {@code SETS} frames later.
     */
    private static void copyPackInputs(ImageSet set, CommandEncoder encoder, GpuSampler nearest) {
        for (int i = 0; i < set.packInputs.length; i++) {
            GpuTextureView source = com.viris.pack.VitrailBridge.targetAfterDeferred(packPipeline.inputTargets.get(i));
            if (source == null) {
                continue;
            }
            CompiledRenderPipeline pipeline = PACK_COMPOSITES.computeIfAbsent("input", k -> packInputPipeline());
            try (RenderPass pass = encoder.createRenderPass(() -> "Voxy pack input", set.packInputs[i].view(), Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.setUniform("Source", source, nearest);
                pass.draw(3, 1, 0, 0);
            }
        }
    }

    private static CompiledRenderPipeline packInputPipeline() {
        PACK_SHADERS.put(NAMESPACE + ":packinput", """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform sampler2D Source;
            layout(location = 0) out vec4 colour;
            void main() {
                colour = texelFetch(Source, ivec2(gl_FragCoord.xy), 0);
            }
            """);
        return compile(fullscreen("packinput", BindGroupLayout.builder()
                .withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .build());
    }

    /** What the patch's sampler reads in the set being drawn. */
    public static int packInputGlTexture(int index) {
        return index < current.packInputs.length ? current.packInputs[index].glTexture() : 0;
    }

    private static CompiledRenderPipeline packCompositePipeline(GpuTextureView[] targets) {
        StringBuilder shader = new StringBuilder("""
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform sampler2D LodOpaque;
            uniform sampler2D LodTrans;
            uniform sampler2D SceneDepth;
            """);
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (int i = 0; i < targets.length; i++) {
            shader.append("uniform sampler2D LodTarget").append(i).append(";\n");
            shader.append("layout(location = ").append(i).append(") out vec4 out").append(i).append(";\n");
            layout.withUniform("LodTarget" + i, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        layout.withUniform("LodOpaque", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("LodTrans", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER);
        shader.append("""
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                float lod = min(texelFetch(LodOpaque, p, 0).r, texelFetch(LodTrans, p, 0).r);
                if (lod >= 1.0 || texelFetch(SceneDepth, p, 0).r != CLEAR_DEPTH) {
                    discard;
                }
            """);
        for (int i = 0; i < targets.length; i++) {
            shader.append("    out").append(i).append(" = texelFetch(LodTarget").append(i).append(", p, 0);\n");
        }
        shader.append("}\n");
        String name = "packcomposite" + targets.length + "_" + Integer.toHexString(java.util.Arrays.hashCode(
            java.util.Arrays.stream(targets).map(t -> t.texture().getFormat().toString()).toArray()));
        PACK_SHADERS.put(NAMESPACE + ":" + name, shader.toString());
        RenderPipeline.Builder builder = fullscreen(name, layout.build()).withShaderDefine("CLEAR_DEPTH", clearDepth());
        for (int i = 0; i < targets.length; i++) {
            builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), targets[i].texture().getFormat(), ColorTargetState.WRITE_ALL));
        }
        return compile(builder.build());
    }

    private static final java.util.Map<String, String> PACK_SHADERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The view a pack's vxDepthTex sampler reads this frame (far everywhere when there is none). */
    public static GpuTextureView vxDepthView(String sampler) {
        if (packReadSet != null && packReadSet.vxOpaque != null) {
            return sampler.contains("Trans") ? packReadSet.vxTrans.view() : packReadSet.vxOpaque.view();
        }
        return farView;
    }

    /** A 1x1 depth of 1 (far), made outside any render pass: what vxDepthTex reads without LODs. */
    private static void ensureFarView() {
        if (farView != null) {
            return;
        }
        farTexture = RenderSystem.getDevice().createTexture("Voxy far depth", GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_FLOAT, 1, 1, 1, 1);
        java.nio.ByteBuffer one = org.lwjgl.system.MemoryUtil.memAlloc(4).putFloat(0, 1.0F);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(farTexture, one, 0, 0, 0, 0, 1, 1);
        org.lwjgl.system.MemoryUtil.memFree(one);
        farView = RenderSystem.getDevice().createTextureView(farTexture);
    }

    /** With the frame's submit (VulkanCommandEncoderMixin): the pack's passes are done with the set's depth. */
    public static void beforeSubmit() {
        if (vkDoneAtSubmit && frameSet != null) {
            vkDoneAtSubmit = false;
            VulkanCommandEncoder vulkan = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
            vulkan.signalSemaphore(frameSet.vkDone[0], 0L, ALL_COMMANDS);
            frameSet.vkDonePending = true;
        }
    }

    private static void dropPackPipeline() {
        GL45C.glFinish();
        packReadSet = null;
        // Forget which pack it was built for, else the same pack coming back (shaders toggled or reloaded) finds an
        // equal key and never builds the pipeline again: no LODs.
        packKey = "";
        if (packPipeline != null) {
            packPipeline.close();
            packPipeline = null;
        }
    }

    /** Voxy builds its render system again (new pipeline: the pack's, or its own). */
    private static void recreateRenderer() {
        me.cortex.voxy.client.core.IVoxyRenderSystemHolder holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder != null && holder.voxy$getRenderSystem() != null) {
            holder.voxy$shutdownRenderer();
            holder.voxy$createRenderer();
        }
    }

    /** Reverse-Z (the game's default on 26.3) clears depth to 0, else 1; Voxy decides the same way. */
    private static float clearDepth() {
        return DepthStencilState.DEFAULT.depthTest() == CompareOp.GREATER_THAN_OR_EQUAL ? 0.0F : 1.0F;
    }

    private static void ensureResources(int width, int height, GpuFormat targetFormat) {
        lightmapGlTexture();
        current.resize(width, height);
        if (compositePipeline == null || compositeFormat != targetFormat) {
            compositeFormat = targetFormat;
            RenderPipeline.Builder composite = fullscreen("composite",
                BindGroupLayout.builder().withUniform("LodColor", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("LodDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withShaderDefine("CLEAR_DEPTH", clearDepth())
                .withColorTargetState(0, new ColorTargetState(
                    Optional.of(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)), targetFormat,
                    ColorTargetState.WRITE_ALL))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true));
            compositePipeline = compile(composite.build());
        }
        if (lightmapPipeline == null) {
            RenderPipeline.Builder copy = fullscreen("lightmap",
                BindGroupLayout.builder().withUniform("Lightmap", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL));
            lightmapPipeline = compile(copy.build());
        }
    }

    private static RenderPipeline.Builder fullscreen(String name, BindGroupLayout layout) {
        return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/" + name))
            .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "fullscreen"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, name))
            .withBindGroupLayout(layout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false);
    }

    private static CompiledRenderPipeline compile(RenderPipeline pipeline) {
        return RenderSystem.getDevice().compilePipeline(pipeline, SHADERS, Runnable::run).join().finishCompile();
    }
}
