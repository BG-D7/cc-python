-- SPDX-FileCopyrightText: 2026 diff
--- Run Python 3 (GraalPy) programs on this computer.
--
-- Python runs on its own server thread; this API is the Lua side of a run. It draws the program's output with
-- CC's own write(), runs the Lua calls the program makes (turtle, peripherals, read() ...) and hands it the
-- computer's events, so a Python program behaves like a Lua one: it can be terminated with Ctrl+T, it shares the
-- terminal with term.redirect and multishell, and it never freezes the computer.
--
-- @module python

local native = _G._python

local function pack(...) return { n = select("#", ...), ... } end

local function now() return os.epoch("utc") / 1000 end

--- Drive one run until it finishes. Returns ok, error, value, printed.
local function drive(id, env)
    local refs, refIds, nextRef = {}, {}, 0
    local defaultFg, defaultBg = term.getTextColour(), term.getBackgroundColour()
    local history = {}

    local function refId(v)
        local rid = refIds[v]
        if rid == nil then
            nextRef = nextRef + 1
            rid = nextRef
            refs[rid], refIds[v] = v, rid
        end
        return rid
    end

    -- Lua value -> something CC can hand to Java. Functions, and tables that carry functions or a metatable,
    -- stay here and travel as references the program can call back.
    local export
    export = function(v, depth, seen)
        local t = type(v)
        if t == "function" then return { __kik_ref = refId(v), kind = "function" } end
        if t == "thread" or t == "userdata" then return tostring(v) end
        if t ~= "table" then return v end
        depth, seen = depth or 0, seen or {}
        local mt = getmetatable(v)
        local object = type(mt) == "table" and (mt.__index ~= nil or mt.__call ~= nil)
        local keys, named = {}, {}
        for k, x in pairs(v) do
            if type(x) == "function" and type(k) == "string" then
                keys[#keys + 1], named[k] = k, true
                object = true
            end
        end
        -- Methods inherited through the metatable (vector and other class-style objects).
        local index = type(mt) == "table" and rawget(mt, "__index")
        if type(index) == "table" then
            for k, x in pairs(index) do
                if type(x) == "function" and type(k) == "string" and not named[k] then
                    keys[#keys + 1], named[k] = k, true
                end
            end
        end
        if seen[v] or depth > 32 then
            return { __kik_ref = refId(v), kind = "table", keys = keys }
        end
        seen[v] = true
        local out = {}
        for k, x in pairs(v) do
            if type(x) ~= "function" and type(k) ~= "table" and type(k) ~= "function" then
                out[k] = export(x, depth + 1, seen)
            end
        end
        seen[v] = nil
        if object then return { __kik_ref = refId(v), kind = "table", keys = keys, fields = out } end
        return out
    end

    -- Value from Java -> Lua: references become the original Lua values again.
    local function import(v)
        if type(v) ~= "table" then return v end
        if v.__kik_ref ~= nil then return refs[v.__kik_ref] end
        local out = {}
        for k, x in pairs(v) do out[k] = import(x) end
        return out
    end

    local function resolve(path)
        local v = env
        for part in string.gmatch(path, "[^%.]+") do
            if type(v) ~= "table" then return nil end
            v = v[part]
        end
        return v
    end

    local function reply(r)
        local out = { n = r.n, r[1] }
        for i = 2, r.n do out[i] = export(r[i]) end
        native.reply(id, table.unpack(out, 1, out.n))
    end

    local function callable(f)
        if type(f) == "function" then return true end
        local mt = type(f) == "table" and getmetatable(f)
        return type(mt) == "table" and mt.__call ~= nil
    end

    local function setColour(set, c)
        if c == 0 then c = set == term.setTextColour and defaultFg or defaultBg end
        pcall(set, c)
    end

    local function draw(ops)
        for i = 1, #ops, 2 do
            local op, arg = ops[i], ops[i + 1]
            if op == "t" then
                write(arg)
            elseif op == "e" then
                local old = term.getTextColour()
                if old == defaultFg and term.isColour() then term.setTextColour(colours.red) end
                write(arg)
                term.setTextColour(old)
            elseif op == "cr" then
                local _, y = term.getCursorPos()
                term.setCursorPos(1, y)
            elseif op == "fg" then
                setColour(term.setTextColour, arg)
            elseif op == "bg" then
                setColour(term.setBackgroundColour, arg)
            elseif op == "cls" then
                term.clear()
            elseif op == "el" then
                local x, y = term.getCursorPos()
                local w = term.getSize()
                if x <= w then
                    term.write(string.rep(" ", w - x + 1))
                    term.setCursorPos(x, y)
                end
            elseif op == "pos" then
                term.setCursorPos(arg[1], arg[2])
            end
        end
    end

    local heartbeat, heartbeatAt = nil, -math.huge
    while true do
        while true do
            local r = pack(native.poll(id))
            local kind = r[1]
            if kind == "idle" then
                break
            elseif kind == "done" then
                return r[2], r[3], r[4], r[5]
            elseif kind == "out" then
                draw(r[2])
            elseif kind == "call" then
                local f = type(r[2]) == "number" and refs[r[2]] or resolve(r[2])
                if r[3] ~= nil then f = type(f) == "table" and f[r[3]] or nil end
                if callable(f) then
                    local args = import(r[4] or {})
                    reply(pack(pcall(f, table.unpack(args, 1, r[5] or 0))))
                else
                    local what = type(r[2]) == "number" and "object" or r[2]
                    if r[3] ~= nil then what = what .. "." .. r[3] end
                    native.reply(id, false, "attempt to call a " .. type(f) .. " value (" .. what .. ")")
                end
            elseif kind == "get" then
                native.reply(id, true, export(resolve(r[2])))
            elseif kind == "lua" then
                local fn, err = load(r[2], "=lua", "t", env)
                if fn then
                    local args = import(r[3] or {})
                    reply(pack(pcall(fn, table.unpack(args, 1, r[4] or 0))))
                else
                    native.reply(id, false, err)
                end
            elseif kind == "read" then
                for _, ev in ipairs(r[3] or {}) do os.queueEvent(table.unpack(ev)) end
                local ok, line = pcall(read, nil, r[2] and history or nil)
                if ok and r[2] and line and line:find("%S") and history[#history] ~= line then
                    history[#history + 1] = line
                end
                native.reply(id, ok, line)
            else
                native.reply(id, false, "unknown request " .. tostring(kind))
            end
        end

        -- A periodic timer keeps the run's watchdog fed while the program computes quietly.
        if now() - heartbeatAt > 6 then
            heartbeat, heartbeatAt = os.startTimer(5), now()
        end
        local ev = pack(os.pullEventRaw())
        if not (ev[1] == "timer" and ev[2] == heartbeat) then
            for i = 2, ev.n do ev[i] = export(ev[i]) end
            native.event(id, table.unpack(ev, 1, ev.n))
        end
    end
end

local function start(mode, source, name, args, env)
    if not native then return false, "Python is not available on this computer", nil, false end
    env = env or _G
    local cwd = ""
    if type(env.shell) == "table" and type(env.shell.dir) == "function" then cwd = env.shell.dir() end
    local id, err = native.start(mode, source, name, args or {}, cwd)
    if not id then return false, err, nil, false end
    local ok, a, b, c, d = pcall(drive, id, env)
    if not ok then
        native.kill(id, "Lua side failed: " .. tostring(a))
        return false, a, nil, false
    end
    return a, b, c, d
end

--- Run Python source code.
-- @tparam string code Python source (UTF-8, or the terminal's encoding).
-- @tparam[opt] {string...} args Program arguments (`sys.argv[1:]`).
-- @tparam[opt] table env Lua environment the program's Lua calls run in (default `_G`; pass `_ENV` from a program
-- so `shell` and `require` are visible).
-- @treturn boolean Whether the program succeeded.
-- @treturn string|nil The error, if any.
-- @treturn boolean Whether the error (traceback) was already printed.
function execute(code, args, env)
    local ok, err, _, printed = start("code", code, "<string>", args, env)
    return ok, err, printed
end

--- Run a Python file. Relative paths are resolved against the shell's directory, imports also look next to it.
-- @tparam string path The script.
-- @tparam[opt] {string...} args Program arguments.
-- @tparam[opt] table env See @{execute}.
-- @treturn boolean, string|nil, boolean As @{execute}.
function run(path, args, env)
    local ok, err, _, printed = start("file", "", path, args, env)
    return ok, err, printed
end

--- Evaluate a Python expression and return its value converted to Lua.
-- @tparam string expr The expression.
-- @tparam[opt] table env See @{execute}.
-- @treturn boolean Whether evaluation succeeded.
-- @return The value, or the error message.
function eval(expr, env)
    local ok, err, value = start("eval", expr, "<eval>", {}, env)
    if ok then return true, value end
    return false, err
end

--- The interactive interpreter. Variables persist between lines; Ctrl+T on an empty line (or `exit()`) leaves it.
-- @tparam[opt] table env See @{execute}.
-- @treturn boolean, string|nil As @{execute}.
function repl(env)
    local ok, err, _, printed = start("repl", "", "<stdin>", {}, env)
    return ok, err, printed
end

--- @treturn string The Python version.
function version()
    return native and native.version() or "Python is not available"
end

--- @treturn boolean Whether Python programs are sandboxed on this server (they are unless the admin disabled it).
function sandboxed()
    return native == nil or native.sandboxed()
end
