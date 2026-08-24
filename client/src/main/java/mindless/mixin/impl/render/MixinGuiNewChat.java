package mindless.mixin.impl.render;

import mindless.module.impl.client.Settings;
import mindless.utility.TextGlowUtils;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.runtime.GuiNewChatState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@SideOnly(Side.CLIENT)
@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {
    @Unique
    private static final long RAVEN_MESSAGE_ANIMATION_MS = 320L;

    @Unique
    private final Map<ChatLine, Long> raven$messageBirths = new IdentityHashMap<ChatLine, Long>();

    @Unique
    private long raven$lastAnimationCleanup;

    @Shadow
    private Minecraft mc;

    @Shadow
    private List<ChatLine> drawnChatLines;

    @Shadow
    private int scrollPos;

    @Shadow
    private boolean isScrolled;

    @Shadow
    public abstract boolean getChatOpen();

    @Shadow
    public abstract int getLineCount();

    @Shadow
    public abstract float getChatScale();

    @Shadow
    public abstract int getChatWidth();

    @Inject(method = "drawChat", at = @At("HEAD"), cancellable = true)
    private void raven$renderChat(int updateCounter, CallbackInfo ci) {
        if (mc.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) return;
        int totalLines = drawnChatLines.size();
        if (totalLines <= 0) {
            ci.cancel();
            return;
        }

        boolean chatOpen = getChatOpen();
        int lineCount = getLineCount();
        float scale = Math.max(0.1f, getChatScale());
        int chatWidth = MathHelper.ceiling_float_int(getChatWidth() / scale);
        int visibleLines = Math.min(lineCount, Math.max(0, totalLines - scrollPos));
        ScaledResolution sr = ScaledResolutionCache.get();
        long now = System.currentTimeMillis();
        raven$updateMessageAnimations(now);

        double newestProgress = 1.0;
        if (scrollPos == 0 && !drawnChatLines.isEmpty()) {
            newestProgress = raven$getAnimationProgress(drawnChatLines.get(0), now);
        }
        double newestEase = raven$easeOutCubic(newestProgress);
        double animatedRows = Math.max(0.0, visibleLines - 1.0 + newestEase);

        // Match the scoreboard panel: 5px content padding, the same blur,
        // translucent fill, rounded shadow and inset-from-edge placement.
        float bgX = 3.0f;
        float bgW = chatWidth * scale + 10.0f;
        float bgH = (float) (animatedRows * 9.0f * scale + 10.0f);
        float bgBottom = sr.getScaledHeight() - 23.0f;
        float bgY = bgBottom - bgH;

        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0f, -(sr.getScaledHeight() - 48.0f), 0.0f);
        GuiNewChatState.drawGlass(bgX, bgY, bgW, bgH, false,
                sr.getScaledWidth(), sr.getScaledHeight());
        GlStateManager.popMatrix();

        GlStateManager.pushMatrix();
        GlStateManager.translate(8.0f, 20.0f, 0.0f);
        GlStateManager.scale(scale, scale, 1.0f);

        int rendered = 0;
        float insertionOffset = scrollPos == 0 ? (float) ((1.0 - newestEase) * 9.0) : 0.0f;
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        for (int i = 0; i + scrollPos < totalLines && i < lineCount; i++) {
            ChatLine chatLine = drawnChatLines.get(i + scrollPos);
            if (chatLine == null) continue;
            rendered++;
            double progress = raven$getAnimationProgress(chatLine, now);
            double eased = raven$easeOutCubic(progress);
            float x = (float) ((1.0 - eased) * -12.0);
            float y = -i * 9.0f - 8.0f;
            if (i > 0) y += insertionOffset;
            else y += (float) ((1.0 - eased) * 2.5);
            String text = chatLine.getChatComponent().getFormattedText();
            int alpha = MathHelper.clamp_int((int) Math.round(255.0 * eased), 0, 255);
            int textColor = 0xFFFFFF | (alpha << 24);
            if (Settings.chatGlow != null && Settings.chatGlow.isToggled()) {
                TextGlowUtils.drawGlow(mc.fontRendererObj, text, x, y, textColor);
            }
            mc.fontRendererObj.drawStringWithShadow(text, x, y, textColor);
        }
        GlStateManager.disableBlend();

        // Only while the chat has actually been scrolled back, and clear of the text.
        //
        // Vanilla translates the whole thing three pixels left before drawing this, so the bar
        // lands outside the message column. That translate was lost when this rendering was
        // rewritten, leaving it at local x 0..3 -- directly on top of the first characters of
        // every line, which is the stray coloured stripe down the side of the chat box. Drawing
        // it whenever the backlog overflowed meant it appeared the moment chat was opened, with
        // nothing to scroll, and in vanilla red-and-blue that matched nothing else on screen.
        if (chatOpen && isScrolled && rendered > 0) {
            int fontHeight = mc.fontRendererObj.FONT_HEIGHT;
            int totalHeight = totalLines * fontHeight + totalLines;
            int visibleHeight = rendered * fontHeight + rendered;
            if (totalHeight > visibleHeight) {
                int scrollbarY = scrollPos * visibleHeight / totalHeight;
                int scrollbarHeight = Math.max(4, visibleHeight * visibleHeight / totalHeight);
                net.minecraft.client.gui.Gui.drawRect(-5, -scrollbarY, -3,
                        -scrollbarY - scrollbarHeight, 0xFFFFFF | (120 << 24));
            }
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        ci.cancel();
    }

    @Unique
    private void raven$updateMessageAnimations(long now) {
        for (ChatLine line : drawnChatLines) {
            if (line != null && !raven$messageBirths.containsKey(line)) {
                raven$messageBirths.put(line, now);
            }
        }

        // Avoid allocating a temporary identity set every rendered frame.
        // Old animation entries only need occasional cleanup.
        if (now - raven$lastAnimationCleanup >= 1000L) {
            raven$messageBirths.keySet().retainAll(drawnChatLines);
            raven$lastAnimationCleanup = now;
        }
    }

    @Unique
    private double raven$getAnimationProgress(ChatLine line, long now) {
        Long birth = raven$messageBirths.get(line);
        if (birth == null) return 1.0;
        return MathHelper.clamp_double((now - birth) / (double) RAVEN_MESSAGE_ANIMATION_MS, 0.0, 1.0);
    }

    @Unique
    private double raven$easeOutCubic(double progress) {
        double remaining = 1.0 - progress;
        return 1.0 - remaining * remaining * remaining;
    }

}
