package com.viris;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Patches three of Voxy's mixin handlers after they are merged into their targets (our mixins on the same
 * targets have a higher priority, so they are applied later):
 * <ul>
 * <li>Voxy wraps the creation of the game's graphics device and throws for the Vulkan backend, so the game
 * falls back to OpenGL: its {@code instanceof VulkanBackend} test is replaced by false.</li>
 * <li>Voxy's Iris hook in {@code LevelRenderer} returns at once on Vulkan (no Iris there).</li>
 * <li>Voxy's draw hook in Sodium's chunk renderer reads the game's targets as GL textures: it returns at
 * once on Vulkan, {@link VoxyFrame} calls the same Voxy methods with shared images instead.</li>
 * </ul>
 */
public class VirisMixinPlugin implements IMixinConfigPlugin {

    private static final String VULKAN_BACKEND = "com/mojang/renderpearl/backend/vulkan/VulkanBackend";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".mixin.vitrail.")) {
            return FabricLoader.getInstance().isModLoaded("voxy") && FabricLoader.getInstance().isModLoaded("vitrail");
        }
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (targetClassName.equals(DEFAULT_CHUNK_RENDERER)) {
            addOldEndCall(targetClass);
        }
    }

    private static final String DEFAULT_CHUNK_RENDERER = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer";
    private static final String SHADER_CHUNK_RENDERER = "net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer";
    private static final String OLD_END_DESC = "(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V";

    /**
     * Sodium 0.9.3 changed {@code end(TerrainRenderPass)} to {@code end()}; Voxy 0.2.20's draw hook injects before
     * the old call and crashes the join without it. A placeholder old call goes before the new one (preApply
     * runs before all injections), and {@link #dropOldEndCall} turns it into POP2 after Voxy injected there.
     */
    private static void addOldEndCall(ClassNode target) {
        for (MethodNode method : target.methods) {
            if (!method.name.equals("render")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("end") && call.desc.equals(OLD_END_DESC)) {
                    return; // Sodium 0.9.2: nothing to do
                }
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("end") && call.desc.equals("()V")) {
                    InsnList placeholder = new InsnList();
                    placeholder.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                    placeholder.add(new InsnNode(Opcodes.ACONST_NULL));
                    placeholder.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SHADER_CHUNK_RENDERER, "end", OLD_END_DESC, false));
                    method.instructions.insertBefore(call, placeholder);
                    Viris.LOGGER.info("VIRIS: Sodium's new end() call adapted for Voxy's draw hook");
                    return;
                }
            }
        }
    }

    private static void dropOldEndCall(ClassNode target) {
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(SHADER_CHUNK_RENDERER)
                    && call.name.equals("end") && call.desc.equals(OLD_END_DESC) && method.name.equals("render")
                    && hasNewEnd(method)) {
                    method.instructions.set(call, new InsnNode(Opcodes.POP2));
                }
            }
        }
    }

    private static boolean hasNewEnd(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.name.equals("end") && call.desc.equals("()V")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (targetClassName.equals("net.minecraft.client.renderer.LevelRenderer")) {
            skipOnVulkan(targetClass, "voxy$injectIrisCompat");
            return;
        }
        if (targetClassName.equals(DEFAULT_CHUNK_RENDERER)) {
            dropOldEndCall(targetClass);
            skipOnVulkan(targetClass, "voxy$injectRender");
            return;
        }
        if (targetClassName.equals("me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData")) {
            dropIrisMethods(targetClass);
            return;
        }
        if (!targetClassName.equals("net.minecraft.client.Minecraft")) {
            return;
        }
        int patched = 0;
        for (MethodNode method : targetClass.methods) {
            if (!method.name.contains("voxy$forceOpenglDevice")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                    && type.desc.equals(VULKAN_BACKEND)) {
                    InsnList replacement = new InsnList();
                    replacement.add(new InsnNode(Opcodes.POP));
                    replacement.add(new InsnNode(Opcodes.ICONST_0));
                    method.instructions.insert(insn, replacement);
                    method.instructions.remove(insn);
                    patched++;
                }
            }
        }
        if (patched > 0) {
            Viris.LOGGER.info("VIRIS: Voxy's Vulkan device check turned off");
        } else {
            Viris.LOGGER.warn("VIRIS: Voxy's Vulkan device check not found; the game may start on OpenGL");
        }
    }

    /**
     * Voxy's shader pack pipeline data is built from Iris objects in a few static methods; without Iris the
     * class fails verification because of them. They are removed (VIRIS fills the data from Vitrail),
     * the data itself and its accessors stay as they are.
     */
    private static void dropIrisMethods(ClassNode target) {
        List<String> dropped = new java.util.ArrayList<>();
        target.methods.removeIf(method -> {
            boolean iris = method.desc.contains("net/irisshaders");
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (iris) {
                    break;
                }
                if (insn instanceof MethodInsnNode call) {
                    iris = call.owner.startsWith("net/irisshaders") || call.desc.contains("net/irisshaders")
                        || call.owner.startsWith("me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData$");
                } else if (insn instanceof TypeInsnNode type) {
                    iris = type.desc.contains("net/irisshaders") || type.desc.startsWith("me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData$")
                        && !type.desc.endsWith("$StructLayout") && !type.desc.endsWith("$ImageSet") && !type.desc.endsWith("$SSBOSet");
                } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) {
                    iris = field.owner.startsWith("net/irisshaders") || field.desc.contains("net/irisshaders");
                } else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
                    iris = indy.desc.contains("net/irisshaders") || java.util.Arrays.toString(indy.bsmArgs).contains("irisshaders");
                }
            }
            if (iris && !method.name.equals("<init>")) {
                dropped.add(method.name);
                return true;
            }
            return false;
        });
        Viris.LOGGER.info("VIRIS: Iris-only methods of Voxy's pack pipeline data left out: {}", dropped);
    }

    /** Voxy's void handler {@code name} returns right away while the game renders through Vulkan. */
    private static void skipOnVulkan(ClassNode target, String name) {
        int patched = 0;
        for (MethodNode method : target.methods) {
            if (!method.name.contains(name) || !method.desc.endsWith(")V")) {
                continue;
            }
            LabelNode run = new LabelNode();
            InsnList check = new InsnList();
            check.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/viris/Viris", "isVulkan", "()Z", false));
            check.add(new JumpInsnNode(Opcodes.IFEQ, run));
            check.add(new InsnNode(Opcodes.RETURN));
            check.add(run);
            check.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(check);
            patched++;
        }
        Viris.LOGGER.info("VIRIS: Voxy's {} skipped on Vulkan ({} handler(s))", name, patched);
    }
}
