# SPDX-FileCopyrightText: 2026 diff
# Python side of a CC: Python run. Evaluated once per context; the Java side calls
# _ccpy_boot_api.start(...) and then run() or, for the REPL, step() until it returns False.

import sys, io, types, builtins, traceback, codeop, linecache, posixpath, time as _time
import importlib.abc, importlib.machinery

_N = None  # the run's PyNative, set by start()
_MAX_DEPTH = 64


class LuaError(Exception):
    """An error raised by Lua code (a CC API call)."""


# ---------------------------------------------------------------------------------------------------------------
# value conversion (see PyNative for the token format)

def _enc(values):
    out = []
    for v in values:
        _enc1(v, out, 0)
    return out


def _enc1(v, out, depth):
    if depth > _MAX_DEPTH:
        raise ValueError("value is nested too deeply to pass to Lua")
    if v is None or isinstance(v, bool):
        out.append(v)
    elif isinstance(v, int):
        out.append(v if -(1 << 63) <= v < (1 << 63) else float(v))
    elif isinstance(v, float):
        out.append(v)
    elif isinstance(v, str):
        out.append("s"); out.append(v)
    elif isinstance(v, (bytes, bytearray, memoryview)):
        out.append("y"); out.append(bytes(v).decode("latin-1"))
    elif isinstance(v, LuaRef):
        out.append("r"); out.append(v._id)
    elif isinstance(v, dict):
        out.append("d"); out.append(len(v))
        for k, val in v.items():
            _enc1(k, out, depth + 1)
            _enc1(val, out, depth + 1)
    elif isinstance(v, (list, tuple)):
        out.append("l"); out.append(len(v))
        for item in v:
            _enc1(item, out, depth + 1)
    elif isinstance(v, (set, frozenset)):
        out.append("d"); out.append(len(v))
        for item in v:
            _enc1(item, out, depth + 1)
            out.append(True)
    elif callable(v):
        raise TypeError("Python functions cannot be passed to Lua")
    else:
        raise TypeError("cannot pass %s to Lua" % type(v).__name__)


def _dec(tokens):
    t = list(tokens)
    n = len(t)
    pos = 0

    def one():
        nonlocal pos
        v = t[pos]; pos += 1
        if v is None or isinstance(v, (bool, int, float)):
            return v
        if v == "s":
            s = t[pos]; pos += 1
            return s
        if v == "y":
            s = t[pos]; pos += 1
            return s.encode("latin-1")
        if v == "l":
            count = t[pos]; pos += 1
            return [one() for _ in range(count)]
        if v == "d":
            count = t[pos]; pos += 1
            d = {}
            for _ in range(count):
                k = one()
                val = one()
                if isinstance(k, list):
                    k = tuple(k)
                try:
                    d[k] = val
                except TypeError:
                    pass
            return d
        if v == "r":
            rid = t[pos]; kind = t[pos + 1]; nkeys = t[pos + 2]; pos += 3
            keys = t[pos:pos + nkeys]; pos += nkeys
            fields = one()
            return LuaRef(rid, kind, keys, fields)
        raise ValueError("bad value token %r" % (v,))

    out = []
    while pos < n:
        out.append(one())
    return out


class LuaMulti(tuple):
    """Several Lua return values. Truthy like the first one, so `if turtle.forward():` works."""

    def __bool__(self):
        return bool(self[0]) if self else False


def _ret(values):
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    return LuaMulti(values)


def _check(result):
    """result = [ok, values...] from the driver."""
    if result and result[0]:
        return result[1:]
    msg = result[1] if len(result) > 1 else "Lua error"
    if msg == "Terminated":
        raise KeyboardInterrupt
    raise LuaError(msg if isinstance(msg, str) else repr(msg))


def _call(target, field, args):
    return _ret(_check(_dec(_N.call(target, field, _enc(args)))))


def _camel(name):
    head, *rest = name.split("_")
    return head + "".join(p[:1].upper() + p[1:] for p in rest)


def _snake(name):
    out = []
    for ch in name:
        if ch.isupper():
            out.append("_"); out.append(ch.lower())
        else:
            out.append(ch)
    return "".join(out)


class LuaFunction:
    __slots__ = ("_target", "_field", "__name__")

    def __init__(self, target, field, name):
        self._target = target
        self._field = field
        self.__name__ = name

    def __call__(self, *args):
        return _call(self._target, self._field, args)

    def __repr__(self):
        return "<lua function %s>" % self.__name__


class LuaRef:
    """A Lua function or object (wrapped peripheral, window, term.current() ...) kept alive by the driver."""

    def __init__(self, rid, kind, keys, fields):
        d = self.__dict__
        d["_id"] = rid
        d["_kind"] = kind
        d["_keys"] = list(keys)
        for k in keys:
            d[k] = LuaFunction(rid, k, k)
            alias = _snake(k)
            if alias != k and alias not in d:
                d[alias] = d[k]
        if isinstance(fields, dict):
            for k, v in fields.items():
                if isinstance(k, str) and k not in d:
                    d[k] = v
                    alias = _snake(k)
                    if alias != k and alias not in d and alias not in fields:
                        d[alias] = v

    def __call__(self, *args):
        if self._kind != "f":
            raise TypeError("Lua object is not callable")
        return _call(self._id, None, args)

    def __getattr__(self, name):
        if not name.startswith("__") and "_" in name:
            camel = _camel(name)
            if camel in self.__dict__:
                return self.__dict__[camel]
        raise AttributeError(name)

    def __getitem__(self, name):
        if isinstance(name, str) and not name.startswith("_") and name in self.__dict__:
            return self.__dict__[name]
        raise KeyError(name)

    def __setattr__(self, name, value):
        raise AttributeError("Lua objects are read-only from Python")

    def __dir__(self):
        return [k for k in self.__dict__ if not k.startswith("_")]

    def __repr__(self):
        if self._kind == "f":
            return "<lua function>"
        return "<lua object: %s>" % ", ".join(sorted(self._keys)[:8])


def lua_eval(code, *args):
    """Run a chunk of Lua code in the program's environment and return its results."""
    return _ret(_check(_dec(_N.lua(code, _enc(args)))))


# ---------------------------------------------------------------------------------------------------------------
# CC APIs as Python modules

# Python module name -> Lua global. Names that clash with the standard library (os, io, http) live only under cc.*.
_TOP_LEVEL = {
    "turtle": "turtle", "redstone": "redstone", "rs": "rs", "term": "term", "colors": "colors", "colours": "colours",
    "peripheral": "peripheral", "rednet": "rednet", "gps": "gps", "textutils": "textutils", "settings": "settings",
    "disk": "disk", "keys": "keys", "window": "window", "paintutils": "paintutils", "shell": "shell",
    "multishell": "multishell", "commands": "commands", "pocket": "pocket", "vector": "vector", "help": "help",
    "cc_fs": "fs", "cc_os": "os",
}
_CC_ONLY = {"os": "os", "fs": "fs", "io": "io", "http": "http"}


def _sleep(seconds=0):
    if _N.sleep(float(seconds)):
        raise KeyboardInterrupt


def _pull_event_raw(filter=None):
    return LuaMulti(_dec(_N.pull_event(filter)))


def _pull_event(filter=None):
    ev = _pull_event_raw(filter)
    if ev and ev[0] == "terminate":
        raise KeyboardInterrupt
    return ev


def _queue_event(name, *args):
    _N.queue_event(name, _enc(args))


def _make_lua_module(modname, luaname):
    result = _dec(_N.get(luaname))
    value = result[1] if len(result) > 1 and result[0] else None
    if not isinstance(value, LuaRef):
        raise ModuleNotFoundError("No %r API on this computer" % luaname, name=modname)
    mod = types.ModuleType(modname, "CC %s API" % luaname)
    for k, v in value.__dict__.items():
        if not k.startswith("_"):
            setattr(mod, k, v)
    mod._lua = value
    if luaname == "os":
        # Events and sleeping are handled by the run itself; these would otherwise fight the driver for events.
        for k, v in (("sleep", _sleep), ("pullEvent", _pull_event), ("pull_event", _pull_event),
                     ("pullEventRaw", _pull_event_raw), ("pull_event_raw", _pull_event_raw),
                     ("queueEvent", _queue_event), ("queue_event", _queue_event)):
            setattr(mod, k, v)
    return mod


class _ModuleLoader(importlib.abc.Loader):
    def __init__(self, factory):
        self._factory = factory

    def create_module(self, spec):
        return self._factory(spec.name)

    def exec_module(self, module):
        pass


def _make_cc(name):
    mod = types.ModuleType("cc", "ComputerCraft APIs. `from cc import turtle, os, peripheral`.")
    mod.__path__ = []
    return mod


def _make_cc_lua(name):
    mod = types.ModuleType("cc.lua", "Direct access to Lua.")
    mod.eval = lua_eval
    mod.LuaError = LuaError
    mod.LuaRef = LuaRef
    mod.LuaMulti = LuaMulti
    return mod


def _make_ccpy(name):
    mod = types.ModuleType(name, "CC: Python runtime info.")
    mod.ru = lambda s: "" if s is None else str(s)  # Cyrillic now works everywhere; kept for old scripts
    mod.sandboxed = _N.sandboxed()
    mod.computer_id = _N.computer_id()
    return mod


class _CCFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "java" and _N is not None and _N.sandboxed():
            raise ModuleNotFoundError("No module named 'java' (Java access is off: the Python sandbox is on)",
                                      name=fullname)
        if fullname == "cc":
            factory = _make_cc
        elif fullname == "cc.lua":
            factory = _make_cc_lua
        elif fullname in ("ccpy", "kik"):  # kik: the old name, still used by course scripts
            factory = _make_ccpy
        elif fullname in _TOP_LEVEL:
            lua = _TOP_LEVEL[fullname]
            factory = lambda n, lua=lua: _make_lua_module(n, lua)
        elif fullname.startswith("cc.") and fullname.count(".") == 1:
            short = fullname[3:]
            lua = _CC_ONLY.get(short) or _TOP_LEVEL.get(short)
            if lua is None:
                return None
            factory = lambda n, lua=lua: _make_lua_module(n, lua)
        else:
            return None
        return importlib.machinery.ModuleSpec(fullname, _ModuleLoader(factory), is_package=(fullname == "cc"))


# ---------------------------------------------------------------------------------------------------------------
# text: sources may be UTF-8 or (written in CC's editor) in the server's Lua charset

def _text(data):
    if isinstance(data, str):
        return data
    data = bytes(data)
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return _N.from_lua_bytes(data.decode("latin-1"))


class _SourceLoader(importlib.machinery.SourceFileLoader):
    def source_to_code(self, data, path, *, _optimize=-1):
        return compile(_text(data), path, "exec", dont_inherit=True, optimize=_optimize)

    def get_source(self, fullname):
        return _text(self.get_data(self.get_filename(fullname)))


def _install_path_hook():
    details = [(_SourceLoader, importlib.machinery.SOURCE_SUFFIXES),
               (importlib.machinery.SourcelessFileLoader, importlib.machinery.BYTECODE_SUFFIXES)]
    if not _N.sandboxed():
        details.insert(0, (importlib.machinery.ExtensionFileLoader, importlib.machinery.EXTENSION_SUFFIXES))
    hook = importlib.machinery.FileFinder.path_hook(*details)
    sys.path_hooks[:] = [h for h in sys.path_hooks if "FileFinder" not in getattr(h, "__qualname__", "")]
    sys.path_hooks.append(hook)
    sys.path_importer_cache.clear()


# ---------------------------------------------------------------------------------------------------------------
# standard streams

class _Out(io.TextIOBase):
    def __init__(self, err):
        self._err = err

    @property
    def encoding(self):
        return "utf-8"

    @property
    def errors(self):
        return "replace"

    def writable(self):
        return True

    def isatty(self):
        return False

    def write(self, s):
        if not isinstance(s, str):
            raise TypeError("write() argument must be str, not %s" % type(s).__name__)
        if s:
            _N.write(s, self._err)
        return len(s)

    def flush(self):
        pass


def _read_line(history=False):
    """CC's read(); None at end of input. Ctrl+T raises KeyboardInterrupt."""
    result = _dec(_N.readline(history))
    if not result[0]:
        if result[1] == "Terminated":
            raise KeyboardInterrupt
        raise LuaError(result[1])
    return result[1] if len(result) > 1 else None


class _In(io.TextIOBase):
    @property
    def encoding(self):
        return "utf-8"

    def readable(self):
        return True

    def isatty(self):
        return False

    def readline(self, size=-1):
        line = _read_line()
        return "" if line is None else line + "\n"

    def read(self, size=-1):
        return self.readline()


# ---------------------------------------------------------------------------------------------------------------
# errors

def _print_exception(exc):
    tb = exc.__traceback__
    while tb is not None and tb.tb_frame.f_code.co_filename == "<ccpy>":
        tb = tb.tb_next
    try:
        te = traceback.TracebackException(type(exc), exc, tb, compact=True)
        _hide_internal_frames(te)
        text = "".join(te.format())
    except Exception:
        # Never lose the program's own error because formatting it failed: fall back to a plain traceback.
        lines = ["Traceback (most recent call last):\n"]
        while tb is not None:
            code = tb.tb_frame.f_code
            lines.append('  File "%s", line %s, in %s\n' % (code.co_filename, tb.tb_lineno, code.co_name))
            src = linecache.getline(code.co_filename, tb.tb_lineno).strip()
            if src:
                lines.append("    %s\n" % src)
            tb = tb.tb_next
        lines.append(_summary(exc) + "\n")
        text = "".join(lines)
    sys.stderr.write(text)


def _hide_internal_frames(te, seen=None):
    """Drop frames of this boot module (import hooks, wrappers) anywhere in the traceback and its chain."""
    seen = set() if seen is None else seen
    while te is not None and id(te) not in seen:
        seen.add(id(te))
        te.stack[:] = [f for f in te.stack if f.filename != "<ccpy>"]
        for sub in getattr(te, "exceptions", None) or ():
            _hide_internal_frames(sub, seen)
        if te.__cause__ is not None:
            _hide_internal_frames(te.__cause__, seen)
        te = te.__context__ if not te.__suppress_context__ else None


# East Asian wide/fullwidth ranges, for terminals that render them two cells wide.
_WIDE = ((0x1100, 0x115F), (0x2E80, 0x303E), (0x3041, 0x33FF), (0x3400, 0x4DBF), (0x4E00, 0x9FFF),
         (0xA000, 0xA4CF), (0xAC00, 0xD7A3), (0xF900, 0xFAFF), (0xFE30, 0xFE4F), (0x1F300, 0x1F64F),
         (0x1F900, 0x1F9FF), (0x20000, 0x3FFFD))


def _east_asian_width(ch):
    o = ord(ch)
    if 0xFF01 <= o <= 0xFF60 or 0xFFE0 <= o <= 0xFFE6:
        return "F"
    if 0xFF61 <= o <= 0xFFDC or 0xFFE8 <= o <= 0xFFEE:
        return "H"
    for lo, hi in _WIDE:
        if lo <= o <= hi:
            return "W"
    return "Na" if o < 0x7F else "N"


def _patch_unicodedata():
    """GraalPy delegates east_asian_width to a native module that the sandbox cannot load; traceback needs it for
    any source line with non-ASCII text (Cyrillic). Use a pure-Python table instead."""
    import unicodedata
    try:
        unicodedata.east_asian_width("ж")
    except Exception:
        unicodedata.east_asian_width = _east_asian_width


def _summary(exc):
    msg = str(exc)
    return "%s: %s" % (type(exc).__name__, msg) if msg else type(exc).__name__


def _exit_code(e):
    """(ok, message, printed) for a SystemExit, following CPython: None/0 succeed, other ints fail quietly."""
    code = e.code
    if code is None or code == 0:
        return True, None, False
    if isinstance(code, int):
        return False, "Exit status %d" % code, False
    sys.stderr.write("%s\n" % (code,))
    return False, str(code), True


def _remember_source(filename, source):
    # Tracebacks then show lines even for files in the Lua charset, -c code and the REPL.
    linecache.cache[filename] = (len(source), None, source.splitlines(True), filename)


# ---------------------------------------------------------------------------------------------------------------
# sessions

class _Session:
    def __init__(self, mode, source, name, argv, cwd):
        self.mode = mode
        self.source = source
        self.name = name
        cwd = "/" + (cwd or "").strip("/")
        try:
            import os
            os.chdir(cwd)
        except OSError:
            pass

        main = types.ModuleType("__main__")
        main.__builtins__ = builtins
        sys.modules["__main__"] = main
        self.ns = main.__dict__

        if mode == "FILE":
            path = posixpath.normpath(posixpath.join(cwd, name))
            main.__file__ = path
            sys.argv = [path] + list(argv)
            sys.path.insert(0, posixpath.dirname(path))
        else:
            sys.argv = (["-c"] if mode == "CODE" else [""]) + list(argv)
            sys.path.insert(0, "")
        self.buf = []

    def _source(self):
        if self.mode != "FILE":
            return self.source
        with open(self.ns["__file__"], "rb") as f:
            return _text(f.read())

    def run(self):
        """[ok, error, printed, value-tokens]"""
        try:
            if self.mode == "EVAL":
                _remember_source("<eval>", self.source)
                code = compile(self.source, "<eval>", "eval")
                return [True, None, False, _enc([eval(code, self.ns)])]
            filename = self.ns.get("__file__", "<string>")
            source = self._source()
            _remember_source(filename, source)
            exec(compile(source, filename, "exec"), self.ns)
            return [True, None, False, None]
        except SystemExit as e:
            ok, msg, printed = _exit_code(e)
            return [ok, msg, printed, None]
        except KeyboardInterrupt:
            return [False, "Terminated", False, None]
        except BaseException as e:
            _print_exception(e)
            return [False, _summary(e), True, None]
        finally:
            _flush_std()

    def step(self):
        """One REPL line; False to leave the REPL."""
        prompt = "... " if self.buf else ">>> "
        sys.stdout.write("\033[33m" + prompt + "\033[0m")
        try:
            line = _read_line(history=True)
        except KeyboardInterrupt:
            if not self.buf:
                sys.stdout.write("\n")
                return False
            self.buf = []
            sys.stderr.write("KeyboardInterrupt\n")
            return True
        if line is None:
            return False
        self.buf.append(line)
        source = "\n".join(self.buf)
        try:
            code = codeop.compile_command(source, "<stdin>", "single")
        except (SyntaxError, OverflowError, ValueError) as e:
            self.buf = []
            _print_exception(e)
            return True
        if code is None:
            return True
        # Like CPython's REPL, a compound statement (def/if/for/class/@decorator ...) ends with an empty line,
        # whatever the compiler thinks of the lines so far.
        first = self.buf[0].split("#", 1)[0].rstrip()
        if line.strip() and (first.endswith(":") or first.lstrip().startswith("@")):
            return True
        self.buf = []
        _remember_source("<stdin>", source + "\n")
        try:
            exec(code, self.ns)
        except SystemExit as e:
            ok, msg, printed = _exit_code(e)
            return False
        except KeyboardInterrupt:
            sys.stderr.write("KeyboardInterrupt\n")
        except BaseException as e:
            _print_exception(e)
        finally:
            _flush_std()
        return True


def _flush_std():
    for s in (sys.stdout, sys.stderr):
        try:
            s.flush()
        except Exception:
            pass


def _block_sockets():
    """Raw sockets are off in the sandbox; say so with a normal OSError instead of an internal GraalPy failure."""
    import socket

    def denied(*args, **kwargs):
        raise PermissionError(13, "network sockets are disabled on this server; use the requests or websocket "
                                  "modules (they follow the server's HTTP rules)")

    for name in ("getaddrinfo", "gethostbyname", "gethostbyname_ex", "gethostbyaddr", "getnameinfo",
                 "create_connection", "create_server", "socketpair", "fromfd"):
        if hasattr(socket, name):
            setattr(socket, name, denied)
    socket.socket.__init__ = denied


class _BootApi:
    def start(self, native, mode, source, name, argv, cwd):
        global _N
        _N = native
        sys.stdout = sys.__stdout__ = _Out(False)
        sys.stderr = sys.__stderr__ = _Out(True)
        sys.stdin = sys.__stdin__ = _In()
        # GraalPy's hook wraps "pip" for its bundled pip; ours is our own module, and the wrapper breaks `-m pip`.
        sys.meta_path[:] = [f for f in sys.meta_path if getattr(f, "__name__", "") != "PipImportHook"]
        sys.meta_path.insert(0, _CCFinder())
        if native.sandboxed():
            sys.modules.pop("java", None)
        _patch_unicodedata()
        _install_path_hook()
        _time.sleep = _sleep

        # Our modules (requests, sqlite3 ...) live in CC's ROM and must win over the stdlib's native-only versions.
        # Server-wide libraries and what pip installed on this computer come after the stdlib, as site-packages do.
        if "/rom/python" not in sys.path:
            sys.path.insert(0, "/rom/python")
        for p in ("/rom/python-libs", "/lib/python", "/lib/python/site-packages"):
            if p not in sys.path:
                sys.path.append(p)
        if native.sandboxed():
            _block_sockets()
        for b in ("exit", "quit"):
            if not hasattr(builtins, b):
                setattr(builtins, b, sys.exit)

        import _ccpy
        _ccpy._native = native
        _ccpy.LuaError = LuaError
        return _Session(mode, source, name, list(argv), cwd)


# _ccpy: a tiny private module shared with the helper modules in /rom/python.
sys.modules["_ccpy"] = types.ModuleType("_ccpy")
sys.modules["_ccpy"]._enc = _enc
sys.modules["_ccpy"]._dec = _dec
sys.modules["_ccpy"].text = _text
_ccpy_boot_api = _BootApi()
