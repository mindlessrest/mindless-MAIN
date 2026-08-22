package mindless.utility.gui;

import mindless.utility.RenderUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * The client's own button widget.
 *
 * Every screen in here used to use Forge's {@code GuiButtonExt}. That class is compiled against
 * Forge's <em>patched</em> {@code GuiButton}, which carries an extra {@code packedFGColour} field,
 * and its {@code drawButton} reads that field on every frame. Lunar runs unpatched vanilla
 * classes, so the field does not exist at runtime and the read throws {@link NoSuchFieldError} the
 * first time the screen paints. The class itself resolves fine -- the build bundles
 * {@code net/minecraftforge/**} -- so nothing fails until the button is actually drawn, which is
 * why every "edit position" button opened its screen, showed one frame, and then took the game
 * down with it.
 *
 * This extends vanilla {@link GuiButton} and paints itself, touching nothing Forge added.
 */
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

        // The rounded-rect shader pops a GL attribute stack, which leaves GlStateManager's cache
        // describing state the driver no longer has. Anything drawn after it inherits the
        // mismatch, so put the cache back in step before the accent bar and the label.
        RenderUtils.syncGlState();
        RenderUtils.resetColor();

        if (lit) {
            // Inset past the corner radius so the bar stays inside the rounded outline.
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
