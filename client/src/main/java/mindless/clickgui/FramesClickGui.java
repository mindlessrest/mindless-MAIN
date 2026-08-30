package mindless.clickgui;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.impl.client.Gui;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FramesClickGui extends ClickGui {

    private static final float FRAME_WIDTH = 150f;
    private static final float HEADER_HEIGHT = 26f;
    private static final float MODULE_HEIGHT = 18f;
    private static final float MODULE_PAD = 3f;
    private static final float FRAME_PAD = 8f;
    private static final float FRAME_RADIUS = 8f;
    private static final float TAB_BAR_HEIGHT = 28f;
    private static final float SEARCH_BAR_HEIGHT = 28f;
    private static final float SEARCH_BAR_WIDTH = 220f;

    private static final int TAB_MODULES = 0;
    private static final int TAB_THEME = 1;

    private static final Module.category[] FRAME_CATEGORIES = {
            Module.category.combat, Module.category.movement, Module.category.player,
            Module.category.world, Module.category.render, Module.category.bedwars,
            Module.category.other, Module.category.client
    };

    private final Map<Module.category, float[]> framePositions = new HashMap<>();
    private final Map<Module, Boolean> expandedModules = new HashMap<>();

    private int activeTab = TAB_MODULES;
    private String searchQuery = "";
    private boolean searchFocused = false;

    private Module.category draggingFrame = null;
    private float dragOffsetX, dragOffsetY;

    private ResourceLocation mascotTexture;
    private boolean texturesLoaded;

    public FramesClickGui() {
        super();
        initFramePositions();
    }

    private void initFramePositions() {
        int col = 0;
        float startX = 30f;
        float startY = TAB_BAR_HEIGHT + 20f;
        float gap = 16f;
        for (Module.category cat : FRAME_CATEGORIES) {
            float x = startX + col * (FRAME_WIDTH + gap);
            framePositions.put(cat, new float[]{x, startY});
            col++;
            if (col >= 5) {
                col = 0;
                startY += 300f;
            }
        }
    }

    @Override
    public void initGui() {
        super.initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ensureTextures();
        float scale = Gui.getClickGuiScale();

        int mx = (int) (mouseX / scale);
        int my = (int) (mouseY / scale);

        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1f);

        drawBackground();
        drawTabBar(mx, my);

        if (activeTab == TAB_MODULES) {
            drawFrames(mx, my);
            drawSearchBar(mx, my);
            drawMascot();
        } else {
            drawThemePanel(mx, my);
        }

        GlStateManager.popMatrix();
    }

    private void drawBackground() {
        ScaledResolution sr = new ScaledResolution(mc);
        float w = sr.getScaledWidth() / Gui.getClickGuiScale();
        float h = sr.getScaledHeight() / Gui.getClickGuiScale();

        if (Gui.darkBackground != null && Gui.darkBackground.isToggled()) {
            drawRect(0, 0, (int) w, (int) h, new Color(0, 0, 0, 140).getRGB());
        }

        if (Gui.backgroundBlur != null && Gui.backgroundBlur.getInput() > 0) {
            BlurUtils.prepareBlur(0, 0, w, h);
            drawRect(0, 0, (int) w, (int) h, 0xFF000000);
            BlurUtils.blurEndRegion(2, (float) (Gui.backgroundBlur.getInput() / 100.0 * 4.0), 0.85f, 0, 0, w, h);
        }
    }

    private void drawTabBar(int mx, int my) {
        ScaledResolution sr = new ScaledResolution(mc);
        float screenW = sr.getScaledWidth() / Gui.getClickGuiScale();

        MindlessFontRenderer font = Gui.getClickGuiHeaderFontRenderer();
        if (font == null) return;

        int accent = getAccentColor();
        int textCol = getTextColor();

        RoundedUtils.drawRound(0, 0, screenW, TAB_BAR_HEIGHT, 0, new Color(10, 12, 14, 220));

        String[] tabs = {"Modules", "Theme"};
        float tabW = 80f;
        float totalW = tabs.length * tabW;
        float startX = (screenW - totalW) / 2f;

        for (int i = 0; i < tabs.length; i++) {
            float tx = startX + i * tabW;
            float textW = font.getStringWidth(tabs[i]);
            float textX = tx + (tabW - textW) / 2f;
            float textY = (TAB_BAR_HEIGHT - font.getFontHeight()) / 2f;

            int color = (i == activeTab) ? accent : textCol;
            font.drawString(tabs[i], textX, textY, color, false);

            if (i == activeTab) {
                RoundedUtils.drawRound(textX, TAB_BAR_HEIGHT - 3f, textW, 2f, 1f, new Color(accent));
            }
        }
    }

    private void drawFrames(int mx, int my) {
        MindlessFontRenderer headerFont = Gui.getClickGuiHeaderFontRenderer();
        MindlessFontRenderer moduleFont = Gui.getClickGuiSettingFontRenderer();
        if (headerFont == null || moduleFont == null) return;

        int accent = getAccentColor();
        int textCol = getTextColor();
        int disabledCol = Gui.disabledColor != null ? Gui.disabledColor.getColor() : new Color(160, 160, 160).getRGB();

        for (Module.category cat : FRAME_CATEGORIES) {
            float[] pos = framePositions.get(cat);
            if (pos == null) continue;

            List<Module> modules = getModulesForCategory(cat);
            if (!searchQuery.isEmpty()) {
                modules = filterModules(modules);
                if (modules.isEmpty()) continue;
            }

            float frameH = HEADER_HEIGHT + FRAME_PAD;
            for (Module mod : modules) {
                frameH += MODULE_HEIGHT + MODULE_PAD;
                if (Boolean.TRUE.equals(expandedModules.get(mod))) {
                    frameH += getSettingsHeight(mod);
                }
            }
            frameH += FRAME_PAD;

            float fx = pos[0];
            float fy = pos[1];

            BlurUtils.prepareBlur(fx, fy, FRAME_WIDTH, frameH);
            RoundedUtils.drawRound(fx, fy, FRAME_WIDTH, frameH, FRAME_RADIUS, new Color(0, 0, 0, 200));
            BlurUtils.blurEndRegion(2, 2f, 0.85f, fx - 2, fy - 2, FRAME_WIDTH + 4, frameH + 4);
            RoundedUtils.drawRound(fx, fy, FRAME_WIDTH, frameH, FRAME_RADIUS, new Color(15, 17, 20, 210));
            RoundedUtils.drawRoundOutline(fx, fy, FRAME_WIDTH, frameH, FRAME_RADIUS, 1f,
                    new Color(0, 0, 0, 0), new Color(255, 255, 255, 25));

            String catName = capitalize(cat.name());
            headerFont.drawString(catName, fx + FRAME_PAD, fy + (HEADER_HEIGHT - headerFont.getFontHeight()) / 2f, accent, false);

            RoundedUtils.drawRound(fx + FRAME_PAD, fy + HEADER_HEIGHT - 1, FRAME_WIDTH - FRAME_PAD * 2, 1f, 0.5f, new Color(255, 255, 255, 30));

            float cy = fy + HEADER_HEIGHT + FRAME_PAD;
            for (Module mod : modules) {
                boolean hovered = mx >= fx && mx <= fx + FRAME_WIDTH && my >= cy && my < cy + MODULE_HEIGHT;

                if (hovered) {
                    RoundedUtils.drawRound(fx + 4, cy, FRAME_WIDTH - 8, MODULE_HEIGHT, 4f, new Color(255, 255, 255, 20));
                }

                int modColor = mod.isEnabled() ? accent : disabledCol;
                moduleFont.drawString(mod.getName(), fx + FRAME_PAD + 4, cy + (MODULE_HEIGHT - moduleFont.getFontHeight()) / 2f, modColor, false);
                cy += MODULE_HEIGHT + MODULE_PAD;

                if (Boolean.TRUE.equals(expandedModules.get(mod))) {
                    cy = drawModuleSettings(mod, fx, cy, moduleFont, accent, textCol);
                }
            }
        }
    }

    private float drawModuleSettings(Module mod, float fx, float startY, MindlessFontRenderer font, int accent, int textCol) {
        float cy = startY;
        float indent = FRAME_PAD + 10f;

        for (Setting setting : mod.getSettings()) {
            if (!setting.visible) continue;

            if (setting instanceof ButtonSetting) {
                ButtonSetting btn = (ButtonSetting) setting;
                int color = btn.isToggled() ? accent : new Color(120, 120, 120).getRGB();
                String indicator = btn.isToggled() ? "• " : "  ";
                font.drawString(indicator + setting.name, fx + indent, cy + 2, color, false);
                cy += MODULE_HEIGHT;
            } else if (setting instanceof SliderSetting) {
                SliderSetting slider = (SliderSetting) setting;
                String[] opts = slider.getOptions();
                String val;
                if (opts != null && opts.length > 0) {
                    val = opts[Math.max(0, Math.min(opts.length - 1, (int) slider.getInput()))];
                } else {
                    val = String.format("%.1f", slider.getInput());
                }
                font.drawString(setting.name + ": " + val, fx + indent, cy + 2, textCol, false);
                cy += MODULE_HEIGHT;
            }
        }
        return cy;
    }

    private float getSettingsHeight(Module mod) {
        float h = 0;
        for (Setting setting : mod.getSettings()) {
            if (!setting.visible) continue;
            if (setting instanceof ButtonSetting || setting instanceof SliderSetting) {
                h += MODULE_HEIGHT;
            }
        }
        return h;
    }

    private void drawSearchBar(int mx, int my) {
        ScaledResolution sr = new ScaledResolution(mc);
        float screenW = sr.getScaledWidth() / Gui.getClickGuiScale();
        float screenH = sr.getScaledHeight() / Gui.getClickGuiScale();

        MindlessFontRenderer font = Gui.getClickGuiSettingFontRenderer();
        if (font == null) return;

        float barX = (screenW - SEARCH_BAR_WIDTH) / 2f;
        float barY = screenH - SEARCH_BAR_HEIGHT - 20f;

        int accent = getAccentColor();
        Color borderColor = searchFocused ? new Color(accent) : new Color(255, 255, 255, 40);

        RoundedUtils.drawRound(barX, barY, SEARCH_BAR_WIDTH, SEARCH_BAR_HEIGHT, 6f, new Color(15, 17, 20, 220));
        RoundedUtils.drawRoundOutline(barX, barY, SEARCH_BAR_WIDTH, SEARCH_BAR_HEIGHT, 6f, 1f,
                new Color(0, 0, 0, 0), borderColor);

        String displayText = searchQuery.isEmpty() ? "Search modules..." : searchQuery;
        int textColor = searchQuery.isEmpty() ? new Color(120, 120, 120).getRGB() : 0xFFFFFFFF;
        font.drawString(displayText, barX + 10, barY + (SEARCH_BAR_HEIGHT - font.getFontHeight()) / 2f, textColor, false);
    }

    private void drawMascot() {
        if (Gui.mascot == null || (int) Gui.mascot.getInput() != 0) return;
        if (mascotTexture == null) return;

        ScaledResolution sr = new ScaledResolution(mc);
        float screenW = sr.getScaledWidth() / Gui.getClickGuiScale();
        float screenH = sr.getScaledHeight() / Gui.getClickGuiScale();
        float mascotH = screenH * 0.35f;
        float mascotW = mascotH;
        float mxPos = screenW - mascotW - 20f;
        float myPos = screenH - mascotH - 20f;

        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, 0.8f);
        mc.getTextureManager().bindTexture(mascotTexture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(mxPos, myPos);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(mxPos, myPos + mascotH);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(mxPos + mascotW, myPos + mascotH);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(mxPos + mascotW, myPos);
        GL11.glEnd();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }

    private void drawThemePanel(int mx, int my) {
        ScaledResolution sr = new ScaledResolution(mc);
        float screenW = sr.getScaledWidth() / Gui.getClickGuiScale();
        float screenH = sr.getScaledHeight() / Gui.getClickGuiScale();

        MindlessFontRenderer font = Gui.getClickGuiSettingFontRenderer();
        if (font == null) return;

        float panelW = 300f;
        float panelH = 200f;
        float px = (screenW - panelW) / 2f;
        float py = (screenH - panelH) / 2f;

        RoundedUtils.drawRound(px, py, panelW, panelH, FRAME_RADIUS, new Color(15, 17, 20, 220));
        RoundedUtils.drawRoundOutline(px, py, panelW, panelH, FRAME_RADIUS, 1f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 25));

        Module themeModule = null;
        if (Mindless.getModuleManager() != null) {
            List<Module> themes = Mindless.getModuleManager().inCategory(Module.category.theme);
            if (!themes.isEmpty()) themeModule = themes.get(0);
        }

        float cy = py + 12f;
        int accent = getAccentColor();
        font.drawString("Theme Settings", px + 12f, cy, accent, false);
        cy += 24f;

        if (themeModule != null) {
            for (Setting setting : themeModule.getSettings()) {
                if (!setting.visible) continue;
                if (setting instanceof ButtonSetting) {
                    ButtonSetting btn = (ButtonSetting) setting;
                    int color = btn.isToggled() ? accent : new Color(120, 120, 120).getRGB();
                    font.drawString((btn.isToggled() ? "• " : "  ") + setting.name, px + 16f, cy, color, false);
                    cy += MODULE_HEIGHT;
                } else if (setting instanceof SliderSetting) {
                    SliderSetting slider = (SliderSetting) setting;
                    font.drawString(setting.name + ": " + String.format("%.1f", slider.getInput()), px + 16f, cy, 0xFFFFFFFF, false);
                    cy += MODULE_HEIGHT;
                }
                if (cy > py + panelH - 12f) break;
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        float scale = Gui.getClickGuiScale();
        int mx = (int) (mouseX / scale);
        int my = (int) (mouseY / scale);

        // Tab bar
        if (my < TAB_BAR_HEIGHT) {
            ScaledResolution sr = new ScaledResolution(mc);
            float screenW = sr.getScaledWidth() / scale;
            float tabW = 80f;
            float totalW = 2 * tabW;
            float startX = (screenW - totalW) / 2f;
            if (mx >= startX && mx < startX + totalW) {
                activeTab = (int) ((mx - startX) / tabW);
                return;
            }
        }

        if (activeTab != TAB_MODULES) return;

        // Search bar
        ScaledResolution sr = new ScaledResolution(mc);
        float screenW = sr.getScaledWidth() / scale;
        float screenH = sr.getScaledHeight() / scale;
        float barX = (screenW - SEARCH_BAR_WIDTH) / 2f;
        float barY = screenH - SEARCH_BAR_HEIGHT - 20f;
        searchFocused = mx >= barX && mx <= barX + SEARCH_BAR_WIDTH && my >= barY && my <= barY + SEARCH_BAR_HEIGHT;

        // Frame interactions
        for (Module.category cat : FRAME_CATEGORIES) {
            float[] pos = framePositions.get(cat);
            if (pos == null) continue;

            List<Module> modules = getModulesForCategory(cat);
            if (!searchQuery.isEmpty()) modules = filterModules(modules);
            if (modules.isEmpty()) continue;

            float fx = pos[0];
            float fy = pos[1];

            // Header drag
            if (mx >= fx && mx <= fx + FRAME_WIDTH && my >= fy && my < fy + HEADER_HEIGHT) {
                draggingFrame = cat;
                dragOffsetX = mx - fx;
                dragOffsetY = my - fy;
                return;
            }

            // Module clicks
            float cy = fy + HEADER_HEIGHT + FRAME_PAD;
            for (Module mod : modules) {
                if (mx >= fx && mx <= fx + FRAME_WIDTH && my >= cy && my < cy + MODULE_HEIGHT) {
                    if (mouseButton == 0) {
                        mod.toggle();
                    } else if (mouseButton == 1) {
                        Boolean expanded = expandedModules.getOrDefault(mod, false);
                        expandedModules.put(mod, !expanded);
                    }
                    return;
                }
                cy += MODULE_HEIGHT + MODULE_PAD;
                if (Boolean.TRUE.equals(expandedModules.get(mod))) {
                    float settingsH = getSettingsHeight(mod);
                    if (mx >= fx && mx <= fx + FRAME_WIDTH && my >= cy && my < cy + settingsH) {
                        handleSettingClick(mod, fx, cy, mx, my);
                        return;
                    }
                    cy += settingsH;
                }
            }
        }
    }

    private void handleSettingClick(Module mod, float fx, float startY, int mx, int my) {
        float cy = startY;
        for (Setting setting : mod.getSettings()) {
            if (!setting.visible) continue;
            if (setting instanceof ButtonSetting) {
                if (my >= cy && my < cy + MODULE_HEIGHT) {
                    ((ButtonSetting) setting).toggle();
                    return;
                }
                cy += MODULE_HEIGHT;
            } else if (setting instanceof SliderSetting) {
                cy += MODULE_HEIGHT;
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        draggingFrame = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (draggingFrame != null) {
            float scale = Gui.getClickGuiScale();
            int mx = (int) (mouseX / scale);
            int my = (int) (mouseY / scale);
            float[] pos = framePositions.get(draggingFrame);
            if (pos != null) {
                pos[0] = mx - dragOffsetX;
                pos[1] = my - dragOffsetY;
            }
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }

        if (searchFocused) {
            if (keyCode == Keyboard.KEY_BACK) {
                if (!searchQuery.isEmpty()) searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
            } else if (typedChar >= 32 && typedChar < 127) {
                searchQuery += typedChar;
            }
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    // ------------------------------------------------------------------ util

    private List<Module> getModulesForCategory(Module.category category) {
        if (Mindless.getModuleManager() == null) return new ArrayList<>();
        return Mindless.getModuleManager().inCategory(category);
    }

    private List<Module> filterModules(List<Module> modules) {
        String q = searchQuery.toLowerCase(Locale.ROOT);
        List<Module> result = new ArrayList<>();
        for (Module mod : modules) {
            if (mod.getName().toLowerCase(Locale.ROOT).contains(q)) result.add(mod);
        }
        return result;
    }

    private int getAccentColor() {
        if (Gui.themeColor != null) return Gui.themeColor.getColor();
        return new Color(159, 143, 210).getRGB();
    }

    private int getTextColor() {
        if (Gui.themeTextColor != null) return Gui.themeTextColor.getColor();
        return new Color(235, 234, 230).getRGB();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void ensureTextures() {
        if (texturesLoaded) return;
        texturesLoaded = true;
        try (InputStream stream = FramesClickGui.class.getResourceAsStream("/assets/mindless/textures/gui/mascot_0.png")) {
            if (stream == null) return;
            BufferedImage image = ImageIO.read(stream);
            if (image == null) return;
            DynamicTexture texture = new DynamicTexture(image);
            texture.setBlurMipmap(true, false);
            mascotTexture = mc.getTextureManager().getDynamicTextureLocation("mindless_frames_mascot", texture);
        } catch (Exception ignored) {}
    }
}
