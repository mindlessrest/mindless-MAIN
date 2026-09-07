package mindless;

import mindless.accountmanager.AccountManager;
import mindless.accountmanager.Events;
import mindless.clickgui.ClickGui;
import mindless.clickgui.ModernClickGui;
import mindless.clickgui.FirstRunSetup;
import mindless.command.CommandManager;
import mindless.event.PostProfileLoadEvent;
import mindless.event.PostSetSliderEvent;
import mindless.helper.DebugHelper;
import mindless.helper.MouseHelper;
import mindless.helper.PingHelper;
import mindless.helper.RotationHelper;
import mindless.lag.handler.UnifiedLagHandler;
import mindless.runtime.LunarEventBridge;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.script.ScriptDefaults;
import mindless.script.ScriptManager;
import mindless.script.model.Entity;
import mindless.script.model.NetworkPlayer;
import mindless.utility.AttackPacketTimingTracker;
import mindless.utility.BlockHighlightSharedHandler;
import mindless.utility.FileDropManager;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.ModuleUtils;
import mindless.utility.PacketsHandler;
import mindless.utility.PlayerRelationsManager;
import mindless.utility.ReflectionUtils;
import mindless.utility.profile.Profile;
import mindless.utility.profile.ProfileManager;
import mindless.module.setting.impl.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Mod(modid = "mindless", name = "Mindless", version = "1.0", acceptedMinecraftVersions = "[1.8.9]")
public class Mindless {
    public static boolean DEBUG = false;

    public static Minecraft mc = Minecraft.getMinecraft();

    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);
    private static final ExecutorService cachedExecutor = Executors.newCachedThreadPool();

    public static ModuleManager moduleManager;
    public static ClickGui clickGui;
    public static ProfileManager profileManager;
    public static ScriptManager scriptManager;
    public static CommandManager commandManager;
    public static PlayerRelationsManager playerRelationsManager;
    public static Profile currentProfile;
    public static PacketsHandler packetsHandler;
    public static UnifiedLagHandler lagHandler;

    private static boolean firstLoad;

    public Mindless() {
        moduleManager = new ModuleManager();
    }

    @EventHandler
    public void init(FMLInitializationEvent e) {
        mc.gameSettings.showInventoryAchievementHint = false;

        Runtime.getRuntime().addShutdownHook(new Thread(scheduledExecutor::shutdown));
        Runtime.getRuntime().addShutdownHook(new Thread(cachedExecutor::shutdown));
        Runtime.getRuntime().addShutdownHook(new Thread(Mindless::saveOnShutdown));

        registerHandler(this, true);
        registerHandler(new DebugHelper(), false);
        registerHandler(new MouseHelper(), false);
        registerHandler(RotationHelper.get(), false);
        registerHandler(new PingHelper(), false);
        registerHandler(packetsHandler = new PacketsHandler(), false);
        registerHandler(new ModuleUtils(), false);
        registerHandler(AttackPacketTimingTracker.INSTANCE, false);
        registerHandler(lagHandler = new UnifiedLagHandler(), false);
        registerHandler(new mindless.helper.GameWinDetector(), false);
        AccountManager.init();
        registerHandler(new Events(), false);

        ReflectionUtils.setupFields();
        FontManager.preWarmFonts();
        playerRelationsManager = new PlayerRelationsManager();
        playerRelationsManager.load();
        moduleManager.register();
        registerHandler(new BlockHighlightSharedHandler(), true);
        scriptManager = new ScriptManager();
        clickGui = new ModernClickGui();
        profileManager = new ProfileManager();
        ScriptDefaults.reloadModules();
        scriptManager.loadScripts();
        profileManager.loadProfiles();
        ReflectionUtils.setKeyBindings();
        FileDropManager.install();

        commandManager = new CommandManager();
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent e) {
        if (e.phase == Phase.END) {
            FirstRunSetup.tick(mc);
            if (profileManager != null) {
                profileManager.autoSaveTick();
            }
            if (Utils.nullCheck()) {
                if (mc.thePlayer.ticksExisted % 6000 == 0) { // reset cache every 5 minutes
                    Entity.clearCache();
                    NetworkPlayer.clearCache();
                    if (DebugHelper.BACKGROUND) {
                        Utils.sendMessage("&aticks % 6000 == 0 &7reached, clearing script caches. (&dEntity&7, &dNetworkPlayer&7)");
                    }
                }
                if (ReflectionUtils.ERROR) {
                    Utils.sendMessage("&cThere was an error, relaunch the game.");
                    ReflectionUtils.ERROR = false;
                }

                MouseHelper.updateWheelCache();

                for (Module module : getModuleManager().getModules()) {
                    if (mc.currentScreen == null && module.canBeEnabled()) {
                        module.onKeyBind();
                    }
                    else if (mc.currentScreen instanceof ClickGui) {
                        module.guiUpdate();
                        module.syncKeyBindState();
                    }
                    else {
                        module.syncKeyBindState();
                    }

                    if (module.isEnabled()) {
                        module.onUpdate();
                    }
                }
                if (mc.currentScreen == null) {
                    for (Module module : Mindless.scriptManager.scripts.values()) {
                        module.onKeyBind();
                    }
                }
                else {
                    for (Module module : Mindless.scriptManager.scripts.values()) {
                        module.syncKeyBindState();
                    }
                    if (mc.currentScreen instanceof ClickGui) {
                    if (applyKillAuraRangeConstraints()) {
                        clickGui.onSliderChange();
                    }
                    if (mc.thePlayer.getHealth() <= 0.0f) {
                        mc.displayGuiScreen(null);
                    }
                    }
                }
            }
        }
        else {
            MouseHelper.clearWheelCache();
            if (mc.currentScreen == null && Utils.nullCheck()) {
                for (Profile profile : Mindless.profileManager.profiles) {
                    profile.getModule().onKeyBind();
                }
            }
            else if (Utils.nullCheck()) {
                for (Profile profile : Mindless.profileManager.profiles) {
                    profile.getModule().syncKeyBindState();
                }
            }
        }
    }

    @SubscribeEvent
    public void onPostProfileLoad(PostProfileLoadEvent e) {
        applyKillAuraRangeConstraints();
        clickGui.onSliderChange();
    }

    @SubscribeEvent
    public void onPostSetSlider(PostSetSliderEvent e) {
        applyKillAuraRangeConstraints();
        clickGui.onSliderChange();
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            if (!firstLoad) {
                firstLoad = true;
                scriptManager.loadScripts();
                mindless.module.impl.render.Notifications.pendingStartupAlert = true;
            }
            Entity.clearCache();
            NetworkPlayer.clearCache();
            mindless.utility.FrozenEntitySync.get().clearAll();
            if (DebugHelper.BACKGROUND) {
                Utils.sendMessage("&enew world&7, clearing script caches. (&dEntity&7, &dNetworkPlayer&7)");
            }
        }
    }

    public static ModuleManager getModuleManager() {
        return moduleManager;
    }
private static void saveOnShutdown() {
        try {
            if (unloaded || profileManager == null || currentProfile == null) {
                return;
            }
            profileManager.flushCurrentProfile();
        }
        catch (Throwable ignored) {
        }
    }

    public static ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    public static ExecutorService getCachedExecutor() {
        return cachedExecutor;
    }

    public static void handleFrozenKeybinds() {
        if (!Utils.nullCheck()) return;

        MouseHelper.updateWheelCache();

        if (mc.currentScreen == null) {
            for (Module module : getModuleManager().getModules()) {
                if (module.canBeEnabled()) {
                    module.onKeyBind();
                }
            }
            for (Module module : scriptManager.scripts.values()) {
                module.onKeyBind();
            }
        } else if (mc.currentScreen instanceof ClickGui) {
            for (Module module : getModuleManager().getModules()) {
                module.guiUpdate();
                module.syncKeyBindState();
            }
            for (Module module : scriptManager.scripts.values()) {
                module.syncKeyBindState();
            }
        } else {
            for (Module module : getModuleManager().getModules()) {
                module.syncKeyBindState();
            }
            for (Module module : scriptManager.scripts.values()) {
                module.syncKeyBindState();
            }
        }
    }

    private boolean applyKillAuraRangeConstraints() {
        if (ModuleManager.killAura == null) {
            return false;
        }

        SliderSetting attackRange = ModuleManager.killAura.getAttackRangeSetting();
        SliderSetting swingRange = ModuleManager.killAura.getSwingRangeSetting();
        SliderSetting aimRange = ModuleManager.killAura.getAimRangeSetting();
        if (attackRange == null || swingRange == null || aimRange == null) {
            return false;
        }

        boolean changed = false;
        double attack = attackRange.getInput();
        double swing = swingRange.getInput();
        double aim = aimRange.getInput();

        if (swing < attack) {
            swingRange.setValue(attack);
            swing = swingRange.getInput();
            changed = true;
        }

        if (aim < swing) {
            aimRange.setValue(swing);
            changed = true;
        }

        return changed;
    }
private static final java.util.List<Object[]> EVENT_HANDLERS = new java.util.ArrayList<Object[]>();
    private static volatile boolean unloaded = false;

    private static void registerHandler(Object handler, boolean alsoFmlBus) {
        MinecraftForge.EVENT_BUS.register(handler);
        LunarEventBridge.registerSyntheticListener(handler);
        boolean fml = alsoFmlBus && !LunarEventBridge.isDirectLunar();
        if (fml) {
            net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(handler);
        }
        EVENT_HANDLERS.add(new Object[] { handler, Boolean.valueOf(fml) });
    }
public static boolean isUnloaded() {
        return unloaded;
    }
public static synchronized void uninject() {
        if (unloaded) {
            return;
        }
        unloaded = true;

        try {
            if (mc.currentScreen instanceof ClickGui) {
                mc.displayGuiScreen(null);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (profileManager != null && currentProfile != null) {
                profileManager.flushCurrentProfile();
            }
        } catch (Throwable ignored) {
        }
        enabledBeforeUnload.clear();
        try {
            if (moduleManager != null) {
                for (Module module : ModuleManager.modules) {
                    try {
                        if (module.isEnabled()) {
                            enabledBeforeUnload.add(module);
                            module.disable();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (scriptManager != null) {
                for (Module script : new java.util.ArrayList<Module>(scriptManager.scripts.values())) {
                    try {
                        if (script.isEnabled()) {
                            script.disable();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        for (Object[] entry : EVENT_HANDLERS) {
            try {
                MinecraftForge.EVENT_BUS.unregister(entry[0]);
                LunarEventBridge.unregisterSyntheticListener(entry[0]);
                if (Boolean.TRUE.equals(entry[1])) {
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().unregister(entry[0]);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        } catch (Throwable ignored) {
        }
        try {
            mindless.runtime.MindlessTransformerManager.get().setDisabled(true);
            mindless.runtime.TransformerHooks.untransformNative();
        } catch (Throwable t) {
            markNativeLog("Untransform classes failed: " + t);
        }

        try {
            mindless.runtime.NativeBootstrap.resetStateForReinject();
        } catch (Throwable ignored) {
        }

        FileDropManager.uninstall();

        markNativeLog("Mindless self-destructed; ready for loader re-injection");
        try {
            Utils.sendMessage("&7Mindless self-destructed. Run MindlessLoader to load again.");
        } catch (Throwable ignored) {
        }
    }
private static void markNativeLog(String message) {
        try {
            java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "MindlessNative");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            java.io.File log = new java.io.File(dir, "mindless-native.log");
            try (java.io.PrintWriter writer = new java.io.PrintWriter(
                    new java.io.FileOutputStream(log, true), true)) {
                writer.println("[" + new java.util.Date() + "] " + message);
            }
        } catch (Throwable ignored) {
        }
    }
private static final java.util.List<Module> enabledBeforeUnload = new java.util.ArrayList<Module>();
public static synchronized void reinject() {
        if (!unloaded) {
            return;
        }
        try {
            mindless.runtime.MindlessTransformerManager.get().setDisabled(false);
            mindless.runtime.TransformerHooks.retransformNative();
        } catch (Throwable t) {
            markNativeLog("Retransform classes failed: " + t);
        }

        for (Object[] entry : EVENT_HANDLERS) {
            try {
                MinecraftForge.EVENT_BUS.register(entry[0]);
                LunarEventBridge.registerSyntheticListener(entry[0]);
                if (Boolean.TRUE.equals(entry[1])) {
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(entry[0]);
                }
            } catch (Throwable ignored) {
            }
        }

        for (Module module : enabledBeforeUnload) {
            try {
                if (!module.isEnabled()) {
                    module.enable();
                }
            } catch (Throwable ignored) {
            }
        }
        enabledBeforeUnload.clear();


        unloaded = false;
        markNativeLog("Mindless reinjected; client is active again");
        try {
            Utils.sendMessage("&7Mindless reinjected.");
        } catch (Throwable ignored) {
        }
    }
}
