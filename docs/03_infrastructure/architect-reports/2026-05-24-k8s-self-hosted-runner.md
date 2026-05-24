# K8s Self-Hosted GitHub Actions Runner — Step 1 Analysis (2026-05-24)

## Summary

User CI mandate 2026-05-24: "jsem pro build u nas na k8s" — migrate Puklic CI to self-hosted runner on existing K8s cluster (`registry.damek-soft.eu`, Jervis infrastructure).

Library-first survey identifies **`actions-runner-controller` (ARC)** — GitHub's official Kubernetes operator — as the right reuse target. No custom orchestration.

Step 2 (design) blocked on 4 user-input gaps (cluster arch, capacity, namespace, GitHub App admin).

## A. Existing K8s infrastructure

- Cluster: operational, owned by user, hosts Jervis services
- Registry: `registry.damek-soft.eu/jandamek` (private Docker registry)
- Namespace convention: `jervis` for services
- PVC pattern: RWX, e.g. `jervis-data-pvc` 10 GiB
- DNS: `*.lan.mazlusek.com` via UniFi

**No existing GitHub Actions runner deployed** on the cluster — net-new for Puklic.

**GAP A.1:** Cluster node CPU architecture undocumented. User verify:
```bash
kubectl get nodes -o jsonpath='{.items[*].status.nodeInfo.architecture}'
```

## B. Library survey (per `library-first-before-custom` memory rule)

| Candidate | Source | Maintainer | License | Pros | Cons |
|---|---|---|---|---|---|
| **actions-runner-controller (ARC)** | github.com/actions/actions-runner-controller | GitHub (official) | Apache-2.0 | Production-ready, Helm chart, OIDC token rotation, auto-scaling via webhook | CRD + operator overhead (~50 MB RAM) |
| myowen34/docker-github-actions-runner | hub.docker.com | Community | MIT | Simple Docker image | Manual registration (PAT), no auto-scaling |
| everlasting-dev/github-actions-runner | github | Community | MIT | DinD support | Unmaintained since 2023 |
| Hand-rolled K8s Job | n/a | None | n/a | Full control | Custom operator burden — defeats library-first |

**Recommendation: ARC.** Production-hardened, biweekly releases, GitHub native auth via App + short-lived installation tokens. No custom code.

## C. Build requirements

Runner container needs (~1.2 GB image):
- JDK 21 (Temurin)
- Gradle 8.x (via wrapper, no install needed)
- C++ toolchain: gcc, g++, cmake, ninja, pkg-config
- OpenSSL 3 dev headers (`libssl-dev`)
- git, curl, zip, unzip
- fakeroot, binutils, dpkg-deb (for .deb packaging)
- appimagetool (for .AppImage)

Cache PVC for `~/.gradle` (5-10 GiB) — avoids re-downloading 1+ GB Maven deps per build.

Network outbound: api.github.com, repo1.maven.org, plugin.gradle.org, jitpack.io.

## D. Cost vs GitHub-hosted

| Factor | GitHub-hosted ubuntu-latest | Self-hosted K8s |
|---|---|---|
| Cost | Free for public repos | Sunk K8s electricity |
| Queue | Free tier 1 concurrent | Unlimited parallel |
| Build time | ~10 min (with cache) | ~5-7 min (fast network to Maven + persistent cache) |
| Ops burden | Zero | ARC operator + image maintenance |

**Trigger for switching**: queue congestion (current symptom — runs stuck pending 30+ min). Self-hosted resolves this.

## E. Authentication

**Recommended: GitHub App + OIDC** (ARC native support).
- One-time setup: create GitHub App in Puklic repo (Settings → Integrations)
- Scopes: `contents:read`, `actions:write`
- Store App ID + private key in K8s Secret `puklic-runner-secrets`
- ARC fetches short-lived installation tokens, auto-rotates every 1h

Fallback: PAT with `repo` scope (less secure, manual rotation).

## F. Network

Outbound only (runners poll GitHub). No inbound. Same as Jervis services use today.

## G. Multi-arch builds

| Cluster Arch | Phase 1 Linux-x86_64 | Phase 2 iOS | Notes |
|---|---|---|---|
| x86_64 only | ✅ Native | ⚠️ Stays on macos-latest | Recommended path |
| arm64 only | ⚠️ Cross-compile NOT FEASIBLE (libdave + libx264) | ⚠️ Stays on macos-latest | Use QEMU emulation (10× slower) OR keep GH-hosted for x86_64 |
| Mixed | ✅ Native (x86_64 node selector) | ⚠️ macos-latest | Best case |

**Verify cluster arch before Step 2.**

## H. Existing Jervis docs

No prior GitHub Actions runner setup in Jervis. Step 2 design is net-new.

## Gaps blocking Step 2 design

| ID | Question | Impact |
|---|---|---|
| G.1 | Cluster architecture (x86_64 / arm64 / mixed)? | Linux-x86_64 builds may not be possible on arm64-only |
| G.2 | Can cluster run 1-3 concurrent Gradle builds (2 cores, 4 GiB each)? | Resource sizing for ARC RunnerDeployment |
| A.1 | Namespace: `jervis` shared or `puklic-ci` isolated? | RBAC scope + secret naming |
| E.1 | Admin access to create GitHub App in Puklic repo? | Auth method choice |

## Recommended Step 2 deliverables (after gaps closed)

1. ARC Helm values for Puklic runner pool (replicas, image, resource limits)
2. Dockerfile for runner image (Ubuntu 22.04 base + JDK 21 + C++ toolchain + appimagetool)
3. K8s manifests: namespace, ServiceAccount, RBAC, PVC for cache, Secret for GitHub App
4. CI strategy: which workflows run on self-hosted vs GitHub-hosted (e.g. iOS stays GH)
5. Migration plan: enable self-hosted gradually, deprecate GH-hosted Linux when stable
