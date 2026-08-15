package keystrokesmod.clickgui.components.impl;

import keystrokesmod.Raven;
import keystrokesmod.clickgui.components.Component;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.font.RavenFontRenderer;
import keystrokesmod.utility.profile.ProfileModule;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import java.awt.*;

public class ButtonComponent extends Component {
    private static final int ENABLED_COLOR = new Color(105, 169, 255).getRGB();
    private static final int TRACK_ON = new Color(67, 130, 219, 220).getRGB();
    private static final int TRACK_OFF = new Color(43, 48, 58, 220).getRGB();
    private static final int KNOB_COLOR = new Color(238, 244, 255).getRGB();

    private Module mod;
    public ButtonSetting buttonSetting;
    private ModuleComponent moduleComponent;

    public float o;
    public float x;
    private float y;
    public float xOffset;

    public ButtonComponent(Module mod, ButtonSetting op, ModuleComponent b, float o) {
        this.mod = mod;
        this.buttonSetting = op;
        this.moduleComponent = b;
        this.x = b.categoryComponent.getX() + b.categoryComponent.getWidth();
        this.y = b.categoryComponent.getY() + b.yPos;
        this.o = o;
    }

    public void render() {
        RavenFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        float cx = this.moduleComponent.categoryComponent.getX();
        float cy = this.moduleComponent.categoryComponent.getY();
        float right = cx + this.moduleComponent.categoryComponent.getWidth() - 5;
        float trackLeft = right - 14;
        float trackTop = cy + this.o + 3;

        if (this.buttonSetting.isMethodButton) {
            RenderUtils.drawRoundedRectangle(trackLeft - 1, trackTop, right, trackTop + 7, 3.5f, TRACK_ON);
        }
        else {
            boolean enabled = this.buttonSetting.isToggled();
            RenderUtils.drawRoundedRectangle(trackLeft, trackTop, right, trackTop + 7, 3.5f,
                    enabled ? TRACK_ON : TRACK_OFF);
            float knobLeft = enabled ? right - 6 : trackLeft + 1;
            RenderUtils.drawRoundedRectangle(knobLeft, trackTop + 1, knobLeft + 5, trackTop + 6,
                    2.5f, KNOB_COLOR);
        }

        GL11.glPushMatrix();
        GL11.glScaled(0.5D, 0.5D, 0.5D);
        renderer.drawString(this.buttonSetting.getName(),
                (float) ((cx + 4) * 2) + xOffset,
                (float) ((cy + this.o + 3) * 2),
                this.buttonSetting.isToggled() ? ENABLED_COLOR : 0xFFE1E6EF,
                false);
        GL11.glPopMatrix();
    }

    public void updateHeight(float n) {
        this.o = n;
    }

    @Override
    public float getOffset() {
        return this.o;
    }

    @Override
    public boolean isBaseVisible() {
        return this.buttonSetting.visible;
    }

    public void drawScreen(int x, int y) {
        this.y = this.moduleComponent.categoryComponent.getModuleY() + this.o;
        this.x = this.moduleComponent.categoryComponent.getX();
    }

    public boolean onClick(int x, int y, int b) {
        if (this.i(x, y) && b == 0 && this.moduleComponent.isOpened && this.moduleComponent.isVisible(this)) {
            if (this.buttonSetting.isMethodButton) {
                this.buttonSetting.runMethod();
                return false;
            }
            this.buttonSetting.toggle();
            this.mod.guiButtonToggled(this.buttonSetting);
            if (Raven.currentProfile != null && !this.mod.ignoreOnSave) {
                Raven.currentProfile.getModule().saved = false;
            }
        }
        return false;
    }

    public boolean i(int x, int y) {
        return x > this.x && x < this.x + this.moduleComponent.categoryComponent.getWidth() && y > this.y && y < this.y + 11;
    }
}
