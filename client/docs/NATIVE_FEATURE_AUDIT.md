# Native feature compatibility audit

This audit covers every module registered by `ModuleManager`, plus the shared
ClickGUI, configuration/profile system, HUD editor, chat, scoreboard, font,
notification, and media-rendering paths.

## What "compatible" means

- A feature that only uses Mindless's `onUpdate`, keybind, or ordinary Forge
  event path does not need its own Minecraft transformer.
- A feature that depends on a custom event or modified Minecraft method must
  have a native transformer producer in both MCP and SRG namespaces.
- Direct Lunar does not contain Forge's patched event producers. Its bridge
  now supplies the standard events consumed by Mindless, including ticks,
  world rendering, overlays, GUI opens, chat, mouse input, entity joins,
  world loads, player/living rendering, fog, block highlighting, attacks,
  jumps, living updates, and mob attack-target changes.
- Every transformed class is checked for legal JVMTI retransformation: method
  bodies may change, but no fields, methods, interfaces, or inheritance may
  be added to an already loaded Minecraft class.

## Registered modules reviewed

### Client and framework

Chat Commands, Command Line, GUI, Settings, Spotify Info, Relationships, and
the script/profile managers. Configuration loading is driven from
`NativeBootstrap -> Mindless.init`; ClickGUI construction and profile/script
loading use the same initializer as the normal Forge mod. Spotify rendering
is hooked through `GuiIngameForge` on Forge and `GuiIngame` on direct Lunar.

### Combat

Aim Assist, AntiKnockback, AutoClicker, Autoblock, BlockIn, ClickAssist,
Displace, HitSelect, HitBox, JumpReset, KillAura, KnockbackDelay, LagRange,
Piercing, GhostHand, RawInput, Reach, Reduce, RodAimbot, TPAura, Velocity, and
WTap. Their attack, click, rotation, movement, velocity/explosion packet,
mouse, jump, living-update, and target-selection producers are present.

### Fun and hit feedback

Extra Bobbing, Capes, Flame Trail, SlyPort, Spin, and Hit Effect. Cape,
first/third-person item animation, overlay, render-world, item-chams, sound,
and entity-render hooks are present. Experimental glass sounds and the rain
sound are included in both distributions.

### Minigames

AutoRequeue, AutoWho, ArenaStats, BedWars, ShopHelper, BridgeInfo, DuelsStats,
MurderMystery, SkyWars, SpeedBuilders, SumoFences, and WoolWars. Chat, GUI,
mouse, entity-join, tick, render-world, and container-click events are
available in both runtime profiles.

### Movement

BHop, MovementFix, Boost, Dolphin, Fly, InvMove, KeepSprint, LongJump, NoSlow,
NullMove, Speed, Sprint, Stasis, StopMotion, InstantStop, Teleport, Timer, and
VClip. Player input, strafe, jump, motion packet, collision, movement, and
inventory-input hooks are present. SafeWalk's non-sneaking edge guard is
implemented by modifying the `isSneaking` expression used by `moveEntity`.

### Other

Anticheat, ChatBypass, FakeChat, LatencyAlerts, NameHider, and ViewPackets.
Chat, packet send/dispatch/receive, rendered-name, tab-name, and font-text
paths are present.

### Player

AntiAFK, AntiFireball, AutoJump, AutoSwap, BridgeAssist, Scaffold, Tower, AutoTool,
BedAura, Blink, DelayRemover, FastMine, FastPlace, FakeLag, Freecam,
HideWindow, InvManager, NoFall, NoRotate, SafeWalk, and WaterBucket. Slot
changes, inventory close/open, block hardness/delay, right click, mouse-over,
packet, collision, block highlight, and world-render paths are present.

### Render and HUD

AntiDebuff, AntiShuffle, Arrows, BedESP, BlockESP, BlockOverlay, BodyMaterial,
BreakProgress, Chams, DamageTint, Fullbright, MotionBlur, DamageTags,
HitParticles, ChestESP, ExtendCamera, Freelook, FallView, Holdlook, HUD,
Notifications, Indicators, ItemESP, ItemPhysics, MobESP, Nametags,
NoCameraClip, NoHurtCam, PotionHUD, Radar, Saturation, Watermark, TargetHUD,
Trajectories, TNTTimer, Tracers, Xray, Animations, AlwaysBlock, Player ESP,
and Slow. This includes first/third-person animation timing, armor/held-item
layers, dropped items, living entities, chests, camera/fog/lightmap, outlines,
name suppression, HUD render ticks, world rendering, and shader state.

### World

AntiBot, Atmosphere, Particles, and AmbientSound. Entity joins, living target
updates, custom rain/thunder/sky/time/fog/lightmap/filter rendering,
render-world particles, and packaged sounds are covered. OptiFine CustomSky
now redirects its actual world-time read instead of using an inert placeholder.

## Shared UI paths

- **Chat:** `GuiNewChat` owns the glass background, message animation, bounds,
  and text rendering. `GuiChat` removes the vanilla input rectangle and owns
  the rounded input plus command preview, scrolling, and completion cycling.
- **Scoreboard:** `GuiIngame.renderScoreboard` is replaced by the Mindless
  glass/glow renderer and reports bounds to the HUD editor.
- **Music Player:** Forge renders after `GuiIngameForge.renderPlayerList`;
  direct Lunar renders at the end of `GuiIngame.renderGameOverlay`. Media
  polling remains off the render thread.
- **HUD/array list/TargetHUD/PotionHUD/Radar/Watermark/notifications:** these
  consume render-tick or render-world events, supplied by Forge or the direct
  bridge as appropriate.
- **ClickGUI and HUD editor:** client ticks open the GUI and update settings;
  GUI input transformers cover keyboard, mouse, containers, and chat.

## Automated guards

`TransformerCompatibilityTest` transforms every registered MCP/SRG target and
rejects missing targets, inert transforms, dangling helper calls, or JVMTI
schema changes. `FeatureCoverageContractTest` additionally guards the named
feature hooks above and verifies that every custom event consumed by a module
has a concrete producer. `RuntimeAccessorContractTest` rejects Mixin-only
interface casts outside the normal Mixin implementation package.

These checks establish load-time and hook compatibility. A live Minecraft
smoke test remains necessary for exact OpenGL appearance, server-specific
menus/scoreboards, Spotify/Windows media availability, and gameplay behavior.
