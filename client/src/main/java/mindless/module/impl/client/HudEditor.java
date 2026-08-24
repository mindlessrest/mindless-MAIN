package mindless.module.impl.client;

import mindless.module.ModuleManager;
import mindless.module.impl.player.HideWindow;
import mindless.module.impl.render.HUD;
import mindless.module.impl.render.PotionHUD;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.GuiIngameState;
import mindless.utility.RenderUtils;
import mindless.utility.TextGlowUtils;
import mindless.utility.gui.MindlessButton;
import mindless.utility.media.SpotifyMiniPlayerRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class HudEditor {
    private HudEditor() {
    }

    public static final class Screen extends GuiScreen {
        /** Corner and edge grips, clockwise from the top-left. */
        private static final int NW = 0, N = 1, NE = 2, E = 3, SE = 4, S = 5, SW = 6, W = 7;

        private static final float HANDLE_HALF = 3.0f;
        private static final float GRAB_HALF = 6.0f;
        private static final float MIN_SPAN = 6.0f;

        private final List<Element> elements = new ArrayList<Element>();
        private MindlessButton doneButton;
        private MindlessButton resetButton;
        private MindlessButton resetAllButton;
        private Element hovered;
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

        @Override
        public void initGui() {
            super.initGui();
            buildElements();
            buttonList.add(doneButton = new MindlessButton(1, width - 90, height - 25, 85, 20, "Done"));
            buttonList.add(resetButton = new MindlessButton(2, 5, height - 25, 125, 20, "Reset hovered"));
            buttonList.add(resetAllButton = new MindlessButton(3, 135, height - 25, 90, 20, "Reset all"));
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawRect(0, 0, width, height, 0x9A000000);
            drawCenteredString(fontRendererObj, "Mindless HUD Editor", width / 2, 9, 0xFFFFFFFF);
            drawCenteredString(fontRendererObj,
                    "Drag to move. Pull a corner or edge to resize. Saved with your profile.",
                    width / 2, 21, 0xFFC9D1DA);

            if (elements.isEmpty()) buildElements();
            if (dragging != null) {
                dragging.moveClamped(mouseX - dragOffsetX, mouseY - dragOffsetY, width, height);
            }
            if (resizing != null) {
                applyResize(mouseX, mouseY);
            }

            for (Element element : elements) {
                element.render();
                // Clamping fights the resize anchor, which is deliberately allowed to run past
                // the edge while the grip is being dragged.
                if (element != resizing) element.ensureOnScreen(width, height);
            }
            if (resizing != null) anchorResized();

            hovered = findTopmost(mouseX, mouseY);

            for (Element element : elements) {
                boolean active = element == hovered || element == dragging || element == resizing;
                drawOutline(element, active ? 0xFFFFFFFF : 0x70FFFFFF);
                if (active) {
                    drawLabel(element);
                    drawHandles(element, mouseX, mouseY);
                }
            }

            resetButton.enabled = hovered != null;
            resetButton.displayString = hovered == null ? "Reset hovered" : "Reset " + hovered.name;
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
            if (mouseButton == 0) {
                // Grips win over the body, so grabbing a corner never starts a move instead.
                int handle = hovered != null ? hovered.handleAt(mouseX, mouseY) : -1;
                if (handle >= 0) {
                    beginResize(hovered, handle);
                }
                else {
                    Element selected = findTopmost(mouseX, mouseY);
                    if (selected != null) {
                        dragging = selected;
                        dragOffsetX = mouseX - selected.left;
                        dragOffsetY = mouseY - selected.top;
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
            }
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button == doneButton) {
                mc.displayGuiScreen(null);
            }
            else if (button == resetButton && hovered != null) {
                hovered.reset();
                hovered.render();
            }
            else if (button == resetAllButton) {
                for (Element element : elements) element.reset();
            }
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        // -------------------------------------------------------------- resizing

        /**
         * These overlays size themselves from their own contents, so there is nothing to stretch
         * independently in each axis. Every grip drives the same scale; what the grip chooses is
         * which point stays still, so the overlay grows away from wherever it is not being held.
         */
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

        // -------------------------------------------------------------- elements

        private void buildElements() {
            elements.clear();

            elements.add(new Element("ArrayList") {
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
                    Settings.resetScoreboardPosition();
                }
            });

            if (ModuleManager.spotifyMiniPlayer != null) {
                elements.add(new Element("Music Player") {
                    @Override
                    void render() {
                        setBounds(SpotifyMiniPlayerRenderer.renderPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        float elementWidth = Math.max(1.0F, right - this.left);
                        float elementHeight = Math.max(1.0F, bottom - this.top);
                        SpotifyMiniPlayer.setCustomPositionFromAbsolute(left, top, elementWidth, elementHeight,
                                new ScaledResolution(mc));
                        setBounds(SpotifyMiniPlayerRenderer.renderPreview());
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
                        // Only draggable as its own overlay. Inside the mini player it is a
                        // section of that panel and moves with it.
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

            // The bedwars overlays all share one panel, so they all drag the same way.
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

            if (ModuleManager.hideWindow != null) {
                final HideWindow hideWindow = ModuleManager.hideWindow;
                elements.add(new Element("Hide Window") {
                    @Override
                    void render() {
                        setBounds(hideWindow.renderDesignerPreview(hideWindow.getPosX(), hideWindow.getPosY()));
                    }

                    @Override
                    void moveTo(float left, float top) {
                        float centerX = left + Math.max(1.0F, right - this.left) * 0.5F;
                        float centerY = top + Math.max(1.0F, bottom - this.top) * 0.5F;
                        setBounds(hideWindow.renderDesignerPreview(centerX, centerY));
                    }

                    @Override
                    void reset() {
                        hideWindow.resetPosition();
                    }

                    @Override
                    SliderSetting scaleSetting() {
                        return hideWindow.scaleSetting();
                    }
                });
            }
        }

        private float[] renderScoreboardPreview(Float requestedX, Float requestedY) {
            ScaledResolution resolution = new ScaledResolution(mc);
            FontRenderer font = fontRendererObj;
            String title = "MINDLESS";
            String[] rows = new String[] { "Mode: Bed Wars", "Kills: 4", "Beds: 1", "Wins: 12" };
            float scale = GuiIngameState.SCOREBOARD_SCALE;
            int contentWidth = font.getStringWidth(title);
            for (String row : rows) contentWidth = Math.max(contentWidth, font.getStringWidth(row));
            float lineHeight = font.FONT_HEIGHT * scale;
            float panelWidth = contentWidth * scale + GuiIngameState.HORIZONTAL_PADDING * 2.0F;
            float panelHeight = (rows.length + 1) * lineHeight
                    + GuiIngameState.VERTICAL_PADDING * 2.0F + 2.0F;
            float defaultLeft = resolution.getScaledWidth() - 3.0F - panelWidth;
            float defaultTop = resolution.getScaledHeight() / 2.0F - panelHeight * 0.5F;

            if (requestedX != null && requestedY != null) {
                Settings.setScoreboardPosition(requestedX, requestedY, panelWidth, panelHeight, resolution);
            }
            float left = Settings.getScoreboardX(panelWidth, resolution, defaultLeft);
            float top = Settings.getScoreboardY(panelHeight, resolution, defaultTop);
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
            if (Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled()) {
                TextGlowUtils.drawGlow(font, title, titleX, titleY, 0xFFFFFFFF);
            }
            font.drawString(title, titleX, titleY, 0xFFFFFFFF);
            for (int i = 0; i < rows.length; i++) {
                int y = Math.round((bottom - GuiIngameState.VERTICAL_PADDING
                        - (i + 1) * lineHeight) / scale);
                int rowX = Math.round((left + GuiIngameState.HORIZONTAL_PADDING) / scale);
                if (Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled()) {
                    TextGlowUtils.drawGlow(font, rows[i], rowX, y, 0xFFFFFFFF);
                }
                font.drawString(rows[i], rowX, y, 0xFFFFFFFF);
            }
            GlStateManager.popMatrix();
            return new float[] { left, top, right, bottom };
        }

        /**
         * Body hits win over grips, so a grip belonging to one overlay can never take priority
         * over another overlay lying directly under the cursor.
         */
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

            /** The slider a resize drives, or null when the overlay has no scale of its own. */
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
                float margin = 2.0F;
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
                float margin = 2.0F;
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
