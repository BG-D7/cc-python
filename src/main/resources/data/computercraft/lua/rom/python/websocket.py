# SPDX-FileCopyrightText: 2026 diff
"""A subset of the `websocket-client` library for ComputerCraft.

Connections follow the server's CC: Tweaked HTTP rules and websocket message size limit, unless the server overrides
them in the CC: Python config.
"""

import _ccpy

__all__ = ["create_connection", "WebSocket", "WebSocketApp", "WebSocketException", "WebSocketTimeoutException",
           "WebSocketConnectionClosedException", "WebSocketBadStatusException", "ABNF"]


class WebSocketException(Exception):
    pass


class WebSocketTimeoutException(WebSocketException):
    pass


class WebSocketConnectionClosedException(WebSocketException):
    pass


class WebSocketBadStatusException(WebSocketException):
    pass


class WebSocketPayloadException(WebSocketException):
    pass


class ABNF:
    OPCODE_CONT = 0x0
    OPCODE_TEXT = 0x1
    OPCODE_BINARY = 0x2
    OPCODE_CLOSE = 0x8
    OPCODE_PING = 0x9
    OPCODE_PONG = 0xA


def _headers(header):
    if not header:
        return []
    items = header.items() if isinstance(header, dict) else (h.split(":", 1) for h in header)
    flat = []
    for k, v in items:
        flat += ["s", str(k).strip(), "s", str(v).strip()]
    return flat


class WebSocket:
    """A connected websocket. Use create_connection() to get one."""

    def __init__(self, timeout=None):
        self._ws = None
        self.timeout = timeout
        self.connected = False
        self.status = None
        self.close_status = None
        self.close_reason = None

    def connect(self, url, header=None, timeout=None, **options):
        if timeout is not None:
            self.timeout = timeout
        ws = _ccpy._native.websocket(url, _headers(header), float(self.timeout or 0))
        err = ws.error()
        if err is not None:
            if err == "Timed out":
                raise WebSocketTimeoutException(err)
            raise WebSocketBadStatusException(err) if "status" in err.lower() else WebSocketException(err)
        self._ws = ws
        self.connected = True
        self.status = 101

    def settimeout(self, timeout):
        self.timeout = timeout

    def gettimeout(self):
        return self.timeout

    def _need(self):
        if not self.connected:
            raise WebSocketConnectionClosedException("socket is already closed.")

    def send(self, payload, opcode=ABNF.OPCODE_TEXT):
        self._need()
        if isinstance(payload, (bytes, bytearray, memoryview)) or opcode == ABNF.OPCODE_BINARY:
            if isinstance(payload, str):
                payload = payload.encode("utf-8")
            err = self._ws.send(bytes(payload).decode("latin-1"), True)
        else:
            err = self._ws.send(str(payload), False)
        if err is not None:
            if err == "Websocket is closed":
                self.connected = False
                raise WebSocketConnectionClosedException(err)
            if err == "Message is too large":
                raise WebSocketPayloadException(err)
            raise WebSocketException(err)
        return len(payload)

    def send_text(self, text):
        return self.send(text, ABNF.OPCODE_TEXT)

    def send_bytes(self, data):
        return self.send(data, ABNF.OPCODE_BINARY)

    send_binary = send_bytes

    def recv_data(self):
        self._need()
        m = list(self._ws.receive(float(self.timeout or 0)))
        kind = m[0]
        if kind == "text":
            return ABNF.OPCODE_TEXT, m[1]
        if kind == "binary":
            return ABNF.OPCODE_BINARY, m[1].encode("latin-1")
        if kind == "timeout":
            raise WebSocketTimeoutException("Connection timed out")
        self.connected = False
        if kind == "close":
            self.close_status, self.close_reason = int(m[1]), m[2]
            raise WebSocketConnectionClosedException("Connection to remote host was lost.")
        raise WebSocketException(m[1])

    def recv(self):
        return self.recv_data()[1]

    def ping(self, payload=""):
        self._need()

    def pong(self, payload=""):
        self._need()

    def close(self, status=1000, reason=b"", timeout=3):
        if self._ws is not None:
            self._ws.close()
        self.connected = False

    abort = shutdown = close

    def __iter__(self):
        return self

    def __next__(self):
        try:
            return self.recv()
        except WebSocketConnectionClosedException:
            raise StopIteration from None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def create_connection(url, timeout=None, class_=WebSocket, header=None, **options):
    ws = class_(timeout=timeout)
    ws.connect(url, header=header, **options)
    return ws


class WebSocketApp:
    """Callback-style client: run_forever() connects and calls on_message for every message until closed."""

    def __init__(self, url, header=None, on_open=None, on_message=None, on_error=None, on_close=None, on_ping=None,
                 on_pong=None, on_cont_message=None, keep_running=True, get_mask_key=None, cookie=None,
                 subprotocols=None, on_data=None):
        self.url = url
        self.header = dict(header or {}) if isinstance(header, dict) else header
        if cookie:
            self.header = dict(self.header or {}, Cookie=cookie)
        self.on_open = on_open
        self.on_message = on_message
        self.on_error = on_error
        self.on_close = on_close
        self.on_data = on_data
        self.keep_running = False
        self.sock = None

    def _callback(self, fn, *args):
        if fn is None:
            return
        try:
            fn(self, *args)
        except KeyboardInterrupt:
            raise
        except Exception as e:
            if fn is not self.on_error and self.on_error is not None:
                self.on_error(self, e)
            else:
                raise

    def run_forever(self, ping_interval=0, ping_timeout=None, reconnect=None, **options):
        """Returns False after a normal close, True if it stopped because of an error (like websocket-client)."""
        self.keep_running = True
        self.sock = WebSocket(timeout=None)
        try:
            self.sock.connect(self.url, header=self.header)
        except WebSocketException as e:
            self.keep_running = False
            self._callback(self.on_error, e)
            return True
        self._callback(self.on_open)
        failed = False
        try:
            while self.keep_running:
                self.sock.settimeout(0.5)
                try:
                    opcode, data = self.sock.recv_data()
                except WebSocketTimeoutException:
                    continue
                if self.on_data is not None:
                    self._callback(self.on_data, data, opcode, True)
                self._callback(self.on_message, data)
        except WebSocketConnectionClosedException:
            pass
        except WebSocketException as e:
            failed = True
            self._callback(self.on_error, e)
        finally:
            status, reason = self.sock.close_status, self.sock.close_reason
            self.sock.close()
            self.keep_running = False
            self._callback(self.on_close, status, reason)
        return failed

    def send(self, data, opcode=ABNF.OPCODE_TEXT):
        if self.sock is None or not self.sock.connected:
            raise WebSocketConnectionClosedException("Connection is already closed.")
        return self.sock.send(data, opcode)

    def close(self, **kwargs):
        self.keep_running = False
        if self.sock is not None:
            self.sock.close()


def enableTrace(traceable, handler=None, level="DEBUG"):
    pass
