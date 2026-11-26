#!/usr/bin/env python3
"""Measures the Puklic Linux desktop app against the Phase 5 performance targets.

Per run a fresh mock Discord server (mock_discord.py), a fresh HOME/XDG tree and a pre-seeded
token are used, so every sample is a true cold start with an empty cache and database.

Scenarios:
  idle    1 guild, logged in, window shown, RSS sampled --idle-seconds after the first frame
  active  10 guilds; the app's built-in auto-test loads the first 5 text channels of every guild
          (50 messages each) into the message cache; RSS sampled --settle-seconds after it ends
  start   cold start = harness spawn -> `first-frame` milestone (and -> `session-connected`)

Results are written as JSON (all samples and medians) and printed as a table.
"""
import argparse
import json
import os
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
MILESTONE = re.compile(r"milestone=(\S+) jvmUptimeMs=(\d+) epochMs=(\d+)")
AUTOTEST_DONE = "auto-test: COMPLETE"
STARTUP_TIMEOUT_S = 120
ACTIVE_TIMEOUT_S = 300


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def read_status(pid):
    fields = {}
    for line in Path(f"/proc/{pid}/status").read_text().splitlines():
        key, _, value = line.partition(":")
        if key in ("VmRSS", "VmHWM", "RssAnon", "RssFile", "RssShmem"):
            fields[key] = int(value.split()[0]) / 1024
    return fields


def bundled_java_options(app_dir):
    cfg = Path(app_dir, "lib/app/Puklic.cfg")
    if not cfg.exists():
        return []
    return [line.split("=", 1)[1] for line in cfg.read_text().splitlines() if line.startswith("java-options=")]


class AppRun:
    def __init__(self, args, scenario, workdir):
        self.args, self.scenario, self.workdir = args, scenario, workdir
        self.milestones, self.lines, self.autotest_done = {}, [], threading.Event()
        self.guilds = 1 if scenario == "idle" else 10

    def start_mock(self):
        self.port = free_port()
        self.mock = subprocess.Popen(
            [sys.executable, "-I", str(HERE / "mock_discord.py"), "--port", str(self.port),
             "--guilds", str(self.guilds), "--gif-every", str(self.args.gif_every)], stdout=subprocess.PIPE, text=True)
        self.mock.stdout.readline()

    def environment(self):
        home = self.workdir / "home"
        secrets = self.workdir / "secrets"
        secrets.mkdir(parents=True)
        (secrets / "discord.token").write_text("perf-token")
        env = dict(os.environ, HOME=str(home), XDG_DATA_HOME=str(home / "data"),
                   XDG_CACHE_HOME=str(home / "cache"), XDG_CONFIG_HOME=str(home / "config"),
                   PERF_SECRET_DIR=str(secrets), PATH=f"{HERE / 'bin'}:{os.environ['PATH']}",
                   JAVA_TOOL_OPTIONS=self.jvm_options())
        env.setdefault("SKIKO_RENDER_API", "SOFTWARE_FAST")
        return env

    def jvm_options(self):
        flags = self.args.jvm_flags.split() if self.args.jvm_flags is not None else []
        props = [f"-Dpuklic.discord.apiBase=http://127.0.0.1:{self.port}/api/v10",
                 f"-Dpuklic.discord.gatewayUrl=ws://127.0.0.1:{self.port}/?v=10&encoding=json",
                 "-Dpuklic.update.autoCheck=false"]
        if self.scenario == "active":
            props.append("-Dpuklic.dev.autotest=true")
        return " ".join(flags + props)

    def command(self):
        return [str(Path(self.args.app_dir) / "bin/Puklic")]

    def pump_output(self):
        for line in self.process.stdout:
            self.lines.append(line)
            found = MILESTONE.search(line)
            if found:
                self.milestones.setdefault(found.group(1), int(found.group(3)))
            if AUTOTEST_DONE in line:
                self.autotest_done.set()

    def wait_for(self, predicate, timeout, what):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return
            if self.process.poll() is not None:
                raise RuntimeError(f"app exited early ({what}); tail:\n{''.join(self.lines[-30:])}")
            time.sleep(0.1)
        raise RuntimeError(f"timeout waiting for {what}; tail:\n{''.join(self.lines[-30:])}")

    def run(self):
        self.start_mock()
        try:
            spawn_ms = int(time.time() * 1000)
            self.process = subprocess.Popen(self.command(), env=self.environment(), stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, text=True)
            threading.Thread(target=self.pump_output, daemon=True).start()
            self.wait_for(lambda: {"first-frame", "session-connected"} <= self.milestones.keys(),
                          STARTUP_TIMEOUT_S, "first-frame + session-connected")
            return self.sample(spawn_ms)
        finally:
            if getattr(self, "process", None):
                self.process.terminate()
                self.process.wait(timeout=30)
            self.mock.terminate()

    def sample(self, spawn_ms):
        if self.scenario == "active":
            self.wait_for(self.autotest_done.is_set, ACTIVE_TIMEOUT_S, "auto-test completion")
            time.sleep(self.args.settle_seconds)
        else:
            time.sleep(self.args.idle_seconds)
        result = read_status(self.process.pid)
        result["firstFrameMs"] = self.milestones["first-frame"] - spawn_ms
        result["sessionConnectedMs"] = self.milestones["session-connected"] - spawn_ms
        return result


def summarise(samples):
    return {key: round(statistics.median(s[key] for s in samples), 1) for key in samples[0]}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--app-dir", required=True, type=lambda p: str(Path(p).resolve()), help="jpackage app image (.../app/Puklic)")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--idle-seconds", type=int, default=60)
    parser.add_argument("--settle-seconds", type=int, default=30)
    parser.add_argument("--jvm-flags", default=None,
                        help="extra JVM flags passed via JAVA_TOOL_OPTIONS (default: none, i.e. the shipped launcher config)")
    parser.add_argument("--label", default="baseline")
    parser.add_argument("--gif-every", type=int, default=0, help="attach an animated GIF to every Nth mock message")
    parser.add_argument("--scenarios", default="idle,active")
    parser.add_argument("--output", default=None)
    args = parser.parse_args()

    report = {"label": args.label, "host": {"cpus": os.cpu_count(), "kernel": os.uname().release},
              "skikoRenderApi": os.environ.get("SKIKO_RENDER_API", "SOFTWARE_FAST"), "shippedJvmOptions": bundled_java_options(args.app_dir),
              "extraJvmFlags": args.jvm_flags or "", "scenarios": {}}
    for scenario in args.scenarios.split(","):
        samples = []
        for index in range(args.runs):
            workdir = Path(tempfile.mkdtemp(prefix=f"puklic-perf-{scenario}-"))
            try:
                samples.append(AppRun(args, scenario, workdir).run())
            finally:
                shutil.rmtree(workdir, ignore_errors=True)
            print(f"[{args.label}] {scenario} run {index + 1}: {samples[-1]}", flush=True)
        report["scenarios"][scenario] = {"samples": samples, "median": summarise(samples)}
    text = json.dumps(report, indent=2)
    print(text)
    if args.output:
        Path(args.output).write_text(text + "\n")


if __name__ == "__main__":
    main()
