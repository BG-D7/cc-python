# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии — [SemVer](https://semver.org/lang/ru/).
Тег релиза `vX.Y.Z` совпадает с `mod_version`; артефакт называется `cc-python-X.Y.Z+<версия Minecraft>`.

## [1.0.0] — 2026-11-14

Первый публичный релиз. Minecraft 26.2, Fabric и NeoForge (один JAR), CC: Tweaked 1.120.2, Java 25.

### Добавлено
- Python 3.13 (GraalPy 25.5) в компьютерах и черепахах CC: Tweaked: программа `python`, REPL, `python.eval`/`python.run`
  из Lua. Рантайм внутри JAR, других модов и установленного Python не нужно.
- Песочница по умолчанию: только диск компьютера и ROM, без Java, процессов и сырых сокетов.
- `requests`, `websocket` по правилам HTTP из конфига CC; `sqlite3` с базой на диске компьютера.
- `pip install` pure-Python пакетов с PyPI на диск компьютера, с зависимостями и `requirements.txt`.
- Кириллица в выводе, вводе, аргументах и исходниках (CP1251 / UTF-8); ресурспак с кириллическим шрифтом
  `cc-python-cyrillic-*.zip` (CCPL).
- Модуль `ccpy` (`import kik` работает как старое имя).
