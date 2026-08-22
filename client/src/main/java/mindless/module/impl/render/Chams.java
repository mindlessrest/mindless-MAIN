package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.world.AntiBot;
import mindless.module.setting.impl.ButtonSetting;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

public class Chams extends Module {
    private ButtonSetting ignoreBots;
    private ButtonSetting renderSelf;
    private ButtonSetting hidePlayers;

    /**
     * Whether the polygon offset is currently pushed. The pre and post hooks used to each work
     * out for themselves whether they applied, which came apart the moment a setting changed
     * between the two halves of one player's render and left the offset stuck on.
     */
    private static boolean offsetPushed;

    public Chams() {
        super("Chams", Module.category.render, 0);
        this.liteModule = true;
        this.registerSetting(ignoreBots = new ButtonSetting("Ignore bots", false));
        this.registerSetting(hidePlayers = new ButtonSetting("Hide players", false));
        this.registerSetting(renderSelf = new ButtonSetting("Render self", false));
    }

    /**
     * Called from the RenderPlayer hook rather than through {@code RenderPlayerEvent}. Forge
     * posts that event from its own patched copy of the render classes; Lunar has no Forge
     * patches, so nothing ever posted it and this module did nothing at all.
     *
     * @return true when the player should not be drawn.
     */
    public static boolean onRenderPlayerPre(Entity entity) {
        offsetPushed = false;

        Module module = ModuleManager.getModule(Chams.class);
        if (!(module instanceof Chams) || !module.isEnabled() || entity == null) {
            return false;
        }
        Chams chams = (Chams) module;

        boolean self = entity == mc.thePlayer;
        if (self && (!chams.renderSelf.isToggled() || mc.currentScreen != null)) {
            return false;
        }
        if (chams.hidePlayers.isToggled() && !(self && chams.renderSelf.isToggled())) {
            return true;
        }
        if (chams.ignoreBots.isToggled() && AntiBot.isBot(entity)) {
            return false;
        }

        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(1.0f, -4_000_000.0f);
        offsetPushed = true;
        return false;
    }

    public static void onRenderPlayerPost(Entity entity) {
        if (!offsetPushed) {
            return;
        }
        offsetPushed = false;
        GL11.glPolygonOffset(1.0f, 4_000_000.0f);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    @Override
    public void onDisable() {
        if (offsetPushed) {
            offsetPushed = false;
            GL11.glPolygonOffset(1.0f, 4_000_000.0f);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }
    }
}
