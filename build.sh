#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CLIENT_DIR="$ROOT/client"
BOOTSTRAPPER_DIR="$ROOT/bootstrapper"
BOOTSTRAPPER_BUILD="$BOOTSTRAPPER_DIR/build"
TUI_DIR="$ROOT/loader/tui"
TUI_BUILD="$TUI_DIR/build"

# Prefer clang
CC="${CC:-clang}"
CXX="${CXX:-clang++}"
export CC CXX

BOLD='\033[1m'
GREEN='\033[92m'
RED='\033[91m'
CYAN='\033[96m'
RESET='\033[0m'

ok()   { echo -e "  ${GREEN}[+]${RESET} $1"; }
err()  { echo -e "  ${RED}[x]${RESET} $1"; }
info() { echo -e "  ${CYAN}[>]${RESET} $1"; }

echo -e "\n${BOLD}==========================================${RESET}"
echo -e "${BOLD}  Mindless United - Linux Build${RESET}"
echo -e "${BOLD}==========================================\n${RESET}"

# ---- Detect JAVA_HOME ----
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(which javac 2>/dev/null || which java 2>/dev/null)")")")
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    err "JAVA_HOME not set or missing jni.h. Export JAVA_HOME=<jdk-path>."
    exit 1
fi
ok "JAVA_HOME: $JAVA_HOME"

# ---- Build client (Gradle) ----
build_client() {
    echo -e "\n${BOLD}Client - gradle build${RESET}"
    cd "$CLIENT_DIR"
    chmod +x gradlew
    ./gradlew build lunarPayloadJar -x test -x compileTestJava --parallel --build-cache --warning-mode=none
    ok "client built"
}

# ---- Build bootstrapper (.so + injector) ----
build_bootstrapper() {
    echo -e "\n${BOLD}Bootstrapper - cmake build${RESET}"

    FORGE_JAR="$CLIENT_DIR/build/libs/mindless.jar"
    LUNAR_JAR="$CLIENT_DIR/build/intermediates/mindless-lunar-mcp-with-forge.jar"

    if [ ! -f "$FORGE_JAR" ]; then
        err "Forge JAR missing: $FORGE_JAR (run client build first)"
        return 1
    fi
    if [ ! -f "$LUNAR_JAR" ]; then
        err "Lunar JAR missing: $LUNAR_JAR (run client build first)"
        return 1
    fi

    mkdir -p "$BOOTSTRAPPER_BUILD"
    cmake -S "$BOOTSTRAPPER_DIR" -B "$BOOTSTRAPPER_BUILD" \
        -DCMAKE_C_COMPILER="$CC" \
        -DRAVEN_JAVA_HOME="$JAVA_HOME" \
        -DRAVEN_FORGE_PAYLOAD_JAR="$FORGE_JAR" \
        -DRAVEN_LUNAR_PAYLOAD_JAR="$LUNAR_JAR" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$BOOTSTRAPPER_BUILD" --config Release --parallel "$(nproc)"
    ok "bootstrapper built"

    if [ -f "$BOOTSTRAPPER_BUILD/dist/RavenNative.so" ]; then
        ok "RavenNative.so -> $BOOTSTRAPPER_BUILD/dist/RavenNative.so"
    fi
    if [ -f "$BOOTSTRAPPER_BUILD/dist/RavenInjector" ]; then
        ok "RavenInjector  -> $BOOTSTRAPPER_BUILD/dist/RavenInjector"
    fi
}

# ---- Build TUI loader ----
build_loader() {
    echo -e "\n${BOLD}TUI Loader - cmake build${RESET}"
    mkdir -p "$TUI_BUILD"
    cmake -S "$TUI_DIR" -B "$TUI_BUILD" \
        -DCMAKE_CXX_COMPILER="$CXX" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$TUI_BUILD" --config Release --parallel "$(nproc)"
    ok "TUI loader built"

    if [ -f "$TUI_BUILD/MindlessLoaderTUI" ]; then
        cp "$TUI_BUILD/MindlessLoaderTUI" "$ROOT/MindlessLoaderTUI"
        ok "MindlessLoaderTUI -> $ROOT/MindlessLoaderTUI"
    fi
}

# ---- Main ----
case "${1:-all}" in
    client)       build_client ;;
    bootstrapper) build_bootstrapper ;;
    loader)       build_loader ;;
    all)          build_client && build_bootstrapper && build_loader ;;
    *)
        echo "Usage: $0 [client|bootstrapper|loader|all]"
        exit 1
        ;;
esac

echo -e "\n${BOLD}${GREEN}Done.${RESET}"
