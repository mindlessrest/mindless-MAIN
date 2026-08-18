package mindless.module.impl.client;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.minigames.BridgeInfo;
import mindless.module.impl.player.HideWindow;
import mindless.module.impl.render.HUD;
import mindless.module.impl.render.PotionHUD;
import mindless.module.impl.render.TargetHUD;
import mindless.runtime.GuiIngameState;
import mindless.utility.RenderUtils;
import mindless.utility.TextGlowUtils;
import mindless.utility.media.SpotifyMiniPlayerRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.config.GuiButtonExt;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class HudEditor {
    private HudEditor() {
    }

    public static final class Screen extends GuiScreen {
        private final List<Element> elements = new ArrayList<Element>();
        private GuiButtonExt doneButton;
        private GuiButtonExt resetButton;
        private GuiButtonExt resetAllButton;
        private Element hovered;
        private Element dragging;
        private float dragOffsetX;
        private float dragOffsetY;

        @Override
        public void initGui() {
            super.initGui();
            buildElements();
            buttonList.add(doneButton = new GuiButtonExt(1, width - 90, height - 25, 85, 20, "Done"));
            buttonList.add(resetButton = new GuiButtonExt(2, 5, height - 25, 125, 20, "Reset hovered"));
            buttonList.add(resetAllButton = new GuiButtonExt(3, 135, height - 25, 90, 20, "Reset all"));
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawRect(0, 0, width, height, 0x9A000000);
            drawCenteredString(fontRendererObj, "Mindless HUD Editor", width / 2, 9, 0xFFFFFFFF);
            drawCenteredString(fontRendererObj,
                    "Drag an overlay to move it. Positions are saved with your profile.",
                    width / 2, 21, 0xFFC9D1DA);

            if (elements.isEmpty()) buildElements();
            if (dragging != null) {
                dragging.moveClamped(mouseX - dragOffsetX, mouseY - dragOffsetY, width, height);
            }

            for (Element element : elements) {
                element.render();
                element.ensureOnScreen(width, height);
            }
            hovered = findTopmost(mouseX, mouseY);

            for (Element element : elements) {
                boolean active = element == hovered || element == dragging;
                drawOutline(element, active ? 0xFFFFFFFF : 0x70FFFFFF);
                if (active) drawLabel(element);
            }

            resetButton.enabled = hovered != null;
            resetButton.displayString = hovered == null ? "Reset hovered" : "Reset " + hovered.name;
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
            if (mouseButton == 0) {
                Element selected = findTopmost(mouseX, mouseY);
                if (selected != null) {
                    dragging = selected;
                    dragOffsetX = mouseX - selected.left;
                    dragOffsetY = mouseY - selected.top;
                }
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) dragging = null;
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
                });
            }

            Module bridgeModule = ModuleManager.getModule("Bridge Info");
            if (bridgeModule instanceof BridgeInfo) {
                final BridgeInfo bridge = (BridgeInfo) bridgeModule;
                elements.add(new Element("Bridge Info") {
                    @Override
                    void render() {
                        setBounds(bridge.renderDesignerPreview());
                    }

                    @Override
                    void moveTo(float left, float top) {
                        bridge.setHudPosition(Math.round(left), Math.round(top));
                        setBounds(bridge.renderDesignerPreview());
                    }

                    @Override
                    void reset() {
                        bridge.resetPosition();
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

            BlurUtils.prepareBlur();
            RoundedUtils.drawRound(left, top, panelWidth, panelHeight,
                    GuiIngameState.PANEL_RADIUS, 0xFF000000);
            BlurUtils.blurEndRegion(1, 1.4F, GuiIngameState.PANEL_BLUR_OPACITY,
                    left - 2.0F, top - 2.0F, panelWidth + 4.0F, panelHeight + 4.0F);
            RoundedUtils.drawRoundShadow(left, top, panelWidth, panelHeight,
                    GuiIngameState.PANEL_RADIUS, GuiIngameState.PANEL_SHADOW_SOFTNESS,
                    GuiIngameState.PANEL_SHADOW_COLOR);
            RoundedUtils.drawLiquidGlass(left, top, panelWidth, panelHeight,
                    GuiIngameState.PANEL_RADIUS, GuiIngameState.PANEL_FILL_COLOR);

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

        private Element findTopmost(float mouseX, float mouseY) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                Element element = elements.get(i);
                if (element.contains(mouseX, mouseY)) return element;
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
            int textWidth = fontRendererObj.getStringWidth(element.name);
            float labelTop = element.top >= 48.0F ? element.top - 14.0F : element.bottom + 4.0F;
            RoundedUtils.drawRound(element.left - 3.0F, labelTop - 2.0F,
                    textWidth + 6.0F, 12.0F, 4.0F, new Color(0, 0, 0, 190));
            fontRendererObj.drawString(element.name, element.left, labelTop, 0xFFFFFFFF, true);
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

