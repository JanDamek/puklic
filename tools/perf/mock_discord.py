#!/usr/bin/env python3
"""Minimal Discord simulator for the Puklic performance baseline.

Serves, on a single port and with the Python standard library only:
  * REST v10 under /api/v10 (self user, settings-proto, channel messages, ack/typing no-ops)
  * the gateway WebSocket (JSON encoding): HELLO -> IDENTIFY -> READY with N full guilds
    (channels, roles, self member embedded, as in user-mode READY), heartbeat ACKs.

Content is generated deterministically from --guilds / --channels / --messages so every run
presents the app with the same data.
"""
import argparse
import base64
import hashlib
import json
import re
import struct
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DISCORD_EPOCH_MS = 1420070400000
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
HEARTBEAT_INTERVAL_MS = 41250
TEXT_CHANNEL, VOICE_CHANNEL, CATEGORY_CHANNEL = 0, 2, 4
SELF_USER_ID = "900000000000000001"
BASE_TIME = datetime(2026, 11, 20, 8, 0, 0, tzinfo=timezone.utc)

SAMPLE_CONTENT = [
    "Morning everyone, did the deploy go through?",
    "**Heads up:** maintenance window starts at 22:00 UTC tonight.",
    "Check this out https://example.com/some/long/path?with=query&and=more",
    "```kotlin\nfun main() = println(\"hello\")\n```",
    "lol that is *exactly* what I was thinking :joy:",
    "<@900000000000000002> can you take a look at the failing job?",
    "> quoted line\nand a reply that spans\nseveral lines of text",
    "~~wrong~~ right, the answer is 42 and that is final",
    "ok",
    "Has anybody tried the new build on Wayland? Scaling looks off at 150% here.",
]


class World:
    def __init__(self, guilds, channels, messages):
        self.guild_count, self.channel_count, self.message_count = guilds, channels, messages
        self.users = [self.user(900000000000000001 + i) for i in range(40)]
        self.guilds = [self.build_guild(i) for i in range(guilds)]
        self.dms = [self.build_dm(i) for i in range(3)]
        self.channel_index = {}
        for guild in self.guilds:
            for ch in guild["channels"]:
                self.channel_index[ch["id"]] = ch
        for dm in self.dms:
            self.channel_index[dm["id"]] = dm

    @staticmethod
    def user(uid):
        return {"id": str(uid), "username": f"user{uid % 1000}", "global_name": f"User {uid % 1000}",
                "discriminator": "0", "avatar": None, "bot": False, "public_flags": 0}

    def build_guild(self, gi):
        gid = 100000000000000000 + gi * 1000
        channels = [{"id": str(gid + 1), "type": CATEGORY_CHANNEL, "name": "Text", "position": 0}]
        for ci in range(self.channel_count):
            channels.append({"id": str(gid + 10 + ci), "type": TEXT_CHANNEL, "name": f"channel-{ci}",
                             "position": ci + 1, "parent_id": str(gid + 1), "topic": "Topic of channel",
                             "last_message_id": str(self.message_id(gid + 10 + ci, self.message_count - 1))})
        for vi in range(3):
            channels.append({"id": str(gid + 500 + vi), "type": VOICE_CHANNEL, "name": f"voice-{vi}",
                             "position": 100 + vi, "bitrate": 64000, "user_limit": 0})
        roles = [{"id": str(gid), "name": "@everyone", "permissions": "1071698660929", "position": 0, "color": 0}]
        roles += [{"id": str(gid + 800 + r), "name": f"role-{r}", "permissions": "0", "position": r + 1,
                   "color": 3447003} for r in range(9)]
        return {"id": str(gid), "name": f"Guild {gi}", "icon": None, "owner_id": SELF_USER_ID, "features": [],
                "member_count": 250, "permissions": "1071698660929", "roles": roles, "channels": channels,
                "members": [{"user": self.users[0], "roles": []}]}

    def build_dm(self, di):
        peer = self.users[1 + di]
        return {"id": str(300000000000000000 + di), "type": 1, "recipient_ids": [peer["id"]],
                "last_message_id": str(self.message_id(300000000000000000 + di, self.message_count - 1))}

    @staticmethod
    def message_id(channel_id, index):
        ms = int((BASE_TIME + timedelta(minutes=index * 3)).timestamp() * 1000) - DISCORD_EPOCH_MS
        return (ms << 22) | (int(channel_id) % 1000 << 12) | (index & 0xFFF)

    def message(self, channel_id, index):
        author = self.users[(index * 7 + int(channel_id)) % len(self.users)]
        stamp = (BASE_TIME + timedelta(minutes=index * 3)).strftime("%Y-%m-%dT%H:%M:%S.000000+00:00")
        return {"id": str(self.message_id(channel_id, index)), "channel_id": str(channel_id), "author": author,
                "content": f"[{index}] {SAMPLE_CONTENT[index % len(SAMPLE_CONTENT)]}", "timestamp": stamp,
                "edited_timestamp": None, "tts": False, "pinned": False, "attachments": [], "embeds": [],
                "reactions": [], "mentions": [], "mention_roles": [], "mention_everyone": False,
                "flags": 0, "type": 0}

    def messages_page(self, channel_id, limit, before):
        newest_first = [self.message(channel_id, i) for i in range(self.message_count - 1, -1, -1)]
        if before:
            newest_first = [m for m in newest_first if int(m["id"]) < int(before)]
        return newest_first[:limit]

    def ready(self, ws_url):
        return {"v": 10, "session_id": "perf-session-1", "resume_gateway_url": ws_url,
                "user": self.users[0], "users": self.users, "guilds": self.guilds,
                "private_channels": self.dms}


WORLD = None
WS_URL = None
MESSAGES_RE = re.compile(r"^/api/v10/channels/(\d+)/messages$")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() == "websocket":
            return self.serve_gateway()
        url = urlparse(self.path)
        match = MESSAGES_RE.match(url.path)
        if url.path == "/api/v10/users/@me":
            return self.reply(200, WORLD.users[0])
        if url.path.startswith("/api/v10/users/@me/settings-proto"):
            return self.reply(200, {"settings": ""})
        if match and match.group(1) in WORLD.channel_index:
            query = parse_qs(url.query)
            limit = int(query.get("limit", ["50"])[0])
            before = query.get("before", [None])[0]
            return self.reply(200, WORLD.messages_page(match.group(1), limit, before))
        self.reply(404, {"message": "Unknown", "code": 0})

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.reply(200, {})

    do_PATCH = do_PUT = do_DELETE = do_POST

    def serve_gateway(self):
        accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] + WS_GUID).encode()).digest())
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept.decode())
        self.end_headers()
        self.close_connection = True
        send_text(self.wfile, {"op": 10, "d": {"heartbeat_interval": HEARTBEAT_INTERVAL_MS}})
        sequence = 0
        while True:
            frame = read_frame(self.rfile)
            if frame is None:
                return
            opcode, payload = frame
            if opcode == 8:
                return
            if opcode != 1:
                continue
            message = json.loads(payload)
            if message["op"] == 1:
                send_text(self.wfile, {"op": 11})
            elif message["op"] in (2, 6):
                sequence += 1
                send_text(self.wfile, {"op": 0, "t": "READY", "s": sequence, "d": WORLD.ready(WS_URL)})


def send_text(out, obj):
    data = json.dumps(obj).encode()
    header = bytearray([0x81])
    if len(data) < 126:
        header.append(len(data))
    elif len(data) < 65536:
        header += bytes([126]) + struct.pack(">H", len(data))
    else:
        header += bytes([127]) + struct.pack(">Q", len(data))
    out.write(bytes(header) + data)
    out.flush()


def read_exact(stream, count):
    buf = b""
    while len(buf) < count:
        chunk = stream.read(count - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def read_frame(stream):
    head = read_exact(stream, 2)
    if head is None:
        return None
    opcode, length = head[0] & 0x0F, head[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", read_exact(stream, 8))[0]
    mask = read_exact(stream, 4) if head[1] & 0x80 else None
    payload = read_exact(stream, length) if length else b""
    if payload is None:
        return None
    if mask:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return opcode, payload


def main():
    global WORLD, WS_URL
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--guilds", type=int, default=1)
    parser.add_argument("--channels", type=int, default=8, help="text channels per guild")
    parser.add_argument("--messages", type=int, default=120, help="messages per channel")
    args = parser.parse_args()
    WORLD = World(args.guilds, args.channels, args.messages)
    WS_URL = f"ws://127.0.0.1:{args.port}/?v=10&encoding=json"
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(f"mock discord on 127.0.0.1:{args.port} guilds={args.guilds}", flush=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Event().wait()


if __name__ == "__main__":
    main()
