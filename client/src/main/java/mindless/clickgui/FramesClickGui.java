/*package mindless.clickgui;

import mindless.Raven;
import mindless.module.Module;
import mindless.module.impl.client.Gui;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FramesClickGui extends ClickGui {

    private static final Module.category[] FRAME_CATEGORIES = {
            Module.category.combat, Module.category.movement, Module.category.player,
            Module.category.world, Module.category.render, Module.category.bedwars,
            Module.category.other, Module.category.client
    };

    private static final int TAB_MODULES = 0;
    private static final int TAB_THEME = 1;
    private static final String[] TAB_NAMES = {"Modules", "Theme"};

    private static final float FRAME_WIDTH = 140f;
    private static final float HEADER_HEIGHT = 26f;
    private static final float MODULE_ROW_HEIGHT = 18f;
    private static final float FRAME_PAD = 8f;
    private static final float FRAME_RADIUS = 8f;
    private static final float TOP_BAR_HEIGHT = 28f;
    private static final float SEARCH_BAR_HEIGHT = 28f;
    private static final float SETTING_ROW_HEIGHT = 16f;

    private final Map<Module.category, Frame> frames = new LinkedHashMap<>();
    private int activeTab = TAB_MODULES;
    private String search = "";
    private boolean searchFocused;
    private Module expandedModule;
    private Frame draggingFrame;
    private float dragOffsetX, dragOffsetY;
    private ResourceLocation mascotTexture;
    private boolean textureLoaded;

    public FramesClickGui() {
        super();
        initFramePositions();
    }

    private void initFramePositions() {
        int col = 0;
        float startX = 30f;
        float startY = TOP_BAR_HEIGHT + 20f;
        float gap = 16f;
        for (Module.category cat : FRAME_CATEGORIES) {
            Frame f = new Frame();
            f.category = cat;
            f.x = startX + col * (FRAME_WIDTH + gap);
            f.y = startY;
            frames.put(cat, f);
            col++;
            if (f.x + FRAME_WIDTH * 2 > 1200) {
                col = 0;
                startY += 300f;
            }
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float scale = Gui.getClickGuiScale();
        int mx = (int) (mouseX / scale);
        int my = (int) (mouseY / scale);

        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1f);

        drawBackground();
        drawTopBar(mx, my);

        if (activeTab == TAB_MODULES) {
            drawFrames(mx, my);
            drawSearchBar(mx, my);
        } else {
            drawThemePanel(mx, my);
        }

        drawMascot();

        GlStateManager.popMatrix();
    }

    private void drawBackground() {
        float sw = width / Gui.getClickGuiScale();
        float sh = height / Gui.getClickGuiScale();
        if (Gui.darkBackground != null && Gui.darkBackground.isToggled()) {
            drawRect(0, 0, (int) sw, (int) sh, new Color(0, 0, 0, 120).getRGB());
        }
        if (Gui.backgroundBlur != null && (int) Gui.backgroundBlur.getInput() > 0) {
            float pct = (float) Gui.backgroundBlur.getInput() / 100f;
            BlurUtils.prepareBlur();
            BlurUtils.blurEndRegion(2, 2f * pct, 0.85f, 0, 0, sw, sh);
            GL20.glUseProgram(0);
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
        }
    }

    private void drawTopBar(int mx, int my) {
        float sw = width / Gui.getClickGuiScale();
        int barColor = new Color(10, 12, 14, 220).getRGB();
        RoundedUtils.drawRound(0, 0, sw, TOP_BAR_HEIGHT, 0f, barColor);

        RavenFontRenderer font = getHeaderFont();
        if (font == null) return;

        float tabW = 80f;
        float tabStartX = (sw - tabW * TAB_NAMES.length) / 2f;
        int accent = getAccentColor();

        for (int i = 0; i < TAB_NAMES.length; i++) {
            float tx = tabStartX + i * tabW;
            float textX = tx + (tabW - font.getStringWidth(TAB_NAMES[i])) / 2f;
            float textY = (TOP_BAR_HEIGHT - font.getFontHeight()) / 2f;
            int color = (i == activeTab) ? accent : new Color(200, 200, 200).getRGB();
            font.drawString(TAB_NAMES[i], textX, textY, color, false);
            if (i == activeTab) {
                RoundedUtils.drawRound(tx + 10, TOP_BAR_HEIGHT - 2.5f, tabW - 20, 2f, 1f, new Color(accent, true));
            }
        }
    }

    private void drawFrames(int mx, int my) {
        RavenFontRenderer headerFont = getHeaderFont();
        RavenFontRenderer bodyFont = getBodyFont();
        if (headerFont == null || bodyFont == null) return;

        int accent = getAccentColor();
        float radius = FRAME_RADIUS * ThemeManager.roundingScale();
        String searchLower = search.toLowerCase(Locale.ROOT);

        for (Frame frame : frames.values()) {
            List<Module> modules = getModulesForCategory(frame.category);
            if (!searchLower.isEmpty()) {
                List<Module> filtered = new ArrayList<>();
                for (Module m : modules) {
                    if (m.getName().toLowerCase(Locale.ROOT).contains(searchLower)) filtered.add(m);
                }
                if (filtered.isEmpty()) continue;
                modules = filtered;
            }

            float frameH = HEADER_HEIGHT + modules.size() * MODULE_ROW_HEIGHT + FRAME_PAD;
            if (expandedModule != null && modules.contains(expandedModule)) {
                frameH += getSettingsHeight(expandedModule);
            }

            // Panel background
            BlurUtils.prepareBlur(frame.x, frame.y, FRAME_WIDTH, frameH);
            RoundedUtils.drawRound(frame.x, frame.y, FRAME_WIDTH, frameH, radius, 0xFF000000);
            BlurUtils.blurEndRegion(2, 2.2f, 0.8f, frame.x - 2, frame.y - 2, FRAME_WIDTH + 4, frameH + 4);
            GL20.glUseProgram(0);
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);
            RoundedUtils.drawRound(frame.x, frame.y, FRAME_WIDTH, frameH, radius, new Color(15, 17, 20, 200));
            RoundedUtils.drawRoundOutline(frame.x, frame.y, FRAME_WIDTH, frameH, radius, 1f,
                    new Color(0, 0, 0, 0), new Color(255, 255, 255, 20));

            // Header
            String catName = capitalize(frame.category.name());
            headerFont.drawString(catName, frame.x + FRAME_PAD, frame.y + (HEADER_HEIGHT - headerFont.getFontHeight()) / 2f, accent, false);

            // Modules
            float rowY = frame.y + HEADER_HEIGHT;
            for (Module mod : modules) {
                boolean hovered = mx >= frame.x && mx <= frame.x + FRAME_WIDTH && my >= rowY && my < rowY + MODULE_ROW_HEIGHT;
                if (hovered) {
                    RoundedUtils.drawRound(frame.x + 3, rowY, FRAME_WIDTH - 6, MODULE_ROW_HEIGHT, 4f, new Color(255, 255, 255, 20));
                }
                int textColor = mod.isEnabled() ? accent : new Color(200, 200, 200).getRGB();
                bodyFont.drawString(mod.getName(), frame.x + FRAME_PAD + 4, rowY + (MODULE_ROW_HEIGHT - bodyFont.getFontHeight()) / 2f, textColor, false);
                rowY += MODULE_ROW_HEIGHT;

                // Expanded settings
                if (mod == expandedModule) {
                    rowY = drawModuleSettings(mod, frame.x, rowY, bodyFont);
                }
            }

            frame.lastRenderedHeight = frameH;
        }
    }

    private float drawModuleSettings(Module mod, float frameX, float startY, RavenFontRenderer font) {
        float y = startY;
        int accent = getAccentColor();
        float padLeft = frameX + FRAME_PAD + 10f;

        for (Setting setting : mod.getSettings()) {
            if (setting instanceof DescriptionSetting) continue;

            if (setting instanceof ButtonSetting) {
                ButtonSetting btn = (ButtonSetting) setting;
                int col = btn.isToggled() ? accent : new Color(130, 130, 130).getRGB();
                font.drawString(setting.name, padLeft, y + 2f, col, false);
                y += SETTING_ROW_HEIGHT;
            } else if (setting instanceof SliderSetting) {
                SliderSetting slider = (SliderSetting) setting;
                String[] opts = slider.getOptions();
                String val;
                if (opts != null && opts.length > 0) {
                    int idx = (int) Math.max(0, Math.min(opts.length - 1, slider.getInput()));
                    val = opts[idx];
                } else {
                    val = String.format("%.1f", slider.getInput());
                }
                font.drawString(setting.name + ": " + val, padLeft, y + 2f, new Color(170, 170, 170).getRGB(), false);
                y += SETTING_ROW_HEIGHT;
            }
        }
        return y;
    }

    private float getSettingsHeight(Module mod) {
        float h = 0;
        for (Setting setting : mod.getSettings()) {
            if (setting instanceof DescriptionSetting) continue;
            if (setting instanceof ButtonSetting || setting instanceof SliderSetting) {
                h += SETTING_ROW_HEIGHT;
            }
        }
        return h;
    }

    private void drawSearchBar(int mx, int my) {
        float sw = width / Gui.getClickGuiScale();
        float sh = height / Gui.getClickGuiScale();
        float barW = 260f;
        float barX = (sw - barW) / 2f;
        float barY = sh - SEARCH_BAR_HEIGHT - 16f;

        RoundedUtils.drawRound(barX, barY, barW, SEARCH_BAR_HEIGHT, 6f * ThemeManager.roundingScale(), new Color(15, 17, 20, 220));
        RoundedUtils.drawRoundOutline(barX, barY, barW, SEARCH_BAR_HEIGHT, 6f * ThemeManager.roundingScale(), 1f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, searchFocused ? 40 : 15));

        RavenFontRenderer font = getBodyFont();
        if (font == null) return;

        float textY = barY + (SEARCH_BAR_HEIGHT - font.getFontHeight()) / 2f;
        if (search.isEmpty() && !searchFocused) {
            font.drawString("Search...", barX + 10f, textY, new Color(100, 100, 100).getRGB(), false);
        } else {
            font.drawString(search + (searchFocused ? "_" : ""), barX + 10f, textY, new Color(210, 210, 210).getRGB(), false);
        }
    }

    private void drawThemePanel(int mx, int my) {
        float sw = width / Gui.getClickGuiScale();
        float sh = height / Gui.getClickGuiScale();
        float panelW = 300f;
        float panelH = 200f;
        float px = (sw - panelW) / 2f;
        float py = (sh - panelH) / 2f;

        RoundedUtils.drawRound(px, py, panelW, panelH, 8f * ThemeManager.roundingScale(), new Color(15, 17, 20, 220));
        RavenFontRenderer font = getBodyFont();
        if (font == null) return;

        float y = py + 14f;
        font.drawString("Theme settings are available in the Central GUI style.", px + 14f, y, new Color(170, 170, 170).getRGB(), false);
    }

    private void drawMascot() {
        if (Gui.mascot == null || (int) Gui.mascot.getInput() != 0) return;
        ensureMascotTexture();
        if (mascotTexture == null) return;

        float sw = width / Gui.getClickGuiScale();
        float sh = height / Gui.getClickGuiScale();
        float size = 120f;
        float mx = sw - size - 30f;
        float my = sh - size - 50f;

        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(mascotTexture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(mx, my);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(mx, my + size);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(mx + size, my + size);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(mx + size, my);
        GL11.glEnd();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void ensureMascotTexture() {
        if (textureLoaded) return;
        textureLoaded = true;
        try (InputStream stream = FramesClickGui.class.getResourceAsStream("/assets/mindless/textures/gui/mascot_0.png")) {
            if (stream == null) return;
            BufferedImage image = ImageIO.read(stream);
            if (image == null) return;
            DynamicTexture tex = new DynamicTexture(image);
            tex.setBlurMipmap(true, false);
            mascotTexture = mc.getTextureManager().getDynamicTextureLocation("mindless_frames_mascot", tex);
        } catch (Exception ignored) {}
    }

    // ---- Input handling ----

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        float scale = Gui.getClickGuiScale();
        int mx = (int) (mouseX / scale);
        int my = (int) (mouseY / scale);

        // Top bar tabs
        if (my < TOP_BAR_HEIGHT) {
            float sw = width / scale;
            float tabW = 80f;
            float tabStartX = (sw - tabW * TAB_NAMES.length) / 2f;
            for (int i = 0; i < TAB_NAMES.length; i++) {
                float tx = tabStartX + i * tabW;
                if (mx >= tx && mx <= tx + tabW) {
                    activeTab = i;
                    return;
                }
            }
        }

        if (activeTab != TAB_MODULES) return;

        // Search bar click
        float sw = width / scale;
        float sh = height / scale;
        float barW = 260f;
        float barX = (sw - barW) / 2f;
        float barY = sh - SEARCH_BAR_HEIGHT - 16f;
        searchFocused = mx >= barX && mx <= barX + barW && my >= barY && my <= barY + SEARCH_BAR_HEIGHT;

        // Frame interactions
        String searchLower = search.toLowerCase(Locale.ROOT);
        for (Frame frame : frames.values()) {
            List<Module> modules = getFilteredModules(frame.category, searchLower);
            if (modules.isEmpty()) continue;

            // Header drag
            if (mx >= frame.x && mx <= frame.x + FRAME_WIDTH && my >= frame.y && my < frame.y + HEADER_HEIGHT) {
                if (mouseButton == 0) {
                    draggingFrame = frame;
                    dragOffsetX = mx - frame.x;
                    dragOffsetY = my - frame.y;
                }
                return;
            }

            // Module click
            float rowY = frame.y + HEADER_HEIGHT;
            for (Module mod : modules) {
                if (mx >= frame.x && mx <= frame.x + FRAME_WIDTH && my >= rowY && my < rowY + MODULE_ROW_HEIGHT) {
                    if (mouseButton == 0) {
                        mod.toggle();
                    } else if (mouseButton == 1) {
                        expandedModule = (expandedModule == mod) ? null : mod;
                    }
                    return;
                }
                rowY += MODULE_ROW_HEIGHT;
                if (mod == expandedModule) {
                    float settingsH = getSettingsHeight(mod);
                    // Click on settings area - toggle buttons
                    if (mx >= frame.x && mx <= frame.x + FRAME_WIDTH && my >= rowY && my < rowY + settingsH) {
                        handleSettingClick(mod, frame.x, rowY, mx, my);
                        return;
                    }
                    rowY += settingsH;
                }
            }
        }
    }

    private void handleSettingClick(Module mod, float frameX, float startY, int mx, int my) {
        float y = startY;
        for (Setting setting : mod.getSettings()) {
            if (setting instanceof DescriptionSetting) continue;
            if (setting instanceof ButtonSetting) {
                if (my >= y && my < y + SETTING_ROW_HEIGHT) {
                    ((ButtonSetting) setting).toggle();
                    return;
                }
                y += SETTING_ROW_HEIGHT;
            } else if (setting instanceof SliderSetting) {
                if (my >= y && my < y + SETTING_ROW_HEIGHT) {
                    SliderSetting slider = (SliderSetting) setting;
                    String[] opts = slider.getOptions();
                    if (opts != null && opts.length > 0) {
                        int idx = (int) slider.getInput();
                        slider.setValue((idx + 1) % opts.length);
                    }
                    return;
                }
                y += SETTING_ROW_HEIGHT;
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        draggingFrame = null;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (draggingFrame != null) {
            float scale = Gui.getClickGuiScale();
            draggingFrame.x = mouseX / scale - dragOffsetX;
            draggingFrame.y = mouseY / scale - dragOffsetY;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }

        if (searchFocused) {
            if (keyCode == Keyboard.KEY_BACK) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            } else if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
                search += typedChar;
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        // Could add scroll for frames later
    }

    // ---- Helpers ----

    private List<Module> getModulesForCategory(Module.category category) {
        if (Raven.getModuleManager() == null) return new ArrayList<>();
        List<Module> result = new ArrayList<>();
        for (Module mod : Raven.getModuleManager().inCategory(category)) {
            if (Gui.shouldShowModule(mod)) result.add(mod);
        }
        return result;
    }

    private List<Module> getFilteredModules(Module.category category, String searchLower) {
        List<Module> modules = getModulesForCategory(category);
        if (searchLower.isEmpty()) return modules;
        List<Module> filtered = new ArrayList<>();
        for (Module m : modules) {
            if (m.getName().toLowerCase(Locale.ROOT).contains(searchLower)) filtered.add(m);
        }
        return filtered;
    }

    private int getAccentColor() {
        if (Gui.themeColor != null) return Gui.themeColor.getColor();
        return new Color(159, 143, 210).getRGB();
    }

    private RavenFontRenderer getHeaderFont() {
        return FontManager.getClickGuiHeaderRenderer(Gui.getSelectedFontName());
    }

    private RavenFontRenderer getBodyFont() {
        return FontManager.getClickGuiSettingRenderer(Gui.getSelectedFontName());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static class Frame {
        Module.category category;
        float x, y;
        float lastRenderedHeight;
    }
}
*/