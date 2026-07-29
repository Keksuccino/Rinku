package de.keksuccino.rinku.mixins;

import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinMinecraftContractTest {

    @Test
    void screenInterceptionTargetsMinecraftOn2612() throws Exception {
        Method shadow = MixinMinecraft.class.getDeclaredMethod("setScreen", Screen.class);

        assertNotNull(shadow.getAnnotation(Shadow.class));
        assertTrue(Modifier.isAbstract(shadow.getModifiers()));
    }

    @Test
    void screenInterceptionRunsCancellablyBeforeVanillaChangesScreens() throws Exception {
        Method hook = MixinMinecraft.class.getDeclaredMethod("before_setScreen_RINKU", Screen.class, CallbackInfo.class);
        Inject injection = hook.getAnnotation(Inject.class);

        assertNotNull(injection);
        assertArrayEquals(new String[]{"setScreen"}, injection.method());
        assertEquals("HEAD", injection.at()[0].value());
        assertTrue(injection.cancellable());
    }

    @Test
    void renderMailboxAndShutdownHooksRemainDirectFrameLifecycleHooks() throws Exception {
        Method frameHook = MixinMinecraft.class.getDeclaredMethod("before_runTick_RINKU", boolean.class, CallbackInfo.class);
        Method shutdownHook = MixinMinecraft.class.getDeclaredMethod("before_close_RINKU", CallbackInfo.class);
        Inject frameInjection = frameHook.getAnnotation(Inject.class);
        Inject shutdownInjection = shutdownHook.getAnnotation(Inject.class);

        assertNotNull(frameInjection);
        assertArrayEquals(new String[]{"runTick"}, frameInjection.method());
        assertEquals("HEAD", frameInjection.at()[0].value());
        assertNotNull(shutdownInjection);
        assertArrayEquals(new String[]{"close"}, shutdownInjection.method());
        assertEquals("HEAD", shutdownInjection.at()[0].value());
    }
}
