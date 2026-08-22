#!/usr/bin/env python3
import os
import re
import sys
import json
import shutil
import subprocess
import glob
from pathlib import Path

ROOT        = Path(__file__).parent.resolve()
LOADER_DIR  = ROOT / "loader"
CLIENT_DIR  = ROOT / "client"
NATIVE_DIR  = CLIENT_DIR / "native"
PRESET_FILE = LOADER_DIR / "CMakePresets.json"
PRESET_TEMPLATE = LOADER_DIR / "CMakePresets.template.json"
BUILD_DIR   = LOADER_DIR / "out" / "build" / "windows-clang"
OUTPUT_EXE  = ROOT / "MindlessLoader.exe"

FORGE_JAR   = CLIENT_DIR / "build" / "libs" / "mindless.jar"
LUNAR_JAR   = CLIENT_DIR / "build" / "intermediates" / "mindless-lunar-mcp-with-forge.jar"
NATIVE_BUILD_DIR = CLIENT_DIR / "native_build"
NATIVE_DLL_OUT   = NATIVE_BUILD_DIR / "dist" / "RavenNative.dll"

LOADER_RUNTIME   = LOADER_DIR / "assets" / "runtime" / "RavenNative.dll"

VS_ROOTS = [
    r"C:\Program Files\Microsoft Visual Studio",
    r"C:\Program Files (x86)\Microsoft Visual Studio",
]
VS_VARIANTS = ["Community", "Professional", "Enterprise", "BuildTools", "Preview"]

RESET  = "\033[0m"
BOLD   = "\033[1m"
GREEN  = "\033[92m"
YELLOW = "\033[93m"
RED    = "\033[91m"
CYAN   = "\033[96m"

def ok(msg):   print(f"  {GREEN}[+]{RESET} {msg}")
def warn(msg): print(f"  {YELLOW}[!]{RESET} {msg}")
def err(msg):  print(f"  {RED}[x]{RESET} {msg}")
def info(msg): print(f"  {CYAN}[>]{RESET} {msg}")
def section(title): print(f"\n{BOLD}{title}{RESET}")


def find_file(candidates):
    for c in candidates:
        p = Path(c)
        if p.is_file():
            return p
    return None


def find_dir(candidates):
    for c in candidates:
        p = Path(c)
        if p.is_dir():
            return p
    return None


def which(name):
    result = shutil.which(name)
    return Path(result) if result else None


def glob_first(pattern):
    matches = sorted(glob.glob(pattern, recursive=False), reverse=True)
    return Path(matches[0]) if matches else None


def find_in_vs_installs(relative_sub_path):
    """
    Walk every Visual Studio root/year/variant combo that actually exists
    on disk and look for relative_sub_path inside it. Year folders are
    discovered dynamically (not hardcoded), so this works for any VS
    version - 2019, 2022, 2026, whatever comes next.
    Years are checked newest-first.
    """
    for root in VS_ROOTS:
        root_p = Path(root)
        if not root_p.is_dir():
            continue
        year_dirs = sorted(
            (d for d in root_p.iterdir() if d.is_dir()),
            key=lambda d: d.name,
            reverse=True,
        )
        for year_dir in year_dirs:
            for variant in VS_VARIANTS:
                candidate = year_dir / variant / relative_sub_path
                if candidate.is_file():
                    return candidate
    return None


def detect_llvm():
    user = Path.home()
    candidates = [
        user / "Tools" / "LLVM" / "bin" / "clang-cl.exe",
        r"C:\Program Files\LLVM\bin\clang-cl.exe",
        r"C:\LLVM\bin\clang-cl.exe",
        r"C:\Program Files (x86)\LLVM\bin\clang-cl.exe",
    ]
    found = find_file(candidates)
    if not found:
        found = which("clang-cl")
    if not found:
        return None
    lld = found.parent / "lld-link.exe"
    return (found, lld) if lld.is_file() else None


def detect_ninja():
    in_path = which("ninja")
    if in_path:
        return in_path
    sub = r"Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
    return find_in_vs_installs(sub)


def detect_vcpkg():
    user = Path.home()
    candidates = [
        r"C:\vcpkg",
        user / "vcpkg",
        r"C:\dev\vcpkg",
        r"C:\tools\vcpkg",
        user / "scoop" / "apps" / "vcpkg" / "current",
    ]
    found = find_dir(candidates)
    if not found:
        toolchain = which("vcpkg")
        if toolchain:
            found = Path(toolchain).parent
    return found


def detect_cmake():
    in_path = which("cmake")
    if in_path:
        return in_path
    candidates = [
        r"C:\Program Files\CMake\bin\cmake.exe",
        r"C:\Program Files (x86)\CMake\bin\cmake.exe",
    ]
    found = find_file(candidates)
    if found:
        return found
    sub = r"Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    return find_in_vs_installs(sub)


# Roots to crawl looking for javaw.exe. Covers the common vendor install
# locations plus user-level installs (sdkman-on-windows style, scoop, jenv).
JAVA_SEARCH_ROOTS = [
    r"C:\Program Files\Eclipse Adoptium",
    r"C:\Program Files\Java",
    r"C:\Program Files\Microsoft",
    r"C:\Program Files\Amazon Corretto",
    r"C:\Program Files\Zulu",
    r"C:\Program Files\BellSoft",
    r"C:\Program Files (x86)\Java",
    r"C:\Program Files (x86)\Eclipse Adoptium",
]


def _find_javaw_under(root, max_depth=6):
    """os.walk with a depth cap - a plain recursive glob over Program Files
    is slow and JDK installs are never nested more than a few levels deep."""
    found = []
    root_p = Path(root)
    if not root_p.is_dir():
        return found
    base_depth = len(root_p.parts)
    for dirpath, dirnames, filenames in os.walk(root_p):
        depth = len(Path(dirpath).parts) - base_depth
        if depth >= max_depth:
            dirnames[:] = []
            continue
        if "javaw.exe" in filenames:
            found.append(Path(dirpath) / "javaw.exe")
    return found


def _java_home_from_javaw(javaw_path):
    # javaw.exe normally lives in <jdk_home>/bin/javaw.exe
    bin_dir = javaw_path.parent
    home = bin_dir.parent if bin_dir.name.lower() == "bin" else bin_dir
    return home


def _query_java_version(java_home):
    """Runs java -version and parses the major version number.
    Handles both old (1.8.0_301 -> 8) and new (17.0.9 -> 17) schemes."""
    java_exe = java_home / "bin" / "java.exe"
    if not java_exe.is_file():
        return None
    try:
        result = subprocess.run(
            [str(java_exe), "-version"],
            capture_output=True, text=True, timeout=10,
        )
    except (subprocess.SubprocessError, OSError):
        return None
    output = (result.stdout or "") + (result.stderr or "")
    m = re.search(r'version\s+"(\d+)(?:\.(\d+))?', output)
    if not m:
        return None
    major = int(m.group(1))
    if major == 1 and m.group(2):
        major = int(m.group(2))
    return major


def scan_java_installs():
    """
    Finds every javaw.exe reachable under the known install roots (plus
    JAVA_HOME if set), resolves each to a JDK home, and queries its real
    version via `java -version` rather than trusting the folder name.

    Returns a list of dicts: {home, major, has_javac}, deduped by home,
    sorted best-first (real JDK before JRE, higher version first).
    """
    homes = set()

    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        jh = Path(env_home)
        if (jh / "bin" / "javaw.exe").is_file():
            homes.add(jh)

    for root in JAVA_SEARCH_ROOTS:
        for javaw in _find_javaw_under(root):
            homes.add(_java_home_from_javaw(javaw))

    installs = []
    for home in homes:
        major = _query_java_version(home)
        if major is None:
            continue
        has_javac = (home / "bin" / "javac.exe").is_file()
        installs.append({"home": home, "major": major, "has_javac": has_javac})

    installs.sort(key=lambda i: (i["has_javac"], i["major"]), reverse=True)
    return installs


def find_jdk(installs, major=None):
    """Pick the best install from scan_java_installs(). If major is given,
    prefer an exact version match (still preferring a real JDK over a bare
    JRE); otherwise just return the best available."""
    if major is not None:
        exact = [i for i in installs if i["major"] == major]
        if exact:
            exact.sort(key=lambda i: i["has_javac"], reverse=True)
            return exact[0]["home"]
        return None
    return installs[0]["home"] if installs else None


def run(cmd, cwd, env=None):
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    result = subprocess.run(cmd, cwd=str(cwd), env=merged_env)
    return result.returncode == 0


def update_preset(clang, lld, ninja, vcpkg):
    # The preset carries absolute toolchain paths, so it is not tracked -- it used to flip back
    # and forth in every commit as each contributor rebuilt. A fresh clone seeds it from the
    # template instead, and every path below is overwritten anyway.
    source = PRESET_FILE if PRESET_FILE.is_file() else PRESET_TEMPLATE
    if not source.is_file():
        err(f"{source.name} not found in loader/")
        sys.exit(1)
    with open(source, "r", encoding="utf-8-sig") as f:
        data = json.load(f)
    toolchain = str(vcpkg / "scripts" / "buildsystems" / "vcpkg.cmake").replace("\\", "/")
    for preset in data.get("configurePresets", []):
        if preset.get("name") == "windows-clang":
            cv = preset.setdefault("cacheVariables", {})
            cv["CMAKE_C_COMPILER"]     = str(clang).replace("\\", "/")
            cv["CMAKE_CXX_COMPILER"]   = str(clang).replace("\\", "/")
            cv["CMAKE_LINKER"]         = str(lld).replace("\\", "/")
            cv["CMAKE_MAKE_PROGRAM"]   = str(ninja).replace("\\", "/")
            cv["CMAKE_TOOLCHAIN_FILE"] = toolchain
    with open(PRESET_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=4)
        f.write("\n")


def build_client(jdk17):
    section("Client - gradle build")
    gradlew = CLIENT_DIR / "gradlew.bat"
    if not gradlew.is_file():
        err("gradlew.bat not found in client/")
        return False
    env = {}
    if jdk17:
        env["JAVA_HOME"] = str(jdk17)
        info(f"JAVA_HOME = {jdk17}")
    cmd = [str(gradlew), "build", "lunarPayloadJar", "-x", "test", "-x", "compileTestJava"]
    if not run(cmd, CLIENT_DIR, env):
        err("gradle build failed")
        return False
    ok("client built")
    if FORGE_JAR.is_file():
        ok(f"Forge JAR  -> {FORGE_JAR}")
    if LUNAR_JAR.is_file():
        ok(f"Lunar JAR  -> {LUNAR_JAR}")
    return True


def build_native_dll(cmake, clang, ninja, jdk):
    section("RavenNative.dll - build")

    if not FORGE_JAR.is_file():
        err(f"Forge JAR missing: {FORGE_JAR}")
        err("Run client build first.")
        return False
    if not LUNAR_JAR.is_file():
        err(f"Lunar JAR missing: {LUNAR_JAR}")
        err("Run client build first.")
        return False

    NATIVE_BUILD_DIR.mkdir(parents=True, exist_ok=True)

    # Force Ninja to re-embed the JAR by removing the compiled resource artifact
    # and the output DLL. Ninja only tracks .rc file timestamps, not the content
    # of files referenced inside .rc (the JAR). Simply touching payload.rc is
    # unreliable because configure_file may write the same bytes without changing
    # the mtime. Deleting the .res and output DLL guarantees a full recompile.
    for res_file in NATIVE_BUILD_DIR.rglob("payload.rc.res"):
        res_file.unlink(missing_ok=True)
    dist_dll = NATIVE_BUILD_DIR / "dist" / "RavenNative.dll"
    if dist_dll.is_file():
        dist_dll.unlink()

    cfg_cmd = [
        str(cmake), "-S", str(NATIVE_DIR), "-B", str(NATIVE_BUILD_DIR),
        "-G", "Ninja",
        f"-DCMAKE_C_COMPILER={str(clang).replace(chr(92), '/')}",
        f"-DCMAKE_MAKE_PROGRAM={str(ninja).replace(chr(92), '/')}",
        f"-DRAVEN_JAVA_HOME={str(jdk).replace(chr(92), '/')}",
        f"-DRAVEN_FORGE_PAYLOAD_JAR={str(FORGE_JAR).replace(chr(92), '/')}",
        f"-DRAVEN_LUNAR_PAYLOAD_JAR={str(LUNAR_JAR).replace(chr(92), '/')}",
    ]
    extra_env = {"PATH": str(clang.parent) + os.pathsep + os.environ.get("PATH", "")}
    if not run(cfg_cmd, CLIENT_DIR, extra_env):
        err("RavenNative cmake configure failed")
        return False
    ok("configured")

    build_cmd = [str(cmake), "--build", str(NATIVE_BUILD_DIR), "--config", "Release"]
    if not run(build_cmd, CLIENT_DIR, extra_env):
        err("RavenNative build failed")
        return False
    ok("build complete")

    LOADER_RUNTIME.parent.mkdir(parents=True, exist_ok=True)

    if not NATIVE_DLL_OUT.is_file():
        err(f"RavenNative.dll not found at {NATIVE_DLL_OUT}")
        return False
    
    shutil.copy2(str(NATIVE_DLL_OUT), str(LOADER_RUNTIME))
    ok(f"RavenNative.dll -> {LOADER_RUNTIME}")

    return True


def build_loader(cmake, extra_env):
    section("Loader - configure")
    if not run([str(cmake), "--preset", "windows-clang"], LOADER_DIR, extra_env):
        err("cmake configure failed")
        return False
    ok("configured")

    # Force rebuild when embedded assets change: touch the generated .rc so
    # Ninja re-links the EXE with fresh RavenNative.dll / other resources.
    loader_rc = BUILD_DIR / "resources_gen.rc"
    if loader_rc.is_file():
        loader_rc.touch()
    # Also remove the cached .res and the EXE to guarantee full re-link.
    loader_res = BUILD_DIR / "resources_gen.res"
    if loader_res.is_file():
        loader_res.unlink()
    loader_exe_build = BUILD_DIR / "Release" / "MindlessLoader.exe"
    if loader_exe_build.is_file():
        loader_exe_build.unlink()

    section("Loader - build")
    cmd = [str(cmake), "--build", str(BUILD_DIR), "--config", "Release"]
    if not run(cmd, LOADER_DIR, extra_env):
        err("cmake build failed")
        return False
    ok("build complete")

    built = LOADER_DIR / "MindlessLoader.exe"
    if built.is_file():
        shutil.copy2(str(built), str(OUTPUT_EXE))
        ok(f"EXE -> {OUTPUT_EXE}")
        return True

    alt = BUILD_DIR / "Release" / "MindlessLoader.exe"
    if alt.is_file():
        shutil.copy2(str(alt), str(OUTPUT_EXE))
        ok(f"EXE -> {OUTPUT_EXE}")
        return True

    err("MindlessLoader.exe not found after build")
    return False


def main():
    print(f"\n{BOLD}{'='*50}{RESET}")
    print(f"{BOLD}  Mindless United - build{RESET}")
    print(f"{BOLD}{'='*50}{RESET}")

    build_loader_flag = "--loader" in sys.argv or "--all" in sys.argv or len(sys.argv) == 1
    build_client_flag = "--client" in sys.argv or "--all" in sys.argv or len(sys.argv) == 1

    section("Detecting tools")

    llvm = detect_llvm()
    clang = lld = None
    if llvm:
        clang, lld = llvm
        ok(f"LLVM clang-cl : {clang}")
        ok(f"LLVM lld-link : {lld}")
    else:
        err("LLVM clang-cl not found")
        if build_loader_flag:
            sys.exit(1)

    ninja = detect_ninja()
    if ninja:
        ok(f"Ninja         : {ninja}")
    else:
        err("Ninja not found")
        if build_loader_flag:
            sys.exit(1)

    vcpkg = detect_vcpkg()
    if vcpkg:
        ok(f"vcpkg         : {vcpkg}")
    else:
        err("vcpkg not found")
        if build_loader_flag:
            sys.exit(1)

    cmake = detect_cmake()
    if cmake:
        ok(f"CMake         : {cmake}")
    else:
        err("CMake not found")
        if build_loader_flag:
            sys.exit(1)

    info("Scanning for Java installs (this walks Program Files, may take a sec)...")
    java_installs = scan_java_installs()
    if java_installs:
        info(f"Found {len(java_installs)} Java install(s):")
        for inst in java_installs:
            kind = "JDK" if inst["has_javac"] else "JRE"
            info(f"    - {kind} {inst['major']:<3} {inst['home']}")

    jdk17 = find_jdk(java_installs, major=17)
    if jdk17:
        ok(f"JDK 17        : {jdk17}")
    else:
        warn("JDK 17 not found - client/gradle may fail")

    jdk_any = jdk17 or find_jdk(java_installs)
    if jdk_any:
        ok(f"JDK (native)  : {jdk_any}")
    else:
        warn("No JDK found - RavenNative.dll build will fail")

    extra_env = {}
    if llvm:
        extra_env["PATH"] = str(clang.parent) + os.pathsep + os.environ.get("PATH", "")

    success = True

    if build_client_flag:
        if not build_client(jdk17):
            print(f"\n{BOLD}{RED}Build failed.{RESET}")
            sys.exit(1)

        if jdk_any and llvm and cmake and ninja:
            if not build_native_dll(cmake, clang, ninja, jdk_any):
                print(f"\n{BOLD}{RED}Build failed.{RESET}")
                sys.exit(1)
        else:
            warn("Skipping RavenNative.dll rebuild - missing tools")

    if build_loader_flag and llvm and ninja and vcpkg and cmake:
        section("Updating CMakePresets.json")
        update_preset(clang, lld, ninja, vcpkg)
        ok("preset updated")
        if not build_loader(cmake, extra_env):
            success = False

    print()
    if success:
        print(f"{BOLD}{GREEN}All done.{RESET}")
        if OUTPUT_EXE.is_file():
            size_mb = OUTPUT_EXE.stat().st_size / (1024 * 1024)
            print(f"  {GREEN}MindlessLoader.exe{RESET}  {size_mb:.1f} MB  ->  {OUTPUT_EXE}")
    else:
        print(f"{BOLD}{RED}Build failed.{RESET}")
        sys.exit(1)


if __name__ == "__main__":
    main()