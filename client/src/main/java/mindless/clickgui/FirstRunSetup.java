package mindless.clickgui;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.impl.client.Gui;
import mindless.utility.profile.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.awt.Desktop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Lightweight, menu-only onboarding shown once per installation. */
public final class FirstRunSetup extends GuiScreen {
    private static final String FLAG_NAME = "first_run_complete";
    private static boolean checked;
    private static boolean complete;
    private static boolean shownThisSession;

    private final GuiScreen parent;
    private int profileIndex;
    private boolean binding;
    private String status = "";

    private FirstRunSetup(GuiScreen parent) {
        this.parent = parent;
    }

    public static void tick(Minecraft mc) {
        if (mc == null || shownThisSession || isComplete(mc)) return;
        if (mc.theWorld != null || !(mc.currentScreen instanceof GuiMainMenu)) return;

        shownThisSession = true;
        mc.displayGuiScreen(new FirstRunSetup(mc.currentScreen));
    }

    private static boolean isComplete(Minecraft mc) {
        if (!checked) {
            checked = true;
            complete = flagFile(mc).isFile();
        }
        return complete;
    }

    private static File flagFile(Minecraft mc) {
        return new File(new File(mc.mcDataDir, "mindless"), FLAG_NAME);
    }

    @Override
    public void initGui() {
        buttonList.clear();
        List<Profile> profiles = Mindless.profileManager == null ? null : Mindless.profileManager.profiles;
        if (profiles != null && Mindless.currentProfile != null) {
            int current = profiles.indexOf(Mindless.currentProfile);
            profileIndex = current < 0 ? 0 : current;
        }

        int x = width / 2 - 100;
        int y = height / 2 - 48;
        buttonList.add(new GuiButton(0, x, y, 200, 20, bindLabel()));
        buttonList.add(new GuiButton(1, x, y + 28, 24, 20, "<"));
        buttonList.add(new GuiButton(2, x + 176, y + 28, 24, 20, ">"));
        buttonList.add(new GuiButton(3, x, y + 56, 200, 20, "Open scripts folder"));
        buttonList.add(new GuiButton(4, x, y + 88, 98, 20, "Finish"));
        buttonList.add(new GuiButton(5, x + 102, y + 88, 98, 20, "Skip"));
        refreshProfileButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int center = width / 2;
        int top = height / 2 - 104;
        drawCenteredString(fontRendererObj, "Welcome to Mindless", center, top, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj,
                "Choose the basics now. Everything can be changed later.", center, top + 16, 0xFFAAAAAA);
        drawCenteredString(fontRendererObj, "Starting profile: " + selectedProfileName(),
                center, height / 2 - 13, 0xFFCCCCCC);
        drawCenteredString(fontRendererObj, "Scripts: " + scriptsDirectory().getAbsolutePath(),
                center, height / 2 + 47, 0xFF888888);
        if (!status.isEmpty()) {
            drawCenteredString(fontRendererObj, status, center, height / 2 + 75, 0xFFAAAAAA);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                binding = true;
                button.displayString = "Press a key (Esc to cancel)";
                break;
            case 1:
                cycleProfile(-1);
                break;
            case 2:
                cycleProfile(1);
                break;
            case 3:
                openScriptsFolder();
                break;
            case 4:
                applyProfile();
                finish();
                break;
            case 5:
                finish();
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (binding) {
            binding = false;
            if (keyCode != Keyboard.KEY_ESCAPE) {
                Module gui = Module.getModule(Gui.class);
                if (gui != null) gui.setBind(keyCode);
            }
            GuiButton button = findButton(0);
            if (button != null) button.displayString = bindLabel();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            finish();
        }
    }

    private void cycleProfile(int direction) {
        List<Profile> profiles = Mindless.profileManager == null ? null : Mindless.profileManager.profiles;
        if (profiles == null || profiles.isEmpty()) return;
        profileIndex = (profileIndex + direction + profiles.size()) % profiles.size();
        refreshProfileButtons();
    }

    private void refreshProfileButtons() {
        boolean multiple = Mindless.profileManager != null && Mindless.profileManager.profiles.size() > 1;
        GuiButton previous = findButton(1);
        GuiButton next = findButton(2);
        if (previous != null) previous.enabled = multiple;
        if (next != null) next.enabled = multiple;
    }

    private String selectedProfileName() {
        List<Profile> profiles = Mindless.profileManager == null ? null : Mindless.profileManager.profiles;
        if (profiles == null || profiles.isEmpty()) return "default";
        profileIndex = Math.max(0, Math.min(profileIndex, profiles.size() - 1));
        return profiles.get(profileIndex).getName();
    }

    private void applyProfile() {
        if (Mindless.profileManager != null) {
            Mindless.profileManager.loadProfile(selectedProfileName());
        }
    }

    private String bindLabel() {
        Module gui = Module.getModule(Gui.class);
        int key = gui == null ? Keyboard.KEY_RSHIFT : gui.getKeycode();
        return "ClickGUI key: " + (key == 0 ? "None" : Keyboard.getKeyName(key));
    }

    private void openScriptsFolder() {
        File directory = scriptsDirectory();
        if (!directory.exists()) directory.mkdirs();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(directory);
                status = "Opened scripts folder.";
            }
            else {
                status = "Scripts folder is shown below.";
            }
        }
        catch (Exception e) {
            status = "Could not open the folder; use the path below.";
        }
    }

    private File scriptsDirectory() {
        if (Mindless.scriptManager != null && Mindless.scriptManager.directory != null) {
            return Mindless.scriptManager.directory;
        }
        return new File(new File(mc.mcDataDir, "mindless"), "scripts");
    }

    private GuiButton findButton(int id) {
        for (Object entry : buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.id == id) return button;
        }
        return null;
    }

    private void finish() {
        File flag = flagFile(mc);
        try {
            File directory = flag.getParentFile();
            if (!directory.exists()) directory.mkdirs();
            Files.write(flag.toPath(), "complete\n".getBytes(StandardCharsets.UTF_8));
            complete = true;
        }
        catch (Exception e) {
            status = "Setup was saved for this session only.";
        }
        mc.displayGuiScreen(parent);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
