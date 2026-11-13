-- SPDX-FileCopyrightText: 2026 diff
-- In-game pip command for ComputerCraft: Tweaked

local tArgs = { ... }

if #tArgs == 0 or tArgs[1] == "help" or tArgs[1] == "--help" or tArgs[1] == "-h" then
    print("pip - Python Package Installer for CC: Tweaked")
    print("Usage:")
    print("  pip install <pkg>       - Install package from PyPI into /lib")
    print("  pip uninstall <pkg>     - Remove package from /lib")
    print("  pip list                - List packages in /lib")
    return
end

local cmd = tArgs[1]

if cmd == "install" then
    if #tArgs < 2 then
        printError("Usage: pip install <package>")
        return
    end
    for i = 2, #tArgs do
        local pkg = tArgs[i]
        shell.run("py", "-c", "import pip; pip.install('" .. pkg .. "')")
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
