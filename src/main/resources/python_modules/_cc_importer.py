# SPDX-FileCopyrightText: 2026 diff
# Virtual Module Loader for ComputerCraft Filesystem

import sys
import importlib.abc
import importlib.machinery

class CCModuleLoader(importlib.abc.Loader):
    def __init__(self, fullname, filepath, is_package, fs):
        self.fullname = fullname
        self.filepath = filepath
        self.is_package = is_package
        self.fs = fs

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        code_str = self.fs.readAll(self.filepath)
        if code_str is None:
            raise ImportError(f"Cannot read module '{self.fullname}' from CC filesystem at '{self.filepath}'")
        module.__file__ = self.filepath
        if self.is_package:
            pkg_dir = self.filepath.rsplit("/", 1)[0]
            module.__path__ = [pkg_dir]
            module.__package__ = self.fullname
        else:
            module.__package__ = self.fullname.rpartition(".")[0]
        code = compile(code_str, self.filepath, "exec")
        exec(code, module.__dict__)

class CCPathFinder(importlib.abc.MetaPathFinder):
    def __init__(self, fs):
        self.fs = fs
        self.search_roots = ["", "lib", "lib/python", "lib/python/site-packages", "site-packages"]

    def find_spec(self, fullname, path, target=None):
        if not self.fs:
            return None

        parts = fullname.split(".")
        leaf_name = parts[-1]

        search_dirs = path if path is not None else self.search_roots

        for root in search_dirs:
            prefix = f"{root}/" if root and not root.endswith("/") else (f"{root}" if root else "")

            # 1. Package with __init__.py
            pkg_file = f"{prefix}{leaf_name}/__init__.py".lstrip("/")
            if self.fs.exists(pkg_file):
                loader = CCModuleLoader(fullname, pkg_file, is_package=True, fs=self.fs)
                spec = importlib.machinery.ModuleSpec(fullname, loader, origin=pkg_file, is_package=True)
                spec.submodule_search_locations = [f"{prefix}{leaf_name}".lstrip("/")]
                return spec

            # 2. Standalone module .py
            mod_file = f"{prefix}{leaf_name}.py".lstrip("/")
            if self.fs.exists(mod_file):
                loader = CCModuleLoader(fullname, mod_file, is_package=False, fs=self.fs)
                return importlib.machinery.ModuleSpec(fullname, loader, origin=mod_file, is_package=False)

        return None

def install_hook(fs):
    if not fs:
        return
    for finder in sys.meta_path:
        if isinstance(finder, CCPathFinder):
            return
    sys.meta_path.insert(0, CCPathFinder(fs))
