local results = {}
local function log(ok, name, detail)
    results[#results + 1] = (ok and "PASS " or "FAIL ") .. name .. (detail and (": " .. tostring(detail)) or "")
end

-- Run fn with output going to a hidden window; returns the visible text.
local W, H = 51, 40
local function capture(fn)
    local win = window.create(term.current(), 1, 1, W, H, false)
    local old = term.redirect(win)
    local ok, a, b, c = pcall(fn)
    term.redirect(old)
    local lines = {}
    for y = 1, H do lines[#lines + 1] = (win.getLine(y)):gsub("%s+$", "") end
    while #lines > 0 and lines[#lines] == "" do lines[#lines] = nil end
    return table.concat(lines, "\n"), ok, a, b, c
end

local function test(name, fn)
    local p = fs.open("progress.log", "a")
    p.writeLine(os.epoch("utc") .. " " .. name)
    p.close()
    local ok, err = pcall(fn)
    if not ok then log(false, name, err) end
end

local function check(name, cond, detail) log(cond and true or false, name, (not cond) and detail or nil) end

test("print-cyrillic", function()
    local out, _, ok, err = capture(function() return python.execute("print('Привет, мир!', 2 + 3)") end)
    check("print-cyrillic", ok and out == "Привет, мир! 5", out .. " / " .. tostring(err))
end)

test("shell-run-import", function()
    local out = capture(function() shell.run("python", "app/main.py", "x", "ё") end)
    check("shell-run-import", out == "помощник ['x', 'ё']", out)
end)

test("traceback", function()
    local out = capture(function() shell.run("python", "app/bad.py") end)
    local _, n = out:gsub("ZeroDivisionError", "")
    check("traceback", n == 1 and out:find("line 4") and out:find("line 6"), out)
end)

test("terminate", function()
    local t0, ok, err = os.clock()
    parallel.waitForAny(
        function() ok, err = python.execute("while True:\n    pass\n") end,
        function() sleep(2); os.queueEvent("terminate"); while true do os.pullEventRaw() end end)
    check("terminate", ok == false and err == "Terminated" and os.clock() - t0 < 10,
        tostring(ok) .. " " .. tostring(err) .. " " .. (os.clock() - t0))
end)

test("cc-not-blocked", function()
    local ticks = 0
    parallel.waitForAny(
        function() python.execute("x = 0\nwhile True:\n    x += 1\n") end,
        function() for _ = 1, 20 do sleep(0.1); ticks = ticks + 1 end; os.queueEvent("terminate"); while true do os.pullEventRaw() end end)
    check("cc-not-blocked", ticks == 20, ticks)
end)

test("time-sleep", function()
    local t0 = os.epoch("utc")
    local ok = python.execute("import time\ntime.sleep(1)\n")
    local dt = os.epoch("utc") - t0
    check("time-sleep", ok and dt >= 900 and dt < 4000, dt)
end)

test("sqlite", function()
    local ok1, e1 = python.execute([[
import sqlite3
con = sqlite3.connect('game.db')
con.execute('create table if not exists t (name text)')
con.execute('delete from t')
con.execute('insert into t values (?)', ('кот',))
con.commit()
con.close()
]])
    local out, _, ok2, e2 = capture(function() return python.execute([[
import sqlite3
print(sqlite3.connect('game.db').execute('select name from t').fetchone()[0])
]]) end)
    check("sqlite", ok1 and ok2 and fs.exists("game.db") and fs.getSize("game.db") > 0 and out == "кот",
        tostring(e1) .. " / " .. tostring(e2) .. " / " .. out)
end)

test("repl", function()
    local out = capture(function()
        parallel.waitForAll(
            function() python.repl(_ENV) end,
            function()
                -- All typed at once, before the interpreter is up: typeahead must not be lost.
                for _, line in ipairs({ "x = 5", "x * 2", "s = 'щука'", "s.upper()", "exit()" }) do
                    os.queueEvent("paste", line)
                    os.queueEvent("key", keys.enter, false)
                end
            end)
    end)
    check("repl", out:find("\n10\n", 1, true) and out:find("'ЩУКА'", 1, true), out)
end)

test("lua-apis", function()
    local out, _, ok, err = capture(function() return python.execute([[
import colors, os as pyos
from cc import lua
print(colors.combine(colors.red, colors.white), lua.eval('return os.getComputerID()'))
]], nil, _ENV) end)
    check("lua-apis", ok and out == ("16385 " .. os.getComputerID()), out .. " / " .. tostring(err))
end)

test("eval", function()
    local ok, v = python.eval("{'a': [1, 2.5, 'ж']}")
    check("eval", ok and type(v) == "table" and v.a[2] == 2.5 and v.a[3] == "ж", textutils.serialise(v))
end)

test("sandbox", function()
    local out, _, ok, err = capture(function() return python.execute([[
r = []
try:
    open('/etc/passwd'); r.append('etc')
except OSError: pass
try:
    import java; java.type('java.lang.System'); r.append('java')
except Exception: pass
print(r)
]]) end)
    check("sandbox", ok and out == "[]", out .. " / " .. tostring(err))
end)

test("requests-local-denied", function()
    local out, _, ok, err = capture(function() return python.execute([[
import requests
try:
    requests.get('http://127.0.0.1:25601/')
    print('connected')
except requests.ConnectionError:
    print('denied')
]]) end)
    check("requests-local-denied", ok and out == "denied", out .. " / " .. tostring(err))
end)

test("requests-internet", function()
    local out, _, ok, err = capture(function() return python.execute([[
import requests
r = requests.get('https://example.com/', timeout=10)
print(r.status_code, 'Example' in r.text)
]]) end)
    check("requests-internet", ok and out == "200 True", out .. " / " .. tostring(err))
end)

test("cc-http-probe", function()
    local okU, errU = http.checkURL("https://example.com/")
    local h, e = http.get("https://example.com/")
    log(true, "cc-http-probe", "checkURL=" .. tostring(okU) .. " " .. tostring(errU) .. " get=" .. tostring(h ~= nil) .. " " .. tostring(e))
    if h then h.close() end
end)

test("cc-pypi-probe", function()
    local h, e = http.get("https://files.pythonhosted.org/packages/b7/ce/149a00dd41f10bc29e5921b496af8b574d8413afcd5e30dfa0ed46c2cc5e/six-1.17.0-py2.py3-none-any.whl", nil, true)
    log(true, "cc-pypi-probe", "get=" .. tostring(h ~= nil) .. " " .. tostring(e))
    if h then h.close() end
end)

test("pip", function()
    local out = capture(function() shell.run("pip", "install", "six") end)
    local out2, _, ok, err = capture(function() return python.execute("import six\nprint(six.__name__, bool(six.PY3))") end)
    check("pip", fs.exists("lib/python/site-packages/six.py") and ok and out2 == "six True",
        out .. " / " .. out2 .. " / " .. tostring(err))
end)

test("websocket", function()
    -- CC's own websocket on the same URL first: the verdict of CC's rules must match.
    local cc, ccErr = http.websocket("wss://echo.websocket.org/")
    if cc then cc.close() end
    local out, _, ok, err = capture(function() return python.execute([[
import websocket
try:
    ws = websocket.create_connection('wss://echo.websocket.org/', timeout=10)
except websocket.WebSocketException as e:
    print('error', e)
else:
    ws.recv()
    ws.send('привет')
    print(ws.recv())
    ws.close()
]]) end)
    -- Python may be allowed where CC is not: the mod's own http.rules override CC's.
    if cc or out == "привет" then
        check("websocket", ok and out == "привет", out .. " / " .. tostring(err))
    else
        local want = "error " .. tostring(ccErr)
        check("websocket(cc-denied)", ok and out:sub(1, #want) == want, "cc: " .. tostring(ccErr) .. " py: " .. out)
    end
end)

local f = fs.open("results.txt", "w")
f.write(table.concat(results, "\n") .. "\n")
f.close()
os.shutdown()
