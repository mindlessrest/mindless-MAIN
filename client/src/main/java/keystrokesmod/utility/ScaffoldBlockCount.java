package keystrokesmod.utility;

import keystrokesmod.module.ModuleManager;
import keystrokesmod.runtime.LunarEventBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

public class ScaffoldBlockCount {
    private final Minecraft mc;
    private Timer fadeTimer;
    private Timer fadeInTimer;
    private float previousAlpha;

    public ScaffoldBlockCount(Minecraft mc) {
        this.mc = mc;
        this.fadeTimer = null;
        this.fadeInTimer = new Timer(150);
        this.fadeInTimer.start();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (previousAlpha <= 10 && fadeInTimer == null) {
            onDisable();
            return;
        }
        if (!Utils.nullCheck() || ModuleManager.scaffold == null || !ModuleManager.scaffold.showBlockCount.isToggled()) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            if (mc.currentScreen != null) {
                return;
            }
            ScaledResolution scaledResolution = new ScaledResolution(mc);
            int blocks = ModuleManager.scaffold.totalBlocks();
            String color = "§";
            if (blocks <= 5) {
                color += "c";
            }
            else if (blocks <= 15) {
                color += "6";
            }
            else if (blocks <= 25) {
                color += "e";
            }
            else {
                color = "";
            }
            float alpha = fadeTimer == null ? 255 : (255 - fadeTimer.getValueInt(0, 255, 1));
            if (fadeInTimer != null) {
                alpha = fadeInTimer.getValueFloat(10, 255, 1);
                if (alpha == 255) {
                    fadeInTimer = null;
                }
            }
            previousAlpha = alpha;
            int colorAlpha = Utils.mergeAlpha(-1, (int) previousAlpha);
            float renderY = scaledResolution.getScaledHeight() / 2 + 4;

            // Stack below FastPlace block count if it's also rendering
            if (ModuleManager.fastPlace != null
                    && ModuleManager.fastPlace.isEnabled()
                    && ModuleManager.fastPlace.showBlockCount.isToggled()) {
                renderY += mc.fontRendererObj.FONT_HEIGHT + 2;
            }

            GL11.glPushMatrix();
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            String text = "§7Scaffold §8| " + color + blocks + " §7block" + (blocks == 1 ? "" : "s");
            mc.fontRendererObj.drawStringWithShadow(text, scaledResolution.getScaledWidth() / 2 + 8, renderY, colorAlpha);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glPopMatrix();
        }
    }

    public void beginFade() {
        this.fadeTimer = new Timer(150);
        this.fadeTimer.start();
        this.fadeInTimer = null;
    }

    public void onDisable() {
        // Registered through LunarEventBridge so the FML tick bus (Forge) and
        // Raven's own bus (direct Lunar) are both unhooked correctly.
        LunarEventBridge.unregisterTickListener(this);
        fadeInTimer = null;
        fadeTimer = null;
    }
}
