package mindless.utility.profile;

import com.google.gson.*;
import mindless.Mindless;
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
import mindless.module.impl.render.BlockCounter;
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
private static final long AUTO_SAVE_DELAY_MS = 4000L;

    public File directory;
    public List<Profile> profiles = new ArrayList<>();
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
            Mindless.getCachedExecutor().execute(new Runnable() {
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
            for (Module module : Mindless.moduleManager.getModules()) {
                if (module.ignoreOnSave && !shouldSaveModuleStateOnly(module)) {
                    continue;
                }
                JsonObject moduleInformation = module.ignoreOnSave ? getModuleStateObject(module) : getJsonObject(module);
                jsonArray.add(moduleInformation);
            }
            if (Mindless.scriptManager != null && Mindless.scriptManager.scripts != null) {
                for (Module module : Mindless.scriptManager.scripts.values()) {
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
public void flushCurrentProfile() {
        Profile profile = Mindless.currentProfile;
        if (profile == null || !isAutoSaveEnabled() || profile.getModule().saved) {
            return;
        }
        saveProfile(profile);
    }
public void autoSaveTick() {
        Profile profile = Mindless.currentProfile;
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
        else if (module instanceof BlockCounter) {
            BlockCounter counter = (BlockCounter) module;
            moduleInformation.addProperty("posX", counter.getPosX());
            moduleInformation.addProperty("posY", counter.getPosY());
            moduleInformation.addProperty("relPosX", counter.getRelativePosX());
            moduleInformation.addProperty("relPosY", counter.getRelativePosY());
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
            moduleInformation.addProperty("modernGuiOffsetX",
                    mindless.clickgui.ModernClickGui.getDragOffsetX());
            moduleInformation.addProperty("modernGuiOffsetY",
                    mindless.clickgui.ModernClickGui.getDragOffsetY());
            moduleInformation.addProperty("mascotOffsetX",
                    mindless.clickgui.ModernClickGui.getMascotOffsetX());
            moduleInformation.addProperty("mascotOffsetY",
                    mindless.clickgui.ModernClickGui.getMascotOffsetY());
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
public void loadProfile(String name) {
        Profile existingProfile = getProfile(name);
        String profileName = existingProfile != null ? existingProfile.getName() : normalizeProfileName(name);
        Profile outgoing = Mindless.currentProfile;
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
        float[] savedMascotOffset = null;
        boolean loadedRelationshipsState = false;
        JsonObject legacyHudData = null;

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

            Module module = Mindless.moduleManager.getModule(moduleName);
            if (module == null && moduleName.startsWith("sc-") && Mindless.scriptManager != null) {
                for (Module scriptModule : Mindless.scriptManager.scripts.values()) {
                    if (scriptModule.getName().equals(moduleName.substring(3))) {
                        module = scriptModule;
                    }
                }
            }
            if (module == null) {
                continue;
            }

            if (moduleName.equalsIgnoreCase("HUD")) {
                legacyHudData = moduleInformation;
            }

            loadedModuleData.put(module, moduleInformation);

            if (module instanceof Relationships) {
                loadedRelationshipsState = true;
            }

            if (module.getName().equals("Gui")) {
                readGuiCategoryState(moduleInformation, savedGuiCategoryState);
                if (moduleInformation.has("mascotOffsetX") && moduleInformation.has("mascotOffsetY")) {
                    try {
                        savedMascotOffset = new float[] {
                                moduleInformation.get("mascotOffsetX").getAsFloat(),
                                moduleInformation.get("mascotOffsetY").getAsFloat() };
                    } catch (Exception malformed) {
                        savedMascotOffset = null;
                    }
                }
                if (moduleInformation.has("modernGuiOffsetX") && moduleInformation.has("modernGuiOffsetY")) {
                    try {
                        savedModernGuiOffset = new float[] {
                                moduleInformation.get("modernGuiOffsetX").getAsFloat(),
                                moduleInformation.get("modernGuiOffsetY").getAsFloat() };
                    } catch (Exception malformed) {
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

        if (!loadedRelationshipsState && ModuleManager.relationships != null && Mindless.playerRelationsManager != null) {
            RequestedModuleState relationshipsState = requestedModuleStates.get(ModuleManager.relationships);
            if (relationshipsState != null) {
                relationshipsState.enabled = Mindless.playerRelationsManager.isActive();
            }
        }

        if (legacyHudData != null && ModuleManager.spotifyMiniPlayer != null
                && !loadedModuleData.containsKey(ModuleManager.spotifyMiniPlayer)) {
            loadedModuleData.put(ModuleManager.spotifyMiniPlayer, legacyHudData);
            RequestedModuleState spotifyState = requestedModuleStates.get(ModuleManager.spotifyMiniPlayer);
            if (spotifyState != null) {
                readModuleState(legacyHudData, spotifyState);
            }
        }

        try {
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
            Mindless.currentProfile = loaded;
            loaded.getModule().saved = true;
        }
        dirtySince = 0L;
        saveLastProfile(profileName);

        try {
            boolean loadGuiPositions = Gui.loadGuiPositions.isToggled();
            Mindless.clickGui.refreshAfterProfileLoad();
            if (loadGuiPositions) {
                for (CategoryComponent c : ClickGui.categories) {
                    SavedCategoryState state = savedGuiCategoryState.get(c.category.name());
                    if (state != null) {
                        c.applySavedState(state.x, state.y, state.opened, true);
                    }
                }
                if (savedMascotOffset != null) {
                    mindless.clickgui.ModernClickGui.setMascotOffset(
                            savedMascotOffset[0], savedMascotOffset[1]);
                }
                if (savedModernGuiOffset != null) {
                    mindless.clickgui.ModernClickGui.setDragOffset(
                            savedModernGuiOffset[0], savedModernGuiOffset[1]);
                }
            }
            Mindless.clickGui.enforceHorizontalProfileLayout();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        if (Mindless.currentProfile != null) {
            MinecraftForge.EVENT_BUS.post(new PostProfileLoadEvent(Mindless.currentProfile.getName()));
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
            else if (module.getName().equals("Target HUD")) {
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
            else if (module instanceof BlockCounter) {
                BlockCounter counter = (BlockCounter) module;
                if (moduleInformation.has("relPosX") && moduleInformation.has("relPosY")) {
                    counter.setRelativePosition(
                            moduleInformation.get("relPosX").getAsFloat(),
                            moduleInformation.get("relPosY").getAsFloat()
                    );
                }
                else if (moduleInformation.has("posX") || moduleInformation.has("posY")) {
                    float posX = moduleInformation.has("posX") ? moduleInformation.get("posX").getAsFloat() : counter.getPosX();
                    float posY = moduleInformation.has("posY") ? moduleInformation.get("posY").getAsFloat() : counter.getPosY();
                    counter.setAbsolutePosition(posX, posY);
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
        List<Module> loadableModules = new ArrayList<Module>(Mindless.getModuleManager().getModules());
        if (Mindless.scriptManager != null && Mindless.scriptManager.scripts != null) {
            loadableModules.addAll(Mindless.scriptManager.scripts.values());
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

        boolean wasCurrentProfile = removedProfile != null && Mindless.currentProfile == removedProfile;
        if (removedProfile != null) {
            profiles.remove(removedProfile);
        }
        if (wasCurrentProfile) {
            Mindless.currentProfile = null;
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
        String currentProfileName = Mindless.currentProfile != null ? Mindless.currentProfile.getName() : null;
        boolean currentProfileSaved = Mindless.currentProfile == null || Mindless.currentProfile.getModule().saved;
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
            Mindless.currentProfile = getProfile(currentProfileName);
            if (Mindless.currentProfile != null) {
                Mindless.currentProfile.getModule().saved = currentProfileSaved;
            }
        }
        if (Mindless.currentProfile == null && !profiles.isEmpty()) {
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
            Mindless.currentProfile = toLoad;
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
        Mindless.currentProfile = null;
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
        if (Mindless.clickGui == null || Mindless.clickGui.categories == null) {
            return;
        }
        for (CategoryComponent categoryComponent : Mindless.clickGui.categories) {
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
