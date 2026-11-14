package work.diffdev.kik.python;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Unpacks the Python home that GraalPy's python-resources artifact ships inside the mod jar (stdlib, GraalPy core
 * modules, headers, natives for this platform) into a cache directory, once per resource version.
 *
 * <p>The resources are listed in {@code META-INF/resources/<name>.files} and read through the class loader, so this
 * works the same from a Fabric or NeoForge mod jar and from the test classpath.
 *
 * <p>Layout: {@code lib/pythonX.Y} (stdlib), {@code lib/graalpyA.B} (core and natives), {@code include/pythonX.Y}, with
 * the versions of the bundled GraalPy ({@link Versions}).
 */
public record RuntimeHome(Path root, Path coreHome, Path stdlibHome) {
    private static final String RES = "META-INF/resources/";
    private static final String MARKER = ".complete";

    /** Python language level and GraalPy release of the bundled runtime. */
    public record Versions(int pythonMajor, int pythonMinor, int pythonMicro, int graalMajor, int graalMinor) {
        public static final Versions BUNDLED = read();

        public String python() {
            return pythonMajor + "." + pythonMinor;
        }

        public String graalPy() {
            return graalMajor + "." + graalMinor;
        }

        /**
         * GraalPy's own {@code graalpy_versions} resource (python-language jar): one byte per number, offset by 33 --
         * Python major, minor, micro, then GraalPy major, minor, micro.
         */
        private static Versions read() {
            try (InputStream in = RuntimeHome.class.getClassLoader().getResourceAsStream("graalpy_versions")) {
                if (in == null) throw new IllegalStateException("graalpy_versions is missing from the mod jar");
                byte[] b = in.readNBytes(6);
                if (b.length < 6) throw new IllegalStateException("graalpy_versions is truncated");
                return new Versions(b[0] - 33, b[1] - 33, b[2] - 33, b[3] - 33, b[4] - 33);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static RuntimeHome prepare(Path cacheDir) throws IOException {
        ClassLoader cl = RuntimeHome.class.getClassLoader();
        Versions v = Versions.BUNDLED;
        String coreDir = "lib/graalpy" + v.graalPy();
        String stdlibDir = "lib/python" + v.python();
        String platform = platform();
        List<Part> parts = new ArrayList<>();
        parts.add(new Part("libpython", RES + "libpython/", stdlibDir + "/"));
        parts.add(new Part("libgraalpy", RES + "libgraalpy/", coreDir + "/"));
        parts.add(new Part("include", RES + "include/", "include/python" + v.python() + "/"));
        if (platform != null && cl.getResource(RES + platform + "/native.files") != null) {
            parts.add(new Part(platform + "/native", RES + platform + "/", ""));
        }

        StringBuilder fp = new StringBuilder(platform == null ? "none" : platform).append(':').append(coreDir).append(':').append(stdlibDir);
        for (Part p : parts) {
            String sha = readResource(cl, RES + p.name() + ".sha256");
            if (sha == null) throw new IOException("GraalPy resource " + p.name() + " is missing from the mod jar");
            fp.append(':').append(sha.trim());
        }
        Path root = cacheDir.resolve("graalpy-home").resolve(hash(fp.toString()));
        RuntimeHome home = new RuntimeHome(root, root.resolve(coreDir), root.resolve(stdlibDir));
        if (Files.exists(root.resolve(MARKER))) return home;

        Files.createDirectories(root.getParent());
        Path tmp = Files.createTempDirectory(root.getParent(), "unpack-");
        try {
            for (Part p : parts) extract(cl, p, tmp);
            Files.writeString(tmp.resolve(MARKER), fp.toString());
            try {
                Files.move(tmp, root, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Another server instance unpacked it at the same time.
                if (!Files.exists(root.resolve(MARKER))) throw e;
            }
        } finally {
            deleteTree(tmp);
        }
        return home;
    }

    private record Part(String name, String resourcePrefix, String targetPrefix) {}

    private static void extract(ClassLoader cl, Part part, Path into) throws IOException {
        String list = readResource(cl, RES + part.name() + ".files");
        if (list == null) throw new IOException("GraalPy resource list " + part.name() + " is missing");
        for (String line : list.split("\n")) {
            int eq = line.lastIndexOf(" = ");
            String entry = (eq >= 0 ? line.substring(0, eq) : line).trim();
            if (entry.isEmpty() || !entry.startsWith(part.resourcePrefix())) continue;
            String rel = part.targetPrefix() + entry.substring(part.resourcePrefix().length());
            Path target = into.resolve(rel).normalize();
            if (!target.startsWith(into)) throw new IOException("Bad resource path " + entry);
            if (entry.endsWith("/")) {
                Files.createDirectories(target);
                continue;
            }
            try (InputStream in = cl.getResourceAsStream(entry)) {
                if (in == null) throw new IOException("GraalPy resource file " + entry + " is missing");
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (eq >= 0 && line.substring(eq + 3).trim().startsWith("rwx")) target.toFile().setExecutable(true);
        }
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "darwin" : os.contains("linux") ? "linux" : null;
        String a = arch.equals("amd64") || arch.equals("x86_64") ? "amd64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
        return o == null || a == null ? null : o + "/" + a;
    }

    private static String readResource(ClassLoader cl, String name) throws IOException {
        try (InputStream in = cl.getResourceAsStream(name)) {
            if (in == null) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                return sb.toString();
            }
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
