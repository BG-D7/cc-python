# SPDX-FileCopyrightText: 2026 diff
"""In-game pip: installs pure-Python wheels from PyPI onto the computer's disk.

Packages go to /lib/python/site-packages (on sys.path for every script). Only wheels that need no compiled code
(``py3-none-any``) can be installed. Downloads follow the server's HTTP rules like any other request.
"""

import importlib
import io
import os
import re
import sys
import zipfile

import requests

SITE = "/lib/python/site-packages"
INDEX = "https://pypi.org/pypi"

# Distributions this mod already provides; a requirement on one of them is satisfied without downloading.
PROVIDED = {"requests", "websocket-client", "pip", "micropip"}

_C_INFO, _C_OK, _C_WARN, _C_ERR, _C_DIM, _C_END = "\033[36m", "\033[32m", "\033[33m", "\033[31m", "\033[90m", "\033[0m"


class PipError(Exception):
    pass


def normalize_name(name):
    return re.sub(r"[-_.]+", "-", name).lower()


# ------------------------------------------------------------------------------------------------- versions

_VERSION = re.compile(r"^v?(?:(\d+)!)?(\d+(?:\.\d+)*)(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\d*))?"
                      r"(?:-(\d+)|[-_.]?(?:post|rev|r)[-_.]?(\d*))?(?:[-_.]?dev[-_.]?(\d*))?(?:\+.*)?$", re.I)


def parse_version(text):
    """A sortable key for a PEP 440 version, or None if it is not one."""
    m = _VERSION.match(text.strip())
    if not m:
        return None
    epoch, rel, pre_l, pre_n, post_a, post_b, dev = m.groups()
    release = tuple(int(x) for x in rel.split("."))
    while len(release) > 1 and release[-1] == 0:
        release = release[:-1]
    pre = None
    if pre_l:
        pre_l = {"alpha": "a", "beta": "b", "c": "rc", "pre": "rc", "preview": "rc"}.get(pre_l.lower(), pre_l.lower())
        pre = ({"a": 0, "b": 1, "rc": 2}[pre_l], int(pre_n or 0))
    post = post_a if post_a is not None else post_b
    # Order: dev < pre < release < post.
    pre_key = (-1, 0) if dev is not None and pre is None and post is None else (pre or (3, 0))
    post_key = int(post or 0) if post is not None else -1
    dev_key = int(dev or 0) if dev is not None else float("inf")
    return int(epoch or 0), release, pre_key, post_key, dev_key


def is_prerelease(text):
    k = parse_version(text)
    return k is None or k[2][0] < 3 or k[4] != float("inf")


def _matches(version, spec):
    """Checks a version against a comma-separated specifier such as '>=1.2,<2'."""
    v = parse_version(version)
    if v is None:
        return False
    for part in filter(None, (p.strip() for p in spec.split(","))):
        m = re.match(r"^(~=|===|==|!=|<=|>=|<|>)\s*(.+)$", part)
        if not m:
            continue
        op, target = m.group(1), m.group(2).strip()
        if op == "===":
            if version != target:
                return False
            continue
        if op in ("==", "!=") and target.endswith(".*"):
            prefix = tuple(int(x) for x in target[:-2].split(".") if x.isdigit())
            ok = (v[1] + (0,) * len(prefix))[:len(prefix)] == prefix
            if ok != (op == "=="):
                return False
            continue
        t = parse_version(target)
        if t is None:
            return False
        if op == "~=":
            prefix = t[1][:-1] if len(t[1]) > 1 else t[1]
            if v < t or (v[1] + (0,) * len(prefix))[:len(prefix)] != prefix:
                return False
        elif not {"==": v == t, "!=": v != t, "<=": v <= t, ">=": v >= t, "<": v < t, ">": v > t}[op]:
            return False
    return True


# -------------------------------------------------------------------------------------------- requirements

def _environment(extras=()):
    vi = sys.version_info
    return {
        "python_version": "%d.%d" % (vi[0], vi[1]),
        "python_full_version": "%d.%d.%d" % (vi[0], vi[1], vi[2]),
        "implementation_name": sys.implementation.name,
        "implementation_version": "%d.%d.%d" % (vi[0], vi[1], vi[2]),
        "platform_python_implementation": "GraalVM",
        "sys_platform": "linux",
        "platform_system": "Linux",
        "os_name": "posix",
        "platform_machine": "x86_64",
        "platform_release": "",
        "platform_version": "",
        "extra": set(normalize_name(e) for e in extras),
    }


_MARKER_TOKEN = re.compile(r"\s*(\(|\)|'[^']*'|\"[^\"]*\"|===|==|!=|<=|>=|~=|<|>|not\s+in\b|in\b|and\b|or\b|[A-Za-z_.]+)")


def eval_marker(marker, extras=()):
    """Evaluates a PEP 508 environment marker; parts it cannot understand count as true."""
    env = _environment(extras)
    tokens = []
    pos = 0
    while marker[pos:].strip():
        m = _MARKER_TOKEN.match(marker, pos)
        if not m:
            return True
        tokens.append(re.sub(r"\s+", " ", m.group(1)))
        pos = m.end()
    i = 0

    def value():
        nonlocal i
        tok = tokens[i]
        i += 1
        if tok[0] in "'\"":
            return tok[1:-1]
        return env.get(tok, "")

    def compare():
        nonlocal i
        if tokens[i] == "(":
            i += 1
            r = disjunction()
            i += 1  # ")"
            return r
        left = value()
        op = tokens[i]
        i += 1
        right = value()
        if isinstance(left, set) or isinstance(right, set):
            names = left if isinstance(left, set) else right
            other = normalize_name(right if isinstance(left, set) else left)
            return (other in names) == (op == "==")
        if op in ("in", "not in"):
            return (left in right) == (op == "in")
        if op != "===" and parse_version(left) is not None and parse_version(right) is not None:
            return _matches(left, op + right)
        return {"==": left == right, "!=": left != right, "===": left == right}.get(op, True)

    def conjunction():
        nonlocal i
        r = compare()
        while i < len(tokens) and tokens[i] == "and":
            i += 1
            r = compare() and r
        return r

    def disjunction():
        nonlocal i
        r = conjunction()
        while i < len(tokens) and tokens[i] == "or":
            i += 1
            r = conjunction() or r
        return r

    try:
        return bool(disjunction())
    except (IndexError, KeyError, ValueError):
        return True


_REQ = re.compile(r"^\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*(?:\[([^\]]*)\])?\s*\(?([^;()]*)\)?\s*(?:;(.*))?$")


def parse_requirement(text):
    """'name[extra]>=1,<2; marker' -> (name, extras, spec, marker), or None if it is not a requirement."""
    m = _REQ.match(text)
    if not m:
        return None
    name, extras, spec, marker = m.groups()
    extras = tuple(e.strip() for e in (extras or "").split(",") if e.strip())
    return name, extras, re.sub(r"\s+", "", spec or ""), (marker or "").strip()


# ----------------------------------------------------------------------------------------------- installed

def _dist_infos():
    """normalized name -> (dist-info base name, version)."""
    try:
        names = os.listdir(SITE)
    except OSError:
        return {}
    out = {}
    for n in names:
        if n.endswith(".dist-info"):
            base = n[:-len(".dist-info")]
            name, _, version = base.rpartition("-")
            out[normalize_name(name)] = (base, version)
    return out


def installed_version(name):
    info = _dist_infos().get(normalize_name(name))
    return info[1] if info else None


# ------------------------------------------------------------------------------------------------- PyPI

def _get_json(url):
    try:
        resp = requests.get(url, timeout=30)
    except requests.RequestException as e:
        raise PipError("network error: %s" % e) from None
    if resp.status_code == 404:
        return None
    if not resp.ok:
        raise PipError("PyPI returned HTTP %d" % resp.status_code)
    try:
        return resp.json()
    except ValueError:
        raise PipError("PyPI returned invalid JSON") from None


_PURE_TAG = re.compile(r"-(py3\d*|py2\.py3|py3\.py2)-none-any\.whl$")


def _pure_wheel(files):
    for f in files or ():
        if f.get("packagetype") == "bdist_wheel" and _PURE_TAG.search(f.get("filename", "")) and not f.get("yanked"):
            return f
    return None


def find_release(name, spec, pre=False):
    """(version, release JSON, wheel file info) for the newest matching version with a pure-Python wheel."""
    data = _get_json("%s/%s/json" % (INDEX, name))
    if data is None:
        raise PipError("no package named '%s' on PyPI" % name)
    exact = "==" in spec
    candidates = []
    for version in (data.get("releases") or {}):
        if parse_version(version) is None or (spec and not _matches(version, spec)):
            continue
        if not pre and not exact and is_prerelease(version):
            continue
        candidates.append(version)
    candidates.sort(key=parse_version, reverse=True)
    native_only = None
    for version in candidates:
        wheel = _pure_wheel(data["releases"][version])
        if wheel is None:
            native_only = native_only or version
            continue
        if version == (data.get("info") or {}).get("version"):
            info = data
        else:
            info = _get_json("%s/%s/%s/json" % (INDEX, name, version)) or data
        return version, info, wheel
    if native_only:
        raise PipError("'%s' %s has no pure-Python wheel (py3-none-any); packages with compiled code cannot be "
                       "installed on in-game computers" % (name, native_only))
    raise PipError("no version of '%s' matches '%s'" % (name, spec or "any"))


# --------------------------------------------------------------------------------------------- install

_KEEP_META = ("METADATA", "entry_points.txt", "top_level.txt", "WHEEL")


def _write_wheel(content, wheel_name):
    """Unpacks a wheel into SITE and returns the files written. Leaves nothing behind if it fails."""
    try:
        zf = zipfile.ZipFile(io.BytesIO(content))
    except zipfile.BadZipFile:
        raise PipError("downloaded file %s is not a valid wheel" % wheel_name) from None
    written = []
    dist_info = None
    try:
        for entry in zf.infolist():
            name = entry.filename
            if entry.is_dir() or "__pycache__/" in name or name.startswith("/") or ".." in name.split("/"):
                continue
            top, _, rest = name.partition("/")
            if top.endswith(".data"):
                kind, _, rest = rest.partition("/")
                if kind not in ("purelib", "platlib"):
                    continue  # scripts, headers and data files have no place on a CC computer
                name = rest
            elif top.endswith(".dist-info"):
                dist_info = top
                if rest not in _KEEP_META:
                    continue  # licences and the like only cost disk space
            target = SITE + "/" + name
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "wb") as f:
                f.write(zf.read(entry))
            written.append(name)
        if dist_info is None:
            raise PipError("wheel %s has no .dist-info" % wheel_name)
        for meta, text in (("INSTALLER", "ccpython-pip\n"), ("RECORD", None)):
            path = "%s/%s" % (dist_info, meta)
            written.append(path)
            if text is None:
                text = "".join(n + ",,\n" for n in written)
            with open(SITE + "/" + path, "w") as f:
                f.write(text)
        return written
    except OSError as e:
        _remove_files(written)
        raise PipError("could not write %s: %s (is the disk full?)" % (wheel_name, e.strerror or e)) from None
    except BaseException:
        _remove_files(written)
        raise


def _remove_files(names):
    dirs = set()
    for n in names:
        try:
            os.remove(SITE + "/" + n)
        except OSError:
            pass
        d = os.path.dirname(n)
        while d:
            dirs.add(d)
            d = os.path.dirname(d)
    for d in sorted(dirs, key=len, reverse=True):
        try:
            os.rmdir(SITE + "/" + d)
        except OSError:
            pass


def install(requirement, upgrade=False, pre=False, no_deps=False, _seen=None, _depth=0):
    """Installs a requirement such as 'rich', 'six==1.16.0' or 'attrs>=23'. Returns True on success."""
    seen = set() if _seen is None else _seen
    indent = "  " * _depth
    req = parse_requirement(requirement)
    if req is None:
        print("%s%s[pip] Error:%s invalid requirement %r" % (indent, _C_ERR, _C_END, requirement))
        return False
    name, extras, spec, marker = req
    if marker and not eval_marker(marker):
        return True
    key = normalize_name(name)
    if key in seen:
        return True
    seen.add(key)
    if key in PROVIDED:
        print("%s%sRequirement already satisfied: %s (built in)%s" % (indent, _C_DIM, name, _C_END))
        return True
    have = installed_version(name)
    if have is not None and not upgrade and (not spec or _matches(have, spec)):
        print("%s%sRequirement already satisfied: %s %s%s" % (indent, _C_DIM, name, have, _C_END))
        return True
    try:
        print("%s%s[pip]%s Collecting %s%s" % (indent, _C_INFO, _C_END, name, spec))
        version, info, wheel = find_release(name, spec, pre)
        if have == version:
            print("%s%sRequirement already satisfied: %s %s%s" % (indent, _C_DIM, name, have, _C_END))
            return True
        ok = True
        if not no_deps:
            for dep in (info.get("info") or {}).get("requires_dist") or ():
                d = parse_requirement(dep)
                if d is None or (d[3] and not eval_marker(d[3], extras)):
                    continue
                dep_req = d[0] + ("[%s]" % ",".join(d[1]) if d[1] else "") + d[2]
                if not install(dep_req, pre=pre, _seen=seen, _depth=_depth + 1):
                    ok = False
        size = max(1, (wheel.get("size") or 0) // 1024)
        print("%s%s[pip]%s Downloading %s (%d KB)" % (indent, _C_INFO, _C_END, wheel["filename"], size))
        try:
            resp = requests.get(wheel["url"], timeout=120)
        except requests.RequestException as e:
            raise PipError("download failed: %s" % e) from None
        if not resp.ok:
            raise PipError("download failed: HTTP %d" % resp.status_code)
        if have is not None:
            uninstall(name, quiet=True)
        files = _write_wheel(resp.content, wheel["filename"])
        importlib.invalidate_caches()
        print("%s%sSuccessfully installed %s-%s (%d files)%s" % (indent, _C_OK, name, version, len(files), _C_END))
        return ok
    except PipError as e:
        print("%s%s[pip] Error:%s %s" % (indent, _C_ERR, _C_END, e))
        return False


def install_requirements(path, upgrade=False, pre=False, _seen=None):
    seen = set() if _seen is None else _seen
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        print("%s[pip] Error:%s cannot read %s: %s" % (_C_ERR, _C_END, path, e.strerror or e))
        return False
    ok = True
    base = os.path.dirname(path)
    for line in lines:
        line = re.sub(r"(^|\s)#.*$", "", line).strip()
        if not line:
            continue
        if line.startswith(("-r ", "--requirement ")):
            nested = line.split(None, 1)[1].strip()
            ok = install_requirements(os.path.join(base, nested), upgrade, pre, seen) and ok
        elif line.startswith("-"):
            print("%s[pip] Note:%s option %r is ignored" % (_C_WARN, _C_END, line))
        else:
            ok = install(line, upgrade=upgrade, pre=pre, _seen=seen) and ok
    return ok


def uninstall(name, quiet=False):
    info = _dist_infos().get(normalize_name(name))
    if info is None:
        if not quiet:
            print("%s[pip] %s is not installed%s" % (_C_WARN, name, _C_END))
        return False
    base, version = info
    try:
        with open("%s/%s.dist-info/RECORD" % (SITE, base), encoding="utf-8") as f:
            files = [line.split(",", 1)[0] for line in f.read().splitlines() if line.strip()]
    except OSError:
        files = []
    files += ["%s.dist-info/%s" % (base, meta) for meta in _KEEP_META + ("INSTALLER", "RECORD")]
    _remove_files(files)
    if not quiet:
        print("%sSuccessfully uninstalled %s-%s%s" % (_C_OK, name, version, _C_END))
    return True


def installed():
    """[(name, version)] of installed packages, sorted by name."""
    return sorted((base.rpartition("-")[0], version) for base, version in _dist_infos().values())


def list_installed():
    rows = installed()
    if not rows:
        print("No packages installed in %s" % SITE)
        return rows
    width = max(7, max(len(n) for n, _ in rows))
    print("%s%-*s  %s%s" % (_C_INFO, width, "Package", "Version", _C_END))
    for n, v in rows:
        print("%-*s  %s" % (width, n, v))
    return rows


def show(name):
    info = _dist_infos().get(normalize_name(name))
    if info is None:
        print("%s[pip] %s is not installed%s" % (_C_WARN, name, _C_END))
        return False
    try:
        with open("%s/%s.dist-info/METADATA" % (SITE, info[0]), encoding="utf-8", errors="replace") as f:
            head = f.read().split("\n\n", 1)[0]
    except OSError:
        head = ""
    for line in head.splitlines():
        if line.split(":", 1)[0] in ("Name", "Version", "Summary", "Home-page", "Author", "License", "Requires-Dist"):
            print(line)
    print("Location: %s" % SITE)
    return True


USAGE = """Usage:
  pip install <package> [...]     install pure-Python packages from PyPI
  pip install "<pkg>==<version>"  a specific version (also >=, <, ~=, !=)
  pip install -r <file>           install from a requirements file
  pip install -U <package>        upgrade
      --pre      allow pre-releases    --no-deps  skip dependencies
  pip uninstall <package> [...]   remove a package
  pip list | freeze               list installed packages
  pip show <package>              package details"""


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or args[0] in ("-h", "--help", "help"):
        print(USAGE)
        return 0
    cmd, rest = args[0], args[1:]
    if cmd in ("-V", "--version"):
        print("pip (CC: Python, pure-Python wheels from PyPI) into %s" % SITE)
        return 0
    if cmd in ("-r", "--requirement"):
        cmd, rest = "install", args
    if cmd == "install":
        upgrade = pre = no_deps = False
        reqs, files = [], []
        i = 0
        while i < len(rest):
            a = rest[i]
            if a in ("-U", "--upgrade"):
                upgrade = True
            elif a == "--pre":
                pre = True
            elif a == "--no-deps":
                no_deps = True
            elif a in ("-r", "--requirement"):
                i += 1
                if i >= len(rest):
                    print("pip: -r needs a file name")
                    return 2
                files.append(rest[i])
            elif a.startswith("-r") and len(a) > 2:
                files.append(a[2:])
            elif a.startswith("-"):
                print("pip: unknown option %s" % a)
                return 2
            else:
                reqs.append(a)
            i += 1
        if not reqs and not files:
            print("pip: nothing to install")
            return 2
        seen = set()
        ok = True
        for f in files:
            ok = install_requirements(f, upgrade, pre, seen) and ok
        for r in reqs:
            ok = install(r, upgrade=upgrade, pre=pre, no_deps=no_deps, _seen=seen) and ok
        return 0 if ok else 1
    if cmd == "uninstall":
        names = [a for a in rest if not a.startswith("-")]
        if not names:
            print("pip: uninstall needs a package name")
            return 2
        return 0 if all([uninstall(n) for n in names]) else 1
    if cmd == "list":
        list_installed()
        return 0
    if cmd == "freeze":
        for n, v in installed():
            print("%s==%s" % (n, v))
        return 0
    if cmd == "show":
        return 0 if rest and all([show(n) for n in rest]) else 1
    print("pip: unknown command '%s' (try pip help)" % cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main())
