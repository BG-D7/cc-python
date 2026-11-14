# SPDX-FileCopyrightText: 2026 diff
"""DB-API 2.0 sqlite3 for ComputerCraft.

The database file lives on the computer's own disk (it counts against the disk quota and moves with the computer).
Changes are written to that file on commit, or after every statement in autocommit mode (isolation_level=None).
"""

import datetime as _datetime
import re as _re

import _kik

apilevel = "2.0"
threadsafety = 1
paramstyle = "qmark"
sqlite_version = "3.46.0"
sqlite_version_info = (3, 46, 0)
version = "2.6.0"
version_info = (2, 6, 0)
PARSE_DECLTYPES = 1
PARSE_COLNAMES = 2


class Warning(Exception):
    pass


class Error(Exception):
    sqlite_errorcode = None
    sqlite_errorname = None


class InterfaceError(Error):
    pass


class DatabaseError(Error):
    pass


class DataError(DatabaseError):
    pass


class OperationalError(DatabaseError):
    pass


class IntegrityError(DatabaseError):
    pass


class InternalError(DatabaseError):
    pass


class ProgrammingError(DatabaseError):
    pass


class NotSupportedError(DatabaseError):
    pass


_ERRORS = {c.__name__: c for c in (Error, InterfaceError, DatabaseError, DataError, OperationalError,
                                   IntegrityError, InternalError, ProgrammingError, NotSupportedError)}


def _raise(result):
    raise _ERRORS.get(result[1], OperationalError)(result[2])


_adapters = {}


def register_adapter(type_, adapter):
    _adapters[type_] = adapter


def register_converter(name, converter):
    pass  # detect_types is not supported; values come back as stored


class Row:
    def __init__(self, cursor, values):
        self._names = [d[0] for d in cursor.description]
        self._values = tuple(values)

    def keys(self):
        return list(self._names)

    def __getitem__(self, key):
        if isinstance(key, (int, slice)):
            return self._values[key]
        low = key.lower()
        for i, n in enumerate(self._names):
            if n.lower() == low:
                return self._values[i]
        raise IndexError("No item with that key")

    def __len__(self):
        return len(self._values)

    def __iter__(self):
        return iter(self._values)

    def __eq__(self, other):
        return isinstance(other, Row) and self._names == other._names and self._values == other._values

    def __hash__(self):
        return hash((tuple(self._names), self._values))

    def __repr__(self):
        return "<sqlite3.Row %r>" % (self._values,)


def _param(v):
    a = _adapters.get(type(v))
    if a is not None:
        v = a(v)
    if v is None or isinstance(v, (bool, int, float)):
        return [v]
    if isinstance(v, str):
        return ["s", v]
    if isinstance(v, (bytes, bytearray, memoryview)):
        return ["y", bytes(v).decode("latin-1")]
    if isinstance(v, _datetime.datetime):
        return ["s", v.isoformat(" ")]
    if isinstance(v, _datetime.date):
        return ["s", v.isoformat()]
    raise ProgrammingError("Error binding parameter: type %r is not supported" % type(v).__name__)


# A string literal, quoted identifier or comment (kept as is), or a named parameter.
_NAMED = _re.compile(r"""('(?:[^']|'')*'|"(?:[^"]|"")*"|--[^\n]*|/\*.*?\*/)|[:@$]([A-Za-z_][A-Za-z0-9_]*)""", _re.S)


def _bind(sql, parameters):
    """Turns named parameters into positional ones; returns (sql, flat token list)."""
    if parameters is None:
        parameters = ()
    if isinstance(parameters, dict) or (hasattr(parameters, "keys") and hasattr(parameters, "__getitem__")):
        values = []

        def repl(m):
            if m.group(1):
                return m.group(1)
            name = m.group(2)
            try:
                values.append(parameters[name])
            except KeyError:
                raise ProgrammingError("You did not supply a value for binding parameter :%s." % name) from None
            return "?"

        sql = _NAMED.sub(repl, sql)
        parameters = values
    elif isinstance(parameters, (str, bytes)) or not hasattr(parameters, "__iter__"):
        raise ProgrammingError("parameters are of unsupported type")
    flat = []
    for v in parameters:
        flat += _param(v)
    return sql, flat


class Cursor:
    def __init__(self, connection):
        self.connection = connection
        self.description = None
        self.rowcount = -1
        self.lastrowid = None
        self.arraysize = 1
        self.row_factory = connection.row_factory
        self._rows = []
        self._pos = 0
        self._closed = False

    def _check(self):
        if self._closed:
            raise ProgrammingError("Cannot operate on a closed cursor.")
        self.connection._check()

    def execute(self, sql, parameters=()):
        self._check()
        conn = self.connection
        sql, flat = _bind(sql, parameters)
        r = list(conn._db.execute(sql, flat))
        if r[0] != "ok":
            _raise(r)
        conn._note(sql)
        cols, rows = _kik._dec(list(r[1]))
        self.rowcount = int(r[2])
        if r[3]:
            self.lastrowid = int(r[3])
        if cols is None:
            self.description = None
            self._rows = []
        else:
            self.description = tuple((c, None, None, None, None, None, None) for c in cols)
            factory = self.row_factory
            out = []
            for row in rows:
                row = tuple(conn._text(v) for v in row)
                out.append(factory(self, row) if factory is not None else row)
            self._rows = out
        self._pos = 0
        return self

    def executemany(self, sql, seq_of_parameters):
        self._check()
        total = 0
        for params in seq_of_parameters:
            self.execute(sql, params)
            if self.description is not None:
                raise ProgrammingError("executemany() can only execute DML statements.")
            total += max(self.rowcount, 0)
        self.rowcount = total
        return self

    def executescript(self, sql_script):
        self._check()
        r = list(self.connection._db.execute_script(sql_script))
        if r[0] != "ok":
            _raise(r)
        self.connection._in_txn = False
        self.description = None
        self._rows = []
        return self

    def fetchone(self):
        self._check()
        if self._pos >= len(self._rows):
            return None
        row = self._rows[self._pos]
        self._pos += 1
        return row

    def fetchmany(self, size=None):
        self._check()
        n = self.arraysize if size is None else size
        out = self._rows[self._pos:self._pos + n]
        self._pos += len(out)
        return out

    def fetchall(self):
        self._check()
        out = self._rows[self._pos:]
        self._pos = len(self._rows)
        return out

    def setinputsizes(self, sizes):
        pass

    def setoutputsize(self, size, column=None):
        pass

    def close(self):
        self._closed = True
        self._rows = []

    def __iter__(self):
        return self

    def __next__(self):
        row = self.fetchone()
        if row is None:
            raise StopIteration
        return row


_DML = _re.compile(r"\s*(INSERT|UPDATE|DELETE|REPLACE)\b", _re.I)


class Connection:
    Warning = Warning
    Error = Error
    InterfaceError = InterfaceError
    DatabaseError = DatabaseError
    DataError = DataError
    OperationalError = OperationalError
    IntegrityError = IntegrityError
    InternalError = InternalError
    ProgrammingError = ProgrammingError
    NotSupportedError = NotSupportedError

    def __init__(self, database, timeout=5.0, detect_types=0, isolation_level="", check_same_thread=True,
                 factory=None, cached_statements=128, uri=False):
        if hasattr(database, "__fspath__"):
            database = database.__fspath__()
        if isinstance(database, bytes):
            database = database.decode("utf-8")
        if uri and database.startswith("file:"):
            database = database[5:].split("?", 1)[0] or ":memory:"
        self._db = None
        db = _kik._native.sqlite_open(database)
        err = db.error()
        if err is not None:
            raise OperationalError(err)
        self._db = db
        self.row_factory = None
        self.text_factory = str
        self._isolation_level = ""
        self._in_txn = False
        self.isolation_level = isolation_level

    def _check(self):
        if self._db is None:
            raise ProgrammingError("Cannot operate on a closed database.")

    @property
    def isolation_level(self):
        return self._isolation_level

    @isolation_level.setter
    def isolation_level(self, value):
        self._check()
        r = list(self._db.set_autocommit(value is None))
        if r[0] != "ok":
            _raise(r)
        self._isolation_level = value
        if value is None:
            self._in_txn = False

    @property
    def in_transaction(self):
        return self._in_txn

    @property
    def total_changes(self):
        return self.execute("SELECT total_changes()").fetchone()[0]

    def _note(self, sql):
        # The bridge keeps a transaction open unless autocommit is on; report it the way sqlite3 does (after DML).
        if self._isolation_level is not None and _DML.match(sql):
            self._in_txn = True

    def _text(self, v):
        if isinstance(v, str) and self.text_factory is not str:
            raw = v.encode("utf-8")
            return raw if self.text_factory is bytes else self.text_factory(raw)
        return v

    def cursor(self, factory=Cursor):
        self._check()
        return factory(self)

    def execute(self, sql, parameters=()):
        return self.cursor().execute(sql, parameters)

    def executemany(self, sql, seq_of_parameters):
        return self.cursor().executemany(sql, seq_of_parameters)

    def executescript(self, sql_script):
        return self.cursor().executescript(sql_script)

    def commit(self):
        self._check()
        r = list(self._db.commit())
        if r[0] != "ok":
            _raise(r)
        self._in_txn = False

    def rollback(self):
        self._check()
        r = list(self._db.rollback())
        if r[0] != "ok":
            _raise(r)
        self._in_txn = False

    def close(self):
        if self._db is not None:
            self._db.close()
            self._db = None

    def create_function(self, name, narg, func, *, deterministic=False):
        raise NotSupportedError("user-defined SQL functions are not supported")

    def iterdump(self):
        yield "BEGIN TRANSACTION;"
        tables = self.execute("SELECT name, sql FROM sqlite_master WHERE sql NOT NULL AND type == 'table'").fetchall()
        for name, sql in tables:
            if name.startswith("sqlite_"):
                continue
            yield "%s;" % sql
            qname = name.replace('"', '""')
            cols = [r[1] for r in self.execute('PRAGMA table_info("%s")' % qname).fetchall()]
            select = ", ".join('quote("%s")' % c.replace('"', '""') for c in cols)
            for row in self.execute('SELECT %s FROM "%s"' % (select, qname)).fetchall():
                yield 'INSERT INTO "%s" VALUES(%s);' % (qname, ",".join(str(x) for x in row))
        for (sql,) in self.execute("SELECT sql FROM sqlite_master WHERE sql NOT NULL AND "
                                   "type IN ('index', 'trigger', 'view')").fetchall():
            yield "%s;" % sql
        yield "COMMIT;"

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        if exc_type is None:
            self.commit()
        else:
            self.rollback()
        return False

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


def connect(database, timeout=5.0, detect_types=0, isolation_level="", check_same_thread=True, factory=Connection,
            cached_statements=128, uri=False, **kwargs):
    return factory(database, timeout, detect_types, isolation_level, check_same_thread, None, cached_statements, uri)


def complete_statement(statement):
    s = _NAMED.sub(lambda m: "" if m.group(1) else m.group(0), statement).strip()
    return s.endswith(";")


Binary = memoryview
Date = _datetime.date
Time = _datetime.time
Timestamp = _datetime.datetime
