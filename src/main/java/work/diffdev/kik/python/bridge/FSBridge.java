package work.diffdev.kik.python.bridge;

import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.core.filesystem.FileSystemException;
import dan200.computercraft.core.filesystem.FileSystemWrapper;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Collections;
import java.util.Set;

public class FSBridge {
    private final FileSystem fs;

    public FSBridge(FileSystem fs) {
        this.fs = fs;
    }

    public List<String> list(String path) {
        if (fs == null) return Collections.emptyList();
        try {
            return fs.list(path);
        } catch (FileSystemException e) {
            return Collections.emptyList();
        }
    }

    public boolean exists(String path) {
        if (fs == null) return false;
        try {
            return fs.exists(path);
        } catch (FileSystemException e) {
            return false;
        }
    }

    public boolean isDir(String path) {
        if (fs == null) return false;
        try {
            return fs.isDir(path);
        } catch (FileSystemException e) {
            return false;
        }
    }

    public boolean isReadOnly(String path) {
        if (fs == null) return true;
        try {
            return fs.isReadOnly(path);
        } catch (FileSystemException e) {
            return true;
        }
    }

    public String getName(String path) {
        return FileSystem.getName(path);
    }

    public long getSize(String path) {
        if (fs == null) return 0;
        try {
            return fs.getSize(path);
        } catch (FileSystemException e) {
            return 0;
        }
    }

    public void makeDir(String path) {
        if (fs != null) {
            try {
                fs.makeDir(path);
            } catch (FileSystemException ignored) {}
        }
    }

    public void delete(String path) {
        if (fs != null) {
            try {
                fs.delete(path);
            } catch (FileSystemException ignored) {}
        }
    }

    public void move(String from, String to) {
        if (fs != null) {
            try {
                fs.move(from, to);
            } catch (FileSystemException ignored) {}
        }
    }

    public void copy(String from, String to) {
        if (fs != null) {
            try {
                fs.copy(from, to);
            } catch (FileSystemException ignored) {}
        }
    }

    public String readAll(String path) {
        if (fs == null) return null;
        try (FileSystemWrapper<SeekableByteChannel> wrapper = fs.openForRead(path)) {
            SeekableByteChannel ch = wrapper.get();
            ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
            ch.read(buf);
            return new String(buf.array(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean writeAll(String path, String content) {
        if (fs == null) return false;
        try (FileSystemWrapper<SeekableByteChannel> wrapper = fs.openForWrite(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            SeekableByteChannel ch = wrapper.get();
            ByteBuffer buf = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            ch.write(buf);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
