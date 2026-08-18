package mindless.clickgui.components.impl;

import mindless.clickgui.components.Component;
import mindless.module.impl.client.Gui;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.utility.Theme;
import mindless.utility.font.RavenFontRenderer;
import org.lwjgl.opengl.GL11;

public class DescriptionComponent extends Component {
    public DescriptionSetting desc;
    private ModuleComponent p;
    public float o;
    public float x;
    public float y;

    public DescriptionComponent(DescriptionSetting desc, ModuleComponent b, float o) {
        this.desc = desc;
        this.p = b;
        this.x = b.categoryComponent.getX() + b.categoryComponent.getWidth();
        this.y = b.categoryComponent.getY() + b.yPos;
        this.o = o;
    }

    public void render() {
        RavenFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        GL11.glPushMatrix();
        GL11.glScaled(0.5D, 0.5D, 0.5D);
        renderer.drawString(this.desc.getDesc(), (float) ((this.p.categoryComponent.getX() + 4) * 2), (float) ((this.p.categoryComponent.getY() + this.o + 3) * 2), 0xFF929DAE, false);
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
        return this.desc.visible;
    }
}
