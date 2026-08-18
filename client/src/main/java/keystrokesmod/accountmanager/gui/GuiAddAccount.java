package keystrokesmod.accountmanager.gui;

import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;
import keystrokesmod.utility.shader.RoundedUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public class GuiAddAccount extends GuiScreen {
    private final GuiScreen previousScreen;

    public GuiAddAccount(GuiScreen previousScreen) {
        this.previousScreen = previousScreen;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2, bw = 200, bh = 20, gap = 6;
        int startY = height / 2 - (5 * bh + 4 * gap) / 2;
        buttonList.add(new GuiButton(0, cx - bw/2, startY,            bw, bh, "Microsoft"));
        buttonList.add(new GuiButton(1, cx - bw/2, startY + (bh+gap),   bw, bh, "Cookie"));
        buttonList.add(new GuiButton(2, cx - bw/2, startY + (bh+gap)*2, bw, bh, "Cracked"));
        buttonList.add(new GuiButton(3, cx - bw/2, startY + (bh+gap)*3, bw, bh, "Access Token"));
        buttonList.add(new GuiButton(5, cx - bw/2, startY + (bh+gap)*4, bw, bh, "Refresh Token"));
        buttonList.add(new GuiButton(4, cx - bw/2, startY + (bh+gap)*5 + 4, bw, bh, "Back"));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawRect(0, 0, width, height, GuiAccountManager.C_BG);
        RavenFontRenderer sfBold = FontManager.getClickGuiHeaderRenderer("Sf-Bold");
        RavenFontRenderer sfReg  = FontManager.getClickGuiSmallRenderer("Sf-Regular");

        int cardW = 240, cardH = height / 2 + 20;
        int cardX = width / 2 - cardW / 2, cardY = height / 2 - cardH / 2;
        RoundedUtils.drawRound(cardX, cardY, cardW, cardH, 6f, GuiAccountManager.C_PANEL);
        drawRect(cardX, cardY, cardX + cardW, cardY + 1, GuiAccountManager.C_ACCENT_DIM);

        sfBold.drawString("Add Account", width / 2f - sfBold.getStringWidth("Add Account") / 2f, cardY + 10f, GuiAccountManager.C_TEXT, false);
        sfReg.drawString("Choose account type", width / 2f - sfReg.getStringWidth("Choose account type") / 2f, cardY + 24f, GuiAccountManager.C_DIM, false);

        for (GuiButton b : buttonList) {
            boolean isBack = b.id == 4;
            boolean hov = mx >= b.xPosition && mx < b.xPosition + b.width
                    && my >= b.yPosition && my < b.yPosition + b.height;
            int bg  = isBack ? GuiAccountManager.C_ROW : 0xCC181A2A;
            int bgH = isBack ? GuiAccountManager.C_ROW_HOV : 0xCC1E2035;
            int fg  = isBack ? GuiAccountManager.C_MUTED : GuiAccountManager.C_ACCENT;
            RoundedUtils.drawRound(b.xPosition, b.yPosition, b.width, b.height, 4f, hov ? bgH : bg);
            RavenFontRenderer fr = FontManager.getClickGuiSettingRenderer("Sf-Regular");
            float tw = fr.getStringWidth(b.displayString);
            fr.drawString(b.displayString, b.xPosition + b.width/2f - tw/2f,
                    b.yPosition + b.height/2f - fr.getFontHeight()/2f, fg, false);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null) return;
        switch (button.id) {
            case 0: mc.displayGuiScreen(new GuiMicrosoftAuth(this));      break;
            case 1: mc.displayGuiScreen(new GuiCookieAuth(this));         break;
            case 2: mc.displayGuiScreen(new GuiCrackedAuth(this));        break;
            case 3: mc.displayGuiScreen(new GuiTokenLogin(this));         break;
            case 5: mc.displayGuiScreen(new GuiRefreshTokenLogin(this));  break;
            case 4: mc.displayGuiScreen(new GuiAccountManager(previousScreen)); break;
        }
    }
}
