package mindless.transformer.impl.render;

import mindless.runtime.FontRendererState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.gui.FontRenderer;
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
