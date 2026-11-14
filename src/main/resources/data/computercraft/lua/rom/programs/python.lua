-- SPDX-FileCopyrightText: 2026 diff
-- python [-c code | -m module | script.py] [args...]: run Python 3, or the interactive interpreter.

local args = { ... }

if not python or not _python then
    printError("Python is not available on this computer.")
    return
end

local function usage()
    print("Usage: python [option] [script.py | -c code | -m module] [args...]")
    print("  -c code     run code given as a string")
    print("  -m module   run a library module as a program")
    print("  -V          print the Python version")
    print("Without arguments: the interactive interpreter.")
end

local function finish(ok, err, printed)
    if not ok and err and not printed then printError(err) end
end

local first = args[1]
if first == "-h" or first == "--help" or first == "-?" then
    usage()
elseif first == "-V" or first == "-v" or first == "--version" then
    print(python.version())
elseif first == "-c" then
    if not args[2] then
        printError("python: -c needs an argument")
        return
    end
    finish(python.execute(args[2], { table.unpack(args, 3) }, _ENV))
elseif first == "-m" then
    local module = args[2]
    if not module or not module:match("^[%w_][%w_%.]*$") then
        printError("python: -m needs a module name")
        return
    end
    local code = "import runpy\nrunpy.run_module(" .. string.format("%q", module) ..
        ", run_name='__main__', alter_sys=True)"
    finish(python.execute(code, { table.unpack(args, 3) }, _ENV))
elseif first and first:sub(1, 1) == "-" and first ~= "-" then
    printError("python: unknown option " .. first)
    usage()
elseif first and first ~= "-" then
    local path = shell.resolve(first)
    if not fs.exists(path) and fs.exists(path .. ".py") then path = path .. ".py" end
    if not fs.exists(path) then
        printError("python: can't open file '" .. first .. "': No such file")
        return
    elseif fs.isDir(path) then
        printError("python: '" .. first .. "' is a directory")
        return
    end
    finish(python.run("/" .. path, { table.unpack(args, 2) }, _ENV))
else
    if term.isColour() then term.setTextColour(colours.yellow) end
    print(python.version())
    if term.isColour() then term.setTextColour(colours.lightGrey) end
    print("exit() or Ctrl+T on an empty line to leave.")
    term.setTextColour(colours.white)
    finish(python.repl(_ENV))
end
