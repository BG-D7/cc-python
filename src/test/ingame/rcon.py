import socket, struct, sys, os
# TEST_SERVER = server directory (default: the 26.2 one); the port comes from its server.properties.
server = os.path.expanduser(os.environ.get("TEST_SERVER", "~/Games/kik-python-test"))
pw = open(os.path.join(server, "rcon.pass")).read().strip()
props = dict(l.split("=", 1) for l in open(os.path.join(server, "server.properties")) if "=" in l and not l.startswith("#"))
s = socket.create_connection(("127.0.0.1", int(props.get("rcon.port", "25575"))), timeout=10)
def pkt(i, t, body):
    d = struct.pack("<ii", i, t) + body.encode() + b"\0\0"
    s.sendall(struct.pack("<i", len(d)) + d)
    n = struct.unpack("<i", s.recv(4))[0]; buf = b""
    while len(buf) < n: buf += s.recv(n - len(buf))
    return buf[8:-2].decode(errors="replace")
pkt(1, 3, pw)
for c in sys.argv[1:]:
    print(">", c); print(pkt(2, 2, c.strip()))
