package work.diffdev.kik.python.vfs;

import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.core.filesystem.FileSystemException;
import dan200.computercraft.core.filesystem.FileSystemWrapper;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only filesystem a sandboxed Python script sees. Paths map onto the computer's own CC filesystem (its disk,
 * floppies, /rom), except for read-only host mounts such as the Python stdlib under /rom/python.
 * Every write goes through CC, so disk quotas and read-only mounts are enforced exactly as for Lua.
 */
public final class CCFileSystem implements org.graalvm.polyglot.io.FileSystem {
    /** A host directory exposed at a virtual prefix. */
    public record HostMount(VPath prefix, Path hostRoot, boolean readOnly) {}

    private final List<HostMount> mounts;
    private volatile FileSystem cc;
    private volatile VPath cwd = VPath.ROOT;

    public CCFileSystem(List<HostMount> mounts) {
        this.mounts = List.copyOf(mounts);
    }

    /** Attach the computer whose disk this context sees. Contexts are built (pre-warmed) before a computer is known. */
    public void bind(FileSystem cc) {
        this.cc = cc;
    }

    // --- path handling ---------------------------------------------------------------------------------------

    @Override
    public Path parsePath(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) throw new UnsupportedOperationException("Only file: URIs");
        return VPath.of(uri.getPath());
    }

    @Override
    public Path parsePath(String path) {
        return VPath.of(path);
    }

    private VPath abs(Path path) {
        if (!(path instanceof VPath v)) throw new IllegalArgumentException("Foreign path " + path);
        return (VPath) (v.isAbsolute() ? v : cwd.resolve(v)).normalize();
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return abs(path);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        VPath p = abs(path);
        if (!exists(p)) throw new NoSuchFileException(p.toString());
        return p;
    }

    @Override
    public void setCurrentWorkingDirectory(Path dir) {
        cwd = abs(dir);
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public String getPathSeparator() {
        return ":";
    }

    @Override
    public Path getTempDirectory() {
        return VPath.of("/tmp");
    }

    @Override
    public boolean isSameFile(Path a, Path b, LinkOption... options) {
        return abs(a).equals(abs(b));
    }

    private FileSystem cc() {
        FileSystem fs = cc;
        return fs != null ? fs : EMPTY.get();
    }

    /** Stand-in disk for an unbound (pre-warming) context: an empty read-only root. */
    private static final java.util.function.Supplier<FileSystem> EMPTY = new java.util.function.Supplier<>() {
        private FileSystem fs;

        @Override
        public synchronized FileSystem get() {
            if (fs == null) {
                try {
                    fs = new FileSystem("hdd", new dan200.computercraft.api.filesystem.Mount() {
                        @Override
                        public boolean exists(String path) {
                            return path.isEmpty();
                        }

                        @Override
                        public boolean isDirectory(String path) {
                            return path.isEmpty();
                        }

                        @Override
                        public void list(String path, List<String> contents) {}

                        @Override
                        public long getSize(String path) {
                            return 0;
                        }

                        @Override
                        public SeekableByteChannel openForRead(String path) throws IOException {
                            throw new NoSuchFileException(path);
                        }
                    });
                } catch (FileSystemException e) {
                    throw new IllegalStateException(e);
                }
            }
            return fs;
        }
    };

    /** Host file for a path inside a host mount, or null if the path belongs to CC. */
    private Resolved resolve(VPath p) {
        for (HostMount m : mounts) {
            if (p.startsWith(m.prefix())) {
                Path host = m.hostRoot();
                for (String part : ((VPath) m.prefix().relativize(p)).parts()) host = host.resolve(part);
                return new Resolved(m, host.normalize());
            }
        }
        return null;
    }

    private record Resolved(HostMount mount, Path host) {
        boolean escapes() {
            return !host.startsWith(mount.hostRoot().normalize());
        }
    }

    /** Names of host mounts that live directly inside the given directory (so listing /rom shows "python"). */
    private List<String> mountChildren(VPath dir) {
        List<String> out = new ArrayList<>();
        for (HostMount m : mounts) {
            VPath parent = (VPath) m.prefix().getParent();
            if (parent != null && parent.equals(dir)) out.add(m.prefix().getFileName().toString());
        }
        return out;
    }

    private boolean isMountAncestor(VPath dir) {
        for (HostMount m : mounts) {
            if (m.prefix().startsWith(dir) && !m.prefix().equals(dir)) return true;
        }
        return false;
    }

    private boolean exists(VPath p) throws IOException {
        Resolved r = resolve(p);
        if (r != null) return !r.escapes() && Files.exists(r.host());
        if (isMountAncestor(p)) return true;
        try {
            return cc().exists(p.ccPath());
        } catch (FileSystemException e) {
            return false;
        }
    }

    private boolean isDir(VPath p) throws IOException {
        Resolved r = resolve(p);
        if (r != null) return !r.escapes() && Files.isDirectory(r.host());
        if (isMountAncestor(p)) return true;
        try {
            return cc().isDir(p.ccPath());
        } catch (FileSystemException e) {
            return false;
        }
    }

    // --- errors ---------------------------------------------------------------------------------------------

    private static IOException translate(VPath p, FileSystemException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String path = p.toString();
        if (msg.contains("No such file")) return new NoSuchFileException(path);
        if (msg.contains("Access denied")) return new AccessDeniedException(path);
        if (msg.contains("File exists")) return new FileAlreadyExistsException(path);
        if (msg.contains("Not a directory")) return new NotDirectoryException(path);
        if (msg.contains("Out of space")) return new IOException(path + ": No space left on device");
        return new IOException(path + ": " + msg);
    }

    private static AccessDeniedException readOnly(VPath p) {
        return new AccessDeniedException(p.toString(), null, "Read-only file system");
    }

    // --- operations -----------------------------------------------------------------------------------------

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        VPath p = abs(path);
        Resolved r = resolve(p);
        if (r != null) {
            if (r.escapes() || !Files.exists(r.host())) throw new NoSuchFileException(p.toString());
            if (modes.contains(AccessMode.WRITE) && r.mount().readOnly()) throw readOnly(p);
            return;
        }
        if (isMountAncestor(p)) {
            if (modes.contains(AccessMode.WRITE)) throw readOnly(p);
            return;
        }
        try {
            if (!cc().exists(p.ccPath())) throw new NoSuchFileException(p.toString());
            if (modes.contains(AccessMode.WRITE) && cc().isReadOnly(p.ccPath())) throw readOnly(p);
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        VPath p = abs(dir);
        Resolved r = resolve(p);
        if (r != null) {
            if (r.mount().readOnly() || r.escapes()) throw readOnly(p);
            Files.createDirectory(r.host());
            return;
        }
        if (exists(p)) throw new FileAlreadyExistsException(p.toString());
        VPath parent = (VPath) p.getParent();
        if (parent != null && !isDir(parent)) throw new NoSuchFileException(p.toString());
        try {
            cc().makeDir(p.ccPath());
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        VPath p = abs(path);
        Resolved r = resolve(p);
        if (r != null) {
            if (r.mount().readOnly() || r.escapes()) throw readOnly(p);
            Files.delete(r.host());
            return;
        }
        if (isMountAncestor(p)) throw readOnly(p);
        try {
            String cp = p.ccPath();
            if (!cc().exists(cp)) throw new NoSuchFileException(p.toString());
            // CC deletes directories recursively; POSIX rmdir must refuse a non-empty one.
            if (cc().isDir(cp) && !cc().list(cp).isEmpty()) throw new DirectoryNotEmptyException(p.toString());
            if (cc().isReadOnly(cp)) throw readOnly(p);
            cc().delete(cp);
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        VPath p = abs(path);
        boolean write = options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND);
        Resolved r = resolve(p);
        if (r != null) {
            if (r.escapes()) throw new NoSuchFileException(p.toString());
            if (write && r.mount().readOnly()) throw readOnly(p);
            return Files.newByteChannel(r.host(), options);
        }
        if (isMountAncestor(p)) throw new IOException(p + ": Is a directory");

        String cp = p.ccPath();
        try {
            boolean exists = cc().exists(cp);
            if (exists && cc().isDir(cp)) throw new IOException(p + ": Is a directory");
            if (!write) {
                if (!exists) throw new NoSuchFileException(p.toString());
                return new WrappedChannel(cc().openForRead(cp), false);
            }

            boolean create = options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW);
            if (options.contains(StandardOpenOption.CREATE_NEW) && exists) throw new FileAlreadyExistsException(p.toString());
            if (!exists && !create) throw new NoSuchFileException(p.toString());
            // CC would silently create missing parent directories; open() must fail instead.
            VPath parent = (VPath) p.getParent();
            if (!exists && parent != null && !isDir(parent)) throw new NoSuchFileException(p.toString());
            if (cc().isReadOnly(cp)) throw readOnly(p);

            Set<OpenOption> ccOptions = new HashSet<>();
            for (OpenOption o : options) {
                if (o == StandardOpenOption.CREATE_NEW) ccOptions.add(StandardOpenOption.CREATE);
                else if (o == StandardOpenOption.READ || o == StandardOpenOption.WRITE || o == StandardOpenOption.APPEND
                    || o == StandardOpenOption.CREATE || o == StandardOpenOption.TRUNCATE_EXISTING) ccOptions.add(o);
            }
            boolean append = ccOptions.contains(StandardOpenOption.APPEND);
            // CC refuses READ together with APPEND ("a+"); such a handle can only write.
            if (append) ccOptions.remove(StandardOpenOption.READ);
            ccOptions.add(StandardOpenOption.WRITE);
            return new WrappedChannel(cc().openForWrite(cp, ccOptions), true);
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        VPath p = abs(dir);
        Set<String> names = new LinkedHashSet<>();
        Resolved r = resolve(p);
        if (r != null) {
            if (r.escapes() || !Files.isDirectory(r.host())) throw new NotDirectoryException(p.toString());
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(r.host())) {
                for (Path child : ds) names.add(child.getFileName().toString());
            }
        } else if (isMountAncestor(p) && !safeExists(p)) {
            // Purely virtual directory that only holds mounts.
        } else {
            try {
                if (!cc().exists(p.ccPath())) throw new NoSuchFileException(p.toString());
                if (!cc().isDir(p.ccPath())) throw new NotDirectoryException(p.toString());
                names.addAll(cc().list(p.ccPath()));
            } catch (FileSystemException e) {
                throw translate(p, e);
            }
        }
        names.addAll(mountChildren(p));

        List<Path> entries = new ArrayList<>();
        for (String n : names) {
            Path child = p.resolve(n);
            if (filter == null || filter.accept(child)) entries.add(child);
        }
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return entries.iterator();
            }

            @Override
            public void close() {}
        };
    }

    private boolean safeExists(VPath p) {
        try {
            return cc().exists(p.ccPath());
        } catch (FileSystemException e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        VPath p = abs(path);
        long size;
        boolean dir;
        FileTime modified, created, accessed;
        boolean readOnly;

        Resolved r = resolve(p);
        if (r != null) {
            if (r.escapes()) throw new NoSuchFileException(p.toString());
            BasicFileAttributes a = Files.readAttributes(r.host(), BasicFileAttributes.class);
            size = a.size();
            dir = a.isDirectory();
            modified = a.lastModifiedTime();
            created = a.creationTime();
            accessed = a.lastAccessTime();
            readOnly = r.mount().readOnly();
        } else if (isMountAncestor(p) && !safeExists(p)) {
            size = 0;
            dir = true;
            modified = created = accessed = FileTime.fromMillis(0);
            readOnly = true;
        } else {
            try {
                String cp = p.ccPath();
                if (!cc().exists(cp)) throw new NoSuchFileException(p.toString());
                BasicFileAttributes a = cc().getAttributes(cp);
                size = a.size();
                dir = a.isDirectory();
                modified = a.lastModifiedTime();
                created = a.creationTime();
                accessed = a.lastAccessTime() != null ? a.lastAccessTime() : modified;
                readOnly = cc().isReadOnly(cp);
            } catch (FileSystemException e) {
                throw translate(p, e);
            }
        }

        String view = "basic";
        String names = attributes;
        int colon = attributes.indexOf(':');
        if (colon >= 0) {
            view = attributes.substring(0, colon);
            names = attributes.substring(colon + 1);
        }

        Map<String, Object> all = new HashMap<>();
        all.put("size", size);
        all.put("isDirectory", dir);
        all.put("isRegularFile", !dir);
        all.put("isSymbolicLink", false);
        all.put("isOther", false);
        all.put("lastModifiedTime", modified);
        all.put("lastAccessTime", accessed);
        all.put("creationTime", created);
        all.put("fileKey", null);
        if (view.equals("unix") || view.equals("posix")) {
            int perm = dir ? 0755 : 0644;
            if (readOnly) perm &= ~0222;
            all.put("mode", (dir ? 0040000 : 0100000) | perm);
            all.put("ino", (long) p.toString().hashCode() & 0xffffffffL);
            all.put("dev", 1L);
            all.put("rdev", 0L);
            all.put("nlink", 1);
            all.put("uid", 0);
            all.put("gid", 0);
            all.put("ctime", modified);
            all.put("permissions", null);
            all.put("owner", null);
            all.put("group", null);
        }

        if (names.equals("*")) return all;
        Map<String, Object> out = new HashMap<>();
        for (String n : names.split(",")) {
            if (all.containsKey(n)) out.put(n, all.get(n));
        }
        return out;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        // CC has no permissions or settable timestamps; accept silently like a FAT drive.
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        VPath s = abs(source), t = abs(target);
        if (resolve(s) != null || resolve(t) != null) throw readOnly(resolve(s) != null ? s : t);
        try {
            if (!cc().exists(s.ccPath())) throw new NoSuchFileException(s.toString());
            if (cc().exists(t.ccPath())) {
                boolean replace = false;
                for (CopyOption o : options) replace |= o == StandardCopyOption.REPLACE_EXISTING;
                if (!replace) throw new FileAlreadyExistsException(t.toString());
                if (cc().isDir(t.ccPath()) && !cc().list(t.ccPath()).isEmpty()) throw new DirectoryNotEmptyException(t.toString());
                cc().delete(t.ccPath());
            }
            cc().move(s.ccPath(), t.ccPath());
        } catch (FileSystemException e) {
            throw translate(s, e);
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        VPath s = abs(source), t = abs(target);
        if (resolve(t) != null) throw readOnly(t);
        Resolved rs = resolve(s);
        try {
            if (cc().exists(t.ccPath())) {
                boolean replace = false;
                for (CopyOption o : options) replace |= o == StandardCopyOption.REPLACE_EXISTING;
                if (!replace) throw new FileAlreadyExistsException(t.toString());
                cc().delete(t.ccPath());
            }
            if (rs != null) {
                if (rs.escapes()) throw new NoSuchFileException(s.toString());
                byte[] data = Files.readAllBytes(rs.host());
                try (var w = cc().openForWrite(t.ccPath(), Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    w.get().write(ByteBuffer.wrap(data));
                }
                return;
            }
            cc().copy(s.ccPath(), t.ccPath());
        } catch (FileSystemException e) {
            throw translate(s, e);
        }
    }

    @Override
    public long getFileStoreUsableSpace(Path path) throws IOException {
        VPath p = abs(path);
        if (resolve(p) != null) return 0;
        try {
            return cc().getFreeSpace(p.ccPath());
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    @Override
    public long getFileStoreUnallocatedSpace(Path path) throws IOException {
        return getFileStoreUsableSpace(path);
    }

    @Override
    public long getFileStoreTotalSpace(Path path) throws IOException {
        VPath p = abs(path);
        if (resolve(p) != null) return 0;
        try {
            return cc().getCapacity(p.ccPath()).orElse(cc().getFreeSpace(p.ccPath()));
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    @Override
    public boolean isFileStoreReadOnly(Path path) throws IOException {
        VPath p = abs(path);
        Resolved r = resolve(p);
        if (r != null) return r.mount().readOnly();
        try {
            return cc().isReadOnly(p.ccPath());
        } catch (FileSystemException e) {
            throw translate(p, e);
        }
    }

    /** Channel that releases the CC file handle (and its open-file slot) when closed. */
    private static final class WrappedChannel implements SeekableByteChannel {
        private final FileSystemWrapper<SeekableByteChannel> wrapper;
        private final SeekableByteChannel ch;
        private final boolean writable;

        WrappedChannel(FileSystemWrapper<SeekableByteChannel> wrapper, boolean writable) {
            this.wrapper = wrapper;
            this.ch = wrapper.get();
            this.writable = writable;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return ch.read(dst);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (!writable) throw new NonWritableChannelException();
            return ch.write(src);
        }

        @Override
        public long position() throws IOException {
            return ch.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ch.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return ch.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ch.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return ch.isOpen();
        }

        @Override
        public void close() throws IOException {
            wrapper.close();
        }
    }
}
