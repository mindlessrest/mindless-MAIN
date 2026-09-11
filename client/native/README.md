# Mindless native injection bundle

`MindlessNative.dll` embeds two Mindless payloads and selects the correct one at
runtime:

- Forge 1.8.9 uses the SRG payload.
- Lunar Client 1.8.9 uses the MCP/named payload used by Lunar's baked game
  classes.

The Lunar payload is self-contained: it includes the MCP-mapped Forge event
contracts Mindless uses plus a loader-independent event bus and lifecycle bridge.
It can therefore run on a plain Lunar 1.8.9 + OptiFine profile; installing or
preloading Forge inside Lunar is not required.

`MindlessInjector.exe` performs the standard `CreateRemoteThread + LoadLibraryW`
bootstrap into the selected `java.exe`/`javaw.exe` process.

## Requirements

- Windows x64
- Visual Studio 2022 C++ x64 toolchain + Windows SDK
- CMake 3.21+
- A JDK exposing `include/jni.h` and `include/win32/jvmti.h` (JDK 8 works)

## Build (via Gradle wrapper)

From the repository root:

```powershell
.\gradlew.bat prepareInjectionBundle -PnativeJavaHome="C:\Program Files\Java\jdk1.8.0_301"
```

Outputs land in `build/injection/`:

```
MindlessNative.dll
MindlessInjector.exe
README.md
```

Under the hood, Gradle produces both namespaces and embeds them in the DLL:

- resource 421: Forge/SRG (`remapJar`)
- resource 422: Lunar/MCP + embedded event contracts (`lunarPayloadJar`)

## Native-only rebuild

```powershell
cmake -S native -B build\native -A x64 `
  -DMINDLESS_JAVA_HOME="C:\Program Files\Java\jdk1.8.0_301" `
  -DMINDLESS_FORGE_PAYLOAD_JAR="build\libs\mindless.jar" `
  -DMINDLESS_LUNAR_PAYLOAD_JAR="build\intermediates\mindless-lunar-mcp-with-forge.jar"
cmake --build build\native --config Release
```

Artifacts are written to `build/native/dist/`.

## Injection

1. Launch either **Minecraft Forge 1.8.9** or **Lunar Client 1.8.9 +
   OptiFine**. Forge-enabled Lunar profiles remain supported too. Vanilla,
   Fabric and other loaders are not supported. Injecting on the main menu is
   recommended.
2. For a local development build, run:

   ```powershell
   .\build\injection\MindlessTestLoader.exe
   ```

   You get a live list of `java.exe` / `javaw.exe` windows. Pick the
   Minecraft one with Up/Down and press Enter.

   Non-interactive: `MindlessTestLoader.exe <pid> MindlessNative.dll`

   The test loader and its development DLL bypass the production loader session.
   Production DLL builds still require authentication from `MindlessLoader.exe`.

3. On success the injector prints `Loaded ...`; the DLL writes its progress
   log to `mindless-native.log` next to the DLL. Inside the game you should
   see `[MindlessNative] Mindless bootstrap complete` on stdout (Forge log) and
   the Mindless click GUI opens on its default keybind.

When replacing this bundle with a newer build, fully close Minecraft/Lunar and
start it again before reinjecting. Java classes and Forge event registrations
cannot be replaced safely inside an already-running client; the bootstrap
rejects an older Mindless payload with exit code 19.

## When to inject

The native bridge installs a JVMTI `ClassFileLoadHook`. Classes that are
already loaded are retransformed immediately; targets loaded later pass
through the same hook. Startup now stops if a loaded target fails instead
of reporting Mindless as active with missing hooks.

For the clearest diagnosis, inject on the title screen and verify the newest
entries in `mindless-native.log`: the batch must say `retransformed N/N`, there
must be no `MindlessTransformer-ERR`, and the Java log must end with
`Mindless bootstrap complete`.

## Failure modes

Every step writes a line to `mindless-native.log`. Common exit codes from the
DLL bootstrap thread:

| Code | Meaning |
| ---- | ------- |
| 2    | `jvm.dll` never became visible in the process |
| 3    | `JNI_GetCreatedJavaVMs` export missing |
| 4    | No JVM registered within 60 seconds |
| 5    | `AttachCurrentThreadAsDaemon` failed |
| 6    | JVMTI 1.2 unavailable |
| 7    | Selected embedded payload extraction failed |
| 8    | Minecraft class loader / `Client thread` not found within 60 seconds |
| 9    | Game class loader is not URLClassLoader (unexpected on 1.8.9) |
| 10   | Could not set the worker thread's context class loader |
| 11   | `mindless.runtime.NativeBootstrap` failed to load |
| 12   | Native module could not be pinned for callback safety |
| 13   | `NativeBootstrap.start` threw |
| 14   | One or more ClassTransform/JVMTI hooks failed to install |
| 15   | Runtime namespace could not be identified safely |
| 16   | The selected payload is incomplete or attached to the wrong class loader |
| 17   | Runtime namespace/profile could not be passed to the Java payload |
| 18   | Global reference to the game class loader could not be created |
| 19   | An older/different Mindless payload won class loading and was rejected |

When any of these fires, Mindless initialization stops and is not reported as
active; the game process remains running so the log can be inspected.
