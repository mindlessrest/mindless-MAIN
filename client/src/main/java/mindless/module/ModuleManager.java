package mindless.module;

import mindless.module.impl.client.ChatCommands;
import mindless.module.impl.client.CommandLine;
import mindless.module.impl.client.Gui;
import mindless.module.impl.client.Relationships;
import mindless.module.impl.client.Settings;
import mindless.module.impl.client.SpotifyMiniPlayer;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.impl.combat.*;
import mindless.module.impl.fun.Capes;
import mindless.module.impl.minigames.*;
import mindless.module.impl.movement.*;
import mindless.module.impl.render.ExtraBobbing;
import mindless.module.impl.network.Backtrack;
import mindless.module.impl.other.*;
import mindless.module.impl.player.*;
import mindless.module.impl.render.*;
import mindless.module.impl.world.*;
import mindless.utility.font.RavenFontRenderer;
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
    public static CommandLine commandLine;
    public static SpotifyMiniPlayer spotifyMiniPlayer;
    public static ThemeManager themeManager;
    public static LongJump longJump;
    public static AntiBot antiBot;
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
    public static PotionHUD potionHUD;
    public static Timer timer;
    public static Fly fly;
    public static WTap wTap;
    public static Velocity velocity;
    public static AntiDebuff antiDebuff;
    public static TargetHUD targetHUD;
    public static NoFall noFall;
    public static SexyESP sexyESP;
    public static MobESP mobESP;
    public static Reduce reduce;
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
    public static BHop bHop;
    public static NoHurtCam noHurtCam;
    public static AutoTool autoTool;
    public static AutoSwap autoSwap;
    public static Scaffold scaffold;
    public static Clutch clutch;
    public static Tower tower;
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
    public static Autoblock autoBlock;
    public static Backtrack backtrack;
    public static Debug debug;
    public static mindless.script.Manager scriptManager;

    public void register() {
        this.addModule(chatCommands = new ChatCommands());
        this.addModule(commandLine = new CommandLine());
        this.addModule(new Gui());
        this.addModule(new Settings());
        this.addModule(themeManager = new ThemeManager());
        this.addModule(spotifyMiniPlayer = new SpotifyMiniPlayer());
        this.addModule(relationships = new Relationships());
        if (mindless.Raven.playerRelationsManager == null || mindless.Raven.playerRelationsManager.isActive()) {
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
        this.addModule(killAura = new KillAura());
        this.addModule(knockbackDelay = new KnockbackDelay());
        this.addModule(piercing = new Piercing());
        this.addModule(ghostHand = new GhostHand());
        this.addModule(new RawInput());
        this.addModule(reach = new Reach());
        this.addModule(reduce = new Reduce());
        this.addModule(new RodAimbot());
        this.addModule(new TPAura());
        this.addModule(velocity = new Velocity());
        this.addModule(wTap = new WTap());

        this.addModule(new ExtraBobbing());
        Capes capes = new Capes();
        this.addModule(capes);
        capes.enable();
        this.addModule(new HitEffect());

        this.addModule(new AutoRequeue());
        this.addModule(bedwars = new BedWars());
        this.addModule(shopHelper = new ShopHelper());

        this.addModule(bHop = new BHop());
        this.addModule(movementFix = new MovementFix());
        this.addModule(new Boost());
        this.addModule(new Dolphin());
        this.addModule(fly = new Fly());
        this.addModule(invmove = new InvMove());
        this.addModule(keepSprint = new KeepSprint());
        this.addModule(longJump = new LongJump());
        this.addModule(noSlow = new NoSlow());
        this.addModule(new NullMove());
        this.addModule(new Speed());
        this.addModule(sprint = new Sprint());
        this.addModule(new Stasis());
        this.addModule(new StopMotion());
        this.addModule(new InstantStop());
        this.addModule(new Jump45());
        this.addModule(new Teleport());
        this.addModule(timer = new Timer());
        this.addModule(new VClip());

        this.addModule(new Anticheat());
        this.addModule(new ChatBypass());
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
        this.addModule(tower = new Tower());
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
        this.addModule(new BodyMaterial());
        this.addModule(new BreakProgress());
        this.addModule(chams = new Chams());
        this.addModule(new DamageTint());
        this.addModule(new Fullbright());
        this.addModule(new MotionBlur());
        this.addModule(new DamageTags());
        this.addModule(new HitParticles());
        this.addModule(new ChestESP());
        this.addModule(extendCamera = new ExtendCamera());
        this.addModule(freelook = new Freelook());
        this.addModule(new FallView());
        this.addModule(new Holdlook());
        this.addModule(new DynamicIsland());
        this.addModule(hud = new HUD());
        this.addModule(new Notifications());
        this.addModule(new Indicators());
        this.addModule(new ItemESP());
        this.addModule(new ItemPhysics());
        this.addModule(mobESP = new MobESP());
        this.addModule(new Nametags());
        this.addModule(noCameraClip = new NoCameraClip());
        this.addModule(noHurtCam = new NoHurtCam());
        this.addModule(potionHUD = new PotionHUD());
        this.addModule(new Radar());
        this.addModule(new Saturation());
        this.addModule(targetHUD = new TargetHUD());
        this.addModule(new Trajectories());
        this.addModule(new TNTTimer());
        this.addModule(new Tracers());
        this.addModule(new Xray());
        this.addModule(new Animations());
        this.addModule(new AlwaysBlock());
        this.addModule(sexyESP = new SexyESP());
        // Preserve profiles made before SexyESP became the sole Player ESP.
        modulesByName.put("SexyESP", sexyESP);
        modulesByNormalizedName.put(normalizeModuleName("SexyESP"), sexyESP);
        modulesByName.put("Outline ESP", sexyESP);
        modulesByNormalizedName.put(normalizeModuleName("Outline ESP"), sexyESP);
        this.addModule(new Slow());

        this.addModule(antiBot = new AntiBot());
        this.addModule(weather = new Weather());
        this.addModule(ambience = new Ambience());
        this.addModule(new Particles());

        this.addModule(new mindless.script.Manager());

        movementFix.enable();
        antiBot.enable();
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

    public static Module getModule(String moduleName) {
        Module module = modulesByName.get(moduleName);
        if (module != null) {
            return module;
        }
        return modulesByNormalizedName.get(normalizeModuleName(moduleName));
    }

    public static Module getModule(Class<?> clazz) {
        return modulesByClass.get(clazz);
    }

    public static void sort() {
        if (HUD.alphabeticalSort.isToggled()) {
            organizedModules.sort(Comparator.comparing(Module::getNameInHud));
            return;
        }

        final RavenFontRenderer hudFont = HUD.getHudFontRenderer();
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
