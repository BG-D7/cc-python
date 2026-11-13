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

    @Test
    @DisplayName("Pip and micropip modules compliance with requires_dist dependency resolver")
    void testPipModule() {
        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, null, null, null, null);

        String script = String.join("\n",
            "import pip, micropip",
            "assert hasattr(pip, 'install')",
            "assert hasattr(pip, 'uninstall')",
            "assert hasattr(pip, 'list_installed')",
            "assert hasattr(micropip, 'install')",
            "assert hasattr(micropip, 'uninstall')",
            "",
            "# Test requires_dist parser logic",
            "assert pip.parse_requirement('MarkupSafe>=2.0') == 'MarkupSafe'",
            "assert pip.parse_requirement('markdown-it-py>=2.2.0') == 'markdown-it-py'",
            "assert pip.parse_requirement('Babel>=2.7; extra == \"i18n\"') is None",
            "assert pip.parse_requirement('ipywidgets<9; extra == \"jupyter\"') is None",
            "assert pip.parse_requirement('legacy-pkg; python_version < \"3.8\"') is None",
            "assert pip.parse_requirement('modern-pkg; python_version >= \"3.10\"') == 'modern-pkg'",
            "",
            "# Test PEP 503 name normalization",
            "assert pip.normalize_name('Markdown-It-Py') == 'markdown-it-py'",
            "assert pip.normalize_name('foo_bar.baz') == 'foo-bar-baz'",
            "",
            "# Test built-in module detection",
            "assert pip.is_installed('requests')",
            "assert pip.is_installed('sqlite3')",
            "print('PIP_RESOLVER_OK')"
        );

        session.runCode(script, "test_pip.py", List.of(), bridge);
        bridge.handleInitialStep();

        String line0 = terminal.getLine(0).toString().trim();
        assertEquals("PIP_RESOLVER_OK", line0);
    }

    @Test
    @DisplayName("Pip uninstall removes package and cleans manifest")
    void testPipUninstall() throws Exception {
        Path tempDir = Files.createTempDirectory("cc-pip-test");
        var mount = new dan200.computercraft.core.filesystem.WritableFileMount(tempDir.toFile(), 10_000_000);
        var fs = new dan200.computercraft.core.filesystem.FileSystem("hdd", mount);

        // Pre-create simulated installed package and manifest on mounted disk
        Path roverDir = tempDir.resolve("lib/my_rover");
        Path instDir = tempDir.resolve("lib/.installed");
        Files.createDirectories(roverDir);
        Files.createDirectories(instDir);
        Files.writeString(roverDir.resolve("core.py"), "def drive(): return 1\n");
        Files.writeString(instDir.resolve("my-rover.manifest"), "lib/my_rover/core.py\n");

        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, fs, null, null, null);

        String script = String.join("\n",
            "import pip",
            "assert pip.is_installed('my-rover')",
            "res = pip.uninstall('my-rover')",
            "assert res == True",
            "assert not pip.is_installed('my-rover')",
            "print('UNINSTALL_OK')"
        );

        session.runCode(script, "test_uninstall.py", List.of(), bridge);
        bridge.handleInitialStep();

        StringBuilder fullOutput = new StringBuilder();
        for (int i = 0; i < terminal.getHeight(); i++) {
            fullOutput.append(terminal.getLine(i).toString()).append("\n");
        }
        String outStr = fullOutput.toString();
        assertTrue(outStr.contains("Successfully uninstalled"));
        assertTrue(outStr.contains("UNINSTALL_OK"));
        assertFalse(fs.exists("lib/my_rover/core.py"));
        assertFalse(fs.exists("lib/.installed/my-rover.manifest"));
    }

    @Test
    @DisplayName("Pip requirements file (-r) parsing and installation")
    void testPipRequirementsFile() throws Exception {
        Path tempDir = Files.createTempDirectory("cc-reqs-test");
        var mount = new dan200.computercraft.core.filesystem.WritableFileMount(tempDir.toFile(), 10_000_000);
        var fs = new dan200.computercraft.core.filesystem.FileSystem("hdd", mount);

        // Pre-create requirements.txt in virtual root
        Path reqsFile = tempDir.resolve("requirements.txt");
        String reqsContent = String.join("\n",
            "# Core dependencies test",
            "requests >= 2.0 # built-in satisfied",
            "sqlite3 # built-in",
            "",
            "# Should be skipped due to incompatible python version marker:",
            "fake_incompatible_pkg == 9.9.9; python_version < '3.0'"
        );
        Files.writeString(reqsFile, reqsContent);

        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, fs, null, null, null);

        String script = String.join("\n",
            "import pip, micropip",
            "assert hasattr(pip, 'install_requirements')",
            "assert hasattr(micropip, 'install_requirements')",
            "res1 = pip.install_requirements('requirements.txt')",
            "assert res1 == True",
            "res2 = pip.install('-r requirements.txt')",
            "assert res2 == True",
            "print('REQS_OK')"
        );

        session.runCode(script, "test_reqs.py", List.of(), bridge);
        bridge.handleInitialStep();

        StringBuilder fullOutput = new StringBuilder();
        for (int i = 0; i < terminal.getHeight(); i++) {
            fullOutput.append(terminal.getLine(i).toString()).append("\n");
        }
        String outStr = fullOutput.toString();
        assertTrue(outStr.contains("Reading requirements from 'requirements.txt'"));
        assertTrue(outStr.contains("Skipping fake_incompatible_pkg"));
        assertTrue(outStr.contains("REQS_OK"));
    }

    @Test
    @DisplayName("Virtual CC filesystem module import via CCPathFinder")
    void testCCPathFinderImport() throws Exception {
        Path tempDir = Files.createTempDirectory("cc-fs-test");
        Path roverMod = tempDir.resolve("rover_engine.py");
        Files.writeString(roverMod, "def get_thrust(): return 4200\n");

        var mount = new dan200.computercraft.core.filesystem.WritableFileMount(tempDir.toFile(), 10_000_000);
        var fs = new dan200.computercraft.core.filesystem.FileSystem("hdd", mount);

        Terminal terminal = new Terminal(51, 19, true);
        PythonExecutionBridge bridge = new PythonExecutionBridge();
        PythonSession session = new PythonSession(null, null, terminal, fs, null, null, null);

        String script = String.join("\n",
            "import rover_engine",
            "val = rover_engine.get_thrust()",
            "print(f'THRUST_{val}')"
        );

        session.runCode(script, "test_rover.py", List.of(), bridge);
        bridge.handleInitialStep();

        String line0 = terminal.getLine(0).toString().trim();
        assertEquals("THRUST_4200", line0);
    }

    @Test
    @DisplayName("WheelExtractor extracts ZIP/wheel into CC virtual filesystem")
    void testWheelExtractor() throws Exception {
        Path tempDir = Files.createTempDirectory("wheel-test");
        var mount = new dan200.computercraft.core.filesystem.WritableFileMount(tempDir.toFile(), 10_000_000);
        var fs = new dan200.computercraft.core.filesystem.FileSystem("hdd", mount);
        var fsBridge = new work.diffdev.kik.python.bridge.FSBridge(fs);

        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("sample_pkg/__init__.py"));
            zos.write("VERSION = '1.2.3'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        int extracted = work.diffdev.kik.python.pip.WheelExtractor.extract(baos.toByteArray(), "lib", fsBridge);
        assertEquals(1, extracted);
        assertTrue(fs.exists("lib/sample_pkg/__init__.py"));
    }
}
