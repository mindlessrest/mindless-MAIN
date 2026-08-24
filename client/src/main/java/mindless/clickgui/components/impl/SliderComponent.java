package mindless.clickgui.components.impl;

import mindless.Raven;
import mindless.clickgui.components.Component;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.Gui;
import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class SliderComponent extends Component {
    public SliderSetting sliderSetting;
    private ModuleComponent moduleComponent;
    public float o;
    public float x;
    private float y;
    public boolean heldDown = false;
    private double width;
    public float xOffset;

    private double targetValue;
    private double displayedValue;
    private static final double SLIDER_SPEED = 0.6;
    private static final float SLIDER_BAR_HEIGHT = 4.0f;

    public SliderComponent(SliderSetting sliderSetting, ModuleComponent moduleComponent, float o) {
        this.sliderSetting = sliderSetting;
        this.moduleComponent = moduleComponent;
        this.o = o;

        double initial = (sliderSetting.getInput() == -1 && sliderSetting.canBeDisabled) ? -1 : sliderSetting.getInput();
        this.targetValue = initial;
        this.displayedValue = initial;
        this.width = this.sliderSetting.getInput() == -1 ? 0
            : (double) (this.moduleComponent.categoryComponent.getWidth() - 8)
                * (this.sliderSetting.getInput() - this.sliderSetting.getMin())
                / (this.sliderSetting.getMax() - this.sliderSetting.getMin());
    }

    @Override
    public void render() {
        float left = this.moduleComponent.categoryComponent.getX() + 4 + (xOffset / 2);
        float trackTop = this.moduleComponent.categoryComponent.getY() + this.o + 11;
        float trackRight = this.moduleComponent.categoryComponent.getX() + 4
                + this.moduleComponent.categoryComponent.getWidth() - 8;
        float trackBottom = this.moduleComponent.categoryComponent.getY() + this.o + 15;

        RenderUtils.drawRoundedRectangle(left, trackTop, trackRight,
                this.sliderSetting.isString ? trackBottom + 2 : trackBottom,
                4, this.sliderSetting.isString ? 0xA84A5668 : 0xDC1F242F);
        if (this.sliderSetting.isString) {
            RenderUtils.drawRoundedRectangle(left + 0.6f, trackTop + 0.6f, trackRight - 0.6f,
                    trackBottom + 1.4f, 3.4f, 0xED171C25);
        }

        float right = (float) (left + this.width);

        if (right - left > 84) {
            right = left + 84;
        }

        if (!this.sliderSetting.isString) {
            RenderUtils.drawRoundedRectangle(left, trackTop, right, trackBottom,
                    4, 0xFF69A9FF);
        }

        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);

        double input = getRenderedInputValue();
        String suffix = this.sliderSetting.getSuffix();
        String valueText;

        if (input == -1 && this.sliderSetting.canBeDisabled) {
            valueText = "\u00a7cDisabled";
            suffix = "";
        }
        else {
            if (input != 1
                && (suffix.equals(" second") || suffix.equals(" block") || suffix.equals(" tick"))
                && this.moduleComponent.mod.moduleCategory() != Module.category.scripts) {
                suffix += "s";
            }

            if (this.sliderSetting.isString) {
                int idx = (int) Math.round(input);
                idx = Math.max(0, Math.min(idx, this.sliderSetting.getOptions().length - 1));
                valueText = this.sliderSetting.getOptions()[idx];
            }
            else {
                valueText = Utils.asWholeNum(input);
            }
        }

        float labelX = (float) ((this.moduleComponent.categoryComponent.getX() + 4) * 2) + xOffset;
        float labelY = (float) ((this.moduleComponent.categoryComponent.getY() + this.o + 3) * 2);

        RavenFontRenderer settingRenderer = Gui.getClickGuiSettingFontRenderer();
        if (this.sliderSetting.isString) {
            settingRenderer.drawString(this.sliderSetting.getName(), labelX, labelY,
                    0xFFE7EAF0, true);
            String selectorText = "<  " + valueText + "  >";
            float selectorCenterX = left + trackRight;
            float selectorTextX = selectorCenterX - settingRenderer.getStringWidth(selectorText) / 2.0f;
            float selectorTextY = (trackTop + 0.3f) * 2.0f;
            settingRenderer.drawString(selectorText, selectorTextX, selectorTextY,
                    0xFF8FC5FF, false);
        }
        else if (shouldPreviewFontSlider()) {
            drawFontPreview(labelX, labelY, valueText, suffix);
        }
        else {
            settingRenderer.drawString(
                this.sliderSetting.getName() + ": " + (this.sliderSetting.isString ? "\u00a7e" : "\u00a7b") + valueText + suffix,
                labelX,
                labelY,
                -1,
                true
            );
        }

        GL11.glPopMatrix();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        this.y = this.moduleComponent.categoryComponent.getModuleY() + this.o;
        this.x = this.moduleComponent.categoryComponent.getX();

        if (this.heldDown) {
            double d = Math.min(this.moduleComponent.categoryComponent.getWidth() - 8, Math.max(0, mouseX - this.x));
            if (d == 0.0 && this.sliderSetting.canBeDisabled) {
                this.targetValue = -1;
            }
            else {
                double n = roundToInterval(
                    d / (double) (this.moduleComponent.categoryComponent.getWidth() - 8)
                        * (this.sliderSetting.getMax() - this.sliderSetting.getMin()) + this.sliderSetting.getMin(),
                    4
                );
                this.targetValue = n;
            }

            this.displayedValue = displayedValue + (targetValue - displayedValue) * SLIDER_SPEED;

            if (!shouldCommitOnRelease()) {
                if (targetValue == -1) {
                    sliderSetting.setValueRaw(-1);
                }
                else {
                    sliderSetting.setValue(this.targetValue);
                }
            }

            if (this.displayedValue == -1) {
                this.width = 0;
            }
            else {
                double range = sliderSetting.getMax() - sliderSetting.getMin();
                double fraction = (this.displayedValue - sliderSetting.getMin()) / range;
                this.width = (this.moduleComponent.categoryComponent.getWidth() - 8) * fraction;
            }

            if (this.sliderSetting.getInput() != this.sliderSetting.getMin()
                && ModuleManager.hud != null
                && ModuleManager.hud.isEnabled()
                && !ModuleManager.organizedModules.isEmpty()) {
                ModuleManager.sort();
            }

            if (Raven.currentProfile != null) {
                Raven.currentProfile.getModule().saved = false;
            }
        }
    }

    public void onSliderChange() {
        double initial = (sliderSetting.getInput() == -1 && sliderSetting.canBeDisabled) ? -1 : sliderSetting.getInput();
        this.targetValue = initial;
        this.displayedValue = initial;
        this.width = this.sliderSetting.getInput() == -1 ? 0
            : (double) (this.moduleComponent.categoryComponent.getWidth() - 8)
                * (this.sliderSetting.getInput() - this.sliderSetting.getMin())
                / (this.sliderSetting.getMax() - this.sliderSetting.getMin());
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return (u(mouseX, mouseY) || i(mouseX, mouseY))
            && this.moduleComponent.isOpened
            && this.moduleComponent.isVisible(this);
    }

    public void adjustValue(int direction) {
        if (direction == 0) {
            return;
        }

        double previousValue = this.sliderSetting.getInput();
        if (this.sliderSetting.canBeDisabled && previousValue == -1) {
            if (direction > 0) {
                this.sliderSetting.setValue(this.sliderSetting.getMin());
            }
        }
        else if (this.sliderSetting.canBeDisabled
            && direction < 0
            && previousValue <= this.sliderSetting.getMin()) {
            this.sliderSetting.setValueRaw(-1);
        }
        else {
            this.sliderSetting.setValue(previousValue + direction * this.sliderSetting.getInterval());
        }

        if (Double.compare(previousValue, this.sliderSetting.getInput()) == 0) {
            return;
        }

        onSliderChange();

        if (ModuleManager.hud != null
            && ModuleManager.hud.isEnabled()
            && !ModuleManager.organizedModules.isEmpty()) {
            ModuleManager.sort();
        }

        if (Raven.currentProfile != null) {
            Raven.currentProfile.getModule().saved = false;
        }

        if (shouldCommitOnRelease()) {
            Raven.clickGui.requestScaleRefresh();
        }
    }

    /** Same rounding as the setting itself, and for the same reason it does not use BigDecimal. */
    private static double roundToInterval(double value, int places) {
        return SliderSetting.roundToInterval(value, places);
    }

    @Override
    public boolean onClick(int mouseX, int mouseY, int button) {
        if (this.sliderSetting.isString && (button == 0 || button == 1)
                && (u(mouseX, mouseY) || i(mouseX, mouseY))
                && this.moduleComponent.isOpened && this.moduleComponent.isVisible(this)) {
            int count = this.sliderSetting.getOptions().length;
            if (count > 0) {
                int current = Math.max(0, Math.min(count - 1, (int) Math.round(this.sliderSetting.getInput())));
                boolean forward = button == 0
                        ? mouseX >= this.x + this.moduleComponent.categoryComponent.getWidth() / 2.0f
                        : mouseX < this.x + this.moduleComponent.categoryComponent.getWidth() / 2.0f;
                int next = (current + (forward ? 1 : -1) + count) % count;
                this.sliderSetting.setValue(next);
                onSliderChange();
                if (Raven.currentProfile != null) {
                    Raven.currentProfile.getModule().saved = false;
                }
            }
            return true;
        }
        if ((u(mouseX, mouseY) || i(mouseX, mouseY)) && button == 0 && this.moduleComponent.isOpened && this.moduleComponent.isVisible(this)) {
            this.heldDown = true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        boolean wasHeldDown = this.heldDown;
        this.heldDown = false;
        if (button == 0 && wasHeldDown && shouldCommitOnRelease()) {
            if (targetValue == -1) {
                sliderSetting.setValueRaw(-1);
            }
            else {
                sliderSetting.setValue(this.targetValue);
            }
            onSliderChange();
            Raven.clickGui.requestScaleRefresh();
        }
    }

    public boolean u(int mouseX, int mouseY) {
        return mouseX > this.x && mouseX < this.x + this.moduleComponent.categoryComponent.getWidth() / 2 + 1 && mouseY > this.y && mouseY < this.y + 16;
    }

    public boolean i(int mouseX, int mouseY) {
        return mouseX > this.x + this.moduleComponent.categoryComponent.getWidth() / 2 && mouseX < this.x + this.moduleComponent.categoryComponent.getWidth() && mouseY > this.y && mouseY < this.y + 16;
    }

    @Override
    public void onGuiClosed() {
        this.heldDown = false;
    }

    public void updateHeight(float n) {
        this.o = n;
    }

    private boolean shouldPreviewFontSlider() {
        return this.sliderSetting.isString
            && ((this.moduleComponent.mod instanceof HUD && this.sliderSetting == HUD.font)
            || (this.moduleComponent.mod instanceof Gui && this.sliderSetting == Gui.font));
    }

    private boolean shouldCommitOnRelease() {
        return this.moduleComponent.mod instanceof Gui && this.sliderSetting == Gui.guiScale;
    }

    private double getRenderedInputValue() {
        return shouldCommitOnRelease() && this.heldDown ? this.targetValue : this.sliderSetting.getInput();
    }

    private void drawFontPreview(float labelX, float labelY, String valueText, String suffix) {
        String prefix = this.sliderSetting.getName() + ": ";
        Minecraft mc = Minecraft.getMinecraft();
        mc.fontRendererObj.drawStringWithShadow(prefix, labelX, labelY, -1);

        // Use the fixed clickgui-sized preview path for both GUI and HUD selectors.
        RavenFontRenderer previewRenderer = FontManager.getClickGuiSettingRenderer(valueText);
        float valueX = labelX + mc.fontRendererObj.getStringWidth(prefix);
        float valueY = labelY - (previewRenderer.getFontHeight() - mc.fontRendererObj.FONT_HEIGHT) / 2.0f;
        previewRenderer.drawString(valueText + suffix, valueX, valueY, 0xFFFFFF, true);
    }

    @Override
    public float getOffset() {
        return this.o;
    }

    @Override
    public boolean isBaseVisible() {
        return this.sliderSetting.visible;
    }
}
