package mindless.clickgui.components.impl;

import mindless.clickgui.components.Component;
import mindless.module.impl.client.Gui;
import mindless.module.setting.impl.GroupSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Timer;
import mindless.utility.font.MindlessFontRenderer;
import org.lwjgl.opengl.GL11;

public class GroupComponent extends Component {
    public GroupSetting setting;
    private ModuleComponent component;
    public float o;
    private float x;
    private float y;
    public boolean opened;

    private Timer smoothTimer;
    private float animationProgress;
    private float animationStartProgress;
    private float animationTargetProgress;

    private static final float ANIMATION_DURATION = 250f;

    public GroupComponent(GroupSetting setting, ModuleComponent moduleComponent, float o) {
        this.setting = setting;
        this.component = moduleComponent;
        this.o = o;
        this.x = moduleComponent.categoryComponent.getX() + moduleComponent.categoryComponent.getWidth();
        this.y = moduleComponent.categoryComponent.getY() + moduleComponent.yPos;
        this.opened = setting.isOpened();
        this.animationProgress = opened ? 1f : 0f;
        this.animationStartProgress = this.animationProgress;
        this.animationTargetProgress = this.animationProgress;
    }

    /**
     * Current live animation progress: 0 closed, 1 open.
     * Used for height, indentation, and reveal during animation.
     */
    public float getAnimationProgress() {
        if (smoothTimer != null) {
            if (System.currentTimeMillis() - smoothTimer.last >= ANIMATION_DURATION + 30) {
                smoothTimer = null;
                animationProgress = animationTargetProgress;
                animationStartProgress = animationTargetProgress;
            } else {
                animationProgress = smoothTimer.getValueFloat(animationStartProgress, animationTargetProgress, 1);
                if (animationProgress == animationTargetProgress) {
                    smoothTimer = null;
                    animationStartProgress = animationTargetProgress;
                }
            }
        }
        return animationProgress;
    }

    public void render() {
        float progress = getAnimationProgress();
        MindlessFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        float cx = this.component.categoryComponent.getX();
        float cy = this.component.categoryComponent.getY();
        RenderUtils.drawRoundedRectangle(cx + 3, cy + this.o + 1,
                cx + this.component.categoryComponent.getWidth() - 3, cy + this.o + 11,
                3.0f, 0x321D2330);

        GL11.glPushMatrix();
        GL11.glScaled(0.5D, 0.5D, 0.5D);
        float strX = (cx + 5) * 2;
        float strY = (cy + this.o + 3) * 2;
        int arrowWidth = renderer.getStringWidth(">");
        int fontHeight = renderer.getFontHeight();

        GL11.glPushMatrix();
        GL11.glTranslatef(strX + arrowWidth / 2.0f, strY + fontHeight / 2.0f, 0F);
        GL11.glRotatef(90F * progress, 0F, 0F, 1F);
        GL11.glTranslatef(-arrowWidth / 2.0f, -fontHeight / 2.0f, 0F);
        drawString(renderer, ">", 0, 0);
        GL11.glPopMatrix();

        renderer.drawString(this.setting.getName(), strX + arrowWidth + 6, strY, 0xFFC7CFDC, false);
        GL11.glPopMatrix();
    }

    public void updateHeight(float n) {
        this.o = n;
    }

    @Override
    public float getOffset() {
        return this.o;
    }

    public void drawScreen(int x, int y) {
        this.y = this.component.categoryComponent.getModuleY() + this.o;
        this.x = this.component.categoryComponent.getX();
    }

    public boolean onClick(int x, int y, int b) {
        if (this.overGroup(x, y) && (b == 0 || b == 1) && this.component.isOpened) {
            float currentProgress = getAnimationProgress();
            this.animationStartProgress = currentProgress;
            this.opened = !this.opened;
            this.setting.setOpened(this.opened);
            this.animationTargetProgress = this.opened ? 1f : 0f;
            (this.smoothTimer = new Timer(ANIMATION_DURATION)).start();
            this.component.updateSettingPositions();
            return true;
        }
        return false;
    }

    public void onGuiClosed() {
        smoothTimer = null;
        animationProgress = opened ? 1f : 0f;
        animationStartProgress = animationProgress;
        animationTargetProgress = animationProgress;
    }

    public boolean overGroup(int x, int y) {
        return x > this.x && x < this.x + this.component.categoryComponent.getWidth() && y > this.y && y < this.y + 11;
    }

    private void drawString(MindlessFontRenderer renderer, String text, float x, float y) {
        renderer.drawString(text, x, y, -1, false);
    }
}
