# kik-cc-python (Код и Космос: Python для ComputerCraft)

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-green.svg)](https://fabricmc.net/)
[![NeoForge](https://img.shields.io/badge/Loader-NeoForge-blueviolet.svg)](https://neoforged.net/)
[![ComputerCraft: Tweaked](https://img.shields.io/badge/CC%3A%20Tweaked-1.120.2-orange.svg)](https://tweaked.cc/)
[![Python](https://img.shields.io/badge/Python-3.12%20(GraalPy)-yellow.svg)](https://www.graalvm.org/python/)
[![Java](https://img.shields.io/badge/Java-25%20LTS-red.svg)](https://adoptium.net/)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)

**kik-cc-python** добавляет в компьютеры и черепахи **CC: Tweaked** второй язык — **Python 3.12** (GraalPy 25).
Один гибридный JAR для Minecraft 26.2 работает и на **Fabric**, и на **NeoForge**. Мод самостоятельный: GraalPy,
стандартная библиотека и SQLite лежат внутри JAR. Других Python-модов и установленного Python не нужно.

Сделан для образовательного курса **«Код и Космос»** (KiK), но годится для любого сервера с CC: Tweaked.

---

## Что умеет

- **Python как родной язык CC.** Программа на Python ведёт себя как программа на Lua:
  - пишет в терминал через `term.redirect`, мониторы и `multishell`;
  - останавливается по Ctrl+T;
  - не подвешивает компьютер: Python работает в своём потоке сервера.
- **Песочница по умолчанию.** Скрипт видит только диск своего компьютера и ROM. Нет доступа к файлам сервера,
  Java, процессам и сырым сокетам. Отключается одним ключом конфига для тех, кто понимает риск.
- **`requests` и `websocket`** подчиняются тем же правилам HTTP, что и Lua-API `http`: белый и чёрный списки,
  лимиты размера, вкл/выкл. Правила можно переопределить в конфиге мода.
- **`sqlite3`.** Файл базы лежит на диске компьютера: учитывается в квоте и переезжает вместе с компьютером.
- **REPL.** Переменные живут между строками, выражения печатаются, есть история строк. Ввод, набранный
  до появления приглашения, не теряется.
- **Импорт из папки скрипта**, из `/lib/python`, из пакетов, поставленных `pip`, и из общей серверной папки.
- **Понятные ошибки.** Traceback с номерами строк, печатается один раз.
- **Кириллица везде:** вывод, ввод, аргументы, исходники в UTF-8 или CP1251, строки из Lua. Терминал работает
  в кодировке шрифта kik-pack (CP1251).
- **`pip install`** ставит pure-Python пакеты (wheel `py3-none-any`) с PyPI с зависимостями и маркерами PEP 508.
  Пакеты ставятся на диск компьютера.

---

## Использование в игре

### Программы

| Команда | Что делает |
|---|---|
| `python` / `py` | Интерактивный REPL |
| `python script.py [args…]` | Запуск файла (`.py` можно не писать); путь относительно текущей папки shell |
| `python -c "код" [args…]` | Однострочник |
| `python -m модуль [args…]` | Запуск модуля как `__main__` (`python -m pip list`) |
| `python -V` | Версия |
| `pip install|uninstall|list|freeze|show …` | Пакеты (то же, что `python -m pip`) |

```text
> python
Python 3.12 (GraalPy 25.0) for CC: Tweaked
>>> x = 5
>>> x * 2
10
>>> import turtle
>>> turtle.forward()
True
>>> exit()
```

### Lua API из Python

API компьютера импортируются как модули: `turtle`, `redstone`/`rs`, `term`, `colors`/`colours`,
`peripheral`, `rednet`, `gps`, `textutils`, `settings`, `disk`, `keys`, `window`, `paintutils`, `shell`,
`multishell`, `commands`, `pocket`, `vector`, `help`.

Lua-модули `os`, `fs`, `io` и `http` называются так же, как модули stdlib Python. Поэтому их Lua-версии лежат
под префиксом: `cc.os`, `cc.fs`, `cc.io`, `cc.http`, либо `cc_os`, `cc_fs`.

Имена функций работают в обоих стилях: `turtle.turnRight()` и `turtle.turn_right()`.

```python
import turtle, colors
from cc import lua

turtle.forward()
print(turtle.get_fuel_level(), colors.combine(colors.red, colors.white))
print(lua.eval("return os.getComputerID()"))   # произвольный Lua-код
```

`input()` — это `read()` CC. `time.sleep()` и ожидание событий отдают управление компьютеру. Ctrl+T приходит
в скрипт как `KeyboardInterrupt`. Если скрипт его проглотил или крутится в цикле, через
`limits.terminateGraceMs` он прерывается принудительно. Повторный Ctrl+T прерывает сразу.

### Python из Lua (`python` API)

```lua
local ok, err, printed = python.run("rover.py", { "arg1" }, _ENV)   -- файл
local ok, err = python.execute("print('Привет')")                  -- код
local ok, value = python.eval("{'a': [1, 2.5]}")                    -- значение -> таблица Lua
python.repl(_ENV)
print(python.version(), python.sandboxed())
```

Передавайте `_ENV`, чтобы Python видел `shell`, `require` и другие локальные переменные программы.

### Библиотеки

- `requests`: `get/post/put/patch/delete/head/options`, `Session` (cookies), `Response.json()/text/content`,
  `raise_for_status`, `timeout`, редиректы, исключения в стиле requests.
- `websocket` (API websocket-client): `create_connection`, `WebSocket.send/recv/close`, `WebSocketApp`.
- `sqlite3` (DB-API 2.0):
  - параметры `?` и `:name`, `Row`, транзакции и контекстный менеджер;
  - `executescript`, `iterdump`.
- `pip` / `micropip`. Пакеты ставятся в `/lib/python/site-packages` на диске компьютера.
- Стандартная библиотека Python 3.12 без C-расширений: `json`, `re`, `math`, `random`, `datetime`,
  `collections`, `itertools`, `asyncio`, `dataclasses`, `logging`, `pathlib`, `zipfile` и т. д.

Общие для всего сервера модули и пакеты кладутся в `config/kik-cc-python/libs/`. В Python эта папка видна как
`/rom/python-libs` и не расходует диски компьютеров.

---

## Конфигурация

Файл `config/kik-cc-python/config.properties` создаётся при первом запуске. Изменения применяются без
перезапуска сервера: к новым запускам скриптов.

| Ключ | По умолчанию | Смысл |
|---|---|---|
| `sandbox.disable` | `false` | **Опасно.** `true` снимает песочницу: `import java`, файлы сервера под `/host`, сокеты, процессы. Полный контроль над сервером. |
| `http.enabled` | `inherit` | `requests` вкл/выкл; `inherit` = `http.enabled` из конфига CC |
| `websocket.enabled` | `inherit` | То же для `websocket` |
| `http.rules` | `inherit` | Свои правила вместо правил CC: `action host[:port]` через `;`, первое совпадение побеждает. Пример: `deny $private; allow api.example.com; deny *` |
| `text.encoding` | `cp1251` | Кодировка строк Lua и шрифта: `cp1251` (kik-pack), `latin1` (штатный шрифт CC), `utf-8` |
| `limits.maxRunSeconds` | `0` | Лимит времени одного запуска, с (0 = нет; REPL не ограничивается) |
| `limits.terminateGraceMs` | `1500` | Сколько ждать реакции на Ctrl+T перед принудительной остановкой |
| `limits.maxSessions` / `limits.maxSessionsPerComputer` | `32` / `4` | Одновременные скрипты на сервер / на компьютер |
| `limits.maxEventQueue` | `256` | Очередь событий скрипта (как у CC) |
| `performance.prewarm` | `true` | Распаковать рантайм и прогреть интерпретатор при старте сервера |

Рантайм GraalPy (около 25 МБ) распаковывается один раз в `~/.kik-graalpy-cache/graalpy-home/<хеш>/`.

---

## Безопасность (песочница)

| Угроза | Что делает мод |
|---|---|
| Чтение и запись файлов сервера | Своя файловая система поверх FS компьютера CC: диск компьютера, его дискеты, `/rom`. Выйти за них нельзя, квота диска соблюдается. |
| Java и краш сервера | `allowHostClassLookup(false)`, `HostAccess` только для аннотированных методов моста, без native access и polyglot. |
| Процессы (`subprocess`, `os.system`) | `allowCreateProcess(false)` |
| Сеть мимо правил | Сырые сокеты запрещены (`PermissionError`). HTTP и websocket проходят только через мост, где действуют правила CC (`$private` и т. п.) или правила конфига. |
| Переменные окружения сервера | `EnvironmentAccess.NONE` |
| Бесконечный цикл, зависший скрипт | Скрипт в отдельном потоке и никогда не блокирует поток CC. Ctrl+T, `limits.maxRunSeconds` и выключение компьютера останавливают его гарантированно, даже во время сетевого запроса. |

Лимита памяти на один скрипт нет: его даёт только общий `-Xmx` сервера.

---

## Сборка и тесты

Нужен **JDK 25**. CC: Tweaked и NeoForge подключаются как `compileOnly` из `libs/`. GraalPy и sqlite-jdbc
берутся из Maven.

```bash
./gradlew packageAioJar        # build/libs/kik-cc-python-<версия>-aio.jar (~100 МБ, всё внутри)
./gradlew cleanTest test       # JUnit
```

**JUnit** (`PyRunTest`) запускает настоящий GraalPy через Java-заменитель Lua-драйвера и настоящую
`FileSystem` CC на временной папке. Покрыты:
- вывод и кириллица, CP1251-исходники;
- traceback;
- импорт из папки скрипта;
- REPL;
- Ctrl+T: в цикле, во `sleep` и для скрипта, который глотает `KeyboardInterrupt`;
- `maxRunSeconds`;
- песочница;
- sqlite на диске компьютера;
- вызовы Lua API и `eval`;
- правила HTTP, таймаут и Ctrl+T при зависшем ответе сервера;
- `python -m pip`.

**Игровые тесты** идут на отдельном локальном сервере Fabric с этим JAR. Скрипт `startup.lua` на компьютере
прогоняет те же сценарии через настоящий CC:
- `shell.run("python", …)`;
- `parallel` с Ctrl+T;
- REPL с вводом через события;
- `pip install`;
- `requests` и `websocket` в интернет.

Результаты пишутся в файл на диске компьютера. NeoForge локально не тестируется.

---

## Лицензия

**GNU LGPL v3**, см. [LICENSE](LICENSE). CC: Tweaked распространяется под ComputerCraft Public License,
GraalVM/GraalPy/Truffle под Universal Permissive License (UPL), sqlite-jdbc под Apache 2.0.
