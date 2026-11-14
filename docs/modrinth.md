**CC: Python** adds a second language to [CC: Tweaked](https://modrinth.com/mod/cc-tweaked) computers and turtles: **Python 3.13** (GraalPy 25.5).
One jar for Minecraft 26.2 runs on both **Fabric** and **NeoForge**. It is self-contained: GraalPy, the standard
library and SQLite are inside, so you need no other Python mods and no Python installed.

It grew out of a CC: Tweaked programming course and is made for classrooms and community servers.

## Features

- **Python feels native to CC.** A Python program writes to the terminal through `term.redirect`, monitors and
  `multishell`, stops on Ctrl+T, and runs in its own thread so it never freezes the computer.
- **Sandboxed by default.** A script sees only its computer's disk and the ROM: no server files, Java, processes or
  raw sockets. One config key turns it off if you know what you are doing.
- **`requests` and `websocket`** follow the same HTTP rules as CC's Lua `http` API (allow/deny lists, size limits).
- **`sqlite3`** with the database on the computer's disk: counts towards the quota and moves with the computer.
- **REPL** with persistent variables, expression echo, multi-line blocks and history.
- **`pip install`** of pure-Python packages (`py3-none-any` wheels) from PyPI onto the computer, with dependencies
  and `requirements.txt`.
- **Readable errors:** tracebacks with line numbers, printed once.
- **All CC APIs as Python modules:** `import turtle, term, colors, peripheral, rednet…`, both `turnRight()` and
  `turn_right()` work. From Lua, the `python` API runs Python files and code and returns values as tables.
- **Cyrillic everywhere** (CP1251 / UTF-8), with an optional Cyrillic terminal font resource pack.

## Installation

1. Minecraft **26.2** on **Java 25**, **Fabric** or **NeoForge**, with **CC: Tweaked 1.120.2**.
2. Put `cc-python-<version>-aio.jar` into `mods/`. That's the only file.
3. On first start the runtime (~25 MB) is unpacked to `~/.ccpython-cache/`. Then `python` works on every computer
   and turtle.

The mod is needed on the server only (in singleplayer, on the client). Players joining a server don't need it.

```text
> python
Python 3.13 (GraalPy 25.5) for CC: Tweaked
>>> import turtle
>>> turtle.forward()
True
>>> exit()
> python rover.py arg1
> pip install six
```

## Cyrillic font

CC's default font has no Cyrillic. The optional resource pack `cc-python-cyrillic-<version>.zip` (attached to each
version here and on [GitHub Releases](https://github.com/BG-D7/cc-python/releases)) adds Cyrillic at CP1251 positions; a server can push it
with `resource-pack=` in `server.properties`. Without it, set `text.encoding = latin1` in `config/ccpython/config.properties`.
The font is based on the CC: Tweaked font and the [CCCyrillic](https://modrinth.com/resourcepack/cccyrillic) glyphs
and is licensed under the CCPL, separately from the mod.

## Links

- Source, docs (in Russian) and issues: [github.com/BG-D7/cc-python](https://github.com/BG-D7/cc-python)
- License: GNU LGPL v3. Bundled components and their licenses: [THIRD-PARTY-NOTICES.md](https://github.com/BG-D7/cc-python/blob/main/THIRD-PARTY-NOTICES.md)
