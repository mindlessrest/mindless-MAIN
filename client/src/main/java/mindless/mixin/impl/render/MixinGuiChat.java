package mindless.mixin.impl.render;

import mindless.Mindless;
import mindless.clickgui.animation.ScrollOffsetAnimation;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mindless.utility.RenderUtils;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.runtime.GuiNewChatState;

@SideOnly(Side.CLIENT)
@Mixin(GuiChat.class)
public abstract class MixinGuiChat extends MixinGuiScreen {
    private static final int MAX_PREVIEW_ROWS = 6;
    private static final long PREVIEW_SCROLL_DURATION_MS = 200L;

    @Unique
    private String[] mindless$commandCompletions = new String[0];

    @Unique
    private int mindless$commandCompletionIndex = -1;

    @Unique
    private final ScrollOffsetAnimation mindless$previewScrollAnim = new ScrollOffsetAnimation(PREVIEW_SCROLL_DURATION_MS);

    @Unique
    private String mindless$previewScrollInput = "";

    @Shadow
    protected GuiTextField inputField;

    @Shadow
    private boolean waitingOnAutocomplete;

    @Shadow
    public abstract void onAutocompleteResponse(String[] suggestions);

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void mindless$beforeDrawScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        // GuiTextField can have its rectangular background re-enabled by other
        // UI/coremods. Keep it off so it cannot cover our rounded panel.
        inputField.setEnableBackgroundDrawing(false);

        int left = 3;
        int top = this.height - 15;
        int right = this.width - 3;
        int bottom = this.height - 2;

        // Its blur mask is batched into GuiNewChat's existing blur pass.
        // Drawing the matching glow and glass fill here keeps the input visually
        // identical without adding another full-screen blur operation.
        GuiNewChatState.drawSurface(left, top, right - left, bottom - top);
    }

    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;drawRect(IIIII)V"))
    private void mindless$removeVanillaInputBackground(int left, int top, int right, int bottom, int color) {
        // Replaced by the matching rounded glass input panel above.
    }

    @Inject(method = "keyTyped", at = @At("RETURN"))
    private void updateLength(CallbackInfo callbackInfo) {
        if (Mindless.commandManager != null && Mindless.commandManager.isCommand(inputField.getText())) {
            inputField.setMaxStringLength(256);
        }
        else {
            inputField.setMaxStringLength(100);
        }
    }

    @Inject(method = "keyTyped", at = @At("HEAD"))
    private void clearCommandCompletionState(char typedChar, int keyCode, CallbackInfo callbackInfo) {
        if (keyCode != Keyboard.KEY_TAB) {
            mindless$resetCommandCompletions();
        }
    }

    @Inject(method = "handleMouseInput", at = @At("HEAD"), cancellable = true)
    private void handleSuggestionScroll(CallbackInfo callbackInfo) {
        if (Mindless.commandManager == null) {
            return;
        }

        int wheelInput = Mouse.getEventDWheel();
        if (wheelInput == 0) {
            return;
        }

        String input = inputField.getText();
        if (!Mindless.commandManager.isCommand(input)) {
            mindless$resetPreviewScroll();
            return;
        }

        String[] suggestions = Mindless.commandManager.getPreviewSuggestions(input);
        int visibleRows = Math.min(MAX_PREVIEW_ROWS, suggestions.length);
        if (visibleRows == 0 || suggestions.length <= visibleRows) {
            mindless$syncPreviewScrollState(input, suggestions.length, visibleRows);
            return;
        }

        int mouseX = Mouse.getEventX() * this.width / mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / mc.displayHeight - 1;
        if (!mindless$isMouseOverPreviewPanel(mouseX, mouseY, suggestions, visibleRows)) {
            return;
        }

        mindless$syncPreviewScrollState(input, suggestions.length, visibleRows);
        float scrollSpeed = mindless.module.impl.client.Gui.scrollSpeed != null
            ? (float) mindless.module.impl.client.Gui.scrollSpeed.getInput()
            : 20f;
        float delta = scrollSpeed * (wheelInput / 120f);
        if (delta != 0f) {
            mindless$previewScrollAnim.extend(-delta);
            mindless$clampPreviewScroll(suggestions.length, visibleRows);
        }

        callbackInfo.cancel();
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void drawLiveCommandSuggestions(int mouseX, int mouseY, float partialTicks, CallbackInfo callbackInfo) {
        if (Mindless.commandManager == null) {
            mindless$resetPreviewScroll();
            return;
        }

        String input = inputField.getText();
        if (!Mindless.commandManager.isCommand(input)) {
            mindless$resetPreviewScroll();
            return;
        }

        String[] suggestions = Mindless.commandManager.getPreviewSuggestions(input);
        if (suggestions.length == 0) {
            mindless$resetPreviewScroll();
            return;
        }

        int rows = Math.min(MAX_PREVIEW_ROWS, suggestions.length);
        mindless$syncPreviewScrollState(input, suggestions.length, rows);
        int fontHeight = mc.fontRendererObj.FONT_HEIGHT;
        int rowHeight = fontHeight + 2;
        int widest = mc.fontRendererObj.getStringWidth("Suggestions");

        for (String suggestion : suggestions) {
            widest = Math.max(widest, mc.fontRendererObj.getStringWidth(suggestion));
        }

        int panelLeft = 4;
        int panelRight = Math.min(this.width - 4, panelLeft + widest + 10);
        int panelBottom = this.height - 16;
        int panelTop = panelBottom - 6 - fontHeight - rows * (fontHeight + 2);
        int contentTop = panelTop + 5 + fontHeight;
        int contentHeight = rows * rowHeight;

        mindless$drawDarkPanel(panelLeft, panelTop, panelRight, panelBottom, 0xC0000000);
        RenderUtils.drawRoundedRectangle(panelLeft + 4, panelTop + 1, panelRight - 4, panelTop + 2,
                0.5f, 0x30FFFFFF);

        mc.fontRendererObj.drawStringWithShadow("Suggestions", panelLeft + 4, panelTop + 3, 0xFFE8D8FF);

        RenderUtils.scissorPushGui(panelLeft, contentTop, panelRight - panelLeft, contentHeight);
        float scrollOffset = mindless$previewScrollAnim.getValue();
        int activeIndex = mindless$getActiveSuggestionIndex(input, suggestions);
        for (int i = 0; i < suggestions.length; i++) {
            int textY = Math.round(contentTop - scrollOffset + i * rowHeight);
            if (textY + fontHeight < contentTop || textY > contentTop + contentHeight) {
                continue;
            }

            int color = i == activeIndex ? 0xFFFFFFFF : 0xFFBFC4D6;
            mc.fontRendererObj.drawStringWithShadow(suggestions[i], panelLeft + 4, textY, color);
        }
        RenderUtils.scissorPop();
    }

    @Inject(method = "sendAutocompleteRequest", at = @At("HEAD"), cancellable = true)
    private void handleClientCommandCompletion(String full, String ignored, CallbackInfo callbackInfo) {
        if (Mindless.commandManager == null || !Mindless.commandManager.isCommand(full)) {
            return;
        }

        String[] suggestions = Mindless.commandManager.getAutoComplete(full);
        if (suggestions.length == 0) {
            return;
        }

        if (suggestions.length == 1 && full.equalsIgnoreCase(suggestions[0])) {
            return;
        }

        waitingOnAutocomplete = true;
        this.onAutocompleteResponse(suggestions);
        callbackInfo.cancel();
    }

    @Inject(method = "autocompletePlayerNames", at = @At("HEAD"), cancellable = true)
    private void handleCommandAutocomplete(CallbackInfo callbackInfo) {
        if (Mindless.commandManager == null) {
            return;
        }

        String input = inputField.getText();
        if (!Mindless.commandManager.isCommand(input)) {
            mindless$resetCommandCompletions();
            return;
        }

        String[] suggestions;
        if (mindless$canCycleCurrentCompletion(input)) {
            suggestions = mindless$commandCompletions;
            mindless$commandCompletionIndex = (mindless$commandCompletionIndex + 1) % suggestions.length;
        }
        else {
            suggestions = Mindless.commandManager.getAutoComplete(input);
            if (suggestions.length == 0) {
                mindless$resetCommandCompletions();
                callbackInfo.cancel();
                return;
            }

            mindless$commandCompletions = suggestions;
            mindless$commandCompletionIndex = 0;
        }

        inputField.setText(suggestions[mindless$commandCompletionIndex]);
        inputField.setCursorPositionEnd();
        callbackInfo.cancel();
    }

    @Unique
    private boolean mindless$canCycleCurrentCompletion(String input) {
        if (mindless$commandCompletions.length == 0 || mindless$commandCompletionIndex < 0) {
            return false;
        }

        for (String suggestion : mindless$commandCompletions) {
            if (suggestion.equals(input)) {
                return true;
            }
        }

        return false;
    }

    @Unique
    private void mindless$resetCommandCompletions() {
        mindless$commandCompletions = new String[0];
        mindless$commandCompletionIndex = -1;
    }

    @Unique
    private void mindless$syncPreviewScrollState(String input, int suggestionCount, int visibleRows) {
        if (!input.equals(mindless$previewScrollInput)) {
            mindless$previewScrollInput = input;
            mindless$previewScrollAnim.reset(0f);
        }
        mindless$clampPreviewScroll(suggestionCount, visibleRows);
    }

    @Unique
    private void mindless$clampPreviewScroll(int suggestionCount, int visibleRows) {
        int rowHeight = mc.fontRendererObj.FONT_HEIGHT + 2;
        float maxScroll = Math.max(0f, (suggestionCount - visibleRows) * rowHeight);
        mindless$previewScrollAnim.clampTarget(0f, maxScroll);
    }

    @Unique
    private boolean mindless$isMouseOverPreviewPanel(int mouseX, int mouseY, String[] suggestions, int visibleRows) {
        int fontHeight = mc.fontRendererObj.FONT_HEIGHT;
        int widest = mc.fontRendererObj.getStringWidth("Suggestions");
        for (String suggestion : suggestions) {
            widest = Math.max(widest, mc.fontRendererObj.getStringWidth(suggestion));
        }

        int panelLeft = 4;
        int panelRight = Math.min(this.width - 4, panelLeft + widest + 10);
        int panelBottom = this.height - 16;
        int panelTop = panelBottom - 6 - fontHeight - visibleRows * (fontHeight + 2);
        return mouseX >= panelLeft && mouseX <= panelRight && mouseY >= panelTop && mouseY <= panelBottom;
    }

    @Unique
    private int mindless$getActiveSuggestionIndex(String input, String[] suggestions) {
        String loweredInput = input == null ? "" : input.toLowerCase();
        for (int i = 0; i < suggestions.length; i++) {
            if (loweredInput.endsWith(suggestions[i].toLowerCase())) {
                return i;
            }
        }
        return 0;
    }

    @Unique
    private void mindless$resetPreviewScroll() {
        mindless$previewScrollInput = "";
        mindless$previewScrollAnim.reset(0f);
    }

    @Unique
    private static void mindless$drawDarkPanel(int left, int top, int right, int bottom, int originalColor) {
        int alpha = 64;

        BlurUtils.prepareBlur();
        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 4.0f, 0xFFFFFFFF);
        BlurUtils.blurEnd(1, 1.8f);

        BlurUtils.prepareBloom();
        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 4.0f, 0xFF000000);
        BlurUtils.bloomEnd(2, 3.0f);

        RenderUtils.drawRoundedRectangle(left, top, right, bottom, 4.0f,
                alpha << 24);
    }
}
