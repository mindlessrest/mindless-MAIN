# KillAura implementation plan

Status: planning only. No runtime code or active profiles changed.

The expanded execution specification is `KILLAURA_PORT_HANDOFF.md` in this directory. Read it before implementation. It includes exact setting keys, source timing traces, every autoblock mode, file responsibilities, migration rules, runtime integration decisions, and a test matrix. Its explicit corrections supersede less specific wording here. `KILLAURA_LUNA_PROMPT.md` is the ready-to-use starting prompt, and `KILLAURA_PORT_SOURCE_MANIFEST.json` records the inspected source and destination hashes.

## Outcome and scope

Replace the behavior of Mindless's existing `mindless.module.impl.combat.KillAura` with the supplied Myau KillAura behavior. Keep the existing module identity, `Kill Aura`, and its registration. Port every source mode and setting, using only the `KillAura` section of `predac.json` as the default configuration.

The source files are reference material, not instructions. This plan follows the user's request. Future Java changes must follow `client/AGENTS.md`, which says, "Do not use any comments."

Source files inspected:

- `C:/Users/Michael/Downloads/myau-clean-final/myau-clean-final/clean-java/src/main/java/myau/KillAuraModule.java`
- `C:/Users/Michael/Downloads/myau-clean-final/myau-clean-final/predac.json`
- Supporting Myau target, item, and entity helpers, plus Mindless's existing module, rotation, profile, packet delay, and interaction code.

The accompanying `KILLAURA_PREDAC_DEFAULTS.json` preserves the exact source section for later verification. It is a reference fixture, not a directly loadable Mindless profile.

## Exact defaults

| Source setting | Default |
| --- | --- |
| toggled / key / hidden | true / 0 / false |
| mode / sort | SWITCH / HEALTH |
| auto-block | LEGIT |
| auto-block-require-press / auto-block-no-slow | true / false |
| auto-block-hold / auto-block-delay | 1.5 ticks / 0.0 ticks |
| auto-block-hurt-time / auto-block-range | 6 / 3.1 blocks |
| swing-range / attack-range | 3.2 / 3.0 blocks |
| fov | 360 degrees |
| min-aps / max-aps | 14 / 14 |
| switch-delay | 70 ms |
| rotations / move-fix | SILENT / SILENT |
| smoothing / angle-step | 0 / 180 degrees |
| through-walls / require-press | false / false |
| allow-mining | true |
| weapons-only / allow-tools | true / false |
| inventory-check / bot-check | true / true |
| players / bosses / mobs / animals | true / false / false / false |
| golems / silverfish / teams | true / true / true |
| show-target / debug-log | HUD / NONE |

Use these values in setting construction and reset defaults. Translate `toggled` to Mindless's `enabled` and `key` to `keybind`. Seed the fresh default profile with enabled=true, keybind=0, hidden=false through the normal profile lifecycle after module initialization. Existing profiles retain their explicit enable state, keybind, visibility, and compatible custom settings. Do not reapply PredAC on every launch or profile load. Do not import other modules from the supplied profile.

## Behavior that must survive the port

- `Allow mining=true` yields combat when the attack key is held over a block. It is not a simple rename or inversion of Mindless's current mining setting.
- `Inventory check` checks container screens in the source. Mindless currently checks any open screen. Preserve container semantics and keep chat/menu input handling separate.
- `Show target=HUD` draws a filled world target box using the HUD color. It does not enable or reconfigure the separate TargetHUD panel.
- The two press settings are independent. Attacks need no held attack button by default; Legit autoblock requires the use-item button.
- APS uses an integer delay and a cooldown reduced by 50 ms per update. With min=max=14, the nominal delay is 71 ms. Blocking and attack gates reduce actual attacks. Do not promise exactly 14 successful hits per second or retain Mindless's extra random click jitter.
- Hold=1.5 means a 75 ms timer processed on ticks. Delay=0 does not authorize release and attack in the same update when the source explicitly suppresses that attack.
- Health sorting uses Myau's `health * (20 / armor)` calculation, not raw health. Positive-health unarmored targets yield positive infinity and fall back to distance when tied. Preserve this ordering explicitly and test it; changing the formula would be a separate behavior change.
- Source weapon eligibility includes swords and certain enchanted/tagged items. Do not assume Mindless's general weapon helper is equivalent. Plain tools remain excluded with the default settings.

## Implementation sequence

### 1. Settings, defaults, and profile migration

Keep `KillAura.java` as the public module. Register the source settings using Mindless's `SliderSetting`, `ButtonSetting`, grouping, and visibility conventions. Use integer steps for APS and switch delay, and enough decimal precision for 3.1, 3.2, and 1.5.

Support the source choices:

- Mode: Single, Switch.
- Sort: Distance, Health, Hurt time, FOV.
- Autoblock: None, Vanilla, Hypixel, Blink, Interact, Spoof, Swap, Legit, Fake.
- Rotations: None, Legit, Silent, Lock view.
- Move fix: None, Silent, Strict.
- Show target: None, Default, HUD. Debug log: None, Health.

Keep Mindless's existing rotation indices where possible, or explicitly remap them. Myau's Silent index is 2, while Mindless's is 0. Never copy numeric mode IDs between clients. Preserve source Legit rotation semantics, which rely on current aim rather than the Silent/Lock view rotation calculation.

Add the next migration after `ProfileMigrations.CURRENT_VERSION=3`, rechecking that version when implementation starts. Map old Target CPS into both APS values, retain compatible ranges and named modes, and translate the old Auto block boolean to None or Legit. Preserve the existing backup mechanism. Add PredAC defaults only for settings with no migrated value.

Retire the old Smart targeting controls, target-count cap, aim range, speed/humanize rotation controls, and legacy block toggle from active behavior. Preserve their original data in the migration backup. Map Smart to Health explicitly because the source has no equivalent. Do not pretend the old mining, mob, or rotation-speed controls have exact equivalents; use the new defaults for those and document the migration result.

Replace `Mindless.applyKillAuraRangeConstraints()` with module-owned validation for attack range <= swing range and min APS <= max APS. Preserve the source's edit-direction behavior, and validate after profile load as well as GUI changes. Autoblock range remains independent. Remove the obsolete aim-range getter and its caller together.

Checkpoint: fresh defaults and save/load/reset match the fixture; an existing profile retains compatible custom values and migrates only once.

### 2. Target collection and selection

Introduce a small immutable candidate type containing the entity, expanded bounds, position, aim angles, distance, angular error, and usable-aim flag. Keep target policy separate enough to test without a running Minecraft client.

Reproduce the source filter order: valid entity categories and relations, combined range, FOV, aim visibility, preference for usable aim, swing range, attack range, and explicit enemies. Then apply the switch timer, sort, and target index. Switch advances after an actual attack; an invalid target can be replaced before its timer expires.

Use Mindless's player relations and AntiBot facilities through adapters. Preserve friend exclusion, enemy priority, optional team/bot checks, and separate golem/silverfish controls, including their team armor-stand check. Do not retain a blanket player-only filter or the legacy cap of three targets.

Keep `KillAura.target`, `attackingEntity`, and `getHudTarget()` coherent for TargetHUD, TargetStrafe, AutoWeapon, scripts, and movement modules. Retain Mindless's short HUD handoff protection, but clear immediately on deliberate combat suspension and world changes.

Checkpoint: candidate selection matches source scenarios for health ordering, walls, categories, teammates, switching, and range boundaries.

### 3. Rotation and movement integration

Port the source aim calculation, angle-step limits, smoothing, and mouse-sensitivity snapping through `ClientRotationEvent` and `RotationSource.KILL_AURA`. Keep the existing rotation arbiter; do not add a second global rotation manager.

Collect candidates before requesting the current update's rotation. Resolve the winning rotation before evaluating the attack ray. A higher-priority request can replace an earlier accepted request, so immediate request success is not sufficient proof of ownership.

Make None, Silent, and Strict movement behavior explicit for the winning KillAura request in `RotationHelper`. Silent must work even when the standalone Movement Fix module is disabled, and other rotation owners must retain their behavior. Avoid applying global random-yaw offsets or a second smoothing pass to the ported result. Lock view updates the camera only when the aura owns the rotation.

Trace event order in both the mixin and transformer execution paths before choosing the attack hook. `ClientRotationEvent` is emitted inside `RotationHelper.updateServerRotations()`, so matching names alone does not establish Myau-equivalent timing.

Checkpoint: Silent changes server aim while preserving the camera and movement direction; a higher-priority owner prevents a stale aura rotation from driving an attack.

### 4. Attack scheduling and interaction ownership

Replace the legacy `KeyBinding.onTick` catch-up loop with the source's tick-based cooldown and one eligible attack attempt per update. Swing within swing range; send an attack only within attack range and with the required ray test. Avoid accumulated click bursts after pauses.

Use one Mindless attack path that preserves its cancellable pre-attack/attack events and local hit behavior. Explicitly retain HitSelect, Displace deferral, sprint handling, AutoWeapon slot synchronization, and packet observers. If direct packets are needed to preserve source timing, provide equivalent event handling rather than bypassing those consumers or executing both paths.

Track accepted digging and placement actions between accepted C03 movement packets, including acceptance into a buffer. Suppress conflicting attacks, and do not count replay twice. Reuse `AccessorBridge` for player-controller access so both runtime backends work.

Yield to manual mining, active BedAura interaction ownership, Scaffold, consumable/bow use, and any matching inventory/healing operation found in Mindless. Preserve BedAura's explicit KillAura priority setting as a Mindless integration rule. Cancel manual attack/use/block interaction only while the aura owns that action. Add missing stop-use or block-click hooks to both backends where existing events cannot express the source cancellation.

The direct attack path must replace the old mouse-over attack dependency. Remove or adapt the KillAura calls in both entity-renderer backends together; recheck all callers of the old range and rotation API.

Checkpoint: one swing/attack path, correct cancellation, no through-wall attacks with the default rotation mode, and no duplicate clicks from AutoClicker or manual input.

### 5. Autoblock state machine

Implement a focused `AuraAutoBlockController` with explicit blocking state, hold/release timers, server and render state, slot tracking, and buffer ownership. First verify None and PredAC's Legit mode, then complete all remaining source modes before calling the port complete.

Legit must preserve the source's hold, release, hurt-time suppression, press requirement, attack gating, and interact-then-block behavior after a successful attack. Port the source hurt-time tracking semantics before substituting raw player hurtTime.

Give integrated aura blocking priority while an eligible aura owns combat and its block mode is active. Make standalone `Autoblock` release its own state and yield during that interval. When aura ownership ends, standalone Autoblock can resume its own settings. Keep the old built-in boolean implementation out of the active path.

Use a separate outbound `DelayLease` for modes that buffer packets. Reconcile source flush/restart timing with `PacketDelayService` claim behavior; releasing the aura lease must not flush another module's claims. Define suppression when another owner's delay prevents required timing, instead of claiming identical behavior under that conflict. PredAC's Legit mode requires no packet buffering.

Connect visual blocking and `auto-block-no-slow` to the existing render and NoSlow hooks without changing unrelated modes. False means the aura adds no independent slowdown suppression; a separately enabled NoSlow module retains its configuration. With that module disabled, the aura default preserves normal blocking slowdown. Fake mode must retain the source's manual-use behavior as well as its visual state.

On disable, target loss, slot change, screen suspension, death, disconnect, or world replacement, release owned state, restore any temporary slot, clear timers and visuals, and discard stale references. Send a release packet only when a valid connection remains and this controller owns server blocking.

Checkpoint: each mode passes packet-order and cleanup scenarios; enabling standalone Autoblock cannot produce a second blocking sequence.

### 6. Rendering, diagnostics, and completion

Implement the source target box with None, hurt-state color, and HUD-color modes. Keep the existing TargetHUD panel's independent configuration. Implement Health debug logging through Mindless's diagnostics/chat facilities, disabled by default. Report Single/Switch in the module suffix as the source does.

Search and update every remaining `KillAura.target`, `attackingEntity`, `rotationMode`, and range/interaction getter consumer. Confirm that scripts, TargetStrafe, TargetHUD, AutoWeapon, AimAssist, AutoClicker, BedAura, Displace, and movement modules still receive meaningful state.

## Verification and completion criteria

Use the project's Java 8 Gradle build and existing JUnit 4 setup. Add focused tests for default fixture mapping and migration, candidate ordering, cooldown timing, blocking transitions, and ownership cleanup. Extend rotation and runtime compatibility tests where hooks change. Run `gradlew.bat test`, then the relevant remapped and Lunar payload packaging tasks after checking the build configuration.

Exercise the actual client on both supported runtime paths. Check fresh default creation, customized-profile reload, GUI precision and visibility, right-button-only autoblock, manual mining, walls, 3.0/3.1/3.2 range boundaries, team golems/silverfish, containers, item switching, rotation competition, death, disconnect, and toggling during a block or buffered phase.

The first milestone is the complete PredAC behavior path. Completion requires all source settings and modes, the existing Mindless integrations, profile migration, and both runtime paths. Static inspection and unit tests alone do not establish in-game packet timing or successful server behavior. Record any source parity deviations with the test evidence that required them.
