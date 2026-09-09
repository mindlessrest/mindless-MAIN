package mindless.module.impl.client;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.render.HUD;
import mindless.module.impl.render.PotionHUD;
import mindless.module.impl.render.Radar;
import mindless.module.impl.render.DynamicIsland;
import mindless.module.impl.render.StatsHUD;
import mindless.module.impl.render.TargetHUD;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.GuiIngameState;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import mindless.utility.gui.MindlessButton;
import mindless.utility.media.MediaPlayerRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The HUD layout editor, and the module that opens it.
 *
 * It is a module so it can carry a keybind and its own settings like everything else, rather than
 * living as a button buried in another module's list. Enabling it opens the screen and switches
 * straight back off, the way the Gui module does, so it never sits in the array list.
 */
public class HudEditor extends Module {

    public static ButtonSetting depthOfField;
    public static SliderSetting blurStrength;

    public HudEditor() {
        super("HUD Editor", "Drag and resize your HUD elements.", category.client);
        this.liteModule = true;
        this.registerSetting(depthOfField = new ButtonSetting("Depth of field", true));
        this.registerSetting(blurStrength = new SliderSetting("Blur strength", "%", 100.0, 0.0, 100.0, 5.0));
    }

    @Override
    public void guiUpdate() {
        blurStrength.setVisible(depthOfField.isToggled(), this);
    }

    @Override
    public void onEnable() {
        if (Utils.nullCheck()) {
            mc.displayGuiScreen(new Screen());
        }
        this.disable();
    }

    public static final class Screen extends GuiScreen {
        private static final int NW = 0, N = 1, NE = 2, E = 3, SE = 4, S = 5, SW = 6, W = 7;

        private static final float HANDLE_HALF = 2.5f;
        private static final float GRAB_HALF = 6.0f;
        private static final float MIN_SPAN = 6.0f;
        private static final int GRID_SIZE = 8;
        private static final float GUIDE_THRESHOLD = 4.0f;

        private static final int DOF_PASSES = 2;
        private static final float DOF_RADIUS = 4.2f;
        /** Fractions of the screen diagonal: sharp inside the first, fully soft past the second. */
        private static final float DOF_NEAR = 0.15f;
        private static final float DOF_FAR = 0.52f;
        private static final int DOF_SEGMENTS = 28;
        private static final float FOCUS_SMOOTH = 0.18f;
        private static final int SCRIM = 0x5E000000;

        private float focusX = Float.NaN;
        private float focusY = Float.NaN;

        private final List<Element> elements = new ArrayList<Element>();
        private MindlessButton doneButton;
        private MindlessButton resetAllButton;
        private MindlessButton snapButton;
        private Element hovered;
        private Element selected;
        private Element dragging;
        private float dragOffsetX;
        private float dragOffsetY;

        private Element resizing;
        private int resizeHandle = -1;
        private float resizeStartScale;
        private float resizeStartWidth;
        private float resizeStartHeight;
        private float resizeAnchorX;
        private float resizeAnchorY;
        private boolean snapEnabled = true;
        private boolean guideX;
        private boolean guideY;

        @Override
        public void initGui() {
            super.initGui();
            buildElements();
            buttonList.add(doneButton = new MindlessButton(1, width - 63, 5, 58, 20, "Done"));
            buttonList.add(resetAllButton = new MindlessButton(3, width - 132, 5, 64, 20, "Reset"));
            buttonList.add(snapButton = new MindlessButton(2, width - 222, 5, 85, 20, snapLabel()));
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawBackdrop(mouseX, mouseY);
            drawGrid();

            if (elements.isEmpty()) buildElements();
            if (dragging != null) {
                moveDragged(mouseX - dragOffsetX, mouseY - dragOffsetY);
            }
            if (resizing != null) {
                applyResize(mouseX, mouseY);
            }

            for (Element element : elements) {
                element.render();
                if (element != resizing) element.ensureOnScreen(width, height);
            }
            if (resizing != null) anchorResized();

            hovered = findTopmost(mouseX, mouseY);

            for (Element element : elements) {
                boolean active = element == selected || element == hovered || element == dragging || element == resizing;
                drawOutline(element, active ? 0xE6FFFFFF : 0x26FFFFFF);
                if (active) {
                    drawLabel(element);
                    if (element == selected || element == dragging || element == resizing) {
                        drawHandles(element, mouseX, mouseY);
                    }
                }
            }

            if (guideX) RenderUtils.drawRect(width * 0.5F, 30.0F, width * 0.5F + 0.5F, height, 0x807C6CFF);
            if (guideY) RenderUtils.drawRect(0.0F, height * 0.5F, width, height * 0.5F + 0.5F, 0x807C6CFF);

            drawRect(0, 0, width, 30, 0xE6101013);
            RenderUtils.drawRect(0.0F, 29.0F, width, 30.0F, 0x303A3A43);
            fontRendererObj.drawString("HUD editor", 9, 10, 0xFFF2F2F5, false);
            String hint = selected == null
                    ? "Drag an element to reposition it"
                    : selected.name + "  ·  Arrow keys to nudge  ·  R to reset";
            drawRect(0, height - 20, width, height, 0xD9101013);
            fontRendererObj.drawString(hint, 9, height - 14, 0xFF9898A3, false);

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
            if (mouseButton == 0) {
                int handle = hovered != null ? hovered.handleAt(mouseX, mouseY) : -1;
                if (handle >= 0) {
                    selected = hovered;
                    beginResize(hovered, handle);
                }
                else {
                    Element selected = findTopmost(mouseX, mouseY);
                    if (selected != null) {
                        this.selected = selected;
                        dragging = selected;
                        dragOffsetX = mouseX - selected.left;
                        dragOffsetY = mouseY - selected.top;
                    } else if (mouseY > 30 && mouseY < height - 20) {
                        this.selected = null;
                    }
                }
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                dragging = null;
                resizing = null;
                resizeHandle = -1;
                guideX = false;
                guideY = false;
            }
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button == doneButton) {
                mc.displayGuiScreen(null);
            }
            else if (button == snapButton) {
                snapEnabled = !snapEnabled;
                snapButton.displayString = snapLabel();
            }
            else if (button == resetAllButton) {
                for (Element element : elements) element.reset();
                selected = null;
            }
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) throws IOException {
            if (keyCode == Keyboard.KEY_G) {
                snapEnabled = !snapEnabled;
                snapButton.displayString = snapLabel();
                return;
            }
            if (selected != null && keyCode == Keyboard.KEY_R) {
                selected.reset();
                return;
            }
            if (selected != null && (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT
                    || keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN)) {
                float step = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)
                        ? GRID_SIZE : 1.0F;
                float x = selected.left;
                float y = selected.top;
                if (keyCode == Keyboard.KEY_LEFT) x -= step;
                if (keyCode == Keyboard.KEY_RIGHT) x += step;
                if (keyCode == Keyboard.KEY_UP) y -= step;
                if (keyCode == Keyboard.KEY_DOWN) y += step;
                selected.moveClamped(x, y, width, height);
                return;
            }
            super.keyTyped(typedChar, keyCode);
        }

        private String snapLabel() {
            return snapEnabled ? "Snap 8px" : "Snap off";
        }

        private void drawGrid() {
            int minor = 0x0DFFFFFF;
            int major = 0x16FFFFFF;
            for (int x = GRID_SIZE; x < width; x += GRID_SIZE) {
                int color = x % (GRID_SIZE * 4) == 0 ? major : minor;
                RenderUtils.drawRect(x, 30.0F, x + 0.5F, height - 20.0F, color);
            }
            for (int y = 32; y < height - 20; y += GRID_SIZE) {
                int color = y % (GRID_SIZE * 4) == 0 ? major : minor;
                RenderUtils.drawRect(0.0F, y, width, y + 0.5F, color);
            }
        }

        private void moveDragged(float requestedLeft, float requestedTop) {
            guideX = false;
            guideY = false;
            float elementWidth = dragging.right - dragging.left;
            float elementHeight = dragging.bottom - dragging.top;

            if (snapEnabled && !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    && !Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
                requestedLeft = Math.round(requestedLeft / GRID_SIZE) * GRID_SIZE;
                requestedTop = Math.round(requestedTop / GRID_SIZE) * GRID_SIZE;
            }

            float centeredLeft = (width - elementWidth) * 0.5F;
            float centeredTop = (height - elementHeight) * 0.5F;
            if (Math.abs(requestedLeft - centeredLeft) <= GUIDE_THRESHOLD) {
                requestedLeft = centeredLeft;
                guideX = true;
            }
            if (Math.abs(requestedTop - centeredTop) <= GUIDE_THRESHOLD) {
                requestedTop = centeredTop;
                guideY = true;
            }
            dragging.moveClamped(requestedLeft, requestedTop, width, height);
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        /**
         * The world behind, thrown out of focus around whatever you are working on.
         *
         * A true depth of field wants the depth buffer, and 1.8.9 attaches its depth as a
         * renderbuffer rather than a texture, so there is nothing here to sample -- reworking the
         * game's framebuffer to get one would be a large change to pay for a backdrop. This does
         * the part you actually see. One blur of the frame is composited back through a mask that
         * is clear over the element under the cursor and opaque out towards the edges, so focus
         * falls away from where you are looking.
         *
         * The cost is one blur chain. The pyramid it builds is cached per frame inside KawaseBlur
         * and shared with every other blur on screen, the mask is three draw calls, and the
         * composite is a single full screen quad -- so this is one pass whatever the screen holds.
         */
        private void drawBackdrop(int mouseX, int mouseY) {
            if (HudEditor.depthOfField == null || !HudEditor.depthOfField.isToggled()) {
                drawRect(0, 0, width, height, 0x88000000);
                return;
            }

            float strength = (float) (HudEditor.blurStrength.getInput() / 100.0);
            if (strength <= 0.01f) {
                drawRect(0, 0, width, height, 0x88000000);
                return;
            }

            Element focus = dragging != null ? dragging : resizing != null ? resizing : hovered;
            boolean framed = focus != null && focus.hasBounds();
            float targetX = framed ? (focus.left + focus.right) * 0.5f : mouseX;
            float targetY = framed ? (focus.top + focus.bottom) * 0.5f : mouseY;
            if (Float.isNaN(focusX)) {
                focusX = targetX;
                focusY = targetY;
            }
            focusX += (targetX - focusX) * FOCUS_SMOOTH;
            focusY += (targetY - focusY) * FOCUS_SMOOTH;

            float diagonal = (float) Math.sqrt(width * (double) width + height * (double) height);
            BlurUtils.prepareBlur();
            drawFocusMask(focusX, focusY, diagonal * DOF_NEAR, diagonal * DOF_FAR);
            BlurUtils.blurEnd(DOF_PASSES, DOF_RADIUS, strength);

            drawRect(0, 0, width, height, SCRIM);
        }

        /**
         * Writes the circle of confusion into the blur mask.
         *
         * Blending is off on purpose: the shapes overwrite one another rather than mixing, so the
         * ring's ramp lands exactly on top of the screen fill instead of adding to it. Alpha is
         * what the composite shader reads, and the colour never matters.
         */
        private void drawFocusMask(float centreX, float centreY, float near, float far) {
            GlStateManager.disableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.disableDepth();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer buffer = tessellator.getWorldRenderer();

            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            buffer.pos(0.0, height, 0.0).color(255, 255, 255, 255).endVertex();
            buffer.pos(width, height, 0.0).color(255, 255, 255, 255).endVertex();
            buffer.pos(width, 0.0, 0.0).color(255, 255, 255, 255).endVertex();
            buffer.pos(0.0, 0.0, 0.0).color(255, 255, 255, 255).endVertex();
            tessellator.draw();

            buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
            buffer.pos(centreX, centreY, 0.0).color(255, 255, 255, 0).endVertex();
            for (int i = 0; i <= DOF_SEGMENTS; i++) {
                double angle = i * Math.PI * 2.0 / DOF_SEGMENTS;
                buffer.pos(centreX + Math.cos(angle) * near, centreY + Math.sin(angle) * near, 0.0)
                        .color(255, 255, 255, 0).endVertex();
            }
            tessellator.draw();

            buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= DOF_SEGMENTS; i++) {
                double angle = i * Math.PI * 2.0 / DOF_SEGMENTS;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                buffer.pos(centreX + cos * near, centreY + sin * near, 0.0)
                        .color(255, 255, 255, 0).endVertex();
                buffer.pos(centreX + cos * far, centreY + sin * far, 0.0)
                        .color(255, 255, 255, 255).endVertex();
            }
            tessellator.draw();

            GlStateManager.enableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
        }
private void beginResize(Element element, int handle) {
            SliderSetting slider = element.scaleSetting();
            if (slider == null) return;

            float elementWidth = element.right - element.left;
            float elementHeight = element.bottom - element.top;
            if (elementWidth < MIN_SPAN || elementHeight < MIN_SPAN) return;

            resizing = element;
            resizeHandle = handle;
            resizeStartScale = (float) slider.getInput();
            resizeStartWidth = elementWidth;
            resizeStartHeight = elementHeight;
            resizeAnchorX = anchorX(element, handle);
            resizeAnchorY = anchorY(element, handle);
        }

        private void applyResize(float mouseX, float mouseY) {
            SliderSetting slider = resizing.scaleSetting();
            if (slider == null) return;

            float dx = Math.abs(mouseX - resizeAnchorX);
            float dy = Math.abs(mouseY - resizeAnchorY);

            float ratio;
            if (resizeHandle == N || resizeHandle == S) ratio = dy / resizeStartHeight;
            else if (resizeHandle == E || resizeHandle == W) ratio = dx / resizeStartWidth;
            else ratio = (dx / resizeStartWidth + dy / resizeStartHeight) * 0.5f;

            if (Float.isNaN(ratio) || Float.isInfinite(ratio) || ratio <= 0.0f) return;
            slider.setValue(resizeStartScale * ratio);
        }

        private void anchorResized() {
            float elementWidth = resizing.right - resizing.left;
            float elementHeight = resizing.bottom - resizing.top;

            float left = resizeAnchorX;
            float top = resizeAnchorY;
            if (resizeHandle == NW || resizeHandle == SW || resizeHandle == W) left -= elementWidth;
            if (resizeHandle == NW || resizeHandle == N || resizeHandle == NE) top -= elementHeight;

            resizing.moveTo(left, top);
        }

        private static float anchorX(Element element, int handle) {
            switch (handle) {
                case NW: case W: case SW: return element.right;
                case N: case S: case NE: case E: case SE: default: return element.left;
            }
        }

        private static float anchorY(Element element, int handle) {
            switch (handle) {
                case NW: case N: case NE: return element.bottom;
                case W: case E: case SW: case S: case SE: default: return element.top;
            }
        }

        private void drawHandles(Element element, int mouseX, int mouseY) {
            if (element.scaleSetting() == null || !element.hasBounds()) return;

            for (int handle = 0; handle < 8; handle++) {
                float hx = element.handleX(handle);
                float hy = element.handleY(handle);
                boolean hot = (resizing == element && resizeHandle == handle)
                        || (resizing == null && Math.abs(mouseX - hx) <= GRAB_HALF
                                             && Math.abs(mouseY - hy) <= GRAB_HALF);

                float half = hot ? HANDLE_HALF + 1.0f : HANDLE_HALF;
                RoundedUtils.drawRound(hx - half, hy - half, half * 2.0f, half * 2.0f, 1.5f,
                        new Color(0, 0, 0, 200));
                RoundedUtils.drawRound(hx - half + 1.0f, hy - half + 1.0f,
                        half * 2.0f - 2.0f, half * 2.0f - 2.0f, 1.0f,
                        hot ? new Color(255, 255, 255) : new Color(222, 225, 234));
            }
        }

        private void buildElements() {
            elements.clear();

            elements.add(new Element("Array List") {
                @Override
                void render() {
                    setBounds(HUD.renderDesignerPreview());
                }

                @Override
                void moveTo(float left, float top) {
                    HUD.setDesignerTopLeft(left, top);
                    setBounds(HUD.renderDesignerPreview());
                }

                @Override
                void reset() {
                    HUD.resetPosition();
                }

                @Override
                SliderSetting scaleSetting() {
                    return HUD.fontSize;
                }
            });

            elements.add(new Element("Scoreboard") {
                @Override
                void render() {
                    setBounds(renderScoreboardPreview(null, null));
                }

                @Override
                void moveTo(float left, float top) {
                    setBounds(renderScoreboardPreview(left, top));
                }

                @Override
                void reset() {
                    HUD.resetScoreboardPosition();
                }

                @Override
                SliderSetting scaleSetting() {
                    return mindless.module.impl.render.ScoreboardModule.getScaleSetting();
                }
            });

            if (ModuleManager.spotifyMiniPlayer != null) {
                elements.add(new Element("Music Player") {
                    @Override
                    void render() {
                        setBounds(MediaPlayerRenderer.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        float elementWidth = Math.max(1.0F, right - this.left);
                        float elementHeight = Math.max(1.0F, bottom - this.top);
                        SpotifyMiniPlayer.setCustomPositionFromAbsolute(left, top, elementWidth, elementHeight,
                                new ScaledResolution(mc));
                        setBounds(MediaPlayerRenderer.renderPreview());
                    }

                    @Override
                    void reset() {
                        SpotifyMiniPlayer.clearCustomPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return SpotifyMiniPlayer.scale;
                    }
                });
            }

            if (ModuleManager.potionHUD != null) {
                final PotionHUD potion = ModuleManager.potionHUD;
                elements.add(new Element("Potion HUD") {
                    @Override
                    void render() {
                        setBounds(potion.renderDesignerPreview(potion.getPosX(), potion.getPosY()));
                    }

                    @Override
                    void moveTo(float left, float top) {
                        setBounds(potion.renderDesignerPreview(left, top));
                    }

                    @Override
                    void reset() {
                        potion.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return potion.scaleSetting();
                    }
                });
            }

            if (ModuleManager.audioVisualizer != null) {
                final mindless.module.impl.render.AudioVisualizer visualizer =
                        ModuleManager.audioVisualizer;
                elements.add(new Element("Audio Visualizer") {
                    @Override
                    void render() {
                        if (!visualizer.isEnabled()
                                || visualizer.placement() != mindless.module.impl.render.AudioVisualizer.PLACEMENT_STANDALONE) {
                            setBounds(null);
                            return;
                        }
                        setBounds(visualizer.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        setBounds(visualizer.renderDesignerPreview(left, top));
                    }

                    @Override
                    void reset() {
                        visualizer.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return visualizer.scaleSetting();
                    }
                });
            }
            for (final mindless.module.impl.bedwars.BedwarsHud panel : new mindless.module.impl.bedwars.BedwarsHud[] {
                    ModuleManager.bedTracker, ModuleManager.resourceTracker, ModuleManager.eventTimers }) {
                if (panel == null) continue;
                elements.add(new Element(panel.getName()) {
                    @Override
                    void render() {
                        setBounds(panel.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        setBounds(panel.renderDesignerPreview(left, top));
                    }

                    @Override
                    void reset() {
                        panel.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return panel.scaleSetting();
                    }
                });
            }

            if (ModuleManager.waila != null) {
                final mindless.module.impl.render.Waila waila = ModuleManager.waila;
                elements.add(new Element("WAILA") {
                    @Override
                    void render() {
                        setBounds(waila.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        setBounds(waila.renderDesignerPreview(left, top));
                    }

                    @Override
                    void reset() {
                        waila.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return waila.scaleSetting();
                    }
                });
            }

            if (ModuleManager.sessionInfo != null) {
                final mindless.module.impl.render.SessionInfo session = ModuleManager.sessionInfo;
                elements.add(new Element("Session Info") {
                    @Override
                    void render() {
                        setBounds(session.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        setBounds(session.renderDesignerPreview(left, top));
                    }

                    @Override
                    void reset() {
                        session.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return session.scaleSetting();
                    }
                });
            }


            if (ModuleManager.blockCounter != null) {
                final mindless.module.impl.render.BlockCounter counter = ModuleManager.blockCounter;
                elements.add(new Element("Block Counter") {
                    @Override
                    void render() {
                        setBounds(counter.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        ScaledResolution sr = new ScaledResolution(mc);
                        counter.setRelativePosition(left / Math.max(1, sr.getScaledWidth()),
                                top / Math.max(1, sr.getScaledHeight()));
                        setBounds(counter.renderPreview());
                    }

                    @Override
                    void reset() {
                        counter.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return counter.scaleSetting();
                    }
                });
            }

            if (ModuleManager.radar != null) {
                final Radar radar = ModuleManager.radar;
                elements.add(new Element("Radar") {
                    @Override
                    void render() {
                        setBounds(radar.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        setBounds(radar.renderDesignerPreview(left, top));
                    }

                    @Override
                    void reset() {
                        radar.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return radar.scaleSetting();
                    }
                });
            }

            if (ModuleManager.targetHUD != null) {
                final TargetHUD targetHud = ModuleManager.targetHUD;
                elements.add(new Element("Target HUD") {
                    @Override
                    void render() {
                        setBounds(targetHud.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        float offsetX = left - this.left + targetHud.posX;
                        float offsetY = top - this.top + targetHud.posY;
                        setBounds(targetHud.renderDesignerPreview(offsetX, offsetY));
                    }

                    @Override
                    void reset() {
                        targetHud.resetPosition();
                    }
                });
            }

            if (ModuleManager.statsHUD != null) {
                final StatsHUD stats = ModuleManager.statsHUD;
                if (stats.isFpsEnabled()) {
                    elements.add(new Element("FPS") {
                        @Override
                        void render() { setBounds(stats.renderFpsPreview()); }
                        @Override
                        void moveTo(float left, float top) { setBounds(stats.renderFpsAt(left, top)); }
                        @Override
                        void reset() { stats.getFpsPanel().resetPosition(); }
                        @Override
                        SliderSetting scaleSetting() { return stats.scaleSetting(); }
                    });
                }
                if (stats.isBpsEnabled()) {
                    elements.add(new Element("BPS") {
                        @Override
                        void render() { setBounds(stats.renderBpsPreview()); }
                        @Override
                        void moveTo(float left, float top) { setBounds(stats.renderBpsAt(left, top)); }
                        @Override
                        void reset() { stats.getBpsPanel().resetPosition(); }
                        @Override
                        SliderSetting scaleSetting() { return stats.scaleSetting(); }
                    });
                }
                if (stats.isPingEnabled()) {
                    elements.add(new Element("Ping") {
                        @Override
                        void render() { setBounds(stats.renderPingPreview()); }
                        @Override
                        void moveTo(float left, float top) { setBounds(stats.renderPingAt(left, top)); }
                        @Override
                        void reset() { stats.getPingPanel().resetPosition(); }
                        @Override
                        SliderSetting scaleSetting() { return stats.scaleSetting(); }
                    });
                }
            }

            if (ModuleManager.dynamicIsland != null) {
                final DynamicIsland island = ModuleManager.dynamicIsland;
                elements.add(new Element("Island Text") {
                    @Override
                    void render() {
                        setBounds(island.isIslandMode() ? null : island.getTextBounds());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        island.textPosX = left;
                        island.textPosY = top;
                        setBounds(island.getTextBounds());
                    }

                    @Override
                    void reset() {
                        island.resetPosition();
                    }
                });

                // Grabbable on every anchor. It used to be offered only on Custom, so the pill was
                // the one element here you could not drag -- you had to know to go and change a
                // setting first. Taking hold of it switches the anchor for you.
                elements.add(new Element("Dynamic Island") {
                    @Override
                    void render() {
                        setBounds(island.isIslandMode() ? island.getIslandBounds() : null);
                    }

                    @Override
                    void moveTo(float left, float top) {
                        island.moveIslandTo(left, top);
                        setBounds(island.getIslandBounds());
                    }

                    @Override
                    void reset() {
                        island.resetPosition();
                    }
                });
            }
        }

        private float[] renderScoreboardPreview(Float requestedX, Float requestedY) {
            ScaledResolution resolution = new ScaledResolution(mc);
            FontRenderer font = fontRendererObj;
            String title;
            String[] rows;
            if (!GuiIngameState.visibleLines.isEmpty()) {
                net.minecraft.scoreboard.ScoreObjective obj = mc.theWorld != null
                        ? mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1) : null;
                title = obj != null ? obj.getDisplayName() : "Scoreboard";
                rows = GuiIngameState.visibleLines.toArray(new String[0]);
            } else {
                title = "MINDLESS";
                rows = new String[] { "Mode: Bed Wars", "Kills: 4", "Beds: 1", "Wins: 12" };
            }
            float scale = GuiIngameState.SCOREBOARD_SCALE
                    * mindless.module.impl.render.ScoreboardModule.getScale();
            int contentWidth = font.getStringWidth(title);
            for (String row : rows) contentWidth = Math.max(contentWidth, font.getStringWidth(row));
            float lineHeight = font.FONT_HEIGHT * scale;
            float panelWidth = contentWidth * scale + GuiIngameState.HORIZONTAL_PADDING * 2.0F;
            float panelHeight = (rows.length + 1) * lineHeight
                    + GuiIngameState.VERTICAL_PADDING * 2.0F + 2.0F;
            float defaultLeft = resolution.getScaledWidth() - 3.0F - panelWidth;
            float defaultTop = resolution.getScaledHeight() / 2.0F - panelHeight * 0.5F;

            if (requestedX != null && requestedY != null) {
                HUD.setScoreboardPosition(requestedX, requestedY, panelWidth, panelHeight, resolution);
            }
            float left = HUD.getScoreboardX(panelWidth, resolution, defaultLeft);
            float top = HUD.getScoreboardY(panelHeight, resolution, defaultTop);
            float right = left + panelWidth;
            float bottom = top + panelHeight;

            BlurUtils.prepareBlur(left, top, panelWidth, panelHeight);
            RoundedUtils.drawRound(left, top, panelWidth, panelHeight,
                    GuiIngameState.panelRadius(), 0xFF000000);
            BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                    left - 2.0F, top - 2.0F, panelWidth + 4.0F, panelHeight + 4.0F);
            RoundedUtils.drawRound(left, top, panelWidth, panelHeight,
                    GuiIngameState.panelRadius(), GuiIngameState.PANEL_FILL_COLOR);

            GlStateManager.pushMatrix();
            GlStateManager.scale(scale, scale, 1.0F);
            float titleWidth = font.getStringWidth(title) * scale;
            int titleX = Math.round((left + (panelWidth - titleWidth) * 0.5F) / scale);
            int titleY = Math.round((top + GuiIngameState.VERTICAL_PADDING) / scale);
            font.drawString(title, titleX, titleY, 0xFFFFFFFF);
            for (int i = 0; i < rows.length; i++) {
                int y = Math.round((bottom - GuiIngameState.VERTICAL_PADDING
                        - (i + 1) * lineHeight) / scale);
                int rowX = Math.round((left + GuiIngameState.HORIZONTAL_PADDING) / scale);
                font.drawString(rows[i], rowX, y, 0xFFFFFFFF);
            }
            GlStateManager.popMatrix();
            return new float[] { left, top, right, bottom };
        }
private Element findTopmost(float mouseX, float mouseY) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                Element element = elements.get(i);
                if (element.contains(mouseX, mouseY)) return element;
            }
            for (int i = elements.size() - 1; i >= 0; i--) {
                Element element = elements.get(i);
                if (element.handleAt(mouseX, mouseY) >= 0) return element;
            }
            return null;
        }

        private void drawOutline(Element element, int color) {
            if (!element.hasBounds()) return;
            RenderUtils.drawRect(element.left - 1.0F, element.top - 1.0F, element.right + 1.0F, element.top, color);
            RenderUtils.drawRect(element.left - 1.0F, element.bottom, element.right + 1.0F, element.bottom + 1.0F, color);
            RenderUtils.drawRect(element.left - 1.0F, element.top - 1.0F, element.left, element.bottom + 1.0F, color);
            RenderUtils.drawRect(element.right, element.top - 1.0F, element.right + 1.0F, element.bottom + 1.0F, color);
        }

        private void drawLabel(Element element) {
            String text = element.name;
            SliderSetting slider = element.scaleSetting();
            if (slider != null) {
                text = text + "  " + String.format("%.2fx", slider.getInput());
            }
            int textWidth = fontRendererObj.getStringWidth(text);
            float labelTop = element.top >= 48.0F ? element.top - 14.0F : element.bottom + 4.0F;
            RoundedUtils.drawRound(element.left - 3.0F, labelTop - 2.0F,
                    textWidth + 6.0F, 12.0F, 4.0F, new Color(0, 0, 0, 190));
            fontRendererObj.drawString(text, element.left, labelTop, 0xFFFFFFFF, true);
        }

        private abstract class Element {
            final String name;
            float left;
            float top;
            float right;
            float bottom;

            Element(String name) {
                this.name = name;
            }

            abstract void render();
            abstract void moveTo(float left, float top);
            abstract void reset();
SliderSetting scaleSetting() {
                return null;
            }

            void setBounds(float[] bounds) {
                if (bounds == null || bounds.length < 4) {
                    left = top = right = bottom = 0.0F;
                    return;
                }
                left = bounds[0];
                top = bounds[1];
                right = bounds[2];
                bottom = bounds[3];
            }

            boolean hasBounds() {
                return right > left && bottom > top;
            }

            boolean contains(float mouseX, float mouseY) {
                return hasBounds() && mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
            }

            float handleX(int handle) {
                switch (handle) {
                    case NW: case W: case SW: return left;
                    case N: case S: return (left + right) * 0.5F;
                    default: return right;
                }
            }

            float handleY(int handle) {
                switch (handle) {
                    case NW: case N: case NE: return top;
                    case W: case E: return (top + bottom) * 0.5F;
                    default: return bottom;
                }
            }

            int handleAt(float mouseX, float mouseY) {
                if (scaleSetting() == null || !hasBounds()) return -1;
                for (int handle = 0; handle < 8; handle++) {
                    if (Math.abs(mouseX - handleX(handle)) <= GRAB_HALF
                            && Math.abs(mouseY - handleY(handle)) <= GRAB_HALF) {
                        return handle;
                    }
                }
                return -1;
            }

            void moveClamped(float requestedLeft, float requestedTop, int screenWidth, int screenHeight) {
                if (!hasBounds()) return;
                float elementWidth = right - left;
                float elementHeight = bottom - top;
                float margin = 0.0F;
                float maxLeft = Math.max(margin, screenWidth - elementWidth - margin);
                float maxTop = Math.max(margin, screenHeight - elementHeight - margin);
                float clampedLeft = Math.max(margin, Math.min(maxLeft, requestedLeft));
                float clampedTop = Math.max(margin, Math.min(maxTop, requestedTop));
                moveTo(clampedLeft, clampedTop);
            }

            void ensureOnScreen(int screenWidth, int screenHeight) {
                if (!hasBounds()) return;
                float elementWidth = right - left;
                float elementHeight = bottom - top;
                float margin = 0.0F;
                float clampedLeft = Math.max(margin,
                        Math.min(Math.max(margin, screenWidth - elementWidth - margin), left));
                float clampedTop = Math.max(margin,
                        Math.min(Math.max(margin, screenHeight - elementHeight - margin), top));
                if (Math.abs(clampedLeft - left) > 0.25F || Math.abs(clampedTop - top) > 0.25F) {
                    moveTo(clampedLeft, clampedTop);
                }
            }
        }
    }
}
