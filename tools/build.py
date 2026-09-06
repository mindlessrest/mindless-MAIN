#!/usr/bin/env python3
# mindless automatic build
import os
import re
import sys
import json
import shutil
import subprocess
import glob
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

# This script lives in tools/, so the repository root is one level up.
ROOT        = Path(__file__).resolve().parent.parent
LOADER_DIR  = ROOT / "loader"
CLIENT_DIR  = ROOT / "client"
NATIVE_DIR  = CLIENT_DIR / "native"
PRESET_FILE = LOADER_DIR / "CMakePresets.json"
PRESET_TEMPLATE = LOADER_DIR / "CMakePresets.template.json"
BUILD_DIR   = LOADER_DIR / "out" / "build" / "windows-clang"
OUTPUT_EXE  = ROOT / "MindlessLoader.exe"
# Lives beside this script rather than in the repository root: it is a cache of detected
# compiler and JDK paths that build.py owns outright, and nothing else ever reads it.
TOOL_CACHE_FILE = Path(__file__).resolve().parent / ".build_tools_cache.json"
CPU_COUNT = max(1, os.cpu_count() or 4)

FORGE_JAR   = CLIENT_DIR / "build" / "libs" / "mindless.jar"
LUNAR_JAR   = CLIENT_DIR / "build" / "intermediates" / "mindless-lunar-mcp-with-forge.jar"
FORGE_JAR_OBF   = CLIENT_DIR / "build" / "libs" / "mindless-obf.jar"
LUNAR_JAR_OBF   = CLIENT_DIR / "build" / "intermediates" / "mindless-lunar-mcp-with-forge-obf.jar"
FORGE_MAPPING   = CLIENT_DIR / "build" / "mappings" / "forge.json"
LUNAR_MAPPING   = CLIENT_DIR / "build" / "mappings" / "lunar.json"
NATIVE_BUILD_DIR = CLIENT_DIR / "native_build"
NATIVE_DLL_OUT   = NATIVE_BUILD_DIR / "dist" / "MindlessNative.dll"

LOADER_RUNTIME   = LOADER_DIR / "assets" / "runtime" / "MindlessNative.dll"
OBF_JAR          = ROOT / "tools" / "obf" / "build" / "libs" / "mindless-obf.jar"
OBF_DIR          = ROOT / "tools" / "obf"

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


def load_tool_cache():
    if not TOOL_CACHE_FILE.is_file():
        return {}
    try:
        with open(TOOL_CACHE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError):
        return {}
    valid = {}
    for key, path_str in data.items():
        if not path_str:
            continue
        p = Path(path_str)
        if p.exists():
            valid[key] = p
    return valid


def save_tool_cache(entries):
    data = {k: (str(v) if v else None) for k, v in entries.items()}
    try:
        with open(TOOL_CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except OSError:
        pass


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
    bin_dir = javaw_path.parent
    home = bin_dir.parent if bin_dir.name.lower() == "bin" else bin_dir
    return home


def _query_java_version(java_home):
    release_file = java_home / "release"
    if release_file.is_file():
        try:
            text = release_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            text = ""
        m = re.search(r'JAVA_VERSION="(\d+)(?:\.(\d+))?', text)
        if m:
            major = int(m.group(1))
            if major == 1 and m.group(2):
                major = int(m.group(2))
            return major

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
    homes = set()

    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        jh = Path(env_home)
        if (jh / "bin" / "javaw.exe").is_file():
            homes.add(jh)

    with ThreadPoolExecutor(max_workers=len(JAVA_SEARCH_ROOTS)) as pool:
        for javaw_list in pool.map(_find_javaw_under, JAVA_SEARCH_ROOTS):
            for javaw in javaw_list:
                homes.add(_java_home_from_javaw(javaw))

    if not homes:
        return []

    installs = []
    homes = list(homes)
    with ThreadPoolExecutor(max_workers=min(32, max(4, len(homes)))) as pool:
        futures = {pool.submit(_query_java_version, home): home for home in homes}
        for fut in as_completed(futures):
            home = futures[fut]
            major = fut.result()
            if major is None:
                continue
            has_javac = (home / "bin" / "javac.exe").is_file()
            installs.append({"home": home, "major": major, "has_javac": has_javac})

    installs.sort(key=lambda i: (i["has_javac"], i["major"]), reverse=True)
    return installs


def find_jdk(installs, major=None):
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


def update_preset(clang, lld, ninja, vcpkg, voyager=None):
    source = PRESET_FILE if PRESET_FILE.is_file() else PRESET_TEMPLATE
    if not source.is_file():
        err(f"{source.name} not found in loader/")
        sys.exit(1)
    with open(source, "r", encoding="utf-8-sig") as f:
        data = json.load(f)
    old_text = json.dumps(data, sort_keys=True)
    toolchain = str(vcpkg / "scripts" / "buildsystems" / "vcpkg.cmake").replace("\\", "/")
    for preset in data.get("configurePresets", []):
        if preset.get("name") == "windows-clang":
            cv = preset.setdefault("cacheVariables", {})
            loader_c = clang
            loader_cxx = clang
            if voyager:
                loader_c = voyager / "bin" / "clang.exe"
                loader_cxx = voyager_cxx(voyager)
            cv["CMAKE_C_COMPILER"]     = str(loader_c).replace("\\", "/")
            cv["CMAKE_CXX_COMPILER"]   = str(loader_cxx).replace("\\", "/")
            cv["CMAKE_LINKER"]         = str(lld).replace("\\", "/")
            cv["CMAKE_MAKE_PROGRAM"]   = str(ninja).replace("\\", "/")
            cv["CMAKE_TOOLCHAIN_FILE"] = toolchain
            cv["VCPKG_INSTALLED_DIR"]  = str(LOADER_DIR / "vcpkg_installed").replace("\\", "/")
            cv["VCPKG_TARGET_TRIPLET"] = "x64-windows-static"
            if voyager:
                # ── Loader --prod obfuscation (Voyager/Hikari LLVM passes) ────────────────
                # Rationale for each pass:
                #   -enable-cffobf        : flattens every function into a dispatch-switch loop;
                #                           the strongest single anti-analysis pass
                #   -enable-bcfobf        : inserts opaque predicates + dead branches; forces
                #                           decompilers to reason about unreachable code paths
                #     -bcf_prob=100       : apply to every eligible basic block
                #     -bcf_loop=3         : run the pass three times per function for deeper nesting
                #     -bcf_cond_compl=5   : maximally complex opaque predicate expressions
                #     -bcf_junkasm        : inject junk inline asm into altered blocks
                #   -enable-subobf        : replaces arithmetic/logic with equivalent but
                #                           harder-to-pattern-match sequences
                #     -sub_prob=100       : substitute every eligible instruction
                #     -sub_loop=3         : loop substitution 3x for compounding effect
                #   -enable-splitobf      : splits every basic block into multiple; inflates CFG
                #     -split_num=10       : maximum allowed splits per block
                #   -enable-constenc      : XOR-encrypts integer constants at compile time,
                #                           decrypts at runtime; defeats constant-propagation
                #     -constenc_times=3   : loop 3x
                #     -constenc_togv      : also move constants to global variables
                #     -constenc_togv_prob=80
                #     -constenc_subxor    : obfuscate the XOR itself
                #     -constenc_subxor_prob=80
                #   -enable-strcry        : encrypts every string literal; defeats grep/strings
                #     -strcry_prob=100    : encrypt all string elements
                #   -enable-indibran      : replaces direct jumps with indirect ones via a
                #                           runtime-computed jump table; defeats static CFG
                #     -indibran-enc-jump-target : encrypt jump-table targets for extra depth
                #   -enable-funcwra       : wraps callsites in thunks; obscures call graph
                #     -fw_prob=80         : wrap 80 % of callsites
                #     -fw_times=3         : triple-wrap
                # Production profile: keep the custom Voyager clang for compilation,
                # but avoid the multiplicative pass settings that can make LLVM exhaust
                # memory on CI runners. Linking still uses the separately detected normal
                # LLVM lld-link.exe through CMAKE_LINKER below.
                hikari_flags = (
                    "-mllvm -voyager"
                    " -mllvm -enable-cffobf"
                    " -mllvm -enable-bcfobf"
                    " -mllvm -bcf_prob=50"
                    " -mllvm -bcf_loop=1"
                    " -mllvm -bcf_cond_compl=2"
                    " -mllvm -enable-subobf"
                    " -mllvm -sub_prob=50"
                    " -mllvm -sub_loop=1"
                    " -mllvm -enable-splitobf"
                    " -mllvm -split_num=2"
                )
                cv["MINDLESS_PRODUCTION_OBFUSCATION_FLAGS"] = hikari_flags
                cv["MINDLESS_PRIVATE_PDB"] = "ON"
            else:
                cv.pop("MINDLESS_PRODUCTION_OBFUSCATION_FLAGS", None)
                cv["MINDLESS_PRIVATE_PDB"] = "OFF"
            cv.pop("CMAKE_C_FLAGS_RELEASE", None)
            cv.pop("CMAKE_CXX_FLAGS_RELEASE", None)
    preset_contents = json.dumps(data, indent=4) + "\n"
    current_contents = PRESET_FILE.read_text(encoding="utf-8-sig") if PRESET_FILE.is_file() else ""
    if current_contents != preset_contents:
        PRESET_FILE.write_text(preset_contents, encoding="utf-8")
    new_text = json.dumps(data, sort_keys=True)
    if old_text != new_text:
        cmake_cache = BUILD_DIR / "CMakeCache.txt"
        if cmake_cache.is_file():
            cmake_cache.unlink()
            info("CMakeCache.txt deleted (preset changed — forcing reconfigure)")


def detect_voyager():
    env_path = os.environ.get("VOYAGER_PATH")
    if not env_path:
        return None
    root = Path(env_path).resolve()
    cxx = root / "bin" / "clang-cl.exe"
    if not cxx.is_file():
        cxx = root / "bin" / "clang++.exe"
    required = [root / "bin" / "clang.exe", cxx, root / "lib" / "clang" / "20"]
    return root if all(path.exists() for path in required) else None


def voyager_cxx(root):
    clang_cl = root / "bin" / "clang-cl.exe"
    return clang_cl if clang_cl.is_file() else root / "bin" / "clang++.exe"


def build_obf_jar(jdk):
    if OBF_JAR.is_file() and os.environ.get("MINDLESS_OBF_CACHE_HIT") == "1":
        ok("MindlessObf restored from cache")
        return True
    inputs = list((OBF_DIR / "src").rglob("*")) + [
        OBF_DIR / "build.gradle.kts",
        OBF_DIR / "settings.gradle.kts",
    ]
    inputs = [path for path in inputs if path.is_file()]
    if OBF_JAR.is_file() and all(path.stat().st_mtime <= OBF_JAR.stat().st_mtime for path in inputs):
        return True
    section("Building MindlessObf")
    gradlew = CLIENT_DIR / "gradlew.bat"
    env = {}
    if jdk:
        env["JAVA_HOME"] = str(jdk)
    cmd = [str(gradlew), "jar"]
    if not run(cmd, OBF_DIR, env):
        err("MindlessObf build failed")
        return False
    ok("MindlessObf built")
    return True


def obfuscate_jar(jdk, input_jar, output_jar, mapping_file, label):
    if not OBF_JAR.is_file():
        warn(f"MindlessObf jar not found, skipping {label} obfuscation")
        return False
    java = jdk / "bin" / "java.exe" if jdk else Path("java.exe")
    cmd = [str(java), "-jar", str(OBF_JAR), str(input_jar), str(output_jar), "--mapping", str(mapping_file)]
    info(f"Obfuscating {label}...")
    if output_jar.is_file():
        output_jar.unlink()
    if not run(cmd, ROOT):
        err(f"{label} obfuscation failed")
        return False
    if not output_jar.is_file() or output_jar.stat().st_size == 0:
        err(f"{label} obfuscation did not produce an output JAR")
        return False
    size_kb = output_jar.stat().st_size // 1024
    ok(f"{label} obfuscated ({size_kb} KB)")
    return True


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
    cmd = [str(gradlew), "build", "lunarPayloadJar", "-x", "test", "-x", "compileTestJava",
           "--parallel", "--build-cache", "--warning-mode=none", f"--max-workers={CPU_COUNT}"]
    if not run(cmd, CLIENT_DIR, env):
        err("gradle build failed")
        return False
    ok("client built")
    if FORGE_JAR.is_file():
        ok(f"Forge JAR  -> {FORGE_JAR}")
    if LUNAR_JAR.is_file():
        ok(f"Lunar JAR  -> {LUNAR_JAR}")
    return True


def build_native_dll(cmake, clang, lld, ninja, jdk, voyager=None, prod=False):
    section("MindlessNative.dll - build")

    if prod:
        forge_jar = FORGE_JAR_OBF if FORGE_JAR_OBF.is_file() else FORGE_JAR
        lunar_jar = LUNAR_JAR_OBF if LUNAR_JAR_OBF.is_file() else LUNAR_JAR
    else:
        forge_jar = FORGE_JAR
        lunar_jar = LUNAR_JAR

    if not forge_jar.is_file():
        err(f"Forge JAR missing: {forge_jar}")
        err("Run client build first.")
        return False
    if not lunar_jar.is_file():
        err(f"Lunar JAR missing: {lunar_jar}")
        err("Run client build first.")
        return False

    if forge_jar == FORGE_JAR_OBF:
        ok(f"Using obfuscated Forge JAR")
    if lunar_jar == LUNAR_JAR_OBF:
        ok(f"Using obfuscated Lunar JAR")

    native_clang = clang
    if voyager:
        hikari_clang = voyager / "bin" / "clang.exe"
        if hikari_clang.is_file():
            native_clang = hikari_clang
            ok(f"Hikari clang  : {native_clang}")

    NATIVE_BUILD_DIR.mkdir(parents=True, exist_ok=True)

    for res_file in NATIVE_BUILD_DIR.rglob("payload.rc.res"):
        res_file.unlink(missing_ok=True)
    dist_dll = NATIVE_BUILD_DIR / "dist" / "MindlessNative.dll"
    try:
        if dist_dll.is_file():
            dist_dll.unlink()
    except PermissionError:
        warn("MindlessNative.dll is in use by another process, skipping deletion")

    # ── MindlessNative.dll --prod obfuscation (Voyager/Hikari LLVM passes) ───
    # Stronger than the loader flags intentionally: the native DLL is the
    # highest-value target (it carries the embedded JARs and injection logic),
    # so it receives the full suite.  Passes not used for the loader
    # (bcfobf, splitobf, constenc, funcwra) are added here.
    #
    #   -enable-cffobf              : control-flow flattening (dispatch loop)
    #   -enable-bcfobf              : bogus control flow + opaque predicates
    #     -bcf_prob=100             : all blocks
    #     -bcf_loop=3               : three nesting passes
    #     -bcf_cond_compl=5         : most complex predicate form
    #     -bcf_junkasm              : inject junk asm into altered blocks
    #   -enable-subobf              : instruction substitution
    #     -sub_prob=100             : all eligible instructions
    #     -sub_loop=3               : three substitution passes
    #   -enable-splitobf            : basic-block splitting (inflates CFG)
    #     -split_num=10             : maximum splits per block
    #   -enable-constenc            : constant encryption (XOR at rest)
    #     -constenc_times=3
    #     -constenc_togv            : hoist constants into global vars
    #     -constenc_togv_prob=80
    #     -constenc_subxor          : obfuscate the XOR operator itself
    #     -constenc_subxor_prob=80
    #   -enable-strcry              : string encryption
    #     -strcry_prob=100          : all string elements
    #   -enable-indibran            : indirect branching via runtime jump table
    #     -indibran-enc-jump-target : encrypt jump-table targets
    #   -enable-funcwra             : callsite thunk wrapping (obscures call graph)
    #     -fw_prob=80
    #     -fw_times=3
    hikari_cflags = ""
    if voyager and native_clang != clang:
        hikari_cflags = (
            "-mllvm -voyager"
            " -mllvm -enable-cffobf"
            " -mllvm -enable-bcfobf"
            " -mllvm -bcf_prob=50"
            " -mllvm -bcf_loop=1"
            " -mllvm -bcf_cond_compl=2"
            " -mllvm -enable-subobf"
            " -mllvm -sub_prob=50"
            " -mllvm -sub_loop=1"
            " -mllvm -enable-splitobf"
            " -mllvm -split_num=2"
        )


    cfg_cmd = [
        str(cmake), "-S", str(NATIVE_DIR), "-B", str(NATIVE_BUILD_DIR),
        "-G", "Ninja",
        f"-DCMAKE_C_COMPILER={str(native_clang).replace(chr(92), '/')}",
        f"-DCMAKE_LINKER={str(lld).replace(chr(92), '/')}",
        f"-DCMAKE_MAKE_PROGRAM={str(ninja).replace(chr(92), '/')}",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DMINDLESS_JAVA_HOME={str(jdk).replace(chr(92), '/')}",
        f"-DMINDLESS_FORGE_PAYLOAD_JAR={str(forge_jar).replace(chr(92), '/')}",
        f"-DMINDLESS_LUNAR_PAYLOAD_JAR={str(lunar_jar).replace(chr(92), '/')}",
        f"-DMINDLESS_PRODUCTION={'ON' if prod else 'OFF'}",
        f"-DMINDLESS_DEBUG_LOGS={'ON' if os.environ.get('MINDLESS_DEBUG_LOGS') == '1' else 'OFF'}",
    ]
    if hikari_cflags:
        cfg_cmd.append(f"-DCMAKE_C_FLAGS_RELEASE={hikari_cflags}")
    extra_env = {
        "PATH": str(native_clang.parent) + os.pathsep
        + str(lld.parent) + os.pathsep
        + os.environ.get("PATH", "")
    }

    cmake_cache = NATIVE_BUILD_DIR / "CMakeCache.txt"
    cmakelists = NATIVE_DIR / "CMakeLists.txt"
    expected_production_cache = f"MINDLESS_PRODUCTION:BOOL={'ON' if prod else 'OFF'}"
    expected_debug_cache = f"MINDLESS_DEBUG_LOGS:BOOL={'ON' if os.environ.get('MINDLESS_DEBUG_LOGS') == '1' else 'OFF'}"
    expected_build_type_cache = "CMAKE_BUILD_TYPE:STRING=Release"
    cache_text = cmake_cache.read_text(encoding="utf-8", errors="ignore") if cmake_cache.is_file() else ""
    needs_configure = (
        not cmake_cache.is_file()
        or (cmakelists.is_file() and cmakelists.stat().st_mtime > cmake_cache.stat().st_mtime)
        or expected_production_cache not in cache_text
        or expected_debug_cache not in cache_text
        or expected_build_type_cache not in cache_text
    )
    if needs_configure:
        if not run(cfg_cmd, CLIENT_DIR, extra_env):
            err("MindlessNative cmake configure failed")
            return False
        ok("configured")
    else:
        info("configure skipped (CMakeCache up to date)")

    native_jobs = 1 if voyager else CPU_COUNT
    build_cmd = [str(cmake), "--build", str(NATIVE_BUILD_DIR), "--config", "Release",
                 "--parallel", str(native_jobs)]
    if not run(build_cmd, CLIENT_DIR, extra_env):
        err("MindlessNative build failed")
        return False
    ok("build complete")

    LOADER_RUNTIME.parent.mkdir(parents=True, exist_ok=True)

    if not NATIVE_DLL_OUT.is_file():
        err(f"MindlessNative.dll not found at {NATIVE_DLL_OUT}")
        return False
    
    shutil.copy2(str(NATIVE_DLL_OUT), str(LOADER_RUNTIME))
    ok(f"MindlessNative.dll -> {LOADER_RUNTIME}")

    return True


def build_loader(cmake, extra_env):
    section("Loader - configure")
    loader_cache = BUILD_DIR / "CMakeCache.txt"
    loader_cmakelists = LOADER_DIR / "CMakeLists.txt"
    needs_configure = (
        not loader_cache.is_file()
        or (loader_cmakelists.is_file() and loader_cmakelists.stat().st_mtime > loader_cache.stat().st_mtime)
        or (PRESET_FILE.is_file() and PRESET_FILE.stat().st_mtime > loader_cache.stat().st_mtime)
    )
    if needs_configure:
        if not run([str(cmake), "--preset", "windows-clang"], LOADER_DIR, extra_env):
            err("cmake configure failed")
            return False
        ok("configured")
    else:
        info("configure skipped (CMakeCache up to date)")

    loader_rc = BUILD_DIR / "resources_gen.rc"
    if loader_rc.is_file():
        loader_rc.touch()
    loader_res = BUILD_DIR / "resources_gen.res"
    if loader_res.is_file():
        loader_res.unlink()
    loader_exe_build = BUILD_DIR / "Release" / "MindlessLoader.exe"
    if loader_exe_build.is_file():
        loader_exe_build.unlink()

    section("Loader - build")
    cmd = [str(cmake), "--build", str(BUILD_DIR), "--config", "Release",
           "--parallel", str(CPU_COUNT)]
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

    known_flags = {"--loader", "--client", "--all", "--no-cache", "--prod"}
    has_target = any(a in {"--loader", "--client", "--all"} for a in sys.argv[1:])
    default_all = len(sys.argv) == 1 or (not has_target)
    build_loader_flag = "--loader" in sys.argv or "--all" in sys.argv or default_all
    build_client_flag = "--client" in sys.argv or "--all" in sys.argv or default_all
    no_cache_flag = "--no-cache" in sys.argv
    prod_flag = "--prod" in sys.argv

    section("Detecting tools")

    cache = {} if no_cache_flag else load_tool_cache()

    def resolve_llvm():
        cached_clang, cached_lld = cache.get("clang"), cache.get("lld")
        if cached_clang and cached_lld:
            return (cached_clang, cached_lld)
        return detect_llvm()

    def resolve(name, detector):
        cached = cache.get(name)
        return cached if cached else detector()

    detectors = {
        "llvm":  resolve_llvm,
        "ninja": lambda: resolve("ninja", detect_ninja),
        "vcpkg": lambda: resolve("vcpkg", detect_vcpkg),
        "cmake": lambda: resolve("cmake", detect_cmake),
    }
    results = {}
    with ThreadPoolExecutor(max_workers=len(detectors)) as pool:
        futures = {pool.submit(fn): name for name, fn in detectors.items()}
        for fut in as_completed(futures):
            results[futures[fut]] = fut.result()

    llvm, ninja, vcpkg, cmake = results["llvm"], results["ninja"], results["vcpkg"], results["cmake"]

    clang = lld = None
    if llvm:
        clang, lld = llvm
        ok(f"LLVM clang-cl : {clang}")
        ok(f"LLVM lld-link : {lld}")
    else:
        err("LLVM clang-cl not found")
        if build_loader_flag:
            sys.exit(1)

    if ninja:
        ok(f"Ninja         : {ninja}")
    else:
        err("Ninja not found")
        if build_loader_flag:
            sys.exit(1)

    if vcpkg:
        ok(f"vcpkg         : {vcpkg}")
    else:
        err("vcpkg not found")
        if build_loader_flag:
            sys.exit(1)

    if cmake:
        ok(f"CMake         : {cmake}")
    else:
        err("CMake not found")
        if build_loader_flag:
            sys.exit(1)

    cached_jdk17, cached_jdk_any = cache.get("jdk17"), cache.get("jdk_any")
    if cached_jdk17 or cached_jdk_any:
        jdk17, jdk_any = cached_jdk17, cached_jdk_any or cached_jdk17
    else:
        info("Scanning for Java installs (this walks Program Files, may take a sec)...")
        java_installs = scan_java_installs()
        if java_installs:
            info(f"Found {len(java_installs)} Java install(s):")
            for inst in java_installs:
                kind = "JDK" if inst["has_javac"] else "JRE"
                info(f"    - {kind} {inst['major']:<3} {inst['home']}")
        jdk17 = find_jdk(java_installs, major=17)
        jdk_any = jdk17 or find_jdk(java_installs)

    if jdk17:
        ok(f"JDK 17        : {jdk17}")
    else:
        warn("JDK 17 not found - client/gradle may fail")

    if jdk_any:
        ok(f"JDK (native)  : {jdk_any}")
    else:
        warn("No JDK found - MindlessNative.dll build will fail")

    voyager = None
    if prod_flag:
        voyager = detect_voyager()
        if voyager:
            ok(f"Voyager       : {voyager}")
        else:
            err("Voyager compiler validation failed")
            err("Set VOYAGER_PATH to the extracted root containing bin and lib\\clang\\20")
            sys.exit(1)

    save_tool_cache({
        "clang": clang, "lld": lld, "ninja": ninja, "vcpkg": vcpkg, "cmake": cmake,
        "jdk17": jdk17, "jdk_any": jdk_any,
    })

    extra_env = {}
    if llvm:
        extra_env["PATH"] = str(clang.parent) + os.pathsep + os.environ.get("PATH", "")

    success = True

    if build_client_flag:
        if not build_client(jdk17):
            print(f"\n{BOLD}{RED}Build failed.{RESET}")
            sys.exit(1)

        if prod_flag:
            section("JAR obfuscation")
            if not build_obf_jar(jdk17):
                err("MindlessObf build failed")
                sys.exit(1)
            with ThreadPoolExecutor(max_workers=2) as pool:
                forge_future = pool.submit(
                    obfuscate_jar, jdk17, FORGE_JAR, FORGE_JAR_OBF, FORGE_MAPPING, "Forge JAR"
                ) if FORGE_JAR.is_file() else None
                lunar_future = pool.submit(
                    obfuscate_jar, jdk17, LUNAR_JAR, LUNAR_JAR_OBF, LUNAR_MAPPING, "Lunar JAR"
                ) if LUNAR_JAR.is_file() else None
                forge_obfuscated = forge_future.result() if forge_future else False
                lunar_obfuscated = lunar_future.result() if lunar_future else False
            if not forge_obfuscated or not lunar_obfuscated:
                print(f"\n{BOLD}{RED}Production build failed during JAR obfuscation.{RESET}")
                sys.exit(1)

        if jdk_any and llvm and cmake and ninja:
            if not build_native_dll(cmake, clang, lld, ninja, jdk_any, voyager=voyager, prod=prod_flag):
                print(f"\n{BOLD}{RED}Build failed.{RESET}")
                sys.exit(1)
        else:
            warn("Skipping MindlessNative.dll rebuild - missing tools")

    if build_loader_flag and llvm and ninja and vcpkg and cmake:
        section("Updating CMakePresets.json")
        update_preset(clang, lld, ninja, vcpkg, voyager=voyager)
        ok("preset updated")

        if voyager:
            extra_env["PATH"] = str(voyager / "bin") + os.pathsep + extra_env.get("PATH", os.environ.get("PATH", ""))

        if not build_loader(cmake, extra_env):
            success = False

    print()
    if success:
        print(f"{BOLD}{GREEN}All done.{RESET}")
        if prod_flag:
            print(f"  {GREEN}[PROD BUILD]{RESET}", end="")
            if voyager:
                print(f" Voyager obfuscation applied", end="")
            if FORGE_JAR_OBF.is_file():
                print(f" + JAR obfuscation", end="")
            print()
        if OUTPUT_EXE.is_file():
            size_mb = OUTPUT_EXE.stat().st_size / (1024 * 1024)
            print(f"  {GREEN}MindlessLoader.exe{RESET}  {size_mb:.1f} MB  ->  {OUTPUT_EXE}")
    else:
        print(f"{BOLD}{RED}Build failed.{RESET}")
        sys.exit(1)


if __name__ == "__main__":
    main()
