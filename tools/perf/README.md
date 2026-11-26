# tools/perf — Linux desktop performance baseline

Measures the Phase 5 targets (`docs/07_roadmap/phases.md`) without a real Discord account:

| Metric | Target |
|---|---|
| RSS idle (logged in, 1 guild) | < 150 MB |
| RSS active (10 guilds, 5 channels cached each) | < 300 MB |
| Cold start (spawn -> first frame) | < 2 s |

Results and methodology: `docs/06_ops/perf-baseline.md`.

## Parts

- `mock_discord.py` — Discord simulator (Python stdlib only): REST v10 + gateway WebSocket (JSON
  encoding) serving READY with N full guilds, channels, roles and generated messages.
- `bin/secret-tool` — file-backed stand-in for libsecret, so the app starts logged in (token
  `perf-token` pre-seeded) on a machine without a keyring.
- `measure.py` — launches the packaged app against the mock with a fresh HOME per run, reads
  `VmRSS` from `/proc/<pid>/status` and the `Startup` milestones from the app log, repeats, and
  prints/writes median results.
- `setup-xvfb.sh` — rootless Xvfb (+ X11/GL client libraries, `objcopy` for jlink) for hosts
  without a display, via `apt-get download` + `dpkg -x`.

The app is pointed at the mock with `-Dpuklic.discord.apiBase=...` and
`-Dpuklic.discord.gatewayUrl=...` (see `DiscordEndpointsConfig.kt`); `-Dpuklic.dev.autotest=true`
makes the app itself load the first 5 text channels of every guild (50 messages each).

## One command

```sh
tools/perf/run.sh                 # build the app image, start Xvfb if needed, measure, write JSON
PERF_LABEL=tuned PERF_JVM_FLAGS="-XX:+UseSerialGC" tools/perf/run.sh --runs 1   # extra args go to measure.py
```

Environment: `PERF_LABEL` (result file name), `PERF_JVM_FLAGS` (extra JVM flags via `JAVA_TOOL_OPTIONS`), `SKIKO_RENDER_API`
(default `SOFTWARE_FAST`, the CPU renderer, because virtual displays have no GPU).

Scenarios per run (default 3 runs each, median reported):

- `idle` — 1 guild; RSS sampled 60 s after first frame and session connected.
- `active` — 10 guilds; RSS sampled 30 s after the auto-test finished loading the channels.
- cold start = harness spawn timestamp -> `milestone=first-frame` log line (also reported:
  `sessionConnectedMs`, the time until READY has been processed).

`--gif-every N` makes the mock attach an animated GIF (`assets/anim.gif`, served from the mock) to every Nth message.

Caveats: virtual display and software rendering (a real GPU path moves memory into the driver),
loopback network (no TLS, no CDN: avatars/icons are `null` in the mock), warm OS page cache.
