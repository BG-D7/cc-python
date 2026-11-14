package work.diffdev.ccpython.vfs;

import java.io.File;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Unix-style path of the virtual Python filesystem. It does not depend on the host OS, so a Windows server still
 * gives scripts "/lib/foo.py" style paths.
 */
public final class VPath implements Path {
    public static final VPath ROOT = new VPath(true, new String[0]);

    private final boolean absolute;
    private final String[] parts;

    private VPath(boolean absolute, String[] parts) {
        this.absolute = absolute;
        this.parts = parts;
    }

    public static VPath of(String path) {
        String p = path.replace('\\', '/');
        boolean abs = p.startsWith("/");
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) {
            if (!s.isEmpty()) out.add(s);
        }
        return new VPath(abs, out.toArray(new String[0]));
    }

    /** Path relative to the CC root ("lib/foo.py"), after normalisation. */
    public String ccPath() {
        return String.join("/", ((VPath) normalize()).parts);
    }

    public String[] parts() {
        return parts.clone();
    }

    private static VPath check(Path other) {
        if (other instanceof VPath v) return v;
        if (other == null) throw new NullPointerException();
        throw new ProviderMismatchException();
    }

    @Override
    public FileSystem getFileSystem() {
        throw new UnsupportedOperationException("virtual path");
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return absolute ? ROOT : null;
    }

    @Override
    public Path getFileName() {
        return parts.length == 0 ? null : new VPath(false, new String[] { parts[parts.length - 1] });
    }

    @Override
    public Path getParent() {
        if (parts.length == 0) return null;
        if (parts.length == 1) return absolute ? ROOT : null;
        return new VPath(absolute, Arrays.copyOf(parts, parts.length - 1));
    }

    @Override
    public int getNameCount() {
        return parts.length;
    }

    @Override
    public Path getName(int index) {
        if (index < 0 || index >= parts.length) throw new IllegalArgumentException();
        return new VPath(false, new String[] { parts[index] });
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > parts.length || beginIndex >= endIndex) throw new IllegalArgumentException();
        return new VPath(false, Arrays.copyOfRange(parts, beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other) {
        VPath o = check(other);
        if (o.absolute != absolute || o.parts.length > parts.length) return false;
        for (int i = 0; i < o.parts.length; i++) {
            if (!o.parts[i].equals(parts[i])) return false;
        }
        return true;
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(of(other));
    }

    @Override
    public boolean endsWith(Path other) {
        VPath o = check(other);
        if (o.absolute) return equals(o);
        if (o.parts.length > parts.length) return false;
        int off = parts.length - o.parts.length;
        for (int i = 0; i < o.parts.length; i++) {
            if (!o.parts[i].equals(parts[off + i])) return false;
        }
        return true;
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(of(other));
    }

    @Override
    public Path normalize() {
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            if (s.equals(".")) continue;
            if (s.equals("..")) {
                if (!out.isEmpty() && !out.getLast().equals("..")) out.removeLast();
                else if (!absolute) out.add(s);
                continue;
            }
            out.add(s);
        }
        return new VPath(absolute, out.toArray(new String[0]));
    }

    @Override
    public Path resolve(Path other) {
        VPath o = check(other);
        if (o.absolute) return o;
        if (o.parts.length == 0) return this;
        String[] joined = Arrays.copyOf(parts, parts.length + o.parts.length);
        System.arraycopy(o.parts, 0, joined, parts.length, o.parts.length);
        return new VPath(absolute, joined);
    }

    @Override
    public Path resolve(String other) {
        return resolve(of(other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(of(other));
    }

    @Override
    public Path relativize(Path other) {
        VPath o = check(other);
        if (o.absolute != absolute) throw new IllegalArgumentException("'other' is different type of Path");
        VPath a = (VPath) normalize(), b = (VPath) o.normalize();
        int common = 0;
        while (common < a.parts.length && common < b.parts.length && a.parts[common].equals(b.parts[common])) common++;
        List<String> out = new ArrayList<>();
        for (int i = common; i < a.parts.length; i++) out.add("..");
        out.addAll(Arrays.asList(b.parts).subList(common, b.parts.length));
        return new VPath(false, out.toArray(new String[0]));
    }

    @Override
    public URI toUri() {
        try {
            // The multi-argument constructor quotes what needs quoting (spaces, '<', non-ASCII ...).
            return new URI("file", null, toAbsolutePath().toString(), null);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Path toAbsolutePath() {
        return absolute ? this : ROOT.resolve(this);
    }

    @Override
    public Path toRealPath(LinkOption... options) {
        return toAbsolutePath().normalize();
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException("virtual path");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Path> iterator() {
        List<Path> names = new ArrayList<>();
        for (String s : parts) names.add(new VPath(false, new String[] { s }));
        return names.iterator();
    }

    @Override
    public int compareTo(Path other) {
        return toString().compareTo(check(other).toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VPath v && v.absolute == absolute && Arrays.equals(v.parts, parts);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(parts) * 31 + (absolute ? 1 : 0);
    }

    @Override
    public String toString() {
        String joined = String.join("/", parts);
        return absolute ? "/" + joined : joined;
    }
}
