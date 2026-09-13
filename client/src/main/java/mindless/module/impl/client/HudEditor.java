package mindless.module.impl.client;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.render.HUD;
import mindless.module.impl.render.PotionHUD;
import mindless.module.impl.render.Radar;
import mindless.module.impl.render.DynamicIsland;
import mindless.module.impl.render.StatsHUD;
import mindless.module.impl.render.TargetHUD;
import mindless.module.impl.render.Watermark;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.GuiIngameState;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import mindless.utility.media.MediaPlayerRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
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
        this.registerSetting(blurStrength = new SliderSetting("Blur strength", "%", 35.0, 0.0, 100.0, 5.0));
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
        /** Half the side of the square a handle answers the mouse in. */
        private static final float GRAB_HALF = 5.0f;
        /**
         * How far inside an element's edge a handle still answers.
         *
         * Handles used to answer in a twelve-pixel square centred on the edge, so on an element
         * twenty pixels tall the top and bottom rows of handles between them covered nearly all of
         * it, and a press meant to drag started a resize instead. Now only a sliver inside the edge
         * belongs to a handle; everything deeper is the element, and moves it.
         */
        private static final float GRAB_INSIDE = 2.0f;
        /** Below this size an element gets its four corner handles and no edge ones. */
        private static final float EDGE_HANDLE_MIN_WIDTH = 44.0f;
        private static final float EDGE_HANDLE_MIN_HEIGHT = 28.0f;
        private static final float MIN_SPAN = 6.0f;
        private static final int GRID_SIZE = 8;
        private static final float GUIDE_THRESHOLD = 4.0f;
        private static final float SCROLL_SCALE_STEP = 0.05f;

        private static final int BACKDROP_BLUR_PASSES = 1;
        private static final float BACKDROP_BLUR_RADIUS = 1.6f;
        private static final int SCRIM = 0x2A000000;
        private static final int GUIDE = 0xB07C6CFF;

        private static final float TOOLBAR_HEIGHT = 18.0f;
        private static final float TOOLBAR_MARGIN = 8.0f;

        private final List<Element> elements = new ArrayList<Element>();
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
        private final List<float[]> guides = new ArrayList<float[]>();

        /** An element whose scale the wheel just changed, held at its centre until it redraws. */
        private Element scrolled;
        private float scrolledCenterX;
        private float scrolledCenterY;

        private float toolbarX = Float.NaN;
        private float toolbarY = Float.NaN;
        private float toolbarAlpha = 1.0f;
        private final float[][] toolbarButtons = new float[3][];

        @Override
        public void initGui() {
            super.initGui();
            buildElements();
            toolbarX = Float.NaN;
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawBackdrop();
            if (dragging != null && snapEnabled && !shiftDown()) drawGrid();

            if (elements.isEmpty()) buildElements();
            guides.clear();
            if (dragging != null) {
                moveDragged(mouseX - dragOffsetX, mouseY - dragOffsetY);
            }
            if (resizing != null) {
                applyResize(mouseX, mouseY);
            }

            for (Element element : elements) {
                element.render();
                if (element == scrolled) {
                    float half = (element.right - element.left) * 0.5F;
                    float halfHeight = (element.bottom - element.top) * 0.5F;
                    element.moveTo(scrolledCenterX - half, scrolledCenterY - halfHeight);
                    scrolled = null;
                }
                if (element != resizing) element.ensureOnScreen(width, height);
            }
            if (resizing != null) anchorResized();

            hovered = findTopmost(mouseX, mouseY);

            for (Element element : elements) {
                // An element that has never had a size is one whose module drew nothing at all.
                // Outlining those put empty rectangles over the screen and stacked them wherever
                // zero happened to fall.
                if (!element.hasBounds()) continue;
                boolean focused = element == selected || element == dragging || element == resizing;
                boolean lit = focused || element == hovered;
                drawOutline(element, focused ? 0xF07C6CFF : lit ? 0xB8FFFFFF : 0x30FFFFFF);
                if (focused) drawHandles(element, mouseX, mouseY);
            }
            for (float[] guide : guides) {
                RenderUtils.drawRect(guide[0], guide[1], guide[2], guide[3], GUIDE);
            }
            Element labelled = dragging != null ? dragging : resizing != null ? resizing
                    : hovered != null ? hovered : selected;
            if (labelled != null && labelled.hasBounds()) drawLabel(labelled, labelled == selected);

            drawToolbar(mouseX, mouseY);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
            if (mouseButton == 0 && clickToolbar(mouseX, mouseY)) return;
            if (mouseButton == 0) {
                int handle = -1;
                if (selected != null && selected.hasBounds()) handle = selected.handleAt(mouseX, mouseY);
                if (handle >= 0) {
                    beginResize(selected, handle);
                }
                else {
                    Element target = findTopmost(mouseX, mouseY);
                    if (target != null) {
                        selected = target;
                        dragging = target;
                        dragOffsetX = mouseX - target.left;
                        dragOffsetY = mouseY - target.top;
                    } else {
                        selected = null;
                    }
                }
            }
            else if (mouseButton == 1) {
                // Right click resets the element under the cursor, the same as R with it selected.
                Element target = findTopmost(mouseX, mouseY);
                if (target != null) {
                    target.reset();
                    selected = target;
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
                guides.clear();
            }
        }

        @Override
        public void handleMouseInput() throws IOException {
            super.handleMouseInput();
            int wheel = org.lwjgl.input.Mouse.getEventDWheel();
            if (wheel == 0 || dragging != null || resizing != null) return;
            Element target = hovered != null ? hovered : selected;
            SliderSetting slider = target == null ? null : target.scaleSetting();
            if (slider == null || !target.hasBounds()) return;
            // The wheel resizes in place: the element keeps its centre rather than growing from
            // its corner, which is what a handle does and is not what a scroll means.
            scrolled = target;
            scrolledCenterX = (target.left + target.right) * 0.5F;
            scrolledCenterY = (target.top + target.bottom) * 0.5F;
            double factor = wheel > 0 ? 1.0 + SCROLL_SCALE_STEP : 1.0 / (1.0 + SCROLL_SCALE_STEP);
            slider.setValue(slider.getInput() * factor);
            selected = target;
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) throws IOException {
            if (keyCode == Keyboard.KEY_G) {
                snapEnabled = !snapEnabled;
                return;
            }
            if (selected != null && keyCode == Keyboard.KEY_R) {
                selected.reset();
                return;
            }
            if (selected != null && (keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_BACK)) {
                // Turning an overlay off from here saves going back to the click GUI to find it,
                // and the editor is where you notice you do not want it.
                if (selected.disableOverlay()) {
                    selected = null;
                    hovered = null;
                    dragging = null;
                    resizing = null;
                    elements.clear();
                }
                return;
            }
            if (selected != null && (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT
                    || keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN)) {
                float step = shiftDown() ? GRID_SIZE : 1.0F;
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

        private static boolean shiftDown() {
            return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        }

        /**
         * Snap, Reset all and Done, in a pill that stays out of the way of the layout being edited.
         *
         * It used to sit fixed in the top-right corner, which is exactly where array lists and
         * watermarks live, so the controls covered the things they were for. It now takes whichever
         * of four spots along the screen edges overlaps the fewest elements, glides there rather
         * than jumping, and fades nearly out while something is being dragged or resized.
         */
        private void drawToolbar(int mouseX, int mouseY) {
            String snap = snapEnabled ? "Snap on" : "Snap off";
            String[] labels = {snap, "Reset all", "Done"};
            float padding = 9.0F;
            float gap = 2.0F;
            float totalWidth = gap * 2.0F;
            float[] widths = new float[labels.length];
            for (int i = 0; i < labels.length; i++) {
                widths[i] = fontRendererObj.getStringWidth(labels[i]) + padding * 2.0F;
                totalWidth += widths[i] + (i > 0 ? gap : 0.0F);
            }

            if (dragging == null && resizing == null) {
                float[] spot = quietestToolbarSpot(totalWidth);
                if (Float.isNaN(toolbarX)) {
                    toolbarX = spot[0];
                    toolbarY = spot[1];
                } else {
                    toolbarX += (spot[0] - toolbarX) * 0.25F;
                    toolbarY += (spot[1] - toolbarY) * 0.25F;
                }
            }
            float busy = dragging != null || resizing != null ? 0.18F : 1.0F;
            toolbarAlpha += (busy - toolbarAlpha) * 0.3F;
            int alpha = Math.round(255 * toolbarAlpha);

            float x = Math.round(toolbarX);
            float y = Math.round(toolbarY);
            RoundedUtils.drawRound(x, y, totalWidth, TOOLBAR_HEIGHT, TOOLBAR_HEIGHT * 0.5F,
                    new Color(14, 14, 18, Math.round(215 * toolbarAlpha)));
            float cursor = x + gap;
            for (int i = 0; i < labels.length; i++) {
                float bx = cursor, by = y + gap, bw = widths[i], bh = TOOLBAR_HEIGHT - gap * 2.0F;
                toolbarButtons[i] = new float[]{bx, by, bx + bw, by + bh};
                boolean hot = toolbarAlpha > 0.6F && mouseX >= bx && mouseX <= bx + bw
                        && mouseY >= by && mouseY <= by + bh;
                boolean primary = i == labels.length - 1;
                if (primary || hot) {
                    int fill = primary ? (hot ? 0x7C6CFF : 0x6A5BE0) : 0xFFFFFF;
                    int fillAlpha = primary ? alpha : Math.round(28 * toolbarAlpha);
                    RoundedUtils.drawRound(bx, by, bw, bh, bh * 0.5F,
                            new Color((fill >> 16) & 255, (fill >> 8) & 255, fill & 255, fillAlpha));
                }
                int textColor = i == 0 && !snapEnabled ? 0x9898A3 : 0xF2F2F5;
                fontRendererObj.drawString(labels[i], bx + padding,
                        by + (bh - fontRendererObj.FONT_HEIGHT) * 0.5F + 1.0F,
                        (Math.max(4, alpha) << 24) | textColor, false);
                cursor += bw + gap;
            }
        }

        /** Top, bottom, left or right of centre: the one covering the least of the layout. */
        private float[] quietestToolbarSpot(float toolbarWidth) {
            float[][] spots = {
                    {(width - toolbarWidth) * 0.5F, TOOLBAR_MARGIN},
                    {(width - toolbarWidth) * 0.5F, height - TOOLBAR_HEIGHT - 48.0F},
                    {TOOLBAR_MARGIN, (height - TOOLBAR_HEIGHT) * 0.5F},
                    {width - toolbarWidth - TOOLBAR_MARGIN, (height - TOOLBAR_HEIGHT) * 0.5F}
            };
            float[] best = spots[0];
            float bestOverlap = Float.MAX_VALUE;
            for (float[] spot : spots) {
                float overlap = 0.0F;
                for (Element element : elements) {
                    if (!element.hasBounds()) continue;
                    float ox = Math.min(spot[0] + toolbarWidth + 4.0F, element.right)
                            - Math.max(spot[0] - 4.0F, element.left);
                    float oy = Math.min(spot[1] + TOOLBAR_HEIGHT + 4.0F, element.bottom)
                            - Math.max(spot[1] - 4.0F, element.top);
                    if (ox > 0.0F && oy > 0.0F) overlap += ox * oy;
                }
                if (overlap < bestOverlap - 0.5F) {
                    bestOverlap = overlap;
                    best = spot;
                }
            }
            return best;
        }

        private boolean clickToolbar(int mouseX, int mouseY) {
            if (toolbarAlpha < 0.6F) return false;
            for (int i = 0; i < toolbarButtons.length; i++) {
                float[] b = toolbarButtons[i];
                if (b == null || mouseX < b[0] || mouseX > b[2] || mouseY < b[1] || mouseY > b[3]) continue;
                if (i == 0) {
                    snapEnabled = !snapEnabled;
                } else if (i == 1) {
                    for (Element element : elements) element.reset();
                    selected = null;
                } else {
                    mc.displayGuiScreen(null);
                }
                return true;
            }
            return false;
        }

        private void drawGrid() {
            int minor = 0x0AFFFFFF;
            int major = 0x14FFFFFF;
            for (int x = GRID_SIZE; x < width; x += GRID_SIZE) {
                int color = x % (GRID_SIZE * 4) == 0 ? major : minor;
                RenderUtils.drawRect(x, 0.0F, x + 0.5F, height, color);
            }
            for (int y = GRID_SIZE; y < height; y += GRID_SIZE) {
                int color = y % (GRID_SIZE * 4) == 0 ? major : minor;
                RenderUtils.drawRect(0.0F, y, width, y + 0.5F, color);
            }
        }

        /**
         * Places the dragged element, snapping to the grid, the screen's centre lines and edges,
         * and the edges and centres of the other elements.
         *
         * Alignment to neighbours is what the grid alone never gave: two panels could only be
         * lined up by eye. Snaps within a few pixels win over the grid, and each one draws the
         * guide it snapped to. Holding shift places freely.
         */
        private void moveDragged(float requestedLeft, float requestedTop) {
            float elementWidth = dragging.right - dragging.left;
            float elementHeight = dragging.bottom - dragging.top;
            if (!snapEnabled || shiftDown()) {
                dragging.moveClamped(requestedLeft, requestedTop, width, height);
                return;
            }

            float left = Math.round(requestedLeft / GRID_SIZE) * GRID_SIZE;
            float top = Math.round(requestedTop / GRID_SIZE) * GRID_SIZE;

            List<Float> xTargets = new ArrayList<Float>();
            List<Float> yTargets = new ArrayList<Float>();
            xTargets.add(0.0F);
            xTargets.add(width * 0.5F);
            xTargets.add((float) width);
            yTargets.add(0.0F);
            yTargets.add(height * 0.5F);
            yTargets.add((float) height);
            for (Element other : elements) {
                if (other == dragging || !other.hasBounds()) continue;
                xTargets.add(other.left);
                xTargets.add((other.left + other.right) * 0.5F);
                xTargets.add(other.right);
                yTargets.add(other.top);
                yTargets.add((other.top + other.bottom) * 0.5F);
                yTargets.add(other.bottom);
            }

            float[] offsetsX = {0.0F, elementWidth * 0.5F, elementWidth};
            float[] offsetsY = {0.0F, elementHeight * 0.5F, elementHeight};
            float bestDx = GUIDE_THRESHOLD + 1.0F, snappedLeft = left, guideX = Float.NaN;
            for (float target : xTargets) {
                for (float offset : offsetsX) {
                    float d = Math.abs(requestedLeft + offset - target);
                    if (d <= GUIDE_THRESHOLD && d < bestDx) {
                        bestDx = d;
                        snappedLeft = target - offset;
                        guideX = target;
                    }
                }
            }
            float bestDy = GUIDE_THRESHOLD + 1.0F, snappedTop = top, guideY = Float.NaN;
            for (float target : yTargets) {
                for (float offset : offsetsY) {
                    float d = Math.abs(requestedTop + offset - target);
                    if (d <= GUIDE_THRESHOLD && d < bestDy) {
                        bestDy = d;
                        snappedTop = target - offset;
                        guideY = target;
                    }
                }
            }
            if (!Float.isNaN(guideX)) guides.add(new float[]{guideX - 0.25F, 0.0F, guideX + 0.25F, height});
            if (!Float.isNaN(guideY)) guides.add(new float[]{0.0F, guideY - 0.25F, width, guideY + 0.25F});
            dragging.moveClamped(snappedLeft, snappedTop, width, height);
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        /**
         * A light blur behind the layout, not a frosted wall.
         *
         * Two passes at a wide radius smeared the world into a colour field, which made it hard to
         * judge how an element would actually read over it. One narrow pass keeps the scene
         * recognisable and still lifts the elements off it.
         */
        private void drawBackdrop() {
            if (HudEditor.depthOfField == null || !HudEditor.depthOfField.isToggled()) {
                drawRect(0, 0, width, height, 0x55000000);
                return;
            }

            float strength = (float) (HudEditor.blurStrength.getInput() / 100.0);
            if (strength <= 0.01f) {
                drawRect(0, 0, width, height, 0x55000000);
                return;
            }

            BlurUtils.prepareBlur();
            drawRect(0, 0, width, height, 0xFFFFFFFF);
            BlurUtils.blurEnd(BACKDROP_BLUR_PASSES, BACKDROP_BLUR_RADIUS, strength);

            drawRect(0, 0, width, height, SCRIM);
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
            int hovering = resizing == null ? element.handleAt(mouseX, mouseY) : -1;
            for (int handle = 0; handle < 8; handle++) {
                if (!element.showsHandle(handle)) continue;
                float hx = element.handleX(handle);
                float hy = element.handleY(handle);
                boolean hot = (resizing == element && resizeHandle == handle) || hovering == handle;
                float half = hot ? HANDLE_HALF + 0.75f : HANDLE_HALF;
                RoundedUtils.drawRound(hx - half - 0.75f, hy - half - 0.75f, (half + 0.75f) * 2.0f,
                        (half + 0.75f) * 2.0f, half + 0.75f, new Color(0, 0, 0, 170));
                RoundedUtils.drawRound(hx - half, hy - half, half * 2.0f, half * 2.0f, half,
                        hot ? new Color(124, 108, 255) : new Color(242, 242, 245));
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

            // The sub-toggles say which readouts the panel contains; the module itself says
            // whether any of it is on screen to be moved.
            if (ModuleManager.statsHUD != null && ModuleManager.statsHUD.isEnabled()) {
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
                elements.add(new Element("Dynamic Island") {
                    @Override
                    void render() {
                        setBounds(island.getIslandBounds());
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

            if (ModuleManager.keystrokes != null) {
                final mindless.module.impl.render.Keystrokes keystrokes = ModuleManager.keystrokes;
                elements.add(new Element("Keystrokes") {
                    @Override
                    void render() {
                        setBounds(keystrokes.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        keystrokes.moveTo(left, top);
                        setBounds(keystrokes.renderPreview());
                    }

                    @Override
                    void reset() {
                        keystrokes.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return keystrokes.scaleSetting();
                    }
                });
            }

            if (ModuleManager.watermark != null) {
                final Watermark watermark = ModuleManager.watermark;
                elements.add(new Element("Watermark") {
                    @Override
                    void render() {
                        setBounds(watermark.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        watermark.moveTo(left, top);
                        setBounds(watermark.renderPreview());
                    }

                    @Override
                    void reset() {
                        watermark.resetPosition();
                    }
                });
            }

            // Nothing to place for an overlay that is switched off. Building them anyway is
            // what put boxes on screen for things the game never draws, and piled the ones
            // that report no size into the same corner.
            java.util.Iterator<Element> iterator = elements.iterator();
            while (iterator.hasNext()) {
                Module owner = ModuleManager.getModule(iterator.next().name);
                if (owner != null && owner.canBeEnabled && !owner.isEnabled()) {
                    iterator.remove();
                }
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
            // Handles stick out past the edge, so the selected element still answers just outside it.
            if (selected != null && selected.handleAt(mouseX, mouseY) >= 0) return selected;
            return null;
        }

        private void drawOutline(Element element, int color) {
            if (!element.hasBounds()) return;
            float w = element.right - element.left;
            float h = element.bottom - element.top;
            RoundedUtils.drawRoundOutline(element.left - 1.0F, element.top - 1.0F, w + 2.0F, h + 2.0F,
                    Math.min(3.0F, Math.min(w, h) * 0.5F), 0.5F, new Color(0, 0, 0, 0),
                    new Color(color, true), 1.0F);
        }

        /**
         * Name and scale above the element, and on the selected one the few things it responds to.
         *
         * Those shortcuts used to live in a bar across the bottom of the screen for the whole
         * session, over whatever was placed there. They only matter for the element in hand, so
         * they sit with it.
         */
        private void drawLabel(Element element, boolean withHints) {
            String text = element.name;
            SliderSetting slider = element.scaleSetting();
            if (slider != null) {
                text = text + "  " + String.format("%.2fx", slider.getInput());
            }
            String hints = withHints
                    ? (slider != null ? "Scroll resizes \u00b7 " : "") + "Arrows nudge \u00b7 R resets \u00b7 Del hides"
                    : null;
            int textWidth = fontRendererObj.getStringWidth(text);
            int hintWidth = hints == null ? 0 : fontRendererObj.getStringWidth(hints);
            float chipWidth = Math.max(textWidth, hintWidth) + 10.0F;
            float chipHeight = hints == null ? 13.0F : 24.0F;
            float chipTop = element.top - chipHeight - 4.0F >= 2.0F
                    ? element.top - chipHeight - 4.0F : element.bottom + 4.0F;
            float chipLeft = Math.max(2.0F, Math.min(width - chipWidth - 2.0F, element.left - 1.0F));
            RoundedUtils.drawRound(chipLeft, chipTop, chipWidth, chipHeight, 4.0F, new Color(12, 12, 16, 215));
            fontRendererObj.drawString(text, chipLeft + 5.0F, chipTop + 3.0F, 0xFFF2F2F5, false);
            if (hints != null) {
                fontRendererObj.drawString(hints, chipLeft + 5.0F, chipTop + 14.0F, 0xFF8E8D99, false);
            }
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

            /**
             * Switch off whatever draws this, by the name the element carries.
             *
             * @return true when something was actually turned off.
             */
            boolean disableOverlay() {
                Module module = ModuleManager.getModule(name);
                if (module == null || !module.isEnabled() || !module.canBeEnabled) {
                    return false;
                }
                module.toggle();
                return true;
            }
SliderSetting scaleSetting() {
                return null;
            }

            /**
             * Keep the last real rectangle when a module draws nothing this frame.
             *
             * WAILA with nothing under the crosshair, the potion list with no potions, the
             * target panel with no target: all of them report nothing, and zeroing on that made
             * the element vanish and stop answering the mouse. Which is exactly when you want to
             * be able to put it somewhere.
             */
            void setBounds(float[] bounds) {
                if (bounds == null || bounds.length < 4
                        || bounds[2] - bounds[0] < 0.5F || bounds[3] - bounds[1] < 0.5F) {
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
                // Deeper inside than the grab sliver is the element itself, whatever its size.
                if (mouseX > left + GRAB_INSIDE && mouseX < right - GRAB_INSIDE
                        && mouseY > top + GRAB_INSIDE && mouseY < bottom - GRAB_INSIDE) {
                    return -1;
                }
                for (int handle = 0; handle < 8; handle++) {
                    if (showsHandle(handle)
                            && Math.abs(mouseX - handleX(handle)) <= GRAB_HALF
                            && Math.abs(mouseY - handleY(handle)) <= GRAB_HALF) {
                        return handle;
                    }
                }
                return -1;
            }

            boolean showsHandle(int handle) {
                boolean corner = handle == NW || handle == NE || handle == SE || handle == SW;
                return corner || (right - left >= EDGE_HANDLE_MIN_WIDTH && bottom - top >= EDGE_HANDLE_MIN_HEIGHT);
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
