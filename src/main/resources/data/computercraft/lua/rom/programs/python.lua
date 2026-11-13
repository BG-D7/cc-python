-- SPDX-FileCopyrightText: 2026 diff
-- Python 3 (GraalPy) runner and REPL for CC: Tweaked

local tArgs = { ... }

if not python then
    printError("Python API is not available on this computer.")
    return
end

-- Version flag
if #tArgs == 1 and (tArgs[1] == "-v" or tArgs[1] == "--version") then
    print(python.version())
    return
end

-- Help flag
if #tArgs == 1 and (tArgs[1] == "-h" or tArgs[1] == "--help") then
    print("Usage: python [options] [script [args]]")
    print("Options:")
    print("  -c cmd     : program passed in as string")
    print("  -v, --version : print the Python version")
    print("  -h, --help    : print this help message")
    print("Run without arguments to start the interactive REPL.")
    return
end

-- Inline code execution: python -c "code" [args...]
if #tArgs >= 2 and tArgs[1] == "-c" then
    local code = tArgs[2]
    local pyArgs = {}
    for i = 3, #tArgs do
        table.insert(pyArgs, tArgs[i])
    end
    local ok, err = python.execute(code, pyArgs)
    if not ok and err then
        printError(err)
    end
    return
end

-- Script execution: python script.py [args...]
if #tArgs >= 1 and tArgs[1] ~= "-i" then
    local path = shell.resolve(tArgs[1])
    if not fs.exists(path) then
        printError("No such file: " .. tArgs[1])
        return
    end
    if fs.isDir(path) then
        printError(tArgs[1] .. " is a directory")
        return
    end

    local pyArgs = {}
    for i = 2, #tArgs do
        table.insert(pyArgs, tArgs[i])
    end

    local ok, err = python.run(path, pyArgs)
    if not ok and err then
        printError(err)
    end
    return
end

-- Interactive REPL
if term.isColour() then
    term.setTextColour(colours.yellow)
end
print("Python 3.12 (GraalPy on CC: Tweaked)")
print('Type "exit()" or "quit()" to leave.')
if term.isColour() then
    term.setTextColour(colours.white)
end

local history = {}
while true do
    write(">>> ")
    local line = read(nil, history)
    if not line then
        break
    end

    local trimmed = line:gsub("^%s*(.-)%s*$", "%1")
    if trimmed == "exit()" or trimmed == "quit()" or trimmed == "exit" or trimmed == "quit" then
        break
    end

    if #trimmed > 0 then
        local code = line
        if line:sub(-1) == ":" then
            while true do
                write("... ")
                local nextLine = read(nil, history)
                if not nextLine or nextLine:match("^%s*$") then
                    break
                end
                code = code .. "\n" .. nextLine
            end
        end

        local ok, err = python.execute(code, {})
        if not ok and err then
            printError(err)
        end
    end
end
