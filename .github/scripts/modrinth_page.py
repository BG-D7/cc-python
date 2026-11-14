"""Creates the Modrinth project or syncs its page (summary, docs/modrinth.md, links, disclosures) from the repo.

Run by .github/workflows/modrinth-page.yml with MODRINTH_TOKEN (scopes: create, read and write projects).
IMAGES=true also uploads docs/images (icon, gallery). They are drawn by a script, so Modrinth rule 6.2 (no images
derived from generative AI output) may apply: prefer real in-game screenshots uploaded by hand.
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
API3 = "https://api.modrinth.com/v3"
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

# Modrinth content disclosures (rules §6.1 and the project settings), kept honest and up to date here.
DISCLOSURES = [
    {"type": "ai_content", "uses": ["code", "text"],
     "note": "Most of the code, the documentation and this page were written with Claude (Anthropic) under the "
             "author's direction. Design, testing (unit and in-game) and review are by the author."},
    {"type": "system_interactions",
     "interactions": ["On first start, unpacks the bundled GraalPy runtime (about 25 MB) into ~/.ccpython-cache "
                      "in the user's home directory"],
     "note": "Python scripts on in-game computers are sandboxed to the computer's own disk by default."},
    {"type": "derivative_work", "sources": [
        {"label": "CC: Tweaked terminal font (CCPL)", "link": "https://github.com/cc-tweaked/CC-Tweaked",
         "note": "Base of the optional Cyrillic font resource pack"},
        {"label": "CCCyrillic by sashafiesta", "link": "https://modrinth.com/resourcepack/cccyrillic",
         "note": "Cyrillic glyphs of the optional font resource pack"},
    ]},
]

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


def call(method, path, data=None, content_type=None, ok_404=False, api=API):
    headers = {"Authorization": TOKEN, "User-Agent": "BG-D7/cc-python page sync"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(api + path, data=data, headers=headers, method=method)
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


images = os.environ.get("IMAGES") == "true"
icon = open("docs/images/icon.png", "rb").read()
project = call("GET", f"/project/{SLUG}", ok_404=True)
if project is None:
    data = dict(PAGE, slug=SLUG, project_type="mod", is_draft=True, initial_versions=[])
    body, ctype = multipart({"data": json.dumps(data)}, {"icon": ("icon.png", "image/png", icon)} if images else {})
    project = call("POST", "/project", body, ctype)
    print(f"Created draft project {project['id']}")
else:
    call("PATCH", f"/project/{project['id']}", json.dumps(PAGE).encode(), "application/json")
    if images:
        call("PATCH", f"/project/{project['id']}/icon?ext=png", icon, "image/png")
    print(f"Updated project {project['id']} ({project['status']})")

call("PATCH", f"/project/{project['id']}/disclosures", json.dumps({"set": DISCLOSURES, "remove": []}).encode(),
     "application/json", api=API3)
print("Disclosures: " + ", ".join(d["type"] for d in DISCLOSURES))

project = call("GET", f"/project/{project['id']}")
have = {g.get("title") for g in project.get("gallery", [])}
for order, (file, title, description) in enumerate(GALLERY):
    if not images or title in have:
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
