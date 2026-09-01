package mindless.utility.gui;

import mindless.utility.RenderUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
public class MindlessButton extends GuiButton {
    private static final int FILL = 0xE0181B1C;
    private static final int FILL_HOVER = 0xEC262A2B;
    private static final int FILL_DISABLED = 0xB0121415;
    private static final int TEXT = 0xFFEBEAE6;
    private static final int TEXT_HOVER = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF696C6C;
    private static final int ACCENT = 0xFF9F8FD2;
    private static final float RADIUS = 4.0F;

    public MindlessButton(int id, int x, int y, String text) {
        super(id, x, y, text);
    }

    public MindlessButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }

        this.hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        boolean lit = this.enabled && this.hovered;

        RoundedUtils.drawRound(this.xPosition, this.yPosition, this.width, this.height, RADIUS,
                !this.enabled ? FILL_DISABLED : this.hovered ? FILL_HOVER : FILL);
        RenderUtils.syncGlState();
        RenderUtils.resetColor();

        if (lit) {
            int inset = Math.round(RADIUS);
            drawRect(this.xPosition + inset, this.yPosition + this.height - 1,
                    this.xPosition + this.width - inset, this.yPosition + this.height, ACCENT);
        }

        int color = !this.enabled ? TEXT_DISABLED : lit ? TEXT_HOVER : TEXT;
        this.drawCenteredString(mc.fontRendererObj, this.displayString,
                this.xPosition + this.width / 2, this.yPosition + (this.height - 8) / 2, color);

        this.mouseDragged(mc, mouseX, mouseY);
    }
}
