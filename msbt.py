import sys
import os
import subprocess
import platform
import ctypes
import shutil
import zipfile
import tempfile
import glob

if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

def enable_vt():
    if platform.system() != "Windows":
        return
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.GetStdHandle(-11)
    mode = ctypes.c_ulong()
    kernel32.GetConsoleMode(handle, ctypes.byref(mode))
    kernel32.SetConsoleMode(handle, mode.value | 0x0004)

enable_vt()

RST = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
R = "\033[38;2;255;80;80m"
G = "\033[38;2;80;255;120m"
B = "\033[38;2;80;160;255m"
Y = "\033[38;2;255;200;60m"
M = "\033[38;2;200;120;255m"
C = "\033[38;2;100;220;220m"
W = "\033[38;2;220;220;220m"
GRAY = "\033[38;2;120;120;120m"
PINK = "\033[38;2;255;136;180m"

BANNER = f"""{PINK}
                       █████      █████   
                      ░░███      ░░███    
 █████████████   █████ ░███████  ███████  
░░███░░███░░███ ███░░  ░███░░███░░░███░   
 ░███ ░███ ░███░░█████ ░███ ░███  ░███    
 ░███ ░███ ░███ ░░░░███░███ ░███  ░███ ███
 █████░███ ███████████ ████████   ░░█████ 
░░░░░ ░░░ ░░░░░░░░░░░ ░░░░░░░░     ░░░░░  {RST}
{DIM}  mindless script build tool{RST}
"""

def log_info(msg):
    print(f"  {B}●{RST} {msg}")

def log_ok(msg):
    print(f"  {G}✓{RST} {msg}")

def log_warn(msg):
    print(f"  {Y}!{RST} {msg}")

def log_fail(msg):
    print(f"  {R}✗{RST} {msg}")

def log_step(msg):
    print(f"\n{BOLD}{W}{msg}{RST}")

def find_jdk():
    javac = shutil.which("javac")
    if javac:
        return os.path.dirname(os.path.dirname(os.path.realpath(javac)))

    search_paths = []
    home = os.path.expanduser("~")

    if platform.system() == "Windows":
        search_paths += [
            os.path.join(home, ".jdks"),
            os.path.join(home, "scoop", "apps"),
            r"C:\Program Files\Java",
            r"C:\Program Files\Eclipse Adoptium",
            r"C:\Program Files\Microsoft\jdk",
            r"C:\Program Files\Zulu",
            r"C:\Program Files\Amazon Corretto",
        ]
    else:
        search_paths += [
            "/usr/lib/jvm",
            "/usr/local/lib/jvm",
            os.path.join(home, ".jdks"),
            os.path.join(home, ".sdkman", "candidates", "java"),
        ]

    javac_name = "javac.exe" if platform.system() == "Windows" else "javac"

    for search in search_paths:
        if not os.path.isdir(search):
            continue
        for entry in sorted(os.listdir(search), reverse=True):
            candidate = os.path.join(search, entry)
            if not os.path.isdir(candidate):
                continue
            javac_path = os.path.join(candidate, "bin", javac_name)
            if os.path.isfile(javac_path):
                return candidate
            for sub in sorted(os.listdir(candidate), reverse=True) if os.path.isdir(candidate) else []:
                sub_path = os.path.join(candidate, sub)
                javac_path = os.path.join(sub_path, "bin", javac_name)
                if os.path.isfile(javac_path):
                    return sub_path

    java_home = os.environ.get("JAVA_HOME")
    if java_home and os.path.isfile(os.path.join(java_home, "bin", javac_name)):
        return java_home

    return None

def find_msa_jar():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(script_dir, "msa.jar"),
        os.path.join(script_dir, "client", "build", "libs", "msa.jar"),
        os.path.join(script_dir, "..", "msa.jar"),
    ]
    for c in candidates:
        if os.path.isfile(c):
            return os.path.abspath(c)
    return None

def compile_script(java_file, jdk_path, msa_path):
    script_name = os.path.splitext(os.path.basename(java_file))[0]
    log_step(f"Compiling: {C}{script_name}.java{RST}")

    javac = os.path.join(jdk_path, "bin", "javac.exe" if platform.system() == "Windows" else "javac")
    jar_tool = os.path.join(jdk_path, "bin", "jar.exe" if platform.system() == "Windows" else "jar")

    with open(java_file, "r", encoding="utf-8") as f:
        source = f.read()

    tmp_dir = tempfile.mkdtemp(prefix="msbt_")
    out_dir = os.path.join(tmp_dir, "classes")
    os.makedirs(out_dir)

    wrapped_name = f"sc_{script_name}"
    wrapped_source = source

    imports = [
        "java.awt.Color", "java.util.Collections", "java.util.List",
        "java.util.ArrayList", "java.util.Arrays", "java.util.Map",
        "java.util.Set", "java.util.HashMap", "java.util.HashSet",
        "java.util.concurrent.ConcurrentHashMap", "java.util.LinkedHashMap",
        "java.util.LinkedHashSet", "java.util.Iterator", "java.util.Comparator",
        "java.util.concurrent.atomic.AtomicInteger",
        "java.util.concurrent.atomic.AtomicLong",
        "java.util.concurrent.atomic.AtomicBoolean",
        "java.util.Random", "java.util.regex.Matcher",
    ]

    header = ""
    for imp in imports:
        header += f"import {imp};\n"
    header += "import keystrokesmod.script.model.*;\n"
    header += "import keystrokesmod.script.packet.clientbound.*;\n"
    header += "import keystrokesmod.script.packet.serverbound.*;\n"
    header += f"public class {wrapped_name} extends keystrokesmod.script.ScriptDefaults {{\n"
    header += f"public static final keystrokesmod.script.ScriptDefaults.modules modules = new keystrokesmod.script.ScriptDefaults.modules(\"{script_name}\");\n"
    header += f"public static final String scriptName = \"{script_name}\";\n"

    wrapped_source = header + wrapped_source + "\n}"

    src_file = os.path.join(tmp_dir, f"{wrapped_name}.java")
    with open(src_file, "w", encoding="utf-8") as f:
        f.write(wrapped_source)

    log_info(f"javac: {DIM}{javac}{RST}")
    log_info(f"classpath: {DIM}{msa_path}{RST}")

    cmd = [
        javac,
        "-source", "1.8",
        "-target", "1.8",
        "-cp", msa_path,
        "-d", out_dir,
        "-encoding", "UTF-8",
        "-nowarn",
        src_file,
    ]

    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        log_fail("compilation failed")
        errors = result.stderr.strip() if result.stderr else result.stdout.strip()
        for line in errors.split("\n"):
            if "error:" in line.lower():
                print(f"    {R}{line.strip()}{RST}")
            else:
                print(f"    {GRAY}{line.strip()}{RST}")
        shutil.rmtree(tmp_dir, ignore_errors=True)
        return None

    log_ok("compiled successfully")

    output_jar = os.path.join(os.path.dirname(java_file), f"{script_name}.jar")

    with zipfile.ZipFile(output_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(out_dir):
            for file in files:
                full_path = os.path.join(root, file)
                arc_name = os.path.relpath(full_path, out_dir)
                zf.write(full_path, arc_name)

    shutil.rmtree(tmp_dir, ignore_errors=True)

    size_kb = os.path.getsize(output_jar) / 1024
    log_ok(f"output: {G}{output_jar}{RST} ({size_kb:.1f} KB)")
    return output_jar

def main():
    print(BANNER)

    if len(sys.argv) < 2:
        log_fail("no input file")
        print(f"\n  {W}usage:{RST} drag a .java file onto this script")
        print(f"  {W}   or:{RST} python msbt.py <script.java> [script2.java ...]")
        input(f"\n  {DIM}press enter to exit{RST}")
        sys.exit(1)

    java_files = [f for f in sys.argv[1:] if f.endswith(".java")]
    if not java_files:
        log_fail("no .java files provided")
        input(f"\n  {DIM}press enter to exit{RST}")
        sys.exit(1)

    log_step("Finding JDK")
    jdk_path = find_jdk()
    if not jdk_path:
        log_fail("no JDK found on this system")
        log_warn("install any JDK (8-21) and make sure javac is on PATH or in a standard location")
        input(f"\n  {DIM}press enter to exit{RST}")
        sys.exit(1)
    log_ok(f"JDK: {C}{jdk_path}{RST}")

    log_step("Finding msa.jar")
    msa_path = find_msa_jar()
    if not msa_path:
        log_fail("msa.jar not found")
        log_warn("place msa.jar next to msbt.py")
        input(f"\n  {DIM}press enter to exit{RST}")
        sys.exit(1)
    log_ok(f"msa.jar: {C}{msa_path}{RST}")

    results = []
    for java_file in java_files:
        if not os.path.isfile(java_file):
            log_fail(f"file not found: {java_file}")
            results.append(None)
            continue
        jar = compile_script(java_file, jdk_path, msa_path)
        results.append(jar)

    print()
    success = sum(1 for r in results if r)
    failed = len(results) - success
    if failed == 0:
        log_ok(f"{G}{BOLD}all {success} script(s) compiled{RST}")
    else:
        log_warn(f"{success} compiled, {R}{failed} failed{RST}")

    input(f"\n  {DIM}press enter to exit{RST}")

if __name__ == "__main__":
    main()
