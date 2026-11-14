# SPDX-FileCopyrightText: 2026 diff
"""A small subset of the `requests` library for ComputerCraft.

Requests follow the server's CC: Tweaked HTTP rules (the same allow/deny list as Lua's http API; local and private
addresses are blocked by default) unless the server overrides them in the kik-cc-python config.
"""

import json as _json
import threading as _threading
from urllib.parse import urlencode as _urlencode, urlsplit as _urlsplit, urlunsplit as _urlunsplit

import _kik

__all__ = ["request", "get", "post", "put", "patch", "delete", "head", "options", "Session", "session", "Response",
           "RequestException", "ConnectionError", "HTTPError", "Timeout", "TooManyRedirects", "get_async", "post_async"]


class RequestException(IOError):
    def __init__(self, *args, response=None, request=None):
        super().__init__(*args)
        self.response = response
        self.request = request


class ConnectionError(RequestException):
    pass


class HTTPError(RequestException):
    pass


class Timeout(RequestException):
    pass


class ConnectTimeout(ConnectionError, Timeout):
    pass


class ReadTimeout(Timeout):
    pass


class TooManyRedirects(RequestException):
    pass


class InvalidURL(RequestException, ValueError):
    pass


class JSONDecodeError(RequestException, ValueError):
    pass


class exceptions:  # requests.exceptions.X
    RequestException = RequestException
    ConnectionError = ConnectionError
    HTTPError = HTTPError
    Timeout = Timeout
    ConnectTimeout = ConnectTimeout
    ReadTimeout = ReadTimeout
    TooManyRedirects = TooManyRedirects
    InvalidURL = InvalidURL
    JSONDecodeError = JSONDecodeError


class CaseInsensitiveDict(dict):
    """Header dict: lookups ignore case, the last spelling set is kept."""

    def __init__(self, data=None):
        super().__init__()
        self._keys = {}
        for k, v in (data or {}).items():
            self[k] = v

    def __setitem__(self, key, value):
        low = key.lower()
        if low in self._keys:
            super().__delitem__(self._keys[low])
        self._keys[low] = key
        super().__setitem__(key, value)

    def __getitem__(self, key):
        return super().__getitem__(self._keys[key.lower()])

    def __delitem__(self, key):
        super().__delitem__(self._keys.pop(key.lower()))

    def __contains__(self, key):
        return isinstance(key, str) and key.lower() in self._keys

    def get(self, key, default=None):
        return self[key] if key in self else default

    def setdefault(self, key, default=None):
        if key not in self:
            self[key] = default
        return self[key]

    def update(self, other=(), **kw):
        for k, v in dict(other, **kw).items():
            self[k] = v


def _charset(content_type):
    for part in (content_type or "").split(";")[1:]:
        k, _, v = part.strip().partition("=")
        if k.lower() == "charset":
            return v.strip("\"' ") or None
    return None


class Response:
    def __init__(self, status, reason, url, headers, content):
        self.status_code = status
        self.reason = reason
        self.url = url
        self.headers = headers
        self.content = content
        self.encoding = _charset(headers.get("content-type"))
        self.history = []

    @property
    def ok(self):
        return self.status_code < 400

    @property
    def apparent_encoding(self):
        try:
            self.content.decode("utf-8")
            return "utf-8"
        except UnicodeDecodeError:
            return "cp1251"

    @property
    def text(self):
        try:
            return self.content.decode(self.encoding or self.apparent_encoding, errors="replace")
        except LookupError:
            return self.content.decode("utf-8", errors="replace")

    def json(self, **kwargs):
        try:
            return _json.loads(self.text, **kwargs)
        except ValueError as e:
            raise JSONDecodeError(str(e), response=self) from None

    def iter_content(self, chunk_size=1, decode_unicode=False):
        data = self.text if decode_unicode else self.content
        size = chunk_size or len(data) or 1
        for i in range(0, len(data), size):
            yield data[i:i + size]

    def iter_lines(self, chunk_size=512, decode_unicode=False, delimiter=None):
        data = self.text if decode_unicode else self.content
        yield from (data.split(delimiter) if delimiter else data.splitlines())

    def raise_for_status(self):
        kind = "Client" if 400 <= self.status_code < 500 else "Server" if self.status_code >= 500 else None
        if kind:
            raise HTTPError("%d %s Error: %s for url: %s" % (self.status_code, kind, self.reason, self.url),
                            response=self)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        pass

    def __bool__(self):
        return self.ok

    def __repr__(self):
        return "<Response [%d]>" % self.status_code


def _add_params(url, params):
    if not params:
        return url
    query = params if isinstance(params, (str, bytes)) else _urlencode(params, doseq=True)
    if isinstance(query, bytes):
        query = query.decode("latin-1")
    parts = _urlsplit(url)
    q = parts.query + ("&" if parts.query else "") + query
    return _urlunsplit((parts.scheme, parts.netloc, parts.path, q, parts.fragment))


def _body(data, json, files, headers):
    if files:
        raise NotImplementedError("file uploads are not supported")
    if json is not None:
        headers.setdefault("Content-Type", "application/json")
        return _json.dumps(json).encode("utf-8")
    if data is None:
        return None
    if isinstance(data, dict) or (isinstance(data, (list, tuple)) and data and isinstance(data[0], tuple)):
        headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
        return _urlencode(data, doseq=True).encode("utf-8")
    if isinstance(data, str):
        return data.encode("utf-8")
    if hasattr(data, "read"):
        data = data.read()
        return data.encode("utf-8") if isinstance(data, str) else bytes(data)
    return bytes(data)


def _raise(msg):
    if msg == "Timed out":
        raise Timeout(msg)
    if msg == "Too many redirects":
        raise TooManyRedirects(msg)
    if msg == "URL malformed" or msg.startswith("Invalid protocol"):
        raise InvalidURL(msg)
    raise ConnectionError(msg)


def request(method, url, params=None, data=None, headers=None, cookies=None, files=None, auth=None, timeout=None,
            allow_redirects=True, proxies=None, hooks=None, stream=None, verify=None, cert=None, json=None):
    if not isinstance(url, str) or "://" not in url:
        raise InvalidURL("Invalid URL %r: No scheme supplied" % (url,))
    method = method.upper()
    url = _add_params(url, params)
    hdrs = CaseInsensitiveDict(headers)
    if auth is not None:
        import base64
        user, password = auth
        token = base64.b64encode(("%s:%s" % (user, password)).encode("utf-8")).decode("ascii")
        hdrs["Authorization"] = "Basic " + token
    if cookies:
        hdrs["Cookie"] = "; ".join("%s=%s" % kv for kv in dict(cookies).items())
    body = _body(data, json, files, hdrs)
    if isinstance(timeout, tuple):
        timeout = sum(t for t in timeout if t)
    flat = []
    for k, v in hdrs.items():
        flat += ["s", str(k), "s", str(v)]
    r = list(_kik._native.http_request(method, url, flat, None if body is None else body.decode("latin-1"),
                                       float(timeout or 0), bool(allow_redirects)))
    if r[0] != "ok":
        _raise(r[1])
    _, status, reason, final_url, raw_headers, content = r
    raw_headers = list(raw_headers)
    resp_headers = CaseInsensitiveDict()
    for i in range(0, len(raw_headers) - 1, 2):
        k, v = raw_headers[i], raw_headers[i + 1]
        resp_headers[k] = resp_headers[k] + ", " + v if k in resp_headers else v
    return Response(int(status), reason, final_url, resp_headers, content.encode("latin-1"))


def get(url, params=None, **kwargs):
    return request("GET", url, params=params, **kwargs)


def options(url, **kwargs):
    return request("OPTIONS", url, **kwargs)


def head(url, **kwargs):
    kwargs.setdefault("allow_redirects", False)
    return request("HEAD", url, **kwargs)


def post(url, data=None, json=None, **kwargs):
    return request("POST", url, data=data, json=json, **kwargs)


def put(url, data=None, **kwargs):
    return request("PUT", url, data=data, **kwargs)


def patch(url, data=None, **kwargs):
    return request("PATCH", url, data=data, **kwargs)


def delete(url, **kwargs):
    return request("DELETE", url, **kwargs)


class Session:
    def __init__(self):
        self.headers = CaseInsensitiveDict()
        self.cookies = {}
        self.params = {}
        self.auth = None

    def request(self, method, url, **kwargs):
        headers = CaseInsensitiveDict(self.headers)
        headers.update(kwargs.pop("headers", None) or {})
        cookies = dict(self.cookies)
        cookies.update(kwargs.pop("cookies", None) or {})
        p = kwargs.pop("params", None)
        params = dict(self.params)
        if isinstance(p, dict):
            params.update(p)
        elif p:
            params = p
        kwargs.setdefault("auth", self.auth)
        resp = request(method, url, headers=dict(headers), cookies=cookies, params=params, **kwargs)
        set_cookie = resp.headers.get("set-cookie")
        if set_cookie:
            for item in set_cookie.split(","):
                name, _, rest = item.strip().partition("=")
                if name and rest and " " not in name:
                    self.cookies[name] = rest.split(";", 1)[0]
        return resp

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, data=None, json=None, **kwargs):
        return self.request("POST", url, data=data, json=json, **kwargs)

    def put(self, url, data=None, **kwargs):
        return self.request("PUT", url, data=data, **kwargs)

    def patch(self, url, data=None, **kwargs):
        return self.request("PATCH", url, data=data, **kwargs)

    def delete(self, url, **kwargs):
        return self.request("DELETE", url, **kwargs)

    def head(self, url, **kwargs):
        kwargs.setdefault("allow_redirects", False)
        return self.request("HEAD", url, **kwargs)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def session():
    return Session()


class FutureResponse:
    """Result of get_async/post_async: the request runs on a background thread."""

    def __init__(self, fn, args, kwargs):
        self._result = None
        self._error = None
        self._thread = _threading.Thread(target=self._run, args=(fn, args, kwargs), daemon=True)
        self._thread.start()

    def _run(self, fn, args, kwargs):
        try:
            self._result = fn(*args, **kwargs)
        except BaseException as e:
            self._error = e

    def is_done(self):
        return not self._thread.is_alive()

    def result(self, timeout=None):
        self._thread.join(timeout)
        if self._thread.is_alive():
            raise Timeout("request still running")
        if self._error is not None:
            raise self._error
        return self._result


def get_async(url, **kwargs):
    return FutureResponse(get, (url,), kwargs)


def post_async(url, data=None, json=None, **kwargs):
    return FutureResponse(post, (url,), dict(kwargs, data=data, json=json))


class codes:
    ok = 200
    created = 201
    no_content = 204
    moved = 301
    found = 302
    not_modified = 304
    bad_request = 400
    unauthorized = 401
    forbidden = 403
    not_found = 404
    too_many_requests = 429
    server_error = internal_server_error = 500
