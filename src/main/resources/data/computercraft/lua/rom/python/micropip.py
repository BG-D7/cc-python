# SPDX-FileCopyrightText: 2026 diff
"""micropip-style alias for pip (as in Pyodide): micropip.install("pkg")."""

from pip import install, install_requirements, installed, list_installed, show, uninstall

__all__ = ["install", "install_requirements", "installed", "list_installed", "show", "uninstall", "list"]


def list():
    return dict(installed())
