# Mindless startup and native injection

## Root cause of the inactive build

Mindless previously produced only a normal Forge coremod/Mixin JAR. Its
manifest names Sponge's `MixinTweaker`, and `mixins.raven.json` is installed
during Forge's early launch phase. That JAR is valid in a Forge mods folder,
but it cannot be appended to an already-running Minecraft JVM and initialized
like Pigeon.

Pigeon's executable is a different startup path. It injects a native DLL,
adds an embedded payload to the game ClassLoader, installs a JVMTI
`ClassFileLoadHook`, retransforms Minecraft classes that are already loaded,
and finally invokes the mod initializer. Mindless had none of that native
bootstrap or runtime transformer wiring. Consequently `Raven.init` was never
called, so configuration loading, ClickGUI construction, event registration,
and every module all remained inactive.

Copying an older Pigeon DLL/executable does not fix this: its DLL embeds the
Pigeon payload at native build time, not the current Mindless JAR.

## Corrected architecture

Mindless now supports two explicit launch modes:

1. **Normal Forge mode** — `build/libs/raven-bS-16.jar`
   - Put this JAR in the Forge 1.8.9 mods directory.
   - Forge loads the full `mixins.raven.json` set during startup.
   - Do not run the native injector for the same client.
2. **Pigeon-style native mode** — `build/injection/`
   - Keep `RavenInjector.exe` and `RavenNative.dll` together.
   - Start Forge 1.8.9 or Lunar 1.8.9, preferably stop at the title screen,
     and then run the injector.
   - The DLL chooses its embedded Forge/SRG or Lunar/MCP payload, validates
     that `NativeBootstrap` came from that exact payload, retransforms all
     registered Pigeon-compatible targets, and initializes Mindless on the
     Minecraft client thread.

Do not install the Forge JAR and inject the native bundle into the same game
process. The duplicate classes can win ClassLoader resolution before the
embedded payload and are deliberately rejected rather than starting a mixed
or stale client.

## Additional defects corrected

- Native injection can change method bodies of loaded classes, but JVMTI may
  not add the accessor interfaces that Sponge Mixin normally supplies.
  Mindless still cast Minecraft objects to those interfaces in many modules.
  Those uses now go through a cached reflection bridge with both MCP and SRG
  names, preventing post-injection `ClassCastException`s in combat, render,
  player, utility, and script code.
- Forge 1.8.9 posts `ClientTickEvent` on the FML event bus. Mindless registered
  only on `MinecraftForge.EVENT_BUS`, so tick-driven keybind and module updates
  could remain inert. The mod and enabled modules now register with the FML
  bus in Forge mode while avoiding duplicate registration in direct Lunar
  mode.
- Several custom event types used parameterized-only constructors. Forge's
  1.8.9 listener discovery can reject those event contracts. Safe no-argument
  constructors were added and are covered by a registration contract test.
- Transformer startup is fail-closed: a missing mapping, failed target, schema
  change, wrong payload, or incomplete retransform prevents the mod from being
  reported as active and writes a diagnostic instead.

## Native feature-hook coverage

The native transformer registry now covers every meaningful Minecraft target
used by the current Mixin configuration. This includes the player/controller
and packet event pipeline, Slow and sword animations in first and third
person, Always Block item-use rendering, GUI/container input, chat,
scoreboard/HUD rendering, player and item render layers, capes, tab names,
chests, dropped-item physics, saturation, lightmap updates, camera effects,
and selection overrides used by combat/player modules.

State that Sponge would normally store in fields or interfaces added to a
Minecraft class is instead held by schema-safe runtime helpers. This matters
because JVMTI retransformation may replace method bodies but may not change an
already loaded class's fields, methods, or implemented interfaces. The same
helpers are used by the normal Mixin path so both launch modes behave
consistently.

HUD alerts no longer depend on a dark texture being visible against a dark
notification panel. Their success/failure badges are drawn procedurally with
a high-contrast symbol, avoiding both the invisible-icon issue and texture
binding state leaks.

Transformer compatibility tests exercise the registered hooks against both
MCP and SRG Minecraft class layouts. Passing those tests proves that each hook
can be located and applied without illegal class-schema changes; final visual
and gameplay behavior still needs an in-game smoke test because the test JVM
does not provide a live OpenGL world or server.

## Build and verification

From the repository root with JDK 17 available:

```powershell
.\gradlew.bat test remapJar prepareInjectionBundle --no-daemon
```

The native compilation also requires the Visual Studio 2022 x64 C++ tools,
Windows SDK, and CMake. The Gradle task detects Visual Studio's bundled CMake
and the active JDK headers on this machine.

Useful output/log locations:

- Forge JAR: `build/libs/raven-bS-16.jar`
- Native bundle: `build/injection/`
- Native progress: `build/injection/raven-native.log`
- Java bootstrap failures: `%TEMP%/RavenNative/raven-native-java.log`
- Transformer diagnostics: `%TEMP%/RavenNative/raven-transformer.log`

After replacing either build, fully close and restart Minecraft before testing.
An injected JVM cannot safely unload and replace previously defined Mindless
classes or Forge listener registrations.
