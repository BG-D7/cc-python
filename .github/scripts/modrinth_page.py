"""Creates the Modrinth project or syncs its page (summary, docs/modrinth.md, links, icon, gallery) from the repo.

Run by .github/workflows/modrinth-page.yml with MODRINTH_TOKEN (scopes: create, read and write projects).
SUBMIT=true then sends the draft to Modrinth moderation; it needs at least one version uploaded.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://api.modrinth.com/v2"
SLUG = "cc-python"
REPO = "https://github.com/BG-D7/cc-python"
TOKEN = os.environ["MODRINTH_TOKEN"]

PAGE = {
    "title": "CC: Python",
    "description": "Python 3.13 (GraalPy) for CC: Tweaked computers and turtles: sandboxed, with requests, websocket "
                   "and sqlite3 that follow CC's rules. Self-contained, no other mods needed.",
    "body": open("docs/modrinth.md", encoding="utf-8").read(),
    "categories": ["technology", "utility"],
    "additional_categories": ["library"],
    "client_side": "optional",
    "server_side": "required",
    "license_id": "LGPL-3.0-only",
    "license_url": f"{REPO}/blob/main/LICENSE",
    "source_url": REPO,
    "issues_url": f"{REPO}/issues",
    "wiki_url": f"{REPO}#readme",
}

GALLERY = [
    ("1-repl.png", "REPL", "Variables live between lines, expressions are echoed, multi-line blocks"),
    ("2-script.png", "Scripts", "Arguments and colours through term and colors"),
    ("3-traceback.png", "Tracebacks", "Line numbers and carets, printed once"),
    ("4-ctrl-t.png", "Ctrl+T", "Stops even while True: pass"),
    ("5-pip.png", "pip install", "Pure-Python packages from PyPI onto the computer's disk"),
    ("6-sqlite-requests.png", "sqlite3 and requests", "Database on the computer's disk; HTTP under CC's rules"),
    ("7-lua-calls-python.png", "Lua calls Python", "python.eval returns Lua tables"),
    ("8-sandbox.png", "Sandbox", "Only the computer's disk, no Java"),
]


def call(method, path, data=None, content_type=None, ok_404=False):
    headers = {"Authorization": TOKEN, "User-Agent": "BG-D7/cc-python page sync"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            body = r.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        if ok_404 and e.code == 404:
            return None
        sys.exit(f"{method} {path}: HTTP {e.code} {e.read().decode(errors='replace')}")


def multipart(fields, files):
    b = uuid.uuid4().hex
    out = b""
    for name, value in fields.items():
        out += (f'--{b}\r\nContent-Disposition: form-data; name="{name}"\r\n'
                f'Content-Type: application/json\r\n\r\n{value}\r\n').encode()
    for name, (filename, ctype, content) in files.items():
        out += (f'--{b}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                f'Content-Type: {ctype}\r\n\r\n').encode() + content + b"\r\n"
    return out + f"--{b}--\r\n".encode(), f"multipart/form-data; boundary={b}"


icon = open("docs/images/icon.png", "rb").read()
project = call("GET", f"/project/{SLUG}", ok_404=True)
if project is None:
    data = dict(PAGE, slug=SLUG, project_type="mod", is_draft=True, initial_versions=[])
    body, ctype = multipart({"data": json.dumps(data)}, {"icon": ("icon.png", "image/png", icon)})
    project = call("POST", "/project", body, ctype)
    print(f"Created draft project {project['id']}")
else:
    call("PATCH", f"/project/{project['id']}", json.dumps(PAGE).encode(), "application/json")
    call("PATCH", f"/project/{project['id']}/icon?ext=png", icon, "image/png")
    print(f"Updated project {project['id']} ({project['status']})")

project = call("GET", f"/project/{project['id']}")
have = {g.get("title") for g in project.get("gallery", [])}
for order, (file, title, description) in enumerate(GALLERY):
    if title in have:
        continue
    q = urllib.parse.urlencode({"ext": "png", "featured": "true" if order == 0 else "false",
                                "title": title, "description": description, "ordering": order})
    call("POST", f"/project/{project['id']}/gallery?{q}", open(f"docs/images/{file}", "rb").read(), "image/png")
    print(f"Gallery: {title}")

if os.environ.get("SUBMIT") == "true":
    if project["status"] != "draft":
        print(f"Not a draft ({project['status']}), nothing to submit")
    elif not project.get("versions"):
        sys.exit("Upload a version first (Modrinth workflow), then submit")
    else:
        call("PATCH", f"/project/{project['id']}", json.dumps({"status": "processing"}).encode(), "application/json")
        print("Submitted for review")

print(f"https://modrinth.com/mod/{SLUG}")
