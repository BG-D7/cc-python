package work.diffdev.kik.python.net;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.sqlite.SQLiteConnection;
import work.diffdev.kik.python.PyRun;
import work.diffdev.kik.python.vfs.CCFileSystem;
import work.diffdev.kik.python.vfs.VPath;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQLite for Python's sqlite3 module. The database lives in memory and is stored as an ordinary file on the
 * computer's disk on every commit, so it obeys the disk quota, moves with the computer and never touches the host
 * filesystem.
 */
public final class SqliteBridge implements AutoCloseable {
    private final PyRun run;
    private final VPath path; // null for :memory:
    private final SQLiteConnection conn;
    private final String error;
    private boolean autocommit;
    private long savedChanges;

    private SqliteBridge(PyRun run, VPath path, SQLiteConnection conn, String error) {
        this.run = run;
        this.path = path;
        this.conn = conn;
        this.error = error;
    }

    public static SqliteBridge open(PyRun run, String database) {
        VPath path = null;
        try {
            byte[] image = null;
            if (!database.equals(":memory:") && !database.isEmpty()) {
                CCFileSystem fs = run.fileSystem();
                path = (VPath) fs.toAbsolutePath(fs.parsePath(database));
                Path parent = path.getParent();
                if (parent != null && !Boolean.TRUE.equals(isDir(fs, parent))) throw new NoSuchFileException(parent + " does not exist");
                image = read(fs, path);
            }
            SQLiteConnection conn = (SQLiteConnection) new org.sqlite.JDBC().connect("jdbc:sqlite::memory:", new java.util.Properties());
            if (image != null && image.length > 0) conn.deserialize("main", image);
            conn.setAutoCommit(false);
            SqliteBridge db = new SqliteBridge(run, path, conn, null);
            db.savedChanges = conn.getDatabase().total_changes();
            run.track(db);
            return db;
        } catch (SQLException | IOException e) {
            return new SqliteBridge(run, path, null, "unable to open database file (" + e.getMessage() + ")");
        }
    }

    private static Object isDir(CCFileSystem fs, Path dir) {
        try {
            return fs.readAttributes(dir, "isDirectory").get("isDirectory");
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] read(CCFileSystem fs, VPath path) throws IOException {
        try (SeekableByteChannel ch = fs.newByteChannel(path, Set.of(StandardOpenOption.READ))) {
            ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
            while (buf.hasRemaining() && ch.read(buf) > 0) {}
            return buf.array();
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Null if open, else why opening failed. */
    @HostAccess.Export
    public String error() {
        return error;
    }

    /** Autocommit mode (Python's isolation_level=None): every change is stored straight away. */
    @HostAccess.Export
    public Object[] set_autocommit(boolean on) {
        try {
            conn.setAutoCommit(on);
            autocommit = on;
            if (on) persist();
            return new Object[] { "ok" };
        } catch (SQLException e) {
            return sqlError(e);
        } catch (IOException e) {
            return new Object[] { "error", "OperationalError", "disk I/O error: cannot save " + path + " (" + e.getMessage() + ")" };
        }
    }

    /**
     * Run one statement. Result: {@code "ok", tokens([columns, rows]), rowcount, lastrowid} or {@code "error", kind,
     * message} where kind is a sqlite3 exception class name.
     */
    @HostAccess.Export
    public Object[] execute(String sql, Value params) {
        run.checkStopping();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            List<Object> p = params(params);
            for (int i = 0; i < p.size(); i++) st.setObject(i + 1, p.get(i));
            boolean hasRows = st.execute();
            List<Object> tokens = new ArrayList<>();
            long rowcount = -1;
            if (hasRows) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    tokens.add("l");
                    tokens.add(n);
                    for (int c = 1; c <= n; c++) {
                        tokens.add("s");
                        tokens.add(md.getColumnLabel(c));
                    }
                    List<Object> rows = new ArrayList<>();
                    int count = 0;
                    while (rs.next()) {
                        if ((count & 255) == 0) run.checkStopping();
                        rows.add("l");
                        rows.add(n);
                        for (int c = 1; c <= n; c++) value(rs, c, rows);
                        count++;
                    }
                    tokens.add("l");
                    tokens.add(count);
                    tokens.addAll(rows);
                }
            } else {
                rowcount = st.getUpdateCount();
                tokens.add(null);
                tokens.add("l");
                tokens.add(0);
            }
            long lastRowId = conn.getDatabase().total_changes() > 0 ? lastInsertRowId() : 0;
            if (autocommit) persist();
            return new Object[] { "ok", tokens.toArray(), rowcount, lastRowId };
        } catch (SQLException e) {
            return sqlError(e);
        } catch (IOException e) {
            return new Object[] { "error", "OperationalError", "disk I/O error: cannot save " + path + " (" + e.getMessage() + ")" };
        }
    }

    /** Run several statements separated by semicolons (executescript); commits first, like CPython. */
    @HostAccess.Export
    public Object[] execute_script(String sql) {
        run.checkStopping();
        try {
            if (!conn.getAutoCommit()) conn.commit();
            conn.getDatabase().exec(sql, conn.getAutoCommit());
            if (!conn.getAutoCommit()) conn.commit();
            persist();
            return new Object[] { "ok" };
        } catch (SQLException e) {
            return sqlError(e);
        } catch (IOException e) {
            return new Object[] { "error", "OperationalError", "disk I/O error: cannot save " + path + " (" + e.getMessage() + ")" };
        }
    }

    @HostAccess.Export
    public Object[] commit() {
        try {
            if (!conn.getAutoCommit()) conn.commit();
            persist();
            return new Object[] { "ok" };
        } catch (SQLException e) {
            return sqlError(e);
        } catch (IOException e) {
            return new Object[] { "error", "OperationalError", "disk I/O error: cannot save " + path + " (" + e.getMessage() + ")" };
        }
    }

    @HostAccess.Export
    public Object[] rollback() {
        try {
            if (!conn.getAutoCommit()) conn.rollback();
            return new Object[] { "ok" };
        } catch (SQLException e) {
            return sqlError(e);
        }
    }

    @HostAccess.Export
    @Override
    public void close() {
        run.untrack(this);
        try {
            if (conn != null && !conn.isClosed()) {
                // Like CPython, closing without commit() discards the open transaction.
                if (!conn.getAutoCommit()) conn.rollback();
                conn.close();
            }
        } catch (SQLException ignored) {}
    }

    private void persist() throws SQLException, IOException {
        if (path == null) return;
        long changes = conn.getDatabase().total_changes();
        if (changes == savedChanges) return;
        byte[] image = conn.serialize("main");
        CCFileSystem fs = run.fileSystem();
        try (SeekableByteChannel ch = fs.newByteChannel(path, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            ByteBuffer buf = ByteBuffer.wrap(image);
            while (buf.hasRemaining()) ch.write(buf);
        }
        savedChanges = changes;
    }

    private long lastInsertRowId() throws SQLException {
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static void value(ResultSet rs, int c, List<Object> out) throws SQLException {
        Object v = rs.getObject(c);
        if (v == null) {
            out.add(null);
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            out.add(((Number) v).longValue());
        } else if (v instanceof Number n) {
            out.add(n.doubleValue());
        } else if (v instanceof byte[] b) {
            out.add("y");
            out.add(new String(b, StandardCharsets.ISO_8859_1));
        } else {
            out.add("s");
            out.add(v.toString());
        }
    }

    /** Parameters: primitives, "s", text, "y", latin-1 bytes. */
    private static List<Object> params(Value t) {
        List<Object> out = new ArrayList<>();
        if (t == null || t.isNull()) return out;
        long n = t.getArraySize();
        for (int i = 0; i < n; i++) {
            Value v = t.getArrayElement(i);
            if (v.isNull()) out.add(null);
            else if (v.isBoolean()) out.add(v.asBoolean() ? 1L : 0L);
            else if (v.isNumber()) out.add(v.fitsInLong() ? (Object) v.asLong() : (Object) v.asDouble());
            else {
                String tag = v.asString();
                String s = t.getArrayElement(++i).asString();
                out.add(tag.equals("y") ? s.getBytes(StandardCharsets.ISO_8859_1) : s);
            }
        }
        return out;
    }

    private static Object[] sqlError(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        // sqlite-jdbc prefixes messages with "[SQLITE_xxx] description (detail)"; keep the detail like CPython.
        int open = msg.lastIndexOf('(');
        String detail = open >= 0 && msg.endsWith(")") ? msg.substring(open + 1, msg.length() - 1) : msg;
        String kind = msg.contains("CONSTRAINT") ? "IntegrityError"
            : msg.contains("SQLITE_ERROR") && (msg.contains("syntax") || msg.contains("no such")) ? "OperationalError"
            : msg.contains("SQLITE_RANGE") || msg.contains("parameter") ? "ProgrammingError"
            : "OperationalError";
        return new Object[] { "error", kind, detail };
    }
}
