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
#   ./build.sh --prod          production build, what ships
#   ./build.sh --client-only   Gradle stages only
#   ./build.sh --loader-only   native DLL and loader only, reusing existing jars
#   ./build.sh --deps          fetch the toolchain and third-party sources, build nothing
#
# Missing host packages are installed through the detected package manager. Set
# MINDLESS_AUTO_INSTALL=0 to require manual provisioning instead.
#
# Caches live under ~/.cache/mindless unless MINDLESS_CACHE says otherwise, so a second run
# does not re-download the SDK.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$ROOT/client"
NATIVE_DIR="$CLIENT_DIR/native"
LOADER_DIR="$ROOT/loader"
CROSS_DIR="$ROOT/tools/cross"

GRADLEW="$CLIENT_DIR/gradlew"
FORGE_JAR="$CLIENT_DIR/build/libs/mindless.jar"
LUNAR_JAR="$CLIENT_DIR/build/intermediates/mindless-lunar-mcp-with-forge.jar"
NATIVE_DLL_OUT="$CLIENT_DIR/native_build/dist/MindlessNative.dll"
LOADER_RUNTIME="$LOADER_DIR/assets/runtime/MindlessNative.dll"
OUTPUT_EXE="$ROOT/MindlessLoader.exe"

CACHE="${MINDLESS_CACHE:-$HOME/.cache/mindless}"
BUILD_CACHE="${MINDLESS_BUILD_CACHE:-$CACHE/build}"
NATIVE_BUILD_DIR="$BUILD_CACHE/native"
LOADER_BUILD_DIR="$BUILD_CACHE/loader"
NATIVE_BUILT_DLL="$NATIVE_BUILD_DIR/dist/MindlessNative.dll"
GRADLE_PROJECT_CACHE="${MINDLESS_GRADLE_PROJECT_CACHE:-$CACHE/gradle-project}"
XWIN_ROOT="${XWIN_ROOT:-$CACHE/xwin}"
WIN_JDK="${MINDLESS_WIN_JDK:-$CACHE/jdk-win}"
FETCHCONTENT_BASE_DIR="${MINDLESS_FETCHCONTENT_DIR:-$CACHE/sources}"

# Only the headers are used: include/jni.h, include/jvmti.h, and the Windows-specific
# include/win32/jni_md.h. A Linux JDK's jni_md.h declares JNIEXPORT the ELF way, so a
# Windows JDK has to supply them even though nothing here ever runs it.
WIN_JDK_URL="${MINDLESS_WIN_JDK_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse}"

JOBS="$(nproc 2>/dev/null || echo 4)"
AUTO_INSTALL="${MINDLESS_AUTO_INSTALL:-1}"
PROD=0
DEV_TARGET=0
CLIENT_ONLY=0
LOADER_ONLY=0
DEPS_ONLY=0

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
        -h|--help)     sed -n '3,40p' "${BASH_SOURCE[0]}" | grep '^#' | sed 's/^# \?//'; exit 0 ;;
        *)             die "unknown option: $arg" ;;
    esac
done

printf '\n%s%s%s\n' "$BOLD" "==================================================" "$RESET"
printf '%s  Mindless United - cross build (linux -> windows x64)%s\n' "$BOLD" "$RESET"
printf '%s%s%s\n' "$BOLD" "==================================================" "$RESET"

# ---------------------------------------------------------------------------
# Toolchain
# ---------------------------------------------------------------------------
section "Detecting tools"

run_root() {
    if [ "${EUID:-$(id -u)}" -eq 0 ]; then
        "$@"
    else
        die "cannot install host packages on a non-root runner without sudo; provision the missing packages in the runner image or set MINDLESS_AUTO_INSTALL=0"
    fi
}

add_llvm_paths() {
    local llvm_dir
    for llvm_dir in /usr/lib/llvm-*/bin /usr/local/opt/llvm/bin /opt/homebrew/opt/llvm/bin; do
        [ -d "$llvm_dir" ] || continue
        case ":$PATH:" in
            *":$llvm_dir:"*) ;;
            *) PATH="$llvm_dir:$PATH"; export PATH ;;
        esac
    done
}

add_cargo_path() {
    local cargo_bin="${CARGO_HOME:-$HOME/.cargo}/bin"
    if [ -x "$cargo_bin" ]; then
        case ":$PATH:" in
            *":$cargo_bin:"*) ;;
            *) PATH="$cargo_bin:$PATH"; export PATH ;;
        esac
    fi
}

install_packages() {
    local manager="$1"
    shift
    local packages=("$@")
    [ "$AUTO_INSTALL" = 1 ] || die "missing tools: ${missing[*]} (automatic installation disabled by MINDLESS_AUTO_INSTALL=0)"
    section "Installing host dependencies"
    info "$manager: ${packages[*]}"
    case "$manager" in
        apt-get)
            run_root apt-get update
            run_root env DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"
            ;;
        dnf)    run_root dnf install -y "${packages[@]}" ;;
        yum)    run_root yum install -y "${packages[@]}" ;;
        pacman) run_root pacman -Sy --needed --noconfirm "${packages[@]}" ;;
        zypper) run_root zypper --non-interactive install --no-recommends "${packages[@]}" ;;
        apk)    run_root apk add --no-cache "${packages[@]}" ;;
        brew)   brew install "${packages[@]}" ;;
        *)      die "no supported package manager found; install: ${packages[*]}" ;;
    esac
}

package_manager=""
for candidate in apt-get dnf yum pacman zypper apk brew; do
    if command -v "$candidate" >/dev/null 2>&1; then
        package_manager="$candidate"
        break
    fi
done

add_llvm_paths
add_cargo_path
missing=()
for tool in clang-cl lld-link llvm-rc llvm-lib llvm-mt cmake ninja git curl unzip perl pkg-config; do
    if command -v "$tool" >/dev/null 2>&1; then
        ok "$tool"
    else
        warn "$tool not found"
        missing+=("$tool")
    fi
done

if [ "${#missing[@]}" -ne 0 ]; then
    case "$package_manager" in
        apt-get) packages=(llvm clang lld cmake ninja-build git curl unzip perl pkg-config cargo ca-certificates xz-utils) ;;
        dnf|yum) packages=(llvm clang lld cmake ninja-build git curl unzip perl pkgconf-pkg-config cargo ca-certificates xz) ;;
        pacman)  packages=(llvm clang lld cmake ninja git curl unzip perl pkgconf rust ca-certificates xz) ;;
        zypper)  packages=(llvm clang lld cmake ninja git curl unzip perl pkg-config cargo ca-certificates xz) ;;
        apk)     packages=(llvm clang lld cmake ninja git curl unzip perl pkgconf cargo ca-certificates xz) ;;
        brew)    packages=(llvm lld cmake ninja git curl unzip perl pkg-config rust ca-certificates xz) ;;
        *)       packages=(llvm clang lld cmake ninja git curl unzip perl pkg-config cargo ca-certificates xz) ;;
    esac
    install_packages "$package_manager" "${packages[@]}"
    add_llvm_paths
fi

missing=()
for tool in clang-cl lld-link llvm-rc llvm-lib llvm-mt cmake ninja git curl unzip perl pkg-config; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
[ "${#missing[@]}" -eq 0 ] || die "required tools are still missing after installation: ${missing[*]}"

if ! command -v xwin >/dev/null 2>&1; then
    if ! command -v cargo >/dev/null 2>&1; then
        case "$package_manager" in
            apt-get|dnf|yum|zypper|apk) packages=(cargo) ;;
            pacman|brew) packages=(rust) ;;
            *) packages=(cargo) ;;
        esac
        install_packages "$package_manager" "${packages[@]}"
    fi
    command -v cargo >/dev/null 2>&1 || die "cargo is required to install xwin"
    [ "$AUTO_INSTALL" = 1 ] || die "xwin not found (automatic installation disabled by MINDLESS_AUTO_INSTALL=0)"
    section "Installing xwin"
    cargo install --locked xwin
    add_cargo_path
fi

find_java_home() {
    local javac_path javac_real
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        return 0
    fi
    if javac_path="$(command -v javac 2>/dev/null)"; then
        javac_real="$(readlink -f "$javac_path" 2>/dev/null || printf '%s' "$javac_path")"
        JAVA_HOME="${javac_real%/bin/javac}"
        [ -x "$JAVA_HOME/bin/javac" ] && { export JAVA_HOME; return 0; }
    fi
    return 1
}

if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/javac" ]; then
    warn "JAVA_HOME has no javac: $JAVA_HOME"
    unset JAVA_HOME
fi
if ! find_java_home; then
    for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-temurin; do
        [ -x "$candidate/bin/javac" ] && { export JAVA_HOME="$candidate"; break; }
    done
fi
[ -n "${JAVA_HOME:-}" ] || {
    case "$package_manager" in
        apt-get) packages=(temurin-17-jdk) ;;
        dnf|yum) packages=(java-17-openjdk-devel) ;;
        pacman)  packages=(jdk17-openjdk) ;;
        zypper)  packages=(java-17-openjdk-devel) ;;
        apk)     packages=(openjdk17-jdk) ;;
        brew)    packages=(openjdk@17) ;;
        *)       packages=(openjdk-17) ;;
    esac
    install_packages "$package_manager" "${packages[@]}"
    for candidate in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-temurin \
                     /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/jdk-17; do
        [ -x "$candidate/bin/javac" ] && { export JAVA_HOME="$candidate"; break; }
    done
}
[ -n "${JAVA_HOME:-}" ] || die "Java 17 was not found after installation; set JAVA_HOME manually"
[ -x "$JAVA_HOME/bin/javac" ] || die "JAVA_HOME has no javac: $JAVA_HOME"
java_major="$("$JAVA_HOME/bin/javac" -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')"
[ "$java_major" = "17" ] || warn "JAVA_HOME is Java $java_major, the build expects 17"
ok "JAVA_HOME = $JAVA_HOME"

mkdir -p "$CACHE"
mkdir -p "$BUILD_CACHE" "$GRADLE_PROJECT_CACHE/client"

# --- Windows SDK and MSVC CRT ------------------------------------------------
# xwin moves extracted files into its output tree. Keep its cache and staged output beside
# the final sysroot so that rename(2) never has to cross from /tmp onto another filesystem.
# Check representative files rather than a directory because an interrupted splat leaves
# the directory structure behind.
if [ ! -f "$XWIN_ROOT/crt/include/xloctime" ] ||
   [ ! -f "$XWIN_ROOT/crt/lib/x86_64/libcmt.lib" ] ||
   [ ! -f "$XWIN_ROOT/sdk/lib/um/x86_64/kernel32.lib" ]; then
    section "Windows SDK - fetch"
    command -v xwin >/dev/null 2>&1 || die "xwin is unavailable after installation; run: cargo install --locked xwin"
    info "downloading the MSVC CRT and Windows SDK into $XWIN_ROOT"
    xwin_parent="$(dirname "$XWIN_ROOT")"
    mkdir -p "$xwin_parent"
    tmp="$(mktemp -d "$xwin_parent/.xwin-fetch.XXXXXX")"
    if ! xwin --accept-license --arch x86_64 --cache-dir "$tmp/cache" splat --output "$tmp/sysroot"; then
        rm -rf "$tmp"
        die "xwin failed to fetch the Windows SDK"
    fi
    rm -rf "$XWIN_ROOT"
    mv "$tmp/sysroot" "$XWIN_ROOT"
    rm -rf "$tmp"
    ok "sysroot ready"
else
    ok "Windows SDK cached at $XWIN_ROOT"
fi
export XWIN_ROOT

# --- Windows JDK headers -----------------------------------------------------
if [ ! -f "$WIN_JDK/include/jni.h" ] ||
   [ ! -f "$WIN_JDK/include/jvmti.h" ] ||
   [ ! -f "$WIN_JDK/include/win32/jni_md.h" ]; then
    section "Windows JDK headers - fetch"
    info "the native DLL needs Windows JDK headers, including include/win32/jni_md.h"
    tmp="$(mktemp -d)"
    curl -fL --retry 3 -o "$tmp/jdk.zip" "$WIN_JDK_URL"
    unzip -q "$tmp/jdk.zip" -d "$tmp/x"
    extracted="$(find "$tmp/x" -maxdepth 1 -mindepth 1 -type d | head -n1)"
    [ -n "$extracted" ] || die "the Windows JDK archive did not unpack as expected"
    rm -rf "$WIN_JDK"
    mkdir -p "$(dirname "$WIN_JDK")"
    mv "$extracted" "$WIN_JDK"
    rm -rf "$tmp"
    [ -f "$WIN_JDK/include/jni.h" ] || die "no include/jni.h in the downloaded JDK"
    [ -f "$WIN_JDK/include/jvmti.h" ] || die "no include/jvmti.h in the downloaded JDK"
    [ -f "$WIN_JDK/include/win32/jni_md.h" ] || die "no include/win32/jni_md.h in the downloaded JDK"
    ok "headers ready"
else
    ok "Windows JDK headers cached at $WIN_JDK"
fi

TOOLCHAIN="$CROSS_DIR/windows-clang-cl.cmake"
[ -f "$TOOLCHAIN" ] || die "missing $TOOLCHAIN"

configure_loader() {
    cmake -S "$LOADER_DIR" -B "$LOADER_BUILD_DIR" -G "Ninja Multi-Config" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DFETCHCONTENT_BASE_DIR="$FETCHCONTENT_BASE_DIR" \
        -DMINDLESS_FETCH_DEPS=ON \
        -DXWIN_ROOT="$XWIN_ROOT" \
        -DMINDLESS_PRODUCTION="$([ "$PROD" -eq 1 ] && echo ON || echo OFF)" \
        -DMINDLESS_PRIVATE_PDB=OFF
}

if [ "$DEPS_ONLY" -eq 1 ]; then
    section "Windows dependencies - fetch"
    configure_loader
    section "Done"
    ok "toolchain and dependency sources cached, nothing built"
    exit 0
fi

# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------
if [ "$LOADER_ONLY" -eq 0 ]; then
    section "Client - gradle build"
    chmod +x "$GRADLEW" 2>/dev/null || true
    ( cd "$CLIENT_DIR" && ./gradlew remapJar lunarPayloadJar \
        --parallel --build-cache --warning-mode=none "--max-workers=$JOBS" \
        --project-cache-dir "$GRADLE_PROJECT_CACHE/client" )
    [ -s "$FORGE_JAR" ] || die "Forge jar missing after the build: $FORGE_JAR"
    [ -s "$LUNAR_JAR" ] || die "Lunar jar missing after the build: $LUNAR_JAR"
    ok "client built"
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

payload_forge="$FORGE_JAR"
payload_lunar="$LUNAR_JAR"
[ -s "$payload_forge" ] || die "Forge payload missing, run without --loader-only first"
[ -s "$payload_lunar" ] || die "Lunar payload missing, run without --loader-only first"

if [ "$DEV_TARGET" -eq 1 ]; then
    target="MindlessDev"; exe_name="dev.exe"; output="$ROOT/dev.exe"
else
    target="MindlessLoader"; exe_name="MindlessLoader.exe"; output="$OUTPUT_EXE"
fi

build_native() {
    find "$NATIVE_BUILD_DIR" -name 'payload.rc.res' -delete 2>/dev/null || true
    rm -f "$NATIVE_BUILT_DLL"
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
    [ -s "$NATIVE_BUILT_DLL" ] || return 1
    mkdir -p "$(dirname "$NATIVE_DLL_OUT")" "$(dirname "$LOADER_RUNTIME")"
    cp -f "$NATIVE_BUILT_DLL" "$NATIVE_DLL_OUT"
    cp -f "$NATIVE_BUILT_DLL" "$LOADER_RUNTIME"
    ok "MindlessNative.dll -> $NATIVE_DLL_OUT"
}

build_loader() {
    configure_loader
    cmake --build "$LOADER_BUILD_DIR" --config Release --target "$target" --parallel "$JOBS"
    local built="" candidate
    for candidate in "$LOADER_DIR/$exe_name" "$LOADER_BUILD_DIR/Release/$exe_name" "$LOADER_BUILD_DIR/$exe_name"; do
        [ -s "$candidate" ] && { built="$candidate"; break; }
    done
    [ -n "$built" ] || return 1
    cp -f "$built" "$output"
    ok "EXE -> $output"
}

section "Native and loader - parallel build"
build_native &
native_pid=$!
build_loader &
loader_pid=$!
build_status=0
wait "$native_pid" || build_status=$?
wait "$loader_pid" || build_status=$?
[ "$build_status" -eq 0 ] || die "native or loader build failed"

# ---------------------------------------------------------------------------
section "Done"
ok "$(basename "$output")   $(( $(stat -c%s "$output") / 1024 )) KB"
ok "MindlessNative.dll      $(( $(stat -c%s "$NATIVE_DLL_OUT") / 1024 )) KB"
ok "Forge jar               $(( $(stat -c%s "$FORGE_JAR") / 1024 )) KB"
ok "Lunar jar               $(( $(stat -c%s "$LUNAR_JAR") / 1024 )) KB"
printf '\n'
