# K8s Self-Hosted Runner — Design v2 (Step 2 + Step 3 critic, 2026-05-24)

> Revised after Step 3 critic. v1 → v2 changes:
> - **Switched legacy `RunnerDeployment` (summerwind.dev) → `gha-runner-scale-set` mode** (current GitHub-recommended, blocker #1)
> - **Fork PR builds stay on GH-hosted** (blocker #7 — self-hosted on untrusted fork code = RCE risk)
> - Autoscaling built-in via scale set (`minRunners: 0, maxRunners: 3`) — replaces static `replicas: 2` (major #8)
> - Per-runner RWO PVC (major #4 consistency fix; matches Gradle lock semantics)
> - PriorityClass + NetworkPolicy manifests included (majors #5, #6)
> - imagePullSecret declared (major #10)
> - Image base + final tag both pinned with date-sha (major #2, #16)
> - K8s-CI-2 split into 2a (manifests) + 2b (GitHub App) — atomic revert (major #17)
> - Helm install `--wait --timeout 5m` (minor #18)
> - Smoke test uses `--entrypoint /bin/bash` override (major #19)
> - Adoptium GPG fingerprint pinned, appimagetool pinned by SHA, Trivy scan added (minors #12, #14, #15)

## Summary

Install **`actions-runner-controller`** in `gha-runner-scale-set` mode (GitHub-recommended since ARC 0.27, Sep 2023) into isolated `puklic-ci` namespace. Scale set `puklic-runner-amd64` with **`minRunners: 0, maxRunners: 3`** auto-scales ephemeral runners on amd64 nodes. Image `registry.damek-soft.eu/jandamek/puklic-runner:<date>-<sha>` built on `ghcr.io/actions/actions-runner:<pinned>` base with JDK 21 + C++ toolchain + appimagetool. GitHub App OIDC auth. Per-runner RWO PVC via `volumeClaimTemplates` for Gradle cache. `NetworkPolicy` deny-egress to `jervis` + K8s API. `PriorityClass: puklic-ci-low` so Jervis evicts CI. **Fork PRs stay GH-hosted** (RCE prevention). 9 commitable slices (K8s-CI-2 split). Trivy scan in image build.

## 1. Goal + non-goals

**Goal:** ARC scale-set mode in `puklic-ci`. Ephemeral Linux-amd64 runners 0-3 auto-scale via workflow_job webhook (built into scale-set chart). Build `.deb`/`.AppImage` (Linux) + `libdave.so` on cluster.

**Non-goals:**
- macOS / iOS / Windows builds → GH-hosted
- ARC org-scoped runners (using repo-scoped for v1)
- Linux-arm64 runner (Phase 1 = amd64)
- Self-hosted on fork PRs (deliberate RCE prevention)
- Replacing `release` aggregation job

## 2. Architecture

```
                 GitHub.com
          ┌─────────────────────┐
          │ JanDamek/puklic     │
          │ workflow_job events │
          └──────────┬──────────┘
                     │ HTTPS (App installation token, ~1h TTL)
                     ▼
┌──────────────────────────────────────────────────────────┐
│ K8s cluster                                              │
│  ┌─ namespace: puklic-ci ─────────────────────────────┐  │
│  │ ┌────────────────────────────────┐                 │  │
│  │ │ gha-runner-scale-set-controller│                 │  │
│  │ │ (Helm release: puklic-arc-ctrl)│                 │  │
│  │ │ NetworkPolicy egress allowlist │                 │  │
│  │ └────────────┬───────────────────┘                 │  │
│  │              │ watches AutoscalingRunnerSet        │  │
│  │              ▼                                     │  │
│  │ ┌─────────────────────────────────┐                │  │
│  │ │ AutoscalingRunnerSet            │                │  │
│  │ │ name: puklic-runner-amd64       │                │  │
│  │ │ minRunners: 0, maxRunners: 3    │                │  │
│  │ │ nodeSelector: amd64             │                │  │
│  │ │ priorityClassName: puklic-ci-low│                │  │
│  │ └────────────┬────────────────────┘                │  │
│  │              │ creates EphemeralRunner pods        │  │
│  │              ▼                                     │  │
│  │  ┌─────────────────────────────┐                   │  │
│  │  │ EphemeralRunner pod (job N) │◄── per-runner    │  │
│  │  │ image: puklic-runner:date-sha│   RWO PVC        │  │
│  │  │ /home/runner/_work          │   (volumeClaimT) │  │
│  │  └─────────────────────────────┘                   │  │
│  │  ... (up to maxRunners=3 in parallel)              │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  jervis namespace ← isolated by NetworkPolicy           │
└──────────────────────────────────────────────────────────┘
```

**Job lifecycle (auto-scale):**
1. GitHub fires `workflow_job: queued` → controller webhook
2. Controller creates ephemeral pod, registers with GitHub, pod takes job
3. Job completes → pod terminates → next queued job (or scale down to minRunners)
4. PVC per-pod via `volumeClaimTemplates` (no shared lock issues)

## 3. Container image

**Path:** `k8s/runner-image/Dockerfile`
**Tag scheme:** `registry.damek-soft.eu/jandamek/puklic-runner:<YYYYMMDD>-<short-sha>` PLUS floating `:latest` for manual testing
**Pin:** AutoscalingRunnerSet always references specific date-sha tag, NOT `:latest`
**Size budget:** <1.5 GiB

**Base:** `ghcr.io/actions/actions-runner:2.323.0` (pinned to specific release; matches gha-runner-scale-set mode expectations).

```dockerfile
FROM ghcr.io/actions/actions-runner:2.323.0

USER root

# 1. JDK 21 Temurin with GPG fingerprint verification
ARG ADOPTIUM_KEY_FP="3B04D753C9050D9A5D343F39843C48A565F8F04B"
RUN apt-get update && apt-get install -y --no-install-recommends \
      wget gnupg ca-certificates \
 && wget -qO /tmp/adoptium.asc https://packages.adoptium.net/artifactory/api/gpg/key/public \
 && gpg --import-options show-only --import /tmp/adoptium.asc 2>&1 \
      | grep -q "$ADOPTIUM_KEY_FP" || (echo "Adoptium GPG fingerprint mismatch" && exit 1) \
 && gpg --dearmor < /tmp/adoptium.asc > /etc/apt/trusted.gpg.d/adoptium.gpg \
 && rm /tmp/adoptium.asc \
 && echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
      > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update && apt-get install -y --no-install-recommends temurin-21-jdk \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# 2. C++ toolchain + OpenSSL 3 + .deb packaging
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential cmake ninja-build pkg-config \
      libssl-dev \
      git curl zip unzip tar \
      fakeroot binutils dpkg-dev \
 && rm -rf /var/lib/apt/lists/*

# 3. appimagetool — pinned by SHA-256
ARG APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage"
# Verified upstream SHA-256 (design v2 had wrong value df3baf5...; corrected in commit 33c6683)
ARG APPIMAGETOOL_SHA="0019dfc4b32d63c1392aa264aed2253c1e0c2fb09216f8e2cc269bbfb8bb49b5"
RUN curl -sSL -o /usr/local/bin/appimagetool "${APPIMAGETOOL_URL}" \
 && echo "${APPIMAGETOOL_SHA}  /usr/local/bin/appimagetool" | sha256sum -c - \
 && chmod +x /usr/local/bin/appimagetool

USER runner
WORKDIR /home/runner
```

**Build + scan script** `k8s/runner-image/build.sh`:
```bash
#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY="registry.damek-soft.eu/jandamek"
DATE=$(date +%Y%m%d)
SHA=$(git rev-parse --short HEAD)
TAG="${REGISTRY}/puklic-runner:${DATE}-${SHA}"
LATEST="${REGISTRY}/puklic-runner:latest"

# Build
docker build --platform linux/amd64 -t "${TAG}" -t "${LATEST}" -f "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}"

# Scan for HIGH/CRITICAL CVEs (fail build if found)
if command -v trivy >/dev/null 2>&1; then
  trivy image --severity HIGH,CRITICAL --exit-code 1 "${TAG}" \
    || (echo "✗ Trivy found HIGH/CRITICAL vulnerabilities — review before pushing" && exit 1)
else
  echo "⚠ Trivy not installed; skipping CVE scan. Install via 'brew install trivy' or apt."
fi

# Smoke test
docker run --rm --entrypoint /bin/bash "${TAG}" -c \
  "java -version && cmake --version && openssl version && appimagetool --version" \
  || (echo "✗ Smoke test failed" && exit 1)

# Push both tags
docker push "${TAG}"
docker push "${LATEST}"
echo "✓ pushed ${TAG} + :latest"
echo "Use this tag in AutoscalingRunnerSet: ${TAG}"
```

(Uses host Docker per Jervis `build_kb.sh` convention. Verify `jervis/k8s/build_kb.sh` actually uses host Docker not kaniko before committing — if kaniko, mirror that.)

## 4. K8s manifests

All under `k8s/runner/`:

### a) `namespace.yaml`
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: puklic-ci
  labels:
    app.kubernetes.io/name: puklic-ci
    app.kubernetes.io/part-of: puklic
```

### b) `priorityclass.yaml`
```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata:
  name: puklic-ci-low
value: -100
preemptionPolicy: Never
globalDefault: false
description: "Puklic CI runners — evict before Jervis services on resource pressure"
```

### c) `secret-github-app.yaml.tmpl` (NOT committed with values; .gitignore the realized file)
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: puklic-runner-github-app
  namespace: puklic-ci
type: Opaque
stringData:
  github_app_id: "<APP_ID>"
  github_app_installation_id: "<INSTALL_ID>"
  github_app_private_key: |
    -----BEGIN RSA PRIVATE KEY-----
    <REPLACE>
    -----END RSA PRIVATE KEY-----
```
(Note: gha-runner-scale-set may auto-discover installation_id from app_id; keep it for backward compat.)

### d) `secret-registry-pull.yaml.tmpl` (NOT committed with values)
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: puklic-registry-pull
  namespace: puklic-ci
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: <base64-of-docker-config-with-registry-creds>
```
Create via:
```bash
kubectl create secret docker-registry puklic-registry-pull \
  -n puklic-ci \
  --docker-server=registry.damek-soft.eu \
  --docker-username=<USER> \
  --docker-password=<PASS>
```

### e) `arc-controller-values.yaml` (Helm values for controller chart)
```yaml
# gha-runner-scale-set-controller
resources:
  requests: { cpu: 50m, memory: 64Mi }
  limits:   { cpu: 200m, memory: 256Mi }
nodeSelector: {}  # controller can run any arch
```

### f) `arc-runner-set-values.yaml` (Helm values for scale-set chart)
```yaml
# gha-runner-scale-set release name: puklic-runner-amd64
githubConfigUrl: "https://github.com/JanDamek/puklic"
githubConfigSecret: puklic-runner-github-app

minRunners: 0
maxRunners: 3

template:
  spec:
    imagePullSecrets:
      - name: puklic-registry-pull
    priorityClassName: puklic-ci-low
    nodeSelector:
      kubernetes.io/arch: amd64
    securityContext:
      runAsNonRoot: true
      runAsUser: 1000
    containers:
      - name: runner
        image: registry.damek-soft.eu/jandamek/puklic-runner:<PINNED-DATE-SHA>
        imagePullPolicy: Always
        resources:
          requests: { cpu: "1",   memory: "2Gi" }
          limits:   { cpu: "2",   memory: "4Gi" }
        volumeMounts:
          - name: gradle-cache
            mountPath: /home/runner/.gradle
    serviceAccountName: puklic-runner

# Per-runner cache via volumeClaimTemplates (RWO, eliminates lock corruption)
volumeClaimTemplates:
  - metadata:
      name: gradle-cache
    spec:
      accessModes: [ReadWriteOnce]
      resources: { requests: { storage: 10Gi } }
```

### g) `rbac.yaml`
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: puklic-runner
  namespace: puklic-ci
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: puklic-runner
  namespace: puklic-ci
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: puklic-runner
  namespace: puklic-ci
subjects:
  - kind: ServiceAccount
    name: puklic-runner
    namespace: puklic-ci
roleRef:
  kind: Role
  name: puklic-runner
  apiGroup: rbac.authorization.k8s.io
```

### h) `networkpolicy.yaml`
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: puklic-runner-egress
  namespace: puklic-ci
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/component: runner
  policyTypes:
    - Egress
    - Ingress
  ingress: []  # default-deny all inbound
  egress:
    # DNS
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    # HTTPS to GitHub + Maven + Gradle + jitpack + own registry
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except:
              - 10.0.0.0/8       # cluster pod CIDR (typical)
              - 172.16.0.0/12
              - 192.168.0.0/16
      ports:
        - protocol: TCP
          port: 443
    # Allow egress to own registry (RFC1918 — exception via ipBlock allow)
    - to:
        - ipBlock:
            cidr: <REGISTRY_CIDR>  # e.g. specific IP of registry.damek-soft.eu
      ports:
        - protocol: TCP
          port: 443
```
(NB: tune CIDRs against actual cluster network. Default-deny ingress; egress allowlist HTTPS + DNS + own registry. Explicitly blocks lateral movement to `jervis` namespace.)

### i) `kustomization.yaml`
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: puklic-ci
resources:
  - namespace.yaml
  - priorityclass.yaml
  - rbac.yaml
  - networkpolicy.yaml
# Secrets + Helm-managed resources OMITTED
```

## 5. GitHub App setup

1. https://github.com/JanDamek/puklic → Settings → Integrations → GitHub Apps → New GitHub App
2. Name: "Puklic K8s Runner". Webhook: **uncheck Active** (scale-set chart uses its own listener).
3. **Repository permissions:**
   - Contents: **Read-only**
   - Actions: **Read & write**
   - Metadata: **Read-only** (auto)
   - Administration: **Read & write**
     - Note: repo-scoped runners have no sub-scope finer than this. Org-scoped runners could use `Self-hosted runners: Read & write` but we're using repo scope per v1.
   - **No webhook** (we don't expose inbound)
4. Generate private key, download `.pem`
5. Note **App ID** (top of settings page)
6. Install on `JanDamek/puklic`. Note **Installation ID** from URL `/settings/installations/<INSTALL_ID>`
7. Create K8s Secret:
   ```bash
   kubectl create secret generic puklic-runner-github-app \
     -n puklic-ci \
     --from-literal=github_app_id=<APP_ID> \
     --from-literal=github_app_installation_id=<INSTALL_ID> \
     --from-file=github_app_private_key=<PEM>
   ```

## 6. ARC install procedure

```bash
# Slice K8s-CI-2a: foundational manifests
kubectl apply -k k8s/runner/   # applies kustomization (no Secrets, no Helm)

# Slice K8s-CI-2b: user creates GitHub App + Secret per §5
# Also create imagePullSecret per §4d

# Slice K8s-CI-3: install controller (Helm chart 1)
helm repo add actions-runner-controller \
  https://actions-runner-controller.github.io/actions-runner-controller
helm repo update
helm install puklic-arc-ctrl \
  oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set-controller \
  --namespace puklic-ci \
  -f k8s/runner/arc-controller-values.yaml \
  --version <PINNED-VERSION> \
  --wait --timeout 5m

# Slice K8s-CI-4: install scale set (Helm chart 2)
helm install puklic-runner-amd64 \
  oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set \
  --namespace puklic-ci \
  -f k8s/runner/arc-runner-set-values.yaml \
  --version <PINNED-VERSION> \
  --wait --timeout 5m

# Verify
kubectl get pods -n puklic-ci
kubectl get autoscalingrunnerset -n puklic-ci
# Browse: https://github.com/JanDamek/puklic/settings/actions/runners
# Expect: 0 idle runners (scale set is at minRunners=0); pod appears on first job
```

## 7. Workflow migration

### `.github/workflows/build-installers.yml`

Linux matrix entry uses scale set name (gha-runner-scale-set mode):
```yaml
matrix:
  include:
    - os: puklic-runner-amd64   # scale set name, not labels
      target: linux-x86_64
      artifact-glob: |
        desktop/app/build/compose/binaries/main/deb/*.deb
        desktop/app/build/compose/binaries/main/appimage/*.AppImage
    - os: macos-14
      ...
runs-on: ${{ matrix.os }}
```

**Fork PR guard** added to Linux job:
```yaml
jobs:
  build-linux:
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    # ... rest of job
```
This skips self-hosted on fork PRs (RCE prevention). Fork PRs build on GH-hosted only — add separate fork-PR job with `runs-on: ubuntu-latest`.

Remove `Linux deb tooling` step (image has it).

### `.github/workflows/build-libdave.yml`

Same scale-set name for Linux matrix entry + same fork-PR guard.

### `release` job

Stays on `ubuntu-latest` (lightweight, no benefit moving).

## 8. Monitoring + maintenance

- Controller logs: `kubectl logs -n puklic-ci -l app.kubernetes.io/component=controller-manager`
- Scale set logs: `kubectl logs -n puklic-ci -l app.kubernetes.io/component=runner` (live + GitHub Actions UI)
- Token rotation: automatic via GitHub App + scale set
- Image rebuild: monthly via `rebuild-runner-image.yml` workflow on GH-hosted (bootstraps cluster image)
- Capacity: `kubectl top pods -n puklic-ci`; Kibana alert if scale-set pod restart loop
- Trivy scan in `build.sh` — fails on HIGH/CRITICAL CVE
- Cache hygiene: per-runner PVC ephemeral — auto-cleaned on pod delete

## 9. Migration phases (9 commitable slices, each independently revertible)

1. **K8s-CI-1** Build runner image, push to registry. `build.sh` runs Trivy + smoke test. Verify size <1.5 GiB.
2. **K8s-CI-2a** Apply foundational manifests via `kubectl apply -k k8s/runner/` (namespace, priorityclass, rbac, networkpolicy). Revert: `kubectl delete -k k8s/runner/`.
3. **K8s-CI-2b** User creates GitHub App per §5 + applies Secrets (`puklic-runner-github-app`, `puklic-registry-pull`). Revert: delete App + delete Secrets.
4. **K8s-CI-3** Helm install gha-runner-scale-set-controller. `--wait --timeout 5m` blocks until Ready. Verify controller pod Ready.
5. **K8s-CI-4** Helm install gha-runner-scale-set `puklic-runner-amd64`. Verify AutoscalingRunnerSet visible; **0 runners idle** (correct: minRunners=0).
6. **K8s-CI-5** Add `hello-world.yml` workflow on `runs-on: puklic-runner-amd64`. Dispatch manually. Verify pod spawns, job green, pod terminates.
7. **K8s-CI-6** Migrate Linux job in `build-installers.yml` to scale set + add fork-PR guard. Trigger via push from main repo. Verify .deb + .AppImage; compare wall-clock vs GH-hosted.
8. **K8s-CI-7** Migrate Linux job in `build-libdave.yml`. Verify libdave.so; `file libdave.so` shows ELF amd64.
9. **K8s-CI-8** Operational runbook `docs/06_ops/k8s-ci.md` (restart, image rebuild via cron, scale up/down, decommission).

## 10. Test plan (Step 5 verification matrix)

| Slice | Verification |
|---|---|
| K8s-CI-1 | `docker run --rm --entrypoint /bin/bash <tag> -c '...'` outputs JDK 21.x, cmake≥3.22, OpenSSL 3.x, appimagetool 13. Image <1.5 GiB. Trivy scan green. |
| K8s-CI-2a | `kubectl get ns,priorityclass,sa,role,rolebinding,networkpolicy -n puklic-ci` shows all resources Created. |
| K8s-CI-2b | `kubectl get secret puklic-runner-github-app puklic-registry-pull -n puklic-ci` shows both exist. |
| K8s-CI-3 | Controller pod Ready 1/1; logs free of auth/CRD errors after install. |
| K8s-CI-4 | `kubectl get autoscalingrunnerset puklic-runner-amd64 -n puklic-ci` Phase=Ready; 0 runners; GitHub repo runners page shows "Self-hosted runners" listing the scale set. |
| K8s-CI-5 | hello-world job creates EphemeralRunner pod, completes green, pod terminates within 30s. |
| K8s-CI-6 | build-installers.yml Linux job uses self-hosted (visible in run metadata: "Self-hosted: puklic-runner-amd64"); .deb + .AppImage uploaded; build time ≤ GH-hosted baseline. |
| K8s-CI-7 | build-libdave.yml Linux job produces valid libdave.so; `file libdave.so` shows `ELF 64-bit LSB shared object, x86-64`. |

## 11. Risks

1. **GitHub App permission misconfig** — symptom: 403 on runner register. Mitigation: §5 step 3 exact permission list + test with K8s-CI-4 + K8s-CI-5 hello-world before migrating real workflows.
2. **Runner image obsolescence** — JDK/OpenSSL CVEs. Mitigation: Trivy scan in `build.sh` fails on HIGH/CRITICAL; monthly cron rebuild via GH-hosted bootstrap workflow.
3. **Per-runner PVC quota** — 3 max concurrent × 10 GiB = 30 GiB. Mitigation: monitor PVC usage; expand StorageClass quota if needed; ephemeral runners delete PVC on termination via `volumeClaimRetentionPolicy: WhenDeleted` (verify scale-set chart supports this).
4. **Cluster eviction pressure on Jervis** — `PriorityClass: puklic-ci-low` ensures Jervis (default priority 0) preempts CI. Hard limits=4 GiB + node-level autoscaler if cluster grows.
5. **Runner agent / controller version skew** — scale-set chart pins agent image. Mitigation: pin Helm chart version + base image SHA together; upgrade in one window.

## 12. Open questions for Step 4 (user approval)

- **Cluster pod CIDR for NetworkPolicy egress except blocks** — verify with `kubectl get nodes -o jsonpath='{.items[0].spec.podCIDR}'`. Default in §4h assumes `10.0.0.0/8`. Adjust if different.
- **Registry CIDR for NetworkPolicy egress allow** — `registry.damek-soft.eu` IP. Verify with `dig`. Pin in §4h.
- **Adoptium GPG fingerprint freshness** — pinned to `3B04D753C9050D9A5D343F39843C48A565F8F04B`. Verify current at https://adoptium.net/installation/linux/.
- **`jervis/k8s/build_kb.sh` mechanism** — host Docker or kaniko? Determines `build.sh` pattern (§3).
- **Scale set chart version pin** — recommend latest stable from https://github.com/actions/actions-runner-controller/releases at time of slice K8s-CI-3. Note exact version in slice commit message.
