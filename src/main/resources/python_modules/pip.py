# SPDX-FileCopyrightText: 2026 diff
# Lightweight in-game pip / micropip package installer for ComputerCraft (GraalPy)

import sys
import java
import requests

WheelExtractor = java.type("work.diffdev.kik.python.pip.WheelExtractor")

def install(package_name, target_dir="lib"):
    package_name = package_name.strip()
    if not package_name:
        print("Error: No package name specified.")
        return False

    print(f"\033[36m[pip]\033[0m Looking up '{package_name}' on PyPI...")
    api_url = f"https://pypi.org/pypi/{package_name}/json"

    try:
        resp = requests.get(api_url, timeout=15)
    except Exception as e:
        print(f"\033[31m[pip] Network error:\033[0m {e}")
        return False

    if resp.status_code == 404:
        print(f"\033[31m[pip] Error:\033[0m Package '{package_name}' not found on PyPI.")
        return False
    elif not resp.ok:
        print(f"\033[31m[pip] Error:\033[0m PyPI returned HTTP status {resp.status_code}")
        return False

    try:
        data = resp.json()
    except Exception:
        print("\033[31m[pip] Error:\033[0m Invalid JSON response from PyPI.")
        return False

    info = data.get("info", {})
    version = info.get("version", "unknown")
    urls = data.get("urls", [])

    # Find a pure-python wheel (*py3-none-any.whl or *py2.py3-none-any.whl or *none-any.whl)
    selected_wheel = None
    for u in urls:
        fname = u.get("filename", "")
        if fname.endswith(".whl") and ("py3-none-any" in fname or "py2.py3-none-any" in fname or "none-any" in fname):
            selected_wheel = u
            break

    if not selected_wheel:
        print(f"\033[33m[pip] Warning:\033[0m '{package_name}' {version} does not provide a pure-Python wheel (py3-none-any.whl).")
        print("Packages requiring native C-compiler extensions cannot be installed on in-game computers.")
        return False

    wheel_name = selected_wheel.get("filename", "wheel.whl")
    wheel_url = selected_wheel.get("url")
    wheel_size = selected_wheel.get("size", 0)
    wheel_kb = max(1, wheel_size // 1024)

    print(f"\033[36m[pip]\033[0m Downloading {wheel_name} ({wheel_kb} KB)...")
    try:
        download_resp = requests.get(wheel_url, timeout=60)
    except Exception as e:
        print(f"\033[31m[pip] Download failed:\033[0m {e}")
        return False

    if not download_resp.ok:
        print(f"\033[31m[pip] Download error:\033[0m HTTP {download_resp.status_code}")
        return False

    raw_bytes = download_resp._raw.getBytes()
    cc_fs = sys.modules.get("cc_fs")
    if not cc_fs:
        print("\033[31m[pip] Error:\033[0m ComputerCraft filesystem bridge is unavailable.")
        return False

    print(f"\033[36m[pip]\033[0m Extracting into /{target_dir}...")
    try:
        files_count = WheelExtractor.extract(raw_bytes, target_dir, cc_fs)
        print(f"\033[32m[pip] Successfully installed {package_name}-{version} ({files_count} files in /{target_dir})\033[0m")
        return True
    except Exception as e:
        print(f"\033[31m[pip] Extraction error:\033[0m {e}")
        return False

def list_installed(target_dir="lib"):
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
        print("  pip install <package>   Install a pure-Python library from PyPI")
        print("  pip list                List packages installed in /lib")
    elif args[0] == "install":
        if len(args) < 2:
            print("Usage: pip install <package>")
        else:
            for pkg in args[1:]:
                install(pkg)
    elif args[0] == "list":
        list_installed()
    else:
        print(f"Unknown pip command: {args[0]}")
