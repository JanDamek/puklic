# Puklic self-hosted runner image

Container image for the K8s self-hosted GitHub Actions runners in the
`puklic-ci` namespace. Built on `ghcr.io/actions/actions-runner:2.323.0`
with JDK 21 Temurin, C++ toolchain, OpenSSL 3, and appimagetool 13.

Design: [`docs/03_infrastructure/architect-reports/2026-05-24-k8s-runner-design.md`](../../docs/03_infrastructure/architect-reports/2026-05-24-k8s-runner-design.md) §3.

## Build

```bash
./build.sh
```

The script:
1. Builds `linux/amd64` image with two tags:
   - `registry.damek-soft.eu/jandamek/puklic-runner:<YYYYMMDD>-<short-sha>` (pinned)
   - `registry.damek-soft.eu/jandamek/puklic-runner:latest` (floating, manual testing only)
2. Runs Trivy CVE scan for HIGH/CRITICAL (fails if found; warns if Trivy not installed)
3. Smoke-tests JDK 21, cmake, OpenSSL, appimagetool versions inside the image
4. Pushes both tags

Pattern follows `jervis/k8s/build_kb.sh` (host Docker + `docker push`).

## Tag scheme

| Tag | Purpose |
|---|---|
| `:<YYYYMMDD>-<short-sha>` | Pinned, immutable — referenced by `AutoscalingRunnerSet` in `arc-runner-set-values.yaml` |
| `:latest` | Floating — for manual `docker run` testing only. **Never** referenced by runners. |

After each successful build, update `k8s/runner/arc-runner-set-values.yaml`
`template.spec.containers[0].image` to the new date-sha tag and `helm upgrade`
the scale-set release.

## When to rebuild

- **Monthly** — JDK and OS security patches (cron via GH-hosted workflow planned, see design §8)
- **On Trivy HIGH/CRITICAL** — out-of-band when a new CVE is published
- **On JDK / appimagetool version bump** — update `Dockerfile` ARGs and rebuild
- **On actions-runner base image bump** — track `ghcr.io/actions/actions-runner` releases; bump `FROM` pin

## Verify

```bash
# Smoke test
docker run --rm --entrypoint /bin/bash \
  registry.damek-soft.eu/jandamek/puklic-runner:<tag> \
  -c "java -version && cmake --version && openssl version && appimagetool --version"

# Trivy scan (standalone)
trivy image --severity HIGH,CRITICAL \
  registry.damek-soft.eu/jandamek/puklic-runner:<tag>

# Size check (budget < 1.5 GiB)
docker images registry.damek-soft.eu/jandamek/puklic-runner --format '{{.Tag}}\t{{.Size}}'
```

## Notes

- Adoptium GPG fingerprint is pinned in the Dockerfile (`ADOPTIUM_KEY_FP` ARG).
  Verify against <https://adoptium.net/installation/linux/> if the build fails
  on fingerprint mismatch.
- appimagetool is SHA-256-pinned (`APPIMAGETOOL_SHA` ARG).
- Image runs as user `runner` (UID 1000); root only used during apt installs.
