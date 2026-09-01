package mindless.transformer.impl.render;

import mindless.module.impl.client.Settings;
import mindless.module.impl.render.ChatModule;
import mindless.runtime.GuiNewChatState;
import mindless.runtime.HudTextRenderer;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.TextGlowUtils;
import mindless.utility.ScaledResolutionCache;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.GL11;

import java.util.List;

import net.minecraft.client.gui.Gui;

@CTransformer(GuiNewChat.class)
public abstract class TransformerGuiNewChat {

    @CShadow
    private Minecraft mc;

    @CShadow
    private List<ChatLine> drawnChatLines;

    @CShadow
    private int scrollPos;

    @CShadow
    private boolean isScrolled;

    @CShadow
    public abstract boolean getChatOpen();

    @CShadow
    public abstract int getLineCount();

    @CShadow
    public abstract float getChatScale();

    @CShadow
    public abstract int getChatWidth();

    @CInline
    @CInject(method = "drawChat", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$renderChat(int updateCounter, InjectionCallback ci) {
        if (mc.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) return;
        int totalLines = drawnChatLines.size();
        if (totalLines <= 0) {
            ci.setCancelled(true);
            return;
        }

        boolean chatOpen = getChatOpen();
        int lineCount = getLineCount();
        float scale = Math.max(0.1f, getChatScale());
        int chatWidth = MathHelper.ceiling_float_int(getChatWidth() / scale);
        int visibleLines = Math.min(lineCount, Math.max(0, totalLines - scrollPos));
        ScaledResolution sr = ScaledResolutionCache.get();
        long now = System.currentTimeMillis();
        GuiNewChatState.updateMessageAnimations(drawnChatLines, now);

        double newestProgress = 1.0;
        if (scrollPos == 0 && !drawnChatLines.isEmpty()) {
            newestProgress = GuiNewChatState.getAnimationProgress(drawnChatLines.get(0), now);
        }
        double newestEase = GuiNewChatState.easeOutCubic(newestProgress);
        double animatedRows = Math.max(0.0, visibleLines - 1.0 + newestEase);
        MindlessFontRenderer chatFont = ChatModule.getCustomFont();
        float rowHeight = (chatFont != null ? chatFont.getLineHeight() : 9.0f) + ChatModule.lineSpacing();
        if (rowHeight < 1.0f) rowHeight = 1.0f;
        float headSize = ChatModule.playerHeads() ? ChatModule.headSize() : 0.0f;
        float textIndent = headSize > 0.0f ? headSize + 2.0f : 0.0f;
        float bgX = 3.0f;
        float bgW = chatWidth * scale + 10.0f + textIndent * scale;
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
            double progress = GuiNewChatState.getAnimationProgress(chatLine, now);
            double eased = GuiNewChatState.easeOutCubic(progress);
            float x = (float) ((1.0 - eased) * -12.0);
            float y = -i * rowHeight - 8.0f;
            if (i > 0) y += insertionOffset;
            else y += (float) ((1.0 - eased) * 2.5);
            String text = chatLine.getChatComponent().getFormattedText();
            int alpha = MathHelper.clamp_int((int) Math.round(255.0 * eased), 0, 255);
            int textColor = 0xFFFFFF | (alpha << 24);

            if (headSize > 0.0f) {
                String sender = GuiNewChatState.senderOf(chatLine.getChatComponent());
                GuiNewChatState.drawPlayerHead(sender, x, y - 1.0f, headSize, alpha);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO);
            }

            boolean glow = Settings.chatGlow != null && Settings.chatGlow.isToggled();
            HudTextRenderer.draw(chatFont, mc.fontRendererObj, text, x + textIndent, y,
                    textColor, ChatModule.textShadow(), glow);
        }
        GlStateManager.disableBlend();
        if (chatOpen && isScrolled && rendered > 0) {
            float fontHeight = rowHeight;
            int totalHeight = (int) (totalLines * fontHeight) + totalLines;
            int visibleHeight = (int) (rendered * fontHeight) + rendered;
            if (totalHeight > visibleHeight) {
                int scrollbarY = scrollPos * visibleHeight / totalHeight;
                int scrollbarHeight = Math.max(4, visibleHeight * visibleHeight / totalHeight);
                Gui.drawRect(-5, -scrollbarY, -3,
                        -scrollbarY - scrollbarHeight, 0xFFFFFF | (120 << 24));
            }
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        ci.setCancelled(true);
    }
}
