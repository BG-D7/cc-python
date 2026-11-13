# SPDX-FileCopyrightText: 2026 diff
# Requests API implementation backed by Java HttpClient

import java
import json as _json

JavaHttpClient = java.type("work.diffdev.kik.python.http.JavaHttpClient")
HashMap = java.type("java.util.HashMap")

class HTTPError(Exception):
    pass

class Response:
    def __init__(self, java_resp, url=""):
        self._raw = java_resp
        self.url = url
        self.status_code = int(java_resp.status_code)
        self.text = str(java_resp.getBody() or "")
        self.headers = {}
        j_map = java_resp.headers
        if j_map:
            for k in j_map.keySet():
                val_list = j_map.get(k)
                if val_list and not val_list.isEmpty():
                    self.headers[str(k).lower()] = str(val_list.get(0))
        self._json_cache = None

    @property
    def ok(self):
        return 200 <= self.status_code < 400

    @property
    def content(self):
        return self.text.encode("utf-8")

    def json(self):
        if self._json_cache is None:
            self._json_cache = _json.loads(self.text)
        return self._json_cache

    def raise_for_status(self):
        if 400 <= self.status_code < 500:
            raise HTTPError(f"{self.status_code} Client Error: for url: {self.url}")
        elif 500 <= self.status_code < 600:
            raise HTTPError(f"{self.status_code} Server Error: for url: {self.url}")

    def __repr__(self):
        return f"<Response [{self.status_code}]>"

    def __bool__(self):
        return self.ok

class FutureResponse:
    def __init__(self, future, url=""):
        self._future = future
        self._url = url

    def is_done(self):
        return self._future.isDone()

    def result(self):
        return Response(self._future.get(), self._url)

class Session:
    def __init__(self):
        self.headers = {}

    def request(self, method, url, **kwargs):
        headers = self.headers.copy()
        if "headers" in kwargs and kwargs["headers"]:
            headers.update(kwargs["headers"])
        kwargs["headers"] = headers
        return request(method, url, **kwargs)

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, **kwargs):
        return self.request("POST", url, **kwargs)

    def put(self, url, **kwargs):
        return self.request("PUT", url, **kwargs)

    def delete(self, url, **kwargs):
        return self.request("DELETE", url, **kwargs)

    def close(self):
        pass

def session():
    return Session()


def _to_java_map(headers):
    j_map = HashMap()
    if not headers:
        return j_map
    for key, value in headers.items():
        j_map.put(str(key), str(value))
    return j_map

def request(method, url, params=None, data=None, json=None, headers=None, timeout=30):
    if params:
        query = "&".join(f"{k}={v}" for k, v in params.items())
        url = f"{url}?{query}" if "?" not in url else f"{url}&{query}"

    h = _to_java_map(headers)
    body_str = None
    if json is not None:
        body_str = _json.dumps(json)
        h.put("Content-Type", "application/json")
    elif data is not None:
        if isinstance(data, dict):
            body_str = "&".join(f"{k}={v}" for k, v in data.items())
            h.put("Content-Type", "application/x-www-form-urlencoded")
        else:
            body_str = str(data)

    resp = JavaHttpClient.request(method, url, body_str, h, int(timeout))
    return Response(resp, url)

def get(url, params=None, **kwargs):
    return request("GET", url, params=params, **kwargs)

def post(url, data=None, json=None, **kwargs):
    return request("POST", url, data=data, json=json, **kwargs)

def put(url, data=None, json=None, **kwargs):
    return request("PUT", url, data=data, json=json, **kwargs)

def patch(url, data=None, json=None, **kwargs):
    return request("PATCH", url, data=data, json=json, **kwargs)

def delete(url, **kwargs):
    return request("DELETE", url, **kwargs)

def head(url, **kwargs):
    return request("HEAD", url, **kwargs)

def get_async(url, headers=None, **kwargs):
    h = _to_java_map(headers)
    return FutureResponse(JavaHttpClient.getAsync(url, h), url)

def post_async(url, data=None, json=None, headers=None, **kwargs):
    h = _to_java_map(headers)
    body_str = _json.dumps(json) if json else (str(data) if data else None)
    return FutureResponse(JavaHttpClient.postAsync(url, body_str, h), url)
