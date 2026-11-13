# SPDX-FileCopyrightText: 2026 diff
# In-game pip package installer with recursive dependency resolution,
# version selection (==), and clean uninstallation for ComputerCraft (GraalPy)

import sys
import re
import java
import requests

WheelExtractor = java.type("work.diffdev.kik.python.pip.WheelExtractor")

# Known built-in modules that satisfy requirements without needing download
BUILTIN_MODULES = {
    "requests", "sqlite3", "websocket", "pil", "pillow",
    "math", "json", "random", "re", "sys", "os", "time",
    "datetime", "collections", "itertools", "functools",
    "pathlib", "logging", "asyncio", "hashlib", "urllib",
    "io", "string", "struct", "copy", "turtle", "redstone",
    "term", "cc", "kik"
}

def normalize_name(name):
    """Normalize package name according to PEP 503."""
    return re.sub(r"[-_.]+", "-", name).lower()

def check_python_version_marker(marker_str):
    """Evaluates PEP 508 python_version markers against current GraalPy version."""
    m = re.search(r"python_version\s*([<>=!]+)\s*['\"]([0-9.]+)['\"]", marker_str)
    if m:
        op, ver = m.groups()
        cur = sys.version_info[:2]
        target = tuple(map(int, ver.split(".")[:2]))
        if op == "<": return cur < target
        if op == "<=": return cur <= target
        if op == ">": return cur > target
        if op == ">=": return cur >= target
        if op == "==": return cur == target
        if op == "!=": return cur != target
    return True

def parse_requirement(req_str):
    """
    Parses a PEP 508 requirement string from requires_dist.
    Returns package name if it should be installed, or None if it is optional/incompatible.
    """
    req_str = req_str.strip()
    if not req_str:
        return None

    # Handle environment markers after ';'
    if ";" in req_str:
        req_part, marker = req_str.split(";", 1)
        marker = marker.strip()
        # Skip optional extras (e.g. extra == "jupyter", extra == "dev")
        if "extra" in marker:
            return None
        # Check python_version markers
        if not check_python_version_marker(marker):
            return None
        # Skip non-linux OS markers if specified
        if "sys_platform == 'win32'" in marker or 'sys_platform == "win32"' in marker:
            return None
    else:
        req_part = req_str

    # Extract clean package name (before comparison operators or brackets)
    m = re.match(r"^([a-zA-Z0-9_\-.]+)", req_part.strip())
    if m:
        pkg_name = m.group(1).strip()
        return pkg_name
    return None

def is_installed(package_name, target_dir="lib"):
    """Checks if a package is already installed or available in built-ins/sys.modules."""
    norm = normalize_name(package_name)
    if norm in BUILTIN_MODULES or package_name.lower() in BUILTIN_MODULES:
        return True

    cc_fs = sys.modules.get("cc_fs")
    if not cc_fs or not cc_fs.exists(target_dir):
        return False

    norm_underscore = norm.replace("-", "_")
    candidates = [
        f"{target_dir}/{package_name}",
        f"{target_dir}/{package_name}.py",
        f"{target_dir}/{norm}",
        f"{target_dir}/{norm}.py",
        f"{target_dir}/{norm_underscore}",
        f"{target_dir}/{norm_underscore}.py",
        f"{target_dir}/.installed/{norm}.manifest"
    ]
    for c in candidates:
        if cc_fs.exists(c):
            return True
    return False

def install(package_spec, target_dir="lib", visited=None, depth=0):
    """
    Installs a package and all its pure-Python dependencies from PyPI.
    Supports package specs with version constraints (e.g. 'rich', 'jinja2==3.1.2').
    """
    if visited is None:
        visited = set()

    package_spec = package_spec.strip()
    if not package_spec:
        print("Error: Empty package specification.")
        return False

    # Parse version if specified (e.g. package==1.2.3)
    target_version = None
    if "==" in package_spec:
        raw_name, target_version = package_spec.split("==", 1)
        raw_name = raw_name.strip()
        target_version = target_version.strip()
    elif ">=" in package_spec:
        raw_name, _ = package_spec.split(">=", 1)
        raw_name = raw_name.strip()
    elif "<=" in package_spec:
        raw_name, _ = package_spec.split("<=", 1)
        raw_name = raw_name.strip()
    elif "~=" in package_spec:
        raw_name, _ = package_spec.split("~=", 1)
        raw_name = raw_name.strip()
    else:
        m = re.match(r"^([a-zA-Z0-9_\-.]+)", package_spec)
        raw_name = m.group(1).strip() if m else package_spec

    norm_name = normalize_name(raw_name)
    indent = "  " * depth

    # Prevent circular recursion
    if norm_name in visited:
        return True

    # Check if already installed
    if is_installed(raw_name, target_dir):
        print(f"{indent}\033[90mRequirement already satisfied: {raw_name} (in /{target_dir} or built-in)\033[0m")
        visited.add(norm_name)
        return True

    if target_version:
        print(f"{indent}\033[36m[pip]\033[0m Collecting {raw_name}=={target_version}...")
        api_url = f"https://pypi.org/pypi/{raw_name}/{target_version}/json"
    else:
        print(f"{indent}\033[36m[pip]\033[0m Collecting {raw_name}...")
        api_url = f"https://pypi.org/pypi/{raw_name}/json"

    try:
        resp = requests.get(api_url, timeout=15)
    except Exception as e:
        print(f"{indent}\033[31m[pip] Network error:\033[0m {e}")
        return False

    if resp.status_code == 404:
        if target_version:
            print(f"{indent}\033[31m[pip] Error:\033[0m Package '{raw_name}' version '{target_version}' not found on PyPI.")
        else:
            print(f"{indent}\033[31m[pip] Error:\033[0m Package '{raw_name}' not found on PyPI.")
        return False
    elif not resp.ok:
        print(f"{indent}\033[31m[pip] Error:\033[0m PyPI returned HTTP status {resp.status_code}")
        return False

    try:
        data = resp.json()
    except Exception:
        print(f"{indent}\033[31m[pip] Error:\033[0m Invalid JSON response from PyPI.")
        return False

    info = data.get("info", {})
    available_version = target_version if target_version else info.get("version", "unknown")
    urls = data.get("urls", [])

    # Find pure-python wheel: *py3-none-any.whl or *py2.py3-none-any.whl or *none-any.whl
    selected_wheel = None
    for u in urls:
        fname = u.get("filename", "")
        if fname.endswith(".whl") and ("py3-none-any" in fname or "py2.py3-none-any" in fname or "none-any" in fname):
            selected_wheel = u
            break

    if not selected_wheel:
        print(f"{indent}\033[33m[pip] Warning:\033[0m '{raw_name}' {available_version} does not provide a pure-Python wheel (py3-none-any.whl).")
        print(f"{indent}Packages with native C-compiler extensions cannot be installed on in-game computers.")
        return False

    visited.add(norm_name)

    # 1. Parse and install dependencies (requires_dist)
    requires_dist = info.get("requires_dist", []) or []
    dependencies = []
    for req in requires_dist:
        dep_name = parse_requirement(req)
        if dep_name and normalize_name(dep_name) not in visited:
            dependencies.append(dep_name)

    if dependencies:
        print(f"{indent}\033[36m[pip]\033[0m Resolving dependencies for {raw_name}: {', '.join(dependencies)}")
        for dep in dependencies:
            dep_ok = install(dep, target_dir=target_dir, visited=visited, depth=depth + 1)
            if not dep_ok:
                print(f"{indent}\033[33m[pip] Note:\033[0m Dependency '{dep}' could not be installed, continuing with '{raw_name}'...")

    # 2. Download and extract package wheel
    wheel_name = selected_wheel.get("filename", "wheel.whl")
    wheel_url = selected_wheel.get("url")
    wheel_size = selected_wheel.get("size", 0)
    wheel_kb = max(1, wheel_size // 1024)

    print(f"{indent}\033[36m[pip]\033[0m Downloading {wheel_name} ({wheel_kb} KB)...")
    try:
        download_resp = requests.get(wheel_url, timeout=60)
    except Exception as e:
        print(f"{indent}\033[31m[pip] Download failed:\033[0m {e}")
        return False

    if not download_resp.ok:
        print(f"{indent}\033[31m[pip] Download error:\033[0m HTTP {download_resp.status_code}")
        return False

    raw_bytes = download_resp._raw.getBytes()
    cc_fs = sys.modules.get("cc_fs")
    if not cc_fs:
        print(f"{indent}\033[31m[pip] Error:\033[0m ComputerCraft filesystem bridge is unavailable.")
        return False

    print(f"{indent}\033[36m[pip]\033[0m Extracting into /{target_dir}...")
    try:
        extracted_files = list(WheelExtractor.extractFiles(raw_bytes, target_dir, cc_fs))
        files_count = len(extracted_files)

        # Record manifest for exact, clean uninstallation
        try:
            manifest_dir = f"{target_dir}/.installed"
            if not cc_fs.exists(manifest_dir):
                cc_fs.makeDir(manifest_dir)
            manifest_path = f"{manifest_dir}/{norm_name}.manifest"
            manifest_content = "\n".join(str(f) for f in extracted_files)
            cc_fs.writeAll(manifest_path, manifest_content)
        except Exception:
            pass

        print(f"{indent}\033[32m[pip] Successfully installed {raw_name}-{available_version} ({files_count} files in /{target_dir})\033[0m")
        return True
    except Exception as e:
        print(f"{indent}\033[31m[pip] Extraction error:\033[0m {e}")
        return False

def uninstall(package_name, target_dir="lib"):
    """Removes an installed package from /lib using manifest or fallback search."""
    package_name = package_name.strip()
    if not package_name:
        print("Error: No package name specified.")
        return False

    cc_fs = sys.modules.get("cc_fs")
    if not cc_fs or not cc_fs.exists(target_dir):
        print(f"Directory /{target_dir} does not exist.")
        return False

    norm_name = normalize_name(package_name)
    manifest_path = f"{target_dir}/.installed/{norm_name}.manifest"

    deleted_count = 0
    # 1. Manifest-based clean uninstallation
    if cc_fs.exists(manifest_path):
        content = cc_fs.readAll(manifest_path)
        if content:
            lines = [line.strip() for line in content.split("\n") if line.strip()]
            for f in lines:
                if cc_fs.exists(f):
                    cc_fs.delete(f)
                    deleted_count += 1

            # Clean up empty parent directories
            dirs_to_check = set()
            for f in lines:
                if "/" in f:
                    parent = f.rsplit("/", 1)[0]
                    if parent != target_dir:
                        dirs_to_check.add(parent)
            for d in sorted(dirs_to_check, key=len, reverse=True):
                try:
                    if cc_fs.exists(d) and cc_fs.isDir(d):
                        children = cc_fs.list(d)
                        if not children or len(children) == 0:
                            cc_fs.delete(d)
                except Exception:
                    pass

        cc_fs.delete(manifest_path)
        print(f"\033[32m[pip] Successfully uninstalled {package_name} ({deleted_count} files removed)\033[0m")
        return True

    # 2. Fallback heuristic uninstallation
    norm_underscore = norm_name.replace("-", "_")
    candidates = [
        f"{target_dir}/{package_name}",
        f"{target_dir}/{package_name}.py",
        f"{target_dir}/{norm_name}",
        f"{target_dir}/{norm_name}.py",
        f"{target_dir}/{norm_underscore}",
        f"{target_dir}/{norm_underscore}.py",
    ]

    deleted = False
    for c in candidates:
        if cc_fs.exists(c):
            cc_fs.delete(c)
            print(f"\033[32m[pip] Successfully uninstalled {c}\033[0m")
            deleted = True
            break

    if not deleted:
        print(f"\033[33m[pip] Package '{package_name}' not found in /{target_dir}.\033[0m")
        return False
    return True

def list_installed(target_dir="lib"):
    """Lists installed packages in /lib."""
    cc_fs = sys.modules.get("cc_fs")
    if not cc_fs or not cc_fs.exists(target_dir):
        print(f"Directory /{target_dir} is empty or does not exist.")
        return
    files = cc_fs.list(target_dir)
    print(f"\033[36mPackages installed in /{target_dir}:\033[0m")
    count = 0
    for f in sorted(files):
        if not f.startswith("."):
            print(f"  • {f}")
            count += 1
    if count == 0:
        print(f"  (no packages found in /{target_dir})")

if __name__ == "__main__":
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help", "help"):
        print("Usage:")
        print("  pip install <package>   Install a pure-Python library and its dependencies")
        print("  pip install <pkg>==<v>  Install a specific version of a package")
        print("  pip uninstall <package> Remove an installed library from /lib")
        print("  pip list                List packages installed in /lib")
    elif args[0] == "install":
        if len(args) < 2:
            print("Usage: pip install <package>")
        else:
            for pkg in args[1:]:
                install(pkg)
    elif args[0] == "uninstall":
        if len(args) < 2:
            print("Usage: pip uninstall <package>")
        else:
            for pkg in args[1:]:
                uninstall(pkg)
    elif args[0] == "list":
        list_installed()
    else:
        print(f"Unknown pip command: {args[0]}")
