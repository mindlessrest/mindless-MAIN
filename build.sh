#!/usr/bin/env bash
#
# Mindless United - cross build, Linux host to Windows x64.
#
# The Linux counterpart of tools/build.py. Same stages, same outputs, same layout on disk:
# the client jars from Gradle, MindlessNative.dll, and MindlessLoader.exe at the repo root.
#
# tools/build.py cannot be reused here because it shells out to gradlew.bat and hunts for
# Visual Studio installs. The compilers are the same either way: the Windows build already
# uses clang-cl and lld-link, and both run natively on Linux once the MSVC CRT and the
# Windows SDK are present. xwin fetches those from Microsoft's own redistributables.
#
#   ./build.sh                 dev build
#   ./build.sh --prod          obfuscated build, what ships
#   ./build.sh --client-only   Gradle stages only
#   ./build.sh --loader-only   native DLL and loader only, reusing existing jars
#   ./build.sh --deps          cache the toolchain, build nothing
#   ./build.sh --pack-deps     tar up loader/vcpkg_installed for another machine
#
# One dependency does not cross-build: OpenSSL. vcpkg drives nmake for it on a Windows
# triplet and nmake does not exist here, and it cannot be dropped because the bundled auth
# SDK calls EVP, HMAC and RAND directly. clang-cl emits the same MSVC ABI on either host,
# though, so a loader/vcpkg_installed tree produced by a Windows build links here unchanged.
# Run `build.bat` or `./build.sh --pack-deps` once on the Windows machine, copy the tarball
# over, and this script uses it.
#
# Caches live under ~/.cache/mindless unless MINDLESS_CACHE says otherwise, so a second run
# does not re-download the SDK.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$ROOT/client"
NATIVE_DIR="$CLIENT_DIR/native"
NATIVE_BUILD_DIR="$CLIENT_DIR/native_build"
LOADER_DIR="$ROOT/loader"
LOADER_BUILD_DIR="$LOADER_DIR/out/build/linux-cross"
OBF_DIR="$ROOT/tools/obf"
CROSS_DIR="$ROOT/tools/cross"

GRADLEW="$CLIENT_DIR/gradlew"
FORGE_JAR="$CLIENT_DIR/build/libs/mindless.jar"
LUNAR_JAR="$CLIENT_DIR/build/intermediates/mindless-lunar-mcp-with-forge.jar"
FORGE_JAR_OBF="$CLIENT_DIR/build/libs/mindless-obf.jar"
LUNAR_JAR_OBF="$CLIENT_DIR/build/intermediates/mindless-lunar-mcp-with-forge-obf.jar"
FORGE_MAPPING="$CLIENT_DIR/build/mappings/forge.json"
LUNAR_MAPPING="$CLIENT_DIR/build/mappings/lunar.json"
OBF_JAR="$OBF_DIR/build/libs/mindless-obf.jar"
NATIVE_DLL_OUT="$NATIVE_BUILD_DIR/dist/MindlessNative.dll"
LOADER_RUNTIME="$LOADER_DIR/assets/runtime/MindlessNative.dll"
OUTPUT_EXE="$ROOT/MindlessLoader.exe"

CACHE="${MINDLESS_CACHE:-$HOME/.cache/mindless}"
XWIN_ROOT="${XWIN_ROOT:-$CACHE/xwin}"
WIN_JDK="${MINDLESS_WIN_JDK:-$CACHE/jdk-win}"
VCPKG_INSTALLED="${MINDLESS_VCPKG_INSTALLED:-$LOADER_DIR/vcpkg_installed}"
VCPKG_TREE="$VCPKG_INSTALLED/x64-windows-static"
DEPS_TARBALL="${MINDLESS_DEPS_TARBALL:-$ROOT/mindless-windows-deps.tar.zst}"

# Only the headers are used, and only for jni.h and include/win32/jvmti.h. A Linux JDK ships
# include/linux instead, and its jni_md.h declares JNIEXPORT the ELF way, so a Windows JDK
# has to supply them even though nothing here ever runs it.
WIN_JDK_URL="${MINDLESS_WIN_JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse}"

JOBS="$(nproc 2>/dev/null || echo 4)"
PROD=0
DEV_TARGET=0
CLIENT_ONLY=0
LOADER_ONLY=0
DEPS_ONLY=0
PACK_DEPS=0

RESET=$'\033[0m'; BOLD=$'\033[1m'; GREEN=$'\033[92m'
YELLOW=$'\033[93m'; RED=$'\033[91m'; CYAN=$'\033[96m'
ok()      { printf '  %s[+]%s %s\n' "$GREEN" "$RESET" "$*"; }
warn()    { printf '  %s[!]%s %s\n' "$YELLOW" "$RESET" "$*"; }
die()     { printf '  %s[x]%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }
info()    { printf '  %s[>]%s %s\n' "$CYAN" "$RESET" "$*"; }
section() { printf '\n%s%s%s\n' "$BOLD" "$*" "$RESET"; }

for arg in "$@"; do
    case "$arg" in
        --prod)        PROD=1 ;;
        --dev)         DEV_TARGET=1 ;;
        --client-only) CLIENT_ONLY=1 ;;
        --loader-only) LOADER_ONLY=1 ;;
        --deps)        DEPS_ONLY=1 ;;
        --pack-deps)   PACK_DEPS=1 ;;
        -h|--help)     sed -n '3,40p' "${BASH_SOURCE[0]}" | grep '^#' | sed 's/^# \?//'; exit 0 ;;
        *)             die "unknown option: $arg" ;;
    esac
done

printf '\n%s%s%s\n' "$BOLD" "==================================================" "$RESET"
printf '%s  Mindless United - cross build (linux -> windows x64)%s\n' "$BOLD" "$RESET"
printf '%s%s%s\n' "$BOLD" "==================================================" "$RESET"

if [ "$PACK_DEPS" -eq 1 ]; then
    section "Packing the Windows dependency tree"
    [ -d "$VCPKG_TREE/lib" ] || die "nothing to pack, $VCPKG_TREE does not exist"
    compressor=(zstd -19 -T0); suffix=".tar.zst"
    command -v zstd >/dev/null 2>&1 || { compressor=(gzip -9); suffix=".tar.gz"; DEPS_TARBALL="${DEPS_TARBALL%.tar.zst}.tar.gz"; }
    tar -C "$VCPKG_INSTALLED/.." -cf - "$(basename "$VCPKG_INSTALLED")" | "${compressor[@]}" > "$DEPS_TARBALL"
    ok "wrote $DEPS_TARBALL ($(( $(stat -c%s "$DEPS_TARBALL" 2>/dev/null || stat -f%z "$DEPS_TARBALL") / 1048576 )) MB)"
    info "drop it in the repo root on the Linux box and build.sh unpacks it itself"
    exit 0
fi

# ---------------------------------------------------------------------------
# Toolchain
# ---------------------------------------------------------------------------
section "Detecting tools"

missing=()
for tool in clang-cl lld-link llvm-rc llvm-lib cmake ninja git curl unzip; do
    if command -v "$tool" >/dev/null 2>&1; then
        ok "$tool"
    else
        warn "$tool not found"
        missing+=("$tool")
    fi
done

if [ "${#missing[@]}" -ne 0 ]; then
    if command -v pacman >/dev/null 2>&1; then
        die "install them first:  sudo pacman -S --needed llvm clang lld cmake ninja git curl unzip"
    fi
    die "missing required tools: ${missing[*]}"
fi

if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-temurin; do
        [ -x "$candidate/bin/javac" ] && { export JAVA_HOME="$candidate"; break; }
    done
fi
[ -n "${JAVA_HOME:-}" ] || die "set JAVA_HOME to a JDK 17 (pacman -S jdk17-openjdk)"
[ -x "$JAVA_HOME/bin/javac" ] || die "JAVA_HOME has no javac: $JAVA_HOME"
java_major="$("$JAVA_HOME/bin/javac" -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')"
[ "$java_major" = "17" ] || warn "JAVA_HOME is Java $java_major, the build expects 17"
ok "JAVA_HOME = $JAVA_HOME"

mkdir -p "$CACHE"

# --- Windows SDK and MSVC CRT ------------------------------------------------
if [ ! -d "$XWIN_ROOT/crt/include" ]; then
    section "Windows SDK - fetch"
    command -v xwin >/dev/null 2>&1 || die "xwin not found. Install it: cargo install xwin  (or pacman -S xwin from the AUR)"
    info "downloading the MSVC CRT and Windows SDK into $XWIN_ROOT"
    tmp="$(mktemp -d)"
    xwin --accept-license --arch x86_64 --cache-dir "$tmp" splat --output "$XWIN_ROOT"
    rm -rf "$tmp"
    ok "sysroot ready"
else
    ok "Windows SDK cached at $XWIN_ROOT"
fi
export XWIN_ROOT

# --- Windows JDK headers -----------------------------------------------------
if [ ! -f "$WIN_JDK/include/win32/jvmti.h" ]; then
    section "Windows JDK headers - fetch"
    info "the native DLL includes jni.h and include/win32/jvmti.h, which a Linux JDK does not ship"
    tmp="$(mktemp -d)"
    curl -fL --retry 3 -o "$tmp/jdk.zip" "$WIN_JDK_URL"
    unzip -q "$tmp/jdk.zip" -d "$tmp/x"
    extracted="$(find "$tmp/x" -maxdepth 1 -mindepth 1 -type d | head -n1)"
    [ -n "$extracted" ] || die "the Windows JDK archive did not unpack as expected"
    rm -rf "$WIN_JDK"
    mkdir -p "$(dirname "$WIN_JDK")"
    mv "$extracted" "$WIN_JDK"
    rm -rf "$tmp"
    [ -f "$WIN_JDK/include/win32/jvmti.h" ] || die "no include/win32/jvmti.h in the downloaded JDK"
    ok "headers ready"
else
    ok "Windows JDK headers cached at $WIN_JDK"
fi

# --- Windows dependency tree -------------------------------------------------
# Not built here. vcpkg's OpenSSL port for a Windows triplet drives nmake, which does not
# exist on Linux, and OpenSSL cannot be dropped: shared/authsdk/cpp/src/crypto.cpp calls
# EVP, HMAC and RAND straight from it. Since clang-cl emits MSVC-ABI objects on either host,
# the .lib files a Windows build already produced link here without being rebuilt.
if [ ! -f "$VCPKG_TREE/lib/libcrypto.lib" ]; then
    if [ -f "$DEPS_TARBALL" ]; then
        section "Windows dependencies - unpack"
        mkdir -p "$(dirname "$VCPKG_INSTALLED")"
        tar -C "$(dirname "$VCPKG_INSTALLED")" -xf "$DEPS_TARBALL"
        ok "unpacked $DEPS_TARBALL"
    fi
fi
if [ ! -f "$VCPKG_TREE/lib/libcrypto.lib" ]; then
    printf '\n'
    die "$(cat <<EOF
no prebuilt Windows dependencies at $VCPKG_TREE

They cannot be built on Linux: vcpkg's OpenSSL port for a Windows triplet drives nmake,
and the bundled auth SDK needs OpenSSL, so it cannot simply be left out.

Produce them once on the Windows machine, where the normal build already does it:

    python tools/build.py          (or build.bat)
    bash build.sh --pack-deps      writes mindless-windows-deps.tar.zst

Copy that file to this repo root and re-run. clang-cl targets the same ABI on both
hosts, so the libraries link unchanged.
EOF
)"
fi
for lib in libcrypto libssl libcurl freetype; do
    [ -f "$VCPKG_TREE/lib/$lib.lib" ] || die "$lib.lib missing from $VCPKG_TREE/lib"
done
ok "Windows dependencies at $VCPKG_TREE"

TOOLCHAIN="$CROSS_DIR/windows-clang-cl.cmake"
[ -f "$TOOLCHAIN" ] || die "missing $TOOLCHAIN"

if [ "$DEPS_ONLY" -eq 1 ]; then
    section "Done"
    ok "toolchain cached, nothing built"
    exit 0
fi

# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------
if [ "$LOADER_ONLY" -eq 0 ]; then
    section "Client - gradle build"
    chmod +x "$GRADLEW" 2>/dev/null || true
    ( cd "$CLIENT_DIR" && ./gradlew build lunarPayloadJar \
        -x test -x compileTestJava \
        --parallel --build-cache --warning-mode=none "--max-workers=$JOBS" )
    [ -s "$FORGE_JAR" ] || die "Forge jar missing after the build: $FORGE_JAR"
    [ -s "$LUNAR_JAR" ] || die "Lunar jar missing after the build: $LUNAR_JAR"
    ok "client built"

    if [ "$PROD" -eq 1 ]; then
        section "Building MindlessObf"
        # tools/obf has no wrapper of its own; build.py runs the client's from that directory.
        ( cd "$OBF_DIR" && "$GRADLEW" jar --warning-mode=none )
        [ -s "$OBF_JAR" ] || die "MindlessObf jar missing: $OBF_JAR"
        ok "MindlessObf built"

        section "JAR obfuscation"
        mkdir -p "$(dirname "$FORGE_MAPPING")"
        for pair in "forge:$FORGE_JAR:$FORGE_JAR_OBF:$FORGE_MAPPING" \
                    "lunar:$LUNAR_JAR:$LUNAR_JAR_OBF:$LUNAR_MAPPING"; do
            IFS=: read -r label input output mapping <<<"$pair"
            info "obfuscating $label"
            rm -f "$output"
            "$JAVA_HOME/bin/java" -jar "$OBF_JAR" "$input" "$output" --mapping "$mapping"
            [ -s "$output" ] || die "$label obfuscation produced nothing"
            ok "$label obfuscated ($(( $(stat -c%s "$output") / 1024 )) KB)"
        done
    fi
fi

if [ "$CLIENT_ONLY" -eq 1 ]; then
    section "Done"
    ok "client only, native and loader skipped"
    exit 0
fi

# ---------------------------------------------------------------------------
# MindlessNative.dll
# ---------------------------------------------------------------------------
section "MindlessNative.dll - build"

if [ "$PROD" -eq 1 ] && [ -s "$FORGE_JAR_OBF" ]; then
    payload_forge="$FORGE_JAR_OBF"; ok "using the obfuscated Forge jar"
else
    payload_forge="$FORGE_JAR"
fi
if [ "$PROD" -eq 1 ] && [ -s "$LUNAR_JAR_OBF" ]; then
    payload_lunar="$LUNAR_JAR_OBF"; ok "using the obfuscated Lunar jar"
else
    payload_lunar="$LUNAR_JAR"
fi
[ -s "$payload_forge" ] || die "Forge payload missing, run without --loader-only first"
[ -s "$payload_lunar" ] || die "Lunar payload missing, run without --loader-only first"

# The resource script embeds the payload jars, so a stale .res silently ships the old client.
find "$NATIVE_BUILD_DIR" -name 'payload.rc.res' -delete 2>/dev/null || true
rm -f "$NATIVE_DLL_OUT"

cmake -S "$NATIVE_DIR" -B "$NATIVE_BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DXWIN_ROOT="$XWIN_ROOT" \
    -DCMAKE_BUILD_TYPE=Release \
    -DMINDLESS_JAVA_HOME="$WIN_JDK" \
    -DMINDLESS_FORGE_PAYLOAD_JAR="$payload_forge" \
    -DMINDLESS_LUNAR_PAYLOAD_JAR="$payload_lunar" \
    -DMINDLESS_PRODUCTION="$([ "$PROD" -eq 1 ] && echo ON || echo OFF)" \
    -DMINDLESS_DEBUG_LOGS="$([ "${MINDLESS_DEBUG_LOGS:-0}" = "1" ] && echo ON || echo OFF)"

cmake --build "$NATIVE_BUILD_DIR" --config Release --parallel "$JOBS"
[ -s "$NATIVE_DLL_OUT" ] || die "MindlessNative.dll not produced"
mkdir -p "$(dirname "$LOADER_RUNTIME")"
cp -f "$NATIVE_DLL_OUT" "$LOADER_RUNTIME"
ok "MindlessNative.dll -> $LOADER_RUNTIME"

# ---------------------------------------------------------------------------
# Loader
# ---------------------------------------------------------------------------
section "Loader - configure"

# Not `cmake --preset windows-clang`: that preset is guarded on a Windows host and pins
# absolute Windows tool paths. The cache variables it would have set are passed here instead,
# so CMakePresets.json stays exactly as the Windows build left it.
cmake -S "$LOADER_DIR" -B "$LOADER_BUILD_DIR" -G "Ninja Multi-Config" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_PREFIX_PATH="$VCPKG_TREE" \
    -DXWIN_ROOT="$XWIN_ROOT" \
    -DMINDLESS_PRODUCTION="$([ "$PROD" -eq 1 ] && echo ON || echo OFF)" \
    -DMINDLESS_PRIVATE_PDB="$([ "$PROD" -eq 1 ] && echo ON || echo OFF)"
ok "configured"

section "Loader - build"
if [ "$DEV_TARGET" -eq 1 ]; then
    target="MindlessDev"; exe_name="dev.exe"; output="$ROOT/dev.exe"
else
    target="MindlessLoader"; exe_name="MindlessLoader.exe"; output="$OUTPUT_EXE"
fi

rm -f "$LOADER_BUILD_DIR/resources_gen.res" "$LOADER_BUILD_DIR/Release/$exe_name"
cmake --build "$LOADER_BUILD_DIR" --config Release --target "$target" --parallel "$JOBS"

built=""
for candidate in "$LOADER_DIR/$exe_name" "$LOADER_BUILD_DIR/Release/$exe_name" "$LOADER_BUILD_DIR/$exe_name"; do
    [ -s "$candidate" ] && { built="$candidate"; break; }
done
[ -n "$built" ] || die "$exe_name not found after the build"
cp -f "$built" "$output"
ok "EXE -> $output"

# ---------------------------------------------------------------------------
section "Done"
ok "$(basename "$output")   $(( $(stat -c%s "$output") / 1024 )) KB"
ok "MindlessNative.dll      $(( $(stat -c%s "$NATIVE_DLL_OUT") / 1024 )) KB"
if [ "$PROD" -eq 1 ]; then
    [ -s "$FORGE_JAR_OBF" ] && ok "Forge jar (obfuscated)  $(( $(stat -c%s "$FORGE_JAR_OBF") / 1024 )) KB"
    [ -s "$LUNAR_JAR_OBF" ] && ok "Lunar jar (obfuscated)  $(( $(stat -c%s "$LUNAR_JAR_OBF") / 1024 )) KB"
else
    ok "Forge jar               $(( $(stat -c%s "$FORGE_JAR") / 1024 )) KB"
    ok "Lunar jar               $(( $(stat -c%s "$LUNAR_JAR") / 1024 )) KB"
fi
printf '\n'
