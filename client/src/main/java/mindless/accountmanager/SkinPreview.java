package mindless.accountmanager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * A turnable full-body skin preview.
 *
 * Built on ModelPlayer's boxes directly rather than on GuiInventory.drawEntityOnScreen, because
 * that needs a live EntityLivingBase and therefore a world -- and this screen is usually opened
 * from the main menu, where there is not one. Driving the model parts by hand needs no entity at
 * all, and it is what allows a pose of our own instead of whatever the entity happened to be
 * doing.
 *
 * Drag to turn it. Released, it drifts back to facing you and the head follows the cursor, so the
 * panel looks alive without the model spinning on its own.
 */
public final class SkinPreview {
    private static final float MODEL_SCALE = 0.0625f;

    private ModelPlayer classic;
    private ModelPlayer slim;

    private float yaw = 20.0f;
    private float pitch = 0.0f;
    /** Where the drag started, and the rotation it started from. */
    private boolean dragging;
    private int dragFromX;
    private int dragFromY;
    private float dragYaw;
    private float dragPitch;
    private long lastFrame;
    /** Where the figure ended up last frame, so callers can draw against it. */
    private float lastCenterX;
    private float lastFeetY;
    private float lastScale;

    public float bodyCenterX() {
        return lastCenterX;
    }

    public float bodyTop() {
        return lastFeetY - lastScale * 2.0f;
    }

    public float bodyBottom() {
        return lastFeetY;
    }

    /** Half the shoulder-to-shoulder span, arms included. */
    public float bodyHalfWidth() {
        return lastScale * 0.52f;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void beginDrag(int mouseX, int mouseY) {
        dragging = true;
        dragFromX = mouseX;
        dragFromY = mouseY;
        dragYaw = yaw;
        dragPitch = pitch;
    }

    public void endDrag() {
        dragging = false;
    }

    public void reset() {
        dragging = false;
        yaw = 20.0f;
        pitch = 0.0f;
    }

    /**
     * @return true when the click landed on the preview and was taken as a drag.
     */
    public boolean mouseClicked(int mouseX, int mouseY, int x, int y, int width, int height) {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
            return false;
        }
        beginDrag(mouseX, mouseY);
        return true;
    }

    public void draw(ResourceLocation skin, boolean slimModel, int x, int y, int width, int height,
                     int mouseX, int mouseY) {
        if (skin == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();

        long now = System.currentTimeMillis();
        float delta = lastFrame == 0L ? 0.0f : Math.min(0.1f, (now - lastFrame) / 1000.0f);
        lastFrame = now;

        if (dragging) {
            yaw = dragYaw + (mouseX - dragFromX);
            // Clamped so it cannot be rolled past upside down, which has no useful view in it.
            pitch = Math.max(-35.0f, Math.min(35.0f, dragPitch + (mouseY - dragFromY) * 0.5f));
        }
        else {
            // Ease back to the front rather than snapping, and let go of the pitch entirely.
            float blend = Math.min(1.0f, delta * 4.0f);
            yaw += wrap(20.0f - yaw) * blend;
            pitch += (0.0f - pitch) * blend;
        }

        // The model is two units tall once ModelRenderer applies its 1/16 scale. Width is
        // capped too, or a short wide panel would push the arms outside it.
        float scale = Math.min(height / 2.2f, width / 1.3f);
        float centerX = x + width / 2.0f;
        // Vanilla stands the doll on its feet rather than on its middle, so this is where
        // the feet have to land for the body to sit centred in the panel.
        float feetY = y + (height + scale * 2.0f) / 2.0f;
        lastCenterX = centerX;
        lastFeetY = feetY;
        lastScale = scale;

        ModelPlayer model = model(slimModel);
        pose(model, mouseX, mouseY, centerX, feetY - scale * 1.6f);

        // Fixed-function geometry sent through whatever shader the surrounding screen last
        // bound comes out as nothing, or as a flat black cut-out.
        OpenGlHelper.glUseProgram(0);

        GlStateManager.pushMatrix();
        GlStateManager.enableColorMaterial();
        GlStateManager.translate(centerX, feetY, 100.0f);
        // Only X is mirrored. Model space already runs +Y downward, the same way GUI space
        // does, so negating Y as well is what had it standing on its head -- the entity path
        // flips Y because it goes through world space first, and this does not.
        GlStateManager.scale(-scale, scale, scale);
        // The lighting rig is built square to the camera and the turn undone straight away,
        // which is what the inventory doll does. Enabling it further down, after the model
        // rotations, lit the figure from behind instead and left it reading as a silhouette.
        GlStateManager.rotate(135.0f, 0.0f, 1.0f, 0.0f);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135.0f, 0.0f, 1.0f, 0.0f);
        GlStateManager.rotate(pitch, 1.0f, 0.0f, 0.0f);
        // The biped model is built facing away down -Z, and the GUI camera looks the other
        // way, so without this half turn the preview opens on the back of the head.
        GlStateManager.rotate(yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        // Applied first, in model space: drops the body so its feet, not its middle, sit on
        // the origin -- the same offset the entity renderer uses.
        GlStateManager.translate(0.0f, -1.5078125f, 0.0f);

        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableRescaleNormal();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        // The second layer is transparent where it is unused, so it needs blending and the alpha
        // test on or a slim skin renders a solid block around the arms.
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01f);

        mc.getTextureManager().bindTexture(skin);
        renderModel(model);

        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableColorMaterial();
        GlStateManager.popMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private ModelPlayer model(boolean slimModel) {
        if (slimModel) {
            if (slim == null) {
                slim = new ModelPlayer(0.0f, true);
            }
            return slim;
        }
        if (classic == null) {
            classic = new ModelPlayer(0.0f, false);
        }
        return classic;
    }

    /**
     * A standing pose with the head tracking the cursor.
     *
     * Set every angle explicitly: the model keeps whatever it was last given, so leaving one out
     * carries a stale rotation over from the previous frame.
     */
    private void pose(ModelPlayer model, int mouseX, int mouseY, float centerX, float centerY) {
        float lookYaw = 0.0f;
        float lookPitch = 0.0f;
        if (!dragging) {
            lookYaw = Math.max(-35.0f, Math.min(35.0f, (centerX - mouseX) * 0.25f));
            lookPitch = Math.max(-25.0f, Math.min(25.0f, (mouseY - centerY) * 0.25f));
        }

        model.bipedHead.rotateAngleY = (float) Math.toRadians(lookYaw);
        model.bipedHead.rotateAngleX = (float) Math.toRadians(lookPitch);
        model.bipedHead.rotateAngleZ = 0.0f;
        copyAngles(model.bipedHead, model.bipedHeadwear);

        model.bipedBody.rotateAngleX = 0.0f;
        model.bipedBody.rotateAngleY = 0.0f;
        model.bipedBody.rotateAngleZ = 0.0f;

        // A few degrees out from the body, so the arms read as separate from the torso instead
        // of merging into one silhouette at this size.
        model.bipedRightArm.rotateAngleX = 0.0f;
        model.bipedRightArm.rotateAngleY = 0.0f;
        model.bipedRightArm.rotateAngleZ = (float) Math.toRadians(5.0);
        model.bipedLeftArm.rotateAngleX = 0.0f;
        model.bipedLeftArm.rotateAngleY = 0.0f;
        model.bipedLeftArm.rotateAngleZ = (float) Math.toRadians(-5.0);

        model.bipedRightLeg.rotateAngleX = 0.0f;
        model.bipedRightLeg.rotateAngleY = 0.0f;
        model.bipedRightLeg.rotateAngleZ = 0.0f;
        model.bipedLeftLeg.rotateAngleX = 0.0f;
        model.bipedLeftLeg.rotateAngleY = 0.0f;
        model.bipedLeftLeg.rotateAngleZ = 0.0f;

        copyAngles(model.bipedBody, model.bipedBodyWear);
        copyAngles(model.bipedRightArm, model.bipedRightArmwear);
        copyAngles(model.bipedLeftArm, model.bipedLeftArmwear);
        copyAngles(model.bipedRightLeg, model.bipedRightLegwear);
        copyAngles(model.bipedLeftLeg, model.bipedLeftLegwear);
    }

    private static void copyAngles(net.minecraft.client.model.ModelRenderer from,
                                   net.minecraft.client.model.ModelRenderer to) {
        if (to == null) {
            return;
        }
        to.rotateAngleX = from.rotateAngleX;
        to.rotateAngleY = from.rotateAngleY;
        to.rotateAngleZ = from.rotateAngleZ;
        to.rotationPointX = from.rotationPointX;
        to.rotationPointY = from.rotationPointY;
        to.rotationPointZ = from.rotationPointZ;
    }

    /**
     * Draw the boxes directly instead of calling ModelBase.render.
     *
     * render wants an Entity to read limb swing and age from, and there is no entity here. The
     * parts themselves need nothing but a scale.
     */
    private void renderModel(ModelPlayer model) {
        model.bipedBody.render(MODEL_SCALE);
        model.bipedRightArm.render(MODEL_SCALE);
        model.bipedLeftArm.render(MODEL_SCALE);
        model.bipedRightLeg.render(MODEL_SCALE);
        model.bipedLeftLeg.render(MODEL_SCALE);
        model.bipedHead.render(MODEL_SCALE);

        // Second layer last so it blends over the base rather than being depth-rejected by it.
        renderIfPresent(model.bipedBodyWear);
        renderIfPresent(model.bipedRightArmwear);
        renderIfPresent(model.bipedLeftArmwear);
        renderIfPresent(model.bipedRightLegwear);
        renderIfPresent(model.bipedLeftLegwear);
        renderIfPresent(model.bipedHeadwear);
    }

    private void renderIfPresent(net.minecraft.client.model.ModelRenderer part) {
        if (part != null) {
            part.render(MODEL_SCALE);
        }
    }

    private static float wrap(float degrees) {
        while (degrees > 180.0f) {
            degrees -= 360.0f;
        }
        while (degrees < -180.0f) {
            degrees += 360.0f;
        }
        return degrees;
    }
}
