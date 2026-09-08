package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.runtime.GuiNewChatState;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
public class ChatModule extends Module {
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();

    private static ChatModule instance;

    private static ButtonSetting background;
    private static SliderSetting backgroundOpacity;
    private static SliderSetting cornerRadius;
    private static SliderSetting font;
    private static SliderSetting fontScale;
    private static ButtonSetting textShadow;
    private static ButtonSetting playerHeads;
    private static SliderSetting headSize;
    private static SliderSetting lineSpacing;
    private static SliderSetting chatWidth;
    private static SliderSetting chatLines;
    private static ButtonSetting markOwnMessages;
    private static SliderSetting ownMarkerText;

    public ChatModule() {
        super("Chat", "Restyles chat with a font, panel and heads.", category.render);
        this.registerSetting(background = new ButtonSetting("Background", true));
        this.registerSetting(backgroundOpacity = new SliderSetting("Background opacity", 85.0, 0.0, 100.0, 1.0));
        this.registerSetting(cornerRadius = new SliderSetting("Corner radius", 8.0, 0.0, 14.0, 0.5));
        this.registerSetting(font = new SliderSetting("Font", 0, FONT_OPTIONS));
        this.registerSetting(fontScale = new SliderSetting("Font scale", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", true));
        this.registerSetting(playerHeads = new ButtonSetting("Player heads", false));
        this.registerSetting(headSize = new SliderSetting("Head size", 8.0, 6.0, 12.0, 0.5));
        this.registerSetting(lineSpacing = new SliderSetting("Line spacing", 0.0, -2.0, 6.0, 0.5));
        // Disabled means vanilla, which caps at what the video options allow. Set either one
        // and it governs wrapping as well as the panel, so a wider chat actually fits more on
        // a line rather than drawing a wider box around the same wrapping.
        this.registerSetting(chatWidth = new SliderSetting("Chat width", "px", true, 320.0, 80.0, 640.0, 5.0));
        this.registerSetting(chatLines = new SliderSetting("Chat lines", true, 10.0, 1.0, 30.0, 1.0));
        this.registerSetting(markOwnMessages = new ButtonSetting("Mark own messages", true));
        this.registerSetting(ownMarkerText = new SliderSetting("Own marker", 0,
                new String[]{ "(you)", "(me)", "<-- you" }));
        instance = this;
    }

    @Override
    public void guiUpdate() {
        boolean panel = background != null && background.isToggled();
        if (backgroundOpacity != null) {
            backgroundOpacity.setVisible(panel, this);
        }
        if (cornerRadius != null) {
            cornerRadius.setVisible(panel, this);
        }
        if (fontScale != null) {
            fontScale.setVisible(!isMinecraftFontSelected(), this);
        }
        if (headSize != null) {
            headSize.setVisible(playerHeads != null && playerHeads.isToggled(), this);
        }
    }

    @Override
    public void onEnable() {
        rewrapChat();
    }

    @Override
    public void onDisable() {
        rewrapChat();
    }

    @Override
    public void onSlide(SliderSetting setting) {
        if (setting == font || setting == fontScale) {
            rewrapChat();
        }
    }

    /**
     * Lines are wrapped once, when they arrive, against whatever font was selected then. Changing
     * the face or its scale afterwards leaves every line in the buffer wrapped to the old metrics,
     * so the backlog has to be split again.
     */
    private static void rewrapChat() {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
        if (minecraft == null || minecraft.ingameGUI == null) {
            return;
        }
        net.minecraft.client.gui.GuiNewChat chat = minecraft.ingameGUI.getChatGUI();
        if (chat != null) {
            chat.refreshChat();
        }
    }

    private static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static boolean drawBackground() {
        return !active() || background == null || background.isToggled();
    }
public static float backgroundOpacity() {
        if (!active() || backgroundOpacity == null) {
            return 0.85f;
        }
        return (float) Math.max(0.0, Math.min(1.0, backgroundOpacity.getInput() / 100.0));
    }

    public static float cornerRadius() {
        if (!active() || cornerRadius == null) {
            return -1.0f;
        }
        return (float) cornerRadius.getInput();
    }

    public static boolean textShadow() {
        return !active() || textShadow == null || textShadow.isToggled();
    }

    /**
     * Tag messages you sent with a marker so your own lines stand out in a busy chat.
     *
     * The sender comes from the same senderOf() the head rendering uses, so the two always
     * agree on who sent a line; matching on the raw text would tag anyone who merely said
     * your name.
     */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (!active() || markOwnMessages == null || !markOwnMessages.isToggled()) return;
        if (event.type != 0 || event.message == null) return;
        if (mc.thePlayer == null) return;

        // Same resolution the heads use: metadata when the line has it, text otherwise, and
        // confirmed against the tab list either way. senderOf alone returns null for lines that
        // carry no click event or insertion, which is most of them on a lot of servers, so your
        // own messages were never matched.
        String sender = GuiNewChatState.resolvedSenderOf(event.message);
        if (sender == null || !sender.equalsIgnoreCase(mc.thePlayer.getName())) return;

        event.message.appendSibling(new ChatComponentText(" \u00a77" + ownMarkerLabel()));
    }

    private static String ownMarkerLabel() {
        int index = ownMarkerText == null ? 0 : (int) ownMarkerText.getInput();
        switch (index) {
            case 1:  return "(me)";
            case 2:  return "<-- you";
            default: return "(you)";
        }
    }

    public static boolean playerHeads() {
        return active() && playerHeads != null && playerHeads.isToggled();
    }

    public static float headSize() {
        return !active() || headSize == null ? 8.0f : (float) headSize.getInput();
    }
    /**
     * The chat width to use, or the vanilla one when the override is off.
     *
     * Read by the wrapper and by both render paths, so the stored lines and the panel drawn
     * around them always agree on how wide chat is.
     */
    public static int width(int vanillaWidth) {
        if (!active() || chatWidth == null || chatWidth.getInput() < 0) {
            return vanillaWidth;
        }
        return (int) chatWidth.getInput();
    }

    /** Visible lines, or the vanilla count when the override is off. */
    public static int lines(int vanillaLines) {
        if (!active() || chatLines == null || chatLines.getInput() < 0) {
            return vanillaLines;
        }
        return Math.max(1, (int) chatLines.getInput());
    }

public static float lineSpacing() {
        return !active() || lineSpacing == null ? 0.0f : (float) lineSpacing.getInput();
    }
public static MindlessFontRenderer getCustomFont() {
        if (!active() || font == null || isMinecraftFontSelected()) {
            return null;
        }

        int index = (int) font.getInput();
        if (index < 0 || index >= FONT_OPTIONS.length) {
            return null;
        }

        float scale = fontScale == null ? 1.0f : (float) fontScale.getInput();
        return FontManager.getHudRenderer(FONT_OPTIONS[index], scale);
    }

    private static boolean isMinecraftFontSelected() {
        if (font == null) {
            return true;
        }
        int index = (int) font.getInput();
        return index < 0 || index >= FONT_OPTIONS.length || FontManager.isMinecraftFont(FONT_OPTIONS[index]);
    }
}
