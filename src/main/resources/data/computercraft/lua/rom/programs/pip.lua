-- SPDX-FileCopyrightText: 2026 diff
-- In-game pip command for ComputerCraft: Tweaked

local tArgs = { ... }

if #tArgs == 0 or tArgs[1] == "help" or tArgs[1] == "--help" or tArgs[1] == "-h" then
    print("pip - Python Package Installer for CC: Tweaked")
    print("Usage:")
    print("  pip install <pkg>       - Install package from PyPI into /lib")
    print("  pip install -r <file>   - Install packages from requirements file")
    print("  pip -r <file>           - Install packages from requirements file")
    print("  pip uninstall <pkg>     - Remove package from /lib")
    print("  pip list                - List packages in /lib")
    return
end

local cmd = tArgs[1]

if cmd == "-r" or cmd == "--requirement" then
    if #tArgs < 2 then
        printError("Usage: pip -r <requirements.txt>")
        return
    end
    for i = 2, #tArgs do
        local reqFile = tArgs[i]
        shell.run("py", "-c", "import pip; pip.install_requirements('" .. reqFile .. "')")
    end
elseif cmd == "install" then
    if #tArgs < 2 then
        printError("Usage: pip install <package> or pip install -r <file>")
        return
    end
    local i = 2
    while i <= #tArgs do
        local arg = tArgs[i]
        if arg == "-r" or arg == "--requirement" then
            i = i + 1
            if i <= #tArgs then
                local reqFile = tArgs[i]
                shell.run("py", "-c", "import pip; pip.install_requirements('" .. reqFile .. "')")
            else
                printError("Usage: pip install -r <requirements.txt>")
                return
            end
        elseif arg:sub(1, 2) == "-r" then
            local reqFile = arg:sub(3)
            if #reqFile > 0 then
                shell.run("py", "-c", "import pip; pip.install_requirements('" .. reqFile .. "')")
            else
                printError("Usage: pip install -r <requirements.txt>")
                return
            end
        else
            shell.run("py", "-c", "import pip; pip.install('" .. arg .. "')")
        end
        i = i + 1
    end
elseif cmd == "uninstall" then
    if #tArgs < 2 then
        printError("Usage: pip uninstall <package>")
        return
    end
    for i = 2, #tArgs do
        local pkg = tArgs[i]
        shell.run("py", "-c", "import pip; pip.uninstall('" .. pkg .. "')")
    end
elseif cmd == "list" then
    shell.run("py", "-c", "import pip; pip.list_installed()")
else
    printError("Unknown command: " .. cmd)
    print("Run 'pip help' for usage.")
end
