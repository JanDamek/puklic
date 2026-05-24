# `k8s/runner/` — Puklic CI runner foundational manifests

Foundational K8s resources for the self-hosted GitHub Actions runner namespace
`puklic-ci`. This directory implements **slice K8s-CI-2a** of the runner
rollout. See the design doc:

- `docs/03_infrastructure/architect-reports/2026-05-24-k8s-runner-design.md`

Subsequent slices (K8s-CI-2b GitHub App Secret, K8s-CI-3 controller Helm
install, K8s-CI-4 scale-set Helm install) are NOT part of this directory's
`kustomization.yaml` — they are applied separately.

## Contents

| File | Purpose |
|---|---|
| `namespace.yaml` | `puklic-ci` namespace |
| `priorityclass.yaml` | `puklic-ci-low` (value=-100) — Jervis preempts CI on pressure |
| `rbac.yaml` | `puklic-runner` ServiceAccount + Role (pods get/list) + RoleBinding |
| `networkpolicy.yaml` | Default-deny ingress + egress allowlist (DNS, HTTPS, own registry) |
| `kustomization.yaml` | Bundles the four manifests above for `kubectl apply -k` |
| `secret-github-app.yaml.tmpl` | Template for GitHub App credentials (NOT applied by kustomize) |
| `secret-registry-pull.yaml.tmpl` | Template for registry pull credentials (NOT applied by kustomize) |

Secrets are intentionally **excluded from `kustomization.yaml`** so that real
credentials are never tracked in git. The realized files
`secret-github-app.yaml` and `secret-registry-pull.yaml` are listed in the
repo `.gitignore`.

## Open questions to verify before apply

1. **Cluster pod CIDR** — `networkpolicy.yaml` uses `10.0.0.0/8` as the
   default RFC1918 `except` range to block lateral movement to in-cluster
   services. Verify your cluster:

   ```bash
   kubectl get nodes -o jsonpath='{.items[0].spec.podCIDR}'
   ```

   Adjust the `except:` list in `networkpolicy.yaml` if your pod CIDR
   differs.

2. **Registry CIDR** — `networkpolicy.yaml` contains the placeholder
   `<REGISTRY_CIDR>` for the allow-egress block targeting
   `registry.damek-soft.eu`. Resolve and pin before apply:

   ```bash
   dig +short registry.damek-soft.eu
   # -> e.g. 1.2.3.4   ->  use "1.2.3.4/32"
   ```

   Replace `<REGISTRY_CIDR>` in `networkpolicy.yaml` with the result.

## How to apply

```bash
# Verify the bundle renders without error:
kubectl kustomize k8s/runner/

# Apply:
kubectl apply -k k8s/runner/

# Verify resources exist:
kubectl get ns puklic-ci
kubectl get priorityclass puklic-ci-low
kubectl get sa,role,rolebinding,networkpolicy -n puklic-ci
```

Expected (slice K8s-CI-2a verification, per design §10):

```
NAME                     STATUS   AGE
namespace/puklic-ci      Active   …
priorityclass/puklic-ci-low …
serviceaccount/puklic-runner …
role.rbac.authorization.k8s.io/puklic-runner …
rolebinding.rbac.authorization.k8s.io/puklic-runner …
networkpolicy.networking.k8s.io/puklic-runner-egress …
```

## GitHub App Secret (slice K8s-CI-2b — separate)

Per design §5: create GitHub App, install on `JanDamek/puklic`, download
private key `.pem`, then:

```bash
kubectl create secret generic puklic-runner-github-app \
  -n puklic-ci \
  --from-literal=github_app_id=<APP_ID> \
  --from-literal=github_app_installation_id=<INSTALL_ID> \
  --from-file=github_app_private_key=<PATH-TO-PEM>
```

## Registry pull Secret (slice K8s-CI-2b — separate)

```bash
kubectl create secret docker-registry puklic-registry-pull \
  -n puklic-ci \
  --docker-server=registry.damek-soft.eu \
  --docker-username=<USER> \
  --docker-password=<PASS>
```

## How to revert

```bash
# Deletes namespace + all kustomize-managed resources.
# NOTE: deleting the namespace also removes any Secrets that were created in
# it (puklic-runner-github-app, puklic-registry-pull) and any Helm releases
# (puklic-arc-ctrl, puklic-runner-amd64). Uninstall those first if you want
# them preserved.
kubectl delete -k k8s/runner/

# PriorityClass is cluster-scoped — `kubectl delete -k` should remove it
# but verify:
kubectl get priorityclass puklic-ci-low
```

## References

- Design (Step 2 + Step 3 critic): `docs/03_infrastructure/architect-reports/2026-05-24-k8s-runner-design.md`
- Slice index: design §9 (K8s-CI-1 … K8s-CI-8)
- Verification matrix: design §10
