# Kill Aura port progress

Updated 2026-09-11 after taking over the interrupted Luna implementation.

Implementation and packaging are complete. In-game validation is pending; no live Forge/Lunar session or server behavior is claimed. Paths below are relative to the client working directory.

## Implemented

- Replaced legacy Kill Aura execution with source settings, target selection, rotations, signed tick timing, and all nine autoblock behaviors. The 35 settings match the PredAC fixture; its three metadata fields are handled by profile creation. No other PredAC module was imported.
- Retained the public module identity and target/HUD APIs. Removed active legacy Smart scoring, target cap, aim-range constraints, catch-up clicks, and boolean blocking behavior.
- Added strict settings loading and version 4 migration, preserving compatible customized values and the existing backup mechanism. Module-owned constraints preserve edit direction.
- Integrated final rotation ownership, source sensitivity snapping, Silent/Strict movement, one cancellable attack path, accepted-packet accounting, and local health tracking.
- Implemented None, Vanilla, Hypixel, Blink, Interact, Spoof, Swap, Legit, and Fake as separate state-machine paths with behavior checks. Buffering uses a dedicated lease in the existing packet-delay service.
- Coordinated AutoWeapon, standalone Autoblock, NoSlow, BedAura, Displace, rendering, manual input, mode/slot changes, disable, profiles, and world transitions.
- Wired Forge mixins and injectable transformers, including both outbound packet overloads, once-per-base-tick execution, stop-use cancellation, and native slowdown support. Fixed the native jump override to preserve remote-entity behavior.
- Preserved Silent Bed Breaker's vanilla item-strength call and configured 5.5 reach. Added its existing five smoke checks to the normal project tests.

## Phase and host decisions

Candidate preparation runs once at the base game tick. Rotation is requested through the existing arbiter. Both player backends invoke the aura directly after all PreMotion subscribers resolve, immediately before the movement packet. This prevents an earlier successful request from driving an attack after another owner wins. Timer subupdates are excluded.

Compared with Myau's update-head execution, this phase occurs after living-input processing. First-tick blocking slowdown and movement under a late competing rotation require live verification. No claim of identical intra-tick packet timing is made.

Mindless's existing relation, team, and bot classifiers remain authoritative. Its BedAura priority setting, attack events, Displace deferral, and brief HUD handoff are retained. AutoWeapon temporarily selects the attack weapon and restores it before autoblock starts. Fake uses existing rendering hooks without changing gameplay item use.

Buffered modes use the host service's bounded five-second lease. They pause when another outbound owner prevents source flush timing, and release only aura claims. Accepted C03 resets action state; enqueue counts as acceptance and replay does not count again. Per-thread send receipts prevent background packets from overwriting a synchronous aura send result.

## Verification

All 24 authoritative source-manifest hashes matched before implementation. Backups of takeover source and the previous injection bundle are under `../../reports/killaura-takeover/baseline`.

The final focused run passed all 53 tests: 20 aura tests, five Silent Bed Breaker smoke tests, both new runtime compatibility checks, event registration, profile migrations, rotation arbitration, and packet-delay ownership. See `../../reports/killaura-takeover/final-verified-build.log` and `verified-test-results` for the actual result.

The last full Forge suite ran 95 tests: 88 passed, five failed, and two were skipped. It preceded the final per-thread receipt regression test; the focused run covers that final change. Remaining pre-existing failures are:

- Missing PlayerTeleportEvent producer.
- Missing SlotUpdateEvent hook in TransformerMinecraft.
- Mixin-only casts in Jump45 and AutoSwap.
- Missing LayerArmorBase transformer registration, reported once for each runtime namespace.

The full injectable suite had the same failures plus an event-inspection test classpath failure because Mixin callback classes are absent from its runtime dependencies. The Forge event-registration check passes. Failures were not hidden or assertions weakened.

Forge and injectable/native packages are built separately. The DLL's RCDATA 421 and 422 resources are extracted without executing it, compared byte-for-byte with the current SRG and MCP payloads, and checked for the aura controller and preserved Silent Bed Breaker classes at Java class version 52. Hashes are recorded in `../../reports/killaura-takeover/artifact-verification.json`.

## Artifacts and remaining live checks

- Injectable bundle: `build/injection/`. Use `MindlessTestLoader.exe` with the adjacent `MindlessNative.dll`.
- Forge mod: `../../reports/killaura-takeover/artifacts/forge/mindless.jar`.
- Build logs: `../../reports/killaura-takeover/final-verified-build.log` and `injectable-build.log`.

Fully restart Minecraft/Lunar before loading the new bundle. Live checks still needed are actual bootstrap/retransformation on both clients, packet/phase traces for every mode, first-block slowdown and movement, rendering, real target filtering/obstruction, and lifecycle/competing-module scenarios from the handoff. Pure behavior and transformed-bytecode tests do not replace these checks or establish server acceptance.
