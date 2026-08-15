package keystrokesmod.transformer.impl.render;

import keystrokesmod.runtime.FontRendererState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.gui.FontRenderer;

/**
 * MixinFontRenderer replacement.
 *
 * The original used @ModifyVariable to rewrite the incoming {@code string}
 * argument. ClassTransform's equivalent (@CLocalVariable + @CInject) is
 * bytecode-fragile at STORE positions. We take a simpler route: cancel the
 * call at HEAD, replace the string, and re-invoke the same method via
 * reflection with the modified argument. Recursion state lives outside the
 * already-loaded FontRenderer class so JVMTI retransformation preserves its
 * schema.
 */
@CTransformer(FontRenderer.class)
public abstract class TransformerFontRenderer {
    @CInline
    @CInject(method = "renderString",
            target = @CTarget("HEAD"), cancellable = true)
    private void renderStringHead(String string, float x, float y, int color,
            boolean dropShadow, InjectionCallback cir) {
        if (FontRendererState.isReentrant()) return;
        String rewritten = FontRendererState.rewrite(string);
        if (rewritten == string || (rewritten != null && rewritten.equals(string))) return;
        FontRendererState.enter();
        try {
            java.lang.reflect.Method m = FontRenderer.class.getDeclaredMethod(
                    "renderString", String.class, float.class, float.class, int.class, boolean.class);
            m.setAccessible(true);
            Object result = m.invoke(this, rewritten, x, y, color, dropShadow);
            cir.setReturnValue(result);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Method m = FontRenderer.class.getDeclaredMethod(
                        "func_180455_b", String.class, float.class, float.class, int.class, boolean.class);
                m.setAccessible(true);
                Object result = m.invoke(this, rewritten, x, y, color, dropShadow);
                cir.setReturnValue(result);
            } catch (Throwable ignored2) { /* leave original arg */ }
        } finally {
            FontRendererState.exit();
        }
    }

    @CInline
    @CInject(method = "getStringWidth",
            target = @CTarget("HEAD"), cancellable = true)
    private void getStringWidthHead(String string, InjectionCallback cir) {
        if (FontRendererState.isReentrant()) return;
        String rewritten = FontRendererState.rewrite(string);
        if (rewritten == string || (rewritten != null && rewritten.equals(string))) return;
        FontRendererState.enter();
        try {
            java.lang.reflect.Method m = FontRenderer.class.getDeclaredMethod(
                    "getStringWidth", String.class);
            m.setAccessible(true);
            Object result = m.invoke(this, rewritten);
            cir.setReturnValue(result);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Method m = FontRenderer.class.getDeclaredMethod(
                        "func_78256_a", String.class);
                m.setAccessible(true);
                Object result = m.invoke(this, rewritten);
                cir.setReturnValue(result);
            } catch (Throwable ignored2) { /* leave original arg */ }
        } finally {
            FontRendererState.exit();
        }
    }
}
