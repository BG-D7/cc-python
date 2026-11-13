package work.diffdev.kik.python;

import dan200.computercraft.core.terminal.Terminal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PythonSessionTest {

    @BeforeAll
    static void setup() {
        System.setProperty("polyglotimpl.DisableMultiReleaseCheck", "true");
        Path cacheTmp = Paths.get(System.getProperty("user.home"), ".kik-graalpy-cache", "tmp");
        try {
            Files.createDirectories(cacheTmp);
            System.setProperty("org.sqlite.tmpdir", cacheTmp.toString());
            Class.forName("org.sqlite.JDBC");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Test
    @DisplayName("Python basic evaluation and standard library (math, json, random)")
    void testBasicStdlib() {
        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, null, null, null, null);

        String script = String.join("\n",
            "import math, json, random",
            "val = math.sqrt(256)",
            "assert val == 16.0",
            "data = json.loads('{\"kik\": 42}')",
            "assert data['kik'] == 42",
            "print('STDLIB_OK')"
        );

        session.runCode(script, "test_stdlib.py", List.of(), bridge);
        var result = bridge.handleInitialStep();
        assertNotNull(result);
        
        String line0 = terminal.getLine(0).toString().trim();
        assertEquals("STDLIB_OK", line0);
    }

    @Test
    @DisplayName("Python asyncio execution")
    void testAsyncio() {
        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, null, null, null, null);

        String script = String.join("\n",
            "import asyncio",
            "async def run_coro():",
            "    await asyncio.sleep(0.001)",
            "    return 'ASYNC_SUCCESS'",
            "res = asyncio.run(run_coro())",
            "print(res)"
        );

        session.runCode(script, "test_asyncio.py", List.of(), bridge);
        bridge.handleInitialStep();

        String line0 = terminal.getLine(0).toString().trim();
        assertEquals("ASYNC_SUCCESS", line0);
    }

    @Test
    @DisplayName("Requests module API compliance")
    void testRequests() {
        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, null, null, null, null);

        String script = String.join("\n",
            "import requests",
            "s = requests.Session()",
            "assert hasattr(requests, 'get')",
            "assert hasattr(requests, 'post')",
            "print('STATUS_200')"
        );

        session.runCode(script, "test_requests.py", List.of(), bridge);
        bridge.handleInitialStep();

        String line0 = terminal.getLine(0).toString().trim();
        assertEquals("STATUS_200", line0);
    }

    @Test
    @DisplayName("SQLite in-memory database CRUD")
    void testSqlite() {
        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, null, null, null, null);

        String script = String.join("\n",
            "import sqlite3",
            "conn = sqlite3.connect(':memory:')",
            "conn.execute('CREATE TABLE rover (id INT, battery INT)')",
            "conn.execute('INSERT INTO rover VALUES (1, 100)')",
            "conn.commit()",
            "val = conn.execute('SELECT battery FROM rover WHERE id = 1').fetchone()[0]",
            "print(f'BATTERY_{val}')"
        );

        session.runCode(script, "test_sqlite.py", List.of(), bridge);
        bridge.handleInitialStep();

        String line0 = terminal.getLine(0).toString().trim();
        assertEquals("BATTERY_100", line0);
    }

    @Test
    @DisplayName("Russian Cyrillic CP1251 font mapping translation")
    void testRussianEncoding() {
        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, null, null, null, null);

        String script = "print('Привет')\n";

        session.runCode(script, "test_ru.py", List.of(), bridge);
        bridge.handleInitialStep();

        String line0 = terminal.getLine(0).toString().trim();
        // CP1251 representation: П(0xCF), р(0xF0), и(0xE8), в(0xE2), е(0xE5), т(0xF2)
        assertEquals("\u00CF\u00F0\u00E8\u00E2\u00E5\u00F2", line0);
    }
}
