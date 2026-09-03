package mindless.module;

import mindless.module.impl.client.HideModules;
import mindless.module.impl.client.ChatCommands;
import mindless.module.impl.client.Gui;
import mindless.module.impl.client.Relationships;
import mindless.module.impl.client.Settings;
import mindless.module.impl.client.SpotifyMiniPlayer;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.impl.combat.*;
import mindless.module.impl.bedwars.*;
import mindless.module.impl.minigames.*;
import mindless.module.impl.movement.*;
import mindless.module.impl.render.ExtraBobbing;
import mindless.module.impl.network.Backtrack;
import mindless.module.impl.other.*;
import mindless.module.impl.player.*;
import mindless.module.impl.render.*;
import mindless.module.impl.world.*;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.profile.Manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleManager {
    public static List<Module> modules = new ArrayList<>();
    public static List<Module> organizedModules = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Module> modulesByName = new HashMap<>();
    private static final Map<String, Module> modulesByNormalizedName = new HashMap<>();
    private static final Map<Class<?>, Module> modulesByClass = new HashMap<>();

    public static NameHider nameHider;
    public static FastPlace fastPlace;
    public static InvMove invmove;
    public static AntiFireball antiFireball;
    public static BedAura bedAura;
    public static FastMine fastMine;
    public static AntiShuffle antiShuffle;
    public static MovementFix movementFix;
    public static SpotifyMiniPlayer spotifyMiniPlayer;
    public static ThemeManager themeManager;
    public static LongJump longJump;
    public static mindless.module.impl.world.TargetFilter targetFilter;
    public static NoSlow noSlow;
    public static KillAura killAura;
    public static AutoClicker autoClicker;
    public static HitSelect hitSelect;
    public static KnockbackDelay knockbackDelay;
    public static HitBox hitBox;
    public static Reach reach;
    public static NoRotate noRotate;
    public static BlockESP blockESP;
    public static BedESP bedESP;
    public static Blink blink;
    public static Chams chams;
    public static HUD hud;
    public static AudioVisualizer audioVisualizer;
    public static PotionHUD potionHUD;
    public static SessionInfo sessionInfo;
    public static Timer timer;
    public static Fly fly;
    public static WTap wTap;
    public static Velocity velocity;
    public static AntiDebuff antiDebuff;
    public static TargetHUD targetHUD;
    public static StatsHUD statsHUD;
    public static Radar radar;
    public static DynamicIsland watermark;
    public static NoFall noFall;
    public static SexyESP sexyESP;
    public static MobESP mobESP;
    public static SafeWalk safeWalk;
    public static KeepSprint keepSprint;
    public static Piercing piercing;
    public static GhostHand ghostHand;
    public static AntiKnockback antiKnockback;
    public static ExtendCamera extendCamera;
    public static Freelook freelook;
    public static InvManager invManager;
    public static NoCameraClip noCameraClip;
    public static BedWars bedwars;
    public static Overlay overlay;
    public static Speed speed;
    public static NoHurtCam noHurtCam;
    public static AutoTool autoTool;
    public static AutoSwap autoSwap;
    public static Scaffold scaffold;
    public static Stasis stasis;
    public static Clutch clutch;
    public static Sprint sprint;
    public static Weather weather;
    public static Ambience ambience;
    public static ChatCommands chatCommands;
    public static BlockIn blockIn;
    public static AutoBlockin autoBlockin;
    public static Relationships relationships;
    public static HideWindow hideWindow;
    public static Displace displace;
    public static ShopHelper shopHelper;
    public static BedTracker bedTracker;
    public static ResourceTracker resourceTracker;
    public static EventTimers eventTimers;
    public static Autoblock autoBlock;
    public static Backtrack backtrack;
    public static Debug debug;
    public static mindless.module.impl.other.DiscordRPC discordRPC;
    public static mindless.script.Manager scriptManager;

    public void register() {
        this.addModule(chatCommands = new ChatCommands());
        this.addModule(new Gui());
        this.addModule(new Settings());
        this.addModule(new HideModules());
        this.addModule(themeManager = new ThemeManager());
        this.addModule(spotifyMiniPlayer = new SpotifyMiniPlayer());
        this.addModule(relationships = new Relationships());
        if (mindless.Mindless.playerRelationsManager == null || mindless.Mindless.playerRelationsManager.isActive()) {
            relationships.enable();
        }

        this.addModule(new AimAssist());
        this.addModule(antiKnockback = new AntiKnockback());
        this.addModule(new AutoBow());
        this.addModule(autoClicker = new AutoClicker());
        this.addModule(autoBlock = new Autoblock());
        this.addModule(blockIn = new BlockIn());
        this.addModule(autoBlockin = new AutoBlockin());
        this.addModule(new ClickAssist());
        this.addModule(displace = new Displace());
        this.addModule(hitSelect = new HitSelect());
        this.addModule(hitBox = new HitBox());
        this.addModule(new JumpReset());
        this.addModule(new Criticals());
        this.addModule(new Regen());
        this.addModule(killAura = new KillAura());
        this.addModule(knockbackDelay = new KnockbackDelay());
        this.addModule(piercing = new Piercing());
        this.addModule(ghostHand = new GhostHand());
        this.addModule(new RawInput());
        this.addModule(reach = new Reach());
        this.addModule(new RodAimbot());
        this.addModule(new TPAura());
        this.addModule(velocity = new Velocity());
        this.addModule(wTap = new WTap());

        this.addModule(new ExtraBobbing());

        this.addModule(new AutoRequeue());
        this.addModule(new AntiMisplace());
        this.addModule(new AutoGG());
        this.addModule(new PickupAlerts());
        this.addModule(new UpgradeAlerts());
        this.addModule(bedTracker = new BedTracker());
        this.addModule(resourceTracker = new ResourceTracker());
        this.addModule(eventTimers = new EventTimers());
        this.addModule(bedwars = new BedWars());
        this.addModule(overlay = new Overlay());
        this.addModule(shopHelper = new ShopHelper());

        this.addModule(movementFix = new MovementFix());
        this.addModule(new Boost());
        this.addModule(new Dolphin());
        this.addModule(fly = new Fly());
        this.addModule(invmove = new InvMove());
        this.addModule(keepSprint = new KeepSprint());
        this.addModule(longJump = new LongJump());
        this.addModule(noSlow = new NoSlow());
        this.addModule(new NullMove());
        this.addModule(speed = new Speed());
        this.addModule(new TargetStrafe());
        this.addModule(sprint = new Sprint());
        this.addModule(stasis = new Stasis());
        this.addModule(new StopMotion());
        this.addModule(new InstantStop());
        this.addModule(new Jump45());
        this.addModule(new Teleport());
        this.addModule(timer = new Timer());
        this.addModule(new VClip());

        this.addModule(new Anticheat());
        this.addModule(new ChatBypass());
        this.addModule(discordRPC = new mindless.module.impl.other.DiscordRPC());
        this.addModule(new FakeChat());
        this.addModule(new LatencyAlerts());
        this.addModule(nameHider = new NameHider());
        this.addModule(debug = new Debug());
        this.addModule(new ViewPackets());

        this.addModule(new AntiAFK());
        this.addModule(antiFireball = new AntiFireball());
        this.addModule(new AutoJump());
        this.addModule(autoSwap = new AutoSwap());
        this.addModule(new BridgeAssist());
        this.addModule(scaffold = new Scaffold());
        this.addModule(clutch = new Clutch());
        this.addModule(autoTool = new AutoTool());
        this.addModule(bedAura = new BedAura());
        this.addModule(blink = new Blink());
        this.addModule(new DelayRemover());
        this.addModule(fastMine = new FastMine());
        this.addModule(fastPlace = new FastPlace());
        this.addModule(new FakeLag());
        this.addModule(backtrack = new Backtrack());
        this.addModule(new LagRange());
        this.addModule(new Freecam());
        this.addModule(hideWindow = new HideWindow());
        this.addModule(invManager = new InvManager());
        this.addModule(noFall = new NoFall());
        this.addModule(noRotate = new NoRotate());
        this.addModule(safeWalk = new SafeWalk());
        this.addModule(new WaterBucket());

        this.addModule(new Manager());

        this.addModule(antiDebuff = new AntiDebuff());
        this.addModule(antiShuffle = new AntiShuffle());
        this.addModule(new Arrows());
        this.addModule(bedESP = new BedESP());
        this.addModule(blockESP = new BlockESP());
        this.addModule(new BlockOverlay());

        this.addModule(new BreakProgress());
        this.addModule(chams = new Chams());
        this.addModule(new DamageTint());
        this.addModule(new Fullbright());
        this.addModule(new DamageTags());

        this.addModule(new ChestESP());
        this.addModule(extendCamera = new ExtendCamera());
        this.addModule(freelook = new Freelook());
        this.addModule(new FallView());
        this.addModule(new Holdlook());
        this.addModule(watermark = new DynamicIsland());
        this.addModule(sessionInfo = new SessionInfo());
        this.addModule(hud = new HUD());
        this.addModule(new Notifications());
        this.addModule(new Indicators());
        this.addModule(new ItemESP());
        this.addModule(new ItemPhysics());
        this.addModule(mobESP = new MobESP());
        this.addModule(new Nametags());
        this.addModule(noCameraClip = new NoCameraClip());
        this.addModule(noHurtCam = new NoHurtCam());
        this.addModule(audioVisualizer = new AudioVisualizer());
        this.addModule(potionHUD = new PotionHUD());
        this.addModule(radar = new Radar());
        this.addModule(statsHUD = new StatsHUD());
        this.addModule(new ScoreboardModule());
        this.addModule(new mindless.module.impl.render.ChatModule());
        this.addModule(new Saturation());
        this.addModule(targetHUD = new TargetHUD());
        this.addModule(new Trajectories());
        this.addModule(new TNTTimer());
        this.addModule(new Tracers());
        this.addModule(new Xray());
        this.addModule(new Animations());
        this.addModule(new AlwaysBlock());
        this.addModule(sexyESP = new SexyESP());
        modulesByName.put("SexyESP", sexyESP);
        modulesByNormalizedName.put(normalizeModuleName("SexyESP"), sexyESP);
        modulesByName.put("Outline ESP", sexyESP);
        modulesByNormalizedName.put(normalizeModuleName("Outline ESP"), sexyESP);
        this.addModule(new Slow());

        this.addModule(targetFilter = new mindless.module.impl.world.TargetFilter());
        this.addModule(weather = new Weather());
        this.addModule(ambience = new Ambience());
        this.addModule(new Particles());

        this.addModule(new mindless.script.Manager());

        movementFix.enable();
        targetFilter.enable();
        modules.sort(Comparator.comparing(Module::getName));
    }

    public void addModule(Module module) {
        modules.add(module);
        modulesByName.put(module.getName(), module);
        modulesByNormalizedName.put(normalizeModuleName(module.getName()), module);
        modulesByClass.put(module.getClass(), module);
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> inCategory(Module.category category) {
        ArrayList<Module> categoryModules = new ArrayList<>();

        for (Module module : this.getModules()) {
            if (module.moduleCategory().equals(category)) {
                categoryModules.add(module);
            }
        }

        return categoryModules;
    }
private static final Map<String, String> LEGACY_MODULE_NAMES = buildLegacyModuleNames();

    private static Map<String, String> buildLegacyModuleNames() {
        Map<String, String> names = new HashMap<>();
        names.put("HUD", "ArrayList");
        return names;
    }

    public static Module getModule(String moduleName) {
        Module module = modulesByName.get(moduleName);
        if (module != null) {
            return module;
        }
        module = modulesByNormalizedName.get(normalizeModuleName(moduleName));
        if (module != null) {
            return module;
        }
        String renamed = LEGACY_MODULE_NAMES.get(moduleName);
        return renamed == null ? null : modulesByName.get(renamed);
    }

    public static Module getModule(Class<?> clazz) {
        return modulesByClass.get(clazz);
    }

    public static void sort() {
        if (HUD.alphabeticalSort.isToggled()) {
            organizedModules.sort(Comparator.comparing(Module::getNameInHud));
            return;
        }

        final MindlessFontRenderer hudFont = HUD.getHudFontRenderer();
        organizedModules.sort((o1, o2) -> hudFont.getStringWidth(HUD.getHudRenderText(o2)) - hudFont.getStringWidth(HUD.getHudRenderText(o1)));
    }

    private static String normalizeModuleName(String moduleName) {
        if (moduleName == null) {
            return "";
        }

        StringBuilder normalized = new StringBuilder(moduleName.length());
        for (int i = 0; i < moduleName.length(); i++) {
            char character = moduleName.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    public static boolean canExecuteChatCommand() {
        return chatCommands != null && chatCommands.isEnabled();
    }
}
