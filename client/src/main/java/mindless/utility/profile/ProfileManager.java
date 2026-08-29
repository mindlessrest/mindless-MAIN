package mindless.utility.profile;

import com.google.gson.*;
import mindless.Raven;
import mindless.clickgui.ClickGui;
import mindless.clickgui.components.impl.CategoryComponent;
import mindless.event.PostProfileLoadEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.Gui;
import mindless.module.impl.client.Relationships;
import mindless.module.impl.client.Settings;
import mindless.module.impl.minigames.BedWars;
import mindless.module.impl.player.FastPlace;
import mindless.module.impl.player.HideWindow;
import mindless.module.impl.render.HUD;
import mindless.module.impl.render.PotionHUD;
import mindless.module.impl.render.TargetHUD;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.BlockListSetting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.setting.impl.StringListSetting;
import mindless.module.setting.impl.TextSetting;
import mindless.script.Manager;
import mindless.utility.IMinecraftInstance;
import mindless.utility.Utils;
import net.minecraftforge.common.MinecraftForge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class ProfileManager implements IMinecraftInstance {
    private static final String DEFAULT_PROFILE_NAME = "default";
    private static final char[] INVALID_PROFILE_NAME_CHARS = new char[]{'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    private static final class SavedCategoryState {
        final float x, y;
        final boolean opened;

        SavedCategoryState(float x, float y, boolean opened) {
            this.x = x;
            this.y = y;
            this.opened = opened;
        }
    }

    private static final class RequestedModuleState {
        boolean enabled;
        int keybind;
        Boolean hidden;

        RequestedModuleState(boolean enabled, int keybind) {
            this.enabled = enabled;
            this.keybind = keybind;
        }
    }

    /**
     * How long a change sits unwritten before it is saved on its own.
     *
     * <p>Long enough that dragging a slider writes once rather than once a frame, short enough
     * that a crash costs a few seconds of fiddling rather than an evening of it.
     */
    private static final long AUTO_SAVE_DELAY_MS = 4000L;

    public File directory;
    public List<Profile> profiles = new ArrayList<>();

    /** When the current profile first went unsaved, or 0 when it is clean. */
    private long dirtySince;

    public ProfileManager() {
        directory = new File(mc.mcDataDir + File.separator + "mindless", "profiles");
        if (!directory.exists()) {
            boolean success = directory.mkdirs();
            if (!success) {
                System.out.println("There was an issue creating profiles directory.");
                return;
            }
        }
        if (getProfileFiles().isEmpty()) {
            saveProfile(new Profile(DEFAULT_PROFILE_NAME, 0));
        }
    }

    public void saveProfile(Profile profile) {
        if (profile == null) {
            return;
        }
        String serialized = serializeProfile(profile);
        if (serialized == null || !writeProfileFile(profile.getName(), serialized)) {
            failedMessage("save", profile.getName());
            return;
        }
        profile.getModule().saved = true;
        dirtySince = 0L;
    }

    /**
     * Saves without holding up the frame.
     *
     * <p>The reading of module state still happens here, on the game thread, so what gets written
     * is one coherent snapshot; only the write itself is handed off. A profile is around fifty
     * kilobytes and the write is followed by a flush to the disk, which is not something to do in
     * the middle of a frame every time a slider moves.
     */
    private void saveProfileInBackground(Profile profile) {
        if (profile == null) {
            return;
        }
        final String serialized = serializeProfile(profile);
        if (serialized == null) {
            return;
        }
        final String name = profile.getName();
        try {
            Raven.getCachedExecutor().execute(new Runnable() {
                public void run() {
                    writeProfileFile(name, serialized);
                }
            });
        }
        catch (Exception e) {
            writeProfileFile(name, serialized);
        }
    }

    private String serializeProfile(Profile profile) {
        try {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("keybind", profile.getModule().getKeycode());
            JsonArray jsonArray = new JsonArray();
            for (Module module : Raven.moduleManager.getModules()) {
                if (module.ignoreOnSave && !shouldSaveModuleStateOnly(module)) {
                    continue;
                }
                JsonObject moduleInformation = module.ignoreOnSave ? getModuleStateObject(module) : getJsonObject(module);
                jsonArray.add(moduleInformation);
            }
            if (Raven.scriptManager != null && Raven.scriptManager.scripts != null) {
                for (Module module : Raven.scriptManager.scripts.values()) {
                    if (module.ignoreOnSave) {
                        continue;
                    }
                    JsonObject moduleInformation = getJsonObject(module);
                    jsonArray.add(moduleInformation);
                }
            }
            jsonObject.add("modules", jsonArray);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            return gson.toJson(jsonObject);
        }
        catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Writes a profile whole, or not at all.
     *
     * <p>Opening the profile itself for writing empties it first, so anything that interrupted the
     * write -- the game being closed, a crash, the machine going down -- left behind half a
     * profile or none of one, and the next launch reported it as unloadable. The new contents go
     * to a temporary file, are flushed to the disk rather than left in the operating system's
     * cache, and only then replace the profile in a single move. The copy being replaced is kept
     * alongside as {@code .bak}, which is what a load falls back to if a profile is ever damaged
     * from outside this method.
     *
     * @return whether the profile on disk now holds the given contents
     */
    private boolean writeProfileFile(String profileName, String serialized) {
        File target = new File(directory, profileName + ".json");
        File temp = new File(directory, profileName + ".json.tmp");
        File backup = new File(directory, profileName + ".json.bak");
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                return false;
            }

            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(serialized.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            finally {
                out.close();
            }

            if (target.isFile() && target.length() > 0L) {
                try {
                    Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                catch (Exception ignored) {
                    // A missing backup is worth less than the save itself; carry on.
                }
            }

            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * Writes the current profile if it has unsaved changes, on this thread.
     *
     * <p>For the two moments where there is no later: switching to another profile, which
     * overwrites every module with the incoming one's state, and the game closing.
     */
    public void flushCurrentProfile() {
        Profile profile = Raven.currentProfile;
        if (profile == null || !isAutoSaveEnabled() || profile.getModule().saved) {
            return;
        }
        saveProfile(profile);
    }

    /**
     * Saves the current profile a few seconds after it was last changed.
     *
     * <p>Profiles used to be written only when "Update profile" was pressed, and nothing in the
     * menu said whether that was still owed. Toggling a module, closing the game and coming back
     * to find it off again reads as the profile being broken rather than as never having been
     * saved, and switching profiles threw the same changes away without a word.
     */
    public void autoSaveTick() {
        Profile profile = Raven.currentProfile;
        if (profile == null || !isAutoSaveEnabled()) {
            dirtySince = 0L;
            return;
        }

        ProfileModule module = profile.getModule();
        if (module.saved) {
            dirtySince = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (dirtySince == 0L) {
            dirtySince = now;
            return;
        }
        if (now - dirtySince < AUTO_SAVE_DELAY_MS) {
            return;
        }

        dirtySince = 0L;
        module.saved = true;
        saveProfileInBackground(profile);
    }

    /**
     * Whether profiles are written without being asked.
     *
     * <p>Off unless turned on, so a profile only changes on disk when "Update profile" is pressed.
     * The active row in the menu reads "Unsaved" while a write is owed, which is the part that was
     * missing before -- the old behaviour was this one without the indicator, so changes were lost
     * on quit with nothing having said they were pending.
     *
     * <p>Before the setting exists there is no session to save yet, so the answer is the same as
     * the default rather than the opposite of it.
     */
    private static boolean isAutoSaveEnabled() {
        return Settings.autoSaveProfiles != null && Settings.autoSaveProfiles.isToggled();
    }

    public Profile createProfile(String requestedName, int bind) {
        String profileName = normalizeProfileName(requestedName);
        String validationError = validateProfileName(profileName, null);
        if (validationError != null) {
            Utils.sendMessage("&c" + validationError);
            return null;
        }

        Profile profile = new Profile(profileName, bind);
        saveProfile(profile);
        profiles.add(profile);
        refreshProfileModules();
        return profile;
    }

    private static JsonObject getJsonObject(Module module) {
        JsonObject moduleInformation = new JsonObject();
        moduleInformation.addProperty("name", (module.moduleCategory() == Module.category.scripts && !(module instanceof Manager)) ?  "sc-" + module.getName() :  module.getName());
        if (module.canBeEnabled) {
            moduleInformation.addProperty("enabled", module.isEnabled());
            moduleInformation.addProperty("hidden", module.isHidden());
            moduleInformation.addProperty("keybind", module.getKeycode());
        }
        if (module instanceof HUD) {
            moduleInformation.addProperty("posX", HUD.posX);
            moduleInformation.addProperty("posY", HUD.posY);
            moduleInformation.addProperty("relPosX", HUD.getRelativePosX());
            moduleInformation.addProperty("relPosY", HUD.getRelativePosY());
        }
        else if (module instanceof TargetHUD) {
            moduleInformation.addProperty("posX", ModuleManager.targetHUD.posX);
            moduleInformation.addProperty("posY", ModuleManager.targetHUD.posY);
        }
        else if (module instanceof PotionHUD) {
            PotionHUD potionHUD = (PotionHUD) module;
            moduleInformation.addProperty("posX", potionHUD.getPosX());
            moduleInformation.addProperty("posY", potionHUD.getPosY());
            moduleInformation.addProperty("relPosX", potionHUD.getRelativePosX());
            moduleInformation.addProperty("relPosY", potionHUD.getRelativePosY());
        }
        else if (module instanceof mindless.module.impl.render.AudioVisualizer) {
            mindless.module.impl.render.AudioVisualizer visualizer = (mindless.module.impl.render.AudioVisualizer) module;
            moduleInformation.addProperty("relPosX", visualizer.getRelativePosX());
            moduleInformation.addProperty("relPosY", visualizer.getRelativePosY());
        }
        else if (module instanceof mindless.module.impl.render.SessionInfo) {
            mindless.module.impl.render.SessionInfo session = (mindless.module.impl.render.SessionInfo) module;
            moduleInformation.addProperty("posX", session.getPosX());
            moduleInformation.addProperty("posY", session.getPosY());
            moduleInformation.addProperty("relPosX", session.getRelativePosX());
            moduleInformation.addProperty("relPosY", session.getRelativePosY());
        }
        else if (module instanceof HideWindow) {
            HideWindow hw = (HideWindow) module;
            moduleInformation.addProperty("posX", hw.getPosX());
            moduleInformation.addProperty("posY", hw.getPosY());
            moduleInformation.addProperty("relPosX", hw.getRelativePosX());
            moduleInformation.addProperty("relPosY", hw.getRelativePosY());
        }
        else if (module instanceof FastPlace) {
            FastPlace fp = (FastPlace) module;
            moduleInformation.addProperty("posX", fp.getPosX());
            moduleInformation.addProperty("posY", fp.getPosY());
            moduleInformation.addProperty("relPosX", fp.getRelativePosX());
            moduleInformation.addProperty("relPosY", fp.getRelativePosY());
        }
        else if (module instanceof BedWars) {
            BedWars bedWars = (BedWars) module;
            moduleInformation.addProperty("closestEnemyPosX", bedWars.getClosestEnemyPosX());
            moduleInformation.addProperty("closestEnemyPosY", bedWars.getClosestEnemyPosY());
            moduleInformation.addProperty("closestEnemyRelPosX", bedWars.getClosestEnemyRelativePosX());
            moduleInformation.addProperty("closestEnemyRelPosY", bedWars.getClosestEnemyRelativePosY());
            moduleInformation.addProperty("magicMilkPosX", bedWars.getMagicMilkPosX());
            moduleInformation.addProperty("magicMilkPosY", bedWars.getMagicMilkPosY());
            moduleInformation.addProperty("magicMilkRelPosX", bedWars.getMagicMilkRelativePosX());
            moduleInformation.addProperty("magicMilkRelPosY", bedWars.getMagicMilkRelativePosY());
        }
        else if (module instanceof Gui) {
            for (CategoryComponent c : ClickGui.categories) {
                moduleInformation.addProperty(c.category.name(), c.x + "," + c.y + "," + c.opened);
            }
            // Where the modern window was dragged to. The legacy category panels above have always
            // been saved; this one was not, so the window went back to the middle of the screen on
            // every launch however far it had been moved.
            moduleInformation.addProperty("modernGuiOffsetX",
                    mindless.clickgui.ModernClickGui.getDragOffsetX());
            moduleInformation.addProperty("modernGuiOffsetY",
                    mindless.clickgui.ModernClickGui.getDragOffsetY());
        }
        for (Setting setting : module.getSettings()) {
            if (setting instanceof ButtonSetting && !((ButtonSetting) setting).isMethodButton) {
                moduleInformation.addProperty(setting.getProfileKey(), ((ButtonSetting) setting).isToggled());
            }
            else if (setting instanceof SliderSetting) {
                moduleInformation.addProperty(setting.getProfileKey(), ((SliderSetting) setting).getInput());
            }
            else if (setting instanceof KeySetting) {
                moduleInformation.addProperty(setting.getProfileKey(), ((KeySetting) setting).getKey());
            }
            else if (setting instanceof TextSetting) {
                moduleInformation.addProperty(setting.getProfileKey(), ((TextSetting) setting).getText());
            }
            else if (setting instanceof ColorSetting) {
                ColorSetting cs = (ColorSetting) setting;
                moduleInformation.addProperty(setting.getProfileKey(),
                        cs.getRed() + "," + cs.getGreen() + "," + cs.getBlue() + "," + cs.getAlpha());
            }
            else if (setting instanceof BlockListSetting) {
                moduleInformation.add(setting.getProfileKey(), ((BlockListSetting) setting).toJsonArray());
            }
            else if (setting instanceof StringListSetting) {
                moduleInformation.add(setting.getProfileKey(), ((StringListSetting) setting).toJsonArray());
            }
        }
        return moduleInformation;
    }

    private static JsonObject getModuleStateObject(Module module) {
        JsonObject moduleInformation = new JsonObject();
        moduleInformation.addProperty("name", module.getName());
        if (module.canBeEnabled) {
            moduleInformation.addProperty("enabled", module.isEnabled());
            moduleInformation.addProperty("hidden", module.isHidden());
            moduleInformation.addProperty("keybind", module.getKeycode());
        }
        return moduleInformation;
    }

    private static boolean shouldSaveModuleStateOnly(Module module) {
        return module instanceof Relationships;
    }

    /**
     * Applies a saved profile to every module.
     *
     * <p>Read first, apply second. The file is turned into a plan -- which modules should be on,
     * what each one's settings and position should be -- before anything is touched, so a
     * malformed entry is skipped rather than abandoning the load partway through with half the
     * client on the old profile and half on the new one, still labelled as whichever came last.
     * That half-applied state was then what "Update profile" wrote back to disk.
     */
    public void loadProfile(String name) {
        Profile existingProfile = getProfile(name);
        String profileName = existingProfile != null ? existingProfile.getName() : normalizeProfileName(name);

        // Anything unsaved belongs to the profile being left, and the load below overwrites every
        // module with the incoming one. Written out here or gone without a word.
        Profile outgoing = Raven.currentProfile;
        if (outgoing != null && !outgoing.getName().equalsIgnoreCase(profileName)) {
            flushCurrentProfile();
        }

        File file = findProfileFile(profileName);
        if (file == null) {
            failedMessage("load", profileName);
            System.out.println("Failed to load " + profileName);
            return;
        }

        JsonObject profileJson = readProfileJson(file, profileName);
        JsonArray modules = profileJson == null ? null : profileJson.getAsJsonArray("modules");
        if (modules == null) {
            failedMessage("load", profileName);
            return;
        }

        List<Module> loadableModules = getLoadableModules();
        captureSettingDefaults(loadableModules);

        Map<Module, RequestedModuleState> requestedModuleStates = createDefaultRequestedModuleStates(loadableModules);
        Map<Module, JsonObject> loadedModuleData = new LinkedHashMap<Module, JsonObject>();
        Map<String, SavedCategoryState> savedGuiCategoryState = new HashMap<String, SavedCategoryState>();
        float[] savedModernGuiOffset = null;
        boolean loadedRelationshipsState = false;

        for (JsonElement moduleJson : modules) {
            if (moduleJson == null || !moduleJson.isJsonObject()) {
                continue;
            }
            JsonObject moduleInformation = moduleJson.getAsJsonObject();
            JsonElement nameElement = moduleInformation.get("name");
            if (nameElement == null || !nameElement.isJsonPrimitive()) {
                continue;
            }
            String moduleName = nameElement.getAsString();
            if (moduleName == null || moduleName.isEmpty()) {
                continue;
            }

            Module module = Raven.moduleManager.getModule(moduleName);
            if (module == null && moduleName.startsWith("sc-") && Raven.scriptManager != null) {
                for (Module scriptModule : Raven.scriptManager.scripts.values()) {
                    if (scriptModule.getName().equals(moduleName.substring(3))) {
                        module = scriptModule;
                    }
                }
            }
            if (module == null) {
                continue;
            }

            loadedModuleData.put(module, moduleInformation);

            if (module instanceof Relationships) {
                loadedRelationshipsState = true;
            }

            if (module.getName().equals("Gui")) {
                readGuiCategoryState(moduleInformation, savedGuiCategoryState);
                if (moduleInformation.has("modernGuiOffsetX") && moduleInformation.has("modernGuiOffsetY")) {
                    try {
                        savedModernGuiOffset = new float[] {
                                moduleInformation.get("modernGuiOffsetX").getAsFloat(),
                                moduleInformation.get("modernGuiOffsetY").getAsFloat() };
                    } catch (Exception malformed) {
                        // A profile written before this existed, or hand-edited. Leave it centred.
                        savedModernGuiOffset = null;
                    }
                }
            }

            if (module.canBeEnabled()) {
                RequestedModuleState requestedState = requestedModuleStates.get(module);
                if (requestedState == null) {
                    requestedState = new RequestedModuleState(false, 0);
                    requestedModuleStates.put(module, requestedState);
                }
                readModuleState(moduleInformation, requestedState);
            }
        }

        if (!loadedRelationshipsState && ModuleManager.relationships != null && Raven.playerRelationsManager != null) {
            RequestedModuleState relationshipsState = requestedModuleStates.get(ModuleManager.relationships);
            if (relationshipsState != null) {
                relationshipsState.enabled = Raven.playerRelationsManager.isActive();
            }
        }

        try {
            // Off first: a module's own disable can write to its settings, which would otherwise
            // land on top of the values just read for it.
            for (Module module : loadableModules) {
                RequestedModuleState requestedState = requestedModuleStates.get(module);
                if (requestedState != null && !requestedState.enabled && module.isEnabled()) {
                    module.disable();
                }
            }

            for (Module module : loadableModules) {
                RequestedModuleState requestedState = requestedModuleStates.get(module);
                if (requestedState == null) {
                    continue;
                }
                module.setBind(requestedState.keybind);
                if (requestedState.hidden != null) {
                    module.setHidden(requestedState.hidden);
                }
            }

            for (Map.Entry<Module, JsonObject> entry : loadedModuleData.entrySet()) {
                applyModulePosition(entry.getKey(), entry.getValue());
            }

            // Over every module, not just the ones the file mentions. A setting the profile has no
            // opinion about goes back to its default instead of keeping whatever the profile
            // before it left there, which is what let two profiles disagree about a value only one
            // of them had ever been asked for.
            for (Module module : loadableModules) {
                if (!module.ignoreOnSave) {
                    applyModuleSettings(module, loadedModuleData.get(module));
                }
            }

            for (Module module : loadableModules) {
                RequestedModuleState requestedState = requestedModuleStates.get(module);
                if (requestedState == null) {
                    continue;
                }
                if (requestedState.enabled && !module.isEnabled()) {
                    module.enable();
                }
                else if (!requestedState.enabled && module.isEnabled()) {
                    module.disable();
                }
            }

            // Last, so modules that keep their own copy of their settings pick the new ones up
            // even when they were already on and so were never re-enabled.
            for (Module module : loadableModules) {
                try {
                    module.onProfileLoad();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        catch (Exception e) {
            failedMessage("load", profileName);
            e.printStackTrace();
        }

        Profile loaded = getProfile(profileName);
        if (loaded != null) {
            Raven.currentProfile = loaded;
            // Freshly read from disk, so nothing is owed until something changes.
            loaded.getModule().saved = true;
        }
        dirtySince = 0L;
        saveLastProfile(profileName);

        try {
            boolean loadGuiPositions = Gui.loadGuiPositions.isToggled();
            Raven.clickGui.refreshAfterProfileLoad();
            if (loadGuiPositions) {
                for (CategoryComponent c : ClickGui.categories) {
                    SavedCategoryState state = savedGuiCategoryState.get(c.category.name());
                    if (state != null) {
                        c.applySavedState(state.x, state.y, state.opened, true);
                    }
                }
                if (savedModernGuiOffset != null) {
                    mindless.clickgui.ModernClickGui.setDragOffset(
                            savedModernGuiOffset[0], savedModernGuiOffset[1]);
                }
            }
            Raven.clickGui.enforceHorizontalProfileLayout();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        if (Raven.currentProfile != null) {
            MinecraftForge.EVENT_BUS.post(new PostProfileLoadEvent(Raven.currentProfile.getName()));
        }
    }

    private static void readModuleState(JsonObject moduleInformation, RequestedModuleState requestedState) {
        try {
            if (moduleInformation.has("enabled")) {
                requestedState.enabled = moduleInformation.get("enabled").getAsBoolean();
            }
            if (moduleInformation.has("hidden")) {
                requestedState.hidden = moduleInformation.get("hidden").getAsBoolean();
            }
            if (moduleInformation.has("keybind")) {
                requestedState.keybind = moduleInformation.get("keybind").getAsInt();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void readGuiCategoryState(JsonObject moduleInformation, Map<String, SavedCategoryState> into) {
        for (Map.Entry<String, JsonElement> setting : moduleInformation.entrySet()) {
            String settingName = setting.getKey();
            if (!Module.categoriesString.contains(settingName)) {
                continue;
            }
            try {
                String[] parts = setting.getValue().getAsString().split(",");
                if (parts.length < 2) {
                    continue;
                }
                float posX = Float.parseFloat(parts[0]);
                float posY = Float.parseFloat(parts[1]);
                boolean opened = parts.length > 2 && Boolean.parseBoolean(parts[2]);
                into.put(settingName, new SavedCategoryState(posX, posY, opened));
            }
            catch (Exception ignored) {
            }
        }
    }

    /**
     * Puts one module's settings where the profile says they should be.
     *
     * <p>A setting the file names is read from it; a setting it does not name goes back to the
     * value it was built with. Both halves matter: without the second, profiles leak into each
     * other, and a profile written before a setting existed silently adopts whatever the last
     * profile set it to. Settings that have never been asked for their default -- registered after
     * the first profile load, which scripts can do -- are left alone rather than reset to nothing.
     */
    private static void applyModuleSettings(Module module, JsonObject moduleInformation) {
        for (Setting setting : module.getSettings()) {
            try {
                if (moduleInformation != null && hasSavedValue(setting, moduleInformation)) {
                    setting.loadProfile(moduleInformation);
                }
                else if (setting.hasCapturedDefault()) {
                    setting.resetToDefault();
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean hasSavedValue(Setting setting, JsonObject moduleInformation) {
        String[] keys = setting.getProfileKeys();
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key != null && moduleInformation.has(key)) {
                return true;
            }
        }
        return false;
    }

    private static void captureSettingDefaults(List<Module> modules) {
        for (Module module : modules) {
            for (Setting setting : module.getSettings()) {
                setting.captureDefaultOnce();
            }
        }
    }

    /** Restores the on-screen position of the modules that have one. */
    private static void applyModulePosition(Module module, JsonObject moduleInformation) {
        try {
            if (module == ModuleManager.hud) {
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    HUD.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                    float hudX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : HUD.posX;
                    float hudY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : HUD.posY;
                    HUD.setAbsolutePosition(hudX, hudY);
                }
            }
            else if (module.getName().equals("TargetHUD")) {
                if (moduleInformation.has("posX")) {
                    ModuleManager.targetHUD.posX = moduleInformation.get("posX").getAsInt();
                }
                if (moduleInformation.has("posY")) {
                    ModuleManager.targetHUD.posY = moduleInformation.get("posY").getAsInt();
                }
            }
            else if (module instanceof PotionHUD) {
                PotionHUD potionHUD = (PotionHUD) module;
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    potionHUD.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                    float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : potionHUD.getPosX();
                    float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : potionHUD.getPosY();
                    potionHUD.setAbsolutePosition(posX, posY);
                }
            }
            else if (module instanceof mindless.module.impl.render.AudioVisualizer) {
                mindless.module.impl.render.AudioVisualizer visualizer = (mindless.module.impl.render.AudioVisualizer) module;
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    visualizer.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
            }
            else if (module instanceof mindless.module.impl.render.SessionInfo) {
                mindless.module.impl.render.SessionInfo session = (mindless.module.impl.render.SessionInfo) module;
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    session.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                    float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : session.getPosX();
                    float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : session.getPosY();
                    session.setAbsolutePosition(posX, posY);
                }
            }
            else if (module instanceof HideWindow) {
                HideWindow hw = (HideWindow) module;
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    hw.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                    float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : hw.getPosX();
                    float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : hw.getPosY();
                    hw.setAbsolutePosition(posX, posY);
                }
            }
            else if (module instanceof FastPlace) {
                FastPlace fp = (FastPlace) module;
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    fp.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                    float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : fp.getPosX();
                    float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : fp.getPosY();
                    fp.setAbsolutePosition(posX, posY);
                }
            }
            else if (module instanceof BedWars) {
                BedWars bedWars = (BedWars) module;
                if (moduleInformation.has("closestEnemyRelPosX") && moduleInformation.has("closestEnemyRelPosY")) {
                    bedWars.setClosestEnemyRelativePosition(
                            moduleInformation.get("closestEnemyRelPosX").getAsFloat(),
                            moduleInformation.get("closestEnemyRelPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("closestEnemyPosX") || moduleInformation.has("closestEnemyPosY")) {
                    float posX = moduleInformation.has("closestEnemyPosX")
                            ? moduleInformation.get("closestEnemyPosX").getAsFloat()
                            : bedWars.getClosestEnemyPosX();
                    float posY = moduleInformation.has("closestEnemyPosY")
                            ? moduleInformation.get("closestEnemyPosY").getAsFloat()
                            : bedWars.getClosestEnemyPosY();
                    bedWars.setClosestEnemyAbsolutePosition(posX, posY);
                }

                if (moduleInformation.has("magicMilkRelPosX") && moduleInformation.has("magicMilkRelPosY")) {
                    bedWars.setMagicMilkRelativePosition(
                            moduleInformation.get("magicMilkRelPosX").getAsFloat(),
                            moduleInformation.get("magicMilkRelPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("magicMilkPosX") || moduleInformation.has("magicMilkPosY")) {
                    float posX = moduleInformation.has("magicMilkPosX")
                            ? moduleInformation.get("magicMilkPosX").getAsFloat()
                            : bedWars.getMagicMilkPosX();
                    float posY = moduleInformation.has("magicMilkPosY")
                            ? moduleInformation.get("magicMilkPosY").getAsFloat()
                            : bedWars.getMagicMilkPosY();
                    bedWars.setMagicMilkAbsolutePosition(posX, posY);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** The file for a profile, matching the name exactly first and then ignoring case. */
    private File findProfileFile(String profileName) {
        String wanted = profileName + ".json";
        File exact = new File(directory, wanted);
        if (exact.isFile()) {
            return exact;
        }
        for (File file : getProfileFiles()) {
            if (file.getName().equalsIgnoreCase(wanted)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Reads a profile, falling back to the copy kept beside it.
     *
     * <p>A profile that could not be parsed used to be reported as a failure and left exactly as
     * it was, so every launch after failed the same way and the profile was effectively gone. The
     * backup is written before each save, so at worst it is one save behind; restoring it turns a
     * lost profile into a lost edit.
     */
    private JsonObject readProfileJson(File file, String profileName) {
        JsonObject parsed = parseProfileFile(file);
        if (parsed != null) {
            return parsed;
        }

        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        JsonObject fromBackup = parseProfileFile(backup);
        if (fromBackup == null) {
            return null;
        }

        try {
            Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception ignored) {
        }
        Utils.sendMessage("&e" + profileName + " &7was damaged; restored the last saved copy.");
        return fromBackup;
    }

    private static JsonObject parseProfileFile(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return null;
        }
        try (FileReader fileReader = new FileReader(file)) {
            JsonElement parsed = new JsonParser().parse(fileReader);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            return parsed.getAsJsonObject();
        }
        catch (Exception e) {
            return null;
        }
    }

    private List<Module> getLoadableModules() {
        List<Module> loadableModules = new ArrayList<Module>(Raven.getModuleManager().getModules());
        if (Raven.scriptManager != null && Raven.scriptManager.scripts != null) {
            loadableModules.addAll(Raven.scriptManager.scripts.values());
        }
        return loadableModules;
    }

    private Map<Module, RequestedModuleState> createDefaultRequestedModuleStates(List<Module> modules) {
        Map<Module, RequestedModuleState> requestedModuleStates = new HashMap<Module, RequestedModuleState>();
        for (Module module : modules) {
            if (module.canBeEnabled()) {
                requestedModuleStates.put(module, new RequestedModuleState(false, 0));
            }
        }
        return requestedModuleStates;
    }

    public boolean deleteProfile(String name) {
        Profile removedProfile = getProfile(name);
        String profileName = removedProfile != null ? removedProfile.getName() : normalizeProfileName(name);
        File profileFile = new File(directory, profileName + ".json");

        if (profileFile.exists() && !(profileFile.delete() || !profileFile.exists())) {
            return false;
        }

        boolean wasCurrentProfile = removedProfile != null && Raven.currentProfile == removedProfile;
        if (removedProfile != null) {
            profiles.remove(removedProfile);
        }
        if (wasCurrentProfile) {
            Raven.currentProfile = null;
        }

        if (profiles.isEmpty()) {
            Profile fallbackProfile = createProfile(DEFAULT_PROFILE_NAME, 0);
            if (fallbackProfile == null) {
                return false;
            }
        }
        else {
            refreshProfileModules();
            if (wasCurrentProfile) {
                Profile fallbackProfile = getDefaultOrFirstProfile();
                if (fallbackProfile != null) {
                    loadProfile(fallbackProfile.getName());
                }
            }
        }
        return removedProfile != null;
    }

    public void loadProfiles() {
        String currentProfileName = Raven.currentProfile != null ? Raven.currentProfile.getName() : null;
        boolean currentProfileSaved = Raven.currentProfile == null || Raven.currentProfile.getModule().saved;
        profiles.clear();
        if (!directory.exists() && !directory.mkdirs()) {
            Utils.sendMessage("&cFailed to load profiles.");
            return;
        }

        List<File> profileFiles = getProfileFiles();
        if (profileFiles.isEmpty()) {
            saveProfile(new Profile(DEFAULT_PROFILE_NAME, 0));
            profileFiles = getProfileFiles();
        }

        for (File file : profileFiles) {
            String fileName = file.getName();
            String profileName = fileName.substring(0, fileName.length() - ".json".length());
            try {
                // One unreadable profile used to abandon every profile after it. Now it costs
                // only itself, and only after the backup beside it has also been tried.
                JsonObject profileJson = readProfileJson(file, profileName);
                if (profileJson == null) {
                    failedMessage("load", profileName);
                    continue;
                }

                int keybind = 0;

                if (profileJson.has("keybind")) {
                    try {
                        keybind = profileJson.get("keybind").getAsInt();
                    }
                    catch (Exception ignored) {
                    }
                }

                Profile profile = new Profile(profileName, keybind);
                profiles.add(profile);
            } catch (Exception e) {
                failedMessage("load", profileName);
                e.printStackTrace();
            }
        }

        if (currentProfileName != null) {
            Raven.currentProfile = getProfile(currentProfileName);
            if (Raven.currentProfile != null) {
                Raven.currentProfile.getModule().saved = currentProfileSaved;
            }
        }
        
        // Auto-load the last active profile, or default if none saved
        if (Raven.currentProfile == null && !profiles.isEmpty()) {
            String lastProfile = getLastProfile();
            Profile toLoad = null;
            if (lastProfile != null) {
                toLoad = getProfile(lastProfile);
            }
            if (toLoad == null) {
                toLoad = getProfile(DEFAULT_PROFILE_NAME);
            }
            if (toLoad == null) {
                toLoad = profiles.get(0);
            }
            Raven.currentProfile = toLoad;
            loadProfile(toLoad.getName());
        }

        Utils.sendMessage("&b" + profileFiles.size() + " &7profiles loaded.");
    }

    public List<File> getProfileFiles() {
        List<File> profileFiles = new ArrayList<>();
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files == null) {
                return profileFiles;
            }
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".json")) {
                    continue;
                }
                profileFiles.add(file);
            }
        }
        return profileFiles;
    }

    public Profile getProfile(String name) {
        for (Profile profile : profiles) {
            if (profile.getName().equalsIgnoreCase(name)) {
                return profile;
            }
        }
        return null;
    }

    public void loadInitialProfile() {
        Raven.currentProfile = null;
    }

    public void failedMessage(String reason, String name) {
        Utils.sendMessage("&cFailed to " + reason + ": &b" + name);
    }

    public boolean renameProfile(Profile profile, String requestedName) {
        if (profile == null) {
            Utils.sendMessage("&cFailed to rename profile.");
            return false;
        }

        String oldName = profile.getName();
        String newName = normalizeProfileName(requestedName);
        String validationError = validateProfileName(newName, oldName);
        if (validationError != null) {
            Utils.sendMessage("&c" + validationError);
            return false;
        }

        if (oldName.equals(newName)) {
            profile.setName(newName);
            return true;
        }

        File oldFile = new File(directory, oldName + ".json");
        File newFile = new File(directory, newName + ".json");

        if (!oldFile.exists()) {
            failedMessage("rename", oldName);
            return false;
        }

        try {
            Files.move(oldFile.toPath(), newFile.toPath());
            profile.setName(newName);
            return true;
        }
        catch (Exception e) {
            failedMessage("rename", oldName);
            e.printStackTrace();
            return false;
        }
    }

    private void refreshProfileModules() {
        if (Raven.clickGui == null || Raven.clickGui.categories == null) {
            return;
        }
        for (CategoryComponent categoryComponent : Raven.clickGui.categories) {
            if (categoryComponent.category == Module.category.profiles) {
                categoryComponent.reloadModules(true);
                break;
            }
        }
    }

    private Profile getDefaultOrFirstProfile() {
        Profile defaultProfile = getProfile(DEFAULT_PROFILE_NAME);
        if (defaultProfile != null) {
            return defaultProfile;
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private String validateProfileName(String profileName, String currentName) {
        if (profileName.isEmpty()) {
            return "Profile name cannot be empty.";
        }
        if (profileName.endsWith(".") || profileName.endsWith(" ")) {
            return "Profile name cannot end with a space or period.";
        }
        for (char c : profileName.toCharArray()) {
            if (c < 32 || containsInvalidProfileChar(c)) {
                return "Profile name contains invalid characters.";
            }
        }
        for (Profile profile : profiles) {
            if (profile.getName().equalsIgnoreCase(profileName) && (currentName == null || !profile.getName().equalsIgnoreCase(currentName))) {
                return "Profile already exists: " + profileName;
            }
        }
        return null;
    }

    private boolean containsInvalidProfileChar(char c) {
        for (char invalidChar : INVALID_PROFILE_NAME_CHARS) {
            if (invalidChar == c) {
                return true;
            }
        }
        return false;
    }

    private String normalizeProfileName(String name) {
        return name == null ? "" : name.trim();
    }

    private File getLastProfileFile() {
        return new File(directory.getParent(), "last_profile.txt");
    }

    private void saveLastProfile(String name) {
        try (FileWriter writer = new FileWriter(getLastProfileFile())) {
            writer.write(name);
        } catch (Exception ignored) {}
    }

    private String getLastProfile() {
        File file = getLastProfileFile();
        if (!file.exists()) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String name = new String(bytes).trim();
            if (name.isEmpty()) return null;
            return name;
        } catch (Exception ignored) {
            return null;
        }
    }
}
