package de.keksuccino.rinku.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinMinecraftBackportContractTest {

    @Test
    void downloaderScreenGateTargetsMinecraftSetScreen() throws Exception {
        Method vanillaMethod = Minecraft.class.getDeclaredMethod("setScreen", Screen.class);
        Method hook = MixinMinecraft.class.getDeclaredMethod("before_setScreen_RINKU", Screen.class, CallbackInfo.class);
        Inject injection = hook.getAnnotation(Inject.class);
        AnnotationNode mixin = readClassAnnotation(MixinMinecraft.class, Type.getDescriptor(Mixin.class));
        int targetsIndex = mixin.values.indexOf("value");

        assertTrue(targetsIndex >= 0);
        assertEquals(List.of(Type.getType(Minecraft.class)), mixin.values.get(targetsIndex + 1));
        assertEquals(void.class, vanillaMethod.getReturnType());
        assertArrayEquals(new String[]{"setScreen"}, injection.method());
        assertEquals("HEAD", injection.at()[0].value());
        assertTrue(injection.cancellable());
    }

    @Test
    void frameHookMatchesThe1192GameRendererDescriptor() throws Exception {
        Method vanillaMethod = GameRenderer.class.getDeclaredMethod("render", float.class, long.class, boolean.class);
        Method hook = MixinGameRenderer.class.getDeclaredMethod("before_render_Rinku", float.class, long.class, boolean.class, CallbackInfo.class);
        Inject injection = hook.getAnnotation(Inject.class);

        assertEquals(void.class, vanillaMethod.getReturnType());
        assertArrayEquals(new String[]{"render"}, injection.method());
        assertEquals("HEAD", injection.at()[0].value());
    }

    @Test
    void textureCleanupAccessorTargetsThe1192TextureMap() throws Exception {
        TextureManager.class.getDeclaredField("byPath");
        Method accessorMethod = AccessorMixinTextureManager.class.getDeclaredMethod("getByPath_Rinku");
        Accessor accessor = accessorMethod.getAnnotation(Accessor.class);

        assertEquals("byPath", accessor.value());
    }

    private static AnnotationNode readClassAnnotation(Class<?> type, String descriptor) throws Exception {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            assertNotNull(input);
            ClassNode classNode = new ClassNode();
            new ClassReader(input).accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (AnnotationNode annotation : classNode.invisibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
            for (AnnotationNode annotation : classNode.visibleAnnotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        throw new AssertionError("Missing class annotation " + descriptor + " on " + type.getName());
    }
}
