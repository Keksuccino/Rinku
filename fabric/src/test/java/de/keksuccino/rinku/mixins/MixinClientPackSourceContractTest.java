package de.keksuccino.rinku.mixins;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinClientPackSourceContractTest {
    @Test
    void classInitializerHookRunsAfterItsMixinOwnedLoggerIsInitialized() throws Exception {
        Field logger = MixinClientPackSource.class.getDeclaredField("LOGGER_RINKU");
        Method hook = MixinClientPackSource.class.getDeclaredMethod("on_clinit_RINKU", CallbackInfo.class);
        Inject injection = hook.getAnnotation(Inject.class);

        assertTrue(Modifier.isStatic(logger.getModifiers()));
        assertTrue(Modifier.isFinal(logger.getModifiers()));
        assertEquals("TAIL", injection.at()[0].value());
    }
}
