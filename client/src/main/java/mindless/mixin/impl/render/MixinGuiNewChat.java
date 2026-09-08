package mindless.mixin.impl.render;

import mindless.module.impl.render.ChatModule;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.runtime.ChatWrapping;
import mindless.runtime.GuiNewChatState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@SideOnly(Side.CLIENT)
@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {
    @Unique
    private static final long MINDLESS_MESSAGE_ANIMATION_MS = 320L;

    @Unique
    private final Map<ChatLine, Long> mindless$messageBirths = new IdentityHashMap<ChatLine, Long>();

    @Unique
    private long mindless$lastAnimationCleanup;

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

    /**
     * Wraps incoming lines with the font they will be drawn in.
     *
     * setChatLine splits with mc.fontRendererObj and stores the result, so a wider custom face
     * left every stored line too long -- text ran past the panel and off the screen edge, and
     * nothing downstream re-measures it. With no custom font this hands straight back to vanilla.
     */
    @Redirect(method = "setChatLine", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiUtilRenderComponents;splitText(Lnet/minecraft/util/IChatComponent;ILnet/minecraft/client/gui/FontRenderer;ZZ)Ljava/util/List;"))
    private List<IChatComponent> mindless$splitChatLine(IChatComponent component, int wrapWidth,
                                                        FontRenderer font, boolean spaceAtEnd,
                                                        boolean keepFormatting) {
        return ChatWrapping.split(component, wrapWidth, font, spaceAtEnd, keepFormatting);
    }

    @Inject(method = "drawChat", at = @At("HEAD"), cancellable = true)
    private void mindless$renderChat(int updateCounter, CallbackInfo ci) {
        mindless.utility.Diagnostics.glSnapshot("chat draw (mixin path)");
        mindless.utility.RenderUtils.beginTextPass();
        if (mc.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) return;
        int totalLines = drawnChatLines.size();
        if (totalLines <= 0) {
            ci.cancel();
            return;
        }

        boolean chatOpen = getChatOpen();
        int lineCount = ChatModule.lines(getLineCount());
        float scale = Math.max(0.1f, getChatScale());
        int chatWidth = MathHelper.ceiling_float_int(ChatModule.width(getChatWidth()) / scale);
        int visibleLines = Math.min(lineCount, Math.max(0, totalLines - scrollPos));
        ScaledResolution sr = ScaledResolutionCache.get();
        long now = System.currentTimeMillis();
        mindless$updateMessageAnimations(now);

        double newestProgress = 1.0;
        if (scrollPos == 0 && !drawnChatLines.isEmpty()) {
            newestProgress = mindless$getAnimationProgress(drawnChatLines.get(0), now);
        }
        double newestEase = mindless$easeOutCubic(newestProgress);
        double animatedRows = Math.max(0.0, visibleLines - 1.0 + newestEase);
        MindlessFontRenderer chatFont = ChatModule.getCustomFont();
        float rowHeight = (chatFont != null ? chatFont.getLineHeight() : 9.0f) + ChatModule.lineSpacing();
        if (rowHeight < 1.0f) rowHeight = 1.0f;
        float headSize = ChatModule.playerHeads() ? ChatModule.headSize() : 0.0f;
        float textIndent = headSize > 0.0f ? headSize + 2.0f : 0.0f;
        float bgX = 3.0f;
        // Hug the messages rather than spanning the configured chat width. The panel was always
        // drawn at full width whatever was in it, so a few short lines sat in a large empty slab
        // of background. Clamped to the configured width so a long line still wraps as before.
        float widestLine = 0.0f;
        for (int i = 0; i + scrollPos < totalLines && i < lineCount; i++) {
            ChatLine measured = drawnChatLines.get(i + scrollPos);
            if (measured == null) continue;
            String measuredText = measured.getChatComponent().getFormattedText();
            float lineWidth = chatFont != null
                    ? chatFont.getStringWidth(measuredText)
                    : mc.fontRendererObj.getStringWidth(measuredText);
            if (lineWidth > widestLine) widestLine = lineWidth;
        }
        float contentWidth = Math.min((float) chatWidth, widestLine + 6.0f);
        if (contentWidth < 24.0f) contentWidth = 24.0f;
        float bgW = contentWidth * scale + 10.0f + textIndent * scale;
        float bgH = (float) (animatedRows * rowHeight * scale + 10.0f);
        float bgBottom = sr.getScaledHeight() - 23.0f;
        float bgY = bgBottom - bgH;

        if (ChatModule.drawBackground()) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0f, -(sr.getScaledHeight() - 48.0f), 0.0f);
            GuiNewChatState.drawGlass(bgX, bgY, bgW, bgH, chatOpen,
                    sr.getScaledWidth(), sr.getScaledHeight());
            GlStateManager.popMatrix();
        }

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
            double progress = mindless$getAnimationProgress(chatLine, now);
            double eased = mindless$easeOutCubic(progress);
            float x = (float) ((1.0 - eased) * -12.0);
            float y = -i * rowHeight - 8.0f;
            if (i > 0) y += insertionOffset;
            else y += (float) ((1.0 - eased) * 2.5);
            String text = chatLine.getChatComponent().getFormattedText();
            int alpha = MathHelper.clamp_int((int) Math.round(255.0 * eased), 0, 255);
            int textColor = 0xFFFFFF | (alpha << 24);

            if (headSize > 0.0f) {
                String sender = GuiNewChatState.senderOf(chatLine.getChatComponent());
                GuiNewChatState.drawPlayerHead(sender, chatLine.getChatComponent().getFormattedText(),
                        x, y - 1.0f, headSize, alpha);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO);
            }

            float textX = x + textIndent;
            if (chatFont != null) {
                chatFont.drawString(text, textX, y, textColor, ChatModule.textShadow());
            }
            else if (ChatModule.textShadow()) {
                mc.fontRendererObj.drawStringWithShadow(text, textX, y, textColor);
            }
            else {
                mc.fontRendererObj.drawString(text, textX, y, textColor, false);
            }
        }
        GlStateManager.disableBlend();
        if (chatOpen && isScrolled && rendered > 0) {
            int fontHeight = Math.max(1, Math.round(rowHeight));
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
    private void mindless$updateMessageAnimations(long now) {
        for (ChatLine line : drawnChatLines) {
            if (line != null && !mindless$messageBirths.containsKey(line)) {
                mindless$messageBirths.put(line, now);
            }
        }
        if (now - mindless$lastAnimationCleanup >= 1000L) {
            mindless$messageBirths.keySet().retainAll(drawnChatLines);
            mindless$lastAnimationCleanup = now;
        }
    }

    @Unique
    private double mindless$getAnimationProgress(ChatLine line, long now) {
        Long birth = mindless$messageBirths.get(line);
        if (birth == null) return 1.0;
        return MathHelper.clamp_double((now - birth) / (double) MINDLESS_MESSAGE_ANIMATION_MS, 0.0, 1.0);
    }

    @Unique
    private double mindless$easeOutCubic(double progress) {
        double remaining = 1.0 - progress;
        return 1.0 - remaining * remaining * remaining;
    }

}
