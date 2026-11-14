import socket, struct, sys, os
pw = open(os.path.expanduser("~/Games/kik-python-test/rcon.pass")).read().strip()
s = socket.create_connection(("127.0.0.1", 25602), timeout=10)
def pkt(i, t, body):
    d = struct.pack("<ii", i, t) + body.encode() + b"\0\0"
    s.sendall(struct.pack("<i", len(d)) + d)
    n = struct.unpack("<i", s.recv(4))[0]; buf = b""
    while len(buf) < n: buf += s.recv(n - len(buf))
    return buf[8:-2].decode(errors="replace")
pkt(1, 3, pw)
for c in sys.argv[1:]:
    print(">", c); print(pkt(2, 2, c))
