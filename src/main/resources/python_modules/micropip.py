# SPDX-FileCopyrightText: 2026 diff
# micropip compatibility alias for pip

from pip import install, uninstall, list_installed, install_requirements

__all__ = ["install", "uninstall", "list_installed", "install_requirements"]
